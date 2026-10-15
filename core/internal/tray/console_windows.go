//go:build windows

package tray

import "golang.org/x/sys/windows"

var freeConsole = windows.NewLazySystemDLL("kernel32.dll").NewProc("FreeConsole")

// DetachConsole drops the console window a console-subsystem huginn.exe gets
// when the Startup shortcut runs `huginn core tray` — the tray role has nothing
// to print, and a stray console on every login is not acceptable.
func DetachConsole() { freeConsole.Call() }
