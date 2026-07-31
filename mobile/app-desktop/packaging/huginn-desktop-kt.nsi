; Windows installer for the Compose Multiplatform desktop client.
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

!ifndef APP_VERSION
  !error "APP_VERSION not defined — pass -DAPP_VERSION=x.y.z"
!endif
!ifndef SRC_DIR
  !error "SRC_DIR not defined — pass -DSRC_DIR=<jpackage app-image dir>"
!endif
!ifndef OUT_FILE
  !error "OUT_FILE not defined — pass -DOUT_FILE=<path to the .exe to write>"
!endif

!define APP_ID    "huginn-desktop-kt"
!define APP_NAME  "Huginn Desktop (Compose)"
!define APP_EXE   "huginn-desktop-kt.exe"
!define PUBLISHER "silencelen"
!define UNINST_KEY "Software\Microsoft\Windows\CurrentVersion\Uninstall\${APP_ID}"

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

!define MUI_ABORTWARNING
!insertmacro MUI_PAGE_DIRECTORY
!insertmacro MUI_PAGE_INSTFILES
!insertmacro MUI_UNPAGE_CONFIRM
!insertmacro MUI_UNPAGE_INSTFILES
!insertmacro MUI_LANGUAGE "English"

Section "Install"
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

  CreateDirectory "$SMPROGRAMS\${APP_NAME}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"
  CreateShortCut "$SMPROGRAMS\${APP_NAME}\Uninstall ${APP_NAME}.lnk" "$INSTDIR\Uninstall.exe"
SectionEnd

Section "Uninstall"
  Delete "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk"
  Delete "$SMPROGRAMS\${APP_NAME}\Uninstall ${APP_NAME}.lnk"
  RMDir "$SMPROGRAMS\${APP_NAME}"
  Delete "$INSTDIR\Uninstall.exe"
  RMDir /r "$INSTDIR\app"
  RMDir /r "$INSTDIR\runtime"
  Delete "$INSTDIR\${APP_EXE}"
  RMDir "$INSTDIR"
  DeleteRegKey HKCU "${UNINST_KEY}"
  DeleteRegKey HKCU "Software\${APP_ID}"
SectionEnd
