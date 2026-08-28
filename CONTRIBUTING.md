# Contributing to Huginn

Thanks for your interest! Huginn is one repo with two very different halves, and the
ground rules differ by half:

- **The terminal core** (`client/`, `server/` minus `appd/`) is intentionally tiny — a
  few shell scripts and a tmux config. Keep it lean, portable, and dependency-free:
  plain `bash`, `tmux`, and PowerShell. No frameworks, no build step.
- **The apps** (`server/appd/`, `mobile/`) are real programs with tests: a
  zero-dependency Node ≥ 20 daemon, and a Kotlin Multiplatform codebase that builds the
  Android app and the Compose desktop client. "Tiny" doesn't apply there — *layered*
  does: read [`docs/ADDING-A-FEATURE.md`](docs/ADDING-A-FEATURE.md) before touching
  `mobile/`, and write in the lowest module that can hold the change (`:core` → `:ui` →
  the shells). There is one desktop client and it is the Compose one; the Electron
  client that used to live in `desktop/` was retired and deleted on 2026-08-27.

## Ground rules (everywhere)

- **No secrets, ever.** No real IPs, hostnames, keys, or tokens in the repo. Use
  placeholders (`<host>`, `<VMID>`).
- **Keep `huginn.ps1` and `huginn.sh` in sync** — they're a pair. A new subcommand goes
  in both, plus the help text and the tab-completer.
- **Zero dependencies stays true.** The daemon has no `npm install`; keep it that way.
  The Kotlin side pins its versions in `mobile/gradle/libs.versions.toml`.
- **Tests gate the apps.** `mobile/scripts/build.sh` runs the Kotlin suites *and* the
  daemon's `node --test` suite before any APK, and asserts the test **count** as well as
  the exit code. A change that removes tests from discovery will be caught — but don't
  make it try.

## Good first contributions

- A `client/install.sh` path for fish/zsh, or a macOS note.
- Provisioning recipes for other hosts (cloud-init, Docker, NixOS).
- A real demo GIF (see [`assets/demo.tape`](assets/demo.tape)).
- Docs fixes, clearer wording, more FAQ entries.
- Daemon routes currently have thin direct test coverage (`server/appd/test/` exercises
  the libs) — route-level tests are welcome.

## Dev notes

- **The two CLI client files are ASCII-only.** `client/huginn.ps1` and
  `client/huginn.sh` must contain **no non-ASCII characters** — no box-drawing,
  em-dashes (`—`), ellipses (`…`), or smart quotes. `huginn update`'s `scp` path
  delivers the file without a BOM, and Windows PowerShell 5.1 then mis-decodes any
  non-ASCII as the ANSI code page and fails to parse the whole file. Use `-`, `--`,
  `...`, and straight quotes. (READMEs, `docs/`, and the Kotlin/Node sources are
  exempt — they're never sourced by a shell.)
- **Line endings matter.** Shell scripts must be **LF** (enforced by
  [`.gitattributes`](.gitattributes)); PowerShell stays CRLF. Don't commit CRLF shell
  scripts — they break on the Linux host.
- **Executable bits:** server `bin/*`, `setup.sh`, and the `*.sh` installers are tracked
  `+x`. If you add a runnable script, set it: `git update-index --chmod=+x path`.
- **tmux specifics:** the `Alt-o` binding relies on `##{client_tty}` (deferred format
  expansion). If you touch it, test with two attached clients.
- **Kotlin tests use `kotlin.test`**, whose `assertEquals` takes the message **last**
  where JUnit takes it first — with three String arguments that difference compiles
  silently. Convert assertions by hand, never by `sed`.
- **Branding:** the raven is one canonical path, `assets/brand/raven.svg`;
  `assets/brand/generate.sh` regenerates every raster and lists the hand-carried copies
  (the Android vector drawables, the desktop's `RavenMark.kt`). Change the path, chase
  the list.

## Cutting a release

Each component versions independently and its changelog is the source of the
release notes — write the `## <version>` section first, or the tooling refuses:

- **CLI / server core**: bump both `client/huginn.{sh,ps1}` (they stay in
  lockstep), add the `CHANGELOG.md` section, then
  `scripts/github-release.sh core X.Y.Z` (tags `vX.Y.Z`, cuts the GitHub
  release from the section).
- **Android app**: `mobile/scripts/ship.sh` — publishes to the store *and*
  mirrors to GitHub (`app-vX.Y.Z` + the signed APK) automatically.
- **Compose desktop**: `mobile/scripts/release-desktop.sh` — publishes to the
  update channel *and* mirrors to GitHub (`desktop-vX.Y.Z` + both installers).
- **Daemon**: deploys are not releases; when a version is worth marking,
  `scripts/github-release.sh appd X.Y.Z` (notes via
  `HUGINN_RELEASE_NOTES_FILE` if there's no changelog section).

The GitHub mirror is best-effort by design: the store/channel publish is the
release, and a GitHub outage must not fail it.

## Workflow

1. Fork → branch → change.
2. `shellcheck` your bash where you can; `Invoke-ScriptAnalyzer` for PowerShell is a
   bonus. For `mobile/`, run `scripts/build.sh` (or at minimum the affected module's
   tests); for the daemon, `node --test server/appd/test/`.
3. Open a PR with a clear description of what and why. Small, focused PRs merge fastest.

## Code of conduct

Be kind and constructive. See [`CODE_OF_CONDUCT.md`](CODE_OF_CONDUCT.md).
