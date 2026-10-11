# word-10-perch — the raven replaces the `I`

**Width:** 44 columns × 5 rows, all three text files. **Charsets:**
`wordmark.txt` Unicode (hairline letters as word-09 plus `█ ▀ ▄` for the
bird; no `▶` any more); `square.txt` Unicode with square corners and `\`
(renders completely in Liberation Mono, see the preview); `ascii.txt`
**7-bit ASCII, eligible for the CLI client files**. `wordmark.svg` /
`wordmark-dark.svg` are the README-safe renditions: the same 44 × 5 grid drawn
as geometry (lines, arcs and rects on a 10 × 20 cell), no font involved.
**Files:** `wordmark.txt`, `square.txt`, `ascii.txt`, `wordmark.svg`,
`wordmark-dark.svg`, `preview.png`.

```
│  │ │  │ ╭───╮       ▄█▀██▄   │╲   │ │╲   │
│  │ │  │ │         ▄███████▀▀ │ ╲  │ │ ╲  │
├──┤ │  │ │ ╶─┐   ▄████████    │  ╲ │ │  ╲ │
│  │ │  │ │   │ ▄██▀███████    │   ╲│ │   ╲│
│  │ ╰──╯ ╰───╯ ▀█   ▀███▀     │    │ │    │

|  | |  | .---.       .####.   |\   | |\   |
|  | |  | |          ##o####== | \  | | \  |
|--| |  | | --.    ########    |  \ | |  \ |
|  | |  | |   |   #########    |   \| |   \|
|  | '--' '---'  ##  #####     |    | |    |
```

## Concept

The hairline letters of `word-09` with a raven standing in the `I`'s place.
The bird is now taken from the canonical mark rather than drawn beside it:
`raven.svg` was rasterised onto a 14 × 10 half-block grid (square pixels, the
cell of a terminal font being about twice as tall as wide) and hand-tuned, so
the proportions are the SVG's: a 1.65:1 silhouette, the head over the right
half of the body, the eye a hole, a long straight bill at mid-head (two full
cells tapering to two half cells), a plump breast, the tail trailing to the
lower left with the notch between tail and belly that the SVG has, perched,
no feet. It is the only solid shape in a word made of lines, so it reads
first and the letters read around it.

The ASCII twin keeps the silhouette as a `#` mass with an `o` eye and a `==`
bill (the bill of the existing help-screen raven, `(o)==-`), on the ASCII
hairline letters, so the client scripts show the same lockup the server does.

## Intended uses

The signature banner, printed server-side: `huginn-status`, the attach banner,
the installer console (Unicode allowed on all of these); the CLI client's
help masthead via `ascii.txt`; the README hero and docs via the SVGs
(`<picture>` with `prefers-color-scheme` to switch the two); the apps' About
line in their terminal view. Animation: the bird leaves the `I` and returns.

## Strengths

- A real lockup, not a font: the raven is primary, in the one place it had no
  presence (the terminal). At 12 px DejaVu Sans Mono and 13 px JetBrains
  Mono (preview, 1:1 rows) the eye, bill, breast and tail are all present on
  both grounds in one colour.
- It is a raven now, not a sparrow: long bill, big head, hunched back.
- No `▶`: the bird uses only `█ ▀ ▄`, so with the square letters the whole
  mark renders in Liberation Mono and in IBM Plex Mono (which has no `▶`).
- Three charsets plus a geometry SVG: desktop terminal, any-font terminal,
  7-bit client, README.
- 44 columns, 5 rows; passes `asciicheck.py`.

## Weaknesses

- The word is still `HUG [bird] NN`: on row 1 there are seven blank cells
  between the `G` and the bird's head, because a raven's head is at the
  upper right and its tail at the lower left. The review asked for the head to
  move two cells left; that version was rendered and reads as a chick (see
  the revision note), so the void stays. First-time readers need to recognise
  the bird before the word completes.
- Inherits word-09's fragilities: `wordmark.txt` needs `╭ ╮ ╰ ╯ ╲ ╶`
  (Liberation Mono: tofu), JetBrains Mono notches the diagonal, GitHub `<pre>`
  opens gaps (preview). That is what `square.txt`, `ascii.txt` and the SVG
  are for.
- The ASCII bird is textured, not solid: `#` is a hatched glyph, so the `o`
  eye is faint at 12 px and the silhouette reads as a bird before it reads as
  a raven.
- The pixel raven is a rasterisation of the SVG at 14 × 10, not a trace: the
  silhouettes agree, the curves do not exist.
- The geometry SVG's hairlines are 1.6 units on a 10-unit cell; below about
  200 px wide the letters go grey while the bird stays black.

## Relation to the current identity

Keeps the raven primary in the terminal, and now keeps its proportions: the
pixel bird is derived from `raven.svg` by rasterisation, with the same
posture (profile facing right, perched, no legs, eye as a hole, long bill).

## Revision

Bird redrawn from a rasterisation of `raven.svg` (previous bird: 11 × 10 grid,
short `▶` bill at the top corner, read as a sparrow); the review's literal fix
("head two cells left, `█▶` on the row below the eye") was built as one of
six candidates and rendered at 12 px and 24 px beside the others; it read as
a chick with a notch and was rejected in favour of the canonical proportions.
Bill lengthened to four cells and the `▶` dependency removed. Added
`square.txt`, `ascii.txt`, `wordmark.svg` and `wordmark-dark.svg`. The
"signature splash" use now names the server-side banner explicitly and the
client gets `ascii.txt`. Width 41 → 44 columns (still fits Termux at 50).
Preview rebuilt with 1:1 rows at 12 and 13 px, both renderer families,
Liberation Mono and GitHub `<pre>` for every file.
