# word-02-mono9-caps — figlet `mono9`, `HUGINN`

**Width:** 40 columns × 5 rows. **Charset:** Unicode (block elements `█ ▀ ▄`
only); **not eligible for `client/huginn.sh` / `huginn.ps1`**. No 7-bit twin
of its own: the ASCII caps are `word-07-cyberlarge` and
`word-09-hairline/ascii.txt`. **Files:** `wordmark.txt`, `preview.png`.

## Concept

A lighter block capital: single-column strokes in `█ ▄ ▀`, 5 rows tall, with
a properly drawn diagonal `N` and a rounded `G`. It has the discipline of a
bitmap font (it is derived from X11's 9-pixel mono) rather than the swagger
of a banner font, which is closer to the product's "warm, quiet terminal"
intent. The flat, shadowless strokes obey the monochrome-first rule and the
letterforms are the same ones as `word-03-mono9-lower`, so caps and
lowercase can coexist.

## Intended uses

README hero replacement for the ANSI Shadow banner (rendered to PNG/SVG, not
as `<pre>`); server-side banners (`huginn-status` header, attach banner);
installer console.

## Strengths

- Lighter on the page than the baseline while staying fully legible; the `N`
  actually has a diagonal and reads at 12 px in every face in the preview.
- Uses only `█ ▀ ▄`, which every monospace font has (Liberation Mono row in
  the preview renders it completely).
- 40 columns: comfortable on a 50-column phone with margin for a prompt.
- Passes `asciicheck.py` natively; no cursor device needed.
- Same family as the lowercase variation, so the two can be a pair.

## Weaknesses

- Still a stock figlet font; distinctive only by restraint.
- Single-column strokes look thin on a high-DPI phone at small font sizes.
- Half-block rows show horizontal seams in GitHub `<pre>` (preview, last
  row) and faint seams in font-glyph terminals.

## Relation to the current identity

Drops the shadow, keeps the weight. Pairs naturally with a mono-led type
system (type-02) or as the terminal face of type-01.

## Revision

Stripped the common leading blank column and the second blank column between
`I` and `N` (figlet's side bearing), so every letter gap is one column and
the mark is 40 × 5 instead of 42 × 5; it no longer reads `HUGI NN` at 12 px.
Removed the `huginn --version` use (that header is printed by the 7-bit
client). Charset line added; preview rebuilt (see word-01's revision note
for the new preview format).
