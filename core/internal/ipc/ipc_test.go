package ipc

import (
	"bufio"
	"context"
	"io"
	"log"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"
)

func start(t *testing.T) (*Server, *Hub, *http.Client) {
	t.Helper()
	dir := t.TempDir()
	s := New(filepath.Join(dir, "run", "core.sock"), log.New(io.Discard, "", 0))
	hub := NewHub()
	s.Mux.Handle("GET /core/events", hub)
	s.Mux.HandleFunc("GET /core/status", func(w http.ResponseWriter, r *http.Request) {
		WriteJSON(w, 200, map[string]string{"peer": Peer(r)})
	})
	if err := s.Listen(); err != nil {
		t.Fatal(err)
	}
	go s.Serve()
	t.Cleanup(func() { s.Shutdown(context.Background()) })
	return s, hub, Client(s.Path)
}

func TestModesAndPeer(t *testing.T) {
	s, _, c := start(t)
	for p, want := range map[string]os.FileMode{filepath.Dir(s.Path): 0o700, s.Path: 0o600} {
		fi, err := os.Stat(p)
		if err != nil {
			t.Fatal(err)
		}
		if fi.Mode().Perm() != want {
			t.Errorf("%s mode %o, want %o", p, fi.Mode().Perm(), want)
		}
	}
	resp, err := Do(context.Background(), c, "GET", "/core/status", nil)
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != 200 || !strings.Contains(string(b), "uid=") {
		t.Fatalf("status %d body %s", resp.StatusCode, b)
	}
}

func TestProtoGate(t *testing.T) {
	_, _, c := start(t)
	resp, err := c.Get("http://huginn-core/core/status") // no header
	if err != nil {
		t.Fatal(err)
	}
	b, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if resp.StatusCode != http.StatusUpgradeRequired || !strings.Contains(string(b), "huginn update") {
		t.Fatalf("got %d %s", resp.StatusCode, b)
	}
}

func TestSecondCoreRefused(t *testing.T) {
	s, _, _ := start(t)
	other := New(s.Path, log.New(io.Discard, "", 0))
	if err := other.Listen(); err != ErrInUse {
		t.Fatalf("second Listen = %v, want ErrInUse", err)
	}
}

func TestStaleSocketReplaced(t *testing.T) {
	dir := t.TempDir()
	p := filepath.Join(dir, "core.sock")
	a := New(p, log.New(io.Discard, "", 0))
	if err := a.Listen(); err != nil {
		t.Fatal(err)
	}
	a.ln.(*peerCheckedListener).Listener.(interface{ SetUnlinkOnClose(bool) }).SetUnlinkOnClose(false)
	a.ln.Close() // dead listener, socket file left behind
	b := New(p, log.New(io.Discard, "", 0))
	if err := b.Listen(); err != nil {
		t.Fatalf("Listen over a stale socket: %v", err)
	}
	b.ln.Close()
}

func TestHubSnapshotThenLive(t *testing.T) {
	_, hub, c := start(t)
	hub.Publish(Event{Name: "device", Data: map[string]string{"state": "enrolled"}})
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := Do(ctx, c, "GET", "/core/events", nil)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	rd := bufio.NewReader(resp.Body)
	next := func() string {
		for {
			line, err := rd.ReadString('\n')
			if err != nil {
				t.Fatal(err)
			}
			if strings.HasPrefix(line, "data: ") {
				return strings.TrimSpace(line[6:])
			}
		}
	}
	if got := next(); got != `{"state":"enrolled"}` {
		t.Fatalf("snapshot %q", got)
	}
	hub.Publish(Event{Name: "device", Data: map[string]string{"state": "busy"}})
	if got := next(); got != `{"state":"busy"}` {
		t.Fatalf("live %q", got)
	}
}

func TestHubDropsSlowSubscriber(t *testing.T) {
	hub := NewHub()
	ch, _ := hub.subscribe()
	for i := 0; i < 100; i++ { // never read: must not block the publisher
		hub.Publish(Event{Name: "x", Data: i})
	}
	n := 0
	for range ch {
		n++
	}
	if n != 64 {
		t.Fatalf("delivered %d before drop, want the buffer (64)", n)
	}
}
