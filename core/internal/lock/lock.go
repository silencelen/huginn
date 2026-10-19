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
// Windows: one PowerShell call reads the session ids LogonUI.exe runs in, the
// account it runs as and `quser`. Only that account's own sessions count: a
// connected console or rdp-tcp#N session of its that is not showing LogonUI is
// somebody here. Another account's session never is. All of it works from
// session 0, where the boot task runs.
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
// the session name (if any) is the field just before it, and the state
// follows it. Anything that is not a row is ignored ("No User exists for *",
// localised headers). Pure.
func ParseQuser(text string) []QuserRow {
	var rows []QuserRow
	for _, raw := range strings.Split(text, "\n") {
		if row, ok := parseQuserRow(raw); ok {
			rows = append(rows, row)
		}
	}
	return rows
}

func parseQuserRow(raw string) (QuserRow, bool) {
	raw = strings.TrimRight(raw, "\r")
	current := strings.HasPrefix(raw, ">")
	fields := strings.Fields(strings.TrimPrefix(raw, ">"))
	if len(fields) < 3 {
		return QuserRow{}, false
	}
	idAt := firstNumber(fields)
	if idAt < 1 || idAt+1 >= len(fields) {
		return QuserRow{}, false
	}
	id, _ := strconv.Atoi(fields[idAt])
	row := QuserRow{User: fields[0], ID: id, State: strings.ToLower(fields[idAt+1]), Current: current}
	if idAt >= 2 {
		row.Session = fields[idAt-1]
	}
	return row, true
}

func firstNumber(fields []string) int {
	for i, f := range fields {
		if _, err := strconv.Atoi(f); err == nil {
			return i
		}
	}
	return -1
}

// ownerRow reads raw as one of owner's rows, or says it is not one. quser pads
// USERNAME to a column, and a Windows account name can hold a space ("alex
// smith"), so the row must start with the whole name and then hold at most a
// session name before the id: "alex" does not claim "alex smith"'s row, nor
// "alexander"'s. Both sides compare in lower case. Pure.
func ownerRow(raw, owner string) (QuserRow, bool) {
	raw = strings.TrimRight(raw, "\r")
	current := strings.HasPrefix(raw, ">")
	body := strings.ToLower(strings.TrimLeft(strings.TrimPrefix(raw, ">"), " \t"))
	owner = strings.ToLower(strings.TrimSpace(owner))
	rest, ok := strings.CutPrefix(body, owner)
	if owner == "" || !ok || rest == "" || (rest[0] != ' ' && rest[0] != '\t') {
		return QuserRow{}, false
	}
	fields := strings.Fields(rest)
	idAt := firstNumber(fields)
	if idAt < 0 || idAt > 1 || idAt+1 >= len(fields) {
		return QuserRow{}, false
	}
	id, _ := strconv.Atoi(fields[idAt])
	row := QuserRow{User: owner, ID: id, State: fields[idAt+1], Current: current}
	if idAt == 1 {
		row.Session = fields[0]
	}
	return row, true
}

// interactive: a console or rdp-tcp#N session name. quser prints a session
// name only for a connected session (a disconnected row leaves the column
// empty), and the name, unlike the STATE text ("Active", "Aktiv", "Actif"), is
// the same in every language.
func interactive(session string) bool {
	s := strings.ToLower(session)
	return s == "console" || rdpSession.MatchString(s)
}

// WindowsReading is the rule as a pure function, for owner, the account the
// core runs as (as [Environment]::UserName prints it; any case). logonUI nil
// = LogonUI could not be asked, and no owner = the account is not known:
// either is Unknown.
//
// ⚠ ONLY OWNER'S OWN SESSIONS SAY "SOMEBODY IS HERE". A connected console or
// rdp-tcp#N session of owner's that is not showing LogonUI is unlocked.
// Otherwise a lock or sign-in screen anywhere is locked, and so is another
// account's connected session: somebody else at this machine is not the owner
// (a shared PC, another account's RDP session while the owner's screen is
// locked). With neither, nothing is up at all: unlocked, as before.
func WindowsReading(quser string, logonUI map[int]bool, owner string) Reading {
	rd, _ := windowsDecide(quser, logonUI, owner)
	return rd
}

func windowsDecide(quser string, logonUI map[int]bool, owner string) (Reading, string) {
	if logonUI == nil {
		return Unknown, "the lock probe could not list the sessions LogonUI runs in"
	}
	if strings.TrimSpace(owner) == "" {
		return Unknown, "the lock probe could not tell which account it runs as"
	}
	other := false
	for _, raw := range strings.Split(quser, "\n") {
		if r, ok := ownerRow(raw, owner); ok {
			if interactive(r.Session) && !logonUI[r.ID] {
				return Unlocked, "this account's session " + strconv.Itoa(r.ID) + " is signed in and unlocked"
			}
			continue
		}
		if r, ok := parseQuserRow(raw); ok && (r.State == "active" || interactive(r.Session)) {
			other = true
		}
	}
	switch {
	case len(logonUI) > 0:
		return Locked, "a lock or sign-in screen is up"
	case other:
		return Locked, "another account is signed in here, and this one is not"
	}
	return Unlocked, "no lock or sign-in screen is up"
}

// WindowsScript is the one PowerShell command whose output ParseWindows
// reads: all of it from one child, so it describes one moment. The account
// name comes from the same child as quser's rows, so the two are written in
// the same encoding, whatever the console's code page does to a name.
const WindowsScript = "$ErrorActionPreference='SilentlyContinue'; " +
	"$s=@(Get-Process LogonUI | Select-Object -ExpandProperty SessionId); " +
	"Write-Output ('LOGONUI ' + ($s -join ' ')); " +
	"Write-Output ('ME ' + [Environment]::UserName); " +
	"Write-Output 'QUSER'; " +
	"$q = & quser 2>&1; if ($q) { $q | Write-Output }"

// ParseWindows turns the script's output into a result: the LOGONUI line,
// the ME line and everything after the QUSER line, found line by line (a row
// of quser's can hold any of those words). Pure.
func ParseWindows(out string) Result {
	lines := strings.Split(strings.ReplaceAll(strings.ToLower(out), "\r", ""), "\n")
	var ids map[int]bool
	owner, at := "", -1
	for i, l := range lines {
		t := strings.TrimSpace(l)
		switch {
		case ids == nil && (t == "logonui" || strings.HasPrefix(t, "logonui ")):
			ids = map[int]bool{}
			for _, f := range strings.Fields(t[len("logonui"):]) {
				if n, err := strconv.Atoi(f); err == nil {
					ids[n] = true
				}
			}
		case ids != nil && owner == "" && strings.HasPrefix(t, "me "):
			owner = strings.TrimSpace(t[len("me "):])
		case ids != nil && t == "quser":
			at = i
		}
		if at >= 0 {
			break
		}
	}
	if ids == nil || at < 0 {
		return Result{Reading: Unknown, Reason: "the lock probe got an unexpected answer from PowerShell"}
	}
	rd, why := windowsDecide(strings.Join(lines[at+1:], "\n"), ids, owner)
	return Result{Reading: rd, Reason: why}
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
