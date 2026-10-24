package daemon

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"path/filepath"
	"slices"
	"strings"
	"sync"
	"testing"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
	"github.com/silencelen/huginn/core/internal/store"
)

// `huginn core pair --url http://127.0.0.1:8787` on the
// server itself skipped the proof, and the status request that followed handed
// the token to whatever held that port. Pairing proves a daemon on this
// machine exactly as it proves one anywhere else.

func pairRig(t *testing.T) *Daemon {
	t.Helper()
	t.Setenv(appd.TokenEnv, "")     // a developer's shell must not change what is tested
	t.Setenv(appd.PlaintextEnv, "") // likewise
	dir := t.TempDir()
	st, err := store.Open(store.Paths{Config: dir})
	if err != nil {
		t.Fatal(err)
	}
	return &Daemon{st: st, secrets: store.NewFileSecrets(filepath.Join(dir, "secrets"))}
}

// squatter holds a loopback port in plain http and answers like a daemon.
func squatter(t *testing.T) (*httptest.Server, func() []string) {
	t.Helper()
	var mu sync.Mutex
	var auth []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		if a := r.Header.Get("Authorization"); a != "" {
			auth = append(auth, a)
		}
		mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		_, _ = io.WriteString(w, `{"ok":true,"appdVersion":"9.9.9"}`)
	}))
	t.Cleanup(srv.Close)
	return srv, func() []string { mu.Lock(); defer mu.Unlock(); return slices.Clone(auth) }
}

func TestPairNeverSendsTheTokenToALoopbackSquatter(t *testing.T) {
	d := pairRig(t)
	tok := strings.Repeat("a", 64)
	sq, auth := squatter(t)
	if !strings.HasPrefix(sq.URL, "http://127.0.0.1:") {
		t.Fatalf("not a loopback http url: %s", sq.URL)
	}
	_, _, err := d.pair(context.Background(), Pair{URL: sq.URL, Token: tok})
	if !errors.Is(err, appd.ErrOldDaemon) {
		t.Fatalf("paired with a listener that never proved itself: %v", err)
	}
	if got := auth(); len(got) != 0 {
		t.Fatalf("the squatter was handed %q", got)
	}
	if _, err := d.secrets.Get(store.SecretToken); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("a refused pairing saved the token: %v", err)
	}
	if cfg, _, _ := d.st.Load(); cfg.Appd.URL != "" {
		t.Fatalf("a refused pairing saved the address %s", cfg.Appd.URL)
	}
}

func TestPairProvesALoopbackDaemonAndSavesTheTLSAddress(t *testing.T) {
	d := pairRig(t)
	tok := strings.Repeat("b", 64)
	srv := appdtest.NewServer(t, tok, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Header.Get("Authorization") != "Bearer "+tok {
			http.Error(w, `{"error":"unauthorized"}`, 401)
			return
		}
		_, _ = io.WriteString(w, `{"appdVersion":"`+appdtest.Version+`"}`)
	}))
	typed := strings.Replace(srv.URL, "https://", "http://", 1) // as a core on the server stored it
	fp, saved, err := d.pair(context.Background(), Pair{URL: typed, Token: tok})
	if err != nil {
		t.Fatal(err)
	}
	if want := appd.Fingerprint(srv.Certificate().Raw); fp != want {
		t.Fatalf("proved %q, want the daemon's own leaf %q", fp, want)
	}
	cfg, _, err := d.st.Load()
	if err != nil {
		t.Fatal(err)
	}
	if saved != srv.URL || cfg.Appd.URL != srv.URL {
		t.Fatalf("saved %q / core.json %q, want %s", saved, cfg.Appd.URL, srv.URL)
	}
	if got := cfg.Pins[appd.PinTag(tok)]; !slices.Equal(got, []string{fp}) {
		t.Fatalf("pins %v", cfg.Pins)
	}
	if got, _ := d.secrets.Get(store.SecretToken); got != tok {
		t.Fatal("the token was not saved")
	}
}

// The escape hatch is the old behaviour on purpose: plain http to loopback,
// with nothing proving who answers, and nothing pinned.
func TestPairWithThePlaintextHatchKeepsLoopbackPlain(t *testing.T) {
	d := pairRig(t)
	t.Setenv(appd.PlaintextEnv, "local")
	tok := strings.Repeat("c", 64)
	sq, auth := squatter(t)
	fp, saved, err := d.pair(context.Background(), Pair{URL: sq.URL, Token: tok})
	if err != nil {
		t.Fatal(err)
	}
	if fp != "" || saved != sq.URL {
		t.Fatalf("fp %q saved %q", fp, saved)
	}
	if got := auth(); len(got) != 1 {
		t.Fatalf("want the one opted-in plain request, got %q", got)
	}
}

// A core paired before loopback was proved holds http://127.0.0.1:8787; the
// route book's own lookups need it in the form it is now dialled.
func TestStoredLoopbackAddressesAreUpgradedOnce(t *testing.T) {
	d := pairRig(t)
	set := func(url string) {
		t.Helper()
		if _, _, err := d.st.Update(func(c *store.Config) error {
			c.Appd.URL = url
			c.Appd.Routes = []store.Route{
				{URL: "http://localhost:8788", Name: "here"},
				{URL: "https://127.0.0.1:8787"}, // the same daemon, already upgraded: kept once
				{URL: "https://192.0.2.10:8787", Name: "lan", Kind: "lan"},
			}
			c.Appd.Pinned = "http://localhost:8788"
			return nil
		}); err != nil {
			t.Fatal(err)
		}
	}
	set("http://127.0.0.1:8787")
	changed, err := upgradeStoredURLs(d.st)
	if err != nil || len(changed) != 3 {
		t.Fatalf("changed %q, %v", changed, err)
	}
	cfg, _, _ := d.st.Load()
	var routes []string
	for _, r := range cfg.Appd.Routes {
		routes = append(routes, r.URL+" "+r.Name)
	}
	if cfg.Appd.URL != "https://127.0.0.1:8787" || cfg.Appd.Pinned != "https://localhost:8788" ||
		!slices.Equal(routes, []string{"https://localhost:8788 here", "https://192.0.2.10:8787 lan"}) {
		t.Fatalf("core.json after: url %s pinned %s routes %q", cfg.Appd.URL, cfg.Appd.Pinned, routes)
	}
	if again, err := upgradeStoredURLs(d.st); err != nil || len(again) != 0 {
		t.Fatalf("a second start changed %q, %v", again, err)
	}

	// Under the hatch a plain loopback pairing is what was asked for.
	t.Setenv(appd.PlaintextEnv, "local")
	set("http://127.0.0.1:8787")
	if changed, err := upgradeStoredURLs(d.st); err != nil || len(changed) != 0 {
		t.Fatalf("with the hatch: changed %q, %v", changed, err)
	}
	if cfg, _, _ := d.st.Load(); cfg.Appd.URL != "http://127.0.0.1:8787" {
		t.Fatalf("with the hatch the pairing was rewritten: %s", cfg.Appd.URL)
	}
}
