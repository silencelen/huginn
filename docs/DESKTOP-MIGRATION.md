# Desktop: Electron → Compose Multiplatform

Decided 2026-07-30, after two spikes that both passed. The desktop client is
moving from Electron/TypeScript to Compose Multiplatform, sharing one Kotlin
codebase with the Android app. Electron is maintenance-only until parity, then
retired.

## Why

The goal is one behaviour and one look on both, differing only by aspect
ratio. Electron can only ever *imitate* the Android app, by hand, forever.
Measured before the decision: of the Electron app's code, ~2,545 lines were
line-for-line ports of 2,225 lines of Kotlin, ~2,100 lines were re-written
versions of the same test suites, and ~5,900 lines re-drew a UI that already
existed in Compose. Only ~2,500 lines were genuinely Electron.

## What the spikes proved

**KMP restructure works.** 14 files / 1,557 lines moved to `commonMain` with
zero `expect`/`actual`; the Android app builds byte-identically and all 179
tests pass. Compose Multiplatform publishes the same `androidx.compose.*`
package names, so files using `Color` and `AnnotatedString` compile in common
code unchanged, and no skiko lands in the APK.

**Windows installers still build on huginn.** PRESTIGE is not required.
Proven chain, with a real Compose app producing a working 63 MB installer:

```
Gradle (Linux) → jpackage.exe UNDER WINE (app-image, native .exe launcher)
               → Linux makensis (installer)
               → existing /v1/desktop channel
```

`jpackage` cannot cross-compile (its valid types on Linux are app-image, rpm,
deb). Hydraulic Conveyor was rejected: it requires *public, unauthenticated*
access to the update site, because Windows drives its updates through the OS
MSIX engine where no Bearer token can be injected — and ours is Bearer-authed
on the tailnet. update4j is archived. The updater is hand-rolled (~1-2 days)
against the `manifest.json` the release script already produces.

## Traps (each one cost real time to find)

- **JUnit → kotlin.test argument order is silent.** JUnit is
  `assertEquals(message, expected, actual)`; kotlin.test is
  `assertEquals(expected, actual, message)`. With three `String` arguments it
  compiles clean and asserts something different. Never convert with `sed`.
- **`gradlew packageMsi` on Linux exits 0 and produces nothing** (`onlyIf`
  false). Assert that artifacts exist; never trust the exit code.
- **A global desktop target ships the wrong natives** — declaring
  `compose.desktop.windows_x64` globally put `skiko-windows-x64` inside the
  Linux `.deb`. Use per-target configurations.
- **Smart casts stop working across the module boundary** for the
  `if (!x.isNullOrBlank()) { use x }` idiom. Hoist to a local val; do not
  reach for `!!` in code that runs on the owner's daily driver.
- **A test gate that only covers one module is worse than none.** After the
  `:core` split, `scripts/build.sh` ran 58 of 179 tests and reported green.
  It now runs both modules and asserts a test-count floor.
- **`MaterialTheme` does not provide `LocalContentColor`; `Surface` does.**
  Without a `Surface` at the root, every unstyled `Text` renders BLACK — black
  ink on the app's near-black background, on every screen at once. Nothing
  warns. The phone never hit it because its root has always been a Surface.
- **`Modifier.weight` handed straight to `SelectionContainer`** let the scroll
  area take the remaining height and the composer was then laid out past the
  window's bottom edge and clipped away — a chat window with no way to type in
  it, and nothing in the logs. Put the weight on a plain `Box` and wrap the
  container inside it.
- **Headless verification needs a headful JDK and `java.awt.headless=false`.**
  `openjdk-17-jre-headless` has no `libawt_xawt.so`, so the app dies in skiko's
  `Setup.init` with `UnsatisfiedLinkError`; and the Gradle daemon runs with
  `java.awt.headless=true`, which `JavaExec` inherits, so the app dies in
  `getGlobalDensity` claiming *"No X11 DISPLAY variable was set"* against a
  perfectly good Xvfb. Both diagnoses point away from the real cause.
  `:app-desktop` sets the property and re-exports `DISPLAY` on every JavaExec.
- **Skiko cannot make a GL context under Xvfb** and falls back to software.
  Fine for verification; it does mean the GPU path is never exercised headless.
- **`fillMaxWidth` before `widthIn` silently swallows the cap.** `fillMaxWidth`
  hands DOWN fixed constraints, and a `widthIn` inside fixed constraints can only
  coerce into them — so a bubble meant to stop at a reading measure spanned the
  whole 1280pt window instead, and nothing warned. Cap first, fill second. Found
  by looking at a screenshot, not by a compiler.
- **Moving composables across a module boundary is not free, but it is cheap.**
  The extraction cost the debug APK 15 KB of dex (synthetic accessors, per-module
  `ComposableSingletons`) and no new library: `classes.dex` and `classes2.dex`
  came out byte-identical, which is also the proof that CMP's
  `materialIconsExtended` resolved to the AndroidX artifact `:app` already had
  rather than a second copy.
