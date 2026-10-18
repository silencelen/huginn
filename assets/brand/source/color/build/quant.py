#!/usr/bin/env python3
"""quant.py <in.png> <out.png> <direction-id>

Palette-quantise a preview to 256 colours WITHOUT moving any token colour: every
hex in the direction's spec is pinned into the palette, the rest of the 256 slots
go to the most frequent colours in the render (the flat areas), and pixels snap
to the nearest palette entry with no dithering. ImageMagick's -colors 256 shifted
even #FFFFFF, which is not acceptable on a sheet whose job is to show exact hex.
Afterwards it verifies that every token present in the input is still present.
"""
import os
import sys

import numpy as np
from PIL import Image

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from spec import DIRECTIONS  # noqa: E402


def tokens_of(d):
    out = set()
    for s in ('dark', 'light', 'syntax_dark', 'syntax_light', 'ansi'):
        out.update(d[s].values())
    return sorted(out)


def main(src, dst, did):
    d = next(x for x in DIRECTIONS if x['id'] == did)
    pinned = [tuple(int(h[i:i + 2], 16) for i in (1, 3, 5)) for h in tokens_of(d)]
    im = Image.open(src).convert('RGB')
    freq = sorted(im.getcolors(1 << 24), reverse=True)
    pal = list(dict.fromkeys(pinned))
    for n, c in freq:
        if len(pal) >= 256:
            break
        if c not in pal:
            pal.append(c)
    # Exact nearest-colour mapping (PIL's own palette conversion rounds the
    # lookup and moved #E4E2DC to #E3E1DB): map each unique colour of the render
    # to its nearest palette entry, then index the whole image through that.
    arr = np.asarray(im)
    flat_px = arr.reshape(-1, 3)
    uniq, inv = np.unique(flat_px, axis=0, return_inverse=True)
    palarr = np.array(pal, dtype=np.int64)
    nearest = np.empty(len(uniq), dtype=np.uint8)
    for i in range(0, len(uniq), 4096):
        chunk = uniq[i:i + 4096].astype(np.int64)
        dist = ((chunk[:, None, :] - palarr[None, :, :]) ** 2).sum(axis=2)
        nearest[i:i + 4096] = dist.argmin(axis=1)
    idx = nearest[inv.reshape(-1)].reshape(arr.shape[0], arr.shape[1])
    q = Image.fromarray(idx, mode='P')
    flat = []
    for c in pal:
        flat.extend(c)
    flat += [0, 0, 0] * (256 - len(pal))
    q.putpalette(flat)
    q.save(dst, optimize=True)
    before = {c for _, c in im.getcolors(1 << 24)}
    after = {c for _, c in Image.open(dst).convert('RGB').getcolors(1 << 24)}
    lost = [c for c in pinned if c in before and c not in after]
    if lost:
        print('LOST token colours:', ['#%02X%02X%02X' % c for c in lost])
        sys.exit(1)
    print(f'{dst}: {os.path.getsize(dst) // 1024} KB, {len(after)} colours, all {len([c for c in pinned if c in before])} present tokens exact')


if __name__ == '__main__':
    main(*sys.argv[1:4])
