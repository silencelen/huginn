package console

import (
	"bufio"
	"context"
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"strings"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// The parity screens (docs/design/console.md phase 3): chats, projects, rounds,
// archive, settings.

// ---- shapes ---------------------------------------------------------------------

type chatRow struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Mode        string `json:"mode"`
	Running     bool   `json:"running"`
	Pending     int    `json:"pending"`
	UpdatedAt   int64  `json:"updatedAt"`
	LastSnippet string `json:"lastSnippet"`
	HostName    string `json:"hostName"`
	Turns       int    `json:"turns"`
}

type chatMsg struct {
	Type      string  `json:"type"`
	Text      string  `json:"text"`
	Name      string  `json:"name"`
	Input     string  `json:"input"`
	Ts        int64   `json:"ts"`
	CostUsd   float64 `json:"costUsd"`
	Turns     int     `json:"turns"`
	Ok        *bool   `json:"ok"`
	ErrorText string  `json:"errorText"`
}

type chatDetail struct {
	chatRow
	Messages    []chatMsg `json:"messages"`
	PartialText string    `json:"partialText"`
	Seq         int       `json:"seq"`
}

type project struct {
	ID              string `json:"id"`
	Name            string `json:"name"`
	Kind            string `json:"kind"`
	Status          string `json:"status"`
	MemberCount     int    `json:"memberCount"`
	Alive           int    `json:"alive"`
	Busy            int    `json:"busy"`
	Waiting         int    `json:"waiting"`
	ManifestSummary string `json:"manifestSummary"`
	UpdatedAt       int64  `json:"updatedAt"`
}

type member struct {
	Role         string  `json:"role"`
	Name         string  `json:"name"`
	Alive        bool    `json:"alive"`
	State        *string `json:"state"`
	NeedsYou     bool    `json:"needsYou"`
	Title        *string `json:"title"`
	Turns        int     `json:"turns"`
	EstCostUsd   float64 `json:"estCostUsd"`
	PendingSends int     `json:"pendingSends"`
}

type round struct {
	ID           string `json:"id"`
	Title        string `json:"title"`
	Prompt       string `json:"prompt"`
	Goal         string `json:"goal"`
	Enabled      bool   `json:"enabled"`
	Mode         string `json:"mode"`
	Cadence      string `json:"cadence"`
	NextRunAtSec *int64 `json:"nextRunAtSec"`
	Running      bool   `json:"running"`
	LastRun      *struct {
		At       int64  `json:"at"`
		ChatID   string `json:"chatId"`
		Status   string `json:"status"`
		Headline string `json:"headline"`
	} `json:"lastRun"`
}

type archived struct {
	ID          string `json:"id"`
	Title       string `json:"title"`
	Model       string `json:"model"`
	LastMessage string `json:"lastMessage"`
	ArchivedAt  int64  `json:"archivedAt"`
	Live        bool   `json:"live"`
	RevivedAs   string `json:"revivedAs"`
}

// ---- calls ---------------------------------------------------------------------

func (b *backend) chats(ctx context.Context) ([]chatRow, error) {
	var r struct {
		Chats []chatRow `json:"chats"`
	}
	return r.Chats, b.get(ctx, "/appd/v1/chats", &r)
}

func (b *backend) chat(ctx context.Context, id string) (chatDetail, error) {
	var d chatDetail
	return d, b.get(ctx, "/appd/v1/chats/"+esc(id), &d)
}

func (b *backend) projects(ctx context.Context) ([]project, error) {
	var r struct {
		Projects []project `json:"projects"`
	}
	return r.Projects, b.get(ctx, "/appd/v1/projects", &r)
}

func (b *backend) dashboard(ctx context.Context, id string) ([]member, error) {
	var r struct {
		Members []member `json:"members"`
	}
	return r.Members, b.get(ctx, "/appd/v1/projects/"+esc(id)+"/dashboard", &r)
}

func (b *backend) rounds(ctx context.Context) ([]round, error) {
	var r struct {
		Rounds []round `json:"rounds"`
	}
	return r.Rounds, b.get(ctx, "/appd/v1/rounds", &r)
}

func (b *backend) archive(ctx context.Context) ([]archived, error) {
	var r struct {
		Archives []archived `json:"archives"`
	}
	return r.Archives, b.get(ctx, "/appd/v1/archive", &r)
}

