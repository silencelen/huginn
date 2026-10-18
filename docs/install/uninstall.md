# Uninstalling

## A client computer

```bash
huginn uninstall            # asks first; --yes skips the question
huginn uninstall --all      # also removes the 'Host huginn' SSH entry, and the key if Huginn made it
```

It removes everything Huginn installed on that computer, top down: the desktop app, the local-AI
tier, the computer's device entry on the server, huginn core and its boot service, and the
`huginn` command. It prints what it removed and what it kept on purpose (for example an SSH key
you had before Huginn). Windows asks for administrator approval once, to remove the boot task.

Removing only the desktop app (Programs and Features on Windows, `apt purge huginn-desktop-kt` on
Debian, `pacman -R huginn-desktop-kt` on Arch) leaves huginn core and the command in place.

## The Android app

Uninstall it the usual way; nothing on the server needs removing. A push registration it leaves
behind is dropped once Firebase reports it gone. If the phone was lost rather than uninstalled,
[rotate the access token](../security.md#rotate-the-token).

## The server

```bash
sudo bash /usr/local/src/huginn/server/uninstall.sh            # keeps chats, settings and the token
sudo bash /usr/local/src/huginn/server/uninstall.sh --purge    # removes those too
```

It removes the daemon, its service and settings, the Claude Code hooks setup added, and the host
tools. Claude Code, Node.js, tmux and your files stay. A tmux server that still has sessions is
left running until you end them. `--purge` deletes the access token, which disconnects every
paired client.
