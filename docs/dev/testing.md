# Testing

Every component has a gate. Run the gates for what you touched before you open a pull
request. CI runs the daemon suite and the shell syntax checks on pushes and pull requests to
`main`, and CodeQL compiles every Kotlin module, but CI does not run the Kotlin, Go or client
suites for you.

| You changed | Run |
|---|---|
| `server/appd/` | [The daemon suite](#the-daemon-suite) |
| `client/`, `server/bin/`, `server/lib/`, a generator or its source | [The client gate](#the-client-gate) |
| `core/` | [Go tests](#go-tests) and [the end-to-end scripts](#end-to-end-scripts) |
| `mobile/` | [The Kotlin suites](#the-kotlin-suites) |
| `shared/device-policy.json` | The generator, then all of the above: every implementation is held to the same case matrix |

## The rules behind the gates

**Counts are asserted, not only exit codes.** A test runner whose glob matches nothing, or a
module whose test sources moved, exits 0 having run nothing. So the gates count the tests that
passed and compare the count with a floor in `scripts/test-floors.env`, the one place floors
are written:

| Floor | What it counts |
|---|---|
| `APPD_MIN` | Passing tests in the daemon suite |
| `KOTLIN_SHARED_MIN` | `:core` (both targets), `:ui` and `:app` tests |
| `DESKTOP_EXTRA_MIN` | `:app-desktop` tests, on top of `KOTLIN_SHARED_MIN` |

When you add tests, raise the floor in the same change, a few below the new actual count. Never
copy a floor into a script; source the file. Lower a floor only when you delete tests on
purpose, and say why in the comment beside it.

**A new test must fail on the old code.** Before you trust a test, run it against the code as
it was before your change (stash the fix, or break the line it covers) and watch it fail. A
test that passes either way proves nothing. When you fix a bug, the regression test comes
first.

**Tests stay off the network and away from real servers.** The suites use throwaway tokens,
private daemons started from the tree on loopback, private tmux sockets (`tmux -L <name>`,
never the default server), mock HTTP engines and stand-in `claude` scripts. No test sends a
request to a real daemon or uses a real Claude Code login. The few client-gate lanes that read
a server's `/etc/huginn-appd/token` (see below) do so only to check that no output ever
contains it, or to give a private daemon the same token; they skip where the file is not
readable.

**A skip is not a pass.** A check that cannot run prints `SKIP` and says why. Read the skips.

**Redirect, never pipe, a gate's output.** `gate | tee log` returns `tee`'s exit status in a
shell without `pipefail`, so a red suite reads green. Use `gate > log 2>&1; echo "exit $?"`.

## The daemon suite

```bash
node --test server/appd/test/*.test.js > /tmp/appd-tests.tap 2>&1; echo "exit $?"
. scripts/test-floors.env
grep -E '^# (pass|fail)' /tmp/appd-tests.tap; echo "floor: $APPD_MIN"
```

- Pass the explicit glob. A bare directory argument is an error to `node --test`, and a glob
  that matches nothing exits 0.
- It takes a few minutes. The route tests start real daemons from the tree on loopback, each
  on its own port range and its own tmux socket; they need `tmux` and `openssl`. A few tests
  need `python3` and skip without it.
- **A leaked test daemon fails the suite.** If an earlier run was interrupted, a
  `node .../huginn-appd.js` from your checkout may still hold a port in a test's range. It
  refuses the new run's token, and every test in the colliding file fails with
  `401 unauthorized`. Find it with `pgrep -a node` and stop it yourself.
- **Adding a route test file:** take the next free port block and add it to the
  `PORT ALLOCATION` table at the top of the route test files, in every file that carries the
  table.
- `server/appd/test/docs-truth.test.js` reads the `/v1/ping` and `/v1/challenge` rows of the
  API table in `docs/reference/api.md`. Keep those rows, and what they say about the token, when
  you edit that page.

The device-policy case matrix in `device-policy-cases.test.js` runs against the Node runner by
default. Point it at a huginn core binary to hold the Go runner to the same matrix:

```bash
(cd core && go build -o /tmp/huginn-dev ./cmd/huginn)
HUGINN_DEVICE_BIN=/tmp/huginn-dev node --test server/appd/test/device-policy-cases.test.js
```

CI also runs `node --check server/appd/huginn-appd.js`.

## The client gate

```bash
scripts/test-client.sh > /tmp/client-gate.log 2>&1; echo "exit $?"
grep -E 'FAIL|SKIP' /tmp/client-gate.log
```

`scripts/test-client.sh` holds the shell clients and the host tools to what they claim:

- both clients parse, and their version constants agree with each other and with
  `CHANGELOG.md`;
- both expose the same verbs, and send exactly what they should over SSH (asserted against
  stubs, in both bash and PowerShell);
- one session-name rule in every place that enforces one;
- the generated files are in sync (`gen-help.js`, `gen-state-table.js` and
  `gen-local-manifest.js`, each with `--check`; the daemon suite runs
  `gen-device-policy.js --check` and `gen-state-table.js --check` too), everything the
  clients print is 7-bit, both help screens fit 80 columns;
- the one-line installers, the desktop install and uninstall paths, the core hand-off, the
  Node device runner, the local AI tier;
- the project verbs end to end against a real daemon started from the tree.

It also runs `scripts/test-device-tls.js`, `scripts/test-local-tier.js` and
`scripts/test-claude-title.sh`. Each of those runs on its own too:

```bash
node --test scripts/test-device-tls.js     # the Node runner's half of the TLS contract
node --test scripts/test-local-tier.js     # the local AI tier's persistence logic
node --test scripts/test-llm-shim.js       # the local model shim speaks Claude Code's stream format
scripts/test-claude-title.sh               # the title hook's nested-claude guard
```

What it needs:

| Missing | What happens |
|---|---|
| `pwsh` (PowerShell 7) | Every PowerShell check prints `SKIP ... <-- not a pass`. Install PowerShell 7 to run the other half of the gate |
| util-linux `script` | The checks that need a terminal skip |
| A readable `/etc/huginn-appd/token` | The token-leak checks and the real-daemon project lane skip. They run on a Huginn Server |
| An installed server | The last lane compares the tree with installed copies of the clients and host tools, and skips each file that is not installed |

Run `shellcheck` on any bash you changed; CI runs it in advisory mode over every shell file it
finds, after a `bash -n` syntax check of each file on its own.

## Go tests

```bash
cd core
go vet ./... && go test ./...
```

- `core/internal/appd` and `core/internal/notify` start a real daemon from the tree when
  `node` (and, for `notify`, `tmux`) is installed, and skip those tests when it is not.
  `go test -short ./...` skips the `notify` one on purpose.
- `TestEmbeddedMatchesClient` in `core/internal/clientkit` fails when the embedded shell
  client differs from `client/`. Refresh the copies (see
  [Go core](go-core.md#the-embedded-shell-client)).
- `core/internal/policy` holds the Go table to `shared/device-policy-cases.json`.

### Golden files

The console's screens and the service files are compared with golden files in `testdata/`.
After an intended change, rewrite them and review the diff before you commit:

```bash
cd core
go test ./internal/console -update
go test ./internal/service -update
git diff internal/console/testdata internal/service/testdata
```

A golden diff you did not expect is a bug, not a file to regenerate.

## End-to-end scripts

```bash
core/test/e2e.sh
core/test/e2e-migrate.sh
```

Each builds the binary from the tree, starts a real daemon from the tree on a free loopback
port with its own token, data, state and tmux socket, and runs huginn core under an isolated
`HOME` and `XDG_RUNTIME_DIR` with a stand-in `claude`. `e2e.sh` covers pairing, the socket's
modes, the `/appd/*` proxy (GET, streams, error pass-through, refusing non-`/v1/` paths), the
route book, the local tier's token seed, the device runner end to end, upgrades and
`huginn uninstall`. `e2e-migrate.sh` checks that a first start adopts an existing device row
instead of creating a second one.

They need `go`, `node`, `curl`, `python3` and `tmux`. Each prints `PASS` or `FAIL` per check,
and its exit status is the number of failed checks.

## The Kotlin suites

From `mobile/`:

```bash
./gradlew --rerun-tasks --no-build-cache \
  :core:jvmTest :core:testDebugUnitTest :ui:jvmTest :app:testDebugUnitTest :app-desktop:test
```

`--rerun-tasks --no-build-cache` makes the tasks really run. Without them an unchanged module's
test task is up to date, Gradle skips it, and the result files you count afterwards are the
previous run's.

Count against the floors:

```bash
. ../scripts/test-floors.env
n() { grep -ho 'tests="[0-9]*"' "$1"/*.xml 2>/dev/null | grep -oE '[0-9]+' | awk '{s+=$1} END {print s+0}'; }
shared=$(( $(n core/build/test-results/jvmTest) + $(n core/build/test-results/testDebugUnitTest) \
         + $(n app/build/test-results/testDebugUnitTest) + $(n ui/build/test-results/jvmTest) ))
desktop=$(n app-desktop/build/test-results/test)
echo "shared $shared (floor $KOTLIN_SHARED_MIN)  desktop $desktop (floor $DESKTOP_EXTRA_MIN)"
```

Every one of the five result directories must hold at least one test. A directory with none
means a suite stopped being discovered.

`scripts/build.sh debug` runs the four shared suites with the same flags, checks the shared
floor, runs the daemon suite with its floor, and builds a debug APK. It does not run
`:app-desktop:test`; run that yourself when you touch the desktop app.

Kotlin test rules:

- **`kotlin.test` takes the message last.** `:core`, `:ui` and `:app-desktop` use
  `kotlin.test`, whose `assertEquals` is `(expected, actual, message)`. `:app` uses JUnit 4,
  whose `assertEquals` is `(message, expected, actual)`. With three `String` arguments a wrong
  order compiles and asserts the wrong thing. Convert assertions by hand when you move a test,
  never with `sed`.
- **Keep `:app-desktop` on Gradle's default JUnit 4 runner.** `useJUnitPlatform()` leaves the
  JUnit 5 engine off the classpath, and the suite is then discovered as zero tests and exits 0.
- **Most shared tests run twice**, once per target of `:core`. `:ui` runs on the JVM target
  only, because a real drawing surface needs a real bitmap.
- **Test inputs are captured, not invented**: verbatim `tmux capture-pane -e` output, the
  server-sent event frames the daemon emits, scrubbed daemon responses. A renamed daemon field
  fails `ApiContractTest` instead of showing an empty screen.

More on the Kotlin suites: [Kotlin clients](kotlin-clients.md#testing).

## When Claude Code updates

Huginn reads Claude Code's screen, hooks and transcript files. When Claude Code changes, run
the checklist in [Claude Code integration](claude-code-integration.md#fragility-checklist), and
record the Claude Code version you verified against in your pull request.
