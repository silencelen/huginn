//go:build linux

package store

import (
	"context"
	"errors"
	"fmt"
	"os"
	"time"

	"github.com/godbus/dbus/v5"
)

// Secret Service (org.freedesktop.secrets) over the user's session bus.
//
// ⚠ "plain" session algorithm: the secret crosses the user's own session bus
// unencrypted, which only processes of this uid can connect to. The DH
// algorithm protects against nothing that bus does not already exclude.
//
// ⚠ NEVER PROMPTS. The core is a service with no UI; a locked collection or an
// operation that needs a prompt returns ErrLocked and the caller says so.

const (
	ssName        = "org.freedesktop.secrets"
	ssPath        = dbus.ObjectPath("/org/freedesktop/secrets")
	ssService     = "org.freedesktop.Secret.Service"
	ssCollection  = "org.freedesktop.Secret.Collection"
	ssItem        = "org.freedesktop.Secret.Item"
	ssSession     = "org.freedesktop.Secret.Session"
	ssLoginPath   = dbus.ObjectPath("/org/freedesktop/secrets/collection/login")
	ssCallTimeout = 5 * time.Second
	noPrompt      = dbus.ObjectPath("/")
)

type ssSecret struct {
	Session     dbus.ObjectPath
	Parameters  []byte
	Value       []byte
	ContentType string
}

func keyringName() string { return BackendSecretService }

// SecretServiceSecrets talks to the Secret Service at Addr. Each call opens
// its own connection: the core asks rarely, and a long-lived connection would
// need its own reconnect logic for a bus that restarts.
type SecretServiceSecrets struct{ Addr string }

func (SecretServiceSecrets) Backend() string { return BackendSecretService }

// SessionBusAddress is where the user's session bus is, without D-Bus
// autolaunch (which would spawn a private bus with no keyring on it). Pure:
// stat says whether a path exists.
func SessionBusAddress(env func(string) string, stat func(string) bool) (string, error) {
	if a := env("DBUS_SESSION_BUS_ADDRESS"); a != "" && a != "autolaunch:" {
		return a, nil
	}
	if rt := env("XDG_RUNTIME_DIR"); rt != "" && stat(rt+"/bus") {
		return "unix:path=" + rt + "/bus", nil
	}
	return "", fmt.Errorf("%w: no session bus (DBUS_SESSION_BUS_ADDRESS unset and no $XDG_RUNTIME_DIR/bus)", ErrUnavailable)
}

func openKeyring() (Secrets, error) {
	addr, err := SessionBusAddress(os.Getenv, func(p string) bool { _, err := os.Stat(p); return err == nil })
	if err != nil {
		return nil, err
	}
	s := SecretServiceSecrets{Addr: addr}
	err = s.with(func(ctx context.Context, c *ssConn) error {
		_, err := c.collection(ctx)
		return err
	})
	if err != nil {
		return nil, err
	}
	return s, nil
}

type ssConn struct {
	conn    *dbus.Conn
	session dbus.ObjectPath
}

func (s SecretServiceSecrets) with(fn func(ctx context.Context, c *ssConn) error) error {
	ctx, cancel := context.WithTimeout(context.Background(), 4*ssCallTimeout)
	defer cancel()
	conn, err := dbus.Connect(s.Addr, dbus.WithContext(ctx))
	if err != nil {
		return fmt.Errorf("%w: session bus: %v", ErrUnavailable, err)
	}
	defer conn.Close()
	if ok, err := hasService(ctx, conn); err != nil {
		return fmt.Errorf("%w: session bus: %v", ErrUnavailable, err)
	} else if !ok {
		return fmt.Errorf("%w: no Secret Service on the session bus", ErrUnavailable)
	}
	var out dbus.Variant
	var session dbus.ObjectPath
	if err := call(ctx, conn.Object(ssName, ssPath), ssService+".OpenSession", "plain", dbus.MakeVariant("")).Store(&out, &session); err != nil {
		return fmt.Errorf("%w: Secret Service OpenSession: %v", ErrUnavailable, err)
	}
	defer call(ctx, conn.Object(ssName, session), ssSession+".Close")
	return fn(ctx, &ssConn{conn: conn, session: session})
}

func call(ctx context.Context, o dbus.BusObject, method string, args ...any) *dbus.Call {
	cctx, cancel := context.WithTimeout(ctx, ssCallTimeout)
	defer cancel()
	return o.CallWithContext(cctx, method, 0, args...)
}

// hasService: owned now, or activatable on demand (gnome-keyring is usually
// started by the first call).
func hasService(ctx context.Context, conn *dbus.Conn) (bool, error) {
	bus := conn.BusObject()
	var owned bool
	if err := call(ctx, bus, "org.freedesktop.DBus.NameHasOwner", ssName).Store(&owned); err != nil {
		return false, err
	}
	if owned {
		return true, nil
	}
	var names []string
	if err := call(ctx, bus, "org.freedesktop.DBus.ListActivatableNames").Store(&names); err != nil {
		return false, err
	}
	for _, n := range names {
		if n == ssName {
			return true, nil
		}
	}
	return false, nil
}

