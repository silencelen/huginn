# Reskin handoff — for the session that builds and merges

Written 2026-10-13 by the session that ran the brand explorations. That session
had no Android SDK and Gradle could not resolve the Kotlin plugin there, so
**nothing in `mobile/` on this branch has been compiled**. The shell changes
pass `bash -n` and the CLI gate (`scripts/test-client.sh`: 178 ok; the only
failures are the daemon-token tools, which need `/etc/huginn-appd/token`).

## What is on `claude/amazing-keller-uk2wd5`

In commit order, newest last:

1. The explorations (`assets/brand/explorations/`): six rounds, every variation
   with its README and preview, the owner's picks recorded in
   `explorations/README.md` "Decisions so far".
2. **The brand book**: `assets/brand/BRAND.md` (the rules) and its rendered twin.
3. **The brand in the product** (three commits, "brand: …"):
   - `assets/brand/raven*.svg` replaced with the decided mark; `wordmark*.svg`,
     `lockup*.svg` cut from Plex Mono outlines with the raven as the cursor;
     `generate.sh` with a Playwright fallback; every raster regenerated.
   - `mobile/ui/.../theme/Theme.kt` on the Moss & Heather tokens with
     `HuginnColors`; `core/.../ui/Ansi.kt` on the brand ANSI-16;
     `app-desktop/.../tray/RavenMark.kt` and `TrayIcons.kt` on the new mark and
     the tile; Android launcher foreground and status icon; `colors.xml`.
   - `server/bin/huginn-status`, `huginn-claude-title`, `server/tmux.conf`,
     `client/huginn.sh` (colour on the two messages a user must notice).
   - `README.md` header on the lockup; `assets/brand/raven-blink.svg`.
4. **The UI audit**: `assets/brand/UI-AUDIT.md` (and its page), six PR briefs
   under `assets/brand/pr/`.
5. **PR 1 drafted in code** ("PR 1 draft: state language"): `ui/.../theme/StateColors.kt`
   plus the fourteen sites in `pr/01-state-language.md`. Uncompiled.

## First thing to do

```sh
cd mobile && scripts/build.sh          # both Kotlin suites, asserts the counts
```

Expect to fix small things: a missing import, a `when` that is no longer
exhaustive, a `LocalHuginnColors.current` read outside a composable. The intent
of every change is in the commit message and the brief; the brief wins over the
code where they disagree.

Tests that pin old values and were updated: `core/src/commonTest/.../RoundsTest.kt`
(round colour keys). Tests that pin fixtures and were left alone on purpose:
`TerminalGridTest` FG/BG, `VerbInkTest`'s own scheme. `PaletteTest` gates the
roles the theme leaves undefined (`secondaryContainer`, `tertiaryContainer`);
PR 2 may define `secondaryContainer`, and then that gate's list must shrink.

## Then

Split the drafted commits into pull requests as `pr/README.md` lists them, in
that order, each with device screenshots (the audits could not take any). Each
brief has the sites, the acceptance checks and what to screenshot. PRs 2 to 6
are briefs only; they are sized at one to three days each.

Versions and changelogs per component before a cut: client and server core
1.6.2 → 1.7.0 (root `CHANGELOG.md`, `HUGINN_VERSION` in both clients), Android
3.9.2 → 3.10.0 (`mobile/CHANGELOG.md`), desktop 1.10.1 → 1.11.0
(`mobile/app-desktop/CHANGELOG.md`); the daemon only if the Telegram marks
change (PR 6). Merging to `main` deploys the terminal client at once, because
`huginn update` pulls the client files from `main`.

## Decisions you should not relitigate

Every one of these was chosen by the owner on a chooser; the record is in
`BRAND.md` "Record of decisions".

- The mark is the upright fold raven; the tile is the mark in negative.
- Moss & Heather is the palette; needs-you is the lit accent, never error red;
  the tray shows the tinted tile plus the badge; the needs-you glyph is `▲` and
  error is `×`.
- Plex Mono leads, Plex Sans reads; the lockup is lowercase `huginn` with the
  raven as the cursor.
- The client's help screen keeps today's `(o)==-` bird, and the 7-bit landing,
  takeoff and boot stay as they are. That is a deliberate exception.
- The one-row raven is `(o>` with `(!>` and `(->`.
- The hero is the still lockup; the recommended motion set ships.

## Known open refinements (not blockers)

Green means both working (moss) and success (lime); the heather never reaches
the tray; the lit moss reads as a highlighter beside the lime. Refine before the
tokens are frozen, then regenerate `tokens.json`, `Theme.kt` and `Ansi.kt` from
`assets/brand/explorations/color/build/` (edit `spec.py`, run `palette.py`,
which refuses if any contrast rule fails).
