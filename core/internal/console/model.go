package console

import (
	"context"
	"errors"
	"fmt"
	"os"
	"os/exec"
	"sort"
	"strings"
	"sync"
	"time"

	"github.com/charmbracelet/bubbles/cursor"
	"github.com/charmbracelet/bubbles/textinput"
	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// Options is what the core binary hands the console.
type Options struct {
	Socket  string // the core's socket
	Proto   string // the core protocol version
	Version string // this binary's version
	Binary  string // this binary, for `huginn core …` children
	Home    string
}

// The minimum supported terminal: below it the console says so and offers the verbs.
const minW, minH = 60, 18

type screen int

const (
	scrHome screen = iota
	scrSessions
	scrChats
	scrProjects
	scrRounds
	scrDevices
	scrUsage
	scrArchive
	scrSettings
	scrCore
	scrHelp
	scrChat    // one conversation (from chats or a round)
	scrProject // one project's dashboard
)

type section struct {
	scr   screen
	key   string
	name  string
	verb  string // the one-shot command that does the same
	ready bool   // built in this release
}

// sections, in the design's stable numbering (docs/design/console.md §5).
var sections = []section{
	{scrSessions, "1", "sessions", "huginn ls", true},
	{scrChats, "2", "chats", "the desktop or phone app", true},
	{scrProjects, "3", "projects", "huginn projects", true},
	{scrRounds, "4", "rounds", "huginn rounds", true},
	{scrDevices, "5", "devices", "huginn devices", true},
	{scrUsage, "6", "usage", "huginn headroom", true},
	{scrArchive, "7", "archive", "huginn archive", true},
	{scrSettings, "8", "settings", "huginn core status", true},
	{scrCore, "9", "core", "huginn core status", true},
}

func sectionFor(s screen) section {
	for _, x := range sections {
		if x.scr == s {
			return x
		}
	}
	switch s {
	case scrChat:
		return section{scr: s, name: "chat", verb: "the desktop or phone app", ready: true}
	case scrProject:
		return section{scr: s, name: "project", verb: "huginn projects show <name>", ready: true}
	}
	return section{scr: s, name: "help", verb: "huginn help", ready: true}
}

// ---- messages -----------------------------------------------------------------

type fetched struct {
	core     coreStatus
	coreErr  error
	st       appdStatus
	sessions []session
	devices  []device
	hr       headroom
	cnt      counts
	lists    lists
	appdErr  error
	gen      int
}

type changed struct{}
type linkState struct{ live bool }
type done struct {
	text    string
	err     error
	refetch bool
}
type tick struct{}

// ---- model --------------------------------------------------------------------

type model struct {
	o   Options
	b   *backend
	t   theme
	ctx context.Context

	w, h  int
	scr   screen
	stack []screen

	data    fetched
	loaded  bool
	fetchAt time.Time
	gen     int
	live    bool
	events  chan tea.Msg

	cur map[screen]int // cursor per screen, kept across refetches

	chat chatState
	proj projState

	filter    map[screen]string // kept `/` filters, per screen
	filtering *textinput.Model  // the `/` field while it has focus
	pal       *palette          // the `:` palette while open

	input   *prompt
	flash   string
	flashOK bool
	flashAt time.Time
}

// prompt is a one-line question at the bottom of the screen.
type prompt struct {
	label  string
	ti     textinput.Model
	submit func(string) tea.Cmd
	// must, when set, is the exact text the answer has to be (typed confirmation).
	must string
}

func newModel(ctx context.Context, o Options) *model {
	return &model{
		o: o, b: newBackend(o.Socket, o.Proto), t: newTheme(), ctx: ctx,
		cur: map[screen]int{}, events: make(chan tea.Msg, 16), filter: map[screen]string{},
	}
}

func (m *model) Init() tea.Cmd {
	go m.follow()
	return tea.Batch(m.fetch(), m.next(), tea.SetWindowTitle("huginn"), every())
}

func every() tea.Cmd { return tea.Tick(30*time.Second, func(time.Time) tea.Msg { return tick{} }) }

// follow keeps appd's change signal open through the core, reconnecting every
// 3 s like the desktop, and turns each change into a refetch.
func (m *model) follow() {
	for m.ctx.Err() == nil {
		err := m.b.watch(m.ctx, func() {
			select {
			case m.events <- changed{}:
			default:
			}
		})
		_ = err
		select {
		case m.events <- linkState{live: false}:
		default:
		}
		select {
		case <-m.ctx.Done():
			return
		case <-time.After(3 * time.Second):
		}
	}
}

func (m *model) next() tea.Cmd {
	return func() tea.Msg {
		select {
		case msg := <-m.events:
			return msg
		case <-m.ctx.Done():
			return nil
		}
	}
}

// fetch loads everything the screens show, in parallel; a generation number
// keeps a late answer from overwriting a newer one.
func (m *model) fetch() tea.Cmd {
	m.gen++
	gen := m.gen
	b := m.b
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 15*time.Second)
		defer cancel()
		f := fetched{gen: gen}
		f.core, f.coreErr = b.coreStatus(ctx)
		if f.coreErr != nil {
			return f
		}
		var wg sync.WaitGroup
		var mu sync.Mutex
		setErr := func(err error) {
			mu.Lock()
			if err != nil && f.appdErr == nil {
				f.appdErr = err
			}
			mu.Unlock()
		}
		run := func(fn func()) { wg.Add(1); go func() { defer wg.Done(); fn() }() }
		run(func() { var err error; f.st, err = b.appdStatus(ctx); setErr(err) })
		run(func() { var err error; f.sessions, err = b.sessions(ctx); setErr(err) })
		run(func() { var err error; f.devices, err = b.devices(ctx); setErr(err) })
		run(func() { var err error; f.hr, err = b.headroom(ctx); setErr(err) })
		run(func() { f.lists.chats, _ = b.chats(ctx) })
		run(func() { f.lists.projects, _ = b.projects(ctx) })
		run(func() { f.lists.rounds, _ = b.rounds(ctx) })
		run(func() { f.lists.archive, _ = b.archive(ctx) })
		wg.Wait()
		return f
	}
}

