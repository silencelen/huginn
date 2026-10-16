package console

import (
	"os"
	"runtime"
	"strings"

	"github.com/charmbracelet/lipgloss"
)

// The brand in a terminal (assets/brand/BRAND.md §3, §4): the ANSI-16 slot
// pins (brightRed error, brightCyan success, brightYellow needs-you,
// brightMagenta info and the CLI's accent), moss as the accent in truecolor,
// the state glyphs with their 7-bit twins, and no meaning carried by colour
// alone. lipgloss degrades to 256/16 colours and honours NO_COLOR by itself.

func tone(true24, ansi string) lipgloss.TerminalColor {
	return lipgloss.CompleteColor{TrueColor: true24, ANSI256: ansi, ANSI: ansi}
}

var (
	cAccent  = lipgloss.CompleteColor{TrueColor: "#7CB98E", ANSI256: "13", ANSI: "13"} // moss / heather
	cWorking = tone("#7CB98E", "2")
	cNeeds   = tone("#E8D07E", "11")
	cError   = tone("#EE757D", "9")
	cOK      = tone("#80CFC9", "14")
	cInfo    = tone("#B49CDB", "13")
	cDim     = tone("#747D77", "8")
	cText    = tone("#E2E6E1", "15")
	cSelBg   = lipgloss.CompleteColor{TrueColor: "#1C1F1D", ANSI256: "235", ANSI: "0"}
)

type theme struct {
	ascii bool

	title, heading, dim, text, key, err, ok, needs, working, info lipgloss.Style
	selected                                                      lipgloss.Style
	frame                                                         lipgloss.Style
}

func newTheme() theme {
	t := theme{ascii: useASCII()}
	t.title = lipgloss.NewStyle().Foreground(cAccent).Bold(true)
	t.heading = lipgloss.NewStyle().Foreground(cInfo).Bold(true)
	t.dim = lipgloss.NewStyle().Foreground(cDim)
	t.text = lipgloss.NewStyle().Foreground(cText)
	t.key = lipgloss.NewStyle().Foreground(cAccent).Bold(true)
	t.err = lipgloss.NewStyle().Foreground(cError)
	t.ok = lipgloss.NewStyle().Foreground(cOK)
	t.needs = lipgloss.NewStyle().Foreground(cNeeds).Bold(true)
	t.working = lipgloss.NewStyle().Foreground(cWorking)
	t.info = lipgloss.NewStyle().Foreground(cInfo)
	// Selection: a tier-1 ground in colour, reverse video without it. Never a
	// left accent bar (owner taste).
	t.selected = lipgloss.NewStyle().Background(cSelBg).Foreground(cText)
	if os.Getenv("NO_COLOR") != "" {
		t.selected = lipgloss.NewStyle().Reverse(true)
	}
	border := lipgloss.RoundedBorder()
	if t.ascii {
		border = lipgloss.Border{Top: "-", Bottom: "-", Left: "|", Right: "|", TopLeft: "+", TopRight: "+", BottomLeft: "+", BottomRight: "+", MiddleLeft: "+", MiddleRight: "+", Middle: "+", MiddleTop: "+", MiddleBottom: "+"}
	}
	t.frame = lipgloss.NewStyle().Border(border).BorderForeground(cDim)
	return t
}

// useASCII: the 7-bit twins when Unicode is not safe — asked for, a locale
// that is not UTF-8, or the classic Windows console (its default fonts break
// the half-blocks), unless Windows Terminal says it is there.
func useASCII() bool {
	if v := os.Getenv("HUGINN_ASCII"); v != "" {
		return v != "0"
	}
	if runtime.GOOS == "windows" {
		return os.Getenv("WT_SESSION") == "" && os.Getenv("TERM_PROGRAM") == ""
	}
	for _, k := range []string{"LC_ALL", "LC_CTYPE", "LANG"} {
		if v := os.Getenv(k); v != "" {
			v = strings.ToLower(v)
			return !strings.Contains(v, "utf-8") && !strings.Contains(v, "utf8")
		}
	}
	return false
}

// state is one of the brand's seven words.
type state int

const (
	stShell state = iota
	stWorking
	stNeeds
	stWaiting
	stOK
	stError
	stInfo
)

var stateWords = map[state]string{stShell: "shell", stWorking: "working", stNeeds: "needs you", stWaiting: "waiting", stOK: "ok", stError: "error", stInfo: "info"}

func (t theme) glyph(s state) string {
	uni := map[state]string{stShell: "◦", stWorking: "●", stNeeds: "▲", stWaiting: "○", stOK: "■", stError: "×", stInfo: "▸"}
	seven := map[state]string{stShell: "-", stWorking: "*", stNeeds: "!", stWaiting: "o", stOK: "+", stError: "x", stInfo: ">"}
	g := uni[s]
	if t.ascii {
		g = seven[s]
	}
	switch s {
	case stWorking:
		return t.working.Render(g)
	case stNeeds:
		return t.needs.Render(g)
	case stError:
		return t.err.Render(g)
	case stOK:
		return t.ok.Render(g)
	case stInfo:
		return t.info.Render(g)
	}
	return t.dim.Render(g)
}

// sessionState maps appd's hook state to the brand word.
func sessionState(s string) state {
	switch s {
	case "running":
		return stWorking
	case "attention":
		return stNeeds
	case "idle":
		return stWaiting
	}
	return stShell
}

func (t theme) cursor() string {
	if t.ascii {
		return ">"
	}
	return "›"
}
