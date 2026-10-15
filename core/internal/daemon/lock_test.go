package daemon

import (
	"context"
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
