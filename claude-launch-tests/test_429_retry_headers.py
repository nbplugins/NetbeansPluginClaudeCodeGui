"""
Hypothesis: Claude Code CLI's built-in retry behavior on HTTP 429 responses can be
influenced by response headers (Retry-After, or a should-retry-style convention),
so a proxy could tell the CLI "don't bother retrying, this is a hard quota limit"
instead of the CLI blindly retrying up to 10 times with its own backoff schedule.

Background: the NetBeans plugin's OpenAI-compatible proxy already returns a clean
Anthropic-format `rate_limit_error` on 429 (see OpenAIProxyServlet.toCodexAnthropicError),
but captured logs from a real session still showed the CLI retrying
"attempt 1/10" .. "attempt 10/10" with 1s-39s backoff before giving up — even though
the underlying quota wouldn't reset for ~29 days. This test probes whether any
response header changes that behavior, run against a local fake server standing in
for ANTHROPIC_BASE_URL (same mechanism the plugin's proxy uses).

Run: python3 claude-launch-tests/test_429_retry_headers.py

Result: CONFIRMED — both mechanisms work.
- baseline (no extra headers): 8 requests over 90s, then still hadn't finished
  (timed out at our 90s test cap; the real backoff schedule is 1s/2s/4s/8s/16s/32s/39s(capped)
  and would keep going up to attempt 10/10, matching the ~10min real-session logs)
- Retry-After: 999999 (a value far larger than any sane retry budget): 1 request,
  ~0.1s, CLI immediately surfaces `{"error":"rate_limit","is_error":true,
  "api_error_status":429,...}` and exits — no retries at all
- Retry-After: 0: behaved like baseline (8+ requests, still retrying) — a small
  value does NOT suppress retries, only a large one does
- x-should-retry: false: 1 request, ~0.1s, same immediate-surface behavior as
  the large Retry-After case

Conclusion: Claude Code CLI's SDK (Stainless-generated) honors both a large
`Retry-After` value and an explicit `X-Should-Retry: false` header to skip its
own retry loop and surface the error immediately. Applied in
OpenAIProxyServlet.setRetryAfterIfHardLimit(): when a Codex `usage_limit_reached`
error body carries `resets_in_seconds` (a genuine, long-lived hard quota limit),
the proxy now forwards it as `Retry-After` on the HTTP response — but only for
that specific hard-limit case, not for ordinary transient 429s (which have no
`resets_in_seconds` field), so those still get the CLI's normal short-backoff
retry behavior. This only applies to non-streaming responses (where the proxy
sets the real HTTP status/headers); the streaming (SSE) error paths in
OpenAIProxyServlet already commit to `HTTP 200` before the upstream error is
known, so there's no way to retroactively attach a Retry-After header there —
the error is only conveyed via the SSE `event: error` body in that case.
"""

import http.server
import json
import os
import subprocess
import threading
import time

CLAUDE = '/usr/local/bin/claude'
CWD = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))
ENV_BASE = {k: v for k, v in os.environ.items()
            if k not in ('CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT',
                         'ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY')}

RATE_LIMIT_BODY = json.dumps({
    "type": "error",
    "error": {"type": "rate_limit_error", "message": "Rate limit test — should not resolve"},
}).encode('utf-8')

CMD_TAIL = ['--print', 'say hi', '--output-format', 'stream-json', '--verbose',
            '--dangerously-skip-permissions']


class CountingHandler(http.server.BaseHTTPRequestHandler):
    """Always answers /v1/messages with 429 + RATE_LIMIT_BODY, optionally with
    extra headers. Records every request's arrival time on the class-level list
    supplied via `server.hits`."""

    extra_headers = {}

    def log_message(self, fmt, *args):
        pass  # keep test output focused on our own summary

    def do_POST(self):
        self.server.hits.append(time.monotonic())
        self.send_response(429)
        self.send_header('Content-Type', 'application/json')
        for k, v in self.extra_headers.items():
            self.send_header(k, v)
        self.send_header('Content-Length', str(len(RATE_LIMIT_BODY)))
        self.end_headers()
        self.wfile.write(RATE_LIMIT_BODY)


def run_variant(label, extra_headers, timeout=90):
    handler_cls = type('Handler', (CountingHandler,), {'extra_headers': extra_headers})
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), handler_cls)
    server.hits = []
    port = server.server_address[1]
    thread = threading.Thread(target=server.serve_forever, daemon=True)
    thread.start()

    env = dict(ENV_BASE)
    env['ANTHROPIC_BASE_URL'] = f'http://127.0.0.1:{port}'
    env['ANTHROPIC_AUTH_TOKEN'] = 'sk-test-fake'

    print(f'\n=== {label} (headers={extra_headers or "none"}) ===')
    start = time.monotonic()
    try:
        proc = subprocess.run([CLAUDE] + CMD_TAIL, env=env, cwd=CWD,
                               stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                               timeout=timeout)
        elapsed = time.monotonic() - start
        timed_out = False
    except subprocess.TimeoutExpired as e:
        elapsed = time.monotonic() - start
        timed_out = True
        proc = None

    server.shutdown()
    thread.join(timeout=5)

    hit_count = len(server.hits)
    gaps = [round(b - a, 2) for a, b in zip(server.hits, server.hits[1:])]
    print(f'requests to fake server: {hit_count}')
    print(f'gaps between requests (s): {gaps}')
    print(f'wall time: {elapsed:.1f}s{" (TIMED OUT)" if timed_out else ""}')
    if proc is not None:
        tail = proc.stdout.decode('utf-8', errors='replace')[-500:]
        print(f'stdout tail: {tail!r}')
        err_tail = proc.stderr.decode('utf-8', errors='replace')[-300:]
        if err_tail.strip():
            print(f'stderr tail: {err_tail!r}')
    return hit_count, elapsed, timed_out


if __name__ == '__main__':
    results = {}
    results['baseline (no extra headers)'] = run_variant(
        'baseline (no extra headers)', {})
    results['Retry-After: 999999 (far future)'] = run_variant(
        'Retry-After: 999999 (far future)', {'Retry-After': '999999'})
    results['Retry-After: 0'] = run_variant(
        'Retry-After: 0', {'Retry-After': '0'})
    results['x-should-retry: false'] = run_variant(
        'x-should-retry: false', {'x-should-retry': 'false'})

    print('\n\n=== SUMMARY ===')
    print(f'{"variant":40s} {"requests":>9s} {"seconds":>9s}')
    for label, (hits, elapsed, timed_out) in results.items():
        print(f'{label:40s} {hits:9d} {elapsed:9.1f}{"  TIMEOUT" if timed_out else ""}')
