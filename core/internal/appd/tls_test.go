package appd

// The client's half of the TLS contract against FAKE daemons, each one a way
// the token could reach somebody who is not the daemon: a proof over the wrong
// certificate accepted, the legacy proof accepted over TLS, a hang in the
// handshake, a downgrade to plain HTTP, or token bytes written to a socket
// before the certificate was judged. The real-appd half is e2e_test.go.

import (
	"bytes"
	"context"
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/hmac"
	"crypto/rand"
	"crypto/sha256"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/hex"
	"encoding/json"
	"errors"
	"io"
	"math/big"
	"net"
	"net/http"
	"net/http/httptest"
	"strings"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

const testToken = "7e57700000000000000000000000000000000000000000000000000000c0ffee"

type testCert struct {
	cert tls.Certificate
	fp   string
}

// mintCert is a self-signed P-256 pair of our own, like appd mints.
func mintCert(t *testing.T, cn string) testCert {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{
		SerialNumber: big.NewInt(time.Now().UnixNano()),
		Subject:      pkix.Name{CommonName: cn},
		NotBefore:    time.Now().Add(-time.Hour),
		NotAfter:     time.Now().Add(24 * time.Hour),
		KeyUsage:     x509.KeyUsageDigitalSignature,
		ExtKeyUsage:  []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth},
	}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	return testCert{cert: tls.Certificate{Certificate: [][]byte{der}, PrivateKey: key}, fp: Fingerprint(der)}
}

func legacyProof(tok, nonce string) string {
	m := hmac.New(sha256.New, []byte(tok))
	m.Write([]byte(nonce))
	return hex.EncodeToString(m.Sum(nil))
}

type seenReq struct {
	path string
	auth string
}

type fake struct {
	srv   *httptest.Server
	other http.HandlerFunc // answers everything but the challenge, when set
	mu    sync.Mutex
	seen  []seenReq
}

func (f *fake) requests() []seenReq {
	f.mu.Lock()
	defer f.mu.Unlock()
	return append([]seenReq(nil), f.seen...)
}

func (f *fake) url() string { return "https://" + f.srv.Listener.Addr().String() }

// fakeDaemon answers /v1/challenge with answer(nonce) and everything else with
// {"ok":true}, recording every request that reaches it.
func fakeDaemon(t *testing.T, cfg *tls.Config, answer func(nonce string) any, before ...func(*fake)) *fake {
	t.Helper()
	f := &fake{}
	f.srv = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.seen = append(f.seen, seenReq{path: r.URL.Path, auth: r.Header.Get("Authorization")})
		f.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/v1/challenge" {
			_ = json.NewEncoder(w).Encode(answer(r.URL.Query().Get("nonce")))
			return
		}
		if f.other != nil {
			f.other(w, r)
			return
		}
		_, _ = io.WriteString(w, `{"ok":true}`)
	}))
	// Our own TLS listener rather than StartTLS, which slips httptest's own
	// certificate into Certificates and so shadows GetCertificate.
	f.srv.Listener = tls.NewListener(f.srv.Listener, cfg)
	for _, b := range before {
		b(f)
	}
	f.srv.Start()
	t.Cleanup(f.srv.Close)
	return f
}

func certCfg(c testCert) *tls.Config { return &tls.Config{Certificates: []tls.Certificate{c.cert}} }

// goodAnswer is what a real daemon holding tok behind cert c says.
func goodAnswer(tok string, c testCert) func(string) any {
	return func(n string) any {
		return map[string]any{"proof": legacyProof(tok, n), "version": "3.11.0",
			"tls": map[string]any{"v": 1, "fp": c.fp, "proof": BoundProof(tok, n, c.fp)}}
	}
}

func newTestClient(t *testing.T, u string, pins PinStore, mod func(*Config)) *Client {
	t.Helper()
	t.Setenv(TokenEnv, "") // a developer's shell must not change what is tested
	cfg := Config{URL: u, Tokens: StaticToken(testToken), Pins: pins, Logf: t.Logf}
	if mod != nil {
		mod(&cfg)
	}
	c, err := New(cfg)
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(c.Close)
	return c
}

