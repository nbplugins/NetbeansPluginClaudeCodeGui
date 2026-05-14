"""
POC: проверяем, что Claude Code вызывает инструмент show_markdown
в интерактивном режиме (без --print).

Тест A: план-режим (--permission-mode plan), явный запрос вызвать show_markdown
Тест B: план-режим + --append-system-prompt, промпт без упоминания инструмента
Тест C: обычный режим + --append-system-prompt, промпт без упоминания инструмента

Для каждого теста проверяем: вызван ли show_markdown и что передано
в filePath / workingDirectory.
"""
import json, os, queue, tempfile, threading, time
from http.server import BaseHTTPRequestHandler, HTTPServer
from socketserver import ThreadingMixIn

import pexpect

CLAUDE = '/usr/local/bin/claude'
CWD    = '/tmp'
ENV    = {k: v for k, v in os.environ.items()
          if k not in ('CLAUDECODE', 'CLAUDE_CODE_ENTRYPOINT')}

SHOW_MARKDOWN_SCHEMA = {
    "type": "object",
    "properties": {
        "markdown":  {"type": "string", "description": "Markdown content to display"},
        "title":     {"type": "string", "description": "Tab title (optional)"},
        "filePath":  {"type": "string", "description": (
            "Absolute path to the source .md file (optional). "
            "If the plan was written to a file (e.g. ~/.claude/plans/foo.md), "
            "pass that path here so the IDE can resolve relative image paths "
            "and use the filename as the tab name."
        )},
        "workingDirectory": {"type": "string", "description": (
            "Absolute path to the current project working directory (optional). "
            "Pass this so the IDE can associate the preview with the correct project."
        )}
    },
    "required": ["markdown"]
}

APPEND_SYSTEM_PROMPT = (
    'When you produce a plan or structured output, always call the show_markdown '
    'MCP tool (server: poc) to display it in the IDE. '
    'Pass the plan markdown in the markdown argument, a short title in title, '
    'the current working directory in workingDirectory, '
    'and — if you wrote the plan to a file — its absolute path in filePath.'
)

PROMPT = 'Make a short 3-step plan for building a hello-world web app.'


# ── MCP server ────────────────────────────────────────────────────────────────

class MCPHandler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args): pass

    def do_GET(self):
        if self.path != '/sse':
            self.send_response(404); self.end_headers(); return
        self.send_response(200)
        self.send_header('Content-Type', 'text/event-stream')
        self.send_header('Cache-Control', 'no-cache')
        self.send_header('Connection', 'keep-alive')
        self.end_headers()
        self._sse('endpoint', '/messages')
        q = self.server.sse_queue
        while True:
            try:
                msg = q.get(timeout=120)
                if msg is None: break
                self._sse('message', msg)
            except Exception: break

    def _sse(self, event, data):
        try:
            self.wfile.write(f'event: {event}\ndata: {data}\n\n'.encode())
            self.wfile.flush()
        except Exception: pass

    def _reply(self, resp):
        self.server.sse_queue.put(json.dumps(resp))

    def do_POST(self):
        length = int(self.headers.get('Content-Length', 0))
        body   = self.rfile.read(length)
        self.send_response(202); self.end_headers()
        try:
            msg    = json.loads(body)
            method = msg.get('method', '')
            req_id = msg.get('id')

            if method == 'initialize':
                self._reply({'jsonrpc': '2.0', 'id': req_id, 'result': {
                    'protocolVersion': '2024-11-05',
                    'capabilities': {'tools': {}},
                    'serverInfo': {'name': 'poc-server', 'version': '1.0'}
                }})
            elif method == 'notifications/initialized':
                pass
            elif method == 'tools/list':
                self._reply({'jsonrpc': '2.0', 'id': req_id, 'result': {'tools': [{
                    'name': 'show_markdown',
                    'description': (
                        'Display markdown content in the IDE Markdown Preview tab. '
                        'Call this to show plans, summaries, or structured output '
                        'with rich formatting outside the terminal.'
                    ),
                    'inputSchema': SHOW_MARKDOWN_SCHEMA
                }]}})
            elif method == 'tools/call':
                tool_name = msg.get('params', {}).get('name', '')
                arguments = msg.get('params', {}).get('arguments', {})
                if tool_name == 'show_markdown':
                    self.server.tool_called.set()
                    self.server.tool_args.update(arguments)
                    self._reply({'jsonrpc': '2.0', 'id': req_id,
                                 'result': {'content': [{'type': 'text', 'text': 'ok'}]}})
                else:
                    self._reply({'jsonrpc': '2.0', 'id': req_id,
                                 'error': {'code': -32601, 'message': 'Unknown tool'}})
            else:
                self._reply({'jsonrpc': '2.0', 'id': req_id,
                             'error': {'code': -32601, 'message': 'Method not found'}})
        except Exception as e:
            print(f'[server] POST error: {e}', flush=True)


