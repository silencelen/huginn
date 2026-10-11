# word-10-perch — the raven replaces the `I`

**Width:** 41 columns × 5 rows. **Files:** `wordmark.txt`, `preview.png`.

## Concept

The hairline letters of `word-09` with a 5-row raven standing in the `I`'s
place: solid half-block pixels (`█ ▀ ▄`) with a one-pixel eye, a `▶` bill
pointing into the word, a sloping breast and a tail trailing to the lower
left, perched, feet hidden, exactly the posture of the canonical mark. The
bird is the only solid shape in a word made of lines, so it reads first and
the letters read around it. It is the ASCII answer to the "raven as the
tittle of the i" lockups in the type specimens.

```
│  │ │  │ ╭───╮      ▄█▀██▶ │╲   │ │╲   │
│  │ │  │ │          ████▀  │ ╲  │ │ ╲  │
├──┤ │  │ │ ╶─┐    ▄█████   │  ╲ │ │  ╲ │
│  │ │  │ │   │  ▄███████   │   ╲│ │   ╲│
│  │ ╰──╯ ╰───╯ ▀▀  ▀████▀  │    │ │    │
```

## Intended uses

The CLI's signature splash (desktop terminals), the installer's banner, the
"about" line in the apps' terminal view; the animation dimension can let the
bird leave the `I` and return.

## Strengths

- A real lockup, not a font: the mark and the word are one object, which no
  figlet output can do.
- The raven reads as a bird at every size tried (it has an eye, a bill and a
  tail; the 3-row attempts did not and were discarded).
- Heavy bird on hairline letters works on both grounds and in one colour.
- 41 columns, 5 rows; passes `asciicheck.py`.

## Weaknesses

- Inherits every fragility of `word-09`: needs cell-tight box drawing, so it
  is a terminal mark, not a GitHub `<pre>` mark.
- Depends on `▶` (U+25B6) for the bill; it is in DejaVu Sans Mono and
  JetBrains Mono but a font without it will show a tofu box.
- The pixel raven is a sibling of the SVG raven, not a trace of it; the
  silhouettes agree (bill right, perched, tail low-left) but the proportions
  differ, and the eye is a notch rather than a hole.
- The word is `HUG NN` with a bird in it; first-time readers need the raven to
  be recognised before the word completes.

## Relation to the current identity

Keeps the raven primary in the one place it currently has no presence: the
terminal. The silhouette follows `raven.svg` (profile facing right, perched,
no legs, eye on the head).
