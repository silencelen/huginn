# Kotlin clients

The Android app and the desktop app are one Gradle project in `mobile/`. Most of a feature is
written once and shows up in both. This page covers where code goes, how each app reaches the
daemon, and the rules that keep the two apps consistent. Toolchains and build commands are in
[building.md](building.md); test gates are in [testing.md](testing.md).

## Modules

```text
:core        pure Kotlin + the HTTP layer      ->  both apps, no UI
:ui          Compose composables               ->  both apps, the same pixels
:app         Android shell                     ->  phone only
:app-desktop Compose Desktop shell             ->  desktop only
```

| Module | Plugins and targets | Holds |
|---|---|---|
| `:core` | Kotlin Multiplatform, `androidTarget()` + `jvm()` | Wire models (`data/Models.kt`), `HuginnClient`, the route book and the TLS proof, the ANSI/terminal-grid, markdown and syntax renderers, the pure UI rules under `ui/` (transcript grouping and merge, local echo, live-input diffing, prompt gating, the pane lease), `notify/WatchCycle.kt`, the generated device-policy table, update models. |
| `:ui` | Compose Multiplatform, `androidTarget()` + `jvm()` | The theme (`theme/Theme.kt`, `theme/StateColors.kt`), the markdown and code renderer, the transcript rows, the terminal painter, and the shared screens (rounds, projects, devices, apps, headroom, settings scaffolds). Bundles the IBM Plex fonts under `src/commonMain/composeResources/font/`. |
| `:app` | Android application, package `com.silencelen.huginn` | Everything that needs a `Context`: push, alarms, workers, the widget, biometric lock, share target, camera, notification channels, DataStore settings. |
| `:app-desktop` | Plain Kotlin JVM + Compose Desktop (one target, not multiplatform) | The window, panes, key handling, command palette, a JSON settings file, and the link to huginn core. |

Source paths follow the usual layout. Kotlin packages are rooted at `com/silencelen/huginn/`:

| Path | What compiles there |
|---|---|
| `mobile/core/src/commonMain/kotlin/` | Shared logic, both targets |
| `mobile/core/src/androidMain/kotlin/`, `mobile/core/src/jvmMain/kotlin/` | The `actual` side of `data/Platform.kt` |
| `mobile/core/src/jvmShared/kotlin/` | One extra source root compiled into both targets: the daemon TLS transport (`data/OkHttpDaemonTransport.kt`) |
| `mobile/core/src/commonTest/kotlin/` | `kotlin.test` suites that run on both targets |
| `mobile/core/src/jvmTest/kotlin/` | JVM-only tests (the TLS transport against real sockets) |
| `mobile/ui/src/commonMain/kotlin/` | Shared composables |
| `mobile/ui/src/androidMain/kotlin/`, `mobile/ui/src/jvmMain/kotlin/` | Per-platform files such as `CellPainter.android.kt` / `CellPainter.jvm.kt` |
| `mobile/ui/src/jvmTest/kotlin/` | `:ui` tests (JVM only) |
| `mobile/app/src/main/kotlin/`, `mobile/app/src/test/kotlin/` | The Android app and its JVM unit tests |
| `mobile/app-desktop/src/main/kotlin/`, `mobile/app-desktop/src/test/kotlin/` | The desktop app and its tests |

Library versions are pinned in `mobile/gradle/libs.versions.toml`. Compose Multiplatform is pinned
there (1.7.3) separately from Kotlin. On the Android target its artifacts resolve to the AndroidX
ones, so no skiko renderer reaches the APK.

## The rule: write it in the lowest module that can hold it

A parser belongs in `:core`. A card belongs in `:ui`. Only the frame around them belongs in a
shell: navigation, window management, notifications, the file picker.

The module boundaries are enforced by the compiler:

- `:core` has no Android dependency and no Compose UI toolkit. It sees `compose.runtime` and
  `compose.ui` only so shared code can name `Color` and `AnnotatedString`. You cannot write a
  widget there.
- `:ui`'s `commonMain` has no Android dependency.
- Nothing in `:core` or `:ui` can see a shell.

So drift between the two apps fails the build instead of shipping.

### Worked example: a new kind of transcript row

Say the daemon starts sending a `checkpoint` event and both apps should show it.

1. `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/Models.kt`: add the field. Every
   field the daemon may omit is nullable or has a default, so an older app keeps parsing a newer
   daemon. `HuginnClient` decodes with `ignoreUnknownKeys = true`.
2. `.../ui/TranscriptGroups.kt` in `:core`: if the event changes how rows group, change it here,
   once, with a test in `:core`'s `commonTest`.
