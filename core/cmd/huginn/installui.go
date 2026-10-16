package main

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/silencelen/huginn/core/internal/clientkit"
	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/proc"
	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/tray"
	"github.com/silencelen/huginn/core/internal/ui"
	"github.com/silencelen/huginn/core/internal/version"
)

// `huginn core install` as an installer: one line per component (binary,
// CLI, service, linger, tray), the detail only where something needs a look.
// The service package's step-by-step report is the source; this folds it.

// installBinaryUI is selfInstall with lines instead of a step list. compact
// (the shell client already said "core 2.4.0 installed"): an unchanged
// binary and CLI are not repeated.
func installBinaryUI(u *ui.Out, compact bool) (string, bool) {
	home, _ := os.UserHomeDir()
	l, err := install.DefaultLayout()
	if err != nil {
		u.Line(ui.Fail, "binary", err.Error())
		return "", false
	}
	exe, err := os.Executable()
	if err != nil {
		u.Line(ui.Fail, "binary", err.Error())
		return "", false
	}
	u.Spin("binary", "installing huginn "+version.Version)
	r, err := install.Install(l, exe, version.Version)
	if err != nil {
		u.Line(ui.Fail, "binary", "could not install: "+err.Error())
		u.Note(strings.Join(r.Steps, "\n"))
		return "", false
	}
	if r.Changed || !compact {
		where := r.Binary
		if l.Link != "" {
			where = l.Link
		}
		u.Line(ui.OK, "binary", version.Version+" at "+tilde(home, where))
		for _, s := range r.Steps {
			if strings.Contains(s, "PATH") {
				u.Note(s)
			}
		}
	}
	e := clientkit.DefaultEnv()
	steps, err := clientkit.Install(e)
	switch {
	case err != nil:
		u.Line(ui.Warn, "CLI", "could not install the huginn CLI: "+err.Error())
	case len(steps) > 0 || !compact:
		u.Line(ui.OK, "CLI", cliSummary(home, e, steps))
	}
	return r.Binary, true
}

// cliSummary: where the CLI is and what loads it.
func cliSummary(home string, e clientkit.Env, steps []string) string {
	var rc []string
	fresh := false
	for _, s := range steps {
		if f, ok := strings.CutPrefix(s, "the CLI loads in new shells: "); ok {
			rc = append(rc, filepath.Base(f))
		}
		fresh = fresh || strings.HasPrefix(s, "installed the huginn CLI")
	}
	script := tilde(home, e.Script())
	switch {
	case len(rc) > 0:
		return "installed " + script + "; new shells load it (" + strings.Join(rc, ", ") + ")"
	case fresh:
		return "installed " + script
	case len(steps) > 0:
		return "updated " + script
	case runtime.GOOS == "windows":
		return script + ", loaded by your PowerShell profile"
	}
	return script + ", loaded by your shell"
}

// stepComp is the component a service step belongs to.
func stepComp(name string) string {
	switch {
	case name == "check binary":
		return "binary"
	case strings.HasPrefix(name, "linger"):
		return "linger"
	case strings.Contains(name, "tray"), strings.Contains(name, service.TrayTaskName):
		return "tray"
	}
	return "service"
}

type compResult struct {
	comp  string
	steps []service.Step
}

// fold groups a report's steps by component, in the order they ran.
func fold(rep service.Report) []compResult {
	var out []compResult
	idx := map[string]int{}
	for _, s := range rep.Steps {
		c := stepComp(s.Name)
		i, ok := idx[c]
		if !ok {
			i = len(out)
			idx[c] = i
			out = append(out, compResult{comp: c})
		}
		out[i].steps = append(out[i].steps, s)
	}
	return out
}

// settleComp prints one folded component. okText says what an all-good
// component reads; "" prints nothing for it (the binary's check: its own
// line came first).
func settleComp(u *ui.Out, c compResult, okText string) {
	var failed, warn, skipped *service.Step
	notDone, attempted := 0, 0
	for i := range c.steps {
		s := &c.steps[i]
		switch s.State {
		case service.Failed:
			failed = s
		case service.Warning:
			if warn == nil {
				warn = s
			}
		case service.NotDone:
			notDone++
			continue
		case service.Skipped:
			if skipped == nil {
				skipped = s
			}
		}
		if s.State != service.Skipped {
			attempted++
		}
	}
	switch {
	case failed != nil:
		u.Line(ui.Fail, c.comp, failed.Name+" failed")
		if failed.Detail != "" {
			u.Note(failed.Detail)
		}
	case notDone == len(c.steps):
		u.Line(ui.Skip, c.comp, "not done: an earlier step failed")
	case warn != nil:
		u.Line(ui.Warn, c.comp, warn.Detail)
	case attempted == 0 && skipped != nil:
		u.Line(ui.Skip, c.comp, skipped.Detail)
	case okText != "":
		u.Line(ui.OK, c.comp, okText)
	}
}

// renderServiceReport prints every component but the tray, and hands the
// tray's back: its line waits until the tray has (or has not) been started.
func renderServiceReport(u *ui.Out, rep service.Report, okText func(compResult) string) (trayC *compResult) {
	for _, c := range fold(rep) {
		if c.comp == "tray" {
			cc := c
			trayC = &cc
			continue
		}
		settleComp(u, c, okText(c))
	}
	for _, n := range rep.Notes {
		u.Note(n)
	}
	return trayC
}

func lingerText(c compResult) string {
	for _, s := range c.steps {
		if s.State == service.Done {
			return "turned on: huginn core runs at boot, with nobody logged in"
		}
	}
	return "on: huginn core runs at boot, with nobody logged in"
}

// afterServiceInstallUI waits for the new service to answer, starts the tray
// in this login when none is up, and settles the tray's line.
func afterServiceInstallUI(u *ui.Out, bin string, autostart bool, trayC *compResult) {
	if autostart {
		markTray()
	}
	u.Spin("core", "waiting for huginn core to answer")
	st, running := waitCore(15*time.Second, func(_ coreSnapshot, up bool) bool { return up })
	if !running {
		u.Line(ui.Warn, "core", "installed, but not answering yet - huginn core status says why")
	}
	started, why := false, ""
	if running {
		started, why = startTrayNow(bin, st)
	}
	if trayC == nil {
		return
	}
	at := "starts at login"
	if runtime.GOOS == "windows" {
		at = "starts at sign-in"
	}
	switch {
	case started:
		at += "; started now"
	case why != "":
		at += "; " + why
	}
	settleComp(u, *trayC, at)
}

// startTrayNow starts the tray in this login when none is attached (or
// holding its lock). false, "" when there is nothing to do or it must not be
// done here (no desktop session; an elevated process, whose tray would run as
// administrator in the person's desktop).
func startTrayNow(bin string, st coreSnapshot) (bool, string) {
	if st.Tray || runtime.GOOS == "android" || !proc.InteractiveSession() || proc.Elevated() {
		return false, ""
	}
	if tray.Held(filepath.Join(paths().Runtime, "tray.lock")) {
		return false, ""
	}
	if err := proc.StartDetached(bin, "core", "tray"); err != nil {
		return false, "could not start it now (" + err.Error() + "); it starts at your next sign-in"
	}
	return true, ""
}
