// Package lock answers "is somebody at this machine?" for the act-mode fence
// — the Go port of the desktop's device/LockProbe.kt.
//
// Three answers, not two. Unknown is what a box with no session to ask about
// returns, and it is NOT the same as a screen somebody locked: the fence
// treats it as locked (Reading.Locked), but the UI must say the honest
// sentence, and "unlock your screen" is the wrong one for a headless box.
//
// This is a presence probe, not a permission: what a locked machine may do is
// the device policy's answer, and whether a lock withdraws anything here is
// the owner's actWhileLocked setting.
//
// Linux: the core runs as a systemd --user service, outside the graphical
// session, so XDG_SESSION_ID is unset and `loginctl show-session self` has no
// self. The user's graphical session is found among all of the user's logind
// sessions (D-Bus org.freedesktop.login1 first, `loginctl` text as the
// fallback) and its LockedHint is read.
//
// Windows: one PowerShell call reads the session ids LogonUI.exe runs in and
// `quser`; an active, unlocked rdp-tcp#N session counts as present. Both work
// from session 0, where the boot task runs.
package lock

import (
	"regexp"
	"sort"
	"strconv"
	"strings"
)

// Reading is what the probe found.
type Reading int

const (
	Unknown Reading = iota
	Locked
	Unlocked
)

func (r Reading) String() string {
	switch r {
	case Locked:
		return "locked"
	case Unlocked:
		return "unlocked"
	}
	return "unknown"
}

// Locked is the fence's answer: unknown is locked.
func (r Reading) Locked() bool { return r != Unlocked }

// Known: whether the probe answered at all — the UI's question, never the fence's.
func (r Reading) Known() bool { return r != Unknown }

// Result is a reading plus why.
type Result struct {
	Reading Reading
	// Reason is a printable sentence, always set for Unknown.
	Reason string
	// NoSession: Unknown because there is no graphical session at all
	// (headless box, nobody logged in). The UI must not tell anybody to
	// unlock a screen.
	NoSession bool
	// Session is the logind session id the Linux reading came from.
	Session string
}

// ----------------------------------------------------------------- windows

// QuserRow is one row of `quser`, as printed. Session is empty for a
// disconnected session (quser prints nothing in that column).
type QuserRow struct {
	User    string
	Session string
	ID      int
	State   string
	Current bool
}

var rdpSession = regexp.MustCompile(`^rdp-tcp#\d+$`)

// ParseQuser parses quser output, tolerating the missing SESSIONNAME of a
// disconnected row: the id is the first bare-number field after the user,
// and the state follows it. Anything that is not a row is ignored ("No User
// exists for *", localised headers). Pure.
func ParseQuser(text string) []QuserRow {
	var rows []QuserRow
	for _, raw := range strings.Split(text, "\n") {
		raw = strings.TrimRight(raw, "\r")
		current := strings.HasPrefix(raw, ">")
		fields := strings.Fields(strings.TrimPrefix(raw, ">"))
		if len(fields) < 3 {
			continue
		}
		idAt := -1
		for i, f := range fields {
			if _, err := strconv.Atoi(f); err == nil {
				idAt = i
				break
			}
		}
		if idAt < 1 || idAt+1 >= len(fields) {
			continue
		}
		id, _ := strconv.Atoi(fields[idAt])
		row := QuserRow{User: fields[0], ID: id, State: strings.ToLower(fields[idAt+1]), Current: current}
		if idAt >= 2 {
			row.Session = fields[1]
		}
		rows = append(rows, row)
	}
	return rows
}

// WindowsReading is the rule as a pure function. logonUI nil = LogonUI could
// not be asked → Unknown. An active rdp-tcp#N session that is not itself
// showing LogonUI is somebody here; otherwise a lock screen anywhere is
// locked, and none is unlocked.
func WindowsReading(quser string, logonUI map[int]bool) Reading {
	if logonUI == nil {
		return Unknown
	}
	for _, r := range ParseQuser(quser) {
		if r.State == "active" && rdpSession.MatchString(strings.ToLower(r.Session)) && !logonUI[r.ID] {
			return Unlocked
		}
	}
	if len(logonUI) > 0 {
		return Locked
	}
	return Unlocked
}

// WindowsScript is the one PowerShell command whose output ParseWindows
// reads: both halves from one child, so they describe the same moment.
const WindowsScript = "$ErrorActionPreference='SilentlyContinue'; " +
	"$s=@(Get-Process LogonUI | Select-Object -ExpandProperty SessionId); " +
	"Write-Output ('LOGONUI ' + ($s -join ' ')); " +
	"Write-Output 'QUSER'; " +
	"$q = & quser 2>&1; if ($q) { $q | Write-Output }"

