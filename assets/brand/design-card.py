#!/usr/bin/env python3
"""Build assets/brand/design-card.html, the design reference for what Huginn ships.

    python3 assets/brand/design-card.py            # writes design-card.html beside this file
    python3 assets/brand/design-card.py --body OUT # also writes the page body alone (for publishing)

The card is generated so it cannot drift from the product. Every colour comes from
the frozen tokens (source/color/color-04-moss-heather/tokens.json and the
ANSI file beside them), every state from server/lib/state-table.json, every mark
from the shipped SVGs in this folder and round3/fold-system. The type scale, the
spacing and radius scale and the row metrics are the numbers in
mobile/ui/.../theme/Theme.kt and ui/system/Scale.kt; change them there first, then
here. Contrast is WCAG 2.x against each scheme's background.

The card describes what is decided and in place. History, alternatives and
explorations are on the archive/design-history branch and in BRAND.md's record, not here.
"""
import html, json, os, re, sys

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.normpath(os.path.join(HERE, '..', '..'))
TOK = os.path.join(HERE, 'source/color/color-04-moss-heather/tokens.json')
ANSI = os.path.join(HERE, 'source/color/color-04-moss-heather/terminal/moss-heather.ansi.json')
STATES = os.path.join(ROOT, 'server/lib/state-table.json')
FOLD = os.path.join(HERE, 'source/round3/fold-system')

# ---------------------------------------------------------------- colour maths (WCAG 2.x)
def _rgb(h): h = h.lstrip('#'); return [int(h[i:i + 2], 16) / 255 for i in (0, 2, 4)]
def _lin(c): return c / 12.92 if c <= 0.03928 else ((c + 0.055) / 1.055) ** 2.4
def lum(h): r, g, b = (_lin(c) for c in _rgb(h)); return 0.2126 * r + 0.7152 * g + 0.0722 * b
def contrast(a, b): x, y = sorted((lum(a), lum(b)), reverse=True); return (x + 0.05) / (y + 0.05)
def lstar(h):
    y = lum(h); e = (6 / 29) ** 3
    return 116 * (y ** (1 / 3) if y > e else y / (3 * (6 / 29) ** 2) + 4 / 29) - 16

# ---------------------------------------------------------------- sources
tokens = json.load(open(TOK))
D = {k: (v['hex'] if isinstance(v, dict) else v) for k, v in tokens['schemes']['dark'].items()}
L = {k: (v['hex'] if isinstance(v, dict) else v) for k, v in tokens['schemes']['light'].items()}
SYN = tokens['syntax']
ansi = json.load(open(ANSI))
A = ansi['ansi16']
state_table = json.load(open(STATES))['states']

def svg_path(path, name):
    s = open(os.path.join(path, name)).read()
    return re.search(r' d="([^"]+)"', s).group(1)
MARK = svg_path(HERE, 'raven.svg')
SOLID = svg_path(FOLD, 'mark-32.svg')
HEAD = svg_path(FOLD, 'mark-16.svg')
TILE = svg_path(FOLD, 'glyph-c.svg')
lock = open(os.path.join(HERE, 'lockup-dark.svg')).read()
LOCK_VB = re.search(r'viewBox="([^"]+)"', lock).group(1)
LOCK_BODY = re.sub(r'<!--.*?-->', '', lock[lock.index('>') + 1:lock.rindex('</svg>')], flags=re.S)
LOCK_BODY = re.sub(r'fill="#[0-9A-Fa-f]{6}"', 'fill="currentColor"', LOCK_BODY).strip()

def versions():
    out = {}
    try:
        out['CLI'] = re.search(r"HUGINN_VERSION='([^']+)'", open(os.path.join(ROOT, 'client/huginn.sh')).read()).group(1)
        out['Android'] = re.search(r'versionName\s*=\s*"([^"]+)"', open(os.path.join(ROOT, 'mobile/app/build.gradle.kts')).read()).group(1)
        out['desktop'] = re.search(r'^## \[?([0-9.]+)', open(os.path.join(ROOT, 'mobile/app-desktop/CHANGELOG.md')).read(), re.M).group(1)
        out['appd'] = re.search(r'^## \[?([0-9.]+)', open(os.path.join(ROOT, 'server/appd/CHANGELOG.md')).read(), re.M).group(1)
    except Exception:
        pass
    return out
V = versions()

E = html.escape

# ---------------------------------------------------------------- marks
def mark(d, size, tr='translate(2,11)', vb='0 0 108 108', cls=''):
    t = f'<g transform="{tr}">' if tr else '<g>'
    return (f'<svg class="{cls}" width="{size}" height="{size}" viewBox="{vb}" aria-hidden="true">{t}'
            f'<path fill="currentColor" fill-rule="evenodd" d="{d}"/></g></svg>')

def tile(size, color, badge=None, label=''):
    b = f'<circle cx="53" cy="11" r="9" fill="{badge}"/>' if badge else ''
    return (f'<svg width="{size}" height="{size}" viewBox="0 0 64 64" role="img" aria-label="{E(label)}">'
            f'<path fill="{color}" fill-rule="evenodd" d="{TILE}"/>{b}</svg>')

def lockup(height, cls='lockup'):
    return f'<svg class="{cls}" height="{height}" viewBox="{LOCK_VB}" role="img" aria-label="huginn">{LOCK_BODY}</svg>'

