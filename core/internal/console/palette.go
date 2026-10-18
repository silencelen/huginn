package console

import (
	"bufio"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strings"

	"github.com/charmbracelet/bubbles/cursor"
	"github.com/charmbracelet/bubbles/textinput"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/sahilm/fuzzy"
)

// ---- / filter -----------------------------------------------------------------
//
// `/` narrows the list on screen as you type: every word must appear in the
// row (name, title, preview…), case-insensitive. ⏎ keeps it, esc clears it. A
// kept filter shows in the header; esc on that screen clears it before it goes
// back.

var filterable = map[screen]bool{scrSessions: true, scrChats: true, scrProjects: true, scrRounds: true, scrArchive: true, scrDevices: true}

func (m *model) startFilter() tea.Cmd {
	if !filterable[m.scr] {
		m.say("nothing to filter here", true)
		return nil
	}
	ti := textinput.New()
	ti.Prompt = ""
	ti.SetValue(m.filter[m.scr])
	ti.CharLimit = 200
	ti.Width = 40
	ti.Cursor.SetMode(cursor.CursorStatic)
	ti.Focus()
	m.filtering = &ti
	return nil
}

func (m *model) filterKey(k tea.KeyMsg) tea.Cmd {
	switch k.Type {
	case tea.KeyEsc, tea.KeyCtrlC:
		m.filtering = nil
		delete(m.filter, m.scr)
		m.cur[m.scr] = 0
		return nil
	case tea.KeyEnter, tea.KeyDown, tea.KeyUp:
		m.filtering = nil
		if k.Type != tea.KeyEnter {
			m.move(k.String(), 1<<30)
		}
		return nil
	}
	var cmd tea.Cmd
	*m.filtering, cmd = m.filtering.Update(k)
	if v := strings.TrimSpace(m.filtering.Value()); v != "" {
		m.filter[m.scr] = v
	} else {
		delete(m.filter, m.scr)
	}
	m.cur[m.scr] = 0
	return cmd
}

// matches: every word of the screen's filter is in one of the fields.
func (m *model) matches(s screen, fields ...string) bool {
	f := strings.ToLower(m.filter[s])
	if f == "" {
		return true
	}
	hay := strings.ToLower(strings.Join(fields, " "))
	for _, w := range strings.Fields(f) {
		if !strings.Contains(hay, w) {
			return false
		}
	}
	return true
}

func (m *model) sessionRows() (out []session, inProjects int) {
	vis, inProj := m.visibleSessions()
	for _, s := range vis {
		if m.matches(scrSessions, append([]string{s.Name, s.Title, s.LiveModel, stateWords[s.state()]}, s.Preview...)...) {
			out = append(out, s)
		}
	}
	return out, inProj
}

func (m *model) chatRows() (out []chatRow) {
	for _, c := range m.data.lists.chats {
		if m.matches(scrChats, c.Title, c.Mode, c.LastSnippet, c.HostName) {
			out = append(out, c)
		}
	}
	return out
}

func (m *model) projectRows() (out []project) {
	for _, p := range m.data.lists.projects {
		if m.matches(scrProjects, p.Name, p.Kind, p.Status, p.ManifestSummary) {
			out = append(out, p)
		}
	}
	return out
}

func (m *model) roundRows() (out []round) {
	for _, r := range m.data.lists.rounds {
		head := ""
		if r.LastRun != nil {
			head = r.LastRun.Headline
		}
		if m.matches(scrRounds, r.Title, r.Cadence, r.Prompt, head) {
			out = append(out, r)
		}
	}
	return out
}

func (m *model) archiveRows() (out []archived) {
	for _, a := range m.data.lists.archive {
		if m.matches(scrArchive, a.Title, a.Model, a.LastMessage) {
			out = append(out, a)
		}
	}
	return out
}

// ---- : palette ------------------------------------------------------------------
//
// `:` is the bridge to the CLI (docs/design/console.md §6): it finds screens,
// sessions, chats, projects, rounds and actions by fuzzy name, and anything
// else typed there runs as `huginn <that>` with the real terminal.

type candidate struct {
	label string // what is matched and shown
	hint  string
	run   func() tea.Cmd
}

type palette struct {
	ti    textinput.Model
	all   []candidate
	shown []candidate
	cur   int
}

func (m *model) startPalette() tea.Cmd {
	ti := textinput.New()
	ti.Prompt = ""
	ti.CharLimit = 500
	ti.Width = 50
	ti.Cursor.SetMode(cursor.CursorStatic)
	ti.Focus()
	p := &palette{ti: ti, all: m.candidates()}
	p.refilter()
	m.pal = p
	return nil
}