3. `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptView.kt`: draw it. Both
   apps now render it the same way, because it is one composable.
4. Nothing in either shell. You are done.

Three files, one test suite, both platforms. Aim for that shape.

If the daemon's response shape changed, also update the captured fixture under
`mobile/app/src/test/resources/` and assert the new field name in `ApiContractTest`. The fixture
README in that directory gives the scrub rule and how to capture a response.

## When the platforms really differ

Use these tools in this order.

1. **A parameter.** Touch targets and density differ between a thumb and a mouse. Each shell sets
   them and shared composables read them:
   - `HuginnTheme(monoStyle = ..., rowMetrics = ..., rootSurface = ...)`: `MonoStylePhone` /
     `MonoStyleDesktop`, `PhoneRowMetrics` / `DesktopRowMetrics`.
   - `LocalTranscriptMetrics` (bubble width) and `LocalMonoStyle`.
   - The `CellPainter` interface (`ui/TerminalCanvas.kt`), handed to the shared terminal painter.

   A desktop window narrowed to a phone's width then gets the phone's answer. `expect`/`actual`
   cannot express that, because it is a runtime question, not a platform one.
2. **A source-set split** when the platforms need different code, not a different value. The glyph
   blit is drawn through `android.graphics` on the phone and skia on the desktop:
   `CellPainter.android.kt` and `CellPainter.jvm.kt` (`SkiaCellPainter`). Each side gets a whole
   file instead of one signature forced to describe both.
3. **`expect`/`actual`** only when the platform API itself differs. There are four, all in
   `:core`'s `data/Platform.kt`, and the number should stay small:

   | Declaration | Why it is platform-specific |
   |---|---|
   | `huginnHttpEngine()` | Ktor engines are published per platform. The ordinary engine (system CAs), for GitHub only. |
   | `huginnTransport()` | The pinned channel to the daemon (see [protocol-tls.md](protocol-tls.md)). |
   | `huginnIoDispatcher` | Where the SSE readers parse (`Dispatchers.IO` on both targets), kept off the UI thread. |
   | `localTimeFormat(atMs)` | `commonMain` has no zone database or locale. Take the offset at the instant being rendered, not "now". |

Anything that talks to the daemon goes through `HuginnClient` and `huginnTransport()`, never
`huginnHttpEngine()`. The plain engine trusts system CAs and would carry the token past the pin
check.

The TLS transport is one file in `src/jvmShared/kotlin`, compiled into both targets. Keep it that
way. The security-critical half of the scheme must not exist as two copies that can drift.

## What each shell owns

| Phone (`:app`) | Desktop (`:app-desktop`) |
|---|---|
| FCM push, Doze alarms, WorkManager workers | The watch SSE stream (a desktop does not sleep) |
| Biometric lock, `FLAG_SECURE` | Close to tray, the `huginn://` scheme handler |
| Share target, camera, MediaStore | Command palette, keyboard model, splitter |
| The home-screen widget (Glance) | Diagnostics, the setup controller and its huginn core step |
| DataStore settings | A JSON settings file (`DesktopSettings`) |

If a feature seems to belong in both columns, look again. Usually the decision is shared and only
the mechanism differs, and the decision belongs in `:core`. `WatchCycle.finishedSince` is the
example: both apps decide "which chats finished" with the same code, then notify in their own way.

## How each app reaches the daemon

### Android

The Android app talks to the daemon directly over HTTPS. `HuginnClient` runs on the transport from
`huginnTransport()`, which pins the daemon's certificate by proof against the token. The app holds
the token. The contract is in [protocol-tls.md](protocol-tls.md).

### Desktop

The desktop app goes through huginn core on the same machine and holds no daemon token. Every call
`HuginnClient` makes is carried to the core's `/appd/...` proxy over the core's local socket. The
core adds the token, enforces its own TLS pin, picks an address from its route book, and returns
the daemon's status, headers and body unchanged. See [architecture.md](architecture.md) and
[go-core.md](go-core.md) for the core's side.

The pieces, all under `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/core/`:

