package daemon

import (
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net/http"
	"net/http/httptest"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"sync"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/lock"
	"github.com/silencelen/huginn/core/internal/store"
)

// devFake is appd's device surface for one machine. Like appd, a poll from a
// device that holds a handed run loses that run (the runner restarted), and a
// deleted device loses its run too.
type devFake struct {
	mu      sync.Mutex
	queue   []string
	handed  map[string]bool
	lines   map[string][]string
	done    map[string]*appd.Events
	lost    []string
	polls   int
	open    int
	maxOpen int
	enrols  int
	deleted bool
}

func (f *devFake) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("GET /v1/status", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, `{"ok":true}`) })
	mux.HandleFunc("GET /v1/watch", func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		w.(http.Flusher).Flush()
		<-r.Context().Done()
	})
	mux.HandleFunc("POST /v1/devices", func(w http.ResponseWriter, r *http.Request) {
		var e map[string]any
		json.NewDecoder(r.Body).Decode(&e)
		f.mu.Lock()
		f.enrols++
		f.deleted = false
		f.mu.Unlock()
		w.WriteHeader(201)
		json.NewEncoder(w).Encode(map[string]any{"id": "dev1", "name": e["name"], "scope": e["scope"]})
	})
	mux.HandleFunc("POST /v1/devices/dev1/beat", func(w http.ResponseWriter, r *http.Request) { io.WriteString(w, `{"ok":true}`) })
	mux.HandleFunc("DELETE /v1/devices/dev1", func(w http.ResponseWriter, r *http.Request) {
		f.mu.Lock()
		f.deleted = true
		for id := range f.handed {
			f.lost = append(f.lost, id+": the device was removed")
			delete(f.handed, id)
		}
		f.mu.Unlock()
		io.WriteString(w, `{"ok":true}`)
	})
	mux.HandleFunc("GET /v1/devices/dev1/work", f.poll)
	mux.HandleFunc("POST /v1/devices/dev1/work/{wid}/events", func(w http.ResponseWriter, r *http.Request) {
		wid := r.PathValue("wid")
		var ev appd.Events
		json.NewDecoder(r.Body).Decode(&ev)
		f.mu.Lock()
		defer f.mu.Unlock()
		if !f.handed[wid] {
			w.WriteHeader(404)
			io.WriteString(w, `{"error":"no such run"}`)
			return
		}
		f.lines[wid] = append(f.lines[wid], ev.Lines...)
		if ev.Done {
			f.done[wid] = &ev
			delete(f.handed, wid)
		}
		io.WriteString(w, `{"ok":true}`)
	})
	return mux
}

// poll is GET /work, held for a short while instead of 25 s.
func (f *devFake) poll(w http.ResponseWriter, r *http.Request) {
	f.mu.Lock()
	f.polls++
	for id := range f.handed {
		f.lost = append(f.lost, id+": the runner restarted mid-run")
		delete(f.handed, id)
	}
	f.open++
	f.maxOpen = max(f.maxOpen, f.open)
	f.mu.Unlock()
	defer func() { f.mu.Lock(); f.open--; f.mu.Unlock() }()
	idle := time.After(300 * time.Millisecond)
	for {
		f.mu.Lock()
		if len(f.queue) > 0 && !f.deleted {
			id := f.queue[0]
			f.queue = f.queue[1:]
			f.handed[id] = true
			f.mu.Unlock()
			fmt.Fprintf(w, `{"work":{"id":%q,"chatId":"c-%s","prompt":"hi","mode":"ask"}}`, id, id)
			return
		}
		f.mu.Unlock()
		select {
		case <-r.Context().Done():
			return
		case <-idle:
			io.WriteString(w, `{"work":null}`)
			return
		case <-time.After(20 * time.Millisecond):
		}
	}
}

func (f *devFake) queueJob(id string) {
	f.mu.Lock()
	f.queue = append(f.queue, id)
	f.mu.Unlock()
}

// restartRig is a paired, enabled core with its real IPC routes, runner and
// appd client, against devFake, with an engine that streams a line every
// quarter second for the seconds it is given in its prompt's place (the job
// id's suffix), then prints its result and leaves a mark.
type restartRig struct {
	d     *Daemon
	f     *devFake
	ipc   *httptest.Server
	url   string
	tok   string
	marks string
}

