# Security

This page describes what Huginn exposes, what each part holds, where the limits are, and how to
harden a server. To report a vulnerability, see [SECURITY.md](../SECURITY.md).

## The trust model

Huginn is single-user: one server, one person, one access token. The server runs Claude Code as
root, so anything that can drive a session can do anything root can do on that machine. Huginn
does not try to contain that from inside. The isolation boundary is the machine itself, which is
why the server belongs on a dedicated virtual machine, container or computer with nothing else of
value on it. Everyone who can reach the server's ports with the token, log in to it over SSH, or
run programs as you on a paired computer is inside that boundary.

## What is exposed

### On the server

**SSH, port 22.** The terminal client attaches to sessions over SSH, as `root` unless you chose
another user when you set up the client. The host-side commands (`huginn status`, `huginn rounds`,
`huginn -p` and the others) run over the same connection. Every key in root's
`~/.ssh/authorized_keys` is a root login.

**`huginn-appd`, port 8787.** The daemon serves the console, huginn core, the desktop app and the
Android app.

- **HTTPS with a self-signed certificate.** On first start the daemon creates a P-256 certificate
  in `/var/lib/huginn-appd/tls/`. Clients do not need a certificate authority. Before a client
  sends the token, it asks the daemon for a proof: an HMAC, keyed with the token, over a fresh
  random value and the fingerprint of the certificate the client actually saw. Only a holder of
  the token can produce it, and a proof made for another certificate does not match, so an
  interceptor is refused. The client then pins that certificate. A changed certificate is
  proved again before it is trusted, never accepted on sight. The full protocol is in
  [TLS protocol](dev/protocol-tls.md).
- **Plain HTTP from loopback only.** The same port answers plain HTTP only from the server
  itself (`127.0.0.0/8`, `::1`). Any other plain HTTP request is refused with `426`. Plain HTTP
  has no identity check: while the daemon is stopped or restarting, any account on the server can
  take the port. So nothing in Huginn sends the token over it. The host-side tools and the
  installer use HTTPS pinned to the daemon's key, and huginn core, the device runner and the
  desktop app run the proof above on the server too. Keep other user accounts off the server all
  the same.
- **The token is root-equivalent.** Every route except `GET /v1/challenge` (the proof above)
  requires `Authorization: Bearer <token>`. A token holder can type into any session, answer
  Claude Code's permission prompts, start act chats, read transcripts and files, and queue work on
  your devices. There is no read-only token and no per-device token.
- **The token stays off command lines.** The `huginn` command's remote verbs hand it to `curl` on
  the server on standard input (`curl -K -`), and the examples in these docs send it as a header
  read from a file, so it never appears in another account's process list.
- **No rate limiting on authentication.** A wrong token gets `401` immediately, with no delay or
  lockout. Only the unauthenticated `/v1/challenge` route is limited (20 requests per second per
  address). The token is 32 random bytes; it stays unguessable only while nobody can try at
  scale, which is one more reason to keep the port off the internet. Every request is logged with
  its method, path and status, so refused attempts show up as `401` lines in the daemon's log.
- **Uploads.** Attachments from the apps are accepted whatever their type, up to 128 MB each, into
  `/var/lib/huginn-appd/uploads/`.
- **What sits behind the token.** `/var/lib/huginn-appd/accounts/` holds the full OAuth
  credentials of every saved Claude login, and `/etc/huginn-appd/fcm-service-account.json`, if you
  set up push, is a Google service-account key. Both are readable by root only.

### On each computer: huginn core

huginn core is a background service that runs as you: a systemd user unit
(`huginn-core.service`) on Linux, and the scheduled tasks "Huginn Core" and "Huginn Tray" on
Windows.

