# PR 2 · Surfaces, severity and the accent

**Goal.** Use the surface tiers the brand reserves, one severity ramp for every
meter, and the accent only for live-and-you.

**Surfaces** (`BRAND.md` §4 "Tiers"):
- Code wells and tables to `surfaceContainerLowest`: `ui/.../MarkdownText.kt:236` (table), `:436` (proposal), `:477` (code card).
- List rows and bubbles to `surfaceContainerLow`; cards to `surfaceContainer`; dialogs, menus, the active tab to `surfaceContainerHigh`; chips to `surfaceContainerHighest`.
- Remove the stacked `surfaceVariant.copy(alpha = …)` fills (eleven of them: `TranscriptView.kt` ThinkingBlock :459, ToolCard :523, ToolResultOrphan :689, CommandNote :715; `WorkViews.kt` AgentCard :218; `StreamPickerRow.kt` :435; `PlanView.kt` ExtraUsageCard; `ScratchpadViews.kt` pad chip; `AppFormFields.kt`; `HeadroomSettingsView.kt:592`).
- Dividers to `outlineHairline` (26 `outlineVariant` divider uses); `outline` for strokes.

**Severity ramp** (normal = outline, warning = stateAttention, critical = error):
- `ui/.../SessionSignals.kt:43-80` ContextMeter and ContextBadge (≥ 80 %).
- `ui/.../PlanView.kt:261-265` meter colours (today heather = normal, moss = warning).
- `app/.../ui/StatusScreen.kt:120-124, 154-160` (MemPalace "rebuilding", disk 80 %).
- `ui/.../SessionControls.kt:102` stalled mark; `ui/.../TranscriptView.kt:779` LimitNotice (headroom, not failure).
- `core/.../HeadroomRules.severityColorKey` to the same keys.

**Accent discipline** (primary = working dot and pulse, selection, links, cursor, the one filled Send):
- Strip primary from: Status section headings (`app-desktop/.../StatusView.kt:95-101`), Headroom section labels (`ui/.../HeadroomSettingsView.kt:418`), tool detail text (`TranscriptView.kt:562`), relay attribution (`:844`), SubagentsCard and AskCard headers (`:294`, `:617`), the ChatsScreen bolt (`app/.../ChatsScreen.kt`), "+N queued" and the queued dot (tertiary pink → onSurfaceVariant, `ChatsScreen.kt:340, 397, 404`), workflow ids (tertiary → onSurfaceVariant mono, `WorkViews.kt:242`, `SessionOverviewView.kt:778`, `app/.../SessionScreen.kt:1102`).
- Filled buttons for routine actions become text or outlined: NewestPill (`ui/.../Follow.kt:262-267`), "Live" (`app-desktop/.../SessionView.kt:1239`), "Save token" and "Copy" (`SettingsRows.kt:236-242`, `SettingsPages.kt:156`), "Grant microphone access", "New round" empty state (`RoundsPane.kt:107`).
- Stop and Interrupt to plain outlined: `app/.../ChatScreen.kt:498`, `app/.../SessionScreen.kt:812`, `app-desktop/.../SessionView.kt:1462`; soft "Wrap up" to onSurfaceVariant (`Theme.kt verbInk SOFT`, `Menus.kt:206`); destructive confirm buttons in dialogs to error (`SessionsScreen.kt` Kill/Archive/Forget, `ChatTopBar.kt:146-176`, `RoundsPane.kt:198`, `DevicesPane.kt:103`).
- Overview lanes (`ui/.../SessionOverviewView.kt:519-572`): six syntax hues → ink steps (onSurface, onSurfaceVariant, outline at two alphas), failed = error, live = primary.
- FilterChips: define `secondaryContainer`/`onSecondaryContainer` once in `Theme.kt` from the tokens (`#352A52` / `#E2D7F5`, light `#E6DCF5` / `#2E1A52`) and update `PaletteTest`'s gate accordingly, or pass `FilterChipDefaults.filterChipColors` once (`RoundEditorView.kt` ×7, `CreateProjectSheet.kt:125`, `AppFormFields.kt:283`).
- Apps favicons (`ui/.../AppsView.kt:327`): monochrome letter tile by default, favicon only on the detail view.
- `res/values/themes.xml`: status and navigation bar colours follow the scheme in light mode.

**Acceptance.** No `surfaceVariant.copy(alpha` in `:ui`; no `tertiary` reads outside
Theme.kt; one filled button per screen; screenshots of a transcript with a code block,
a tool card and an ask card; Status with a warning meter; the Overview tab.