func (m *model) say(text string, ok bool) {
	m.flash, m.flashOK, m.flashAt = text, ok, time.Now()
}

func (m *model) act(fn func(ctx context.Context) (string, error)) tea.Cmd {
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 30*time.Second)
		defer cancel()
		text, err := fn(ctx)
		return done{text: text, err: err, refetch: true}
	}
}

func (m *model) open(s screen) {
	if s == m.scr {
		return
	}
	m.stack = append(m.stack, m.scr)
	m.scr = s
}

func (m *model) back() bool {
	if len(m.stack) == 0 {
		return false
	}
	if m.scr == scrChat && m.chat.cancel != nil {
		m.chat.cancel() // stop following; the run itself goes on on the host
	}
	m.scr = m.stack[len(m.stack)-1]
	m.stack = m.stack[:len(m.stack)-1]
	return true
}

func (m *model) Update(msg tea.Msg) (tea.Model, tea.Cmd) {
	switch msg := msg.(type) {
	case tea.WindowSizeMsg:
		m.w, m.h = msg.Width, msg.Height
		return m, nil
	case fetched:
		if msg.gen < m.gen && m.loaded {
			return m, nil
		}
		m.data, m.loaded, m.fetchAt = msg, true, time.Now()
		if msg.coreErr == nil && msg.appdErr == nil {
			m.live = true
		}
		return m, nil
	case changed:
		m.live = true
		return m, tea.Batch(m.fetch(), m.next())
	case linkState:
		m.live = msg.live
		return m, m.next()
	case tick:
		if m.flash != "" && time.Since(m.flashAt) > 8*time.Second {
			m.flash = ""
		}
		var cmd tea.Cmd
		if m.data.coreErr != nil || time.Since(m.fetchAt) > time.Minute {
			cmd = m.fetch()
		}
		return m, tea.Batch(cmd, every())
	case done:
		if msg.err != nil {
			m.say(msg.err.Error(), false)
		} else if msg.text != "" {
			m.say(msg.text, true)
		}
		if msg.refetch {
			return m, m.fetch()
		}
		return m, nil
	case chatLoaded:
		m.chat.loading, m.chat.err = false, msg.err
		if msg.err == nil && msg.d.ID == m.chat.id {
			m.chat.d = msg.d
			m.chat.live.Reset()
			if msg.d.Running && !m.chat.busy {
				m.chat.busy = true
				return m, m.follow1()
			}
		}
		return m, nil
	case chatEvent:
		return m, tea.Batch(m.onChatEvent(msg), m.next())
	case openChatMsg:
		cmd := m.openChat(msg.id)
		if msg.compose {
			return m, tea.Batch(cmd, m.ask("say:", "", "", func(text string) tea.Cmd { return m.send(text) }))
		}
		return m, cmd
	case dashLoaded:
		if msg.id == m.proj.p.ID {
			m.proj.loading, m.proj.err, m.proj.members = false, msg.err, msg.members
		}
		return m, nil
	case revived:
		m.say("revived as "+msg.name, true)
		return m, tea.Batch(m.fetch(), m.attachTo(msg.name, false))
	case tea.KeyMsg:
		switch {
		case m.input != nil:
			return m, m.promptKey(msg)
		case m.pal != nil:
			return m, m.paletteKey(msg)
		case m.filtering != nil:
			return m, m.filterKey(msg)
		}
		return m, m.key(msg)
	}
	return m, nil
}

