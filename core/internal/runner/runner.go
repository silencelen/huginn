// Package runner is the device runner: it offers this machine to huginn as a
// place to run work. Ported from client/huginn-device (Node) and the desktop's
// DeviceRunner.kt, which it replaces (docs/design/core.md P1).
//
// THE TRANSPORT IS A PULL. The runner opens outbound connections and listens on
// nothing, which is why it works the same on a box in the next room and a laptop
// on hotel wi-fi, and why offering a machine needs no firewall change.
//
// WHAT THE DAEMON MAY DO TO THIS MACHINE is decided HERE, by the policy package,
// never by what arrives on the wire. A work item carries a request and no
// authority.
package runner

import (
	"context"
	"errors"
	"fmt"
	"log"
	"os"
	"sync"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/policy"
	"github.com/silencelen/huginn/core/internal/version"
)

// Config is the runner's view of this machine's device settings. It is read
// again at every beat and every job, never captured: the desktop's runner once
// captured the scope at enrolment and re-sent it on every beat, so a change in
// Settings never reached the daemon until the app restarted (audit 2026-10-03, E1).
type Config struct {
	Enabled        bool
	ID             string // empty until the daemon assigns one
	Name           string
	Scope          string // wire word; parsed through policy.ParseScope
	Root           string
	Claude         string // engine for ask/act; "" = "claude" on PATH
	LLM            string // engine for generate; "" = none configured
	Node           string // node binary for .js engines and npm shims
	ActWhileLocked bool
	Persistent     *bool // tri-state; absent stays absent all the way to the daemon
	Models         []appd.Model
	UnenrolOwed    bool // turned off with an id: the row must be DELETEd first
}

// Lock is one reading of whether the user's session is locked. Known=false
// means the probe could not tell; the fence then treats the machine as locked,
// and Why says which (so a headless box is never told to unlock a screen).
type Lock struct {
	Locked bool
	Known  bool
	Why    string
}

// Status is what the runner reports for /core/device and the GUI. The field
// names match the desktop's DeviceStatus so its Settings panel reads it as-is.
type Status struct {
	Enabled   bool   `json:"enabled"`
	DeviceID  string `json:"deviceId,omitempty"`
	Enrolled  bool   `json:"enrolled"`
	Busy      bool   `json:"busy"`
	Locked    bool   `json:"locked"`
	LockKnown bool   `json:"lockKnown"`
	Note      string `json:"note"`
}

// Notes the GUI matches on to decide whether to re-resolve routes. Prefixes,
// kept as constants so a reworded sentence cannot silently unhandle a case.
const (
	NoteRetrying   = "Retrying"
	NoteNotReached = "Not reaching huginn"
)

// Hooks is everything the runner needs from the rest of the core.
type Hooks struct {
	API      *appd.Client
	Conf     func() Config
	SaveID   func(id, llmSlug string) // the daemon assigned/confirmed a row
	ClearOwe func()                   // the unenrol landed
	Lock     func(ctx context.Context) Lock
	Publish  func(Status)
	Logf     func(format string, args ...any)
	Home     string
}

// Runner is one device runner. One job at a time.
type Runner struct {
	h    Hooks
	wake chan struct{}

	mu         sync.Mutex
	status     Status
	active     func() // cancels the in-flight run, if any
	pollCancel func() // cancels the in-flight long poll, if any
}

// New returns a runner; call Serve.
func New(h Hooks) *Runner {
	if h.Logf == nil {
		h.Logf = log.Printf
	}
	if h.Publish == nil {
		h.Publish = func(Status) {}
	}
	if h.Home == "" {
		h.Home, _ = os.UserHomeDir()
	}
	return &Runner{h: h, wake: make(chan struct{}, 1)}
}

// Kick makes a settings change (on/off, scope, lock policy) take effect now.
//
// ⚠ IT MUST CUT THE LONG POLL. The loop re-reads its settings between polls, and
// a poll is held open for up to 25 s — so without this, "turn off" and a scope
// change waited for the next empty poll (caught by core/test/e2e.sh). Only the
// POLL is cut, never a running job: a job finishes under the settings it started
// with, and the next one sees the new ones.
func (r *Runner) Kick() {
	r.mu.Lock()
	c := r.pollCancel
	r.mu.Unlock()
	if c != nil {
		c()
	}
	select {
	case r.wake <- struct{}{}:
	default:
	}
}

