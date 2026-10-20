package console

import (
	"context"
	"encoding/json"
	"fmt"
	"net"
	"net/http"
	"path/filepath"
	"strings"
	"sync"
	"testing"

	tea "github.com/charmbracelet/bubbletea"
)

// listCore is a fake core whose chats and archive change between fetches, the
// way appd's do: both lists come newest first, so a chat created on the phone,
// a run that answers, or a session archived elsewhere moves every row below.
type listCore struct {
	mu      sync.Mutex
	chats   []chatRow
	archive []archived
	deleted []string // what the console asked to delete or forget
}

func (c *listCore) serve(t *testing.T) string {
	t.Helper()
	sock := filepath.Join(t.TempDir(), "core.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	js := func(w http.ResponseWriter, v any) {
		w.Header().Set("Content-Type", "application/json")
		json.NewEncoder(w).Encode(v)
	}
	mux := http.NewServeMux()
	mux.HandleFunc("GET /core/status", func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, `{"version":"2.6.2","paired":true,"device":{"status":{"enabled":true}}}`)
	})
	mux.HandleFunc("GET /appd/v1/chats", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		js(w, map[string]any{"chats": c.chats})
	})
	mux.HandleFunc("GET /appd/v1/chats/{id}", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		for _, x := range c.chats {
			if x.ID == r.PathValue("id") {
				js(w, chatDetail{chatRow: x})
				return
			}
		}
		w.WriteHeader(404)
	})
	mux.HandleFunc("DELETE /appd/v1/chats/{id}", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.deleted = append(c.deleted, "chat "+r.PathValue("id"))
		fmt.Fprint(w, `{"ok":true}`)
	})
	mux.HandleFunc("GET /appd/v1/archive", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		js(w, map[string]any{"archives": c.archive})
	})
	mux.HandleFunc("DELETE /appd/v1/archive/{id}", func(w http.ResponseWriter, r *http.Request) {
		c.mu.Lock()
		defer c.mu.Unlock()
		c.deleted = append(c.deleted, "archive "+r.PathValue("id"))
		fmt.Fprint(w, `{"ok":true}`)
	})
	mux.HandleFunc("/appd/", func(w http.ResponseWriter, r *http.Request) { fmt.Fprint(w, `{}`) })
	srv := &http.Server{Handler: mux}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
	return sock
}

func (c *listCore) set(fn func()) {
	c.mu.Lock()
	defer c.mu.Unlock()
	fn()
}

func press(m *model, keys ...string) {
	for _, k := range keys {
		var msg tea.KeyMsg
		switch k {
		case "enter":
			msg = tea.KeyMsg{Type: tea.KeyEnter}
		case "esc":
			msg = tea.KeyMsg{Type: tea.KeyEsc}
		case "down":
			msg = tea.KeyMsg{Type: tea.KeyDown}
		default:
			msg = tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(k)}
		}
		_, cmd := m.Update(msg)
		drain(m, cmd)
	}
}

func listModel(t *testing.T, c *listCore) *model {
	t.Helper()
	m := newModel(context.Background(), Options{Socket: c.serve(t), Proto: "1", Version: "2.6.2", Home: t.TempDir()})
	m.Update(tea.WindowSizeMsg{Width: 100, Height: 30})
	m.Update(m.fetch()())
	return m
}

// confirm answers the open prompt, checking it names want, and returns what
// the console then deleted.
func confirm(t *testing.T, m *model, c *listCore, word, want string) []string {
	t.Helper()
	if m.input == nil {
		t.Fatal("no confirmation opened")
	}
	if !strings.Contains(m.input.label, want) {
		t.Errorf("the confirmation does not name %q: %q", want, m.input.label)
	}
	press(m, append(keys(word), "enter")...)
	c.mu.Lock()
	defer c.mu.Unlock()
	return append([]string(nil), c.deleted...)
}

func TestDeleteTakesTheChatUnderTheCursorAfterTheListMoves(t *testing.T) {
	c := &listCore{chats: []chatRow{
		{ID: "c-a", Title: "Recent"},
		{ID: "c-keep", Title: "Tax notes - KEEP"},
		{ID: "c-old", Title: "Old scratch"},
	}}
	m := listModel(t, c)
	press(m, "2", "down", "down")
	// A chat created on the phone lands on top; the watch refetches.
	c.set(func() { c.chats = append([]chatRow{{ID: "c-new", Title: "From the phone"}}, c.chats...) })
	m.Update(m.fetch()())
	press(m, "x")
	if got := confirm(t, m, c, "delete", `"Old scratch"`); strings.Join(got, ",") != "chat c-old" {
		t.Fatalf("deleted %q, but the cursor was on Old scratch (c-old)", got)
	}
}

// Using a chat moves it to the top of the list while it is open; back on the
// list, the cursor is still on it.
func TestDeleteAfterUsingTheChatTakesThatChat(t *testing.T) {
	c := &listCore{chats: []chatRow{
		{ID: "c-a", Title: "Recent"},
		{ID: "c-keep", Title: "Tax notes - KEEP"},
		{ID: "c-old", Title: "Old scratch"},
	}}
	m := listModel(t, c)
	press(m, "2", "down", "down", "enter")
	if m.scr != scrChat || m.chat.id != "c-old" {
		t.Fatalf("did not open Old scratch: screen %v chat %q", m.scr, m.chat.id)
	}
	// The last message and its answer make it the newest chat.
	c.set(func() { c.chats = []chatRow{c.chats[2], c.chats[0], c.chats[1]} })
	m.Update(m.fetch()())
	press(m, "esc", "x")
	if got := confirm(t, m, c, "delete", `"Old scratch"`); strings.Join(got, ",") != "chat c-old" {
		t.Fatalf("deleted %q, but the chat just used was Old scratch (c-old)", got)
	}
}

func TestForgetTakesTheArchivedConversationUnderTheCursor(t *testing.T) {
	c := &listCore{archive: []archived{
		{ID: "a-1", Title: "Last week's migration"},
		{ID: "a-keep", Title: "Router migration - KEEP"},
		{ID: "a-old", Title: "Old scratch session"},
	}}
	m := listModel(t, c)
	press(m, "7", "down", "down")
	// A session archived elsewhere lands on top.
	c.set(func() { c.archive = append([]archived{{ID: "a-new", Title: "Just archived"}}, c.archive...) })
	m.Update(m.fetch()())
	press(m, "x")
	if got := confirm(t, m, c, "forget", `"Old scratch session"`); strings.Join(got, ",") != "archive a-old" {
		t.Fatalf("forgot %q, but the cursor was on Old scratch session (a-old)", got)
	}
}

// A row that is gone leaves the cursor where it was, on a row that the
// confirmation then names.
func TestCursorStaysPutWhenItsRowIsGone(t *testing.T) {
	c := &listCore{chats: []chatRow{{ID: "c-a", Title: "One"}, {ID: "c-b", Title: "Two"}, {ID: "c-c", Title: ""}}}
	m := listModel(t, c)
	press(m, "2", "down")
	c.set(func() { c.chats = []chatRow{c.chats[0], c.chats[2]} })
	m.Update(m.fetch()())
	press(m, "x")
	if got := confirm(t, m, c, "delete", "(untitled)"); strings.Join(got, ",") != "chat c-c" {
		t.Fatalf("deleted %q", got)
	}
}