func (m *model) ask(label, value string, must string, submit func(string) tea.Cmd) tea.Cmd {
	ti := textinput.New()
	ti.Prompt = ""
	ti.SetValue(value)
	ti.CharLimit = 2000
	ti.Width = 50
	ti.Cursor.SetMode(cursor.CursorStatic)
	ti.Focus()
	m.input = &prompt{label: label, ti: ti, submit: submit, must: must}
	return nil
}

func (m *model) promptKey(k tea.KeyMsg) tea.Cmd {
	switch k.Type {
	case tea.KeyEsc, tea.KeyCtrlC:
		m.input = nil
		m.say("cancelled", true)
		return nil
	case tea.KeyEnter:
		p := m.input
		v := strings.TrimSpace(p.ti.Value())
		m.input = nil
		if p.must != "" && v != p.must {
			m.say("not done: type "+p.must+" exactly to confirm", false)
			return nil
		}
		if v == "" && p.must == "" {
			return nil
		}
		return p.submit(v)
	}
	var cmd tea.Cmd
	m.input.ti, cmd = m.input.ti.Update(k)
	return cmd
}

func (m *model) key(k tea.KeyMsg) tea.Cmd {
	s := k.String()
	switch s {
	case "ctrl+c", "q":
		return tea.Quit
	case "esc", "backspace", "h", "left":
		if s == "esc" && m.filter[m.scr] != "" {
			delete(m.filter, m.scr)
			m.cur[m.scr] = 0
			return nil
		}
		if !m.back() && s == "esc" {
			m.say("q quits", true)
		}
		return nil
	case "?":
		m.open(scrHelp)
		return nil
	case "r":
		if m.scr != scrSessions {
			m.say("refreshing…", true)
			return m.fetch()
		}
	case "ctrl+l":
		return tea.ClearScreen
	}
	if m.data.coreErr != nil && m.loaded {
		return m.gateKey(s)
	}
	switch s {
	case ":":
		return m.startPalette()
	case "/":
		return m.startFilter()
	}
	for _, x := range sections {
		if s == x.key {
			m.stack = nil
			if m.scr != scrHome {
				m.stack = []screen{scrHome}
			}
			m.scr = x.scr
			return nil
		}
	}
	if m.scr == scrChat && s == "ctrl+x" {
		return m.chatKey(s)
	}
	switch m.scr {
	case scrHome:
		return m.homeKey(s)
	case scrChats:
		return m.chatsKey(s)
	case scrChat:
		return m.chatKey(s)
	case scrProjects:
		return m.projectsKey(s)
	case scrProject:
		return m.projectKey(s)
	case scrRounds:
		return m.roundsKey(s)
	case scrArchive:
		return m.archiveKey(s)
	case scrSessions:
		return m.sessionsKey(s)
	case scrDevices:
		return m.devicesKey(s)
	case scrCore:
		return m.coreKey(s)
	}
	m.move(s, 0)
	return nil
}

