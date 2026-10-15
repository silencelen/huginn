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

	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/store"
	"github.com/silencelen/huginn/core/internal/tray"
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
	case "pair":
		return corePair(args[1:])
	case "tray":
		// The tray role (docs/CORE-ARCHITECTURE.md §3.1): started at graphical
		// login, draws the icon and posts notifications that `core run` decides.
		tray.DetachConsole()
		tray.Run(tray.Options{Socket: daemon.SocketPath(paths())})
		return 0
	case "self-install":
		if _, ok := selfInstall(); !ok {
			return 1
		}
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

func coreInstall(args []string) int {
	fs := flag.NewFlagSet("core install", flag.ContinueOnError)
	logon := fs.String("logon", "s4u", "Windows only: s4u (no stored password) or password")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	// Install the binary first, so the service points at the stable per-user
	// path rather than wherever this copy happens to be running from (a
	// download folder, an app bundle).
	bin, ok := selfInstall()
	if !ok {
		return 1
	}
	if sys := packagedCore(bin); sys != "" {
		fmt.Fprintf(os.Stderr, "huginn core install: %s (a package) already provides huginn on this machine — one core per machine. Use that one (`%s core install`) or remove the package first.\n", sys, sys)
		return 1
	}
	fmt.Println("Installing huginn core to start at boot, as " + currentUser() + " — " + bin)
	switch runtime.GOOS {
	case "linux":
		o, err := service.LinuxDefaults(bin)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		retireNodeRunner()
		rep, linger := service.InstallLinux(o)
		fmt.Print(rep.String())
		if linger != service.LingerOn {
			fmt.Println("Note: without linger the core starts at login, not at boot.")
		}
		return exitFor(rep)
	case "windows":
		o, err := service.WindowsDefaults(bin)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		switch *logon {
		case "s4u":
			o.Logon = service.LogonS4U
		case "password":
			o.Logon = service.LogonPassword
			// Not echoed (security scan F3): a password on screen is in scrollback
			// and in every screen share. Piped stdin (automation) still works.
			fmt.Printf("Windows password for %s (used once, kept by Task Scheduler): ", o.User)
			if term.IsTerminal(int(os.Stdin.Fd())) {
				b, err := term.ReadPassword(int(os.Stdin.Fd()))
				fmt.Println()
				if err != nil {
					fmt.Fprintln(os.Stderr, "huginn core install: reading the password: "+err.Error())
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
		rep := service.InstallWindows(o)
		fmt.Print(rep.String())
		return exitFor(rep)
	}
	fmt.Fprintln(os.Stderr, "huginn core install: not supported on "+runtime.GOOS)
	return 1
}

// retireNodeRunner stops the Node runner's user service so two runners never
// beat under one adopted id. The unit file is left in place (and said so): the
// Node runner's `off` is still the way to remove it, and stopping is enough.
func retireNodeRunner() {
	home, _ := os.UserHomeDir()
	unit := filepath.Join(home, ".config", "systemd", "user", "huginn-device.service")
	if !fileExists(unit) {
		return
	}
	out, err := exec.Command("systemctl", "--user", "disable", "--now", "huginn-device.service").CombinedOutput()
	if err != nil {
		fmt.Printf("  the Node runner (huginn-device.service) could NOT be stopped: %s — stop it before the core enrols, or both will run\n", strings.TrimSpace(string(out)))
		return
	}
	fmt.Println("  stopped and disabled the Node runner (huginn-device.service); the core adopts its device id")
}

func exitFor(r service.Report) int {
	if r.FailedStep() != "" {
		return 1
	}
	return 0
}

func coreUninstall() int {
	bin := stableBinary()
	switch runtime.GOOS {
	case "linux":
		o, err := service.LinuxDefaults(bin)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		rep := service.UninstallLinux(o)
		fmt.Print(rep.String())
		return exitFor(rep)
	case "windows":
		o, err := service.WindowsDefaults(bin)
		if err != nil {
			fmt.Fprintln(os.Stderr, err)
			return 1
		}
		rep := service.UninstallWindows(o)
		fmt.Print(rep.String())
		return exitFor(rep)
	}
	return 1
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
		Paired                                               bool
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
	url := fs.String("url", "", "huginn address, e.g. https://192.168.10.20:8787")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	if *url == "" {
		fmt.Fprintln(os.Stderr, "usage: huginn core pair --url <https://host:8787>   (token on stdin, or $HUGINN_APPD_TOKEN)")
		return 2
	}
	tok := os.Getenv("HUGINN_APPD_TOKEN")
	if tok == "" {
		if fi, _ := os.Stdin.Stat(); fi != nil && fi.Mode()&os.ModeCharDevice != 0 {
			fmt.Print("appd token: ")
		}
		line, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		tok = strings.TrimSpace(line)
	}
	var out map[string]string
	if _, err := call(http.MethodPost, "/core/pair", daemon.Pair{URL: *url, Token: tok}, &out); err != nil {
		fmt.Fprintln(os.Stderr, "huginn core pair: "+err.Error())
		fmt.Fprintln(os.Stderr, "(the core must be running: `huginn core run`, or `huginn core install`)")
		return 1
	}
	if fp := out["fingerprint"]; fp != "" {
		fmt.Printf("paired with %s (certificate %s… proved by the token)\n", *url, fp[:16])
	} else {
		fmt.Printf("paired with %s (loopback, plain http)\n", *url)
	}
	return 0
}
