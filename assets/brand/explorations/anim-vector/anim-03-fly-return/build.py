#!/usr/bin/env python3
"""build.py — bake anim-03-fly-return (anim.svg, then anim.html via wrap.mjs).

The perched mark is the canonical <path id="raven"> read from assets/brand/raven.svg
(one swap). The flight silhouette (body + one wing used twice) is new geometry drawn
for this exploration and lives below in BODY / WING. The whole 5 s loop is CSS
keyframes generated here so the numbers stay exact.

Revision 2 (after review):
- ONE outer <g transform="translate(2,11)"> (the mark's own group transform) holds both
  the perched mark and the flyer, so the rest frame IS raven.svg's placement; the
  perched group no longer carries a transform attribute that its CSS animation overrode.
- Hand-overs are cuts (step-end), not dissolves. The wings are the continuous element: they unfurl
  on the perched mark (2.26-2.40 s) and the body swap (perched -> flight body) is a
  30 ms step at 2.50 s under the first downstroke. On landing the body swaps back at
  4.60 s during the flare and the wings fold away on the perched mark (4.60-4.80 s).
- Translate and rotate live on separate groups (.flyer / .pose) so each gets one easing.
- The flight path stays low enough that the up-stroke wing tip never leaves the top
  edge while the body is on canvas (checked by capture.mjs's edge scan).
- Resting values (no animations) hide the flyer: the file is the static mark.
- The flight body has the mark's evenodd eye hole and a heavier head.

Timeline (s): 0.00-2.00 perched hold · 2.00-2.30 crouch · 2.26-2.40 wings unfurl ·
2.40-2.52 first downstroke, body cut at 2.50 · 2.50-3.40 fly out right (ease-in) ·
3.40-3.60 off-canvas, jump to the left · 3.60-4.40 fly in from the left (ease-out),
wings beat until 4.00 then glide · 4.40-4.60 flare, body cut back at 4.60 ·
4.60-4.80 wings fold · 4.63-5.00 settle. Wing beat 0.28 s (down 42 %, up 58 %).
"""
import os, re, subprocess
HERE = os.path.dirname(os.path.abspath(__file__))
RAVEN = os.path.abspath(os.path.join(HERE, '..', '..', '..', 'raven.svg'))
src = open(RAVEN, encoding='utf-8').read()
D = ' '.join(re.search(r'<path[^>]*\sd="([^"]+)"', src, re.S).group(1).split())
GT = re.search(r'<g transform="([^"]+)"', src).group(1)

# flight body: heavy round head (top at y 36), the mark's bill proportion, wedge tail; eye hole r 2 like the mark
BODY = ("M96,48 C90,44.5 84,41 78,40 C76.5,35.8 68.5,35 64.5,39.6 C58,42 50,44 40,46.5 L24,49 L10,45.5 L5,51 L11,58 L26,57 "
        "C36,58.5 48,58.8 58,57 C66,55.5 72,53 78,51 Z "
        "M72.5,43.2 m-2,0 a2,2 0 1,0 4,0 a2,2 0 1,0 -4,0 Z")
WING = "M46,49 C42,40 41,28 44,16 C45,11 46,8 48,6 L50,13 C51,10 53,7 55,5 L56,12 C58,9 61,7 64,6 L62,14 C66,24 66,36 63,49 Z"
PIVOT = (54, 47)
T = 5.0
EO = 'cubic-bezier(0.2, 0, 0, 1)'      # ease-out (arrivals)
EI = 'cubic-bezier(0.5, 0, 1, 1)'      # ease-in (departures)
EIO = 'cubic-bezier(0.45, 0, 0.55, 1)'  # symmetric
LIN = 'linear'

def pct(t): return f"{t / T * 100:.3f}%"
def kf(name, frames):
    """frames: list of (t, props dict, easing or None). Times must be strictly increasing."""
    assert all(b[0] > a[0] for a, b in zip(frames, frames[1:])), name
    out = [f"    @keyframes {name} {{"]
    for t, props, ease in frames:
        p = '; '.join(f"{k}: {v}" for k, v in props.items())
        if ease: p += f"; animation-timing-function: {ease}"
        out.append(f"      {pct(t):9s} {{ {p}; }}")
    out.append("    }")
    return '\n'.join(out)

