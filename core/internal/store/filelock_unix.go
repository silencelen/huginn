//go:build !windows

package store

import (
	"fmt"
	"os"

	"golang.org/x/sys/unix"
)

func isWindows() bool { return false }

// lockFile takes an exclusive advisory lock on path (created 0600), blocking
// until it is free. The returned func releases it.
func lockFile(path string) (func(), error) {
	f, err := os.OpenFile(path, os.O_RDWR|os.O_CREATE, 0o600)
	if err != nil {
		return nil, fmt.Errorf("store: lock %s: %w", path, err)
	}
	for {
		err = unix.Flock(int(f.Fd()), unix.LOCK_EX)
		if err != unix.EINTR {
			break
		}
	}
	if err != nil {
		f.Close()
		return nil, fmt.Errorf("store: lock %s: %w", path, err)
	}
	return func() {
		unix.Flock(int(f.Fd()), unix.LOCK_UN)
		f.Close()
	}, nil
}
