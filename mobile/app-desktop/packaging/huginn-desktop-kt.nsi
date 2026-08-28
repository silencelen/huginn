; Windows installer for the Huginn desktop client.
;
; Built by LINUX makensis (no Windows machine involved), wrapping the app-image
; that Windows jpackage.exe produced under wine. scripts/release-desktop.sh
; drives both steps and supplies every ${define} below — nothing here has a
; default, so a missing one is a compile error rather than an installer that
; silently ships version 0.0.0 into the wrong folder.
;
; DELIBERATELY DISJOINT FROM THE ELECTRON CLIENT. The owner is running Huginn
; Desktop 0.4.0 (Electron) on the same machine, and until parity both must be
; installable side by side. Every name that Windows treats as an identity is
; different here: install directory, registry uninstall key, Start Menu entry and
; executable name. Nothing this installer writes can be mistaken by Windows — or
; by an uninstaller — for the Electron app's.

Unicode true
!include "MUI2.nsh"
!include "FileFunc.nsh"
; ${If}/${EndIf} used by EnsureNotRunning below.
!include "LogicLib.nsh"

!ifndef APP_VERSION
  !error "APP_VERSION not defined — pass -DAPP_VERSION=x.y.z"
!endif
!ifndef SRC_DIR
  !error "SRC_DIR not defined — pass -DSRC_DIR=<jpackage app-image dir>"
!endif
!ifndef OUT_FILE
  !error "OUT_FILE not defined — pass -DOUT_FILE=<path to the .exe to write>"
!endif
; The ONE plugin, checked in beside this file rather than installed into makensis
; or read out of electron-builder's cache — plugins/README.md says why a binary is
; in the tree at all. It comes from the script like every other path here because
; it has to be ABSOLUTE: `!addplugindir` accepts a relative path, adds NOTHING,
; and admits it only at -V4, so the resulting "Plugin not found" reads like a
; missing DLL rather than a missing leading slash.
!ifndef PLUGIN_DIR
  !error "PLUGIN_DIR not defined — pass -DPLUGIN_DIR=<absolute path to packaging/plugins/x86-unicode>"
!endif
!addplugindir /x86-unicode "${PLUGIN_DIR}"

!define APP_ID    "huginn-desktop-kt"
; Was "Huginn Desktop (Compose)" until 0.8.8. The qualifier existed only to tell
; this client apart from the Electron one, which is gone — so it now said nothing
; except to make the owner read four extra characters on every Start Menu entry.
; APP_NAME is DISPLAY ONLY: InstallDir and UNINST_KEY both key off APP_ID, so the
; rename cannot install alongside the old copy or orphan its uninstall entry. What
; it DOES move is the Start Menu folder, which OLD_SM_NAME below cleans up.
!define APP_NAME  "Huginn Desktop"
; The pre-0.8.8 Start Menu folder. An upgrade writes shortcuts under the new name
; and would otherwise leave the old folder sitting there forever with a working
; shortcut in it — two entries for one app, one of which stops being updated.
!define OLD_SM_NAME "Huginn Desktop (Compose)"
!define APP_EXE   "huginn-desktop-kt.exe"
!define PUBLISHER "silencelen"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"
; MUST equal WindowsToastNotifier.AUMID, the string the app hands to
; CreateToastNotifier. A desktop app has no notification identity of its own: it
; borrows the one stamped on its Start Menu shortcut, and if the two strings
; disagree Windows accepts every toast and shows none of them, with no error
; anywhere. release-desktop.sh compares the two files so they cannot drift.
!define AUMID     "com.silencelen.huginn.desktop-kt"

Name "${APP_NAME} ${APP_VERSION}"
OutFile "${OUT_FILE}"
; PER-USER, under %LOCALAPPDATA%. The alternative is Program Files, which needs
; elevation — and an unsigned installer asking for admin is exactly the prompt a
; person should refuse. Nothing in this app needs to write outside the user's own
; profile.
InstallDir "$LOCALAPPDATA\Programs\${APP_ID}"
InstallDirRegKey HKCU "Software\${APP_ID}" "InstallDir"
RequestExecutionLevel user
SetCompressor /SOLID lzma
; The app-image is ~170 MB of jars, a 17 MB skiko DLL and a jlink runtime; the
; default dictionary leaves most of the redundancy between them uncompressed.
SetCompressorDictSize 64

