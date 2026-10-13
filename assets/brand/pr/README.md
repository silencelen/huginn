# The reskin pull requests

Six pull requests, drafted on `claude/amazing-keller-uk2wd5` (now the rebased `reskin` branch) as labelled commits
("PR 1 draft: ...") for a local session with a build to split, compile, screenshot
and merge. The order compounds: each later PR is built on the earlier ones. The
findings behind each come from [`../UI-AUDIT.md`](../UI-AUDIT.md); the rules from
[`../BRAND.md`](../BRAND.md). Each brief lists the exact sites, the acceptance
checks and what a reviewer should screenshot.

| PR | brief | state on this branch |
|---|---|---|
| 1 · State language | [`01-state-language.md`](01-state-language.md) | in code; compiles, RoundsTest + PaletteTest pass (2026-10-13); awaiting device screenshots |
| 2 · Surfaces, severity, the accent | [`02-surfaces-severity-accent.md`](02-surfaces-severity-accent.md) | brief only |
| 3 · The row system | [`03-row-system.md`](03-row-system.md) | brief only |
| 4 · Session screen and the composer | [`04-session-and-composer.md`](04-session-and-composer.md) | brief only |
| 5 · Notifications, widgets, type | [`05-notifications-widgets-type.md`](05-notifications-widgets-type.md) | brief only |
| 6 · Terminal | [`06-terminal.md`](06-terminal.md) | brief only |

Build and test gates (none of which ran in the authoring container, which could
not resolve the Kotlin Gradle plugin): `mobile/scripts/build.sh` (both Kotlin
suites, asserts the counts), `scripts/test-client.sh` (the CLI gate; passes here
except the daemon-token tools), `bash -n` on every shell file, and the
non-ASCII grep over `client/huginn.sh` and `huginn.ps1` output text.
