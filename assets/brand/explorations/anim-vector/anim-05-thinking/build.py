#!/usr/bin/env python3
"""build.py — bake anim-05-thinking: anim.svg (option c, cursor), feather.svg (a),
eye-scan.svg (b) and anim.html (all three side by side, large and at 24 px) from the
canonical mark in assets/brand/raven.svg.

Revision 2 (after review):
- (c) the cursor no longer blinks on its own 530/530 ms clock; it breathes on the apps'
  PulseDot table (ten alphas 0.35 0.5 0.68 0.85 1 1 0.85 0.68 0.5 0.35, 160 ms steps,
  1.6 s), so the chat indicator, the live marker and the session list share one rhythm.
- (c) cursor proportion: height = the raven's head-top-to-body-bottom (one "line"), width
  0.55 x height (a terminal cell), on the body's baseline, half a cell after the bill.
- (a) the feather is 26 units with a barb notch and a 1-unit rachis gap, and its resting
  transform is a keyframe beside the raven (it used to rest clipped at the origin).
- (b) the pupil is r 1.35 travelling +/-0.95 inside the r 2 hole: visible at hero size.
- Every <style> scopes --ink on the svg element, not :root.
"""
import os, re
HERE = os.path.dirname(os.path.abspath(__file__))
RAVEN = os.path.abspath(os.path.join(HERE, '..', '..', '..', 'raven.svg'))
src = open(RAVEN, encoding='utf-8').read()
D = ' '.join(re.search(r'<path[^>]*\sd="([^"]+)"', src, re.S).group(1).split())
defs = f'<defs>\n    <path id="raven" fill-rule="evenodd" d="{D}"/>\n  </defs>'
BW, BH, BX, BY = 84.6, 52.0, 10.0, 17.0   # mark bbox in path coords

def mark(x, y, w):
    """The canonical mark with its bbox fitted w wide at (x,y)."""
    s = w / BW
    return f'<g transform="translate({x:g},{y:g}) scale({s:.4f}) translate(-{BX:g},-{BY:g})"><use href="#raven" class="ink"/></g>'

HEAD = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 108 54" width="432" height="216" class="opt" id="{id}">'
PULSE = [0.35, 0.5, 0.68, 0.85, 1, 1, 0.85, 0.68, 0.5, 0.35]   # PulseDot, 160 ms per step

# (a) feather: raven left, 44 wide; a 26-unit feather drops to its right, tumbling.
FEATHER = '''<g class="feather ink">
    <path d="M0.5,-12.5 C3.6,-10.2 5.4,-3.5 4.9,1.2 L2.6,1.6 L4.6,3.4 C4.1,6.8 3.2,9.6 1.9,11.8 L0.5,12.5 Z"/>
    <path d="M-0.5,-12.5 C-3,-10.5 -4.3,-4 -3.4,2.6 L-2.4,8.4 L-0.5,12.5 Z"/>
    <rect x="-0.22" y="-13.5" width="0.44" height="29" rx="0.22"/>
  </g>'''
A = f'''{HEAD.format(id='opt-a')}
  <title>Huginn thinking: feather</title>
  <style>
    svg {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    #opt-a .feather {{ transform-origin: 0px 0px; transform: translate(80px, 14px) rotate(25deg); animation: huginn-feather 2.8s linear infinite; }}
    @keyframes huginn-feather {{
      0%   {{ transform: translate(72px, -8px) rotate(-30deg); opacity: 0; animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      10%  {{ opacity: 1; }}
      30%  {{ transform: translate(80px, 14px) rotate(25deg);  animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      60%  {{ transform: translate(68px, 32px) rotate(-28deg); animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      82%  {{ opacity: 1; }}
      90%  {{ transform: translate(78px, 50px) rotate(22deg);  animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      100% {{ transform: translate(72px, 64px) rotate(-10deg); opacity: 0; }}
    }}
    @media (prefers-reduced-motion: reduce) {{ #opt-a .feather {{ animation: none; }} }}
  </style>
  {defs}
  {mark(6, 14, 44)}
  {FEATHER}
</svg>'''

# (b) eye scan: raven 80 wide; an ink pupil inside the eye hole (path coords 64.5,25.5 r 2) slides left-right.
sb = 80 / BW
ex = 14 + (64.5 - BX) * sb; ey = 3 + (25.5 - BY) * sb
B = f'''{HEAD.format(id='opt-b')}
  <title>Huginn thinking: eye scan</title>
  <style>
    svg {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    #opt-b .pupil {{ animation: huginn-scan 2.4s cubic-bezier(0.45, 0, 0.55, 1) infinite; }}
    @keyframes huginn-scan {{
      0%   {{ transform: translateX({-0.95*sb:.2f}px); }}
      32%  {{ transform: translateX({ 0.95*sb:.2f}px); }}
      50%  {{ transform: translateX({ 0.95*sb:.2f}px); }}
      82%  {{ transform: translateX({-0.95*sb:.2f}px); }}
      100% {{ transform: translateX({-0.95*sb:.2f}px); }}
    }}
    @media (prefers-reduced-motion: reduce) {{ #opt-b .pupil {{ animation: none; transform: none; }} }}
  </style>
  {defs}
  {mark(14, 3, 80)}
  <circle class="pupil ink" cx="{ex:.2f}" cy="{ey:.2f}" r="{1.35*sb:.2f}"/>
</svg>'''

