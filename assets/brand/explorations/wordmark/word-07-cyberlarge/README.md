# word-07-cyberlarge — hand-fixed figlet `cyberlarge`, `HUGINN`, the slim 7-bit mark

**Width:** 40 columns × 3 rows. **Charset: 7-bit ASCII** (`_ | \` and spaces);
**eligible for `client/huginn.sh` and `client/huginn.ps1`**, plain-text
release notes, email, `NOTICE` files. **Files:** `wordmark.txt`, `preview.png`.

```
_    _ _    _  _____ _____ __   _ __   _
|____| |    | |  ___   |   | \  | | \  |
|    | |____| |____| __|__ |  \_| |  \_|
```

## Concept

Pure ASCII in three rows: a thin, airy capital with wide proportions. Because
it uses no box or block glyphs it is immune to the one thing that bites every
other variation: it looks the same in a terminal, in GitHub's `<pre>`, in a
plain-text email and in a `.txt` licence header, and it is one of only two
marks in the folder that may live inside the CLI client files (the other is
`word-09-hairline/ascii.txt`). It is the light-weight counterpart to the
heavy baseline.

The figlet original was uneven (H/U/G 7 cells, I 5, N 6, and a `G` that read
as `(=`); the letters are redrawn on a 6-cell module (I stays 5) and the `G`
has a closed spur: ` _____ / |  ___ / |____|`.

## Intended uses

`huginn --help` masthead and the installer's first console line in the
*client* scripts (the only place a Unicode mark cannot go), README when the
block seams must be avoided, release notes, email, `NOTICE`.

## Strengths

- Renders identically everywhere: terminal, GitHub `<pre>` (preview, last
  row), plain text; nothing can be missing from a font.
- Reads `HUGINN` at 12 px DejaVu and 13 px JetBrains Mono, dark and light
  (preview); the `G` now closes.
- 40 columns fits Termux; passes `asciicheck.py`; safe in a bash quoted
  heredoc and in a PowerShell here-string (no `$`, no backtick).

## Weaknesses

- Thin: at phone sizes on a dark ground the strokes are faint, and the
  underscores render as dashed lines in every font because `_` does not span
  its cell.
- A 3-row ASCII capital has a low ceiling: the top bars sit at the baseline
  of row 1, so the letters look shorter than they are.
- Lighter than everything else in the folder; next to the heavy marks it
  looks like a different brand. Its real twin is `word-09`'s ASCII twin,
  which carries the same hairline letterforms as the signature.

## Relation to the current identity

A genuinely different weight from today's banner; the first ASCII-only
candidate. Terminal cousin of type-01's tight sans.

## Revision

Hand-edited per the review: 6-cell H/U/N, closed-spur `G`, leading blank
column stripped (44 → 40 columns); tested at 12 px before shipping. The
README now says it is 7-bit and client-eligible, which is its strongest
argument. A heavier 7-bit mark was not added as a separate variation: the
7-bit role for the signature is filled by `word-09-hairline/ascii.txt` and
`word-10-perch/ascii.txt`, which keep the folder to one letterform family.
