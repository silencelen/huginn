// Package doctor is `huginn doctor`: a deterministic health report of THIS
// machine's Huginn client, with no Claude involved. It walks the chain a
// client depends on, top down - the binary, the shell client, the boot
// service, the core's socket, the pairing, every route and its TLS pin, the
// server through the core, the device, the local AI tier, ssh to the server,
// Claude Code and the skills - and says for each what it found, and the one
// thing to try.
//
// Everything the doctor asks of the machine goes through a Probe, so the
// checks are tested against fakes: no network, no real services, no real
// commands. Default() is the real machine.
package doctor

import (
	"encoding/json"
	"fmt"
	"net"
	"net/url"
	"os"
	"path/filepath"
	"regexp"
	"slices"
	"strconv"
	"strings"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/skills"
	"github.com/silencelen/huginn/core/internal/store"
)

// State is a check's verdict.
type State string

const (
	OK   State = "ok"
	Warn State = "warn" // works, with something to look at
	Fail State = "fail" // broken; the exit status says so
	Skip State = "skip" // not applicable on this machine, or nothing to check yet
)

// Check is one row of the report. ID is stable across releases, so a script
// may key on it; Detail is one line for a person; Fix is the one thing to try.
type Check struct {
	ID     string `json:"id"`
	State  State  `json:"state"`
	Detail string `json:"detail"`
	Fix    string `json:"fix,omitempty"`
	// Comp is the component column of the human report (binary, CLI,
	// service, core, tray, pairing, route, server, device, local AI, ssh,
	// claude, skills). Not in the JSON: the id carries it.
	Comp string `json:"-"`
}

// Probe is everything the doctor asks of the machine. Files are read for
// real (tests use a scratch home); commands, the socket and the network go
// through the function fields, which tests replace.
type Probe struct {
	GOOS       string
	Home       string
	Version    string // this binary's
	Executable string // this binary, resolved
	Env        func(string) string
	Layout     install.Layout
	Paths      store.Paths
	// Linux or Windows is set for the boot-service check; the Runner inside
	// is used for systemctl, loginctl and schtasks.
	Linux   *service.LinuxOptions
	Windows *service.WindowsOptions
	// Run runs every other external command: the installed binary's `version`,
	// ssh -G, ssh-keygen -F, claude --version, node huginn-local status.
	Run      service.Runner
	LookPath func(string) (string, error)
	// Core talks to this user's running core over its socket; nil error means
	// it answered 2xx and out was filled.
	Core func(method, path string, out any) error
	// DialTLS completes a TLS handshake with hostport (any certificate: the
	// doctor judges the leaf's fingerprint against the pins itself) and
	// returns the leaf's sha256 fingerprint and how long the connect took.
	DialTLS func(hostport string, timeout time.Duration) (fp string, latency time.Duration, err error)
	// DialTCP connects and closes: port 22, and a plaintext loopback route.
	DialTCP func(hostport string, timeout time.Duration) (latency time.Duration, err error)
	// ClientVersion is the version of the shell client this core carries for
	// goos (clientkit.Version).
	ClientVersion func(goos string) string
	// Skills is the state of the Claude Code skills (skills.Status).
	Skills func() []skills.Entry
	// SocketPath is where the core answers, for the message when it does not.
	SocketPath string
	// LocalDir is the local AI tier's directory (localtier.Dir).
	LocalDir string
	// Manager is the local AI manager (~/.huginn/huginn-local).
	Manager string
}

// Timeouts, short on purpose: a doctor that hangs is no doctor.
const (
	dialTimeout = 5 * time.Second
)

// Run makes the report, in chain order.
func Run(p Probe) []Check {
	r := &report{p: p}
	r.binary()
	r.cli()
	r.serviceCheck()
	r.socket()
	r.tray()
	r.pairing()
	r.routes()
	r.server()
	r.device()
	r.localAI()
	r.ssh()
	r.claude()
	r.skills()
	return r.out
}

// Failed: at least one check failed (exit 1).
func Failed(checks []Check) bool {
	return slices.ContainsFunc(checks, func(c Check) bool { return c.State == Fail })
}

// JSON is the stable array a script reads.
func JSON(checks []Check) []byte {
	if checks == nil {
		checks = []Check{}
	}
	b, _ := json.MarshalIndent(checks, "", "  ")
	return append(b, '\n')
}

