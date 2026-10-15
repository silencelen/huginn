package lock

import (
	"context"
	"errors"
	"os/exec"
	"strings"
	"testing"
	"time"
)

// Fixtures are the literal shapes quser prints (ported from LockProbeTest.kt).
var (
	consoleActive = strings.ToLower(` USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
>silencelen            console             1  Active      none   9/14/2026 8:02 AM`)
	rdpActive = strings.ToLower(` USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
>silencelen            rdp-tcp#7           2  Active          .  9/14/2026 8:02 AM`)
	rdpDisconnected = strings.ToLower(` USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
 silencelen                                1  Disc         1:20  9/14/2026 8:02 AM`)
	noUser = "no user exists for *"
	both   = strings.ToLower(` USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME
>silencelen            rdp-tcp#7           2  Active          .  9/14/2026 8:02 AM
 someone                                   3  Disc         1:20  9/13/2026 1:00 PM`)
)

func set(ids ...int) map[int]bool {
	m := map[int]bool{}
	for _, i := range ids {
		m[i] = true
	}
	return m
}

func TestParseQuser(t *testing.T) {
	c := ParseQuser(consoleActive)
	if len(c) != 1 || c[0].Session != "console" || c[0].ID != 1 || c[0].State != "active" || !c[0].Current {
		t.Fatalf("%+v", c)
	}
	d := ParseQuser(rdpDisconnected)
	if len(d) != 1 || d[0].Session != "" || d[0].ID != 1 || d[0].State != "disc" {
		t.Fatalf("disconnected: %+v", d)
	}
	if len(ParseQuser(noUser)) != 0 || len(ParseQuser("")) != 0 || len(ParseQuser(both)) != 2 {
		t.Fatal("rows")
	}
	if r := ParseQuser(strings.ReplaceAll(rdpActive, "\n", "\r\n")); len(r) != 1 || r[0].ID != 2 {
		t.Fatalf("CRLF: %+v", r)
	}
}

func TestWindowsReading(t *testing.T) {
	cases := []struct {
		name  string
		quser string
		ids   map[int]bool
		want  Reading
	}{
		{"active rdp is somebody", rdpActive, set(1), Unlocked},
		{"beside a stale disc session", both, set(1), Unlocked},
		{"disc is nobody", rdpDisconnected, set(1), Locked},
		{"disc with no lock screen anywhere", rdpDisconnected, set(), Unlocked},
		{"console unlocked", consoleActive, set(), Unlocked},
		{"console locked (LogonUI tells them apart)", consoleActive, set(1), Locked},
		{"sign-in screen", noUser, set(1), Locked},
		{"nobody, nothing up", noUser, set(), Unlocked},
		{"lock screen in the rdp session", rdpActive, set(1, 2), Locked},
		{"id must match the row", both, set(2), Locked},
		{"LogonUI unanswerable", rdpActive, nil, Unknown},
		{"no quser falls back", "", set(), Unlocked},
		{"nonsense", "nonsense that is not a table", set(1), Locked},
	}
	for _, c := range cases {
		if got := WindowsReading(c.quser, c.ids); got != c.want {
			t.Errorf("%s: got %s want %s", c.name, got, c.want)
		}
	}
}

func TestParseWindows(t *testing.T) {
	out := "LOGONUI 1\r\nQUSER\r\n USERNAME              SESSIONNAME        ID  STATE   IDLE TIME  LOGON TIME\r\n>silencelen            rdp-tcp#7           2  Active          .  9/14/2026 8:02 AM\r\n"
	if r := ParseWindows(out); r.Reading != Unlocked {
		t.Fatalf("%+v", r)
	}
	if r := ParseWindows("LOGONUI \r\nQUSER\r\nNo User exists for *\r\n"); r.Reading != Unlocked {
		t.Fatalf("empty LogonUI: %+v", r)
	}
	if r := ParseWindows("LOGONUI 1 3\nQUSER\n"); r.Reading != Locked {
		t.Fatalf("%+v", r)
	}
	for _, bad := range []string{"", "Get-Process : something", "LOGONUI 1"} {
		if r := ParseWindows(bad); r.Reading != Unknown || r.Reason == "" {
			t.Fatalf("%q: %+v", bad, r)
		}
	}
}

