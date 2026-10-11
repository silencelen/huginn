#!/usr/bin/env python3
"""preview.py — render terminal-art .txt files to one PNG, on the brand's dark and light grounds.

  preview.py --out preview.png [--cell 10x18] [--width 980] [--grounds dark,light] [--font-blocks] FILE[:label] ...

Each file becomes a card: a small muted label plus a <pre> in DejaVu Sans Mono, bone #E8E2DA
on #12100F (dark) and ink #1B1817 on #FAF7F2 (light). Rendering goes through tools/render.mjs
(Chromium) and the result is trimmed with ImageMagick.

Faithfulness notes
  * The grid is defined by --cell WxH in px (default 10x18: a 16.6px DejaVu Sans Mono with ~1.08
    leading, i.e. a "16px terminal"). Terminals are ~1:2 cells; a plain `line-height:1` <pre>
    would be 1:1.66, which squashes the art. Keep the width EVEN: half blocks are one gradient,
    but quadrant glyphs are still 50%-wide boxes and an odd width puts their edge on a half pixel.
  * Block elements (U+2580-259F except the shades ░▒▓) are drawn as cell-sized boxes, not font
    glyphs. That is what Kitty, WezTerm, Alacritty, foot and Windows Terminal do, and it is the
    only way to avoid hairline seams between adjacent blocks. Font-drawn blocks (xterm, GitHub's
    code view, most editors) MAY show seams or row gaps; --font-blocks renders that look instead.
  * Glyphs DejaVu Sans Mono lacks (braille, runes) are wrapped in a one-cell inline block so they
    occupy exactly one cell, which is what a terminal does with a fallback font. The fallback
    fonts are Noto Sans Symbols 2 (braille) and Noto Sans Runic, fetched from Google Fonts, which
    is what a Linux desktop or Android (Termux) typically falls back to. The preview is honest
    about alignment, not about every terminal's fallback: see the README.
  * A FILE ending in .html is inserted into the <pre> as-is (for coloured mock-ups); its text
    nodes get the same treatment. Classes: gold, muted, inv (gold ground), dim.
"""
import argparse
import functools
import html
import os
import re
import subprocess
import sys
import tempfile

HERE = os.path.dirname(os.path.abspath(__file__))
RENDER_TOOL = os.path.normpath(os.path.join(HERE, '..', 'tools', 'render.mjs'))
MONO = 'DejaVu Sans Mono'
ADVANCE = 1233 / 2048          # DejaVu Sans Mono advance width in em
FONT_BLOCKS = False            # --font-blocks: draw block elements with the font (xterm/GitHub look)

GROUNDS = {
    'dark': dict(bg='#12100F', fg='#E8E2DA', lbl='#8A8177', gold='#C8A45C', goldink='#241C09', surf='#241F1D'),
    'light': dict(bg='#FAF7F2', fg='#1B1817', lbl='#5C544B', gold='#7A5A16', goldink='#FFFFFF', surf='#EFE9E0'),
}

# quadrant bits: 1 top-left, 2 top-right, 4 bottom-left, 8 bottom-right
QUAD = {'▘': 1, '▝': 2, '▀': 3, '▖': 4, '▌': 5, '▞': 6, '▛': 7,
        '▗': 8, '▚': 9, '▐': 10, '▜': 11, '▄': 12, '▙': 13, '▟': 14,
        '█': 15}
LOWER = {chr(0x2581 + i): (i + 1) / 8 for i in range(7)}          # ▁..▇ lower eighths
LEFT = {chr(0x258f - i): (i + 1) / 8 for i in range(7)}           # ▏..▉ left eighths
OTHER = {'▔': ('top', 1 / 8), '▕': ('right', 1 / 8)}    # ▔ ▕


HALVES = {3: 'left top/100% 50%', 12: 'left bottom/100% 50%', 5: 'left top/50% 100%', 10: 'right top/50% 100%'}
QUADS = {1: 'left top', 2: 'right top', 4: 'left bottom', 8: 'right bottom'}