type report struct {
	p   Probe
	out []Check
	// what earlier checks learned
	core             coreSnap
	coreUp           bool
	cfg              store.Config
	cfgOK            bool
	sshHost, sshPort string
	claudeHere       bool
}

func (r *report) add(id, comp string, st State, detail, fix string) {
	r.out = append(r.out, Check{ID: id, Comp: comp, State: st, Detail: detail, Fix: fix})
}

// coreSnap is what GET /core/status answers, as far as the doctor reads it.
type coreSnap struct {
	Version string   `json:"version"`
	Pid     int      `json:"pid"`
	Paired  bool     `json:"paired"`
	URL     string   `json:"url"`
	Pins    []string `json:"pins"`
	Tray    bool     `json:"tray"`
	Device  struct {
		Status struct {
			Enabled   bool   `json:"enabled"`
			DeviceID  string `json:"deviceId"`
			Enrolled  bool   `json:"enrolled"`
			Busy      bool   `json:"busy"`
			Locked    bool   `json:"locked"`
			LockKnown bool   `json:"lockKnown"`
			Note      string `json:"note"`
		} `json:"status"`
		Settings struct {
			Enabled        *bool   `json:"enabled"`
			Name           *string `json:"name"`
			Scope          *string `json:"scope"`
			Root           *string `json:"root"`
			ActWhileLocked *bool   `json:"actWhileLocked"`
		} `json:"settings"`
	} `json:"device"`
}

func (r *report) tilde(p string) string {
	if h := r.p.Home; h != "" && strings.HasPrefix(p, h+string(filepath.Separator)) {
		return "~" + p[len(h):]
	}
	return p
}

func (r *report) env(k string) string {
	if r.p.Env == nil {
		return ""
	}
	return r.p.Env(k)
}

func (r *report) run(name string, args ...string) service.Result {
	if r.p.Run == nil {
		return service.Result{Err: fmt.Errorf("%s: no runner", name)}
	}
	return r.p.Run.Run(name, args...)
}

func (r *report) lookPath(name string) (string, error) {
	if r.p.LookPath == nil {
		return "", os.ErrNotExist
	}
	return r.p.LookPath(name)
}

var versionLine = regexp.MustCompile(`huginn (?:core )?(\S+)`)

// urlPrefix is Go's `Get "http://huginn-core/core/status": ` in front of a
// dial error, which says nothing a person needs.
var urlPrefix = regexp.MustCompile(`^[A-Za-z]+ "[^"]*": `)

// binaryVersion is the installed core's version: read off the `current` link
// on Linux (no process started), else asked of the binary itself.
func (r *report) binaryVersion(bin string) string {
	if r.p.GOOS != "windows" {
		if t, err := os.Readlink(filepath.Join(r.p.Layout.Root, "current")); err == nil {
			if v := filepath.Base(t); v != "" && v != "." {
				return v
			}
		}
	}
	if exe := r.p.Executable; exe != "" {
		if fe, err := os.Stat(exe); err == nil {
			if fb, err := os.Stat(bin); err == nil && os.SameFile(fe, fb) {
				return r.p.Version
			}
		}
	}
	res := r.run(bin, "version")
	if m := versionLine.FindStringSubmatch(res.Stdout); res.OK() && m != nil {
		return m[1]
	}
	return ""
}

func (r *report) binary() {
	bin := r.p.Layout.Binary()
	if bin == "" {
		r.add("core.binary", "binary", Fail, "no install layout for this machine (HOME or LOCALAPPDATA is not set)", "")
		return
	}
	if _, err := os.Stat(bin); err != nil {
		r.add("core.binary", "binary", Fail, "huginn core is not installed at "+r.tilde(bin), "huginn core install")
		return
	}
	v := r.binaryVersion(bin)
	where := r.tilde(bin) + " (install dir " + r.tilde(r.p.Layout.Root) + ")"
	switch {
	case v == "":
		r.add("core.binary", "binary", Warn, "huginn core at "+where+" did not say its version", "huginn core install")
	case v != r.p.Version:
		r.add("core.binary", "binary", Warn, fmt.Sprintf("this command is huginn %s, the installed core is %s at %s", r.p.Version, v, where), "run the installed one: "+r.tilde(bin)+" doctor; or huginn core update")
	default:
		r.add("core.binary", "binary", OK, "huginn "+v+" at "+where, "")
	}
}

