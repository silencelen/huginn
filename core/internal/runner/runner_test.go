package runner

import (
	"context"
	"encoding/json"
	"fmt"
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
)

// Shrunk once for the whole package: setting them per test races with the
// previous test's runner goroutine, which is still winding down.
func TestMain(m *testing.M) {
	beatEvery, retryAfter, pollBlipPause, flushEvery = 100*time.Millisecond, 100*time.Millisecond, 20*time.Millisecond, 20*time.Millisecond
	os.Exit(m.Run())
}

// fakeDaemon is just enough of appd's device surface to drive one runner.
type fakeDaemon struct {
	t          *testing.T
	mu         sync.Mutex
	work       []map[string]any // queued work items
	events     []appd.Events    // every POST to …/events, in order
	beats      int
	beatScopes []string
	deletes    int
	enrols     []map[string]any
	// knobs
	eventsStatus int // non-zero: answer every events POST with this status
	cancelAfter  int // >0: ack the Nth events POST with cancel
	deleteStatus int // status for DELETE
	scopeAnswer  string
}

func (d *fakeDaemon) handler() http.Handler {
	mux := http.NewServeMux()
	mux.HandleFunc("POST /v1/devices", func(w http.ResponseWriter, r *http.Request) {
		var e map[string]any
		json.NewDecoder(r.Body).Decode(&e)
		d.mu.Lock()
		d.enrols = append(d.enrols, e)
		sc := e["scope"]
		if d.scopeAnswer != "" {
			sc = d.scopeAnswer
		}
		d.mu.Unlock()
		w.WriteHeader(201)
		json.NewEncoder(w).Encode(map[string]any{"id": "dev1", "name": e["name"], "scope": sc})
	})
	mux.HandleFunc("POST /v1/devices/dev1/beat", func(w http.ResponseWriter, r *http.Request) {
		var b map[string]any
		json.NewDecoder(r.Body).Decode(&b)
		d.mu.Lock()
		d.beats++
		d.beatScopes = append(d.beatScopes, fmt.Sprint(b["scope"]))
		d.mu.Unlock()
		json.NewEncoder(w).Encode(map[string]any{"ok": true})
	})
	mux.HandleFunc("GET /v1/devices/dev1/work", func(w http.ResponseWriter, r *http.Request) {
		d.mu.Lock()
		var item map[string]any
		if len(d.work) > 0 {
			item, d.work = d.work[0], d.work[1:]
		}
		d.mu.Unlock()
		if item == nil {
			time.Sleep(50 * time.Millisecond)
		}
		json.NewEncoder(w).Encode(map[string]any{"work": item})
	})
	mux.HandleFunc("POST /v1/devices/dev1/work/{wid}/events", func(w http.ResponseWriter, r *http.Request) {
		var ev appd.Events
		json.NewDecoder(r.Body).Decode(&ev)
		d.mu.Lock()
		d.events = append(d.events, ev)
		n := len(d.events)
		st, cancelAfter := d.eventsStatus, d.cancelAfter
		d.mu.Unlock()
		if st != 0 && !ev.Done {
			w.WriteHeader(st)
			fmt.Fprint(w, `{"error":"nope"}`)
			return
		}
		json.NewEncoder(w).Encode(map[string]any{"ok": true, "cancel": cancelAfter > 0 && n >= cancelAfter})
	})
	mux.HandleFunc("DELETE /v1/devices/dev1", func(w http.ResponseWriter, r *http.Request) {
		d.mu.Lock()
		d.deletes++
		st := d.deleteStatus
		d.mu.Unlock()
		if st == 0 {
			st = 200
		}
		w.WriteHeader(st)
		fmt.Fprint(w, `{"ok":true}`)
	})
	return mux
}

