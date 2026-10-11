# marks-a — vector mark, set A (close to the current DNA)

Three directions that keep the current mark's pose — raven in profile, facing
right, perched, no legs, bill right, eye as an evenodd hole — and lift it to
professional quality. All three share the brand palette (ink `#161310`, bone
`#E8E2DA`, dark ground `#12100F`) and add no colours. Marks 01 and 02 keep the
108×108 box and `translate(2,11)` of `assets/brand/raven.svg` with a bounding
box inside the current one, so every hand-carried copy (`RavenMark.kt`,
`ic_launcher_foreground.xml`, `ic_stat_huginn.xml`) updates by swapping path
data; both ship a separate compact **head drawing for 16–22 px**, because no
drawing of the wide perched pose reads at 16.

**Look first:** [`compare.png`](compare.png) — the current mark and all three
side by side at 64 / 32 / 16, plus the drawing each one actually ships at 16.

| Variation | One line | Look at |
|---|---|---|
| [`mark-01-refined`](mark-01-refined/) | the current silhouette redrawn with raven anatomy: flat crown, arched hooked bill, nape, wing hump, soft hackles, wedge tail; plus a head-and-bill drawing for 16 px | [`mark-01-refined/preview.png`](mark-01-refined/preview.png) |
| [`mark-02-faceted`](mark-02-faceted/) | the same raven cut from 12 straight edges with a round eye: one edge carries back, wing and tail; ships an unfold build-in and a braille tracing; 9-edge head for 16 px | [`mark-02-faceted/preview.png`](mark-02-faceted/preview.png) |
| [`mark-03-monoline`](mark-03-monoline/) | the raven as a Lucide-style 24-grid 2 px stroke icon (long arched bill, wedge tail) with a filled companion for ≤ 20 px, shown in a set beside Lucide-equivalents | [`mark-03-monoline/preview.png`](mark-03-monoline/preview.png) |

## How they compare (what the renders show)

- **64 px and 32 px:** 01 and 02 both read as a raven (bill, flat crown,
  wedge tail) where the current mark reads as a round-headed songbird. They
  are now **different silhouettes** at these sizes: 01 has a convex wing hump
  and a rounded belly, 02 one straight back-to-tail edge and an angular head.
  03 (filled) is a heavy-billed bird, chunkier than both.
- **16 px:** every full-pose drawing, current mark included, is a smudge with
  a bill. The answer in this set is the compact head drawing that 01 and 02
  ship (`-16.svg`): in the tray at 16 it reads as a bird head with an eye on
  dark and light grounds, and at 22 it is unambiguous. 03's filled companion is
  a clean blob with a bill.
- **512 px (hero):** 01 is the warmer, drawn bird; 02 is the sharper, more
  ownable object. A tone decision, not a quality one.
- **Surfaces (in each preview):** launcher under the adaptive mask with the
  66 dp safe ring (both marks inside at 32.4 dp; the current mark is at 32.6),
  the tray composition from `TrayIcons.kt` in `#7B8794` at 16/22 with the
  attention badge, the status icon at 48/72 physical px.

## What changed in revision 2

The creative-director review found the first set under-delivered on the two
things a "close to the DNA" set exists for — a better raven and a 16 px
answer — and that its drop-in claim broke the launcher safe circle by 0.55 dp.
All three were revised in place: flat crown measured (span 21.0 vs 12.8 /
canon 13.6), tail corner raised (32.4 dp), wingtip nick deleted, hackles
redrawn, nape deepened; 02 rebuilt with 12 facets and a round eye so it
differs from 01 at icon size, with the morph and braille claims now proven by
files; 03's head rebuilt with a raven bill and the status-bar proposal
withdrawn; genuine `-16` head drawings added to 01 and 02, with the eyeless
silhouettes moved to `-status`; previews carry launcher, tray and status
simulations; `compare.png` added. Per-variation READMEs have a "Revision"
paragraph with the details.

## Verification

Every SVG was rendered with `tools/render.mjs` (marks and tiles at 512/64/32/16;
`-16` drawings at 32/22/16 on bone and on `#12100F`; `-status` at 48/72;
24-grid files at 192/48/24/20/16; the specimen and the braille file as HTML)
and looked at; each `preview.png` is the exact render. Geometry claims
(bounding boxes, launcher radius under `pivot (52,43) scale 0.72`, crown span,
eye ink ring) were computed from the shipped path data by sampling the curves.
`mark-02-faceted.braille.txt` passes `tools/asciicheck.py`; the specimen's
headings pass `tools/contrast.py` at AA.

## Not done here

- No hand-pass in a vector editor on the cubic control points of 01 (the
  curves come from a tuned point list; C1-continuous except at the intended
  corners). A final polish belongs in the brand-book step once a direction is
  chosen.
- The 16 px head drawings are shown in the tray and as favicons, not yet in
  the Android notification small-icon slot (which is the `-status` silhouette
  at 48–96 px and does not need them).
- Set B (the more adventurous marks) is a sibling folder; this set deliberately
  stays close to the current DNA.