func (r *report) cli() {
	name := "huginn.sh"
	if r.p.GOOS == "windows" {
		name = "huginn.ps1"
	}
	script := filepath.Join(r.p.Home, ".huginn", name)
	b, err := os.ReadFile(script)
	if err != nil {
		r.add("core.cli", "CLI", Fail, "the huginn CLI is not installed at "+r.tilde(script), "huginn core self-install (the core lays the CLI down)")
		return
	}
	have := versionOf(b)
	want := ""
	if r.p.ClientVersion != nil {
		want = r.p.ClientVersion(r.p.GOOS)
	}
	switch {
	case have == "":
		r.add("core.cli", "CLI", Warn, r.tilde(script)+" declares no version", "huginn update")
	case want != "" && have != want:
		r.add("core.cli", "CLI", Warn, fmt.Sprintf("CLI %s at %s does not match huginn core %s", have, r.tilde(script), want), "huginn update")
	default:
		r.add("core.cli", "CLI", OK, have+" at "+r.tilde(script), "")
	}
}

var cliVersion = regexp.MustCompile(`(?m)^\$?(?:script:)?HUGINN_VERSION\s*=\s*'([^']+)'`)

func versionOf(b []byte) string {
	if m := cliVersion.FindSubmatch(b); m != nil {
		return string(m[1])
	}
	return ""
}

func (r *report) serviceCheck() {
	switch {
	case r.p.GOOS == "linux" && r.p.Linux != nil:
		s := service.StatusLinux(*r.p.Linux)
		switch {
		case !s.Installed:
			r.add("core.service", "service", Fail, service.UnitName+" is not installed: huginn core does not start at boot", "huginn core install")
		case s.Active != "active":
			r.add("core.service", "service", Fail, fmt.Sprintf("%s is %s (enabled: %s)", service.UnitName, s.Active, s.Enabled),
				"huginn core start; the reason: journalctl --user -u "+service.UnitName+" -n 50")
		case s.Linger != service.LingerOn:
			r.add("core.service", "service", Warn, fmt.Sprintf("%s is active, but linger is %s: it runs only while you are logged in", service.UnitName, s.Linger),
				"loginctl enable-linger "+r.p.Linux.User+" (huginn core install does it when it can)")
		case s.Enabled != "enabled":
			r.add("core.service", "service", Warn, fmt.Sprintf("%s is active but %s: it will not start at boot", service.UnitName, s.Enabled), "systemctl --user enable "+service.UnitName)
		default:
			r.add("core.service", "service", OK, service.UnitName+" enabled and active, linger on", "")
		}
	case r.p.GOOS == "windows" && r.p.Windows != nil:
		s := service.StatusWindows(*r.p.Windows)
		switch {
		case !s.Registered:
			r.add("core.service", "service", Fail, `the "`+service.TaskName+`" task is not registered: huginn core does not start at boot`, "huginn core install (asks for administrator approval once)")
		case s.Problem != "":
			r.add("core.service", "service", Warn, `the "`+service.TaskName+`" task: `+s.Problem, "huginn core install")
		case strings.EqualFold(s.Task.Enabled, "false"):
			r.add("core.service", "service", Fail, `the "`+service.TaskName+`" task is disabled`, "enable it in Task Scheduler, or huginn core install")
		default:
			r.add("core.service", "service", OK, fmt.Sprintf(`"%s" task registered (logon %s, as %s)`, service.TaskName, s.Task.LogonType, s.Task.UserID), "")
		}
	default:
		r.add("core.service", "service", Skip, "no boot service on "+r.p.GOOS+" (the shell client is the whole client here)", "")
	}
}

func (r *report) socket() {
	if r.p.Core == nil {
		r.add("core.socket", "core", Skip, "no way to reach a core here", "")
		return
	}
	var st coreSnap
	if err := r.p.Core("GET", "/core/status", &st); err != nil {
		r.add("core.socket", "core", Fail, "huginn core is not answering on "+r.tilde(r.p.SocketPath)+" ("+oneLine(urlPrefix.ReplaceAllString(err.Error(), ""))+")",
			"huginn core start, then huginn core status")
		return
	}
	r.core, r.coreUp = st, true
	r.add("core.socket", "core", OK, fmt.Sprintf("huginn core %s answers (pid %d)", st.Version, st.Pid), "")
}

