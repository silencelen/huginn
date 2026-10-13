"""Print every rule and whether it passes, for tuning. palette.py runs the same checks
and refuses to emit if any fails. Rules:
  text pairs >= 4.5:1, UI pairs >= 3:1, syntax >= 4.5:1 on the code ground,
  every ANSI slot but black >= 3:1 on the terminal ground,
  tray ladder idle -> working -> attention >= TRAY_MIN_DL L* apart (both schemes),
  the same ladder in ANSI-16 (brightBlack -> accent slot -> brightYellow) >= ANSI_LADDER_MIN_DL,
  monochrome tray tints (onSurface at MONO_TRAY_ALPHAS over the ground) >= TRAY_MIN_DL apart,
  tertiary vs every state colour: >= TERTIARY_MIN_DL L* or >= TERTIARY_MIN_DHUE degrees,
  slot pins: brightRed = error, brightGreen = stateSuccess, brightYellow = stateAttention, brightBlue = stateInfo,
  the CLI's 16-colour slots >= LEGACY_MIN on every legacy palette in LEGACY_PALETTES.
"""
import sys
from colorlib import contrast, lstar, hsl, alpha_over
from spec import (DIRECTIONS, TEXT_PAIRS, UI_PAIRS, SYNTAX_KEYS, TRAY_ORDER, TRAY_MIN_DL, ANSI_KEYS,
                  ANSI_LADDER_MIN_DL, MONO_TRAY_ALPHAS, TERTIARY_MIN_DL, TERTIARY_MIN_DHUE, TERTIARY_MIN_SAT, SLOT_PINS,
                  LEGACY_PALETTES, LEGACY_MIN)

STATE_KEYS = ['stateIdle', 'stateWorking', 'stateAttention', 'stateSuccess', 'stateError', 'stateInfo']


def dhue(a, b):
    """Hue gap in degrees; 0 when either colour is a near-neutral (hue is meaningless there)."""
    if min(hsl(a)[1], hsl(b)[1]) < TERTIARY_MIN_SAT:
        return 0.0
    d = abs(hsl(a)[0] - hsl(b)[0]) % 360
    return min(d, 360 - d)


def ansi_ladder(d):
    """(slot, hex, L*) for brightBlack -> accent slot -> brightYellow."""
    a = d['ansi']
    return [(k, a[k], lstar(a[k])) for k in ('brightBlack', d['accent_ansi'], 'brightYellow')]


def mono_ladder(t):
    """(state, alpha, flat hex, L*) for the monochrome tray tints of one scheme."""
    return [(k, al, alpha_over(t['onSurface'], t['background'], al), lstar(alpha_over(t['onSurface'], t['background'], al)))
            for k, al in MONO_TRAY_ALPHAS.items()]


def legacy_rows(d):
    """For each legacy palette and CLI semantic: (palette, semantic, slot, hex, ratio on that palette's ground)."""
    out = []
    for name, p in LEGACY_PALETTES.items():
        for sem, slot in d['cli'].items():
            out.append((name, sem, slot, p[slot], contrast(p[slot], p['ground'])))
    return out


def check(d, verbose=False):
    fails = []

    def rule(ok, msg, always=False):
        if not ok:
            fails.append(msg)
        if verbose or always or not ok:
            print(f'  {"ok " if ok else "XX "}{msg}')

    for scheme in ('dark', 'light'):
        t = d[scheme]
        for fg, bg, mn in TEXT_PAIRS + UI_PAIRS:
            r = contrast(t[fg], t[bg])
            rule(r >= mn, f'{scheme:5} {fg:22} on {bg:24} {r:5.2f} (min {mn})')
        syn = d['syntax_' + scheme]
        for k in SYNTAX_KEYS:
            r = contrast(syn[k], t['surfaceContainerLowest'])
            rule(r >= 4.5, f'{scheme:5} syntax.{k:10} on code ground {r:5.2f} (min 4.5)')
        ls = [(k, lstar(t[k])) for k in TRAY_ORDER]
        for (ka, la), (kb, lb) in zip(ls, ls[1:]):
            dl = abs(lb - la)
            rule(dl >= TRAY_MIN_DL, f'{scheme:5} tray L* {ka}={la:.1f} {kb}={lb:.1f} dL*={dl:.1f} (min {TRAY_MIN_DL:.0f})', always=True)
        ml = mono_ladder(t)
        for (ka, aa, ha, la), (kb, ab, hb, lb) in zip(ml, ml[1:]):
            dl = abs(lb - la)
            rule(dl >= TRAY_MIN_DL, f'{scheme:5} mono {aa:.0%} {ha} L*={la:.1f} -> {ab:.0%} {hb} L*={lb:.1f} dL*={dl:.1f} (min {TRAY_MIN_DL:.0f})', always=True)
        for k in STATE_KEYS:
            dl = abs(lstar(t['tertiary']) - lstar(t[k]))
            dh = dhue(t['tertiary'], t[k])
            rule(dl >= TERTIARY_MIN_DL or dh >= TERTIARY_MIN_DHUE,
                 f'{scheme:5} tertiary {t["tertiary"]} vs {k:14} dL*={dl:4.1f} dHue={dh:5.1f} (need dL*>={TERTIARY_MIN_DL:.0f} or dHue>={TERTIARY_MIN_DHUE:.0f})')
    a = d['ansi']
    for k in ANSI_KEYS:
        if k == 'black':
            continue
        r = contrast(a[k], a['background'])
        rule(r >= 3.0, f'ansi  {k:14} on ground {r:5.2f} (min 3.0)')
    lad = ansi_ladder(d)
    for (ka, ha, la), (kb, hb, lb) in zip(lad, lad[1:]):
        dl = abs(lb - la)
        rule(dl >= ANSI_LADDER_MIN_DL, f'ansi  ladder {ka} {ha} L*={la:.1f} -> {kb} {hb} L*={lb:.1f} dL*={dl:.1f} (min {ANSI_LADDER_MIN_DL:.0f})', always=True)
    for slot, tok in d.get('slot_pins', SLOT_PINS):
        rule(a[slot] == d['dark'][tok], f'ansi  pin {slot} {a[slot]} == dark.{tok} {d["dark"][tok]}')
    for name, sem, slot, h, r in legacy_rows(d):
        rule(r >= LEGACY_MIN, f'cli16 {name:15} {sem:7} {slot:13} {h} {r:5.2f} on {LEGACY_PALETTES[name]["ground"]} (min {LEGACY_MIN})')
    return fails


if __name__ == '__main__':
    v = '-v' in sys.argv
    bad = 0
    for d in DIRECTIONS:
        print('==', d['id'])
        f = check(d, v)
        bad += len(f)
        print('  FAILS:', len(f))
        for x in f:
            print('    -', x)
    sys.exit(1 if bad else 0)
