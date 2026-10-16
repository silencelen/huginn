//go:build !windows

package clientkit

import (
	"os"
	"runtime"
)

// DefaultEnv is this machine.
func DefaultEnv() Env {
	home, _ := os.UserHomeDir()
	return Env{GOOS: runtime.GOOS, Home: home, Shell: os.Getenv("SHELL")}
}
