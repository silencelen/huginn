// Package daemon assembles `huginn core run`: the store, the token, the appd
// client, the device runner and the IPC server, for one OS user
// (docs/design/core.md §3, P1).
package daemon

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/policy"
	"github.com/silencelen/huginn/core/internal/runner"
	"github.com/silencelen/huginn/core/internal/store"
	"github.com/silencelen/huginn/core/internal/traystate"
	"github.com/silencelen/huginn/core/internal/version"
)

// SocketPath is where this user's core listens. The GUI computes the same
// path (desktop/core/CorePaths.kt) — change both together.
func SocketPath(p store.Paths) string { return filepath.Join(p.Runtime, "core.sock") }

// Daemon is one running core.
type Daemon struct {
	paths     store.Paths
	st        *store.Store
	secrets   store.Secrets
	sel       store.Selection
	hub       *ipc.Hub
	logf      func(string, ...any)
	lock      *lockReader
	ctx       context.Context // the daemon's lifetime; runners restart under it
	watch     *watcher        // appd watch stream -> tray summary + notices
	installID string          // X-Huginn-Client

	restartMu sync.Mutex // one runner stop-then-start at a time (restartRunner)

	mu      sync.Mutex
	api     *appd.Client
	run     *runner.Runner
	stopRun context.CancelFunc
	runDone chan struct{}
	migrate string        // one line: what the first-start import did
	last    runner.Status // newest status published, runner or not ("not paired" has no runner)

	restartOnce sync.Once
	restartNow  chan struct{} // closed when an update asked this core to exit and be restarted
}

// ErrRestart is what Run returns when POST /core/restart asked the core to
// exit so its supervisor starts the binary now at the stable path (an update).
// `core run` exits 0 on it: systemd's Restart=always restarts any exit, and on
// Windows the updater starts the task itself (self-install).
var ErrRestart = errors.New("restart requested")

// Options for Run.
type Options struct {
	Paths  store.Paths
	Logger *log.Logger
}

// OpenSecretsForService picks the secrets backend for the core.
//
// ⚠ THE FILE STORE IS THE DEFAULT, not the keyring (a reversal of D11, found in
// P1): the core starts at BOOT, before anyone logs in, and on a Linux desktop
// the login keyring is locked until a login unlocks it (Windows Credential
// Manager is likely unavailable to an S4U task too). A keyring token is
// therefore unreadable at exactly the moment always-on exists for. The keyring
// remains an explicit opt-in: HUGINN_SECRETS=keyring.
func OpenSecretsForService(p store.Paths, recorded string) (store.Secrets, store.Selection) {
	force := os.Getenv("HUGINN_SECRETS")
	if force == "" && recorded == "" {
		force = "file"
	}
	return store.OpenSecrets(store.SecretsOptions{Dir: p.SecretsDir(), Recorded: recorded, Force: force})
}

