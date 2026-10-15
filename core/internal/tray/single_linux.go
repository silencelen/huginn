//go:build linux && !android

package tray

import (
	"os"

	"golang.org/x/sys/unix"
)

// tryLock takes the one-tray-per-user lock without waiting. The lock dies with
// the process, so a crashed tray never leaves a stale one.
func tryLock(path string) (*os.File, bool) {
	f, err := os.OpenFile(path, os.O_CREATE|os.O_RDWR, 0o600)
	if err != nil {
		return nil, false
	}
	if unix.Flock(int(f.Fd()), unix.LOCK_EX|unix.LOCK_NB) != nil {
		f.Close()
		return nil, false
	}
	return f, true
}
