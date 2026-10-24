# Contributing: developer docs

These pages are for building, testing and changing Huginn. They assume you cloned the
repository on a Linux machine. To run Huginn rather than change it, start at
[the install guide](../install/README.md).

| Page | What it covers |
|---|---|
| [Architecture](architecture.md) | How the server, the daemon, huginn core, the shell client, the console and the Kotlin apps fit together |
| [Building](building.md) | Toolchains, and how to build each component on your machine |
| [Testing](testing.md) | Every test gate, how to run it, and the rules the gates enforce |
| [Claude Code integration](claude-code-integration.md) | The Claude Code interfaces Huginn depends on, and what to check when Claude Code updates |
| [Daemon TLS protocol](protocol-tls.md) | The daemon's TLS and pin-by-proof contract, for anyone writing a client |
| [Kotlin clients](kotlin-clients.md) | The shared Kotlin modules, the Android and desktop apps, and the rules for adding a feature |
| [Go core](go-core.md) | The `huginn` binary: packages, IPC, the embedded shell client, platform services |

The visual design (mark, palette, state language, type) is documented in
[the design docs](../design/README.md) and `assets/brand/BRAND.md`.

## Repository map

```text
client/              the shell client and the one-line installers
core/                huginn core: one Go binary
server/              the Huginn Server install and its host tools
server/appd/         huginn-appd, the daemon the apps pair with
mobile/core/         shared Kotlin logic (Android + JVM)
mobile/ui/           shared Compose UI (Android + JVM)
mobile/app/          the Android app
mobile/app-desktop/  the desktop app (Windows, Linux)
shared/              data files several languages read: device policy, local AI pins
skills/              the Claude Code skills: huginn, huginn-doctor (carried by the core), huginn-server (installed by the server)
scripts/             generators and test gates
assets/brand/        the brand: mark, tokens, rules, design card
docs/                user, reference and contributor documentation
```

