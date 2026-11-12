// Package proc starts the tray role from an updater or from an outgoing tray:
// detached from the caller, so it outlives it and holds none of its handles
// (the desktop app reads `self-install`'s output until the pipe closes, and a
// tray that inherited that pipe would keep it open for its whole life).
package proc

import (
	"os/exec"
	"strings"
)

// StartDetached starts bin with args in its own session/process group, with
// stdin/stdout/stderr on the null device, and forgets it.
func StartDetached(bin string, args ...string) error {
	cmd := exec.Command(bin, args...)
	detach(cmd)
	if err := cmd.Start(); err != nil {
		// A job object that forbids breakaway refuses the flag (Windows).
		if retry := plain(cmd); retry != nil {
			if err2 := retry.Start(); err2 == nil {
				return retry.Process.Release()
			}
		}
		return err
	}
	return cmd.Process.Release()
}

// StartHidden is StartDetached for a console program that starts console
// programs of its own (cmd running ping): on Windows it gets a console that is
// never shown, which its children share. A DETACHED_PROCESS cmd has no console,
// so its ping opened a window of its own - an empty "ping -n 4 127.0.0.1"
// terminal after every uninstall (run 4).
func StartHidden(bin string, args ...string) error {
	cmd := exec.Command(bin, args...)
	hidden(cmd, true)
	if err := cmd.Start(); err != nil {
		retry := exec.Command(bin, args...)
		hidden(retry, false)
		if err2 := retry.Start(); err2 == nil {
			return retry.Process.Release()
		}
		return err
	}
	return cmd.Process.Release()
}

// Output runs bin briefly and returns its stdout, with no console window on
// Windows (a console program started from the tray would flash one).
func Output(bin string, args ...string) (string, error) {
	cmd := exec.Command(bin, args...)
	quiet(cmd)
	b, err := cmd.Output()
	return string(b), err
}

// WMICreateScript is the PowerShell that starts line through WMI
// (Win32_Process.Create) for StartIndependent on Windows. It lives here, with
// no build tag, so its words are tested on every platform.
//
// The process is created with ShowWindow = 0 (SW_HIDE). huginn.exe is a
// console program, and WMI gives a console program a console of its own: with
// no startup information that console is a VISIBLE window, which showed the
// core's log after an update's restart and ended the core when it was closed
// (2026-11-11, desktop 2.13.0). Hidden, not detached: programs the core starts
// share its console, so a hidden one keeps them windowless too, where a core
// with no console at all would give each of them a new, visible one.
func WMICreateScript(line string) string {
	return "$si = New-CimInstance -ClassName Win32_ProcessStartup -ClientOnly -Property @{ ShowWindow = [uint16]0 }; " +
		"$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = '" +
		strings.ReplaceAll(line, "'", "''") + "'; ProcessStartupInformation = $si }; exit [int]$r.ReturnValue"
}
