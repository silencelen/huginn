# The one vendored NSIS plugin

`x86-unicode/WinShell.dll` — 3 KB, exports `SetLnkAUMI`, `UninstAppUserModelId`,
`UninstShortcut`. `huginn-desktop-kt.nsi` uses the first two.

## Why a binary is checked in here at all

Stamping `System.AppUserModel.ID` onto a `.lnk` is a four-interface COM dance
(`IShellLink` -> `IPropertyStore::SetValue(PKEY_AppUserModel_ID)` -> `Commit` ->
`IPersistFile::Save`). Without that stamp Windows drops every toast this app
posts — silently, exit code 0, nothing in any log — because a desktop app's
notification identity IS its Start Menu shortcut. That is the failure the
Compose client shipped with until 2026-08-04.

The alternative was hand-rolling the COM calls in `System::Call`, which keeps the
tree text-only. It was rejected for one reason: **there is no Windows machine in
this dev loop**, so hand-rolled pointer arithmetic could only be reasoned about,
never run — and a mistake in it fails exactly the way the bug it replaces does,
silently. `WinShell` is the implementation every electron-builder app on earth
ships, including the Electron Huginn Desktop the owner is running right now, so
this DLL has already executed on his machine.

## Provenance

Copied byte-for-byte from electron-builder's own resource bundle on huginn:

    /root/.cache/electron-builder/nsis-resources-3.4.1/.../plugins/x86-unicode/WinShell.dll
    sha256 9be85b986ea66a6997dde658abe82b3147ed2a1a3dcb784bb5176f41d22815a6

It lives HERE rather than being read from that cache because the cache is a
download directory another tool owns and prunes at will — the same reason
`release-desktop.sh` prefers the apt `makensis` over the cached one. A release
that silently stops stamping the AUMID because a cache was cleaned is the exact
bug this fixes, arriving by a different door.

Upstream: <https://nsis.sourceforge.io/WinShell_plug-in> (zlib licence).

## Adding a second one

Don't, unless the job genuinely cannot be done without it. `EnsureNotRunning`
uses `tasklist`/`taskkill` rather than the `nsProcess` plugin precisely because a
plugin-free job should stay plugin-free; one vendored binary is a supply-chain
decision made once and documented, two is a habit.