// move handles list navigation for the current screen over n rows.
func (m *model) move(s string, n int) {
	c := m.cur[m.scr]
	switch s {
	case "up", "k":
		c--
	case "down", "j":
		c++
	case "pgup":
		c -= 10
	case "pgdown":
		c += 10
	case "home", "g":
		c = 0
	case "end", "G":
		c = n - 1
	}
	if c >= n {
		c = n - 1
	}
	if c < 0 {
		c = 0
	}
	m.cur[m.scr] = c
}

// ---- running children with the real terminal ------------------------------------

func (m *model) attachTo(name string, solo bool) tea.Cmd {
	if !hasSSHHost(m.o.Home, sshHost()) {
		m.say("attaching needs SSH to the huginn host: run huginn setup ssh (or :setup ssh)", false)
		return nil
	}
	a, err := newAttach(sshHost(), name, solo)
	if err != nil {
		m.say(err.Error(), false)
		return nil
	}
	return tea.Exec(a, func(err error) tea.Msg {
		if err != nil {
			return done{err: err, refetch: true}
		}
		return done{text: "back from " + name, refetch: true}
	})
}

// run hands the terminal to a `huginn …` child (core install, update) and
// returns when it exits, so its prompts (UAC, sudo) and output work as usual.
func (m *model) run(what string, args ...string) tea.Cmd {
	c := exec.Command(m.o.Binary, args...)
	return tea.ExecProcess(c, func(err error) tea.Msg {
		if err != nil {
			return done{err: fmt.Errorf("%s: %w", what, err), refetch: true}
		}
		return done{text: what + ": done", refetch: true}
	})
}

// startCore is `huginn core start`: the service, and on Windows a direct
// start when Task Scheduler holds the task back.
func startCore(bin string) error {
	out, err := exec.Command(bin, "core", "start").CombinedOutput()
	if err != nil {
		return errors.New(strings.TrimSpace(string(out)))
	}
	return nil
}

// ---- view ---------------------------------------------------------------------

func (m *model) View() string {
	if m.w == 0 {
		return ""
	}
	if m.w < minW || m.h < minH {
		return fmt.Sprintf("huginn: make this window at least %d × %d (it is %d × %d), or use the verbs: huginn help", minW, minH, m.w, m.h)
	}
	var body string
	switch {
	case !m.loaded:
		body = m.t.dim.Render("  connecting to huginn core…")
	case m.data.coreErr != nil:
		body = m.gateView()
	case m.pal != nil:
		body = m.paletteView()
	default:
		switch m.scr {
		case scrHome:
			body = m.homeView()
		case scrSessions:
			body = m.sessionsView()
		case scrDevices:
			body = m.devicesView()
		case scrUsage:
			body = m.usageView()
		case scrCore:
			body = m.coreView()
		case scrHelp:
			body = m.helpView()
		case scrChats:
			body = m.chatsView()
		case scrChat:
			body = m.chatView()
		case scrProjects:
			body = m.projectsView()
		case scrProject:
			body = m.projectView()
		case scrRounds:
			body = m.roundsView()
		case scrArchive:
			body = m.archiveView()
		case scrSettings:
			body = m.settingsView()
		default:
			body = m.laterView()
		}
	}
	head := clamp(m.header(), m.w)
	foot := clamp(m.footer(), m.w)
	room := m.h - lipgloss.Height(head) - lipgloss.Height(foot)
	body = fit(body, m.w, room)
	return lipgloss.JoinVertical(lipgloss.Left, head, body, foot)
}

// fit pads or cuts body to exactly rows lines, each no wider than w.
func fit(body string, w, rows int) string {
	lines := strings.Split(body, "\n")
	if len(lines) > rows {
		lines = lines[:rows]
	}
	for len(lines) < rows {
		lines = append(lines, "")
	}
	for i, l := range lines {
		if lipgloss.Width(l) > w {
			lines[i] = truncate(l, w)
		}
	}
	return strings.Join(lines, "\n")
}

