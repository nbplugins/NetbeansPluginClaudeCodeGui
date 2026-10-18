#!/usr/bin/env python3
"""
Capture the rendered footer (bottom screen lines) of the Claude Code TUI while
cycling edit modes with Shift+Tab, both when idle and while Claude is working.

Answers: which line is the bottom-most non-blank one in each mode, and does the
mode indicator always live on it?  ScreenContentDetector.detectEditMode() only
inspects that single line, so anything rendered below the mode indicator makes
the mode undetectable.

Cross-platform: uses pywinpty on Windows, the stdlib pty elsewhere, and pyte to
render the byte stream into a screen grid (the same view JediTerm gives the plugin).

    pip install pyte pywinpty     # pywinpty only on Windows
    python test_edit_mode_footer.py [--work]
"""

import os
import shutil
import sys
import tempfile
import threading
import time

import pyte

COLS, ROWS = 120, 40
SHIFT_TAB = '\x1b[Z'


class Term:
    def __init__(self, argv, cwd):
        env = dict(os.environ)
        env.pop('CLAUDECODE', None)
        env.pop('CLAUDE_CODE_ENTRYPOINT', None)
        env['TERM'] = 'xterm-256color'
        self.screen = pyte.Screen(COLS, ROWS)
        self.stream = pyte.Stream(self.screen)
        self.lock = threading.Lock()
        if os.name == 'nt':
            from winpty import PtyProcess
            self.proc = PtyProcess.spawn(argv, cwd=cwd, env=env, dimensions=(ROWS, COLS))
            self._read = lambda: self.proc.read(4096)
            self._write = self.proc.write
        else:
            import pty
            import subprocess
            master, slave = pty.openpty()
            import fcntl, struct, termios
            fcntl.ioctl(slave, termios.TIOCSWINSZ, struct.pack('HHHH', ROWS, COLS, 0, 0))
            self.proc = subprocess.Popen(argv, cwd=cwd, env=env, stdin=slave,
                                         stdout=slave, stderr=slave, close_fds=True)
            self._read = lambda: os.read(master, 4096).decode('utf-8', 'replace')
            self._write = lambda s: os.write(master, s.encode())
        threading.Thread(target=self._pump, daemon=True).start()

    def _pump(self):
        while True:
            try:
                data = self._read()
            except Exception:
                return
            if not data:
                time.sleep(0.01)
                continue
            with self.lock:
                self.stream.feed(data)

    def write(self, s):
        self._write(s)

    def lines(self):
        with self.lock:
            return [l.rstrip() for l in self.screen.display]

    def bottom(self, n=4):
        nb = [l for l in self.lines() if l.strip()]
        return nb[-n:]

    def wait_for(self, text, timeout=30):
        end = time.time() + timeout
        while time.time() < end:
            if any(text in l for l in self.lines()):
                return True
            time.sleep(0.1)
        return False

    def close(self):
        try:
            if os.name == 'nt':
                self.proc.terminate(force=True)
            else:
                self.proc.kill()
        except Exception:
            pass


def dump(term, label):
    print(f'--- {label}')
    for l in term.bottom():
        print('   |' + l)


def main():
    work = '--work' in sys.argv
    claude = shutil.which('claude')
    if claude is None:
        sys.exit('claude not found on PATH')
    cwd = tempfile.mkdtemp(prefix='cc-mode-footer-')
    term = Term([claude], cwd)
    try:
        if term.wait_for('Yes, I trust', 15):
            time.sleep(1)
            term.write('\x1b[B')   # default is "No, exit" — move to "Yes"
            time.sleep(0.5)
            term.write('\r')
        if not term.wait_for('shift+tab', 30) and not term.wait_for('for shortcuts', 5):
            dump(term, 'startup (no footer found)')
        time.sleep(2)
        dump(term, 'idle initial')
        for i in range(5):
            term.write(SHIFT_TAB)
            time.sleep(1.2)
            dump(term, f'idle after shift+tab #{i + 1}')

        if work:
            term.write('Count slowly from 1 to 40, one number per line, no tools.')
            time.sleep(0.5)
            term.write('\r')
            time.sleep(2.5)
            dump(term, 'working initial')
            for i in range(5):
                term.write(SHIFT_TAB)
                time.sleep(1.2)
                dump(term, f'working after shift+tab #{i + 1}')
            term.wait_for('40', 60)
            time.sleep(3)
            dump(term, 'idle after response')
    finally:
        term.close()


if __name__ == '__main__':
    main()