// followChat reads a run's events through the core until it ends, handing
// each to emit. The send is a plain POST (202); this is how its answer arrives.
func (b *backend) followChat(ctx context.Context, id string, since int, emit func(event string, data json.RawMessage)) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/appd/v1/chats/"+esc(id)+fmt.Sprintf("/stream?since=%d", since), nil)
	if err != nil {
		return err
	}
	req.Header.Set("X-Huginn-Core-Proto", b.proto)
	req.Header.Set("Accept", "text/event-stream")
	res, err := b.stream.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	sc := bufio.NewScanner(res.Body)
	sc.Buffer(make([]byte, 1<<20), 16<<20)
	ev := ""
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "event:"):
			ev = strings.TrimSpace(line[6:])
		case strings.HasPrefix(line, "data:"):
			emit(ev, json.RawMessage(strings.TrimSpace(line[5:])))
			if ev == "done" {
				return nil
			}
		}
	}
	return sc.Err()
}

// ---- messages -------------------------------------------------------------------

type lists struct {
	chats    []chatRow
	projects []project
	rounds   []round
	archive  []archived
}

type chatLoaded struct {
	d   chatDetail
	err error
}
type chatEvent struct {
	id, event string
	gen       int // the follow it came from (chatState.gen)
	data      json.RawMessage
}
type dashLoaded struct {
	id      string
	members []member
	err     error
}

// ---- chats ----------------------------------------------------------------------

func (m *model) chatsView() string {
	t := m.t
	var b strings.Builder
	b.WriteString("\n")
	rows := m.chatRows()
	if len(rows) == 0 && m.filter[scrChats] != "" {
		b.WriteString("   " + t.dim.Render("nothing matches the filter (esc clears it)") + "\n")
		return b.String()
	}
	if len(rows) == 0 {
		b.WriteString("   " + t.dim.Render("no chats yet. n starts one (ask answers, act can change things)") + "\n")
		return b.String()
	}
	m.listRows(&b, scrChats, len(rows), func(i int) string {
		c := rows[i]
		g := t.glyph(stWaiting)
		if c.Running {
			g = t.glyph(stWorking)
		}
		title := c.Title
		if title == "" {
			title = "(untitled)"
		}
		return g + " " + pad(t.text.Render(title), 30) + " " + pad(t.dim.Render(c.Mode), 4) + " " + pad(t.dim.Render(oneLine(c.LastSnippet)), m.w-52) + " " + t.dim.Render(ago(c.UpdatedAt))
	})
	return b.String()
}

func oneLine(s string) string { return strings.Join(strings.Fields(s), " ") }

// named is a title as a confirmation names it: quoted, on one line. A
// destructive prompt names its target (docs/design/console.md §6).
func named(title string) string {
	if title = oneLine(title); title == "" {
		return "(untitled)"
	}
	return `"` + title + `"`
}

// listRows draws n rows with the cursor kept visible.
func (m *model) listRows(b *strings.Builder, s screen, n int, row func(int) string) {
	t := m.t
	cur := m.cur[s]
	if cur >= n {
		cur = n - 1
		m.cur[s] = cur
	}
	room := m.h - 8
	if room < 3 {
		room = 3
	}
	start := 0
	if cur >= room {
		start = cur - room + 1
	}
	for i := start; i < n && i < start+room; i++ {
		r := row(i)
		mark := "  "
		if i == cur {
			mark = t.key.Render(t.cursor()) + " "
			r = t.selected.Render(pad(r, m.w-6))
		}
		b.WriteString("  " + mark + r + "\n")
	}
	if n > room {
		b.WriteString("    " + t.dim.Render(fmt.Sprintf("%d of %d", cur+1, n)) + "\n")
	}
}

