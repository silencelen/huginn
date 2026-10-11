# mark-05-glyph — the terminal raven (raven as negative space)

**Look at:** `preview.png`

## Concept

The container is the mark and the raven is the hole. A solid rounded square
of ink carries a raven's head and breast cut out of it, the breast opening
onto the bottom edge so the bird rises out of the ground rather than floating
in a frame. The eye is a single 4x4 *cell* of ink set back inside the hole.
The whole thing is ONE evenodd path (container, minus raven, plus eye), so it
works in one flat colour on any ground: in a tray it is a tile with a bird in
it; on the app's dark ground, tinted bone or a state colour, it is a light
tile with a dark raven; inverted it is a bone tile whose raven is the dark
ground showing through. Three containers share the same head: a rounded
square (primary), a circle (the breast closed by an inner arc), and a
terminal window (an 8-unit title bar split off by a 4-unit gap, with a bone
cell at its left as the prompt block).

**The head.** Flat crown, a small forehead step, a straight culmen that
arches only in its last three units, a straight gonys from the tip back to
the chin, and a throat that flows straight into the breast with no neck:
the raven's heavy, long bill (20 units long, 12.7 deep at the base) and
shaggy throat, drawn as plainly as a container will allow. The hole sits
with 18 units of ink above the crown, 9 to the right of the tip and 9-14 to
the left, so the dark mass and the bird balance at 512.

**Why the eye is a square cell, not a dot.** At 16px a round eye of any
useful size becomes a grey smudge; a cell at x 28-32, y 24-28 of a 64 box
lands on whole pixels at 16, 32 and 64 and stays a crisp dark pixel. A
filled cell is also what a terminal block cursor is, which is the one eye
this product should have: the blink animation of the eye *is* the cursor
blink.

## Files

| File | What |
|---|---|
| `mark-05-glyph.svg` | primary: rounded square container in ink, raven hole, eye cell; viewBox 0 0 64 64; one path |
| `mark-05-glyph-inverse.svg` | the same path in bone on an ink ground |
| `mark-05-glyph-tile.svg` | the ink container inset 10% on a rounded bone tile (80x80, rx 18 = 22.5%): app icon / taskbar, **48 px and up** |
| `mark-05-glyph-circle.svg` | circle container, same head; the breast is closed by a radius-28 arc so the ring keeps 4 units of ink everywhere — for avatar slots that crop to a disc |
| `mark-05-glyph-window.svg` | terminal-window container, same head moved down 8 (eye at y 32-36, still whole pixels); title bar 8, gap 4 (2 px and 1 px at 16), prompt cell at the bar's left — CLI banner header, docs, the desktop app's about panel |
| `preview.png` | all of the above at 512/64/32/16 on both grounds, the primary tinted bone and the apps' state colours on the app background as the tray would show it, the tile at 48 and 32, and the current mark |

## Intended uses

- **System tray, notification icon, favicon**: the primary, tinted by state
  (one colour, so any state colour works: `preview.png` shows bone, rune-gold
  "working", error "attention" and muted "idle" at 16) — a tile reads at 16
  where a thin bird silhouette does not.
- **App icon** (`-tile.svg`): Android adaptive foreground and desktop icon,
  from 48 px up; below that the un-tiled primary.
- **Avatar**: `-circle.svg` wherever the slot is round (GitHub org, Slack).
- **Terminal / docs chrome**: `-window.svg` next to the wordmark, where the
  "terminal window" shape says what the product is.
- **Animation hook**: the eye cell toggles (blink / cursor) without touching
  the silhouette; the hole can also fill from the bottom as a progress or
  "session attaching" motion; the window's prompt cell can blink in step.

## Strengths

- A clean container silhouette at every size: the one shape that is
  genuinely safe at 16px in a tray, and it still carries a bird.
- One path, one colour, no strokes, no gradients: trivially tinted, trivially
  inverted, pixel-aligned eye; VectorDrawable, Java2D and Compose all draw it
  with `evenOdd` and nothing else.
- Reads as a corvid head at 512 and 64 and as "a bird in a tile" at 32.
- The negative-space idea is distinctive next to the many "bird silhouette"
  marks; the inverse (dark raven rising through a bone tile) is the best
  single image in the set; the window variant ties the raven to the terminal
  literally.

## Weaknesses

- At 16px it is "a dark tile with a light bird-shaped hole": the bill is one
  or two pixels and the species is gone. That is the honest ceiling of any
  16px mark; this one at least keeps a solid, recognisable tile.
- It is a head, not a whole raven: the wedge tail and the perched pose of the
  current mark are lost, so at hero size it reads corvid (crow, rook) before
  raven; it needs the full mark (or `mark-06-flight`) beside it there.
- The tile master is soft at 32 (its cells are not on whole pixels) and a
  bone ring round a muddy centre at 16: use the un-tiled primary below 48.
- The window's prompt cell is a soft dot at 16; the gap is 1 px, which is
  fine, but the bar reads as a card edge rather than a title bar until 32.
- The circle's closed breast turns the bust into a cameo; the "rising out of
  the ground" idea is lost in that variant.

## Relation to the current mark and palette

Same ink `#161310` and bone `#E8E2DA`, same profile-facing-right convention,
same "eye works on any ground" rule — but inverted: the current mark is a
positive raven with an evenodd eye hole; this one is a negative raven with
an evenodd eye *cell*. The two can coexist: the current (or pixel) raven as
the figure, this glyph as the tile/tray form of the same bird.

## Revision

After review: the head was rebuilt — the previous hole was one continuous
arc from crown to bill tip with a short, deep bill and a straight angled
chin, which drew a falcon. The new hole has a flat crown, a forehead step,
a straight culmen with the arch only at the tip, a shallow gonys and a
throat that flows into the breast (an intermediate version with a pinched
neck read as a goose and was rejected). The hole moved up and left
(crown 20 to 18, tip 58 to 55, back edge 18 to 9) so the walls balance;
the eye cell moved with it to x 28-32, y 24-28 (still whole pixels at
16/32/64). The circle's breast is now closed by a radius-28 arc instead of
opening onto the edge, which removed the sub-2-unit ring slivers; the
window's bar is 8 with a 4 gap and a prompt cell, with the head moved down
8 for breathing room; the tile's lower limit is now stated as 48, not 32.
All five files are generated from one shared head subpath.
