//go:build windows

package localtier

// No umask on Windows; the test that uses this skips there.
func syscallUmask(int) int { return 0 }