// terminal waits for the done frame and returns every frame so far.
func (d *fakeDaemon) terminal(t *testing.T) (appd.Events, []appd.Events) {
	t.Helper()
	deadline := time.Now().Add(10 * time.Second)
	for time.Now().Before(deadline) {
		d.mu.Lock()
		evs := append([]appd.Events(nil), d.events...)
		d.mu.Unlock()
		for _, e := range evs {
			if e.Done {
				return e, evs
			}
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("no terminal frame")
	return appd.Events{}, nil
}

// fakeClaude writes a shell script that behaves like `claude -p --output-format
// stream-json`: it prints the given lines (or runs a body) after reading stdin.
func fakeClaude(t *testing.T, body string) string {
	t.Helper()
	p := filepath.Join(t.TempDir(), "claude")
	os.WriteFile(p, []byte("#!/bin/sh\ncat >/dev/null\n"+body+"\n"), 0o755)
	return p
}

type harness struct {
	d    *fakeDaemon
	r    *Runner
	conf Config
	mu   sync.Mutex
	stop context.CancelFunc
}

func run(t *testing.T, d *fakeDaemon, conf Config, lock Lock) *harness {
	t.Helper()
	if runtime.GOOS == "windows" {
		t.Skip("shell fakes")
	}
	srv := httptest.NewServer(d.handler())
	t.Cleanup(srv.Close)
	api, err := appd.New(appd.Config{URL: srv.URL, Tokens: appd.StaticToken("t0k3n"), Pins: &appd.MemoryPinStore{}})
	if err != nil {
		t.Fatal(err)
	}
	h := &harness{d: d, conf: conf}
	h.r = New(Hooks{
		API:      api,
		Conf:     func() Config { h.mu.Lock(); defer h.mu.Unlock(); return h.conf },
		SaveID:   func(id, _ string) { h.mu.Lock(); h.conf.ID = id; h.mu.Unlock() },
		ClearOwe: func() { h.mu.Lock(); h.conf.UnenrolOwed = false; h.mu.Unlock() },
		Lock:     func(context.Context) Lock { return lock },
		Logf:     func(f string, a ...any) { t.Logf(f, a...) },
		Home:     t.TempDir(),
	})
	ctx, cancel := context.WithCancel(context.Background())
	h.stop = cancel
	go h.r.Serve(ctx)
	t.Cleanup(cancel)
	return h
}

func item(mode string) map[string]any {
	return map[string]any{"id": "w1", "chatId": "c1", "prompt": "hi", "mode": mode}
}

func TestActRunDeliversOutputThenEndingAlone(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("act")}}
	claude := fakeClaude(t, `echo '{"type":"system","subtype":"init"}'; echo '{"type":"result","result":"ok"}'`)
	run(t, d, Config{Enabled: true, Name: "box", Scope: "own", Claude: claude}, Lock{Known: true})
	end, all := d.terminal(t)
	if end.Error != "" || end.ExitCode == nil || *end.ExitCode != 0 || len(end.Lines) != 0 {
		t.Fatalf("ending %+v", end)
	}
	var lines []string
	for _, e := range all {
		lines = append(lines, e.Lines...)
	}
	if len(lines) != 2 || !strings.Contains(lines[1], `"result"`) {
		t.Fatalf("lines %q", lines)
	}
	if got := d.enrols[0]; got["actWhileLocked"] != false || got["platform"] != Platform() || got["root"] != nil {
		t.Fatalf("enrolment %v", got)
	}
}

func TestLookScopeRefusesActWithoutSpawning(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("act")}}
	marker := filepath.Join(t.TempDir(), "spawned")
	claude := fakeClaude(t, "touch "+marker)
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: claude}, Lock{Known: true})
	end, _ := d.terminal(t)
	if end.Error == "" {
		t.Fatal("expected a refusal")
	}
	if _, err := os.Stat(marker); err == nil {
		t.Fatal("the engine was spawned for refused work")
	}
}

func TestUnknownLockFencesActButHeadlessDoesNot(t *testing.T) {
	// Unknown (probe failed) → treated as locked → act refused.
	d := &fakeDaemon{t: t, work: []map[string]any{item("act")}}
	run(t, d, Config{Enabled: true, Name: "box", Scope: "own", Claude: fakeClaude(t, "true")}, Lock{Known: false, Locked: false, Why: "probe failed"})
	if end, _ := d.terminal(t); end.Error == "" {
		t.Fatal("unknown lock must fence act")
	}
	// Headless: known unlocked (no screen to lock) → act runs.
	d2 := &fakeDaemon{t: t, work: []map[string]any{item("act")}}
	run(t, d2, Config{Enabled: true, Name: "box", Scope: "own", Claude: fakeClaude(t, `echo '{"type":"result"}'`)}, Lock{Known: true, Why: "no graphical session"})
	if end, _ := d2.terminal(t); end.Error != "" {
		t.Fatalf("headless act refused: %s", end.Error)
	}
}

func TestMissingWorkRootSaysSo(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("act")}}
	run(t, d, Config{Enabled: true, Name: "box", Scope: "work", Root: "/nonexistent/root", Claude: fakeClaude(t, "true")}, Lock{Known: true})
	end, _ := d.terminal(t)
	if !strings.Contains(end.Error, "/nonexistent/root is not a directory") {
		t.Fatalf("got %q", end.Error)
	}
}

func TestMissingEngineNamedWithoutPath(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("ask")}}
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: "/nope/claude"}, Lock{Known: true})
	end, _ := d.terminal(t)
	if !strings.HasPrefix(end.Error, "claude was not found on box") {
		t.Fatalf("got %q", end.Error)
	}
}

func TestNonZeroExitQuotesStderrHead(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("ask")}}
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: fakeClaude(t, "echo 'Error: not logged in' >&2; echo stack >&2; exit 3")}, Lock{Known: true})
	end, _ := d.terminal(t)
	if end.Error != "claude exited 3 on box: Error: not logged in" || *end.ExitCode != 3 {
		t.Fatalf("got %+v", end)
	}
}

func TestHugeLineShrunkNotLost(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("ask")}}
	body := `printf '{"type":"user","c":"%s"}\n' "$(head -c 600000 /dev/zero | tr '\0' 'A')"`
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: fakeClaude(t, body)}, Lock{Known: true})
	_, all := d.terminal(t)
	found := false
	for _, e := range all {
		for _, l := range e.Lines {
			if len(l) > maxLineBytes {
				t.Fatalf("a %d-byte line was sent", len(l))
			}
			if strings.Contains(l, `"type":"user"`) && strings.Contains(l, "too large to send") {
				found = true
			}
		}
	}
	if !found {
		t.Fatal("the shrunk line never arrived")
	}
}