CUT = 0.03  # s: a cut is a step-end over 30 ms: the value holds, then jumps; no frame ever shows both bodies
STEP = 'step-end'
REST = 'translate(-5px, -6px)'   # flyer rest: its shoulder pivot sits on the perched mark's shoulder
PUSH = 'translate(-5px, -9px)'   # same, lifted 3 units by the push-off

# --- perched mark: crouch, push, cut away, cut back, settle ----------------------------
perched = [
    (0.00, {'transform': 'translate(0px, 0px) scaleY(1)', 'opacity': 1}, None),
    (2.00, {'transform': 'translate(0px, 0px) scaleY(1)', 'opacity': 1}, EIO),
    (2.30, {'transform': 'translate(0px, 0px) scaleY(0.94)', 'opacity': 1}, EIO),
    (2.44, {'transform': 'translate(0px, 0px) scaleY(1)', 'opacity': 1}, EIO),
    (2.50, {'transform': 'translate(0px, -3px) scaleY(1)', 'opacity': 1}, STEP),
    (2.50 + CUT, {'transform': 'translate(0px, -3px) scaleY(1)', 'opacity': 0}, None),
    (4.60, {'transform': 'translate(0px, -3px) scaleY(1)', 'opacity': 0}, STEP),
    (4.60 + CUT, {'transform': 'translate(0px, -3px) scaleY(1)', 'opacity': 1}, EO),
    (4.75, {'transform': 'translate(0px, 0px) scaleY(1)', 'opacity': 1}, EIO),
    (4.85, {'transform': 'translate(0px, 0px) scaleY(0.965)', 'opacity': 1}, EO),
    (5.00, {'transform': 'translate(0px, 0px) scaleY(1)', 'opacity': 1}, None),
]
# --- flyer: translate + visibility (wings show from 2.26, everything gone at 4.80) ----
flyer = [
    (0.00, {'transform': REST, 'opacity': 0}, None),
    (2.26, {'transform': REST, 'opacity': 0}, LIN),
    (2.27, {'transform': REST, 'opacity': 1}, LIN),
    (2.44, {'transform': REST, 'opacity': 1}, EIO),
    (2.50, {'transform': PUSH, 'opacity': 1}, EI),
    (3.40, {'transform': 'translate(135px, -22px)', 'opacity': 1}, None),
    (3.45, {'transform': 'translate(135px, -22px)', 'opacity': 0}, None),
    (3.55, {'transform': 'translate(-128px, -12px)', 'opacity': 0}, None),
    (3.60, {'transform': 'translate(-128px, -12px)', 'opacity': 1}, EO),
    (4.40, {'transform': 'translate(-8px, -2px)', 'opacity': 1}, EIO),
    (4.60, {'transform': PUSH, 'opacity': 1}, EO),
    (4.75, {'transform': REST, 'opacity': 1}, LIN),
    (4.80, {'transform': REST, 'opacity': 1}, LIN),
    (4.81, {'transform': REST, 'opacity': 0}, None),
    (5.00, {'transform': REST, 'opacity': 0}, None),
]
# --- pose: rotation about the shoulder (nose up on the climb, slight nose-down on the glide, flare) --
pose = [
    (0.00, {'transform': 'rotate(0deg)'}, None),
    (2.50, {'transform': 'rotate(0deg)'}, EI),
    (2.80, {'transform': 'rotate(-14deg)'}, LIN),
    (3.40, {'transform': 'rotate(-10deg)'}, None),
    (3.55, {'transform': 'rotate(6deg)'}, LIN),
    (4.00, {'transform': 'rotate(4deg)'}, LIN),
    (4.40, {'transform': 'rotate(2deg)'}, EIO),
    (4.60, {'transform': 'rotate(-16deg)'}, EIO),
    (4.80, {'transform': 'rotate(-18deg)'}, None),
    (5.00, {'transform': 'rotate(-18deg)'}, None),
]
# --- flight body: present only between the two cuts ----------------------------------
body = [
    (0.00, {'opacity': 0}, None),
    (2.50, {'opacity': 0}, STEP),
    (2.50 + CUT, {'opacity': 1}, None),
    (4.60, {'opacity': 1}, STEP),
    (4.60 + CUT, {'opacity': 0}, None),
    (5.00, {'opacity': 0}, None),
]
# --- wings: unfurl on the perched mark, beat while airborne, glide, flare, fold ----------
BEAT = 0.28; UP, DOWN = -45, 55
FOLDED = 'scale(0.25)'
def wing_frames(offset):
    r = lambda a, s='scale(1)': {'transform': f'rotate({a + offset}deg) {s}'}
    fr = [(0.00, r(-120, FOLDED), None),
          (2.26, r(-120, FOLDED), EO),
          (2.40, r(UP), EIO)]                          # unfurled and raised
    t = 2.40
    while t + BEAT <= 4.00 + 1e-9:
        if t > 2.40: fr.append((t, r(UP), EIO))
        fr.append((t + BEAT * 0.42, r(DOWN), EIO))   # downstroke; the 2.50 body cut sits under the first one
        t += BEAT
    fr.append((t, r(UP), EO))
    fr.append((t + 0.25, r(5), None))                 # glide
    fr.append((4.40, r(5), EO))                       # flare: wings up...
    fr.append((4.58, r(-70), EIO))                    # ...and back
    fr.append((4.66, r(-95), EIO))
    fr.append((4.80, r(-130, FOLDED), None))          # fold away on the perched mark
    fr.append((5.00, r(-130, FOLDED), None))
    return fr

