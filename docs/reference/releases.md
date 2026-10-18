# Releases and compatibility

Huginn is released from [GitHub Releases](https://github.com/silencelen/huginn/releases). Each
component has its own tag prefix and its own version number.

## Components

| Tag | Component | Release assets |
|---|---|---|
| `cli-v<version>` | The `huginn` command and huginn core | `huginn-<version>-linux-x64`, `huginn-<version>-linux-arm64`, `huginn-<version>-windows-x64.exe`, `huginn-<version>-android-arm64` (Termux), `core-manifest.json`, `SHA256SUMS` |
| `desktop-v<version>` | The desktop app | `Huginn-Desktop-Setup-<version>.exe` (Windows installer), `huginn-desktop-kt_<version>-1_amd64.deb` (Debian, Ubuntu), `huginn-desktop-kt-<version>-1-x86_64.pkg.tar.zst` (Arch), `huginn-desktop-kt-<version>-linux-x64.tar.gz` (portable Linux), `manifest.json` |
| `app-v<version>` | The Android app | `Huginn-<version>.apk`, `latest.json` |
| `appd-v<version>` | The server daemon, `huginn-appd` | None: the server installs from the tagged source |

The manifests list each file with its size and SHA-256 hash; `SHA256SUMS` lists the core binaries
in the format `sha256sum -c` reads. See [Verify downloads](../install/verify.md).

The `huginn` command itself (`huginn.sh` on Linux and Termux, `huginn.ps1` on Windows) is part of
the tagged source rather than a release asset. The client installer downloads it from the newest
`cli-v` tag.

## Which versions move together

- **The `huginn` command and huginn core** share one version, released together under `cli-v`.
- **The desktop app** uses the same version numbers. A `desktop-v` release carries the huginn core
  of the same version, and its installer installs that core alongside the app. Not every `cli-v`
  release has a matching desktop release: when only the command or the core changed, there is
  none.
- **The Android app** (`app-v`) and **the daemon** (`appd-v`) have version numbers of their own.

To see what you run:

```bash
huginn version                     # huginn core, and its local protocol version
cat /usr/local/src/huginn/.huginn-ref        # on the server: the release it was installed from
curl -s -H "Authorization: Bearer $(cat /etc/huginn-appd/token)" \
  http://127.0.0.1:8787/v1/ping      # on the server: the daemon's version
```

The desktop app shows its version under Settings → Updates & diagnostics, and the Android app
under Settings → Software update.

## How each part updates

| Part | How to update | What it checks |
|---|---|---|
| Server | Run the install command again: `curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/server/get.sh \| sudo bash`. It installs the newest `cli-v` or `appd-v` release, keeps your token, certificate, data and settings, and does not interrupt sessions. | The source comes over HTTPS from GitHub. |
| `huginn` command, huginn core, desktop app, local AI | `huginn update` on each computer. It replaces the `huginn` command, then updates huginn core and the desktop app where they are installed. Local AI is updated too on Linux when you run it in a terminal; on Windows it tells you to run `huginn local update`. | The core and the desktop app are installed only if their SHA-256 matches the release manifest. The command is syntax-checked before it replaces the old one. |
| huginn core alone | `huginn core update` | As above. |
| Desktop app alone | `huginn desktop update`. For a `.deb` or Arch package it hands the verified package to `apt` or `pacman` with `sudo`. The Windows and portable Linux installs also update from the app itself: it checks at launch and every four hours, downloads and verifies the update, and installs it when you restart the app. | SHA-256 from the release manifest. |
| Local AI | `huginn local update` (asks before it changes anything) | Runtime versions are pinned in the `huginn` release. |
| Android app | Settings → Software update: check, download and install are three separate steps. | SHA-256 from the release's `latest.json`, then Android's own check that the update is signed with the same key as the installed app. |


Running sessions on the server keep running through every update. Claude Code itself is not
updated by any of these; see [Security](../security.md#hardening-checklist).

## Compatibility

There is no published compatibility matrix. **Keep the server and every client on current
releases**, and when you update, update the server first.

What the code does guarantee:

- **Clients need `huginn-appd` 3.11 or later**, the first daemon with TLS. Against an older daemon
  they stop and say "the daemon is older than 3.11 and has no TLS; update it". They never fall
  back to plain HTTP.
- **An older daemon** is otherwise tolerated: features it does not have yet are hidden in the
  clients rather than shown as errors.
- **A newer daemon** keeps renamed API fields under their old names for one release, so clients
  one release behind keep working.
- **The desktop app and huginn core** on the same computer must speak the same local protocol
  (`huginn version` prints it). On a mismatch, the core answers with an error that says to run
  `huginn update`.
