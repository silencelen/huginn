# marks-b — vector mark, set B

Three directions that break further from the current `raven.svg`, each built
for a specific role in a terminal-native identity. Every variation keeps the
raven primary, works in one colour on any ground (ink `#161310` / bone
`#E8E2DA`), ships a tile master with the set's 22.5 % corner radius, and was
rendered with `tools/render.mjs` at 512/64/32/16 (and at the sizes it is
weak at) and looked at before it was kept.

| Variation | One line | Look at |
|---|---|---|
| [`mark-04-pixel`](mark-04-pixel/) | The ASCII-native raven: a 16x16 master and its refined 32x32 twin in `grid.txt` are the source of truth; the vector marks, the half-block / braille / 7-bit terminal art, the animation frames and the per-density PNGs are all generated from the same cells. | `mark-04-pixel/preview.png` |
| [`mark-05-glyph`](mark-05-glyph/) | The terminal raven: a raven head and breast cut as negative space out of a solid rounded square (also a circle and a terminal window), eye as a pixel-aligned ink cell; one evenodd path, the tray-safe shape. | `mark-05-glyph/preview.png` |
| [`mark-06-flight`](mark-06-flight/) | The raven in flight from below, wedge tail and fingered primaries, for hero, splash and installer; plus a three-shape 24x24 "bird returning" glyph for the tray's attention state. | `mark-06-flight/preview.png` |

**How they fit together** — `system.png` at this level shows the three
used as one system on the app ground with the apps' state language (muted
idle, rune-gold working, error-red needs-you): `mark-05-glyph` is the tray,
notification and favicon shape (tinted by state), `mark-06-flight-small` is
the tray's attention state, `mark-06-flight` (splash lockup / inverse) is
the hero and launch moment, `mark-04-pixel` is the terminal: braille in the
README and docs, 7-bit ASCII in the CLI clients, half-block in tmux. Any one
can be chosen alone; the pixel raven is the only one that makes the terminal
art and the icon the same drawing, and the only one with a CLI-safe form.

Candid ranking for a 16px tray: glyph (clean tile, bird inside) >
pixel `m16` (crisp, a corvid with a long bill) > flight-small (a bird, not a
raven) >> flight. For a 512px hero: flight > glyph inverse > pixel `m32`.
Species certainty: flight (wedge tail + primaries) > pixel `m32` > glyph
(corvid, could be crow) > pixel `m16` > flight-small (none).

Revision after review: all three were redrawn in place — see the
"Revision" section of each README. The pixel masters were redrawn (duck /
puffin reads removed), the glyph's head was rebuilt (falcon read removed)
and rebalanced, the flight mark was rebuilt inside its viewBox with real
primaries, a 75 % head and a wedge tail. Added: 7-bit ASCII and padded-PNG
forms of the pixel raven, shipped animation frames, the wide splash lockup,
and `system.png`.
