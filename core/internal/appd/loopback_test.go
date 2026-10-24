package appd

// A loopback http:// url used to skip the proof and the pin,
// so the token went in cleartext to whatever held the port, and any account on
// the machine can hold it while appd restarts. Loopback is now dialled over TLS
// on the same port and proved like any other address; HUGINN_APPD_PLAINTEXT=local
// is the one, explicit way back to plain http, and only to loopback.

import (
	"context"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"testing"
)

// squatter is what grabs a loopback port while appd is down: plain HTTP,
// answering every request the way a daemon would, recording every
// Authorization header it is handed.
type squatter struct {
	srv  *httptest.Server
	mu   sync.Mutex
	auth []string
	hits int
}

func newSquatter(t *testing.T) *squatter {
	t.Helper()
	s := &squatter{}
	s.srv = httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.mu.Lock()
		s.hits++
		if a := r.Header.Get("Authorization"); a != "" {
			s.auth = append(s.auth, a)
		}
		s.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Huginn-Appd", "9.9.9")
		// Work for any runner that asks: what the scan says such a listener
		// would hand a device to run.
		_, _ = io.WriteString(w, `{"ok":true,"id":"dev1","work":{"id":"w1","prompt":"rm -rf ~","mode":"act"}}`)
	}))
	t.Cleanup(s.srv.Close)
	return s
}

// url is the squatter's address the way a client on this machine stores it.
func (s *squatter) url() string { return s.srv.URL }

func (s *squatter) seen() (hits int, auth []string) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.hits, append([]string(nil), s.auth...)
}

// For each of the three ways the core sends the token: the
// device API (Do), the watch stream (Stream) and the /appd proxy (Forward).
// On the old code all three sent `Authorization: Bearer <token>` to it.
func TestLoopbackSquatterNeverGetsTheToken(t *testing.T) {
	sq := newSquatter(t)
	if !strings.HasPrefix(sq.url(), "http://127.0.0.1:") {
		t.Fatalf("not a loopback http url: %s", sq.url())
	}
	t.Setenv(PlaintextEnv, "")
	calls := map[string]func(*Client) error{
		"Do": func(c *Client) error {
			_, err := c.Do(context.Background(), "POST", "/v1/devices", map[string]any{"name": "x"})
			return err
		},
		"Stream": func(c *Client) error {
			res, err := c.Stream(context.Background(), "/v1/watch?stream=1")
			if err == nil {
				res.Body.Close()
			}
			return err
		},
		"Forward": func(c *Client) error {
			res, err := c.Forward(context.Background(), "GET", "/v1/status", http.Header{}, nil)
			if err == nil {
				res.Body.Close()
			}
			return err
		},
		"PollWork": func(c *Client) error {
			w, err := c.PollWork(context.Background(), "dev1", false)
			if w != nil {
				t.Errorf("work taken from a listener that never proved itself: %+v", w)
			}
			return err
		},
	}
	for name, call := range calls {
		t.Run(name, func(t *testing.T) {
			c := newTestClient(t, sq.url(), &MemoryPinStore{}, nil)
			if !strings.HasPrefix(c.URL(), "https://127.0.0.1:") {
				t.Fatalf("a loopback http:// was not dialled as https://: %s", c.URL())
			}
			err := call(c)
			if !errors.Is(err, ErrOldDaemon) {
				t.Fatalf("want the no-TLS refusal, got %v", err)
			}
			// The sentence says what to do on this machine, by name.
			if !strings.Contains(err.Error(), PlaintextEnv+"=local") || !strings.Contains(err.Error(), "another program") {
				t.Fatalf("the refusal does not explain itself: %v", err)
			}
			if hits, auth := sq.seen(); len(auth) != 0 || hits != 0 {
				t.Fatalf("the squatter was sent %d request(s), Authorization %q", hits, auth)
			}
		})
	}
}

