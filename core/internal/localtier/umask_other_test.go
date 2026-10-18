//go:build !windows

package localtier

import "syscall"

func syscallUmask(n int) int { return syscall.Umask(n) }
