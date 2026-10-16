package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/proc"
	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/tray"
	"github.com/silencelen/huginn/core/internal/version"
)

// The upgrade path (docs/CORE-ARCHITECTURE.md §13): every way a newer core
// reaches a machine — `huginn core update`, `huginn update`, the desktop app's
// bundled core, the bootstrap one-liners — ends in `huginn core self-install`
// run by the new binary. That is where the running core is restarted onto it,
// and where a machine set up before the tray existed (2.0.0) gets the tray.

type coreSnapshot struct {
	Version string `json:"version"`
	Tray    bool   `json:"tray"`
	Pid     int    `json:"pid"`
	Device  struct {
		Status struct {
			Busy     bool   `json:"busy"`
			Enrolled bool   `json:"enrolled"`
			DeviceID string `json:"deviceId"`
		} `json:"status"`
	} `json:"device"`
}

// coreState asks this user's running core how it is, briefly.
func coreState(timeout time.Duration) (coreSnapshot, bool) {
	var st coreSnapshot
	ctx, cancel := context.WithTimeout(context.Background(), timeout)
	defer cancel()
	resp, err := ipc.Do(ctx, ipc.Client(daemon.SocketPath(paths())), http.MethodGet, "/core/status", nil)
	if err != nil {
		return st, false
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK || json.NewDecoder(resp.Body).Decode(&st) != nil {
		return st, false
	}
	return st, true
}

// waitCore polls until ok(state, running) or the timeout.
func waitCore(timeout time.Duration, ok func(coreSnapshot, bool) bool) (coreSnapshot, bool) {
	deadline := time.Now().Add(timeout)
	for {
		st, running := coreState(2 * time.Second)
		if ok(st, running) || time.Now().After(deadline) {
			return st, running
		}
		time.Sleep(500 * time.Millisecond)
	}
}

// upgradeRunning runs after self-install put this version at the stable path.
func upgradeRunning(bin string) {
	st, running := coreState(3 * time.Second)
	if !running && serviceInstalled(bin) {
		// Just (re)started by an install a moment ago: give it time to answer.
		st, running = waitCore(15*time.Second, func(_ coreSnapshot, up bool) bool { return up })
	}
	if running && st.Version != version.Version {
		st, running = restartCore(st)
	}
	ensureTrayAutostart(bin, running)
	ensureTrayTask(bin)
	if running {
		launchTray(bin, st)
	}
}

// serviceInstalled: a boot service for the core is set up for this user.
func serviceInstalled(bin string) bool {
	switch runtime.GOOS {
	case "linux":
		o, err := service.LinuxDefaults(bin)
		return err == nil && fileExists(o.UnitPath())
	case "windows":
		o, err := service.WindowsDefaults(bin)
		return err == nil && service.StatusWindows(o).Registered
	}
	return false
}

// restartCore asks the running (older or newer) core to exit so its
// supervisor starts the binary now installed, and says what happened.
func restartCore(cur coreSnapshot) (coreSnapshot, bool) {
	old := cur.Version
	var out struct {
		AfterJob bool `json:"afterJob"`
	}
	if _, err := call(http.MethodPost, "/core/restart", nil, &out); err != nil {
		// A 2.0.0 core has no /core/restart.
		fmt.Printf("  the running core (%s) could not be asked to restart (%v)\n", old, err)
		return restartBySupervisor(cur)
	}
	if out.AfterJob {
		fmt.Printf("  the running core (%s) restarts onto %s when its current job finishes%s\n", old, version.Version, windowsRetryNote())
		return coreState(2 * time.Second)
	}
	// Gone, or already back on the new binary.
	waitCore(10*time.Second, func(s coreSnapshot, up bool) bool { return !up || s.Version == version.Version })
	return startAndWait(cur)
}

// restartBySupervisor restarts a core that predates /core/restart through its
// service manager — what `huginn core install` does on every run — and, when
// that cannot be reached (no user bus in this shell; a task this user may not
// end), stops the old core by its pid: it is this user's own process, and its
// supervisor (Restart=always; the task's 5-minute repeat) starts the new one.
func restartBySupervisor(cur coreSnapshot) (coreSnapshot, bool) {
	old := cur.Version
	switch runtime.GOOS {
	case "linux":
		if o, err := service.LinuxDefaults(stableBinary()); err == nil && fileExists(o.UnitPath()) {
			if b, err := exec.Command("systemctl", "--user", "restart", service.UnitName).CombinedOutput(); err != nil {
				fmt.Printf("  systemctl --user restart %s: %s\n", service.UnitName, strings.TrimSpace(string(b)))
			}
		}
	case "windows":
		if b, err := exec.Command("schtasks", "/End", "/TN", service.TaskName).CombinedOutput(); err != nil {
			fmt.Printf("  schtasks /End: %s\n", strings.TrimSpace(string(b)))
		}
	}
	st, up := waitCore(5*time.Second, func(s coreSnapshot, up bool) bool { return !up || s.Version != old })
	if up && st.Version == old && st.Device.Status.Busy {
		// Never kill remote work halfway; the next restart picks the new binary.
		fmt.Printf("  the old core (%s) is running a job — it runs %s after its next restart\n", old, version.Version)
		return st, up
	}
	if up && st.Version == old && st.Pid > 0 {
		if err := proc.Stop(st.Pid); err != nil {
			fmt.Printf("  could not stop the old core (pid %d): %v\n", st.Pid, err)
		} else {
			fmt.Printf("  stopped the old core (pid %d)\n", st.Pid)
			waitCore(10*time.Second, func(s coreSnapshot, up bool) bool { return !up || s.Version != old })
		}
	}
	return startAndWait(cur)
}

// startAndWait starts the service where its manager will not do it by itself
// (Windows: Task Scheduler does not restart a task that exited), then waits
// for the new version to answer.
//
// ⚠ Windows: the old core stops answering when it closes its socket, but its
// task instance runs until the process exits (shutdown waits up to 5 s on open
// event streams), and Task Scheduler ignores /Run for a running task. So wait
// for the old pid to go, and repeat /Run while nothing answers (measured on
// PC01, 2.1.1: one /Run in that window left the core down for minutes).
func startAndWait(cur coreSnapshot) (coreSnapshot, bool) {
	old := cur.Version
	if runtime.GOOS == "windows" && cur.Pid > 0 {
		proc.WaitExit(cur.Pid, 15*time.Second)
	}
	var lastRun time.Time
	said := false
	// systemd's RestartSec is 10 s. Windows gets 20 s for its task before the
	// direct start below.
	wait := 30 * time.Second
	if runtime.GOOS == "windows" {
		wait = 20 * time.Second
	}
	st, up := waitCore(wait, func(s coreSnapshot, up bool) bool {
		if up && s.Version == version.Version {
			return true
		}
		if runtime.GOOS == "windows" && !up && time.Since(lastRun) >= 5*time.Second {
			lastRun = time.Now()
			if b, err := exec.Command("schtasks", "/Run", "/TN", service.TaskName).CombinedOutput(); err != nil && !said {
				said = true
				fmt.Printf("  schtasks /Run: %s%s\n", strings.TrimSpace(string(b)), windowsRetryNote())
			}
		}
		return false
	})
	if !up && runtime.GOOS == "windows" {
		st, up = startDirectly()
	}
	switch {
	case up && st.Version == version.Version:
		fmt.Printf("  restarted huginn core: %s -> %s\n", old, st.Version)
	case up:
		fmt.Printf("  huginn core is still %s — it runs %s after its next restart (`huginn core install` restarts it now)\n", st.Version, version.Version)
	default:
		fmt.Printf("  huginn core has not come back yet%s\n", windowsRetryNote())
	}
	return st, up
}

// startDirectly starts the core itself when Task Scheduler holds its task
// back. Seen on PC01 (2026-10-16): on battery with Energy Saver on, every
// start of the "Huginn Core" task was launched and QUEUED, never run, so an
// update's restart left the core down until the laptop was charged. A core
// started this way serves this login; the queued task, when it finally runs,
// finds it listening and steps aside (ErrInUse), and the next boot is the
// task's again.
func startDirectly() (coreSnapshot, bool) {
	fmt.Println("  Windows is holding the boot task back (Energy Saver on battery, or no logon server); starting huginn core directly")
	if err := proc.StartIndependent(stableBinary(), "core", "run"); err != nil {
		fmt.Println("  could not start it directly: " + err.Error())
		return coreSnapshot{}, false
	}
	return waitCore(15*time.Second, func(_ coreSnapshot, up bool) bool { return up })
}

func windowsRetryNote() string {
	if runtime.GOOS == "windows" {
		return " (Task Scheduler starts it within 5 minutes)"
	}
	return ""
}

// trayMarker records that the tray's login autostart was set up once, so an
// autostart the person removed is not put back by every update.
func trayMarker() string { return filepath.Join(paths().State, "tray-autostart") }

func markTray() {
	m := trayMarker()
	os.MkdirAll(filepath.Dir(m), 0o700)
	os.WriteFile(m, []byte(version.Version+"\n"), 0o600)
}

// ensureTrayAutostart gives a machine whose core service predates the tray
// (2.0.0) the tray's login autostart, once.
func ensureTrayAutostart(bin string, running bool) {
	if fileExists(trayMarker()) {
		return
	}
	var st service.State
	var d string
	switch runtime.GOOS {
	case "linux":
		o, err := service.LinuxDefaults(bin)
		if err != nil || !fileExists(o.UnitPath()) {
			return // no core service: nothing to add a tray to
		}
		if fileExists(o.TrayPath()) {
			markTray()
			return
		}
		st, d = service.EnsureTrayLinux(o)
	case "windows":
		o, err := service.WindowsDefaults(bin)
		if err != nil || (!running && !service.StatusWindows(o).Registered) {
			return
		}
		if fileExists(o.StartupLnk()) {
			markTray()
			return
		}
		st, d = service.EnsureTrayWindows(o)
	default:
		return
	}
	switch st {
	case service.Done, service.Unchanged:
		markTray()
		fmt.Println("  tray autostart: " + d)
	case service.Skipped:
		// No graphical session here (an ssh shell): the next run from the desktop adds it.
	default:
		fmt.Println("  tray autostart could not be written: " + d)
	}
}

// ensureTrayTask gives a Windows install from before 2.3.3 the tray's logon
// task, once, when its tray starts at login at all (the Startup shortcut is
// there): the shortcut alone ran 3.5 minutes after sign-in on PC01. A
// shortcut the person removed means no tray at login, and no task either.
func ensureTrayTask(bin string) {
	if runtime.GOOS != "windows" {
		return
	}
	// The marker holds a hash of the task definition, so a release that
	// changes it (2.3.5: the 10 s delay, then the headless console) re-registers
	// it once, and an unchanged one costs nothing.
	o, err := service.WindowsDefaults(bin)
	if err != nil {
		return
	}
	marker := filepath.Join(paths().State, "tray-task")
	// The tray at sign-in is ON while its Startup shortcut is there and enabled
	// (Task Manager's Startup tab is where a person turns it off). Off: the
	// logon task must not start it behind their back.
	if !fileExists(o.StartupLnk()) || startupDisabled(service.TrayShortcut) {
		if service.TrayTaskRegistered(o) {
			o.Runner.Run("schtasks", "/Delete", "/TN", service.TrayTaskName, "/F")
			fmt.Println("  tray at sign-in is off (its Startup entry is gone or disabled): removed the logon task too")
		}
		os.Remove(marker)
		return
	}
	sum := sha256.Sum256([]byte(service.TrayTaskXML(o.Binary, o.User)))
	want := hex.EncodeToString(sum[:])
	if b, err := os.ReadFile(marker); err == nil && strings.TrimSpace(string(b)) == want && service.TrayTaskRegistered(o) {
		return
	}
	st, d := service.EnsureTrayTaskWindows(o)
	fmt.Println("  tray at sign-in: " + d)
	if st == service.Done {
		os.MkdirAll(filepath.Dir(marker), 0o700)
		os.WriteFile(marker, []byte(want+"\n"), 0o600)
	}
	os.Remove(filepath.Join(paths().State, "tray-task-2"))
}

// afterServiceInstall: `core install` wrote (or skipped) the autostart itself.
func afterServiceInstall(bin string, autostart bool) {
	if autostart {
		markTray()
	}
	st, running := waitCore(15*time.Second, func(_ coreSnapshot, up bool) bool { return up })
	if running {
		launchTray(bin, st)
	}
}

// launchTray starts the tray now, in this login, when none is attached — so an
// install or an update shows the icon without waiting for the next sign-in.
// Never from an elevated process (Windows `core install` runs elevated): that
// tray would run as administrator in the person's desktop.
func launchTray(bin string, st coreSnapshot) {
	if st.Tray || runtime.GOOS == "android" || !proc.InteractiveSession() || proc.Elevated() {
		return
	}
	// A tray that is reconnecting to the restarted core (and handing itself to
	// the new binary) is not attached for a moment, but it holds the lock.
	if tray.Held(filepath.Join(paths().Runtime, "tray.lock")) {
		return
	}
	if err := proc.StartDetached(bin, "core", "tray"); err != nil {
		fmt.Println("  could not start the tray now: " + err.Error() + " (it starts at your next sign-in)")
		return
	}
	fmt.Println("  started the tray")
}
