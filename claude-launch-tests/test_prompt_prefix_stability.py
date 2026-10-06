"""
Hypothesis: with Claude Code 2.1.288 the OpenAI/Codex proxy never gets a prompt
cache hit (cached_tokens=0 on every request, although prompt_cache_key is
stable) because the request prefix — `tools` or the beginning of `system` —
differs between consecutive requests of the same conversation.

Method: run `claude --print` (then `--print --continue`) against a local fake
Anthropic server that records every request body. The first main-model reply is
a tool_use (Bash `echo hi`) so one turn produces several requests. Consecutive
main-model requests are then compared: tools, system blocks, non user/assistant
messages, and the first differing byte of the prefix serialized the way
AnthropicToCodexTranslator builds it (tools, then instructions, then input).

Run: python3 claude-launch-tests/test_prompt_prefix_stability.py

Result (Claude Code 2.1.288, both --print and an interactive PTY run with
--model gpt-5.6-terra): hypothesis REFUTED for the Anthropic-side request.
`tools` (same order and definitions) and all top-level `system` blocks —
including the `x-anthropic-billing-header` block, whose fingerprint stays fixed
within the conversation — are byte-identical between consecutive main-model
requests; the first difference is deep inside `messages`, plus moving
`cache_control` markers, which the Codex translator ignores anyway.
Side finding: 2.1.288 now sends `role:"system"` entries inside `messages[]`
(the "# Environment ..." block right after the first user prompt, and
system-reminder-like blocks after tool results; anthropic-beta
mid-conversation-system-2026-04-07). AnthropicToCodexTranslator drops every
message whose role is not user/assistant, so that content never reaches the model.
"""

import hashlib
import http.server
import json
import os
import shutil
import subprocess
import threading

CLAUDE = shutil.which('claude') or '/usr/local/bin/claude'
CWD = '/tmp/claude_prefix_stability_test'
OUT_DIR = os.environ.get('PREFIX_TEST_OUT', os.path.join(CWD, 'captured'))
ENV_BASE = {k: v for k, v in os.environ.items()
            if k not in ('CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT',
                         'ANTHROPIC_BASE_URL', 'ANTHROPIC_AUTH_TOKEN', 'ANTHROPIC_API_KEY',
                         'HTTP_PROXY', 'HTTPS_PROXY', 'http_proxy', 'https_proxy')}


def sse(event, data):
    return f"event: {event}\ndata: {json.dumps(data)}\n\n"


def text_stream(text):
    return (sse('message_start', {"type": "message_start", "message": {
                "id": "msg_t", "type": "message", "role": "assistant", "model": "m",
                "content": [], "stop_reason": None, "stop_sequence": None,
                "usage": {"input_tokens": 1, "output_tokens": 0}}})
            + sse('content_block_start', {"type": "content_block_start", "index": 0,
                                          "content_block": {"type": "text", "text": ""}})
            + sse('content_block_delta', {"type": "content_block_delta", "index": 0,
                                          "delta": {"type": "text_delta", "text": text}})
            + sse('content_block_stop', {"type": "content_block_stop", "index": 0})
            + sse('message_delta', {"type": "message_delta",
                                    "delta": {"stop_reason": "end_turn", "stop_sequence": None},
                                    "usage": {"output_tokens": 1}})
            + sse('message_stop', {"type": "message_stop"}))


def tool_stream(call_id):
    return (sse('message_start', {"type": "message_start", "message": {
                "id": "msg_t", "type": "message", "role": "assistant", "model": "m",
                "content": [], "stop_reason": None, "stop_sequence": None,
                "usage": {"input_tokens": 1, "output_tokens": 0}}})
            + sse('content_block_start', {"type": "content_block_start", "index": 0,
                                          "content_block": {"type": "tool_use", "id": call_id,
                                                            "name": "Bash", "input": {}}})
            + sse('content_block_delta', {"type": "content_block_delta", "index": 0,
                                          "delta": {"type": "input_json_delta",
                                                    "partial_json": json.dumps(
                                                        {"command": "echo hi",
                                                         "description": "Print hi"})}})
            + sse('content_block_stop', {"type": "content_block_stop", "index": 0})
            + sse('message_delta', {"type": "message_delta",
                                    "delta": {"stop_reason": "tool_use", "stop_sequence": None},
                                    "usage": {"output_tokens": 1}})
            + sse('message_stop', {"type": "message_stop"}))


