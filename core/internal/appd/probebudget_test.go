package appd

// The challenge is answered by whoever holds the address, before it has proved
// anything, so what it sends may cost us only a bounded amount of memory. On
// the old code the body was capped but the status line and headers were not:
// a peer that never ended its status line was read into memory until the
// probe's deadline.

import (
	"bufio"
	"bytes"
	"context"
	"crypto/tls"
	"encoding/json"
	"io"
	"net"
	"net/http"
	"net/http/httptest"
	"runtime"
	"strings"
	"testing"
	"time"
)

// floodListener is a TLS peer that answers any request with a status line
// that does not end: "HTTP/1.1 200 ", then flood bytes with no line break,
// and then it holds the connection open as if more were on the way. The flood
// is finite only to keep a failing run (the old code) from taking the test
// process's memory with it.
func floodListener(t *testing.T, c testCert, flood int) string {
	t.Helper()
	ln, err := tls.Listen("tcp", "127.0.0.1:0", certCfg(c))
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			conn, err := ln.Accept()
			if err != nil {
				return
			}
			go func(conn net.Conn) {
				defer conn.Close()
				_ = conn.SetDeadline(time.Now().Add(30 * time.Second))
				if _, err := http.ReadRequest(bufio.NewReader(conn)); err != nil {
					return
				}
				if _, err := io.WriteString(conn, "HTTP/1.1 200 "); err != nil {
					return
				}
				chunk := bytes.Repeat([]byte("A"), 32<<10)
				for sent := 0; sent < flood; sent += len(chunk) {
					if _, err := conn.Write(chunk); err != nil {
						return
					}
				}
				_, _ = io.Copy(io.Discard, conn) // until the client hangs up
			}(conn)
		}
	}()
	t.Cleanup(func() { ln.Close() })
	return "https://" + ln.Addr().String()
}

func TestEndlessStatusLineFailsFastWithBoundedMemory(t *testing.T) {
	const flood = 8 << 20
	c := mintCert(t, "flood")
	u := floodListener(t, c, flood)
	cl := newTestClient(t, u, nil, func(cfg *Config) {
		cfg.ProbeTimeout = 3 * time.Second
	})

	var before, after runtime.MemStats
	runtime.GC()
	runtime.ReadMemStats(&before)
	t0 := time.Now()
	_, err := cl.Prove(context.Background())
	took := time.Since(t0)
	runtime.ReadMemStats(&after)
	// TotalAlloc only grows, so a GC in between cannot hide anything. Reading
	// the flood into a line costs at least the flood itself.
	grew := after.TotalAlloc - before.TotalAlloc
	t.Logf("answered in %v, having allocated %d bytes: %v", took, grew, err)

	if err == nil || !strings.Contains(err.Error(), "header ran past 16 KiB") {
		t.Errorf("want the header budget's refusal, got %v", err)
	} else {
		shownSafely(t, "the error", err.Error())
	}
	if took > 1500*time.Millisecond {
		t.Errorf("the probe read for %v; it should stop at the budget, not the 3s deadline", took)
	}
	if grew > flood/4 {
		t.Errorf("the probe allocated %d bytes against a peer that sent %d", grew, flood)
	}
}

// The budget leaves room for any honest answer: a header block of several
// KiB still parses, and the daemon behind it is proved and pinned.
func TestChallengeWithLargeHeaderStillProves(t *testing.T) {
	c := mintCert(t, "good")
	answer := goodAnswer(testToken, c)
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		w.Header().Set("X-Padding", strings.Repeat("p", 12<<10))
		_ = json.NewEncoder(w).Encode(answer(r.URL.Query().Get("nonce")))
	}))
	srv.Listener = tls.NewListener(srv.Listener, certCfg(c))
	srv.Start()
	t.Cleanup(srv.Close)

	store := &MemoryPinStore{}
	cl := newTestClient(t, "https://"+srv.Listener.Addr().String(), store, nil)
	fp, err := cl.Prove(context.Background())
	if err != nil {
		t.Fatal(err)
	}
	if fp != c.fp {
		t.Fatalf("proved %s, want %s", fp, c.fp)
	}
	if got := pinsOf(t, store, testToken); len(got) != 1 || got[0] != c.fp {
		t.Fatalf("pins = %v", got)
	}
}