css = '\n'.join([
    kf('huginn-perched', perched),
    kf('huginn-flyer', flyer),
    kf('huginn-pose', pose),
    kf('huginn-body', body),
    kf('huginn-wing-near', wing_frames(0)),
    kf('huginn-wing-far', wing_frames(-20)),
])

svg = f'''<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" viewBox="0 0 108 108" width="512" height="512">
  <title>Huginn raven: out and back</title>
  <!-- anim-03-fly-return. GENERATED by build.py; do not hand-edit. The thought goes out
       and comes back: the perched mark (canonical <path id="raven">) crouches, its wings
       unfurl, the body cuts to a flight silhouette under the first downstroke, it beats out
       of frame to the right, returns from the left, glides in, flares, cuts back to the
       perched mark and folds its wings. 5 s loop: 2 s perched, 3 s flight. The flight
       silhouette (#body, #wing) is new geometry; the wing is one shape used twice, rotated
       about the shoulder ({PIVOT[0]},{PIVOT[1]}). Both birds sit in the mark's own
       group transform, so the rest frame is raven.svg's placement. Resting state (no
       animations) = the static mark. -->
  <style>
    svg {{ --ink: #161310; }}
    .ink {{ fill: var(--ink); }}
    .perched {{ transform-box: fill-box; transform-origin: 50% 100%; animation: huginn-perched {T:g}s linear infinite; }}
    .flyer   {{ opacity: 0; animation: huginn-flyer {T:g}s linear infinite; }}
    .pose    {{ transform-origin: 54px 48px; animation: huginn-pose {T:g}s linear infinite; }}
    .fbody   {{ opacity: 0; animation: huginn-body {T:g}s linear infinite; }}
    .wing    {{ transform-origin: {PIVOT[0]}px {PIVOT[1]}px; }}
    .near    {{ animation: huginn-wing-near {T:g}s linear infinite; }}
    .far     {{ animation: huginn-wing-far {T:g}s linear infinite; }}
{css}
    @media (prefers-reduced-motion: reduce) {{
      .perched, .flyer, .pose, .fbody, .near, .far {{ animation: none; }}
    }}
  </style>
  <defs>
    <path id="raven" fill-rule="evenodd" d="{D}"/>
    <path id="body" fill-rule="evenodd" d="{BODY}"/>
    <path id="wing" d="{WING}"/>
  </defs>
  <g transform="{GT}">
    <g class="flyer ink">
      <g class="pose">
        <g class="wing far"><use href="#wing" transform="translate(-4,0) scale(0.9) translate(5.5,4)"/></g>
        <g class="fbody"><use href="#body"/></g>
        <g class="wing near"><use href="#wing"/></g>
      </g>
    </g>
    <g class="perched ink">
      <use href="#raven"/>
    </g>
  </g>
</svg>
'''
open(os.path.join(HERE, 'anim.svg'), 'w', encoding='utf-8').write(svg)
subprocess.run(['node', os.path.join(HERE, '..', 'wrap.mjs'), os.path.join(HERE, 'anim.svg'),
                os.path.join(HERE, 'anim.html'), 'Huginn — anim-03-fly-return'], check=True)
print('built')
