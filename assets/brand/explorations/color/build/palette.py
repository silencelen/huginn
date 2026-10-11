#!/usr/bin/env python3
"""Emit every file of the three colour directions from spec.py.

    python3 build/palette.py            (run from assets/brand/explorations/color)

Refuses to write anything if a contrast, lightness, hue or slot rule in spec.py fails
(see check.py for the list), and refuses to write cli-colors.sh / cli-colors.ps1 if
they contain a single non-ASCII byte (they are inlined into client/huginn.sh and
huginn.ps1, which must stay 7-bit). Previews are rendered afterwards with build/shot.mjs;
the READMEs come from build/readmes.py so that every number in them is computed.
"""
import json
import os
import subprocess
import sys
from itertools import combinations

sys.path.insert(0, os.path.dirname(__file__))
from colorlib import contrast, lstar, grade, hsl, alpha_over  # noqa: E402
from spec import (DIRECTIONS, TEXT_PAIRS, UI_PAIRS, SYNTAX_KEYS, TRAY_ORDER,  # noqa: E402
                  TRAY_MIN_DL, ANSI_KEYS, ROLE_NOTES, ANSI_LADDER_MIN_DL, MONO_TRAY_ALPHAS,
                  TERTIARY_MIN_DL, TERTIARY_MIN_DHUE, SLOT_PINS, LEGACY_PALETTES, LEGACY_MIN,
                  CONSOLE_NAME, SGR, GLYPH, STATE_GLYPH)
from check import check, ansi_ladder, mono_ladder, legacy_rows, dhue  # noqa: E402

ROOT = os.path.dirname(os.path.dirname(os.path.abspath(__file__)))          # .../color
EXPL = os.path.dirname(ROOT)                                                 # .../explorations
CONTRAST_PY = os.path.join(EXPL, 'tools', 'contrast.py')

RAVEN_PATH = ('M94,32 C87,27.5 80,25.8 74,25.5 C72.5,19.5 64,17 58.5,19.3 C54,21.3 51,24 48,27 '
              'C41,32.5 35,37.5 30,42 L10,60 L16,69 L37,54 C40,58.5 45,61.5 51,62.5 C58,63.3 64,60 67,55.5 '
              'C70.3,50.3 71.8,45.2 70,41 L67.8,40.2 L71.3,37.8 L68.8,36.2 L72,34.8 C79,34.6 86,34.2 93,33.6 '
              'C94.6,33 94.6,32.5 94,32 Z M64.5,25.5 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0 Z')
RAVEN_SYMBOL = ('<svg width="0" height="0" style="position:absolute"><symbol id="raven" viewBox="0 0 108 108">'
                f'<g transform="translate(2,11)"><path fill="currentColor" fill-rule="evenodd" d="{RAVEN_PATH}"/></g>'
                '</symbol></svg>')

STATE_KEYS = ['stateIdle', 'stateWorking', 'stateAttention', 'stateSuccess', 'stateError', 'stateInfo']
STATE_LABEL = {'stateIdle': 'idle', 'stateWorking': 'working', 'stateAttention': 'attention',
               'stateSuccess': 'success', 'stateError': 'error', 'stateInfo': 'info'}
SURFACES = ['background', 'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
            'surfaceContainerHigh', 'surfaceContainerHighest']
SURF_TIER = {'background': 'ground', 'surfaceContainerLowest': 'tier 0', 'surfaceContainerLow': 'tier 1',
             'surfaceContainer': 'tier 2', 'surfaceContainerHigh': 'tier 3', 'surfaceContainerHighest': 'tier 4'}

FONT_LINK = ('<link rel="preconnect" href="https://fonts.googleapis.com">'
             '<link href="https://fonts.googleapis.com/css2?family=Inter:wght@400;500;600;700'
             '&family=JetBrains+Mono:wght@400;500;700&display=swap" rel="stylesheet">')
UI_FONT = "Inter, 'Segoe UI', Roboto, 'DejaVu Sans', sans-serif"
MONO_FONT = "'JetBrains Mono', 'Cascadia Mono', 'DejaVu Sans Mono', Menlo, Consolas, monospace"
# The tmux line and the glyph are set in DejaVu Sans Mono on purpose: it has U+25E2 and the
# block elements (verified with fc-list in the ascii dimension), which is what a Linux host
# and most terminals fall back to; JetBrains Mono's Geometric Shapes coverage is partial.
GLYPH_FONT = "'DejaVu Sans Mono', 'Cascadia Mono', Menlo, Consolas, monospace"


# ------------------------------------------------------------------ helpers
def ground_for(fg, t):
    """The ground a token is checked against in the swatch table."""
    if fg.startswith('on'):
        base = fg[2].lower() + fg[3:]
        return base if base in t else 'background'
    return 'background'


def pairs_for(d, scheme):
    """Every (fg, bg, min) pair contrast.txt proves, incl. syntax on the code ground."""
    t = d[scheme]
    out = [(t[fg], t[bg], fg, bg, mn) for fg, bg, mn in TEXT_PAIRS + UI_PAIRS]
    syn = d['syntax_' + scheme]
    for k in SYNTAX_KEYS:
        out.append((syn[k], t['surfaceContainerLowest'], 'syntax.' + k, 'surfaceContainerLowest', 4.5))
    return out


def run_contrast_py(pairs):
    args = []
    for fg, bg, *_ in pairs:
        args += [fg, bg]
    return subprocess.run([sys.executable, CONTRAST_PY] + args, capture_output=True, text=True, check=True).stdout


def write(path, text, ascii_only=False):
    if ascii_only and not text.isascii():
        bad = sorted({c for c in text if not c.isascii()})
        print(f'refusing to write {os.path.relpath(path, ROOT)}: non-ASCII {bad}')
        sys.exit(1)
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, 'w', encoding='utf-8', newline='\n') as f:
        f.write(text)
    print('  wrote', os.path.relpath(path, ROOT))


def tc(h):
    r, g, b = (int(h[i:i + 2], 16) for i in (1, 3, 5))
    return f'38;2;{r};{g};{b}'


def cli_truecolor_hex(d, sem):
    """The hex the truecolor CLI branch pins: the accent is the brand accent itself (for 03 the
    ember, which has no ANSI slot); the other four are their slot's hex."""
    return d['dark']['accent'] if sem == 'accent' else d['ansi'][d['cli'][sem]]


# ------------------------------------------------------------- contrast.txt
def contrast_txt(d):
    lines = [f'# {d["id"]} - contrast proof', '#',
             '# Sections 1-3 are the verbatim output of tools/contrast.py (text pairs must be >= 4.5:1,',
             '# UI pairs >= 3:1, ANSI slots >= 3:1, the CLI 16-colour slots >= 3:1 on legacy palettes).',
             '# Sections 4-6 are lightness (CIELAB L*) and hue tables computed by build/colorlib.py.', '']
    for scheme in ('dark', 'light'):
        pairs = pairs_for(d, scheme)
        out = run_contrast_py(pairs).rstrip('\n').split('\n')
        lines.append(f'## 1. {scheme} scheme: tools/contrast.py output, one line per pair')
        lines.append(f'##   {"fg role":24} {"ground role":24} min   contrast.py')
        for (fg, bg, fr, br, mn), line in zip(pairs, out):
            lines.append(f'{fr:26} {br:24} {mn:<5} {line}')
        lines.append('')
    lines.append('## 2. ANSI-16 against the terminal ground (tools/contrast.py; black is the ground itself)')
    a = d['ansi']
    pairs = [(a[k], a['background']) for k in ANSI_KEYS if k != 'black']
    out = run_contrast_py(pairs).rstrip('\n').split('\n')
    for k, line in zip([k for k in ANSI_KEYS if k != 'black'], out):
        lines.append(f'{"ansi." + k:26} {"background":24} {3.0:<5} {line}')
    lines.append('')
    lines.append('## 3. The CLI 16-colour branch on legacy palettes (tools/contrast.py): slot hex on that palette\'s ground')
    rows = legacy_rows(d)
    out = run_contrast_py([(h, LEGACY_PALETTES[n]['ground']) for n, _, _, h, _ in rows]).rstrip('\n').split('\n')
    for (n, sem, slot, h, _), line in zip(rows, out):
        lines.append(f'{n + " " + sem:26} {slot:24} {LEGACY_MIN:<5} {line}')
    lines.append('')
    lines.append('## 4. State colours by lightness (L*)')
    lines.append(f'## Rule: adjacent tray states (idle -> working -> attention) differ by >= {TRAY_MIN_DL:.0f} L*,')
    lines.append(f'## in the app tokens, in the ANSI-16 ladder (brightBlack -> accent slot -> brightYellow) and')
    lines.append(f'## in the monochrome tray tints (onSurface at {", ".join(f"{v:.0%}" for v in MONO_TRAY_ALPHAS.values())} over the ground).')
    for scheme in ('dark', 'light'):
        t = d[scheme]
        lines.append(f'## {scheme}')
        for k in STATE_KEYS:
            r = contrast(t[k], t['background'])
            lines.append(f'{k:16} {t[k]}  L*={lstar(t[k]):5.1f}  hue={hsl(t[k])[0]:5.1f}  vs background {r:5.2f}:1 {grade(r)}')
        lines.append('   pairwise (dL* / contrast ratio between the two state colours):')
        for a_, b_ in combinations(STATE_KEYS, 2):
            dl = abs(lstar(t[a_]) - lstar(t[b_]))
            lines.append(f'   {STATE_LABEL[a_]:9} vs {STATE_LABEL[b_]:9}  dL*={dl:5.1f}  {contrast(t[a_], t[b_]):4.2f}:1'
                         + ('   (tray pair)' if a_ in TRAY_ORDER and b_ in TRAY_ORDER else ''))
        lines.append('   monochrome tray tints:')
        for k, al, h, L in mono_ladder(t):
            lines.append(f'   {STATE_LABEL[k]:9} onSurface at {al:.0%} over background = {h}  L*={L:5.1f}')
        lines.append('')
    lines.append('## 5. ANSI-16 state ladder (L*)')
    for k, h, L in ansi_ladder(d):
        lines.append(f'{k:16} {h}  L*={L:5.1f}')
    lines.append('')
    lines.append(f'## 6. tertiary vs each state: needs dL* >= {TERTIARY_MIN_DL:.0f} or a hue gap >= {TERTIARY_MIN_DHUE:.0f} degrees (neutral states count by L* only)')
    for scheme in ('dark', 'light'):
        t = d[scheme]
        for k in STATE_KEYS:
            lines.append(f'{scheme:5} tertiary {t["tertiary"]} vs {k:14} {t[k]}  dL*={abs(lstar(t["tertiary"]) - lstar(t[k])):5.1f}  dHue={dhue(t["tertiary"], t[k]):5.1f}')
    lines.append('')
    return '\n'.join(lines) + '\n'


