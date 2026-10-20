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

// runCore is a fake core with one chat whose run is answering. Like appd's
// Run.emit, an event goes to every stream open on the run, and a message sent
// while it answers is queued (202 queued).
type runCore struct {
	mu     sync.Mutex
	subs   map[chan string]bool
	opened int // streams opened in all
}

func (c *runCore) serve(t *testing.T) string {
	t.Helper()
	c.subs = map[chan string]bool{}
	sock := filepath.Join(t.TempDir(), "core.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /core/status", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"version":"2.6.2","paired":true,"device":{"status":{"enabled":true}}}`)
	})
	mux.HandleFunc("GET /appd/v1/chats/{id}", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"id":"c1","title":"Restart nginx","mode":"act","running":true,"seq":2,"partialText":"Checking nginx. ",
			"messages":[{"type":"user","text":"restart nginx"}]}`)
	})
	mux.HandleFunc("POST /appd/v1/chats/{id}/messages", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(202)
		fmt.Fprint(w, `{"ok":true,"queued":true,"position":1}`)
	})
	mux.HandleFunc("GET /appd/v1/chats/{id}/stream", func(w http.ResponseWriter, r *http.Request) {
		ch := make(chan string, 16)
		c.mu.Lock()
		c.subs[ch] = true
		c.opened++
		c.mu.Unlock()
		defer func() {
			c.mu.Lock()
			delete(c.subs, ch)
			c.mu.Unlock()
		}()
		w.Header().Set("Content-Type", "text/event-stream")
		w.WriteHeader(200)
		fl := w.(http.Flusher)
		fl.Flush()
		for {
			select {
			case f := <-ch:
				fmt.Fprint(w, f)
				fl.Flush()
			case <-r.Context().Done():
				return
			}
		}
	})
	mux.HandleFunc("/appd/", func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, `{}`) })
	srv := &http.Server{Handler: mux}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
	return sock
}

// emit sends one event to every stream open on the run.
func (c *runCore) emit(event, data string) {
	c.mu.Lock()
	defer c.mu.Unlock()
	for ch := range c.subs {
		ch <- "event: " + event + "\ndata: " + data + "\n\n"
	}
}

func (c *runCore) open() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return len(c.subs)
}

func (c *runCore) openedInAll() int {
	c.mu.Lock()
	defer c.mu.Unlock()
	return c.opened
}

// pumpUntil hands the console what its follows deliver, as its Update loop
// would, until ok() holds or d has passed.
func pumpUntil(m *model, d time.Duration, ok func() bool) bool {
	for deadline := time.Now().Add(d); !ok(); {
		if time.Now().After(deadline) {
			return false
		}
		select {
		case msg := <-m.events:
			if ev, isChat := msg.(chatEvent); isChat {
				drain(m, m.onChatEvent(ev))
			}
		case <-time.After(10 * time.Millisecond):
		}
	}
	return true
}

func runModel(t *testing.T, c *runCore) *model {
	t.Helper()
	m := newModel(context.Background(), Options{Socket: c.serve(t), Proto: "1", Version: "2.6.2", Home: t.TempDir()})
	m.Update(tea.WindowSizeMsg{Width: 100, Height: 30})
	m.Update(m.fetch()())
	return m
}

// copiesOf emits one token on the run once the newest follow (the opened-th
// stream) is on it, and counts how often the open chat shows it.
func copiesOf(t *testing.T, m *model, c *runCore, opened int) int {
	t.Helper()
	// Every follow but that one has had time to close.
	pumpUntil(m, 2*time.Second, func() bool { return c.openedInAll() >= opened && c.open() == 1 })
	c.emit("delta", `{"text":"Reloading nginx: ok."}`)
	pumpUntil(m, 300*time.Millisecond, func() bool { return false }) // every copy that is coming
	return strings.Count(m.chat.live.String(), "Reloading nginx: ok.")
}

// Sending while the run answers: appd queues the message, the console reloads
// the chat and follows the run again, and the answer still reads once.
func TestSendWhileARunAnswersShowsEachTokenOnce(t *testing.T) {
	c := &runCore{}
	m := runModel(t, c)
	drain(m, m.openChat("c1"))
	if !pumpUntil(m, 2*time.Second, func() bool { return c.open() == 1 }) {
		t.Fatal("opening a chat whose run is answering did not follow it")
	}
	press(m, append(append([]string{"i"}, keys("and check php-fpm")...), "enter")...)
	if !pumpUntil(m, 3*time.Second, func() bool { return c.openedInAll() >= 2 }) {
		t.Fatal("the queued send did not reload the chat and follow the run")
	}
	if n := copiesOf(t, m, c, 2); n != 1 {
		t.Fatalf("the answer shows a token %d times (%d streams open): %q", n, c.open(), m.chat.live.String())
	}
}

// Leaving a chat stops its follow for good: a late event from it neither
// reloads the chat nor starts another follow behind the list, so opening the
// chat again shows the answer once.
func TestReopeningARunningChatShowsEachTokenOnce(t *testing.T) {
	c := &runCore{}
	m := runModel(t, c)
	for i := 0; i < 5; i++ {
		drain(m, m.openChat("c1"))
		if !pumpUntil(m, 2*time.Second, func() bool { return c.openedInAll() > i }) {
			t.Fatalf("visit %d: the chat was not followed", i+1)
		}
		press(m, "esc")
		pumpUntil(m, 100*time.Millisecond, func() bool { return false })
	}
	drain(m, m.openChat("c1"))
	if n := copiesOf(t, m, c, 6); n != 1 {
		t.Fatalf("the answer shows a token %d times (%d streams open): %q", n, c.open(), m.chat.live.String())
	}
}
