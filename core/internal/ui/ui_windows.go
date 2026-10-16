//go:build windows

package ui

import (
	"os"

	"golang.org/x/sys/windows"
)

// enableVT turns on escape-sequence handling for the console behind f
// (conhost leaves it off for a process that does not ask). false: an old
// console with no VT, which then gets plain lines.
func enableVT(f *os.File) bool {
	h := windows.Handle(f.Fd())
	var m uint32
	if windows.GetConsoleMode(h, &m) != nil {
		return false
	}
	if m&windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING != 0 {
		return true
	}
	return windows.SetConsoleMode(h, m|windows.ENABLE_VIRTUAL_TERMINAL_PROCESSING) == nil
}

// platformUnicode: the same test as the PowerShell client's _Huginn-UiUni -
// Windows Terminal, or a console already on UTF-8. A classic console's
// raster font has no box-drawing wingbeat.
func platformUnicode() bool {
	if os.Getenv("WT_SESSION") != "" {
		return true
	}
	cp, err := windows.GetConsoleOutputCP()
	return err == nil && cp == 65001
}