// Run serves until ctx ends.
func Run(ctx context.Context, o Options) error {
	logger := o.Logger
	if logger == nil {
		logger = log.New(os.Stderr, "huginn-core: ", log.LstdFlags)
	}
	st, err := store.Open(o.Paths)
	if err != nil {
		return err
	}
	cfg, rep, err := st.Load()
	if err != nil {
		return err
	}
	if rep.Corrupt {
		logger.Printf("core.json did not parse (%s) — kept as %s, starting fresh", rep.ParseError, rep.CorruptPath)
	}
	sec, sel := OpenSecretsForService(o.Paths, cfg.Secrets)
	logger.Printf("secrets: %s (%s)", sel.Backend, sel.Why)

	d := &Daemon{paths: o.Paths, st: st, secrets: sec, sel: sel, hub: ipc.NewHub(), logf: logger.Printf, lock: newLockReader(), ctx: ctx,
		restartNow: make(chan struct{})}
	d.watch = &watcher{d: d}
	d.watch.keepTray.Store(1) // the app's default, until it says
	d.installID = installID(st)
	if cfg.MigratedFrom == nil && cfg.Device.ID == "" && cfg.Appd.URL == "" {
		d.migrate = d.firstStartImport(ctx)
		logger.Printf("first start: %s", d.migrate)
	}
	if changed, err := upgradeStoredURLs(st); err != nil {
		logger.Printf("could not rewrite the saved huginn addresses in the form they are dialled: %v", err)
	} else if len(changed) > 0 {
		logger.Printf("saved huginn addresses now in the form they are dialled (TLS, proved): %s", strings.Join(changed, ", "))
	}

	srv := ipc.New(SocketPath(o.Paths), logger)
	d.routes(srv.Mux)
	if err := srv.Listen(); err != nil {
		return err
	}
	logger.Printf("huginn core %s listening on %s", version.Version, srv.Path)
	d.restartRunner(ctx)

	errc := make(chan error, 1)
	go func() { errc <- srv.Serve() }()
	select {
	case <-ctx.Done():
	case err = <-errc:
	case <-d.restartNow:
		logger.Printf("huginn core %s exiting for a restart onto the installed binary", version.Version)
		err = ErrRestart
	}
	sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	srv.Shutdown(sctx)
	// A job still running is not stopped here: an update waits for it before
	// asking, and a service manager stopping the core signals the child too.
	d.stopRunner(false)
	return err
}

// tokenSource reads the token from the secrets store on every request, so a
// re-pair underneath a running client is picked up.
type tokenSource struct{ s store.Secrets }

func (t tokenSource) Token() (string, error) {
	v, err := t.s.Get(store.SecretToken)
	if err == nil && strings.TrimSpace(v) != "" {
		return strings.TrimSpace(v), nil
	}
	why := "this machine is not paired with huginn — run `huginn core pair`"
	if err != nil && !errors.Is(err, store.ErrNotFound) {
		why = fmt.Sprintf("the token store (%s) could not be read: %v", t.s.Backend(), err)
	}
	return "", &appd.NoTokenError{Reason: appd.TokenMissing, Why: why}
}

// pinStore adapts the store's tag-keyed map to the appd client's single set.
// Only one tag is ever kept: a set proved under a different token is useless.
type pinStore struct{ st *store.Store }

func (p pinStore) LoadPins() (appd.PinSet, error) {
	cfg, _, err := p.st.Load()
	if err != nil {
		return appd.PinSet{}, err
	}
	for tag, pins := range cfg.Pins {
		return appd.PinSet{Tag: tag, Pins: pins}, nil
	}
	return appd.PinSet{}, nil
}

func (p pinStore) SavePins(ps appd.PinSet) error {
	_, _, err := p.st.Update(func(c *store.Config) error {
		c.Pins = map[string][]string{ps.Tag: ps.Pins}
		return nil
	})
	return err
}

// restartRunner (re)builds the appd client from the current config and starts
// a fresh runner on it: at start, after a pairing with another huginn, and
// after an unpair (which leaves none). A route edit, or the same huginn paired
// again, re-routes the running client instead (reroute).
//
// ⚠ ONE AT A TIME, AND THE OLD RUNNER IS GONE BEFORE THE NEW ONE IS PUBLISHED.
// stopRunner used to unpublish the runner first and wait for it after, so a
// second call (a retried change, the next address the desktop added) found
// nothing to stop and started a runner of its own, which the first call then
// overwrote. That runner could no longer be stopped by anything, kept polling
// as this same device, and each of its polls made appd drop the other
// runner's job. Until the swap, the old client keeps serving the /appd proxy
// and busy() still sees the old runner's job.
//
// ⚠ A JOB THE OLD RUNNER IS RUNNING IS STOPPED. It ran for the huginn this
// core is leaving and can no longer report to it; left alone, the restart
// waited on it (an IPC call hung for the rest of the job) while an act run
// went on changing files for nobody.
func (d *Daemon) restartRunner(ctx context.Context) {
	d.restartMu.Lock()
	defer d.restartMu.Unlock()
	d.restartRunnerLocked(ctx)
}

