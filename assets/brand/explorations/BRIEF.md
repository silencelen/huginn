# Huginn brand overhaul — exploration brief

This folder holds the *variations* produced for the brand overhaul, before the
brand book is written. Each subfolder is one dimension of the identity; each
variation inside it is a self-contained candidate the owner can pick from.

## What Huginn is

One command (`huginn`) reaches a persistent Claude Code session on your own
always-on host, from a laptop, a desktop or a phone. Native Android and desktop
(Compose) apps add chats, live session views and permission prompts answered
from the lock screen. Surfaces that carry the brand today:

| Surface | Where | Today |
|---|---|---|
| README hero | `README.md` | raven SVG + figlet "HUGINN" (ANSI Shadow) + tagline *Thought, on call from anywhere.* |
| CLI | `client/huginn.sh`, `client/huginn.ps1`, Termux | plain text, tmux status bar |
| Android launcher | `mobile/app/.../ic_launcher_foreground.xml` | ink raven on bone tile (adaptive icon) |
| Android notification | `ic_stat_huginn.xml` | monochrome raven |
| Desktop tray | `app-desktop/.../tray/TrayIcons.kt` | raven tinted by state: idle grey `#7B8794`, working blue `#7AA2F7`, attention amber `#E0AF68` + badge dot |
| Desktop window/taskbar | `RavenMark.kt`, `huginn.ico`, `huginn.png` | ink raven on rounded bone tile |
| Installer (NSIS) | `packaging/wizard.bmp` | bone raven on `#12100F`, "Huginn Desktop" in `#8A8177` |
| App UI (phone + desktop) | `mobile/ui/.../theme/Theme.kt` | see palette below |
| Demo GIF | `assets/demo.tape` (VHS) | Catppuccin Mocha theme (NOT brand colours) |

## The story

Named for Huginn, one of Odin's two ravens: *thought* (his brother Muninn is
*memory*). The ravens fly out over the world each day and return to tell him
what they saw. Huginn is your thinking surface: a session that lives on an
always-on host, that you attach to from any device, mirrored and reattachable.
Motifs available to the identity: raven, flight-out-and-return, thought,
Odin's one eye, runes (Ansuz ᚨ is Odin's rune; Raido ᚱ is journey; the Elder
Futhark is a legitimate motif but must stay subtle and never read as neo-pagan
or far-right iconography — avoid Othala ᛟ, Tiwaz ᛏ, Sowilo ᛋ, and any
valknut/swastika-adjacent form), terminals, monospace, the cursor.

## The current identity (the baseline to beat, not to discard)