| File | Role |
|---|---|
| `CorePaths.kt` | Where the core's socket is: `$XDG_RUNTIME_DIR/huginn/core.sock` (fallback `/run/user/<uid>/huginn/core.sock`) on Linux, `%LOCALAPPDATA%\huginn\run\core.sock` on Windows. AF_UNIX on both platforms. Must match `core/internal/store/paths.go`. |
| `CoreSocket.kt` | A `java.net.Socket` over a non-blocking `SocketChannel` on the AF_UNIX path, plus `CoreSocketFactory` for OkHttp. |
| `CoreDaemonTransport.kt` | The `DaemonTransport` used while the core carries the app. OkHttp with `CoreSocketFactory`, a fixed loopback DNS answer and no proxy. Base URL `CoreDaemonTransport.APPD_BASE` = `http://127.0.0.1/appd`. That host is never dialled: every socket goes to the core. Because the base is plain `http`, `HuginnClient`'s pinning step never engages. No `Authorization` header is sent. |
| `CoreClient.kt` | The core's own `/core/*` verbs (status, pair, routes, device, presence, events), base `http://huginn-core`. |
| `CoreBoot.kt` | Decides once per run, before `AppStore` builds its one `HuginnClient`, whether the run goes through the core. |
| `CoreDevice.kt` | The `CoreGate` state and the core's event stream (tray attached, GUI commands from the tray). |

Every request to the core carries `X-Huginn-Core-Proto` (`CoreClient.PROTO`). The core answers a
mismatch with `426` and the fix (`huginn update`). When the core cannot reach the daemon it answers
`502` with a JSON `error` and a `kind` (`unreachable`, `tls` or `auth`); when it is not paired it
answers `503` with `kind` `unpaired`. The app shows that state instead of a parse failure.

While huginn core does not answer, the app shows only its core screen (`CoreGateView`: set up or
start huginn core). `CoreBoot` still has an own-link outcome (`CoreBoot.Outcome.Direct`) for an
older desktop install that holds a token of its own: it hands that token to the core
(`POST /core/pair`), proves one request through the core, and only then deletes the token from the
app's settings. `HUGINN_DESKTOP_DIRECT=1` keeps a run on its own link for debugging. New code must
not depend on the direct path.

Three rules shape `CoreSocket`. Each violation hangs or kills the app instead of failing one call:

1. Do not use `Channels.newInputStream` / `newOutputStream`. On JDK 17 they share the channel's
   blocking lock, so one thread parked in `read()` stops every write. Read and write the channel
   directly.
2. Keep the channel non-blocking and wait on a `Selector` for at most `soTimeout`. A blocking
   channel ignores `SO_TIMEOUT`, and OkHttp's pool health check reads with a 1 ms timeout.
