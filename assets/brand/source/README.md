# Brand source files

The files the product's brand assets are built from, and the tools that build them. The rules
they implement are in [`../BRAND.md`](../BRAND.md).

| Folder | What |
|---|---|
| `color/` | The colour system. `color-04-moss-heather/` holds the shipped tokens (`tokens.json`) and the terminal palette (`terminal/moss-heather.ansi.json`, `tmux.conf.snippet`); `build/palette.py` generates them from `build/spec.py` and refuses a set that breaks a contrast or ladder rule. The other three palettes are the alternatives the generator still emits. |
| `round3/fold-system/` | The raven: `mark*.svg`, the tile glyphs (`glyph-*.svg`) and the terminal rasters (`ascii-*.txt`), all built by `build.py` from one geometry. |
| `round2/r2-01-snapped/`, `marks-b/mark-04-pixel/` | Inputs `round3/fold-system/build.py` still reads. |
| `round5/` | The one-row and five-row text ravens the console prints (huginn core keeps a copy and a test compares them). |
| `anim-vector/` | Motion: the blink behind `../raven-blink.svg`, the attention pulse and the tray sprite spec. |
| `tools/` | `render.mjs` (SVG to PNG), `cut_lockup.py` (the wordmark and lockup outlines), `contrast.py`, `asciicheck.py`. |

The full record of the brand explorations (every candidate, preview and chooser) is on the
`archive/design-history` branch.