# -------------------------------------------------------------- tokens.json
def tokens_json(d):
    def scheme(s):
        t = d[s]
        return {k: {'hex': v, 'role': ROLE_NOTES.get(k, ''), 'lstar': round(lstar(v), 1),
                    'contrastOnGround': round(contrast(v, t[ground_for(k, t)]), 2),
                    'ground': ground_for(k, t)} for k, v in t.items()}

    def mono(s):
        return {STATE_LABEL[k]: {'alpha': al, 'hex': h, 'lstar': round(L, 1)} for k, al, h, L in mono_ladder(d[s])}
    a = d['ansi']
    return json.dumps({
        'id': d['id'], 'name': d['name'], 'tagline': d['tagline'],
        'accent': {'name': d['accent_name'], 'dark': d['dark']['accent'], 'light': d['light']['accent'],
                   'ansi': d['accent_ansi']},
        'schemes': {'dark': scheme('dark'), 'light': scheme('light')},
        'syntax': {'dark': d['syntax_dark'], 'light': d['syntax_light'],
                   'ground': 'surfaceContainerLowest'},
        'states': {'order': TRAY_ORDER, 'minAdjacentDeltaLstar': TRAY_MIN_DL,
                   'ansi': d['state_ansi'],
                   'ansiLadderLstar': {k: round(L, 1) for k, _, L in ansi_ladder(d)},
                   'monochromeTray': {'rule': 'onSurface at these opacities over background; attention adds the badge dot',
                                      'dark': mono('dark'), 'light': mono('light')},
                   'glyphs': {k: {'unicode': u, 'ascii': s} for k, (u, s) in STATE_GLYPH.items()}},
        'terminal': {'ansi16': {k: a[k] for k in ANSI_KEYS},
                     'foreground': a['foreground'], 'background': a['background'],
                     'cursor': a['cursor'], 'selection': a['selection'],
                     'contrastOnGround': {k: round(contrast(a[k], a['background']), 2) for k in ANSI_KEYS},
                     'slotPins': {slot: tok for slot, tok in SLOT_PINS}},
        'cli': {'slots': d['cli'], 'truecolor': {k: cli_truecolor_hex(d, k) for k in d['cli']},
                'sgr16': {k: SGR[v] for k, v in d['cli'].items()},
                'consoleColor': {k: CONSOLE_NAME[v] for k, v in d['cli'].items()}},
        'glyph': GLYPH,
    }, indent=2) + '\n'


# ------------------------------------------------------------ swatches.html
CSS = """
*{box-sizing:border-box} body{margin:0;font-family:%(ui)s;font-size:13px;line-height:1.4;-webkit-font-smoothing:antialiased}
.scheme{padding:36px 40px 44px} h1{font-size:30px;margin:0 0 4px;font-weight:700;letter-spacing:-.01em}
h2{font-size:12px;font-weight:600;letter-spacing:.12em;text-transform:uppercase;margin:30px 0 10px;opacity:.75}
.sub{font-size:15px;opacity:.8;margin:0} .mono{font-family:%(mono)s;font-variant-ligatures:none}
.row{display:flex;gap:10px;flex-wrap:wrap} .surf{flex:1 1 0;height:96px;border-radius:10px;padding:10px 12px;position:relative;font-size:11px}
.surf b{display:block;font-size:12px;margin-bottom:2px} .surf .m{position:absolute;left:12px;bottom:10px;opacity:.85}
.tok{display:flex;align-items:center;gap:12px;padding:8px 10px;border-radius:10px;border:1px solid transparent}
.tok .chip{width:44px;height:44px;border-radius:8px;flex:none;display:flex;align-items:center;justify-content:center;font-weight:600;font-size:18px}
.tok .n{font-weight:600} .tok .r{opacity:.75;font-size:11px}
.grid3{display:grid;grid-template-columns:repeat(3,1fr);gap:8px} .grid2{display:grid;grid-template-columns:repeat(2,1fr);gap:8px}
.acc{border-radius:12px;padding:12px 14px;display:flex;flex-direction:column;gap:6px}
.acc .pill{display:inline-flex;align-items:center;gap:8px;padding:6px 12px;border-radius:999px;font-weight:600;font-size:12px;width:max-content}
.state{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:10px}
.dot{width:12px;height:12px;border-radius:50%%;flex:none} .state .t{font-weight:600} .state small{display:block;opacity:.75;font-weight:400;font-size:11px}
table{border-collapse:collapse;font-size:11.5px} td,th{padding:4px 10px;text-align:left;white-space:nowrap} th{font-weight:600;opacity:.75}
pre{margin:0;font-family:%(mono)s;font-variant-ligatures:none;font-size:12.5px;line-height:1.55;padding:14px 16px;border-radius:10px;overflow:hidden}
.mock{display:grid;grid-template-columns:1.1fr 1fr 1.2fr;gap:14px}
.card{border-radius:14px;padding:14px;display:flex;flex-direction:column;gap:10px}
.bubble{border-radius:14px;padding:10px 12px;max-width:92%%;font-size:12.5px;line-height:1.45}
.sess{display:flex;align-items:center;gap:10px;padding:10px 12px;border-radius:10px}
.sess .nm{font-weight:600;font-size:12.5px} .sess .st{font-size:11px;opacity:.8}
.tray{display:flex;gap:14px;align-items:flex-end;flex-wrap:wrap}
.tray .ic{display:flex;flex-direction:column;align-items:center;gap:6px;font-size:10.5px;opacity:.9}
.tray .box{width:48px;height:48px;border-radius:9px;display:flex;align-items:center;justify-content:center;position:relative}
.tray svg{width:40px;height:40px} .badge{position:absolute;right:5px;top:5px;width:10px;height:10px;border-radius:50%%}
.bar{display:flex;align-items:center;gap:18px;padding:0 14px;border-radius:6px;height:36px}
.bar .px{position:relative;display:block;flex:none} .bar .px svg{display:block}
.bar .px i{position:absolute;border-radius:50%%;display:block}
.barlab{display:flex;gap:18px;padding:4px 14px 0;font-size:10px;opacity:.75}
.leg{display:flex;flex-wrap:wrap;gap:6px 14px;font-size:11px;margin-top:8px} .leg span{display:inline-flex;align-items:center;gap:6px}
.sw{width:11px;height:11px;border-radius:3px;display:inline-block}
.ansi{display:grid;grid-template-columns:repeat(8,1fr);gap:6px}
.ansi div{height:54px;border-radius:8px;padding:6px 8px;font-size:10.5px;line-height:1.3}
.foot{font-size:11px;opacity:.7;margin-top:26px}
""" % {'ui': UI_FONT, 'mono': MONO_FONT}


def ratio_txt(fg, bg):
    r = contrast(fg, bg)
    return f'{r:.2f}:1 {grade(r)}'


def surfaces_html(t):
    on = t['onSurface']
    cells = []
    for k in SURFACES:
        cells.append(f'<div class="surf" style="background:{t[k]};color:{on};border:1px solid {t["outlineHairline"]}">'
                     f'<b>{k}</b><span style="opacity:.8">{SURF_TIER[k]} - {ROLE_NOTES.get(k, "")}</span>'
                     f'<span class="m mono">{t[k]}  L*{lstar(t[k]):.0f}  ink {contrast(on, t[k]):.1f}:1</span></div>')
    return '<div class="row">' + ''.join(cells) + '</div>'


