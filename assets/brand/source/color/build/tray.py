#!/usr/bin/env python3
"""tray.py <out-dir>

Write one SVG per direction, scheme and tray variant (idle / working / attention+badge
and the three monochrome tints) with the raven on a tier-1 tray bar, so that
tools/render.mjs can render them at the real tray sizes (22 and 16 px) and
tools/sheet.sh can tile them with hard pixels for a look. This is the verification
step the brief's rule 2 asks for; the committed sheet carries the same icons as an
HTML strip rendered by the same Chromium.
"""
import os
import sys

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from spec import DIRECTIONS, MONO_TRAY_ALPHAS  # noqa: E402
from palette import RAVEN_PATH  # noqa: E402


def svg(bg, color, opacity=1.0, badge=None):
    b = f'<circle cx="94" cy="14" r="14" fill="{badge}"/>' if badge else ''
    return (f'<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 108 108" width="108" height="108">'
            f'<rect width="108" height="108" fill="{bg}"/>'
            f'<g transform="translate(2,11)" opacity="{opacity}"><path fill="{color}" fill-rule="evenodd" d="{RAVEN_PATH}"/></g>{b}</svg>\n')


def main(out):
    os.makedirs(out, exist_ok=True)
    names = []
    for d in DIRECTIONS:
        for scheme in ('dark', 'light'):
            t = d[scheme]
            bar, on = t['surfaceContainerLow'], t['onSurface']
            variants = [('idle', svg(bar, t['stateIdle'])), ('working', svg(bar, t['stateWorking'])),
                        ('attention', svg(bar, t['stateAttention'], badge=t['stateAttention'])),
                        ('mono-idle', svg(bar, on, MONO_TRAY_ALPHAS['stateIdle'])),
                        ('mono-working', svg(bar, on, MONO_TRAY_ALPHAS['stateWorking'])),
                        ('mono-attention', svg(bar, on, MONO_TRAY_ALPHAS['stateAttention'], badge=on))]
            for name, body in variants:
                p = os.path.join(out, f'{d["id"]}-{scheme}-{name}.svg')
                with open(p, 'w', encoding='utf-8') as f:
                    f.write(body)
                names.append(p)
    print('\n'.join(names))


if __name__ == '__main__':
    main(sys.argv[1])
