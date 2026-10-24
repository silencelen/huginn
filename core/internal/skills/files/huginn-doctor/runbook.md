# Runbook: each check, what a failure looks like, what to do

Read-only commands first. Anything marked **ask** needs the person's yes.

## core.binary

| OS | Look | Fix |
|---|---|---|
| Linux | `ls -l ~/.local/bin/huginn ~/.local/share/huginn/current`; `huginn version` | `huginn core install` (re-runnable); a `/usr/bin/huginn` from a package means one core per machine: use that one |
| Windows | `Get-Command huginn`; `& "$env:LOCALAPPDATA\huginn\bin\huginn.exe" version` | `huginn core install` |

`core.cli` warns when `~/.huginn/huginn.sh` (`huginn.ps1`) is another version than the core:
`huginn update` fixes both. A missing CLI with a core present: `huginn core self-install` lays it
down again.

## core.service

Linux:

```bash
systemctl --user status huginn-core.service
systemctl --user is-enabled huginn-core.service
loginctl show-user "$USER" --property=Linger      # Linger=yes: runs at boot, with nobody logged in
journalctl --user -u huginn-core.service -n 100 --no-pager
```

- `inactive`/`failed`: `huginn core start`; if it fails again at once, the journal says why
  (a second core, a bad `core.json`, no runtime dir).
- `Linger=no`: the core runs only while the person is logged in. `huginn core install` turns
  linger on; if logind refuses, the person runs `sudo loginctl enable-linger "$USER"`.
- No unit file: `huginn core install`.
- `systemctl --user` fails with "Failed to connect to bus" over ssh: there is no user bus in that
  shell; `export XDG_RUNTIME_DIR=/run/user/$(id -u) DBUS_SESSION_BUS_ADDRESS=unix:path=$XDG_RUNTIME_DIR/bus`
  first, or ask the person to run it from a desktop terminal.

Windows (PowerShell):

```powershell
schtasks /Query /TN "Huginn Core" /V /FO LIST
schtasks /Query /TN "Huginn Tray" /V /FO LIST
huginn core status
```

- Not registered: `huginn core install` (one administrator prompt).
- Registered, `Last Result` not 0, or `Ready` but nothing answers: `huginn core start` starts it
  directly when Task Scheduler holds the task back (Energy Saver on battery, no logon server).
- `--logon password` installs must be redone after a password change.

## core.socket

| OS | Socket | Look |
|---|---|---|
| Linux | `$XDG_RUNTIME_DIR/huginn/core.sock` (fallback `/run/user/<uid>/huginn/core.sock`) | `curl -s --unix-socket "$XDG_RUNTIME_DIR/huginn/core.sock" -H 'X-Huginn-Core-Proto: 1' http://huginn-core/core/status` |
| Windows | `%LOCALAPPDATA%\huginn\run\core.sock` | `huginn core status` |

- Service active, socket silent: the journal shows the crash. A `426` means the CLI and the core
  speak different protocol versions: `huginn update`.
- `another huginn core is already serving this socket`: a second core (a from-source build, a
  manually started `huginn core run`); the person decides which one stays.
- The socket directory must be 0700 and the person's own; the core refuses otherwise.

## core.pairing

`huginn core status` prints `huginn: not paired` or the address. Pairing needs the server's
address (`https://<host>:8787`) and the token (`sudo cat /etc/huginn-appd/token` ON THE SERVER).
`huginn core pair --url ...` asks for the token with no echo. **Ask**; never handle the token
yourself. Pairing a different server, or unpairing, stops a running device job.

## route:<url>

```bash
huginn core route                      # every address, active one marked, last error
huginn core route add https://192.0.2.10:8787 --kind lan      # ask
huginn core route pin https://192.0.2.10:8787                # ask; `pin none` undoes it
```

- `connection refused` / `timed out`: the server is off, the address is wrong, or this machine
  is on another network (VPN down). Nothing on this machine fixes a server that is down.
- `could not prove it is your huginn daemon` / certificate not the proved one: another machine
  answers on that address, or the server's certificate changed (reinstall, deleted
  `/var/lib/huginn-appd/tls`). **Stop and tell the person.** If they confirm a reinstall,
  `huginn core pair --url <url>` proves the new certificate with the token.
- `the daemon is older than 3.11 and has no TLS`: the server needs updating; nothing here.
- Only `http://127.0.0.1:...` is ever plain, and only with `HUGINN_APPD_PLAINTEXT=local` in the
  service's environment. Do not suggest plaintext for anything else.

## server.status

Through the core's proxy: `curl -s --unix-socket ... http://huginn-core/appd/v1/status` (Linux).
Fields: `appdVersion`, `claude` (Claude Code's version on the server), `host`, `uptimeSec`,
`sessions`, `disk`. An error here with routes `ok` is the daemon itself: on the server,
`systemctl status huginn-appd` and `journalctl -u huginn-appd -t huginn-appd -n 100`. A Claude
Code session running there has the `huginn-server` skill for exactly this.

## device

`huginn device status`. `enrolled false` while `on`: the core is not reaching the server (see
routes) or the row was removed on the server (**ask** before `huginn device off` then `on`
again). `locked true` on a headless box: the lock probe failed and counts as locked; act is
withdrawn until it can read the state. Never widen a scope to fix a symptom.

## local-ai

`huginn local status` and `huginn local doctor` (both read-only). Services down: on Linux
`systemctl --user status huginn-local-llm huginn-local-runner` (or the system units with
`--system`); on Windows the two services under `services.msc`. `persistent false`: the tier
stops at sign-out; `huginn local persist` (**ask**: it elevates). Anything that reinstalls or
purges models: **ask**.

## ssh.config, ssh.resolve, ssh.port, ssh.hostkey

```bash
grep -A8 -i '^Host huginn' ~/.ssh/config     # the stanza huginn setup ssh writes
ssh -G huginn | grep -E '^(hostname|port|user|identityfile) '
ssh -o BatchMode=yes -o ConnectTimeout=8 huginn true; echo $?     # 0 = the server accepts the key
ssh-keygen -F <hostname>                   # 0 = host key known
```

- No stanza: `huginn setup ssh` (safe; it writes `Host huginn`, reuses `~/.ssh/id_ed25519` or
  makes one, and prints the `authorized_keys` line for the server when the server does not know
  the key). `--host` names another address when the paired one does not answer ssh.
- `Permission denied`: the server does not trust the key. The person adds the printed line to
  `~/.ssh/authorized_keys` on the server (as the SSH user, `root` by default).
- `Host key verification failed` / `IDENTIFICATION HAS CHANGED`: the first is a host this
  computer never saw (the person answers ssh's own prompt); the second means the server's key
  changed: **stop and tell the person**; `ssh-keygen -R <hostname>` is theirs to run.
- Port 22 silent while 8787 answers: the server's firewall or VLAN lets the daemon's port
  through and not ssh; `huginn setup ssh` offers the server's other addresses, and the person
  can point `Host huginn` at one with `--host`. Everything except attaching works without ssh.
- Windows: ssh is Windows' OpenSSH client (`Settings > System > Optional features > OpenSSH
  Client`). `ssh -T` avoids the "Pseudo-terminal will not be allocated" line in scripts.

## claude.code, claude.skills

`claude --version`; `huginn skills status`. `huginn skills install` links (Linux) or copies
(Windows) `huginn` and `huginn-doctor` into `~/.claude/skills`, replacing only what Huginn put
there; a directory the person wrote is left alone and named. `huginn skills remove` undoes it.
A new Claude Code session is needed to load a skill that was just installed.
