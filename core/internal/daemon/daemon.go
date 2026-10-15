// Package daemon assembles `huginn core run`: the store, the token, the appd
// client, the device runner and the IPC server, for one OS user
// (docs/CORE-ARCHITECTURE.md §3, P1).
package daemon

import (
	"context"
	"errors"
	"fmt"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/policy"
	"github.com/silencelen/huginn/core/internal/runner"
	"github.com/silencelen/huginn/core/internal/store"
	"github.com/silencelen/huginn/core/internal/version"
)

// SocketPath is where this user's core listens. The GUI computes the same
// path (desktop/core/CorePaths.kt) — change both together.
func SocketPath(p store.Paths) string { return filepath.Join(p.Runtime, "core.sock") }

// Daemon is one running core.
type Daemon struct {
	paths   store.Paths
	st      *store.Store
	secrets store.Secrets
	sel     store.Selection
	hub     *ipc.Hub
	logf    func(string, ...any)
	lock    *lockReader
	ctx     context.Context // the daemon's lifetime; runners restart under it
	watch     *watcher // appd watch stream -> tray summary + notices
	installID string   // X-Huginn-Client

	mu      sync.Mutex
	api     *appd.Client
	run     *runner.Runner
	stopRun context.CancelFunc
	runDone chan struct{}
	migrate string // one line: what the first-start import did
	last    runner.Status // newest status published, runner or not ("not paired" has no runner)
}

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

	d := &Daemon{paths: o.Paths, st: st, secrets: sec, sel: sel, hub: ipc.NewHub(), logf: logger.Printf, lock: newLockReader(), ctx: ctx}
	d.watch = &watcher{d: d}
	d.installID = installID(st)
	if cfg.MigratedFrom == nil && cfg.Device.ID == "" && cfg.Appd.URL == "" {
		d.migrate = d.firstStartImport(ctx)
		logger.Printf("first start: %s", d.migrate)
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
	}
	sctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	srv.Shutdown(sctx)
	d.stopRunner()
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
// a fresh runner on it. Called at start and after a re-pair.
func (d *Daemon) restartRunner(ctx context.Context) {
	d.stopRunner()
	cfg, _, err := d.st.Load()
	if err != nil {
		d.publishDevice(runner.Status{Note: "Cannot read core.json: " + err.Error()})
		return
	}
	if cfg.Appd.URL == "" {
		d.publishDevice(runner.Status{Enabled: cfg.Device.Enabled, Note: "Not paired with huginn — run `huginn core pair`"})
		return
	}
	api, err := appd.New(appd.Config{URL: cfg.Appd.URL, Tokens: tokenSource{d.secrets}, Pins: pinStore{d.st}, Logf: d.logf,
		Headers: d.watch.headers})
	if err != nil {
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
	d.mu.Lock()
	d.api, d.run, d.stopRun, d.runDone = api, r, cancel, done
	d.mu.Unlock()
	go func() {
		defer close(done)
		var wg sync.WaitGroup
		wg.Add(2)
		go func() { defer wg.Done(); r.Serve(rctx) }()
		go func() { defer wg.Done(); d.watch.run(rctx, api) }()
		wg.Wait()
	}()
}

func (d *Daemon) stopRunner() {
	d.mu.Lock()
	stop, done, api := d.stopRun, d.runDone, d.api
	d.stopRun, d.runDone, d.run, d.api = nil, nil, nil, nil
	d.mu.Unlock()
	if stop != nil {
		stop()
		<-done
	}
	if api != nil {
		api.Close()
	}
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
		Enabled: dv.Enabled, ID: dv.ID, Name: name, Scope: dv.Scope, Root: dv.Root,
		Claude: dv.Claude, LLM: dv.LLM, Node: dv.Node, ActWhileLocked: dv.ActWhileLocked,
		UnenrolOwed: dv.UnenrolOwed,
	}
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
	scope := policy.ParseScope(dv.Scope)
	if dv.Scope == "" {
		scope = policy.EnrolDefault
	}
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
// leaves the working pairing in place.
func (d *Daemon) pair(ctx context.Context, p Pair) (string, error) {
	url := appd.NormalizeURL(strings.TrimSpace(p.URL))
	tok := strings.TrimSpace(p.Token)
	if tok == "" {
		return "", errors.New("no token given")
	}
	mem := &appd.MemoryPinStore{}
	probe, err := appd.New(appd.Config{URL: url, Tokens: appd.StaticToken(tok), Pins: mem})
	if err != nil {
		return "", err
	}
	defer probe.Close()
	pctx, cancel := context.WithTimeout(ctx, 20*time.Second)
	defer cancel()
	fp := ""
	if !strings.HasPrefix(url, "http://") {
		if fp, err = probe.Prove(pctx); err != nil {
			return "", err
		}
	}
	if _, err := probe.Do(pctx, "GET", "/v1/status", nil); err != nil {
		return "", err
	}
	if err := d.secrets.Set(store.SecretToken, tok); err != nil {
		return "", fmt.Errorf("saving the token (%s): %w", d.secrets.Backend(), err)
	}
	ps, _ := mem.LoadPins()
	_, _, err = d.st.Update(func(c *store.Config) error {
		c.Appd.URL = url
		c.Secrets = d.secrets.Backend()
		c.Pins = nil
		if ps.Tag != "" {
			c.Pins = map[string][]string{ps.Tag: ps.Pins}
		}
		return nil
	})
	if err != nil {
		return "", err
	}
	return fp, nil
}

func (d *Daemon) routes(mux *http.ServeMux) {
	mux.HandleFunc("GET /core/events", func(w http.ResponseWriter, r *http.Request) {
		// A tray role identifies itself, so the core knows somebody can SEE a
		// notification before it claims them from appd (D14).
		if r.URL.Query().Get("role") == "tray" {
			d.watch.trays.Add(1)
			defer d.watch.trays.Add(-1)
		}
		d.hub.ServeHTTP(w, r)
	})
	// POST /core/presence {"focused":"sess:<name>"|"chat:<id>"|""}: what the GUI
	// shows, so its question is not also a toast. Repeated while visible; a
	// report older than focusTTL is ignored.
	mux.HandleFunc("POST /core/presence", func(w http.ResponseWriter, r *http.Request) {
		var p struct {
			Focused string `json:"focused"`
		}
		if err := decode(r, &p); err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		d.watch.setFocus(p.Focused)
		ipc.WriteJSON(w, 200, map[string]bool{"claim": d.watch.claim()})
	})
	mux.HandleFunc("GET /core/status", func(w http.ResponseWriter, r *http.Request) {
		cfg, _, _ := d.st.Load()
		ipc.WriteJSON(w, 200, map[string]any{
			"version": version.Version, "proto": version.Proto, "pid": os.Getpid(),
			"paired": cfg.Appd.URL != "", "url": cfg.Appd.URL,
			"secrets": d.sel.Backend, "secretsWhy": d.sel.Why,
			"migratedFrom": cfg.MigratedFrom, "firstStart": d.migrate,
			"tray": d.watch.trays.Load() > 0, "notifyClaim": d.watch.claim(),
			"device": d.deviceView(nil),
		})
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
	// re-enrol with a credential the owner just took away.
	mux.HandleFunc("POST /core/unpair", func(w http.ResponseWriter, r *http.Request) {
		d.stopRunner()
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
		fp, err := d.pair(r.Context(), p)
		if err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		d.restartRunner(d.ctx)
		ipc.WriteJSON(w, 200, map[string]string{"ok": "paired", "fingerprint": fp})
	})
}
