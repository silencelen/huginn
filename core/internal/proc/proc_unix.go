//go:build !windows

package proc

import (
	"os"
	"os/exec"
	"syscall"
)

func detach(cmd *exec.Cmd) { cmd.SysProcAttr = &syscall.SysProcAttr{Setsid: true} }

func plain(*exec.Cmd) *exec.Cmd { return nil }

// InteractiveSession: this process belongs to a graphical login, where a tray
// can be drawn (an ssh shell or a service has neither variable).
func InteractiveSession() bool {
	return os.Getenv("DISPLAY") != "" || os.Getenv("WAYLAND_DISPLAY") != ""
}

func quiet(*exec.Cmd) {}

// Elevated: running as root, where a tray must not be started from.
func Elevated() bool { return os.Geteuid() == 0 }

// Stop asks this user's process pid to exit (SIGTERM: the core shuts down cleanly).
func Stop(pid int) error { return syscall.Kill(pid, syscall.SIGTERM) }
