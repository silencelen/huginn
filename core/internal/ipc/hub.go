package ipc

import (
	"encoding/json"
	"fmt"
	"net/http"
	"sync"
	"time"
)

// Event is one core state change pushed to subscribers of /core/events.
type Event struct {
	Name string
	Data any
}

// Hub fans core events out to SSE subscribers (the tray role and the GUI's
// settings pages). A subscriber that stops reading is dropped rather than
// allowed to hold the publisher: the runner must never block on a slow window.
type Hub struct {
	mu   sync.Mutex
	subs map[chan Event]struct{}
	last map[string]Event // newest of each name, replayed to a new subscriber
}

// NewHub returns an empty hub.
func NewHub() *Hub { return &Hub{subs: map[chan Event]struct{}{}, last: map[string]Event{}} }

// Publish records ev as the newest of its name and sends it to every
// subscriber that has room; a full subscriber is disconnected.
func (h *Hub) Publish(ev Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	h.last[ev.Name] = ev
	for ch := range h.subs {
		select {
		case ch <- ev:
		default:
			delete(h.subs, ch)
			close(ch)
		}
	}
}

// Send is Publish without the record: subscribers connected now get ev, and
// one connecting later never sees it. For commands, which must happen once.
func (h *Hub) Send(ev Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	for ch := range h.subs {
		select {
		case ch <- ev:
		default:
			delete(h.subs, ch)
			close(ch)
		}
	}
}

func (h *Hub) subscribe() (chan Event, []Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	ch := make(chan Event, 64)
	h.subs[ch] = struct{}{}
	snap := make([]Event, 0, len(h.last))
	for _, ev := range h.last {
		snap = append(snap, ev)
	}
	return ch, snap
}

func (h *Hub) unsubscribe(ch chan Event) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if _, ok := h.subs[ch]; ok {
		delete(h.subs, ch)
		close(ch)
	}
}

// keepalive is shorter than any idle timeout between here and the GUI.
const keepalive = 25 * time.Second

// ServeHTTP streams events as SSE: the newest of each name first (so a fresh
// subscriber needs no separate GET), then live ones, with a `:` keepalive.
func (h *Hub) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "streaming unsupported", http.StatusInternalServerError)
		return
	}
	ch, snap := h.subscribe()
	defer h.unsubscribe(ch)
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)
	for _, ev := range snap {
		writeEvent(w, ev)
	}
	fl.Flush()
	tick := time.NewTicker(keepalive)
	defer tick.Stop()
	for {
		select {
		case <-r.Context().Done():
			return
		case ev, ok := <-ch:
			if !ok {
				return // dropped as too slow; the client reconnects and gets a fresh snapshot
			}
			writeEvent(w, ev)
			fl.Flush()
		case <-tick.C:
			fmt.Fprint(w, ":\n\n")
			fl.Flush()
		}
	}
}

func writeEvent(w http.ResponseWriter, ev Event) {
	b, err := json.Marshal(ev.Data)
	if err != nil {
		b = []byte(`{"error":"unencodable event"}`)
	}
	fmt.Fprintf(w, "event: %s\ndata: %s\n\n", ev.Name, b)
}