Canonical mark: `assets/brand/raven.svg` — ONE evenodd path in a 108×108
viewBox, raven in profile facing right, perched (no legs on purpose: "legs
turn to mud at 16px"), eye is a hole so the mark works on any ground.
Every platform icon is hand-derived from this path (the generator script
`assets/brand/generate.sh` lists them).

Palette (Theme.kt, "warm near-black with a parchment ink: a terminal that does
not look like a terminal emulator"):

| Token | Hex | Role |
|---|---|---|
| Ink (mark) | `#161310` | the raven on light grounds |
| Bone | `#E8E2DA` | ink on dark grounds; the icon tile |
| Bg | `#12100F` | app ground (dark is primary) |
| Surface1 / Surface2 | `#1B1817` / `#241F1D` | raised surfaces |
| Accent "rune-gold" | `#C8A45C` | the ONE accent |
| AccentInk | `#241C09` | text on gold |
| Muted | `#8A8177` | secondary text |
| Secondary | `#7DAFEA` | links/functions |
| Error | `#E8736D` | destructive |
| Light bg / ink | `#FAF7F2` / `#1B1817` | light scheme |
| Light primary | `#7A5A16` | gold, darkened for light |

Syntax (dark): keyword `#C495DC`, string `#8CCB7B`, number `#E3C169`,
function `#7DAFEA`, meta `#6FC4C7`. Type: system Material 3 typography; mono
is the platform monospace at 11/15 (phone) and 13/17 (desktop).

Known incoherences the overhaul should resolve:
- The tray state colours (grey/blue/amber) come from "the Electron client" and
  are not the app palette.
- The demo GIF uses Catppuccin Mocha, not the brand.
- The CLI has no visual identity beyond a terminal title.
- The README wordmark is a stock figlet font.
- The mark's body is a blob and the tail a stick at 512px; it only earns its
  shape at 32px and below.

## Owner's direction (verbatim intent)

"professional, quality, and modern design … a cohesive style throughout
platforms and areas … overhaul of our logo and branding elements … keep a
raven as our primary icon … incorporating ASCII art and animation."

## Rules for every variation

1. **Self-contained.** A variation is a folder or file that stands alone with a
   short `README.md` (name, one-paragraph concept, where it would be used,
   what it is good and bad at). No invented facts about the product.
2. **Verified.** Vector marks are rendered at 512/64/32/16 with
   `tools/render.mjs` and the renders are LOOKED AT; a mark that does not read
   as a raven at 16px is revised, not shipped. ASCII art passes
   `tools/asciicheck.py`. Palettes pass `tools/contrast.py` (AA for text).
3. **Raven stays primary.** Every direction keeps a raven as the mark.
4. **Monochrome first.** Every mark must work as one flat colour (tray,
   notification, favicon, terminal) before any colour is added.
5. **No emoji in brand assets.** Glyphs come from box drawing, block
   elements, braille and ASCII; icons from SVG.
6. **Terminal-safe.** ASCII/Unicode art uses only single-width characters
   (box drawing U+2500–257F, blocks U+2580–259F, geometric shapes
   U+25A0–25FF, braille U+2800–28FF, ASCII). Equal line lengths in a block.
7. **Name things plainly.** Variation ids are `<dimension>-<nn>-<slug>`
   (e.g. `mark-03-faceted`). Keep the slug short.

## Tools (in `tools/`)

- `node tools/render.mjs <file.svg|file.html> <out-prefix> [sizes] [bg]` —
  Chromium renders; `sheet.sh <out.png> <pngs…>` tiles them for a look.
- `python3 tools/asciicheck.py <txt…>` — ragged/wide/odd-character check.
- `python3 tools/contrast.py '#fg' '#bg' …` — WCAG ratios.

## Addendum: constraints and surfaces found by the repo scout (read before critiquing or revising)

**Hard constraint — the CLI client files are ASCII-only.** `client/huginn.sh`
and `client/huginn.ps1` must contain no non-ASCII characters (CONTRIBUTING.md
"Dev notes"): `huginn update` ships the file over `scp` without a BOM and
Windows PowerShell 5.1 then mis-decodes it. So anything that will live IN the
CLI (banner, spinner, help raven, status glyphs) needs a **pure 7-bit ASCII
version** (`/\_()=~-|<>^v.:'` etc.). Unicode versions are fine for: README and
docs, the server-side tools (`server/bin/huginn-status` already prints Unicode
and ANSI colour), tmux status bar, the apps, the installer's console. Every
terminal variation should state which of the two it is, and the CLI-facing
ones must ship both.

**The current CLI raven** (help screen of both clients, `huginn.sh:988-994`),
the baseline for the compact ASCII ravens:

```
        _
       (o)==-   huginn - remote Claude Code node.  aliases: rclaude, rcc
       //\
    =~/_/
```

**Terminal title.** Tabs read `<session>` and, while Claude runs,
`<icon> <session>` with emoji icons: 🔄 running, ✋ needs you, ✅ waiting
(`server/bin/huginn-claude-title`, overridable with `HUGINN_ICON_*`). This is
a surface the overhaul may re-glyph (ASCII or single-width Unicode candidates
welcome), since brand assets carry no emoji.

**ANSI colour in use.** `huginn-status`: cyan header "Huginn — host", green /
yellow / red for states, gated on a TTY and `NO_COLOR`. PowerShell client uses
Red / Yellow / Green / DarkGray `-ForegroundColor`. The apps' terminal view has
its own 16-colour ANSI palette tuned to the near-black ground
(`mobile/core/.../ui/Ansi.kt`), which a brand terminal theme should agree
with: 0-7 `#3B3733 #D1544F #69A95B #C7A24A #5B8FD6 #A974C4 #4FA5A8 #CFC8BF`,
8-15 `#6B645D #E8736D #8CCB7B #E3C169 #7DAFEA #C495DC #6FC4C7 #F2ECE4`.

**tmux status bar today** (`server/tmux.conf`):
`status-left "[#{session_name}]  #[fg=colour238]Alt-d:detach  Alt-o:solo  ^b[:scroll#[fg=default]  "`,
`status-left-length 56`. Nothing else is styled.

**State language.** In the apps: running → `primary` (rune-gold), attention →
`error`, idle → `onSurfaceVariant`, no Claude → `outline`; words "working",
"needs you", "waiting". Tray: idle `#7B8794`, working `#7AA2F7`, attention
`#E0AF68` (Tokyo-Night-ish, imported from the retired Electron client). Rounds
verdicts: "All clear" / "Worth a look" / "Needs you". A unified semantic set
must map onto all of these.

**Motion house rule: "a slow pulse rather than a spinner".** The apps' live
marker is `PulseDot`, a STEPPED breath: ten alphas
`0.35 0.5 0.68 0.85 1 1 0.85 0.68 0.5 0.35` at 160 ms per step (1.6 s cycle),
chosen so the phone draws ~6 frames/s instead of 100. "Thinking" text fades
0.35→1 over 900 ms, reversing. Any new brand motion should respect this
energy level and frame budget on the phone; the splash/README may be livelier.

**Layout house rules in the apps:** no left accent bars; selection is a
surface tint, state is a small dot, a count is a muted suffix. Corner radii in
use: 8 dp most common, then 10, 6, 20. No custom fonts are bundled (Material 3
default typography; `Typeface.MONOSPACE` on Android; desktop mono falls back
JetBrains Mono → DejaVu Sans Mono → Liberation Mono → Noto Sans Mono).

**Capitalisation (observed, unwritten):** "Huginn" is the product/app ("Open
Huginn", "Huginn Desktop"); lower-case "huginn" is the command, the host and
the daemon in prose ("Ask huginn"). Muninn (memory) is the companion node and
the README has a "Memory (the other raven)" section — a second raven form may
be reserved for it.

**Surfaces off-brand today (migration list for the colour/cohesion work):**
Glance widgets use system Material You colours (bare `GlanceTheme`), Linux
desktop notifications use stock `dialog-*` icons, Android notifications set
no accent colour, the VHS demo uses Catppuccin Mocha, README headers use emoji
(✨ 🚀 ⌨️ 📱 …), Telegram alerts use emoji (out of scope for brand assets but
worth a glyph table), docs still mention the dropped `huginn:<session>` title.
