# Round 4 — the raven in colour

The mark is decided (`../round3/fold-system/`). Round 4 puts it on the three
palette directions from round 1 (`../color/`) and asks the four questions the
decision checklist says hang on colour. Nothing new is generated here: the
chooser ("The Raven in Colour") draws the decided mark, `glyph-c` and Snapped's
CLI art on each direction's real surfaces using that direction's own
`tokens.json` and `terminal/*.ansi.json` values, so the choice is made by
looking at the system rather than at swatches.

| question | options | recommendation |
|---|---|---|
| Palette ground | `color-01-ink-bone-gold` · `color-02-slate-verdigris` · `color-03-soot-ember` · `color-04-moss-heather` (added at the owner's request: evergreens, purples, dark greys) | 01: the owner's palette is already the product's most distinctive trait and the raven was drawn on it; zero shipped assets change |
| What needs-you means | the accent lit up (new `stateAttention`, the ladder every direction is built on) · error red (today) | lit accent: a question and a failure stop sharing a colour, and the tray ladder survives monochrome |
| Tray cue for needs-you | tinted tile + badge dot · tint only | tint + badge: two cues, survives a template tray, and the attention pulse already pops the dot |
| The state glyph for needs-you | `◆` (colour table) · `▲` (ascii table); `!` in 7-bit either way | diamond: it is in JetBrains Mono and leaves the triangle for error |

Whichever ground wins, the tiers, the state ladder, the ANSI slot pins and the
terminal layer carry over unchanged. The round-4 chooser records the picks;
round 5 is the type direction and the terminal letterform on the chosen ground.

## Decided (round-4 chooser, 2026-10-13)

| question | pick |
|---|---|
| Palette ground | `color-04-moss-heather` |
| What needs-you means | the accent lit up (`stateAttention`) |
| Tray cue | tinted tile + badge dot |
| State glyph for needs-you | `▲` (`!` in 7-bit); error therefore takes `×` (`x`), set in `color/build/spec.py` `STATE_GLYPH` and regenerated into every direction's `tokens.json` |

The recommendation above was 01; the owner chose the direction they asked for.
The three trade-offs in `color/color-04-moss-heather/README.md` (green means
working and success; the heather never reaches the tray; the lit moss is a
highlighter) are the open refinements for the brand book. `▲` is not in
JetBrains Mono's geometric set, so the apps' terminal view will draw it from a
fallback font; the apps themselves draw dots, not glyphs.
