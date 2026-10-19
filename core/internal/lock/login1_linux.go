//go:build linux

package lock

import (
	"context"
	"errors"

	"github.com/godbus/dbus/v5"
)

// login1Sessions lists logind's sessions with the properties the decision
// needs, over the system bus. It does not filter by uid; LinuxDecide does.
func login1Sessions(ctx context.Context) ([]Session, error) {
	conn, err := dbus.ConnectSystemBus(dbus.WithContext(ctx))
	if err != nil {
		return nil, err
	}
	defer conn.Close()
	mgr := conn.Object("org.freedesktop.login1", "/org/freedesktop/login1")
	var rows []struct {
		ID   string
		UID  uint32
		User string
		Seat string
		Path dbus.ObjectPath
	}
	if err := mgr.CallWithContext(ctx, "org.freedesktop.login1.Manager.ListSessions", 0).Store(&rows); err != nil {
		return nil, err
	}
	var out []Session
	for _, r := range rows {
		var props map[string]dbus.Variant
		err := conn.Object("org.freedesktop.login1", r.Path).CallWithContext(ctx,
			"org.freedesktop.DBus.Properties.GetAll", 0, "org.freedesktop.login1.Session").Store(&props)
		if err != nil {
			if dbusErrorName(err) == "org.freedesktop.DBus.Error.UnknownObject" {
				continue // gone between the two calls
			}
			// Anything else (a timeout, logind going down) leaves a session
			// unread, and the one left out could be the locked one.
			return nil, err
		}
		s := Session{ID: r.ID, UID: int(r.UID), User: r.User}
		str := func(k string) string { v, _ := props[k].Value().(string); return v }
		yn := func(k string) string {
			v, ok := props[k].Value().(bool)
			switch {
			case !ok:
				return ""
			case v:
				return "yes"
			}
			return "no"
		}
		s.Type, s.Class, s.State = str("Type"), str("Class"), str("State")
		s.LockedHint, s.Remote = yn("LockedHint"), yn("Remote")
		out = append(out, s)
	}
	return out, nil
}

// dbusErrorName is the D-Bus error name of a failed call, or "".
func dbusErrorName(err error) string {
	var v dbus.Error
	if errors.As(err, &v) {
		return v.Name
	}
	var p *dbus.Error
	if errors.As(err, &p) && p != nil {
		return p.Name
	}
	return ""
}
