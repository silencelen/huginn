//go:build linux

package main

import (
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/version"
)

// `huginn core install` runs on every re-run of the one-liner and from the
// console's i. A core in the middle of a device job must come through it with
// the job: systemd's restart of the unit stops the job's claude with it. Such
// a core is left running and asked to restart itself after the job, as an
// update asks it.

// runningCore stands in for this user's core on its socket.
type runningCore struct {
	mu       sync.Mutex
	busy     bool
	restarts int // POST /core/restart
}

func (c *runningCore) asked() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.restarts
}

// scratchMachine points every path `core install` uses into a temp dir, with
// a PATH that holds only stand-ins for systemctl (which logs what it is
// asked) and loginctl: nothing reaches a real service manager or a real core.
// It returns the systemctl log and the core's socket path.
func scratchMachine(t *testing.T) (systemctl func() string, sock string) {
	t.Helper()
	if fileExists("/usr/bin/huginn") {
		t.Skip("a packaged huginn is installed here, and core install refuses to install beside it")
	}
	root := t.TempDir()
	home, bin, run := filepath.Join(root, "home"), filepath.Join(root, "bin"), filepath.Join(root, "run")
	for _, d := range []string{home, bin, filepath.Join(run, "huginn")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	log := filepath.Join(root, "systemctl.log")
	os.WriteFile(filepath.Join(bin, "systemctl"), []byte("#!/bin/sh\necho \"$*\" >> '"+log+"'\n"), 0o755)
	os.WriteFile(filepath.Join(bin, "loginctl"), []byte("#!/bin/sh\necho Linger=yes\n"), 0o755)
	for k, v := range map[string]string{
		"PATH": bin, "HOME": home, "XDG_RUNTIME_DIR": run, "HUGINN_CONFIG_DIR": "",
		"XDG_CONFIG_HOME": filepath.Join(home, ".config"), "XDG_DATA_HOME": filepath.Join(home, ".local", "share"),
		"XDG_STATE_HOME": filepath.Join(home, ".local", "state"), "DISPLAY": "", "WAYLAND_DISPLAY": "",
		"SHELL": "/bin/bash", "HUGINN_UI_COMPACT": "",
	} {
		t.Setenv(k, v)
	}
	return func() string { b, _ := os.ReadFile(log); return string(b) }, filepath.Join(run, "huginn", "core.sock")
}

func (c *runningCore) serve(t *testing.T, sock string) {
	t.Helper()
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /core/status", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		json.NewEncoder(w).Encode(map[string]any{"version": version.Version, "tray": true,
			"device": map[string]any{"status": map[string]any{"enabled": true, "enrolled": true, "busy": c.busy}}})
	})
	mux.HandleFunc("POST /core/restart", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.restarts++
		w.WriteHeader(202)
		json.NewEncoder(w).Encode(map[string]any{"restarting": true, "afterJob": c.busy, "version": version.Version})
	})
	srv := &http.Server{Handler: mux}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
}

// installQuietly runs `huginn core install --no-tray` with its output caught.
func installQuietly(t *testing.T) (int, string) {
	t.Helper()
	f, err := os.CreateTemp(t.TempDir(), "out")
	if err != nil {
		t.Fatal(err)
	}
	defer f.Close()
	stdout, stderr := os.Stdout, os.Stderr
	os.Stdout, os.Stderr = f, f
	rc := coreInstall([]string{"--no-tray"})
	os.Stdout, os.Stderr = stdout, stderr
	b, _ := os.ReadFile(f.Name())
	return rc, string(b)
}

func TestCoreInstallLeavesAJobRunning(t *testing.T) {
	systemctl, sock := scratchMachine(t)
	c := &runningCore{busy: true}
	c.serve(t, sock)
	rc, out := installQuietly(t)
	if rc != 0 {
		t.Fatalf("core install exited %d:\n%s", rc, out)
	}
	if strings.Contains(systemctl(), "--user restart "+service.UnitName) {
		t.Fatalf("core install restarted the unit while the core ran a job, which stops the job:\n%s", systemctl())
	}
	if !strings.Contains(systemctl(), "--user start "+service.UnitName) {
		t.Fatalf("the unit was not started:\n%s", systemctl())
	}
	if c.asked() != 1 {
		t.Fatalf("the core was asked %d times to restart after its job", c.asked())
	}
	if !strings.Contains(out, "running a job: it restarts onto "+version.Version+" when the job is done") {
		t.Fatalf("the install does not say the core restarts after its job:\n%s", out)
	}
}

// With no job running, core install restarts the core onto the installed
// binary at once, as it always has.
func TestCoreInstallRestartsAnIdleCore(t *testing.T) {
	systemctl, sock := scratchMachine(t)
	c := &runningCore{}
	c.serve(t, sock)
	if rc, out := installQuietly(t); rc != 0 {
		t.Fatalf("core install exited %d:\n%s", rc, out)
	}
	if !strings.Contains(systemctl(), "--user restart "+service.UnitName) {
		t.Fatalf("an idle core was not restarted:\n%s", systemctl())
	}
	if c.asked() != 0 {
		t.Fatalf("an idle core was asked to restart after a job")
	}
}