func (d *Daemon) restartRunnerLocked(ctx context.Context) {
	if old := d.haltRunner(true); old != nil {
		defer old.Close()
	}
	cfg, _, err := d.st.Load()
	if err != nil {
		d.setRunner(nil, nil, nil, nil)
		d.publishDevice(runner.Status{Note: "Cannot read core.json: " + err.Error()})
		return
	}
	if cfg.Appd.URL == "" {
		d.setRunner(nil, nil, nil, nil)
		d.publishDevice(runner.Status{Enabled: cfg.Device.Enabled, Note: "Not paired with huginn — run `huginn core pair`"})
		return
	}
	api, err := appd.New(d.apiConfig(cfg))
	if err != nil {
		d.setRunner(nil, nil, nil, nil)
		d.publishDevice(runner.Status{Enabled: cfg.Device.Enabled, Note: "The saved huginn address is refused: " + err.Error()})
		return
	}
	home, _ := os.UserHomeDir()
	r := runner.New(runner.Hooks{
		API:      api,
		Conf:     d.runnerConf,
		SaveID:   d.saveID,
		ClearOwe: d.clearOwe,
		Lock:     d.lock.read,
		Publish:  d.publishDevice,
		Logf:     d.logf,
		Home:     home,
	})
	rctx, cancel := context.WithCancel(ctx)
	done := make(chan struct{})
	d.setRunner(api, r, cancel, done)
	go func() {
		defer close(done)
		var wg sync.WaitGroup
		wg.Add(3)
		go func() { defer wg.Done(); r.Serve(rctx) }()
		go func() { defer wg.Done(); d.watch.run(rctx, api) }()
		go func() {
			// Back on the preferred address once it answers again (a pinned
			// handshake, nothing sent; a no-op while already on it).
			defer wg.Done()
			t := time.NewTicker(time.Minute)
			defer t.Stop()
			for {
				select {
				case <-rctx.Done():
					return
				case <-t.C:
					api.TryPreferred(rctx)
				}
			}
		}()
		wg.Wait()
	}()
}

// stopRunner stops the runner and leaves none; cancelJobs stops a job it is
// running too (see haltRunner).
func (d *Daemon) stopRunner(cancelJobs bool) {
	d.restartMu.Lock()
	defer d.restartMu.Unlock()
	old := d.haltRunner(cancelJobs)
	d.setRunner(nil, nil, nil, nil)
	if old != nil {
		old.Close()
	}
}

// haltRunner stops the runner and waits until it has. restartMu is held. The
// runner and its client stay published until the caller replaces them, and
// the client is returned for the caller to close after that. Without
// cancelJobs a job it is running is left to finish, and this waits for it.
func (d *Daemon) haltRunner(cancelJobs bool) *appd.Client {
	d.mu.Lock()
	stop, done, api, r := d.stopRun, d.runDone, d.api, d.run
	d.mu.Unlock()
	if stop != nil {
		if cancelJobs && r != nil {
			r.CancelJobs()
		}
		stop()
		<-done
	}
	return api
}

// reroute gives the running client the saved route book, in place
// (appd.Client.Reroute): a route edit, or the same huginn paired again on
// another address, leaves the runner and any job it is running alone, and
// the IPC call that asked for it answers at once.
func (d *Daemon) reroute() {
	d.restartMu.Lock()
	defer d.restartMu.Unlock()
	cfg, _, err := d.st.Load()
	if api := d.apiClient(); err == nil && api != nil {
		ac := d.apiConfig(cfg)
		if err = api.Reroute(ac.URL, ac.Routes, ac.Fixed); err == nil {
			// A runner waiting to retry after "Not reaching huginn" tries the
			// new book now, as a fresh runner would have.
			d.kick()
			return
		}
		d.logf("the saved huginn addresses are refused (%v); starting the runner again on them", err)
	}
	// No client to re-route (the saved address was refused before), or a book
	// it refuses: built from the saved config, which reports why.
	d.restartRunnerLocked(d.ctx)
}

