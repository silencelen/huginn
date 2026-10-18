# Server configuration

Where Huginn Server keeps its files, every setting the daemon reads, and how to change them. Run
the commands on this page as root on the server.

## Files and locations

| Path | What it is | On update |
|---|---|---|
| `/usr/local/src/huginn/` | The release the installer ran from. `.huginn-ref` names it. | Replaced |
| `/opt/huginn-appd/` | The daemon: `huginn-appd.js`, `lib/`, `hooks/`, `install-hooks.js`. | Replaced |
| `/etc/huginn-appd/token` | The access token (mode 0600). Root-equivalent; see [Security](../security.md). | Kept |
| `/etc/huginn-appd/fcm-service-account.json` | Optional Firebase service-account key for instant Android push. | Yours |
| `/var/lib/huginn-appd/` | The daemon's data (mode 0700). See the table below. | Kept |
| `/etc/systemd/system/huginn-appd.service` | The daemon's systemd unit. | Replaced |
| `/etc/systemd/system/huginn-appd.service.d/hardening.conf` | The daemon's sandbox (kernel protections). | Replaced |
| `/etc/systemd/system/huginn-appd.service.d/site.conf` | **Your settings.** Written once at install. | Never touched |
| `/usr/local/bin/` | Host tools: `cc`, `huginn-status`, `huginn-rounds`, `huginn-devices`, `huginn-headroom`, `huginn-archive`, `huginn-projects`, `huginn-claude-title`, `huginn-llm`, `huginn-ask`. | Replaced, except copies edited here and newer than the release (`HUGINN_FORCE=1` replaces those too) |
| `/usr/local/lib/huginn/` | The state table the host tools share. | Replaced |
| `/root/.tmux.conf` | tmux configuration. A different existing file is saved as `/root/.tmux.conf.pre-huginn` first. | Replaced |
| `/root/.claude/settings.json` | Claude Code settings. The installer merges Huginn's hooks into `hooks` and leaves everything else alone. | Hooks re-merged |
| `/run/huginn-claude-state/` | Per-session state written by the hooks (memory only, cleared at boot). | |
| `/etc/huginn/persona.md` | Optional text appended to the system prompt of chats, rounds and `huginn -p`/`-y`. Read on every run; no restart needed. | Yours |

Inside `/var/lib/huginn-appd/`:

| Path | What it holds |
|---|---|
| `tls/cert.pem`, `tls/key.pem` | The daemon's self-signed certificate and key. |
| `uploads/` | Attachments from the apps. Images are kept; other files are pruned after `HUGINN_APPD_UPLOAD_KEEP_DAYS`. |
| `chats/` | Chat transcripts. |
| `accounts/` | Saved Claude logins, with their OAuth credentials. |
| `archive/` | Archived sessions and copies of their transcripts. |
| `rounds/`, `projects/`, `scratchpads/`, `session-meta/` | Rounds and their reports, projects, scratchpad pages, per-session notes. |
| `headroom/` and `*.json` | Usage state, settings, registered devices and phones. |

Back up `/var/lib/huginn-appd` and `/etc/huginn-appd` together, and protect the backup: it holds
the token and every saved login.

## Change a setting

The daemon reads its settings from environment variables set in systemd drop-ins. Put yours in
`site.conf`: installs and updates replace the unit and `hardening.conf`, and never touch any other
drop-in.

1. Edit `/etc/systemd/system/huginn-appd.service.d/site.conf`. Each setting is one
   `Environment=` line under `[Service]`; quote the whole assignment when the value has spaces:

   ```ini
   [Service]
   Environment=HUGINN_APPD_WORKDIR=/root/projects
   Environment=HUGINN_APPD_TELEGRAM_SCRIPT=/root/bin/send-alert.sh
   Environment="HUGINN_APPD_EXTRA_TOOLS=mcp__memory mcp__github"
   ```

2. Reload systemd and restart the daemon:

   ```bash
   systemctl daemon-reload
   systemctl restart huginn-appd
   ```

   Restarting does not end sessions. A chat that was running when the daemon stopped is recorded
   as interrupted.

3. Check what the daemon sees, and that it can still list your sessions:

   ```bash
   systemctl show huginn-appd -p Environment
   curl -s -H "Authorization: Bearer $(cat /etc/huginn-appd/token)" http://127.0.0.1:8787/v1/sessions
   ```

   An empty session list while sessions are running means the daemon cannot see tmux, usually
   because a drop-in changed its sandbox (for example by adding `PrivateTmp=yes`).

### A stricter sandbox

`hardening.conf` keeps the daemon, and the chats and rounds it starts, from changing the kernel,
but leaves the file system writable, so an Act chat can do what a session can. To confine them
further, add a drop-in of your own, for example `huginn-appd.service.d/strict.conf`:

```ini
[Service]
ProtectSystem=strict
ReadWritePaths=/var/lib/huginn-appd /run /root /tmp
```

