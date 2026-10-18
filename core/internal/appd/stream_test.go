package appd

import (
	"bufio"
	"context"
	"net/http"
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

// Over the proved channel (a fake that proves itself, on loopback like the
// daemon on the server itself): the caller's headers ride along, the token
// header is never theirs to set, and a 4xx is a StatusError.
func TestStreamStampsHeadersButNeverTheToken(t *testing.T) {
	var got http.Header
	cert := mintCert(t, "stream")
	f := fakeDaemon(t, certCfg(cert), goodAnswer(testToken, cert), func(f *fake) {
		f.other = func(w http.ResponseWriter, r *http.Request) {
			if r.URL.Path == "/v1/denied" {
				http.Error(w, "nope", 401)
				return
			}
			got = r.Header.Clone()
			w.Header().Set("Content-Type", "text/event-stream")
			w.Write([]byte("event: state\ndata: {}\n\n"))
		}
	})
	c := newTestClient(t, f.url(), nil, func(cfg *Config) {
		cfg.Headers = func() map[string]string {
			return map[string]string{"X-Huginn-Notify": "1", "authorization": "Bearer stolen"}
		}
	})
	res, err := c.Stream(context.Background(), "/v1/watch?stream=1")
	if err != nil {
		t.Fatal(err)
	}
	res.Body.Close()
	if got.Get("X-Huginn-Notify") != "1" || got.Get("Authorization") != "Bearer "+testToken {
		t.Fatalf("headers %v", got)
	}
	// And a 4xx comes back as a StatusError with the body closed.
	if _, err := c.Stream(context.Background(), "/v1/denied"); !IsAuth(err) {
		t.Fatalf("got %v", err)
	}
}