func (r *report) graphical() bool {
	if r.p.GOOS == "windows" {
		return true
	}
	return r.env("DISPLAY") != "" || r.env("WAYLAND_DISPLAY") != ""
}

func (r *report) trayAutostart() (bool, bool) {
	switch {
	case r.p.GOOS == "linux" && r.p.Linux != nil:
		_, err := os.Stat(r.p.Linux.TrayPath())
		return err == nil, true
	case r.p.GOOS == "windows" && r.p.Windows != nil:
		_, err := os.Stat(r.p.Windows.StartupLnk())
		return err == nil, true
	}
	return false, false
}

func (r *report) tray() {
	switch {
	case !r.coreUp:
		r.add("core.tray", "tray", Skip, "huginn core is not running", "")
	case r.core.Tray:
		r.add("core.tray", "tray", OK, "attached to huginn core", "")
	case !r.graphical():
		r.add("core.tray", "tray", Skip, "no graphical session here: nothing to draw the tray on", "")
	default:
		auto, known := r.trayAutostart()
		switch {
		case known && !auto:
			r.add("core.tray", "tray", Skip, "not running, and not set to start at login (--no-tray)", "huginn core tray show, or huginn core install --tray")
		default:
			r.add("core.tray", "tray", Warn, "not running; it starts at your next login", "huginn core tray show")
		}
	}
}

func (r *report) loadConfig() {
	b, err := os.ReadFile(r.p.Paths.CoreFile())
	if err != nil {
		return
	}
	cfg, err := store.ParseConfig(b)
	if err != nil {
		return
	}
	r.cfg, r.cfgOK = cfg, true
}

func (r *report) pairing() {
	r.loadConfig()
	switch {
	case r.coreUp && r.core.Paired:
		r.add("core.pairing", "pairing", OK, "paired with "+r.core.URL, "")
	case r.coreUp && r.core.URL != "":
		r.add("core.pairing", "pairing", Fail, "huginn core names "+r.core.URL+" but holds no token for it", "huginn core pair --url "+r.core.URL+" (asks for the token)")
	case r.coreUp:
		r.add("core.pairing", "pairing", Fail, "huginn core is not paired with a server", "huginn core pair --url https://<host>:8787 (asks for the token)")
	case r.cfgOK && r.cfg.Appd.URL != "":
		r.add("core.pairing", "pairing", Warn, "core.json names "+r.cfg.Appd.URL+"; whether the token is there only a running core can say", "huginn core start")
	default:
		r.add("core.pairing", "pairing", Fail, "huginn core is not paired with a server", "huginn core pair --url https://<host>:8787 (asks for the token)")
	}
}

// routeList is the addresses the core knows: the one it paired on, then the
// route book, as core.json has them.
func (r *report) routeList() []string {
	var out []string
	if r.cfgOK && r.cfg.Appd.URL != "" {
		out = append(out, r.cfg.Appd.URL)
		for _, rt := range r.cfg.Appd.Routes {
			if !slices.Contains(out, rt.URL) {
				out = append(out, rt.URL)
			}
		}
	} else if r.coreUp && r.core.URL != "" {
		out = append(out, r.core.URL)
	}
	return out
}

func (r *report) pins() []string {
	var pins []string
	if r.cfgOK {
		for _, ps := range r.cfg.Pins {
			pins = append(pins, ps...)
		}
	}
	pins = append(pins, r.core.Pins...)
	return pins
}

type routesView struct {
	Routes []struct {
		URL       string `json:"url"`
		Active    bool   `json:"active"`
		LastError string `json:"lastError"`
	} `json:"routes"`
}

