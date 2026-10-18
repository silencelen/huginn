package main

import (
	"bufio"
	"context"
	"encoding/json"
	"errors"
	"flag"
	"fmt"
	"io"
	"log"
	"net/http"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
	"strings"
	"syscall"
	"time"

	"github.com/silencelen/huginn/core/internal/clientkit"
	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/proc"
	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/store"
	"github.com/silencelen/huginn/core/internal/tray"
	"github.com/silencelen/huginn/core/internal/ui"
	"github.com/silencelen/huginn/core/internal/version"
	"golang.org/x/term"
)

// core dispatches `huginn core …`.
func core(args []string) int {
	if len(args) == 0 {
		usage()
		return 2
	}
	// Termux is a client only (D15): Android stops background processes, and the
	// phone's presence is the Android app's job.
	if runtime.GOOS == "android" && args[0] != "status" {
		fmt.Fprintln(os.Stderr, "huginn core does not run on Android/Termux — on a phone the huginn app is the device. `huginn` here attaches and runs commands.")
		return 2
	}
	switch args[0] {
	case "run":
		return coreRun()
	case "install":
		return coreInstall(args[1:])
	case "uninstall":
		return coreUninstall()
	case "status":
		return coreStatus()
	case "start":
		return coreStart()
	case "pair":
		return corePair(args[1:])
	case "tray":
		// `core tray show`: start the icon now, detached, and return - the
		// desktop app's "Tray icon" switch turned on (and a person's way back
		// after "Hide this icon"). Not as administrator, and only where a
		// desktop session can show it.
		if len(args) > 1 && args[1] == "show" {
			var st struct {
				Tray bool `json:"tray"`
			}
			if _, err := call(http.MethodGet, "/core/status", nil, &st); err == nil && st.Tray {
				fmt.Println("the tray icon is already showing")
				return 0
			}
			if !proc.InteractiveSession() || proc.Elevated() {
				fmt.Fprintln(os.Stderr, "huginn core tray show: run it from your own desktop session, not as administrator")
				return 1
			}
			if err := proc.StartDetached(stableBinary(), "core", "tray"); err != nil {
				fmt.Fprintln(os.Stderr, "huginn core tray show: "+err.Error())
				return 1
			}
			fmt.Println("started the tray icon")
			return 0
		}
		// The tray role (docs/CORE-ARCHITECTURE.md §3.1): started at graphical
		// login, draws the icon and posts notifications that `core run` decides.
		//
		// --at-login: started by the "Huginn Tray" logon task. The tray at
		// sign-in is ON only while its Startup shortcut is there and enabled
		// (Task Manager's Startup tab), so a person who turned that off gets no
		// tray from the task either, at once rather than after the next update.
		if len(args) > 1 && args[1] == "--at-login" && runtime.GOOS == "windows" {
			if o, err := service.WindowsDefaults(stableBinary()); err == nil &&
				(!fileExists(o.StartupLnk()) || startupDisabled(service.TrayShortcut)) {
				return 0
			}
		}
		tray.DetachConsole()
		p := paths()
		tray.Run(tray.Options{Socket: daemon.SocketPath(p), Lock: filepath.Join(p.Runtime, "tray.lock")})
		return 0
	case "notify-test":
		// Hidden: one sample notification through the real notifier, then
		// withdrawn. Proves delivery without going through appd.
		if err := tray.NotifyTest(8 * time.Second); err != nil {
			fmt.Fprintln(os.Stderr, "huginn core notify-test: "+err.Error())
			return 1
		}
		fmt.Println("posted and withdrew a test notification")
		return 0
	case "route", "routes":
		return coreRoute(args[1:])
	case "update":
		// Taking the newest release is the CLI's job (it fetches, verifies and
		// self-installs); from fish, cmd.exe or a script this binary is what
		// runs, and it printed its usage and exited 2.
		if code, ok := forwardToClient(append([]string{"core"}, args...)); ok {
			return code
		}
		fmt.Fprintln(os.Stderr, "huginn core update runs through the CLI, which is not installed here: run the installer one-liner again, which takes the newest release")
		return 2
	case "self-install":
		_, force := hasFlag(args, "--force")
		if have := newerInstalled(); have != "" && !force {
			// Kept, and a success: the caller (an installer, the app) wanted a
			// core here, and a newer one is.
			fmt.Println("  kept huginn " + have + ": it is newer than this copy (" + version.Version + "); `core self-install --force` installs this one anyway")
			return 0
		}
		bin, ok := selfInstall()
		if !ok {
			return 1
		}
		upgradeRunning(bin)
		return 0
	}
	usage()
	return 2
}