def ink_html(t):
    items = []
    for k in ['onBackground', 'onSurface', 'onSurfaceVariant', 'outline', 'outlineVariant', 'outlineHairline']:
        g = 'background'
        deco = k in ('outlineVariant', 'outlineHairline')
        proof = (f'decorative, no minimum: {contrast(t[k], t[g]):.2f}:1 on {g}, {contrast(t[k], t["surfaceContainerHigh"]):.2f}:1 on tier 3'
                 if deco else f'on {g}: {ratio_txt(t[k], t[g])} - on tier 3: {ratio_txt(t[k], t["surfaceContainerHigh"])}')
        items.append(f'<div class="tok" style="background:{t["surfaceContainerLow"]};border-color:{t["outlineHairline"]}">'
                     f'<div class="chip" style="background:{t[g]};color:{t[k]};border:1px solid {t["outlineVariant"]}">'
                     f'{"Aa" if k.startswith("on") else "&#9644;"}</div>'
                     f'<div><div class="n">{k} <span class="mono" style="font-weight:400;opacity:.8">{t[k]}</span></div>'
                     f'<div class="r">{ROLE_NOTES.get(k, "")}<br>{proof}</div></div></div>')
    return '<div class="grid3">' + ''.join(items) + '</div>'


def accents_html(t):
    groups = [('primary', 'onPrimary', 'primaryContainer', 'onPrimaryContainer'),
              ('secondary', 'onSecondary', 'secondaryContainer', 'onSecondaryContainer'),
              ('error', 'onError', 'errorContainer', 'onErrorContainer')]
    out = []
    for base, on, cont, oncont in groups:
        out.append(
            f'<div class="acc" style="background:{t["surfaceContainerLow"]};border:1px solid {t["outlineHairline"]}">'
            f'<div class="n" style="font-weight:600">{base} <span class="mono" style="font-weight:400;opacity:.8">{t[base]}</span>'
            f' <span style="opacity:.7;font-weight:400">- {ROLE_NOTES.get(base, "")}</span></div>'
            f'<div class="pill" style="background:{t[base]};color:{t[on]}">{on} {t[on]} - {ratio_txt(t[on], t[base])}</div>'
            f'<div class="pill" style="background:{t[cont]};color:{t[oncont]}">{oncont} on {cont} - {ratio_txt(t[oncont], t[cont])}</div>'
            f'<div class="r" style="font-size:11px;opacity:.8">{base} as text on background: {ratio_txt(t[base], t["background"])}'
            f'; on tier 3: {ratio_txt(t[base], t["surfaceContainerHigh"])}</div></div>')
    out.append(
        f'<div class="acc" style="background:{t["surfaceContainerLow"]};border:1px solid {t["outlineHairline"]}">'
        f'<div class="n" style="font-weight:600">tertiary <span class="mono" style="font-weight:400;opacity:.8">{t["tertiary"]}</span>'
        f' <span style="opacity:.7;font-weight:400">- {ROLE_NOTES["tertiary"]}</span></div>'
        f'<div class="pill" style="background:{t["background"]};color:{t["tertiary"]};border:1px solid {t["outlineVariant"]}">tertiary text - {ratio_txt(t["tertiary"], t["background"])}</div>'
        f'<div class="n" style="font-weight:600;margin-top:4px">inkMark / boneTile <span class="mono" style="font-weight:400;opacity:.8">{t["inkMark"]} / {t["boneTile"]}</span></div>'
        f'<div class="pill" style="background:{t["boneTile"]};color:{t["inkMark"]}"><svg style="width:18px;height:18px"><use href="#raven"/></svg> the icon tile - {ratio_txt(t["inkMark"], t["boneTile"])}</div>'
        f'</div>')
    return '<div class="grid2">' + ''.join(out) + '</div>'


def px_icon(color, size, badge=None, opacity=1.0):
    """The raven at a real tray size (16 or 22 CSS px at deviceScaleFactor 1), with the badge dot."""
    bs = 5 if size <= 16 else 7
    b = f'<i style="background:{badge};width:{bs}px;height:{bs}px;right:-1px;top:-1px"></i>' if badge else ''
    return (f'<span class="px" style="width:{size}px;height:{size}px;color:{color}">'
            f'<svg width="{size}" height="{size}" style="opacity:{opacity}"><use href="#raven"/></svg>{b}</span>')


def tray_strip_html(t, scheme):
    """Three coloured states and three monochrome tints at 22 and 16 px on a tray bar of tier 1 and tier 3."""
    on = t['onSurface']
    mono = {k: al for k, al in MONO_TRAY_ALPHAS.items()}
    out = []
    for bar in ('surfaceContainerLow', 'surfaceContainerHigh'):
        cells = []
        for size in (22, 16):
            cells += [px_icon(t['stateIdle'], size), px_icon(t['stateWorking'], size),
                      px_icon(t['stateAttention'], size, badge=t['stateAttention']),
                      f'<span style="width:1px;height:{size}px;background:{t["outlineVariant"]}"></span>',
                      px_icon(on, size, opacity=mono['stateIdle']), px_icon(on, size, opacity=mono['stateWorking']),
                      px_icon(on, size, badge=on, opacity=mono['stateAttention']),
                      f'<span style="width:10px"></span>']
        out.append(f'<div class="bar" style="background:{t[bar]};border:1px solid {t["outlineHairline"]}">{"".join(cells)}</div>')
    lab = (f'<div class="barlab"><span>22 px: idle / working / attention+badge | mono {mono["stateIdle"]:.0%} / {mono["stateWorking"]:.0%} / 100%+badge</span>'
           f'<span style="margin-left:auto">16 px: the same six</span></div>')
    return ('<div style="display:flex;flex-direction:column;gap:8px;max-width:620px">' + out[0] + out[1] + lab + '</div>')


def states_html(t, scheme):
    chips = []
    for k in STATE_KEYS:
        chips.append(f'<div class="state" style="background:{t["surfaceContainerLow"]};border:1px solid {t["outlineHairline"]}">'
                     f'<span class="dot" style="background:{t[k]}"></span>'
                     f'<div class="t" style="color:{t[k]}">{STATE_LABEL[k]} <span class="mono" style="font-weight:400;opacity:.8">{t[k]}</span>'
                     f'<small>L* {lstar(t[k]):.0f} - on background {ratio_txt(t[k], t["background"])}'
                     f' - on tier 3 {contrast(t[k], t["surfaceContainerHigh"]):.1f}:1</small></div></div>')
    keys = STATE_KEYS
    head = ''.join(f'<th><span class="sw" style="background:{t[k]}"></span> {STATE_LABEL[k]}</th>' for k in keys)
    rows = []
    for a_ in keys:
        cells = []
        for b_ in keys:
            if a_ == b_:
                cells.append(f'<td class="mono" style="opacity:.35">L* {lstar(t[a_]):.0f}</td>')
                continue
            tray = a_ in TRAY_ORDER and b_ in TRAY_ORDER
            dl = abs(lstar(t[a_]) - lstar(t[b_]))
            style = f'background:{t["primaryContainer"]};color:{t["onPrimaryContainer"]};font-weight:600' if tray else ''
            cells.append(f'<td class="mono" style="{style}">{dl:.0f} <span style="opacity:.6">/ {contrast(t[a_], t[b_]):.2f}</span></td>')
        rows.append(f'<tr><th><span class="sw" style="background:{t[a_]}"></span> {STATE_LABEL[a_]}</th>{"".join(cells)}</tr>')
    def hue_cell(k):
        h = dhue(t['tertiary'], t[k])
        return f'<span style="opacity:.6"> / {h:.0f}&deg;</span>' if h else '<span style="opacity:.6"> / neutral</span>'
    tcells = ''.join(f'<td class="mono">{abs(lstar(t["tertiary"]) - lstar(t[k])):.0f} <span style="opacity:.6">/ {contrast(t["tertiary"], t[k]):.2f}</span>'
                     f'{hue_cell(k)}</td>' for k in keys)
    rows.append(f'<tr style="border-top:1px solid {t["outlineVariant"]}"><th><span class="sw" style="background:{t["tertiary"]}"></span> tertiary</th>{tcells}</tr>')
    table = (f'<table style="border:1px solid {t["outlineHairline"]};border-radius:8px"><tr><th></th>{head}</tr>' + ''.join(rows) + '</table>')
    mono_txt = ', '.join(f'{STATE_LABEL[k]} {al:.0%} = L* {L:.0f}' for k, al, h, L in mono_ladder(t))
    strip = (f'<div style="margin-top:14px;font-size:11px;opacity:.75;margin-bottom:6px">At tray size, {scheme} bars (tier 1, tier 3): coloured states, then monochrome tints</div>'
             + tray_strip_html(t, scheme))
    return ('<div class="grid3">' + ''.join(chips) + '</div>'
            f'<div style="margin-top:10px;display:flex;gap:24px;align-items:flex-start"><div>{table}{strip}</div>'
            f'<div style="font-size:11.5px;max-width:420px;opacity:.85">Each cell: <b>dL*</b> / contrast ratio between the two colours; the tertiary row adds the hue gap. '
            f'Highlighted cells are the tray pairs, which must be >= {TRAY_MIN_DL:.0f} L* apart: the three tray states are one family at three '
            f'lightness steps (dim stone, the accent, the accent lit up), so a monochrome tray and a 16-colour terminal still tell them apart; '
            f'attention also carries the badge dot. Success / error / info are text semantics separated by hue; where a cell is below 12 '
            f'they are not distinguishable by lightness alone, and the UI never relies on that (they always come with a word). '
            f'tertiary (subagent markers, graph series) sits beside state dots, so it must be >= {TERTIARY_MIN_DL:.0f} L* or >= {TERTIARY_MIN_DHUE:.0f}&deg; from every state. '
            f'Monochrome tray tints: {mono_txt}. The bars under the table are the six tray icons at 22 and 16 px; '
            f'tray-sizes.png beside this sheet is the same set rendered with tools/render.mjs and upscaled with hard pixels.</div></div>')


