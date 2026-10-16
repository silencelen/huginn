//go:build !windows

package tray

// DetachConsole is a no-op off Windows: a Linux autostart entry has no console.
func DetachConsole() {}

// waitForTaskbar: only Windows has a taskbar that can be missing at sign-in.
func waitForTaskbar() {}
