"""
Investigate: when Claude shows the Ultraplan "Would you like to proceed?" menu
with option 4 "Tell Claude what to change", what byte sequence correctly:
  1. Selects option 4 and activates text-entry mode
  2. Submits the typed text back to Claude

Standard type-input menus use: digit → text → \r
But option 4 says "shift+tab to approve with this feedback",
suggesting shift+tab (\x1b[Z) might be the submit key.

Test variants:
  A. 4 → text → \r
  B. 4 → text → shift+tab (\x1b[Z)
"""

import pexpect
import re
import time
import sys
import os
import signal

CLAUDE = '/usr/local/bin/claude'
CWD = '/tmp'

SHIFT_TAB = '\x1b[Z'
ENTER = '\r'

MARKER = 'XMARKERX42'  # unique string to identify our feedback


def safe_buf(child):
    before = child.before if isinstance(child.before, str) else ''
    after = child.after if isinstance(child.after, str) else ''
    buf = child.buffer if isinstance(child.buffer, str) else ''
    return before + after + buf


def run_test(label, submit_key_name, submit_key, logpath, script_suffix):
    print(f'\n=== {label} ===', flush=True)
    logfile = open(logpath, 'w')
    child = pexpect.spawn(
        CLAUDE,
        ['--permission-mode', 'plan',
         f'Write a hello world Python script to /tmp/hello_plan_{script_suffix}.py'],
        cwd=CWD,
        encoding='utf-8',
        timeout=120,
        logfile=logfile,
    )
    try:
        # Wait for the plan-review menu
        print('  Waiting for plan-review menu (up to 120s)...', flush=True)
        child.expect(r'proceed\?', timeout=120)
        print('  "proceed?" detected', flush=True)

        # Drain remaining menu output (wait for menu to finish drawing)
        try:
            child.expect(pexpect.TIMEOUT, timeout=2.0)
        except pexpect.TIMEOUT:
            pass

        buf_plain = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', safe_buf(child))
        has4 = 'Tell' in buf_plain or '4.' in buf_plain
        print(f'  Option 4 visible: {has4}', flush=True)

        # Step 1: Send "4" to select option 4
        print(f'  Sending "4"...', flush=True)
        child.send('4')
        # Wait for Claude to respond (redraw menu or enter text mode)
        try:
            child.expect(pexpect.TIMEOUT, timeout=1.5)
        except pexpect.TIMEOUT:
            pass
        buf_after4 = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', safe_buf(child))
        print(f'  After "4" (plain): {repr(buf_after4[-200:])}', flush=True)

        # Step 2: Type the feedback
        test_text = f'{MARKER} add error handling'
        print(f'  Typing: {repr(test_text)}', flush=True)
        child.send(test_text)
        time.sleep(0.3)

        # Step 3: Submit with tested key
        print(f'  Submitting with {submit_key_name}...', flush=True)
        child.send(submit_key)

        # Wait for Claude to react — look for:
        # - New ❯ prompt (main prompt, not menu cursor)
        # - "proceed?" again (Claude re-planned with our feedback)
        # - "written" or file path (Claude executed with our feedback)
        # - timeout = no reaction
        print('  Waiting for reaction (up to 60s)...', flush=True)
        idx = child.expect(
            [r'❯\xa0', r'proceed\?', r'written|Written|hello_plan', pexpect.TIMEOUT],
            timeout=60
        )
        result_buf = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', safe_buf(child))
        if idx == 0:
            print(f'  RESULT: Main prompt appeared — Claude accepted feedback', flush=True)
        elif idx == 1:
            print(f'  RESULT: New plan-review menu — Claude re-planned with feedback', flush=True)
        elif idx == 2:
            print(f'  RESULT: Claude executed/wrote the script', flush=True)
        else:
            print(f'  RESULT: TIMEOUT — no recognizable reaction', flush=True)
        print(f'  Final excerpt: {repr(result_buf[-300:])}', flush=True)

    except pexpect.TIMEOUT as e:
        buf = re.sub(r'\x1b\[[0-9;]*[A-Za-z]', '', child.buffer or '')
        print(f'  OUTER TIMEOUT. Buffer: {repr(buf[-400:])}', flush=True)
    except pexpect.EOF:
        print('  EOF', flush=True)
    except Exception as e:
        print(f'  ERROR: {e}', flush=True)
    finally:
        try:
            os.kill(child.pid, signal.SIGTERM)
        except Exception:
            pass
        try:
            child.close()
        except Exception:
            pass
        logfile.close()


run_test('Test A: 4→text→\\r', 'enter', ENTER, '/tmp/pexpect_ultraplan_a.log', 'enter')
run_test('Test B: 4→text→shift+tab', 'shifttab', SHIFT_TAB, '/tmp/pexpect_ultraplan_b.log', 'shifttab')

print('\nDone.', flush=True)