VIProductVersion "${APP_VERSION}.0"
VIAddVersionKey "ProductName" "${APP_NAME}"
VIAddVersionKey "CompanyName" "${PUBLISHER}"
VIAddVersionKey "FileDescription" "${APP_NAME} installer"
VIAddVersionKey "FileVersion" "${APP_VERSION}"
VIAddVersionKey "ProductVersion" "${APP_VERSION}"
VIAddVersionKey "LegalCopyright" "${PUBLISHER}"

; The brand raven (packaging/huginn.ico, from assets/brand/generate.sh) on the
; installer and uninstaller themselves. The installed app's own icon comes from
; jpackage --icon; this covers the Setup exe the owner double-clicks. Absolute,
; like PLUGIN_DIR and for the same reason.
!ifndef ICON_FILE
  !error "ICON_FILE not defined — pass -DICON_FILE=<absolute path to packaging/huginn.ico>"
!endif
!define MUI_ICON   "${ICON_FILE}"
!define MUI_UNICON "${ICON_FILE}"

!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES

; ------------------------------------------------- the last page starts the app
;
; A checkbox on the finish page, CHECKED, and pressing Finish starts the client.
; MUI puts the Exec in that page's LEAVE handler, so "launch it when you close
; the installer" is literally what happens rather than a paraphrase of it.
;
; It earns its place on the UPDATE path more than on a first install. An update
; arrives through EnsureNotRunning below, which closes the running client —
; force-ends it, in practice, because this app hides to the tray rather than
; exiting — so until now an update TOOK THE APP AWAY and handed back a Start
; Menu shortcut. Nothing else in the chain puts it back: DesktopUpdater runs the
; downloaded installer with its UI, deliberately without /S, and stops there,
; while the button that started the whole thing is labelled "Install and
; restart". This is the half of that sentence the installer owes.
;
; A PLAIN Exec IS RIGHT HERE BECAUSE THIS INSTALLER NEVER ELEVATES. It declares
; RequestExecutionLevel user and installs under $LOCALAPPDATA (see InstallDir
; above), so the token it holds is the person's own — the same one that has to
; own the settings file, the loopback record SingleInstance writes beside it and
; the tray icon. Exec hands the new process exactly that token, and that is the
; only reason no de-elevation dance (ShellExecAsUser and its relatives) is
; needed here: an app started from an ADMIN token creates files the unelevated
; app may then be unable to replace, and if the elevation borrowed another
; account's credentials it does all of that in the wrong profile entirely. If
; this installer ever moves to Program Files and starts asking for admin, this
; line has to change with it.
;
; Silent installs show no pages at all, so /S — which the self-updater never
; uses, but release-desktop.sh's wine smoke test does — launches nothing.
!define MUI_FINISHPAGE_RUN "$INSTDIR\${APP_EXE}"
!define MUI_FINISHPAGE_RUN_TEXT "Launch ${APP_NAME}"
!insertmacro MUI_PAGE_FINISH

!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

; ---------------------------------------------------------------- running app
;
; An update over a RUNNING app silently half-fails: Windows locks the executable
; and the jars, so the payload wipe below leaves the old files in place and the
; copy writes what it can. The owner hit exactly that going 0.2.0 -> 0.3.0 — the
; installer neither closed the app nor said anything, and only worked once he
; closed it himself.
;
; Two things make this less obvious than it looks:
;
;   * Asking the WINDOW to close is not enough. This app has close-to-tray, so a
;     WM_CLOSE (which is all `taskkill` without /F sends) is handled as "hide",
;     and the process keeps running with every file still locked.
;   * So the graceful attempt is made first and given time, and only then is the
;     process ended outright. A force-end skips the shutdown hook that returns
;     any tmux pane to its own size — the daemon reclaims that within 90s on its
;     own, which is why this is an acceptable last resort rather than a silent
;     one.
;
; `tasklist`/`taskkill` are used rather than the nsProcess plugin: a job the
; shipped Windows tools already do should not cost a second vendored binary.

