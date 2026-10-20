package daemon

import (
	"crypto/ecdsa"
	"crypto/elliptic"
	"crypto/rand"
	"crypto/tls"
	"crypto/x509"
	"crypto/x509/pkix"
	"encoding/json"
	"errors"
	"math/big"
	"net/http"
	"net/http/httptest"
	"net/url"
	"slices"
	"strings"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
	"github.com/silencelen/huginn/core/internal/store"
)

// answered waits for job id's ending and says what is wrong with it, if
// anything: an error, a missing result, or a run appd lost.
func (g *restartRig) answered(t *testing.T, id string) string {
	t.Helper()
	g.waitFor(t, id+"'s ending", 20*time.Second, func() bool {
		g.f.mu.Lock()
		defer g.f.mu.Unlock()
		return g.f.done[id] != nil || len(g.f.lost) > 0
	})
	g.f.mu.Lock()
	defer g.f.mu.Unlock()
	switch end := g.f.done[id]; {
	case len(g.f.lost) > 0:
		return "appd lost " + strings.Join(g.f.lost, "; ")
	case end.Error != "" || end.ExitCode == nil || *end.ExitCode != 0:
		return "the job ended with " + end.Error
	case !slices.ContainsFunc(g.f.lines[id], func(l string) bool { return strings.Contains(l, "THE ANSWER") }):
		return "the job's answer never reached appd"
	}
	return ""
}

// Settings > Host and `huginn core route` edit the route book while this
// machine runs a job. The edit used to restart the runner under the job: the
// call waited for the rest of the job (the desktop and the CLI give up after
// 30 s), the /appd proxy answered "not paired" meanwhile, and the job's output
// and ending were lost, so appd failed the chat. Now the edit only re-routes.
func TestRouteEditsDuringAJobLeaveItAlone(t *testing.T) {
	g := newRestartRig(t, 3)
	g.startJob(t, "w1")
	other := "https://192.0.2.10:8787"
	for _, c := range []struct{ method, path, body string }{
		{"PATCH", "/core/routes", `{"autoSwitch":false}`},
		{"POST", "/core/routes", `{"url":"` + other + `","kind":"lan"}`},
		{"PATCH", "/core/routes", `{"pinned":"` + g.url + `"}`},
		{"PATCH", "/core/routes", `{"pinned":""}`},
		{"DELETE", "/core/routes?url=" + url.QueryEscape(other), ""},
		{"PATCH", "/core/routes", `{"autoSwitch":true}`},
	} {
		code, body, took := g.call(t, c.method, c.path, c.body)
		if code != 200 || took > time.Second {
			t.Fatalf("%s %s %s: %d after %v: %s", c.method, c.path, c.body, code, took.Round(time.Millisecond), body)
		}
		if code, body, _ := g.call(t, "GET", "/appd/v1/status", ""); code != 200 {
			t.Fatalf("the desktop's link through the core after %s %s: %d %s", c.method, c.body, code, body)
		}
	}
	if !g.d.busy() {
		t.Fatal("the job was over before the edits were: the test proves nothing")
	}
	if why := g.answered(t, "w1"); why != "" {
		t.Fatal(why)
	}
	if g.f.enrols != 1 {
		t.Fatalf("the route edits enrolled the machine %d times", g.f.enrols)
	}
}

// A route edit that mends the link takes effect at once, as the restart it
// replaces did: a runner sitting out its retry after "Not reaching huginn"
// tries the new book now, not 15 s later.
func TestRouteEditThatMendsTheLinkTakesEffectAtOnce(t *testing.T) {
	g := newRestartRig(t, 1)
	dead := "https://127.0.0.1:1"
	for _, c := range []struct{ method, path, body string }{
		{"POST", "/core/routes", `{"url":"` + dead + `"}`},
		{"PATCH", "/core/routes", `{"pinned":"` + dead + `"}`},
	} {
		if code, body, _ := g.call(t, c.method, c.path, c.body); code != 200 {
			t.Fatalf("%s %s: %d %s", c.method, c.body, code, body)
		}
	}
	g.d.restartRunner(g.d.ctx) // on the dead address only: it cannot enrol
	g.waitFor(t, "the runner to give up for now", 10*time.Second, func() bool {
		g.d.mu.Lock()
		note := g.d.last.Note
		g.d.mu.Unlock()
		return strings.HasPrefix(note, "Not reaching huginn")
	})
	g.f.mu.Lock()
	g.f.enrols = 0 // the runner the first edits started enrolled before the pin
	g.f.mu.Unlock()
	if code, body, _ := g.call(t, "PATCH", "/core/routes", `{"pinned":""}`); code != 200 {
		t.Fatalf("unpin: %d %s", code, body)
	}
	g.waitFor(t, "the runner to enrol on the mended book", 3*time.Second, func() bool {
		g.f.mu.Lock()
		defer g.f.mu.Unlock()
		return g.f.enrols > 0
	})
}