func (m *model) chatsKey(s string) tea.Cmd {
	rows := m.chatRows()
	sel := func() (chatRow, bool) {
		c := m.cur[scrChats]
		if c < 0 || c >= len(rows) {
			return chatRow{}, false
		}
		return rows[c], true
	}
	switch s {
	case "enter", "right", "l":
		if c, ok := sel(); ok {
			return m.openChat(c.ID)
		}
	case "n":
		return m.ask("new chat — ask (answers) or act (can change things)?", "ask", "", func(mode string) tea.Cmd {
			if mode != "ask" && mode != "act" {
				m.say("ask or act", false)
				return nil
			}
			return func() tea.Msg {
				ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
				defer cancel()
				var c chatRow
				if err := m.b.do(ctx, http.MethodPost, "/appd/v1/chats", map[string]string{"mode": mode}, &c); err != nil {
					return done{err: err}
				}
				return openChatMsg{id: c.ID, compose: true}
			}
		})
	case "R":
		if c, ok := sel(); ok {
			id := c.ID
			return m.ask("title:", c.Title, "", func(title string) tea.Cmd {
				return m.act(func(ctx context.Context) (string, error) {
					return "renamed", m.b.do(ctx, http.MethodPatch, "/appd/v1/chats/"+esc(id), map[string]string{"title": title}, nil)
				})
			})
		}
	case "x":
		if c, ok := sel(); ok {
			id := c.ID
			return m.ask("delete the chat "+named(c.Title)+" and its transcript? type delete:", "", "delete", func(string) tea.Cmd {
				return m.act(func(ctx context.Context) (string, error) {
					return "deleted", m.b.do(ctx, http.MethodDelete, "/appd/v1/chats/"+esc(id), nil, nil)
				})
			})
		}
	}
	m.move(s, len(rows))
	return nil
}

type openChatMsg struct {
	id      string
	compose bool
}

func (m *model) openChat(id string) tea.Cmd {
	m.stopFollow()
	m.chat = chatState{id: id, loading: true}
	m.open(scrChat)
	return m.loadChat(id)
}

func (m *model) loadChat(id string) tea.Cmd {
	b := m.b
	return func() tea.Msg {
		ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
		defer cancel()
		d, err := b.chat(ctx, id)
		return chatLoaded{d: d, err: err}
	}
}

// chatState is the open conversation: what appd has, plus the run streaming in.
type chatState struct {
	id      string
	d       chatDetail
	loading bool
	err     error
	live    strings.Builder // the answer as it streams
	tool    string          // the tool running now
	busy    bool
	cancel  context.CancelFunc // stops the follow below
	gen     int                // the follow whose events are shown; 0: none
	scroll  int                // lines up from the bottom
}

// startFollow stops whatever follows the open chat and numbers the follow
// that replaces it; onChatEvent shows only that one's events.
//
// ⚠ One follow at a time. appd sends every event of a run to every stream on
// it, and a send while a run answers is queued, reloads the chat and follows
// the run again: a stream left open beside that one showed each token twice.
func (m *model) startFollow() (context.Context, int) {
	m.stopFollow()
	m.follows++
	ctx, cancel := context.WithCancel(m.ctx)
	m.chat.cancel, m.chat.gen = cancel, m.follows
	return ctx, m.follows
}

// stopFollow stops following the open chat, and drops whatever its stream
// still delivers. The run itself goes on on the host.
func (m *model) stopFollow() {
	if m.chat.cancel != nil {
		m.chat.cancel()
	}
	m.chat.cancel, m.chat.gen = nil, 0
}

func (m *model) send(text string) tea.Cmd {
	id := m.chat.id
	b := m.b
	m.chat.live.Reset()
	m.chat.busy = true
	m.chat.d.Messages = append(m.chat.d.Messages, chatMsg{Type: "user", Text: text})
	m.chat.scroll = 0
	ctx, gen := m.startFollow()
	events := m.events
	go func() {
		pctx, pcancel := context.WithTimeout(ctx, 30*time.Second)
		var r struct {
			Queued   bool `json:"queued"`
			Position int  `json:"position"`
		}
		err := b.do(pctx, http.MethodPost, "/appd/v1/chats/"+esc(id)+"/messages", map[string]string{"text": text}, &r)
		pcancel()
		if err != nil {
			events <- chatEvent{id: id, gen: gen, event: "error", data: mustJSON(map[string]string{"text": err.Error()})}
			events <- chatEvent{id: id, gen: gen, event: "done"}
			return
		}
		if r.Queued {
			events <- chatEvent{id: id, gen: gen, event: "queued", data: mustJSON(map[string]int{"position": r.Position})}
			events <- chatEvent{id: id, gen: gen, event: "done"}
			return
		}
		err = b.followChat(ctx, id, 0, func(ev string, data json.RawMessage) {
			select {
			case events <- chatEvent{id: id, gen: gen, event: ev, data: data}:
			case <-ctx.Done():
			}
		})
		if err != nil && ctx.Err() == nil {
			events <- chatEvent{id: id, gen: gen, event: "error", data: mustJSON(map[string]string{"text": "the stream dropped: " + err.Error()})}
		}
		events <- chatEvent{id: id, gen: gen, event: "done"}
	}()
	return nil
}