func (d *Daemon) setRunner(api *appd.Client, r *runner.Runner, stop context.CancelFunc, done chan struct{}) {
	d.mu.Lock()
	d.api, d.run, d.stopRun, d.runDone = api, r, stop, done
	d.mu.Unlock()
}

func (d *Daemon) runnerConf() runner.Config {
	cfg, _, err := d.st.Load()
	if err != nil {
		return runner.Config{} // disabled until the file reads again
	}
	dv := cfg.Device
	name := dv.Name
	if name == "" {
		name = defaultName()
	}
	return runner.Config{
		Enabled: dv.Enabled, ID: dv.ID, Name: name, Scope: deviceScope(dv), Root: dv.Root,
		Claude: dv.Claude, LLM: dv.LLM, Node: dv.Node, ActWhileLocked: dv.ActWhileLocked,
		UnenrolOwed: dv.UnenrolOwed,
	}
}

// deviceScope is the scope this machine offers: the saved one, or the
// enrolment default while none has been chosen.
//
// ⚠ ONE ANSWER FOR SETTINGS AND THE RUNNER. Nothing saves a scope until the
// owner picks one (turning the device on sends only `enabled`), and ParseScope
// floors "" to generate, as it does any junk: the runner enrolled a
// local-model server that could run no ask or act work while Settings showed
// Look selected.
func deviceScope(dv store.Device) string {
	if dv.Scope == "" {
		return policy.EnrolDefault
	}
	return policy.ParseScope(dv.Scope)
}

func defaultName() string {
	h, _ := os.Hostname()
	if r := []rune(h); len(r) > 40 {
		h = string(r[:40])
	}
	return h
}

func (d *Daemon) saveID(id, llmSlug string) {
	d.st.Update(func(c *store.Config) error {
		c.Device.ID = id
		if llmSlug != "" {
			c.Device.LLMSlug = llmSlug
		}
		return nil
	})
}

func (d *Daemon) clearOwe() {
	d.st.Update(func(c *store.Config) error {
		c.Device.UnenrolOwed = false
		c.Device.ID = "" // retired: the next enrolment mints a fresh row
		return nil
	})
}

func (d *Daemon) publishDevice(s runner.Status) {
	d.mu.Lock()
	d.last = s
	d.mu.Unlock()
	d.hub.Publish(ipc.Event{Name: "device", Data: d.deviceView(&s)})
}

// DeviceView is GET /core/device: the live status plus the settings that drive it.
type DeviceView struct {
	Status   runner.Status  `json:"status"`
	Settings DeviceSettings `json:"settings"`
}

// DeviceSettings is the editable half (PATCH /core/device).
type DeviceSettings struct {
	Enabled        *bool   `json:"enabled,omitempty"`
	Name           *string `json:"name,omitempty"`
	Scope          *string `json:"scope,omitempty"`
	Root           *string `json:"root,omitempty"`
	ActWhileLocked *bool   `json:"actWhileLocked,omitempty"`
	Claude         *string `json:"claude,omitempty"`
}

func (d *Daemon) deviceView(s *runner.Status) DeviceView {
	if s == nil {
		// ⚠ THE LAST PUBLISHED STATUS, not "the runner's or nothing": while the
		// core is unpaired there is no runner, and GET answered a blank note while
		// the event stream said "not paired" (caught by CoreClientTest).
		d.mu.Lock()
		cur := d.last
		d.mu.Unlock()
		s = &cur
	}
	cfg, _, _ := d.st.Load()
	dv := cfg.Device
	scope := deviceScope(dv)
	name := dv.Name
	if name == "" {
		name = defaultName()
	}
	return DeviceView{Status: *s, Settings: DeviceSettings{
		Enabled: &dv.Enabled, Name: &name, Scope: &scope, Root: &dv.Root,
		ActWhileLocked: &dv.ActWhileLocked, Claude: &dv.Claude,
	}}
}

