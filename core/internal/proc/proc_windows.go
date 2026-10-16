//go:build windows

package proc

import (
	"encoding/base64"
	"fmt"
	"os/exec"
	"strings"
	"syscall"
	"time"
	"unicode/utf16"

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

// WaitExit waits up to timeout for pid to be gone; false if it is still there.
// A core that stopped answering is still a running task instance until its
// process exits, and Task Scheduler ignores /Run for a task that is running.
func WaitExit(pid int, timeout time.Duration) bool {
	h, err := windows.OpenProcess(windows.SYNCHRONIZE, false, uint32(pid))
	if err != nil {
		return true // gone (or never ours to wait on)
	}
	defer windows.CloseHandle(h)
	ev, err := windows.WaitForSingleObject(h, uint32(timeout/time.Millisecond))
	return err == nil && ev == windows.WAIT_OBJECT_0
}

// StartIndependent starts bin with args through WMI (Win32_Process.Create), so
// the process belongs to no job of the caller's: an ssh session, the desktop
// app or a console that started it can end without taking it along. Used to
// start the core directly when Task Scheduler is holding its task back
// (Energy Saver on battery, no logon server reachable).
func StartIndependent(bin string, args ...string) error {
	line := `"` + bin + `"`
	for _, a := range args {
		line += " " + a
	}
	ps := "$r = Invoke-CimMethod -ClassName Win32_Process -MethodName Create -Arguments @{ CommandLine = '" +
		strings.ReplaceAll(line, "'", "''") + "' }; exit [int]$r.ReturnValue"
	u := utf16.Encode([]rune(ps))
	b := make([]byte, len(u)*2)
	for i, c := range u {
		b[2*i], b[2*i+1] = byte(c), byte(c>>8)
	}
	c := exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-EncodedCommand", base64.StdEncoding.EncodeToString(b))
	quiet(c)
	out, err := c.CombinedOutput()
	if err != nil {
		return fmt.Errorf("%v: %s", err, strings.TrimSpace(string(out)))
	}
	return nil
}
