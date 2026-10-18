//go:build windows

package tray

import (
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var freeConsole = windows.NewLazySystemDLL("kernel32.dll").NewProc("FreeConsole")

// DetachConsole drops the console window a console-subsystem huginn.exe gets
// when the Startup shortcut runs `huginn core tray` — the tray role has nothing
// to print, and a stray console on every login is not acceptable.
func DetachConsole() { freeConsole.Call() }

var findWindow = windows.NewLazySystemDLL("user32.dll").NewProc("FindWindowW")

// waitForTaskbar holds the tray until the taskbar exists. The "Huginn Tray"
// logon task starts the tray at the instant of sign-in, before Explorer has
// made Shell_TrayWnd; an icon added then is refused, and the tray never
// reached its ready callback — so it never connected to the core and showed
// nothing (2026-10-16: started 13:13:37, never attached; restarted
// once the taskbar was up, attached in seconds). Two minutes, then try anyway.
func waitForTaskbar() {
	cls, _ := windows.UTF16PtrFromString("Shell_TrayWnd")
	deadline := time.Now().Add(2 * time.Minute)
	for time.Now().Before(deadline) {
		if h, _, _ := findWindow.Call(uintptr(unsafe.Pointer(cls)), 0); h != 0 {
			return
		}
		time.Sleep(500 * time.Millisecond)
	}
}