func (r *report) routes() {
	list := r.routeList()
	if len(list) == 0 {
		r.add("route", "route", Skip, "no server address to check", "")
		return
	}
	health := map[string]struct{ active bool }{}
	if r.coreUp && r.p.Core != nil {
		var v routesView
		if err := r.p.Core("GET", "/core/routes", &v); err == nil {
			for _, h := range v.Routes {
				health[strings.TrimSuffix(h.URL, "/")] = struct{ active bool }{h.Active}
			}
		}
	}
	pins := r.pins()
	for _, raw := range list {
		id := "route:" + raw
		u, err := url.Parse(raw)
		if err != nil || u.Hostname() == "" {
			r.add(id, "route", Fail, raw+" is not an address this client can dial", "huginn core route remove "+raw)
			continue
		}
		port := u.Port()
		if port == "" {
			port = "8787"
		}
		hostport := net.JoinHostPort(u.Hostname(), port)
		active := ""
		if h, ok := health[strings.TrimSuffix(raw, "/")]; ok && h.active {
			active = ", the active one"
		}
		if u.Scheme == "http" {
			if r.p.DialTCP == nil {
				r.add(id, "route", Skip, raw+": nothing here can dial it", "")
				continue
			}
			lat, err := r.p.DialTCP(hostport, dialTimeout)
			if err != nil {
				r.add(id, "route", Fail, raw+": "+oneLine(err.Error()), "is the daemon on this machine running? systemctl status huginn-appd")
				continue
			}
			r.add(id, "route", Warn, fmt.Sprintf("%s answers in %s; plain http, so nothing proves who answers (only HUGINN_APPD_PLAINTEXT=local allows it)", raw, ms(lat)), "")
			continue
		}
		if r.p.DialTLS == nil {
			r.add(id, "route", Skip, raw+": nothing here can dial it", "")
			continue
		}
		fp, lat, err := r.p.DialTLS(hostport, dialTimeout)
		switch {
		case err != nil:
			r.add(id, "route", Fail, raw+": "+oneLine(err.Error()), "is the server up and reachable from this network (VPN)? huginn core route shows what the core sees")
		case len(pins) == 0:
			r.add(id, "route", Warn, fmt.Sprintf("%s answers in %s%s, but no certificate has been proved yet", raw, ms(lat), active), "huginn core pair --url "+raw+" proves it with the token")
		case slices.Contains(pins, fp):
			r.add(id, "route", OK, fmt.Sprintf("%s answers in %s%s; its certificate is the proved one", raw, ms(lat), active), "")
		default:
			short := fp
			if len(short) > 16 {
				short = short[:16]
			}
			r.add(id, "route", Fail, fmt.Sprintf("%s answers, but with certificate %s..., not the proved one: another machine is on that address, or the server was reinstalled", raw, short),
				"if the server was reinstalled, pair again: huginn core pair --url "+raw+"; otherwise do not use that address")
		}
	}
}

type serverStatus struct {
	Host        string `json:"host"`
	AppdVersion string `json:"appdVersion"`
	Claude      string `json:"claude"`
	UptimeSec   int64  `json:"uptimeSec"`
	Sessions    int    `json:"sessions"`
}

func (r *report) server() {
	switch {
	case !r.coreUp:
		r.add("server.status", "server", Skip, "huginn core is not running to ask through", "")
		return
	case !r.core.Paired:
		r.add("server.status", "server", Skip, "not paired", "")
		return
	}
	var st serverStatus
	if err := r.p.Core("GET", "/appd/v1/status", &st); err != nil {
		r.add("server.status", "server", Fail, "the server did not answer through huginn core: "+oneLine(err.Error()),
			"huginn core route (which address is active); on the server: systemctl status huginn-appd")
		return
	}
	claude := st.Claude
	if claude == "" {
		claude = "unknown"
	}
	r.add("server.status", "server", OK, fmt.Sprintf("huginn-appd %s on %s, Claude Code %s, %d sessions, up %s", st.AppdVersion, st.Host, claude, st.Sessions, dur(time.Duration(st.UptimeSec)*time.Second)), "")
}

