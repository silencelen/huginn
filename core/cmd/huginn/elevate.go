package main

import (
	"encoding/base64"
	"os/exec"
	"strings"
	"unicode/utf16"
)

// runElevatedWindows runs exe with args once as administrator, behind ONE
// UAC prompt, hidden, and waits. Its output cannot cross the elevation
// boundary as a stream, so it goes to log, which the caller reads. The
// command travels as -EncodedCommand: no quoting layer in between can mangle
// a path with spaces. exit 1223 (ERROR_CANCELLED) = the prompt was declined.
func runElevatedWindows(exe string, args []string, log string) (int, error) {
	inner := elevatedInner(exe, args, log)
	outer := "try { $p = Start-Process -FilePath powershell.exe -ArgumentList '-NoProfile','-NonInteractive','-ExecutionPolicy','Bypass','-EncodedCommand','" +
		encodePS(inner) + "' -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode } catch { exit 1223 }"
	err := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", outer).Run()
	if ee, ok := err.(*exec.ExitError); ok {
		return ee.ExitCode(), nil
	}
	if err != nil {
		return -1, err
	}
	return 0, nil
}

// elevatedInner is the script the elevated PowerShell runs: exe with args,
// stdout and stderr to log as UTF-8 (not `*> log`: Windows PowerShell 5.1
// writes that as UTF-16), and exe's exit code as its own.
func elevatedInner(exe string, args []string, log string) string {
	q := func(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }
	parts := make([]string, len(args))
	for i, a := range args {
		parts[i] = q(a)
	}
	return "& " + q(exe) + " " + strings.Join(parts, " ") + " 2>&1 | ForEach-Object { \"$_\" } | Set-Content -Encoding UTF8 -Path " + q(log) + "; exit $LASTEXITCODE"
}

// encodePS is PowerShell's -EncodedCommand form: base64 of UTF-16LE.
func encodePS(s string) string {
	u := utf16.Encode([]rune(s))
	b := make([]byte, 2*len(u))
	for i, c := range u {
		b[2*i], b[2*i+1] = byte(c), byte(c>>8)
	}
	return base64.StdEncoding.EncodeToString(b)
}
