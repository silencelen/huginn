# word-08-slant-lower — figlet `slant`, `huginn`, with the cursor

**Width:** 36 columns × 6 rows (34 word, 1 gap, 1 cursor). **Charset:**
`wordmark.txt` is Unicode by exactly one glyph (the `█` cursor); `ascii.txt`
is the **7-bit twin** with a `|` cursor, **eligible for `client/huginn.sh` /
`huginn.ps1`**. **Files:** `wordmark.txt`, `ascii.txt`, `preview.png`.

```
    __                _            █
   / /_  __  ______ _(_)___  ____  █
  / __ \/ / / / __ '/ / __ \/ __ \ █
 / / / / /_/ / /_/ / / / / / / / / █
/_/ /_/\__,_/\__, /_/_/ /_/_/ /_/  █
            /____/                 █
```

## Concept

The italic: lowercase `huginn` in figlet's classic slanted ASCII, followed by
the brand cursor (one column, full block height, `█` or `|`). The cursor is
what lets a lowercase mark pass the equal-length rule (see word-03 for the
plain statement of that), and here it is also the upright counterpoint to the
slant: the word leans, the cursor stands.

## Intended uses

Sign-offs in the CLI client (`ascii.txt` after a successful attach or update),
the installer's final page, README section dividers where the heavy mark
would be too much.

## Strengths

- The only italic/script candidate that reads cleanly at terminal size;
  `script` and `lean` were surveyed and are prettier but ambiguous. Reads
  `huginn` at 12 px in every face including GitHub `<pre>` (preview).
- `ascii.txt` is pure 7-bit; `wordmark.txt` needs only `█`.
- Fits Termux at 50 columns.

## Weaknesses

- A slanted figlet font is a nostalgic idiom; it reads "1990s BBS" before
  it reads "modern".
- The `u` renders as `\/ /` and can scan as `v`; the `g` descender hangs a
  full row below the word.
- The upright cursor beside a slanted word is a deliberate contrast that some
  will read as a mistake.
- The `|` cursor of the ASCII twin is thinner than the `/` strokes beside it.

## Relation to the current identity

The brand has never had an italic voice; this gives the CLI one without
adding a font. Terminal cousin of the italic taglines in all three type
directions.

## Revision

`ascii.txt` added (cursor `|`). The backtick in the `g` (`__ `/`) is now an
apostrophe in both files, so the block is safe in a PowerShell double-quoted
here-string as well as a single-quoted one (no `$` or backtick remains).
Cursor spec unified with word-03. Preview rebuilt with both files.
