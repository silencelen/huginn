#!/usr/bin/env python3
"""build.py — bake anim-05-thinking: anim.svg (option c, cursor), feather.svg (a),
eye-scan.svg (b) and anim.html (all three side by side, large and at 24 px) from the
canonical mark in assets/brand/raven.svg."""
import os, re
HERE = os.path.dirname(os.path.abspath(__file__))
RAVEN = os.path.abspath(os.path.join(HERE, '..', '..', '..', 'raven.svg'))
src = open(RAVEN, encoding='utf-8').read()
D = ' '.join(re.search(r'<path[^>]*\sd="([^"]+)"', src, re.S).group(1).split())
defs = f'<defs>\n    <path id="raven" fill-rule="evenodd" d="{D}"/>\n  </defs>'

def mark(x, y, w):
    """The canonical mark with its bbox (path coords x 10..94.6, y 17..69) fitted w wide at (x,y)."""
    s = w / 84.6
    return f'<g transform="translate({x:g},{y:g}) scale({s:.4f}) translate(-10,-17)"><use href="#raven" class="ink"/></g>'

HEAD = '<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 108 54" width="432" height="216" class="opt" id="{id}">'

# (a) feather: raven left, 44 wide; a feather drops to its right, tumbling. New 16-unit shape.
A = f'''{HEAD.format(id='opt-a')}
  <title>Huginn thinking: feather</title>
  <style>
    :root {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    #opt-a .feather {{ transform-origin: 0px 0px; animation: huginn-feather 2.8s linear infinite; }}
    @keyframes huginn-feather {{
      0%   {{ transform: translate(70px, -6px) rotate(-30deg); opacity: 0; animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      10%  {{ opacity: 1; }}
      30%  {{ transform: translate(78px, 12px) rotate(25deg);  animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      60%  {{ transform: translate(66px, 30px) rotate(-28deg); animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      82%  {{ opacity: 1; }}
      90%  {{ transform: translate(76px, 48px) rotate(22deg);  animation-timing-function: cubic-bezier(0.45, 0, 0.55, 1); }}
      100% {{ transform: translate(70px, 60px) rotate(-10deg); opacity: 0; }}
    }}
    @media (prefers-reduced-motion: reduce) {{ #opt-a .feather {{ animation: none; opacity: 0; }} }}
  </style>
  {defs}
  {mark(6, 14, 44)}
  <g class="feather ink">
    <path d="M0,-7 C3.2,-4.5 3.4,2.5 0.6,7.5 L-0.4,2.4 L-2.8,2.2 C-3.4,-1 -2.6,-4.8 0,-7 Z"/>
    <rect x="-0.35" y="-8.5" width="0.7" height="18" rx="0.35"/>
  </g>
</svg>'''

# (b) eye scan: raven 80 wide; an ink pupil inside the eye hole (path coords 64.5,25.5 r 2) slides left-right.
sb = 80 / 84.6
ex = 14 + (64.5 - 10) * sb; ey = 3 + (25.5 - 17) * sb
B = f'''{HEAD.format(id='opt-b')}
  <title>Huginn thinking: eye scan</title>
  <style>
    :root {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    #opt-b .pupil {{ animation: huginn-scan 2.4s cubic-bezier(0.45, 0, 0.55, 1) infinite; }}
    @keyframes huginn-scan {{
      0%   {{ transform: translateX({-0.85*sb:.2f}px); }}
      32%  {{ transform: translateX({ 0.85*sb:.2f}px); }}
      50%  {{ transform: translateX({ 0.85*sb:.2f}px); }}
      82%  {{ transform: translateX({-0.85*sb:.2f}px); }}
      100% {{ transform: translateX({-0.85*sb:.2f}px); }}
    }}
    @media (prefers-reduced-motion: reduce) {{ #opt-b .pupil {{ animation: none; transform: none; }} }}
  </style>
  {defs}
  {mark(14, 3, 80)}
  <circle class="pupil ink" cx="{ex:.2f}" cy="{ey:.2f}" r="{1.0*sb:.2f}"/>
</svg>'''

# (c) cursor: raven glyph + a block cursor blinking at a terminal cadence. RECOMMENDED.
C = f'''{HEAD.format(id='opt-c')}
  <title>Huginn thinking: cursor</title>
  <style>
    :root {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    #opt-c .cursor {{ animation: huginn-cursor 1.06s steps(2, jump-none) infinite; }}
    @keyframes huginn-cursor {{ from {{ opacity: 1; }} to {{ opacity: 0; }} }}
    @media (prefers-reduced-motion: reduce) {{ #opt-c .cursor {{ animation: none; opacity: 1; }} }}
  </style>
  {defs}
  {mark(10, 14, 48)}
  <rect class="cursor ink" x="68" y="17.5" width="10" height="21"/>
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
  if (new URLSearchParams(location.search).has('freeze')) {{
    const s = document.createElement('style'); s.textContent = '* {{ animation-play-state: paused !important; }}';
    document.head.appendChild(s);
  }}
</script>
<style>
  html {{ --ink: #161310; --ground: #E8E2DA; --muted: #8A8177; }}
  html[data-theme="dark"] {{ --ink: #E8E2DA; --ground: #12100F; }}
  html, body {{ margin: 0; height: 100%; background: var(--ground); color: var(--muted); }}
  body {{ display: grid; grid-template-columns: repeat(3, 1fr); gap: 16px; padding: 16px; box-sizing: border-box; align-content: center;
         font: 12px/1.3 ui-monospace, "DejaVu Sans Mono", Menlo, Consolas, monospace; }}
  figure {{ margin: 0; display: grid; gap: 8px; justify-items: start; }}
  figure svg {{ width: 100%; height: auto; }}
  figure .small svg {{ width: 48px; height: 24px; }}
  .small {{ display: flex; align-items: center; gap: 8px; }}
  figcaption {{ white-space: pre; }}
  .rec {{ color: var(--ink); }}
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
<figcaption class="rec">(c) cursor    1.06 s blink   RECOMMENDED</figcaption>
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
print('built')