| Path | What lives there |
|---|---|
| `client/` | `huginn.sh` (bash, sourced as a function) and `huginn.ps1` (PowerShell); the installers `get.sh`, `get.ps1`, `install.sh`, `install.ps1`; `help-table.txt`, the source of both help screens; and three Node programs: `huginn-device` (the device runner for a machine without huginn core), `huginn-local` and `huginn-llm-shim` (the experimental local AI tier). |
| `core/` | huginn core. `cmd/huginn/` is the binary, `internal/` its packages, `test/` the end-to-end scripts, `third_party/bubbletea/` a patched dependency, `build.sh` the cross-compiling build. See [Go core](go-core.md). |
| `server/` | `get.sh`, `setup.sh` and `uninstall.sh` for a Huginn Server; `tmux.conf`; `claude-hooks.json` (the hook spec merged into `~/.claude/settings.json`); host tools in `bin/` (`cc`, `huginn-ask`, `huginn-status`, `huginn-claude-title`, the `huginn-*` renderers); the generated state-table readers in `lib/`. |
| `server/appd/` | `huginn-appd`, a zero-dependency Node daemon: `huginn-appd.js`, `lib/` (mostly pure modules), `hooks/` (its own Claude Code hooks), `install.sh`, the systemd unit and drop-ins in `systemd.d/`, and `test/`. |
| `mobile/core/` | Kotlin Multiplatform, Android and JVM targets: wire models, `HuginnClient` (every HTTP call either app makes), route book and TLS pinning, renderers, pure UI rules. |
| `mobile/ui/` | Compose Multiplatform, Android and JVM targets: the theme, `theme/StateColors.kt`, markdown, transcript rows, the terminal painter. |
| `mobile/app/` | The Android app. |
| `mobile/app-desktop/` | The Compose Desktop app. It reaches the daemon through huginn core. |
| `mobile/scripts/` | `build.sh` (the Kotlin and daemon test gate, then an APK) and `package-linux.sh` (a portable tarball and an Arch package from a built app-image). The other scripts there are release tooling. |
| `shared/` | `device-policy.json` and its generated case matrix, `local-runtime.json` (the local AI tier's download pins). |
| `skills/` | Claude Code skills, each a directory with `SKILL.md` and topic files: `huginn` and `huginn-doctor` for a client computer (embedded in huginn core from `core/internal/skills/files/`), `huginn-server` for sessions on the server (`server/setup.sh` installs it). Placeholders only, like the docs. |
| `scripts/` | Generators (`gen-help.js`, `gen-device-policy.js`, `gen-state-table.js`, `gen-local-manifest.js`), the client gate `test-client.sh`, the shared test floors `test-floors.env`, and focused test files (`test-device-tls.js`, `test-local-tier.js`, `test-llm-shim.js`, `test-claude-title.sh`). |
| `assets/brand/` | `raven.svg` (the one canonical mark), `BRAND.md` (the rules), the design card and its generator, `generate.sh` (every raster from the mark). |
| `docs/` | This documentation. |
| `proto/` | Standalone feasibility probes (JVM over AF_UNIX, the Go tray, a Windows S4U task). Not part of any build. |
| `provision/` | Notes for preparing a host (generic machine, Proxmox LXC). |
| `.github/` | CI (daemon tests, shell syntax and shellcheck) and CodeQL (compiles every Kotlin module). |

## Golden rules

These hold everywhere in the tree. Most are enforced by a test, named in brackets.

1. **The shell clients print 7-bit ASCII only.** No em dashes, ellipses, box drawing or
   smart quotes in any string in `client/huginn.sh`, `client/huginn.ps1`,
   `client/install.sh`, `client/install.ps1` or `client/help-table.txt`. Windows
   PowerShell 5.1 reads a file without a byte-order mark in the ANSI code page, so one
   non-ASCII character prints as garbage on a Windows screen. Comments may carry UTF-8,
   because they are never printed. Use `-`, `--`, `...` and straight quotes.
   [`scripts/test-client.sh`, lane 5f2]
2. **`huginn.sh` and `huginn.ps1` are a pair.** A new verb goes into both dispatchers,
   both tab completers and `client/help-table.txt`. Their `HUGINN_VERSION` constants,
   their `# Version:` header comments and the top `CHANGELOG.md` entry agree.
   [`test-client.sh`, lanes 2 and 3]
3. **Help text is generated.** Edit `client/help-table.txt`, then run
   `node scripts/gen-help.js`. It splices the body into both clients between markers. Never
   edit the help block in a client by hand. Both help screens fit 80 columns.
   [`node scripts/gen-help.js --check`, run by `test-client.sh`]
4. **Device policy is generated.** What a remote request may become on a machine is
   written once, in `shared/device-policy.json`. Edit it, run
   `node scripts/gen-device-policy.js`, and read the diff. The generator writes the case
   matrix, the Node runner's embedded copy, the Kotlin table, the daemon's table and the Go
   table (`core/internal/policy/table_gen.go`). Every implementation is tested against the
   same case matrix. [`--check`; `server/appd/test/device-policy-cases.test.js`,
   `DevicePolicyCasesTest.kt`, `core/internal/policy`]
5. **One state language.** The seven state words are exactly: working, needs you, waiting,
   shell, ok, error, info. The host tools read them from `server/lib/state-table.json`
   through readers generated by `node scripts/gen-state-table.js`. In Kotlin,
   `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/theme/StateColors.kt` is the
   only place a state maps to a colour or a word. "Needs you" is a question waiting: it
   takes the lit accent, never the error colour. [`gen-state-table.js --check`,
   `server/appd/test/state-table.test.js`]
6. **The shell client huginn core carries matches `client/`.** huginn core embeds
   `client/huginn.sh`, `huginn.ps1`, `huginn-local` and `huginn-llm-shim` as
   `core/internal/clientkit/files/*.txt`, with carriage returns stripped. When you change
   one of those files, refresh its copy in the same commit:
   `tr -d '\r' < client/huginn.sh > core/internal/clientkit/files/huginn.sh.txt`.
   [`TestEmbeddedMatchesClient`; `core/build.sh` refuses to build on drift] The same holds
   for the skills it carries: `skills/huginn` and `skills/huginn-doctor` are copied into
   `core/internal/skills/files/`. [`TestEmbeddedMatchesSkills`; `core/build.sh`]
7. **Kotlin goes in the lowest module that can hold it:** `:core`, then `:ui`, then the
   app (`:app` or `:app-desktop`). Logic one app needs today, the other will need
   tomorrow. See [Kotlin clients](kotlin-clients.md).
8. **No new dependencies by accident.** The daemon has no `npm install` and stays that
   way. Kotlin versions are pinned in `mobile/gradle/libs.versions.toml`. Go
   dependencies are in `core/go.mod`.
9. **Test counts are floors.** The gates assert how many tests ran, not only that nothing
   failed, against `scripts/test-floors.env`. Raise the floor when you add tests. A new
   test must fail on the code before your change. See [Testing](testing.md).
10. **No secrets or real addresses in the tree.** Use placeholders (`<host>`,
    `192.0.2.10`). Tests use their own throwaway tokens and private daemons.
11. **Line endings and modes.** Everything is LF except `*.ps1`, which is CRLF
    (`.gitattributes`). Runnable scripts are tracked executable:
    `git update-index --chmod=+x <path>`.
12. **The local AI tier's pins are generated.** Edit `shared/local-runtime.json`, then run
    `node scripts/gen-local-manifest.js`, which embeds it in `client/huginn-local`.
13. **The brand is not improvised.** Colours come from the brand tokens, the mark is
    `assets/brand/raven.svg`, and `assets/brand/generate.sh` regenerates every raster and
    lists the hand-carried copies (Android vector drawables, the desktop's
    `RavenMark.kt`). After changing tokens, states or the mark, regenerate the design card
    with `python3 assets/brand/design-card.py`.

## Workflow

1. Fork, branch, change.
2. Run the gates for what you touched (see [Testing](testing.md)). Run `shellcheck` on
   bash you changed.
3. Open a pull request that says what changed and why. If the change depends on Claude
   Code behaviour, record the Claude Code version you verified against (see
   [Claude Code integration](claude-code-integration.md)).