- **A new shared module starts outside the test gate.** `:ui` now owns the
  terminal grid walk for BOTH clients, so `scripts/build.sh` gained `:ui:jvmTest`
  and its floor went 375 → 382 the same day the module landed. The same lesson as
  the `:core` split, one module later.
- **A per-view controller launched onto the app scope leaks a poll loop per
  view.** Cancelling `SessionController` cancelled nothing, because its three
  forever-loops belonged to a scope that outlives every view — and the leaked one
  that matters is the screen supervisor, which goes on RE-ACQUIRING the tmux size
  lease for a session nobody is looking at. It owns a `SupervisorJob` child of the
  app's now. The RELEASE still has to run on the parent: the child is cancelled at
  the exact moment the release is needed.
- **The lease release must be `NonCancellable`.** Every release path is reached
  from inside the `collectLatest` whose job is to be cancelled — hiding the window
  cancels the collector that is releasing *because* the window was hidden. Without
  it the coroutine dies at the suspension point mid-DELETE, having already cleared
  its own record, and nothing left knows the window is still `manual`.
- **A parked `/screen?wait=` re-captures with its OWN stale geometry.** When the
  pane changes, the parked request calls `captureScreen` again with the `cols`/
  `rows` it was started with — so a resize bounces the window through the old size
  once before settling (verified: 86x30, 107x37, 86x30 inside 250 ms). Cancelling
  the client request does not prevent it; the daemon has already applied the size.
  Self-correcting, and cosmetic on a pane nobody is attached to.
- **The daemon declines to lease a size that already matches**, deliberately —
  "if we hold none, the size is somebody else's doing". So after a release, a
  re-show at the same geometry leaves the client believing it holds a lease the
  daemon does not have. Harmless in that direction (one spare `DELETE`); the
  client must never make the opposite assumption.
- **`compose.desktop.currentOs` is not the whole Windows classpath.** Swapping
  skiko for the windows-x64 variant is necessary but not sufficient: in a
  PACKAGED app skiko does not load its native out of the jar at all — Compose
  extracts `libskiko-*.so` beside the jars and passes
  `-Dskiko.library.path=$APPDIR`. `createDistributable` does that for the host
  platform for free; the wine path has to unzip `skiko-windows-x64.dll` (and
  `icudtl.dat`) into the jpackage `--input` directory itself. Miss it and the
  installer builds, installs and launches into a `RenderException` with no
  renderer left to fall back to.
- **jpackage puts every jar in `--input` on the classpath itself.** The Linux
  `.cfg` Compose generates lists them explicitly, which reads as though the
  plugin had to; it does not. The wine invocation needs `--main-jar` and nothing
  else. (What Compose really adds are the three `java-options`, which the release
  script copies verbatim out of the generated Linux `.cfg`.)
- **`continue` inside an inline lambda needs language version 2.2.** This module
  is on 2.1, so `runCatching { … }.getOrElse { …; continue }` — the obvious way
  to write "try the next pinned route" — does not compile, and the error names a
  language feature rather than the line's intent.
- **`wine64` is not a command on this box.** wine 9.0 ships a single `/usr/bin/wine`
  that runs 64-bit PE binaries; `wine64` exists only as `/usr/lib/wine/wine64`, not
  on `PATH`. A script testing for `wine64` concludes wine is missing on a machine
  that has it.

## Carry-over: behaviour the Compose desktop must not lose

The Electron app was audited (four passes, 77 findings) and several of these
were bought with real bugs. They are requirements, not preferences.

### Security

- **`huginn://answer` must require a fingerprint.** Without it, any local
  process or clicked web link can approve whatever prompt is on the pane —
  on a root-equivalent agent host.
- **The server address must be allowlisted.** The Bearer token follows it on
  every request; an arbitrary address hands the daemon token to a stranger.
- **The update feed must be pinned**, never derived from a user setting.
  Builds are unsigned, so whoever controls the feed controls what runs.
- **Deny permissions, but allow clipboard writes.** Denying the lot broke
  every copy in the app, silently, for a whole release.

### Lifecycle

- **Pause polling when the window is hidden.** The pane poll is what renews
  the tmux size lease, so a hidden window can pin someone else's session to
  desktop geometry indefinitely.
- **A dropped stream must reattach**, not freeze a half-written answer. Use
  the daemon's `?since=` contract; seed from `partialText` xor replay from
  zero, never both (that renders the answer twice).
- **Claim the notification route only when someone is actually there.**
  Claiming while idle suppresses the household Telegram fallback. The claim
  rides on request headers, so a parked SSE must be reconnected when the idle
  state changes or the claim goes stale for up to 30 minutes.
- **Release the pane-size lease** on view close, window close and quit.
- **Treat a 409 answer as ordinary** (`gone` / `changed`): the click was
  correct when it was offered. Report it; never retry.

### Interface

Desktop earns its own frame: a resizable three-pane layout, a command palette
over everything, keyboard navigation that works from the composer, right-click
menus, tooltips on the marks that carry state, word and line selection in the
terminal, and local echo so typing does not wait for a round trip.

