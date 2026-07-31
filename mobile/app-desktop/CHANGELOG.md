# Huginn Desktop (Compose) changelog

The Compose Multiplatform desktop client, versioned separately from the phone
app and from the Electron desktop client. Its releases go to `/v1/desktop-kt`;
the Electron client's go to `/v1/desktop` and the two never mix — see
`scripts/release-desktop.sh`.

## 0.1.0

First packaged build, and the first that can replace itself.

### It installs on Windows, and nothing but this Linux box built it

There is a Windows installer now, produced end to end on huginn. `jpackage`
cannot cross-compile — on Linux its only valid output types are app-image, rpm
and deb — so the Windows one runs under wine, against a Windows runtime image
linked from Windows jmods, and Linux `makensis` wraps the result. The Linux
`.deb` comes from Compose's own packaging. The two builds share every jar except
skiko, which ships a native per platform and is resolved through a configuration
of its own; a single global declaration is what once put a Linux renderer inside
a Windows package.

The installer is deliberately its own application as far as Windows is
concerned: its own directory, Start Menu entry, uninstall key and executable
name, so it can sit beside the Electron client until that one retires.

### It checks for its own updates

On launch and every four hours the app asks huginn for the newest release,
compares it against its own version, downloads the build for its platform and
checks the file against the SHA-256 the release listing already carries. Then it
stops and says it is ready. Nothing installs itself: these builds are unsigned,
and an application that relaunches into new code without being asked is one you
cannot tell apart from something that went wrong.

Where updates come from is fixed in the program itself and is not the server
address in Settings. That address is editable — it has to be, because which one
reaches huginn depends on which VPN is connected — and an update feed that
followed it would turn one mistyped host into permission to run an executable.
