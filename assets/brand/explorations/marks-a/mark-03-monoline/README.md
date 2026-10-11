# mark-03-monoline — the raven as an icon in a set

**Preview:** `preview.png` (the filled companion as a tile at 512/64/32/16; the stroke icon at 192/48/24/20/16; filled at 48/24/20/16; inverse; a "same bird?" strip beside the two marks' status silhouettes; `specimen.html` rendered in both themes).

## Concept

The raven rebuilt the way Lucide and Phosphor build icons: a 24×24 grid,
2 px stroke, round caps and joins, `stroke="currentColor"`, `fill="none"`.
Rebuilt in this revision around the two raven signatures that fit a 24 grid:
a **bill as long as the head whose culmen is one arc from the flat crown to
the tip** (the Roman nose), and a **two-segment wedge tail**. One outline path
(crown arc, nape, straight back to the tail tip, wedge, belly curve, gape) plus
a 2 px eye dot. A **filled companion** (same outline, filled, no eye) takes
over at **20 px and below**, where strokes close up. Three Lucide-equivalent
icons (terminal, phone, bell) sit beside it in the specimen as scale and
weight references — they are not proposals.

## Files

| File | What |
|---|---|
| `mark-03-monoline.svg` | 24×24, `stroke="currentColor"`, `fill="none"`: the in-app icon for 24 px and up |
| `mark-03-monoline-inverse.svg` | same, stroke bone `#E8E2DA` |
| `mark-03-monoline-filled.svg` | filled companion (fill + stroke `currentColor`, identical footprint, no eye) for ≤ 20 px |
| `mark-03-monoline-tile.svg` | app-icon master built from the **filled** companion on the bone tile; included to match the deliverable shape, see weaknesses |
| `mark-03-monoline-set.svg` | strip: terminal, phone, bell (Lucide-equivalents), raven, raven-filled on one 120×24 canvas |
| `specimen.html` | the set in both themes at 48/24/20/16 plus menu rows (filled companion at 20) |
| `preview.png` | contact sheet + specimen |

## Intended uses

In-app icons on phone and desktop (navigation, menu rows, empty states),
tray menu rows, README badges — wherever the raven must sit at the same weight
as other icons and tint with `currentColor`. **Not** the canonical brand mark,
**not** the launcher icon, and — until it reads as the same drawing as the
chosen mark rather than the same family — **not** the Android status icon:
keep `ic_stat_huginn.xml` on the chosen mark's `-status` silhouette.

## Strengths

- Correct Lucide construction (24 grid, 2 px, round caps/joins, currentColor)
  at the same optical weight as its neighbours in both themes.
- The bill now carries it: at 48 and 24 px it reads as a corvid, not a
  round-headed songbird; the filled companion at 20/16 is a clean heavy-billed
  silhouette.
- Honest switch point: stroke at ≥ 24, filled at ≤ 20, stated and shown; the
  menu rows (20 px) use the filled one.
- One path plus a dot; adds to a React/Compose icon library without
  special-casing. No new colour tokens: the light heading in the specimen is
  ink `#1B1817` at 62 % (effective `#706D6A`, 4.81:1 on `#FAF7F2`, AA).

## Weaknesses (honest)

- The filled companion is a **fat bird**: the 2 px stroke adds a pixel all
  round a 24-grid shape, so at 64 px it is chunkier than marks 01/02 (see the
  "same bird?" strip). It reads as the same family (bill, crown, wedge tail),
  not the same drawing.
- The stroke icon at 16 px is a scribble; only the filled companion works there.
- The tile is a blob at 512 px — a 24-grid icon scaled ×3.6 is not a launcher
  icon. It exists only so this folder has the same files as the others.
- At 24 px the hackles do not exist and the tail wedge is two short segments;
  "bird with a very heavy bill" is the read, which is the job, but it will not
  carry the brand alone.

## Relation to the current mark and palette

Not a path-swap: a different grid (24, not 108) and a different construction
(stroke, not fill). Keeps the pose, the no-legs rule and the bill/crown/tail
of marks 01 and 02; uses only `currentColor` / ink / bone. Pairs with either
mark as the canonical mark.

## Revision (r2)

After the creative-director review: the head was rebuilt (culmen one arc from
the flat crown to the tip at (22,10.5), bill as long as the head, gape at
(16.6,12.4)); the tail is a two-segment wedge (2,19.5)→(6,17.5)→(9,16.3); the
belly was raised to the tail line so the filled icon is less bulbous; the eye
moved to (14,9), 3.5 units from the culmen centreline (one clear pixel between dot and stroke at 24) so it no longer welds at
20 px; the crown ends tangent-continuous into the nape (no peak). The tile is
now built from the filled companion. The specimen's light heading uses ink at
62 % instead of the unlisted `#5C544B`. The README now says the neighbours are
Lucide-equivalents for comparison and recommends keeping the status icon on
the chosen mark's `-status` silhouette, withdrawing the earlier proposal to
put the filled companion in the status bar.