// clamp cuts every line of s to w cells.
func clamp(s string, w int) string {
	lines := strings.Split(s, "\n")
	for i, l := range lines {
		if lipgloss.Width(l) > w {
			lines[i] = truncate(l, w)
		}
	}
	return strings.Join(lines, "\n")
}

func truncate(s string, w int) string {
	return lipgloss.NewStyle().MaxWidth(w).Render(s)
}

func (m *model) header() string {
	t := m.t
	left := t.title.Render(raven1(m.attention()) + " huginn")
	crumb := ""
	if m.scr != scrHome {
		crumb = t.dim.Render("  ›  ") + t.text.Render(sectionFor(m.scr).name)
		if t.ascii {
			crumb = t.dim.Render("  >  ") + t.text.Render(sectionFor(m.scr).name)
		}
	}
	var bits []string
	if h := m.data.st.Host; h != "" {
		bits = append(bits, "host "+h)
	}
	bits = append(bits, "core "+m.o.Version)
	switch {
	case !m.loaded:
	case m.data.coreErr != nil:
		bits = append(bits, t.err.Render("core not answering"))
	case m.data.appdErr != nil:
		bits = append(bits, t.err.Render("huginn unreachable"))
	case m.live:
		bits = append(bits, t.ok.Render("live"))
	default:
		bits = append(bits, t.needs.Render("reconnecting…"))
	}
	right := t.dim.Render(strings.Join(bits, " · "))
	// Narrow: the facts go first, the connection word last of all.
	for len(bits) > 1 && lipgloss.Width(left)+lipgloss.Width(crumb)+lipgloss.Width(right)+3 > m.w {
		bits = bits[1:]
		right = t.dim.Render(strings.Join(bits, " · "))
	}
	gap := m.w - lipgloss.Width(left) - lipgloss.Width(crumb) - lipgloss.Width(right) - 2
	if gap < 1 {
		gap = 1
	}
	return " " + left + crumb + strings.Repeat(" ", gap) + right
}

func (m *model) footer() string {
	t := m.t
	if m.input != nil {
		return "\n " + t.key.Render(m.input.label) + " " + m.input.ti.View() + "\n " + t.dim.Render("⏎ ok · esc cancel")
	}
	if m.pal != nil {
		return "\n " + t.key.Render(":") + " " + m.pal.ti.View() + "\n " + t.dim.Render("⏎ go · ↑↓ choose · esc close")
	}
	if m.filtering != nil {
		return "\n " + t.key.Render("/") + " " + m.filtering.View() + "\n " + t.dim.Render("narrows as you type · ⏎ keep · esc clear")
	}
	keys := m.keysFor()
	same := ""
	if v := sectionFor(m.scr).verb; v != "" && m.scr != scrHome {
		same = t.dim.Render("same as: " + v)
	}
	line2 := same
	if f := m.filter[m.scr]; f != "" {
		line2 = t.needs.Render("filter: "+f) + t.dim.Render("  · / edits · esc clears")
	}
	if m.flash != "" {
		st := t.ok
		if !m.flashOK {
			st = t.err
		}
		line2 = st.Render(m.flash)
	}
	return "\n " + keys + "\n " + line2
}

