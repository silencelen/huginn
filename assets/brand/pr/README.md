# The reskin pull requests

Six pull requests, drafted on `claude/amazing-keller-uk2wd5` (now the rebased `reskin` branch) as labelled commits
("PR 1 draft: ...") for a local session with a build to split, compile, screenshot
and merge. The order compounds: each later PR is built on the earlier ones. The
findings behind each come from [`../UI-AUDIT.md`](../UI-AUDIT.md); the rules from
[`../BRAND.md`](../BRAND.md). Each brief lists the exact sites, the acceptance
checks and what a reviewer should screenshot.

| PR | brief | state on this branch |
|---|---|---|
| 1 · State language | [`01-state-language.md`](01-state-language.md) | done; verified on the Fold 2026-10-13; tokens frozen in round 7 |
| 2 · Surfaces, severity, the accent | [`02-surfaces-severity-accent.md`](02-surfaces-severity-accent.md) | done on `reskin` (e950f3e6, 2026-10-13); suites pass; Fold shots |
| 3 · The row system | [`03-row-system.md`](03-row-system.md) | system layer landed (`ui/system/`: Scale + RowMetrics, ListRow, ListHeader, EmptyState, HouseDialog); headers Title Case; glyphs → icons; bar icons outlined, filled when active; quote bar; desktop rows 12/6. Slice 2: settings categories + project members on ListRow (no pill tint, no selection dot); chats, projects and archived rows on the platform metrics; the seven confirm dialogs (new/rename/kill/archive/forget sessions, delete round, forget device) on HouseDialog. REMAINING: Sessions and Chats rows onto ListRow with the detail slot, desktop Lists.kt rows, Apps, Devices, Pages, Settings rows, setup rail, palette; the house Menu into :ui; the emoji markers (send contract, owner) |
| 4 · Session screen and the composer | [`04-session-and-composer.md`](04-session-and-composer.md) | brief only |
| 5 · Notifications, widgets, type | [`05-notifications-widgets-type.md`](05-notifications-widgets-type.md) | brief only |
| 6 · Terminal | [`06-terminal.md`](06-terminal.md) | brief only |

Build and test gates (none of which ran in the authoring container, which could
not resolve the Kotlin Gradle plugin): `mobile/scripts/build.sh` (both Kotlin
suites, asserts the counts), `scripts/test-client.sh` (the CLI gate; passes here
except the daemon-token tools), `bash -n` on every shell file, and the
non-ASCII grep over `client/huginn.sh` and `huginn.ps1` output text.