def syntax_html(t, syn):
    g = t['surfaceContainerLowest']
    c = {k: f'color:{syn[k]}' for k in SYNTAX_KEYS}
    code = (f'<span style="{c["comment"]}">// what the raven saw: a session, mirrored</span>\n'
            f'<span style="{c["keyword"]}">suspend fun</span> <span style="{c["function"]}">attach</span>(name: String, solo: Boolean = <span style="{c["keyword"]}">false</span>): Session {{\n'
            f'    <span style="{c["keyword"]}">val</span> target = <span style="{c["string"]}">"=$name"</span>          <span style="{c["comment"]}">// exact match, never a prefix</span>\n'
            f'    <span style="{c["meta"]}">@Retry</span>(times = <span style="{c["number"]}">3</span>, delay = <span style="{c["number"]}">45_000</span>)\n'
            f'    <span style="{c["keyword"]}">return</span> <span style="{c["function"]}">tmux</span>.<span style="{c["function"]}">attach</span>(target, solo)\n'
            f'}}\n'
            f'<span style="{c["removed"]}">- Set Theme "Catppuccin Mocha"</span>\n'
            f'<span style="{c["added"]}">+ Set Theme {{ "name": "Huginn", ... }}</span>')
    leg = ''.join(f'<span><i class="sw" style="background:{syn[k]}"></i>{k} <span class="mono">{syn[k]}</span> {contrast(syn[k], g):.1f}:1</span>'
                  for k in SYNTAX_KEYS)
    return (f'<pre style="background:{g};color:{t["onSurface"]};border:1px solid {t["outlineHairline"]}">{code}</pre>'
            f'<div class="leg">{leg}</div>')


def tray_icon(color, box_bg, badge=None, label='', opacity=1.0):
    b = f'<span class="badge" style="background:{badge}"></span>' if badge else ''
    return (f'<div class="ic"><div class="box" style="background:{box_bg};color:{color};opacity:1">'
            f'<svg style="opacity:{opacity}"><use href="#raven"/></svg>{b}</div><span>{label}</span></div>')


def status_block(d, scheme_t, ground, ink, font_size='11.5px'):
    """The `huginn status` panel drawn with the ANSI slots the CLI really uses (cli-colors.sh),
    not with app tokens, so this panel and terminal/mock.html cannot disagree."""
    a, c, sa = d['ansi'], d['cli'], d['state_ansi']
    acc, mut = a[c['accent']], a[c['muted']]
    return (f'<pre style="background:{ground};color:{ink};border:1px solid {scheme_t["outlineHairline"]};font-size:{font_size}">'
            f'<span style="color:{mut}">$</span> huginn status\n'
            f'<span style="color:{acc};font-weight:700">host</span>      ravenhost  <span style="color:{mut}">up 12d 4h</span>\n'
            f'<span style="color:{acc};font-weight:700">sessions</span>  3\n'
            f'  costtracking  <span style="color:{a[sa["stateWorking"]]}">working</span>    claude  12m\n'
            f'  brandbook     <span style="color:{a[sa["stateAttention"]]};font-weight:700">attention</span>  <span style="color:{a[sa["stateAttention"]]}">permission: Edit</span>\n'
            f'  scratch       <span style="color:{a[sa["stateIdle"]]}">idle</span>       2h\n'
            f'<span style="color:{a[c["ok"]]}">ok</span> link up  <span style="color:{a[c["warn"]]}">warn</span> 1 device stale  <span style="color:{a[c["error"]]}">error</span> none\n'
            f'<span style="color:{mut}">$</span> <span style="background:{a["cursor"]};color:{a["background"]}">&nbsp;</span></pre>')


def mock_html(t, d, scheme):
    on = t['onSurface']
    chat = (f'<div class="card" style="background:{t["surfaceContainerLow"]};border:1px solid {t["outlineHairline"]}">'
            f'<div style="font-size:11px;opacity:.7;font-weight:600">chat</div>'
            f'<div class="bubble" style="background:{t["surfaceContainerHigh"]};color:{on};align-self:flex-end">'
            f'Finish the brand palette and prove every pair.</div>'
            f'<div class="bubble" style="background:{t["surfaceContainerLowest"]};color:{on};border:1px solid {t["outlineHairline"]}">'
            f'Done. Three directions, each with dark and light schemes; <span style="color:{t["primary"]};font-weight:600">contrast.txt</span> holds the proof. '
            f'<span style="color:{t["onSurfaceVariant"]}">Edit Theme.kt?</span></div>'
            f'<div style="display:flex;gap:8px;margin-top:2px">'
            f'<span class="pill" style="background:{t["primary"]};color:{t["onPrimary"]};padding:6px 12px;border-radius:999px;font-weight:600;font-size:12px">Allow</span>'
            f'<span class="pill" style="background:{t["surfaceContainerHighest"]};color:{on};padding:6px 12px;border-radius:999px;font-weight:600;font-size:12px">Deny</span>'
            f'<span class="pill" style="color:{t["error"]};padding:6px 12px;border-radius:999px;font-weight:600;font-size:12px">Kill session</span>'
            f'</div>'
            f'<div style="border:1px solid {t["outlineVariant"]};border-radius:10px;padding:8px 10px;font-size:11.5px;display:flex;gap:8px;align-items:center">'
            f'<span class="dot" style="background:{t["stateSuccess"]}"></span><span class="mono" style="color:{t["onSurfaceVariant"]}">Edit</span>'
            f'<span class="mono">Theme.kt</span><span style="color:{t["onSurfaceVariant"]};margin-left:auto">+18 -6</span></div>'
            f'<div style="border:1px solid {t["outlineVariant"]};border-radius:10px;padding:8px 10px;font-size:11.5px;display:flex;gap:8px;align-items:center">'
            f'<span class="dot" style="background:{t["stateError"]}"></span><span class="mono" style="color:{t["onSurfaceVariant"]}">Bash</span>'
            f'<span class="mono">./gradlew :ui:test</span><span style="color:{t["error"]};margin-left:auto">exit 1</span></div>'
            f'<div style="font-size:11px;color:{t["onSurfaceVariant"]};display:flex;gap:10px;align-items:center">'
            f'<span class="dot" style="background:{t["stateWorking"]};width:8px;height:8px"></span>claude is thinking - 23:41 - '
            f'<span style="color:{t["tertiary"]}"><span class="dot" style="background:{t["tertiary"]};width:8px;height:8px;display:inline-block;vertical-align:-1px;margin-right:4px"></span>subagent: critic</span></div>'
            f'</div>')
    sessions = ''.join(
        f'<div class="sess" style="background:{t["surfaceContainer"]};border:1px solid {t["outlineHairline"]}">'
        f'<span class="dot" style="background:{t[k]};{"box-shadow:0 0 0 3px " + t["primaryContainer"] if k == "stateAttention" else ""}"></span>'
        f'<div style="flex:1"><div class="nm" style="color:{on}">{nm}</div><div class="st" style="color:{t["onSurfaceVariant"]}">{st}</div></div>'
        f'<span style="font-size:11px;font-weight:600;color:{t[k]}">{lab}</span></div>'
        for k, nm, st, lab in [('stateWorking', 'costtracking', 'claude is editing Theme.kt - 12 min', 'working'),
                               ('stateAttention', 'brandbook', 'waiting for permission: Edit', 'needs you'),
                               ('stateIdle', 'scratch', 'turn finished 2 h ago - phone attached', 'waiting')]) + (
        f'<div class="sess" style="background:{t["surfaceContainer"]};border:1px solid {t["outlineHairline"]}">'
        f'<span class="dot" style="background:transparent;border:1.5px solid {t["outline"]}"></span>'
        f'<div style="flex:1"><div class="nm" style="color:{on}">notes</div><div class="st" style="color:{t["onSurfaceVariant"]}">shell only - no Claude</div></div>'
        f'<span style="font-size:11px;font-weight:600;color:{t["outline"]}">shell</span></div>')
    sess = (f'<div class="card" style="background:{t["surfaceContainerLow"]};border:1px solid {t["outlineHairline"]}">'
            f'<div style="font-size:11px;opacity:.7;font-weight:600">sessions</div>{sessions}</div>')
    # The terminal block is the dark tier-0 well in BOTH schemes: the app's terminal grid is dark-only
    # (Ansi.kt has one palette), so the light app shows a dark terminal, as a terminal emulator would.
    td = d['dark']
    term = status_block(d, t, td['surfaceContainerLowest'], td['onSurface'])
    if scheme == 'light':
        term += (f'<div style="font-size:10.5px;color:{t["onSurfaceVariant"]};margin-top:-4px">terminal block: the dark tier-0 well with the dark ANSI-16;'
                 f' the grid is dark-only (Ansi.kt has one palette)</div>')
    box = t['surfaceContainerLowest'] if scheme == 'dark' else t['surfaceContainerHighest']
    mono = MONO_TRAY_ALPHAS
    tray = ('<div class="tray">'
            + tray_icon(t['stateIdle'], box, label='idle')
            + tray_icon(t['stateWorking'], box, label='working')
            + tray_icon(t['stateAttention'], box, badge=t['stateAttention'], label='attention')
            + f'<div style="width:1px;height:40px;background:{t["outlineVariant"]};margin:0 2px"></div>'
            + tray_icon(on, box, label=f'mono {mono["stateIdle"]:.0%}', opacity=mono['stateIdle'])
            + tray_icon(on, box, label=f'mono {mono["stateWorking"]:.0%}', opacity=mono['stateWorking'])
            + tray_icon(on, box, badge=on, label='mono 100%')
            + f'<div style="width:1px;height:40px;background:{t["outlineVariant"]};margin:0 2px"></div>'
            + tray_icon(t['inkMark'], t['boneTile'], label='tile')
            + '</div>')
    trayc = (f'<div class="card" style="background:{t["surfaceContainerLow"]};border:1px solid {t["outlineHairline"]}">'
             f'<div style="font-size:11px;opacity:.7;font-weight:600">tray at 40 px: by state, monochrome, tile (16 / 22 px strip under States)</div>{tray}</div>')
    return f'<div class="mock">{chat}{sess}<div style="display:flex;flex-direction:column;gap:14px">{term}{trayc}</div></div>'