func mustJSON(v any) json.RawMessage { b, _ := json.Marshal(v); return b }

func (m *model) onChatEvent(e chatEvent) tea.Cmd {
	if e.id != m.chat.id || e.gen != m.chat.gen {
		return nil // from a follow that was stopped or replaced
	}
	var d struct {
		Text     string `json:"text"`
		Name     string `json:"name"`
		Position int    `json:"position"`
	}
	json.Unmarshal(e.data, &d)
	scrub(&d) // model text, one delta at a time (F25)
	switch e.event {
	case "delta":
		m.chat.live.WriteString(d.Text)
		m.chat.tool = ""
	case "tool_start":
		m.chat.tool = d.Name
	case "queued":
		m.say(fmt.Sprintf("queued behind the running turn (position %d)", d.Position), true)
	case "error":
		m.say(d.Text, false)
	case "done":
		m.chat.busy, m.chat.tool = false, ""
		m.stopFollow()
		return m.loadChat(m.chat.id)
	}
	return nil
}

func (m *model) chatView() string {
	t := m.t
	c := &m.chat
	w := m.w - 6
	if c.loading {
		return "\n   " + t.dim.Render("loading…")
	}
	if c.err != nil {
		return "\n   " + t.err.Render(clean(c.err.Error()))
	}
	var lines []string
	wrap := func(style lipgloss.Style, indent, s string) {
		for _, l := range strings.Split(lipgloss.NewStyle().Width(w-len(indent)).Render(s), "\n") {
			lines = append(lines, indent+style.Render(strings.TrimRight(l, " ")))
		}
	}
	for _, msg := range c.d.Messages {
		switch msg.Type {
		case "user":
			lines = append(lines, "", "  "+t.key.Render("you"))
			wrap(t.text, "  ", msg.Text)
		case "assistant":
			lines = append(lines, "", "  "+t.heading.Render("huginn"))
			wrap(t.text, "  ", msg.Text)
		case "tool":
			lines = append(lines, "  "+t.glyph(stInfo)+" "+t.dim.Render(truncate(msg.Name+"  "+oneLine(msg.Input), w-4)))
		case "result":
			st, word := stOK, "done"
			if msg.Ok != nil && !*msg.Ok {
				st, word = stError, "stopped: "+msg.ErrorText
			}
			lines = append(lines, "  "+t.glyph(st)+" "+t.dim.Render(fmt.Sprintf("%s · %d turns · $%.2f", word, msg.Turns, msg.CostUsd)))
		case "error":
			lines = append(lines, "  "+t.glyph(stError)+" "+t.err.Render(msg.Text))
		}
	}
	live := c.live.String()
	if live == "" && c.busy {
		live = c.d.PartialText
	}
	if c.busy || live != "" {
		lines = append(lines, "", "  "+t.heading.Render("huginn")+" "+t.glyph(stWorking))
		if live != "" {
			wrap(t.text, "  ", live)
		}
		if c.tool != "" {
			lines = append(lines, "  "+t.glyph(stInfo)+" "+t.dim.Render(c.tool+"…"))
		}
	}
	if len(lines) == 0 {
		lines = []string{"", "   " + t.dim.Render("nothing here yet: i writes the first message")}
	}
	room := m.h - 6
	end := len(lines) - c.scroll
	if end < room {
		end = room
	}
	if end > len(lines) {
		end = len(lines)
	}
	start := end - room
	if start < 0 {
		start = 0
	}
	head := "\n  " + t.title.Render(c.d.Title) + "  " + t.dim.Render(c.d.Mode)
	if c.d.Title == "" {
		head = "\n  " + t.title.Render("chat") + "  " + t.dim.Render(c.d.Mode)
	}
	return head + "\n" + strings.Join(lines[start:end], "\n")
}

func (m *model) chatKey(s string) tea.Cmd {
	switch s {
	case "i", "enter", "a":
		return m.ask("say:", "", "", func(text string) tea.Cmd { return m.send(text) })
	case "ctrl+x":
		if m.chat.busy {
			id := m.chat.id
			return m.act(func(ctx context.Context) (string, error) {
				return "cancelled the run", m.b.do(ctx, http.MethodPost, "/appd/v1/chats/"+esc(id)+"/cancel", nil, nil)
			})
		}
	case "up", "k":
		m.chat.scroll++
	case "down", "j":
		if m.chat.scroll > 0 {
			m.chat.scroll--
		}
	case "pgup":
		m.chat.scroll += m.h / 2
	case "pgdown":
		if m.chat.scroll -= m.h / 2; m.chat.scroll < 0 {
			m.chat.scroll = 0
		}
	case "G", "end":
		m.chat.scroll = 0
	}
	return nil
}