!macro EnsureNotRunning UN
Function ${UN}EnsureNotRunning
  retry:
    nsExec::ExecToStack 'cmd /c tasklist /FI "IMAGENAME eq ${APP_EXE}" /NH | find /I "${APP_EXE}"'
    Pop $0
    ${If} $0 != 0
      Return                       ; not running — nothing to do
    ${EndIf}

    MessageBox MB_YESNOCANCEL|MB_ICONEXCLAMATION \
      "${APP_NAME} is still running.$\r$\n$\r$\nIt has to close before these files can be replaced.$\r$\n$\r$\nYes — close it for me$\r$\nNo — I have closed it, try again$\r$\nCancel — stop here" \
      /SD IDYES IDYES close IDNO retry
    Abort "Cancelled: ${APP_NAME} is still running."

  close:
    ; Polite first: this posts WM_CLOSE, which a window without close-to-tray
    ; would honour.
    nsExec::ExecToLog 'taskkill /IM "${APP_EXE}"'
    Pop $0
    Sleep 2500
    nsExec::ExecToStack 'cmd /c tasklist /FI "IMAGENAME eq ${APP_EXE}" /NH | find /I "${APP_EXE}"'
    Pop $0
    ${If} $0 == 0
      ; Still there — it hid to the tray rather than exiting. End it, and its
      ; children with it, or the runtime keeps the jars locked.
      nsExec::ExecToLog 'taskkill /F /T /IM "${APP_EXE}"'
      Pop $0
      Sleep 1500
    ${EndIf}

    ; Prove it, rather than assuming the kill worked. A locked file discovered
    ; halfway through the copy is a broken install with no message.
    nsExec::ExecToStack 'cmd /c tasklist /FI "IMAGENAME eq ${APP_EXE}" /NH | find /I "${APP_EXE}"'
    Pop $0
    ${If} $0 == 0
      MessageBox MB_RETRYCANCEL|MB_ICONSTOP \
        "${APP_NAME} would not close.$\r$\n$\r$\nClose it from the tray, then Retry." \
        /SD IDCANCEL IDRETRY retry
      Abort "Cancelled: ${APP_NAME} could not be closed."
    ${EndIf}
FunctionEnd
!macroend
!insertmacro EnsureNotRunning ""
!insertmacro EnsureNotRunning "un."

; ------------------------------------------------- the local tier's services
;
; `huginn local on` can install two LocalSystem services through WinSW, under
; %ProgramData%\huginn-local\runtime\winsw — the model server and the runner
; that offers this machine's models to huginn. They survive a reboot with
; nobody logged in, which is the entire point of them, and it is also why they
; outlive an uninstaller that only deletes files: a service whose executable
; has gone keeps its entry in the SCM and fails on every boot forever.
;
; ITS OWN UNINSTALL FIRST. The WinSW wrapper knows the service it installed;
; sc.exe is the fallback for the case where the runtime directory is already
; half-gone. Neither is allowed to fail the uninstall — this installer is
; per-user and never elevates, and a LocalSystem service cannot be deleted
; from an unelevated token, so "access denied" is an expected answer here and
; not an error. What that leaves behind is named out loud at the end.
!macro UnLocalService NAME
  ${If} ${FileExists} "$R4\${NAME}.exe"
    nsExec::ExecToLog '"$R4\${NAME}.exe" stop'
    Pop $0
    nsExec::ExecToLog '"$R4\${NAME}.exe" uninstall'
    Pop $0
  ${Else}
    nsExec::ExecToLog 'sc.exe stop ${NAME}'
    Pop $0
    nsExec::ExecToLog 'sc.exe delete ${NAME}'
    Pop $0
  ${EndIf}
!macroend

