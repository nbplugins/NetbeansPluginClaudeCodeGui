"""
Hypothesis: the ChatGPT-subscription Codex backend
(https://chatgpt.com/backend-api/codex/responses) reports cached_tokens=0 for
the plugin's requests not because the prompt prefix changes, but because the
proxy omits the `session_id` request header that Codex CLI sends — the body's
`prompt_cache_key` alone does not keep consecutive requests on the same cache
shard. Also checks whether the backend accepts explicit caching fields.

Method: send the same request (~7k-token instructions, or the real Claude Code
system prompt + tools when a captured request body is passed) several times
with one prompt_cache_key, with and without a `session_id` header, and with
`prompt_cache_options` / `prompt_cache_retention`. Reads the ChatGPT access
token from the plugin's NetBeans profile ("ChatGPT" by default) — never
printed, never refreshed (refreshing would rotate the refresh token behind the
plugin's back). Consumes a few requests of the subscription quota.

Run: python3 claude-launch-tests/test_codex_prompt_cache.py [captured_request.json]
     (captured_request.json: a request saved by test_prompt_prefix_stability.py)
Env: NB_PREFS (path to nbclaudecodegui.properties), NB_PROFILE (profile name),
     CODEX_MODEL (default gpt-5.6-terra)

Result (2026-10-06, gpt-5.6-terra / gpt-5.6-luna / gpt-5.5): CONFIRMED.
- no session_id header: cached_tokens=0 on 4/4 repeats of a 29k-token
  real-shape request; with a 7k prompt hits were random (0, 6912, 6912, 0, 0)
- session_id header = prompt_cache_key: cached_tokens=28160/28977 from the
  2nd request on, every time
- prompt_cache_options → HTTP 400 "prompt_cache_options is not supported on this model"
- prompt_cache_retention → HTTP 400 "Unsupported parameter: prompt_cache_retention"
Applied: OpenAIProxyServlet.buildCodexHttpRequest sends `session_id`;
AnthropicToCodexTranslator never sends explicit caching fields.
"""

import json
import os
import sys
import time
import urllib.error
import urllib.request
import uuid

URL = 'https://chatgpt.com/backend-api/codex/responses'
NB_PREFS = os.environ.get('NB_PREFS', os.path.expanduser(
    '~/.netbeans/29/config/Preferences/io/github/nbclaudecodegui.properties'))
NB_PROFILE = os.environ.get('NB_PROFILE', 'ChatGPT')
MODEL = os.environ.get('CODEX_MODEL', 'gpt-5.6-terra')


def load_profile():
    raw = None
    with open(NB_PREFS, encoding='latin1') as f:
        for line in f:
            if line.startswith('profiles='):
                raw = line[len('profiles='):].rstrip('\n')
    raw = raw.encode('latin1').decode('unicode_escape').replace('\\:', ':').replace('\\=', '=')
    for p in json.loads(raw):
        if p.get('name') == NB_PROFILE:
            return p
    raise SystemExit(f'profile {NB_PROFILE!r} not found in {NB_PREFS}')


PROFILE = load_profile()
_proxy = PROFILE.get('httpsProxy') or PROFILE.get('httpProxy')
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({'https': _proxy} if _proxy else {}))


def prompt_parts():
    if len(sys.argv) > 1:
        body = json.load(open(sys.argv[1]))['body']
        instr = '\n\n'.join(b['text'] for b in body['system'] if b.get('type') == 'text')
        tools = [{"type": "function", "name": t['name'], "description": t.get('description', ''),
                  "parameters": t.get('input_schema')}
                 for t in body.get('tools', []) if not t.get('type', '').startswith('web_search')]
        return instr, tools
    instr = "You are a helpful coding assistant. " + " ".join(
        f"Rule {i}: always keep answers short, precise and correct, rule number {i}." for i in range(400))
    return instr, []


INSTR, TOOLS = prompt_parts()


def call(key, session_header, extra=None, n=0):
    body = {"model": MODEL, "stream": True, "store": False, "instructions": INSTR,
            "prompt_cache_key": key,
            "input": [{"type": "message", "role": "user",
                       "content": [{"type": "input_text", "text": f"Reply with just OK {n}"}]}]}
    if TOOLS:
        body["tools"] = TOOLS
    if extra:
        body.update(extra)
    headers = {"Content-Type": "application/json", "Accept": "text/event-stream",
               "Authorization": "Bearer " + PROFILE['chatgptAccessToken'],
               "ChatGPT-Account-Id": PROFILE['chatgptAccountId'], "originator": "codex_cli_rs"}
    if session_header:
        headers['session_id'] = key
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), method='POST', headers=headers)
    try:
        resp = OPENER.open(req, timeout=180)
    except urllib.error.HTTPError as e:
        return f"HTTP {e.code}: {e.read()[:200].decode(errors='replace')}"
    event = None
    for line in resp:
        line = line.decode().rstrip('\n')
        if line.startswith('event:'):
            event = line[6:].strip()
        elif line.startswith('data:') and event == 'response.completed':
            u = json.loads(line[5:])['response']['usage']
            return f"input={u['input_tokens']} cached={u.get('input_tokens_details', {}).get('cached_tokens')}"
    return 'no response.completed event'


if __name__ == '__main__':
    print(f"model={MODEL} instructions={len(INSTR)} chars tools={len(TOOLS)}")
    variants = [
        ('no session_id header', False, None, 4),
        ('session_id header', True, None, 4),
        ('prompt_cache_options', True, {"prompt_cache_options": {"mode": "explicit", "ttl": "30m"}}, 1),
        ('prompt_cache_retention', True, {"prompt_cache_retention": "24h"}, 1),
    ]
    for name, header, extra, repeats in variants:
        key = str(uuid.uuid4())
        for i in range(repeats):
            print(f"{name} #{i}: {call(key, header, extra, i)}", flush=True)
            time.sleep(3)
