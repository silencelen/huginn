package main

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"time"

	"github.com/silencelen/huginn/core/internal/clientkit"
	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/localtier"
	"github.com/silencelen/huginn/core/internal/proc"
	"github.com/silencelen/huginn/core/internal/store"
	"github.com/silencelen/huginn/core/internal/ui"
	"github.com/silencelen/huginn/core/internal/version"
)

// `huginn uninstall [--all] [--yes]` takes the whole stack off this machine,
// top down (owner rule 2026-10-15: huginn core, which includes the CLI, is the
// product, and the desktop is a layer on top of it — so removing the core
// removes what stands on it):
//
//	1. the desktop app        (it cannot work without what follows)
//	2. the local-AI tier      (its manager lives in ~/.huginn, removed in 5)
//	3. the device row         (while the core and its token still exist)
//	4. huginn core            (service, tray, binary, config, secrets)
//	5. the CLI                (~/.huginn and the shell startup lines)
//	6. --all: the `Host huginn` SSH stanza, and its key if it is huginn's own
//
// The shell clients hand `huginn uninstall` here whenever a core is installed;
// their own copy remains only for platforms with no core (Termux).

// tally is one component's outcome: what went, what stays on purpose, and
// what could not be done (the only kind that fails the run).
type tally struct {
	comp, home string
	u          *ui.Out
	log        io.Writer
	removed    []string
	kept, left []string
}

func (t *tally) gone(s string)  { t.removed = append(t.removed, s) }
func (t *tally) keep(s string)  { t.kept = append(t.kept, s) }
func (t *tally) stays(s string) { t.left = append(t.left, s) }
func (t *tally) spin(what string) {
	t.u.Spin(t.comp, what)
}
func (t *tally) logf(format string, a ...any) { fmt.Fprintf(t.log, format+"\n", a...) }

// settle prints the component's one line: done says what went, none what a
// machine without it reads. Anything that could not be done follows as a
// note, so the line a person scans is the line they act on.
func (t *tally) settle(done, none string) {
	fmt.Fprintf(t.log, "\n[%s]\n", t.comp)
	for _, s := range t.removed {
		fmt.Fprintln(t.log, "  removed  "+s)
	}
	for _, s := range t.kept {
		fmt.Fprintln(t.log, "  kept     "+s)
	}
	for _, s := range t.left {
		fmt.Fprintln(t.log, "  LEFT     "+s)
	}
	if len(t.removed)+len(t.kept)+len(t.left) == 0 {
		fmt.Fprintln(t.log, "  nothing here")
	}
	switch {
	case len(t.left) > 0 && len(t.removed) == 0:
		t.u.Line(ui.Fail, t.comp, "could not be removed")
	case len(t.left) > 0:
		t.u.Line(ui.Warn, t.comp, "removed "+done+", but not all of it")
	case len(t.removed) > 0:
		t.u.Line(ui.OK, t.comp, "removed "+done)
	case len(t.kept) > 0:
		// Nothing went, on purpose: the reason is the line.
		t.u.Line(ui.Skip, t.comp, "kept "+t.kept[0])
		t.kept = t.kept[1:]
	default:
		t.u.Line(ui.Skip, t.comp, none)
	}
	short := func(s string) string {
		if t.home == "" {
			return s
		}
		return strings.ReplaceAll(s, t.home+string(filepath.Separator), "~"+string(filepath.Separator))
	}
	for _, s := range t.left {
		t.u.Note(short(s))
	}
	for _, s := range t.kept {
		t.u.Note("kept " + short(s))
	}
}