Act chats and rounds can then write only to the paths listed (add your working directory if it
is elsewhere; every listed path must exist, or systemd refuses to start the daemon). Sessions are
not affected: they run in their own unit, `huginn-tmux.service`.

## Daemon settings

| Variable | Default | What it does |
|---|---|---|
| `HUGINN_APPD_PORT` | `8787` | Listen port, for HTTPS and loopback HTTP alike. |
| `HUGINN_APPD_BIND` | `0.0.0.0` | Listen address. `0.0.0.0` (every interface) keeps loopback reachable. A single other address does not, and the host tools and the install check talk to `127.0.0.1`; restrict access with a firewall instead. |
| `HUGINN_APPD_DATA` | `/var/lib/huginn-appd` | Data directory. |
| `HUGINN_APPD_TOKEN_FILE` | `/etc/huginn-appd/token` | The access token file. Read once at startup; at least 32 characters, or the daemon refuses to start. |
| `HUGINN_APPD_PLAINTEXT` | `local` | Who may use plain HTTP. `local`: the server itself only; everyone else gets `426` and must use HTTPS. `any`: everyone, which sends the token across the network unencrypted. Leave it at `local`. |
| `HUGINN_APPD_TLS_CERT`, `HUGINN_APPD_TLS_KEY` | unset | Your own certificate and key, instead of the self-signed pair. Set both or neither. See [Use your own certificate](#use-your-own-certificate). |
| `HUGINN_APPD_WORKDIR` | root's home directory | Where chats run. Claude Code loads project settings and skills from here. The installer sets it from `HUGINN_WORKDIR`. |
| `HUGINN_APPD_PROJECTS_DIR` | `/root/projects` | Where a project with no folder of its own is created (`<dir>/<name>`). Trust this folder in Claude Code once and every project under it is trusted. |
| `HUGINN_APPD_SOFT_END_PHRASE` | `Finish outstanding items, commit your work, and prepare to end the session.` | What a soft end types into the session. |
| `HUGINN_APPD_SOFT_END_AUTO` | on | After a soft end, end the session once it settles. `0`: type the phrase only. |
| `HUGINN_APPD_UPLOAD_KEEP_DAYS` | `7` | Days to keep uploads that are not images. Images are never pruned; chat history shows them. |
| `HUGINN_APPD_EXTRA_TOOLS` | unset | Extra Claude Code tools for every chat and round, in both ask and act mode, and for `huginn -p`/`-y`. Space-separated, for example an MCP server's tools: `mcp__memory`. |
| `HUGINN_APPD_PERSONA_FILE` | `/etc/huginn/persona.md` | Text appended to the system prompt of chats, rounds and `huginn -p`/`-y`. No file, no persona. |
| `HUGINN_APPD_TELEGRAM_SCRIPT` | unset | Optional alert relay. The daemon runs `bash <script> --message <text> --source huginn-app`; the script delivers the text wherever you like and exits 0. |
| `HUGINN_FCM_KEY` | `/etc/huginn-appd/fcm-service-account.json` | Firebase service-account key for instant Android push. Works only with an app built against the same Firebase project. |
| `HUGINN_APPD_FIREWALL` | `generic` | How the hosted Apps page writes the firewall rules it suggests: `generic`, or `proxmox` for Proxmox VE guest-firewall syntax. |
| `HUGINN_APPD_FIREWALL_HOST`, `HUGINN_APPD_FIREWALL_FILE` | placeholders | With `proxmox`: the hypervisor and the firewall file the suggested rules name. |
| `HUGINN_API_KEY_FILE` | unset | Optional. A file holding an Anthropic API key; suggested replies are then generated through the API instead of from your Claude plan's usage. |
| `HUGINN_API_KEY_VAR` | unset | With `HUGINN_API_KEY_FILE`: read the key from this `NAME=value` line of an environment file instead of the whole file. |
| `HUGINN_API_MONTHLY_USD` | `5` | The most the API key may spend on suggested replies in a month. Past it, the daemon goes back to your Claude plan. |
| `HUGINN_CLAUDE_BIN` | `/usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe` | The Claude Code binary the model list is read from. Set it when Claude Code is installed somewhere other than npm's global directory. |
| `HUGINN_APPD_ARCHIVE_CAP` | 32 MiB | The largest transcript copy kept with an archived session, in bytes. |
| `HUGINN_APPD_STARTUP_GRACE_MS` | `60000` | How long messages to a new session wait for Claude Code's prompt to appear. Raise it on a slow server. |

Do not set `ANTHROPIC_API_KEY` in a drop-in to feed the suggested-replies key: every Claude Code
process the daemon starts would inherit it and bill the API instead of your subscription. Use
`HUGINN_API_KEY_FILE`.

### Do not set

These exist for the daemon's test suite. Some redirect requests that carry your Claude
credentials, so never copy them into a production drop-in. The three URL overrides are accepted
only for `https:` or loopback addresses, and the daemon logs a line at startup when one is in
force.

| Variable | Why it exists |
|---|---|
| `HUGINN_APPD_USAGE_URL`, `HUGINN_APPD_OAUTH_ACCOUNT_URL`, `HUGINN_APPD_OAUTH_TOKEN_URL` | Stub endpoints for plan usage, account identity and token refresh. They receive your access or refresh token. |
| `HUGINN_API_BASE` | Stub endpoint for the API key above. |
| `HUGINN_APPD_CLAUDE_DIR`, `HUGINN_CLAUDE_SETTINGS` | Move Claude Code's configuration directory and settings file away from the live ones. |
| `HUGINN_APPD_STATE_DIR`, `HUGINN_APPD_TMUX_SOCKET`, `HUGINN_APPD_CLAUDE_SCRATCH`, `HUGINN_HEADROOM_DIR` | Point the daemon at scratch state, a private tmux server, or a headroom directory the installed hooks do not watch. |
| `HUGINN_APPD_PLAN_TTL_MS`, `HUGINN_APPD_NATIVE_GRACE_MS`, `HUGINN_APPD_CONSENT_GRACE_MS`, `HUGINN_APPD_LEASE_MS`, `HUGINN_APPD_LIVE_KEYS_WINDOW_MS`, `HUGINN_APPD_ROUNDS_TICK_MS`, `HUGINN_APPD_ROUND_WRAPUP_S`, `HUGINN_APPD_IMAGE_SERVE_MAX`, `HUGINN_APPD_OAUTH_LOCK_*` | Shorten timers and limits so tests run in seconds. |

The headroom gate hook has its own settings (`HUGINN_HEADROOM_DIR`, `HUGINN_GATE_TIMEOUT`,
`HUGINN_GATE_POLL`, `HUGINN_GATE_STALE_S`). It runs inside Claude Code, not inside the daemon, so a
drop-in cannot set them.

## Use your own certificate

By default the daemon serves a self-signed certificate it created on first start. Clients pin it
through a proof bound to the token (see [Security](../security.md)), so they need no certificate
authority either way. A certificate from your own authority is useful when other tools, such as
your own scripts, should trust the daemon without pinning.

1. Put the certificate (leaf first, then any intermediates) and its private key where root can
   read them, for example `/etc/huginn-appd/tls/`.
2. Name both in `site.conf`:

   ```ini
   [Service]
   Environment=HUGINN_APPD_TLS_CERT=/etc/huginn-appd/tls/fullchain.pem
   Environment=HUGINN_APPD_TLS_KEY=/etc/huginn-appd/tls/privkey.pem
   ```

3. Reload and restart (see [Change a setting](#change-a-setting)).

The daemon checks at startup that both files exist and that the key belongs to the certificate,
and refuses to start otherwise; the reason is in its log. It reads the files only at startup, so
restart it after each renewal. Clients notice the new certificate and prove it again with the
token on their own.

The certificate's host name does not matter to Huginn clients. Do not put a reverse proxy that
terminates TLS in front of the daemon: clients pin the certificate the daemon itself serves, and
refuse the proxy's.

**Replace the self-signed certificate:** delete `/var/lib/huginn-appd/tls/cert.pem` and
`key.pem`, then restart the daemon. It creates a new pair, and clients prove it on their own.

## Logs

The daemon logs to the systemd journal. Its own lines carry the identifier `huginn-appd`:

```bash
journalctl -u huginn-appd -t huginn-appd -f        # the daemon only, following
journalctl -u huginn-appd -n 200                   # everything filed under the unit
```

The second form can also include syslog output from programs in sessions, when their tmux server
runs inside the daemon's service rather than in `huginn-tmux.service`; the daemon notes this once
in its own log.

Each request is logged as method, path, status and duration, for example
`GET /v1/ping 200 2ms`. To see refused tokens:

```bash
journalctl -u huginn-appd -t huginn-appd | grep ' 401 '
```

## The tmux service

Sessions live in one tmux server, shared by every client and by `cc` over SSH. When the daemon has
to create a session and no tmux server is running, it starts one as a transient systemd service,
`huginn-tmux.service`, so that:

- sessions keep running when the daemon restarts or updates;
- sessions started from the apps run outside the daemon's sandbox, with the same access as
  sessions started over SSH.

A tmux server that is already running is left where it is; for example one started by `cc` over
SSH stays in that login.

```bash
systemctl status huginn-tmux      # is the daemon-started tmux server running
tmux ls                           # the sessions in it
```

`systemctl stop huginn-tmux` ends every session in it. The server uninstaller stops it only when
no session is running.

## See also

- [Install the server](../install/server.md): install options, updating and uninstalling.
- [Networking](../install/networking.md): ports, addresses and firewalls.
- [Security](../security.md): what the token and the sandbox protect, and a hardening checklist.
- [HTTP API](api.md): the routes the daemon serves.
