//go:build linux && !android

package tray

import (
	"context"
	"encoding/json"
	"io"
	"log"
	"net/http"
	"path/filepath"
	"testing"
	"time"

	"github.com/godbus/dbus/v5"

	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/traystate"
)

// Model output in a body must not become markup: no clickable link that the
// notification server opens, no <img> that makes it read a local file.
func TestBodyTextEscapesMarkup(t *testing.T) {
	in := `done <a href="https://evil/login">re-auth</a> <img src="file:///etc/passwd"> & more`
	want := `done &lt;a href="https://evil/login"&gt;re-auth&lt;/a&gt; &lt;img src="file:///etc/passwd"&gt; &amp; more`
	if got := bodyText(in); got != want {
		t.Fatalf("bodyText:\n got %s\nwant %s", got, want)
	}
}

var quiet = log.New(io.Discard, "", 0)

// fakeCore answers POST /core/gui on a scratch socket the way `core run` does:
// 202 and the command kept while a desktop app is attached, 409 when none is.
func fakeCore(t *testing.T, attached bool) (sock string, got <-chan traystate.GUICmd) {
	t.Helper()
	ch := make(chan traystate.GUICmd, 4)
	s := ipc.New(filepath.Join(t.TempDir(), "run", "core.sock"), quiet)
	s.Mux.HandleFunc("POST /core/gui", func(w http.ResponseWriter, r *http.Request) {
		var c traystate.GUICmd
		if err := json.NewDecoder(r.Body).Decode(&c); err != nil {
			ipc.WriteJSON(w, 400, map[string]string{"error": err.Error()})
			return
		}
		if !attached {
			ipc.WriteJSON(w, 409, map[string]string{"error": "no desktop app is attached"})
			return
		}
		ch <- c
		ipc.WriteJSON(w, 202, map[string]bool{"sent": true})
	})
	if err := s.Listen(); err != nil {
		t.Fatal(err)
	}
	go s.Serve()
	t.Cleanup(func() { s.Shutdown(context.Background()) })
	return s.Path, ch
}

// clickRig is a tray role with the given core and a posted notice: the
// notifier's click hook is the role's openApp, as Run wires it. The OS's
// huginn:// handler is recorded, not run. No session bus: the handler is
// given the ActionInvoked signal a server would have sent.
func clickRig(t *testing.T, attached bool) (n *dbusNotifier, gui <-chan traystate.GUICmd, opened *[]string) {
	t.Helper()
	t.Setenv("XDG_CACHE_HOME", t.TempDir()) // the notifier's icon file stays out of the real cache
	sock, gui := fakeCore(t, attached)
	opened = &[]string{}
	r := &role{o: Options{Socket: sock, Logger: quiet}, openURL: func(u string) { *opened = append(*opened, u) }}
	r.gui.Attached = attached
	n = newNotifier(quiet, r.openApp).(*dbusNotifier)
	return n, gui, opened
}

const clickTarget = "huginn://open?view=sessions&id=main"

func actionInvoked(id uint32, key string) *dbus.Signal {
	return &dbus.Signal{Name: fdoName + ".ActionInvoked", Path: fdoPath, Body: []interface{}{id, key}}
}

// A click on a notification while the desktop app is attached reaches the
// app the way a menu click does: an open command with the notice's target
// through the core, and nothing handed to the OS. (The old code ran xdg-open
// outright, which a web browser answered where huginn:// did not resolve to
// the app.)
func TestNotificationClickWithAppAttachedGoesThroughTheCore(t *testing.T) {
	n, gui, opened := clickRig(t, true)
	n.urls[7] = clickTarget
	n.clicked(actionInvoked(7, "default"))
	select {
	case c := <-gui:
		if c.Cmd != traystate.CmdOpen || c.URL != clickTarget {
			t.Fatalf("gui command %+v, want open %s", c, clickTarget)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("no gui command reached the core")
	}
	if len(*opened) != 0 {
		t.Fatalf("OpenURL ran %v with the app attached", *opened)
	}
}

// With no desktop app attached the click opens the target through the OS,
// once, which starts the app.
func TestNotificationClickWithNoAppOpensTheTargetOnce(t *testing.T) {
	n, gui, opened := clickRig(t, false)
	n.urls[7] = clickTarget
	n.clicked(actionInvoked(7, "default"))
	if len(*opened) != 1 || (*opened)[0] != clickTarget {
		t.Fatalf("OpenURL calls %v, want one with %s", *opened, clickTarget)
	}
	select {
	case c := <-gui:
		t.Fatalf("a gui command %+v with no app attached", c)
	default:
	}
}

// The handler matches by the notification's id, not by the action key (the
// one action a notice posts is "default"): any key on our id is that notice's
// target, as before; an id we did not post, or a body without one, is nothing.
func TestNotificationClickMatchesByIdNotByActionKey(t *testing.T) {
	n, gui, opened := clickRig(t, true)
	n.urls[7] = clickTarget
	n.clicked(actionInvoked(7, "Open"))
	select {
	case c := <-gui:
		if c.Cmd != traystate.CmdOpen || c.URL != clickTarget {
			t.Fatalf("gui command %+v, want open %s", c, clickTarget)
		}
	case <-time.After(5 * time.Second):
		t.Fatal("a named action on our notification did nothing")
	}
	n.clicked(actionInvoked(8, "default")) // somebody else's notification
	n.clicked(&dbus.Signal{Name: fdoName + ".ActionInvoked", Path: fdoPath})
	select {
	case c := <-gui:
		t.Fatalf("a gui command %+v for a notification that is not ours", c)
	case <-time.After(200 * time.Millisecond):
	}
	if len(*opened) != 0 {
		t.Fatalf("OpenURL ran %v", *opened)
	}
}
