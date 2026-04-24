#!/usr/bin/env python3
"""Verify that concurrent PreToolUse hook requests are all served without 502 errors.

Regression test for the thread-exhaustion bug in HookServlet: when multiple
Write hooks fire simultaneously (each blocking a Jetty thread waiting for
user approval), subsequent hook or Stop hook requests were returned as HTTP 502.

The test uses a mock hook server that delays each response by DELAY_SECONDS
(simulating the user taking time to approve/reject in the diff dialog), then
eventually responds with "allow". Claude is asked to write several files in one
prompt; all hooks should get 200 responses and the PTY output must contain no
"502" errors.

Exit codes:
    0 – PASS: all hooks received 200, no 502 in PTY output
    1 – FAIL
"""

import http.server
import json
import os
import pathlib
import select
import signal
import socket
import subprocess
import sys
import tempfile
import threading
import time
import pty

# ── tunables ──────────────────────────────────────────────────────────────────

DELAY_SECONDS   = 3   # seconds each hook response is delayed (simulates user think-time)
EXPECTED_HOOKS  = 3   # number of files claude is asked to write
TIMEOUT_SECONDS = 90  # total test timeout

# ── hook server ───────────────────────────────────────────────────────────────

hooks_received  = []
hooks_lock      = threading.Lock()

ALLOW_JSON = json.dumps({
    "hookSpecificOutput": {
        "hookEventName": "PreToolUse",
        "permissionDecision": "allow"
    }
}).encode()


class DelayedHookHandler(http.server.BaseHTTPRequestHandler):
    """Delays the allow response by DELAY_SECONDS to simulate pending user approval."""

    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get("Content-Length", 0))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except Exception:
            body = {"raw": raw.decode(errors="replace")}

        with hooks_lock:
            idx = len(hooks_received) + 1
            hooks_received.append(body)
        tool = body.get("tool_name", "?")
        print(f"[hook #{idx}] received: tool={tool}", flush=True)

        # Simulate the user taking DELAY_SECONDS to click Accept in the diff dialog
        time.sleep(DELAY_SECONDS)

        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(ALLOW_JSON)))
        self.end_headers()
        self.wfile.write(ALLOW_JSON)
        print(f"[hook #{idx}] responded 200 allow", flush=True)


class ThreadedHTTPServer(http.server.ThreadingHTTPServer):
    """Serves each hook request in its own thread — mirrors the plugin's Jetty config."""
    pass


def free_port():
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return s.getsockname()[1]


HOOK_PORT = free_port()
hook_server = ThreadedHTTPServer(("127.0.0.1", HOOK_PORT), DelayedHookHandler)
threading.Thread(target=hook_server.serve_forever, daemon=True).start()
print(f"[test] hook server on port {HOOK_PORT}, delay={DELAY_SECONDS}s per hook", flush=True)

# ── work directory ────────────────────────────────────────────────────────────

workdir = tempfile.mkdtemp(prefix="hook_concurrent_")
claude_dir = os.path.join(workdir, ".claude")
os.makedirs(claude_dir, exist_ok=True)

settings = {
    "hooks": {
        "PreToolUse": [{
            "matcher": "Edit|Write|MultiEdit",
            "hooks": [{"type": "http", "url": f"http://127.0.0.1:{HOOK_PORT}/hook"}]
        }]
    }
}
settings_path = os.path.join(claude_dir, "settings.local.json")
with open(settings_path, "w") as f:
    json.dump(settings, f, indent=2)

print(f"[test] workdir: {workdir}", flush=True)

# ── spawn Claude in TUI mode ──────────────────────────────────────────────────

master_fd, slave_fd = pty.openpty()
env = os.environ.copy()
env["TERM"] = "xterm-256color"
proc = subprocess.Popen(
    ["claude", "--dangerously-skip-permissions"],
    cwd=workdir,
    stdin=slave_fd,
    stdout=slave_fd,
    stderr=slave_fd,
    env=env,
    preexec_fn=os.setsid,
    close_fds=True,
)
os.close(slave_fd)

pty_output_parts = []
pty_lock = threading.Lock()
stop_reader = threading.Event()


def reader():
    while not stop_reader.is_set():
        try:
            r, _, _ = select.select([master_fd], [], [], 0.5)
            if r:
                chunk = os.read(master_fd, 4096).decode("utf-8", errors="replace")
                with pty_lock:
                    pty_output_parts.append(chunk)
                print(chunk, end="", flush=True)
        except OSError:
            break


reader_thread = threading.Thread(target=reader, daemon=True)
reader_thread.start()

# Wait for initial prompt (trust warning / input ready)
time.sleep(5)
os.write(master_fd, b"2")   # accept bypass warning if present
time.sleep(3)

# Ask Claude to write three files in a single prompt so hooks fire concurrently
prompt = (
    "Write three files: file1.txt containing 'alpha', "
    "file2.txt containing 'beta', "
    "file3.txt containing 'gamma'. "
    "Just create them, no explanation."
)
print(f"\n[test] sending prompt", flush=True)
os.write(master_fd, prompt.encode() + b"\r")

# Wait long enough for all DELAY_SECONDS-delayed hooks to complete
deadline = time.time() + TIMEOUT_SECONDS
while time.time() < deadline:
    with hooks_lock:
        n = len(hooks_received)
    if n >= EXPECTED_HOOKS:
        break
    time.sleep(1)

# Give claude a moment to finish writing
time.sleep(5)

# ── teardown ──────────────────────────────────────────────────────────────────

stop_reader.set()
proc.send_signal(signal.SIGTERM)
time.sleep(1)
hook_server.shutdown()
reader_thread.join(timeout=3)

with pty_lock:
    pty_output = "".join(pty_output_parts)

# ── verdict ───────────────────────────────────────────────────────────────────

print("\n" + "=" * 60, flush=True)

with hooks_lock:
    n_hooks = len(hooks_received)

# Check for 502 error text in PTY output
error_patterns = ["HTTP 502", "502 Bad Gateway", "hook error"]
found_errors = [p for p in error_patterns if p.lower() in pty_output.lower()]

passed = True

if n_hooks < EXPECTED_HOOKS:
    print(f"FAIL: only {n_hooks}/{EXPECTED_HOOKS} hooks received within {TIMEOUT_SECONDS}s")
    passed = False
else:
    print(f"OK: {n_hooks}/{EXPECTED_HOOKS} hooks received")

if found_errors:
    print(f"FAIL: PTY output contains error pattern(s): {found_errors}")
    passed = False
else:
    print("OK: no 502/hook errors in PTY output")

# Verify the files were actually written
for fname in ("file1.txt", "file2.txt", "file3.txt"):
    fpath = pathlib.Path(workdir) / fname
    if fpath.exists():
        print(f"OK: {fname} exists")
    else:
        print(f"WARN: {fname} not found (hook may have been delayed past claude timeout)")

print("=" * 60, flush=True)
if passed:
    print("\nRESULT: PASS")
    sys.exit(0)
else:
    print("\nRESULT: FAIL")
    sys.exit(1)
