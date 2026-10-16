// Package ui prints the installer-style progress the shell clients print
// (client/huginn.sh _huginn_ui_*, client/huginn.ps1 _Huginn-Ui*), so `huginn
// update`, `huginn uninstall` and `huginn core install` read as one program:
//
//	(o> huginn uninstall
//	     +  desktop   Huginn Desktop 2.3.6 and its settings
//	    -_- core      stopping the boot service
//
// (drawn here in the ASCII fallback; a UTF-8 terminal gets the brand marks).
// One line per component: the brand's state mark at column 5 (a filled
// square ok, a cross error, a triangle needs a look, a ring skipped), the
// component at 8, the text at 18. A busy step shows the wingbeat over the
// mark's column until its line settles. Everything
// goes to stderr; with no terminal there is no spinner and no colour, and the
// lines are the same text. NO_COLOR, HUGINN_ASCII=1|0 and
// HUGINN_REDUCED_MOTION behave as they do in the shell clients.
//
// Source stays 7-bit (the rule test-client.sh [5f] holds the clients to): the
// glyphs are written as escapes.
package ui

import (
	"fmt"
	"io"
	"os"
	"strings"
	"sync"
	"time"

	"golang.org/x/term"
)

// State is how a component ended.
type State int

const (
	OK State = iota
	Warn
	Fail
	Skip
)

// Out is one installer-style stream.
type Out struct {
	w      io.Writer
	tty    bool // spinner and \r redraws
	color  bool
	uni    bool
	still  bool // HUGINN_REDUCED_MOTION: one frame, no animation
	rec    bool // Records: tab-separated records, nothing drawn
	start  time.Time
	mu     sync.Mutex
	active *spinner
}

// New writes to stderr, deciding tty, colour and glyphs the way the shell
// clients do.
func New() *Out {
	tty := term.IsTerminal(int(os.Stderr.Fd())) && os.Getenv("TERM") != "dumb" && enableVT(os.Stderr)
	return &Out{
		w:     os.Stderr,
		tty:   tty,
		color: tty && os.Getenv("NO_COLOR") == "",
		uni:   unicodeOK(),
		still: os.Getenv("HUGINN_REDUCED_MOTION") != "",
		start: time.Now(),
	}
}

// For writes to w as a terminal or not, with explicit glyph choice: tests and
// a caller that renders into a buffer.
func For(w io.Writer, tty, uni bool) *Out {
	return &Out{w: w, tty: tty, color: tty, uni: uni, start: time.Now()}
}

// Records writes each line as a record - state, component and text, tab
// separated - instead of drawing it. Windows runs `huginn core install`'s
// elevated step in a window of its own that closes when it ends, so that step
// hands its lines to the client that asked for it, which draws them in the
// person's own terminal (client/huginn.ps1 _Huginn-UiRecords).
func Records(w io.Writer) *Out { return &Out{w: w, rec: true, start: time.Now()} }

func (o *Out) record(kind, comp, text string) {
	clean := strings.NewReplacer("\t", " ", "\r", " ", "\n", " ")
	o.mu.Lock()
	defer o.mu.Unlock()
	fmt.Fprintf(o.w, "%s\t%s\t%s\n", kind, clean.Replace(comp), clean.Replace(text))
}

var recordNames = map[State]string{OK: "ok", Warn: "warn", Fail: "fail", Skip: "skip"}

// TTY: the stream is a terminal (a caller may then prompt on it).
func (o *Out) TTY() bool { return o.tty }

func unicodeOK() bool {
	switch os.Getenv("HUGINN_ASCII") {
	case "1":
		return false
	case "0":
		return true
	}
	return platformUnicode()
}

func localeUTF8() bool {
	l := os.Getenv("LC_ALL")
	if l == "" {
		l = os.Getenv("LC_CTYPE")
	}
	if l == "" {
		l = os.Getenv("LANG")
	}
	l = strings.ToLower(l)
	return strings.Contains(l, "utf-8") || strings.Contains(l, "utf8")
}

func (o *Out) c(code string) string {
	if !o.color {
		return ""
	}
	return "\x1b[" + code + "m"
}

// Dim wraps s in the muted colour.
func (o *Out) Dim(s string) string { return o.c("90") + s + o.c("0") }

// Bold wraps s in bold.
func (o *Out) Bold(s string) string { return o.c("1") + s + o.c("0") }

