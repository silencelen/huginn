#!/usr/bin/env python3
"""asciicheck.py <file.txt>... — every line in a block must be the same display width.
Blocks are separated by blank lines. Flags East-Asian-wide chars (break terminals),
tabs, trailing spaces, and chars outside: ASCII, box drawing, block elements, braille,
geometric shapes, arrows, latin-1 punctuation."""
import sys, unicodedata
OK_RANGES = [(0x20,0x7E),(0xA1,0xFF),(0x2500,0x259F),(0x25A0,0x25FF),(0x2800,0x28FF),(0x2190,0x21FF),(0x2010,0x2027),(0x16A0,0x16FF)]
def ok(c):
    o = ord(c)
    return any(a <= o <= b for a,b in OK_RANGES)
rc = 0
for path in sys.argv[1:]:
    text = open(path, encoding='utf-8').read().split('\n')
    block, start = [], 1
    def flush(block, start):
        global rc
        if not block: return
        widths = {len(l) for l in block}
        if len(widths) > 1:
            print(f"{path}:{start}: ragged block, widths {sorted(widths)}"); rc = 1
    for i, line in enumerate(text, 1):
        if line.strip() == '':
            flush(block, start); block, start = [], i+1; continue
        block.append(line)
        if '\t' in line: print(f"{path}:{i}: tab"); rc = 1
        if line != line.rstrip(): print(f"{path}:{i}: trailing space"); rc = 1
        for c in line:
            if unicodedata.east_asian_width(c) in ('W','F'):
                print(f"{path}:{i}: wide char {c!r} U+{ord(c):04X}"); rc = 1
            elif not ok(c):
                print(f"{path}:{i}: unusual char {c!r} U+{ord(c):04X} ({unicodedata.name(c,'?')})"); rc = 1
    flush(block, start)
print("ok" if rc == 0 else "problems found")
sys.exit(rc)
