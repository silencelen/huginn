# Contributing to Huginn

Thank you for helping. Bug reports, fixes, documentation and ideas are all welcome.

- **Questions and ideas:** [Discussions](https://github.com/silencelen/huginn/discussions).
- **Bugs:** [open an issue](https://github.com/silencelen/huginn/issues/new/choose) with the
  versions and the steps to reproduce.
- **Security issues:** report them privately; see [SECURITY.md](SECURITY.md).

## The codebase

| Part | Where | Language |
|---|---|---|
| Server: host tools, tmux config, installer | `server/` | bash, Python |
| Server: the daemon | `server/appd/` | Node.js 22, no dependencies |
| Client: the `huginn` command | `client/` | bash and PowerShell (a pair) |
| Client: huginn core and the console | `core/` | Go |
| Apps: shared logic and UI, Android, desktop | `mobile/` | Kotlin, Compose Multiplatform |

The [developer docs](docs/dev/README.md) explain the architecture, how to build each part and how
to run every test.

## Before you open a pull request

1. **Run the tests for what you changed** ([Testing](docs/dev/testing.md)):
   - daemon: `cd server/appd && node --test test/*.test.js`
   - clients: `scripts/test-client.sh` (the PowerShell half needs `pwsh`)
   - core: `cd core && go vet ./... && go test ./...`
   - apps: `cd mobile && ./gradlew :core:jvmTest :ui:jvmTest :app:testDebugUnitTest :app-desktop:test`
2. **Add a test that fails without your change.** The suites assert their test counts, so a
   test that silently stops running is caught.
3. **Keep the pairs in step.** A change to `client/huginn.sh` goes into `client/huginn.ps1` too.
   Help text lives in `client/help-table.txt` and is generated into both
   (`node scripts/gen-help.js`). After changing either client, refresh the copies embedded in
   huginn core (see [Go core](docs/dev/go-core.md)).
4. **Follow the house rules:**
   - The client scripts print 7-bit ASCII only (Windows PowerShell 5.1 misreads anything else).
   - Shell scripts are LF; PowerShell files are CRLF (`.gitattributes` enforces it). Runnable
     scripts are tracked executable.
   - The daemon stays free of npm dependencies.
   - Kotlin goes in the lowest module that can hold it ([Kotlin clients](docs/dev/kotlin-clients.md)).
   - No real addresses, host names, accounts, keys or tokens anywhere, including tests: use
     `192.0.2.x`, `example.net` and similar placeholders.
5. **Describe the change** in the pull request: what it does, why, and how you tested it.

## Design and brand

The visual identity is defined in [`assets/brand/BRAND.md`](assets/brand/BRAND.md): colours, type,
the raven, and the state words (working, needs you, waiting, shell, ok, error, info). Use the
tokens and state tables rather than new colours or wording.

## Code of conduct

Everyone taking part is expected to follow the [Code of Conduct](CODE_OF_CONDUCT.md).
