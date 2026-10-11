# word-06-pagga — figlet `pagga`, `HUGINN`, unshaded

**Width:** 24 columns × 3 rows. **Files:** `wordmark.txt`, `preview.png`.

## Concept

Half-block capitals (`█ ▀`) in a 3-row cap height. The stock `pagga` font
fills its gaps with `░` shading; that fill is stripped here (the only
edit), leaving a clean heavy mark that is the block-element answer to the
box-drawing narrow marks: no line glyphs at all, so it renders identically in
any font that has `█` and `▀`, which is every monospace font.

## Intended uses

Narrow splash where the terminal font's box drawing is unreliable (older
Windows consoles, some Android terminal fonts); tmux status bar; favicon-sized
text in a README table.

## Strengths

- The most font-independent mark in the set: two glyphs, both universal.
- Heaviest of the narrow marks; survives tiny sizes and low contrast.
- 24 columns, 3 rows, passes `asciicheck.py`.

## Weaknesses

- The `N` is a square arch (`█▀█ / █ █ / ▀ ▀`) with no diagonal: the word
  reads because it is known, and the last two letters can scan as `ΠΠ`.
- Blocky to the point of retro; it says "8-bit" more than "modern".
- Block-glyph seams in GitHub `<pre>`.

## Relation to the current identity

A derived variation (shading removed from the stock font). Pairs with
`word-02`/`word-03` in a block-only system.
