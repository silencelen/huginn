//go:build windows

package store

import (
	"fmt"
	"os"

	"golang.org/x/sys/windows"
)

func isWindows() bool { return true }

// lockFile takes an exclusive lock on path with LockFileEx, blocking until it
// is free. The returned func releases it.
func lockFile(path string) (func(), error) {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, fmt.Errorf("store: lock %s: %w", path, err)
	}
	h := windows.Handle(f.Fd())
	ol := new(windows.Overlapped)
	if err := windows.LockFileEx(h, windows.LOCKFILE_EXCLUSIVE_LOCK, 0, 1, 0, ol); err != nil {
		f.Close()
		return nil, fmt.Errorf("store: lock %s: %w", path, err)
	}
	return func() {
		windows.UnlockFileEx(h, 0, 1, 0, ol)
		f.Close()
	}, nil
}