// Status is the latest published status.
func (r *Runner) Status() Status {
	r.mu.Lock()
	defer r.mu.Unlock()
	return r.status
}

func (r *Runner) set(f func(*Status)) {
	r.mu.Lock()
	f(&r.status)
	s := r.status
	r.mu.Unlock()
	r.h.Publish(s)
}

// Timings, variables so tests can shrink them.
var (
	beatEvery      = 60 * time.Second
	retryAfter     = 15 * time.Second
	pollBlipPause  = 3 * time.Second
	flushEvery     = 500 * time.Millisecond
	startupSilence = 180 * time.Second
	// endPatience bounds the retries of a run's last POSTs: appd gives a run
	// up after five minutes without a word, and the next job waits on this one.
	endPatience = 5 * time.Minute
)

const pollFailuresBeforeReenrol = 5

// Serve runs until ctx ends. Never fatal: a daemon that is down, a token that
// was rotated, a box that suspended — all of these are "try again shortly". A
// runner that gave up on the first failure would need somebody to notice.
func (r *Runner) Serve(ctx context.Context) {
	unenrolTries := 0
	for ctx.Err() == nil {
		conf := r.h.Conf()
		if !conf.Enabled {
			// Off. If it was turned off with a row still registered, that row is
			// a debt this loop pays — a laptop retired while offline must not stay
			// listed (and dispatchable) forever.
			if conf.UnenrolOwed && conf.ID != "" {
				if r.retire(ctx, conf.ID, &unenrolTries) {
					continue
				}
				r.waitOrWake(ctx, unenrolBackoff(unenrolTries))
				continue
			}
			unenrolTries = 0
			r.set(func(s *Status) { *s = Status{Note: "Off"} })
			r.waitOrWake(ctx, retryAfter)
			continue
		}
		if err := r.session(ctx, conf); err != nil && ctx.Err() == nil {
			r.h.Logf("not reaching huginn: %v — retrying in %s", err, retryAfter)
			r.set(func(s *Status) {
				s.Enabled, s.Enrolled, s.Busy = true, false, false
				s.Note = fmt.Sprintf("%s: %s", NoteNotReached, short(err))
			})
			r.waitOrWake(ctx, retryAfter)
		}
	}
}

// unenrolBackoff: 5 s doubling to 5 min, as Unenrol.kt.
func unenrolBackoff(tries int) time.Duration {
	d := 5 * time.Second
	for i := 1; i < tries && d < 5*time.Minute; i++ {
		d *= 2
	}
	if d > 5*time.Minute {
		d = 5 * time.Minute
	}
	return d
}

// retire DELETEs the row; a 404 counts as done (it is already gone).
func (r *Runner) retire(ctx context.Context, id string, tries *int) bool {
	err := r.h.API.Delete(ctx, id)
	var se *appd.StatusError
	if err == nil || (errors.As(err, &se) && se.Status == 404) {
		r.h.Logf("unenrolled %s", id)
		if r.h.ClearOwe != nil {
			r.h.ClearOwe()
		}
		*tries = 0
		r.set(func(s *Status) { *s = Status{Note: "Off"} })
		return true
	}
	*tries++
	r.set(func(s *Status) {
		*s = Status{DeviceID: id, Note: fmt.Sprintf("%s removing this machine from huginn: %s", NoteRetrying, short(err))}
	})
	return false
}

func (r *Runner) waitOrWake(ctx context.Context, d time.Duration) {
	t := time.NewTimer(d)
	defer t.Stop()
	select {
	case <-ctx.Done():
	case <-r.wake:
	case <-t.C:
	}
}

