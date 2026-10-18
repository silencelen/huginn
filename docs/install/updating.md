# Updating

## The server

Run the install command again, as root:

```bash
curl -fsSL https://raw.githubusercontent.com/silencelen/huginn/main/server/get.sh | sudo bash
```

It downloads the newest release and re-runs setup, which updates the host tools and the daemon
and keeps your access token, certificate, settings (`site.conf`), chats and data. Running sessions
are not interrupted: they live in their own service, `huginn-tmux`, and survive the daemon's
restart.

Host tools that were edited on the server are kept, and the installer lists them; set
`HUGINN_FORCE=1` to overwrite them.

Claude Code updates itself. To update it by hand: `claude update`.

## The clients

On each computer:

```bash
huginn update
```

This updates everything Huginn installed on that computer, each from its newest release: the
`huginn` command, huginn core, the desktop app, and the local-AI tier if you set it up (it asks
before changing anything). huginn core and the desktop app check for updates by themselves as
well.

## The Android app

The app checks GitHub for a newer release and offers it under Settings. A build you made yourself
checks the repository it was built with, or nothing; see [Android](android.md).

## Versions

The server and the clients are released separately. Keep both on current releases; see
[Releases and compatibility](../reference/releases.md).