3. Translate `ClosedSelectorException` (unchecked) to `SocketException`. A close from another
   thread (Okio's timeout watchdog, `Call.cancel()`) otherwise escapes every `catch (IOException)`
   in OkHttp.

When huginn core's tray is attached, the desktop app shows no tray icon of its own and posts no
notifications: the tray's menu carries the app's items and its clicks reach the app as GUI
commands. The app's own tray and notifier are used only where the core has no tray.

## Rules every client follows

These are wire behaviours the daemon relies on. A new client, or a new screen in an existing one,
must keep them.

- **Send a stable `X-Huginn-Client` id.** The daemon keys the pane lease, the notification claim
  and reported app routes to it.
- **Pane size is a lease, not a setting.** A screen poll with `?cols=&rows=&live=1` claims the tmux
  window at that size for 90 seconds, renewed by polling. Reporting a size without `live=1` claims
  nothing. One client holds a session's lease at a time; a second live client is told who holds it
  instead of moving the window. Release it with `DELETE /v1/sessions/:name/size` when the view
  closes, the window hides, and the app quits. Only the holder's release counts.
  - Pause polling while the window is hidden. The poll is what renews the lease.
  - Run the release inside `withContext(NonCancellable)`. Every release path is reached from inside
    a collector that is being cancelled at that moment.
  - Run the release on a scope that outlives the view. A per-view controller's loops belong to a
    `SupervisorJob` child of the app scope (`SessionController`), so cancelling the view stops its
    polls; the release itself goes through the app-level `PaneLeaseHolder`.
  - `PaneLease` in `:core` is the pure rule: what may be reported, what may be claimed, what must
    be handed back. Put new exit paths through it.
- **A dropped chat stream reattaches.** Use `GET /v1/chats/:id/stream?since=N`. Seed the view from
  the chat's `partialText` and ask for `since=seq`, or replay from zero. Never both: that renders
  the answer twice. When `seq` is absent, skip the seed.
- **A `409` from an answer route is ordinary.** `gone`, `changed` and `undetected` mean the prompt
  moved on after the button was offered. Report it; never retry.
- **Claim notifications only when someone is there.** `X-Huginn-Notify: 1` tells the daemon this
  client will show the alert, which suppresses the fallback relay. Send it only while a person is
  present and the client can actually deliver. A parked SSE carries the header it was opened with,
  so reconnect it when presence changes.
- **A `huginn://` link acts only with this instance's nonce.** The fingerprint (`fp`) is the
  daemon's staleness check, an unkeyed hash of the question, options and dialog body that anyone
  can compute, so it proves nothing about who sent the link. Every toast that carries links gets
  a random nonce (`ActivationNonces`), held only in memory. `answer` and `undo` need it, single
  use, within five minutes. `ack` and `open` need it to take a notification down, for 24 hours; a
  notice-only nonce can never answer or undo. Any other link (no nonce, unknown, expired, after a
  restart, from a browser or another program) is navigation at most: `open` shows the session
  and takes nothing down, and `answer`, `undo` and `ack` do nothing but log. A fingerprint that no
  longer matches the pane still comes back `409` and nothing is typed.
- **The update source is fixed at build time,** never read from a setting
  (`GithubReleases.REPO`; for the Android app, the `huginn.updateRepo` Gradle property described in
  [building.md](building.md)).

## State words and colours

`mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/theme/StateColors.kt` is the one place a
state maps to a colour or a word. The words are exactly: working, needs you, waiting, shell, ok,
error, info. The daemon's wire words (`running`, `attention`, `idle`) are mapped there once
(`stateKindOf`). Needs you is the lit accent, never `colorScheme.error`: a question is not a
failure. Every colour comes from the brand tokens; read
[`assets/brand/BRAND.md`](../../assets/brand/BRAND.md) before changing any of them.

## Compose rules

Each of these fails silently: nothing warns, and the screen is wrong.

- **Give the desktop root a `Surface`.** `MaterialTheme` does not provide `LocalContentColor`;
  `Surface` does. Without one, every unstyled `Text` renders black on the near-black background.
  The desktop passes `HuginnTheme(rootSurface = true)`. The phone's root `Scaffold` is already a
  Surface.
- **Cap before you fill.** `Modifier.widthIn(max = ...)` must come before `fillMaxWidth()`.
  `fillMaxWidth` hands down fixed constraints, and a later `widthIn` can only coerce into them, so
  the cap is swallowed. `CapBeforeFillTest` in `:app-desktop` greps the source tree for the wrong
  order.
- **Put `Modifier.weight` on a plain `Box`, not on a `SelectionContainer`.** Handed straight to the
  container, the scroll area takes the remaining height and lays the composer out past the window
  edge.
- **Hoist to a local `val` instead of relying on a smart cast across modules.** Kotlin does not
  smart-cast a property declared in another module (or a `by`-delegated value), so
  `if (!x.isNullOrBlank()) use(x)` stops compiling when `x` moves. Do not reach for `!!`.
- **Moving composables between modules is cheap.** Extraction into `:ui` costs a little dex and no
  new library. Do not keep a copy in a shell to avoid it.
- **A new shared module starts outside the test gate.** Add its test task to the gate and raise the
  floor in `scripts/test-floors.env` in the same change (see [testing.md](testing.md)).

## Compose Desktop and packaging rules

- **Native libraries are per target.** `compose.desktop.currentOs` (the skiko native for the build
  host) is declared only in `:app-desktop`, and in `:ui`'s `jvmTest`. Never declare
  `compose.desktop.windows_x64` globally: a Linux package then ships the Windows natives, builds
  cleanly and fails at runtime. The Windows classpath is its own configuration,
  `windowsRuntimeClasspath`, staged by `./gradlew :app-desktop:windowsAppLibs`, which asserts a
  Windows skiko jar is present and no Linux one is.
- **`packageMsi` on Linux exits 0 and produces nothing.** `targetFormats` lists only `Deb` for that
  reason. Assert that artifacts exist; never trust a packaging task's exit code.
- **jpackage cannot cross-compile.** On Linux its only types are app-image, rpm and deb. A Windows
  image comes from the Windows `jpackage.exe` run under wine (see [building.md](building.md)).
- **A packaged app loads skiko from `skiko.library.path`, not from the jar.** `createDistributable`
  extracts the native for the host. A Windows image built on Linux must carry
  `skiko-windows-x64.dll` and `icudtl.dat` beside the jars itself, or it launches into a
  `RenderException`.
- **jpackage puts every jar in `--input` on the classpath.** It needs `--main-jar` and nothing
  else. What Compose adds are three `--java-options`, the same ones its generated Linux launcher
  `.cfg` lists.
- **Test for `wine`, not `wine64`.** Debian's wine packages run 64-bit binaries through
  `/usr/bin/wine`; `wine64` is not on `PATH`.
- **The desktop version lives in `mobile/app-desktop/version.txt` and nowhere else.** Gradle
  generates `BuildInfo.VERSION` from it, so `:app-desktop:run` (which has no jar manifest) knows
  its version too.
- **The jlink runtime is minimal.** `java.instrument`, `java.management` and `jdk.unsupported` are
  added explicitly (OkHttp's engine, the coroutines debug agent, Okio). Check with
  `./gradlew :app-desktop:suggestRuntimeModules` after adding a dependency.
- **`kotlin.serialization` must be applied wherever `@Serializable` is used.** Without the plugin,
  `@Serializable` compiles but `.serializer()` does not exist, and the error says only "unresolved
  reference: serializer".

## Running the desktop app from the tree

```bash
cd mobile
./gradlew :app-desktop:run
```

The app needs huginn core running and paired on the same machine; without it you see only the core
screen. Build and run a core from the tree as described in [go-core.md](go-core.md).

To run it headless (for screenshots or checks):

```bash
xvfb-run -a ./gradlew :app-desktop:run
```

- Use a full JDK 17, not a headless JRE. `openjdk-17-jre-headless` has no `libawt_xawt.so`, and the
  app dies in skiko's setup with an `UnsatisfiedLinkError`.
- The Gradle daemon runs with `java.awt.headless=true`, which `JavaExec` inherits. The app would
  then fail claiming no X11 `DISPLAY` is set. `:app-desktop` sets `java.awt.headless=false` and
  forwards `DISPLAY` and `XAUTHORITY` on every `JavaExec` task; keep that block.
- Skiko cannot create a GL context under Xvfb and falls back to software rendering. That is fine
  for checks, but the GPU path is never exercised headless.

## Testing

There is no emulator or instrumentation path. The JVM test suites are the only automated check,
and they are fed bytes captured from the real system: verbatim `tmux capture-pane -e` output, the
SSE frames the daemon emits, and scrubbed daemon responses.

```bash
cd mobile
./gradlew :core:jvmTest :core:testDebugUnitTest :ui:jvmTest \
          :app:testDebugUnitTest :app-desktop:test
./gradlew :app:assembleDebug           # the phone must not regress
./gradlew :app-desktop:compileKotlin
bash scripts/build.sh debug            # the gate: tests, count floors, debug APK
```

`scripts/build.sh` runs `:core`, `:ui` and `:app` plus the daemon suite and asserts test-count
floors; it does not run `:app-desktop:test`, so run that yourself. Floors and why counts are
asserted: [testing.md](testing.md).

| Suite | Where | Runs on |
|---|---|---|
| `:core` `commonTest` | `TerminalGridTest`, `SseTest`, `MarkdownTest`, `HuginnClientTest`, `PaneLeaseTest`, `DaemonTlsTest`, and most others | Both targets (`:core:jvmTest` and `:core:testDebugUnitTest`), so each test runs twice |
| `:core` `jvmTest` | `TlsTransportIntegrationTest`: the real transport against a JDK `HttpsServer` with a self-signed certificate | JVM |
| `:ui` `jvmTest` | `TerminalCanvasTest` (the grid walk against a recording `CellPainter`, plus one real skia render) and the shared screens | JVM only: a real `DrawScope` needs a real `ImageBitmap`, which is a stub on the Android unit-test target |
| `:app` test | What needs Android or the app's own classes: `ApiContractTest`, `ReattachPlanTest`, notification timing | JVM unit tests |
| `:app-desktop` test | Desktop controllers, setup, notifier rules, `compose.uiTest` checks of what a reader sees, and `CoreClientTest` | JVM |

- **Assertion argument order differs by module.** `:core`, `:ui` and `:app-desktop` use
  `kotlin.test`, whose `assertEquals` is `(expected, actual, message)`. `:app` uses JUnit 4
  (`org.junit.Assert`), which is `(message, expected, actual)`. With three `String` arguments a
  wrong order compiles and asserts something else. Convert assertions by hand when you move a test
  between modules, never with `sed`.
- **`:app-desktop` stays on Gradle's default JUnit 4 runner.** Do not add `useJUnitPlatform()`:
  `kotlin("test")` then resolves without the Jupiter engine and the suite is discovered as zero
  tests, exiting 0.
- **Tests never reach the network or a real daemon.** HTTP goes through Ktor's `MockEngine`
  wrapped in `MockTransport`, which simulates the TLS pin and proof rules instead of skipping
  them. `MockTransport` is kept identical in `:core` `commonTest`, `:app` test and `:app-desktop`
  test (a test source set cannot see another module's); edit all three or none.
- **`CoreClientTest` drives a real huginn core.** It builds `core/cmd/huginn` with Go, runs
  `huginn core run` with `HOME`, `XDG_*` pointed at a temporary directory and no token, and talks to
  it over the socket. Without Go, or off Linux, it passes without proving anything.
- **A new test must fail on the old code.** Break the code it covers once and watch it go red
  before you trust it green.
