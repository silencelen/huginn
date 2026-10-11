# word-04-future — figlet `future`, `HUGINN`, the 3-row narrow mark

**Width:** 16 columns × 3 rows. **Files:** `wordmark.txt`, `preview.png`.

## Concept

Heavy box-drawing strokes (`┏━┓ ┃ ┗━┛` with half-stroke ends `╻ ╹`) in a
3-row cap height. At 16 columns it is the narrowest mark in the set and the
one for places where 40 columns is all there is: a Termux splash, a tmux
status bar, the first line of `huginn list`. The heavy line weight keeps it
visible at phone font sizes where thin box drawing turns to dust.

## Intended uses

Termux / narrow phone terminals, tmux status-left, log headers, the
`huginn --help` masthead.

## Strengths

- 16 columns: fits anywhere, including a 40-column Termux session with room
  for a prompt beside it.
- Heavy strokes survive small font sizes and dark/light grounds equally.
- Passes `asciicheck.py` natively.

## Weaknesses

- The `N` has no diagonal (`┏┓╻ / ┃┗┫ / ╹ ╹`); it reads as `N` only because
  the word is known. In isolation `HUGINN` can scan as `HUGIMM`.
- Depends on heavy box-drawing glyphs (U+250F etc.); DejaVu, JetBrains Mono
  and Cascadia have them, some older terminal fonts do not.
- No lowercase.

## Relation to the current identity

New: the brand has never had a narrow mark. Partners with any of the type
directions; sits beside the drawn raven in a tmux status bar.