func TestReadingFence(t *testing.T) {
	if !Unknown.Locked() || Unknown.Known() || !Locked.Locked() || !Locked.Known() || Unlocked.Locked() || !Unlocked.Known() {
		t.Fatal("fence")
	}
}

// The Omarchy capture from the brief: a wayland user session on seat0 and
// the user manager's own session.
const omarchyList = "1 1000 silence seat0 1197 user tty1 no -\n2 1000 silence - 1207 manager - no -\n"

var omarchyShow = map[string]string{
	"1": "Type=wayland\nClass=user\nState=active\nLockedHint=no\nRemote=no\n",
	"2": "Type=unspecified\nClass=manager\nState=active\nLockedHint=no\nRemote=no\n",
}

func TestParseListSessions(t *testing.T) {
	ss := ParseListSessions(omarchyList)
	if len(ss) != 2 || ss[0].ID != "1" || ss[0].UID != 1000 || ss[0].User != "silence" || ss[1].ID != "2" {
		t.Fatalf("%+v", ss)
	}
	// Older layout, and a legend + footer that must be skipped.
	old := "SESSION  UID USER    SEAT  TTY\n      3 1000 silence seat0 tty2\n\n1 sessions listed.\n"
	ss = ParseListSessions(old)
	if len(ss) != 1 || ss[0].ID != "3" {
		t.Fatalf("old: %+v", ss)
	}
}

func fakeLoginctl(list string, show map[string]string, listErr error) func(ctx context.Context, name string, args ...string) (string, error) {
	return func(ctx context.Context, name string, args ...string) (string, error) {
		if name != "loginctl" {
			return "", errors.New("unexpected " + name)
		}
		switch args[0] {
		case "list-sessions":
			return list, listErr
		case "show-session":
			if s, ok := show[args[1]]; ok {
				return s, nil
			}
			return "Failed to get session: No session '" + args[1] + "' known", &exec.ExitError{}
		}
		return "", errors.New("unexpected args")
	}
}

func TestLinuxOmarchyUnlocked(t *testing.T) {
	p := Prober{GOOS: "linux", UID: 1000, Exec: fakeLoginctl(omarchyList, omarchyShow, nil)}
	r := p.Probe(context.Background())
	if r.Reading != Unlocked || r.Session != "1" {
		t.Fatalf("%+v", r)
	}
	show := map[string]string{"1": strings.Replace(omarchyShow["1"], "LockedHint=no", "LockedHint=yes", 1), "2": omarchyShow["2"]}
	p.Exec = fakeLoginctl(omarchyList, show, nil)
	if r := p.Probe(context.Background()); r.Reading != Locked || r.Session != "1" {
		t.Fatalf("locked: %+v", r)
	}
}

func TestLinuxHeadlessIsUnknownNotLocked(t *testing.T) {
	// A lingering service on a headless box: only the manager session.
	p := Prober{GOOS: "linux", UID: 1000, Exec: fakeLoginctl("2 1000 silence - 1207 manager - no -\n", omarchyShow, nil)}
	r := p.Probe(context.Background())
	if r.Reading != Unknown || !r.NoSession || !strings.Contains(r.Reason, "no graphical session") {
		t.Fatalf("%+v", r)
	}
	if strings.Contains(strings.ToLower(r.Reason), "unlock") {
		t.Fatal("must not tell anybody to unlock a screen")
	}
	// No sessions at all; and logind absent.
	p.Exec = fakeLoginctl("", nil, nil)
	if r := p.Probe(context.Background()); r.Reading != Unknown || !r.NoSession {
		t.Fatalf("%+v", r)
	}
	p.Exec = fakeLoginctl("", nil, errors.New("exec: \"loginctl\": executable file not found in $PATH"))
	if r := p.Probe(context.Background()); r.Reading != Unknown || !r.NoSession || !strings.Contains(r.Reason, "loginctl") {
		t.Fatalf("%+v", r)
	}
}