// ApplyDevice writes a settings change. Turning OFF with a registered row
// records the unenrol debt in the same write, so a crash in between cannot
// forget it.
func ApplyDevice(c *store.Config, in DeviceSettings) error {
	if in.Scope != nil {
		sc := strings.TrimSpace(*in.Scope)
		if sc == "generate" {
			return errors.New("scope \"generate\" is set by `huginn local`, not here")
		}
		if policy.ParseScope(sc) != sc {
			return fmt.Errorf("unknown scope %q (look, work or own)", sc)
		}
		c.Device.Scope = sc
	}
	if in.Name != nil {
		n := strings.TrimSpace(*in.Name)
		if r := []rune(n); len(r) > 40 {
			n = string(r[:40])
		}
		c.Device.Name = n
	}
	if in.Root != nil {
		c.Device.Root = strings.TrimSpace(*in.Root)
	}
	if in.ActWhileLocked != nil {
		c.Device.ActWhileLocked = *in.ActWhileLocked
	}
	if in.Claude != nil {
		c.Device.Claude = strings.TrimSpace(*in.Claude)
	}
	if in.Enabled != nil {
		on := *in.Enabled
		if on {
			c.Device.UnenrolOwed = false
		} else if c.Device.Enabled && c.Device.ID != "" {
			c.Device.UnenrolOwed = true
		}
		c.Device.Enabled = on
	}
	return nil
}

// busy: the runner is in the middle of a job.
func (d *Daemon) busy() bool {
	d.mu.Lock()
	r := d.run
	d.mu.Unlock()
	return r != nil && r.Status().Busy
}

// requestRestart exits the core once no job is running, so an update never
// kills remote work halfway: a job finishes under the binary that started it.
func (d *Daemon) requestRestart() {
	d.restartOnce.Do(func() {
		go func() {
			waited := false
			for d.busy() {
				if !waited {
					d.logf("restart requested: waiting for the running job to finish")
					waited = true
				}
				select {
				case <-d.ctx.Done():
					return
				case <-time.After(5 * time.Second):
				}
			}
			time.Sleep(200 * time.Millisecond) // let the 202 reach the caller
			close(d.restartNow)
		}()
	})
}

func (d *Daemon) kick() {
	d.mu.Lock()
	r := d.run
	d.mu.Unlock()
	if r != nil {
		r.Kick()
	}
}

// Pair is POST /core/pair.
type Pair struct {
	URL   string `json:"url"`
	Token string `json:"token"`
}

// pair proves the daemon with the new token BEFORE saving anything, so a typo
// leaves the working pairing in place. It returns the proved fingerprint ("" for
// plain http under HUGINN_APPD_PLAINTEXT=local) and the url as it was saved.
func (d *Daemon) pair(ctx context.Context, p Pair) (fp, saved string, err error) {
	url := appd.NormalizeURL(strings.TrimSpace(p.URL))
	tok := strings.TrimSpace(p.Token)
	if tok == "" {
		return "", "", errors.New("no token given")
	}
	mem := &appd.MemoryPinStore{}
	probe, err := appd.New(appd.Config{URL: url, Tokens: appd.StaticToken(tok), Pins: mem})
	if err != nil {
		return "", "", err
	}
	defer probe.Close()
	pctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	// ⚠ PROVED ON THIS MACHINE TOO (security scan F8). Pairing used to skip the
	// proof for any http:// url, and the status request below then handed the
	// token to whatever held a loopback port. NormalizeURL dials a loopback
	// http:// as https:// now, so the only url left unproved is plain http
	// under HUGINN_APPD_PLAINTEXT=local, which has no certificate to prove.
	if !(strings.HasPrefix(url, "http://") && appd.PlaintextLoopback()) {
		if fp, err = probe.Prove(pctx); err != nil {
			return "", "", err
		}
	}
	if _, err := probe.Do(pctx, "GET", "/v1/status", nil); err != nil {
		return "", "", err
	}
	prevTok, _ := d.secrets.Get(store.SecretToken) // read before it is replaced
	if err := d.secrets.Set(store.SecretToken, tok); err != nil {
		return "", "", fmt.Errorf("saving the token (%s): %w", d.secrets.Backend(), err)
	}
	ps, _ := mem.LoadPins()
	_, _, err = d.st.Update(func(c *store.Config) error {
		// Another daemon (another token): its addresses are not this one's.
		// The same daemon on another address keeps its route book, minus the
		// address that is now the paired one.
		if prevTok != tok {
			c.Appd.Routes, c.Appd.Pinned = nil, ""
		}
		c.Appd.Routes = slices.DeleteFunc(c.Appd.Routes, func(r store.Route) bool { return r.URL == url })
		if c.Appd.Pinned == url {
			c.Appd.Pinned = ""
		}
		c.Appd.URL = url
		c.Secrets = d.secrets.Backend()
		c.Pins = nil
		if ps.Tag != "" {
			c.Pins = map[string][]string{ps.Tag: ps.Pins}
		}
		return nil
	})
	if err != nil {
		return "", "", err
	}
	return fp, url, nil
}