func (r *report) device() {
	if !r.coreUp {
		r.add("device", "device", Skip, "huginn core is not running", "")
		return
	}
	d := r.core.Device
	enabled := d.Settings.Enabled != nil && *d.Settings.Enabled
	if !enabled {
		r.add("device", "device", Skip, "this machine is not offered to huginn", "huginn device on (read-only sessions until widened)")
		return
	}
	name, scope := "", "look"
	if d.Settings.Name != nil {
		name = *d.Settings.Name
	}
	if d.Settings.Scope != nil && *d.Settings.Scope != "" {
		scope = *d.Settings.Scope
	}
	lock := "unlocked"
	switch {
	case !d.Status.LockKnown:
		lock = "lock state unknown (counts as locked)"
	case d.Status.Locked:
		lock = "locked"
	}
	busy := "idle"
	if d.Status.Busy {
		busy = "running a job"
	}
	if !d.Status.Enrolled {
		note := d.Status.Note
		if note == "" {
			note = "not enrolled yet"
		}
		r.add("device", "device", Warn, fmt.Sprintf("offered as %s (scope %s) but not enrolled: %s", name, scope, oneLine(note)), "huginn device status; huginn core route")
		return
	}
	r.add("device", "device", OK, fmt.Sprintf("enrolled as %s, scope %s, %s, %s", name, scope, busy, lock), "")
}

type localStatus struct {
	Setup      bool   `json:"setup"`
	Mode       string `json:"mode"`
	Class      string `json:"class"`
	Persistent *bool  `json:"persistent"`
	Engine     struct {
		Reachable bool `json:"reachable"`
	} `json:"engine"`
	Models []string `json:"models"`
}

func (r *report) localAI() {
	dir := r.p.LocalDir
	if dir == "" {
		r.add("local-ai", "local AI", Skip, "not installed here", "")
		return
	}
	if fi, err := os.Stat(dir); err != nil || !fi.IsDir() {
		r.add("local-ai", "local AI", Skip, "not installed here", "")
		return
	}
	node, err := r.lookPath("node")
	if err != nil {
		r.add("local-ai", "local AI", Warn, r.tilde(dir)+" exists, but node is not on PATH to ask it", "install Node.js LTS, then huginn local status")
		return
	}
	if _, err := os.Stat(r.p.Manager); err != nil {
		r.add("local-ai", "local AI", Warn, r.tilde(dir)+" exists, but the manager "+r.tilde(r.p.Manager)+" is missing", "huginn local update")
		return
	}
	res := r.run(node, r.p.Manager, "status", "--json")
	var st localStatus
	if !res.OK() || json.Unmarshal([]byte(strings.TrimSpace(res.Stdout)), &st) != nil {
		r.add("local-ai", "local AI", Warn, "huginn local status did not answer: "+oneLine(res.Why()), "huginn local doctor")
		return
	}
	switch {
	case !st.Setup:
		r.add("local-ai", "local AI", Warn, r.tilde(dir)+" exists, but the tier is not set up", "huginn local on, or huginn local off --purge")
	case !st.Engine.Reachable:
		r.add("local-ai", "local AI", Fail, fmt.Sprintf("%s (class %s): the model server is not answering", st.Mode, st.Class), "huginn local doctor")
	case st.Persistent != nil && !*st.Persistent:
		r.add("local-ai", "local AI", Warn, fmt.Sprintf("%s (class %s) serving %s; stops when you sign out", st.Mode, st.Class, strings.Join(st.Models, ", ")), "huginn local persist")
	default:
		r.add("local-ai", "local AI", OK, fmt.Sprintf("%s (class %s) serving %s", st.Mode, st.Class, strings.Join(st.Models, ", ")), "")
	}
}

// sshStanza reads `Host <alias>` out of ~/.ssh/config: HostName, Port, User,
// IdentityFile (a quoted path is one argument, as ssh reads it).
func sshStanza(cfg []byte, alias string) (found bool, hostname, port, user, key string) {
	in := false
	for _, line := range strings.Split(string(cfg), "\n") {
		s := strings.TrimSpace(line)
		if s == "" || s[0] == '#' {
			continue
		}
		f := strings.Fields(strings.ReplaceAll(s, "=", " "))
		if len(f) == 0 {
			continue
		}
		kw := strings.ToLower(f[0])
		if kw == "host" || kw == "match" {
			in = kw == "host" && slices.Contains(f[1:], alias)
			found = found || in
			continue
		}
		if !in || len(f) < 2 {
			continue
		}
		val := f[1]
		if rest := strings.TrimSpace(s[len(f[0]):]); strings.HasPrefix(rest, `"`) {
			if j := strings.IndexByte(rest[1:], '"'); j >= 0 {
				val = rest[1 : 1+j]
			}
		}
		switch kw {
		case "hostname":
			if hostname == "" {
				hostname = val
			}
		case "port":
			if port == "" {
				port = val
			}
		case "user":
			if user == "" {
				user = val
			}
		case "identityfile":
			if key == "" {
				key = val
			}
		}
	}
	return
}