Section "Install"
  Call EnsureNotRunning
  SetOutPath "$INSTDIR"
  ; Wipe the previous payload first. An in-place overwrite leaves orphaned jars
  ; from the old release on the classpath, and two versions of the same library
  ; in APPDIR is a failure that only shows up as a NoSuchMethodError at runtime.
  ; The user's settings live under %XDG_CONFIG_HOME%, or %USERPROFILE%\.config
  ; when that is unset — \huginn-desktop-kt either way, and NOT %APPDATA%:
  ; DesktopSettings.defaultFile() has no Windows branch, so it takes the XDG
  ; shape on every platform. Neither root is under $INSTDIR, so this loses
  ; nothing.
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  File /r "${SRC_DIR}/*"

  WriteUninstaller "$INSTDIR\Uninstall.exe"
  WriteRegStr HKCU "Software\${APP_ID}" "InstallDir" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayName"     "${APP_NAME}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayVersion"  "${APP_VERSION}"
  WriteRegStr HKCU "${UNINST_KEY}" "Publisher"       "${PUBLISHER}"
  WriteRegStr HKCU "${UNINST_KEY}" "DisplayIcon"     "$INSTDIR\${APP_EXE}"
  WriteRegStr HKCU "${UNINST_KEY}" "InstallLocation" "$INSTDIR"
  WriteRegStr HKCU "${UNINST_KEY}" "UninstallString" '"$INSTDIR\Uninstall.exe"'
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoModify" 1
  WriteRegDWORD HKCU "${UNINST_KEY}" "NoRepair" 1
  ; So "Apps & features" shows a size rather than a blank.
  ${GetSize} "$INSTDIR" "/S=0K" $0 $1 $2
  IntFmt $0 "0x%08X" $0
  WriteRegDWORD HKCU "${UNINST_KEY}" "EstimatedSize" "$0"

  ; ------------------------------------------------------ notification identity
  ;
  ; The shortcut is not a convenience here, it is the thing that makes toasts
  ; work. Windows files a notification under the calling app's AppUserModelID and
  ; discards it — no error, no log, the Show() call returns normally — when that
  ; ID matches no installed Start Menu shortcut. Every "needs you" the desktop
  ; client raises goes through that path, and the Telegram fallback is suppressed
  ; while the desktop claims the notify route, so an unstamped shortcut is worse
  ; than having no desktop notifier at all: it swallows the message AND the
  ; fallback that would have reached him.
  ;
  ; This is not theory. The Electron client showed no notifications in field use
  ; until its identity was set; the hand-written installer that replaced
  ; electron-builder lost the step, and 0.3.1 shipped without it.
  ; The old folder goes first, and by its LITERAL old name — an upgrade from a
  ; pre-0.8.8 install has shortcuts under it that would otherwise linger, still
  ; launching this app but never updated again.
  WinShell::UninstShortcut "$SMPROGRAMS\${OLD_SM_NAME}\${OLD_SM_NAME}.lnk"
  Delete "$SMPROGRAMS\${OLD_SM_NAME}\${OLD_SM_NAME}.lnk"
  Delete "$SMPROGRAMS\${OLD_SM_NAME}\Uninstall ${OLD_SM_NAME}.lnk"
  RMDir "$SMPROGRAMS\${OLD_SM_NAME}"

  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"
  WinShell::SetLnkAUMI "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "${AUMID}"
  ; Names the identity for Action Center and for Settings > Notifications. Windows
  ; will invent a label without it, but an app the owner cannot FIND in that list
  ; is an app whose notifications he cannot check are enabled — and "notifications
  ; are off for this app" is the other way toasts vanish in silence.
  WriteRegStr HKCU "Software\Classes\AppUserModelId\${AUMID}" "DisplayName" "${APP_NAME}"

  CreateShortCut "$SMPROGRAMS\${APP_NAME}\Uninstall ${APP_NAME}.lnk" "$INSTDIR\Uninstall.exe"

  ; ------------------------------------------------------------- node runtime
  ;
  ; The optional features — serving local AI models, running work as a device —
  ; are Node programs, and the NATIVE claude build no longer implies node is
  ; present (field-proven: the first activation attempt found claude.exe and no
  ; node anywhere). This installer is per-user and unsigned, so it never
  ; elevates ITSELF: when node is missing it OFFERS the winget install, and
  ; the one admin prompt that follows belongs to the signed Node.js package —
  ; a prompt worth trusting, unlike ours would be. Declining costs nothing but
  ; those two features, which say so themselves when asked. Silent installs
  ; (the self-updater path) skip the offer: a surprise UAC mid-update is how
  ; trust dies, so /SD answers No.
  nsExec::ExecToStack 'cmd /c where node'
  Pop $0
  Pop $1
  ${If} $0 != 0
    ${IfNot} ${FileExists} "$PROGRAMFILES64\nodejs\node.exe"
      MessageBox MB_YESNO|MB_ICONQUESTION \
        "Huginn's optional features (serving local AI models, running work as a device) use Node.js, which was not found on this machine.$\r$\n$\r$\nInstall Node.js LTS now?$\r$\n(winget runs it; expect one administrator prompt from the Node.js installer itself.)" \
        /SD IDNO IDNO node_done
      nsExec::ExecToLog 'winget install --id OpenJS.NodeJS.LTS --accept-package-agreements --accept-source-agreements --disable-interactivity'
      Pop $0
      ${If} $0 != 0
        ; Covers a declined UAC, an offline machine, and no winget at all
        ; (nsExec pushes "error" when the exec itself fails) — one honest
        ; fallback for every shape of no.
        MessageBox MB_OK|MB_ICONEXCLAMATION \
          "Node.js did not install (winget said: $0).$\r$\nInstall it any time from nodejs.org, or run:  winget install OpenJS.NodeJS.LTS" \
          /SD IDOK
      ${EndIf}
      node_done:
    ${EndIf}
  ${EndIf}
