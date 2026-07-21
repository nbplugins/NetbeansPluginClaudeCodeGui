"""
Hypothesis: the Anthropic request's `system` field (Claude Code CLI's system
prompt) stays byte-identical turn-to-turn within a session, which is a
prerequisite for explicit prompt-cache breakpoints (prompt_cache_breakpoint) to
actually help — if the "stable" prefix isn't actually stable, anchoring a
breakpoint there is pointless.

Method: run `claude --print` against a local fake HTTP server that echoes a
valid Anthropic message response and records every request body's `system`
field — once as a fresh session (two near-simultaneous requests within that one
process), then again via `--continue` (a second, separate process).

Run: python3 claude-launch-tests/test_system_prompt_prefix_stability.py

Result: MIXED — stable within a continuous process, NOT stable across
`--continue`/`--resume` process restarts.
- Within one `claude` process invocation, all captured requests had an
  identical `system` field (byte-for-byte) — e.g. two requests both had
  system_len=6083 in the first run, both 27038 in the second.
- Across a `--continue` restart (a fresh process), the `system` field changed
  substantially (6083 -> 27038 chars in one observed run) — the CLI rebuilds
  the system prompt on every process start, and it's *not* the same content as
  a brand-new session's first turn.

Conclusion: explicit prompt-cache breakpoints anchored on the system
prompt/instructions are safe and effective for the common case — a single
long-running interactive session with many turns (the exact pattern that
caused the original fast quota burn: a 140-message, ~465KB conversation
resent every turn without ever restarting the process). They are NOT expected
to carry a warm cache across `--continue`/`--resume` restarts, since the
prefix itself changes — but that's consistent with (and no worse than) the
already-expected cache-key reset on restart documented separately
(test_session_id_header_stability.py fixes the *key* staying stable across
restarts; it doesn't and can't fix the prefix *content* changing).
"""

import http.server
import json
import os
import subprocess
import threading

CLAUDE = '/usr/local/bin/claude'
CWD = '/tmp/claude_prefix_stability_test'
ENV_BASE = {k: v for k, v in os.environ.items()
            if k not in ('CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT',
                         'ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY')}

RESPONSE_BODY = json.dumps({
    "id": "msg_test", "type": "message", "role": "assistant", "model": "claude-sonnet-4-5",
    "content": [{"type": "text", "text": "hi there"}],
    "stop_reason": "end_turn", "stop_sequence": None,
    "usage": {"input_tokens": 1, "output_tokens": 1},
}).encode('utf-8')


class RecordingHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        self.server.bodies.append(self.rfile.read(length))
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(RESPONSE_BODY)))
        self.end_headers()
        self.wfile.write(RESPONSE_BODY)


def run(env, prompt, extra_args=()):
    cmd = [CLAUDE, '--print', prompt, '--output-format', 'stream-json',
           '--verbose', '--dangerously-skip-permissions', *extra_args]
    subprocess.run(cmd, env=env, cwd=CWD, capture_output=True, timeout=30)


def main():
    os.makedirs(CWD, exist_ok=True)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), RecordingHandler)
    server.bodies = []
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()

    env = dict(ENV_BASE)
    env['ANTHROPIC_BASE_URL'] = f'http://127.0.0.1:{port}'
    env['ANTHROPIC_AUTH_TOKEN'] = 'sk-test-fake'

    run(env, 'say hi')
    run_1_count = len(server.bodies)
    run(env, 'say hi again', extra_args=('--continue',))

    server.shutdown()

    systems = []
    for i, raw in enumerate(server.bodies):
        req = json.loads(raw)
        system = req.get('system')
        systems.append(system)
        print(f'request {i}: messages={len(req.get("messages", []))} '
              f'system_len={len(str(system)) if system else 0}')

    within_run1_stable = len(set(map(str, systems[:run_1_count]))) <= 1
    within_run2_stable = len(set(map(str, systems[run_1_count:]))) <= 1
    across_runs_stable = systems and systems[0] == systems[-1]

    print(f'\nstable within run 1 (same process, no restart): {within_run1_stable}')
    print(f'stable within run 2 (same process, no restart): {within_run2_stable}')
    print(f'stable across --continue restart: {across_runs_stable}')


if __name__ == '__main__':
    main()
