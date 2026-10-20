package daemon

import (
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/notify"
)

// A tray notice is news at the moment it is decided. The core runs from boot,
// so overnight, with nobody signed in, a chat finishes and appd tells Telegram
// (no tray could show it). The tray that starts at the next sign-in must not
// pop that toast up again hours late, and nor may every reconnect after it.
func TestTrayNoticeIsNotReplayedToATrayThatConnectsLater(t *testing.T) {
	d := &Daemon{hub: ipc.NewHub()}
	d.watch = &watcher{d: d}
	d.watch.keepTray.Store(1)
	mux := http.NewServeMux()
	d.routes(mux)
	srv := httptest.NewServer(mux)
	t.Cleanup(srv.Close)
	chat := func(runs int64) notify.Watch {
		return notify.Watch{Chats: map[string]notify.Chat{"c1": {FinishedRuns: runs, Title: "Fix the build", Snippet: "Done: the build passes."}}}
	}

	d.watch.digest(chat(0)) // seeds
	d.watch.digest(chat(1)) // finished, with no tray attached
	late := subscribe(t, srv, "role=tray&deliver=0")
	if n := late.next("notice", 500*time.Millisecond); n != "" {
		t.Fatalf("a tray that connected after the fact was shown %s", n)
	}

	// A tray that is attached when it happens still gets it, once.
	d.watch.digest(chat(2))
	if n := late.next("notice", 2*time.Second); !strings.Contains(n, `"chat:c1"`) {
		t.Fatalf("the attached tray was not told: %q", n)
	}
	again := subscribe(t, srv, "role=tray&deliver=0")
	if n := again.next("notice", 500*time.Millisecond); n != "" {
		t.Fatalf("a reconnecting tray was shown %s again", n)
	}
}
