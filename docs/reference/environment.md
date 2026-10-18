# Environment variables

The client-side variables Huginn reads: in the `huginn` command (bash and PowerShell), in the
installers, and in huginn core. Server settings live elsewhere; see
[Server configuration](server-config.md).

Set a variable for every new shell in your profile:

```bash
export HUGINN_CONSOLE=0                 # bash: ~/.bashrc (zsh: ~/.zshrc)
```

```powershell
$env:HUGINN_CONSOLE = '0'               # PowerShell: $PROFILE
```

"Set" below means set to any non-empty value, unless a value is given.

In the **Where** column: **bash** is the Linux and Termux client, **PowerShell** the Windows
client, **core** is huginn core (including the console), **installers** are the one-line
installers (`get.sh`, `get.ps1`) and the scripts they run.

## Everyday

| Variable | Default | Effect | Where |
|---|---|---|---|
| `HUGINN_HOST` | `huginn` | The SSH host the command drives: a `Host` alias from `~/.ssh/config`, or any host name `ssh` accepts. Every session verb, the server-side verbs and the console's attach go there. `huginn setup ssh` writes its entry under this alias name. In the Windows installer (`get.ps1`) it instead presets the server's address; clear it after installing (`Remove-Item Env:HUGINN_HOST`), or the client connects to that address directly instead of through the `Host huginn` entry and its user and key. | bash, PowerShell, core, installers |
| `HUGINN_CONSOLE` | console on | `0`: plain `huginn` attaches to the `main` session instead of opening [the console](../guide/console.md). | bash, PowerShell, core |
| `HUGINN_NO_RECONNECT` | unset | Set: do not reconnect automatically when the SSH link to a session drops. | bash, PowerShell, core |
| `HUGINN_NO_TITLE` | unset | Set: do not rename the terminal tab after the session you attach to (and do not save and restore its title). | bash, PowerShell, core |
| `NO_COLOR` | unset | Set: no colour in any output (see [no-color.org](https://no-color.org)). Colour is also left out whenever the output is not a terminal. In the console, the selected row shows in reverse video instead. | bash, PowerShell, core, installers |
| `HUGINN_ASCII` | automatic | `1`: 7-bit art and marks (`* ! o - + x`). `0`: Unicode. Unset: on Linux, Unicode on a UTF-8 locale and 7-bit otherwise; on Windows, Unicode in Windows Terminal and 7-bit in the classic console window (the PowerShell client also uses Unicode in a console set to UTF-8). | bash, PowerShell, core, installers |
| `HUGINN_REDUCED_MOTION` | unset | Set: a still frame instead of the animated spinner in `huginn update`, `huginn core install` and the installers. (The console does not animate.) | bash, PowerShell, core |
| `HUGINN_USER` | `root` | The SSH user on the server, written into the `Host huginn` entry by the Linux client installer and by `huginn setup ssh`. (The Windows installer takes `-User` instead.) | installers (Linux), core |

## Installer presets

Answers to the installer's questions, for unattended installs. With no terminal to ask on, an
unanswered question is answered no. See [Install the clients](../install/clients.md).

| Variable | Default | Effect | Where |
|---|---|---|---|
| `HUGINN_GUI` | asks (default yes) | `y` or `n`: install the desktop app. On Linux the question is only asked in a graphical session, unless this is set. | installers |
| `HUGINN_DEVICE` | asks (default yes) | `y` or `n`: offer this computer to Huginn as a device. | installers |
| `HUGINN_LOCAL` | asks (default no) | `y` or `n`: serve local AI models from this computer. Only asked on a computer with a suitable GPU. | installers |
| `HUGINN_APPD_URL` | worked out from the server address you give | The daemon's address to pair huginn core with, for example `https://192.168.1.20:8787`. The installers derive it from the address you type (`https://` and port `8787` when you give neither); a preset value is used when the address you type has no scheme or port. `huginn core install` also uses it to pair with a pasted token when SSH does not answer. | installers, bash, PowerShell |

The server's address itself is the argument on Linux (`sh -s -- <server>`) and `HUGINN_HOST` on
Windows (in a session you then close, or clear it afterwards; see `HUGINN_HOST` above).

## Updates

| Variable | Default | Effect | Where |
|---|---|---|---|
| `HUGINN_UPDATE_HOST` | unset | An SSH host to fetch client updates from instead of GitHub. | bash, PowerShell |
| `HUGINN_CORE_FILE` | unset | Path to a huginn core binary on this computer. `huginn core install` and `huginn core update` install it instead of downloading a release, for offline installs. | bash, PowerShell |

## huginn core (advanced)

| Variable | Default | Effect | Where |
|---|---|---|---|
| `HUGINN_APPD_TOKEN` | unset | The access token for `huginn core pair`, instead of a prompt or standard input. The token is root-equivalent on the server: do not leave it set in your environment. | core |
| `HUGINN_SECRETS` | `file` | `file` or `keyring`: where the huginn core service keeps the access token. The file store (in huginn core's config directory, readable only by you) is the default because a desktop keyring is usually still locked when the service starts at boot. Changing it does not move a stored token: pair again afterwards. It must be in the service's own environment, so most installs leave it alone. | core |
| `HUGINN_CONFIG_DIR` | `~/.config/huginn` (Linux), `%APPDATA%\huginn` (Windows) | Replaces huginn core's config directory (pairing, settings, file secrets), for example for a second profile on one account. State and runtime directories do not move. The service and your commands must see the same value. | core |
| `HUGINN_APPD_PLAINTEXT` | unset | `local`: send the access token over plain HTTP to a daemon on this computer (`http://127.0.0.1:…`), with nothing checking what answers on that port. Only for a daemon older than 3.11, which has no TLS. Unset, huginn core, the device runner and the desktop app reach a daemon on this computer over HTTPS and check that it is yours first, as they do anywhere else. It never applies to another computer, and the Android app ignores it. It must be in the environment of the process that connects (huginn core's service, the device runner's unit). | core, device runner, desktop app |

## Local AI and computers without huginn core

| Variable | Default | Effect | Where |
|---|---|---|---|
| `HUGINN_LOCAL_DIR` | `~/.config/huginn-local` (Linux), `%ProgramData%\huginn-local` (Windows) | Where the local AI tier installs its models, runtime and settings. `huginn uninstall` removes it from there. | bash, PowerShell, core |
| `HUGINN_LOCAL_REFRESH` | unset | Set: fetch the local AI manager again on the next `huginn local on`. | bash |
| `HUGINN_DEVICE_DIR` | `~/.config/huginn` | Where the Node.js device runner (used on a computer without huginn core) keeps its settings and its copy of the access token. huginn core reads it once, on its first start, to take over an existing runner's enrolment. | bash, PowerShell, core |
| `HUGINN_DEVICE_REFRESH` | unset | Set: fetch the Node.js device runner again on the next `huginn device on`. | bash |

## Read on the server

These are not client variables, but the client relies on them being set on the server:

| Variable | Default | Effect |
|---|---|---|
| `HUGINN_WORKDIR` | the SSH user's home | Where sessions created from a terminal start, and where `huginn -p` and `huginn -y` run. Read from the environment of the SSH login on the server, not from your computer. Sessions created from the apps start in the daemon's `HUGINN_APPD_WORKDIR`; see [Server configuration](server-config.md). |

## Internal: do not set

The client sets these itself, when one command runs another, or they exist for the project's own
tests. Setting them by hand changes behaviour in ways that are not supported.

`HUGINN_SETUP`, `HUGINN_CORE_NOPAIR`, `HUGINN_VIA_CORE`, `HUGINN_CLIENT_SCRIPT`,
`HUGINN_CLIENT_ARGS`, `HUGINN_UI_COMPACT`, `HUGINN_UI_FROM`, `HUGINN_APPD_URL_FILE`,
`HUGINN_TRAY_RETRY`, `HUGINN_DEVICE_BIN`, `HUGINN_DEVICE_MANAGED`, `HUGINN_LOCAL_USER`,
`HUGINN_SHIM_SOURCE`, `HUGINN_SHIM_WATCHDOG_MS`, `HUGINN_SYSTEMD_SYSTEM_DIR`, `HUGINN_OS_RELEASE`.
