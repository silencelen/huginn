# PR 4 · Session screen and the composer

**Goal.** One composer for Chat, Session and Terminal; the session screen's four
strips become tabs plus one context strip; pickers drawn the same way everywhere.

**Composer** (`mobile/ui/.../ui/system/Composer.kt`): the 20 dp outlined field, attach and mic as plain icon buttons, Send as the one filled primary circle (`Icons.AutoMirrored.Outlined.Send`), Stop as a plain outlined circle; queue and wrap-up notices as a single `labelSmall` line above the field in onSurfaceVariant (draft collision included, not error). Replace: `app/.../ChatScreen.kt:422-523`, `app/.../SessionScreen.kt:690-834`, `app/.../TerminalScreen.kt:363-371` (drop the "↦"), `app-desktop/.../ChatView.kt` composer, `app-desktop/.../SessionView.kt` composer and key bar (`:1239-1267`: "Live" as an outlined toggle like the other pills, in `LocalMonoStyle`).

**Session screen:** `app/.../SessionScreen.kt:194-210` TabRow + `SessionControls` chip strip + `StreamPickerRow` + `WorkStrip` → TabRow, then one context strip (model · effort · mode as chips, the context meter, the auto-resume toggle) that collapses to one line; the stream picker and the work strip become a single secondary row that appears only when there are streams or agents. Desktop: `SessionView.kt:288-496` header loses the bare "label ▾" pickers for the same chips as Chat (`ChatOptionsRow.kt:114-142`), the three notice bands become one.

**Overview tab:** lanes on ink steps (done in PR 2); density switch kept.

**Acceptance.** `grep -rn "Filled.Send\|KeyboardReturn" mobile` finds only the shared composer; screenshots of Chat, Session (Conversation and Screen tabs) and the desktop session view at 1280 and 700 wide.