func uninstall(args []string) int {
	all, yes := false, false
	for _, a := range args {
		switch a {
		case "--all":
			all = true
		case "--yes":
			yes = true
		default:
			fmt.Fprintln(os.Stderr, "usage: huginn uninstall [--all] [--yes]")
			return 2
		}
	}
	if runtime.GOOS == "android" {
		fmt.Fprintln(os.Stderr, "huginn uninstall: on Termux, remove the client with the shell's own `huginn uninstall` (source ~/.huginn/huginn.sh first)")
		return 2
	}
	home, _ := os.UserHomeDir()
	if home == "" {
		fmt.Fprintln(os.Stderr, "huginn uninstall: no home directory - refusing to guess where anything lives")
		return 1
	}
	p := paths()
	l, err := install.DefaultLayout()
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn uninstall: "+err.Error())
		return 1
	}
	ce := clientkit.DefaultEnv()
	ldir := localTierDir(home)
	u := ui.New()
	h := func(s string) string { return tilde(home, s) }

	// The plan: what is here, what goes, in the order it goes.
	u.Head("huginn uninstall")
	u.Say("Takes huginn off this machine, top down:")
	u.Say("")
	row := func(here bool, comp, what, absent string) {
		if here {
			u.Row(comp, what, false)
		} else {
			u.Row(comp, absent, true)
		}
	}
	svc := serviceInstalled(stableBinary())
	row(desktopPresent(home), "desktop", "Huginn Desktop and its settings (they hold a copy of the appd token)", "not installed here")
	localSize := ""
	if dirExists(ldir) {
		localSize = humanSize(dirSize(ldir))
	}
	row(dirExists(ldir), "local AI", h(ldir)+": models, sessions, runtime ("+localSize+") - asked separately", "not installed here")
	row(deviceEnrolled(p), "device", "this machine's row on huginn, retired first, while the token exists", "none enrolled here")
	coreWhat := h(l.Root) + ", " + h(p.Config) + ", " + h(p.State)
	if svc {
		coreWhat = "the boot service, the tray, " + coreWhat
	}
	row(svc || dirExists(l.Root), "core", coreWhat, "not installed here")
	row(dirExists(ce.Dir()), "CLI", h(ce.Dir())+" and its line in your shell startup files", "not installed here")
	switch {
	case !sshStanzaPresent(home):
		u.Row("ssh", "no 'Host huginn' stanza here", true)
	case all:
		u.Row("ssh", "the 'Host huginn' stanza, and its key if it is huginn's own", false)
	default:
		u.Row("ssh", "kept: 'Host huginn' and your key (--all removes them)", true)
	}
	if runtime.GOOS == "windows" && svc {
		u.Say("")
		u.Say("Windows asks for administrator approval once, to delete the boot task.")
	}
	u.Say("")
	in := bufio.NewReader(os.Stdin)
	if !yes {
		u.Prompt(`Type "uninstall" to continue: `)
		ans, _ := in.ReadString('\n')
		if strings.TrimSpace(ans) != "uninstall" {
			u.Say("Nothing was removed.")
			return 1
		}
	}
	// The local-AI tier is asked about on its own (owner rule 2026-10-16): it
	// is gigabytes that are slow to fetch again, it is machine-wide, and on
	// Windows taking it off needs an administrator. Default yes: uninstall is
	// the whole stack.
	removeLocal := true
	if dirExists(ldir) && !yes {
		q := "Also remove the local AI tier (" + localSize + " of models, sessions and runtime)?"
		if runtime.GOOS == "windows" && !proc.Elevated() {
			q += " Windows asks for administrator approval for it."
		}
		u.Prompt(q + " [Y/n] ")
		ans, _ := in.ReadString('\n')
		a := strings.ToLower(strings.TrimSpace(ans))
		removeLocal = !(a == "n" || a == "no")
	}
	if !yes {
		u.Say("")
	}

	// Every path and every step's own output goes to a log outside what is
	// being removed: the screen gets one line per component.
	logPath := filepath.Join(os.TempDir(), "huginn-uninstall-"+time.Now().Format("20060102-150405")+".log")
	lf, err := os.OpenFile(logPath, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
	var log io.Writer = io.Discard
	if err == nil {
		defer lf.Close()
		log = lf
		fmt.Fprintf(lf, "huginn uninstall %s - %s\n", version.Version, time.Now().Format(time.RFC3339))
	} else {
		logPath = ""
	}
	failed := false
	part := func(comp string) *tally { return &tally{comp: comp, home: home, u: u, log: log} }
	end := func(t *tally, done, none string) {
		t.settle(done, none)
		failed = failed || len(t.left) > 0
	}

	t := part("desktop")
	removeDesktop(t, home)
	end(t, "Huginn Desktop and its settings", "not installed")

	t = part("local AI")
	if dirExists(ldir) && !removeLocal {
		t.keep(h(ldir) + " - you chose to keep it; its services keep running (reinstall huginn, then huginn local off --purge, to remove it later)")
	} else if dirExists(ldir) {
		removeLocalTier(t, ce, ldir, h(ldir))
	}
	end(t, "the local-AI tier", "not installed")

	t = part("device")
	retireDevice(t, p)
	end(t, "this machine's device row on huginn", "none enrolled here")

	t = part("core")
	removeService(t)
	t.spin("stopping huginn and removing its files")
	stopCoreProcesses(l.Root)
	if st, err := store.Open(p); err == nil {
		if cfg, _, err := st.Load(); err == nil {
			if sec, sel := daemon.OpenSecretsForService(p, cfg.Secrets); sel.Backend != store.BackendFile {
				if err := sec.Delete(store.SecretToken); err != nil {
					t.stays("the appd token in the " + sel.Backend + " - " + err.Error())
				} else {
					t.gone("the appd token in the " + sel.Backend)
				}
			}
		}
	}
	removeCoreFiles(t, home, p, l)
	coreDone := "the binary, its config and state"
	if svc {
		coreDone = "the boot service, the tray, " + coreDone
	}
	end(t, coreDone, "not installed")

	t = part("CLI")
	r, lft := clientkit.Remove(ce)
	t.removed = append(t.removed, r...)
	for _, s := range lft {
		t.stays(s)
	}
	end(t, h(ce.Dir())+" and its shell startup line", "not installed")

	t = part("ssh")
	sshStep(t, home, all)
	end(t, "the 'Host huginn' stanza", "no 'Host huginn' stanza here")

	u.Say("")
	if failed {
		u.Say("huginn is off this machine, except what is marked above.")
	} else {
		u.Say("huginn is off this machine.")
	}
	u.Say("Open a new terminal: this one still has the 'huginn' function loaded.")
	if logPath != "" {
		u.Say("Everything that was removed, path by path: " + logPath)
	}
	u.Took("done")
	if failed {
		return 1
	}
	return 0
}