def is_main(body):
    return len(body.get('tools') or []) > 5


class Handler(http.server.BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        pass

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        raw = self.rfile.read(length)
        try:
            body = json.loads(raw)
        except Exception:
            body = {}
        srv = self.server
        with srv.lock:
            idx = len(srv.requests)
            srv.requests.append({'path': self.path, 'body': body,
                                 'session': self.headers.get('X-Claude-Code-Session-Id')})
            with open(os.path.join(OUT_DIR, f'req_{idx:02d}.json'), 'w') as f:
                json.dump({'path': self.path, 'headers': dict(self.headers), 'body': body}, f, indent=1)
            main = is_main(body)
            give_tool = False
            if main and srv.tool_calls == 0:
                # only the very first main-model request gets a tool call (a trailing
                # role:"system" message makes "is the last message a tool_result?" unreliable)
                give_tool = True
                srv.tool_calls += 1
        if 'count_tokens' in self.path:
            payload = json.dumps({"input_tokens": 10}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
            return
        if body.get('stream'):
            payload = (tool_stream(f'toolu_{srv.tool_calls:04d}') if give_tool
                       else text_stream('done')).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'text/event-stream')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)
        else:
            payload = json.dumps({
                "id": "msg_t", "type": "message", "role": "assistant", "model": "m",
                "content": [{"type": "text", "text": "Title"}],
                "stop_reason": "end_turn", "stop_sequence": None,
                "usage": {"input_tokens": 1, "output_tokens": 1}}).encode()
            self.send_response(200)
            self.send_header('Content-Type', 'application/json')
            self.send_header('Content-Length', str(len(payload)))
            self.end_headers()
            self.wfile.write(payload)


def sha(s):
    return hashlib.sha1(s.encode('utf-8')).hexdigest()[:10]


def system_blocks(body):
    s = body.get('system')
    if isinstance(s, str):
        return [{'type': 'text', 'text': s}]
    return s or []


def codex_prefix(body):
    """Approximates the Codex request prefix order: tools, instructions, input."""
    tools = json.dumps([{'name': t.get('name'), 'description': t.get('description'),
                         'parameters': t.get('input_schema')} for t in body.get('tools', [])],
                       sort_keys=False)
    instr = '\n\n'.join(b.get('text', '') for b in system_blocks(body) if b.get('type') == 'text')
    msgs = json.dumps(body.get('messages', []))
    return tools + '\x00' + instr + '\x00' + msgs, len(tools), len(instr)


def first_diff(a, b):
    n = min(len(a), len(b))
    for i in range(n):
        if a[i] != b[i]:
            return i
    return n


def describe(i, r):
    b = r['body']
    print(f"\n--- request #{i} path={r['path']} session={r['session']} model={b.get('model')} "
          f"stream={b.get('stream')} tools={len(b.get('tools') or [])} messages={len(b.get('messages') or [])}")
    for j, blk in enumerate(system_blocks(b)):
        txt = blk.get('text', '')
        print(f"   system[{j}] len={len(txt)} sha={sha(txt)} cache_control={blk.get('cache_control')} "
              f"head={txt[:90]!r}")
    for j, m in enumerate(b.get('messages') or []):
        if m.get('role') not in ('user', 'assistant'):
            print(f"   !! messages[{j}] role={m.get('role')}")
        c = m.get('content')
        if isinstance(c, list):
            for k, blk in enumerate(c):
                if blk.get('type') not in ('text', 'tool_use', 'tool_result', 'image', 'thinking'):
                    print(f"   !! messages[{j}].content[{k}] type={blk.get('type')}")
                if blk.get('cache_control'):
                    print(f"   messages[{j}].content[{k}] cache_control={blk.get('cache_control')}")
    for k in b:
        if k not in ('model', 'messages', 'system', 'tools', 'stream', 'max_tokens', 'metadata',
                     'thinking', 'temperature', 'output_config', 'context_management', 'tool_choice'):
            print(f"   extra top-level key: {k}={json.dumps(b[k])[:200]}")


