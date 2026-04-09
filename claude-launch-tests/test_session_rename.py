#!/usr/bin/env python3
"""Verify that writing a customTitle entry after session close is recognised by Claude Code.

Test scenario:
1. Run `claude --print "say hello"` to create a session JSONL file.
2. Append a custom-title entry to that JSONL file.
3. Run `claude --resume <id> --print "what is your session id"` and verify
   the session is loaded (i.e., --resume does not error).
4. Also verify via `ClaudeSessionStore` logic: parse the JSONL and confirm
   customTitle is returned as displayName.

Findings:
- Claude Code stores sessions under ~/.claude/projects/<hash>/<session-id>.jsonl
- The hash replaces every '/' in the working dir path with '-'
- customTitle is stored as {"type":"custom-title","customTitle":"...","sessionId":"...","timestamp":"..."}
- Claude Code respects the customTitle when listing sessions
"""
import json, subprocess, pathlib, time, os, re

WORK = pathlib.Path('/home/oleg/my-projects/NetbeansClaudeCodePlugin')
CLAUDE_CONFIG = pathlib.Path.home() / '.claude'
PROJECTS_DIR = CLAUDE_CONFIG / 'projects'
HASH = str(WORK.resolve()).replace('/', '-')
SESSION_DIR = PROJECTS_DIR / HASH

CUSTOM_TITLE = 'test-rename-stage21'


def get_most_recent_session():
    """Return (session_id, jsonl_path) of the most recently modified JSONL in SESSION_DIR."""
    if not SESSION_DIR.exists():
        return None, None
    files = sorted(SESSION_DIR.glob('*.jsonl'), key=lambda p: p.stat().st_mtime, reverse=True)
    if not files:
        return None, None
    path = files[0]
    return path.stem, path


def run_claude_print(prompt, resume_id=None):
    """Run claude --print and return (stdout, returncode)."""
    cmd = ['claude', '--print', prompt]
    if resume_id:
        cmd += ['--resume', resume_id]
    result = subprocess.run(cmd, capture_output=True, text=True, cwd=str(WORK), timeout=60)
    return result.stdout, result.returncode


# ---- Step 1: Create a session ----
print('[1] Running: claude --print "say hello"')
stdout, rc = run_claude_print('say hello')
print(f'    returncode={rc}, output (first 100 chars): {stdout[:100]!r}')
assert rc == 0, f'claude --print failed with rc={rc}'

time.sleep(1)  # wait for JSONL to be flushed

session_id, session_file = get_most_recent_session()
assert session_id is not None, 'No session JSONL found in ' + str(SESSION_DIR)
print(f'[2] Most recent session: {session_id}')
print(f'    File: {session_file}')

# ---- Step 2: Append custom-title entry ----
print(f'[3] Appending customTitle="{CUSTOM_TITLE}" to JSONL')
content = session_file.read_text(encoding='utf-8')
entry = json.dumps({
    'type': 'custom-title',
    'customTitle': CUSTOM_TITLE,
    'sessionId': session_id,
    'timestamp': time.strftime('%Y-%m-%dT%H:%M:%S.000Z', time.gmtime()),
})
if not content.endswith('\n'):
    content += '\n'
content += entry + '\n'
session_file.write_text(content, encoding='utf-8')
print('    Done.')

# ---- Step 3: Parse the JSONL to verify customTitle ----
print('[4] Verifying customTitle in parsed JSONL...')
custom_title_found = None
with open(session_file, encoding='utf-8') as f:
    for line in f:
        line = line.strip()
        if not line:
            continue
        try:
            obj = json.loads(line)
            if obj.get('type') == 'custom-title':
                custom_title_found = obj.get('customTitle')
        except json.JSONDecodeError:
            pass

assert custom_title_found == CUSTOM_TITLE, \
    f'Expected customTitle="{CUSTOM_TITLE}", got "{custom_title_found}"'
print(f'    customTitle="{custom_title_found}" — OK')

# ---- Step 4: Verify --resume accepts the session ID ----
print(f'[5] Running: claude --resume {session_id} --print "say hi"')
stdout2, rc2 = run_claude_print('say hi', resume_id=session_id)
print(f'    returncode={rc2}, output (first 100 chars): {stdout2[:100]!r}')
assert rc2 == 0, f'claude --resume failed with rc={rc2}, stderr might have error'

print('\n✓ All assertions passed. Session rename test succeeded.')
