# mark-01-refined — the current raven, redrawn

**Preview:** `preview.png` (tile at 512/64/32/16 and the `-16` head drawing at 32/22/16; inverse row; a surfaces row with the launcher mask + safe ring, the tray composition at 16/22, the status icon at 48/72; the current mark below for comparison).

## Concept

The same bird the brand already has — a raven in profile facing right, perched,
feathers over feet, bill right — redrawn with a raven's anatomy instead of a
circle-and-wedge. The forehead runs straight into a long arched culmen that
hooks at the tip (the "Roman nose"); the crown is a low flat line, not a dome;
the nape is a gentle concavity so the head reads as a head; the folded wing is
a convex hump that runs straight into a long wedge tail with a crisp cut end;
the throat carries three soft, uneven waves (hackles) below a tucked chin. The
eye is an evenodd hole (r 3.5) with 4.5 units of ink around it.

Because the perched pose is 1.7:1 wide, no version of it reads at 16 px. So this
variation ships **two drawings**: the mark, and a compact head-and-bill drawing
(`-16`) in a near-square box for 16–22 px — the one raven feature (heavy bill,
flat crown, eye) that survives a 16 px grid.

## Files

| File | What |
|---|---|
| `mark-01-refined.svg` | ink `#161310` on transparent, one evenodd path, 108×108 viewBox, `translate(2,11)` |
| `mark-01-refined-inverse.svg` | bone `#E8E2DA`, same path, for `#12100F` grounds |
| `mark-01-refined-tile.svg` | app-icon master: ink mark on the bone tile (same role as `assets/brand/raven-tile.svg`) |
| `mark-01-refined-16.svg` | **the compact 16 px drawing**: head, bill and neck, eye hole r 6.5, box x 13–105 / y 24–90 of a plain 108 viewBox (no translate). For the desktop tray, favicon-16, 16–22 px list rows |
| `mark-01-refined-status.svg` | the mark's silhouette without the eye hole and with the hackles smoothed, for `ic_stat_huginn.xml` (24 dp, i.e. 48–96 physical px). Same box and transform as the mark |
| `preview.png` | contact sheet (the exact renders) |

## Intended uses and drop-in notes (measured, not assumed)

- **README hero, installer panel, desktop window/taskbar icon, favicon ≥ 32:** the mark / tile / inverse.
- **Android launcher** (`ic_launcher_foreground.xml`): swap the path data; the
  group stays `pivot (52,43)`, `scale 0.72`, `translate (2,11)`. The farthest
  point (tail corner 10.5,60.5) lands **32.4 dp** from the icon centre, inside
  the 66 dp safe circle (radius 33); the current mark's farthest point is 32.6.
  Verified in the preview's launcher cell (dashed ring = safe circle).
- **`RavenMark.kt`:** the mark's box is x 10.5–94.6, y 18.1–67.5, inside the
  current `MIN_X 10 / MIN_Y 17 / WIDTH 84.6 / HEIGHT 52` (tighten to
  10.5 / 18.1 / 84.1 / 49.4 to centre it exactly). The `withEye = false`
  branch should become a second path: the `-16` drawing with box
  13 / 23.9 / 92 / 66.1, used by the tray.
- **Desktop tray** (`TrayIcons.kt`): the `-16` drawing in the existing
  composition (idle: fitted to 60×60 at (2,2); attention: fitted to 50×42 at
  (0,22) plus the 18 px badge at (44,2)), rendered at 64 and shown by the
  platform at 16–22. The preview shows all four cells in tray grey `#7B8794`
  on dark and light taskbars.
- **Android status icon** (`ic_stat_huginn.xml`): the `-status` path, same
  `translate(2,11)`; rendered at 48 and 72 in the preview, white on the bar.
- **Favicon 16:** the `-16` drawing. Favicon 32 and up: the mark.

## Strengths

- Reads as a *raven* from 32 px up: heavy hooked bill, flat crown, wedge
  tail. The current mark reads as a round-headed songbird at the same sizes.
- Crown flatness is measurable: the outline band within 2 units of the apex
  spans 21.0 units (previous draft 12.8, current mark 13.6).
- A real 16 px answer: the head drawing reads as a bird head with an eye at
  16 px in the tray on both dark and light grounds, and at 22 px it is
  unambiguous. The current mark and every full-pose drawing are a lozenge there.
- Drop-in in box, transform, winding and eye-as-hole rule; the launcher
  group is untouched and the safe-circle margin is the same as today's.
- Monochrome by construction; no new colours.

## Weaknesses (honest)

- The **full mark** at 16 px is still a smudge with a bill — the head drawing
  is the fix, and it is a second drawing to maintain (two paths in
  `RavenMark.kt`, two favicon sources).
- The `-16` eye on **bone** at 16 px is one lighter grey pixel; it holds
  cleanly on dark grounds and in the grey tray. At 22 px it holds everywhere.
- The hackles are a subtle "beard" at 512 px and vanish below ~96 px; the
  small sizes carry only the silhouette.
- The head drawing's diagonal neck cut is a convention (eagle-head marks use
  it); seen large it is more generic than the full mark. It is not meant to be
  shown above ~32 px.
- Curves come from a tuned point list (Catmull-Rom → cubics); C1-continuous
  everywhere except the intended corners, but a final hand-pass in a vector
  editor would still improve the belly by a hair.

## Relation to the current mark and palette

A direct successor: same viewBox, transform, winding and eye-as-hole rule, a
box inside the current one, the same ink/bone pair. Drop-in for
`assets/brand/raven.svg`, `raven-inverse.svg`, `raven-tile.svg` and the
hand-carried copies in `generate.sh`; adds one new drawing (`-16`) for the
tray and favicon-16. No new colours.

## Revision (r2)

After the creative-director review: the crown is now flat (apex pulled back
and down, forehead one continuous shallow curve into the culmen; crown span
21.0 vs 12.8); the tail corner was raised from (13,68.5) to (14.5,67.5) and
(10,61.5) to (10.5,60.5) so the launcher's 66 dp safe circle holds (32.4 dp,
was 33.55); the 2-unit wingtip "step" was deleted (a real overlap produced a
hairline against the tail, so the back now runs straight into it); the two
throat scallops became three soft uneven waves below a tucked chin; the nape
was deepened and smoothed so the head reads at 64 px; the eye grew to r 3.5
and moved back one unit (ink ring 4.50). The old `-16` file, which was
pixel-identical to the mark at 16, is replaced by a genuine compact head
drawing; the eyeless silhouette moved to `-status` and is rendered at 48/72,
the sizes it is actually drawn at. The preview now carries the launcher ring
overlay, the tray composition with the badge, and the status sizes.
