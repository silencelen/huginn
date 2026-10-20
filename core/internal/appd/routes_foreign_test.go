package appd

import (
	"crypto/tls"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"testing"
	"time"
)

// Away from home the LAN address can be another machine serving HTTPS on the
// same ip:port: a NAS, a router's page, some other web service. Its
// certificate is not the pinned one, so nothing was sent to it, and the
// request belongs on the next address (the top of routes.go). Whatever that
// machine then answers to the proof (a 404, a web page, nothing at all) or a
// handshake this client will not finish with it is still "never reached appd".
func TestRouteForeignHTTPSAddressIsSkippedWhateverItAnswers(t *testing.T) {
	cert, other := mintCert(t, "huginn-appd"), mintCert(t, "nas")
	live := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert))
	pins := &MemoryPinStore{}
	if _, err := newTestClient(t, live.url(), pins, nil).Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil {
		t.Fatal(err) // proves and pins the real daemon
	}

	var mu sync.Mutex
	var auth []string
	web := func(status int, ctype, body string) string {
		srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			mu.Lock()
			if a := r.Header.Get("Authorization"); a != "" {
				auth = append(auth, a)
			}
			mu.Unlock()
			w.Header().Set("Content-Type", ctype)
			w.WriteHeader(status)
			io.WriteString(w, body)
		}))
		srv.Listener = tls.NewListener(srv.Listener, certCfg(other))
		srv.Start()
		t.Cleanup(srv.Close)
		return "https://" + srv.Listener.Addr().String()
	}
	// Finishes TLS, then never answers.
	mute := func() string {
		ln, err := tls.Listen("tcp", "127.0.0.1:0", certCfg(other))
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(func() { ln.Close() })
		go func() {
			for {
				c, err := ln.Accept()
				if err != nil {
					return
				}
				go func() { defer c.Close(); io.Copy(io.Discard, c) }()
			}
		}()
		return "https://" + ln.Addr().String()
	}

	for _, c := range []struct{ name, url string }{
		{"a 404", web(404, "text/plain", "404 page not found")},
		{"a web page", web(200, "text/html", "<html><body>NAS login</body></html>")},
		{"a login redirect", web(302, "text/html", "")},
		{"nothing after the handshake", mute()},
		{"TLS 1.1 only", "https://" + newRecordingTLSServer(t, other, tls.VersionTLS11).addr},
	} {
		cl := newTestClient(t, c.url, pins, func(cfg *Config) { cfg.Routes = []string{live.url()}; cfg.ProbeTimeout = 500 * time.Millisecond })
		if _, err := cl.Do(t.Context(), http.MethodGet, "/v1/status", nil); err != nil {
			t.Errorf("%s on the preferred address: %v (active %s)", c.name, err, activeURL(cl))
			continue
		}
		if activeURL(cl) != live.url() {
			t.Errorf("%s on the preferred address: still active there", c.name)
		}
	}
	// The long-lived requests go the same way.
	foreign := web(404, "text/plain", "404 page not found")
	cl := newTestClient(t, foreign, pins, func(cfg *Config) { cfg.Routes = []string{live.url()} })
	res, err := cl.Stream(t.Context(), "/v1/watch?stream=1")
	if err != nil {
		t.Fatalf("Stream with a foreign preferred address: %v", err)
	}
	res.Body.Close()
	cl = newTestClient(t, foreign, pins, func(cfg *Config) { cfg.Routes = []string{live.url()} })
	res, err = cl.Forward(t.Context(), http.MethodGet, "/v1/status", http.Header{}, nil)
	if err != nil {
		t.Fatalf("Forward with a foreign preferred address: %v", err)
	}
	res.Body.Close()
	mu.Lock()
	defer mu.Unlock()
	if len(auth) != 0 {
		t.Fatalf("the foreign server was handed %q", auth)
	}
}