- **It holds the token** in its own secret store: the Secret Service keyring on Linux or
  Credential Manager on Windows. Where no keyring is usable it falls back to a file readable only
  by you: `~/.config/huginn/secrets/` (mode 0600) or `%APPDATA%\huginn\secrets\` (an access list
  granting only you and SYSTEM).
- **Its local API is a socket, never a TCP port:** `$XDG_RUNTIME_DIR/huginn/core.sock` (directory
  0700, socket 0600) on Linux, `%LOCALAPPDATA%\huginn\run\core.sock` on Windows. Every connection
  is checked before a byte is read: the kernel's record of the connecting process must be your
  own user (uid on Linux, SID on Windows), or the connection is dropped.
- **It proxies the daemon** for the console and the desktop app. It forwards `/v1/` paths only,
  adds the token itself, never passes on an `Authorization` header from the caller, and sends only
  to addresses it has already proved.
- **The boundary on a client is your user account.** Any program running as you on that computer
  can ask huginn core to act on the server for it. Treat a paired computer as one that can drive
  the server.
- **Updates are hash-checked.** `huginn core update` and `huginn update` download the core from
  the project's GitHub releases and install it only if its SHA-256 matches the one in that
  release's `core-manifest.json`. The hash comes from the same release; there is no signature over
  the manifest yet. See [Verify downloads](install/verify.md).

### The desktop app

The desktop app holds no token. It talks only to huginn core over the local socket, so its
settings file contains no secret. It checks GitHub releases at launch and every four hours,
verifies each download against the release manifest's SHA-256, and installs an update only when
you restart it.

Answer and undo links in its notifications (`huginn://…`) act only with the one-time value of the
notification that offered them, within five minutes. Any other link, from a web page or another
program, only opens the session.

### The Android app

A phone has no huginn core, so the Android app pairs directly and holds the token itself, in the
app's private storage. That storage is excluded from cloud backup and from device-to-device
transfer. The app verifies the daemon's certificate with the same proof as huginn core.

- Turn on **Settings → Privacy → Lock the app**. It asks for your fingerprint or the phone's PIN
  when the app returns after a minute in the background. It is off by default.