func pinsOf(t *testing.T, s PinStore, tok string) []string {
	t.Helper()
	set, err := s.LoadPins()
	if err != nil {
		t.Fatal(err)
	}
	return set.For(tok)
}

func noAuth(t *testing.T, reqs []seenReq) {
	t.Helper()
	for _, r := range reqs {
		if r.auth != "" {
			t.Fatalf("an Authorization header reached the impostor on %s", r.path)
		}
	}
}

func TestCorrectProofPinsAndCarriesToken(t *testing.T) {
	c := mintCert(t, "good")
	f := fakeDaemon(t, certCfg(c), goodAnswer(testToken, c))
	store := &MemoryPinStore{}
	cl := newTestClient(t, f.url(), store, nil)
	if _, err := cl.Do(context.Background(), "GET", "/v1/ping", nil); err != nil {
		t.Fatal(err)
	}
	if got := pinsOf(t, store, testToken); !equal(got, []string{c.fp}) {
		t.Fatalf("pins = %v", got)
	}
	r := f.requests()
	if len(r) != 2 || r[0].path != "/v1/challenge" || r[0].auth != "" {
		t.Fatalf("the probe must come first and carry no token: %+v", r)
	}
	if r[1].auth != "Bearer "+testToken {
		t.Fatalf("the pinned request did not carry the token: %+v", r[1])
	}
	// Pinned now: the next request does not probe again.
	if _, err := cl.Do(context.Background(), "GET", "/v1/ping", nil); err != nil {
		t.Fatal(err)
	}
	if n := len(f.requests()); n != 3 {
		t.Fatalf("a pinned client probed again (%d requests)", n)
	}
}

func TestMITMProofOverDifferentCertRefused(t *testing.T) {
	m := mintCert(t, "mitm")
	realFP := strings.Repeat("f", 64)
	// The relay case: a CORRECT proof, but over the real daemon's fingerprint,
	// with the real fp reported beside it. Rule 1: hash what WE saw.
	f := fakeDaemon(t, certCfg(m), func(n string) any {
		return map[string]any{"proof": legacyProof(testToken, n), "version": "3.11.0",
			"tls": map[string]any{"v": 1, "fp": realFP, "proof": BoundProof(testToken, n, realFP)}}
	})
	store := &MemoryPinStore{}
	cl := newTestClient(t, f.url(), store, nil)
	_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
	if !errors.Is(err, ErrIntercepted) || !strings.Contains(err.Error(), InterceptedText) {
		t.Fatalf("want interception, got %v", err)
	}
	if strings.Contains(err.Error(), tokenHint) {
		t.Fatal("the wrong-token hint fired for a peer that reported another fingerprint")
	}
	if len(f.requests()) < 1 {
		t.Fatal("the challenge was never asked")
	}
	noAuth(t, f.requests())
	if got := pinsOf(t, store, testToken); len(got) != 0 {
		t.Fatalf("something was pinned: %v", got)
	}
}

func TestWrongTokenGetsHint(t *testing.T) {
	c := mintCert(t, "other-token")
	// A real daemon with a DIFFERENT token: it reports our fp but its proof
	// cannot match. Still refused, with the hint.
	f := fakeDaemon(t, certCfg(c), goodAnswer(strings.Repeat("9", 64), c))
	cl := newTestClient(t, f.url(), nil, nil)
	_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
	if !errors.Is(err, ErrIntercepted) || !strings.Contains(err.Error(), tokenHint) {
		t.Fatalf("got %v", err)
	}
	noAuth(t, f.requests())
}

func TestLegacyOnlyAnswerIsNotAProof(t *testing.T) {
	c := mintCert(t, "legacy")
	for name, answer := range map[string]func(string) any{
		"no tls":      func(n string) any { return map[string]any{"proof": legacyProof(testToken, n), "version": "3.10.3"} },
		"tls null":    func(n string) any { return map[string]any{"proof": legacyProof(testToken, n), "tls": nil} },
		"tls boolean": func(n string) any { return map[string]any{"proof": legacyProof(testToken, n), "tls": true} },
	} {
		t.Run(name, func(t *testing.T) {
			f := fakeDaemon(t, certCfg(c), answer)
			cl := newTestClient(t, f.url(), nil, nil)
			_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
			if !errors.Is(err, ErrOldDaemon) || !strings.Contains(err.Error(), "older than 3.11") {
				t.Fatalf("want old-daemon, got %v", err)
			}
			noAuth(t, f.requests())
			if n := len(f.requests()); n != 1 {
				t.Fatalf("%d requests reached it; only the probe may", n)
			}
		})
	}
}

