package service

import (
	"strings"
	"testing"
)

// A core in the middle of a device job is not restarted by an install: the
// unit's restart (Windows: ending the task) would stop the job's claude with
// it. The caller asks that core to restart itself after the job.

func TestInstallLinuxLeavesABusyCoreRunning(t *testing.T) {
	f := &fake{rules: []rule{{prefix: "loginctl show-user", res: Result{Stdout: "Linger=yes\n"}}}}
	o := linuxOpts(t, f)
	o.KeepRunning = true
	rep, _ := InstallLinux(o)
	if !rep.OK() {
		t.Fatalf("%s", rep)
	}
	if f.called("systemctl --user restart") {
		t.Fatalf("restarted a core in the middle of a job: %q", f.calls)
	}
	// A unit that is down is still started.
	if !f.called("systemctl --user start " + UnitName) {
		t.Fatalf("the unit was not started: %q", f.calls)
	}
	if s := rep.Steps[4]; !strings.HasPrefix(s.Name, "(re)start") || !strings.Contains(s.Detail, "in the middle of a job") {
		t.Fatalf("step %+v", s)
	}
}

func TestInstallWindowsLeavesABusyCoreRunning(t *testing.T) {
	f := &fake{}
	o := winOpts(t, f)
	o.NoTray = true
	o.KeepRunning = true
	rep := InstallWindows(o)
	if !rep.OK() {
		t.Fatalf("%s", rep)
	}
	if f.called("schtasks /End") {
		t.Fatalf("ended a core in the middle of a job: %q", f.calls)
	}
	if !f.called("schtasks /Create /TN " + TaskName) {
		t.Fatalf("the task was not registered: %q", f.calls)
	}
}
