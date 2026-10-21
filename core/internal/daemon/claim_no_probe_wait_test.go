package daemon

import (
	"context"
	"io"
	"net/http"
	"net/http/httptest"
	"sync"
	"sync/atomic"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
	"github.com/silencelen/huginn/core/internal/lock"
)

// The notify claim rides every request the core sends to appd, the desktop's
// through /appd included. It used to read the lock state through the fence's
// reader, so whichever request found the reading 5 s old ran the probe itself
// (PowerShell on Windows, a fresh system bus connection on Linux) with the
// reader's lock held: that request, and every request beside it, waited for
// the probe. These hold the claim to the newest reading, one probe at a time,
// in the background.

// slowProbe answers the first probe at once (unlocked) and holds every later
// one until release is closed, counting them.
type slowProbe struct {
	calls   atomic.Int32
	release chan struct{}
}

func (p *slowProbe) probe(ctx context.Context) lock.Result {
	if p.calls.Add(1) > 1 {
		select {
		case <-p.release:
		case <-ctx.Done():
		}
	}
	return lock.Result{Reading: lock.Unlocked}
}

// free lets every held probe go. Safe to call twice.
func (p *slowProbe) free() {
	select {
	case <-p.release:
	default:
		close(p.release)
	}
}

func claimRig(t *testing.T) (*Daemon, *watcher, *slowProbe) {
	t.Helper()
	p := &slowProbe{release: make(chan struct{})}
	t.Cleanup(p.free)
	d := &Daemon{lock: &lockReader{probe: p.probe, canGraphical: func(context.Context) (bool, bool) { return true, true }}}
	w := &watcher{d: d}
	d.watch = w
	w.trays.Store(1)
	w.senders.Store(1)
	return d, w, p
}

// ageReading makes the reading in hand older than the cache allows.
func ageReading(l *lockReader) {
	l.mu.Lock()
	l.at = time.Now().Add(-time.Minute)
	l.mu.Unlock()
}

func TestNotifyHeadersDoNotWaitForTheLockProbe(t *testing.T) {
	d, w, p := claimRig(t)
	if h := w.headers()["X-Huginn-Notify"]; h != "1" {
		t.Fatalf("first claim %q, want 1 (the first ask waits for the one reading there is)", h)
	}
	ageReading(d.lock)

	const callers = 8
	var wg sync.WaitGroup
	got := make(chan string, callers)
	for i := 0; i < callers; i++ {
		wg.Add(1)
		go func() {
			defer wg.Done()
			got <- w.headers()["X-Huginn-Notify"]
		}()
	}
	done := make(chan struct{})
	go func() { wg.Wait(); close(done) }()
	select {
	case <-done:
	case <-time.After(5 * time.Second):
		n := p.calls.Load()
		p.free()
		t.Fatalf("headers() waited for the lock probe (%d probes started)", n)
	}
	close(got)
	for h := range got {
		if h != "1" {
			t.Fatalf("a stale reading answered %q; want the reading in hand (1)", h)
		}
	}
	if n := p.calls.Load(); n != 2 {
		t.Fatalf("%d probes for %d callers of an old reading; want 1 new one beside the first", n-1, callers)
	}

	// The probe that went on in the background lands for the next ask.
	p.free()
	deadline := time.Now().Add(5 * time.Second)
	for {
		d.lock.mu.Lock()
		fresh := time.Since(d.lock.at) < lockCache
		d.lock.mu.Unlock()
		if fresh {
			break
		}
		if time.Now().After(deadline) {
			t.Fatal("the background probe never refreshed the reading")
		}
		time.Sleep(10 * time.Millisecond)
	}
}

// The request itself, end to end through the /appd proxy: a desktop request
// that lands while the probe runs is answered without waiting for it.
func TestProxiedRequestDoesNotWaitForTheLockProbe(t *testing.T) {
	d, w, p := claimRig(t)
	var notify atomic.Value
	fake := appdtest.NewServer(t, "core-token", http.HandlerFunc(func(rw http.ResponseWriter, r *http.Request) {
		notify.Store(r.Header.Get("X-Huginn-Notify"))
		rw.Write([]byte(`{"ok":true}`))
	}))
	api, err := appd.New(appd.Config{URL: fake.URL, Tokens: appd.StaticToken("core-token"), Headers: w.headers})
	if err != nil {
		t.Fatal(err)
	}
	t.Cleanup(api.Close)
	d.api = api
	front := httptest.NewServer(http.HandlerFunc(d.proxy))
	t.Cleanup(front.Close)

	get := func() (int, error) {
		res, err := http.Get(front.URL + "/appd/v1/status")
		if err != nil {
			return 0, err
		}
		io.Copy(io.Discard, res.Body)
		res.Body.Close()
		return res.StatusCode, nil
	}
	if code, err := get(); err != nil || code != 200 {
		t.Fatalf("first request: %d %v", code, err)
	}
	ageReading(d.lock)
	type answer struct {
		code int
		err  error
	}
	ch := make(chan answer, 1)
	go func() { c, e := get(); ch <- answer{c, e} }()
	select {
	case a := <-ch:
		if a.err != nil || a.code != 200 {
			t.Fatalf("request while the probe runs: %d %v", a.code, a.err)
		}
	case <-time.After(5 * time.Second):
		// Freed BEFORE failing: the front server's Close waits for this request.
		n := p.calls.Load()
		p.free()
		<-ch
		t.Fatalf("the proxied request waited for the lock probe (%d probes started)", n)
	}
	if v, _ := notify.Load().(string); v != "1" {
		t.Fatalf("appd saw X-Huginn-Notify %q; want the claim from the reading in hand", v)
	}
}

// The act fence is not the claim: the runner's read still waits for a fresh
// reading rather than acting on an old one.
func TestFenceReadStillProbesAnOldReading(t *testing.T) {
	n := 0
	l := &lockReader{probe: func(context.Context) lock.Result {
		n++
		if n == 1 {
			return lock.Result{Reading: lock.Unlocked}
		}
		return lock.Result{Reading: lock.Locked}
	}, canGraphical: func(context.Context) (bool, bool) { return true, true }}
	if got := l.read(context.Background()); got.Locked {
		t.Fatalf("first read %+v", got)
	}
	ageReading(l)
	if got := l.read(context.Background()); !got.Locked || n != 2 {
		t.Fatalf("an old reading was used for the fence: %+v after %d probes", got, n)
	}
}
