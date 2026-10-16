package main

import (
	"bufio"
	"fmt"
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
	"github.com/silencelen/huginn/core/internal/store"
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
// their own copy remains only for platforms with no core (macOS, Termux).

type tally struct{ removed, left []string }

func (t *tally) gone(s string)  { t.removed = append(t.removed, s) }
func (t *tally) stays(s string) { t.left = append(t.left, s) }

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

	fmt.Println("huginn uninstall removes, from THIS machine, top down:")
	fmt.Println("  the desktop app (Huginn Desktop) and its settings, which hold a copy of the appd token")
	fmt.Println("  " + ldir)
	fmt.Println("      the local-AI tier: models, sessions, runtime (can be several GB)")
	fmt.Println("  this machine's device row on huginn (retired first, while the token still exists)")
	fmt.Println("  huginn core: its boot service, the tray, " + l.Root + ", " + p.Config + ", " + p.State)
	fmt.Println("  the huginn CLI: " + ce.Dir() + " and its line in your shell startup files")
	if all {
		fmt.Println("  --all: the 'Host huginn' SSH stanza, and its key IF it is huginn's own")
	}
	fmt.Println()
	if !yes {
		fmt.Print(`Type "uninstall" to continue: `)
		ans, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		if strings.TrimSpace(ans) != "uninstall" {
			fmt.Println("Nothing was removed.")
			return 1
		}
		fmt.Println()
	}
	var t tally

	fmt.Println("==> desktop app")
	removeDesktop(&t, home)

	if dirExists(ldir) {
		fmt.Println("==> local AI tier")
		mgr := filepath.Join(ce.Dir(), "huginn-local")
		node, nerr := exec.LookPath("node")
		if nerr == nil && fileExists(mgr) {
			c := exec.Command(node, mgr, "off", "--purge", "--yes")
			c.Stdin, c.Stdout, c.Stderr = os.Stdin, os.Stdout, os.Stderr
			if c.Run() == nil {
				t.gone(ldir)
			} else {
				t.stays(ldir + " - 'huginn local off --purge' did not finish; run it again when huginn is reachable")
			}
		} else {
			t.stays(ldir + " - no manager or no node here, so nothing could unenrol or remove it")
		}
	}

	fmt.Println("==> device")
	retireDevice(&t, p)

	fmt.Println("==> huginn core")
	removeService(&t)
	stopCoreProcesses(l.Root)
	if st, err := store.Open(p); err == nil {
		if cfg, _, err := st.Load(); err == nil {
			if sec, sel := daemon.OpenSecretsForService(p, cfg.Secrets); sel.Backend != store.BackendFile {
				if err := sec.Delete(store.SecretToken); err != nil {
					t.stays("the appd token in the " + sel.Backend + " - " + err.Error())
				}
			}
		}
	}
	removeCoreFiles(&t, home, p, l)

	fmt.Println("==> huginn CLI")
	r, lf := clientkit.Remove(ce)
	t.removed = append(t.removed, r...)
	t.left = append(t.left, lf...)

	sshStep(&t, home, all)

	fmt.Println()
	fmt.Println("Removed:")
	if len(t.removed) == 0 {
		fmt.Println("  (nothing - was huginn installed here?)")
	}
	for _, s := range t.removed {
		fmt.Println("  " + s)
	}
	if len(t.left) > 0 {
		fmt.Println()
		fmt.Println("Left behind, on purpose or because it could not be done:")
		for _, s := range t.left {
			fmt.Println("  " + s)
		}
	}
	fmt.Println()
	fmt.Println("A shell that already loaded the CLI still has the 'huginn' function: open a new one.")
	if len(t.left) > 0 {
		return 1
	}
	return 0
}

func dirExists(p string) bool { fi, err := os.Stat(p); return err == nil && fi.IsDir() }

func localTierDir(home string) string {
	if d := os.Getenv("HUGINN_LOCAL_DIR"); d != "" {
		return d
	}
	if runtime.GOOS == "windows" {
		pd := os.Getenv("ProgramData")
		if pd == "" {
			pd = `C:\ProgramData`
		}
		return filepath.Join(pd, "huginn-local")
	}
	return filepath.Join(home, ".config", "huginn-local")
}

// retireDevice turns the device off through the running core, which deletes
// the row on appd, and waits for that to land: the core and its token are
// about to go, and a row nobody can retire stays in `huginn devices` forever.
func retireDevice(t *tally, p store.Paths) {
	st, up := coreState(3 * time.Second)
	if !up && serviceInstalled(stableBinary()) {
		startService()
		st, up = waitCore(20*time.Second, func(_ coreSnapshot, up bool) bool { return up })
	}
	if !up {
		if s, err := store.Open(p); err == nil {
			if cfg, _, err := s.Load(); err == nil && cfg.Device.ID != "" {
				t.stays("device " + cfg.Device.ID + " may still be enrolled on huginn (the core was not running to retire it) - remove it from `huginn devices`")
				return
			}
		}
		fmt.Println("  no device enrolled here")
		return
	}
	if !st.Device.Status.Enrolled && st.Device.Status.DeviceID == "" {
		fmt.Println("  no device enrolled here")
		return
	}
	off := false
	if _, err := call(http.MethodPatch, "/core/device", daemon.DeviceSettings{Enabled: &off}, nil); err != nil {
		t.stays("this machine's device row - turning it off failed: " + err.Error())
		return
	}
	end, ok := waitCore(20*time.Second, func(s coreSnapshot, up bool) bool {
		return up && !s.Device.Status.Enrolled && s.Device.Status.DeviceID == ""
	})
	if ok && !end.Device.Status.Enrolled && end.Device.Status.DeviceID == "" {
		t.gone("this machine's device row on huginn")
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
		t.stays("the 'Host huginn' stanza in " + cfg + " and its key - kept (use 'huginn uninstall --all' to remove them)")
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
		t.stays(key + " - NOT removed: nothing marks it as huginn's, and it is very likely your general SSH key")
	}
}
