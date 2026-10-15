//go:build !windows

package tray

// DetachConsole is a no-op off Windows: a Linux autostart entry has no console.
func DetachConsole() {}
