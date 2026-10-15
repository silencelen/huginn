# Windows kit — Q1 (AF_UNIX same + cross session), Q2 (S4U task facts), Q9 (tray)

Build on huginn: `./build-kit.sh` → `huginn-core-proto-win.zip` (≈31 MB: three Go
.exe, the JVM probe, a 28 MB java.base+java.logging JRE cross-linked from
`/opt/jdk-win-x64/current/jmods`).

Run on PC01 (booted into Windows, signed in), ELEVATED PowerShell:

    scp huginn:/root/huginn-core/proto/windows/huginn-core-proto-win.zip $env:TEMP\
    Expand-Archive $env:TEMP\huginn-core-proto-win.zip $env:TEMP\hcp -Force
    cd $env:TEMP\hcp\kit; Set-ExecutionPolicy -Scope Process Bypass; .\probe.ps1 -WithPassword

It registers a throwaway task `huginn-proto-s4u` (S4U, then stored-password with
`-WithPassword`), runs the IPC server in it (session 0) and the probe from the
desktop session, compares the task's facts (Credential Manager, DPAPI, HTTPS,
profile) with the interactive session's, shows the tray icon for 20 s, then
unregisters the task and deletes its run dir. Send back `results.txt`.

Wine (9.0) cannot test this: no AF_UNIX (WSAEAFNOSUPPORT). s4ucheck.exe does run
there (credman/dpapi/https all ok), which only proves the binary.
