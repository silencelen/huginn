# huginn core

One static Go binary, `huginn`, installed per user. It is the client's backbone: it holds the
pairing with the server (address, token, proved certificate pins), starts at boot with nobody
signed in, serves a local socket the console and the desktop app talk to, draws the tray and
notifications, and runs device work when the person allows it. Updates swap the binary and
restart the service; a core in the middle of a device job restarts only after that job.

## Lifecycle

| | Linux | Windows |
|---|---|---|
| install | `huginn core install` writes the binary, swaps `~/.local/share/huginn/current` atomically, points `~/.local/bin/huginn` at it, writes `huginn-core.service` (user unit, `Restart=always`), runs `loginctl enable-linger`, and writes the tray's autostart entry when there is a graphical session | installs `%LOCALAPPDATA%\huginn\bin\huginn.exe`, puts that directory on the user `PATH`, registers the "Huginn Core" task (boot, logon, every 5 min; S4U so no password is stored) and the "Huginn Tray" task + Startup shortcut; one administrator prompt |
| is it running | `huginn core status`; `systemctl --user status huginn-core.service` | `huginn core status`; `schtasks /Query /TN "Huginn Core"` |
| start | `huginn core start` (`systemctl --user start huginn-core.service`) | `huginn core start` (runs the task; starts directly when Task Scheduler holds it back on battery) |
| logs | `journalctl --user -u huginn-core.service -n 100` | the task's history in Task Scheduler; `huginn core status` |
| update | `huginn core update` (or `huginn update`): downloads the release, checks its SHA-256, runs `core self-install`, which lays the CLI and the skills down too and restarts the running core | the same; a running `huginn.exe` is renamed aside |
| stop at boot | `huginn core uninstall` (keeps the binary and the pairing) | the same |
| remove | `huginn uninstall` | the same |

`huginn core self-install` is the one step every update path ends in: it installs this copy of
the binary, the shell client it carries (`~/.huginn/huginn.sh` or `.ps1`) and the Claude Code
skills it carries (`<install dir>/skills/`), refreshing the skills already in
`~/.claude/skills`.

## Pairing, routes and pins

- **Pair**: `huginn core pair --url https://192.0.2.10:8787`. The token comes from stdin,
  `HUGINN_APPD_TOKEN`, or a hidden prompt; never argv. The core proves the server with the token
  before keeping anything: it fetches `/v1/challenge` and checks an HMAC of the certificate's
  fingerprint. The fingerprint is pinned under a tag of the token in `core.json`
  (`pins`), and every later request goes only over a connection whose certificate is pinned.
  `http://` is upgraded to `https://`; there is no way to send the token in the clear to a remote
  host.
- **Routes** (`huginn core route`): the same server's other addresses (a LAN address at home, a
  mesh or tailnet address away). The core switches when a request never reaches the server
  (refused, timed out, wrong certificate); it never retries a request that left. `pin <url>`
  fixes one address, `auto off` stops switching, `add`/`remove` edit the book. Every address is
  judged by the same pin.
- **Not paired** means an address or a token is missing; `huginn core status` says which.

## The socket

HTTP/1.1 over a Unix socket (`$XDG_RUNTIME_DIR/huginn/core.sock`, or
`%LOCALAPPDATA%\huginn\run\core.sock`), peer-checked (the connecting process must be the same
user), every request carrying `X-Huginn-Core-Proto: 1`. There is no TCP listener.

```bash
curl -s --unix-socket "$XDG_RUNTIME_DIR/huginn/core.sock" -H 'X-Huginn-Core-Proto: 1' \
  http://huginn-core/core/status
```

Routes: `GET /core/status`, `GET /core/routes`, `GET /core/device`, `GET /core/events` (SSE),
`POST /core/pair`, `POST /core/unpair`, `POST /core/restart`, and `/appd/v1/...`, a transparent
proxy to the server's API with the token added by the core (so `GET /appd/v1/status` is the
daemon's status). Only read with `GET` unless the person asks for a change.

## Files

| File | Holds |
|---|---|
| `core.json` | schema `v`, `device` (enabled, id, name, scope, root, actWhileLocked, claude path), `appd` (`url`, `routes`, `pinned`, `noAutoSwitch`), `pins` (tag -> fingerprints), `secretsBackend`, `installId`. Never the token. |
| `secrets/appd-token` | The token, 0600 (file backend). |
| state dir | `tray-autostart`, `linger-enabled`, `skills-declined`: markers that say what the installer did or the person declined, so updates do not redo it. |
| `huginn uninstall` log | `/tmp/huginn-uninstall-<stamp>-*.log` (or `%TEMP%`): every path it removed. |

`HUGINN_CONFIG_DIR` moves only the config directory (a second profile); `HUGINN_SECRETS=keyring`
uses the OS keyring instead of the file; both must be in the service's environment too.
