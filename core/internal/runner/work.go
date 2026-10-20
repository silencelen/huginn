package runner

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"
	"sync"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/policy"
)

func isDir(p string) bool {
	fi, err := os.Stat(p)
	return err == nil && fi.IsDir()
}

// retryDelay: exponential from half a second, capped at 30 s. The flush runs
// every 500 ms, so an outage used to be two requests a second until the child exited.
func retryDelay(blips int) time.Duration {
	if blips > 6 {
		blips = 6
	}
	d := 500 * time.Millisecond << blips
	if d > 30*time.Second {
		d = 30 * time.Second
	}
	return d
}

// runWork runs one job to its terminal frame. It never returns early without
// reporting an ending: a run the daemon never hears end holds the machine's one
// job slot until the daemon gives up.
func (r *Runner) runWork(ctx context.Context, id string, w appd.WorkItem, conf Config) {
	scope := policy.ParseScope(conf.Scope)
	lk := r.h.Lock(ctx)
	reported := lk.Locked // what this machine SAYS, on every frame home
	// What the POLICY sees: the owner's standing "keep act while locked" applied
	// to the lock. An unknown lock is a locked one to the fence.
	gate := policy.GateLocked(lk.Locked || !lk.Known, conf.ActWhileLocked)
	post := func(ev appd.Events) (*appd.EventsReply, error) {
		ev.Locked = reported
		if ev.Lines == nil {
			ev.Lines = []string{}
		}
		return r.h.API.PostEvents(ctx, id, w.ID, ev)
	}
	refuse := func(why string) {
		r.h.Logf("refused %s: %s", w.ID, why)
		if _, err := post(appd.Events{Done: true, Error: why}); err != nil {
			r.h.Logf("could not report the refusal of %s: %v", w.ID, err)
		}
	}

	// Refused HERE, by the machine, and said out loud. The daemon pre-checks the
	// same rule so a person hears it when they ask — but this is the check that
	// decides, because this is the process holding the file system.
	if why := policy.Refusal(scope, gate, w.Mode); why != "" {
		refuse(why)
		return
	}
	// The engine fence after the scope fence: generate work needs a configured
	// local engine and must never fall through to claude.
	if why := policy.EngineRefusal(w.Mode, conf.LLM != ""); why != "" {
		refuse(why)
		return
	}

	if why := rideAlongRefusal(deref(w.Model), deref(w.Effort)); why != "" {
		refuse(why)
		return
	}
	pw := policy.Work{Mode: w.Mode, Model: deref(w.Model), Effort: deref(w.Effort), ResumeSessionID: deref(w.ResumeSessionID)}
	argv := policy.ArgvFor(pw, scope, gate, "")
	cwd := policy.CwdFor(scope, gate, conf.Root, r.h.Home)
	r.h.Logf("running %s (%s) in %s", w.ID, w.Mode, cwd)

	r.set(func(s *Status) { s.Busy = true })
	defer r.set(func(s *Status) { s.Busy = false })

	var mu sync.Mutex
	var pending []string
	finished := false
	finish := func(errText string, exitCode *int) {
		mu.Lock()
		if finished {
			mu.Unlock()
			return
		}
		finished = true
		tail := pending
		pending = nil
		mu.Unlock()
		// ⚠ THE END IS RETRIED LIKE THE MIDDLE. The child usually prints its
		// result and exits before the next flush, so the answer rides the tail.
		// Sent once, one 502 or reset lost the answer while the run still ended
		// as a clean finish, and a lost ending let the next poll fail the run as
		// "the runner restarted". Sending again is safe: appd drops a chunk it
		// has seen (its chunk hashes) and answers 404 to an ending it applied.
		// Given up on a refusal, a dead token, a stopped runner or endPatience.
		giveUp := time.Now().Add(endPatience)
		deliver := func(ev appd.Events) error {
			for blips := 1; ; blips++ {
				_, err := post(ev)
				wait := retryDelay(blips)
				if err == nil || appd.IsPermanent(err) || appd.IsAuth(err) || ctx.Err() != nil || time.Now().Add(wait).After(giveUp) {
					return err
				}
				r.h.Logf("could not send the end of %s (retrying in %s): %v", w.ID, wait, err)
				select {
				case <-ctx.Done():
					return err
				case <-time.After(wait):
				}
			}
		}
		// ⚠ THE TAIL FIRST, THEN THE ENDING ALONE. In one POST, an oversized tail
		// took the terminal frame down with it and its retry was identically 413:
		// the ending could NEVER be delivered.
		for _, chunk := range batchLines(tail) {
			if err := deliver(appd.Events{Lines: chunk}); err != nil {
				r.h.Logf("could not send part of the output of %s: %v", w.ID, err)
				if appd.IsPermanent(err) {
					break
				}
			}
		}
		if err := deliver(appd.Events{Done: true, ExitCode: exitCode, Error: errText}); err != nil {
			r.h.Logf("could not report the ending of %s: %v", w.ID, err)
		}
		if errText != "" {
			r.h.Logf("finished %s — %s", w.ID, errText)
		} else {
			r.h.Logf("finished %s", w.ID)
		}
	}

	// ⚠ CHECKED BEFORE THE SPAWN: exec reports a missing executable and a
	// missing cwd the same way, and "claude is not on PATH" for a deleted work
	// root sent the operator after the one thing they had already done.
	if !isDir(cwd) {
		finish(fmt.Sprintf("%s cannot start work: %s is not a directory", conf.Name, cwd), nil)
		return
	}

	engineBin := conf.Claude
	if w.Mode == "generate" {
		engineBin = conf.LLM
	}
	if engineBin == "" {
		engineBin = "claude"
	}
	node := conf.Node
	if node == "" {
		node = "node"
	}
	bin, fullArgv, ok := execFor(resolveEngine(engineBin), argv, node)
	label, _ := engine(w.Mode)
	if !ok {
		finish(batchRefusal(w.Mode, conf.Name), nil)
		return
	}

	cmd := exec.Command(bin, fullArgv...)
	cmd.Dir = cwd
	cmd.Stdin = strings.NewReader(w.Prompt)
	stdout, err := cmd.StdoutPipe()
	if err != nil {
		finish(fmt.Sprintf("could not start %s on %s: %v", label, conf.Name, err), nil)
		return
	}
	var errHead headBuffer
	cmd.Stderr = &errHead
	if err := cmd.Start(); err != nil {
		if errors.Is(err, exec.ErrNotFound) || errors.Is(err, os.ErrNotExist) {
			finish(notFoundText(w.Mode, conf.Name), nil) // the cwd was checked: this IS the executable
		} else {
			finish(fmt.Sprintf("could not start %s on %s: %v", label, conf.Name, err), nil)
		}
		return
	}

	var cancelled, deliveryDead bool
	kill := func() {
		mu.Lock()
		if cancelled || finished {
			mu.Unlock()
			return
		}
		cancelled = true
		mu.Unlock()
		cmd.Process.Kill()
	}
	r.mu.Lock()
	r.active = kill
	cancelAll := r.cancelAll // a CancelJobs that came before this kill was registered
	r.mu.Unlock()
	defer func() { r.mu.Lock(); r.active = nil; r.mu.Unlock() }()
	if cancelAll {
		kill()
	}

	// Startup-liveness watchdog: a child that wedges at startup (a hung
	// auto-update, broken credentials) emits NOTHING and would hold the job slot
	// looking healthy. ONLY startup: a working run may sit for minutes inside one
	// quiet tool, and killing that is the failure the beat liveness exists to stop.
	sawOutput := make(chan struct{})
	var sawOnce sync.Once
	wedged := false
	go func() {
		select {
		case <-sawOutput:
		case <-time.After(startupSilence):
			mu.Lock()
			wedged = !finished
			mu.Unlock()
			if wedged {
				r.h.Logf("%s: no output in %s — stopping a wedged engine", w.ID, startupSilence)
				kill()
			}
		}
	}()

	// The flush loop: batches go up every half second.
	flushDone := make(chan struct{})
	stopFlush := make(chan struct{})
	go func() {
		defer close(flushDone)
		t := time.NewTicker(flushEvery)
		defer t.Stop()
		blips := 0
		var backoffUntil time.Time
		for {
			select {
			case <-stopFlush:
				return
			case <-t.C:
			}
			mu.Lock()
			if deliveryDead {
				pending = nil
			}
			if len(pending) == 0 || time.Now().Before(backoffUntil) || finished {
				mu.Unlock()
				continue
			}
			batch := pending
			pending = nil
			mu.Unlock()
			var ferr error
			for _, chunk := range batchLines(batch) {
				ack, err := post(appd.Events{Lines: chunk})
				if err != nil {
					ferr = err
					break
				}
				if ack.Cancel {
					kill()
				}
			}
			switch {
			case ferr == nil:
				blips, backoffUntil = 0, time.Time{}
			case appd.IsPermanent(ferr):
				// NOT RETRYABLE: after a daemon restart the run is simply gone, and
				// re-queueing hammered the 404 twice a second for the child's life.
				r.h.Logf("giving up on %s: %v", w.ID, ferr)
				kill()
			case appd.IsAuth(ferr):
				// ⚠ TERMINAL FOR DELIVERY, NOT FOR THE CHILD: the token was rotated
				// under a live run, and killing the child could stop a claude
				// mid-edit. Said once; the buffer is dropped rather than grown.
				mu.Lock()
				deliveryDead = true
				pending = nil
				mu.Unlock()
				r.h.Logf("delivery for %s is not authorised (%v) — the child keeps running", w.ID, ferr)
			default:
				// Put them back: a blip mid-run must not silently eat the answer.
				mu.Lock()
				pending = append(batch, pending...)
				mu.Unlock()
				blips++
				backoffUntil = time.Now().Add(retryDelay(blips))
				r.h.Logf("batch failed (retrying in %s): %v", retryDelay(blips), ferr)
			}
		}
	}()

	// Lines can be far larger than bufio.Scanner's limit (a whole tool_result
	// in one stream-json line), so read with ReadString.
	rd := bufio.NewReaderSize(stdout, 64*1024)
	for {
		line, err := rd.ReadString('\n')
		if len(line) > 0 {
			sawOnce.Do(func() { close(sawOutput) })
			if t := strings.TrimRight(line, "\r\n"); strings.TrimSpace(t) != "" {
				mu.Lock()
				if !deliveryDead {
					pending = append(pending, t)
				}
				mu.Unlock()
			}
		}
		if err != nil {
			if err != io.EOF {
				r.h.Logf("%s: reading output: %v", w.ID, err)
			}
			break
		}
	}
	werr := cmd.Wait()
	close(stopFlush)
	<-flushDone

	mu.Lock()
	wasCancelled, wasWedged, dead := cancelled, wedged, deliveryDead
	if dead {
		pending = nil
	}
	mu.Unlock()

	code := cmd.ProcessState.ExitCode()
	switch {
	case wasWedged:
		finish(fmt.Sprintf("%s produced no output for %s on %s and was stopped — it looked wedged on startup (check auth, or a hung update)",
			label, startupSilence, conf.Name), nil)
	case wasCancelled:
		finish(fmt.Sprintf("cancelled on %s", conf.Name), &code)
	case werr == nil:
		finish("", &code)
	default:
		finish(exitedText(w.Mode, conf.Name, code, errHead.String()), &code)
	}
}

func deref(p *string) string {
	if p == nil {
		return ""
	}
	return *p
}

// headBuffer keeps the first 2000 bytes of stderr. ⚠ THE HEAD, NOT THE TAIL
// (see errWords).
type headBuffer struct {
	mu sync.Mutex
	b  []byte
}

func (h *headBuffer) Write(p []byte) (int, error) {
	h.mu.Lock()
	defer h.mu.Unlock()
	if room := 2000 - len(h.b); room > 0 {
		if len(p) < room {
			room = len(p)
		}
		h.b = append(h.b, p[:room]...)
	}
	return len(p), nil
}

func (h *headBuffer) String() string {
	h.mu.Lock()
	defer h.mu.Unlock()
	return string(h.b)
}