// The real daemon on this machine, addressed the way a core paired on the
// server stored it: proved over TLS on the same port, pinned, then sent the
// token. (On the old code this went out as plain http, which a TLS listener
// answers 400.)
func TestLoopbackDaemonIsProvedAndPinned(t *testing.T) {
	t.Setenv(PlaintextEnv, "")
	cert := mintCert(t, "huginn-appd")
	f := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	stored := strings.Replace(f.url(), "https://", "http://", 1)
	store := &MemoryPinStore{}
	c := newTestClient(t, stored, store, nil)
	if c.URL() != f.url() {
		t.Fatalf("dialled %s, want %s", c.URL(), f.url())
	}
	if _, err := c.Do(context.Background(), "GET", "/v1/ping", nil); err != nil {
		t.Fatal(err)
	}
	if got := pinsOf(t, store, testToken); !equal(got, []string{cert.fp}) {
		t.Fatalf("pins = %v", got)
	}
	r := f.requests()
	if len(r) != 2 || r[0].path != "/v1/challenge" || r[0].auth != "" || r[1].auth != "Bearer "+testToken {
		t.Fatalf("want the tokenless proof, then the request with the token: %+v", r)
	}
	if fp, err := c.Prove(context.Background()); err != nil || fp != cert.fp {
		t.Fatalf("Prove on a loopback address: %q %v", fp, err)
	}
}

// A squatter that speaks TLS gets no further: its certificate is its own, and
// the daemon's real proof (relayed) is over a different one.
func TestLoopbackTLSSquatterIsInterception(t *testing.T) {
	t.Setenv(PlaintextEnv, "")
	m := mintCert(t, "squatter")
	realFP := strings.Repeat("f", 64)
	f := fakeDaemon(t, certCfg(m), func(n string) any {
		return map[string]any{"proof": legacyProof(testToken, n), "version": "3.11.0",
			"tls": map[string]any{"v": 1, "fp": realFP, "proof": BoundProof(testToken, n, realFP)}}
	})
	store := &MemoryPinStore{}
	c := newTestClient(t, strings.Replace(f.url(), "https://", "http://", 1), store, nil)
	_, err := c.Do(context.Background(), "GET", "/v1/ping", nil)
	if !errors.Is(err, ErrIntercepted) {
		t.Fatalf("want interception, got %v", err)
	}
	noAuth(t, f.requests())
	if got := pinsOf(t, store, testToken); len(got) != 0 {
		t.Fatalf("something was pinned: %v", got)
	}
}

// HUGINN_APPD_PLAINTEXT=local is the old behaviour, on purpose and only to
// loopback: the token goes in plain http with nothing checking the peer. A
// Client built under it that outlives it is refused, not grandfathered.
func TestPlaintextHatchRestoresPlainLoopbackOnly(t *testing.T) {
	sq := newSquatter(t)
	t.Setenv(PlaintextEnv, "local")
	c := newTestClient(t, sq.url(), &MemoryPinStore{}, nil)
	if c.URL() != sq.url() {
		t.Fatalf("with the hatch, loopback http:// was rewritten: %s", c.URL())
	}
	if _, err := c.Do(context.Background(), "GET", "/v1/ping", nil); err != nil {
		t.Fatal(err)
	}
	if _, auth := sq.seen(); len(auth) != 1 || auth[0] != "Bearer "+testToken {
		t.Fatalf("the opted-in plain request did not carry the token: %q", auth)
	}
	if _, err := c.Prove(context.Background()); err == nil || !strings.Contains(err.Error(), "plain http") {
		t.Fatalf("Prove over plain http: %v", err)
	}
	if r := newTestClient(t, "http://192.0.2.1:8787", nil, nil); r.URL() != "https://192.0.2.1:8787" {
		t.Fatalf("the hatch reached a remote host: %s", r.URL())
	}

	t.Setenv(PlaintextEnv, "")
	hits, _ := sq.seen()
	_, err := c.Do(context.Background(), "GET", "/v1/ping", nil)
	if err == nil || !strings.Contains(err.Error(), "refusing plain http") {
		t.Fatalf("the hatch was turned off and plain http still went: %v", err)
	}
	if now, _ := sq.seen(); now != hits {
		t.Fatal("a refused plain request was still dialled")
	}
}