// removeLocalTier runs the tier's own `huginn local off --purge`, which also
// unenrols its runner while huginn is still reachable. On Windows the tier is
// LocalSystem services under ProgramData, so an unelevated uninstall runs it
// elevated - one UAC prompt, a hidden window, its output into the log.
func removeLocalTier(t *tally, ce clientkit.Env, ldir, shown string) {
	mgr := filepath.Join(ce.Dir(), "huginn-local")
	node, nerr := exec.LookPath("node")
	if nerr == nil && !fileExists(mgr) {
		// The CLI is gone (an earlier uninstall removed it after the tier
		// refused to go): the manager this core carries does the same job.
		if f, err := os.CreateTemp("", "huginn-local-*.js"); err == nil {
			f.Write(clientkit.LocalManager())
			f.Close()
			defer os.Remove(f.Name())
			mgr = f.Name()
		}
	}
	if nerr != nil || !fileExists(mgr) {
		t.stays(shown + " - no node here, so nothing could unenrol or remove it")
		return
	}
	args := []string{mgr, "off", "--purge", "--yes"}
	if runtime.GOOS == "windows" && !proc.Elevated() {
		t.spin("removing models, sessions and the runtime (approve the administrator prompt)")
		elog := filepath.Join(os.TempDir(), "huginn-local-off-"+time.Now().Format("150405")+".log")
		rc, err := runElevatedWindows(node, args, elog)
		if b, rerr := os.ReadFile(elog); rerr == nil {
			t.log.Write(bytes.TrimPrefix(b, []byte("\xef\xbb\xbf"))) // Set-Content's BOM
			os.Remove(elog)
		}
		switch {
		case err != nil:
			t.stays(shown + " - could not ask for administrator approval (" + err.Error() + ")")
		case rc == 1223:
			t.stays(shown + " - administrator approval was declined; from an administrator terminal: huginn local off --purge")
		case rc != 0 || dirExists(ldir):
			t.stays(shown + " - 'huginn local off --purge' did not finish (the log has its output); run it again from an administrator terminal")
		default:
			t.gone(ldir)
		}
		return
	}
	t.spin("removing models, sessions and the runtime")
	c := exec.Command(node, args...)
	c.Stdout, c.Stderr = t.log, t.log
	if c.Run() == nil {
		t.gone(ldir)
	} else {
		t.stays(shown + " - 'huginn local off --purge' did not finish (the log has its output); run it again when huginn is reachable")
	}
}