func (m *model) candidates() []candidate {
	var c []candidate
	jump := func(s screen) func() tea.Cmd {
		return func() tea.Cmd {
			m.stack = []screen{scrHome}
			if s == scrHome {
				m.stack = nil
			}
			m.scr = s
			return nil
		}
	}
	c = append(c, candidate{"home", "screen", jump(scrHome)})
	for _, x := range sections {
		c = append(c, candidate{x.name, "screen · " + x.key, jump(x.scr)})
	}
	c = append(c, candidate{"help", "screen · ?", jump(scrHelp)})
	for _, s := range m.data.sessions {
		name := s.Name
		hint := "attach · " + stateWords[s.state()]
		if s.Title != "" {
			hint += " · " + s.Title
		}
		c = append(c, candidate{"attach " + name, hint, func() tea.Cmd { return m.attachTo(name, false) }})
	}
	for _, ch := range m.data.lists.chats {
		id, title := ch.ID, ch.Title
		if title == "" {
			title = "(untitled)"
		}
		c = append(c, candidate{"chat " + title, "open · " + ch.Mode, func() tea.Cmd { return m.openChat(id) }})
	}
	for _, p := range m.data.lists.projects {
		p := p
		c = append(c, candidate{"project " + p.Name, "dashboard · " + p.Status, func() tea.Cmd {
			m.scr = scrProjects
			for i, x := range m.data.lists.projects {
				if x.ID == p.ID {
					m.cur[scrProjects] = i
				}
			}
			delete(m.filter, scrProjects)
			return m.projectsKey("enter")
		}})
	}
	for _, r := range m.data.lists.rounds {
		r := r
		c = append(c, candidate{"round " + r.Title, "rounds · " + r.Cadence, func() tea.Cmd {
			m.stack = []screen{scrHome}
			m.scr = scrRounds
			delete(m.filter, scrRounds)
			for i, x := range m.data.lists.rounds {
				if x.ID == r.ID {
					m.cur[scrRounds] = i
				}
			}
			return nil
		}})
	}
	c = append(c,
		candidate{"new session", "name it, then attach", func() tea.Cmd { m.scr = scrSessions; return m.sessionsKey("n") }},
		candidate{"new chat", "ask or act", func() tea.Cmd { m.scr = scrChats; return m.chatsKey("n") }},
		candidate{"refresh", "fetch everything now", func() tea.Cmd { return m.fetch() }},
		candidate{"core update", "huginn update", func() tea.Cmd { return m.run("huginn update", "update") }},
		candidate{"core restart", "restart huginn core", func() tea.Cmd { m.scr = scrCore; return m.coreKey("R") }},
		candidate{"quit", "leave the console", func() tea.Cmd { return tea.Quit }},
	)
	return c
}

type labels []candidate

func (l labels) String(i int) string { return l[i].label }
func (l labels) Len() int            { return len(l) }

func (p *palette) refilter() {
	q := strings.TrimSpace(p.ti.Value())
	q = strings.TrimPrefix(q, "huginn ")
	p.cur = 0
	if q == "" {
		p.shown = p.all
		return
	}
	// What contains the text comes first (names that start with it, then the
	// rest, in their own order); fuzzy matching only when nothing does, where
	// it catches a typo instead of burying "devices" under every chat whose
	// title happens to hold a d, an e and a v.
	p.shown = nil
	lq := strings.ToLower(q)
	var starts, contains []candidate
	for _, c := range p.all {
		l := strings.ToLower(c.label)
		switch {
		case strings.HasPrefix(l, lq) || strings.Contains(l, " "+lq):
			starts = append(starts, c)
		case strings.Contains(l, lq):
			contains = append(contains, c)
		}
	}
	p.shown = append(starts, contains...)
	if len(p.shown) > 0 {
		return
	}
	for _, r := range fuzzy.FindFrom(q, labels(p.all)) {
		p.shown = append(p.shown, p.all[r.Index])
	}
}

// verbCandidate runs what was typed as `huginn <words>`.
func (m *model) verbCandidate(text string) (candidate, bool) {
	args := splitArgs(strings.TrimPrefix(strings.TrimSpace(text), "huginn "))
	if len(args) == 0 || args[0] == "console" {
		return candidate{}, false
	}
	if (args[0] == "attach" || args[0] == "solo") && len(args) == 2 {
		solo := args[0] == "solo"
		return candidate{"huginn " + strings.Join(args, " "), "attach", func() tea.Cmd { return m.attachTo(args[1], solo) }}, true
	}
	for _, s := range m.data.sessions {
		if len(args) == 1 && strings.EqualFold(args[0], s.Name) {
			return candidate{"huginn " + args[0], "attach", func() tea.Cmd { return m.attachTo(args[0], false) }}, true
		}
	}
	return candidate{"huginn " + strings.Join(args, " "), "run it here", func() tea.Cmd { return m.runVerb(args) }}, true
}

