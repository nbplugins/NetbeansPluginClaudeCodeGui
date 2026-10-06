"""
Hypothesis: the OpenAI-compatible router used by the plugin's "OpenAI Compatible"
profiles spreads identical requests over several backends with separate prompt
caches, so cached_tokens is randomly 0 even though the prompt prefix is
byte-identical. Some routers keep a conversation on one backend when given a
sticky-routing hint (header or body field). The proxy currently sends only
`prompt_cache_key`.

Method: for each variant send the same ~8k-token chat request N times (unique
prompt per variant so variants don't share a cache) and print cached_tokens.
Variants: nothing, prompt_cache_key, + x-session-id header, + session_id header,
+ `user` body field, + all of them. Reads the API key from the plugin's NetBeans
profile (never printed). Consumes a few cheap requests.

Run: python3 claude-launch-tests/test_router_sticky_cache.py
Env: NB_PREFS, NB_PROFILE (default WorkLlmRouter), ROUTER_MODEL (default z-ai/glm-5.3),
     REPEATS (default 6)

Result (2026-10-06, z-ai/glm-5.3 via llm-router.openintegration.inc, 6 x 8.5k-token
identical requests per variant): HYPOTHESIS NOT CONFIRMED for any hint — no variant
gives stable hits; routing is random on the router side.
- nothing:            hits on 2/5 repeats
- prompt_cache_key:   1/5
- + x-session-id:     2/5
- + session_id:       2/5
- + user field:       1/5
- all hints together: 1/5
A hit (cached ~ the whole prompt) appears after 3-5 identical requests and is often
lost on the next one, so the router spreads requests over several backends with
separate caches and ignores the hints. Nothing to fix in the proxy for this.
"""

import json
import os
import time
import urllib.error
import urllib.request
import uuid

NB_PREFS = os.environ.get('NB_PREFS', os.path.expanduser(
    '~/.netbeans/29/config/Preferences/io/github/nbclaudecodegui.properties'))
NB_PROFILE = os.environ.get('NB_PROFILE', 'WorkLlmRouter')
MODEL = os.environ.get('ROUTER_MODEL', 'z-ai/glm-5.3')
REPEATS = int(os.environ.get('REPEATS', '6'))


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
    raise SystemExit(f'profile {NB_PROFILE!r} not found')


PROFILE = load_profile()
URL = PROFILE['baseUrl'].rstrip('/') + '/v1/chat/completions'
OPENER = urllib.request.build_opener(urllib.request.ProxyHandler({}))  # router host is in noProxy


def system_prompt(tag):
    return f"[{tag}] You are a helpful coding assistant. " + " ".join(
        f"Rule {i}: always keep answers short, precise and correct, rule number {i}." for i in range(450))


def call(tag, key, headers_extra, body_extra, n):
    body = {"model": MODEL, "max_tokens": 8, "stream": False,
            "messages": [{"role": "system", "content": system_prompt(tag)},
                         {"role": "user", "content": f"Reply with just OK {n}"}]}
    if key:
        body["prompt_cache_key"] = key
    body.update(body_extra)
    headers = {"Content-Type": "application/json", "Authorization": "Bearer " + PROFILE['apiKey']}
    headers.update(headers_extra)
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), method='POST', headers=headers)
    try:
        resp = json.load(OPENER.open(req, timeout=180))
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}: {e.read()[:150].decode(errors='replace')}"
    u = resp.get('usage', {})
    cached = (u.get('prompt_tokens_details') or {}).get('cached_tokens', u.get('cached_tokens'))
    return cached, f"prompt={u.get('prompt_tokens')} cached={cached}"


if __name__ == '__main__':
    print(f"model={MODEL} url={URL.split('/v1')[0]} repeats={REPEATS}")
    variants = [
        ('nothing', False, {}, {}),
        ('prompt_cache_key', True, {}, {}),
        ('+x-session-id', True, {'x-session-id': '{k}'}, {}),
        ('+session_id', True, {'session_id': '{k}'}, {}),
        ('+user field', True, {}, {'user': '{k}'}),
        ('all hints', True, {'x-session-id': '{k}', 'session_id': '{k}'}, {'user': '{k}'}),
    ]
    for name, use_key, hdr, extra in variants:
        k = str(uuid.uuid4())
        hdr = {a: b.replace('{k}', k) for a, b in hdr.items()}
        extra = {a: b.replace('{k}', k) for a, b in extra.items()}
        hits = []
        for i in range(REPEATS):
            cached, text = call(k, k if use_key else None, hdr, extra, i)
            hits.append(cached or 0)
            print(f"{name} #{i}: {text}", flush=True)
            time.sleep(2)
        later = hits[1:]
        print(f"=> {name}: hits on {sum(1 for h in later if h)}/{len(later)} repeat requests\n", flush=True)
