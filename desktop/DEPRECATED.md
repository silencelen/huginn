# This Electron client is deprecated

**Decided 2026-07-31. No new features. Security and data-loss fixes only.**

The desktop client is now the Compose Multiplatform one at
[`../mobile/app-desktop/`](../mobile/app-desktop/), which shares one Kotlin
codebase with the Android app. See [`../docs/DESKTOP-MIGRATION.md`](../docs/DESKTOP-MIGRATION.md).

## Why

This app worked, and it was audited hard (four passes, 77 findings). The
problem was never quality — it was that **every feature had to be built twice
and every bug fixed twice.** Measured before the decision, about two-thirds of
this codebase was either a line-for-line port of Kotlin that already existed or
a hand-rebuilt version of a screen Compose already drew:

| | lines |
|---|---|
| ports of logic that existed in Kotlin | ~2,545 |
| re-written versions of the same test suites | ~2,100 |
| a UI Compose already drew | ~5,931 |
| genuinely Electron (window, IPC, packaging) | ~2,521 |

The Compose client renders the phone's own composables. A transcript row is not
a lookalike; it is the same code. That is the property this app could never have.

## What is still true here

- **It still works and is still installed.** The owner runs 0.4.0 and it
  self-updates from `/v1/desktop`, which continues to serve it.
- **The two channels never mix.** `/v1/desktop` is this client;
  `/v1/desktop-kt` is the Compose one. Publishing across that line would hand a
  running program an "update" that replaces it with a different application.
- Fixes that reach this app must be **security or data loss**. Anything else
  goes to the Compose client, or it will be built twice again.

## What this app is good for now

Reference. It was audited more thoroughly than the Compose client has been, and
several behaviours here were bought with real bugs — they are recorded in
`../docs/DESKTOP-MIGRATION.md` under "carry-over", and every one of them is
implemented in the Compose client. Before changing a behaviour there, it is
worth reading how this one does it and why.

## Retirement

Cutover is written down in `../docs/DESKTOP-MIGRATION.md`. It is deliberately
not a directory rename: it is a final release here carrying a notice, then one
Compose release that both starts publishing to `/v1/desktop` and takes over this
app's install path and uninstall key, so Windows sees an upgrade rather than a
second program. Until that day this directory stays exactly where it is.
