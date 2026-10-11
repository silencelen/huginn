#!/usr/bin/env python3
"""lockup.py — combine a terminal-art raven with a figlet wordmark.

  lockup.py --raven FILE --font ansi_shadow [--text HUGINN] [--gap 3] [--align bottom|center|top]
            [--stack] [--tagline TEXT] [--out FILE]

Side by side (default): the raven sits left of the wordmark, aligned to its bottom row.
--stack puts the raven above the wordmark, centred. --tagline adds a plain-text line under
the wordmark (left-aligned to it). Lines are padded with plain spaces to a rectangle (--pad
for another character, e.g. U+2800 for a braille raven). pagga's ░ fillers become spaces.
"""
import argparse
import sys

import pyfiglet

BRAILLE_BLANK = '⠀'
PAD = ' '


def figlet(text, font):
    lines = [l.rstrip() for l in pyfiglet.figlet_format(text, font=font).split('\n')]
    while lines and lines[-1] == '':
        lines.pop()
    while lines and lines[0] == '':
        lines.pop(0)
    if font == 'pagga':
        lines = [l.replace('░', ' ') for l in lines]
        lead = min(len(l) - len(l.lstrip(' ')) for l in lines)
        lines = [l[lead:] for l in lines]
    w = max(len(l) for l in lines)
    return [l.ljust(w) for l in lines]


def load(path):
    lines = open(path, encoding='utf-8').read().rstrip('\n').split('\n')
    lines = [l.replace(BRAILLE_BLANK, ' ') for l in lines]
    w = max(len(l) for l in lines)
    return [l.ljust(w) for l in lines]


def pad_block(lines):
    w = max(len(l) for l in lines)
    out = []
    for l in lines:
        l = l.ljust(w)
        core = l.rstrip(' ')
        out.append(core + PAD * (w - len(core)))
    return out


def side_by_side(raven, word, gap, align):
    rw = len(raven[0])
    h = max(len(raven), len(word))
    if align == 'bottom':
        rtop, wtop = h - len(raven), h - len(word)
    elif align == 'top':
        rtop, wtop = 0, 0
    else:
        rtop, wtop = (h - len(raven)) // 2, (h - len(word)) // 2
    out = []
    for i in range(h):
        r = raven[i - rtop] if 0 <= i - rtop < len(raven) else ' ' * rw
        w = word[i - wtop] if 0 <= i - wtop < len(word) else ''
        out.append(r + ' ' * gap + w)
    return out


def stack(raven, word, gap, tagline=None):
    w = max(len(raven[0]), len(word[0]), len(tagline or ''))
    out = []
    for l in raven:
        left = (w - len(l)) // 2
        out.append(' ' * left + l)
    out.extend([''] * gap)
    for l in word:
        left = (w - len(l)) // 2
        out.append(' ' * left + l)
    if tagline:
        out.append(' ' * ((w - len(tagline)) // 2) + tagline)
    return out


def main():
    global PAD
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('--raven', required=True)
    ap.add_argument('--font', default='ansi_shadow')
    ap.add_argument('--text', default='HUGINN')
    ap.add_argument('--gap', type=int, default=3)
    ap.add_argument('--align', choices=['bottom', 'center', 'top'], default='bottom')
    ap.add_argument('--stack', action='store_true')
    ap.add_argument('--tagline')
    ap.add_argument('--pad', default=' ', help='trailing blank (default: space)')
    ap.add_argument('--out')
    a = ap.parse_args()
    PAD = a.pad

    raven = load(a.raven)
    word = figlet(a.text, a.font)
    if a.stack:
        lines = stack(raven, word, a.gap, a.tagline)
    else:
        lines = side_by_side(raven, word, a.gap, a.align)
        if a.tagline:
            lines.append(' ' * (len(raven[0]) + a.gap) + a.tagline)
    # a stacked lockup has a blank line between raven and wordmark: pad each run separately
    blocks, cur = [], []
    for l in lines:
        if l.strip() == '':
            if cur:
                blocks.append(cur)
            cur = []
        else:
            cur.append(l)
    if cur:
        blocks.append(cur)
    width = max(len(l) for b in blocks for l in b)
    text = '\n\n'.join('\n'.join(pad_block([l.ljust(width) for l in b])) for b in blocks) + '\n'
    if a.out:
        open(a.out, 'w', encoding='utf-8').write(text)
        print(f'{a.out}: {len(lines)} rows x {width} cols', file=sys.stderr)
    else:
        sys.stdout.write(text)


if __name__ == '__main__':
    main()
