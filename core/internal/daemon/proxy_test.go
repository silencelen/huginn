package daemon

import (
	"bufio"
	"bytes"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
)

// The /appd proxy carries everything the desktop sends (docs/DESKTOP-VIA-CORE.md,
// "What the core's proxy carries today"): each test is one traffic kind that the
// first, console-only proxy broke. A fake appd on loopback (plain http is allowed
// there and only there).

type seen struct {
	auth, client, notify, ctype string
	n                           int64
}

func proxyRig(t *testing.T, appdH http.HandlerFunc) (*httptest.Server, *seen) {
	t.Helper()
	s := &seen{}
	fake := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		s.auth, s.client, s.notify, s.ctype = r.Header.Get("Authorization"), r.Header.Get("X-Huginn-Client"), r.Header.Get("X-Huginn-Notify"), r.Header.Get("Content-Type")
		if r.Body != nil {
			s.n, _ = io.Copy(io.Discard, r.Body)
		}
		appdH(w, r)
	}))
	t.Cleanup(fake.Close)
	api, err := appd.New(appd.Config{
		URL: fake.URL, Tokens: appd.StaticToken("core-token"),
		// The old proxy's fixed per-request limit; a forwarded request must not inherit it.
		RequestTimeout: 100 * time.Millisecond,
		Headers: func() map[string]string {
			return map[string]string{"X-Huginn-Client": "core-install", "X-Huginn-Notify": "core"}
		},
	})
	if err != nil {
		t.Fatal(err)
	}
	d := &Daemon{api: api}
	front := httptest.NewServer(http.HandlerFunc(d.proxy))
	t.Cleanup(front.Close)
	return front, s
}

// A chat message POSTed with ?stream=1 answers with events over time: the first
// must reach the caller while appd is still writing the rest.
func TestProxyStreamsPostEvents(t *testing.T) {
	front, _ := proxyRig(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		fl := w.(http.Flusher)
		for i := 0; i < 3; i++ {
			fmt.Fprintf(w, "data: %d\n\n", i)
			fl.Flush()
			time.Sleep(400 * time.Millisecond)
		}
	})
	t0 := time.Now()
	res, err := http.Post(front.URL+"/appd/v1/chats/c1/messages?stream=1", "application/json", strings.NewReader(`{"text":"hi"}`))
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	if ct := res.Header.Get("Content-Type"); ct != "text/event-stream" {
		t.Fatalf("Content-Type %q", ct)
	}
	line, err := bufio.NewReader(res.Body).ReadString('\n')
	if err != nil || line != "data: 0\n" {
		t.Fatalf("first line %q, %v", line, err)
	}
	if el := time.Since(t0); el > 350*time.Millisecond {
		t.Fatalf("first event after %v: the stream was buffered", el)
	}
}

// The Screen tab's long poll waits on appd for up to 25 s; nothing in the proxy
// may cut it short (the client above has a 100 ms request limit).
func TestProxyHasNoFixedTimeout(t *testing.T) {
	front, _ := proxyRig(t, func(w http.ResponseWriter, r *http.Request) {
		time.Sleep(400 * time.Millisecond)
		w.Write([]byte(`{"screen":"x"}`))
	})
	res, err := http.Get(front.URL + "/appd/v1/sessions/main/screen?wait=25000")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 200 || string(b) != `{"screen":"x"}` {
		t.Fatalf("%d %s", res.StatusCode, b)
	}
}

// An upload is raw bytes with the file's own type, which appd turns into the
// file's extension; and it may be larger than the old 32 MiB buffer.
func TestProxyUploadKeepsTypeAndSize(t *testing.T) {
	front, s := proxyRig(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Write([]byte(`{"name":"x.png"}`))
	})
	const size = 40 << 20
	res, err := http.Post(front.URL+"/appd/v1/uploads?name=x.png", "image/png", io.LimitReader(zeros{}, size))
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if res.StatusCode != 200 || s.ctype != "image/png" || s.n != size {
		t.Fatalf("status %d, appd saw Content-Type %q and %d bytes", res.StatusCode, s.ctype, s.n)
	}
}

