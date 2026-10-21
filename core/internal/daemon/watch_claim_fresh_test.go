package daemon

import (
	"context"
	"net/http"
	"sync/atomic"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
	"github.com/silencelen/huginn/core/internal/lock"
)

// The watch stream carries the notify claim, and the watch loop re-opens it
// when the claim flips. Requests may answer the claim from an old lock reading
// (claim_no_probe_wait_test.go), but the loop's own decision must not: it runs
// on its own goroutine, waits for nobody, and a stream opened on an old
// "unlocked" would tell appd this machine shows notifications while its screen
// is locked, until the next check 15 s later.
func TestTheWatchStreamOpensOnAFreshClaim(t *testing.T) {
	var probes atomic.Int32
	// The second probe (the screen has locked since the first) answers once the
	// stream has been asked for, or after 2 s: a loop that opens the stream
	// without waiting for it has already sent its claim by then.
	streamAsked := make(chan struct{})
	var once atomic.Bool
	d := &Daemon{lock: &lockReader{probe: func(context.Context) lock.Result {
		if probes.Add(1) == 1 {
			return lock.Result{Reading: lock.Unlocked}
		}
		select {
		case <-streamAsked:
		case <-time.After(2 * time.Second):
		}
		return lock.Result{Reading: lock.Locked}
	}, canGraphical: func(context.Context) (bool, bool) { return true, true }}}
	w := &watcher{d: d}
	d.watch = w
	w.trays.Store(1)
	w.senders.Store(1)
	if !w.claim() {
		t.Fatal("the first reading (unlocked) should claim")
	}
	ageReading(d.lock)

	notify := make(chan string, 4)
	fake := appdtest.NewServer(t, "core-token", http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		if r.URL.Path == "/v1/watch" {
			select {
			case notify <- r.Header.Get("X-Huginn-Notify"):
			default:
			}
			if once.CompareAndSwap(false, true) {
				close(streamAsked)
			}
			rw.Header().Set("Content-Type", "text/event-stream")
			rw.(http.Flusher).Flush()
			<-r.Context().Done()
			return
		}
		rw.Write([]byte(`{}`))
	}))
	api, err := appd.New(appd.Config{URL: fake.URL, Tokens: appd.StaticToken("core-token"), Headers: w.headers})
	if err != nil {
		t.Fatal(err)
	}
	defer api.Close()
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { w.run(ctx, api); close(done) }()
	defer func() { cancel(); <-done }()

	select {
	case got := <-notify:
		if got != "0" {
			t.Fatalf("the watch stream opened with X-Huginn-Notify %q on a locked screen; want 0", got)
		}
	case <-time.After(10 * time.Second):
		t.Fatal("the watch stream never opened")
	}
}
