//go:build !linux && !windows

package store

import (
	"fmt"
	"runtime"
)

func keyringName() string { return "keyring" }

func openKeyring() (Secrets, error) {
	return nil, fmt.Errorf("%w: no keyring backend on %s", ErrUnavailable, runtime.GOOS)
}
