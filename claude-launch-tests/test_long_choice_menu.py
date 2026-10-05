"""
Hypotheses for long (scrolling) choice menus, using /model in a small terminal:
  A. Down/Up arrows move the cursor and scroll the window.
  B. Down on the last item wraps to the first one (cyclic navigation).
  C. (observed manually) a digit key selects its item immediately -> two-digit numbers cannot be typed.
  D. Arrow navigation + Enter selects the intended item (checked via cursor number only, Esc to cancel).

Screens are rendered with pexpect's ANSI emulator and dumped.
"""
import os
import re
import sys
import time

import pexpect
import pyte

CLAUDE = os.environ.get('CLAUDE_BIN', '/usr/local/bin/claude')
ROWS, COLS = 16, 120
DOWN, UP, ESC = '\x1b[B', '\x1b[A', '\x1b'
CURSOR = re.compile(r'❯\s*(\d+)\.')


class Term:
    def __init__(self):
        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.Stream(self.screen)


def pump(child, term, secs=0.6):
    end = time.time() + secs
    while time.time() < end:
        try:
            term.stream.feed(child.read_nonblocking(65536, timeout=0.1))
        except pexpect.TIMEOUT:
            pass


def lines(term):
    return [l.rstrip() for l in term.screen.display]


def cursor_num(screen):
    for l in lines(screen):
        m = CURSOR.search(l)
        if m:
            return int(m.group(1))
    return None


def dump(title, screen):
    print(f'--- {title} ---')
    for l in lines(screen):
        if l.strip():
            print(l)


def main():
    child = pexpect.spawn(CLAUDE, cwd='/tmp', dimensions=(ROWS, COLS), encoding='utf-8', codec_errors='replace',
                          env=dict(os.environ, TERM='xterm-256color'))
    screen = Term()
    pump(child, screen, 12)
    time.sleep(1.5)
    child.send('/model'); pump(child, screen, 0.3)
    child.send(ESC); pump(child, screen, 0.3)
    child.send('\r'); pump(child, screen, 1.5)
    dump('menu opened', screen)
    start = cursor_num(screen)
    ok = True

    # A: scrolling downward
    seen = [start]
    for _ in range(14):
        child.send(DOWN); pump(child, screen, 0.25)
        seen.append(cursor_num(screen))
    dump('after 14 Down', screen)
    print('cursor sequence on Down:', seen)

    # B: wrap-around is visible in the sequence (max -> 1)
    nums = [n for n in seen if n]
    wrapped = any(b < a for a, b in zip(nums, nums[1:]))
    print('B wrap-around on Down:', wrapped)

    # D: Up navigation
    child.send(UP); pump(child, screen, 0.3)
    print('cursor after one Up:', cursor_num(screen))

    # C (not automated): a digit selects immediately and SAVES the model as default,
    # so '10' cannot be typed - observed manually: '1' applied item 1 at once.

    child.send(ESC); pump(child, screen, 0.5)
    child.close(force=True)
    print('RESULT: scrolling=%s wrap=%s' % (len(set(nums)) > 1, wrapped))
    return 0 if len(set(nums)) > 1 else 1


if __name__ == '__main__':
    sys.exit(main())
