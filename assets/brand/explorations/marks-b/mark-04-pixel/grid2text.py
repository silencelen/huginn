#!/usr/bin/env python3
"""grid2text.py — turn a pixel grid ('#' ink, '.' ground) into terminal art.

    python3 grid2text.py halfblock grid.txt [name] [--frame]   # 2 rows per line: ' ▀ ▄ █'   (Unicode)
    python3 grid2text.py braille   grid.txt [name] [--frame]   # 2x4 px per cell: U+2800..28FF (Unicode)
    python3 grid2text.py ascii     grid.txt [name] [--frame] [--chars '#"_']
                                                              # 2 rows per line, pure 7-bit ASCII
    python3 grid2text.py list      grid.txt                    # names of the grids in the file

Which form goes where (the CLI clients huginn.sh / huginn.ps1 must stay 7-bit ASCII):
  ascii      CLI clients, anything shipped over scp to PowerShell 5.1.
  halfblock  terminals with line-height 1.0 only (tmux pane, huginn-status, the apps'
             terminal view). Web code blocks add line spacing and the rows separate.
  braille    README, docs, web code blocks, tmux: survives line spacing, transparent ground.

--frame wraps the block in a thin frame (box drawing, or +-| in ascii mode). Half-block and
ascii art pad their ground with spaces, so an unframed block has trailing spaces on short
lines; the frame is how such a banner satisfies both terminal rules (equal widths, no trailing
whitespace). Braille pads with U+2800 (blank braille) and needs no frame.

--chars gives the three ascii glyphs for both/top/bottom (default '#"_': a full cell, a cell
whose top half is ink, a cell whose bottom half is ink).

grid.txt holds one or more named grids: a line '@name WxH' followed by H rows of W characters.
Rows are padded/truncated to W so every output line has the same display width (the brief's
terminal-safe rule). Only single-width characters are emitted. No dependencies beyond Python 3.
"""
import sys

def load(path):
    grids, name, rows, w, h = {}, None, [], 0, 0
    for raw in open(path, encoding='utf-8'):
        line = raw.rstrip('\n')
        if line.startswith('@'):
            if name: grids[name] = fix(rows, w, h)
            parts = line[1:].split()
            name = parts[0]; w, h = (int(x) for x in parts[1].lower().split('x'))
            rows = []
        elif name is not None and line and not line.startswith('//'):
            rows.append(line)
    if name: grids[name] = fix(rows, w, h)
    return grids

def fix(rows, w, h):
    rows = [(r + '.' * w)[:w] for r in rows[:h]]
    while len(rows) < h: rows.append('.' * w)
    return rows

def ink(rows, x, y):
    return 0 <= y < len(rows) and 0 <= x < len(rows[y]) and rows[y][x] == '#'

def pairs(rows, both, top, bot, none=' '):
    out = []
    for y in range(0, len(rows), 2):
        s = ''
        for x in range(len(rows[0])):
            t, b = ink(rows, x, y), ink(rows, x, y + 1)
            s += both if t and b else top if t else bot if b else none
        out.append(s)
    return out

def halfblock(rows):
    return pairs(rows, '█', '▀', '▄')

def ascii(rows, chars='#"_'):
    return pairs(rows, chars[0], chars[1], chars[2])

# braille dot bit order (x, y) -> bit
_BITS = {(0,0):0x01,(0,1):0x02,(0,2):0x04,(1,0):0x08,(1,1):0x10,(1,2):0x20,(0,3):0x40,(1,3):0x80}

def braille(rows):
    out = []
    for y in range(0, len(rows), 4):
        s = ''
        for x in range(0, len(rows[0]), 2):
            v = 0
            for (dx, dy), bit in _BITS.items():
                if ink(rows, x + dx, y + dy): v |= bit
            s += chr(0x2800 + v)
        out.append(s)
    return out

def frame(lines, plain=False):
    w = len(lines[0])
    if plain:
        return ['+' + '-' * w + '+'] + ['|' + l + '|' for l in lines] + ['+' + '-' * w + '+']
    return ['┌' + '─' * w + '┐'] + ['│' + l + '│' for l in lines] + ['└' + '─' * w + '┘']

def main():
    argv = sys.argv[1:]
    chars = argv[argv.index('--chars') + 1] if '--chars' in argv else '#"_'
    if '--chars' in argv: del argv[argv.index('--chars'):argv.index('--chars') + 2]
    args = [a for a in argv if not a.startswith('--')]
    if len(args) < 2: print(__doc__); sys.exit(2)
    mode, path = args[0], args[1]
    grids = load(path)
    if mode == 'list':
        for k, v in grids.items(): print(k, f'{len(v[0])}x{len(v)}')
        return
    name = args[2] if len(args) > 2 else next(iter(grids))
    rows = grids[name]
    if mode == 'halfblock': lines = halfblock(rows)
    elif mode == 'braille': lines = braille(rows)
    elif mode == 'ascii': lines = ascii(rows, chars)
    else: print(__doc__); sys.exit(2)
    if '--frame' in argv: lines = frame(lines, plain=(mode == 'ascii'))
    print('\n'.join(lines))

if __name__ == '__main__':
    main()
