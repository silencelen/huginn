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

The desktop app downloads a newer build, verifies it, and then says so: a card at the top of the
session and chat lists (**Huginn x.y.z is ready**, with the first line of what changed), a dot on
Settings and an **Update ready** row in the tray menu. The card offers three answers:
**Install and restart** applies it (a `.deb` or Arch package shows the install command to copy
instead, since those need root); **Later** hides the card until the next launch; **Don't remind
me** hides that version until a newer one arrives. **Settings > Updates & diagnostics** keeps
**Install and restart**, shows when reminders are off, and has **Tell me when an update is ready**
to turn the notice off altogether. Nothing installs on its own.

## The Android app

The app checks GitHub for a newer release and offers it under Settings. A build you made yourself
checks the repository it was built with, or nothing; see [Android](android.md).

## Versions

The server and the clients are released separately. Keep both on current releases; see
[Releases and compatibility](../reference/releases.md).
