//go:build windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"

	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/proc"
	"github.com/silencelen/huginn/core/internal/service"
)

const desktopUninstKey = `Software\Microsoft\Windows\CurrentVersion\Uninstall\huginn-desktop-kt`

// removeDesktop runs the app's own uninstaller silently (it closes a running
// app, removes its files, shortcuts, url scheme and settings) and waits for it.
func removeDesktop(t *tally, home string) {
	k, err := registry.OpenKey(registry.CURRENT_USER, desktopUninstKey, registry.QUERY_VALUE)
	if err != nil {
		if cfg := desktopSettingsDir(home); dirExists(cfg) {
			os.RemoveAll(cfg)
			t.gone(cfg + " (settings left by an earlier uninstall)")
		}
		return
	}
	us, _, err := k.GetStringValue("UninstallString")
	k.Close()
	exe := strings.Trim(strings.TrimSpace(us), `"`)
	dir := filepath.Dir(exe)
	if err != nil || !fileExists(exe) || !strings.EqualFold(filepath.Base(dir), "huginn-desktop-kt") {
		t.stays("Huginn Desktop - its uninstaller is not where the registry says (" + us + "); remove it from Settings > Apps")
		return
	}
	// _?= runs the uninstaller in place and makes NSIS wait for it (without
	// it, the uninstaller copies itself to %TEMP% and returns at once). It
	// asks a running app to quit first (the '!quit' request, 2.3.6).
	t.spin("running Huginn Desktop's uninstaller")
	if err := exec.Command(exe, "/S", "_?="+dir).Run(); err != nil {
		t.stays("Huginn Desktop - its uninstaller failed (" + err.Error() + "); remove it from Settings > Apps")
		return
	}
	os.Remove(exe)
	os.RemoveAll(dir)
	registry.DeleteKey(registry.CURRENT_USER, desktopUninstKey)
	t.gone("Huginn Desktop (" + dir + ") and its settings")
}

func desktopSettingsDir(home string) string {
	if x := os.Getenv("XDG_CONFIG_HOME"); x != "" {
		return filepath.Join(x, "huginn-desktop-kt")
	}
	return filepath.Join(home, ".config", "huginn-desktop-kt")
}

// desktopPresent: the app is registered, or its settings outlived it.
func desktopPresent(home string) bool {
	if k, err := registry.OpenKey(registry.CURRENT_USER, desktopUninstKey, registry.QUERY_VALUE); err == nil {
		k.Close()
		return true
	}
	return dirExists(desktopSettingsDir(home))
}

// removeService deletes the boot task. Deleting a task registered elevated
// can need an administrator; then the same step runs elevated, behind one UAC
// prompt, as this user — what `huginn core install` does to register it.
func removeService(t *tally) {
	bin := stableBinary()
	o, err := service.WindowsDefaults(bin)
	if err != nil {
		t.stays("the boot task - " + err.Error())
		return
	}
	if !service.StatusWindows(o).Registered {
		os.Remove(trayMarker())
		if fileExists(o.StartupLnk()) {
			os.Remove(o.StartupLnk())
			t.gone(o.StartupLnk())
		}
		return
	}
	t.spin("deleting the boot task")
	if rep, err := coreUninstallReport(); err == nil {
		t.logf("%s", strings.TrimRight(rep.String(), "\n"))
		if rep.OK() {
			t.gone(`the "` + service.TaskName + `" boot task and the tray's Startup shortcut`)
			return
		}
	}
	t.spin("waiting for administrator approval, to delete the boot task")
	// Hidden: a visible console here showed nothing, and a click on it froze the
	// step in QuickEdit's "Select" mode (run 7).
	ps := "$p = Start-Process -FilePath '" + strings.ReplaceAll(bin, "'", "''") + "' -ArgumentList 'core','uninstall' -Verb RunAs -WindowStyle Hidden -Wait -PassThru; exit $p.ExitCode"
	if exec.Command("powershell.exe", "-NoProfile", "-NonInteractive", "-Command", ps).Run() != nil || service.StatusWindows(o).Registered {
		t.stays(`the "` + service.TaskName + `" boot task - run "huginn core uninstall" from an administrator terminal`)
		return
	}
	os.Remove(o.StartupLnk())
	t.gone(`the "` + service.TaskName + `" boot task and the tray's Startup shortcut (as administrator)`)
}

func startService() {
	exec.Command("schtasks", "/Run", "/TN", service.TaskName).Run()
}

// stopCoreProcesses ends every process running a binary under root (the
// tray, the core if its task could not end it) except this one: a running
// .exe cannot be deleted.
func stopCoreProcesses(root string) {
	snap, err := windows.CreateToolhelp32Snapshot(windows.TH32CS_SNAPPROCESS, 0)
	if err != nil {
		return
	}
	defer windows.CloseHandle(snap)
	var e windows.ProcessEntry32
	e.Size = uint32(unsafe.Sizeof(e))
	me := uint32(os.Getpid())
	prefix := strings.ToLower(root) + `\`
	for err = windows.Process32First(snap, &e); err == nil; err = windows.Process32Next(snap, &e) {
		if e.ProcessID == me || !strings.EqualFold(windows.UTF16ToString(e.ExeFile[:]), "huginn.exe") {
			continue
		}
		h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, e.ProcessID)
		if err != nil {
			continue
		}
		buf := make([]uint16, windows.MAX_LONG_PATH)
		n := uint32(len(buf))
		err = windows.QueryFullProcessImageName(h, 0, &buf[0], &n)
		windows.CloseHandle(h)
		if err == nil && strings.HasPrefix(strings.ToLower(windows.UTF16ToString(buf[:n])), prefix) {
			proc.Stop(int(e.ProcessID))
		}
	}
	time.Sleep(time.Second)
}

func removeFromPath(t *tally, dir string) {
	if removed, err := install.RemoveUserPath(dir); err != nil {
		t.stays(dir + " in your PATH - " + err.Error())
	} else if removed {
		t.gone(dir + " from your PATH")
	}
}

// removeRootLater: %LOCALAPPDATA%\huginn holds the binary running this, and
// Windows refuses to delete a running .exe. A detached cmd removes the folder
// a moment after this process exits.
func removeRootLater(t *tally, root string) {
	if !dirExists(root) {
		return
	}
	exe, _ := os.Executable()
	if !strings.HasPrefix(strings.ToLower(exe), strings.ToLower(root)+`\`) {
		if err := os.RemoveAll(root); err != nil {
			t.stays(root + " - " + err.Error())
		} else {
			t.gone(root)
		}
		return
	}
	f, err := os.CreateTemp("", "huginn-uninstall-*.cmd")
	if err != nil {
		t.stays(root + " - " + err.Error())
		return
	}
	fmt.Fprintf(f, "@echo off\r\nping -n 4 127.0.0.1 >nul\r\nrmdir /s /q \"%s\"\r\ndel \"%%~f0\"\r\n", root)
	f.Close()
	if err := proc.StartHidden("cmd.exe", "/c", f.Name()); err != nil {
		t.stays(root + " - " + err.Error())
		return
	}
	t.gone(root + " (in a few seconds, once this command has exited)")
}