func TestCancelOnAckKillsChild(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("ask")}, cancelAfter: 1}
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: fakeClaude(t, `while true; do echo '{"type":"tick"}'; sleep 0.05; done`)}, Lock{Known: true})
	end, _ := d.terminal(t)
	if end.Error != "cancelled on box" {
		t.Fatalf("got %+v", end)
	}
}

func TestAuthFailureStopsDeliveryButNotTheChild(t *testing.T) {
	d := &fakeDaemon{t: t, work: []map[string]any{item("ask")}, eventsStatus: 401}
	marker := filepath.Join(t.TempDir(), "ran-to-end")
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: fakeClaude(t, `echo '{"a":1}'; sleep 0.3; echo '{"b":2}'; touch `+marker)}, Lock{Known: true})
	end, _ := d.terminal(t)
	if _, err := os.Stat(marker); err != nil {
		t.Fatal("the child was killed on a 401")
	}
	if end.Error != "" {
		t.Fatalf("ending %+v", end)
	}
}

func TestOldDaemonScopeEchoRefusesServing(t *testing.T) {
	d := &fakeDaemon{t: t, scopeAnswer: "look"}
	h := run(t, d, Config{Enabled: true, Name: "box", Scope: "generate", LLM: "/x/shim.js"}, Lock{Known: true})
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		if s := h.r.Status(); strings.Contains(s.Note, `did not recognise scope "generate"`) {
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatalf("status %+v", h.r.Status())
}

func TestUnenrolDebtPaidAnd404Counts(t *testing.T) {
	d := &fakeDaemon{t: t, deleteStatus: 404}
	h := run(t, d, Config{Enabled: false, ID: "dev1", UnenrolOwed: true}, Lock{Known: true})
	deadline := time.Now().Add(3 * time.Second)
	for time.Now().Before(deadline) {
		h.mu.Lock()
		owed := h.conf.UnenrolOwed
		h.mu.Unlock()
		if !owed {
			if d.deletes < 1 {
				t.Fatal("cleared without a DELETE")
			}
			return
		}
		time.Sleep(20 * time.Millisecond)
	}
	t.Fatal("debt never cleared")
}

func TestBeatsCarryLiveScope(t *testing.T) {
	d := &fakeDaemon{t: t}
	h := run(t, d, Config{Enabled: true, Name: "box", Scope: "look"}, Lock{Known: true})
	time.Sleep(300 * time.Millisecond)
	h.mu.Lock()
	h.conf.Scope = "own"
	h.mu.Unlock()
	time.Sleep(300 * time.Millisecond)
	d.mu.Lock()
	defer d.mu.Unlock()
	if d.beats < 2 || d.beatScopes[0] != "look" || d.beatScopes[len(d.beatScopes)-1] != "own" {
		t.Fatalf("beat scopes %v", d.beatScopes)
	}
}

// Ported from the desktop's UnenrolTest ("the five minutes of nothing"): the
// backoff waits in the loop that watches the toggle, so a flat sleep meant
// turning the device back on did nothing for up to five minutes. Kick must cut
// any wait short; an unkicked wait must still wait.
func TestKickCutsBackoff(t *testing.T) {
	r := New(Hooks{Conf: func() Config { return Config{} }})
	start := time.Now()
	go func() { time.Sleep(50 * time.Millisecond); r.Kick() }()
	r.waitOrWake(context.Background(), 5*time.Minute)
	if d := time.Since(start); d > 2*time.Second {
		t.Fatalf("Kick took %s to wake a 5-minute wait", d)
	}
	start = time.Now()
	r.waitOrWake(context.Background(), 150*time.Millisecond)
	if d := time.Since(start); d < 140*time.Millisecond {
		t.Fatalf("an unkicked wait returned after %s", d)
	}
}

func TestHostileModelRefusedWithoutSpawning(t *testing.T) {
	it := item("ask")
	it["model"] = "opus&calc.exe"
	d := &fakeDaemon{t: t, work: []map[string]any{it}}
	marker := filepath.Join(t.TempDir(), "spawned")
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: fakeClaude(t, "touch "+marker)}, Lock{Known: true})
	end, _ := d.terminal(t)
	if !strings.Contains(end.Error, "not a model name it accepts") {
		t.Fatalf("ending %+v", end)
	}
	if _, err := os.Stat(marker); err == nil {
		t.Fatal("the engine was spawned for a hostile model value")
	}
}

func TestBatchEngineRefused(t *testing.T) {
	bat := filepath.Join(t.TempDir(), "claude.cmd")
	os.WriteFile(bat, []byte("@echo off"), 0o755)
	d := &fakeDaemon{t: t, work: []map[string]any{item("ask")}}
	run(t, d, Config{Enabled: true, Name: "box", Scope: "look", Claude: bat}, Lock{Known: true})
	end, _ := d.terminal(t)
	if !strings.Contains(end.Error, ".cmd/.bat shim this machine will not run") {
		t.Fatalf("ending %+v", end)
	}
}
