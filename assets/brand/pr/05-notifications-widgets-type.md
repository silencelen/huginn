# PR 5 · Notifications, widgets, type

**Notifications** (`app/.../notify/SessionWatchWorker.kt:263-455`, `WatchNotifier.kt`, `HeadroomNotices.kt:145`, `ProjectNotices.kt:109`, `WatchService.kt:216-246, 299`):
- `setColor(primary)` on every notification; small icon stays the tile glyph.
- Channels: `sessions_attention` "Needs you", `chat_results` "Finished", `watch_ongoing` "Watching", plus a new `proposals` "Proposals" channel for project proposals (today they ride "Finished work").
- Ongoing text: "1 needs you · 2 working · 3 chats" using the state words; "all waiting" not "all idle".
- Linux (`app-desktop/.../notify/LibnotifyNotifier.kt:64-81`): `--icon=<raven PNG>` rendered from `assets/brand/raven-inverse.svg` at 96 px into the packaging; Windows toasts (`WindowsToastNotifier.kt:175-213`) keep the AUMID icon.

**Widgets** (`app/.../widget/FleetWidget.kt:84`, `AskWidget.kt:53`): `GlanceTheme(colors = ColorProviders(light = LightColors, dark = DarkColors))` built from `Theme.kt`'s schemes (expose them, they are private today); the row dots and words through `StateColors` equivalents (Glance cannot read composition locals: a `GlanceStateColors` mirror with day/night providers from the tokens); header "huginn" in the mono.

**Type** (`BRAND.md` §5):
- `mobile/ui/build.gradle.kts`: `implementation(compose.components.resources)`; TTFs in `mobile/ui/src/commonMain/composeResources/font/` (`ibm_plex_mono_regular/medium/semibold/italic.ttf`, `ibm_plex_sans_regular/medium/semibold.ttf`; the static files are in the Google Fonts GitHub repos, or convert the woffs the authoring session fetched).
- `Theme.kt`: `HuginnTypography` from the role table (displaySmall 44/52 Mono 600; headlineMedium 28/36 Sans 600; titleLarge 22/28 Sans 500; bodyLarge 16/24 Sans 400; labelMedium 13/16 Mono 500 +6 %; labelSmall 11/16 Mono 500); `MonoStylePhone/Desktop` on the bundled Plex Mono; route the `labelSmall + FontFamily.Monospace` sites (`TranscriptView.kt` ToolCard, orphan, CommandNote; `WorkViews.kt` AgentCard) through `LocalMonoStyle`.
- Terminal canvas (`ui/src/jvmMain/.../CellPainter.jvm.kt` font list): Plex Mono first; `ui/src/androidMain` painter loads the bundled face.
- Disable ligatures in the terminal view.

**Acceptance.** A notification and the ongoing notice screenshot; both widgets on a light and a dark wallpaper; the app at 11, 13 and 44 sp showing Plex; the terminal grid with box and block glyphs from Plex.
