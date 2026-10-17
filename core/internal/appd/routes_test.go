package appd

import (
	"crypto/tls"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"testing"
)

// The route book (routes.go): one daemon, several addresses. Each test is one
// rule from the top of routes.go.

type routeLog struct {
	mu  sync.Mutex
	evs []RouteEvent
}

func (l *routeLog) add(e RouteEvent) { l.mu.Lock(); l.evs = append(l.evs, e); l.mu.Unlock() }
func (l *routeLog) switches() []RouteEvent {
	l.mu.Lock()
	defer l.mu.Unlock()
	var out []RouteEvent
	for _, e := range l.evs {
		if e.Health == nil {
			out = append(out, e)
		}
	}
	return out
}

func deadURL(t *testing.T) string { return fmt.Sprintf("https://127.0.0.1:%d", freePort(t)) }

func activeURL(c *Client) string {
	for _, r := range c.Routes() {
		if r.Active {
			return r.URL
		}
	}
	return ""
}

// A refused connection is tried on the next address, which becomes active.
func TestRouteSwitchesOffADeadAddress(t *testing.T) {
	cert := mintCert(t, "huginn-appd")
	live := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	var log routeLog
	dead := deadURL(t)
	c := newTestClient(t, dead, nil, func(cfg *Config) { cfg.Routes = []string{live.url()}; cfg.OnRoute = log.add })
	if _, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil {
		t.Fatalf("through the second address: %v", err)
	}
	if got := activeURL(c); got != live.url() {
		t.Fatalf("active %s, want %s", got, live.url())
	}
	sw := log.switches()
	if len(sw) != 1 || sw[0].From != dead || sw[0].To != live.url() {
		t.Fatalf("switch events %+v", sw)
	}
	r := c.Routes()
	if r[0].LastFailAt.IsZero() || r[1].LastSeenAt.IsZero() {
		t.Fatalf("health %+v", r)
	}
}

// Away from home the LAN address can be somebody else's machine: its
// certificate is not the proved one. With another address to go to, that is a
// switch, and the impostor never sees the token. With ONE address it stays the
// interception error it always was.
func TestRouteImpostorAddressIsSkippedWithoutTheToken(t *testing.T) {
	cert, other := mintCert(t, "huginn-appd"), mintCert(t, "cafe-printer")
	live := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	// It answers the challenge like a daemon would, but cannot prove the token.
	imp := fakeDaemon(t, certCfg(other), func(n string) any {
		return map[string]any{"version": "3.11.0", "tls": map[string]any{"v": 1, "fp": other.fp, "proof": BoundProof("not-the-token", n, other.fp)}}
	})
	pins := &MemoryPinStore{}
	if _, err := newTestClient(t, live.url(), pins, nil).Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil {
		t.Fatal(err) // proves and pins the real daemon
	}
	c := newTestClient(t, imp.url(), pins, func(cfg *Config) { cfg.Routes = []string{live.url()} })
	if _, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil {
		t.Fatalf("with the real address second: %v", err)
	}
	if activeURL(c) != live.url() {
		t.Fatalf("active %s", activeURL(c))
	}
	noAuth(t, imp.requests())
	only := newTestClient(t, imp.url(), pins, nil)
	if _, err := only.Do(t.Context(), http.MethodGet, "/v1/status", nil); !errors.Is(err, ErrIntercepted) {
		t.Fatalf("one address, wrong certificate: %v, want an interception", err)
	}
	noAuth(t, imp.requests())
}

// An answer from appd, any status, proves the address works: no switch.
func TestRouteStaysOnAnAnswer(t *testing.T) {
	cert := mintCert(t, "huginn-appd")
	first := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert), func(f *fake) {
		f.other = func(w http.ResponseWriter, r *http.Request) { w.WriteHeader(500); io.WriteString(w, `{"error":"x"}`) }
	})
	second := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	c := newTestClient(t, first.url(), nil, func(cfg *Config) { cfg.Routes = []string{second.url()} })
	_, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil)
	var se *StatusError
	if !errors.As(err, &se) || se.Status != 500 || activeURL(c) != first.url() {
		t.Fatalf("err %v, active %s", err, activeURL(c))
	}
	if n := len(second.requests()); n != 0 {
		t.Fatalf("the second address got %d requests", n)
	}
}

