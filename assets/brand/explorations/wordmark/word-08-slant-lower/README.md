# word-08-slant-lower — figlet `slant`, `huginn`, with the cursor column

**Width:** 36 columns × 6 rows (34 word, 1 gap, 1 cursor). **Files:** `wordmark.txt`, `preview.png`.

## Concept

The italic: lowercase `huginn` in figlet's classic slanted ASCII, followed by
a one-column full-height block cursor. Like `word-03` the cursor is what makes
a lowercase mark pass the equal-length rule, and here it is also the upright
counterpoint to the slant: the word leans, the cursor stands. It is pure ASCII
plus one block glyph, so it is nearly as portable as `word-07`.

## Intended uses

Taglines and sign-offs in the CLI (`huginn` after a successful attach), the
installer's final page, README section dividers where the heavy mark would be
too much.

## Strengths

- The only italic/script candidate that reads cleanly at terminal size;
  `script` and `lean` were surveyed and are prettier but ambiguous.
- Portable: ASCII plus `█`.
- Fits Termux at 50 columns.

## Weaknesses

- A slanted figlet font is a nostalgic idiom; it reads "1990s BBS" before
  it reads "modern".
- The `u` renders as `\/ /` and can scan as `v`; the `g` descender hangs a
  full row below the word.
- The upright cursor beside a slanted word is a deliberate contrast that some
  will read as a mistake.

## Relation to the current identity

The brand has never had an italic voice; this gives the CLI one without
adding a font. Terminal cousin of the italic taglines in all three type
directions.