# ---------------------------------------------------------------- palette rows
GROUPS = [
    ('Ground and surfaces', 'Five tiers above and below the ground. Tier 0 sits below the page for code wells and the terminal; each tier up is one step lighter in dark, one step darker in light.', [
        ('background', 'the app ground; surface is the same value'),
        ('surfaceContainerLowest', 'tier 0: code wells, the terminal block'),
        ('surfaceContainerLow', 'tier 1: list rows, chat bubbles, the selected row'),
        ('surfaceContainer', 'tier 2: cards, sheets'),
        ('surfaceContainerHigh', 'tier 3: dialogs, menus, raised cards'),
        ('surfaceContainerHighest', 'tier 4: chips, inputs on a dialog'),
    ]),
    ('Ink and lines', 'Text in two weights of emphasis; lines in three, from a 3:1 stroke down to a row divider.', [
        ('onSurface', 'primary text'),
        ('onSurfaceVariant', 'secondary text, labels, timestamps, notices'),
        ('outline', 'strokes at 3:1: fields, focus-adjacent borders; the normal step of a meter'),
        ('outlineVariant', 'decorative borders, card edges'),
        ('outlineHairline', 'dividers between rows, under a list header'),
    ]),
    ('Accent and voices', 'Moss is the one accent: live and you. Heather is the second voice for code, links and info. The rose is a third voice for markers and series only.', [
        ('primary', 'the accent: working, the selected item, links, the cursor, the one filled Send'),
        ('onPrimary', 'text on a primary fill'),
        ('primaryContainer', 'selected row tint, the active tab, a selected chip, the bottom-bar indicator'),
        ('onPrimaryContainer', 'text on primaryContainer'),
        ('secondary', 'heather: functions, links in code, CLI headings, info'),
        ('onSecondary', 'text on a secondary fill'),
        ('secondaryContainer', 'tinted secondary container'),
        ('onSecondaryContainer', 'text on secondaryContainer'),
        ('tertiary', 'subagent markers, graph series'),
    ]),
    ('Failure', 'Red means something failed, or a verb destroys something. It never means "needs you".', [
        ('error', 'failures, destructive verbs and their confirm buttons'),
        ('onError', 'text on an error fill'),
        ('errorContainer', 'error surface: a banner, a toast'),
        ('onErrorContainer', 'text on errorContainer'),
    ]),
    ('State ladder', 'Waiting, working and needs-you are one family at three lightness steps, at least 12 L* apart, so a monochrome tray and a colour-blind reader still tell them apart. Ok, error and info are told apart by hue.', [
        ('stateIdle', 'waiting: the quiet dot, a reachable machine, the idle tray tile'),
        ('stateWorking', 'working: the live dot, the pulse, the working tray tile'),
        ('stateAttention', 'needs you: the dot, the word, the tray tile plus badge, a warning meter'),
        ('stateSuccess', 'ok: turn finished, tests passed, a proven route'),
        ('stateError', 'error: failed, disconnected (= error)'),
        ('stateInfo', 'info: news that asks nothing (= secondary)'),
    ]),
    ('The mark', 'The raven and its tile, in every scheme.', [
        ('inkMark', 'the raven on light grounds; the icon ink'),
        ('boneTile', 'the icon tile; the raven on dark grounds'),
    ]),
]
SURFACE_KEYS = {'background', 'surface', 'surfaceContainerLowest', 'surfaceContainerLow', 'surfaceContainer',
                'surfaceContainerHigh', 'surfaceContainerHighest', 'inkMark', 'boneTile', 'outlineVariant',
                'outlineHairline', 'primaryContainer', 'secondaryContainer', 'errorContainer', 'onPrimary',
                'onSecondary', 'onError'}

def chip(hexv, scheme, key, on='background'):
    ground = (D if scheme == 'dark' else L)[on]
    note = f'L* {lstar(hexv):.0f}'
    if key not in SURFACE_KEYS:
        note += f' · {contrast(hexv, ground):.1f}:1'
    return (f'<div class="sw"><span class="chip" style="background:{hexv}"></span>'
            f'<span class="swt"><button class="hex" type="button" data-copy="{hexv}" title="Copy {hexv}">{hexv}</button>'
            f'<span class="swn">{note}</span></span></div>')

def palette_section():
    out = []
    for title, lead, rows in GROUPS:
        body = ''.join(
            f'<div class="prow"><div class="pname"><code>{k}</code><span>{E(use)}</span></div>'
            f'<div class="pcell dark">{chip(D[k], "dark", k)}</div><div class="pcell light">{chip(L[k], "light", k)}</div></div>'
            for k, use in rows)
        out.append(f'<div class="pgroup"><h3>{E(title)}</h3><p class="lead">{E(lead)}</p>'
                   f'<div class="ptable"><div class="prow phead"><div>Token</div><div>Dark</div><div>Light</div></div>{body}</div></div>')
    return ''.join(out)

def tiers_demo(s, name):
    tiers = ['surfaceContainerLowest', 'background', 'surfaceContainerLow', 'surfaceContainer', 'surfaceContainerHigh', 'surfaceContainerHighest']
    labels = ['tier 0', 'ground', 'tier 1', 'tier 2', 'tier 3', 'tier 4']
    cells = ''.join(f'<div class="tier" style="background:{s[t]};color:{s["onSurface"]}"><b>{lab}</b>'
                    f'<span style="color:{s["onSurfaceVariant"]}">{s[t]}</span></div>' for t, lab in zip(tiers, labels))
    return f'<div class="tiers" aria-label="{name} surface tiers">{cells}</div>'

# ---------------------------------------------------------------- states
STATE_TOKEN = {'running': 'stateWorking', 'needsYou': 'stateAttention', 'waiting': 'stateIdle',
               'ok': 'stateSuccess', 'error': 'stateError', 'info': 'stateInfo', 'shell': 'outline'}
WIRE = {'running': '<code>running</code>', 'needsYou': '<code>attention</code>', 'waiting': '<code>idle</code>',
        'ok': '', 'error': '<code>error</code>, <code>failed</code>', 'info': '', 'shell': 'anything else'}
SGR_NAME = {30: 'black', 31: 'red', 32: 'green', 33: 'yellow', 34: 'blue', 35: 'magenta', 36: 'cyan', 37: 'white',
            90: 'brightBlack', 91: 'brightRed', 92: 'brightGreen', 93: 'brightYellow', 94: 'brightBlue',
            95: 'brightMagenta', 96: 'brightCyan', 97: 'brightWhite'}

def state_rows():
    rows = ''
    for st in state_table:
        tk = STATE_TOKEN[st['key']]
        quiet = st['key'] in ('waiting', 'shell')
        dot = f'<span class="dot{" quiet" if quiet else ""}" style="background:var(--{tk})"></span>'
        rows += (f'<tr><td>{dot}<b>{E(st["word"])}</b></td><td>{WIRE[st["key"]]}</td><td><code>{tk}</code></td>'
                 f'<td class="g">{E(st["glyph"])}</td><td class="g">{E(st["mark"])}</td><td class="g">{E(st.get("oneRow", ""))}</td>'
                 f'<td><code>{st["sgr"]}</code> {SGR_NAME.get(st["sgr"], "")}</td><td>{E(st["console"])}</td></tr>')
    return rows

# ---------------------------------------------------------------- terminal
PINS = {'brightRed': 'error', 'brightCyan': 'stateSuccess', 'brightYellow': 'stateAttention', 'brightMagenta': 'stateInfo'}
def ansi_grid():
    keys = ['black', 'red', 'green', 'yellow', 'blue', 'magenta', 'cyan', 'white',
            'brightBlack', 'brightRed', 'brightGreen', 'brightYellow', 'brightBlue', 'brightMagenta', 'brightCyan', 'brightWhite']
    cells = ''
    for i, k in enumerate(keys):
        h = A[k]
        ink = '#141615' if lstar(h) > 60 else '#E2E6E1'
        pin = f'<i>= {PINS[k]}</i>' if k in PINS else ''
        ratio = f'{contrast(h, ansi["background"]):.1f}:1' if k != 'black' else 'outlineVariant'
        cells += (f'<div class="aslot" style="background:{h};color:{ink}"><b>{i} {k}</b>'
                  f'<button class="hex" type="button" data-copy="{h}" style="color:{ink}">{h}</button><span>{ratio}</span>{pin}</div>')
    return cells