def block_style(ch):
    """CSS background for a block element, or None if it should stay a font glyph.

    Half blocks (▀ ▄ ▌ ▐) are ONE gradient covering 100% x 50% (or 50% x 100%), never two
    quadrants side by side: two 50%-wide quadrants meet at a fractional pixel on an odd cell
    width (4.5px on a 9px cell) and Chromium draws a hairline seam there. Three-quadrant
    blocks are a half plus one quadrant for the same reason."""
    if FONT_BLOCKS:
        return None
    g = 'linear-gradient(currentColor,currentColor) '
    if ch in QUAD:
        bits = QUAD[ch]
        if bits == 15:
            return 'background:currentColor'
        parts = []
        for hb, pos in HALVES.items():
            if bits & hb == hb:
                parts.append(g + pos + ' no-repeat')
                bits &= ~hb
                break
        for qb, pos in QUADS.items():
            if bits & qb:
                parts.append(g + pos + '/50% 50% no-repeat')
        return 'background:' + ','.join(parts)
    if ch in LOWER:
        return f'background:{g}left bottom/100% {LOWER[ch] * 100:.1f}% no-repeat'
    if ch in LEFT:
        return f'background:{g}left top/{LEFT[ch] * 100:.1f}% 100% no-repeat'
    if ch in OTHER:
        side, frac = OTHER[ch]
        if side == 'top':
            return f'background:{g}left top/100% {frac * 100:.1f}% no-repeat'
        return f'background:{g}right top/{frac * 100:.1f}% 100% no-repeat'
    return None


@functools.lru_cache(maxsize=None)
def mono_has(ch):
    if ord(ch) < 0x80:
        return True
    out = subprocess.run(['fc-list', f':charset={ord(ch):x}', 'family'], capture_output=True, text=True).stdout
    return MONO in out


@functools.lru_cache(maxsize=None)
def cell_html(ch):
    if ch == '\n':
        return '\n'
    bs = block_style(ch)
    if bs:
        return f'<i class=b style="{bs}"></i>'
    if mono_has(ch):
        return html.escape(ch)
    return f'<span class=fb>{html.escape(ch)}</span>'


def mark_up(text):
    return ''.join(cell_html(ch) for ch in text)


def mark_up_html(src):
    """Apply mark_up to the text nodes of an HTML snippet."""
    out = []
    for piece in re.split(r'(<[^>]+>)', src):
        if piece.startswith('<'):
            out.append(piece)
        else:
            out.append(mark_up(html.unescape(piece)))
    return ''.join(out)