func TestLinuxOtherUsersAndTiers(t *testing.T) {
	ss := []Session{
		{ID: "5", UID: 1001, Type: "wayland", Class: "user", State: "active", LockedHint: "no"}, // someone else
		{ID: "6", UID: 1000, Type: "x11", Class: "greeter", State: "active", LockedHint: "no"},  // greeter
		{ID: "7", UID: 1000, Type: "tty", Class: "user", State: "active", LockedHint: "no"},     // ssh/tty
		{ID: "8", UID: 1000, Type: "x11", Class: "user", State: "online", LockedHint: "yes"},    // VT-switched away
		{ID: "9", UID: 1000, Type: "wayland", Class: "user", State: "active", Remote: "yes", LockedHint: "no"},
	}
	r := LinuxDecide(ss, 1000)
	if r.Reading != Locked || r.Session != "8" {
		t.Fatalf("%+v", r)
	}
	ss = append(ss, Session{ID: "10", UID: 1000, Type: "wayland", Class: "user", State: "active", LockedHint: "yes"},
		Session{ID: "11", UID: 1000, Type: "wayland", Class: "user", State: "active", LockedHint: "no"})
	if r := LinuxDecide(ss, 1000); r.Reading != Unlocked || r.Session != "11" {
		t.Fatalf("any active unlocked: %+v", r)
	}
	if r := LinuxDecide([]Session{{ID: "1", UID: 1000, Type: "wayland", Class: "user", State: "active"}}, 1000); r.Reading != Unknown || r.NoSession {
		t.Fatalf("no hint: %+v", r)
	}
}

func TestLogin1PreferredThenFallback(t *testing.T) {
	called := false
	p := Prober{GOOS: "linux", UID: 1000,
		Login1: func(context.Context) ([]Session, error) {
			return []Session{{ID: "1", UID: 1000, Type: "wayland", Class: "user", State: "active", LockedHint: "yes"}}, nil
		},
		Exec: func(context.Context, string, ...string) (string, error) { called = true; return "", nil },
	}
	if r := p.Probe(context.Background()); r.Reading != Locked || called {
		t.Fatalf("%+v called=%v", r, called)
	}
	p.Login1 = func(context.Context) ([]Session, error) { return nil, errors.New("no system bus") }
	p.Exec = fakeLoginctl(omarchyList, omarchyShow, nil)
	if r := p.Probe(context.Background()); r.Reading != Unlocked {
		t.Fatalf("fallback: %+v", r)
	}
}

func TestProbeTimeout(t *testing.T) {
	p := Prober{GOOS: "windows", Timeout: 20 * time.Millisecond,
		Exec: func(ctx context.Context, _ string, _ ...string) (string, error) {
			<-ctx.Done()
			return "", ctx.Err()
		}}
	start := time.Now()
	r := p.Probe(context.Background())
	if r.Reading != Unknown || !strings.Contains(r.Reason, "did not answer") || time.Since(start) > time.Second {
		t.Fatalf("%+v", r)
	}
}

func TestWindowsProbeExitCodeStillParsed(t *testing.T) {
	p := Prober{GOOS: "windows", Exec: func(context.Context, string, ...string) (string, error) {
		return "LOGONUI 1\nQUSER\n", &exec.ExitError{}
	}}
	if r := p.Probe(context.Background()); r.Reading != Locked {
		t.Fatalf("%+v", r)
	}
	p.Exec = func(context.Context, string, ...string) (string, error) { return "", exec.ErrNotFound }
	if r := p.Probe(context.Background()); r.Reading != Unknown || !strings.Contains(r.Reason, "PowerShell") {
		t.Fatalf("%+v", r)
	}
}

func TestOtherOS(t *testing.T) {
	if r := (Prober{GOOS: "darwin"}).Probe(context.Background()); r.Reading != Unknown || r.NoSession {
		t.Fatalf("%+v", r)
	}
}
