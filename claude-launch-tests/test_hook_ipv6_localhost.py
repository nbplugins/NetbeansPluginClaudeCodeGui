#!/usr/bin/env python3
"""Verify that using '127.0.0.1' in the hook URL fixes 502 errors when
'localhost' resolves to IPv6 (::1) but the hook server only listens on IPv4.

Root cause: Bun (the JS runtime in claude.exe) updated in 2.1.118 to strictly
follow getaddrinfo order — on systems where 'localhost' resolves to '::1' first,
hook HTTP requests connect to ::1:PORT, but the Java (Jetty) server listening on
0.0.0.0:PORT (IPv4 only) refuses the connection, and claude reports "HTTP 502".

The test runs two variants back-to-back:
  FAIL variant  – hook server binds IPv4-only, hook URL uses 'localhost'  → expects 502 in PTY
  PASS variant  – hook server binds IPv4-only, hook URL uses '127.0.0.1' → expects no 502

Exit codes:
    0 – both variants behaved as expected (FAIL variant got 502, PASS variant did not)
    1 – unexpected result
"""

import http.server
import json
import os
import pty
import select
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time

TIMEOUT = 60  # seconds per variant

ALLOW_JSON = json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "allow"
    }
}).encode()


# ─────────────────────────────────────────────────────────────────────────────
# Helpers
# ─────────────────────────────────────────────────────────────────────────────

def free_port_ipv4() -> int:
    """Allocate a free port on IPv4 only — like Jetty's ServerConnector."""
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.bind(("0.0.0.0", 0))
        return s.getsockname()[1]


class AllowHookHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(ALLOW_JSON)))
        self.end_headers()
        self.wfile.write(ALLOW_JSON)
        self.wfile.flush()


def start_ipv4_only_server(port: int):
    """Start an HTTP server bound ONLY to 0.0.0.0 (IPv4) — mirrors Jetty default."""
    server = http.server.ThreadingHTTPServer(("0.0.0.0", port), AllowHookHandler)
    t = threading.Thread(target=server.serve_forever, daemon=True)
    t.start()
    return server


def run_variant(hook_url: str, port: int, label: str) -> tuple[bool, str]:
    """
    Run claude, write one file, watch for hook 502 errors.
    Returns (got_502: bool, pty_output: str).
    """
    workdir = tempfile.mkdtemp(prefix=f"hook_ipv6_{label}_")
    claude_dir = os.path.join(workdir, ".claude")
    os.makedirs(claude_dir)

    settings = {
        "hooks": {
            "PreToolUse": [{
                "matcher": "Write",
                "hooks": [{"type": "http", "url": hook_url}]
            }]
        }
    }
    with open(os.path.join(claude_dir, "settings.local.json"), "w") as f:
        json.dump(settings, f)

    master_fd, slave_fd = pty.openpty()
    env = os.environ.copy()
    env["TERM"] = "xterm-256color"
    # Remove HTTP_PROXY so proxy is not a factor — we're testing IPv4/IPv6 only
    env.pop("HTTP_PROXY", None)
    env.pop("HTTPS_PROXY", None)
    env.pop("http_proxy", None)
    env.pop("https_proxy", None)

    proc = subprocess.Popen(
        ["claude", "--dangerously-skip-permissions"],
        cwd=workdir,
        stdin=slave_fd, stdout=slave_fd, stderr=slave_fd,
        env=env,
        preexec_fn=os.setsid,
        close_fds=True,
    )
    os.close(slave_fd)

    parts = []
    stop_ev = threading.Event()

    def reader():
        while not stop_ev.is_set():
            try:
                r, _, _ = select.select([master_fd], [], [], 0.3)
                if r:
                    chunk = os.read(master_fd, 4096).decode("utf-8", errors="replace")
                    parts.append(chunk)
                    print(chunk, end="", flush=True)
            except OSError:
                break

    threading.Thread(target=reader, daemon=True).start()

    # Wait for initial trust/ready prompt
    time.sleep(5)
    os.write(master_fd, b"2")   # accept bypass warning if present
    time.sleep(3)

    print(f"\n[{label}] sending Write prompt", flush=True)
    os.write(master_fd, b"Write 'hello' to test.txt\r")

    # Wait for hook response or timeout
    deadline = time.time() + TIMEOUT
    while time.time() < deadline:
        combined = "".join(parts)
        if "502" in combined or "hook error" in combined.lower():
            break
        # Also stop if claude seems done (prompt reappears)
        if combined.count(">") >= 4:
            break
        time.sleep(1)

    time.sleep(3)
    stop_ev.set()
    try:
        os.killpg(os.getpgid(proc.pid), signal.SIGTERM)
    except Exception:
        pass
    proc.wait(timeout=5)

    output = "".join(parts)
    got_502 = "502" in output or "hook error" in output.lower()
    return got_502, output