func paths() store.Paths {
	p, err := store.DefaultPaths()
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn: "+err.Error())
		os.Exit(1)
	}
	return p
}

func coreRun() int {
	ctx, stop := signal.NotifyContext(context.Background(), os.Interrupt, syscall.SIGTERM)
	defer stop()
	logger := log.New(os.Stderr, "", log.LstdFlags)
	if err := daemon.Run(ctx, daemon.Options{Paths: paths(), Logger: logger}); err != nil {
		if errors.Is(err, daemon.ErrRestart) {
			return 0 // the supervisor starts the installed binary (see self-install)
		}
		if errors.Is(err, ipc.ErrInUse) {
			fmt.Fprintln(os.Stderr, "huginn: a core is already running for this user (`huginn core status`)")
			return 0 // not a failure for a supervisor: the job is being done
		}
		fmt.Fprintln(os.Stderr, "huginn core: "+err.Error())
		return 1
	}
	return 0
}

// stableBinary is the path a service should start: the per-user install's
// stable path when there is one, else this binary. ⚠ Never a versioned
// directory: the updater swaps `current`, and a unit pinned to versions/2.0.0
// would keep starting the old binary after every update.
func stableBinary() string {
	if l, err := install.DefaultLayout(); err == nil && fileExists(l.Binary()) {
		return l.Binary()
	}
	exe, err := os.Executable()
	if err != nil {
		return ""
	}
	if real, err := filepath.EvalSymlinks(exe); err == nil {
		return real
	}
	return exe
}

// selfInstall copies this binary into the per-user layout and says what it did.
func selfInstall() (string, bool) {
	l, err := install.DefaultLayout()
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn: "+err.Error())
		return "", false
	}
	exe, err := os.Executable()
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn: "+err.Error())
		return "", false
	}
	r, err := install.Install(l, exe, version.Version)
	for _, st := range r.Steps {
		fmt.Println("  " + st)
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn core self-install: "+err.Error())
		return "", false
	}
	// The core includes the CLI: the shell client this version carries.
	steps, err := clientkit.Install(clientkit.DefaultEnv())
	for _, st := range steps {
		fmt.Println("  " + st)
	}
	if err != nil {
		fmt.Println("  could not install the huginn CLI: " + err.Error())
	}
	return r.Binary, true
}

// packagedCore is a distro package's copy, when one exists and is not this
// per-user install. One machine has ONE core (§7.4): two would enrol twice.
func packagedCore(perUser string) string {
	if runtime.GOOS != "linux" {
		return ""
	}
	const sys = "/usr/bin/huginn"
	fs, err := os.Stat(sys)
	if err != nil {
		return ""
	}
	if fu, err := os.Stat(perUser); err == nil && os.SameFile(fs, fu) {
		return ""
	}
	return sys
}

func fileExists(p string) bool { _, err := os.Stat(p); return err == nil }

// lingerMarker records that `core install` enabled linger (it was off before).
func lingerMarker() string { return filepath.Join(paths().State, "linger-enabled") }

