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

Section "Install"
  Call EnsureNotRunning
  SetOutPath "$INSTDIR"
  ; Wipe the previous payload first. An in-place overwrite leaves orphaned jars
  ; from the old release on the classpath, and two versions of the same library
  ; in APPDIR is a failure that only shows up as a NoSuchMethodError at runtime.
  ; The user's settings live in %APPDATA%, not here, so this loses nothing.
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
SectionEnd