func TestUnknownProofVersionRefused(t *testing.T) {
	c := mintCert(t, "v2")
	f := fakeDaemon(t, certCfg(c), func(n string) any {
		return map[string]any{"tls": map[string]any{"v": 2, "fp": c.fp, "proof": BoundProof(testToken, n, c.fp)}}
	})
	cl := newTestClient(t, f.url(), nil, nil)
	if _, err := cl.Do(context.Background(), "GET", "/v1/ping", nil); !errors.Is(err, ErrProofVersion) {
		t.Fatalf("got %v", err)
	}
	noAuth(t, f.requests())
}

func TestPlainHTTPOldDaemonNeverDowngraded(t *testing.T) {
	var mu sync.Mutex
	var seen []string
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		seen = append(seen, r.URL.Path+" "+r.Header.Get("Authorization"))
		mu.Unlock()
		_, _ = io.WriteString(w, "{}")
	}))
	t.Cleanup(srv.Close)
	httpsURL := strings.Replace(srv.URL, "http://", "https://", 1)

	// From nothing (the probe meets it): "old daemon". Already pinned (the
	// pinned dial meets it, or a re-proof does): the daemon at this token has
	// proved itself over TLS, so plain HTTP at its address is somebody else,
	// an interception (security review, 2026-10-18; the old code said "old
	// daemon, update it" here too). Neither tries cleartext.
	for _, pinned := range []bool{false, true} {
		store := &MemoryPinStore{}
		if pinned {
			_ = store.SavePins(PinSet{}.With(testToken, strings.Repeat("a", 64)))
		}
		cl := newTestClient(t, httpsURL, store, nil)
		_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
		switch {
		case !pinned && !errors.Is(err, ErrOldDaemon):
			t.Fatalf("first contact: want old-daemon, got %v", err)
		case pinned && (!errors.Is(err, ErrIntercepted) || errors.Is(err, ErrOldDaemon) || strings.Contains(err.Error(), "3.11")):
			t.Fatalf("pinned: want interception and no word of an old daemon, got %v", err)
		}
		if pinned {
			// The re-proof path: Prove with the pin in hand meets it in the probe.
			if _, err := cl.Prove(context.Background()); !errors.Is(err, ErrIntercepted) || errors.Is(err, ErrOldDaemon) {
				t.Fatalf("pinned, re-proving: want interception, got %v", err)
			}
		}
	}
	mu.Lock()
	defer mu.Unlock()
	if len(seen) != 0 {
		t.Fatalf("an HTTP request reached the plain server: %v", seen)
	}
}

// The other half of rule 8, under a pin: a TLS answer with no `tls` proof is
// not the daemon that proved itself for this token over TLS either.
func TestPinnedLegacyAnswerIsInterception(t *testing.T) {
	c := mintCert(t, "legacy-under-pin")
	f := fakeDaemon(t, certCfg(c), func(n string) any {
		return map[string]any{"proof": legacyProof(testToken, n), "version": "3.10.3"}
	})
	store := &MemoryPinStore{}
	_ = store.SavePins(PinSet{}.With(testToken, strings.Repeat("a", 64)))
	cl := newTestClient(t, f.url(), store, nil)
	_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
	if !errors.Is(err, ErrIntercepted) || errors.Is(err, ErrOldDaemon) {
		t.Fatalf("want interception, got %v", err)
	}
	noAuth(t, f.requests())
}

// silentListener accepts TCP and then says nothing at all.
func silentListener(t *testing.T) string {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	var held []net.Conn
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			mu.Lock()
			held = append(held, c)
			mu.Unlock()
		}
	}()
	t.Cleanup(func() {
		ln.Close()
		mu.Lock()
		for _, c := range held {
			c.Close()
		}
		mu.Unlock()
	})
	return "https://" + ln.Addr().String()
}

