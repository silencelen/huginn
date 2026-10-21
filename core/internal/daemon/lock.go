package daemon

import (
	"bytes"
	"context"
	"os/exec"
	"runtime"
	"strings"
	"sync"
	"time"

	"github.com/silencelen/huginn/core/internal/lock"
	"github.com/silencelen/huginn/core/internal/runner"
)

// lockReader turns the lock probe into what the fence needs, and caches it for
// a few seconds (the poll, the beat and a job each ask).
//
// ⚠ "NO GRAPHICAL SESSION" MEANS TWO DIFFERENT MACHINES, and treating them alike
// is wrong either way:
//   - a HEADLESS box (a server, a container) has no screen to lock.
//     The Node runner always reported it unlocked, and act-scope servers rely on
//     that; calling it locked would quietly demote every one of them to ask-only.
//   - a DESKTOP that booted with nobody signed in is sitting at its login screen
//     — nobody is at the desk, exactly what the lock fence is for. Windows shows
//     the same thing as LogonUI and the probe already calls it locked.
//
// So the question is whether the machine CAN show a graphical login: logind's
// seat0 CanGraphical. A desktop at its login screen is locked (the
// "keep act mode while locked" setting is what lets an always-on desktop act unattended);
// a box with no graphical seat is unlocked; a probe that fails is unknown, which
// the fence treats as locked. "No graphical seat" must be logind's own answer:
// a logind or system bus that cannot be asked is a probe that failed, not a
// headless box.
type lockReader struct {
	mu         sync.Mutex // guards at, last and refreshing; never held across a probe
	at         time.Time
	last       runner.Lock
	refreshing bool       // a background probe (peek) is on its way
	probeMu    sync.Mutex // one probe at a time
	// seams for tests
	probe        func(context.Context) lock.Result
	canGraphical func(context.Context) (bool, bool) // (graphical, known)
}

func newLockReader() *lockReader {
	return &lockReader{probe: lock.Probe, canGraphical: seatGraphical}
}

const lockCache = 5 * time.Second

// read is the fence's answer: a reading at most lockCache old, probing (and
// waiting for the probe) when the one in hand is older.
func (l *lockReader) read(ctx context.Context) runner.Lock {
	if lk, ok := l.fresh(); ok {
		return lk
	}
	l.probeMu.Lock()
	defer l.probeMu.Unlock()
	if lk, ok := l.fresh(); ok {
		return lk // probed while this one waited its turn
	}
	lk := l.decide(ctx)
	l.mu.Lock()
	l.last, l.at = lk, time.Now()
	l.mu.Unlock()
	return lk
}

func (l *lockReader) fresh() (runner.Lock, bool) {
	l.mu.Lock()
	defer l.mu.Unlock()
	return l.last, !l.at.IsZero() && time.Since(l.at) < lockCache
}

// peek is the newest reading WITHOUT WAITING FOR A PROBE, for the notify claim.
//
// ⚠ THE CLAIM RIDES EVERY REQUEST THE CORE SENDS, the desktop's through /appd
// included (watcher.headers). Read through read(), the request that found the
// reading 5 s old ran the probe itself - PowerShell on Windows, a new system bus
// connection on Linux - with the lock held, so it and every request beside it
// waited for the probe, every 5 s. A reading that is too old is answered as it
// is, and one probe goes on in the background; only the very first ask, with
// no reading at all, waits for one. The act fence keeps read().
func (l *lockReader) peek() runner.Lock {
	l.mu.Lock()
	if l.at.IsZero() {
		l.mu.Unlock()
		return l.read(context.Background())
	}
	lk := l.last
	if time.Since(l.at) >= lockCache && !l.refreshing {
		l.refreshing = true
		go func() {
			defer func() {
				l.mu.Lock()
				l.refreshing = false
				l.mu.Unlock()
			}()
			l.read(context.Background()) // bounded by lock.Timeout
		}()
	}
	l.mu.Unlock()
	return lk
}

func (l *lockReader) decide(ctx context.Context) runner.Lock {
	res := l.probe(ctx)
	if res.Reading.Known() {
		return runner.Lock{Locked: res.Reading.Locked(), Known: true}
	}
	if res.NoSession && runtime.GOOS == "linux" {
		graphical, known := l.canGraphical(ctx)
		switch {
		case known && !graphical:
			return runner.Lock{Locked: false, Known: true, Why: "headless: no graphical seat"}
		case known && graphical:
			return runner.Lock{Locked: true, Known: true, Why: "nobody is signed in"}
		}
	}
	return runner.Lock{Locked: true, Known: false, Why: res.Reason}
}

// seatGraphical asks logind whether seat0 can show a graphical login. No
// loginctl, or no seat0 at all, is a machine with no graphical stack (an LXC,
// a container): headless, known.
func seatGraphical(ctx context.Context) (bool, bool) {
	return seatGraphicalWith(ctx, exec.LookPath, runSplit)
}

// seatGraphicalWith is seatGraphical with its two calls passed in.
//
// ⚠ ONLY LOGIND SAYING SO IS "NO SEAT0". loginctl fails the same way when there
// is no seat0 and when it cannot reach logind at all (no system bus, logind
// down or not answering), and a desktop in the second state is not headless:
// reading it as headless made its locked screen "unlocked, known". So a
// failure counts as no seat0 only when logind answered that it knows no seat0,
// and every other failure, or an answer that is neither yes nor no, is unknown.
func seatGraphicalWith(ctx context.Context, lookPath func(string) (string, error),
	run func(ctx context.Context, name string, args ...string) (stdout, stderr string, err error)) (graphical, known bool) {
	ctx, cancel := context.WithTimeout(ctx, 5*time.Second)
	defer cancel()
	if _, err := lookPath("loginctl"); err != nil {
		return false, true
	}
	out, errOut, err := run(ctx, "loginctl", "show-seat", "seat0", "-p", "CanGraphical", "--value")
	if err != nil {
		if ctx.Err() == nil && strings.Contains(errOut, "No seat 'seat0' known") {
			return false, true // logind's own answer: there is no seat0
		}
		return false, false
	}
	switch strings.TrimSpace(out) {
	case "yes":
		return true, true
	case "no":
		return false, true
	}
	return false, false
}

// runSplit runs a command and returns what it wrote to stdout and to stderr.
func runSplit(ctx context.Context, name string, args ...string) (string, string, error) {
	var out, errOut bytes.Buffer
	c := exec.CommandContext(ctx, name, args...)
	c.Stdout, c.Stderr = &out, &errOut
	c.WaitDelay = time.Second
	err := c.Run()
	return out.String(), errOut.String(), err
}
