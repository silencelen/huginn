package daemon

import (
	"bufio"
	"context"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/ipc"
)

// guiRig is a core with only its routes: the tray's channel to the desktop app
// (owner rule 2026-10-17: one tray icon, one menu, carrying the app's items).
func guiRig(t *testing.T) *httptest.Server {
	t.Helper()
	d := &Daemon{hub: ipc.NewHub()}
	d.watch = &watcher{d: d}
	d.watch.keepTray.Store(1)
	mux := http.NewServeMux()
	d.routes(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	return srv
}

type sse struct {
	cancel context.CancelFunc
	lines  chan [2]string
}

func subscribe(t *testing.T, srv *httptest.Server, query string) *sse {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, srv.URL+"/core/events?"+query, nil)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	s := &sse{cancel: cancel, lines: make(chan [2]string, 64)}
	go func() {
		defer resp.Body.Close()
		sc := bufio.NewScanner(resp.Body)
		ev := ""
		for sc.Scan() {
			l := sc.Text()
			switch {
			case strings.HasPrefix(l, "event: "):
				ev = strings.TrimPrefix(l, "event: ")
			case strings.HasPrefix(l, "data: "):
				s.lines <- [2]string{ev, strings.TrimPrefix(l, "data: ")}
			}
		}
		close(s.lines)
	}()
	t.Cleanup(cancel)
	return s
}

// next waits for an event of that name (skipping others); "" on timeout.
func (s *sse) next(name string, within time.Duration) string {
	deadline := time.After(within)
	for {
		select {
		case l, ok := <-s.lines:
			if !ok {
				return ""
			}
			if l[0] == name {
				return l[1]
			}
		case <-deadline:
			return ""
		}
	}
}

func post(t *testing.T, srv *httptest.Server, path, body string) int {
	t.Helper()
	resp, err := http.Post(srv.URL+path, "application/json", strings.NewReader(body))
	if err != nil {
		t.Fatal(err)
	}
	resp.Body.Close()
	return resp.StatusCode
}

func TestTrayReachesTheAppThroughTheCore(t *testing.T) {
	srv := guiRig(t)
	if c := post(t, srv, "/core/gui", `{"cmd":"quit"}`); c != 409 {
		t.Fatalf("no app attached: got %d, want 409 (the tray then opens huginn:// itself)", c)
	}
	app := subscribe(t, srv, "role=gui")
	if d := app.next("coregui", 2*time.Second); !strings.Contains(d, `"attached":true`) || !strings.Contains(d, `"closeToTray":true`) {
		t.Fatalf("the app attaching is not told: %q", d)
	}
	if c := post(t, srv, "/core/presence", `{"closeToTray":false}`); c != 200 {
		t.Fatalf("presence: %d", c)
	}
	if d := app.next("coregui", 2*time.Second); !strings.Contains(d, `"closeToTray":false`) {
		t.Fatalf("the app's Close to tray did not reach the tray's menu: %q", d)
	}
	if c := post(t, srv, "/core/gui", `{"cmd":"quit"}`); c != 202 {
		t.Fatalf("quit: got %d, want 202", c)
	}
	if d := app.next("gui.cmd", 2*time.Second); !strings.Contains(d, `"cmd":"quit"`) {
		t.Fatalf("the app never got quit: %q", d)
	}
	// Never retained: an app (or tray) that connects later must not get it.
	late := subscribe(t, srv, "role=tray&deliver=0")
	if d := late.next("gui.cmd", 500*time.Millisecond); d != "" {
		t.Fatalf("a later subscriber was replayed a command: %q", d)
	}
	for _, bad := range []string{`{"cmd":"rm"}`, `{"cmd":"closeToTray"}`, `{"cmd":"open","url":"https://example.net"}`} {
		if c := post(t, srv, "/core/gui", bad); c != 400 {
			t.Fatalf("%s: got %d, want 400", bad, c)
		}
	}
	app.cancel()
	deadline := time.Now().Add(2 * time.Second)
	for post(t, srv, "/core/gui", `{"cmd":"open"}`) != 409 {
		if time.Now().After(deadline) {
			t.Fatal("the app left and the core still says it is attached")
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// The desktop app's "Tray icon" switch: off sends the tray one hide, never
// replayed to a tray that starts later (that one is the switch turned on).
func TestTrayHideReachesTheTrayOnce(t *testing.T) {
	srv := guiRig(t)
	if c := post(t, srv, "/core/tray", `{"cmd":"hide"}`); c != 409 {
		t.Fatalf("no tray: got %d, want 409", c)
	}
	tray := subscribe(t, srv, "role=tray&deliver=0")
	deadline := time.Now().Add(2 * time.Second)
	for post(t, srv, "/core/tray", `{"cmd":"hide"}`) != 202 {
		if time.Now().After(deadline) {
			t.Fatal("the tray attached and hide was still refused")
		}
		time.Sleep(20 * time.Millisecond)
	}
	if d := tray.next("tray.cmd", 2*time.Second); !strings.Contains(d, `"hide"`) {
		t.Fatalf("the tray never got hide: %q", d)
	}
	later := subscribe(t, srv, "role=tray&deliver=0")
	if d := later.next("tray.cmd", 500*time.Millisecond); d != "" {
		t.Fatalf("a tray started later was replayed the hide: %q", d)
	}
	if c := post(t, srv, "/core/tray", `{"cmd":"rm"}`); c != 400 {
		t.Fatalf("unknown command: got %d, want 400", c)
	}
}