- A lost phone means a lost token: [rotate it](#rotate-the-token).
- If you build the app with your own Firebase project for instant push, notification text (the
  session name, the message, and the options of a waiting question) passes through Firebase Cloud
  Messaging. An alert relay script you configure receives the same text.

## Chats: ask and act

A chat is one headless Claude Code run on the server. Its mode decides what it may do:

| Mode | Allowed | Denied |
|---|---|---|
| ask | read, search, `WebFetch`, `WebSearch`, `Skill` | `Bash`, `Edit`, `Write`, `NotebookEdit` |
| act | `Bash`, `Read`, `Edit`, `Write`, `Glob`, `Grep`, `WebFetch`, `WebSearch`, `Skill` | none |

The fence is drawn at **change**, not at reading:

- **Ask can read anything root can read.** Claude Code lets a headless run read and search files
  without any grant, so an ask chat sees the whole server, including attachments. That is what
  makes attachments work in ask mode, and it is why ask is not a privacy boundary.
- **Bash is denied in ask, not left out.** Claude Code approves some shell commands on its own,
  based on their content, so two near-identical commands can get different answers. Denying Bash
  outright makes ask mode predictable: it never runs a command.
- **Act is a root shell driven by a prompt.** Its tools are approved in advance.
- `huginn -p` (ask) and `huginn -y` (act) run through the host tool `huginn-ask`, fenced exactly
  like app chats.
- Tools you add with `HUGINN_APPD_EXTRA_TOOLS` (for example an MCP server's tools) are granted in
  **both** modes. Add only tools you would hand to an ask chat.
- Chats on local models always run in ask mode.

**Sessions are different.** A session is interactive Claude Code in tmux, governed by Claude
Code's own permission settings in `~/.claude/settings.json` and in each project. When a session
asks for permission, the apps show it as **needs you**, and anyone with the token can answer it.

## Devices

A device is one of your computers whose huginn core accepts work from the server. You turn it on,
on that computer, with `huginn device on` or the desktop app's settings; see
[Devices](guide/devices.md).

- **The policy is decided on the device.** The runner on that computer builds the Claude Code
  command line from its own settings. The server only checks in advance so it can tell you at once
  that a computer cannot take a job.
- **Scopes.** `look` allows ask only (read, search, the web; no `Bash`, `Edit` or `Write`). `work`
  and `own` also allow act; they differ only in where a run starts (`work` in the folder you
  choose, `own` in your home directory). The starting folder is not a fence: an act run can reach
  anything your account can. A first enrolment with no scope is `look`.
- **Locked screen.** While the screen is locked, act is withdrawn and the computer answers as
  `look`, unless you turn on **Keep act mode while locked** (`--act-while-locked`), which is off
  by default. A lock state that cannot be read counts as locked.
- **Runs are you.** A device run is Claude Code on that computer, as your user, with your files.
  A stolen token can queue work on every device within its scope, so turn on act scopes only on
  computers you would let Claude Code act on unattended.

## Local AI (experimental)

A computer can serve local models to Huginn (`huginn local on`). When you turn it on:

- the inference server listens on `127.0.0.1` only and requires an API key generated at
  activation; a request without the key is refused;
- the computer gets a separate device row at the `generate` scope, which runs only text
  generation on local models: no tools, no files, and no system prompt from the server;
- its runner keeps its own copy of the access token in the local-AI directory
  (`~/.config/huginn-local/device/` on Linux, `%ProgramData%\huginn-local\device\` on Windows).
  `huginn local off` removes it.
  On Windows that directory is under `%ProgramData%`. Since 2.5.9 it is locked to SYSTEM,
  Administrators and the installing user; on a PC that already served local AI with an older
  release, run `huginn local update` once to apply the lock.

## The daemon's sandbox

`huginn-appd` runs as root with a light systemd sandbox
(`/etc/systemd/system/huginn-appd.service.d/hardening.conf`): `ProtectKernelTunables`,
`ProtectKernelModules`, `ProtectControlGroups`, `RestrictSUIDSGID`, `LockPersonality` and
`RestrictRealtime`. It stops the daemon, and the chats and rounds it starts, from changing the
kernel: no sysctl writes, no module loading, no cgroup edits, no setuid files.

It deliberately does not make the file system read-only. The daemon can already type into root
sessions, so a read-only `/usr` or `/etc` would not contain a compromised daemon; it would only stop
an act chat from doing what the same request does in a session. Two other common options are
absent too: `PrivateTmp`, because the tmux socket lives in `/tmp` and a private `/tmp` hides every
session, and `ProtectHome`, because the daemon manages Claude Code's credentials in `/root/.claude`.

- **Chats and rounds** are started by the daemon and run inside this sandbox.
- **Sessions run outside it.** When the daemon has to start tmux, it starts it as its own
  transient service, `huginn-tmux.service`, so a session started from the apps has the same access
  as one started over SSH, and it survives daemon restarts and updates.
- **A stricter sandbox** is a drop-in away, for example `ProtectSystem=strict` with a
  `ReadWritePaths=` list; act chats and rounds can then write only where you allow. See
  [Server configuration](reference/server-config.md).

The sandbox is defense in depth for the daemon process, not a boundary around Claude Code: the
daemon can read the token and every credential on the server, and a session can do anything
root can.

## What Huginn does not do

- **No users or roles.** One token grants everything; there is no read-only access and no
  per-device token to revoke.
- **No rate limiting or lockout** on the daemon's authentication.
- **No internet exposure.** Huginn is built for a private network or a VPN. A TLS-terminating
  reverse proxy cannot sit in front of the daemon, because clients pin the daemon's own
  certificate; only TLS passthrough works.
- **No sandbox around Claude Code.** Sessions, act chats and act device runs are limited by
  Claude Code's own permission settings, the scope you chose and the machine they run on.
- **No secret management.** Every file on the server, project secrets included, is readable by
  every session and every chat, ask chats too.
- **No encryption at rest.** Chats, uploads, transcripts and saved logins are plain files under
  `/var/lib/huginn-appd` and `/root/.claude`.
- **No signed release manifests yet.** Downloads are checked against SHA-256 hashes published in
  the same release. Windows files are signed with a certificate that is not yet publicly trusted.
  See [Verify downloads](install/verify.md).

## Hardening checklist

1. **Give Huginn a machine of its own.** A virtual machine, an unprivileged container or a small
   dedicated computer, with no personal data and no other services on it. See
   [Install the server](install/server.md).
2. **Keep it on your own network.** Reach ports 22 and 8787 over your LAN or a VPN you control,
   and never forward them from the internet. See [Networking](install/networking.md).
3. **Firewall both ports to your networks.** For example, with `ufw`:

   ```bash
   ufw allow from 192.168.1.0/24 to any port 22,8787 proto tcp
   ufw allow from 10.8.0.0/24 to any port 22,8787 proto tcp
   ```

   Check the rules before `ufw enable`, so you do not lock out your own SSH session.
4. **Use SSH keys only.** Set `PasswordAuthentication no` in the server's SSH configuration, keep
   one key per computer, and delete a key from root's `~/.ssh/authorized_keys` when the computer
   is lost or retired.
5. **Protect the token, and rotate it** when a device that held it is lost, when a computer that
   held it may be compromised, or when you suspect it was copied. See
   [Rotate the token](#rotate-the-token).
6. **Turn on the Android app lock** (Settings → Privacy → Lock the app).
7. **Keep Claude Code updated.** Re-running the server installer does not update Claude Code once
   it is installed. Check with `claude --version` and update with `claude update`. Running
   sessions keep the old version until they restart.
8. **Keep Huginn updated.** Re-run the server install command, and run `huginn update` on each
   computer. See [Releases](reference/releases.md).
9. **Back up the server's Huginn state**, and protect the backup like the token, because it
   contains it and every saved Claude login:

   ```bash
   tar -czf huginn-backup.tar.gz /var/lib/huginn-appd /etc/huginn-appd \
       /etc/systemd/system/huginn-appd.service.d/site.conf
   ```

   Back up `/root/.claude` (Claude Code's settings, credentials and transcripts) and your work
   directories with your usual tools.
10. **Review the hooks Huginn adds to Claude Code.** The installer merges these into
    `/root/.claude/settings.json`; they run in every Claude Code session on the server:

    | Script | Event | What it does |
    |---|---|---|
    | `/usr/local/bin/huginn-claude-title` | session, prompt, tool, notification, compaction and stop events | Records each session's state for the apps and sets the terminal title. |
    | `/opt/huginn-appd/hooks/huginn-permission-hook` | `PermissionRequest` | Lets an answer from the apps reach Claude Code's permission dialog. |
    | `/opt/huginn-appd/hooks/huginn-headroom-gate` | `SubagentStart`, `PreToolUse` (`Agent`, `Workflow`) | Holds new agents while an account is close to a usage limit. |
    | `/opt/huginn-appd/hooks/huginn-modelswitch-hook` | `PostModelSwitch` | Records model changes so Huginn can confirm a move. |

    None of them can block an action. The title hook runs asynchronously, and the other three
    exit successfully every time: they can delay an action, but never refuse one or answer a
    prompt by themselves. List them with `jq .hooks /root/.claude/settings.json`. The
    server uninstaller removes them all.
11. **Review what you grant.** Extra tools and MCP servers in `HUGINN_APPD_EXTRA_TOOLS` reach ask
    chats as well as act chats, and the persona file is read on every run. See
    [Server configuration](reference/server-config.md).

### Rotate the token

The daemon reads the token once at startup and refuses one shorter than 32 characters. As root on
the server:

```bash
sh -c 'umask 077; openssl rand -hex 32 > /etc/huginn-appd/token'
systemctl restart huginn-appd
cat /etc/huginn-appd/token
```

Restarting the daemon does not end sessions; a chat that was running is recorded as interrupted.
Every client then needs the new token:

- **huginn core**, on each computer. This also covers the console, the desktop app and the
  computer's device runs:

  ```bash
  huginn core pair --url https://192.168.1.20:8787
  ```

  It asks for the token without echoing it. The certificate is proved again with the new token.
  Pairing with a different token clears the extra addresses in `huginn core route`; add them
  again with `huginn core route add`.
- **The Android app:** Settings → Host → Token.
- **Computers that serve local AI** keep their own copy (see [Local AI](#local-ai-experimental)):
  turn local AI off and on again there (`huginn local off`, then `huginn local on`, or the desktop
  app's switch) after pairing huginn core with the new token.

Until a client has the new token, the daemon answers it with `401`.

## Reporting a vulnerability

Report security issues privately, as described in [SECURITY.md](../SECURITY.md).