// dirSize is the bytes under dir (best effort: unreadable entries count 0).
func dirSize(dir string) int64 {
	var n int64
	filepath.WalkDir(dir, func(_ string, d os.DirEntry, err error) error {
		if err == nil && !d.IsDir() {
			if fi, err := d.Info(); err == nil {
				n += fi.Size()
			}
		}
		return nil
	})
	return n
}

func humanSize(n int64) string {
	switch {
	case n >= 1<<30:
		return fmt.Sprintf("%.1f GB", float64(n)/(1<<30))
	case n >= 1<<20:
		return fmt.Sprintf("%d MB", n>>20)
	}
	return fmt.Sprintf("%d KB", n>>10)
}

// tilde shortens a path under home for the screen (the log keeps it whole).
func tilde(home, p string) string {
	if home != "" && strings.HasPrefix(p, home+string(filepath.Separator)) {
		return "~" + p[len(home):]
	}
	return p
}

// deviceEnrolled: this core's config names a device on huginn.
func deviceEnrolled(p store.Paths) bool {
	s, err := store.Open(p)
	if err != nil {
		return false
	}
	cfg, _, err := s.Load()
	return err == nil && cfg.Device.ID != ""
}

func dirExists(p string) bool { fi, err := os.Stat(p); return err == nil && fi.IsDir() }

func localTierDir(home string) string { return localtier.Dir(home) }

// retireDevice turns the device off through the running core, which deletes
// the row on appd, and waits for that to land: the core and its token are
// about to go, and a row nobody can retire stays in `huginn devices` forever.
func retireDevice(t *tally, p store.Paths) {
	t.spin("asking huginn core to retire this machine")
	st, up := coreState(3 * time.Second)
	if !up && !deviceEnrolled(p) {
		return // nothing to retire, so no reason to start a core for it
	}
	if !up && serviceInstalled(stableBinary()) {
		t.spin("starting huginn core, so it can retire this machine")
		startService()
		st, up = waitCore(20*time.Second, func(_ coreSnapshot, up bool) bool { return up })
	}
	if !up {
		if s, err := store.Open(p); err == nil {
			if cfg, _, err := s.Load(); err == nil && cfg.Device.ID != "" {
				t.stays("device " + cfg.Device.ID + " may still be enrolled on huginn (the core was not running to retire it) - remove it from `huginn devices`")
			}
		}
		return
	}
	if !st.Device.Status.Enrolled && st.Device.Status.DeviceID == "" {
		return
	}
	id := st.Device.Status.DeviceID
	t.spin("retiring " + id + " on huginn")
	off := false
	if _, err := call(http.MethodPatch, "/core/device", daemon.DeviceSettings{Enabled: &off}, nil); err != nil {
		t.stays("this machine's device row - turning it off failed: " + err.Error())
		return
	}
	end, ok := waitCore(20*time.Second, func(s coreSnapshot, up bool) bool {
		return up && !s.Device.Status.Enrolled && s.Device.Status.DeviceID == ""
	})
	if ok && !end.Device.Status.Enrolled && end.Device.Status.DeviceID == "" {
		t.gone("device " + id + " on huginn")
		return
	}
	t.stays("this machine's device row may still be on huginn (appd did not confirm in 20 s) - remove it from `huginn devices`")
}

