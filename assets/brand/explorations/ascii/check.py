#!/usr/bin/env python3
"""check.py FILE.txt ... — the checks regen.sh runs on every shipped text file.

1. tools/asciicheck.py on each file. Block-element art is padded with plain spaces (a font-proof
   file must not end its rows with U+2800, which DejaVu Sans Mono and Consolas lack), and
   asciicheck's trailing-space rule fires on exactly that. So a file whose only trailing
   whitespace is space padding is checked through a copy with that padding mapped to U+2800:
   the ragged / wide / odd-character rules still apply, the padding rule is reported as 'space
   padded' instead of as a defect. Files padded with U+2800 (braille) are checked as they are.
2. *ascii*.txt must be 7-bit (client/huginn.sh and huginn.ps1 are ASCII-only, CONTRIBUTING.md).
3. A real terminal layer: each row is printed inside a detached tmux (UTF-8, 200 columns) and
   the cursor column is read back; it must equal the row's length, so every character took
   exactly one cell: nothing wide, nothing zero-width, nothing wrapped or swallowed. (A font
   check this is not: tmux has no glyphs.)
"""
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
ASCIICHECK = os.path.normpath(os.path.join(HERE, '..', 'tools', 'asciicheck.py'))
BLANK = '⠀'


def asciicheck(path):
    r = subprocess.run([sys.executable, ASCIICHECK, path], capture_output=True, text=True)
    return r.returncode == 0, r.stdout.strip()


def space_padded_copy(text):
    """Map trailing spaces to U+2800 so asciicheck can judge the rest; None if there are none."""
    lines = text.split('\n')
    if not any(l != l.rstrip(' ') for l in lines):
        return None
    out = []
    for l in lines:
        core = l.rstrip(' ')
        out.append(core + BLANK * (len(l) - len(core)))
    return '\n'.join(out)


def tmux_roundtrip(path, sock='huginn-check'):
    """Print each row inside a detached tmux and read back the cursor column: tmux's own
    wcwidth verdict on every cell. A wide glyph, a zero-width one or a wrapped row all move
    the cursor somewhere other than len(row)."""
    env = dict(os.environ, LANG='C.UTF-8', LC_ALL='C.UTF-8')   # tmux must run in a UTF-8 locale
    T = ['tmux', '-L', sock]
    text = open(path, encoding='utf-8').read().rstrip('\n')
    rows = text.split('\n')                     # padding included: the script prints it too
    script = os.path.join(tempfile.mkdtemp(prefix='tmuxcheck-'), 'run.sh')
    with open(script, 'w', encoding='utf-8') as f:
        f.write('while IFS= read -r line; do printf "%s" "$line"; tmux -L SOCK wait-for -S line; '
                'tmux -L SOCK wait-for next; printf "\\r\\n"; done < FILE; sleep 60\n'
                .replace('SOCK', sock).replace('FILE', repr(os.path.abspath(path))))
    subprocess.run(T + ['kill-server'], capture_output=True, env=env)
    subprocess.run(T + ['-u', 'new-session', '-d', '-x', '200', '-y', '8', 'sh ' + script], check=True, env=env)
    try:
        for i, row in enumerate(rows):
            subprocess.run(T + ['wait-for', 'line'], check=True, env=env, timeout=10)
            x = subprocess.run(T + ['display', '-p', '#{cursor_x}'], capture_output=True, text=True, env=env).stdout.strip()
            if x != str(len(row)):
                return False, f'row {i + 1}: {len(row)} chars but tmux put the cursor at column {x}: {row!r}'
            subprocess.run(T + ['wait-for', '-S', 'next'], check=True, env=env)
        return True, ''
    except subprocess.TimeoutExpired:
        return False, 'tmux handshake timed out'
    finally:
        subprocess.run(T + ['kill-server'], capture_output=True, env=env)


def main(paths):
    bad = 0
    padded = 0
    for p in paths:
        text = open(p, encoding='utf-8').read()
        copy = space_padded_copy(text)
        if copy is not None:
            with tempfile.NamedTemporaryFile('w', suffix='.txt', delete=False, encoding='utf-8') as f:
                f.write(copy)
            ok, msg = asciicheck(f.name)
            os.unlink(f.name)
            msg = msg.replace(f.name, p)
            padded += 1
        else:
            ok, msg = asciicheck(p)
        if not ok:
            print(msg)
            bad += 1
        if 'ascii' in os.path.basename(p) and re.search(r'[^\x00-\x7f]', text):
            print(f'{p}: NOT 7-bit ASCII')
            bad += 1
        ok, msg = tmux_roundtrip(p)
        if not ok:
            print(f'{p}: tmux: {msg}')
            bad += 1
    print(f'check.py: {len(paths)} files, {padded} space-padded block files, {bad} problems')
    return 1 if bad else 0


if __name__ == '__main__':
    sys.exit(main(sys.argv[1:]))
