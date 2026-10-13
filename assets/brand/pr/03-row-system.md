# PR 3 · The row system

**Goal.** One row, one list header, one empty state, one dialog and one menu, on a
spacing and radius scale, on both platforms.

**New shared components** (`mobile/ui/.../ui/system/`):
- `ListRow(leading, title, meta, trailing, selected, onClick)`: dot or icon · title (bodyMedium; mono via `LocalMonoStyle` when it is a name) · one meta line (labelSmall onSurfaceVariant) · trailing time or state word. Phone 16/10, desktop 12/6 (take the inset from a `LocalRowMetrics`). Selected = `surfaceContainerLow` + SemiBold, nothing else.
- `ListHeader(title, count, verb)`: Title Case, labelLarge, 16/8 (desktop 12/4), hairline below.
- `EmptyState(title, line, action?)`: left-aligned, titleSmall + bodySmall, one text button.
- `HouseDialog(title, body, confirm, destructive)`: titleSmall title; destructive confirm in error.
- The house `Menu` from `app-desktop/.../common/Menus.kt` moved to `:ui` and used by the phone ⋮ menus and the chat top bar (`ChatTopBar.kt:123-140` uses a stock DropdownMenu).
- `Scale`: spacing 4/8/12/16/20/24; radius 6 chips, 8 cards and rows, 12 sheets and dialogs; dots 8 and 6; icons 16 and 20.

**Migrate** (the audit's row table): Chats and Sessions rows (`app/.../ChatsScreen.kt:329`, `SessionsScreen.kt:398`, `app-desktop/.../Lists.kt:122-498`), Projects and members (`ui/.../ProjectsListView.kt:175`, `ProjectDashboardView.kt:295, 318`: disclosure on one side, as an icon), Apps (`AppsView.kt:175`), Devices cards (`DevicesView.kt:148`), Archived (`ArchivedSessionsView.kt:146`), Pages (`ScratchpadViews.kt`), Settings categories and rows (`SettingsScaffold.kt:268-320`, `SettingsRows.kt:89-135`: drop the pill tint and the 7 dp selection dot), the setup rail (`SetupScaffold.kt:452`), the command palette rows.

**Headers:** "DEVICES", "ROUNDS", "APPS", "MEMBERS" and the RoundEditor "WHEN / WHAT IT MAY DO / WHERE IT RUNS / TELL ME" labels to Title Case without tracking; Devices gets a header.

**Glyphs to icons:** ▾ ▸ (`ArchivedSessionsView.kt:88`, `ProjectDashboardView.kt:318`, `ProjectsListView.kt:183`), › (`SettingsRows.kt:268`, `SettingsScaffold.kt:310, 336`), ↑ ↓ (`SettingsRows.kt:734`), → (`QuestionLinkBar.kt:42`, `TranscriptView.kt:663`), ⚙ (`core/.../WorkSummary.kt:102`), ↦ and ⇧ (`app/.../TerminalScreen.kt:363, 484`), 📷 📎 📝 (`core/.../AttachmentText.kt:52-55`, `core/.../ScratchpadRules.kt:175`).

**Icons:** Outlined everywhere; Filled only for the active bottom-bar item (`MainActivity.kt:2119-2146` mixes them; the comment at :2421 is wrong); replace the deprecated `Filled.Chat` (`ChatsScreen.kt:246-267`); give Devices and "Local" different icons.

**Acceptance.** Every list on both platforms renders through `ListRow`; `grep` finds no `padding(horizontal = 14.dp)` or `16.dp, 4.dp, 12.dp, 12.dp` row paddings; the markdown quote bar spans multi-line quotes (`MarkdownText.kt:140`, `IntrinsicSize.Min`); screenshots of Sessions, Projects, Settings and Pages on phone and desktop.
