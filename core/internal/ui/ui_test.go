package ui

import (
	"bytes"
	"strings"
	"testing"
	"time"
)

// The columns must match the shell clients' (_huginn_ui_line: glyph at 5,
// component at 8, text at 18), or one `huginn update` prints two layouts.
func TestLineColumns(t *testing.T) {
	var b bytes.Buffer
	o := For(&b, false, false)
	o.Line(OK, "core", "2.4.0 installed")
	o.Line(Fail, "desktop", "x")
	o.Line(Warn, "ssh", "y")
	o.Line(Skip, "local AI", "z")
	want := "     +  core      2.4.0 installed\n" +
		"     x  desktop   x\n" +
		"     !  ssh       y\n" +
		"     -  local AI  z\n"
	if b.String() != want {
		t.Fatalf("got\n%q\nwant\n%q", b.String(), want)
	}
	if i := strings.Index(strings.Split(b.String(), "\n")[0], "2.4.0"); i != 18 {
		t.Fatalf("text starts at %d, want 18", i)
	}
}

func TestUnicodeMarks(t *testing.T) {
	var b bytes.Buffer
	o := For(&b, false, true)
	o.Line(OK, "a", "")
	o.Line(Fail, "a", "")
	o.Line(Warn, "a", "")
	o.Line(Skip, "a", "")
	for _, g := range []string{"\u25a0", "\u00d7", "\u25b2", "\u25e6"} {
		if !strings.Contains(b.String(), "     "+g+"  a") {
			t.Fatalf("mark %q missing at column 5: %q", g, b.String())
		}
	}
}

// Not a terminal: no spinner frames, no escapes, nothing but the lines.
func TestPipedIsPlain(t *testing.T) {
	var b bytes.Buffer
	o := For(&b, false, true)
	o.Head("huginn uninstall")
	o.Spin("core", "stopping")
	time.Sleep(300 * time.Millisecond)
	o.Line(OK, "core", "removed")
	o.Note("detail")
	if strings.Contains(b.String(), "\x1b") || strings.Contains(b.String(), "\r") {
		t.Fatalf("escapes in piped output: %q", b.String())
	}
	if !strings.HasPrefix(b.String(), "(o> huginn uninstall\n") || !strings.Contains(b.String(), "\n"+strings.Repeat(" ", 18)+"detail\n") {
		t.Fatalf("got %q", b.String())
	}
}

// On a terminal the wingbeat turns, then the settled line replaces it.
func TestSpinSettles(t *testing.T) {
	var b bytes.Buffer
	o := For(&b, true, true)
	o.Spin("core", "stopping")
	time.Sleep(400 * time.Millisecond)
	o.Line(OK, "core", "removed")
	s := b.String()
	if strings.Count(s, "core      stopping") < 2 {
		t.Fatalf("spinner did not redraw: %q", s)
	}
	for _, f := range []string{"\u2500\u2584\u2500", "\u2572\u2584\u2571"} {
		if !strings.Contains(s, f) {
			t.Fatalf("frame %q missing: %q", f, s)
		}
	}
	if !strings.HasSuffix(s, "\r\x1b[K     \x1b[96m\u25a0\x1b[0m  core      removed\n") {
		t.Fatalf("settled line: %q", s[len(s)-60:])
	}
	o.Stop() // idempotent
}

// The elevated Windows step's lines travel as records the client redraws.
func TestRecords(t *testing.T) {
	var b bytes.Buffer
	o := Records(&b)
	o.Head("ignored")
	o.Spin("service", "ignored")
	o.Line(OK, "service", "registered\tand started")
	o.Line(Warn, "tray", "x")
	o.Note("line one\nline two")
	o.Say("said")
	want := "ok\tservice\tregistered and started\n" +
		"warn\ttray\tx\n" +
		"note\t\tline one\n" +
		"note\t\tline two\n" +
		"say\t\tsaid\n"
	if b.String() != want {
		t.Fatalf("got %q", b.String())
	}
}
