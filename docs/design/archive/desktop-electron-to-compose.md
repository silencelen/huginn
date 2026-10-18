# Desktop: Electron to Compose Multiplatform

**Status:** Complete. Decided on 2026-07-30 after two spikes that both passed; the Compose client
replaced the Electron client for new work on 2026-07-31, and the Electron client was deleted from
the repository on 2026-08-27.

**Summary:** the desktop client moved from Electron and TypeScript to Compose Multiplatform,
sharing one Kotlin codebase with the Android app. The goal was one behaviour and one look on both,
differing only by aspect ratio; Electron could only ever imitate the Android app by hand. Two
spikes proved that the shared code could move into Kotlin Multiplatform without changing the
Android build, and that Windows installers could be built on Linux. The Electron app's hard-won
behaviours were carried over as requirements.

## Why

Electron can only ever *imitate* the Android app, by hand, forever. Measured before the decision:
of the Electron app's code, about 2,545 lines were line-for-line ports of 2,225 lines of Kotlin,
about 2,100 lines re-wrote the same test suites, and about 5,900 lines re-drew a UI that already
existed in Compose. Only about 2,500 lines were genuinely Electron.

## What the spikes proved

**The Kotlin Multiplatform restructure works.** 14 files (1,557 lines) moved to `commonMain` with
no `expect`/`actual`; the Android app built byte-identically and all 179 tests passed. Compose
Multiplatform publishes the same `androidx.compose.*` package names, so files using `Color` and
`AnnotatedString` compile in common code unchanged, and no skiko lands in the APK.

**Windows installers build on Linux.** No Windows build machine is required. The proven chain,
with a real Compose app producing a working 63 MB installer:

```text
Gradle (Linux) -> jpackage.exe under Wine (app image, native .exe launcher)
               -> Linux makensis (installer)
               -> the desktop update channel
```

`jpackage` cannot cross-compile: its valid types on Linux are app-image, rpm and deb. Hydraulic
Conveyor was rejected because it requires *public, unauthenticated* access to the update site:
Windows drives its updates through the OS MSIX engine, where no bearer token can be injected, and
the update channel is bearer-authenticated on a private network. update4j is archived. The updater
was hand-rolled (about one to two days of work) against the `manifest.json` the release script
already produced.

## Build traps

The spikes and the build phases found a series of Compose, Gradle, jpackage and Wine traps, each of
which cost real time. They are kept as build guidance in
[docs/dev/kotlin-clients.md](../../dev/kotlin-clients.md).

## Carry-over: behaviour the Compose desktop must not lose

The Electron app was audited four times (77 findings), and several of these behaviours were bought
with real bugs. They were treated as requirements, not preferences.

### Security

- **`huginn://answer` must require a fingerprint.** Without it, any local process or clicked web
  link can approve whatever prompt is on the pane, on a root-equivalent agent host.
- **The server address must be allowlisted.** The bearer token follows it on every request; an
  arbitrary address hands the daemon's token to a stranger.
- **The update feed must be pinned**, never derived from a user setting. Whoever controls the feed
  controls what runs.
- **Deny permissions, but allow clipboard writes.** Denying all of them broke every copy in the
  app, silently, for a whole release.

### Lifecycle

- **Pause polling when the window is hidden.** The pane poll is what renews the tmux size lease, so
  a hidden window can pin someone else's session to desktop geometry indefinitely.
- **A dropped stream must reattach**, not freeze a half-written answer. Use the daemon's `?since=`
  contract; seed from `partialText` or replay from zero, never both (that renders the answer twice).
- **Claim the notification route only when someone is actually there.** Claiming while idle
  suppresses the server's away-from-desk alert relay. The claim rides on request headers, so a
  parked SSE must be reconnected when the idle state changes, or the claim goes stale for up to 30
  minutes.
- **Release the pane-size lease** on view close, window close and quit.
- **Treat a 409 answer as ordinary** (`gone` / `changed`): the click was correct when it was
  offered. Report it; never retry.

### Interface

The desktop earns its own frame: a resizable three-pane layout, a command palette over everything,
keyboard navigation that works from the composer, right-click menus, tooltips on the marks that
carry state, word and line selection in the terminal, and local echo so typing does not wait for a
round trip.

