package daemon

import (
	"slices"
	"testing"
	"time"
)

// A scope picked in Settings reaches appd at once, on a beat, now that a
// change which keeps the machine on no longer cuts the long poll (the cut
// poll is what used to bring the beat forward). This package runs the real
// minute between beats and the poll is held, so a beat within the second is
// that change's own.
func TestScopeChangeReachesAppdAtOnce(t *testing.T) {
	g := newRestartRig(t, 1)
	g.f.hold = 10 * time.Second // a real poll is held 25 s: the loop is no help here
	g.d.restartRunner(g.d.ctx)
	g.waitFor(t, "the runner to enrol", 10*time.Second, func() bool {
		g.f.mu.Lock()
		defer g.f.mu.Unlock()
		return g.f.enrols > 0 && g.f.polls > 0
	})
	if code, body, _ := g.call(t, "PATCH", "/core/device", `{"scope":"own"}`); code != 200 {
		t.Fatalf("PATCH /core/device: %d %s", code, body)
	}
	g.waitFor(t, "a beat carrying the new scope", time.Second, func() bool {
		g.f.mu.Lock()
		defer g.f.mu.Unlock()
		return slices.Contains(g.f.beats, "own")
	})
}