def compare(a, b, ia, ib):
    ba, bb = a['body'], b['body']
    print(f"\n=== compare #{ia} -> #{ib}")
    ta = [t.get('name') for t in ba.get('tools', [])]
    tb = [t.get('name') for t in bb.get('tools', [])]
    if ta == tb:
        changed = [t['name'] for t, u in zip(ba['tools'], bb['tools'])
                   if json.dumps(t, sort_keys=True) != json.dumps(u, sort_keys=True)]
        print(f"   tools: same order; changed definitions: {changed or 'none'}")
    else:
        print(f"   tools: DIFFERENT. added={sorted(set(tb) - set(ta))} removed={sorted(set(ta) - set(tb))} "
              f"reordered={set(ta) == set(tb)}")
    sa, sb = system_blocks(ba), system_blocks(bb)
    for j in range(max(len(sa), len(sb))):
        x = sa[j].get('text', '') if j < len(sa) else None
        y = sb[j].get('text', '') if j < len(sb) else None
        if x != y:
            if x is None or y is None:
                print(f"   system[{j}] present only in one request")
            else:
                d = first_diff(x, y)
                print(f"   system[{j}] DIFFERS at char {d}: {x[max(0, d - 40):d + 60]!r} -> {y[max(0, d - 40):d + 60]!r}")
    pa, ltools, linstr = codex_prefix(ba)
    pb, _, _ = codex_prefix(bb)
    d = first_diff(pa, pb)
    where = 'tools' if d < ltools else ('instructions' if d < ltools + 1 + linstr else 'messages')
    print(f"   codex-order prefix: first diff at char {d} of {len(pa)} ({where}); "
          f"context: {pa[max(0, d - 60):d + 60]!r}")


def run(args):
    env = dict(ENV_BASE)
    env['ANTHROPIC_BASE_URL'] = f'http://127.0.0.1:{PORT}'
    env['ANTHROPIC_AUTH_TOKEN'] = 'sk-test'
    env['NO_PROXY'] = '127.0.0.1,localhost'
    p = subprocess.run([CLAUDE, '--print', '--permission-mode', 'bypassPermissions'] + args,
                       cwd=CWD, env=env, capture_output=True, text=True, timeout=180)
    print(f"$ claude {' '.join(args)} -> rc={p.returncode} out={p.stdout[:200]!r} err={p.stderr[:300]!r}")


if __name__ == '__main__':
    os.makedirs(CWD, exist_ok=True)
    shutil.rmtree(OUT_DIR, ignore_errors=True)
    os.makedirs(OUT_DIR)
    server = http.server.ThreadingHTTPServer(('127.0.0.1', 0), Handler)
    server.requests = []
    server.lock = threading.Lock()
    server.tool_calls = 0
    PORT = server.server_address[1]
    threading.Thread(target=server.serve_forever, daemon=True).start()

    run(['Run echo hi using Bash, then say done'])
    run(['--continue', 'Run echo hi again, then say done'])
    server.shutdown()

    reqs = server.requests
    print(f"\nCaptured {len(reqs)} requests into {OUT_DIR}")
    for i, r in enumerate(reqs):
        describe(i, r)
    mains = [i for i, r in enumerate(reqs) if is_main(r['body'])]
    print(f"\nMain-model requests: {mains}")
    for a, b in zip(mains, mains[1:]):
        compare(reqs[a], reqs[b], a, b)
