//go:build linux && !android

package tray

import (
	"fmt"
	"log"
	"os"
	"path/filepath"
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
}

const (
	fdoName = "org.freedesktop.Notifications"
	fdoPath = "/org/freedesktop/Notifications"
)

func newNotifier(l *log.Logger) Notifier {
	n := &dbusNotifier{log: l, ids: map[string]uint32{}, urls: map[uint32]string{}}
	// The raven, written once where a notification server can read it.
	if dir, err := os.UserCacheDir(); err == nil {
		p := filepath.Join(dir, "huginn", "notify-icon.png")
		if os.MkdirAll(filepath.Dir(p), 0o700) == nil && os.WriteFile(p, iconWorking, 0o644) == nil {
			n.icon = p
		}
	}
	return n
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
				if len(sig.Body) < 1 {
					continue
				}
				id, _ := sig.Body[0].(uint32)
				n.mu.Lock()
				u := n.urls[id]
				n.mu.Unlock()
				if u != "" {
					OpenURL(u)
				}
			}
		}()
	}
	n.conn = c
	return c, nil
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
		"huginn", n.ids[t.Key], n.icon, t.Title, t.Body, actions, hints, timeout)
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