func (r *report) ssh() {
	alias := r.env("HUGINN_HOST")
	if alias == "" {
		alias = "huginn"
	}
	cfgPath := filepath.Join(r.p.Home, ".ssh", "config")
	cfg, _ := os.ReadFile(cfgPath)
	found, hostname, _, user, key := sshStanza(cfg, alias)
	switch {
	case found:
		if user == "" {
			user = "root"
		}
		detail := fmt.Sprintf("'Host %s' -> %s, as %s (%s)", alias, hostname, user, r.tilde(cfgPath))
		if strings.HasPrefix(key, "~/") || strings.HasPrefix(key, `~\`) {
			key = filepath.Join(r.p.Home, key[2:])
		}
		if key != "" {
			if _, err := os.Stat(key); err != nil {
				r.add("ssh.config", "ssh", Warn, detail+"; its key "+r.tilde(key)+" does not exist", "huginn setup ssh")
				break
			}
		}
		r.add("ssh.config", "ssh", OK, detail, "")
	case alias != "huginn":
		r.add("ssh.config", "ssh", Warn, "HUGINN_HOST="+alias+" names no 'Host' entry in "+r.tilde(cfgPath)+"; ssh takes it as a plain host name", "")
	default:
		r.add("ssh.config", "ssh", Fail, "no 'Host huginn' entry in "+r.tilde(cfgPath)+": attaching and the session verbs have no way to the server", "huginn setup ssh")
	}

	if _, err := r.lookPath("ssh"); err != nil {
		fix := "install OpenSSH (openssh-client)"
		if r.p.GOOS == "windows" {
			fix = "Settings > System > Optional features > OpenSSH Client"
		}
		r.add("ssh.resolve", "ssh", Fail, "no ssh client on this machine", fix)
		r.add("ssh.port", "ssh", Skip, "no ssh client", "")
		r.add("ssh.hostkey", "ssh", Skip, "no ssh client", "")
		return
	}
	res := r.run("ssh", "-G", alias)
	rh, rp := "", "22"
	for _, line := range strings.Split(res.Stdout, "\n") {
		f := strings.Fields(line)
		if len(f) < 2 {
			continue
		}
		switch f[0] {
		case "hostname":
			rh = f[1]
		case "port":
			rp = f[1]
		}
	}
	switch {
	case !res.OK() || rh == "":
		r.add("ssh.resolve", "ssh", Fail, "ssh -G "+alias+" failed: "+oneLine(res.Why()), "check "+r.tilde(cfgPath)+" for a syntax error (ssh -G "+alias+")")
		r.add("ssh.port", "ssh", Skip, "nothing to connect to", "")
		r.add("ssh.hostkey", "ssh", Skip, "nothing to look up", "")
		return
	case !found && rh == alias:
		r.add("ssh.resolve", "ssh", Warn, "ssh -G resolves "+alias+" to itself: no entry names the server's address", "huginn setup ssh")
		r.add("ssh.port", "ssh", Skip, "no server address to try", "")
		r.add("ssh.hostkey", "ssh", Skip, "no server address to look up", "")
		return
	default:
		r.add("ssh.resolve", "ssh", OK, fmt.Sprintf("ssh -G resolves %s to %s:%s", alias, rh, rp), "")
	}
	r.sshHost, r.sshPort = rh, rp
	if r.p.DialTCP == nil {
		r.add("ssh.port", "ssh", Skip, "nothing here can dial it", "")
	} else if lat, err := r.p.DialTCP(net.JoinHostPort(rh, rp), dialTimeout); err != nil {
		r.add("ssh.port", "ssh", Fail, fmt.Sprintf("%s:%s does not answer: %s", rh, rp, oneLine(err.Error())),
			"is the server up and reachable from this network (VPN)? everything but attaching works without ssh; huginn setup ssh --host ADDRESS points 'Host huginn' at another address")
	} else {
		r.add("ssh.port", "ssh", OK, fmt.Sprintf("%s:%s answers in %s (connect only; no login was attempted)", rh, rp, ms(lat)), "")
	}
	if _, err := r.lookPath("ssh-keygen"); err != nil {
		r.add("ssh.hostkey", "ssh", Skip, "no ssh-keygen to look the host key up with", "")
		return
	}
	name := rh
	if rp != "22" {
		name = "[" + rh + "]:" + rp
	}
	kres := r.run("ssh-keygen", "-F", name)
	switch {
	case kres.Err != nil:
		r.add("ssh.hostkey", "ssh", Skip, "ssh-keygen -F did not run: "+oneLine(kres.Err.Error()), "")
	case kres.Code == 0 && strings.TrimSpace(kres.Stdout) != "":
		r.add("ssh.hostkey", "ssh", OK, "the host key of "+name+" is in known_hosts", "")
	default:
		r.add("ssh.hostkey", "ssh", Warn, "the host key of "+name+" is not in known_hosts yet: the first ssh asks you to accept it", "huginn setup ssh (it runs that first ssh and shows the fingerprint)")
	}
}

func (r *report) claude() {
	path, err := r.lookPath("claude")
	dotClaude := false
	if fi, err := os.Stat(filepath.Join(r.p.Home, ".claude")); err == nil && fi.IsDir() {
		dotClaude = true
	}
	switch {
	case err == nil:
		r.claudeHere = true
		res := r.run(path, "--version")
		v := strings.TrimSuffix(strings.TrimSpace(strings.SplitN(res.Stdout, "\n", 2)[0]), " (Claude Code)")
		if !res.OK() || v == "" {
			r.add("claude.code", "claude", Warn, "claude at "+path+" did not say its version: "+oneLine(res.Why()), "claude --version")
			return
		}
		r.add("claude.code", "claude", OK, "Claude Code "+v+" at "+path, "")
	case dotClaude:
		r.claudeHere = true
		r.add("claude.code", "claude", Warn, "~/.claude exists, but claude is not on PATH in this shell", "")
	default:
		r.add("claude.code", "claude", Skip, "Claude Code is not installed on this machine", "")
	}
}

func (r *report) skills() {
	if r.p.Skills == nil {
		r.add("claude.skills", "skills", Skip, "no skills to check", "")
		return
	}
	st := r.p.Skills()
	var installed, missing, foreign, stale []string
	for _, en := range st {
		switch {
		case en.Installed():
			installed = append(installed, en.Name)
		case en.State == skills.StateForeign:
			foreign = append(foreign, en.Name+" ("+en.Detail+")")
		case en.State == skills.StateStale:
			stale = append(stale, en.Name)
		default:
			missing = append(missing, en.Name)
		}
	}
	switch {
	case len(st) == 0:
		r.add("claude.skills", "skills", Skip, "no skills to check", "")
	case len(installed) == len(st):
		r.add("claude.skills", "skills", OK, strings.Join(installed, ", ")+" in ~/.claude/skills", "")
	case len(stale) > 0:
		r.add("claude.skills", "skills", Warn, "not this version's: "+strings.Join(stale, ", "), "huginn skills install")
	case len(foreign) > 0:
		r.add("claude.skills", "skills", Warn, "your own skill sits where huginn's would go: "+strings.Join(foreign, "; "), "rename yours, then huginn skills install")
	case !r.claudeHere:
		r.add("claude.skills", "skills", Skip, "not installed (no Claude Code here to load them)", "huginn skills install, once Claude Code is")
	default:
		r.add("claude.skills", "skills", Warn, "not installed: "+strings.Join(missing, ", "), "huginn skills install")
	}
}

// oneLine cleans a message for one row: no newlines, no control characters.
func oneLine(s string) string {
	s = appd.Printable(s, 300)
	return strings.Join(strings.Fields(s), " ")
}

func ms(d time.Duration) string {
	return strconv.FormatInt(d.Milliseconds(), 10) + " ms"
}

func dur(d time.Duration) string {
	switch {
	case d >= 48*time.Hour:
		return strconv.Itoa(int(d.Hours()/24)) + " d"
	case d >= time.Hour:
		return strconv.Itoa(int(d.Hours())) + " h"
	}
	return strconv.Itoa(int(d.Minutes())) + " min"
}