def ansi_strip(d):
    a = d['ansi']
    pinned = {slot: tok for slot, tok in SLOT_PINS}
    cells = ''.join(
        f'<div style="background:{a[k]};color:{a["background"] if lstar(a[k]) > 50 else a["foreground"]}">'
        f'{k}<br><span class="mono">{a[k]}</span><br>{contrast(a[k], a["background"]):.1f}:1'
        + (f' <span style="opacity:.75">= {pinned[k]}</span>' if k in pinned else '') + '</div>' for k in ANSI_KEYS)
    lad = ansi_ladder(d)
    return (f'<div class="ansi">{cells}</div>'
            f'<div class="leg"><span><i class="sw" style="background:{a["background"]};border:1px solid {a["brightBlack"]}"></i>background {a["background"]}</span>'
            f'<span><i class="sw" style="background:{a["foreground"]}"></i>foreground {a["foreground"]} {contrast(a["foreground"], a["background"]):.1f}:1</span>'
            f'<span><i class="sw" style="background:{a["cursor"]}"></i>cursor {a["cursor"]}</span>'
            f'<span><i class="sw" style="background:{a["selection"]}"></i>selection {a["selection"]}</span>'
            f'<span>state ladder in 16 colours: {" &rarr; ".join(f"{k} L*{L:.0f}" for k, _, L in lad)}'
            f' (steps {", ".join(f"{abs(b[2] - a_[2]):.0f}" for a_, b in zip(lad, lad[1:]))}; min {ANSI_LADDER_MIN_DL:.0f})</span></div>')


def scheme_html(d, scheme):
    t = d[scheme]
    syn = d['syntax_' + scheme]
    body = (f'<section class="scheme" style="background:{t["background"]};color:{t["onBackground"]}">'
            + (f'<div style="display:flex;align-items:center;gap:18px"><svg style="width:64px;height:64px;color:{t["onBackground"]};flex:none"><use href="#raven"/></svg>'
               f'<div><h1>{d["name"]} <span style="opacity:.6;font-weight:500;font-size:18px">{d["id"]}</span></h1>'
               f'<p class="sub">{d["tagline"]} - <b>{scheme} scheme</b></p></div></div>' if scheme == 'dark'
               else f'<h1 style="font-size:22px">{d["name"]} - <span style="font-weight:500">light scheme</span></h1>')
            + '<h2>Ground and surface tiers (ink contrast on each)</h2>' + surfaces_html(t)
            + '<h2>Ink and outline tiers</h2>' + ink_html(t)
            + '<h2>Accents</h2>' + accents_html(t)
            + '<h2>States: tray, list dots, labels</h2>' + states_html(t, scheme)
            + '<h2>Syntax on the code ground (surfaceContainerLowest)</h2>' + syntax_html(t, syn)
            + '<h2>In use</h2>' + mock_html(t, d, scheme)
            + (('<h2>ANSI-16 on the terminal ground</h2>' + ansi_strip(d)) if scheme == 'dark' else '')
            + '</section>')
    return body


def swatches_html(d):
    return (f'<!doctype html><html lang="en"><head><meta charset="utf-8"><title>{d["name"]} swatches</title>'
            f'{FONT_LINK}<style>{CSS}</style></head><body style="background:{d["dark"]["background"]}">'
            f'{RAVEN_SYMBOL}{scheme_html(d, "dark")}{scheme_html(d, "light")}'
            f'<div class="foot" style="padding:14px 40px 24px;background:{d["light"]["background"]};color:{d["light"]["onSurfaceVariant"]}">'
            f'Generated by build/palette.py from build/spec.py. Ratios are WCAG 2.x; L* is CIELAB. Text pairs >= 4.5:1, UI pairs >= 3:1, '
            f'adjacent tray states >= {TRAY_MIN_DL:.0f} L* apart in the app tokens, the ANSI-16 ladder and the monochrome tints. See contrast.txt for the tools/contrast.py proof.</div>'
            f'</body></html>')


# ------------------------------------------------------------- terminal/
def ansi_json(d):
    a = d['ansi']
    return json.dumps({
        'name': f'Huginn {d["name"]}', 'id': d['id'],
        'background': a['background'], 'foreground': a['foreground'], 'cursor': a['cursor'], 'selection': a['selection'],
        'ansi16': {k: a[k] for k in ANSI_KEYS},
        'contrastOnGround': {k: round(contrast(a[k], a['background']), 2) for k in ANSI_KEYS},
        'lstar': {k: round(lstar(a[k]), 1) for k in ANSI_KEYS},
        'brand': {'accentSlot': d['accent_ansi'], 'accentTruecolor': d['dark']['accent'], 'states': d['state_ansi'],
                  'cli': d['cli'], 'slotPins': {slot: tok for slot, tok in SLOT_PINS},
                  'stateLadderLstar': {k: round(L, 1) for k, _, L in ansi_ladder(d)}, 'glyph': GLYPH},
    }, indent=2) + '\n'


def windows_terminal_json(d):
    a = d['ansi']
    return json.dumps({
        'name': f'Huginn {d["name"]}',
        'background': a['background'], 'foreground': a['foreground'],
        'cursorColor': a['cursor'], 'selectionBackground': a['selection'],
        'black': a['black'], 'red': a['red'], 'green': a['green'], 'yellow': a['yellow'],
        'blue': a['blue'], 'purple': a['magenta'], 'cyan': a['cyan'], 'white': a['white'],
        'brightBlack': a['brightBlack'], 'brightRed': a['brightRed'], 'brightGreen': a['brightGreen'],
        'brightYellow': a['brightYellow'], 'brightBlue': a['brightBlue'], 'brightPurple': a['brightMagenta'],
        'brightCyan': a['brightCyan'], 'brightWhite': a['brightWhite'],
    }, indent=2) + '\n'


def vhs_theme(d):
    a = d['ansi']
    theme = {'name': f'Huginn {d["name"]}',
             'background': a['background'], 'foreground': a['foreground'],
             'selection': a['selection'], 'cursor': a['cursor']}
    theme.update({k: a[k] for k in ANSI_KEYS})
    return theme


def vhs_json(d):
    return json.dumps(vhs_theme(d), indent=2) + '\n'


def vhs_tape_snippet(d):
    one = json.dumps(vhs_theme(d), separators=(', ', ': '))
    return (f'# assets/demo.tape - replace the line\n#     Set Theme "Catppuccin Mocha"\n# with this one (VHS wants the theme JSON on one line):\n'
            f'Set Theme {one}\n')


