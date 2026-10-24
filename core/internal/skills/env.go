package skills

import (
	"os"
	"runtime"

	"github.com/silencelen/huginn/core/internal/install"
)

// DefaultEnv is this machine: the person's home and the core's install root.
func DefaultEnv() (Env, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return Env{}, err
	}
	l, err := install.DefaultLayout()
	if err != nil {
		return Env{}, err
	}
	return Env{GOOS: runtime.GOOS, Home: home, Root: l.Root}, nil
}