House rules that outrank taste: no left accent bars on rows or cards; subtle
in-vernacular state marks rather than loud badges; controls that do the same
verb unify into one control.

## Status: the Electron client is DEPRECATED (2026-07-31)

All five build phases are done and the Compose client is in real use on the
owner's machine. **New feature work goes to `mobile/app-desktop`**; the Electron
client at `desktop/` takes security and data-loss fixes only — see
[`../desktop/DEPRECATED.md`](../desktop/DEPRECATED.md). For how to add something
to both clients at once, see [`ADDING-A-FEATURE.md`](ADDING-A-FEATURE.md).

**Not yet done, deliberately:** the owner's installed Electron client is still
running and `/v1/desktop` still serves it. Retiring the *installed* app is the
cutover described below, and it waits until the Compose client has more than a
day of use behind it. A replacement trusted for an hour is not yet a
replacement — and the cutover takes over the install path, so it is not a step
that undoes itself.

## Phases

1. **`:core` extracted** — done (commit `c0c3b18`). Shared logic has one home.
2. **`HuginnClient` → Ktor**, settings multiplatform — done (commit `c506a60`).
   Unlocks sharing the whole data layer, and moved `SseTest` into shared code.
3. **Compose Desktop app** against `:core`, promoting shared composables into
   a `:ui` module used by both apps.
   - **3a — the app** — done. `:app-desktop` (JVM, Compose 1.7.3, JDK 17):
     three-pane shell, chats + sessions lists on a visibility-gated 5s poll,
     the watch SSE stream, a chat that streams a live run, a status view, and
     a JSON settings store of its own (NOT the Electron client's file).
     Verified headless against the live daemon.
   - **3b — `:ui`** — done. A Compose Multiplatform module both clients render
     from: the theme (one palette, one syntax set, `LocalMonoStyle` for the one
     real difference), the markdown/code renderer, the transcript rows, and the
     terminal grid painter. `:app` and `:app-desktop` each lost their copy. Where
     the clients genuinely differ it is a parameter — mono size and a root-Surface
     flag on the theme, `TranscriptMetrics` for bubble width, a `CellPainter`
     interface for the glyph blit — so a narrowed desktop window can be handed the
     phone's answer, which `expect`/`actual` could not express.
   - **3c — the desktop session view** — done. Conversation and Screen over one
     controller, with the pane-size lease built and PROVEN first. Geometry is
     reported only while the window is visible AND the Screen tab is selected, so
     the conversation view never leases at all; `PaneLease` (pure, in `:core`) says
     what may be held and `PaneLeaseHolder` (app-level, because the release paths
     do not share a lifetime) hands it back release-first. All four exits verified
     against the daemon by watching tmux go `manual` → `smallest`: leaving the
     view, minimizing, closing the window, and SIGTERM through a shutdown hook.
     The transcript merge left the Android view model for `:core` so both clients
     key rows on one identity rule; the two poll backoff ladders joined it, since a
     session that never prompted Claude 409s forever.
4. **Packaging + updater** — done. `mobile/scripts/release-desktop.sh` builds
   both installers on this box and publishes them to a channel of the Compose
   client's own.
   - **Windows**: `:app-desktop:windowsAppLibs` stages a Windows-x64 runtime
     classpath — its OWN resolvable configuration, with the attributes copied
     from `runtimeClasspath` rather than written out again, so it differs from
     the Linux one in skiko and nothing else, and the task asserts that. Then
     Windows `jlink.exe` under wine builds the runtime image, Windows
     `jpackage.exe` under wine builds the app-image, and Linux `makensis` wraps
     it. The jlink module list is READ OUT of the Linux runtime image's
     `release` file rather than maintained a second time.
   - **Linux**: Compose's own `packageDeb`.
   - **Channel**: `/v1/desktop-kt`, served from `DATA_DIR/desktop-kt`, which the
     Electron channel's code cannot reach. `/v1/desktop` is untouched, and the
     release script asserts that on every run.
   - **Updater**: `app-desktop/.../update/`, hand-rolled. Pinned feed, semver
     compare, sha256 verified against what the manifest already carries, then
     `Ready` and stop. Proven end to end by `:app-desktop:updaterProbe`, which
     runs the real updater against the real channel as the release's last gate.
   - **Wired since 0.2.0**: `AppStore` starts the updater (`updater.start(scope)`)
     and the Settings screen renders its state. The note that used to sit here
     said neither was done, which was true only while phase 3c still owned those
     files.
5. **Parity, then retire Electron.**

## Cutover: how the two channels stop being two

NOT a directory rename — a rename is the exact accident the separation exists to
prevent, and it would land on a client that is running. The order is:

1. A final Electron release whose only change is a notice.
2. One Compose release that BOTH starts publishing to `/v1/desktop` and has its
   installer take over the Electron install path and uninstall key, so Windows
   sees an upgrade rather than a second application. `UpdateFeed.PATH` changes in
   that same release.
3. `/v1/desktop-kt` retires once no installed client is still pinned to it.

Until step 2, nothing writes across the line. An Electron client that "updates"
into a Compose build has been replaced by a different application, silently,
from its own update prompt.
