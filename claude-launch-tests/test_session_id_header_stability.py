"""
Hypothesis: Claude Code CLI's `X-Claude-Code-Session-Id` request header stays
identical across a `--continue` restart of the same conversation, since it's
the same identifier used for the `~/.claude/projects/<hash>/<session-id>.jsonl`
file that `--continue` reopens. If true, this header is a better source for the
OpenAI proxy's `prompt_cache_key` than the plugin's own per-process UUID (which
is regenerated on every PTY restart, defeating prompt-cache continuity across
"Continue last session").

Method: run `claude --print` twice from the same working directory against a
local fake HTTP server that echoes a valid Anthropic message response and
records the `X-Claude-Code-Session-Id` header of every request — first as a
new session, then with `--continue`.

Run: python3 claude-launch-tests/test_session_id_header_stability.py

Result: CONFIRMED. Both runs sent the identical session id header
(e.g. `3ff9518a-bfbd-4de7-92fe-13c419eba9f5` in one run), matching the
`session_id` field in the CLI's own `system/init` stream-json event. Applied in
OpenAIProxyServlet: `prompt_cache_key` is now sourced from this header (falling
back to the proxy's internal per-process UUID when absent), so prompt caching
survives "Continue last session" / "Resume session" instead of starting a fresh
cache key on every restart.
"""

import http.server
import json
import os
import subprocess
import threading

CLAUDE = '/usr/local/bin/claude'
CWD = '/tmp/claude_session_id_stability_test'
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
        session_header = self.headers.get('X-Claude-Code-Session-Id', '<MISSING>')
        self.server.session_ids.append(session_header)
        length = int(self.headers.get('Content-Length', 0))
        self.rfile.read(length)
        self.send_response(200)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(RESPONSE_BODY)))
        self.end_headers()
        self.wfile.write(RESPONSE_BODY)


def run(env, prompt, extra_args=()):
    cmd = [CLAUDE, '--print', prompt, '--output-format', 'stream-json',
           '--verbose', '--dangerously-skip-permissions', *extra_args]
    proc = subprocess.run(cmd, env=env, cwd=CWD, capture_output=True, timeout=30)
    return proc.stdout.decode('utf-8', errors='replace')


def main():
    os.makedirs(CWD, exist_ok=True)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), RecordingHandler)
    server.session_ids = []
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()

    env = dict(ENV_BASE)
    env['ANTHROPIC_BASE_URL'] = f'http://127.0.0.1:{port}'
    env['ANTHROPIC_AUTH_TOKEN'] = 'sk-test-fake'

    print('=== First run (new session) ===')
    out1 = run(env, 'say hi')
    ids_after_first = list(server.session_ids)
    print(f'session ids after first run: {ids_after_first}')

    print('\n=== Second run (--continue) ===')
    out2 = run(env, 'say hi again', extra_args=('--continue',))
    ids_after_second = list(server.session_ids)
    print(f'session ids after second run: {ids_after_second}')

    server.shutdown()

    first_unique = set(ids_after_first)
    second_new = set(ids_after_second) - set(ids_after_first)
    print(f'\nfirst-run session id(s): {first_unique}')
    print(f'session id(s) seen only in second run: {second_new}')
    stable = first_unique and second_new <= first_unique
    print(f'\nSTABLE ACROSS --continue: {stable}')


if __name__ == '__main__':
    main()
