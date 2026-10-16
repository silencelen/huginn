//go:build windows

package main

import "golang.org/x/sys/windows/registry"

// startupDisabled: the person turned the shortcut off in Task Manager's
// Startup tab. That leaves the .lnk in place and records the choice under
// StartupApproved\StartupFolder (first byte even = enabled, odd = disabled).
func startupDisabled(lnkName string) bool {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Software\Microsoft\Windows\CurrentVersion\Explorer\StartupApproved\StartupFolder`, registry.QUERY_VALUE)
	if err != nil {
		return false
	}
	defer k.Close()
	b, _, err := k.GetBinaryValue(lnkName)
	return err == nil && len(b) > 0 && b[0]&1 == 1
}