// ---- projects -------------------------------------------------------------------

func (m *model) projectsView() string {
	t := m.t
	var b strings.Builder
	b.WriteString("\n")
	rows := m.projectRows()
	if len(rows) == 0 && m.filter[scrProjects] != "" {
		b.WriteString("   " + t.dim.Render("nothing matches the filter (esc clears it)") + "\n")
		return b.String()
	}
	if len(rows) == 0 {
		b.WriteString("   " + t.dim.Render("no projects. Start one from the desktop or: huginn projects new") + "\n")
		return b.String()
	}
	m.listRows(&b, scrProjects, len(rows), func(i int) string {
		p := rows[i]
		g := t.glyph(stWaiting)
		if p.Busy > 0 {
			g = t.glyph(stWorking)
		}
		if p.Status != "active" && p.Status != "" {
			g = t.glyph(stShell)
		}
		return g + " " + pad(t.text.Render(p.Name), 22) + " " + pad(t.dim.Render(p.Kind), 9) + " " + pad(t.dim.Render(fmt.Sprintf("%d members · %d busy · %d waiting", p.MemberCount, p.Busy, p.Waiting)), 36) + " " + t.dim.Render(p.Status)
	})
	return b.String()
}

func (m *model) projectsKey(s string) tea.Cmd {
	rows := m.projectRows()
	c := m.cur[scrProjects]
	switch s {
	case "enter", "right", "l":
		if c >= 0 && c < len(rows) {
			p := rows[c]
			m.proj = projState{p: p, loading: true}
			m.open(scrProject)
			b := m.b
			return func() tea.Msg {
				ctx, cancel := context.WithTimeout(context.Background(), 20*time.Second)
				defer cancel()
				mem, err := b.dashboard(ctx, p.ID)
				return dashLoaded{id: p.ID, members: mem, err: err}
			}
		}
	case "e":
		if c >= 0 && c < len(rows) {
			p := rows[c]
			return m.ask("end "+p.Name+" gracefully (its sessions wrap up)? type its name:", "", p.Name, func(string) tea.Cmd {
				return m.act(func(ctx context.Context) (string, error) {
					return p.Name + " is winding down", m.b.do(ctx, http.MethodDelete, "/appd/v1/projects/"+esc(p.ID)+"?end=graceful", nil, nil)
				})
			})
		}
	}
	m.move(s, len(rows))
	return nil
}

type projState struct {
	p       project
	members []member
	loading bool
	err     error
}

func memberState(mb member) state {
	switch {
	case mb.NeedsYou:
		return stNeeds
	case !mb.Alive:
		return stShell
	case mb.State != nil:
		return sessionState(*mb.State)
	}
	return stShell
}

func (m *model) projectView() string {
	t := m.t
	ps := &m.proj
	var b strings.Builder
	b.WriteString("\n  " + t.title.Render(ps.p.Name) + "  " + t.dim.Render(ps.p.Kind+" · "+ps.p.Status) + "\n")
	if s := oneLine(ps.p.ManifestSummary); s != "" {
		b.WriteString("  " + t.dim.Render(truncate(s, m.w-4)) + "\n")
	}
	b.WriteString("\n")
	switch {
	case ps.loading:
		b.WriteString("   " + t.dim.Render("loading…") + "\n")
	case ps.err != nil:
		b.WriteString("   " + t.err.Render(clean(ps.err.Error())) + "\n")
	default:
		m.listRows(&b, scrProject, len(ps.members), func(i int) string {
			mb := ps.members[i]
			title := ""
			if mb.Title != nil {
				title = *mb.Title
			}
			return t.glyph(memberState(mb)) + " " + pad(t.text.Render(mb.Name), 24) + " " + pad(t.dim.Render(mb.Role), 8) + " " + pad(t.dim.Render(title), m.w-62) + " " + t.dim.Render(fmt.Sprintf("%d turns · $%.2f", mb.Turns, mb.EstCostUsd))
		})
	}
	return b.String()
}