// A connection that breaks AFTER the request left may have done it (a POST):
// it is never sent again to another address.
func TestRouteNeverResendsADeliveredRequest(t *testing.T) {
	cert := mintCert(t, "huginn-appd")
	first := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert), func(f *fake) {
		f.other = func(w http.ResponseWriter, r *http.Request) {
			io.Copy(io.Discard, r.Body)
			conn, _, _ := w.(http.Hijacker).Hijack()
			conn.Close()
		}
	})
	second := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	c := newTestClient(t, first.url(), nil, func(cfg *Config) { cfg.Routes = []string{second.url()} })
	if _, err := c.Do(t.Context(), http.MethodPost, "/v1/chats/c/messages", map[string]string{"text": "once"}); err == nil {
		t.Fatal("a broken answer read as success")
	}
	for _, r := range second.requests() {
		if r.path != "/v1/challenge" {
			t.Fatalf("the POST was sent again to the second address (%s)", r.path)
		}
	}
	if activeURL(c) != first.url() {
		t.Fatalf("switched to %s on a delivered request", activeURL(c))
	}
}

// Fixed (a pinned route, or auto-switch off) never moves.
func TestRouteFixedNeverSwitches(t *testing.T) {
	cert := mintCert(t, "huginn-appd")
	live := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	dead := deadURL(t)
	c := newTestClient(t, dead, nil, func(cfg *Config) { cfg.Routes = []string{live.url()}; cfg.Fixed = true })
	if _, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil); err == nil || activeURL(c) != dead {
		t.Fatalf("err %v, active %s", err, activeURL(c))
	}
}

// A body is streamed: another address is tried only while none of it was read.
func TestRouteForwardRetriesAnUnsentBody(t *testing.T) {
	cert := mintCert(t, "huginn-appd")
	var got string
	live := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert), func(f *fake) {
		f.other = func(w http.ResponseWriter, r *http.Request) {
			b, _ := io.ReadAll(r.Body)
			got = string(b)
			io.WriteString(w, `{}`)
		}
	})
	c := newTestClient(t, deadURL(t), nil, func(cfg *Config) { cfg.Routes = []string{live.url()} })
	res, err := c.Forward(t.Context(), http.MethodPost, "/v1/uploads?name=a.txt", http.Header{"Content-Type": {"text/plain"}}, strings.NewReader("hello"))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if got != "hello" {
		t.Fatalf("the second address got body %q", got)
	}
}

// Back on the preferred address once it answers again (a pinned handshake,
// nothing sent).
func TestRouteReturnsToThePreferredAddress(t *testing.T) {
	cert := mintCert(t, "huginn-appd")
	port := freePort(t)
	pref := fmt.Sprintf("https://127.0.0.1:%d", port)
	away := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	c := newTestClient(t, pref, nil, func(cfg *Config) { cfg.Routes = []string{away.url()} })
	if _, err := c.Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil || activeURL(c) != away.url() {
		t.Fatalf("err %v, active %s", err, activeURL(c))
	}
	if c.TryPreferred(t.Context()) {
		t.Fatal("moved back to an address that is still down")
	}
	back := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert), func(f *fake) {
		ln, err := net.Listen("tcp", fmt.Sprintf("127.0.0.1:%d", port))
		if err != nil {
			t.Skip("the port was taken in between")
		}
		f.srv.Listener = tls.NewListener(ln, certCfg(cert))
	})
	if !c.TryPreferred(t.Context()) || activeURL(c) != pref {
		t.Fatalf("did not move back (active %s)", activeURL(c))
	}
	if n := len(back.requests()); n != 0 {
		t.Fatalf("the check sent %d requests", n)
	}
}
