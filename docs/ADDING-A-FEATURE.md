# Adding a feature to both the phone and the desktop

One codebase, four modules. Where a change goes decides how many places it
lands, so the only question worth asking first is **"how much of this is the
same on a phone and on a desktop?"**

```
:core        pure Kotlin + the HTTP layer      →  both apps, no UI
:ui          Compose composables               →  both apps, the same pixels
:app         Android shell                     →  phone only
:app-desktop Compose Desktop shell             →  desktop only
```

## The rule

**Write it in the lowest module that can hold it.** A parser belongs in
`:core`, a card belongs in `:ui`, and only the frame around them — navigation,
window management, notifications, the file picker — belongs in a shell.

`:core` cannot import Compose or Android. `:ui` cannot import Android. Those are
compile-time walls, so drift is caught by the build rather than by someone
noticing months later that the two apps disagree.

## Worked example: a new kind of transcript row

Say the daemon starts sending a `checkpoint` event and both clients should show
it.

1. **`:core/data/Models.kt`** — add the field. Every field the server may omit
   is nullable with a default, so an older client keeps parsing a newer daemon.
2. **`:core/ui/TranscriptGroups.kt`** — if it changes how rows group, it changes
   here, once, with a test in `:core`'s `commonTest`.
3. **`:ui/TranscriptView.kt`** — draw it. Both apps now render it, identically,
   because it is one composable.
4. **Nothing in either shell.** You are done.

That is the shape to aim for: three files, one test suite, both platforms.

## When the platforms genuinely differ

Prefer a **parameter** over `expect`/`actual`. Touch targets and density differ
between a thumb and a mouse — that is `LocalTranscriptMetrics` and
`LocalMonoStyle`, set by each shell, read by shared composables. A desktop
window narrowed to a phone's width then gets the phone's answer, which
`expect`/`actual` cannot express because it is a runtime question, not a
platform one.

Reach for `expect`/`actual` only when the platform API itself differs — the
HTTP engine, the IO dispatcher, the glyph blit. There are four in the whole
project; that number should stay small.

## Where the shells legitimately own things

| Phone (`:app`) | Desktop (`:app-desktop`) |
|---|---|
| FCM push, Doze alarms, WorkManager | the watch SSE stream (a desktop does not sleep) |
| biometric lock, `FLAG_SECURE` | tray, close-to-tray, `huginn://` scheme |
| share-target, camera, MediaStore | command palette, keyboard model, splitter |
| DataStore | a JSON settings file |

If a feature appears in both columns, look again — usually the *decision* is
shared and only the *mechanism* differs, and the decision belongs in `:core`.
`WatchCycle.finishedSince` is the example: both clients decide "which chats
finished" with the same code, then notify in their own way.

## Gates before you ship

```
./gradlew :core:jvmTest :core:testDebugUnitTest :ui:jvmTest \
          :app:testDebugUnitTest :app-desktop:test
./gradlew :app:assembleDebug          # the phone must not regress
./gradlew :app-desktop:compileKotlin
bash scripts/build.sh debug           # asserts a test-count FLOOR
```

The floor matters more than it looks. After `:core` was extracted,
`scripts/build.sh` was still running only `:app`'s tests — 58 of 179, exit 0,
green. A gate that covers one module is worse than no gate, because it reads
like coverage.

## Releasing

- **Phone**: `mobile/scripts/build.sh` then `ship.sh` (devstore).
- **Desktop**: `mobile/scripts/release-desktop.sh` — builds the Linux `.deb` and
  the Windows installer *on this box* (jpackage under wine, then `makensis`),
  stages them into `/v1/desktop-kt`, and verifies both back through the wire.
  Installed clients pick it up themselves.

Traps that have already cost time here are in
[`DESKTOP-MIGRATION.md`](DESKTOP-MIGRATION.md); the two worth memorising are
that `gradlew packageMsi` on Linux **exits 0 and produces nothing**, and that a
global desktop target ships the **wrong platform's native libraries** without
complaining.
