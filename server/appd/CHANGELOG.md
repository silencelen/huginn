# Changelog — huginn-appd

The daemon versions independently of the clients. Its releases are tagged
`appd-vX.Y.Z` and their notes are cut from **this** file.

Backfilled 2026-08-14 for 2.55.1 → 2.59.1: appd previously had no changelog of its own, so
`scripts/github-release.sh` pointed the `appd` component at `mobile/CHANGELOG.md` — where appd
appeared only as a side-note on the app releases it happened to ship with. Three versions shipped
undocumented, and the notes-cutting matcher could fuse two sections when an app and an appd
version number collided. Entries below are reconstructed from the shipping commits.

## 2.59.1 — 2026-08-11

### Fixed
- **Tall prompt dialogs went undetected at narrow pane widths.** `detectPrompt`'s run collection,
  header scan and question extraction were all bounded by a fixed `lastContent - 24` lookback. A
  3-4 option dialog with wrapping descriptions runs taller than 24 rows once the pane is narrow —
  fine to 72 columns, but at 64 columns option 1 sits 25 rows above the last line, one past the
  window. The run then started at option 2, the "must be 1..n contiguous" guard failed, and the
  whole prompt was discarded, so a single-question AskUserQuestion fell back to the degraded
  "use the Screen tab" card. Reproduced and bisected by width.

## 2.59.0 — 2026-08-11

### Added
- **`POST /v1/sessions/:name/compact`** types `/compact` into the pane, backing the clients'
  one-tap context manager. Same guards as `/soft-end`: refuses a plain shell with no recorded
  Claude state, never fires while a question is waiting, and reports the queued case when sent
  mid-turn. Live-verified: PreCompact fires and the composer clears.
- Context-used and compaction signals for the conversations surface.

### Fixed
- `isCompacting()` gained a 5-minute mtime TTL so a missed PostCompact cannot pin "Compacting…"
  on forever.

## 2.58.0 — 2026-08-11

### Fixed
- **A multi-question AskUserQuestion could not be answered from the desktop buttons.** A
  multi-part dialog is answered through the TUI's tab strip, so the single digit-then-Enter path
  over-answers: the digit selects and advances question 1 while the Enter confirms question 2's
  default, silently answering two and skidding the pane past the card, after which every tap
  409s. `promptFor` now detects `questionCount > 1` and serves a deliberately non-answerable
  degraded card that routes to the Screen tab rather than misfiring buttons.

### Added
- `parseStatusLine` splits the `·`-separated statusline to extract context and compaction state.

## 2.57.0 — 2026-08-11

### Fixed
- **Root cause of "buttons sometimes work, sometimes don't; text sometimes off".** New pure
  module `lib/ask.js` validates the hook's AskUserQuestion sidecar and fuses it with the pane run
  `detectPrompt` found: positional match on a whitespace-collapsed prefix (covering width
  truncation in either direction), TUI-added rows flagged `extra`, and `multiSelect` required to
  agree or it drops to pane-only. The fused prompt carries the hook's exact question, labels and
  descriptions alongside the pane's caret, which makes `promptFingerprint` **width-stable** — the
  same question at 80 and 46 columns now fingerprints identically. That is what had made a
  lock-screen answer 409 as "changed" after a re-wrap.

## 2.56.0 — 2026-08-11

### Added
- **Soft end / hard end.** `POST /v1/sessions/:name/soft-end` types a wrap-up phrase into the
  pane; with auto-end on (the default) the session is hard-ended once it settles. The timing is
  pure, in `lib/softend.js`: a 3s idle-stability gate closes the queued-phrase race, attention
  cancels, 60s arm-timeout, 6h TTL.
- Uploads are retrievable over GET.

### Fixed
- `hardEndSession()` now also removes the orphaned `/run` state file (Claude's SessionEnd hook
  never fires on a kill) and releases the pane lease; the DELETE route and auto-end share it.
- The state watch starts unconditionally at boot, so auto-end responds within a second whether or
  not alerts are enabled.
- Pane-scrape fixes.

## 2.55.1 — 2026-08-10

### Security
- **`authorized()` uses string operations instead of `/^Bearer\s+(.+)$/`.** The regex backtracked
  polynomially on a hostile header, pre-auth, on every request (js/polynomial-redos). Node's 431
  header cap bounded the damage; the parse is now linear regardless.
- **`MEMPALACE_MARKER` is validated against a plain path charset** before it can be interpolated
  into the ssh probe command. A value carrying shell syntax now disables the probe instead of
  reaching a shell (js/shell-command-injection-from-environment). The variable is root-set, but
  the flow should not exist.
- `accounts.js` insufficient-password-hash dismissed as a false positive: the SHA-256 derives an
  identifier from a rotating token and nothing verifies against it.