func TestHandshakeThatNeverFinishesFailsInsteadOfHanging(t *testing.T) {
	u := silentListener(t)
	t.Run("pinned channel", func(t *testing.T) {
		// Already pinned, so Do goes straight to the pinned dial — the path the
		// serve loop sits on — with a background context and no deadline.
		store := &MemoryPinStore{}
		_ = store.SavePins(PinSet{}.With(testToken, strings.Repeat("a", 64)))
		cl := newTestClient(t, u, store, func(c *Config) {
			c.HandshakeTimeout = 300 * time.Millisecond
			c.RequestTimeout = time.Hour
		})
		t0 := time.Now()
		_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
		if err == nil || !strings.Contains(err.Error(), "did not finish within 300ms") {
			t.Fatalf("got %v", err)
		}
		if d := time.Since(t0); d > 5*time.Second {
			t.Fatalf("not bounded by the handshake deadline: %v", d)
		}
	})
	t.Run("probe", func(t *testing.T) {
		cl := newTestClient(t, u, nil, func(c *Config) {
			c.ProbeTimeout = 300 * time.Millisecond
			c.RequestTimeout = time.Hour
		})
		t0 := time.Now()
		_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
		if err == nil || !strings.Contains(err.Error(), "did not finish within 300ms") {
			t.Fatalf("got %v", err)
		}
		if d := time.Since(t0); d > 5*time.Second {
			t.Fatalf("not bounded by the probe deadline: %v", d)
		}
	})
}

// recordingTLSServer is a raw TLS server that records every DECRYPTED byte a
// client manages to send it. It answers a minimal HTTP response so a
// successful client returns.
type recordingTLSServer struct {
	addr string
	mu   sync.Mutex
	got  bytes.Buffer
}

func (r *recordingTLSServer) plaintext() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.got.String()
}

