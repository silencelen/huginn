# word-03-mono9-lower — figlet `mono9`, `huginn`, with the cursor

**Width:** 43 columns × 7 rows (40 for the word, 2 gap, 1 cursor). **Charset:**
Unicode (block elements `█ ▀ ▄` only); **not eligible for `client/huginn.sh` /
`huginn.ps1`**. The lowercase 7-bit option is `word-08-slant-lower/ascii.txt`.
**Files:** `wordmark.txt`, `preview.png`.

## Concept

Lowercase `huginn` in the same bitmap block face as `word-02`, followed by
the brand cursor: one column of `█`, the full height of the block. The
lowercase matches the command the user actually types (`huginn`), not a
shouted brand name, and the cursor says the session is live and the prompt
is waiting.

About the cursor, plainly: `tools/asciicheck.py` refuses trailing spaces and
ragged blocks, so a lowercase figlet mark (the `h` ascender and `g`
descender rows end short of the last `n`) cannot ship without something in
the last column on every row. Trailing spaces would break nothing in a
terminal; the cursor exists to satisfy the house linter, and it happens to
make a good device. It is now one spec across the folder: **one column, full
block height, `█` in Unicode marks and `|` in 7-bit marks, separated from
the word by the mark's own letter gap** (two columns here, one in word-08).

## Intended uses

Server-side attach banner (printed by the host when a client connects, which
is where Unicode is allowed), `huginn-status`, the apps' terminal view, the
README hero of the mono-led direction (type-02) rendered to PNG/SVG. Not the
CLI client files. Animation hook: the cursor blinks or the raven replaces it.

## Strengths

- Lowercase and quiet; looks like a command, not a logo. `g`, `i` and `n`
  are unambiguous at 12 px (preview).
- Only `█ ▀ ▄`: renders completely in Liberation Mono.
- The cursor gives the animation dimension a hook for free.
- Fits Termux at 50 columns.

## Weaknesses

- The `g` tail is one cell wide and reads as a hook at small sizes.
- The cursor column is mandatory in every placement (see above).
- Seven rows is tall for a phone splash; the word plus a prompt line eats a
  third of a 24-row screen.
- Half-block seams in GitHub `<pre>` (preview, last row).

## Relation to the current identity

Introduces lowercase to a brand that has only ever been `HUGINN`; the apps
and the command are lowercase already, so this closes a gap rather than
opening one. Terminal twin of type-02-plex-mono.

## Revision

Leading blank column stripped; cursor changed from a 2 × 7 block to the
single-column spec shared with word-08; the README now says why the cursor
exists; CLI-client uses moved to the server-side banner; charset line added;
preview rebuilt.