// collection is the "default" alias, else the login collection. Never
// created: creating one prompts for a password.
func (c *ssConn) collection(ctx context.Context) (dbus.ObjectPath, error) {
	var p dbus.ObjectPath
	if err := call(ctx, c.conn.Object(ssName, ssPath), ssService+".ReadAlias", "default").Store(&p); err != nil {
		return "", fmt.Errorf("%w: Secret Service ReadAlias: %v", ErrUnavailable, err)
	}
	if p != noPrompt && p != "" {
		return p, nil
	}
	if _, err := c.conn.Object(ssName, ssLoginPath).GetProperty(ssCollection + ".Label"); err == nil {
		return ssLoginPath, nil
	}
	return "", fmt.Errorf("%w: the Secret Service has no default or login collection", ErrUnavailable)
}

// unlock tries to unlock objects without a prompt.
func (c *ssConn) unlock(ctx context.Context, objs []dbus.ObjectPath) error {
	var done []dbus.ObjectPath
	var prompt dbus.ObjectPath
	if err := call(ctx, c.conn.Object(ssName, ssPath), ssService+".Unlock", objs).Store(&done, &prompt); err != nil {
		return fmt.Errorf("Secret Service Unlock: %w", err)
	}
	if prompt != noPrompt && prompt != "" {
		return fmt.Errorf("%w (unlocking needs a password prompt; log in to the desktop once)", ErrLocked)
	}
	return nil
}

func (c *ssConn) search(ctx context.Context, name string) (unlocked, locked []dbus.ObjectPath, err error) {
	err = call(ctx, c.conn.Object(ssName, ssPath), ssService+".SearchItems", SecretServiceAttrs(name)).Store(&unlocked, &locked)
	if err != nil {
		err = fmt.Errorf("Secret Service SearchItems: %w", err)
	}
	return
}

func (s SecretServiceSecrets) Get(name string) (string, error) {
	if err := ValidName(name); err != nil {
		return "", err
	}
	var v string
	err := s.with(func(ctx context.Context, c *ssConn) error {
		un, lk, err := c.search(ctx, name)
		if err != nil {
			return err
		}
		if len(un) == 0 && len(lk) == 0 {
			return fmt.Errorf("%s: %w", SecretServiceLabel(name), ErrNotFound)
		}
		item := ""
		if len(un) > 0 {
			item = string(un[0])
		} else {
			if err := c.unlock(ctx, lk[:1]); err != nil {
				return err
			}
			item = string(lk[0])
		}
		var sec ssSecret
		if err := call(ctx, c.conn.Object(ssName, dbus.ObjectPath(item)), ssItem+".GetSecret", c.session).Store(&sec); err != nil {
			return fmt.Errorf("Secret Service GetSecret: %w", err)
		}
		if len(sec.Value) == 0 {
			return fmt.Errorf("%s is empty: %w", SecretServiceLabel(name), ErrNotFound)
		}
		v = string(sec.Value)
		return nil
	})
	return v, err
}

func (s SecretServiceSecrets) Set(name, v string) error {
	if err := ValidName(name); err != nil {
		return err
	}
	if v == "" {
		return errors.New("store: refusing to store an empty secret")
	}
	return s.with(func(ctx context.Context, c *ssConn) error {
		coll, err := c.collection(ctx)
		if err != nil {
			return err
		}
		if lockedV, err := c.conn.Object(ssName, coll).GetProperty(ssCollection + ".Locked"); err == nil {
			if b, ok := lockedV.Value().(bool); ok && b {
				if err := c.unlock(ctx, []dbus.ObjectPath{coll}); err != nil {
					return err
				}
			}
		}
		props := map[string]dbus.Variant{
			ssItem + ".Label":      dbus.MakeVariant(SecretServiceLabel(name)),
			ssItem + ".Attributes": dbus.MakeVariant(SecretServiceAttrs(name)),
		}
		sec := ssSecret{Session: c.session, Value: []byte(v), ContentType: "text/plain; charset=utf8"}
		var item, prompt dbus.ObjectPath
		if err := call(ctx, c.conn.Object(ssName, coll), ssCollection+".CreateItem", props, sec, true).Store(&item, &prompt); err != nil {
			return fmt.Errorf("Secret Service CreateItem: %w", err)
		}
		if prompt != noPrompt && prompt != "" {
			return fmt.Errorf("%w (storing needs a prompt)", ErrLocked)
		}
		return nil
	})
}

// Delete removes every item carrying name's attributes; none is success.
func (s SecretServiceSecrets) Delete(name string) error {
	if err := ValidName(name); err != nil {
		return err
	}
	return s.with(func(ctx context.Context, c *ssConn) error {
		un, lk, err := c.search(ctx, name)
		if err != nil {
			return err
		}
		if len(lk) > 0 {
			if err := c.unlock(ctx, lk); err != nil {
				return err
			}
		}
		for _, it := range append(un, lk...) {
			var prompt dbus.ObjectPath
			if err := call(ctx, c.conn.Object(ssName, it), ssItem+".Delete").Store(&prompt); err != nil {
				return fmt.Errorf("Secret Service Delete: %w", err)
			}
			if prompt != noPrompt && prompt != "" {
				return fmt.Errorf("%w (deleting needs a prompt)", ErrLocked)
			}
		}
		return nil
	})
}