def build_html(items, cw, chh, width, grounds):
    font_px = cw / ADVANCE
    cards = []
    for path, label in items:
        text = open(path, encoding='utf-8').read().rstrip('\n')
        body = mark_up_html(text) if path.endswith('.html') else mark_up(text)
        cards.append(f'<div class=card><div class=lbl>{html.escape(label)}</div><pre>{body}</pre></div>')
    cards_html = ''.join(cards)
    panels = []
    for g in grounds:
        c = GROUNDS[g]
        vars_ = ';'.join(f'--{k}:{v}' for k, v in c.items())
        panels.append(f'<div class="ground" style="{vars_}">{cards_html}</div>')
    return f'''<!doctype html><html><head><meta charset="utf-8">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=Noto+Sans+Runic&family=Noto+Sans+Symbols+2&display=block">
<style>
html,body{{margin:0;background:#7f7f7f}}
#wrap{{display:inline-block;vertical-align:top}}
.ground{{width:{width}px;box-sizing:border-box;padding:26px 30px;display:flex;flex-wrap:wrap;gap:26px 40px;
  align-items:flex-start;align-content:flex-start;background:var(--bg);color:var(--fg)}}
.card{{display:flex;flex-direction:column;gap:7px}}
.lbl{{font-family:'{MONO}',monospace;font-size:11px;line-height:1;letter-spacing:.06em;text-transform:uppercase;color:var(--lbl)}}
pre{{margin:0;font-family:'{MONO}',monospace;font-size:{font_px:.3f}px;line-height:{chh}px;white-space:pre;font-variant-ligatures:none}}
.b{{display:inline-block;width:{cw}px;height:{chh}px;vertical-align:top}}
.fb{{display:inline-block;width:{cw}px;height:{chh}px;line-height:{chh}px;overflow:hidden;text-align:center;vertical-align:top;
  font-family:'Noto Sans Symbols 2','Noto Sans Runic','DejaVu Sans','Unifont',sans-serif}}
.gold{{color:var(--gold)}} .muted{{color:var(--lbl)}} .dim{{opacity:.55}}
.inv{{background:var(--gold);color:var(--goldink)}} .surf{{background:var(--surf)}}
</style></head><body><div id="wrap">{''.join(panels)}</div></body></html>'''


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument('items', nargs='+', help='FILE[:label]  (.txt, or .html snippet)')
    ap.add_argument('--out', required=True)
    ap.add_argument('--cell', default='10x18', help='cell WxH px; 10x18 is a 16px terminal cell. Use an EVEN width (see block_style)')
    ap.add_argument('--font-blocks', action='store_true', help='draw block elements as font glyphs (the xterm / GitHub code-view look, seams and all)')
    ap.add_argument('--width', type=int, default=980, help='panel width px')
    ap.add_argument('--grounds', default='dark,light')
    ap.add_argument('--canvas', type=int, default=1800, help='render viewport (square); must exceed the content')
    ap.add_argument('--keep-html', help='also write the HTML here')
    a = ap.parse_args()
    global FONT_BLOCKS
    FONT_BLOCKS = a.font_blocks
    cw, chh = (int(v) for v in a.cell.lower().split('x'))
    if cw % 2 and not FONT_BLOCKS:
        print(f'preview.py: WARNING odd cell width {cw}px: quadrant glyphs may show a seam; use 10x18', file=sys.stderr)

    items = []
    for it in a.items:
        path, _, label = it.partition(':')
        items.append((path, label or os.path.basename(path)))
    page = build_html(items, cw, chh, a.width, a.grounds.split(','))
    tmp = tempfile.mkdtemp(prefix='preview-')
    hp = os.path.join(tmp, 'p.html')
    with open(hp, 'w', encoding='utf-8') as f:
        f.write(page)
    if a.keep_html:
        with open(a.keep_html, 'w', encoding='utf-8') as f:
            f.write(page)
    prefix = os.path.join(tmp, 'r')
    subprocess.run(['node', RENDER_TOOL, hp, prefix, str(a.canvas), '#7f7f7f'], check=True,
                   stdout=subprocess.DEVNULL, cwd=os.path.dirname(RENDER_TOOL))
    raw = f'{prefix}-{a.canvas}.png'
    os.makedirs(os.path.dirname(os.path.abspath(a.out)) or '.', exist_ok=True)
    subprocess.run(['convert', raw, '-trim', '+repage', '-strip', '-define', 'png:compression-level=9', a.out], check=True)
    w, h = subprocess.run(['identify', '-format', '%w %h', a.out], capture_output=True, text=True).stdout.split()
    if int(h) >= a.canvas - 1 or int(w) >= a.canvas - 1:
        print(f'preview.py: WARNING content clipped at canvas {a.canvas}; raise --canvas', file=sys.stderr)
    size = os.path.getsize(a.out)
    if size > 380_000:
        subprocess.run(['convert', a.out, '-colors', '96', '-strip', a.out], check=True)
        size = os.path.getsize(a.out)
    print(f'{a.out}: {w}x{h}, {size // 1024} KB', file=sys.stderr)


if __name__ == '__main__':
    main()