func (m *model) paletteKey(k tea.KeyMsg) tea.Cmd {
	p := m.pal
	switch k.Type {
	case tea.KeyEsc, tea.KeyCtrlC:
		m.pal = nil
		return nil
	case tea.KeyUp:
		if p.cur > 0 {
			p.cur--
		}
		return nil
	case tea.KeyDown, tea.KeyTab:
		if p.cur < len(m.paletteRows())-1 {
			p.cur++
		}
		return nil
	case tea.KeyEnter:
		rows := m.paletteRows()
		m.pal = nil
		if p.cur < len(rows) {
			return rows[p.cur].run()
		}
		return nil
	}
	var cmd tea.Cmd
	p.ti, cmd = p.ti.Update(k)
	p.refilter()
	return cmd
}

// paletteRows: the matches, then "huginn <what you typed>" as the last way out.
func (m *model) paletteRows() []candidate {
	rows := append([]candidate(nil), m.pal.shown...)
	if v, ok := m.verbCandidate(m.pal.ti.Value()); ok {
		rows = append(rows, v)
	}
	return rows
}

func (m *model) paletteView() string {
	t := m.t
	var b strings.Builder
	b.WriteString("\n  " + t.heading.Render("go to, attach, or run") + "  " + t.dim.Render("type to narrow · anything else runs as huginn <it>") + "\n\n")
	rows := m.paletteRows()
	room := m.h - 9
	if room < 3 {
		room = 3
	}
	start := 0
	if m.pal.cur >= room {
		start = m.pal.cur - room + 1
	}
	for i := start; i < len(rows) && i < start+room; i++ {
		r := pad(t.text.Render(rows[i].label), 36) + " " + t.dim.Render(rows[i].hint)
		mark := "  "
		if i == m.pal.cur {
			mark = t.key.Render(t.cursor()) + " "
			r = t.selected.Render(pad(r, m.w-6))
		}
		b.WriteString("  " + mark + r + "\n")
	}
	if len(rows) == 0 {
		b.WriteString("   " + t.dim.Render("type a screen, a session, a chat, or any huginn verb") + "\n")
	}
	return b.String()
}

// runVerb runs `huginn <args>` with the terminal, and holds its output on
// screen until ⏎ (the console's alt screen would otherwise wipe it at once).
func (m *model) runVerb(args []string) tea.Cmd {
	c := exec.Command(m.o.Binary, args...)
	what := "huginn " + strings.Join(args, " ")
	return tea.Exec(&held{cmd: c, what: what}, func(err error) tea.Msg {
		if err != nil {
			return done{err: fmt.Errorf("%s: %w", what, err), refetch: true}
		}
		return done{text: what + ": done", refetch: true}
	})
}

type held struct {
	cmd    *exec.Cmd
	what   string
	stdin  io.Reader
	stdout io.Writer
	stderr io.Writer
}

func (h *held) SetStdin(r io.Reader)  { h.stdin = r }
func (h *held) SetStdout(w io.Writer) { h.stdout = w }
func (h *held) SetStderr(w io.Writer) { h.stderr = w }

func (h *held) Run() error {
	if h.stdin == nil {
		h.stdin = os.Stdin
	}
	if h.stdout == nil {
		h.stdout = os.Stdout
	}
	if h.stderr == nil {
		h.stderr = os.Stderr
	}
	fmt.Fprintf(h.stdout, "\n$ %s\n", h.what)
	h.cmd.Stdin, h.cmd.Stdout, h.cmd.Stderr = h.stdin, h.stdout, h.stderr
	err := h.cmd.Run()
	fmt.Fprint(h.stdout, "\n[press Enter to return to the console] ")
	bufio.NewReader(h.stdin).ReadString('\n')
	return err
}

// splitArgs splits like a shell does for the simple cases: spaces, and
// single or double quotes around words with spaces.
func splitArgs(s string) []string {
	var out []string
	var cur strings.Builder
	var quote rune
	in := false
	for _, r := range s {
		switch {
		case quote != 0:
			if r == quote {
				quote = 0
			} else {
				cur.WriteRune(r)
			}
		case r == '\'' || r == '"':
			quote, in = r, true
		case r == ' ' || r == '\t':
			if in {
				out = append(out, cur.String())
				cur.Reset()
				in = false
			}
		default:
			cur.WriteRune(r)
			in = true
		}
	}
	if in {
		out = append(out, cur.String())
	}
	return out
}
