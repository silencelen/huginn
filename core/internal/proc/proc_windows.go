//go:build windows

package proc

import (
	"os/exec"
	"syscall"

	"golang.org/x/sys/windows"
)

const (
	detachedProcess        = 0x00000008
	createNewProcessGroup  = 0x00000200
	createBreakawayFromJob = 0x01000000
)

func detach(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: detachedProcess | createNewProcessGroup | createBreakawayFromJob, HideWindow: true}
}

func plain(cmd *exec.Cmd) *exec.Cmd {
	c := exec.Command(cmd.Path, cmd.Args[1:]...)
	c.SysProcAttr = &syscall.SysProcAttr{CreationFlags: detachedProcess | createNewProcessGroup, HideWindow: true}
	return c
}

// InteractiveSession: not session 0. Services, scheduled tasks and the OpenSSH
// server all run in session 0, which has no desktop to draw a tray on.
func InteractiveSession() bool {
	var id uint32
	if err := windows.ProcessIdToSessionId(windows.GetCurrentProcessId(), &id); err != nil {
		return false
	}
	return id != 0
}

func quiet(cmd *exec.Cmd) {
	cmd.SysProcAttr = &syscall.SysProcAttr{CreationFlags: 0x08000000 /* CREATE_NO_WINDOW */, HideWindow: true}
}

// Elevated: an administrator token ("Run as administrator", or an elevated
// `core install`). A tray started from here would run elevated in the desktop.
func Elevated() bool { return windows.GetCurrentProcessToken().IsElevated() }

// Stop ends this user's process pid (Windows has no SIGTERM for a windowless
// process; the core's state is written as it changes, so nothing is lost).
func Stop(pid int) error {
	h, err := windows.OpenProcess(windows.PROCESS_TERMINATE, false, uint32(pid))
	if err != nil {
		return err
	}
	defer windows.CloseHandle(h)
	return windows.TerminateProcess(h, 1)
}