func newRecordingTLSServer(t *testing.T, c testCert, maxVersion uint16) *recordingTLSServer {
	t.Helper()
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	r := &recordingTLSServer{addr: ln.Addr().String()}
	cfg := &tls.Config{Certificates: []tls.Certificate{c.cert}, MaxVersion: maxVersion}
	go func() {
		for {
			raw, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				tc := tls.Server(raw, cfg)
				defer tc.Close()
				_ = tc.SetDeadline(time.Now().Add(5 * time.Second))
				buf := make([]byte, 4096)
				for {
					n, err := tc.Read(buf)
					if n > 0 {
						r.mu.Lock()
						r.got.Write(buf[:n])
						r.mu.Unlock()
						if bytes.Contains(buf[:n], []byte("\r\n\r\n")) {
							_, _ = io.WriteString(tc, "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}")
							return
						}
					}
					if err != nil {
						return
					}
				}
			}()
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return r
}

// THE PROPERTY §9 item 4 names: the leaf is judged before any request byte is
// written. A server that would happily decrypt and record whatever arrives,
// presenting a certificate that is NOT pinned, must record nothing — on TLS
// 1.2 and 1.3 — while the same harness with the pin matching records the whole
// request including the token (so the empty result is not a broken harness).
func TestLeafJudgedBeforeAnyRequestByte(t *testing.T) {
	for _, v := range []struct {
		name string
		max  uint16
	}{{"TLS1.2", tls.VersionTLS12}, {"TLS1.3", tls.VersionTLS13}} {
		t.Run(v.name, func(t *testing.T) {
			c := mintCert(t, "recorder")
			srv := newRecordingTLSServer(t, c, v.max)
			body := map[string]string{"secret": "body-" + testToken}

			// Refused: pinned to something else. The re-proof meets the same
			// recorder, which cannot answer a challenge, so it fails too.
			store := &MemoryPinStore{}
			_ = store.SavePins(PinSet{}.With(testToken, strings.Repeat("a", 64)))
			cl := newTestClient(t, "https://"+srv.addr, store, func(c *Config) { c.ProbeTimeout = 2 * time.Second })
			_, err := cl.Do(context.Background(), "POST", "/v1/devices", body)
			if err == nil {
				t.Fatal("an unpinned peer was accepted")
			}
			got := srv.plaintext()
			if strings.Contains(got, testToken) || strings.Contains(strings.ToLower(got), "authorization") || strings.Contains(got, "/v1/devices") {
				t.Fatalf("request bytes reached an unpinned peer:\n%s", got)
			}

			// Positive control: pin the recorder's own cert and the same request
			// is decrypted in full, token and all.
			_ = store.SavePins(PinSet{}.With(testToken, c.fp))
			if _, err := cl.Do(context.Background(), "POST", "/v1/devices", body); err != nil {
				t.Fatalf("control request failed: %v", err)
			}
			got = srv.plaintext()
			if !strings.Contains(got, "Authorization: Bearer "+testToken) || !strings.Contains(got, "POST /v1/devices") {
				t.Fatalf("the harness did not see the pinned request, so the refusal above proves nothing:\n%s", got)
			}
		})
	}
}

// Rule 5, both halves: a rotated certificate that proves is pinned (newest
// first, old kept) and the request retried; a certificate that changes AGAIN
// between the proof and the retry is interception, and gets no token.
func TestRotationReprovedOnceAndFlipFlopIsInterception(t *testing.T) {
	t.Run("rotation", func(t *testing.T) {
		oldC, newC := mintCert(t, "old"), mintCert(t, "new")
		f := fakeDaemon(t, certCfg(newC), goodAnswer(testToken, newC))
		store := &MemoryPinStore{}
		_ = store.SavePins(PinSet{}.With(testToken, oldC.fp))
		cl := newTestClient(t, f.url(), store, nil)
		if _, err := cl.Do(context.Background(), "GET", "/v1/ping", nil); err != nil {
			t.Fatal(err)
		}
		if got := pinsOf(t, store, testToken); !equal(got, []string{newC.fp, oldC.fp}) {
			t.Fatalf("pins = %v", got)
		}
		r := f.requests()
		if len(r) != 2 || r[0].path != "/v1/challenge" || r[0].auth != "" || r[1].auth == "" {
			t.Fatalf("want one tokenless re-proof then the request: %+v", r)
		}
	})
	t.Run("flip-flop", func(t *testing.T) {
		a, b := mintCert(t, "A"), mintCert(t, "B")
		// Connection 1 (pinned attempt) and 3 (retry) see B; connection 2 (the
		// re-proof) sees A, which proves. B never proves.
		var n atomic.Int32
		cfg := &tls.Config{GetCertificate: func(*tls.ClientHelloInfo) (*tls.Certificate, error) {
			if n.Add(1)%2 == 0 {
				return &a.cert, nil
			}
			return &b.cert, nil
		}}
		f := fakeDaemon(t, cfg, goodAnswer(testToken, a))
		store := &MemoryPinStore{}
		_ = store.SavePins(PinSet{}.With(testToken, strings.Repeat("c", 64)))
		cl := newTestClient(t, f.url(), store, nil)
		_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
		if !errors.Is(err, ErrIntercepted) {
			t.Fatalf("want interception, got %v", err)
		}
		if got := n.Load(); got != 3 {
			t.Fatalf("want exactly one re-proof and one retry (3 handshakes), got %d", got)
		}
		r := f.requests()
		if len(r) != 1 || r[0].path != "/v1/challenge" {
			t.Fatalf("only the probe may have reached it: %+v", r)
		}
		noAuth(t, r)
	})
}

// No keep-alive reuse across a pin change: after a token change the old
// connection (judged under the old token's pins) must not carry the new token.
func TestNoConnectionReuseAcrossPinChange(t *testing.T) {
	c := mintCert(t, "reuse")
	var conns atomic.Int32
	cfg := certCfg(c)
	f := fakeDaemon(t, cfg, func(n string) any { return nil }, func(f *fake) {
		f.srv.Config.ConnState = func(_ net.Conn, s http.ConnState) {
			if s == http.StateNew {
				conns.Add(1)
			}
		}
	})
	store := &MemoryPinStore{}
	_ = store.SavePins(PinSet{}.With(testToken, c.fp))
	cl := newTestClient(t, f.url(), store, nil)
	for i := 0; i < 3; i++ {
		if _, err := cl.Do(context.Background(), "GET", "/v1/ping", nil); err != nil {
			t.Fatal(err)
		}
	}
	if got := conns.Load(); got != 1 {
		t.Fatalf("same pins should reuse one connection, got %d", got)
	}
	// Same cert, pinned under a second token (as if proved); the request under
	// that token must open a fresh connection.
	t2 := strings.Repeat("2", 64)
	set, _ := store.LoadPins()
	_ = store.SavePins(set.With(t2, c.fp))
	cl.tokens = StaticToken(t2)
	if _, err := cl.Do(context.Background(), "GET", "/v1/ping", nil); err != nil {
		t.Fatal(err)
	}
	if got := conns.Load(); got != 2 {
		t.Fatalf("a pin-set change reused a pooled connection (%d connections)", got)
	}
}

func TestPathCannotLeaveTheOrigin(t *testing.T) {
	cl := newTestClient(t, "https://127.0.0.1:1", nil, nil)
	for _, p := range []string{"//evil.example/x", "https://evil.example/x", "v1/ping", "/v1/x\r\nHost: evil", `/\evil`} {
		if _, err := cl.Do(context.Background(), "GET", p, nil); err == nil || !strings.Contains(err.Error(), "refusing request path") {
			t.Errorf("%q: got %v", p, err)
		}
	}
}

func TestNoTokenIsAuthAndSendsNothing(t *testing.T) {
	c := mintCert(t, "notoken")
	f := fakeDaemon(t, certCfg(c), goodAnswer(testToken, c))
	cl := newTestClient(t, f.url(), nil, func(cfg *Config) { cfg.Tokens = FileTokenSource{Path: t.TempDir() + "/absent"} })
	_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
	if Classify(err) != KindAuth {
		t.Fatalf("got %v (%v)", err, Classify(err))
	}
	if n := len(f.requests()); n != 0 {
		t.Fatalf("a client with no token still dialled (%d requests)", n)
	}
}

func TestStatusErrorsClassified(t *testing.T) {
	c := mintCert(t, "status")
	codes := map[string]int{"/400": 400, "/401": 401, "/404": 404, "/413": 413, "/503": 503}
	f := fakeDaemon(t, certCfg(c), goodAnswer(testToken, c), func(f *fake) {
		f.other = func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(codes[r.URL.Path])
			_, _ = io.WriteString(w, `{"error":"daemon says no"}`)
		}
	})
	cl := newTestClient(t, f.url(), nil, nil)
	want := map[string]Kind{"/400": KindPermanent, "/401": KindAuth, "/404": KindPermanent, "/413": KindPermanent, "/503": KindTransient}
	for p, k := range want {
		resp, err := cl.Do(context.Background(), "POST", p, map[string]any{})
		if Classify(err) != k {
			t.Errorf("%s: %v (%v)", p, Classify(err), err)
		}
		if resp == nil || !strings.Contains(string(resp.Body), "daemon says no") || !strings.Contains(err.Error(), "daemon says no") {
			t.Errorf("%s: the daemon's own text was lost: %v", p, err)
		}
	}
}

// The redirect a hostile (or broken) peer might send is never followed.
func TestRedirectNotFollowed(t *testing.T) {
	c := mintCert(t, "redirect")
	var hits atomic.Int32
	other := httptest.NewServer(http.HandlerFunc(func(http.ResponseWriter, *http.Request) { hits.Add(1) }))
	t.Cleanup(other.Close)
	f := fakeDaemon(t, certCfg(c), goodAnswer(testToken, c), func(f *fake) {
		f.other = func(w http.ResponseWriter, r *http.Request) {
			http.Redirect(w, r, other.URL+"/steal", http.StatusFound)
		}
	})
	cl := newTestClient(t, f.url(), nil, nil)
	resp, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
	if err != nil || resp.Status != http.StatusFound || hits.Load() != 0 {
		t.Fatalf("redirect followed? status=%v err=%v hits=%d", resp, err, hits.Load())
	}
}
