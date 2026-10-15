//go:build !windows

package store

// restrictToUser is a no-op off Windows: the POSIX modes (0700/0600) are the
// restriction there.
func restrictToUser(string, bool) error { return nil }