func (m *model) projectKey(s string) tea.Cmd {
	ps := &m.proj
	switch s {
	case "enter", "a", "right", "l":
		c := m.cur[scrProject]
		if c >= 0 && c < len(ps.members) && ps.members[c].Alive {
			return m.attachTo(ps.members[c].Name, false)
		}
		m.say("that member is not running", false)
		return nil
	}
	m.move(s, len(ps.members))
	return nil
}

// ---- rounds ---------------------------------------------------------------------

func roundState(r round) state {
	switch {
	case r.Running:
		return stWorking
	case !r.Enabled:
		return stShell
	case r.LastRun == nil:
		return stWaiting
	}
	switch r.LastRun.Status {
	case "ok", "done", "success":
		return stOK
	case "action", "attention":
		return stNeeds
	case "error", "failed":
		return stError
	}
	return stWaiting
}

func (m *model) roundsView() string {
	t := m.t
	var b strings.Builder
	b.WriteString("\n")
	rows := m.roundRows()
	if len(rows) == 0 && m.filter[scrRounds] != "" {
		b.WriteString("   " + t.dim.Render("nothing matches the filter (esc clears it)") + "\n")
		return b.String()
	}
	if len(rows) == 0 {
		b.WriteString("   " + t.dim.Render("no rounds. Rounds are scheduled runs: create them in the desktop or phone app") + "\n")
		return b.String()
	}
	m.listRows(&b, scrRounds, len(rows), func(i int) string {
		r := rows[i]
		next := "paused"
		if r.Enabled && r.NextRunAtSec != nil {
			next = "next " + untilSec(*r.NextRunAtSec)
		}
		head := ""
		if r.LastRun != nil {
			head = oneLine(r.LastRun.Headline)
		}
		return t.glyph(roundState(r)) + " " + pad(t.text.Render(r.Title), 24) + " " + pad(t.dim.Render(r.Cadence), 28) + " " + pad(t.dim.Render(next), 15) + " " + t.dim.Render(truncate(head, m.w-79))
	})
	if c := m.cur[scrRounds]; c >= 0 && c < len(rows) && rows[c].LastRun != nil {
		r := rows[c]
		b.WriteString("\n  " + t.heading.Render(r.Title) + "  " + t.dim.Render("last run "+ago(r.LastRun.At)) + "\n")
		for _, l := range strings.Split(lipgloss.NewStyle().Width(m.w-6).Render(r.LastRun.Headline), "\n") {
			b.WriteString("    " + t.dim.Render(l) + "\n")
		}
	}
	return b.String()
}

func untilSec(sec int64) string {
	return until(time.Unix(sec, 0).UTC().Format(time.RFC3339Nano))
}

func (m *model) roundsKey(s string) tea.Cmd {
	rows := m.roundRows()
	c := m.cur[scrRounds]
	if c < 0 || c >= len(rows) {
		m.move(s, len(rows))
		return nil
	}
	r := rows[c]
	switch s {
	case "p":
		want := !r.Enabled
		w := "resumed " + r.Title
		if !want {
			w = "paused " + r.Title
		}
		return m.act(func(ctx context.Context) (string, error) {
			return w, m.b.do(ctx, http.MethodPatch, "/appd/v1/rounds/"+esc(r.ID), map[string]bool{"enabled": want}, nil)
		})
	case "enter", "o":
		// A round's run is a chat: open its last one.
		if r.LastRun != nil && r.LastRun.ChatID != "" {
			return m.openChat(r.LastRun.ChatID)
		}
		m.say("this round has not run yet", true)
		return nil
	case "x":
		return m.ask("delete the round "+r.Title+" and its history? type delete:", "", "delete", func(string) tea.Cmd {
			return m.act(func(ctx context.Context) (string, error) {
				return "deleted " + r.Title, m.b.do(ctx, http.MethodDelete, "/appd/v1/rounds/"+esc(r.ID), nil, nil)
			})
		})
	}
	m.move(s, len(rows))
	return nil
}

// ---- archive --------------------------------------------------------------------

