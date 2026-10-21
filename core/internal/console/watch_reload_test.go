package console

import (
	"context"
	"fmt"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
)

// What an open console asks for when appd's watch says something changed. A
// busy host changes every few seconds, and every change used to reload all
// nine sources, /v1/status among them (it runs df and tmux on the host each
// time); a change while a reload was on its way started another one.

type countingCore struct {
	mu   sync.Mutex
	hits map[string]int
	sock string
}

func (c *countingCore) count(path string) int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.hits[path]
}

func (c *countingCore) reset() {
	c.mu.Lock()
	c.hits = map[string]int{}
	c.mu.Unlock()
}

func newCountingCore(t *testing.T) *countingCore {
	t.Helper()
	c := &countingCore{hits: map[string]int{}, sock: filepath.Join(t.TempDir(), "core.sock")}
	ln, err := net.Listen("unix", c.sock)
	if err != nil {
		t.Fatal(err)
	}
	statusCalls := 0
	bodies := map[string]string{
		"/core/status":      `{"version":"2.6.3","paired":true,"url":"https://huginn.example.net:8787","tray":true,"device":{"status":{"note":"Waiting for work"}}}`,
		"/appd/v1/sessions": `{"sessions":[{"name":"main","state":"running","title":"Inbox triage"}]}`,
		"/appd/v1/devices":  `{"devices":[]}`,
		"/appd/v1/headroom": `{"mode":"auto","accounts":{}}`,
		"/appd/v1/chats":    `{"chats":[]}`,
		"/appd/v1/projects": `{"projects":[]}`,
		"/appd/v1/rounds":   `{"rounds":[]}`,
		"/appd/v1/archive":  `{"archives":[]}`,
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		c.hits[r.URL.Path]++
		if r.URL.Path == "/appd/v1/status" {
			statusCalls++
		}
		n := statusCalls
		c.mu.Unlock()
		w.Header().Set("Content-Type", "application/json")
		if r.URL.Path == "/appd/v1/status" {
			fmt.Fprintf(w, `{"host":"huginn-%d","appdVersion":"3.13.4","uptimeSec":60,"load":[0.5]}`, n)
			return
		}
		if b, ok := bodies[r.URL.Path]; ok {
			fmt.Fprint(w, b)
			return
		}
		w.WriteHeader(http.StatusNotFound)
		fmt.Fprint(w, `{"error":"no such route"}`)
	})}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
	return c
}

// loadedConsole is a console over core with its first full load landed. Its
// context is already done, so a pending m.next() returns at once instead of
// waiting for the (unstarted) watch follow.
func loadedConsole(t *testing.T, core *countingCore) *model {
	t.Helper()
	ctx, cancel := context.WithCancel(context.Background())
	cancel()
	m := newModel(ctx, Options{Socket: core.sock, Proto: "1", Version: "2.6.3", Home: t.TempDir()})
	m.Update(tea.WindowSizeMsg{Width: 100, Height: 30})
	m.Update(m.fetch()())
	if !m.loaded || m.data.st.Host == "" {
		t.Fatalf("the first load did not land: %+v", m.data.st)
	}
	return m
}

// settle runs commands, and the commands their messages return, to the end.
// One that has not answered in 3 s is a timer (the 30 s tick), not a load,
// and is dropped.
func settle(t *testing.T, m *model, cmds ...tea.Cmd) {
	t.Helper()
	for len(cmds) > 0 {
		cmd := cmds[0]
		cmds = cmds[1:]
		if cmd == nil {
			continue
		}
		got := make(chan tea.Msg, 1)
		go func() { got <- cmd() }()
		var msg tea.Msg
		select {
		case msg = <-got:
		case <-time.After(3 * time.Second):
			continue
		}
		switch msg := msg.(type) {
		case nil:
		case tea.BatchMsg:
			cmds = append(cmds, msg...)
		default:
			_, next := m.Update(msg)
			cmds = append(cmds, next)
		}
	}
}

func TestAWatchChangeDoesNotReloadTheStatuses(t *testing.T) {
	core := newCountingCore(t)
	m := loadedConsole(t, core)
	core.reset()
	for i := 0; i < 3; i++ {
		_, cmd := m.Update(changed{})
		settle(t, m, cmd)
	}
	if n := core.count("/appd/v1/sessions"); n != 3 {
		t.Fatalf("3 changes reloaded the sessions %d times; want 3", n)
	}
	if n, c := core.count("/appd/v1/status"), core.count("/core/status"); n != 0 || c != 0 {
		t.Fatalf("3 watch changes asked for /v1/status %d times and /core/status %d times; want neither", n, c)
	}
	for _, p := range []string{"/appd/v1/chats", "/appd/v1/projects", "/appd/v1/rounds", "/appd/v1/archive", "/appd/v1/devices", "/appd/v1/headroom"} {
		if n := core.count(p); n != 3 {
			t.Errorf("3 changes reloaded %s %d times; want 3 (still live)", p, n)
		}
	}
	if !strings.HasPrefix(m.data.st.Host, "huginn-") || m.data.core.Version == "" {
		t.Fatalf("a watch reload lost the statuses of the last full load: %+v / %+v", m.data.st, m.data.core)
	}
}

func TestWatchChangesDuringAReloadAreOwedOneReload(t *testing.T) {
	core := newCountingCore(t)
	m := loadedConsole(t, core)
	core.reset()
	// Three changes before the first reload has answered.
	var cmds []tea.Cmd
	for i := 0; i < 3; i++ {
		_, cmd := m.Update(changed{})
		cmds = append(cmds, cmd)
	}
	settle(t, m, cmds...)
	if n := core.count("/appd/v1/sessions"); n != 2 {
		t.Fatalf("3 changes, 2 of them while a reload was on its way, reloaded %d times; want 2 (that one and one after it)", n)
	}
}

// The statuses are not forgotten: the minute tick still reloads them while
// changes keep coming, and r reloads everything.
func TestTheStatusesStillReloadOnTheMinute(t *testing.T) {
	core := newCountingCore(t)
	m := loadedConsole(t, core)
	_, cmd := m.Update(changed{})
	settle(t, m, cmd)
	core.reset()
	m.fetchAt = time.Now().Add(-2 * time.Minute)
	_, cmd = m.Update(tick{})
	settle(t, m, cmd)
	if n := core.count("/appd/v1/status"); n != 1 {
		t.Fatalf("the minute tick asked for /v1/status %d times; want 1", n)
	}
	if m.data.st.Host != "huginn-2" {
		t.Fatalf("host %q after the tick's reload; want huginn-2", m.data.st.Host)
	}
	core.reset()
	_, cmd = m.Update(tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune("r")})
	settle(t, m, cmd)
	if n := core.count("/appd/v1/status"); n != 1 {
		t.Fatalf("r asked for /v1/status %d times; want 1", n)
	}
}