// session is one enrolment: enrol, beat, poll until something breaks or the
// device is turned off.
func (r *Runner) session(ctx context.Context, conf Config) error {
	lk := r.h.Lock(ctx)
	id, err := r.enrol(ctx, conf, lk)
	if err != nil {
		return err
	}
	r.h.Logf("enrolled as %s (%s) → %s", conf.Name, policy.ParseScope(conf.Scope), r.h.API.URL())
	r.set(func(s *Status) {
		*s = Status{Enabled: true, DeviceID: id, Enrolled: true, Locked: lk.Locked, LockKnown: lk.Known, Note: idleNote(lk, conf.ActWhileLocked)}
	})

	sctx, stop := context.WithCancel(ctx)
	defer stop()
	beatNow := make(chan struct{}, 1)
	go r.beatLoop(sctx, id, beatNow)

	failures := 0
	lastScope := policy.ParseScope(conf.Scope)
	for sctx.Err() == nil {
		live := r.h.Conf()
		if !live.Enabled {
			return nil // Serve sees Enabled=false and pays the unenrol debt
		}
		// A scope change reaches the daemon at once, not at the next minute.
		if sc := policy.ParseScope(live.Scope); sc != lastScope {
			lastScope = sc
			select {
			case beatNow <- struct{}{}:
			default:
			}
		}
		lk := r.h.Lock(sctx)
		pctx, pcancel := context.WithCancel(sctx)
		r.mu.Lock()
		r.pollCancel = pcancel
		r.mu.Unlock()
		work, err := r.h.API.PollWork(pctx, id, lk.Locked)
		r.mu.Lock()
		r.pollCancel = nil
		r.mu.Unlock()
		kicked := pctx.Err() != nil && sctx.Err() == nil
		pcancel()
		if kicked {
			// Cut by Kick: not a failure. Drain the wake so Serve does not see it
			// twice, then loop to re-read the settings.
			select {
			case <-r.wake:
			default:
			}
			continue
		}
		if err != nil {
			// A blip on one poll is not a reason to re-enrol; re-enrolling on every
			// hiccup is what turns a flaky link into a stream of registrations.
			failures++
			if failures >= pollFailuresBeforeReenrol || appd.IsAuth(err) {
				return err
			}
			r.h.Logf("poll failed (%d/%d): %v", failures, pollFailuresBeforeReenrol, err)
			select {
			case <-sctx.Done():
			case <-time.After(pollBlipPause):
			}
			continue
		}
		failures = 0
		r.set(func(s *Status) {
			s.Locked, s.LockKnown, s.Note = lk.Locked, lk.Known, idleNote(lk, live.ActWhileLocked)
		})
		if work != nil {
			r.runWork(sctx, id, *work, r.h.Conf())
		}
	}
	return nil
}

func (r *Runner) enrol(ctx context.Context, conf Config, lk Lock) (string, error) {
	scope := policy.ParseScope(conf.Scope)
	var root *string
	if conf.Root != "" {
		root = &conf.Root
	}
	host, _ := os.Hostname()
	dev, err := r.h.API.Enrol(ctx, appd.Enrolment{
		ID: conf.ID, Name: conf.Name, Platform: Platform(), Scope: scope, Root: root,
		Version: version.Version, Locked: lk.Locked, Machine: MachineKey(host),
		Models: conf.Models, Persistent: conf.Persistent, ActWhileLocked: conf.ActWhileLocked,
	})
	if err != nil {
		return "", err
	}
	if err := policy.ScopeEcho(scope, dev.Scope); err != nil {
		return "", err
	}
	if dev.ID != conf.ID || dev.LlmSlug != "" {
		if r.h.SaveID != nil {
			r.h.SaveID(dev.ID, dev.LlmSlug)
		}
	}
	return dev.ID, nil
}

// beatLoop says "I am still here, and this is what I will do now" every minute
// (and at once on a scope change). Separate from the poll because they answer
// different questions. A cancel on the beat reaches a run sitting in a quiet
// tool, where no event batch would carry it.
func (r *Runner) beatLoop(ctx context.Context, id string, now <-chan struct{}) {
	t := time.NewTicker(beatEvery)
	defer t.Stop()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
		case <-now:
		}
		live := r.h.Conf()
		lk := r.h.Lock(ctx)
		rep, err := r.h.API.Beat(ctx, id, appd.Beat{
			Locked: lk.Locked, Scope: policy.ParseScope(live.Scope), Version: version.Version,
			Persistent: live.Persistent, ActWhileLocked: live.ActWhileLocked,
		})
		if err == nil && rep.Cancel {
			r.cancelActive()
		}
	}
}

func (r *Runner) cancelActive() {
	r.mu.Lock()
	c := r.active
	r.mu.Unlock()
	if c != nil {
		c()
	}
}

func idleNote(lk Lock, actWhileLocked bool) string {
	switch {
	case !lk.Known && lk.Why != "":
		return "Waiting for work (ask only: " + lk.Why + ")"
	case lk.Locked && !actWhileLocked:
		return "Waiting for work (screen locked: ask only)"
	default:
		return "Waiting for work"
	}
}

func short(err error) string {
	s := err.Error()
	if r := []rune(s); len(r) > 160 {
		return string(r[:160]) + "…"
	}
	return s
}
