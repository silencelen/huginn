//go:build windows

package tray

import (
	"os"

	"golang.org/x/sys/windows"
)

// tryLock takes the one-tray-per-user lock without waiting. The lock dies with
// the process, so a crashed tray never leaves a stale one. Only CONTENTION is
// false: a lock file that cannot be opened at all runs the tray unlocked
// rather than treating it as "another tray" and leaving for the whole login.
func tryLock(path string) (*os.File, bool) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, true
	}
	var ol windows.Overlapped
	if windows.LockFileEx(windows.Handle(f.Fd()), windows.LOCKFILE_EXCLUSIVE_LOCK|windows.LOCKFILE_FAIL_IMMEDIATELY, 0, 1, 0, &ol) != nil {
		f.Close()
		return nil, false
	}
	return f, true
}