// hasToken: the secrets store holds an appd token.
func (d *Daemon) hasToken() bool {
	t, err := d.secrets.Get(store.SecretToken)
	return err == nil && strings.TrimSpace(t) != ""
}

func (d *Daemon) routes(mux *http.ServeMux) {
	mux.HandleFunc("/appd/", d.proxy)
	d.routeHandlers(mux)
	d.localHandlers(mux)
	mux.HandleFunc("GET /core/events", func(w http.ResponseWriter, r *http.Request) {
		// A tray role identifies itself, so the core knows somebody can SEE a
		// notification before it claims them from appd (D14).
		if r.URL.Query().Get("role") == "tray" {
			// The GUI follows this to stand down while a core tray is attached:
			// no second tray icon, no second copy of every notification.
			// A tray that cannot show a notification (no notification server,
			// no shortcut carrying the toast identity) is not a reason to take
			// them away from Telegram (security review, 2.1.0).
			if r.URL.Query().Get("deliver") != "0" {
				d.watch.senders.Add(1)
				defer d.watch.senders.Add(-1)
			}
			if d.watch.trays.Add(1) == 1 {
				d.hub.Publish(ipc.Event{Name: traystate.EventCoreTray, Data: map[string]bool{"attached": true}})
			}
			defer func() {
				if d.watch.trays.Add(-1) == 0 {
					d.hub.Publish(ipc.Event{Name: traystate.EventCoreTray, Data: map[string]bool{"attached": false}})
				}
			}()
		}
		// The desktop app identifies itself too, so the tray can carry its
		// items while it is open (owner rule 2026-10-17: one icon, one menu).
		if r.URL.Query().Get("role") == "gui" {
			if d.watch.guis.Add(1) == 1 {
				d.publishGUI()
			}
			defer func() {
				if d.watch.guis.Add(-1) == 0 {
					// The app is gone (quit, crash, killed), so it shows nothing.
					// Its last focus report would otherwise go on silencing that
					// target's alerts until focusTTL ran out; nothing else ever
					// clears it, since only the app reports focus.
					d.watch.setFocus("")
					d.publishGUI()
				}
			}()
		}
		d.hub.ServeHTTP(w, r)
	})
	// POST /core/presence {"focused":"sess:<name>"|"chat:<id>"|"", "closeToTray":bool}:
	// what the GUI shows, so its question is not also a toast, and its "Close
	// to tray" for the tray's menu. Repeated while visible; a focus older than
	// focusTTL is ignored.
	mux.HandleFunc("POST /core/presence", func(w http.ResponseWriter, r *http.Request) {
		var p struct {
			Focused     *string `json:"focused"` // absent: focus unchanged (a Close-to-tray report alone)
			CloseToTray *bool   `json:"closeToTray"`
		}
		if err := decode(r, &p); err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		if p.Focused != nil {
			d.watch.setFocus(*p.Focused)
		}
		if p.CloseToTray != nil {
			v := int32(0)
			if *p.CloseToTray {
				v = 1
			}
			if d.watch.keepTray.Swap(v) != v {
				d.publishGUI()
			}
		}
		ipc.WriteJSON(w, 200, map[string]bool{"claim": d.watch.claim()})
	})
	// POST /core/tray {"cmd":"hide"}: the desktop app's "Tray icon" switch
	// taking the icon away (it comes back at the next sign-in, or from that
	// switch, which starts `huginn core tray`). Sent once, never retained.
	mux.HandleFunc("POST /core/tray", func(w http.ResponseWriter, r *http.Request) {
		var c struct {
			Cmd string `json:"cmd"`
		}
		if err := decode(r, &c); err != nil || c.Cmd != "hide" {
			ipc.WriteJSON(w, 400, map[string]string{"error": "the one command is hide"})
			return
		}
		if d.watch.trays.Load() == 0 {
			ipc.WriteJSON(w, 409, map[string]string{"error": "no tray icon is showing"})
			return
		}
		d.hub.Send(ipc.Event{Name: traystate.EventTrayCmd, Data: c})
		ipc.WriteJSON(w, 202, map[string]bool{"sent": true})
	})
	// POST /core/gui {"cmd":"open"|"quit"|"closeToTray", "value":bool, "url":"huginn://…"}:
	// the tray's menu acting on the desktop app. Sent once, never retained.
	mux.HandleFunc("POST /core/gui", func(w http.ResponseWriter, r *http.Request) {
		var c traystate.GUICmd
		if err := decode(r, &c); err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		switch {
		case c.Cmd == traystate.CmdCloseToTray && c.Value == nil:
			ipc.WriteJSON(w, 400, map[string]string{"error": "closeToTray needs a value"})
			return
		case c.Cmd != traystate.CmdOpen && c.Cmd != traystate.CmdQuit && c.Cmd != traystate.CmdCloseToTray:
			ipc.WriteJSON(w, 400, map[string]string{"error": "unknown command " + c.Cmd})
			return
		case c.URL != "" && !strings.HasPrefix(c.URL, "huginn://"):
			ipc.WriteJSON(w, 400, map[string]string{"error": "only huginn:// targets"})
			return
		}
		if d.watch.guis.Load() == 0 {
			// The caller opens the app itself (huginn://open) when none is here.
			ipc.WriteJSON(w, 409, map[string]string{"error": "no desktop app is attached"})
			return
		}
		d.hub.Send(ipc.Event{Name: traystate.EventGUICmd, Data: c})
		ipc.WriteJSON(w, 202, map[string]bool{"sent": true})
	})
	mux.HandleFunc("GET /core/status", func(w http.ResponseWriter, r *http.Request) {
		cfg, _, _ := d.st.Load()
		// The proved certificate fingerprints: public hashes, not secrets. A
		// desktop compares them with its own pins to tell "the same huginn on
		// another address" from "another huginn" before it hands its link over.
		pins := []string{}
		for _, ps := range cfg.Pins {
			pins = append(pins, ps...)
		}
		ipc.WriteJSON(w, 200, map[string]any{
			"version": version.Version, "proto": version.Proto, "pid": os.Getpid(),
			// Paired = an address AND a token: an address alone reaches nothing.
			"paired": cfg.Appd.URL != "" && d.hasToken(), "url": cfg.Appd.URL, "pins": pins,
			"secrets": d.sel.Backend, "secretsWhy": d.sel.Why,
			"migratedFrom": cfg.MigratedFrom, "firstStart": d.migrate,
			"tray": d.watch.trays.Load() > 0, "gui": d.watch.guis.Load() > 0, "notifyClaim": d.watch.claim(),
			"device": d.deviceView(nil),
		})
	})
	// POST /core/restart: an update installed a different binary at the stable
	// path; exit (after any running job) so the supervisor starts that one.
	mux.HandleFunc("POST /core/restart", func(w http.ResponseWriter, r *http.Request) {
		busy := d.busy()
		ipc.WriteJSON(w, 202, map[string]any{"restarting": true, "afterJob": busy, "version": version.Version})
		d.requestRestart()
	})
	mux.HandleFunc("GET /core/device", func(w http.ResponseWriter, r *http.Request) {
		ipc.WriteJSON(w, 200, d.deviceView(nil))
	})
	mux.HandleFunc("PATCH /core/device", func(w http.ResponseWriter, r *http.Request) {
		var in DeviceSettings
		if err := decode(r, &in); err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		if _, _, err := d.st.Update(func(c *store.Config) error { return ApplyDevice(c, in) }); err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		d.kick()
		v := d.deviceView(nil)
		d.hub.Publish(ipc.Event{Name: "device", Data: v})
		ipc.WriteJSON(w, 200, v)
	})
	// POST /core/unpair: Settings → Remove this computer. The GUI has already
	// deleted this machine's rows on appd; the core stops its runner and forgets
	// the token, the address, the pins and the device id, so nothing here can
	// re-enrol with a credential the owner just took away. A job it is running
	// is stopped with it: appd dropped that run with the rows, and the call
	// must not wait out a job (the desktop gives up after 30 s and calls the
	// removal failed) while an act run goes on with the machine removed.
	mux.HandleFunc("POST /core/unpair", func(w http.ResponseWriter, r *http.Request) {
		d.stopRunner(true)
		if err := d.secrets.Delete(store.SecretToken); err != nil && !errors.Is(err, store.ErrNotFound) {
			ipc.WriteJSON(w, 500, map[string]string{"error": "forgetting the token: " + err.Error()})
			return
		}
		_, _, err := d.st.Update(func(c *store.Config) error {
			c.Appd = store.Appd{}
			c.Pins = nil
			c.Device.ID, c.Device.Enabled, c.Device.UnenrolOwed = "", false, false
			return nil
		})
		if err != nil {
			ipc.WriteJSON(w, 500, map[string]string{"error": err.Error()})
			return
		}
		d.restartRunner(d.ctx) // publishes "not paired"
		ipc.WriteJSON(w, 200, map[string]string{"ok": "unpaired"})
	})
	mux.HandleFunc("POST /core/pair", func(w http.ResponseWriter, r *http.Request) {
		var p Pair
		if err := decode(r, &p); err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		// The huginn paired until now: its token and the certificates it proved.
		tokBefore, _ := d.secrets.Get(store.SecretToken)
		var pinsBefore []string
		if cfg, _, err := d.st.Load(); err == nil {
			for _, ps := range cfg.Pins {
				pinsBefore = append(pinsBefore, ps...)
			}
		}
		fp, saved, err := d.pair(r.Context(), p)
		if err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		// ⚠ THE SAME HUGINN KEEPS ITS RUNNER: the same token, or a certificate
		// it had already proved (its token was rotated). Its device row and a
		// job running for it stay valid, so only the addresses change. Another
		// huginn starts over (restartRunner).
		if strings.TrimSpace(tokBefore) == strings.TrimSpace(p.Token) || (fp != "" && slices.Contains(pinsBefore, fp)) {
			d.reroute()
		} else {
			d.restartRunner(d.ctx)
		}
		ipc.WriteJSON(w, 200, map[string]string{"ok": "paired", "fingerprint": fp, "url": saved})
	})
}

// publishGUI tells the tray whether the desktop app is attached, and its
// "Close to tray" (retained: a tray that connects later reads it at once).
func (d *Daemon) publishGUI() {
	d.hub.Publish(ipc.Event{Name: traystate.EventGUI, Data: traystate.GUI{
		Attached:    d.watch.guis.Load() > 0,
		CloseToTray: d.watch.keepTray.Load() == 1,
	}})
}