type zeros struct{}

func (zeros) Read(p []byte) (int, error) { clear(p); return len(p), nil }

// A download keeps appd's headers: its type, nosniff, appd's own X-Huginn-*.
func TestProxyDownloadKeepsHeaders(t *testing.T) {
	png := []byte("\x89PNG\r\n\x1a\nbytes")
	front, _ := proxyRig(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "image/png")
		w.Header().Set("X-Content-Type-Options", "nosniff")
		w.Header().Set("X-Huginn-Appd", "3.12.0")
		w.Write(png)
	})
	res, err := http.Get(front.URL + "/appd/v1/apps/files/icon")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if !bytes.Equal(b, png) || res.Header.Get("Content-Type") != "image/png" ||
		res.Header.Get("X-Content-Type-Options") != "nosniff" || res.Header.Get("X-Huginn-Appd") != "3.12.0" {
		t.Fatalf("body %q headers %v", b, res.Header)
	}
}

// appd tells the desktop from the core by X-Huginn-Client (pane lease owner,
// notify claim): a caller's own id reaches appd; without one, the core's does.
// The token is always the core's, whatever a caller sends.
func TestProxyIdentityAndToken(t *testing.T) {
	front, s := proxyRig(t, func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(`{}`)) })
	req, _ := http.NewRequest(http.MethodPost, front.URL+"/appd/v1/sessions/main/size", strings.NewReader(`{}`))
	req.Header.Set("X-Huginn-Client", "desktop-kt-1")
	req.Header.Set("X-Huginn-Notify", "desktop")
	req.Header.Set("Authorization", "Bearer someone-elses")
	res, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if s.client != "desktop-kt-1" || s.notify != "desktop" || s.auth != "Bearer core-token" {
		t.Fatalf("appd saw client %q notify %q auth %q", s.client, s.notify, s.auth)
	}
	res, err = http.Get(front.URL + "/appd/v1/status")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if s.client != "core-install" || s.notify != "core" {
		t.Fatalf("with no caller id appd saw client %q notify %q", s.client, s.notify)
	}
}

// appd's own refusals come back as they are; only an unreachable appd is the
// core's 502, with a kind for the banner; and only /v1/ is forwarded.
func TestProxyStatusAndErrors(t *testing.T) {
	front, _ := proxyRig(t, func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(http.StatusNotFound)
		w.Write([]byte(`{"error":"no session called nope"}`))
	})
	res, err := http.Get(front.URL + "/appd/v1/sessions/nope/overview")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != 404 || string(b) != `{"error":"no session called nope"}` {
		t.Fatalf("%d %s", res.StatusCode, b)
	}
	res, _ = http.Get(front.URL + "/appd/healthz")
	res.Body.Close()
	if res.StatusCode != 404 {
		t.Fatalf("non-/v1 path: %d", res.StatusCode)
	}

	dead, _ := appd.New(appd.Config{URL: "http://127.0.0.1:1", Tokens: appd.StaticToken("t")})
	gone := httptest.NewServer(http.HandlerFunc((&Daemon{api: dead}).proxy))
	defer gone.Close()
	res, err = http.Get(gone.URL + "/appd/v1/status")
	if err != nil {
		t.Fatal(err)
	}
	b, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusBadGateway || !strings.Contains(string(b), `"kind":"unreachable"`) {
		t.Fatalf("dead appd: %d %s", res.StatusCode, b)
	}
	unpaired := httptest.NewServer(http.HandlerFunc((&Daemon{}).proxy))
	defer unpaired.Close()
	res, _ = http.Get(unpaired.URL + "/appd/v1/status")
	b, _ = io.ReadAll(res.Body)
	res.Body.Close()
	if res.StatusCode != http.StatusServiceUnavailable || !strings.Contains(string(b), `"kind":"unpaired"`) {
		t.Fatalf("unpaired: %d %s", res.StatusCode, b)
	}
}
