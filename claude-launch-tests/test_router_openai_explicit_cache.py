"""
Does the OpenAI-compatible router (llm-router, profile WorkLlmRouter) accept and
benefit from OpenAI's caching fields on Chat Completions, as the plugin sends them
when "Explicit Cache" is checked for a model?
Variants per model (same ~8.5k-token request repeated N times, unique prompt per variant):
- prompt_cache_key only (the plugin's default)
- explicit: prompt_cache_options {mode:explicit, ttl:30m} + prompt_cache_breakpoint on the
  system content part (GPT >= 5.6 shape from AnthropicToOpenAITranslator)
- retention: prompt_cache_retention "24h" (older GPT shape)
API key is read from the NetBeans profile and never printed.

Run: python3 claude-launch-tests/test_router_openai_explicit_cache.py
Env: NB_PREFS, NB_PROFILE (WorkLlmRouter), ROUTER_MODELS (comma list, default
     openai/gpt-6-luna,openai/gpt-5.5), REPEATS (6)

Result (2026-10-06, openai/gpt-6-luna via llm-router = LiteLLM -> OpenRouter, 6 x 8.1k-token
identical requests per variant): the router accepts all three field sets (no 400) but none
gives stable hits.
- key-only:   hits on 2/5 repeats (hits on #3, #4)
- explicit:   0/5 (one hit on the very first request, then none) — NOT better than key-only
- retention:  2/5 (hits on #2, #4)
openai/gpt-5.5 could not be measured: HTTP 500 (OpenRouter error) / 429 (no deployments).
Conclusion: the router's cache is random regardless of the OpenAI caching fields.
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
MODELS = os.environ.get('ROUTER_MODELS', 'openai/gpt-6-luna,openai/gpt-5.5').split(',')
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


def system_text(tag):
    return f"[{tag}] You are a helpful coding assistant. " + " ".join(
        f"Rule {i}: always keep answers short, precise and correct, rule number {i}." for i in range(450))


def call(model, variant, key, tag, n):
    text = system_text(tag)
    body = {"model": model, "max_tokens": 8, "stream": False, "prompt_cache_key": key}
    if variant == 'explicit':
        body["prompt_cache_options"] = {"mode": "explicit", "ttl": "30m"}
        sysmsg = {"role": "system", "content": [{"type": "text", "text": text,
                                                 "prompt_cache_breakpoint": {"mode": "explicit"}}]}
    else:
        sysmsg = {"role": "system", "content": text}
        if variant == 'retention':
            body["prompt_cache_retention"] = "24h"
    body["messages"] = [sysmsg, {"role": "user", "content": f"Reply with just OK {n}"}]
    req = urllib.request.Request(URL, data=json.dumps(body).encode(), method='POST', headers={
        "Content-Type": "application/json", "Authorization": "Bearer " + PROFILE['apiKey']})
    try:
        resp = json.load(OPENER.open(req, timeout=180))
    except urllib.error.HTTPError as e:
        return None, f"HTTP {e.code}: {e.read()[:160].decode(errors='replace')}"
    u = resp.get('usage', {})
    d = u.get('prompt_tokens_details') or {}
    cached = d.get('cached_tokens', u.get('cached_tokens'))
    return cached, f"prompt={u.get('prompt_tokens')} cached={cached} cache_write={d.get('cache_write_tokens')}"


if __name__ == '__main__':
    print(f"url={URL.split('/v1')[0]} repeats={REPEATS}", flush=True)
    for model in MODELS:
        for variant in ('key-only', 'explicit', 'retention'):
            k = str(uuid.uuid4())
            hits = []
            for i in range(REPEATS):
                cached, text = call(model, variant, k, k, i)
                print(f"{model} {variant} #{i}: {text}", flush=True)
                if cached is None and text.startswith('HTTP'):
                    break
                hits.append(cached or 0)
                time.sleep(2)
            later = hits[1:]
            print(f"=> {model} {variant}: hits on {sum(1 for h in later if h)}/{len(later)} repeats\n", flush=True)
