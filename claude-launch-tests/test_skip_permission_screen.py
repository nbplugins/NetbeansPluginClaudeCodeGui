#!/usr/bin/env python3
"""
Capture the screen text when Claude is launched with --dangerously-skip-permissions.
Prints the bottom status lines so we know what to detect.
"""

import pexpect
import time

CLAUDE = '/usr/local/bin/claude'
CWD = '/tmp'


def bottom_lines(text: str, n: int = 5) -> list:
    lines = text.splitlines()
    non_blank = [l.rstrip() for l in lines if l.strip()]
    return non_blank[-n:] if len(non_blank) >= n else non_blank


child = pexpect.spawn(
    CLAUDE, ['--dangerously-skip-permissions'],
    cwd=CWD, encoding='utf-8', timeout=30,
    logfile=open('/tmp/test_skip_screen.log', 'w')
)

try:
    idx = child.expect([r'shift\+tab', r'❯', r'bypass', r'skip', pexpect.TIMEOUT], timeout=20)
    print(f"Matched index: {idx}", flush=True)
    time.sleep(1.0)
    child.expect([pexpect.TIMEOUT], timeout=1.0)
    buf = child.before
    print("=== Bottom lines after ready ===")
    for line in bottom_lines(buf, 8):
        print("  " + repr(line))
finally:
    child.close(force=True)
