package daemon

import (
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/lock"
	"github.com/silencelen/huginn/core/internal/notify"
)

// The desktop app takes its focus with it when it goes. Only the app reports
// focus, and the core keeps quiet about the focused target, which CONSUMES
// that alert rather than deferring it; a report outliving its window (the app
// quit, crashed or was killed, so no last report was sent) silenced that
// session's "needs you" until focusTTL ran out.
func TestFocusGoesWithTheApp(t *testing.T) {
	d := &Daemon{hub: ipc.NewHub(), lock: &lockReader{
		probe:        func(context.Context) lock.Result { return lock.Result{Reading: lock.Unlocked} },
		canGraphical: func(context.Context) (bool, bool) { return true, true },
	}}
	d.watch = &watcher{d: d}
	mux := http.NewServeMux()
	d.routes(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)

	tray := subscribe(t, srv, "role=tray")
	app := subscribe(t, srv, "role=gui")
	d.watch.digest(notify.Watch{Sessions: map[string]string{"x": notify.StateRunning}})
	if c := post(t, srv, "/core/presence", `{"focused":"sess:x","closeToTray":true}`); c != 200 {
		t.Fatalf("presence: %d", c)
	}

	app.cancel() // quit: the events stream closes, and that is all the core hears
	deadline := time.Now().Add(2 * time.Second)
	for d.watch.guis.Load() != 0 {
		if time.Now().After(deadline) {
			t.Fatal("the app left and the core still counts it")
		}
		time.Sleep(10 * time.Millisecond)
	}

	d.watch.digest(notify.Watch{Sessions: map[string]string{"x": notify.StateAttention}})
	if n := tray.next("notice", 2*time.Second); !strings.Contains(n, `"key":"sess:x"`) {
		t.Fatalf("x's needs-you was kept quiet for a window that no longer exists: %q", n)
	}
	if f := d.watch.focus(); f != "" {
		t.Fatalf("the core still holds the focus of a window that is gone: %q", f)
	}
}
