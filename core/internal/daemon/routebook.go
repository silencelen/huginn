package daemon

import (
	"net/http"
	"slices"
	"strings"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/store"
)

// The route book (docs/DESKTOP-VIA-CORE.md): the paired daemon's other
// addresses, which the appd client switches between (appd/routes.go). It is
// kept in core.json; /core/routes reads and edits it; the desktop shows it in
// Settings > Host; `huginn core route` is the same thing in a terminal.

// EventRoute is published on /core/events when the active address changes or
// an address's health does.
const EventRoute = "core.route"

// apiConfig is the appd client for cfg: the preferred address first, the
// others after it, or one pinned address and no switching.
func (d *Daemon) apiConfig(cfg store.Config) appd.Config {
	first := cfg.Appd.URL
	var rest []string
	for _, r := range cfg.Appd.Routes {
		rest = append(rest, r.URL)
	}
	fixed := cfg.Appd.NoAutoSwitch
	if p := cfg.Appd.Pinned; p != "" && p != first {
		rest = append([]string{first}, slices.DeleteFunc(rest, func(u string) bool { return u == p })...)
		first, fixed = p, true
	} else if p != "" {
		fixed = true
	}
	return appd.Config{URL: first, Routes: rest, Fixed: fixed, Tokens: tokenSource{d.secrets}, Pins: pinStore{d.st},
		Logf: d.logf, Headers: d.watch.headers, OnRoute: d.routeEvent}
}

func (d *Daemon) routeEvent(ev appd.RouteEvent) {
	if d.hub != nil {
		d.hub.Publish(ipc.Event{Name: EventRoute, Data: ev})
	}
}

// RouteView is one address as /core/routes shows it.
type RouteView struct {
	URL       string    `json:"url"`
	Name      string    `json:"name,omitempty"`
	Kind      string    `json:"kind,omitempty"`
	Preferred bool      `json:"preferred,omitempty"` // the address it was paired on
	Pinned    bool      `json:"pinned,omitempty"`
	Active    bool      `json:"active"`
	LastSeen  time.Time `json:"lastSeenAt,omitzero"`
	LastFail  time.Time `json:"lastFailAt,omitzero"`
	LastError string    `json:"lastError,omitempty"`
}

// RoutesView is GET /core/routes.
type RoutesView struct {
	AutoSwitch bool        `json:"autoSwitch"`
	Routes     []RouteView `json:"routes"`
}

func (d *Daemon) routesView() (RoutesView, error) {
	cfg, _, err := d.st.Load()
	if err != nil {
		return RoutesView{}, err
	}
	v := RoutesView{AutoSwitch: !cfg.Appd.NoAutoSwitch}
	if cfg.Appd.URL == "" {
		return v, nil
	}
	health := map[string]appd.RouteHealth{}
	if api := d.apiClient(); api != nil {
		for _, h := range api.Routes() {
			health[h.URL] = h
		}
	}
	add := func(url, name, kind string, preferred bool) {
		h := health[strings.TrimSuffix(url, "/")]
		v.Routes = append(v.Routes, RouteView{URL: url, Name: name, Kind: kind, Preferred: preferred, Pinned: url == cfg.Appd.Pinned,
			Active: h.Active, LastSeen: h.LastSeenAt, LastFail: h.LastFailAt, LastError: h.LastError})
	}
	add(cfg.Appd.URL, "", "", true)
	for _, r := range cfg.Appd.Routes {
		add(r.URL, r.Name, r.Kind, false)
	}
	return v, nil
}

// RouteAdd is POST /core/routes.
type RouteAdd struct {
	URL  string `json:"url"`
	Name string `json:"name,omitempty"`
	Kind string `json:"kind,omitempty"`
}

// RoutePatch is PATCH /core/routes: Pinned "" unpins; AutoSwitch sets it.
type RoutePatch struct {
	Pinned     *string `json:"pinned,omitempty"`
	AutoSwitch *bool   `json:"autoSwitch,omitempty"`
}

var routeKinds = []string{"", "lan", "mesh", "tailnet", "custom"}