def tmux_snippet(d):
    a, t = d['ansi'], d['dark']
    acc, slot, g = t['accent'], d['accent_ansi'], GLYPH['unicode']
    return f'''# Huginn tmux status line - {d["name"]} ({d["id"]})
# Drop into server/tmux.conf in place of the current status-left line.
#
# The hex values are app tokens (tier 1 / tier 3 surfaces, onSurface, the accent);
# they need truecolor, which in tmux means BOTH of these (without the override tmux
# rounds every hex to the nearest of 256 colours):
#   set -g  default-terminal "tmux-256color"
#   set -as terminal-overrides ",*:Tc"          # tmux >= 3.2 may use: set -as terminal-features ",*:RGB"
# A 16-colour-only fallback with the same layout is at the bottom of this file.
set -g status-position bottom
set -g status-justify left
set -g status-style "bg={t["surfaceContainerLow"]},fg={t["onSurfaceVariant"]}"
set -g status-left-length 60
set -g status-right-length 60
# left: the one-row raven in the accent on the tier-3 surface, then the session name
# (the quiet default from ascii-04-lockups). The raven is `{g}`: U+25E2 U+2586 U+2588
# U+2580 (ascii-03-compact raven-1line-a); if the host font lacks Geometric Shapes use
# `{GLYPH["block"]}` (U+2597 first). The separator is U+258C. All single-width; no powerline
# glyphs (private-use, often missing).
set -g status-left "#[fg={acc},bg={t["surfaceContainerHigh"]},bold] {g} #[fg={t["onSurface"]},bg={t["surfaceContainerHigh"]},bold]#{{session_name}} #[fg={t["surfaceContainerHigh"]},bg={t["surfaceContainerLow"]}]\u258c#[default] "
set -g window-status-format "#[fg={t["onSurfaceVariant"]}] #I:#W "
set -g window-status-current-format "#[fg={t["onSurface"]},bold] #I:#W #[fg={acc}]\u258c#[default]"
set -g window-status-separator ""
# right: the keybind hints the current config shows, then host and clock
set -g status-right "#[fg={t["outline"]}]Alt-d:detach  Alt-o:solo  ^b[:scroll #[fg={t["onSurfaceVariant"]}] #H #[fg={acc},bold] %H:%M "
set -g pane-border-style "fg={t["outlineVariant"]}"
set -g pane-active-border-style "fg={acc}"
set -g message-style "bg={t["surfaceContainerHigh"]},fg={t["onSurface"]}"
set -g message-command-style "bg={t["surfaceContainerHigh"]},fg={acc}"
set -g mode-style "bg={a["selection"]},fg={t["onSurface"]}"
set -g clock-mode-colour "{acc}"

# ---- 16-colour fallback: no truecolor, ANSI names only. With {d["slug"]}.ansi.json
# installed in the terminal these are black={a["black"]} (outlineVariant), brightblack={a["brightBlack"]}
# (outline), white={a["white"]}, {slot}={a[slot]} (the accent slot); on any other palette
# they are that palette's colours.
# set -g status-style "bg=black,fg=brightblack"
# set -g status-left "#[fg={slot},bg=brightblack,bold] {g} #[fg=white,bg=brightblack,bold]#{{session_name}} #[fg=brightblack,bg=black]\u258c#[default] "
# set -g window-status-format "#[fg=brightblack] #I:#W "
# set -g window-status-current-format "#[fg=white,bold] #I:#W #[fg={slot}]\u258c#[default]"
# set -g status-right "#[fg=brightblack]Alt-d:detach  Alt-o:solo  ^b[:scroll #[fg=white] #H #[fg={slot},bold] %H:%M "
# set -g pane-border-style "fg=brightblack"
# set -g pane-active-border-style "fg={slot}"
# set -g message-style "bg=brightblack,fg=white"
# set -g clock-mode-colour "{slot}"
'''


def legacy_table_lines(d, prefix):
    """Header rows for the CLI files: each semantic's slot with its ratio on every legacy palette."""
    names = list(LEGACY_PALETTES)
    a, c = d['ansi'], d['cli']
    head = f'{prefix}  {"semantic":8} {"slot":13} {"SGR":4} {"with the Huginn scheme":30} ' + ' '.join(f'{n:>15}' for n in names)
    lines = [head]
    for sem in c:
        slot = c[sem]
        hx = a[slot]
        own = f'{hx} {contrast(hx, a["background"]):.1f}:1 on {a["background"]}'
        base = f'{prefix}  {sem:8} {slot:13} {SGR[slot]:<4} {own:30} '
        cells = []
        for n in names:
            p_ = LEGACY_PALETTES[n]
            cells.append(f'{p_[slot]} {contrast(p_[slot], p_["ground"]):.1f}:1')
        lines.append(base + ' '.join(f'{x:>15}' for x in cells))
    grounds = f'{prefix}  {"":8} {"":13} {"":4} {"(ground of each legacy palette)":30} ' + ' '.join(f'{LEGACY_PALETTES[n]["ground"]:>15}' for n in names)
    lines.append(grounds)
    return lines


def utf8_octal(s):
    return ''.join(f'\\{b:03o}' for b in s.encode('utf-8'))


def cli_sh(d):
    a, c = d['ansi'], d['cli']
    rows16 = '\n'.join(f'      H_{k.upper()}=$(printf \'\\033[{SGR[c[k]]}m\')' for k in c)
    rows24 = '\n'.join(f'      H_{k.upper()}=$(printf \'\\033[{tc(cli_truecolor_hex(d, k))}m\')   # {cli_truecolor_hex(d, k)}' for k in c)
    table = '\n'.join(legacy_table_lines(d, '#'))
    acc_note = ('the brand accent itself' if d['dark']['accent'] != a[c['accent']]
                else 'the accent slot, which is the brand accent')
    return f'''#!/usr/bin/env bash
# Huginn CLI colours - {d["name"]} ({d["id"]})
# For client/huginn.sh (inline it: huginn.sh is one shipped file) and server/bin/huginn-status.
# PURE 7-BIT ASCII, no BOM: huginn update ships huginn.sh over scp and Windows PowerShell
# 5.1 mis-decodes anything else (CONTRIBUTING.md, Dev notes). The generator refuses to
# write this file if it contains a non-ASCII byte; the raven below is built from escapes.
# Same rules as huginn-status today: colour only on a TTY and only while NO_COLOR is unset.
#
# The 16-colour branch inherits the user's palette, so it uses the BRIGHT slots: on every
# legacy palette the dark red is under 2:1 on its own ground (conhost #800000 on #000000
# is 1.9:1), while Red / Yellow / Green / DarkGray are what huginn.ps1 already prints.
# Ratios of each slot on the legacy palettes it must survive (tools/contrast.py numbers;
# the minimum is 3:1, text in the apps and the Huginn scheme is 4.5:1):
{table}
#   (PowerShell 5.1's own console remaps DarkYellow to #EEEDF0 and uses #012456 as the
#   ground, so an accent in slot 33 shows there as near-white: legible, not gold.)
# The truecolor branch pins the brand hex: H_ACCENT is {acc_note} ({d["dark"]["accent"]}).
if [ -t 1 ] && [ -z "${{NO_COLOR:-}}" ]; then
  case "${{COLORTERM:-}}" in
    truecolor|24bit)
{rows24}
      ;;
    *)
{rows16}
      ;;
  esac
  H_BOLD=$(printf '\\033[1m'); H_RESET=$(printf '\\033[0m')
else
  H_OK=; H_WARN=; H_ERROR=; H_MUTED=; H_ACCENT=; H_BOLD=; H_RESET=
fi
# The one-row raven, both forms (the brief: CLI-facing terminal art ships Unicode and 7-bit
# ASCII). H_GLYPH_ASCII is the head row of the help-screen raven huginn.sh already prints.
# H_GLYPH is the ascii dimension's pick U+25E2 U+2586 U+2588 U+2580 (tail wedge, body, raised
# head, bill) when the locale is UTF-8, else the ASCII form; H_GLYPH_BLOCK swaps the wedge
# for U+2597 for fonts without Geometric Shapes.
H_GLYPH_ASCII='{GLYPH["ascii"]}'
case "${{LC_ALL:-${{LC_CTYPE:-${{LANG:-}}}}}}" in
  *[Uu][Tt][Ff]-8*|*[Uu][Tt][Ff]8*) H_GLYPH=$(printf '{utf8_octal(GLYPH["unicode"])}') ;;
  *) H_GLYPH=$H_GLYPH_ASCII ;;
esac
H_GLYPH_BLOCK=$(printf '{utf8_octal(GLYPH["block"])}')
# usage:  printf '%s%s%s %s\\n' "$H_ACCENT" "$H_GLYPH" "$H_RESET" "huginn - thought, on call from anywhere."
#         printf '%sok%s link up\\n' "$H_OK" "$H_RESET"
#         printf '%shuginn: link dropped - reconnecting%s\\n' "$H_WARN" "$H_RESET"
'''


