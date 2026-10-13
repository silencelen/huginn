# compare — the four candidate marks on the real surfaces

One sheet, four columns (`mark-01-refined`, `mark-02-faceted`, `mark-04-pixel`,
`mark-05-glyph`), nine rows: every surface in the brief's table mocked at its
true pixel size, so the primary mark can be chosen by looking at it where it
will actually live rather than on a contact sheet.

| File | What |
|---|---|
| `in-context.html` | the sheet (2400 px wide, app ground `#12100F`, bone text, JetBrains Mono labels). Every cell references the candidate's own SVG files, tinted with a CSS mask where a surface needs one colour; nothing is redrawn. |
| `in-context.png` | the whole sheet at device scale 1 (2400 x 3253) |
| `in-context-2x.png` | rows 3-6 only (tray, favicon, notification, title bar) at device scale 2, for inspecting the 16 px forms |
| `shoot.mjs` | renders both PNGs with the global Playwright Chromium |

## Rows

1. **README hero** — mark at 120 px beside the figlet HUGINN from `README.md` (ANSI Shadow, pre at 12 px / line-height 1.45 as GitHub renders it) and the tagline; dark, then the light README ground `#FAF7F2` with the ink mark.
2. **Android launcher** — the adaptive icon under a circle mask at 48 dp xhdpi (96 px) and at 108 px, between two neutral placeholder tiles. 01/02 use `ic_launcher_foreground.xml`'s group (mark scaled 0.72 about the canvas centre); 04 uses its tile master (exact at 108, launcher-resampled from the 216 px layer at 96); 05 ships no adaptive layout, so its tile is sized to keep the container's corners inside the 66 dp safe circle.
3. **Desktop tray** — taskbar strip `#1B1817`, 16 px (100 %) and 24 px (150 %): idle `#7B8794`, working rune-gold `#C8A45C`, needs-you `#E8736D` with the badge dot in `TrayIcons.kt`'s composition (raven fitted to 50x42 of 64 at (0,22), dot 18 at (44,2)). Each candidate's small-size drawing: `-16` head for 01/02, `m16` for 04 (1x in both slots, per its integer rule), the untiled primary for 05.
4. **Browser tab / favicon** — 16 px beside "Huginn" on a light and a dark tab strip.
5. **Android notification** — card at 2x density, the monochrome status icon at 24 dp = 48 px in white (`-status` for 01/02, `m16` at 3x for 04, the primary for 05).
6. **Desktop window title bar** — 20 px beside "Huginn" (bone tile with the small drawing for 01/02/04; the primary tinted bone for 05).
7. **Installer welcome panel** — 164x314, `generate.sh`'s layout: bone mark trimmed to ~104 px wide at +52, "Huginn Desktop" 12 pt Liberation Sans `#8A8177` at +140. 04 is 96 wide (3x), 05 is a 104 px square.
8. **CLI help header** — the 7-bit ASCII 4-line raven from `ascii/ascii-04-lockups/help-header-ascii.txt`, identical in every column: it is derived from today's mark and does not change with the vector choice.
9. **Hero** — 512 px on bone (ink mark; 04 is `m32` at 16x; 05 is its tile master).

Tray needs-you is shown in the apps' error red rather than the retired Electron amber, per the brief's state-language note. The pixel candidate has no shipped needs-you composition; the sheet pushes `m16` to the bottom-left of the slot and puts the dot in the cleared corner.

## Regenerate

```sh
cd assets/brand/explorations
node compare/shoot.mjs        # writes compare/in-context.png and compare/in-context-2x.png
```

Needs the global `playwright` (resolved from `npm root -g`) with its Chromium, network access for Google Fonts (JetBrains Mono; the figlet pre uses Liberation Mono / DejaVu Sans Mono so the box-drawing shadow renders as on GitHub). Both PNGs come out well under 1.5 MB; if a change pushes one over, `convert in.png -colors 255 PNG8:out.png` brings it back.

## What the sheet shows

**mark-01-refined.** The safest column. The full mark reads as a raven at 120 and 512 and sits well inside the launcher disc with air around it; the status silhouette at 48 is clearly a bird. At 16 the `-16` head drawing holds on the taskbar in all three tints, with the eye as one clean pixel, and the 24 px row is unambiguous. The cost is visible in the sheet: the tray, favicon and title-bar icon are a different drawing from the hero and the launcher (a head versus a whole perched bird), so the identity at small sizes and at large sizes are cousins, not the same shape. Beside the figlet banner the smooth silhouette is quieter than the heavy block type; it does not clash, but the stock wordmark outweighs it.

**mark-02-faceted.** At every size up to the launcher it is nearly indistinguishable from 01 (the two `-16` heads differ by a vertex), so the small-size verdict is the same: holds at 16, clean at 24. The difference appears at 120 and 512, where the twelve straight edges give a sharper, more graphic silhouette that pairs better with the angular figlet than 01 does, and reads as a crisper launcher icon at 108. The honest downside is also at 512: the angular head edges toward a generic polygon-bird before the eye and bill resolve it. Same two-drawings caveat as 01.

**mark-04-pixel.** The strongest at exactly 16 and 32 and the only one that speaks the same language as the README banner and the terminal: in the hero row the pixel raven and the block-character figlet are visibly one vocabulary, and the 16 px tray icon is the most bird-like of the four (long bill, wedge tail, no anti-aliasing). The sheet also shows the price of the integer rule: in the 24 px tray slot it stays 16 px and sits visibly smaller than its neighbours; at the 96 px launcher the launcher's own resample softens the cells, while 108 is exact; the 512 hero is chunky by design with the bill and tail touching the box edges; the installer mark is 96 wide, not 104. It is a committed retro / terminal-native look, not a refinement of today's mark.

**mark-05-glyph.** The best single image in the set at 512 (the tile master on bone) and the most legible shape at 16, because a solid rounded square with a hole survives where a thin silhouette does not: on the taskbar it is a clean tile in every tint, and the favicon is a crisp dark tile on the light strip. Three things the sheet makes plain: tinting the whole container means the working and needs-you states are loud coloured squares, the loudest in the tray row; in the launcher it is a tile inside a disc rather than a bird on a tile, so it reads as an app icon more than a raven; and a square mark does not fit `generate.sh`'s installer layout (the 104 px container runs into the wordmark line) and needs a new layout there. Next to the figlet the bone tile competes with the block letters; it wants its own wordmark. Species is gone at 16 (a bird in a tile), as its README says.