// removeCoreFiles: the binary, its versions, config (core.json, the file
// secrets store), state and the runtime dir.
func removeCoreFiles(t *tally, home string, p store.Paths, l install.Layout) {
	rm := func(path string) {
		if path == "" {
			return
		}
		if _, err := os.Lstat(path); err != nil {
			return
		}
		if err := os.RemoveAll(path); err != nil {
			t.stays(path + " - " + err.Error())
			return
		}
		t.gone(path)
	}
	if l.Link != "" {
		if dst, err := os.Readlink(l.Link); err == nil && strings.HasPrefix(dst, l.Root+string(filepath.Separator)) {
			rm(l.Link)
		}
	}
	rm(p.Config)
	if runtime.GOOS == "windows" {
		// The pre-core Node runner's enrolment lived here on Windows.
		rm(filepath.Join(home, ".config", "huginn"))
		removeFromPath(t, filepath.Join(l.Root, "bin"))
		removeRootLater(t, l.Root)
		return
	}
	rm(p.State)
	rm(p.Runtime)
	rm(l.Root)
}

// sshStanzaPresent: ~/.ssh/config has a `Host huginn` block.
func sshStanzaPresent(home string) bool {
	b, err := os.ReadFile(filepath.Join(home, ".ssh", "config"))
	if err != nil {
		return false
	}
	for _, line := range strings.Split(string(b), "\n") {
		if f := strings.Fields(line); len(f) == 2 && strings.EqualFold(f[0], "host") && f[1] == "huginn" {
			return true
		}
	}
	return false
}

// sshStep: kept by default (the key is very likely the person's general one);
// --all takes the `Host huginn` stanza, and the key only when it is marked as
// huginn's (its name or its .pub comment says huginn).
func sshStep(t *tally, home string, all bool) {
	cfg := filepath.Join(home, ".ssh", "config")
	b, err := os.ReadFile(cfg)
	if err != nil {
		return
	}
	var out []string
	key, in, found := "", false, false
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) > 0 && strings.EqualFold(f[0], "host") {
			in = len(f) == 2 && f[1] == "huginn"
			found = found || in
		}
		if in && len(f) >= 2 && strings.EqualFold(f[0], "identityfile") && key == "" {
			key = f[1]
		}
		if !in {
			out = append(out, line)
		}
	}
	if !found {
		return
	}
	if strings.HasPrefix(key, "~/") || strings.HasPrefix(key, `~\`) {
		key = filepath.Join(home, key[2:])
	}
	ours := key != "" && strings.Contains(strings.ToLower(filepath.Base(key)), "huginn")
	if !ours && key != "" {
		if pub, err := os.ReadFile(key + ".pub"); err == nil && strings.Contains(strings.ToLower(string(pub)), "huginn") {
			ours = true
		}
	}
	if !all {
		t.keep("'Host huginn' in " + tilde(home, cfg) + " and its key (huginn uninstall --all removes them)")
		return
	}
	if err := store.WriteFileAtomic(cfg, []byte(strings.Join(out, "\n")), 0o600); err != nil {
		t.stays("the 'Host huginn' stanza in " + cfg + " - " + err.Error())
	} else {
		t.gone("the 'Host huginn' stanza in " + cfg)
	}
	switch {
	case ours && fileExists(key):
		os.Remove(key)
		os.Remove(key + ".pub")
		t.gone(key + " and " + key + ".pub (huginn's own key)")
	case key != "":
		t.keep(tilde(home, key) + ": nothing marks it as huginn's, and it is very likely your general SSH key")
	}
}
