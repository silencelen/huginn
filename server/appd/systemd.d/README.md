# Drop-ins for `huginn-appd.service`

`deploy.sh` rewrites the unit file, so anything that must survive a deploy lives
here and is installed to `/etc/systemd/system/huginn-appd.service.d/` instead of
being edited into the unit.

| file | why |
|---|---|
| `hardening.conf` | sandboxing (added 2026-08 after the audit found the unit had none) |

Not tracked here but present on the live host: `override.conf`, which sets
`HUGINN_APPD_BIND=0.0.0.0` for the Yggdrasil LAN gateway.

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