House rules that outrank taste: no left accent bars on rows or cards; subtle state marks in the
product's own vernacular rather than loud badges; controls that do the same verb unify into one
control.

## Phases

1. **`:core` extracted.** Shared logic has one home.
2. **`HuginnClient` moved to Ktor, and settings became multiplatform.** This unlocked sharing the
   whole data layer and moved the SSE tests into shared code.
3. **A Compose Desktop app** against `:core`, promoting shared composables into a `:ui` module used
   by both apps.
   - **3a, the app.** `:app-desktop` (JVM, Compose 1.7.3, JDK 17): a three-pane shell, chat and
     session lists on a visibility-gated 5 s poll, the watch SSE stream, a chat that streams a live
     run, a status view, and a JSON settings store of its own, separate from the Electron client's
     file.
   - **3b, `:ui`.** A Compose Multiplatform module both clients render from: the theme (one palette,
     one syntax set, `LocalMonoStyle` for the one real difference), the markdown and code renderer,
     the transcript rows, and the terminal grid painter. Where the clients genuinely differ, the
     difference is a parameter (mono size and a root-Surface flag on the theme, `TranscriptMetrics`
     for bubble width, a `CellPainter` interface for the glyph blit), so a narrowed desktop window
     can be handed the phone's answer, which `expect`/`actual` could not express.
   - **3c, the desktop session view.** Conversation and Screen over one controller, with the
     pane-size lease built and proven first. Geometry is reported only while the window is visible
     and the Screen tab is selected, so the conversation view never leases at all. `PaneLease`
     (pure, in `:core`) says what may be held, and `PaneLeaseHolder` (app-level, because the release
     paths do not share a lifetime) hands it back release-first. All four exits were verified
     against the daemon by watching tmux go from `manual` to `smallest`: leaving the view,
     minimizing, closing the window, and SIGTERM through a shutdown hook. The transcript merge moved
     from the Android view model into `:core`, so both clients key rows on one identity rule.
4. **Packaging and updater.** The desktop release script builds both installers on Linux and
   publishes them to a channel of the Compose client's own.
   - **Windows:** `:app-desktop:windowsAppLibs` stages a Windows x64 runtime classpath, its own
     resolvable configuration with attributes copied from `runtimeClasspath`, so it differs from the
     Linux one in skiko and nothing else (the task asserts that). Windows `jlink.exe` under Wine
     builds the runtime image, Windows `jpackage.exe` under Wine builds the app image, and Linux
     `makensis` wraps it. The jlink module list is read out of the Linux runtime image's `release`
     file rather than maintained twice.
   - **Linux:** Compose's own `packageDeb`.
   - **Channel:** `/v1/desktop-kt`, served from a directory the Electron channel's code cannot
     reach.
   - **Updater:** hand-rolled, in `app-desktop/.../update/`. A pinned feed, a semver compare, sha256
     verified against what the manifest carries, then `Ready` and stop. Proven end to end by
     `:app-desktop:updaterProbe`, which runs the real updater against the real channel as the
     release's last gate.
5. **Parity, then retire Electron.**

## The planned cutover, and what happened instead

The plan for retiring the *installed* Electron app was not a directory rename (a rename is the exact
accident the channel separation exists to prevent, and it would land on a running client). The
order was:

1. A final Electron release whose only change is a notice.
2. One Compose release that both starts publishing to `/v1/desktop` and has its installer take over
   the Electron install path and Windows uninstall key, so Windows sees an upgrade rather than a
   second application.
3. `/v1/desktop-kt` retires once no installed client is still pinned to it.

Until step 2, nothing writes across the line: an Electron client that "updates" into a Compose
build has been replaced by a different application, silently, from its own update prompt.

The maintainer chose instead to delete the Electron client outright on 2026-08-27:

- **The tree is gone.** `desktop/` (source, tests, lock file, release script, changelog) was deleted
  from the repository.
- **The channel is gone.** appd 2.81.0 stopped routing `/v1/desktop`; a request there gets the
  ordinary 404. `/v1/desktop-kt` keeps its name and contents, because installed Compose clients
  poll that exact path, so step 3 does not happen either.
- **No handover release was cut.** Any installed Electron copy goes stale where it sits: it polls a
  channel that answers 404, never updates again, and has to be uninstalled by hand from Windows'
  list of installed apps.
