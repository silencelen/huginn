//go:build !windows

package main

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strconv"
	"strings"
	"syscall"
	"time"

	"github.com/silencelen/huginn/core/internal/service"
)

// removeDesktop takes Huginn Desktop off: the per-user portable install, the
// distro package (through sudo, asked for, when there is a terminal to ask
// on), and the app's settings, cache and login entry.
func removeDesktop(t *tally, home string) {
	data := xdg("XDG_DATA_HOME", filepath.Join(home, ".local", "share"))
	conf := xdg("XDG_CONFIG_HOME", filepath.Join(home, ".config"))
	cache := xdg("XDG_CACHE_HOME", filepath.Join(home, ".cache"))
	// A running app keeps its files open and would put its settings back.
	if pk, err := exec.LookPath("pkill"); err == nil {
		if exec.Command(pk, "-u", strconv.Itoa(os.Getuid()), "-f", "huginn-desktop-kt").Run() == nil {
			fmt.Println("  quit the running app")
			time.Sleep(2 * time.Second)
		}
	}
	any := false
	root := filepath.Join(data, "huginn-desktop")
	if fileExists(filepath.Join(root, ".huginn-portable")) {
		any = true
		if err := os.RemoveAll(root); err != nil {
			t.stays(root + " - " + err.Error())
		} else {
			t.gone(root + " (the per-user app)")
		}
		link := filepath.Join(home, ".local", "bin", "huginn-desktop")
		if dst, err := os.Readlink(link); err == nil && strings.HasPrefix(dst, root+"/") {
			os.Remove(link)
		}
		entry := filepath.Join(data, "applications", "huginn-desktop-kt.desktop")
		if b, err := os.ReadFile(entry); err == nil && strings.Contains(string(b), root+"/") {
			os.Remove(entry)
		}
	} else if dirExists(root) {
		t.stays(root + " - no .huginn-portable marker, so it was not laid out by huginn and was not removed")
	}
	if dirExists("/opt/huginn-desktop-kt") {
		any = true
		removePackagedDesktop(t)
	}
	for _, d := range []string{filepath.Join(conf, "huginn-desktop-kt"), filepath.Join(cache, "huginn-desktop-kt")} {
		if dirExists(d) {
			any = true
			if err := os.RemoveAll(d); err != nil {
				t.stays(d + " - " + err.Error())
			} else {
				t.gone(d)
			}
		}
	}
	if a := filepath.Join(conf, "autostart", "huginn-desktop-kt.desktop"); fileExists(a) {
		os.Remove(a)
		t.gone(a)
	}
	if !any {
		fmt.Println("  no desktop app here")
	}
}

func xdg(env, def string) string {
	if v := os.Getenv(env); filepath.IsAbs(v) {
		return v
	}
	return def
}

// removePackagedDesktop: the .deb or Arch package under /opt is root's to
// remove. With a terminal, ask sudo; without one, say the command.
func removePackagedDesktop(t *tally) {
	var argv []string
	switch {
	case exec.Command("pacman", "-Qq", "huginn-desktop-kt").Run() == nil:
		argv = []string{"pacman", "-R", "--noconfirm", "huginn-desktop-kt"}
	case dpkgInstalled("huginn-desktop-kt"):
		argv = []string{"apt-get", "purge", "-y", "huginn-desktop-kt"}
	default:
		t.stays("/opt/huginn-desktop-kt - no package owns it; remove it as root")
		return
	}
	if os.Geteuid() != 0 {
		argv = append([]string{"sudo"}, argv...)
	}
	cmdline := strings.Join(argv, " ")
	if fi, err := os.Stdin.Stat(); os.Geteuid() != 0 && (err != nil || fi.Mode()&os.ModeCharDevice == 0) {
		t.stays("the Huginn Desktop package - it is root's; run: " + cmdline)
		return
	}
	fmt.Println("  removing the Huginn Desktop package: " + cmdline)
	c := exec.Command(argv[0], argv[1:]...)
	c.Stdin, c.Stdout, c.Stderr = os.Stdin, os.Stdout, os.Stderr
	if err := c.Run(); err != nil {
		t.stays("the Huginn Desktop package - removal failed; run: " + cmdline)
		return
	}
	t.gone("the Huginn Desktop package (/opt/huginn-desktop-kt)")
}

func dpkgInstalled(pkg string) bool {
	b, err := exec.Command("dpkg-query", "-W", "-f=${Status}", pkg).Output()
	return err == nil && strings.Contains(string(b), "install ok installed")
}

// removeService: the systemd user unit and the tray's login entry.
func removeService(t *tally) {
	if !serviceInstalled(stableBinary()) {
		os.Remove(trayMarker())
		o, err := service.LinuxDefaults(stableBinary())
		if err == nil && fileExists(o.TrayPath()) {
			os.Remove(o.TrayPath())
			t.gone(o.TrayPath())
		}
		fmt.Println("  no boot service")
		return
	}
	if coreUninstall() != 0 {
		t.stays("the huginn-core user service - `huginn core uninstall` reported a failed step (above)")
		return
	}
	t.gone("the huginn-core boot service and the tray's login entry")
	undoLinger(t)
}

// undoLinger: linger huginn turned on goes off again; linger that was on
// before huginn is the person's, and is only mentioned.
func undoLinger(t *tally) {
	user := os.Getenv("USER")
	if b, err := os.ReadFile(lingerMarker()); err == nil {
		if u := strings.TrimSpace(string(b)); u != "" {
			user = u
		}
		if out, err := exec.Command("loginctl", "disable-linger", user).CombinedOutput(); err != nil {
			t.stays("linger for " + user + " - huginn turned it on, and turning it off failed: " + strings.TrimSpace(string(out)) + " (loginctl disable-linger " + user + ")")
			return
		}
		os.Remove(lingerMarker())
		t.gone("linger for " + user + " (huginn core had turned it on)")
		return
	}
	if out, err := exec.Command("loginctl", "show-user", user, "--property=Linger").Output(); err == nil && strings.Contains(string(out), "Linger=yes") {
		t.stays("linger for " + user + " - it was on before huginn, so it stays (loginctl disable-linger " + user + " turns it off)")
	}
}

func startService() {
	exec.Command("systemctl", "--user", "start", service.UnitName).Run()
}

// stopCoreProcesses ends this user's processes running a binary under root
// (the tray; a core started by hand), so nothing keeps the icon up.
func stopCoreProcesses(root string) {
	ents, _ := os.ReadDir("/proc")
	me := os.Getpid()
	for _, e := range ents {
		pid, err := strconv.Atoi(e.Name())
		if err != nil || pid == me {
			continue
		}
		var st syscall.Stat_t
		if syscall.Stat("/proc/"+e.Name(), &st) != nil || int(st.Uid) != os.Getuid() {
			continue
		}
		exe, err := os.Readlink("/proc/" + e.Name() + "/exe")
		if err != nil || !strings.HasPrefix(exe, root+"/") {
			continue
		}
		syscall.Kill(pid, syscall.SIGTERM)
	}
}

func removeFromPath(*tally, string)  {}
func removeRootLater(*tally, string) {}
