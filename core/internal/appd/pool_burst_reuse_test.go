package appd

import (
	"context"
	"io"
	"net"
	"net/http"
	"sync"
	"sync/atomic"
	"testing"
	"time"
)

// A burst of requests at once is what the core carries all day: the console
// loads eight lists in parallel, and the desktop's polls run beside its watch
// stream, its screen long poll and the runner's. The pinned transport kept
// FOUR idle connections, so every connection above that was closed the moment
// it was done, and the next burst opened it again: a full TLS handshake each
// (no resumption, by design), ~45 ms apiece against appd on loopback and two
// round trips more over a mesh link.
func TestABurstReusesItsConnections(t *testing.T) {
	const burst, rounds = 8, 5
	c := mintCert(t, "pool")
	var opened atomic.Int32
	f := fakeDaemon(t, certCfg(c), goodAnswer(testToken, c), func(f *fake) {
		f.srv.Config.ConnState = func(_ net.Conn, s http.ConnState) {
			if s == http.StateNew {
				opened.Add(1)
			}
		}
	})
	// Each request is held until the whole burst has arrived, so the burst
	// needs `burst` connections at once, every round, on any machine.
	var mu sync.Mutex
	var held []chan struct{}
	f.other = func(w http.ResponseWriter, r *http.Request) {
		gate := make(chan struct{})
		mu.Lock()
		held = append(held, gate)
		if len(held) == burst {
			for _, g := range held {
				close(g)
			}
			held = nil
		}
		mu.Unlock()
		select {
		case <-gate:
		case <-time.After(5 * time.Second):
		}
		io.WriteString(w, `{"ok":true}`)
	}
	cl := newTestClient(t, f.url(), &MemoryPinStore{}, nil)
	ctx := context.Background()

	run := func() {
		var wg sync.WaitGroup
		for i := 0; i < burst; i++ {
			wg.Add(1)
			go func() {
				defer wg.Done()
				res, err := cl.Forward(ctx, http.MethodGet, "/v1/sessions", http.Header{}, nil)
				if err != nil {
					t.Error(err)
					return
				}
				io.Copy(io.Discard, res.Body)
				res.Body.Close()
			}()
		}
		wg.Wait()
	}
	run() // proves, pins, and opens the burst's connections
	first := opened.Load()
	for i := 1; i < rounds; i++ {
		run()
	}
	if again := opened.Load() - first; again != 0 {
		t.Fatalf("%d more connections for %d more bursts of %d over a link that stayed up; want 0 (the first burst opened %d)",
			again, rounds-1, burst, first)
	}
}