def cli_ps1(d):
    a, c = d['ansi'], d['cli']
    rows16 = '\n'.join(f'    {k.capitalize():7}= "$e[{SGR[c[k]]}m"' for k in c)
    rows24 = '\n'.join(f'    {k.capitalize():7}= "$e[{tc(cli_truecolor_hex(d, k))}m"   # {cli_truecolor_hex(d, k)}' for k in c)
    rowsfg = '\n'.join(f'  {k.capitalize():7}= \'{CONSOLE_NAME[c[k]]}\'' for k in c)
    table = '\n'.join(legacy_table_lines(d, '#'))
    chars = lambda s: ', '.join(f'0x{ord(ch):04X}' for ch in s)  # noqa: E731
    return f'''# Huginn CLI colours - {d["name"]} ({d["id"]})
# For client/huginn.ps1 (inline it). PURE 7-BIT ASCII, no BOM: the file goes over scp and
# Windows PowerShell 5.1 mis-decodes anything else; the generator refuses non-ASCII here.
# $HuginnColor: ConsoleColor names for Write-Host -ForegroundColor, the BRIGHT slots, which
# are what huginn.ps1 prints today (Red / Yellow / Green / DarkGray): the dark ones are
# unreadable on the default consoles (DarkRed #800000 on #000000 is 1.9:1). $HuginnSgr:
# escape sequences, 16-colour or truecolor. Ratios on the palettes they must survive
# (tools/contrast.py; minimum 3:1, the Huginn scheme itself is >= 4.5:1):
{table}
#   PowerShell 5.1's console remaps DarkYellow to #EEEDF0 on its #012456 ground, so an
#   accent in slot 33 / DarkYellow shows there as near-white: legible, not gold. Install
#   the Windows Terminal scheme beside this file for the brand hex.
$e = [char]27
$HuginnColor = @{{
{rowsfg}
}}
$HuginnSgr = if (-not $Host.UI.SupportsVirtualTerminal -or $env:NO_COLOR) {{
  @{{ Ok=''; Warn=''; Error=''; Muted=''; Accent=''; Bold=''; Reset='' }}
}} elseif ($env:COLORTERM -in 'truecolor','24bit' -or $env:WT_SESSION) {{
  @{{
{rows24}
    Bold   = "$e[1m"; Reset = "$e[0m"
  }}
}} else {{
  @{{
{rows16}
    Bold   = "$e[1m"; Reset = "$e[0m"
  }}
}}
# The one-row raven, both forms. $HuginnGlyphAscii is the head row of the help-screen raven
# huginn.ps1 already prints; $HuginnGlyph is the ascii dimension's pick (U+25E2 U+2586 U+2588
# U+2580) when the console writes UTF-8 (code page 65001, or Windows Terminal), else ASCII;
# $HuginnGlyphBlock swaps the wedge for U+2597 for fonts without Geometric Shapes.
$HuginnGlyphAscii = '{GLYPH["ascii"]}'
$HuginnGlyphBlock = -join [char[]]({chars(GLYPH["block"])})
$HuginnGlyph = if ([Console]::OutputEncoding.CodePage -eq 65001 -or $env:WT_SESSION) {{
  -join [char[]]({chars(GLYPH["unicode"])})
}} else {{ $HuginnGlyphAscii }}
# usage:  Write-Host "huginn: link dropped - reconnecting" -ForegroundColor $HuginnColor.Warn
#         "$($HuginnSgr.Accent)$HuginnGlyph$($HuginnSgr.Reset) huginn - thought, on call from anywhere."
'''


TERM_CSS = """
*{box-sizing:border-box} body{margin:0;font-family:%(ui)s}
.win{margin:28px auto 0;width:1120px;border-radius:8px;overflow:hidden;border:1px solid __BORDER__}
.tabs{height:36px;display:flex;align-items:flex-end;padding:0 10px;gap:2px;font-size:12px}
.tab{padding:7px 14px 8px;border-radius:6px 6px 0 0;display:flex;align-items:center;gap:8px}
.tab i{width:7px;height:7px;border-radius:50%%;display:inline-block}
pre{margin:0;font-family:%(mono)s;font-variant-ligatures:none;font-size:13.5px;line-height:1.5;padding:16px 20px 12px;white-space:pre}
.status{font-family:%(glyph)s;font-size:13.5px;line-height:1.5;display:flex;white-space:pre}
.strip{display:grid;grid-template-columns:repeat(8,1fr);gap:0;font-family:%(mono)s;font-variant-ligatures:none;font-size:10.5px}
.strip div{height:46px;padding:5px 8px;line-height:1.25}
.cap{width:1120px;margin:14px auto 26px;font-size:12px;font-family:%(ui)s;line-height:1.5}
.cap .g{font-family:%(glyph)s}
b{font-weight:700}
""" % {'ui': UI_FONT, 'mono': MONO_FONT, 'glyph': GLYPH_FONT}


def terminal_mock_html(d):
    a, t, c = d['ansi'], d['dark'], d['cli']
    acc, g = t['accent'], GLYPH['unicode']

    def s(k, txt, bold=False):
        return f'<span style="color:{a[k]}{";font-weight:700" if bold else ""}">{txt}</span>'
    dim = lambda x: s('brightBlack', x)  # noqa: E731
    sa = d['state_ansi']
    pre = '\n'.join([
        f'{dim("~/huginn")} {s("green", "feat/brand-colour")} {dim("$")} git status',
        f'On branch {s("cyan", "feat/brand-colour")}',
        'Changes to be committed:',
        f'  {dim("(use \"git restore --staged <file>...\" to unstage)")}',
        f'        {s("green", "modified:   mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/theme/Theme.kt")}',
        f'        {s("green", "new file:   assets/brand/explorations/color/README.md")}',
        'Changes not staged for commit:',
        f'        {s("red", "modified:   assets/demo.tape")}',
        f'        {s("red", "modified:   server/tmux.conf")}',
        'Untracked files:',
        f'        {s("red", "docs/palette.md")}',
        '',
        f'{dim("~/huginn")} {s("green", "feat/brand-colour")} {dim("$")} npm test',
        f'{dim("> huginn@1.4.0 test")}',
        f'{s("green", "ok")}    palette: every text pair >= 4.5:1               {dim("12 ms")}',
        f'{s("green", "ok")}    palette: tray states >= 12 L* apart            {dim("3 ms")}',
        f'{s("green", "ok")}    tmux: status-left is single-width glyphs       {dim("1 ms")}',
        f'{s("red", "FAIL", True)}  demo.tape: theme is still Catppuccin Mocha      {dim("0 ms")}',
        f'      {s("red", "expected")} {s("yellow", "Huginn " + d["name"])}  {s("red", "received")} {s("yellow", "Catppuccin Mocha")}',
        f'{s("yellow", "warn")}  Ansi.kt: palette[0..15] differs from {d["slug"]}.ansi.json',
        f'{s("brightWhite", "3 passed", True)}, {s("brightRed", "1 failed", True)}, {s("brightYellow", "1 warning")}  {dim("(4 total, 16 ms)")}',
        '',
        f'{dim("~/huginn")} {s("green", "feat/brand-colour")} {dim("$")} huginn status',
        f'<span style="color:{a[c["accent"]]};font-family:{GLYPH_FONT}">{g}</span> {s(c["accent"], "huginn", True)} {dim("- thought, on call from anywhere.")}',
        f'{s(c["accent"], "host", True)}      ravenhost       {dim("up 12d 4h  appd 1.4.0  3 devices")}',
        f'{s(c["accent"], "sessions", True)}  3',
        f'  costtracking   {s(sa["stateWorking"], "working")}    claude  {dim("12m   phone + desktop")}',
        f'  brandbook      {s(sa["stateAttention"], "attention", True)}  {s(sa["stateAttention"], "permission: Edit Theme.kt")}',
        f'  scratch        {s(sa["stateIdle"], "idle")}       {dim("2h")}',
        f'{s(c["ok"], "ok")} link up   {s(c["warn"], "warn")} 1 device stale   {s(c["error"], "error")} none   {s("magenta", "llm")} local tier off',
        f'{dim("~/huginn")} {s("green", "feat/brand-colour")} {dim("$")} <span style="background:{a["cursor"]};color:{a["background"]}">&nbsp;</span>',
    ])
    status = (f'<div class="status" style="background:{t["surfaceContainerLow"]};color:{t["onSurfaceVariant"]}">'
              f'<span style="background:{t["surfaceContainerHigh"]};color:{acc};font-weight:700"> {g} </span>'
              f'<span style="background:{t["surfaceContainerHigh"]};color:{t["onSurface"]};font-weight:700">costtracking </span>'
              f'<span style="color:{t["surfaceContainerHigh"]}">&#9612;</span>'
              f'<span style="color:{t["onSurface"]};font-weight:700"> 1:claude </span><span style="color:{acc}">&#9612;</span>'
              f'<span> 2:bash </span><span> 3:logs </span>'
              f'<span style="flex:1"></span>'
              f'<span style="color:{t["outline"]}">Alt-d:detach  Alt-o:solo  ^b[:scroll </span>'
              f'<span> ravenhost </span><span style="color:{acc};font-weight:700"> 23:41 </span></div>')
    strip = ''.join(
        f'<div style="background:{a[k]};color:{a["background"] if lstar(a[k]) > 50 else a["foreground"]}">'
        f'{k}<br>{a[k]}<br>{contrast(a[k], a["background"]):.1f}:1</div>' for k in ANSI_KEYS)
    css = TERM_CSS.replace('__BORDER__', t['outlineVariant'])
    return (f'<!doctype html><html lang="en"><head><meta charset="utf-8"><title>{d["name"]} terminal</title>'
            f'{FONT_LINK}<style>{css}</style></head><body style="background:{t["surfaceContainer"]};color:{t["onSurfaceVariant"]}">'
            f'<div class="win"><div class="tabs" style="background:{t["surfaceContainerLow"]};color:{t["onSurfaceVariant"]}">'
            f'<div class="tab" style="background:{a["background"]};color:{t["onSurface"]}"><i style="background:{a[sa["stateWorking"]]}"></i>costtracking</div>'
            f'<div class="tab"><i style="background:{a[sa["stateAttention"]]}"></i>brandbook</div>'
            f'<div class="tab"><i style="border:1px solid {t["outline"]}"></i>notes</div>'
            f'<div class="tab" style="padding:7px 10px 8px">+</div>'
            f'<span style="margin-left:auto;padding:0 6px 9px;font-size:11px">{d["name"]}</span></div>'
            f'<pre style="background:{a["background"]};color:{a["foreground"]}">{pre}</pre>{status}'
            f'<div class="strip">{strip}</div></div>'
            f'<div class="cap">ANSI-16 of <b>{d["id"]}</b> on its ground {a["background"]}; every slot but black is >= 3:1 '
            f'(see {d["slug"]}.ansi.json). The tab strip is drawn in the direction\'s own tiers with the state dots; the bottom row is the tmux '
            f'status line from tmux.conf.snippet. The one-row raven <span class="g">{g}</span> is the ascii dimension\'s pick (ascii-03-compact), four single-width cells, '
            f'set here in DejaVu Sans Mono, which has U+25E2.</div></body></html>')


