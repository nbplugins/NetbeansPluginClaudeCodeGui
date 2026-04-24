#!/usr/bin/env python3
"""
Discover the screen text for the "bypass permissions" (4th) edit mode.

Claude Code cycles edit modes via Shift+Tab (ESC[Z):
  default → plan → acceptEdits → bypassPermissions → default → ...

This test:
1. Launches Claude interactively.
2. Waits for the READY state.
3. Sends Shift+Tab up to 5 times, printing bottom 3 lines after each press.
4. Exits after all presses so you can see every status-line variant.

Usage:
    cd claude-launch-tests && python3 test_skip_permission_mode.py

Expected output: bottom status lines for each mode, including the
4th "bypass permissions" mode text — use it to update ScreenContentDetector.
"""

import pexpect
import time
import sys

CLAUDE = '/usr/local/bin/claude'
CWD = '/tmp'
SHIFT_TAB = '\x1b[Z'


def bottom_lines(text: str, n: int = 5) -> list[str]:
    """Return the last n non-blank lines from terminal output."""
    lines = text.splitlines()
    non_blank = [l.rstrip() for l in lines if l.strip()]
    return non_blank[-n:] if len(non_blank) >= n else non_blank


def main():
    print("Launching Claude to discover bypass-permissions mode screen text...", flush=True)
    child = pexpect.spawn(
        CLAUDE, [],
        cwd=CWD, encoding='utf-8', timeout=40,
        logfile=open('/tmp/test_skip_perm_mode.log', 'w')
    )

    try:
        # Wait for READY: input cursor ❯ or shift+tab text
        print("Waiting for Claude READY state...", flush=True)
        idx = child.expect([r'shift\+tab', r'❯', pexpect.TIMEOUT], timeout=30)
        if idx == 2:
            print("FAIL: Claude did not reach READY state", flush=True)
            print("Last output:", repr(child.before[-500:]), flush=True)
            sys.exit(1)

        time.sleep(0.8)
        # Read remaining buffered output
        child.expect([pexpect.TIMEOUT], timeout=1.0)
        buf = child.before
        print("\n=== INITIAL STATE ===", flush=True)
        for line in bottom_lines(buf):
            print("  " + repr(line), flush=True)

        # Cycle through modes via Shift+Tab
        for i in range(1, 6):
            print(f"\n=== Shift+Tab #{i} ===", flush=True)
            child.send(SHIFT_TAB)
            time.sleep(0.5)

            # Read whatever came out
            child.expect([pexpect.TIMEOUT], timeout=0.8)
            buf = child.before
            lines = bottom_lines(buf)
            if lines:
                for line in lines:
                    print("  " + repr(line), flush=True)
            else:
                print("  (no output)", flush=True)

        print("\nDone. Check lines above for the bypass-permissions status line text.", flush=True)

    except pexpect.EOF:
        print("EOF — Claude exited unexpectedly", flush=True)
    except Exception as e:
        print(f"Error: {e}", flush=True)
    finally:
        child.close(force=True)


if __name__ == '__main__':
    main()
