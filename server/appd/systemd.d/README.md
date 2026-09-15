# Drop-ins for `huginn-appd.service`

`deploy.sh` does NOT touch the unit — verified 2026-08-14 and still true: it installs
huginn-appd.js, lib/*.js, hooks/* and install-hooks.js, restarts, and (since appd
3.0.0) runs `install-hooks.js` to add the headroom gate to `~/.claude/settings.json`.
It never writes the unit, so a direct edit is not reverted. Drop-ins are
still the right home for policy (they survive a future unit reinstall and keep the
unit itself generic), so anything that must survive a deploy lives
here and is installed to `/etc/systemd/system/huginn-appd.service.d/` instead of
being edited into the unit.

⚠ The hook gate's own knobs (`HUGINN_HEADROOM_DIR`, `HUGINN_GATE_TIMEOUT`,
`HUGINN_GATE_POLL`) are **not** in the table below and a drop-in cannot set them:
the gate runs as a Claude Code hook, in the CLI's environment, not the daemon's.
`node install-hooks.js --uninstall` removes the two hook entries it added.

| file | why |
|---|---|
| `hardening.conf` | sandboxing (added 2026-08 after the audit found the unit had none) |

Not tracked here but present on the live host: `override.conf`, which sets
`HUGINN_APPD_BIND=0.0.0.0` for the Yggdrasil LAN gateway.

## Environment the daemon reads

Set any of these in a drop-in `[Service]` block (`Environment=KEY=value`):

| var | default | what |
|---|---|---|
| `HUGINN_APPD_PORT` | `8787` | listen port |
| `HUGINN_APPD_BIND` | resolved | listen address (`0.0.0.0` on this host) |
| `HUGINN_APPD_DATA` | `/var/lib/huginn-appd` | data root (uploads, chats, desktop channels) |
| `HUGINN_APPD_TOKEN_FILE` | `/etc/huginn-appd/token` | bearer token file |
| `HUGINN_APPD_WORKDIR` | `$HOME` | cwd for spawned `claude` chats |
| `HUGINN_APPD_SOFT_END_PHRASE` | "Finish outstanding items, commit your work, and prepare to end the session." | the wrap-up a soft end types into the pane |
| `HUGINN_APPD_SOFT_END_AUTO` | on (`0` disables) | after a soft end, end the session automatically once it settles; `0` = phrase only, end it yourself |
| `HUGINN_APPD_UPLOAD_KEEP_DAYS` | `7` | retention for NON-image uploads; images are never pruned (they back chat-history thumbnails) |
| `HUGINN_APPD_STATE_DIR` | `/run/huginn-claude-state` | hook state dir — a test knob; do not change in production |
| `HUGINN_APPD_MEMPALACE_HOST` / `_MARKER` | unset | optional companion memory node probe |
| `HUGINN_APPD_TELEGRAM_SCRIPT` | unset | optional Telegram relay for alerts |

Install or update:

```sh
sudo install -D -m 0644 systemd.d/hardening.conf \
  /etc/systemd/system/huginn-appd.service.d/hardening.conf
sudo systemctl daemon-reload && sudo systemctl restart huginn-appd
```

Then check the daemon can still SEE tmux sessions — that is the failure this
directory's comments care most about, and it is silent:

```sh
curl -sH "Authorization: Bearer $(sudo cat /etc/huginn-appd/token)" \
  http://127.0.0.1:8787/v1/sessions
```