func newRestartRig(t *testing.T, seconds int) *restartRig {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("shell fakes")
	}
	t.Setenv(appd.TokenEnv, "")
	t.Setenv(appd.PlaintextEnv, "")
	tok := strings.Repeat("r", 64)
	f := &devFake{handed: map[string]bool{}, lines: map[string][]string{}, done: map[string]*appd.Events{}}
	srv := appdtest.NewServer(t, tok, f.handler())
	dir := t.TempDir()
	marks := filepath.Join(dir, "marks")
	engine := filepath.Join(dir, "claude")
	script := fmt.Sprintf("#!/bin/sh\ncat >/dev/null\necho '{\"type\":\"system\",\"subtype\":\"init\"}'\n"+
		"i=0\nwhile [ $i -lt %d ]; do sleep 0.25; echo \"{\\\"type\\\":\\\"tick\\\",\\\"n\\\":$i}\"; i=$((i+1)); done\n"+
		"echo '{\"type\":\"result\",\"result\":\"THE ANSWER\"}'\necho done >> %s\n", seconds*4, marks)
	if err := os.WriteFile(engine, []byte(script), 0o755); err != nil {
		t.Fatal(err)
	}
	paths := store.Paths{Config: filepath.Join(dir, "cfg"), State: filepath.Join(dir, "state"), Runtime: filepath.Join(dir, "run")}
	st, err := store.Open(paths)
	if err != nil {
		t.Fatal(err)
	}
	sec := store.NewFileSecrets(paths.SecretsDir())
	if err := sec.Set(store.SecretToken, tok); err != nil {
		t.Fatal(err)
	}
	if _, _, err := st.Update(func(c *store.Config) error {
		c.Appd.URL = srv.URL
		c.Device = store.Device{Enabled: true, ID: "dev1", Name: "box", Scope: "look", Claude: engine}
		c.Secrets = sec.Backend()
		return nil
	}); err != nil {
		t.Fatal(err)
	}
	ctx, cancel := context.WithCancel(context.Background())
	d := &Daemon{paths: paths, st: st, secrets: sec, hub: ipc.NewHub(), ctx: ctx, restartNow: make(chan struct{}), installID: "core-test",
		logf: t.Logf,
		lock: &lockReader{
			probe:        func(context.Context) lock.Result { return lock.Result{NoSession: true, Reason: "test"} },
			canGraphical: func(context.Context) (bool, bool) { return false, true },
		}}
	d.watch = &watcher{d: d}
	mux := http.NewServeMux()
	d.routes(mux)
	front := httptest.NewServer(mux)
	t.Cleanup(func() {
		cancel()
		d.stopRunner()
		front.Close()
	})
	return &restartRig{d: d, f: f, ipc: front, url: srv.URL, tok: tok, marks: marks}
}

func (g *restartRig) waitFor(t *testing.T, what string, within time.Duration, cond func() bool) {
	t.Helper()
	deadline := time.Now().Add(within)
	for !cond() {
		if time.Now().After(deadline) {
			t.Fatalf("timed out waiting for %s", what)
		}
		time.Sleep(20 * time.Millisecond)
	}
}

// startJob starts the runner, hands it job id and waits until it is running.
func (g *restartRig) startJob(t *testing.T, id string) {
	t.Helper()
	g.d.restartRunner(g.d.ctx)
	g.f.queueJob(id)
	g.waitFor(t, id+" to be running", 10*time.Second, func() bool {
		g.f.mu.Lock()
		n := len(g.f.lines[id])
		g.f.mu.Unlock()
		return n > 0 && g.d.busy()
	})
}

// call sends one IPC request and says how long it took.
func (g *restartRig) call(t *testing.T, method, path, body string) (int, string, time.Duration) {
	t.Helper()
	start := time.Now()
	req, _ := http.NewRequest(method, g.ipc.URL+path, strings.NewReader(body))
	req.Header.Set("Content-Type", "application/json")
	res, err := (&http.Client{Timeout: 30 * time.Second}).Do(req)
	if err != nil {
		t.Fatalf("%s %s: %v", method, path, err)
	}
	b, _ := io.ReadAll(res.Body)
	res.Body.Close()
	return res.StatusCode, string(b), time.Since(start)
}

func (g *restartRig) marked() int {
	b, _ := os.ReadFile(g.marks)
	return strings.Count(string(b), "done")
}

// Two runner restarts that overlap (a retried change, the next address the
// desktop adds while the first is still being applied) must leave ONE runner.
// The second used to find the first's runner already unpublished, start one of
// its own, and be overwritten by the first: a runner nothing could stop kept
// polling as this same device, and each of its polls lost the other's jobs.
func TestOverlappingRunnerRestartsLeaveOneRunner(t *testing.T) {
	g := newRestartRig(t, 2)
	g.startJob(t, "w1")
	var wg sync.WaitGroup
	for range 2 {
		wg.Add(1)
		go func() { defer wg.Done(); g.d.restartRunner(g.d.ctx) }()
		time.Sleep(200 * time.Millisecond)
	}
	wg.Wait()
	g.waitFor(t, "w1 to end", 15*time.Second, func() bool { return !g.d.busy() && g.marked() == 1 })

	g.f.mu.Lock()
	g.f.maxOpen = g.f.open
	g.f.mu.Unlock()
	time.Sleep(time.Second)
	g.f.mu.Lock()
	most := g.f.maxOpen
	g.f.mu.Unlock()
	if most > 1 {
		t.Fatalf("%d long polls open at once for one device: more than one runner serves it", most)
	}

	g.d.stopRunner()
	g.f.mu.Lock()
	before := g.f.polls
	g.f.mu.Unlock()
	time.Sleep(time.Second)
	g.f.mu.Lock()
	after, open := g.f.polls, g.f.open
	g.f.mu.Unlock()
	if after != before || open != 0 {
		t.Fatalf("after the core stopped its runner, %d new polls arrived and %d stay open", after-before, open)
	}
}