# ─────────────────────────────────────────────────────────────────────────────
# Main
# ─────────────────────────────────────────────────────────────────────────────

def main():
    # Check that localhost actually resolves to ::1 on this system
    addrs = socket.getaddrinfo("localhost", 80)
    preferred = addrs[0][0]  # first address family preferred by OS
    localhost_is_ipv6 = (preferred == socket.AF_INET6)
    print(f"[info] localhost preferred family: {addrs[0][0].name} ({addrs[0][4][0]})")
    if not localhost_is_ipv6:
        print("[info] localhost does NOT resolve to IPv6 first on this system.")
        print("[info] The bug may not be reproducible here — skipping FAIL variant check.")

    port = free_port_ipv4()
    server = start_ipv4_only_server(port)
    print(f"[info] IPv4-only hook server on 0.0.0.0:{port}", flush=True)

    # ── FAIL variant: localhost URL ──────────────────────────────────────────
    localhost_url = f"http://localhost:{port}/hook"
    print(f"\n{'='*60}", flush=True)
    print(f"[FAIL-variant] hook URL = {localhost_url}", flush=True)
    print(f"{'='*60}", flush=True)
    got_502_localhost, out_localhost = run_variant(localhost_url, port, "localhost")
    print(f"\n[FAIL-variant] got_502={got_502_localhost}", flush=True)

    # ── PASS variant: 127.0.0.1 URL ─────────────────────────────────────────
    ipv4_url = f"http://127.0.0.1:{port}/hook"
    print(f"\n{'='*60}", flush=True)
    print(f"[PASS-variant] hook URL = {ipv4_url}", flush=True)
    print(f"{'='*60}", flush=True)
    got_502_ipv4, out_ipv4 = run_variant(ipv4_url, port, "127.0.0.1")
    print(f"\n[PASS-variant] got_502={got_502_ipv4}", flush=True)

    server.shutdown()

    # ── Verdict ──────────────────────────────────────────────────────────────
    print(f"\n{'='*60}", flush=True)
    if localhost_is_ipv6:
        # On IPv6-first systems: localhost should fail, 127.0.0.1 should succeed
        if got_502_localhost and not got_502_ipv4:
            print("PASS: localhost caused 502 (IPv6 connect to IPv4-only server),"
                  " 127.0.0.1 worked correctly.")
            sys.exit(0)
        elif not got_502_localhost:
            print("UNEXPECTED: localhost did NOT cause 502 — hypothesis may be wrong.")
            sys.exit(1)
        elif got_502_ipv4:
            print("FAIL: 127.0.0.1 also caused 502 — 127.0.0.1 fix does NOT solve the problem.")
            sys.exit(1)
        else:
            print("FAIL: unexpected combination.")
            sys.exit(1)
    else:
        # On IPv4-first systems: neither should fail
        if not got_502_localhost and not got_502_ipv4:
            print("PASS (IPv4-first system): neither URL caused 502 — consistent with hypothesis.")
            sys.exit(0)
        else:
            print("FAIL: unexpected 502 on IPv4-first system.")
            sys.exit(1)


if __name__ == "__main__":
    main()