func coreInstall(args []string) int {
	// An older copy hands the install to the newer core already here, rather
	// than putting itself in its place (see downgrade.go).
	if rest, force := hasFlag(args, "--force"); !force {
		if have := newerInstalled(); have != "" {
			l, _ := install.DefaultLayout()
			c := exec.Command(l.Binary(), append([]string{"core", "install"}, rest...)...)
			c.Stdin, c.Stdout, c.Stderr = os.Stdin, os.Stdout, os.Stderr
			if err := c.Run(); err != nil {
				if ee, ok := err.(*exec.ExitError); ok {
					return ee.ExitCode()
				}
				fmt.Fprintln(os.Stderr, "huginn core install: "+err.Error())
				return 1
			}
			return 0
		}
	} else {
		args = rest
	}
	fs := flag.NewFlagSet("core install", flag.ContinueOnError)
	logon := fs.String("logon", "s4u", "Windows only: s4u (no stored password) or password")
	trayOn := fs.Bool("tray", false, "Linux: start the tray at login even when this shell has no graphical session (an ssh shell)")
	noTray := fs.Bool("no-tray", false, "do not start the tray at login")
	report := fs.String("report", "", "write the outcome as records to this file instead of the terminal (the PowerShell client's elevated step)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	u := ui.New()
	if *report != "" {
		f, err := os.OpenFile(*report, os.O_CREATE|os.O_WRONLY|os.O_TRUNC, 0o600)
		if err != nil {
			fmt.Fprintln(os.Stderr, "huginn core install: "+err.Error())
			return 1
		}
		defer f.Close()
		u = ui.Records(f)
	}
	// compact: part of a bigger block (the shell client's `huginn core
	// install` drew the title and the download), so no title and no timing.
	compact := os.Getenv("HUGINN_UI_COMPACT") != "" || *report != ""
	if !compact {
		u.Head("huginn core install")
	}
	// Install the binary first, so the service points at the stable per-user
	// path rather than wherever this copy happens to be running from (a
	// download folder, an app bundle).
	bin, ok := installBinaryUI(u, compact)
	if !ok {
		return 1
	}
	if sys := packagedCore(bin); sys != "" {
		u.Line(ui.Fail, "service", sys+" (a package) already provides huginn on this machine - one core per machine")
		u.Note("Use that one (" + sys + " core install), or remove the package first.")
		return 1
	}
	user := currentUser()
	rc := 1
	switch runtime.GOOS {
	case "linux":
		o, err := service.LinuxDefaults(bin)
		if err != nil {
			u.Line(ui.Fail, "service", err.Error())
			return 1
		}
		o.ForceTray, o.NoTray = *trayOn, *noTray
		retireNodeRunner(u)
		u.Spin("service", "installing "+service.UnitName+" and starting it")
		before := service.ParseLinger(o.Runner.Run("loginctl", "show-user", o.User, "--property=Linger"))
		rep, linger := service.InstallLinux(o)
		if before == service.LingerOff && linger == service.LingerOn {
			// huginn turned it on, so `huginn uninstall` turns it off again.
			os.MkdirAll(paths().State, 0o700)
			os.WriteFile(lingerMarker(), []byte(o.User+"\n"), 0o600)
		}
		trayC := renderServiceReport(u, rep, func(c compResult) string {
			switch c.comp {
			case "service":
				return service.UnitName + " enabled and running, as " + user
			case "linger":
				return lingerText(c)
			}
			return ""
		})
		if rep.OK() {
			afterServiceInstallUI(u, bin, fileExists(o.TrayPath()), trayC)
		} else if trayC != nil {
			settleComp(u, *trayC, "")
		}
		rc = exitFor(rep)
	case "windows":
		o, err := service.WindowsDefaults(bin)
		if err != nil {
			u.Line(ui.Fail, "service", err.Error())
			return 1
		}
		o.NoTray = *noTray
		how := "S4U, so no password is stored"
		switch *logon {
		case "s4u":
			o.Logon = service.LogonS4U
		case "password":
			o.Logon = service.LogonPassword
			how = "with your password, kept by Task Scheduler"
			// Not echoed (security scan F3): a password on screen is in scrollback
			// and in every screen share. Piped stdin (automation) still works.
			// To this window's own stderr even with --report: the person types
			// it here.
			u.Stop()
			fmt.Fprintf(os.Stderr, "Windows password for %s (used once, kept by Task Scheduler): ", o.User)
			if term.IsTerminal(int(os.Stdin.Fd())) {
				b, err := term.ReadPassword(int(os.Stdin.Fd()))
				fmt.Fprintln(os.Stderr)
				if err != nil {
					u.Line(ui.Fail, "service", "reading the password: "+err.Error())
					return 1
				}
				o.Password = string(b)
			} else {
				pw, _ := bufio.NewReader(os.Stdin).ReadString('\n')
				o.Password = strings.TrimRight(pw, "\r\n")
			}
		default:
			fmt.Fprintln(os.Stderr, "--logon must be s4u or password")
			return 2
		}
		u.Spin("service", `registering the "`+service.TaskName+`" task and starting it`)
		rep := service.InstallWindows(o)
		trayC := renderServiceReport(u, rep, func(c compResult) string {
			if c.comp == "service" {
				return `"` + service.TaskName + `" task: starts at boot as ` + user + ` (` + how + `)`
			}
			return ""
		})
		if rep.OK() {
			afterServiceInstallUI(u, bin, fileExists(o.StartupLnk()), trayC)
		} else if trayC != nil {
			settleComp(u, *trayC, "")
		}
		rc = exitFor(rep)
	default:
		u.Line(ui.Fail, "service", "huginn core install is not supported on "+runtime.GOOS)
		return 1
	}
	if !compact {
		u.Took("done")
	}
	return rc
}

// retireNodeRunner: the pre-core Node runner (huginn-device.service) is
// stopped for good, so the core and it never both run; the core adopts its
// device id.
func retireNodeRunner(u *ui.Out) {
	home, _ := os.UserHomeDir()
	unit := filepath.Join(home, ".config", "systemd", "user", "huginn-device.service")
	if !fileExists(unit) {
		return
	}
	out, err := exec.Command("systemctl", "--user", "disable", "--now", "huginn-device.service").CombinedOutput()
	if err != nil {
		u.Line(ui.Warn, "runner", "the old Node runner (huginn-device.service) could NOT be stopped - stop it before the core enrols, or both will run")
		u.Note(strings.TrimSpace(string(out)))
		return
	}
	u.Line(ui.OK, "runner", "stopped the old Node runner (huginn-device.service); the core adopts its device id")
}

func exitFor(r service.Report) int {
	if r.FailedStep() != "" {
		return 1
	}
	return 0
}

func coreUninstall() int {
	rep, err := coreUninstallReport()
	if err != nil {
		fmt.Fprintln(os.Stderr, err)
		return 1
	}
	fmt.Print(rep.String())
	return exitFor(rep)
}

// coreUninstallReport removes the boot service and the tray's autostart and
// returns what each step did, for `huginn core uninstall` to print and for
// `huginn uninstall` to fold into one line.
func coreUninstallReport() (service.Report, error) {
	bin := stableBinary()
	os.Remove(trayMarker()) // a reinstall offers the tray again
	os.Remove(filepath.Join(paths().State, "tray-task"))
	os.Remove(filepath.Join(paths().State, "tray-task-2"))
	switch runtime.GOOS {
	case "linux":
		o, err := service.LinuxDefaults(bin)
		if err != nil {
			return service.Report{}, err
		}
		return service.UninstallLinux(o), nil
	case "windows":
		o, err := service.WindowsDefaults(bin)
		if err != nil {
			return service.Report{}, err
		}
		return service.UninstallWindows(o), nil
	}
	return service.Report{}, fmt.Errorf("huginn core uninstall: not supported on %s", runtime.GOOS)
}

func currentUser() string {
	if runtime.GOOS == "windows" {
		return os.Getenv("USERDOMAIN") + `\` + os.Getenv("USERNAME")
	}
	return os.Getenv("USER")
}

// call talks to this user's running core.
func call(method, path string, body any, out any) (int, error) {
	c := ipc.Client(daemon.SocketPath(paths()))
	ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
	defer cancel()
	resp, err := ipc.Do(ctx, c, method, path, body)
	if err != nil {
		return 0, err
	}
	defer resp.Body.Close()
	b, _ := io.ReadAll(resp.Body)
	if resp.StatusCode >= 400 {
		var e struct {
			Error string `json:"error"`
		}
		json.Unmarshal(b, &e)
		if e.Error == "" {
			e.Error = strings.TrimSpace(string(b))
		}
		return resp.StatusCode, errors.New(e.Error)
	}
	if out != nil {
		return resp.StatusCode, json.Unmarshal(b, out)
	}
	return resp.StatusCode, nil
}

func coreStatus() int {
	var st struct {
		Version, Proto, URL, Secrets, SecretsWhy, FirstStart string
		Pid                                                  int
		Paired, Tray                                         bool
		Device                                               daemon.DeviceView
	}
	if _, err := call(http.MethodGet, "/core/status", nil, &st); err != nil {
		fmt.Println("huginn core: not running (" + err.Error() + ")")
		printServiceStatus()
		return 1
	}
	fmt.Printf("(o> huginn core %s  pid %d\n", st.Version, st.Pid)
	if st.Paired {
		fmt.Printf("  huginn:  %s\n", st.URL)
	} else {
		fmt.Println("  huginn:  not paired — run `huginn core pair --url <https://host:8787>`")
	}
	fmt.Printf("  secrets: %s (%s)\n", st.Secrets, st.SecretsWhy)
	if st.FirstStart != "" {
		fmt.Printf("  import:  %s\n", st.FirstStart)
	}
	printDevice(st.Device)
	// Attached is the core's own answer; the service lines below say whether
	// the tray STARTS AT LOGIN — two different questions (one was read as the
	// other on PC01, 2026-10-15).
	if st.Tray {
		fmt.Println("  tray:    attached")
	} else {
		fmt.Println("  tray:    not running")
	}
	printServiceStatus()
	return 0
}

func printServiceStatus() {
	bin := stableBinary()
	switch runtime.GOOS {
	case "linux":
		if o, err := service.LinuxDefaults(bin); err == nil {
			s := service.StatusLinux(o)
			fmt.Printf("  service: installed=%v enabled=%s active=%s linger=%s\n", s.Installed, s.Enabled, s.Active, s.Linger)
		}
	case "windows":
		if o, err := service.WindowsDefaults(bin); err == nil {
			fmt.Println("  service: " + service.StatusWindows(o).String())
		}
	}
}

// corePair saves the huginn address and token. The token comes from stdin or
// $HUGINN_APPD_TOKEN, never argv (argv is world-readable in the process list).
func corePair(args []string) int {
	fs := flag.NewFlagSet("core pair", flag.ContinueOnError)
	url := fs.String("url", "", "the server's address, e.g. https://192.168.1.20:8787")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *url == "" {
		fmt.Fprintln(os.Stderr, "usage: huginn core pair --url <https://host:8787>   (token on stdin, or $HUGINN_APPD_TOKEN)")
		return 2
	}
	tok := os.Getenv("HUGINN_APPD_TOKEN")
	if tok == "" {
		if term.IsTerminal(int(os.Stdin.Fd())) {
			// Typed or pasted at a prompt: not echoed (it was, and a pasted
			// token then sat in the scrollback; run 3 showed one in a recording).
			fmt.Print("huginn's token (on the host: sudo cat /etc/huginn-appd/token): ")
			b, err := term.ReadPassword(int(os.Stdin.Fd()))
			fmt.Println()
			if err == nil {
				tok = strings.TrimSpace(string(b))
			}
		} else {
			line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
			tok = strings.TrimSpace(line)
		}
	}
	if tok == "" {
		fmt.Fprintln(os.Stderr, "huginn core pair: no token given - nothing was changed")
		return 2
	}
	var out map[string]string
	if _, err := call(http.MethodPost, "/core/pair", daemon.Pair{URL: *url, Token: tok}, &out); err != nil {
		fmt.Fprintln(os.Stderr, "huginn core pair: "+err.Error())
		fmt.Fprintln(os.Stderr, "(the core must be running: `huginn core run`, or `huginn core install`)")
		return 1
	}
	if fp := out["fingerprint"]; fp != "" {
		fmt.Printf("paired with %s (certificate %s... proved by the token)\n", *url, fp[:16])
	} else {
		fmt.Printf("paired with %s (loopback, plain http)\n", *url)
	}
	return 0
}

// coreStart starts an installed core that is not running: through its service,
// and on Windows directly when Task Scheduler holds the task back. The desktop
// app's and the console's "Start huginn core" are this.
func coreStart() int {
	if st, up := coreState(2 * time.Second); up {
		fmt.Println("huginn core " + st.Version + " is already running")
		return 0
	}
	if !serviceInstalled(stableBinary()) {
		fmt.Fprintln(os.Stderr, "huginn core is not set up to start at boot: huginn core install")
		return 1
	}
	switch runtime.GOOS {
	case "windows":
		exec.Command("schtasks", "/Run", "/TN", service.TaskName).Run()
		if _, up := waitCore(8*time.Second, func(_ coreSnapshot, up bool) bool { return up }); !up {
			if _, up := startDirectly(); !up {
				return 1
			}
		}
	default:
		if out, err := exec.Command("systemctl", "--user", "start", service.UnitName).CombinedOutput(); err != nil {
			fmt.Fprintln(os.Stderr, "systemctl --user start "+service.UnitName+": "+strings.TrimSpace(string(out)))
			return 1
		}
		if _, up := waitCore(15*time.Second, func(_ coreSnapshot, up bool) bool { return up }); !up {
			fmt.Fprintln(os.Stderr, "huginn core did not answer after starting: huginn core status says why")
			return 1
		}
	}
	st, _ := coreState(2 * time.Second)
	fmt.Println("huginn core " + st.Version + " is running")
	return 0
}
