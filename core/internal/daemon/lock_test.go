package daemon

import (
	"context"
	"errors"
	"os/exec"
	"runtime"
	"testing"

	"github.com/silencelen/huginn/core/internal/lock"
)

func TestLockMapping(t *testing.T) {
	if runtime.GOOS != "linux" {
		t.Skip("seat logic is linux-only")
	}
	noSession := lock.Result{Reading: lock.Unknown, NoSession: true, Reason: "no graphical session"}
	cases := []struct {
		name               string
		res                lock.Result
		graphical, known   bool
		wantLocked, wantKn bool
	}{
		{"unlocked session", lock.Result{Reading: lock.Unlocked}, false, true, false, true},
		{"locked session", lock.Result{Reading: lock.Locked}, false, true, true, true},
		{"headless server", noSession, false, true, false, true},
		{"desktop at login screen", noSession, true, true, true, true},
		{"seat unknowable", noSession, false, false, true, false},
		{"probe failed", lock.Result{Reading: lock.Unknown, Reason: "timeout"}, false, true, true, false},
	}
	for _, c := range cases {
		l := &lockReader{probe: func(context.Context) lock.Result { return c.res },
			canGraphical: func(context.Context) (bool, bool) { return c.graphical, c.known }}
		got := l.read(context.Background())
		if got.Locked != c.wantLocked || got.Known != c.wantKn {
			t.Errorf("%s: got %+v", c.name, got)
		}
	}
}

// seatGraphical's "no seat0" must be logind's own answer. loginctl fails the
// same way when it cannot reach logind at all, and that is a lock state that
// cannot be read, not a headless box.
func TestSeatGraphicalNeedsLogindsAnswer(t *testing.T) {
	found := func(string) (string, error) { return "/usr/bin/loginctl", nil }
	failed := &exec.ExitError{}
	cases := []struct {
		name             string
		out, errOut      string
		err              error
		graphical, known bool
	}{
		{"a desktop", "yes\n", "", nil, true, true},
		{"a seat that cannot show graphics", "no\n", "", nil, false, true},
		{"no seat0 (systemd 255)", "", "Failed to get path for seat 'seat0': No seat 'seat0' known\n", failed, false, true},
		{"no seat0 (older systemd)", "", "Failed to get seat: No seat 'seat0' known.\n", failed, false, true},
		{"no system bus", "", "Failed to connect to bus: No such file or directory\n", failed, false, false},
		{"logind does not answer", "", "Failed to get path for seat 'seat0': Connection timed out\n", failed, false, false},
		{"a failure that says nothing", "", "", failed, false, false},
		{"an answer that is neither", "maybe\n", "", nil, false, false},
	}
	for _, c := range cases {
		g, k := seatGraphicalWith(context.Background(), found,
			func(context.Context, string, ...string) (string, string, error) { return c.out, c.errOut, c.err })
		if g != c.graphical || k != c.known {
			t.Errorf("%s: got (graphical %v, known %v)", c.name, g, k)
		}
	}
	// No loginctl at all: a machine with no logind, headless (unchanged).
	g, k := seatGraphicalWith(context.Background(), func(string) (string, error) { return "", exec.ErrNotFound },
		func(context.Context, string, ...string) (string, string, error) {
			t.Error("ran a loginctl that is not there")
			return "", "", nil
		})
	if g || !k {
		t.Errorf("no loginctl: got (graphical %v, known %v)", g, k)
	}
}

// One system bus or logind fault on a desktop, end to end through the reader:
// the probe cannot list sessions and loginctl cannot reach logind for the seat
// either. The fence must hold (locked, not known). On the old code this read
// "headless: no graphical seat", unlocked and known.
func TestLogindDownReadsLocked(t *testing.T) {
	busDown := "Failed to connect to bus: No such file or directory\n"
	p := lock.Prober{GOOS: "linux", UID: 1000,
		Login1: func(context.Context) ([]lock.Session, error) { return nil, errors.New("no system bus") },
		Exec:   func(context.Context, string, ...string) (string, error) { return busDown, &exec.ExitError{} },
	}
	l := &lockReader{probe: p.Probe, canGraphical: func(ctx context.Context) (bool, bool) {
		return seatGraphicalWith(ctx, func(string) (string, error) { return "/usr/bin/loginctl", nil },
			func(context.Context, string, ...string) (string, string, error) {
				return "", busDown, &exec.ExitError{}
			})
	}}
	if got := l.read(context.Background()); !got.Locked || got.Known {
		t.Fatalf("logind down read as %+v; want locked, not known", got)
	}
}
