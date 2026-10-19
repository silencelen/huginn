package lock

import (
	"bytes"
	"context"
	"errors"
	"os"
	"os/exec"
	"runtime"
	"strings"
	"time"
)

// Timeout bounds one probe: it runs on the runner's beat loop, and a probe
// that hangs would freeze the beat that says this machine is listening.
const Timeout = 5 * time.Second

// Prober runs the probe; fields are injectable for tests.
type Prober struct {
	GOOS string
	UID  int
	// Exec runs a command and returns its combined output. err is non-nil
	// for a non-zero exit too; out is still whatever it printed.
	Exec func(ctx context.Context, name string, args ...string) (out string, err error)
	// Login1 lists the user's sessions over D-Bus (nil: skip straight to
	// loginctl). An error falls back to loginctl.
	Login1  func(ctx context.Context) ([]Session, error)
	Timeout time.Duration
}

// Default is the probe for this process.
func Default() Prober {
	return Prober{GOOS: runtime.GOOS, UID: os.Getuid(), Exec: execCombined, Login1: login1Sessions, Timeout: Timeout}
}

// Probe reads the lock state of this machine with the default prober.
func Probe(ctx context.Context) Result { return Default().Probe(ctx) }

// Probe reads the lock state, bounded by p.Timeout.
func (p Prober) Probe(ctx context.Context) Result {
	t := p.Timeout
	if t == 0 {
		t = Timeout
	}
	ctx, cancel := context.WithTimeout(ctx, t)
	defer cancel()
	var res Result
	switch p.GOOS {
	case "windows":
		res = p.windows(ctx)
	case "linux":
		res = p.linux(ctx)
	default:
		// Nothing here was asked: not "locked", and the UI must not tell
		// anybody to unlock a machine that may be wide open.
		return Result{Reading: Unknown, Reason: "no lock probe on " + p.GOOS}
	}
	if res.Reading == Unknown && errors.Is(ctx.Err(), context.DeadlineExceeded) {
		res.Reason = "the lock probe did not answer within " + t.String()
		res.NoSession = false
	}
	return res
}

func (p Prober) windows(ctx context.Context) Result {
	out, err := p.Exec(ctx, "powershell", "-NoProfile", "-NonInteractive", "-Command", WindowsScript)
	var ee *exec.ExitError
	if err != nil && !errors.As(err, &ee) {
		return Result{Reading: Unknown, Reason: "the lock probe could not run PowerShell: " + err.Error()}
	}
	return ParseWindows(out)
}

// linux reads the user's sessions from logind.
//
// ⚠ "LOGIND COULD NOT BE ASKED" IS NOT "NO SESSION". NoSession sends the
// daemon on to ask whether this is a headless box, which reads as unlocked;
// a system bus or a logind that does not answer must read as a lock state
// that cannot be read (Unknown, which the fence treats as locked), or one
// fault on a desktop would turn its locked screen into "unlocked". Only a
// machine with no loginctl at all is taken as having no logind.
func (p Prober) linux(ctx context.Context) Result {
	if p.Login1 != nil {
		if ss, err := p.Login1(ctx); err == nil {
			return LinuxDecide(ss, p.UID)
		}
	}
	out, err := p.Exec(ctx, "loginctl", "list-sessions", "--no-legend")
	if errors.Is(err, exec.ErrNotFound) {
		return Result{Reading: Unknown, NoSession: true,
			Reason: "no loginctl here (" + firstLine(out, err) + "); no session to read a lock from"}
	}
	if err != nil {
		return Result{Reading: Unknown,
			Reason: "logind could not list sessions (" + firstLine(out, err) + "), so the lock state cannot be read"}
	}
	ss := ParseListSessions(out)
	var mine []Session
	for _, s := range ss {
		if p.UID >= 0 && s.UID != p.UID {
			continue
		}
		show, err := p.Exec(ctx, "loginctl", "show-session", s.ID,
			"-p", "Type", "-p", "Class", "-p", "State", "-p", "LockedHint", "-p", "Remote")
		if err != nil {
			if strings.Contains(show, "No session '"+s.ID+"' known") {
				continue // the session went away between the two calls
			}
			return Result{Reading: Unknown, Session: s.ID,
				Reason: "logind could not read session " + s.ID + " (" + firstLine(show, err) + "), so the lock state cannot be read"}
		}
		ParseShowSession(show, &s)
		mine = append(mine, s)
	}
	return LinuxDecide(mine, p.UID)
}

func firstLine(out string, err error) string {
	if b := bytes.TrimSpace([]byte(out)); len(b) > 0 {
		if i := bytes.IndexByte(b, '\n'); i >= 0 {
			b = b[:i]
		}
		return string(b)
	}
	return err.Error()
}

func execCombined(ctx context.Context, name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.WaitDelay = time.Second
	b, err := cmd.CombinedOutput()
	return string(b), err
}