class MCPServer(ThreadingMixIn, HTTPServer):
    daemon_threads = True
    def __init__(self, *args, **kwargs):
        super().__init__(*args, **kwargs)
        self.sse_queue  = queue.Queue()
        self.tool_called = threading.Event()
        self.tool_args   = {}


def start_server():
    srv  = MCPServer(('127.0.0.1', 0), MCPHandler)
    port = srv.server_address[1]
    threading.Thread(target=srv.serve_forever, daemon=True).start()
    return srv, port


# ── test runner ───────────────────────────────────────────────────────────────

def run_test(label, extra_args, prompt, logpath):
    srv, port = start_server()
    print(f'\n=== {label} (port={port}) ===', flush=True)

    cfg = json.dumps({'mcpServers': {'poc': {
        'type': 'sse', 'url': f'http://127.0.0.1:{port}/sse'
    }}})
    cfg_file = tempfile.NamedTemporaryFile(mode='w', suffix='.json', delete=False)
    cfg_file.write(cfg); cfg_file.close()

    args = (['--mcp-config', cfg_file.name,
             '--allowedTools', 'mcp__poc__show_markdown']
            + extra_args)

    logfile = open(logpath, 'w')
    child   = pexpect.spawn(CLAUDE, args, cwd=CWD, encoding='utf-8',
                            timeout=120, env=ENV, logfile=logfile)

    try:
        # wait for the input prompt
        child.expect(r'❯', timeout=30)
        time.sleep(0.5)
        child.sendline(prompt)

        # wait until show_markdown is called OR Claude goes idle again
        deadline = time.time() + 90
        while time.time() < deadline:
            if srv.tool_called.is_set():
                break
            try:
                child.expect(r'❯', timeout=3)
                # back at prompt — Claude finished without calling the tool
                break
            except pexpect.TIMEOUT:
                pass
    except pexpect.TIMEOUT:
        print('  TIMEOUT waiting for prompt', flush=True)
    except Exception as e:
        print(f'  ERROR: {e}', flush=True)
    finally:
        child.close(force=True)
        logfile.close()
        os.unlink(cfg_file.name)

    if srv.tool_called.is_set():
        args_out = srv.tool_args
        print(f'  ✓ PASS: show_markdown called', flush=True)
        print(f'    title:            {args_out.get("title", "(not passed)")}', flush=True)
        print(f'    filePath:         {args_out.get("filePath", "(not passed)")}', flush=True)
        print(f'    workingDirectory: {args_out.get("workingDirectory", "(not passed)")}', flush=True)
        print(f'    markdown[:80]:    {args_out.get("markdown","")[:80]}', flush=True)
    else:
        print(f'  ✗ FAIL: show_markdown NOT called — see {logpath}', flush=True)

    srv.shutdown()


def main():
    # A: plan mode, user explicitly asks to call show_markdown
    run_test(
        label      = 'A: plan-mode + explicit call in prompt',
        extra_args = ['--permission-mode', 'plan'],
        prompt     = PROMPT + ' Then use the show_markdown tool to display the plan.',
        logpath    = '/tmp/poc_A.log',
    )

    # B: plan mode + system prompt, user prompt doesn't mention the tool
    run_test(
        label      = 'B: plan-mode + append-system-prompt',
        extra_args = ['--permission-mode', 'plan',
                      '--append-system-prompt', APPEND_SYSTEM_PROMPT],
        prompt     = PROMPT,
        logpath    = '/tmp/poc_B.log',
    )

    # C: no plan mode + system prompt only
    run_test(
        label      = 'C: normal mode + append-system-prompt',
        extra_args = ['--append-system-prompt', APPEND_SYSTEM_PROMPT],
        prompt     = PROMPT,
        logpath    = '/tmp/poc_C.log',
    )


if __name__ == '__main__':
    main()
