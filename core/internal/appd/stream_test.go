package appd

import (
	"bufio"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"
)

// The watch stream over the PROVED https channel of a real appd: the first
// event is the digest ("state"), and the body stays open (it is SSE).
func TestE2EStreamWatch(t *testing.T) {
	a := newRealAppd(t)
	tok := randToken(t)
	a.start(tok)
	c, err := New(Config{URL: a.url(), Tokens: StaticToken(tok), Pins: &MemoryPinStore{},
		Headers: func() map[string]string {
			return map[string]string{"X-Huginn-Client": "core-test", "X-Huginn-Notify": "1"}
		}})
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
	defer cancel()
	res, err := c.Stream(ctx, "/v1/watch?stream=1")
	if err != nil {
		t.Fatal(err)
	}
	defer res.Body.Close()
	rd := bufio.NewReader(res.Body)
	for {
		line, err := rd.ReadString('\n')
		if err != nil {
			t.Fatalf("stream ended before a state event: %v", err)
		}
		if strings.HasPrefix(line, "event: state") {
			return
		}
	}
}

func TestStreamStampsHeadersButNeverTheToken(t *testing.T) {
	var got http.Header
	srv := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got = r.Header.Clone()
		w.Header().Set("Content-Type", "text/event-stream")
		w.Write([]byte("event: state\ndata: {}\n\n"))
	}))
	defer srv.Close()
	c, err := New(Config{URL: srv.URL, Tokens: StaticToken("t0k3n"),
		Headers: func() map[string]string {
			return map[string]string{"X-Huginn-Notify": "1", "authorization": "Bearer stolen"}
		}})
	if err != nil {
		t.Fatal(err)
	}
	res, err := c.Stream(context.Background(), "/v1/watch?stream=1")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if got.Get("X-Huginn-Notify") != "1" || got.Get("Authorization") != "Bearer t0k3n" {
		t.Fatalf("headers %v", got)
	}
	// And a 4xx comes back as a StatusError with the body closed.
	srv2 := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) { http.Error(w, "nope", 401) }))
	defer srv2.Close()
	c2, _ := New(Config{URL: srv2.URL, Tokens: StaticToken("x")})
	if _, err := c2.Stream(context.Background(), "/v1/watch"); !IsAuth(err) {
		t.Fatalf("got %v", err)
	}
}