# (c) cursor: raven glyph + a block cursor breathing on the PulseDot table. RECOMMENDED.
sc = 48 / BW
top = 14; bottom = 14 + (63.3 - BY) * sc            # head top .. body bottom (the tail goes lower)
ch = bottom - top; cw = 0.55 * ch
cx = 10 + 48 + cw / 2                                # half a cell after the bill tip
steps = ''.join(f'      {i * 10}% {{ opacity: {a}; animation-timing-function: step-end; }}\n' for i, a in enumerate(PULSE))
C = f'''{HEAD.format(id='opt-c')}
  <title>Huginn thinking: cursor</title>
  <style>
    svg {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    /* PulseDot: the apps' live marker. Ten alphas, 160 ms each, stepped (about 6 fps). */
    #opt-c .cursor {{ opacity: 1; animation: huginn-pulse 1.6s linear infinite; }}
    @keyframes huginn-pulse {{
{steps}      100% {{ opacity: {PULSE[-1]}; }}
    }}
    @media (prefers-reduced-motion: reduce) {{ #opt-c .cursor {{ animation: none; opacity: 1; }} }}
  </style>
  {defs}
  {mark(10, top, 48)}
  <rect class="cursor ink" x="{cx:.2f}" y="{top:g}" width="{cw:.2f}" height="{ch:.2f}"/>
</svg>'''

open(os.path.join(HERE, 'feather.svg'), 'w', encoding='utf-8').write(A + '\n')
open(os.path.join(HERE, 'eye-scan.svg'), 'w', encoding='utf-8').write(B + '\n')
open(os.path.join(HERE, 'anim.svg'), 'w', encoding='utf-8').write(C + '\n')

def small(svg, i):  # a second copy for the 24 px row needs its own ids so the scoped CSS stays scoped
    return svg.replace(f'id="opt-{i}"', f'id="opt-{i}2"').replace(f'#opt-{i} ', f'#opt-{i}2 ')

html = f'''<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width,initial-scale=1">
<title>Huginn — anim-05-thinking</title>
<script>
  // ?freeze=1 (used by capture.mjs): pause every animation before it ever runs, so a
  // later seek(t) renders from a clean initial state rather than a stale compositor frame.
  // ?static=1: no animations at all (the resting state a non-animating renderer shows).
  const q0 = new URLSearchParams(location.search);
  if (q0.has('freeze') || q0.has('static')) {{
    const s = document.createElement('style');
    s.textContent = q0.has('static') ? '* {{ animation: none !important; }}' : '* {{ animation-play-state: paused !important; }}';
    document.head.appendChild(s);
  }}
</script>
<style>
  html {{ --ground: #E8E2DA; --muted: #8A8177; }}
  html[data-theme="dark"] {{ --ground: #12100F; }}
  html[data-theme="dark"] svg {{ --ink: #E8E2DA; }}
  html, body {{ margin: 0; height: 100%; background: var(--ground); color: var(--muted); }}
  body {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; padding: 16px; box-sizing: border-box; align-content: center;
         font: 12px/1.3 ui-monospace, "DejaVu Sans Mono", Menlo, Consolas, monospace; }}
  figure {{ margin: 0; display: grid; gap: 8px; justify-items: start; }}
  figure svg {{ width: 100%; height: auto; }}
  figure .small svg {{ width: 48px; height: 24px; }}
  .small {{ display: flex; align-items: center; gap: 8px; }}
  figcaption {{ white-space: pre; }}
  .rec {{ color: var(--ink, #161310); }}
  html[data-theme="dark"] .rec {{ color: #E8E2DA; }}
</style>
</head>
<body>
<figure>
{A}
<div class="small">{small(A, 'a')}<span>at 24 px</span></div>
<figcaption>(a) feather   2.8 s loop</figcaption>
</figure>
<figure>
{B}
<div class="small">{small(B, 'b')}<span>at 24 px</span></div>
<figcaption>(b) eye scan  2.4 s loop</figcaption>
</figure>
<figure>
{C}
<div class="small">{small(C, 'c')}<span>at 24 px</span></div>
<figcaption class="rec">(c) cursor    PulseDot breath, 1.6 s stepped   RECOMMENDED</figcaption>
</figure>
<script>
  // Freeze every animation on the page at t seconds; ?t=<s> does it on load, ?theme=dark flips the ground.
  window.seek = function (t) {{
    const anims = document.getAnimations();
    // never exactly 0: Chromium treats a paused animation at currentTime 0 as not started and leaves
    // the compositor layer at its last rasterised (pre-seek) state; 1 ms is invisible and correct
    for (const a of anims) {{ a.pause(); a.currentTime = Math.max(t, 0.001) * 1000; }}
    return anims.length;
  }};
  const q = new URLSearchParams(location.search);
  if (q.get('theme') === 'dark') document.documentElement.dataset.theme = 'dark';
  if (q.has('t')) addEventListener('load', () => window.seek(parseFloat(q.get('t'))));
</script>
</body>
</html>
'''
open(os.path.join(HERE, 'anim.html'), 'w', encoding='utf-8').write(html)
print(f'built: cursor {cw:.1f} x {ch:.1f} at ({cx:.1f},{top}); pupil r {1.35*sb:.2f} travel +/-{0.95*sb:.2f}')
