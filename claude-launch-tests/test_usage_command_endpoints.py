"""
Hypothesis: the `/usage` slash command (interactive, PTY-only — not available in
--print mode) hits some Anthropic API endpoint other than /v1/messages, which a
proxy sitting at ANTHROPIC_BASE_URL (as the plugin's OpenAI-compatible proxy does)
could intercept and use to surface ChatGPT-subscription quota info.

Method: run `claude` interactively under a PTY (via pexpect) with ANTHROPIC_BASE_URL
pointed at a local fake HTTP server that logs every request path it receives (any
method), for a session that never even reaches a real backend. Send "/usage" and
see which path(s), if any, get hit.

Run: python3 claude-launch-tests/test_usage_command_endpoints.py

Result: REFUTED — even more conclusively than expected.
`/usage` makes ZERO network requests. The fake server recorded no hits at all,
before or after sending `/usage`, yet the command still opened a fully populated
panel:

    Settings  Status  Config  Usage  Stats  Session
    Total cost: $0.0000
    Total duration (API): 0s
    Total duration (wall): 5s
    Total code changes: 0 lines added, 0 lines removed
    Usage: 0 input, 0 output, 0 cache read, 0 cache write

Conclusion: `/usage` is a purely local view over the CURRENT session's own
running totals (token counts already returned in each /v1/messages response,
code-diff stats the CLI tracks itself, wall-clock time) — it is not a query to
any live account-wide usage/quota/billing endpoint, for any connection type or
auth mode. This isn't a matter of the plugin's proxy needing to intercept a
different path; there is no such request to intercept. So `/usage` can never
show ChatGPT-subscription-wide remaining quota or reset time, regardless of any
proxy-side changes — that information only exists in the 429 error body
(`resets_in_seconds`) when the limit is actually hit.
"""

import http.server
import os
import threading
import time

import pexpect

CLAUDE = '/usr/local/bin/claude'
CWD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_BASE = {k: v for k, v in os.environ.items()
            if k not in ('CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT',
                         'ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY')}


class LoggingHandler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def _handle(self):
        self.server.hits.append((self.command, self.path))
        body = b'{"error":"not found (test stub)"}'
        self.send_response(404)
        self.send_header('Content-Type', 'application/json')
        self.send_header('Content-Length', str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def do_GET(self):
        self._handle()

    def do_POST(self):
        self._handle()


def drain(child, buf, seconds):
    """Actively pull PTY output for `seconds` — pexpect only reads when asked
    to, so a plain time.sleep() would leave the child blocked on a full PTY
    buffer without ever making progress."""
    end = time.time() + seconds
    while time.time() < end:
        try:
            buf.append(child.read_nonblocking(size=4096, timeout=0.2))
        except pexpect.exceptions.TIMEOUT:
            pass
        except pexpect.exceptions.EOF:
            break


def main():
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), LoggingHandler)
    server.hits = []
    port = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()

    env = dict(ENV_BASE)
    env['ANTHROPIC_BASE_URL'] = f'http://127.0.0.1:{port}'
    env['ANTHROPIC_AUTH_TOKEN'] = 'sk-test-fake'

    child = pexpect.spawn(CLAUDE, ['--dangerously-skip-permissions'],
                           env=env, cwd=CWD, timeout=1, encoding='utf-8')
    buf = []
    try:
        drain(child, buf, 4)  # let the startup banner settle
        before = list(server.hits)
        print(f'requests before /usage: {before}')

        child.send('/usage')
        drain(child, buf, 1)
        child.send('\r')
        drain(child, buf, 4)

        after = list(server.hits)
        print(f'requests after /usage:  {after}')
        print(f'\nNEW requests triggered by /usage: {after[len(before):]}')
    finally:
        child.close(force=True)
        server.shutdown()


if __name__ == '__main__':
    main()