func (d *Daemon) routeHandlers(mux *http.ServeMux) {
	bad := func(w http.ResponseWriter, code int, msg string) {
		ipc.WriteJSON(w, code, map[string]string{"error": msg})
	}
	answer := func(w http.ResponseWriter) {
		v, err := d.routesView()
		if err != nil {
			bad(w, 500, err.Error())
			return
		}
		ipc.WriteJSON(w, 200, v)
	}
	paired := func(w http.ResponseWriter) (store.Config, bool) {
		cfg, _, err := d.st.Load()
		if err != nil {
			bad(w, 500, err.Error())
			return cfg, false
		}
		if cfg.Appd.URL == "" {
			bad(w, 409, "huginn core is not paired with huginn: pair it first")
			return cfg, false
		}
		return cfg, true
	}
	mux.HandleFunc("GET /core/routes", func(w http.ResponseWriter, r *http.Request) { answer(w) })
	mux.HandleFunc("POST /core/routes", func(w http.ResponseWriter, r *http.Request) {
		var in RouteAdd
		if err := decode(r, &in); err != nil {
			bad(w, 400, err.Error())
			return
		}
		u := appd.NormalizeURL(strings.TrimSpace(in.URL))
		if err := appd.URLRefusal(u); err != nil {
			bad(w, 400, err.Error())
			return
		}
		if !slices.Contains(routeKinds, in.Kind) {
			bad(w, 400, "kind must be lan, mesh, tailnet or custom")
			return
		}
		cfg, ok := paired(w)
		if !ok {
			return
		}
		if u == cfg.Appd.URL || slices.ContainsFunc(cfg.Appd.Routes, func(x store.Route) bool { return x.URL == u }) {
			answer(w) // already there: adding it again is a no-op, not an error
			return
		}
		if _, _, err := d.st.Update(func(c *store.Config) error {
			c.Appd.Routes = append(c.Appd.Routes, store.Route{URL: u, Name: strings.TrimSpace(in.Name), Kind: in.Kind, AddedAt: time.Now().UTC()})
			return nil
		}); err != nil {
			bad(w, 500, err.Error())
			return
		}
		d.restartRunner(d.ctx)
		answer(w)
	})
	mux.HandleFunc("DELETE /core/routes", func(w http.ResponseWriter, r *http.Request) {
		u := appd.NormalizeURL(strings.TrimSpace(r.URL.Query().Get("url")))
		cfg, ok := paired(w)
		if !ok {
			return
		}
		if u == cfg.Appd.URL {
			bad(w, 400, "that is the address huginn core was paired on; pair again on another one to replace it")
			return
		}
		i := slices.IndexFunc(cfg.Appd.Routes, func(x store.Route) bool { return x.URL == u })
		if i < 0 {
			bad(w, 404, "no such address in the route book")
			return
		}
		if _, _, err := d.st.Update(func(c *store.Config) error {
			c.Appd.Routes = slices.DeleteFunc(c.Appd.Routes, func(x store.Route) bool { return x.URL == u })
			if c.Appd.Pinned == u {
				c.Appd.Pinned = ""
			}
			return nil
		}); err != nil {
			bad(w, 500, err.Error())
			return
		}
		d.restartRunner(d.ctx)
		answer(w)
	})
	mux.HandleFunc("PATCH /core/routes", func(w http.ResponseWriter, r *http.Request) {
		var in RoutePatch
		if err := decode(r, &in); err != nil {
			bad(w, 400, err.Error())
			return
		}
		cfg, ok := paired(w)
		if !ok {
			return
		}
		pin := ""
		if in.Pinned != nil && *in.Pinned != "" {
			pin = appd.NormalizeURL(strings.TrimSpace(*in.Pinned))
			if pin != cfg.Appd.URL && !slices.ContainsFunc(cfg.Appd.Routes, func(x store.Route) bool { return x.URL == pin }) {
				bad(w, 400, "pin one of the route book's own addresses")
				return
			}
		}
		if _, _, err := d.st.Update(func(c *store.Config) error {
			if in.Pinned != nil {
				c.Appd.Pinned = pin
			}
			if in.AutoSwitch != nil {
				c.Appd.NoAutoSwitch = !*in.AutoSwitch
			}
			return nil
		}); err != nil {
			bad(w, 500, err.Error())
			return
		}
		d.restartRunner(d.ctx)
		answer(w)
	})
}