// ParseWindows turns the script's output into a result. Pure.
func ParseWindows(out string) Result {
	out = strings.ToLower(out)
	i := strings.Index(out, "logonui")
	if i < 0 {
		return Result{Reading: Unknown, Reason: "the lock probe got an unexpected answer from PowerShell"}
	}
	body := out[i+len("logonui"):]
	k := strings.Index(body, "quser")
	if k < 0 {
		return Result{Reading: Unknown, Reason: "the lock probe got an unexpected answer from PowerShell"}
	}
	ids := map[int]bool{}
	for _, f := range strings.Fields(body[:k]) {
		if n, err := strconv.Atoi(f); err == nil {
			ids[n] = true
		}
	}
	rd := WindowsReading(body[k+len("quser"):], ids)
	res := Result{Reading: rd}
	switch rd {
	case Locked:
		res.Reason = "a lock or sign-in screen is up"
	case Unlocked:
		res.Reason = "a session is signed in and unlocked"
	}
	return res
}

// ------------------------------------------------------------------- linux

// Session is one logind session as the Linux probe sees it.
type Session struct {
	ID         string
	UID        int // -1 when unknown
	User       string
	Type       string // wayland, x11, mir, tty, unspecified
	Class      string // user, greeter, lock-screen, manager, background
	State      string // active, online, closing
	LockedHint string // yes, no, or "" when not reported
	Remote     string // yes, no, ""
}

// ParseListSessions reads `loginctl list-sessions --no-legend` (any column
// layout: only SESSION, UID and USER are read, and they are the first three
// in every systemd version). Header and footer lines are skipped because
// their second field is not a number. Pure.
func ParseListSessions(out string) []Session {
	var ss []Session
	for _, line := range strings.Split(out, "\n") {
		f := strings.Fields(line)
		if len(f) < 3 {
			continue
		}
		uid, err := strconv.Atoi(f[1])
		if err != nil {
			continue
		}
		ss = append(ss, Session{ID: f[0], UID: uid, User: f[2]})
	}
	return ss
}

// ParseShowSession reads `loginctl show-session <id> -p …` (Key=Value lines)
// into s. Pure.
func ParseShowSession(out string, s *Session) {
	for _, line := range strings.Split(out, "\n") {
		k, v, ok := strings.Cut(strings.TrimSpace(line), "=")
		if !ok {
			continue
		}
		v = strings.ToLower(strings.TrimSpace(v))
		switch k {
		case "Type":
			s.Type = v
		case "Class":
			s.Class = v
		case "State":
			s.State = v
		case "LockedHint":
			s.LockedHint = v
		case "Remote":
			s.Remote = v
		case "Id":
			if s.ID == "" {
				s.ID = v
			}
		}
	}
}

func graphical(t string) bool { return t == "wayland" || t == "x11" || t == "mir" }

// LinuxDecide picks the user's graphical session(s) and reads the lock.
// Candidates: uid matches (when known), class=user, type wayland/x11/mir,
// not remote. Active sessions are consulted first, then online ones (the
// user switched VT away). Within the chosen tier, any unlocked session means
// somebody is there; all locked is locked; a missing LockedHint is unknown.
// Pure.
func LinuxDecide(ss []Session, uid int) Result {
	tiers := map[string][]Session{}
	for _, s := range ss {
		if uid >= 0 && s.UID >= 0 && s.UID != uid {
			continue
		}
		if s.Class != "user" || !graphical(s.Type) || s.Remote == "yes" {
			continue
		}
		tiers[s.State] = append(tiers[s.State], s)
	}
	for _, state := range []string{"active", "online"} {
		c := tiers[state]
		if len(c) == 0 {
			continue
		}
		sort.SliceStable(c, func(i, j int) bool { return idLess(c[i].ID, c[j].ID) })
		var locked *Session
		for i := range c {
			switch c[i].LockedHint {
			case "no":
				return Result{Reading: Unlocked, Session: c[i].ID,
					Reason: "graphical session " + c[i].ID + " (" + c[i].Type + ") is unlocked"}
			case "yes":
				if locked == nil {
					locked = &c[i]
				}
			}
		}
		if locked != nil {
			return Result{Reading: Locked, Session: locked.ID,
				Reason: "graphical session " + locked.ID + " (" + locked.Type + ") is locked"}
		}
		return Result{Reading: Unknown, Session: c[0].ID,
			Reason: "graphical session " + c[0].ID + " did not report LockedHint"}
	}
	return Result{Reading: Unknown, NoSession: true,
		Reason: "no graphical session for this user (a headless box, or nobody is logged in to a desktop)"}
}

// idLess orders logind ids numerically when both are numbers ("2" < "10").
func idLess(a, b string) bool {
	if len(a) != len(b) {
		_, ea := strconv.Atoi(a)
		_, eb := strconv.Atoi(b)
		if ea == nil && eb == nil {
			return len(a) < len(b)
		}
	}
	return a < b
}