func (m *model) keysFor() string {
	t := m.t
	k := func(key, what string) string { return t.key.Render(key) + " " + t.dim.Render(what) }
	var parts []string
	switch {
	case m.loaded && m.data.coreErr != nil:
		parts = []string{k("s", "start core"), k("i", "set it up"), k("a", "attach main"), k("q", "quit")}
	case m.scr == scrHome:
		parts = []string{k("↑↓", "move"), k("⏎", "open"), k("1-9", "jump"), k("?", "help"), k("q", "quit")}
	case m.scr == scrSessions:
		parts = []string{k("⏎", "attach"), k("n", "new"), k("s", "send"), k("e", "end"), k("R", "rename"), k("x", "kill"), k("esc", "back")}
	case m.scr == scrDevices:
		parts = []string{k("o", "this machine on/off"), k("c", "scope"), k("esc", "back")}
	case m.scr == scrCore:
		parts = []string{k("u", "update"), k("R", "restart"), k("i", "set up"), k("esc", "back")}
	case m.scr == scrChats:
		parts = []string{k("⏎", "open"), k("n", "new"), k("R", "rename"), k("x", "delete"), k("esc", "back")}
	case m.scr == scrChat:
		parts = []string{k("i", "write"), k("↑↓", "scroll"), k("ctrl+x", "cancel run"), k("esc", "back")}
	case m.scr == scrProjects:
		parts = []string{k("⏎", "dashboard"), k("e", "end"), k("esc", "back")}
	case m.scr == scrProject:
		parts = []string{k("⏎", "attach member"), k("esc", "back")}
	case m.scr == scrRounds:
		parts = []string{k("⏎", "last run"), k("p", "pause/resume"), k("x", "delete"), k("esc", "back")}
	case m.scr == scrArchive:
		parts = []string{k("⏎", "revive"), k("x", "forget"), k("esc", "back")}
	default:
		parts = []string{k("esc", "back"), k("?", "help"), k("q", "quit")}
	}
	if filterable[m.scr] && !(m.loaded && m.data.coreErr != nil) {
		parts = append(parts, k("/", "filter"))
	}
	if !(m.loaded && m.data.coreErr != nil) {
		parts = append(parts, k(":", "go to / run"))
	}
	if t.ascii {
		for i := range parts {
			parts[i] = strings.ReplaceAll(strings.ReplaceAll(parts[i], "↑↓", "up/dn"), "⏎", "enter")
		}
	}
	return strings.Join(parts, "  ")
}

// attention: any session or the tray summary needs you.
func (m *model) attention() state {
	for _, s := range m.data.sessions {
		if s.state() == stNeeds {
			return stNeeds
		}
	}
	return stWorking
}

// ---- small helpers ---------------------------------------------------------------

func ago(sec int64) string {
	if sec <= 0 {
		return ""
	}
	if sec > 1e12 { // milliseconds
		sec /= 1000
	}
	d := time.Since(time.Unix(sec, 0))
	switch {
	case d < time.Minute:
		return "now"
	case d < time.Hour:
		return fmt.Sprintf("%dm", int(d.Minutes()))
	case d < 48*time.Hour:
		return fmt.Sprintf("%dh", int(d.Hours()))
	}
	return fmt.Sprintf("%dd", int(d.Hours()/24))
}

func until(iso string) string {
	t, err := time.Parse(time.RFC3339Nano, iso)
	if err != nil {
		return ""
	}
	d := time.Until(t)
	if d <= 0 {
		return "now"
	}
	if d < time.Hour {
		return fmt.Sprintf("in %dm", int(d.Minutes()))
	}
	if d < 48*time.Hour {
		return fmt.Sprintf("in %dh %dm", int(d.Hours()), int(d.Minutes())%60)
	}
	return fmt.Sprintf("in %dd", int(d.Hours()/24))
}

func pad(s string, w int) string {
	if n := lipgloss.Width(s); n < w {
		return s + strings.Repeat(" ", w-n)
	}
	return truncate(s, w)
}

// visibleSessions: project sessions live in Projects (owner rule 2026-09-19).
func (m *model) visibleSessions() (out []session, inProjects int) {
	for _, s := range m.data.sessions {
		if s.Project != nil {
			inProjects++
			continue
		}
		out = append(out, s)
	}
	rank := map[state]int{stNeeds: 0, stWorking: 1, stWaiting: 2, stShell: 3}
	sort.SliceStable(out, func(i, j int) bool {
		a, b := out[i], out[j]
		if rank[a.state()] != rank[b.state()] {
			return rank[a.state()] < rank[b.state()]
		}
		return a.ActivityAt > b.ActivityAt
	})
	return out, inProjects
}

func homeDir() string { h, _ := os.UserHomeDir(); return h }
