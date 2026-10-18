#!/usr/bin/env python3
"""grid2png.py — a pixel grid as a PNG at an INTEGER scale, padded (never resampled) to a size.

    python3 grid2png.py grid.txt name out.png --size N [--scale K] [--ground bone|ink|none] [--ink bone|ink]

The largest integer K with K*grid <= N is used unless --scale is given; the drawing is centred
and the rest is padding. This is how the pixel raven survives the sizes that are not integer
multiples of its grid: Android launcher 48/72/96/144/192 and adaptive-foreground
108/162/216/324/432, a 125%/150% desktop tray (20/24 px), Windows .ico 48 and 256.
Ground: 'none' (transparent; the default: notification, tray, adaptive foreground layer),
'bone' (opaque bone tile, square corners; round them in the container), 'ink' (inverse).
No dependencies beyond Python 3 (writes the PNG with zlib directly).
"""
import sys, zlib, struct
sys.path.insert(0, __file__.rsplit('/', 1)[0] if '/' in __file__ else '.')
from grid2text import load

RGBA = {'ink': (0x16, 0x13, 0x10, 255), 'bone': (0xE8, 0xE2, 0xDA, 255), 'none': (0, 0, 0, 0)}

def png(w, h, px):
    raw = b''.join(b'\0' + b''.join(bytes(px[y][x]) for x in range(w)) for y in range(h))
    def chunk(t, d): return struct.pack('>I', len(d)) + t + d + struct.pack('>I', zlib.crc32(t + d) & 0xffffffff)
    return (b'\x89PNG\r\n\x1a\n' + chunk(b'IHDR', struct.pack('>IIBBBBB', w, h, 8, 6, 0, 0, 0))
            + chunk(b'IDAT', zlib.compress(raw, 9)) + chunk(b'IEND', b''))

def main():
    a = sys.argv[1:]
    path, name, out = a[0], a[1], a[2]
    size = int(a[a.index('--size') + 1])
    ground = RGBA[a[a.index('--ground') + 1]] if '--ground' in a else RGBA['none']
    ink = RGBA[a[a.index('--ink') + 1]] if '--ink' in a else RGBA['ink']
    rows = load(path)[name]
    g = len(rows)
    k = int(a[a.index('--scale') + 1]) if '--scale' in a else max(1, size // g)
    if k * g > size: sys.exit(f'{name} at {k}x is {k*g} px, larger than {size}')
    off = (size - k * g) // 2
    px = [[ground] * size for _ in range(size)]
    for y, r in enumerate(rows):
        for x, c in enumerate(r):
            if c == '#':
                for dy in range(k):
                    for dx in range(k):
                        px[off + y * k + dy][off + x * k + dx] = ink
    open(out, 'wb').write(png(size, size, px))
    print(f'{out}: {name} at {k}x ({k*g} px) in {size}, pad {off}')

if __name__ == '__main__':
    main()
