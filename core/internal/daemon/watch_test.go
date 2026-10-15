package daemon

import (
	"context"
	"strings"
	"testing"

	"github.com/silencelen/huginn/core/internal/lock"
	"github.com/silencelen/huginn/core/internal/notify"
	"github.com/silencelen/huginn/core/internal/traystate"
)

func TestSummarize(t *testing.T) {
	w := notify.Watch{
		Sessions: map[string]string{"b": notify.StateAttention, "a": notify.StateAttention, "c": notify.StateRunning, "d": ""},
		Chats:    map[string]notify.Chat{"x": {Running: true}, "y": {}},
	}
	s := summarize(w)
	if s.State != traystate.Attention || strings.Join(s.Attention, ",") != "a,b" || s.Working != 2 || !s.Connected {
		t.Fatalf("%+v", s)
	}
	if s.Tooltip != "huginn — 2 sessions need you" {
		t.Fatal(s.Tooltip)
	}
	if s := summarize(notify.Watch{Sessions: map[string]string{"c": notify.StateRunning}}); s.State != traystate.Working || s.Tooltip != "huginn — 1 working" {
		t.Fatalf("%+v", s)
	}
	if s := summarize(notify.Watch{}); s.State != traystate.Idle {
		t.Fatalf("%+v", s)
	}
	many := notify.Watch{Sessions: map[string]string{}}
	for _, n := range []string{"a", "b", "c", "d", "e", "f", "g", "h"} {
		many.Sessions[n] = notify.StateAttention
	}
	if s := summarize(many); len(s.Attention) != traystate.MenuCap {
		t.Fatalf("menu not capped: %d", len(s.Attention))
	}
}

func TestRender(t *testing.T) {
	n := render(notify.Decision{Kind: notify.Attention, Session: "main"})
	if n.Action != "post" || !n.Urgent || n.Title != "main needs you" || n.Key != "sess:main" || n.URL != "huginn://open?view=sessions&id=main" {
		t.Fatalf("%+v", n)
	}
	if n := render(notify.Decision{Kind: notify.Withdraw, Key: "sess:main"}); n.Action != "withdraw" || n.Key != "sess:main" {
		t.Fatalf("%+v", n)
	}
	if n := render(notify.Decision{Kind: notify.Finished, ChatID: "c1"}); n.Title != "Chat finished" || n.Body != "huginn has answered" || n.Urgent || n.Key != "chat:c1" {
		t.Fatalf("%+v", n)
	}
	if n := render(notify.Decision{Kind: notify.LimitHit, Session: "s", ResetsAt: "2026-10-15T09:30:00Z"}); !strings.HasPrefix(n.Body, "Resumes at ") {
		t.Fatalf("%+v", n)
	}
	// A session name with URL-special characters is escaped in the open URL.
	if n := render(notify.Decision{Kind: notify.Attention, Session: "a&b"}); n.URL != "huginn://open?view=sessions&id=a%26b" {
		t.Fatal(n.URL)
	}
}

// D14: the core claims notifications only while a tray that can SHOW them is
// attached AND the session is known to be unlocked.
func TestClaim(t *testing.T) {
	for _, c := range []struct {
		trays  int32
		res    lock.Result
		claims bool
		mute   bool // the tray said deliver=0
	}{
		{0, lock.Result{Reading: lock.Unlocked}, false, false},
		{1, lock.Result{Reading: lock.Unlocked}, true, false},
		{1, lock.Result{Reading: lock.Unlocked}, false, true},
		{1, lock.Result{Reading: lock.Locked}, false, false},
		{1, lock.Result{Reading: lock.Unknown, Reason: "probe failed"}, false, false},
	} {
		d := &Daemon{lock: &lockReader{probe: func(context.Context) lock.Result { return c.res }, canGraphical: func(context.Context) (bool, bool) { return true, true }}}
		w := &watcher{d: d}
		w.trays.Store(c.trays)
		if !c.mute {
			w.senders.Store(c.trays)
		}
		if got := w.claim(); got != c.claims {
			t.Errorf("trays=%d reading=%v: claim=%v, want %v", c.trays, c.res.Reading, got, c.claims)
		}
		if h := w.headers()["X-Huginn-Notify"]; (h == "1") != c.claims {
			t.Errorf("header %q disagrees with claim %v", h, c.claims)
		}
	}
}