// Remove this computer while it runs a job: the call answers at once and the
// job is stopped (appd dropped its run with the rows), where it used to wait
// out the job, so the desktop called the removal failed while an act run went
// on changing files on a machine the owner had just removed.
func TestUnpairDuringAJobStopsItAndAnswers(t *testing.T) {
	g := newRestartRig(t, 3)
	g.startJob(t, "w1")
	code, body, took := g.call(t, "POST", "/core/unpair", "{}")
	if code != 200 || took > 2*time.Second {
		t.Fatalf("unpair: %d after %v: %s", code, took.Round(time.Millisecond), body)
	}
	if _, err := g.d.secrets.Get(store.SecretToken); !errors.Is(err, store.ErrNotFound) {
		t.Fatalf("the token is still held once unpair has answered: %v", err)
	}
	time.Sleep(3500 * time.Millisecond) // past the job's own end
	if g.marked() != 0 {
		t.Fatal("the job ran to its end after the machine was removed")
	}
}

// Pairing again while a job runs. The same huginn (on the same or another
// address) keeps the runner and the job, which still delivers its answer;
// another huginn starts over at once, stopping the job that can no longer
// report to the one this core left.
func TestPairingAgainDuringAJob(t *testing.T) {
	g := newRestartRig(t, 3)
	g.startJob(t, "w1")
	code, body, took := g.call(t, "POST", "/core/pair", `{"url":"`+g.url+`","token":"`+g.tok+`"}`)
	if code != 200 || took > 2*time.Second {
		t.Fatalf("pairing again with the same huginn: %d after %v: %s", code, took.Round(time.Millisecond), body)
	}
	if why := g.answered(t, "w1"); why != "" {
		t.Fatal(why)
	}
	if g.f.enrols != 1 {
		t.Fatalf("pairing with the same huginn enrolled the machine %d times", g.f.enrols)
	}

	g.f.queueJob("w2")
	g.waitFor(t, "w2 to be running", 10*time.Second, func() bool { return g.d.busy() })
	f2 := &devFake{handed: map[string]bool{}, lines: map[string][]string{}, done: map[string]*appd.Events{}}
	tok2 := strings.Repeat("s", 64)
	srv2 := otherHuginn(t, tok2, f2.handler())
	// Cleanups run last-in first: the runner's open watch stream on srv2 must
	// be gone before srv2's Close, which waits for every open request.
	t.Cleanup(func() { g.d.stopRunner(true) })
	code, body, took = g.call(t, "POST", "/core/pair", `{"url":"`+srv2.URL+`","token":"`+tok2+`"}`)
	if code != 200 || took > 2*time.Second {
		t.Fatalf("pairing with another huginn: %d after %v: %s", code, took.Round(time.Millisecond), body)
	}
	g.waitFor(t, "the runner to enrol with the new huginn", 10*time.Second, func() bool {
		f2.mu.Lock()
		defer f2.mu.Unlock()
		return f2.enrols > 0
	})
	time.Sleep(3500 * time.Millisecond) // past w2's own end
	if g.marked() != 1 {
		t.Fatal("the job for the huginn this core left ran on to its end")
	}
	if code, body, _ := g.call(t, http.MethodGet, "/appd/v1/status", ""); code != 200 {
		t.Fatalf("the link through the core after pairing elsewhere: %d %s", code, body)
	}
}

// otherHuginn is a fake appd with a certificate of its own, as every real one
// has (appdtest's servers all share httptest's, so two of them read as one).
func otherHuginn(t *testing.T, token string, h http.Handler) *httptest.Server {
	t.Helper()
	key, err := ecdsa.GenerateKey(elliptic.P256(), rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	tmpl := &x509.Certificate{SerialNumber: big.NewInt(time.Now().UnixNano()), Subject: pkix.Name{CommonName: "huginn-appd"},
		NotBefore: time.Now().Add(-time.Hour), NotAfter: time.Now().Add(time.Hour),
		KeyUsage: x509.KeyUsageDigitalSignature, ExtKeyUsage: []x509.ExtKeyUsage{x509.ExtKeyUsageServerAuth}}
	der, err := x509.CreateCertificate(rand.Reader, tmpl, tmpl, &key.PublicKey, key)
	if err != nil {
		t.Fatal(err)
	}
	fp := appd.Fingerprint(der)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/v1/challenge" {
			nonce := r.URL.Query().Get("nonce")
			json.NewEncoder(w).Encode(map[string]any{"version": appdtest.Version,
				"tls": map[string]any{"v": appd.ProofVersion, "fp": fp, "proof": appd.BoundProof(token, nonce, fp)}})
			return
		}
		h.ServeHTTP(w, r)
	}))
	srv.TLS = &tls.Config{Certificates: []tls.Certificate{{Certificate: [][]byte{der}, PrivateKey: key}}}
	srv.StartTLS()
	t.Cleanup(srv.Close)
	return srv
}
