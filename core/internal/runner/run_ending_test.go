package runner

import (
	"context"
	"encoding/json"
	"net/http"
	"runtime"
	"slices"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
)

// flakyEnd is appd's device surface for one job, with a link that drops a
// chosen number of the POSTs carrying the run's result, and of its ending,
// with a 503 (a proxy in front of appd restarting, a reset during a route
// switch). Like appd, it loses a run whose device polls again while holding it.
type flakyEnd struct {
	mu                   sync.Mutex
	given, lost          bool
	lines                []string
	done                 *appd.Events
	failResult, failDone int
}

func (f *flakyEnd) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/devices", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(201)
		w.Write([]byte(`{"id":"dev1","name":"box","scope":"look"}`))
	})
	mux.HandleFunc("POST /v1/devices/dev1/beat", func(w http.ResponseWriter, r *http.Request) { w.Write([]byte(`{"ok":true}`)) })
	mux.HandleFunc("GET /v1/devices/dev1/work", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		if !f.given {
			f.given = true
			f.mu.Unlock()
			w.Write([]byte(`{"work":{"id":"w1","chatId":"c1","prompt":"hi","mode":"ask"}}`))
			return
		}
		if f.done == nil {
			f.lost = true // asked for new work while the run was still with it
		}
		f.mu.Unlock()
		time.Sleep(50 * time.Millisecond)
		w.Write([]byte(`{"work":null}`))
	})
	mux.HandleFunc("POST /v1/devices/dev1/work/w1/events", func(w http.ResponseWriter, r *http.Request) {
		var ev appd.Events
		json.NewDecoder(r.Body).Decode(&ev)
		f.mu.Lock()
		defer f.mu.Unlock()
		carriesResult := slices.ContainsFunc(ev.Lines, func(l string) bool { return strings.Contains(l, `"type":"result"`) })
		if (carriesResult && f.failResult > 0) || (ev.Done && f.failDone > 0) {
			if ev.Done {
				f.failDone--
			} else {
				f.failResult--
			}
			w.WriteHeader(503)
			w.Write([]byte(`{"error":"bad gateway"}`))
			return
		}
		f.lines = append(f.lines, ev.Lines...)
		if ev.Done {
			f.done = &ev
		}
		w.Write([]byte(`{"ok":true}`))
	})
	return mux
}

// The child prints its result and exits at once, so the result usually rides
// the run's last POST rather than a mid-run batch. A blip on that POST, or on
// the ending, must be retried as a blip mid-run is: sent once, the answer was
// lost and the run still ended as a clean finish (or, with the ending lost,
// was failed as "the runner restarted" at the next poll).
func TestEndOfRunSurvivesABlipOnItsLastPosts(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("shell fakes")
	}
	f := &flakyEnd{failResult: 2, failDone: 1}
	srv := appdtest.NewServer(t, "t0k3n", f.handler())
	api, err := appd.New(appd.Config{URL: srv.URL, Tokens: appd.StaticToken("t0k3n"), Pins: &appd.MemoryPinStore{}})
	if err != nil {
		t.Fatal(err)
	}
	claude := fakeClaude(t, `echo '{"type":"system","subtype":"init"}'; sleep 0.3; echo '{"type":"result","result":"THE ANSWER"}'`)
	r := New(Hooks{
		API:  api,
		Conf: func() Config { return Config{Enabled: true, ID: "dev1", Name: "box", Scope: "look", Claude: claude} },
		Lock: func(context.Context) Lock { return Lock{Known: true} },
		Logf: t.Logf, Home: t.TempDir(),
	})
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	go r.Serve(ctx)

	deadline := time.Now().Add(15 * time.Second)
	for {
		f.mu.Lock()
		done, lost, lines := f.done, f.lost, slices.Clone(f.lines)
		f.mu.Unlock()
		if lost {
			t.Fatalf("the run was lost: its ending never arrived before the next poll (lines %q)", lines)
		}
		if done != nil {
			if done.Error != "" || done.ExitCode == nil || *done.ExitCode != 0 {
				t.Fatalf("ending %+v", *done)
			}
			if !slices.ContainsFunc(lines, func(l string) bool { return strings.Contains(l, "THE ANSWER") }) {
				t.Fatalf("the run ended without its result: appd holds %q", lines)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatalf("no ending; appd holds %q", lines)
		}
		time.Sleep(20 * time.Millisecond)
	}
}