def syntax_rows():
    out = ''
    for k in ['keyword', 'string', 'number', 'comment', 'function', 'meta', 'added', 'removed']:
        out += (f'<tr><td><code>{k}</code></td><td>{chip(SYN["dark"][k], "dark", k, "surfaceContainerLowest")}</td>'
                f'<td>{chip(SYN["light"][k], "light", k, "surfaceContainerLowest")}</td></tr>')
    return out

# ---------------------------------------------------------------- type
TYPE = [
    ('displaySmall', 'Plex Mono 600', '44 / 52', '−3 %', 'mono', 600, 44, 52, '-0.03em', 'Settings'),
    ('headlineMedium', 'Plex Sans 600', '28 / 36', '0', 'sans', 600, 28, 36, '0', 'Sessions on ravenhost'),
    ('titleLarge', 'Plex Sans 500', '22 / 28', '0', 'sans', 500, 22, 28, '0', 'Permission to edit Theme.kt'),
    ('bodyLarge', 'Plex Sans 400', '16 / 24', '0', 'sans', 400, 16, 24, '0', 'Huginn watches a Claude session and brings the questions to you.'),
    ('labelMedium', 'Plex Mono 500', '13 / 16', '+6 %', 'mono', 500, 13, 16, '0.06em', 'needs you · 2h'),
    ('labelSmall', 'Plex Mono 500', '11 / 16', '0', 'mono', 500, 11, 16, '0', 'claude 12m · fitted to phone'),
    ('mono, phone', 'Plex Mono 400', '11 / 15', '0', 'mono', 400, 11, 15, '0', 'host    ravenhost   appd 3.12.0'),
    ('mono, desktop', 'Plex Mono 400', '13 / 17', '0', 'mono', 400, 13, 17, '0', 'host    ravenhost   appd 3.12.0'),
]
def type_rows():
    out = ''
    for role, face, size, track, fam, w, px, lh, ls, sample in TYPE:
        out += (f'<tr><td><code>{role}</code></td><td>{face}</td><td class="num">{size}</td><td class="num">{track}</td>'
                f'<td class="spec"><span style="{"white-space:pre;" if role.startswith("mono") else ""}font-family:var(--{fam});font-weight:{w};font-size:{px}px;line-height:{lh}px;letter-spacing:{ls}">{E(sample)}</span></td></tr>')
    return out

