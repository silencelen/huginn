//go:build linux && !android

package tray

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
	"strings"
	"sync"

	"github.com/godbus/dbus/v5"

	"github.com/silencelen/huginn/core/internal/traystate"
)

// dbusNotifier speaks org.freedesktop.Notifications on the session bus — mako,
// dunst, quickshell, GNOME and KDE all serve it. A Notice key maps to the
// server's id, so a re-post REPLACES (replaces_id) and a withdraw closes.
type dbusNotifier struct {
	log  *log.Logger
	mu   sync.Mutex
	ids  map[string]uint32 // notice key -> server id
	urls map[uint32]string // server id -> url opened on click
	conn *dbus.Conn
	icon string
	// open gets the clicked notification's URL: the role's openApp, which
	// hands it to the attached desktop app over /core/gui and only opens
	// huginn:// through the OS when no app is here. It used to be OpenURL
	// outright, and xdg-open on a laptop whose huginn:// handler did not
	// resolve to the app sent the click to a web browser (2026-11-07).
	open func(target string)
}

const (
	fdoName = "org.freedesktop.Notifications"
	fdoPath = "/org/freedesktop/Notifications"
)

// newNotifier: open is what a click on a notification does with its URL (see
// dbusNotifier.open); nil means the OS's huginn:// handler, as before.
func newNotifier(l *log.Logger, open func(target string)) Notifier {
	if open == nil {
		open = OpenURL
	}
	n := &dbusNotifier{log: l, ids: map[string]uint32{}, urls: map[uint32]string{}, open: open}
	// The raven, written once where a notification server can read it.
	if dir, err := os.UserCacheDir(); err == nil {
		p := filepath.Join(dir, "huginn", "notify-icon.png")
		if os.MkdirAll(filepath.Dir(p), 0o700) == nil && os.WriteFile(p, iconWorking, 0o644) == nil {
			n.icon = p
		}
	}
	return n
}

// bodyText escapes the body for servers that read it as markup (KDE, dunst,
// mako, GNOME's subset). The body carries model output (a chat's last words):
// unescaped, an <a href> in it is a clickable link in a trusted "huginn"
// notification, opened by the server — around OpenURL's huginn://-only rule —
// and an <img src> makes the server read a local file. The summary (title) is
// plain text by the spec, so it is left as is.
func bodyText(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;").Replace(s)
}

// CanDeliver: a notification server owns the name, or the bus can start one.
func (n *dbusNotifier) CanDeliver() bool {
	n.mu.Lock()
	defer n.mu.Unlock()
	c, err := n.bus()
	if err != nil {
		return false
	}
	var has bool
	if c.BusObject().Call("org.freedesktop.DBus.NameHasOwner", 0, fdoName).Store(&has) == nil && has {
		return true
	}
	var names []string
	if c.BusObject().Call("org.freedesktop.DBus.ListActivatableNames", 0).Store(&names) == nil {
		for _, x := range names {
			if x == fdoName {
				return true
			}
		}
	}
	return false
}

// bus connects lazily: the tray can start before the notification server does.
func (n *dbusNotifier) bus() (*dbus.Conn, error) {
	if n.conn != nil && n.conn.Connected() {
		return n.conn, nil
	}
	c, err := dbus.ConnectSessionBus()
	if err != nil {
		return nil, err
	}
	// Clicks come back as ActionInvoked signals; "default" is the body click.
	if err := c.AddMatchSignal(dbus.WithMatchInterface(fdoName), dbus.WithMatchMember("ActionInvoked")); err == nil {
		ch := make(chan *dbus.Signal, 16)
		c.Signal(ch)
		go func() {
			for sig := range ch {
				n.clicked(sig)
			}
		}()
	}
	n.conn = c
	return c, nil
}

// clicked is one ActionInvoked signal (id, action key). It is matched by the
// notification's id — ours or not — and not by the key: the one action a
// notice posts is "default", the body click, so any key on our id is the
// person asking for that notice's target. The lock is released before open
// runs: openApp waits on the core, and Post must not queue behind a click.
func (n *dbusNotifier) clicked(sig *dbus.Signal) {
	if len(sig.Body) < 1 {
		return
	}
	id, _ := sig.Body[0].(uint32)
	n.mu.Lock()
	u := n.urls[id]
	n.mu.Unlock()
	if u != "" {
		n.open(u)
	}
}

func (n *dbusNotifier) Post(t traystate.Notice) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	c, err := n.bus()
	if err != nil {
		return err
	}
	urgency := byte(1)
	timeout := int32(-1)
	if t.Urgent {
		urgency, timeout = 2, 0 // critical: stays until answered or withdrawn
	}
	hints := map[string]dbus.Variant{
		"urgency":       dbus.MakeVariant(urgency),
		"desktop-entry": dbus.MakeVariant("huginn-desktop-kt"),
	}
	actions := []string{}
	if t.URL != "" {
		actions = []string{"default", "Open"}
	}
	var id uint32
	call := c.Object(fdoName, fdoPath).Call(fdoName+".Notify", 0,
		"huginn", n.ids[t.Key], n.icon, t.Title, bodyText(t.Body), actions, hints, timeout)
	if call.Err != nil {
		return call.Err
	}
	if err := call.Store(&id); err != nil {
		return fmt.Errorf("notify: %w", err)
	}
	n.ids[t.Key] = id
	if t.URL != "" {
		n.urls[id] = t.URL
	}
	return nil
}

func (n *dbusNotifier) Withdraw(key string) error {
	n.mu.Lock()
	defer n.mu.Unlock()
	id, ok := n.ids[key]
	if !ok {
		return nil
	}
	delete(n.ids, key)
	delete(n.urls, id)
	c, err := n.bus()
	if err != nil {
		return err
	}
	return c.Object(fdoName, fdoPath).Call(fdoName+".CloseNotification", 0, id).Err
}
