package appd

// End to end against a REAL appd from this tree (server/appd), the way
// scripts/test-device-tls.js does it: scratch dirs, a throwaway token, a
// PRIVATE tmux socket, a port the kernel picks, and a TLS-terminating relay in
// front of it that records every byte it decrypts. Only a real relay proves
// the token never reaches an unproven peer.
//
// SAFETY: every daemon this starts is killed in Cleanup, including on a failed
// assertion — a stray appd blocks the release gates.

import (
	"context"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"encoding/hex"
	"encoding/pem"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

type realAppd struct {
	t       *testing.T
	script  string
	tmp     string
	data    string
	port    int
	cmd     *exec.Cmd
	exited  chan struct{}
	tmuxSoc string
}

func randToken(t *testing.T) string {
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	return hex.EncodeToString(b)
}

func freePort(t *testing.T) int {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer ln.Close()
	return ln.Addr().(*net.TCPAddr).Port
}

func newRealAppd(t *testing.T) *realAppd {
	if _, err := exec.LookPath("node"); err != nil {
		t.Skip("node is not installed; the end-to-end tests need a real appd")
	}
	script, err := filepath.Abs(filepath.Join("..", "..", "..", "server", "appd", "huginn-appd.js"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(script); err != nil {
		t.Skipf("no appd at %s: %v", script, err)
	}
	tmp := t.TempDir()
	a := &realAppd{t: t, script: script, tmp: tmp, data: filepath.Join(tmp, "appd-data"), port: freePort(t),
		tmuxSoc: fmt.Sprintf("huginn-coretls-%d", os.Getpid())}
	t.Cleanup(func() {
		a.stop()
		_ = exec.Command("tmux", "-L", a.tmuxSoc, "kill-server").Run()
	})
	return a
}

func (a *realAppd) start(tok string) {
	t := a.t
	t.Helper()
	for _, d := range []string{a.data, filepath.Join(a.tmp, "appd-state"), filepath.Join(a.tmp, "appd-claude")} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	tokFile := filepath.Join(a.tmp, "appd-token")
	if err := os.WriteFile(tokFile, []byte(tok+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	logf, err := os.OpenFile(filepath.Join(a.tmp, "appd.log"), os.O_CREATE|os.O_APPEND|os.O_WRONLY, 0o600)
	if err != nil {
		t.Fatal(err)
	}
	env := []string{}
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "HUGINN_APPD_") && !strings.HasPrefix(kv, "HUGINN_FCM_KEY=") {
			env = append(env, kv)
		}
	}
	// The push key is not HUGINN_APPD_-prefixed, and unset it falls back to the
	// live key on a huginn host: point it at nothing, like the Node suites do.
	env = append(env,
		"HUGINN_FCM_KEY=/nonexistent/huginn-test-fcm-service-account.json",
		fmt.Sprintf("HUGINN_APPD_PORT=%d", a.port),
		"HUGINN_APPD_BIND=127.0.0.1",
		"HUGINN_APPD_DATA="+a.data,
		"HUGINN_APPD_TOKEN_FILE="+tokFile,
		"HUGINN_APPD_STATE_DIR="+filepath.Join(a.tmp, "appd-state"),
		"HUGINN_APPD_CLAUDE_DIR="+filepath.Join(a.tmp, "appd-claude"),
		"HUGINN_APPD_TMUX_SOCKET="+a.tmuxSoc,
		"HUGINN_APPD_WORKDIR="+a.tmp,
	)
	a.cmd = exec.Command("node", a.script)
	a.cmd.Env = env
	a.cmd.Stdout, a.cmd.Stderr = logf, logf
	if err := a.cmd.Start(); err != nil {
		t.Fatal(err)
	}
	a.exited = make(chan struct{})
	go func(c *exec.Cmd, ch chan struct{}, f *os.File) { _ = c.Wait(); f.Close(); close(ch) }(a.cmd, a.exited, logf)

	hc := &http.Client{Timeout: time.Second}
	for i := 0; i < 300; i++ {
		select {
		case <-a.exited:
			t.Fatalf("appd exited at start (server-side, not this client):\n%s", a.logTail())
		default:
		}
		if r, err := hc.Get(fmt.Sprintf("http://127.0.0.1:%d/v1/ping", a.port)); err == nil {
			r.Body.Close()
			return
		}
		time.Sleep(100 * time.Millisecond)
	}
	t.Fatalf("appd did not start (server-side, not this client):\n%s", a.logTail())
}

func (a *realAppd) logTail() string {
	b, _ := os.ReadFile(filepath.Join(a.tmp, "appd.log"))
	if len(b) > 1500 {
		b = b[len(b)-1500:]
	}
	return string(b)
}

func (a *realAppd) stop() {
	if a.cmd == nil || a.cmd.Process == nil {
		return
	}
	_ = a.cmd.Process.Signal(syscall.SIGTERM)
	select {
	case <-a.exited:
	case <-time.After(3 * time.Second):
		_ = a.cmd.Process.Kill()
		<-a.exited
	}
	a.cmd = nil
}

// fp is the fingerprint of the certificate appd minted on disk.
func (a *realAppd) fp() string {
	a.t.Helper()
	b, err := os.ReadFile(filepath.Join(a.data, "tls", "cert.pem"))
	if err != nil {
		a.t.Fatal(err)
	}
	blk, _ := pem.Decode(b)
	if blk == nil {
		a.t.Fatal("cert.pem holds no PEM block")
	}
	if _, err := x509.ParseCertificate(blk.Bytes); err != nil {
		a.t.Fatal(err)
	}
	return Fingerprint(blk.Bytes)
}

func (a *realAppd) url() string { return fmt.Sprintf("https://127.0.0.1:%d", a.port) }

// relay is a TLS-terminating proxy with its own certificate in front of appd:
// it decrypts what the client sends, records every byte, and forwards it over
// a fresh TLS connection to the real daemon — the textbook interception, and
// the one a relayed proof is supposed to defeat.
type relay struct {
	addr string
	mu   sync.Mutex
	got  []byte
}

func (r *relay) plaintext() string {
	r.mu.Lock()
	defer r.mu.Unlock()
	return string(r.got)
}

func newRelay(t *testing.T, cert testCert, upstream string) *relay {
	ln, err := tls.Listen("tcp", "127.0.0.1:0", &tls.Config{Certificates: []tls.Certificate{cert.cert}})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(func() { ln.Close() })
	r := &relay{addr: ln.Addr().String()}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func() {
				defer c.Close()
				up, err := tls.Dial("tcp", upstream, &tls.Config{InsecureSkipVerify: true})
				if err != nil {
					return
				}
				defer up.Close()
				go func() { _, _ = io.Copy(c, up); c.Close() }()
				buf := make([]byte, 4096)
				for {
					n, err := c.Read(buf)
					if n > 0 {
						r.mu.Lock()
						r.got = append(r.got, buf[:n]...)
						r.mu.Unlock()
						if _, werr := up.Write(buf[:n]); werr != nil {
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
	return r
}

func TestE2ERealAppd(t *testing.T) {
	a := newRealAppd(t)
	tok := randToken(t)
	a.start(tok)
	t.Setenv(TokenEnv, "")
	t.Setenv(PlaintextEnv, "") // a developer's shell must not change what is tested
	pollWaitSeconds = 1
	t.Cleanup(func() { pollWaitSeconds = 25 })

	store := &MemoryPinStore{}
	tokens := StaticToken(tok)
	client := func(u string) *Client {
		c, err := New(Config{URL: u, Tokens: tokens, Pins: store, Logf: t.Logf})
		if err != nil {
			t.Fatal(err)
		}
		t.Cleanup(c.Close)
		return c
	}
	ctx := context.Background()
	var devID string

	t.Run("enrol over https pins the daemon's own leaf; the API round-trips", func(t *testing.T) {
		c := client(a.url())
		persistent := false
		dev, err := c.Enrol(ctx, Enrolment{Name: "tls-e2e", Platform: "linux", Scope: "look",
			Version: "2.0.0-test", Machine: "core-test", Persistent: &persistent})
		if err != nil {
			t.Fatal(err)
		}
		if dev.ID == "" || dev.Scope != "look" || dev.EffectiveScope == "" {
			t.Fatalf("enrol reply: %+v", dev)
		}
		devID = dev.ID
		if got := pinsOf(t, store, tok); !equal(got, []string{a.fp()}) {
			t.Fatalf("pins = %v, want the daemon's own leaf %s", got, a.fp())
		}
		if dev.ActWhileLocked == nil || *dev.ActWhileLocked {
			t.Fatalf("actWhileLocked must always travel as a real boolean: %s", dev.Raw)
		}

		br, err := c.Beat(ctx, devID, Beat{Scope: "look", Version: "2.0.0-test"})
		if err != nil || !br.OK || br.EffectiveScope == "" {
			t.Fatalf("beat: %+v %v", br, err)
		}
		w, err := c.PollWork(ctx, devID, false)
		if err != nil || w != nil {
			t.Fatalf("long poll over the pinned channel: %+v %v", w, err)
		}
		g, err := c.Get(ctx, devID)
		if err != nil || g.ID != devID || g.Name != "tls-e2e" {
			t.Fatalf("get: %+v %v", g, err)
		}
		// An events POST for a run that does not exist: 404, permanent.
		_, err = c.PostEvents(ctx, devID, "00000000-0000-0000-0000-000000000000", Events{Done: true, Error: "x"})
		if !IsPermanent(err) {
			t.Fatalf("events for a missing run: want permanent, got %v (%v)", err, Classify(err))
		}
	})

	// Security scan F8: how a core on the server itself was paired. The same
	// port answers TLS, so the stored http:// is dialled as https://, proved
	// and pinned like any other address before the token goes anywhere.
	t.Run("a loopback http:// is dialled over TLS and proved; a wrong token is refused before it is sent", func(t *testing.T) {
		t.Setenv(PlaintextEnv, "")
		plain := fmt.Sprintf("http://127.0.0.1:%d", a.port)
		fresh := &MemoryPinStore{}
		c, err := New(Config{URL: plain, Tokens: tokens, Pins: fresh})
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		if c.URL() != a.url() {
			t.Fatalf("loopback http was dialled as %s, want %s", c.URL(), a.url())
		}
		if _, err := c.Get(ctx, devID); err != nil {
			t.Fatal(err)
		}
		if got := pinsOf(t, fresh, tok); !equal(got, []string{a.fp()}) {
			t.Fatalf("pins = %v, want the daemon's own leaf %s", got, a.fp())
		}
		// A different token cannot make the proof, so it is never sent: the
		// interception sentence with the wrong-token hint, not a 401.
		bad, err := New(Config{URL: plain, Tokens: StaticToken(randToken(t)), Pins: &MemoryPinStore{}})
		if err != nil {
			t.Fatal(err)
		}
		defer bad.Close()
		if _, err := bad.Get(ctx, devID); !errors.Is(err, ErrIntercepted) || !strings.Contains(err.Error(), tokenHint) {
			t.Fatalf("wrong token over loopback: want the refused proof, got %v", err)
		}
	})

	t.Run("HUGINN_APPD_PLAINTEXT=local keeps loopback plain, and a wrong token is then a 401", func(t *testing.T) {
		t.Setenv(PlaintextEnv, "local")
		plain := fmt.Sprintf("http://127.0.0.1:%d", a.port)
		c, err := New(Config{URL: plain, Tokens: tokens, Pins: &MemoryPinStore{}})
		if err != nil {
			t.Fatal(err)
		}
		defer c.Close()
		if c.URL() != plain {
			t.Fatalf("with the hatch, loopback http was rewritten: %s", c.URL())
		}
		if _, err := c.Get(ctx, devID); err != nil {
			t.Fatal(err)
		}
		bad, err := New(Config{URL: plain, Tokens: StaticToken(randToken(t)), Pins: &MemoryPinStore{}})
		if err != nil {
			t.Fatal(err)
		}
		defer bad.Close()
		if _, err := bad.Get(ctx, devID); !IsAuth(err) {
			t.Fatalf("wrong token over plain loopback: want auth, got %v", err)
		}
	})

	t.Run("a TLS relay in front of the real appd is refused and gets no token", func(t *testing.T) {
		rl := newRelay(t, mintCert(t, "relay"), fmt.Sprintf("127.0.0.1:%d", a.port))
		// Twice: once already pinned (the pin check refuses, the re-proof fails),
		// and once from nothing (the first proof fails).
		for _, fresh := range []bool{false, true} {
			s := store
			if fresh {
				s = &MemoryPinStore{}
			}
			c, err := New(Config{URL: "https://" + rl.addr, Tokens: tokens, Pins: s})
			if err != nil {
				t.Fatal(err)
			}
			_, err = c.Get(ctx, devID)
			c.Close()
			if !errors.Is(err, ErrIntercepted) {
				t.Fatalf("fresh=%v: want interception, got %v", fresh, err)
			}
		}
		seen := rl.plaintext()
		if !strings.Contains(seen, "GET /v1/challenge?nonce=") {
			t.Fatalf("the relay never saw the probe, so this proves nothing:\n%s", seen)
		}
		if strings.Contains(seen, tok) {
			t.Fatal("THE TOKEN REACHED THE RELAY")
		}
		if strings.Contains(strings.ToLower(seen), "authorization") {
			t.Fatal("an Authorization header reached the relay")
		}
		if !equal(pinsOf(t, store, tok), []string{a.fp()}) {
			t.Fatal("the refused relay changed the pin set")
		}
	})

	t.Run("rotation: appd mints a new certificate; the client re-proves and pins it", func(t *testing.T) {
		before := a.fp()
		a.stop()
		if err := os.RemoveAll(filepath.Join(a.data, "tls")); err != nil {
			t.Fatal(err)
		}
		a.start(tok)
		after := a.fp()
		if after == before {
			t.Fatal("appd did not mint a new certificate")
		}
		c := client(a.url())
		if _, err := c.Get(ctx, devID); err != nil {
			t.Fatal(err)
		}
		if got := pinsOf(t, store, tok); !equal(got, []string{after, before}) {
			t.Fatalf("pins = %v, want new first and old kept", got)
		}
	})

	t.Run("token change: the old pins are invisible and the new token proves afresh", func(t *testing.T) {
		t2 := randToken(t)
		if got := pinsOf(t, store, t2); len(got) != 0 {
			t.Fatalf("a new token sees pins: %v", got)
		}
		a.stop()
		a.start(t2)
		tokens = StaticToken(t2)
		c := client(a.url())
		// The device row may or may not survive; only the proof matters here.
		if _, err := c.Get(ctx, devID); err != nil && Classify(err) != KindPermanent {
			t.Fatalf("under the new token: %v", err)
		}
		if got := pinsOf(t, store, t2); !equal(got, []string{a.fp()}) {
			t.Fatalf("pins under the new token = %v", got)
		}
		if got := pinsOf(t, store, tok); len(got) != 0 {
			t.Fatalf("the old token's pins survived: %v", got)
		}
	})

	t.Run("delete unenrols; the row is then gone (permanent)", func(t *testing.T) {
		c := client(a.url())
		dev, err := c.Enrol(ctx, Enrolment{Name: "tls-e2e-2", Platform: "linux", Scope: "look", Version: "2.0.0-test"})
		if err != nil {
			t.Fatal(err)
		}
		if err := c.Delete(ctx, dev.ID); err != nil {
			t.Fatal(err)
		}
		if _, err := c.Get(ctx, dev.ID); !IsPermanent(err) {
			t.Fatalf("get after delete: want 404/permanent, got %v", err)
		}
	})
}