SectionEnd

Section "Uninstall"
  ; Same lock problem, same answer: an uninstall over a running app leaves the
  ; directory behind and the entry in Programs and Features.
  Call un.EnsureNotRunning

  ; ------------------------------------------------------ 1. the server FIRST
  ;
  ; ORDER IS THE WHOLE POINT, and it is the same order client/huginn-device
  ; keeps: the device row this machine holds on the daemon can only be removed
  ; with the bearer token sitting in the config file that is about to be
  ; deleted. So the DELETE is attempted while the credential still exists. Wipe
  ; first and that row is unremovable FROM HERE, forever — it stays in `huginn
  ; devices` reading "not reachable", it goes on being offered work, and the one
  ; handle that could have retired it left with the config.
  ;
  ; BEST EFFORT, AND NEVER A GATE. `huginn-device off` refuses to destroy the id
  ; when the DELETE fails, because it can be run again tomorrow. An uninstaller
  ; cannot: the person has already decided, and a dialog about a sleeping host
  ; would only teach them to click through it. A host that is asleep, a laptop
  ; on hotel wi-fi and a stale url therefore cost one stale row and nothing
  ; else. Short timeout, no prompt, no failure path.
  ;
  ; BOTH ROWS. A machine that also serves local models has a SECOND enrolment
  ; (<host>-llm, scope generate) with its own id and its own copy of the token
  ; under %ProgramData%. Removing one and not the other is how the fleet ends up
  ; with a ghost that only ever appears in the model picker.
  ;
  ; PowerShell does the JSON and the HTTP because NSIS can do neither, and it is
  ; written to $PLUGINSDIR — which NSIS deletes on exit by itself — rather than
  ; folded into one enormous -Command: the same call the app already makes when
  ; it drops huginn-toast.ps1 beside its own config.
  ;
  ; The two roots, read the way the programs that WROTE them read them.
  ; $PROFILE and not $APPDATA: DesktopSettings.defaultFile() has no Windows
  ; branch at all, so the settings take the XDG shape on every platform — which
  ; means %XDG_CONFIG_HOME% FIRST and $PROFILE\.config only as its fallback,
  ; exactly the order defaultFile() reads them in. Reading only the fallback is
  ; not a smaller bug than reading the wrong variable: on a machine that sets it
  ; (a dotfiles setup, a roaming or portable profile) the deletion below would
  ; find an empty path and leave the plaintext token where the app put it.
  ; %ProgramData% from the environment, the way huginn-local's localDir() does —
  ; and with the literal fallback it uses, because everything below joins onto
  ; this string and an empty one would aim them at the root of the system drive.
  ReadEnvStr $R0 "ProgramData"
  StrCmp $R0 "" 0 +2
    StrCpy $R0 "C:\ProgramData"
  ReadEnvStr $R3 "XDG_CONFIG_HOME"
  ${If} $R3 == ""
    StrCpy $R1 "$PROFILE\.config\huginn-desktop-kt"
  ${Else}
    StrCpy $R1 "$R3\huginn-desktop-kt"
  ${EndIf}
  StrCpy $R2 "$R0\huginn-local"
  InitPluginsDir
  FileOpen $9 "$PLUGINSDIR\unenrol.ps1" w
  FileWrite $9 "$$ErrorActionPreference = 'SilentlyContinue'$\r$\n"
  FileWrite $9 "function Drop($$b, $$i, $$t) {$\r$\n"
  FileWrite $9 "  if (-not $$b -or -not $$i -or -not $$t) { return }$\r$\n"
  FileWrite $9 "  try { [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12 } catch {}$\r$\n"
  FileWrite $9 "  try { Invoke-WebRequest -Uri ($$b.TrimEnd('/') + '/v1/devices/' + $$i) -Method Delete -Headers @{ Authorization = 'Bearer ' + $$t } -UseBasicParsing -TimeoutSec 5 | Out-Null } catch {}$\r$\n"
  FileWrite $9 "}$\r$\n"
  FileWrite $9 "$$s = '$R1\settings.json'$\r$\n"
  FileWrite $9 "if (Test-Path $$s) { try { $$j = Get-Content $$s -Raw | ConvertFrom-Json; Drop $$j.baseUrl $$j.deviceId $$j.token } catch {} }$\r$\n"
  FileWrite $9 "$$d = '$R2\device\device.json'$\r$\n"
  FileWrite $9 "$$k = '$R2\device\appd-token'$\r$\n"
  FileWrite $9 "if ((Test-Path $$d) -and (Test-Path $$k)) { try { $$j = Get-Content $$d -Raw | ConvertFrom-Json; Drop $$j.url $$j.id ((Get-Content $$k -Raw).Trim()) } catch {} }$\r$\n"
  FileClose $9
  nsExec::ExecToLog 'powershell -NoProfile -NonInteractive -ExecutionPolicy Bypass -File "$PLUGINSDIR\unenrol.ps1"'
  Pop $0

  ; ---------------------------------------- 2. the services, before their files
  ; Runner first, so nothing re-enrols while the rest comes down — the order
  ; `huginn local off` uses, for the same reason.
  StrCpy $R4 "$R2\runtime\winsw"
  !insertmacro UnLocalService "huginn-local-runner"
  !insertmacro UnLocalService "huginn-local-llm"

  ; Unpin and de-register before deleting. A shortcut that is simply removed from
  ; disk leaves its pinned tile and its jump-list history behind, still bound to
  ; the AUMID, so a later reinstall inherits a half-remembered identity.
  WinShell::UninstShortcut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  WinShell::UninstAppUserModelId "${AUMID}"
  DeleteRegKey HKCU "Software\Classes\AppUserModelId\${AUMID}"
  Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Uninstall ${APP_NAME}.lnk"
  RMDir "$SMPROGRAMS\${APP_NAME}"
  ; Belt and braces for a machine that upgraded across the rename and never had
  ; the install-time cleanup run.
  Delete "$SMPROGRAMS\${OLD_SM_NAME}\${OLD_SM_NAME}.lnk"
  Delete "$SMPROGRAMS\${OLD_SM_NAME}\Uninstall ${OLD_SM_NAME}.lnk"
  RMDir "$SMPROGRAMS\${OLD_SM_NAME}"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\${APP_EXE}"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\${APP_ID}"

  ; ------------------------------------------------------ 3. everything ELSE
  ;
  ; Uninstalling is meant to leave nothing, and until this block existed it left
  ; the one thing that matters most: settings.json holds the daemon's bearer
  ; token in PLAINTEXT (it says so itself, in DesktopSettings), and so does
  ; settings.json.corrupt, the copy the loader makes when the file will not
  ; parse. Deleting the application and keeping two copies of a root-equivalent
  ; credential on the disk is the wrong half.
  ;
  ; The whole config directory goes, not a list of files inside it: the token,
  ; the .corrupt and .tmp siblings, the log, the single-instance lock and the
  ; toast helper the notifier writes there all belong to this app and to nothing
  ; else.
  RMDir /r "$R1"
  ; Downloaded installers from the self-updater. Not secret, just hundreds of MB
  ; of an app that is no longer here. DesktopUpdater.defaultCacheDir() honours
  ; %XDG_CACHE_HOME% first, and past runs may have used either root, so both
  ; app-named directories go.
  RMDir /r "$PROFILE\.cache\huginn-desktop-kt"
  ReadEnvStr $R3 "XDG_CACHE_HOME"
  ${If} $R3 != ""
    RMDir /r "$R3\huginn-desktop-kt"
  ${EndIf}
  ; The local tier: models, sessions, the runtime, and a SECOND copy of the
  ; token under device\appd-token. Multi-GB, and invisible in Programs and
  ; Features because nothing here installed it as a package.
  RMDir /r "$R2"
  ; The url scheme this app registers so an Action Center toast can call back
  ; into it. Left behind, it points at an exe that no longer exists.
  DeleteRegKey HKCU "Software\Classes\huginn"
  ; The toast payloads: one XML per notification, written to %TEMP% and left
  ; there by design (Windows reads them asynchronously). Only ours.
  Delete "$TEMP\huginn-toast-*.xml"

  ; The CLI copies the app keeps current for the user (CliSync). NAMED ONE BY
  ; ONE, and the directory itself is only ever removed with a plain RMDir, which
  ; refuses a non-empty one: ~/.huginn is ALSO where client/install.sh puts a
  ; separately-installed base client, and that install is not ours to undo. A
  ; wildcard here would take huginn.ps1 and huginn.ps1.bak with it and leave the
  ; person's shell profile sourcing a file that no longer exists.
  ;
  ; The names come from CliSync.candidates() — every satellite the app WRITES
  ; here, minus the base client it shares with install.sh — plus the siblings
  ; CliSync leaves around one: the .bak of the copy it replaced and the two temp
  ; names a validated download is staged under. A satellite added there and not
  ; added here is a file the app keeps current and the uninstaller walks past —
  ; which is exactly what happened to huginn-llm-shim: CliSync took it on in
  ; 0.13.0 and this list was written in 0.14.0 without it.
  Delete "$PROFILE\.huginn\huginn-device"
  Delete "$PROFILE\.huginn\huginn-device.bak"
  Delete "$PROFILE\.huginn\huginn-device.tmp.js"
  Delete "$PROFILE\.huginn\huginn-device.appsync.tmp.js"
  Delete "$PROFILE\.huginn\huginn-local"
  Delete "$PROFILE\.huginn\huginn-local.bak"
  Delete "$PROFILE\.huginn\huginn-local.tmp.js"
  Delete "$PROFILE\.huginn\huginn-local.appsync.tmp.js"
  Delete "$PROFILE\.huginn\huginn-llm-shim"
  Delete "$PROFILE\.huginn\huginn-llm-shim.bak"
  Delete "$PROFILE\.huginn\huginn-llm-shim.tmp.js"
  Delete "$PROFILE\.huginn\huginn-llm-shim.appsync.tmp.js"
  RMDir "$PROFILE\.huginn"

  ; SAID, rather than assumed. %ProgramData%\huginn-local is written by a
  ; LocalSystem service and this uninstaller runs as the user, so its removal
  ; can be refused — and a person who has just uninstalled an app is owed the
  ; truth about the 5 GB and the second token still on their disk, plus the one
  ; command that finishes the job.
  ${If} ${FileExists} "$R2\*.*"
    MessageBox MB_OK|MB_ICONEXCLAMATION \
      "Huginn Desktop is uninstalled, but the local-AI files could not be removed:$\r$\n$\r$\n$R2$\r$\n$\r$\nThey belong to a LocalSystem service and need an Administrator terminal. To finish:$\r$\n$\r$\n    rmdir /s /q $\"$R2$\"" \
      /SD IDOK
  ${EndIf}
SectionEnd