func (m *model) archiveView() string {
	t := m.t
	var b strings.Builder
	b.WriteString("\n")
	rows := m.archiveRows()
	if len(rows) == 0 && m.filter[scrArchive] != "" {
		b.WriteString("   " + t.dim.Render("nothing matches the filter (esc clears it)") + "\n")
		return b.String()
	}
	if len(rows) == 0 {
		b.WriteString("   " + t.dim.Render("the archive is empty: ended sessions land here, ready to revive") + "\n")
		return b.String()
	}
	m.listRows(&b, scrArchive, len(rows), func(i int) string {
		a := rows[i]
		g := t.glyph(stShell)
		if a.Live {
			g = t.glyph(stWorking)
		}
		title := a.Title
		if title == "" {
			title = "(untitled)"
		}
		return g + " " + pad(t.text.Render(title), 34) + " " + pad(t.dim.Render(a.Model), 12) + " " + pad(t.dim.Render(oneLine(a.LastMessage)), m.w-60) + " " + t.dim.Render(ago(a.ArchivedAt))
	})
	return b.String()
}

func (m *model) archiveKey(s string) tea.Cmd {
	rows := m.archiveRows()
	c := m.cur[scrArchive]
	if c < 0 || c >= len(rows) {
		m.move(s, len(rows))
		return nil
	}
	a := rows[c]
	switch s {
	case "enter", "v":
		b := m.b
		return func() tea.Msg {
			ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
			defer cancel()
			var r struct {
				Name string `json:"name"`
			}
			if err := b.do(ctx, http.MethodPost, "/appd/v1/archive/"+esc(a.ID)+"/revive", nil, &r); err != nil {
				return done{err: err}
			}
			return revived{name: r.Name}
		}
	case "x":
		return m.ask("forget the archived conversation "+named(a.Title)+"? type forget:", "", "forget", func(string) tea.Cmd {
			return m.act(func(ctx context.Context) (string, error) {
				return "forgotten", m.b.do(ctx, http.MethodDelete, "/appd/v1/archive/"+esc(a.ID), nil, nil)
			})
		})
	}
	m.move(s, len(rows))
	return nil
}

type revived struct{ name string }

// ---- settings -------------------------------------------------------------------

func (m *model) settingsView() string {
	t := m.t
	c := m.data.core
	row := func(k, v, how string) string {
		s := "   " + pad(t.dim.Render(k), 26) + t.text.Render(v)
		if how != "" {
			s += "  " + t.dim.Render(how)
		}
		return s + "\n"
	}
	opens := "the console"
	if os.Getenv("HUGINN_CONSOLE") == "0" {
		opens = "attach to main"
	}
	var b strings.Builder
	b.WriteString("\n  " + t.heading.Render("this terminal") + "\n")
	b.WriteString(row("plain huginn opens", opens, "HUGINN_CONSOLE=0 attaches to main instead"))
	ascii := "unicode"
	if t.ascii {
		ascii = "7-bit"
	}
	b.WriteString(row("art and symbols", ascii, "HUGINN_ASCII=1 forces 7-bit, =0 unicode"))
	b.WriteString(row("colour", map[bool]string{true: "off (NO_COLOR)", false: "on"}[os.Getenv("NO_COLOR") != ""], ""))
	b.WriteString("\n  " + t.heading.Render("this machine") + "\n")
	on := "off"
	if c.Device.Status.Enabled {
		on = "on"
	}
	b.WriteString(row("available to huginn", on, "devices (5) changes it"))
	if c.Device.Settings.Scope != nil {
		b.WriteString(row("scope", *c.Device.Settings.Scope, "look · work · own"))
	}
	b.WriteString(row("tray", map[bool]string{true: "attached", false: "not attached"}[c.Tray], "starts at login"))
	b.WriteString("\n  " + t.heading.Render("huginn core") + "\n")
	b.WriteString(row("version", c.Version, "core (9) updates it"))
	b.WriteString(row("paired with", c.URL, "huginn core pair re-pairs"))
	b.WriteString("\n  " + t.dim.Render("Alerts, limits and the desktop's own settings live in the desktop and phone apps.") + "\n")
	return b.String()
}

// follow1 rejoins a run that was already answering when the chat was opened:
// from the chat's own position, with the partial answer it reported.
func (m *model) follow1() tea.Cmd {
	id, since := m.chat.id, m.chat.d.Seq
	m.chat.live.Reset()
	m.chat.live.WriteString(m.chat.d.PartialText)
	ctx, gen := m.startFollow()
	b, events := m.b, m.events
	go func() {
		b.followChat(ctx, id, since, func(ev string, data json.RawMessage) {
			select {
			case events <- chatEvent{id: id, gen: gen, event: ev, data: data}:
			case <-ctx.Done():
			}
		})
		select {
		case events <- chatEvent{id: id, gen: gen, event: "done"}:
		case <-ctx.Done():
		}
	}()
	return nil
}
