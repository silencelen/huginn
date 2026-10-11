# word-06-pagga — figlet `pagga`, `HUGINN`, unshaded

**Width:** 23 columns × 3 rows. **Charset:** Unicode, but only `█` U+2588 and
`▀` U+2580, which every monospace font has (Liberation Mono included, see the
preview); **not eligible for the CLI client files** (7-bit), and no 7-bit twin
exists at this width. **Files:** `wordmark.txt`, `preview.png`.

```
█ █ █ █ █▀▀ ▀█▀ █▀█ █▀█
█▀█ █ █ █ █  █  █ █ █ █
▀ ▀ ▀▀▀ ▀▀▀ ▀▀▀ ▀ ▀ ▀ ▀
```

## Concept

Half-block capitals in a 3-row cap height. The stock `pagga` font fills its
gaps with `░` shading; that fill is stripped here (the only edit besides the
margin), leaving a clean heavy mark that is the block-element answer to the
box-drawing narrow marks: no line glyphs at all, so it renders identically in
any font that has `█` and `▀`.

## Intended uses

The sub-34-column mark (the verdict's choice): server-side output on a narrow
phone terminal, `huginn-status` header on a phone, the installer console at
small window sizes, a tmux pane split on a phone.

## Strengths

- The most font-independent mark in the set: two glyphs, both universal; the
  only narrow mark that renders in Liberation Mono.
- Heaviest of the narrow marks; reads `HUGINN` at 12 px on both grounds
  (preview), and is the only narrow candidate that survives GitHub `<pre>`
  at all (with seams).
- 23 columns, 3 rows, passes `asciicheck.py`.

## Weaknesses

- The `N` is a square arch (`█▀█ / █ █ / ▀ ▀`) with no diagonal: the word
  reads because it is known, and the last two letters can scan as `ΠΠ`.
- Blocky to the point of retro; it says "8-bit" more than "modern".
- Horizontal seams between the half-block rows in GitHub `<pre>` and faint
  seams in font-glyph terminals (DejaVu 12 px row of the preview).

## Relation to the current identity

A derived variation (shading removed from the stock font). Pairs with
`word-02`/`word-03` in a block-only system; the fallback narrow mark under
the hairline family in the verdict.

## Revision

Leading blank column stripped (24 → 23 columns). The tmux status-bar and
"favicon-sized README table" uses are gone (one-line bar; seams in `<pre>`);
replaced with the uses it can serve. Promoted in `type-system.md` to the
narrow mark because it is the one that cannot tofu. Charset line added;
preview rebuilt.
