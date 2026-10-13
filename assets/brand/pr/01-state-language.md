# PR 1 · State language

**Goal.** One state table in one place, read by every dot, word, rail mark, status
line, widget row and round verdict. Needs-you is the lit accent everywhere; red is
failure only; idle is "waiting"; the eight private colour mappings go.

**New file.** `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/theme/StateColors.kt`:
`StateKind` (working · needs you · waiting · shell · ok · error · info),
`stateKindOf(wire)` (running → working, attention → needs you, idle → waiting),
`stateWord()`, `stateColor()` (from `LocalHuginnColors`), `StateDot()` (8 dp,
6 dp quiet).

**Sites changed in the draft** (all on this branch, uncompiled):

| file | before | after |
|---|---|---|
| `app/.../ui/Common.kt:203-230` `StateDot`, `stateLabel` | private map; attention = error; "no claude" | delegate to `StateColors`; "shell" |
| `app/.../ui/SessionsScreen.kt:415, 438` | attention dot and label in error | `stateColor(NEEDS_YOU)` |
| `app-desktop/.../ui/Lists.kt:406-410` | attention = error | `stateColor` |
| `app-desktop/.../ui/Shell.kt:962, 977, 992, 1019` rail marks; `:1389-1395` status line | error | `LocalHuginnColors.current.stateAttention` |
| `app-desktop/.../ui/SessionView.kt:561-564` header dot | error | `stateColor` |
| `app-desktop/.../ui/common/Tips.kt:153-157` | "Waiting on you", "Idle" | "Needs you", "Waiting" |
| `core/.../ui/Rounds.kt:58-66` `roundStatusColorKey` | ACTION = "error", ATTENTION = "primary" | ACTION = "attention", ATTENTION = "info" (news, not a demand) |
| `ui/.../RoundsView.kt:234-242` | maps "error"/"primary" | maps "attention"/"info" to the ladder |
| `core/src/commonTest/.../RoundsTest.kt:267-275` | expects "error"/"primary" | expects "attention"/"info" |
| `ui/.../AppsView.kt:472-485` `ReachDot` | retrofit = primary | retrofit = stateAttention ("needs somebody") |
| `ui/.../DevicesView.kt:166-171` | online = primary 8 dp | online = stateIdle 8 dp (reachable, waiting for work); offline stays outlineVariant 6 dp |
| `ui/.../settings/SettingsRows.kt:862-866` route dot | reachable = primary | reachable = stateSuccess; untried = stateIdle at 35 % |
| `ui/.../settings/SetupScaffold.kt:495-505` `toneColour` | PROVEN = primary; waiting-on-you = onSurface | PROVEN = stateSuccess; waiting-on-you = stateAttention |
| `app/.../widget/FleetWidget.kt:210-222, 312-325` | GlanceTheme error for needs-you | `ColorProvider(day, night)` from the tokens (`#0F3320` / `#E4E2A8`) until PR 5 puts the widget on the scheme |

**Left for PR 2 on purpose** (severity, not state): context ≥ 80 % (`SessionSignals.kt:47`),
the stalled mark (`SessionControls.kt:102`), the plan meters (`PlanView.kt:261`),
the usage-limit notice (`TranscriptView.kt:779`).

**Not changed, by decision:** `ProjectRules.kt:427` maps the daemon's `waiting`
(a member waiting on a human) to attention; that is a wire semantic, correct as is.

**Acceptance.**
- `grep -rn "colorScheme.error" mobile/*/src` returns only failure sites (tool
  failed, exit ≠ 0, disconnected, destructive verbs, refusals).
- `grep -rn "stateIdle\|stateWorking\|stateSuccess" mobile/ui/src` has readers.
- `RoundsTest` passes with the new keys; `PaletteTest` still passes.
- Screenshots: phone Sessions list with one working, one needs-you, one waiting
  row; desktop frame with a needs-you session (rail mark, row dot, header dot,
  status line, tab dot all the same colour); Rounds with a "Needs you" card; the
  Fleet widget.
