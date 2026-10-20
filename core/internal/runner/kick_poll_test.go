package runner

import (
	"context"
	"encoding/json"
	"net/http"
	"runtime"
	"slices"
	"sync"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
)

// kickDaemon parks the runner's first long poll until the test dispatches a
// job into it, and notes whether the runner cut it instead.
type kickDaemon struct {
	parked   chan struct{}
	dispatch chan struct{}

	mu         sync.Mutex
	polls      int
	cut, lost  bool
	handed     bool
	done       *appd.Events
	beatScopes []string
	deleted    bool
}

func (f *kickDaemon) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/devices", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(201)
		w.Write([]byte(`{"id":"dev1","name":"box","scope":"look"}`))
	})
	mux.HandleFunc("POST /v1/devices/dev1/beat", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		json.NewDecoder(r.Body).Decode(&b)
		f.mu.Lock()
		s, _ := b["scope"].(string)
		f.beatScopes = append(f.beatScopes, s)
		f.mu.Unlock()
		w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("DELETE /v1/devices/dev1", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.deleted = true
		f.mu.Unlock()
		w.Write([]byte(`{"ok":true}`))
	})
	mux.HandleFunc("GET /v1/devices/dev1/work", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		n := f.polls
		f.polls++
		if f.handed && f.done == nil {
			f.lost = true // appd: a poll while a run is handed loses that run
		}
		f.mu.Unlock()
		if n == 0 {
			f.parked <- struct{}{}
			select {
			case <-r.Context().Done():
				f.mu.Lock()
				f.cut = true
				f.mu.Unlock()
			case <-f.dispatch:
				f.mu.Lock()
				f.handed = true
				f.mu.Unlock()
				w.Write([]byte(`{"work":{"id":"w1","chatId":"c1","prompt":"hi","mode":"ask"}}`))
			}
			return
		}
		select {
		case <-r.Context().Done():
		case <-time.After(50 * time.Millisecond):
		}
		w.Write([]byte(`{"work":null}`))
	})
	mux.HandleFunc("POST /v1/devices/dev1/work/w1/events", func(w http.ResponseWriter, r *http.Request) {
		var ev appd.Events
		json.NewDecoder(r.Body).Decode(&ev)
		f.mu.Lock()
		if ev.Done {
			f.done = &ev
		}
		f.mu.Unlock()
		w.Write([]byte(`{"ok":true}`))
	})
	return mux
}

func kickRig(t *testing.T, conf Config) (*kickDaemon, *Runner, func(func(*Config))) {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("shell fakes")
	}
	f := &kickDaemon{parked: make(chan struct{}, 1), dispatch: make(chan struct{})}
	srv := appdtest.NewServer(t, "t0k3n", f.handler())
	api, err := appd.New(appd.Config{URL: srv.URL, Tokens: appd.StaticToken("t0k3n"), Pins: &appd.MemoryPinStore{}})
	if err != nil {
		t.Fatal(err)
	}
	var mu sync.Mutex
	r := New(Hooks{
		API:      api,
		Conf:     func() Config { mu.Lock(); defer mu.Unlock(); return conf },
		ClearOwe: func() { mu.Lock(); conf.UnenrolOwed, conf.ID = false, ""; mu.Unlock() },
		Lock:     func(context.Context) Lock { return Lock{Known: true} },
		Logf:     t.Logf, Home: t.TempDir(),
	})
	ctx, cancel := context.WithCancel(context.Background())
	t.Cleanup(cancel)
	go r.Serve(ctx)
	select {
	case <-f.parked:
	case <-time.After(10 * time.Second):
		t.Fatal("the runner never polled")
	}
	return f, r, func(edit func(*Config)) { mu.Lock(); edit(&conf); mu.Unlock() }
}

func waitFor(t *testing.T, what string, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// A settings change that keeps the machine on (Settings sends one per
// keystroke in "Folder for Work runs") must leave the long poll open: a cut
// poll may be the one appd is answering with work at that instant, and the
// runner's next poll then made appd fail the chat as "the runner restarted".
// The change still reaches appd at once, on a beat.
func TestSettingsChangeLeavesTheOpenPollAlone(t *testing.T) {
	f, r, edit := kickRig(t, Config{Enabled: true, ID: "dev1", Name: "box", Scope: "look",
		Claude: fakeClaude(t, `echo '{"type":"result","result":"ok"}'`)})
	edit(func(c *Config) { c.Scope, c.Root = "own", t.TempDir() })
	r.Kick()
	waitFor(t, "a beat with the new scope", func() bool {
		f.mu.Lock()
		defer f.mu.Unlock()
		return slices.Contains(f.beatScopes, "own")
	})
	time.Sleep(100 * time.Millisecond) // a cut would have reached appd by now
	f.mu.Lock()
	cut := f.cut
	f.mu.Unlock()
	if cut {
		t.Fatal("a settings change that keeps the machine on cut the long poll")
	}
	close(f.dispatch) // appd answers the open poll with a job
	waitFor(t, "the job's ending", func() bool { f.mu.Lock(); defer f.mu.Unlock(); return f.done != nil || f.lost })
	f.mu.Lock()
	defer f.mu.Unlock()
	if f.lost || f.done.Error != "" {
		t.Fatalf("lost %v, ending %+v", f.lost, f.done)
	}
}

// Turning the machine off still takes effect at once: the poll is cut, none
// follows, and the row is retired.
func TestTurningOffStillCutsThePoll(t *testing.T) {
	f, r, edit := kickRig(t, Config{Enabled: true, ID: "dev1", Name: "box", Scope: "look"})
	edit(func(c *Config) { c.Enabled, c.UnenrolOwed = false, true })
	r.Kick()
	waitFor(t, "the poll to be cut and the row retired", func() bool {
		f.mu.Lock()
		defer f.mu.Unlock()
		return f.cut && f.deleted
	})
}
