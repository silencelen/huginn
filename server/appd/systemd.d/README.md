# huginn-appd: unit and drop-ins

`server/appd/install.sh` (run by `server/setup.sh`, and by `deploy.sh` after its tests)
installs the unit and the files in this directory:

| file | installed as | what |
|---|---|---|
| `../huginn-appd.service` | `/etc/systemd/system/huginn-appd.service` | the service; replaced on every install |
| `hardening.conf` | `huginn-appd.service.d/hardening.conf` | kernel-level sandboxing; replaced on every install |
| (generated) | `huginn-appd.service.d/site.conf` | this server's own settings; written once, never overwritten |

Put your own settings in `site.conf` (or another drop-in of your own) and run
`systemctl daemon-reload && systemctl restart huginn-appd`. Installs and updates never touch
a drop-in other than `hardening.conf`.

⚠ The headroom gate's own knobs (`HUGINN_HEADROOM_DIR`, `HUGINN_GATE_TIMEOUT`,
`HUGINN_GATE_POLL`, `HUGINN_GATE_STALE_S`) are **not** in the table below and a
drop-in cannot set them: the gate runs as a Claude Code hook, in the CLI's environment,
not the daemon's. `node /opt/huginn-appd/install-hooks.js --uninstall` removes the hook
entries the installer added.

### A stricter sandbox

`hardening.conf` stops the daemon and the chats and rounds it starts from changing the
kernel, but leaves the filesystem writable, so an act chat can do what a session can.
To confine them further, add a drop-in of your own, for example
`huginn-appd.service.d/strict.conf`:

```ini
[Service]
ProtectSystem=strict
ReadWritePaths=/var/lib/huginn-appd /run /root /tmp
```

Act chats and rounds can then write only to those paths. Sessions are not affected: they run in
their own unit, `huginn-tmux.service`.

## Environment the daemon reads

Set any of these in a drop-in `[Service]` block (`Environment=KEY=value`):

| var | default | what |
|---|---|---|
| `HUGINN_APPD_PORT` | `8787` | listen port |
| `HUGINN_APPD_BIND` | `0.0.0.0` | listen address. `0.0.0.0` (every interface) keeps loopback reachable; any single other address does not, and the host tools and the install check dial `127.0.0.1` |
| `HUGINN_APPD_DATA` | `/var/lib/huginn-appd` | data root (uploads, chats, desktop channels) |
| `HUGINN_APPD_TOKEN_FILE` | `/etc/huginn-appd/token` | bearer token file |
| `HUGINN_APPD_PLAINTEXT` | `local` | who may use plain HTTP: `local` = loopback only (everyone else gets `426` and must use `https://`); `any` = the pre-3.11 behaviour, for a migration window only. See `docs/dev/protocol-tls.md` |
| `HUGINN_APPD_TLS_CERT` / `HUGINN_APPD_TLS_KEY` | minted | a CA-issued certificate and key to serve instead of the self-signed pair appd mints into `$HUGINN_APPD_DATA/tls/` on first start; set both or neither |
| `HUGINN_APPD_WORKDIR` | `$HOME` | cwd for spawned `claude` chats |
| `HUGINN_APPD_PROJECTS_DIR` | `$HOME/projects` | where a project with no directory of its own is made (`<dir>/<slug>`); trust it in Claude Code once and every project under it is covered |
| `HUGINN_APPD_SOFT_END_PHRASE` | "Finish outstanding items, commit your work, and prepare to end the session." | the wrap-up a soft end types into the pane |
| `HUGINN_APPD_SOFT_END_AUTO` | on (`0` disables) | after a soft end, end the session automatically once it settles; `0` = phrase only, end it yourself |
| `HUGINN_APPD_UPLOAD_KEEP_DAYS` | `7` | retention for NON-image uploads; images are never pruned (they back chat-history thumbnails) |
| `HUGINN_APPD_STATE_DIR` | `/run/huginn-claude-state` | hook state dir — a test knob; do not change in production |
| `HUGINN_APPD_EXTRA_TOOLS` | unset | tools added to every chat and round, space-separated (for example an MCP server's tools, `mcp__memory`) |
| `HUGINN_APPD_PERSONA_FILE` | `/etc/huginn/persona.md` | optional text appended to the system prompt of chats and rounds; read on every run |
| `HUGINN_APPD_TELEGRAM_SCRIPT` | unset | optional alert relay: called as `bash <script> --message <text> --source huginn-app`; it must deliver the text and exit 0 |
| `HUGINN_FCM_KEY` | `/etc/huginn-appd/fcm-service-account.json` | Firebase service-account key for Android push; only useful with an app built against the same Firebase project |
| `HUGINN_APPD_FIREWALL` | `generic` | how the Apps fix lines write firewall rules: `generic`, or `proxmox` for Proxmox VE guest-firewall syntax |
| `HUGINN_APPD_FIREWALL_HOST` / `_FILE` | placeholders | with `proxmox`: the hypervisor and the firewall file the lines name |
| `HUGINN_APPD_MEMPALACE_HOST` / `_MARKER` | unset | optional probe of a companion memory server, reported in `/v1/status` |

### Test-only knobs — **never set these in production**

Every one of these exists so the route suites can stand up a local stub instead of
reaching `api.anthropic.com` or a live `~/.claude`. They are listed here
because leaving them undocumented is how a drop-in copied out of a test header
survives a deploy unnoticed, and the top three are handed a live credential: two
send the OAuth **access token** in an `Authorization` header and one sends the
**refresh token** in its body.

The three URLs are validated at startup: an override is accepted only when it is
`https:` **or** points at a loopback host (`127.0.0.1`, `localhost`, `[::1]`).
Anything else is refused with a log line and the real endpoint is used instead. An
accepted override also logs one line at startup, so `journalctl -u huginn-appd`
settles the question of whether one is in force.

| var | default | what |
|---|---|---|
| `HUGINN_APPD_USAGE_URL` | `https://api.anthropic.com/api/oauth/usage` | plan/usage endpoint — **carries the access token** |
| `HUGINN_APPD_OAUTH_ACCOUNT_URL` | `https://api.anthropic.com/api/oauth/account` | identity endpoint — **carries the access token** |
| `HUGINN_APPD_OAUTH_TOKEN_URL` | `https://platform.claude.com/v1/oauth/token` | refresh endpoint — **carries the refresh token** |
| `HUGINN_APPD_CLAUDE_DIR` | `~/.claude` | Claude Code's config dir. Moves the credentials file, the OAuth lock and the active-account guard off the live CLI — silently |
| `HUGINN_APPD_PLAN_TTL_MS` | `60000` | how long one usage reading is served for |
| `HUGINN_APPD_NATIVE_GRACE_MS` | `90000` | how long appd defers to the CLI's own limit wait before typing the resume phrase |
| `HUGINN_APPD_CONSENT_GRACE_MS` | `120000` | how long an unanswered Fable consent dialog waits before appd presses the recommended row |

After changing a drop-in, check the daemon can still SEE tmux sessions. That is the
failure the comments in `hardening.conf` care most about, and it is silent:

```bash
curl -sH @<(printf 'Authorization: Bearer %s\n' "$(sudo cat /etc/huginn-appd/token)") \
  http://127.0.0.1:8787/v1/sessions
```

The header goes to curl through a file descriptor, not as an argument: other accounts
on the machine can read every process's arguments, and the token is root-equivalent.