def terminal_readme(d):
    a, c = d['ansi'], d['cli']
    rows = '\n'.join(f'| {k} | `{a[k]}` | {contrast(a[k], a["background"]):.2f}:1 | {lstar(a[k]):.0f} |'
                     + (f' = `{dict(SLOT_PINS)[k]}` |' if k in dict(SLOT_PINS) else ' |') for k in ANSI_KEYS)
    lad = ansi_ladder(d)
    names = list(LEGACY_PALETTES)
    leg_head = '| semantic | slot | SGR | ConsoleColor | Huginn scheme | ' + ' | '.join(names) + ' |\n|---|---|---|---|---|' + '---|' * len(names)
    leg_rows = '\n'.join(
        f'| {sem} | {c[sem]} | {SGR[c[sem]]} | {CONSOLE_NAME[c[sem]]} | `{a[c[sem]]}` {contrast(a[c[sem]], a["background"]):.1f}:1 | '
        + ' | '.join(f'`{LEGACY_PALETTES[n][c[sem]]}` {contrast(LEGACY_PALETTES[n][c[sem]], LEGACY_PALETTES[n]["ground"]):.1f}:1' for n in names) + ' |'
        for sem in c)
    acc_tc = cli_truecolor_hex(d, 'accent')
    acc_line = (f'the truecolor branch pins the ember `{acc_tc}` itself, which has no ANSI slot (the yellow slot is an amber so git stays yellow)'
                if acc_tc != a[c['accent']] else f'the truecolor branch pins `{acc_tc}`, the same hex as the slot')
    return f'''# {d["name"]} - terminal layer

![terminal preview](preview.png)

The CLI, tmux, the VHS demo and the app's own terminal grid (`mobile/core/.../ui/Ansi.kt`)
all read this one ANSI-16 palette. Ground `{a["background"]}` and foreground `{a["foreground"]}`
are the app's `background` / `onBackground`; the brand accent sits in the **{d["accent_ansi"]}** slot
(`{a[d["accent_ansi"]]}`) and is also the cursor, so a plain `\\033[{SGR[d["accent_ansi"]]}m`
is on-brand without truecolor. Four slots are *pinned* to app tokens (rightmost column), so the
CLI, tmux and the app never show two different reds: the terminal's error is the app's error.

| slot | hex | on ground | L* | pinned to |
|---|---|---|---|---|
{rows}

cursor `{a["cursor"]}` - selection `{a["selection"]}`.

States as ANSI slots: {", ".join(f"{STATE_LABEL[k]} = {v}" for k, v in d["state_ansi"].items())}.
The 16-colour state ladder is {" -> ".join(f"{k} L* {L:.0f}" for k, _, L in lad)}
(steps {", ".join(f"{abs(b[2] - a_[2]):.0f}" for a_, b in zip(lad, lad[1:]))}; the rule is >= {ANSI_LADDER_MIN_DL:.0f}, checked by `build/check.py`).

## The CLI's five colours

`cli-colors.sh` / `cli-colors.ps1` are pure 7-bit ASCII (they are inlined into `client/huginn.sh`
and `huginn.ps1`, which must stay ASCII) and carry two branches. The 16-colour branch inherits
the user's palette, so it uses the **bright** slots - the dark red of every default palette is
under 2:1 on its own ground - and these are the colours `huginn.ps1` prints today. The ratios on
the legacy palettes the branch must survive (minimum 3:1):

{leg_head}
{leg_rows}

Grounds: {", ".join(f"{n} `{LEGACY_PALETTES[n]['ground']}`" for n in names)}. PowerShell 5.1's console
remaps DarkYellow to `#EEEDF0`, so an accent in slot 33 shows there as near-white (legible, not
gold). For `accent`, {acc_line}.

## The one-row raven

The tmux segment and the CLI banner use the ascii dimension's pick, `{GLYPH["unicode"]}`
([`ascii-03-compact/raven-1line-a.txt`](../../../ascii/ascii-03-compact/)): tail wedge, body,
raised head, bill; four single-width cells. `{GLYPH["block"]}` is its fallback for fonts without
Geometric Shapes (U+25E2). `{GLYPH["ascii"]}` is the 7-bit form the clients print: the head row of
the help-screen raven already in `huginn.sh`. `glyph.txt` lists the three, one block each, and
passes `tools/asciicheck.py`. (The colour dimension's own earlier three-cell glyph was dropped:
it rendered as a staircase, and two dimensions must not ship two ravens for one tmux line.)

## Files

| file | consumer |
|---|---|
| `{d["slug"]}.ansi.json` | source of truth; `Ansi.kt` palette[0..15], any emulator importer |
| `{d["slug"]}.windows-terminal.json` | paste into Windows Terminal `settings.json` -> `schemes` |
| `{d["slug"]}.vhs.json`, `{d["slug"]}.tape.snippet` | the `Set Theme` line for `assets/demo.tape` |
| `tmux.conf.snippet` | status line for `server/tmux.conf` (truecolor block with the `Tc` override, and a 16-colour fallback) |
| `cli-colors.sh`, `cli-colors.ps1` | the five semantic colours and the glyph for `client/huginn.sh`, `server/bin/huginn-status`, `client/huginn.ps1` |
| `glyph.txt` | the one-row raven: Unicode, block-only, ASCII |
| `mock.html` -> `preview.png` | the render above: git status, a test run, `huginn status`, the tmux line, the 16 slots |
'''


def glyph_txt():
    return GLYPH['unicode'] + '\n\n' + GLYPH['block'] + '\n\n' + GLYPH['ascii'] + '\n'


# ------------------------------------------------------------------- main
def main():
    bad = 0
    for d in DIRECTIONS:
        print('check', d['id'])
        f = check(d)
        bad += len(f)
        for x in f:
            print('   FAIL', x)
    if bad:
        print('refusing to emit: rules fail')
        sys.exit(1)
    for d in DIRECTIONS:
        out = os.path.join(ROOT, d['id'])
        print('emit', d['id'])
        write(os.path.join(out, 'tokens.json'), tokens_json(d))
        write(os.path.join(out, 'contrast.txt'), contrast_txt(d))
        write(os.path.join(out, 'swatches.html'), swatches_html(d))
        term = os.path.join(out, 'terminal')
        write(os.path.join(term, f'{d["slug"]}.ansi.json'), ansi_json(d))
        write(os.path.join(term, f'{d["slug"]}.windows-terminal.json'), windows_terminal_json(d))
        write(os.path.join(term, f'{d["slug"]}.vhs.json'), vhs_json(d))
        write(os.path.join(term, f'{d["slug"]}.tape.snippet'), vhs_tape_snippet(d))
        write(os.path.join(term, 'tmux.conf.snippet'), tmux_snippet(d))
        write(os.path.join(term, 'cli-colors.sh'), cli_sh(d), ascii_only=True)
        write(os.path.join(term, 'cli-colors.ps1'), cli_ps1(d), ascii_only=True)
        write(os.path.join(term, 'mock.html'), terminal_mock_html(d))
        write(os.path.join(term, 'README.md'), terminal_readme(d))
        write(os.path.join(term, 'glyph.txt'), glyph_txt())


if __name__ == '__main__':
    main()
