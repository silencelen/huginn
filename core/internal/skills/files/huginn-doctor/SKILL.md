---
name: huginn-doctor
description: Troubleshoot Huginn on this machine - "huginn can't be reached", "huginn won't start", huginn core not running or not pairing, the console saying "core not answering" or "huginn unreachable", `huginn ls` or attaching over ssh failing, a device that never enrols, or a `huginn doctor` line that reads fail. Step 1 is always `huginn doctor --json`; then walk the chain binary -> core service -> socket -> pairing -> routes/TLS -> ssh -> server, and stop before anything that deletes pairing, secrets or sessions.
---

# Huginn doctor: when huginn cannot be reached or will not start

Everything on this machine is reached in one chain. A break early in the chain explains every
symptom after it, so work top down and stop at the first failure.

```text
binary -> core service -> core socket -> pairing (token + address) -> routes (TLS pin) -> server
                                                                   -> ssh (Host huginn) -> tmux sessions
```

## Step 1: run the doctor and read it

```bash
huginn doctor --json        # deterministic, changes nothing; exit 1 when a check fails
huginn doctor               # the same as lines for a person
```

Each entry is `{id, state, detail, fix}`; `state` is `ok`, `warn`, `fail` or `skip` (not
applicable here). The ids, in chain order:

| id | What it checked |
|---|---|
| `core.binary`, `core.cli` | huginn core is installed, and the shell client matches its version |
| `core.service` | the boot service (`huginn-core.service` + linger; the "Huginn Core" task) |
| `core.socket` | `huginn core run` answers on its socket |
| `core.tray`, `core.pairing` | the tray; an address and a token are held |
| `route:<url>` | each address: reachable, certificate is the proved one, latency |
| `server.status` | the daemon's `/v1/status` through the core: daemon and Claude Code versions |
| `device`, `local-ai` | this machine's enrolment and scope; the local AI tier if installed |
| `ssh.config`, `ssh.resolve`, `ssh.port`, `ssh.hostkey` | `Host huginn`, `ssh -G`, port 22 (TCP only), known host key |
| `claude.code`, `claude.skills` | Claude Code here, and whether these skills are installed |

Take the FIRST `fail` in that order. Its `fix` is the first thing to try. The rest of this skill is
what each failure looks like and how far you may go on your own; the exact commands per OS are in
[runbook.md](runbook.md).

## Step 2: the chain

1. **Binary** (`core.binary` fails): huginn core is not installed, or `huginn` on `PATH` is
   another copy. Safe: `huginn core install` (re-runnable; it asks nothing destructive).
2. **Core service** (`core.service`): the unit is missing, inactive, or linger is off (Linux: it
   then runs only while the person is logged in); the task is not registered (Windows).
   Safe: `huginn core start`; `huginn core install` to register the service again. Read the log
   before restarting anything twice.
3. **Socket** (`core.socket`): the service says running but nothing answers. Usually a crash
   loop (read the journal / task history), a stale socket after a reboot into a different
   `XDG_RUNTIME_DIR`, or a second core (`another huginn core is already serving this socket`).
   Safe: `huginn core start`. Not safe: deleting files under the config or state directories.
4. **Pairing** (`core.pairing`): no address or no token. Safe: tell the person what
   `huginn core pair --url https://<host>:8787` needs (the token from the server,
   `sudo cat /etc/huginn-appd/token` there). Never fetch, print or paste the token yourself.
5. **Routes / TLS** (`route:<url>`): an address does not answer (network, VPN down, server off),
   or answers with a certificate that is not the pinned one. The second means another machine is
   on that address, or the server was reinstalled: STOP and tell the person; `huginn core pair`
   again is their call. Safe: `huginn core route` (read), `huginn core route add` of an address
   the person names.
6. **ssh** (`ssh.*`): no `Host huginn` entry (`huginn setup ssh` writes one; it may ask the
   person to accept a host key and to add a public key on the server), port 22 not answering
   (the server or the network), host key unknown (the first `ssh huginn` asks). Never answer a
   host-key prompt for the person and never edit `~/.ssh/config` beyond what `huginn setup ssh`
   does.
7. **Server** (`server.status`): the core reaches an address but the daemon does not answer, or
   `huginn ls` works and the daemon verbs do not. That is the server's side: the person (or a
   Claude Code session there with the `huginn-server` skill) checks `systemctl status huginn-appd`
   and `journalctl -u huginn-appd -n 100`. Nothing on this machine fixes it.

## Stop and tell the person when

- a fix would delete or rewrite pairing, secrets, `core.json`, `~/.ssh/config`, `known_hosts`
  or anything under `~/.claude` that is not Huginn's own link or copy;
- a route's certificate is not the pinned one;
- the fix is on the server (restart the daemon, kill a session, edit hooks): never from a client;
- a device scope would be widened or act-while-locked turned on;
- `huginn uninstall` or `huginn update` looks like the answer: ask first.

## Report shape

Give the person: the first failing id and its detail, what you ran (read-only unless agreed),
and the one next command. If every check is `ok`, say so and point at the server
(`huginn-server` skill, `journalctl -u huginn-appd`) or the network.