func (o *Out) glyph(s State) (string, string) {
	if o.uni {
		switch s {
		case OK:
			return "\u25a0", "96"
		case Fail:
			return "\u00d7", "91"
		case Warn:
			return "\u25b2", "93"
		}
		return "\u25e6", "90"
	}
	switch s {
	case OK:
		return "+", "96"
	case Fail:
		return "x", "91"
	case Warn:
		return "!", "93"
	}
	return "-", "90"
}

func (o *Out) frames() []string {
	switch {
	case o.uni && o.still:
		return []string{"\u2500\u2584\u2500"}
	case o.still:
		return []string{"-_-"}
	case o.uni:
		return []string{"\u2500\u2584\u2500", "\u2572\u2584\u2571", "\u2500\u2584\u2500", "\u2571\u2580\u2572"}
	}
	return []string{"-_-", `\_/`, "-_-", `/'\`}
}

// clear ends a spinner's redraw on this line (the caller holds mu).
func (o *Out) clear() {
	if o.tty {
		io.WriteString(o.w, "\r\x1b[K")
	}
}

// Head is the block's title: the perched raven and the command.
func (o *Out) Head(title string) {
	if o.rec {
		return
	}
	o.Stop()
	o.mu.Lock()
	defer o.mu.Unlock()
	fmt.Fprintf(o.w, "%s(o>%s %s\n", o.c("95"), o.c("0"), title)
}

// Line settles one component.
func (o *Out) Line(s State, comp, text string) {
	if o.rec {
		o.record(recordNames[s], comp, text)
		return
	}
	o.Stop()
	o.mu.Lock()
	defer o.mu.Unlock()
	g, col := o.glyph(s)
	o.clear()
	fmt.Fprintf(o.w, "     %s%s%s  %-9s %s\n", o.c(col), g, o.c("0"), comp, text)
}

// Note is a muted line under the previous one, aligned with its text.
func (o *Out) Note(text string) {
	if o.rec {
		for _, l := range strings.Split(strings.TrimRight(text, "\n"), "\n") {
			o.record("note", "", l)
		}
		return
	}
	o.Stop()
	o.mu.Lock()
	defer o.mu.Unlock()
	for _, l := range strings.Split(strings.TrimRight(text, "\n"), "\n") {
		fmt.Fprintf(o.w, "%s%s\n", strings.Repeat(" ", 18), o.Dim(l))
	}
}

// Say is a plain line, indented like the block's body.
func (o *Out) Say(text string) {
	if o.rec {
		o.record("say", "", text)
		return
	}
	o.Stop()
	o.mu.Lock()
	defer o.mu.Unlock()
	if text == "" {
		io.WriteString(o.w, "\n")
		return
	}
	fmt.Fprintf(o.w, "    %s\n", text)
}

// Prompt writes text with no newline (a question the caller then reads).
func (o *Out) Prompt(text string) {
	if o.rec {
		return
	}
	o.Stop()
	o.mu.Lock()
	defer o.mu.Unlock()
	fmt.Fprintf(o.w, "    %s", text)
}

// Took is the closing "done in N s" line.
func (o *Out) Took(what string) {
	o.Say(o.Dim(fmt.Sprintf("%s in %d s", what, int(time.Since(o.start).Round(time.Second)/time.Second))))
}

type spinner struct {
	stop chan struct{}
	done chan struct{}
}

// Spin shows the wingbeat for comp while the caller works; the next Line,
// Note, Say, Head or Stop ends it. The caller must not write to the terminal
// itself while it turns (Stop first, e.g. before a sudo prompt).
func (o *Out) Spin(comp, what string) {
	o.Stop()
	if !o.tty || o.rec {
		return
	}
	sp := &spinner{stop: make(chan struct{}), done: make(chan struct{})}
	o.mu.Lock()
	o.active = sp
	o.mu.Unlock()
	fr := o.frames()
	go func() {
		defer close(sp.done)
		t := time.NewTicker(125 * time.Millisecond)
		defer t.Stop()
		for i := 0; ; i++ {
			o.mu.Lock()
			fmt.Fprintf(o.w, "\r\x1b[K    %s%s%s %-9s %s", o.c("92"), fr[i%len(fr)], o.c("0"), comp, what)
			o.mu.Unlock()
			select {
			case <-sp.stop:
				return
			case <-t.C:
			}
		}
	}()
}

// Stop ends a turning spinner and clears its line. Safe to call any time.
func (o *Out) Stop() {
	o.mu.Lock()
	sp := o.active
	o.active = nil
	o.mu.Unlock()
	if sp == nil {
		return
	}
	close(sp.stop)
	<-sp.done
	o.mu.Lock()
	o.clear()
	o.mu.Unlock()
}
