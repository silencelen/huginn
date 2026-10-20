package appd

import (
	"net/http"
	"testing"
)

// Reroute is a route-book edit applied to a client in use (the core's runner,
// its watch stream and its proxy all hold one): the new book is used from the
// next request, on its first address, and what is known about an address
// that stays in it is kept.
func TestRerouteAppliesTheBookInPlace(t *testing.T) {
	cert := mintCert(t, "huginn-appd")
	live := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	dead := deadURL(t)
	c := newTestClient(t, dead, nil, func(cfg *Config) { cfg.Routes = []string{live.url()} })
	if _, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil || activeURL(c) != live.url() {
		t.Fatalf("err %v, active %s", err, activeURL(c))
	}

	// The live address first: used at once, its health kept, and so is the
	// dead one's.
	if err := c.Reroute(live.url(), []string{dead}, false); err != nil {
		t.Fatal(err)
	}
	r := c.Routes()
	if len(r) != 2 || r[0].URL != live.url() || !r[0].Active || r[0].LastSeenAt.IsZero() || r[1].LastFailAt.IsZero() {
		t.Fatalf("after the edit: %+v", r)
	}

	// Pinned to the dead address: no switching, the request fails there.
	if err := c.Reroute(dead, []string{live.url()}, true); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil); err == nil || activeURL(c) != dead {
		t.Fatalf("pinned to a dead address: err %v, active %s", err, activeURL(c))
	}

	// A book with an address the client refuses changes nothing.
	if err := c.Reroute("ftp://192.0.2.1", nil, false); err == nil {
		t.Fatal("an ftp address was taken")
	}
	if activeURL(c) != dead || len(c.Routes()) != 2 {
		t.Fatalf("a refused edit changed the book: %+v", c.Routes())
	}

	// Unpinned again, the live address alone: the next request goes there.
	if err := c.Reroute(live.url(), nil, false); err != nil {
		t.Fatal(err)
	}
	if _, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil || len(c.Routes()) != 1 {
		t.Fatalf("err %v, routes %+v", err, c.Routes())
	}
}
