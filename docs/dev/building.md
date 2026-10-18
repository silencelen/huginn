# Building

How to build each component on your own Linux machine for testing. Nothing on this page
publishes anything.

## Toolchains

| Component | You need |
|---|---|
| Daemon (`server/appd/`) | Node.js 22. No npm packages. For the test suite: `tmux`, `openssl`; `python3` for a few tests that skip without it |
| Shell clients (`client/`) | `bash` and Node.js 22 (the generators). For the client gate: PowerShell 7 (`pwsh`), `python3`, `tmux`, util-linux `script`; `shellcheck` is recommended |
| huginn core (`core/`) | Go 1.26 or later (`core/go.mod` says `go 1.26.0`). No C compiler: every build is `CGO_ENABLED=0`. For the end-to-end tests: `node`, `curl`, `python3`, `tmux` |
| Kotlin apps (`mobile/`) | JDK 17 (a full JDK, not a headless JRE), the Android SDK with the platform for API level 37, and the Gradle wrapper in `mobile/` (it downloads Gradle itself) |
| Linux desktop packages | `dpkg-deb` for the `.deb`; `tar`, `gzip`, `bsdtar` (`libarchive-tools`), `zstd` and `xvfb-run` for the portable tarball and the Arch package |

## The daemon

There is nothing to compile. Check that the daemon parses:

```bash
node --check server/appd/huginn-appd.js
```

### Run a daemon from the tree

The end-to-end tests run the daemon straight from the checkout with every path moved into a
scratch directory, so it never touches a real server's token, tmux server or state. You can do
the same:

```bash
export T="$(mktemp -d)"
mkdir -p "$T/data" "$T/state" "$T/claude" "$T/home"
head -c 32 /dev/urandom | od -An -tx1 | tr -d ' \n' > "$T/token"
printf 'Authorization: Bearer %s\n' "$(cat "$T/token")" > "$T/auth"
chmod 600 "$T/token" "$T/auth"

env $(env | grep -o '^HUGINN_APPD_[A-Z_]*' | sed 's/^/-u /') \
  HOME="$T/home" \
  HUGINN_APPD_PORT=18787 HUGINN_APPD_BIND=127.0.0.1 \
  HUGINN_APPD_DATA="$T/data" HUGINN_APPD_TOKEN_FILE="$T/token" \
  HUGINN_APPD_STATE_DIR="$T/state" HUGINN_APPD_CLAUDE_DIR="$T/claude" \
  HUGINN_APPD_TMUX_SOCKET=huginn-dev HUGINN_APPD_WORKDIR="$T" \
  HUGINN_FCM_KEY="$T/no-fcm-key.json" \
  node server/appd/huginn-appd.js
```

In another terminal (with the same `T`):

```bash
curl -s -H @"$T/auth" http://127.0.0.1:18787/v1/status
curl -sk -H @"$T/auth" https://127.0.0.1:18787/v1/ping
```

- The first `env` clears any `HUGINN_APPD_*` variable your shell already has.
- `HOME`, the data, state and Claude directories point at scratch, so the daemon reads no real
  login, account or session state. `HUGINN_FCM_KEY` names a file that does not exist, so it
  never sends push.
- `HUGINN_APPD_TMUX_SOCKET` puts the daemon's sessions on a private tmux socket
  (`tmux -L huginn-dev ls`), never your own.
- Plain HTTP works because the request comes from loopback. HTTPS uses the certificate the
  daemon minted into `$T/data/tls/` on start.
- `curl -H @file` reads the header from a file, which keeps the token off the command line,
  where other users could read it in the process list.

Every variable is described in [Server configuration](../reference/server-config.md).

### Install your daemon on a test server

On a server you can break, from your checkout:

```bash
sudo bash server/appd/install.sh     # the daemon only: files, unit, hooks, restart, health check
sudo bash server/setup.sh            # the whole server, the same way a new one is installed
```

`install.sh` copies the daemon to `/opt/huginn-appd`, keeps the existing token, certificate,
data and drop-ins, restarts `huginn-appd.service` and checks `/v1/ping` over HTTP and HTTPS on
loopback. Run the test suite first (see [Testing](testing.md)).

## The shell clients

There is nothing to build. To try a change, load the client from the tree into a shell and
point it at a test server:

```bash
source client/huginn.sh
HUGINN_HOST=my-test-server huginn ls
```

```powershell
. ./client/huginn.ps1
$env:HUGINN_HOST = 'my-test-server'; huginn ls
```