# ---------------------------------------------------------------- page
def page(full):
    vline = ' · '.join(f'{k} {v}' for k, v in V.items())
    css_tokens = lambda s: ''.join(f'--{k}:{v};' for k, v in s.items() if k != 'accent')
    head = ('<!doctype html>\n<html lang="en">\n<head>\n<meta charset="utf-8">\n'
            '<meta name="viewport" content="width=device-width,initial-scale=1,viewport-fit=cover">\n') if full else ''
    tail = '\n</body>\n</html>\n' if full else '\n'
    opener = '</head>\n<body>\n' if full else ''
    return head + f'''<title>Huginn Design Card</title>
<meta name="claude:viewport-fit-sides" content="cover">
<link rel="stylesheet" href="https://fonts.googleapis.com/css2?family=IBM+Plex+Mono:ital,wght@0,400;0,500;0,600;1,400&family=IBM+Plex+Sans:wght@400;500;600&display=swap">
<style>
/* Layout: a sticky index beside one reading column; every section is a reference block.
   The card is set in the brand itself: Moss & Heather tokens, Plex Mono display, Plex Sans body. Dark-first. */
:root {{ {css_tokens(D)} --mono:'IBM Plex Mono','DejaVu Sans Mono',ui-monospace,Menlo,Consolas,monospace; --sans:'IBM Plex Sans',system-ui,-apple-system,'Segoe UI',sans-serif; color-scheme:dark; }}
@media (prefers-color-scheme: light) {{ :root:not([data-theme="dark"]) {{ {css_tokens(L)} color-scheme:light; }} }}
:root[data-theme="light"] {{ {css_tokens(L)} color-scheme:light; }}
* {{ box-sizing:border-box; }}
html {{ scroll-behavior:smooth; }}
@media (prefers-reduced-motion: reduce) {{ html {{ scroll-behavior:auto; }} }}
body {{ margin:0; background:var(--background); color:var(--onSurface); font-family:var(--sans); font-size:15.5px; line-height:1.55; }}
.frame {{ max-width:1240px; margin:0 auto; padding-block:40px 96px; padding-left:max(16px, env(safe-area-inset-left, 0px)); padding-right:max(16px, env(safe-area-inset-right, 0px)); display:grid; grid-template-columns:200px minmax(0,1fr); gap:48px; }}
@media (max-width: 960px) {{ .frame {{ grid-template-columns:minmax(0,1fr); gap:24px; }} nav.index {{ position:static !important; }} }}
nav.index {{ position:sticky; top:calc(env(safe-area-inset-top, 0px) + 24px); align-self:start; display:flex; flex-direction:column; gap:4px; font-family:var(--mono); font-size:13px; }}
@media (max-width: 960px) {{ nav.index {{ flex-direction:row; flex-wrap:wrap; gap:4px 14px; }} }}
nav.index a {{ color:var(--onSurfaceVariant); text-decoration:none; padding:2px 0; }}
nav.index a:hover, nav.index a:focus-visible {{ color:var(--primary); }}
nav.index .nlock {{ color:var(--onSurface); margin-bottom:14px; }}
@media (max-width: 960px) {{ nav.index .nlock {{ display:none; }} }}
main {{ display:flex; flex-direction:column; gap:64px; min-width:0; }}
section {{ display:flex; flex-direction:column; gap:18px; scroll-margin-top:24px; min-width:0; }}
h1,h2,h3 {{ margin:0; text-wrap:balance; }}
h1 {{ font-family:var(--mono); font-weight:600; font-size:clamp(26px,3.6vw,36px); letter-spacing:-0.03em; line-height:1.15; }}
h2 {{ font-family:var(--mono); font-weight:600; font-size:22px; letter-spacing:-0.02em; padding-bottom:10px; border-bottom:1px solid var(--outlineHairline); }}
h3 {{ font-family:var(--sans); font-weight:600; font-size:16px; }}
p {{ margin:0; max-width:70ch; }}
.lead {{ color:var(--onSurfaceVariant); }}
.eyebrow {{ font-family:var(--mono); font-size:12px; letter-spacing:0.06em; color:var(--onSurfaceVariant); }}
code, .mono {{ font-family:var(--mono); font-size:0.9em; }}
a {{ color:var(--primary); }}
.hero {{ display:flex; flex-direction:column; gap:16px; }}
.lockup {{ color:var(--onSurface); width:auto; max-width:100%; display:block; }}
.tag {{ font-family:var(--mono); font-style:italic; color:var(--onSurfaceVariant); }}
.meta {{ display:flex; flex-wrap:wrap; gap:6px 18px; font-family:var(--mono); font-size:12.5px; color:var(--onSurfaceVariant); }}
/* panels */
.grid {{ display:grid; grid-template-columns:repeat(auto-fit, minmax(min(240px,100%),1fr)); gap:12px; }}
.panel {{ border-radius:8px; padding:16px; display:flex; flex-direction:column; gap:12px; min-width:0; background:var(--surfaceContainerLow); }}
.panel.bone {{ background:var(--boneTile); color:var(--inkMark); }}
.panel.ground {{ background:var(--inkMark); color:var(--boneTile); box-shadow:inset 0 0 0 1px var(--outlineVariant); }}
.panel .cap {{ font-family:var(--mono); font-size:11.5px; color:var(--onSurfaceVariant); }}
.panel.bone .cap {{ color:{L['onSurfaceVariant']}; }}
.panel.ground .cap {{ color:{D['onSurfaceVariant']}; }}
.row {{ display:flex; align-items:flex-end; gap:16px; flex-wrap:wrap; }}
.rules {{ display:grid; grid-template-columns:repeat(auto-fit, minmax(min(300px,100%),1fr)); gap:12px; }}
.rule {{ background:var(--surfaceContainerLow); border-radius:8px; padding:14px 16px; font-size:14.5px; }}
.rule b {{ display:block; font-family:var(--mono); font-weight:500; font-size:12px; color:var(--onSurfaceVariant); margin-bottom:4px; }}
/* tables */
.tw {{ overflow-x:auto; }}
table {{ border-collapse:collapse; width:100%; font-size:14px; font-variant-numeric:tabular-nums; }}
th,td {{ text-align:left; padding:9px 10px; border-bottom:1px solid var(--outlineHairline); vertical-align:middle; }}
th {{ font-family:var(--mono); font-weight:500; font-size:12px; color:var(--onSurfaceVariant); border-bottom-color:var(--outlineVariant); white-space:nowrap; }}
td.g {{ font-family:'DejaVu Sans Mono',var(--mono); font-size:17px; }}
td.num {{ font-family:var(--mono); white-space:nowrap; }}
td.spec {{ min-width:280px; }}
/* palette */
.pgroup {{ display:flex; flex-direction:column; gap:10px; }}
.ptable {{ display:flex; flex-direction:column; }}
.prow {{ display:grid; grid-template-columns:minmax(0,1.3fr) minmax(0,1fr) minmax(0,1fr); gap:12px; align-items:center; padding:9px 0; border-bottom:1px solid var(--outlineHairline); }}
.phead {{ font-family:var(--mono); font-size:12px; color:var(--onSurfaceVariant); border-bottom-color:var(--outlineVariant); padding-top:0; }}
.pname {{ display:flex; flex-direction:column; min-width:0; }}
.pname code {{ font-size:13px; }}
.pname span {{ font-size:13px; color:var(--onSurfaceVariant); }}
@media (max-width: 620px) {{ .prow {{ grid-template-columns:1fr 1fr; }} .pname {{ grid-column:1 / -1; }} .phead div:first-child {{ display:none; }} }}
.sw {{ display:flex; gap:10px; align-items:center; min-width:0; }}
.chip {{ width:40px; height:40px; border-radius:8px; flex:none; box-shadow:inset 0 0 0 1px color-mix(in srgb, var(--onSurface) 18%, transparent); }}
.swt {{ display:flex; flex-direction:column; min-width:0; }}
.swn {{ font-family:var(--mono); font-size:11.5px; color:var(--onSurfaceVariant); }}
.hex {{ all:unset; cursor:pointer; font-family:var(--mono); font-size:13px; font-weight:500; border-radius:4px; }}
.hex:hover {{ text-decoration:underline; }}
.hex:focus-visible {{ outline:2px solid var(--primary); outline-offset:2px; }}
.tiers {{ display:grid; grid-template-columns:repeat(6, minmax(0,1fr)); border-radius:8px; overflow:hidden; }}
@media (max-width: 620px) {{ .tiers {{ grid-template-columns:repeat(3, minmax(0,1fr)); }} }}
.tier {{ padding:14px 10px; display:flex; flex-direction:column; gap:2px; font-family:var(--mono); font-size:11.5px; min-height:74px; }}
.tier b {{ font-weight:500; font-size:12px; }}
/* states */
.dot {{ display:inline-block; width:8px; height:8px; border-radius:50%; margin-right:10px; vertical-align:1px; }}
.dot.quiet {{ width:6px; height:6px; margin-right:12px; }}
.pulse {{ width:10px; height:10px; border-radius:50%; background:var(--stateWorking); animation:pulse 1.6s steps(1, end) infinite; }}
@keyframes pulse {{ 0%{{opacity:.35}} 10%{{opacity:.5}} 20%{{opacity:.68}} 30%{{opacity:.85}} 40%{{opacity:1}} 50%{{opacity:1}} 60%{{opacity:.85}} 70%{{opacity:.68}} 80%{{opacity:.5}} 90%{{opacity:.35}} 100%{{opacity:.35}} }}
.breath {{ font-family:var(--sans); font-size:13px; color:var(--onSurfaceVariant); animation:breath 0.9s ease-in-out infinite alternate; }}
@keyframes breath {{ from {{ opacity:.35 }} to {{ opacity:1 }} }}
.slide {{ width:36px; height:24px; border-radius:4px; background:var(--surfaceContainerHigh); border:1px solid var(--outlineVariant); position:relative; overflow:hidden; flex:none; }}
.slide::after {{ content:''; position:absolute; top:0; bottom:0; left:0; width:14px; background:var(--primaryContainer); animation:slide 3s linear infinite; }}
@keyframes slide {{ 0%,35% {{ transform:translateX(-14px) }} 40%,85% {{ transform:translateX(0) }} 90%,100% {{ transform:translateX(-14px) }} }}
@media (prefers-reduced-motion: reduce) {{ .pulse, .breath, .slide::after {{ animation:none; opacity:1; }} }}
.ramp {{ display:grid; grid-template-columns:repeat(3, minmax(0,1fr)); gap:12px; }}
@media (max-width: 620px) {{ .ramp {{ grid-template-columns:minmax(0,1fr); }} }}
.meter {{ height:6px; border-radius:3px; background:var(--outlineVariant); overflow:hidden; }}
.meter span {{ display:block; height:100%; border-radius:3px; }}
/* terminal */
.ansi {{ display:grid; grid-template-columns:repeat(8, minmax(0,1fr)); gap:4px; }}
@media (max-width: 760px) {{ .ansi {{ grid-template-columns:repeat(4, minmax(0,1fr)); }} }}
.aslot {{ border-radius:6px; padding:8px; display:flex; flex-direction:column; gap:1px; font-family:var(--mono); font-size:11px; min-width:0; overflow:hidden; }}
.aslot b {{ font-weight:600; }} .aslot .hex {{ font-size:11.5px; }} .aslot i {{ font-style:normal; font-weight:600; }}
.term {{ background:{ansi['background']}; color:{ansi['foreground']}; border-radius:8px; padding:12px 14px; font-family:'DejaVu Sans Mono',var(--mono); font-size:12.5px; line-height:1.5; overflow-x:auto; white-space:pre; }}
/* components */
.spec-row {{ display:grid; grid-template-columns:12px minmax(0,1fr) auto; gap:12px; align-items:center; padding:10px 16px; background:var(--surfaceContainerLow); }}
.spec-row + .spec-row {{ border-top:1px solid var(--outlineHairline); }}
.spec-row.sel {{ background:var(--surfaceContainerLow); }} .spec-row.sel .t {{ font-weight:600; }}
.spec-row .t {{ font-family:var(--mono); font-size:14px; }}
.spec-row .m {{ font-family:var(--mono); font-size:11px; color:var(--onSurfaceVariant); }}
.spec-row .w {{ font-family:var(--mono); font-size:11px; }}
.lh {{ display:flex; align-items:baseline; gap:8px; padding:8px 16px; border-bottom:1px solid var(--outlineHairline); font-size:14px; font-weight:500; }}
.lh .c {{ color:var(--onSurfaceVariant); }} .lh .v {{ margin-left:auto; color:var(--primary); font-size:13px; }}
.dialog {{ background:var(--surfaceContainerHigh); border-radius:12px; padding:20px; display:flex; flex-direction:column; gap:10px; max-width:340px; }}
.dialog h4 {{ margin:0; font-size:14px; font-weight:500; }}
.dialog .acts {{ display:flex; justify-content:flex-end; gap:18px; font-weight:500; font-size:14px; }}
.composer {{ background:var(--surfaceContainerLow); border-radius:8px; padding:10px 12px; display:flex; flex-direction:column; gap:8px; }}
.composer .note {{ font-family:var(--mono); font-size:11px; color:var(--onSurfaceVariant); }}
.composer .line {{ display:flex; align-items:center; gap:10px; }}
.field {{ flex:1; min-width:0; border:1px solid var(--outline); border-radius:20px; padding:9px 14px; color:var(--onSurfaceVariant); font-size:14px; }}
.send {{ width:40px; height:40px; border-radius:50%; background:var(--primary); color:var(--onPrimary); display:flex; align-items:center; justify-content:center; flex:none; }}
.stop {{ width:40px; height:40px; border-radius:50%; border:1px solid var(--outline); display:flex; align-items:center; justify-content:center; flex:none; color:var(--onSurface); }}
.ico {{ width:20px; height:20px; color:var(--onSurfaceVariant); flex:none; }}
.chips {{ display:flex; gap:8px; flex-wrap:wrap; }}
.fchip {{ border-radius:6px; padding:5px 12px; font-size:13px; font-weight:500; border:1px solid var(--outline); color:var(--onSurfaceVariant); }}
.fchip.on {{ background:var(--primaryContainer); color:var(--onPrimaryContainer); border-color:transparent; }}
.tabs {{ display:flex; gap:4px; }}
.tab {{ padding:7px 14px; border-radius:8px; font-size:14px; font-weight:500; color:var(--onSurfaceVariant); }}
.tab.on {{ background:var(--primaryContainer); color:var(--onPrimaryContainer); }}
.empty {{ display:flex; flex-direction:column; gap:4px; padding:16px; }}
.empty b {{ font-weight:500; font-size:14px; }} .empty span {{ font-size:13px; color:var(--onSurfaceVariant); }} .empty a {{ font-weight:500; font-size:14px; text-decoration:none; margin-top:4px; }}
.scale {{ display:flex; align-items:flex-end; gap:14px; flex-wrap:wrap; font-family:var(--mono); font-size:11.5px; color:var(--onSurfaceVariant); }}
.scale div {{ display:flex; flex-direction:column; align-items:center; gap:6px; }}
.scale i {{ display:block; background:var(--primary); }}
.radii div i {{ width:56px; height:40px; background:var(--surfaceContainerHigh); border:1px solid var(--outlineVariant); }}
.toast {{ position:fixed; left:50%; transform:translateX(-50%); bottom:calc(env(safe-area-inset-bottom, 0px) + 20px); background:var(--surfaceContainerHigh); color:var(--onSurface); font-family:var(--mono); font-size:12.5px; padding:8px 14px; border-radius:8px; border:1px solid var(--outlineVariant); }}
</style>
{opener}<div class="frame">
<nav class="index" aria-label="Sections">
  <a class="nlock" href="#top">{lockup(22)}</a>
  <a href="#mark">Mark</a><a href="#tile">Tile</a><a href="#palette">Palette</a><a href="#states">States</a>
  <a href="#type">Type</a><a href="#space">Space and shape</a><a href="#components">Components</a>
  <a href="#motion">Motion</a><a href="#terminal">Terminal</a><a href="#surfaces">Surfaces</a><a href="#voice">Voice</a><a href="#sources">Sources</a>
</nav>
<main>
<section class="hero" id="top">
  <div class="eyebrow">Huginn · design card</div>
  {lockup(64)}
  <div class="tag">thought, on call from anywhere</div>
  <h1>The identity as it ships: one mark, one palette, one state language.</h1>
  <p class="lead">The reference for anything new in the apps, the terminal, the README and the installers. Every value on this card is generated from the token, state and mark files the product builds from, so it matches what is in the release. Copy a hex by clicking it.</p>
  <div class="meta"><span>as shipped in {E(vline)}</span><span>generated by assets/brand/design-card.py</span></div>
</section>

<section id="mark">
  <h2>Mark</h2>
  <p>The raven stands upright, facing right: twelve straight edges, a round eye cut as a hole, and one hairline fold along the folded wing. It is one evenodd path in a 108 box, drawn on a 24 by 16 lattice so the terminal renderings are exact rasters of it.</p>
  <div class="grid">
    <div class="panel bone"><div style="display:flex;justify-content:center">{mark(MARK, 168)}</div><div class="cap">raven.svg · ink {D['inkMark']} on bone {D['boneTile']}</div></div>
    <div class="panel ground"><div style="display:flex;justify-content:center">{mark(MARK, 168)}</div><div class="cap">raven-inverse.svg · bone on the ground</div></div>
    <div class="panel bone"><div class="row">{mark(MARK, 64)}{mark(SOLID, 32)}{mark(SOLID, 16)}{mark(HEAD, 22, tr='')}{mark(HEAD, 16, tr='')}</div>
      <div class="cap">64 and up with the fold · 32 and 16 solid · the head drawing at 22 and 16</div>
      <div class="row"><div style="width:72px;height:72px;border-radius:50%;background:var(--boneTile);display:flex;align-items:center;justify-content:center;box-shadow:inset 0 0 0 1px {L['outlineVariant']}">{mark(MARK, 52)}</div>
      <div style="width:72px;height:72px;border-radius:16px;background:var(--boneTile);display:flex;align-items:center;justify-content:center;box-shadow:inset 0 0 0 1px {L['outlineVariant']}">{mark(MARK, 52)}</div></div>
      <div class="cap">launcher under circle and square masks</div></div>
  </div>
  <div class="panel"><div style="color:var(--onSurface)">{lockup(56)}</div><div class="cap">lockup.svg and lockup-dark.svg · lowercase huginn in Plex Mono SemiBold outlines at −6 %, the raven standing where the cursor would be · wordmark.svg without the raven</div></div>
  <div class="rules">
    <div class="rule"><b>Sizes</b>64 px and up: the mark with the fold. 32 px and below: the solid, since the fold antialiases away. 16 to 22 px on a bone tile: the head drawing. The eye survives every size.</div>
    <div class="rule"><b>Colour</b>Ink on bone, bone on the ground, or one state tint. Never outlined, never a gradient, shadow or second colour.</div>
    <div class="rule"><b>Placement</b>Upright and facing right, never mirrored. The launcher scales the bird 0.72 about (52,43) so its farthest vertex stays inside the 33 dp safe ring. The raven sits inside type only in the cursor lockup.</div>
  </div>
</section>

<section id="tile">
  <h2>Tile</h2>
  <p>The small form for the tray, the notification icon and favicons: the raven cut out of a rounded square (64 box, corner radius 14) at 0.84 of the mark, the eye a 4 by 4 cell on whole pixels so it is exactly one pixel at 16. The tile takes the state colour; needs-you adds the badge (error, r 9 at 53,11).</p>
  <div class="panel" style="background:var(--surfaceContainerLow)"><div class="row">
    {tile(48, D['stateIdle'], label='waiting')}{tile(24, D['stateIdle'], label='waiting')}{tile(16, D['stateIdle'], label='waiting')}<span style="width:12px"></span>
    {tile(48, D['stateWorking'], label='working')}{tile(24, D['stateWorking'], label='working')}{tile(16, D['stateWorking'], label='working')}<span style="width:12px"></span>
    {tile(48, D['stateAttention'], D['error'], 'needs you')}{tile(24, D['stateAttention'], D['error'], 'needs you')}{tile(16, D['stateAttention'], D['error'], 'needs you')}
  </div><div class="cap">waiting · working · needs you, at 48, 24 and 16 · the tray keeps these dark-scheme tints on every taskbar · TrayIcons.kt draws the tile at 64 and lets the OS downscale</div></div>
</section>

<section id="palette">
  <h2>Palette · Moss &amp; Heather</h2>
  <p>A dark grey ground with a breath of green, cool bone ink, moss as the one accent, heather as the second voice. Light deepens the moss to evergreen and the heather to royal purple. Every text pair is at least 4.5:1 on every tier it sits on, UI at least 3:1. Ratios below are against each scheme's background.</p>
  <div class="grid">
    <div class="panel" style="background:{D['background']}"><div class="cap" style="color:{D['onSurfaceVariant']}">dark</div>{tiers_demo(D, 'dark')}</div>
    <div class="panel" style="background:{L['background']}"><div class="cap" style="color:{L['onSurfaceVariant']}">light</div>{tiers_demo(L, 'light')}</div>
  </div>
  {palette_section()}
  <div class="pgroup"><h3>Syntax</h3><p class="lead">On the code ground, tier 0. Every class is at least 4.5:1 there, and the ratios below are measured against it.</p>
  <div class="tw"><table><thead><tr><th>Class</th><th>Dark</th><th>Light</th></tr></thead><tbody>{syntax_rows()}</tbody></table></div></div>
</section>

<section id="states">
  <h2>States</h2>
  <p>Seven words, the same everywhere: the apps, the tray tooltip, the terminal title, the CLI and Telegram. The apps draw dots; terminals print the glyph, or the 7-bit mark where output can reach Windows PowerShell 5.1. Needs-you is the accent lit up, never the error red: a question is not a failure.</p>
  <div class="tw"><table><thead><tr><th>Word</th><th>Daemon sends</th><th>Token</th><th>Glyph</th><th>7-bit</th><th>One row</th><th>16-colour slot</th><th>Console</th></tr></thead><tbody>{state_rows()}</tbody></table></div>
  <div class="rules">
    <div class="rule"><b>Dots</b><span class="dot" style="background:var(--stateWorking)"></span>8 dp for live states. <span class="dot quiet" style="background:var(--stateIdle)"></span>6 dp for waiting, offline and settled things. One dot per row, in the state's colour, nothing else.</div>
    <div class="rule"><b>The accent</b>Primary means live and you: the working dot and pulse, the selected item, links, the cursor, and the one filled button on a screen, which is Send. Never a section label, a warning, or "online".</div>
    <div class="rule"><b>Red</b>Error is for failures and destructive verbs. Stop and Interrupt are plain outlined buttons; a soft verb is onSurfaceVariant; a destructive verb and its confirm button are error.</div>
  </div>
  <h3>Severity</h3>
  <p class="lead">Every meter and threshold reads one ramp: normal is the outline, warning is the needs-you step, critical is the error red. Warning at 70 %, critical at 90 %, unless the daemon sends its own word.</p>
  <div class="ramp">
    <div class="panel"><div class="cap">normal · outline</div><div class="meter"><span style="width:42%;background:var(--outline)"></span></div></div>
    <div class="panel"><div class="cap">warning · stateAttention · from 70 %</div><div class="meter"><span style="width:78%;background:var(--stateAttention)"></span></div></div>
    <div class="panel"><div class="cap">critical · error · from 90 %</div><div class="meter"><span style="width:94%;background:var(--error)"></span></div></div>
  </div>
</section>

<section id="type">
  <h2>Type</h2>
  <p>IBM Plex Mono leads: the wordmark, the display role, labels, terminal, code and key-value rows. IBM Plex Sans reads: titles and body, so transcripts stay comfortable. Both are bundled in the apps (Mono 400, 500, 600 and italic 400; Sans 400, 500, 600).</p>
  <div class="tw"><table><thead><tr><th>Role</th><th>Face</th><th>Size / leading</th><th>Tracking</th><th>Specimen</th></tr></thead><tbody>{type_rows()}</tbody></table></div>
  <div class="rules">
    <div class="rule"><b>Tracking</b>Negative only above 22. The mono is never letter-spaced, except labelMedium's +6 %. The wordmark is −6 %.</div>
    <div class="rule"><b>Where the mono goes</b>Session names, labels, timestamps, code and key-value rows. Phone and desktop draw the same bundled glyphs; the terminal view draws cell by cell, so it never forms ligatures.</div>
    <div class="rule"><b>Headers and tagline</b>Section headers in Title Case, no uppercase tracking. The tagline is Plex Mono italic, one line, never wrapped.</div>
  </div>
</section>

<section id="space">
  <h2>Space and shape</h2>
  <p>One scale for every new value (Scale.kt). Rows sit on platform metrics: phone 16 inset and 10 vertical, desktop 12 and 6; list headers 8 and 4.</p>
  <div class="panel"><div class="scale">{''.join(f'<div><i style="width:{n}px;height:{n}px"></i>{n}</div>' for n in (4, 8, 12, 16, 20, 24))}</div><div class="cap">spacing · 4 · 8 · 12 · 16 · 20 · 24</div></div>
  <div class="panel"><div class="scale radii">{''.join(f'<div><i style="border-radius:{r}px"></i>{r} · {w}</div>' for r, w in ((6, 'chips'), (8, 'cards, rows'), (12, 'sheets, dialogs')))}<div><i style="border-radius:50%;width:40px"></i>circle · dots, Send</div></div><div class="cap">radius</div></div>
  <div class="tw"><table><thead><tr><th>Element</th><th>Value</th></tr></thead><tbody>
    <tr><td>Row, phone</td><td class="num">16 inset · 10 vertical · header 8</td></tr>
    <tr><td>Row, desktop</td><td class="num">12 inset · 6 vertical · header 4</td></tr>
    <tr><td>Dots</td><td class="num">8 live · 6 quiet</td></tr>
    <tr><td>Icons</td><td class="num">16 · 20 · outlined, filled only for the active bottom-bar item</td></tr>
    <tr><td>Composer field</td><td class="num">20 radius · Send circle</td></tr>
  </tbody></table></div>
</section>

<section id="components">
  <h2>Components</h2>
  <p>The shared pieces in ui/system. A new screen is built from these before anything new is drawn.</p>
  <div class="grid">
    <div class="panel" style="padding:0;overflow:hidden">
      <div class="lh">Sessions <span class="c">3</span><span class="v">New</span></div>
      <div class="spec-row sel"><span class="dot" style="background:var(--stateWorking);margin:0"></span><div style="min-width:0"><div class="t">costtracking</div><div class="m">claude 12m · fitted to phone</div></div><span class="w" style="color:var(--stateWorking)">working</span></div>
      <div class="spec-row"><span class="dot" style="background:var(--stateAttention);margin:0"></span><div style="min-width:0"><div class="t">brandbook</div><div class="m">permission: Edit Theme.kt</div></div><span class="w" style="color:var(--stateAttention)">needs you</span></div>
      <div class="spec-row"><span class="dot quiet" style="background:var(--stateIdle);margin:0 2px"></span><div style="min-width:0"><div class="t">scratch</div><div class="m">2h</div></div><span class="w" style="color:var(--onSurfaceVariant)">waiting</span></div>
      <div class="cap" style="padding:10px 16px">ListHeader and ListRow · dot, mono name, one meta line, trailing word · selected is tier 1 plus SemiBold</div>
    </div>
    <div class="panel" style="gap:16px">
      <div class="dialog"><h4>Kill session "scratch"?</h4><div style="font-size:13.5px;color:var(--onSurfaceVariant)">The tmux session and its Claude end. The transcript stays.</div><div class="acts"><span style="color:var(--primary)">Cancel</span><span style="color:var(--error)">Kill</span></div></div>
      <div class="cap">HouseDialog · titleSmall, one verb, destructive in error · radius 12 on tier 3</div>
      <div class="empty" style="background:var(--surfaceContainerLow);border-radius:8px"><b>No sessions yet</b><span>Start one here or with huginn on any device.</span><a href="#components">New session</a></div>
      <div class="cap">EmptyState · left-aligned, one line, at most one text button</div>
    </div>
    <div class="panel" style="gap:14px">
      <div class="composer"><div class="note">held until the turn ends</div><div class="line">
        <svg class="ico" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M16.5 6v11.5a4 4 0 0 1-8 0V5a2.5 2.5 0 0 1 5 0v10.5a1 1 0 0 1-2 0V6H10v9.5a2.5 2.5 0 0 0 5 0V5a4 4 0 0 0-8 0v12.5a5.5 5.5 0 0 0 11 0V6h-1.5z"/></svg>
        <div class="field">Reply in brandbook</div>
        <div class="send" aria-label="Send"><svg width="18" height="18" viewBox="0 0 24 24" aria-hidden="true"><path fill="currentColor" d="M3.4 20.4 20.85 12.9a1 1 0 0 0 0-1.8L3.4 3.6a1 1 0 0 0-1.39 1.22L4.5 12l-2.49 7.18a1 1 0 0 0 1.39 1.22z"/></svg></div>
      </div></div>
      <div class="cap">Composer · one notice line, the 20-radius field, Send as the one filled circle · Stop in its place is a plain outlined circle</div>
      <div class="tabs"><span class="tab on">Conversation</span><span class="tab">Screen</span><span class="tab">Overview</span></div>
      <div class="chips"><span class="fchip on">Opus</span><span class="fchip">Sonnet</span><span class="fchip">Haiku</span></div>
      <div class="cap">The active tab, a selected chip and the bottom-bar indicator sit on primaryContainer</div>
    </div>
  </div>
</section>

<section id="motion">
  <h2>Motion</h2>
  <p>Motion is a slow pulse, not a spinner: it says something is thinking, not that a network is busy. Three pieces move in the apps, and nothing else does.</p>
  <div class="panel" style="flex-direction:row;align-items:center;gap:16px"><span class="pulse" aria-hidden="true"></span><div><b style="font-weight:500">The stepped pulse</b><div class="cap">PulseDot · ten steps of 160 ms, alpha 0.35 → 1 → 0.35 over 1.6 s, in primary · the live marker in the work strip, the session header and the overview; a SettledDot in outline when nothing runs · it ticks only while frames are drawn</div></div></div>
  <div class="panel" style="flex-direction:row;align-items:center;gap:16px"><span class="breath" aria-hidden="true">thinking</span><div><b style="font-weight:500">The thinking line</b><div class="cap">ThinkingLine in chat · the word in onSurfaceVariant, alpha 0.35 ↔ 1, 900 ms each way, between a run starting and its first token</div></div></div>
  <div class="panel" style="flex-direction:row;align-items:center;gap:16px"><span class="slide" aria-hidden="true"></span><div><b style="font-weight:500">The side panel</b><div class="cap">Desktop shell · the panel opens and closes over 150 ms</div></div></div>
  <div class="rules">
    <div class="rule"><b>Restraint</b>Nothing loops at full energy, nothing overshoots, and the resting state is a still dot. A new animation earns its place against these three.</div>
  </div>
</section>

<section id="terminal">
  <h2>Terminal</h2>
  <p>The brand's ANSI-16, installed in the apps' terminal view and shipped as Windows Terminal, VHS and tmux files. Four slots are app tokens, so a terminal and the app never disagree about red. Every slot but black is at least 3:1 on the ground.</p>
  <div class="ansi">{ansi_grid()}</div>
  <p class="lead">Ground {ansi['background']} · foreground {ansi['foreground']} · cursor {ansi['cursor']} · selection {ansi['selection']}</p>
  <div class="term"><span style="color:{A['brightMagenta']}">(o&gt; huginn</span> - ravenhost
  uptime    : 12 days, 3 hours
  load      : 0.42 0.38 0.31 (on 8 cores)
  sessions  : 3 running
  disk /    : 41% used, 112G free</div>
  <div class="term tmux" style="background:{D['surfaceContainerLow']};color:{D['onSurfaceVariant']};padding:4px 0"><span style="background:{D['surfaceContainerHigh']};color:{D['primary']};font-weight:600"> (o&gt; </span><span style="background:{D['surfaceContainerHigh']};color:{D['onSurface']};font-weight:600">costtracking </span><span style="color:{D['surfaceContainerHigh']}">▌</span> <span style="color:{D['outline']}">Alt-d:detach  Alt-o:solo  ^b[:scroll</span>  <span style="color:{D['onSurface']};font-weight:600"> 0:claude </span><span style="color:{D['primary']}">▌</span></div>
  <p class="lead">huginn status on the brand slots, and the tmux bar from server/tmux.conf.</p>
  <div class="rules">
    <div class="rule"><b>Colour</b>Slot numbers, never hex, in anything the user's terminal draws. Colour only on a TTY and never when NO_COLOR is set. The 16-colour accent is brightMagenta; moss is the accent in truecolor, tmux and the apps.</div>
    <div class="rule"><b>Charset</b>client/huginn.sh and huginn.ps1 print 7-bit ASCII only. Single-width characters everywhere; no emoji in titles, status, tmux or Telegram.</div>
    <div class="rule"><b>The one-row raven</b><code>(o&gt;</code> leads status lines and the tmux bar; <code>(!&gt;</code> and <code>(-&gt;</code> carry needs-you and waiting. The client's help screen keeps its four-row bird, <code>(o)==-</code>.</div>
  </div>
</section>

<section id="surfaces">
  <h2>Surfaces</h2>
  <div class="tw"><table><thead><tr><th>Surface</th><th>What it carries</th></tr></thead><tbody>
    <tr><td>README</td><td>The cursor lockup with a dark twin, the tagline, the 48 by 16 raven art in a fenced block.</td></tr>
    <tr><td>Android launcher</td><td>The mark at 0.72 on the bone tile, ink on bone.</td></tr>
    <tr><td>Notifications</td><td>The tile glyph as the small icon, the accent set to primary. Channels: Needs you, Finished, Proposals, Watching.</td></tr>
    <tr><td>Widgets</td><td>On the brand scheme in both themes; the same dots and words as the session list.</td></tr>
    <tr><td>Desktop tray</td><td>The tile tinted waiting, working or needs you; the badge on needs you.</td></tr>
    <tr><td>Desktop window and Linux notifications</td><td>The ink raven on a rounded bone tile; the raven as the libnotify icon.</td></tr>
    <tr><td>Installer</td><td>The lockup in bone on the ground, "Huginn Desktop" below in onSurfaceVariant.</td></tr>
    <tr><td>huginn status</td><td><code>(o&gt; huginn - host</code> first, then the readout in the state-table slots.</td></tr>
    <tr><td>Terminal tab titles</td><td><code>●</code> <code>▲</code> <code>○</code>, or <code>*</code> <code>!</code> <code>o</code> off UTF-8.</td></tr>
    <tr><td>tmux</td><td>Status on tier 1, the session segment on tier 3, <code>(o&gt;</code> in moss.</td></tr>
    <tr><td>Telegram</td><td>The state glyph leads each message; no emoji.</td></tr>
  </tbody></table></div>
</section>

<section id="voice">
  <h2>Voice</h2>
  <div class="rules">
    <div class="rule"><b>The name</b>Huginn in prose; <code>huginn</code> as the command and the wordmark. Never HUGINN.</div>
    <div class="rule"><b>State words</b>working · needs you · waiting · shell · ok · error · info. "Needs you" is a question, never a failure; a failure is an error.</div>
    <div class="rule"><b>Quiet</b>No emoji anywhere. Lowercase output; nothing loud but needs you. The raven carries the character; the type and the colour carry the quality.</div>
  </div>
</section>

<section id="sources">
  <h2>Sources</h2>
  <p class="lead">Change a value at its source, then regenerate this card with <code>python3 assets/brand/design-card.py</code>.</p>
  <div class="tw"><table><thead><tr><th>What</th><th>File</th></tr></thead><tbody>
    <tr><td>Colour tokens, both schemes, syntax</td><td><code>assets/brand/source/color/color-04-moss-heather/tokens.json</code> (generated by <code>color/build/</code>, which refuses any value that fails a contrast or ladder rule)</td></tr>
    <tr><td>ANSI-16 and terminal themes</td><td><code>…/color-04-moss-heather/terminal/</code></td></tr>
    <tr><td>App theme, type, state colours</td><td><code>mobile/ui/…/theme/Theme.kt</code>, <code>StateColors.kt</code>, <code>Chips.kt</code></td></tr>
    <tr><td>Scale and components</td><td><code>mobile/ui/…/system/</code>: Scale, ListRow, ListHeader, EmptyState, HouseDialog, Composer</td></tr>
    <tr><td>Terminal ANSI in the apps</td><td><code>mobile/core/…/ui/Ansi.kt</code></td></tr>
    <tr><td>State words and glyphs, every terminal surface</td><td><code>server/lib/state-table.json</code> (readers generated by <code>scripts/gen-state-table.js</code>)</td></tr>
    <tr><td>Mark, tile, lockup</td><td><code>assets/brand/raven.svg</code>, <code>raven-inverse.svg</code>, <code>raven-tile.svg</code>, <code>lockup.svg</code>, <code>wordmark.svg</code>; small sizes and the tile in <code>source/round3/fold-system/</code> (built by its <code>build.py</code>); rasters by <code>assets/brand/generate.sh</code></td></tr>
    <tr><td>Tray and window icon</td><td><code>mobile/app-desktop/…/tray/TrayIcons.kt</code>, <code>RavenMark.kt</code></td></tr>
    <tr><td>Rules in prose</td><td><code>assets/brand/BRAND.md</code></td></tr>
  </tbody></table></div>
</section>
</main>
</div>
<div class="toast" id="toast" hidden></div>
<script>
(function () {{
  var t = document.getElementById('toast'), timer;
  function say(msg) {{ t.textContent = msg; t.hidden = false; clearTimeout(timer); timer = setTimeout(function () {{ t.hidden = true; }}, 1400); }}
  document.addEventListener('click', function (e) {{
    var b = e.target.closest('[data-copy]'); if (!b) return;
    var v = b.getAttribute('data-copy');
    var done = function () {{ say('Copied ' + v); }};
    try {{ navigator.clipboard.writeText(v).then(done, function () {{ fallback(); }}); }} catch (err) {{ fallback(); }}
    function fallback() {{ var r = document.createRange(); r.selectNodeContents(b); var s = getSelection(); s.removeAllRanges(); s.addRange(r); say('Selected ' + v); }}
  }});
}})();
</script>''' + tail


if __name__ == '__main__':
    out = os.path.join(HERE, 'design-card.html')
    open(out, 'w').write(page(full=True))
    print(out)
    if '--body' in sys.argv:
        b = sys.argv[sys.argv.index('--body') + 1]
        open(b, 'w').write(page(full=False))
        print(b)