`HUGINN_HOST` names an SSH host or alias (default `huginn`).

Some files are partly generated. Edit the source, run the generator, and commit both:

| Edit | Then run | It writes |
|---|---|---|
| `client/help-table.txt` | `node scripts/gen-help.js` | The help body inside `client/huginn.sh` and `client/huginn.ps1` |
| `shared/device-policy.json` | `node scripts/gen-device-policy.js` | `shared/device-policy-cases.json`, the policy block in `client/huginn-device`, `mobile/core/.../device/DevicePolicyTable.kt`, `mobile/core/.../DevicePolicyCasesTest.kt`, `server/appd/lib/device-policy-table.js`, `core/internal/policy/table_gen.go` |
| `server/lib/state-table.json` | `node scripts/gen-state-table.js` | `server/lib/state-table.{sh,py,js}`, `server/appd/lib/state-table.js` |
| `shared/local-runtime.json` | `node scripts/gen-local-manifest.js` | The pins block inside `client/huginn-local` |

Each generator takes `--check`, which changes nothing and fails if a generated file is stale.

If you changed `client/huginn.sh`, `huginn.ps1`, `huginn-local` or `huginn-llm-shim`, also
refresh the copy huginn core embeds (see [Go core](go-core.md#the-embedded-shell-client)):

```bash
for f in huginn.sh huginn.ps1 huginn-local huginn-llm-shim; do
  tr -d '\r' < "client/$f" > "core/internal/clientkit/files/$f.txt"
done
```

## huginn core

Build the binary for this machine:

```bash
cd core
go build -o /tmp/huginn-dev ./cmd/huginn
```

A plain build reports version `2.0.0-dev`. To give it a version, set it at link time:

```bash
go build -ldflags "-X github.com/silencelen/huginn/core/internal/version.Version=2.5.8-dev" \
  -o /tmp/huginn-dev ./cmd/huginn
```

Cross-compile one target:

```bash
CGO_ENABLED=0 GOOS=windows GOARCH=amd64 go build -o /tmp/huginn-dev.exe ./cmd/huginn
```

Build every shipped target the reproducible way:

```bash
core/build.sh 2.5.8-dev            # writes core/dist/2.5.8-dev/ (git-ignored)
```

`core/build.sh <version> [outdir]` builds `linux-x64`, `linux-arm64`, `windows-x64` and
`android-arm64` (Termux) with `CGO_ENABLED=0`, `-trimpath` and an empty build id, so two builds
of one commit are byte-identical. It writes `core-manifest.json` and `SHA256SUMS` beside the
binaries, which needs `python3` and `sha256sum`. It refuses to build when:

- the version is not `x.y.z` or `x.y.z-<suffix>`;
- a file in `core/internal/clientkit/files/` differs from its source in `client/` (it prints
  the command that fixes it);
- the version has no suffix and `client/huginn.sh` carries a different `HUGINN_VERSION`. Use a
  suffix such as `-dev` for local builds.

The Windows binary from a local build is unsigned, and the script says so; that does not stop
the build.

To run a core you built, isolated from your own install, or to install it over your own, see
[Go core](go-core.md#running-a-core-from-the-tree).

## The Kotlin apps

### One-time setup

1. Install JDK 17 and point `JAVA_HOME` at it.
2. Install the Android SDK command-line tools, then the platform and accept the licences:

   ```bash
   sdkmanager "platforms;android-37.0" "platform-tools"
   sdkmanager --licenses
   ```


3. Tell Gradle where the SDK is, either with `ANDROID_HOME` or with
   `mobile/local.properties` (git-ignored):

   ```ini
   sdk.dir=/path/to/android-sdk
   ```

   Every module needs the SDK, including the desktop app, because `:core` and `:ui` build an
   Android target too.

4. Give the Android app a Firebase config. `mobile/app/google-services.json` is git-ignored
   and the build fails without it. Copy the template:

   ```bash
   cp mobile/app/google-services.json.example mobile/app/google-services.json
   ```

   With the template's placeholder values the app builds and works, without push
   notifications. For instant push from your own server, fill the file from your own
   Firebase project (an Android app registered for package `com.silencelen.huginn`) and put
   that project's service-account key on the server; see
   [Notifications](../guide/notifications.md).

`mobile/gradle.properties` caps Gradle at two workers and a 2.5 GB heap, which suits an 8 GB
machine. Raise them locally if you have more memory.

### Build an APK

```bash
cd mobile
./gradlew :app:assembleDebug
```

The debug build is signed with your own debug key when `~/.huginn-app/debug-keystore.properties`
exists (or the file `HUGINN_DEBUG_KEYSTORE_PROPERTIES` names), holding the same four keys as the
release file below. Copy that key to every machine you build on and their debug builds update
each other. Without it, Android's per-machine default debug key signs, and a debug build updates
only debug builds from the same machine. No key is checked in: one in a public repository would
let anyone sign an update over a debug install. The build copies the APK into `mobile/dist/` as
`Huginn-debug-<versionCode>-<timestamp>.apk`.

`./gradlew :app:assembleRelease` signs with your own key when
`~/.huginn-app/keystore.properties` exists (or the file `HUGINN_KEYSTORE_PROPERTIES` names). It
holds `storeFile`, `storePassword`, `keyAlias` and `keyPassword`. Without that file the release
APK is built unsigned and cannot be installed.

`scripts/build.sh` in `mobile/` is the gate around the same build: it runs the Kotlin and
daemon test suites, asserts the test counts, then builds. It uses `JAVA_HOME` and
`ANDROID_HOME` when they are set (otherwise `/usr/lib/jvm/java-17-openjdk-amd64` and
`/opt/android-sdk`), and it needs `node` on `PATH`: without it the daemon suite is skipped.

```bash
cd mobile
scripts/build.sh debug
```

`scripts/build.sh release` refuses an unsigned APK. Both variants rewrite a small manifest in
`mobile/dist/` (`latest-debug.json` or `latest.json`); leave it out of your commit.

| Gradle property or variable | Effect |
|---|---|
| `-Phuginn.updateRepo=<owner>/<repo>` | Where the app's update check looks for releases on GitHub. An APK signed with your key cannot install the official APKs, so point it at your own fork |
| `-Phuginn.updateRepo=` | An empty value turns update checks off |
| `HUGINN_VERSIONCODE=<n>` | Pins the `versionCode`. By default it is the build time in seconds since a fixed epoch (see `mobile/app/build.gradle.kts`), so every build is newer than the last |
| `HUGINN_KEYSTORE_PROPERTIES=<path>` | Another location for the release signing properties |

```bash
./gradlew :app:assembleRelease -Phuginn.updateRepo=you/huginn
```

The app is built for `arm64-v8a` only and needs Android 10 (API 29) or later.

### Build and run the desktop app

```bash
cd mobile
./gradlew :app-desktop:run
```

The desktop app needs huginn core running and paired on the same machine; without one it
shows only the screen that sets up huginn core. See
[Kotlin clients](kotlin-clients.md#running-the-desktop-app-from-the-tree).

Its version is `mobile/app-desktop/version.txt` and nothing else.

Linux packages:

```bash
cd mobile
./gradlew :app-desktop:packageDeb :app-desktop:createDistributable
```

| Output | Path under `mobile/app-desktop/build/compose/binaries/main/` |
|---|---|
| Debian package | `deb/huginn-desktop-kt_<version>-1_amd64.deb` |
| App image | `app/huginn-desktop-kt/` |

Add `-PcoreBinary=<path to a linux-x64 huginn binary>` to bundle a core in the package, the
way installed packages carry one. Without it the package bundles nothing and the app uses the
core already installed.

The portable tarball and the Arch package are built from the app image and the `.deb`:

```bash
cd mobile
V="$(cat app-desktop/version.txt)"
B=app-desktop/build/compose/binaries/main
scripts/package-linux.sh "$B/app/huginn-desktop-kt" "$B/deb/huginn-desktop-kt_${V}-1_amd64.deb" \
  "$V" build/linux-out
```

`package-linux.sh <app-image-dir> <deb> <version> <outdir> [--no-launch]` reads each artifact
back and, unless you pass `--no-launch`, starts the unpacked tarball under `xvfb-run` to prove
it launches.

### The Windows installer

The Windows installer is built on Linux, with no Windows machine: the Windows `jpackage.exe`
runs under wine against a Windows runtime image, and `makensis` wraps the result. That build is
part of the release tooling and has no build-only mode, so it is not covered here.

If you work on the Windows packaging, `./gradlew :app-desktop:windowsAppLibs` stages the
Windows jar set into `mobile/app-desktop/build/windows/lib/` and checks that it carries the
Windows renderer and no Linux one. The packaging rules are in
[Kotlin clients](kotlin-clients.md#compose-desktop-and-packaging-rules).
