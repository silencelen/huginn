package console

import (
	"context"
	"fmt"
	"sort"
	"strings"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// ---- gate: no core, no console (the desktop's CoreGate, in a terminal) ----------

func (m *model) gateView() string {
	t := m.t
	var b strings.Builder
	b.WriteString("\n")
	for _, l := range perch(t.ascii) {
		b.WriteString("   " + t.title.Render(l) + "\n")
	}
	b.WriteString("\n   " + t.heading.Render("huginn core is not answering on this computer") + "\n\n")
	b.WriteString("   " + t.text.Render("The console runs on huginn core, the background service that also") + "\n")
	b.WriteString("   " + t.text.Render("keeps this machine available to huginn.") + "\n\n")
	b.WriteString("   " + t.key.Render("s") + t.dim.Render("  start it (it is set up, just stopped)") + "\n")
	b.WriteString("   " + t.key.Render("i") + t.dim.Render("  set it up to start at boot: huginn core install") + "\n")
	b.WriteString("   " + t.key.Render("a") + t.dim.Render("  attach to the main session anyway (over ssh)") + "\n\n")
	b.WriteString("   " + t.dim.Render(m.data.coreErr.Error()) + "\n")
	return b.String()
}

func (m *model) gateKey(s string) tea.Cmd {
	switch s {
	case "s":
		m.say("starting huginn core…", true)
		return m.act(func(context.Context) (string, error) { return "huginn core started", startCore() })
	case "i":
		return m.run("huginn core install", "core", "install")
	case "a":
		return m.attachTo("main", false)
	}
	return nil
}

// ---- home -----------------------------------------------------------------------

func (m *model) homeView() string {
	t := m.t
	var b strings.Builder
	b.WriteString("\n")
	art := perch(t.ascii)
	tag := []string{"", t.dim.Render("thought, on call from anywhere"), "", m.homeFacts(), ""}
	// The sections always fit; the art takes what is left (BRAND.md: the mark
	// gives way to the one-row raven before content does).
	needN := 0
	for _, s := range m.data.sessions {
		if s.state() == stNeeds {
			needN++
		}
	}
	if needN > 3 {
		needN = 4
	}
	if needN == 0 {
		needN = 1
	}
	// body rows = h - header(1) - footer(3); without art the body is the
	// leading blank, "needs you" and its rows, a blank, and the sections.
	room := m.h - 4 - (1 + 1 + needN + 1 + len(sections))
	switch {
	case room >= len(art)+1 && m.w >= 84:
		for i, l := range art {
			b.WriteString("  " + t.title.Render(l) + "    " + tag[i%len(tag)] + "\n")
		}
		b.WriteString("\n")
	case room >= len(art)+2:
		for _, l := range art {
			b.WriteString("  " + t.title.Render(l) + "\n")
		}
		b.WriteString("  " + tag[1] + "   " + tag[3] + "\n\n")
	case room >= 2:
		b.WriteString("  " + tag[1] + "   " + tag[3] + "\n\n")
	}
	if m.data.appdErr != nil {
		b.WriteString("\n  " + t.err.Render("huginn is not reachable from this core: "+m.data.appdErr.Error()) + "\n")
		if !m.data.core.Paired {
			b.WriteString("  " + t.dim.Render("pair it: huginn core pair --url https://<host>:8787") + "\n")
		}
	}
	// What needs you, first and alone in the lit accent.
	var needs []session
	for _, s := range m.data.sessions {
		if s.state() == stNeeds {
			needs = append(needs, s)
		}
	}
	b.WriteString("  " + t.heading.Render("needs you") + "\n")
	if len(needs) == 0 {
		b.WriteString("   " + t.dim.Render("nothing — every session is working or waiting") + "\n")
	}
	for i, s := range needs {
		if i == 3 {
			b.WriteString("   " + t.dim.Render(fmt.Sprintf("+%d more in sessions", len(needs)-3)) + "\n")
			break
		}
		b.WriteString("   " + t.glyph(stNeeds) + " " + pad(t.text.Render(s.Name), 22) + " " + pad(t.dim.Render(firstLine(s)), m.w-40) + " " + t.dim.Render(ago(s.ActivityAt)) + "\n")
	}
	b.WriteString("\n")
	cur := m.cur[scrHome]
	for i, x := range sections {
		row := pad(t.key.Render(x.key)+" "+t.text.Render(x.name), 16) + " " + m.sectionSummary(x.scr)
		mark := "  "
		if i == cur {
			mark = t.key.Render(t.cursor()) + " "
			row = t.selected.Render(pad(row, m.w-6))
		}
		b.WriteString("  " + mark + row + "\n")
	}
	return b.String()
}

func (m *model) homeFacts() string {
	t := m.t
	on := 0
	for _, d := range m.data.devices {
		if d.Online {
			on++
		}
	}
	bits := []string{}
	if m.data.st.Host != "" {
		bits = append(bits, "host "+m.data.st.Host)
	}
	if len(m.data.devices) > 0 {
		bits = append(bits, fmt.Sprintf("%d of %d devices online", on, len(m.data.devices)))
	}
	return t.dim.Render(strings.Join(bits, " · "))
}

func (m *model) sectionSummary(s screen) string {
	t := m.t
	switch s {
	case scrSessions:
		vis, inProj := m.visibleSessions()
		n := map[state]int{}
		for _, x := range vis {
			n[x.state()]++
		}
		parts := []string{t.text.Render(fmt.Sprintf("%-3d", len(vis)))}
		for _, st := range []state{stWorking, stNeeds, stWaiting} {
			if n[st] > 0 {
				parts = append(parts, t.glyph(st)+" "+t.dim.Render(fmt.Sprintf("%d %s", n[st], stateWords[st])))
			}
		}
		if inProj > 0 {
			parts = append(parts, t.dim.Render(fmt.Sprintf("%d in projects", inProj)))
		}
		return strings.Join(parts, "  ")
	case scrChats:
		run := 0
		for _, c := range m.data.lists.chats {
			if c.Running {
				run++
			}
		}
		s := t.text.Render(fmt.Sprintf("%-3d", len(m.data.lists.chats)))
		if run > 0 {
			s += "  " + t.glyph(stWorking) + " " + t.dim.Render(fmt.Sprintf("%d answering", run))
		}
		return s
	case scrProjects:
		s := t.text.Render(fmt.Sprintf("%-3d", len(m.data.lists.projects)))
		for i, p := range m.data.lists.projects {
			if i == 3 {
				break
			}
			g := t.glyph(stWaiting)
			if p.Busy > 0 {
				g = t.glyph(stWorking)
			}
			s += "  " + g + " " + t.dim.Render(p.Name)
		}
		return s
	case scrRounds:
		s := t.text.Render(fmt.Sprintf("%-3d", len(m.data.lists.rounds)))
		var next *round
		for i, r := range m.data.lists.rounds {
			if r.Enabled && r.NextRunAtSec != nil && (next == nil || *r.NextRunAtSec < *next.NextRunAtSec) {
				next = &m.data.lists.rounds[i]
			}
		}
		if next != nil {
			s += "  " + t.dim.Render("next: "+next.Title+" "+untilSec(*next.NextRunAtSec))
		}
		return s
	case scrArchive:
		return t.text.Render(fmt.Sprintf("%-3d", len(m.data.lists.archive)))
	case scrSettings:
		return t.dim.Render("    this terminal, this machine, the core")
	case scrDevices:
		parts := []string{t.text.Render(fmt.Sprintf("%-3d", len(m.data.devices)))}
		for i, d := range m.data.devices {
			if i == 4 {
				break
			}
			parts = append(parts, m.deviceGlyph(d)+" "+t.dim.Render(d.Name))
		}
		return strings.Join(parts, "  ")
	case scrUsage:
		h := m.data.st.Headroom
		if h.WorstLabel == "" {
			return ""
		}
		return t.dim.Render(fmt.Sprintf("    worst: %s %d%%", strings.ToLower(h.WorstLabel), h.WorstPercent))
	case scrCore:
		c := m.data.core
		w := "core " + c.Version
		if c.Device.Status.Enrolled {
			w += " · this machine is a device"
		}
		return t.dim.Render("    " + w)
	}
	return t.dim.Render("    in the console in 2.4")
}

func (m *model) homeKey(s string) tea.Cmd {
	switch s {
	case "enter", "right", "l":
		m.open(sections[m.cur[scrHome]].scr)
		return nil
	}
	m.move(s, len(sections))
	return nil
}

// ---- sessions ---------------------------------------------------------------------

func firstLine(s session) string {
	if s.Title != "" {
		return s.Title
	}
	for _, p := range s.Preview {
		if p = strings.TrimSpace(p); p != "" {
			return p
		}
	}
	return ""
}

func (m *model) sessionsView() string {
	t := m.t
	vis, inProj := m.sessionRows()
	var b strings.Builder
	b.WriteString("\n")
	if len(vis) == 0 {
		if m.filter[scrSessions] != "" {
			b.WriteString("   " + t.dim.Render("no session matches the filter (esc clears it)") + "\n")
			return b.String()
		}
		b.WriteString("   " + t.dim.Render("no sessions yet. n starts one, or: huginn <name>") + "\n")
		return b.String()
	}
	cur := m.cur[scrSessions]
	if cur >= len(vis) {
		cur = len(vis) - 1
		m.cur[scrSessions] = cur
	}
	listRows := m.h - 16
	if listRows < 4 {
		listRows = 4
	}
	start := 0
	if cur >= listRows {
		start = cur - listRows + 1
	}
	nameW := 24
	for i := start; i < len(vis) && i < start+listRows; i++ {
		s := vis[i]
		meta := t.dim.Render(pad(s.LiveModel, 10) + " " + pad(ago(s.ActivityAt), 4))
		row := t.glyph(s.state()) + " " + pad(t.text.Render(s.Name), nameW) + " " + pad(t.dim.Render(firstLine(s)), m.w-nameW-24) + " " + meta
		mark := "  "
		if i == cur {
			mark = t.key.Render(t.cursor()) + " "
			row = t.selected.Render(pad(row, m.w-6))
		}
		b.WriteString("  " + mark + row + "\n")
	}
	if inProj > 0 {
		b.WriteString("    " + t.dim.Render(fmt.Sprintf("%d more in projects", inProj)) + "\n")
	}
	// The selected session, in a few lines.
	s := vis[cur]
	b.WriteString("\n  " + t.heading.Render(s.Name) + "  " + t.glyph(s.state()) + " " + t.dim.Render(stateWords[s.state()]))
	facts := []string{}
	if s.AttachedClients > 0 {
		facts = append(facts, fmt.Sprintf("%d attached", s.AttachedClients))
	}
	if s.ContextPercent > 0 {
		facts = append(facts, fmt.Sprintf("context %d%%", s.ContextPercent))
	}
	if s.PendingSends > 0 {
		facts = append(facts, fmt.Sprintf("%d queued", s.PendingSends))
	}
	if s.SoftEnding {
		facts = append(facts, "ending")
	}
	if len(facts) > 0 {
		b.WriteString("  " + t.dim.Render("· "+strings.Join(facts, " · ")))
	}
	b.WriteString("\n")
	shown := 0
	for _, p := range s.Preview {
		if p = strings.TrimSpace(p); p == "" || shown == 4 {
			continue
		}
		b.WriteString("    " + t.dim.Render(truncate(p, m.w-6)) + "\n")
		shown++
	}
	return b.String()
}

func (m *model) selectedSession() (session, bool) {
	vis, _ := m.sessionRows()
	c := m.cur[scrSessions]
	if c < 0 || c >= len(vis) {
		return session{}, false
	}
	return vis[c], true
}

func (m *model) sessionsKey(s string) tea.Cmd {
	vis, _ := m.sessionRows()
	sel, ok := m.selectedSession()
	switch s {
	case "enter", "a", "right", "l":
		if ok {
			return m.attachTo(sel.Name, false)
		}
	case "S":
		if ok {
			return m.attachTo(sel.Name, true)
		}
	case "n":
		return m.ask("new session name:", "", "", func(name string) tea.Cmd {
			name = strings.ToLower(name)
			if !validName.MatchString(name) {
				m.say(fmt.Sprintf("%q is not a session name (letters, digits, - and _; up to 50)", name), false)
				return nil
			}
			return tea.Sequence(m.act(func(ctx context.Context) (string, error) {
				return "started " + name, m.b.newSession(ctx, name)
			}), m.attachTo(name, false))
		})
	case "s":
		if ok {
			name := sel.Name
			return m.ask("send to "+name+":", "", "", func(text string) tea.Cmd {
				return m.act(func(ctx context.Context) (string, error) {
					r, err := m.b.sendLine(ctx, name, text)
					return name + ": " + r, err
				})
			})
		}
	case "e":
		if ok {
			name := sel.Name
			return m.ask("wrap up and end "+name+"? type yes:", "", "yes", func(string) tea.Cmd {
				return m.act(func(ctx context.Context) (string, error) {
					return name + " will end when it settles", m.b.softEnd(ctx, name)
				})
			})
		}
	case "R":
		if ok {
			old := sel.Name
			return m.ask("rename "+old+" to:", old, "", func(to string) tea.Cmd {
				to = strings.ToLower(to)
				return m.act(func(ctx context.Context) (string, error) {
					return old + " is now " + to, m.b.renameSession(ctx, old, to)
				})
			})
		}
	case "x":
		if ok {
			name := sel.Name
			return m.ask("kill "+name+" now (unsaved work is lost)? type its name:", "", name, func(string) tea.Cmd {
				return m.act(func(ctx context.Context) (string, error) {
					return "killed " + name, m.b.killSession(ctx, name)
				})
			})
		}
	case "r":
		m.say("refreshing…", true)
		return m.fetch()
	}
	m.move(s, len(vis))
	return nil
}

// ---- devices --------------------------------------------------------------------

func (m *model) deviceGlyph(d device) string {
	switch {
	case !d.Online:
		return m.t.glyph(stShell)
	case d.Running:
		return m.t.glyph(stWorking)
	}
	return m.t.glyph(stWaiting)
}

func (m *model) devicesView() string {
	t := m.t
	var b strings.Builder
	c := m.data.core.Device
	b.WriteString("\n  " + t.heading.Render("this machine") + "\n")
	on := "off"
	if c.Status.Enabled {
		on = "on"
	}
	scope, name := "", ""
	if c.Settings.Scope != nil {
		scope = *c.Settings.Scope
	}
	if c.Settings.Name != nil {
		name = *c.Settings.Name
	}
	b.WriteString("   " + t.text.Render("available to huginn: ") + t.key.Render(on))
	if name != "" {
		b.WriteString(t.dim.Render("  · name " + name))
	}
	if scope != "" {
		b.WriteString(t.dim.Render("  · scope " + scope))
	}
	b.WriteString("\n   " + t.dim.Render(c.Status.Note) + "\n")
	b.WriteString("\n  " + t.heading.Render("the fleet") + "\n")
	devs := m.deviceRows()
	if len(devs) == 0 && m.filter[scrDevices] != "" {
		b.WriteString("   " + t.dim.Render("no device matches the filter (esc clears it)") + "\n")
	} else if len(devs) == 0 {
		b.WriteString("   " + t.dim.Render("no devices. o makes this machine one, or: huginn device on") + "\n")
	}
	cur := m.cur[scrDevices]
	for i, d := range devs {
		st := "online"
		if !d.Online {
			st = "last seen " + ago(d.LastSeenSec)
		} else if d.Running {
			st = "running a job"
		}
		if d.Locked {
			st += " · locked"
		}
		row := m.deviceGlyph(d) + " " + pad(t.text.Render(d.Name), 18) + " " + pad(t.dim.Render(d.Platform), 9) + " " + pad(t.dim.Render(d.Scope), 6) + " " + pad(t.dim.Render(d.Version), 8) + " " + t.dim.Render(st)
		mark := "  "
		if i == cur {
			mark = t.key.Render(t.cursor()) + " "
			row = t.selected.Render(pad(row, m.w-6))
		}
		b.WriteString("  " + mark + row + "\n")
	}
	return b.String()
}

func (m *model) devicesKey(s string) tea.Cmd {
	c := m.data.core.Device
	switch s {
	case "o":
		want := !c.Status.Enabled
		return m.act(func(ctx context.Context) (string, error) {
			w := "this machine is available to huginn"
			if !want {
				w = "this machine is no longer a device (the core retires its row)"
			}
			return w, m.b.setDevice(ctx, map[string]any{"enabled": want})
		})
	case "c":
		cur := "look"
		if c.Settings.Scope != nil {
			cur = *c.Settings.Scope
		}
		next := map[string]string{"look": "work", "work": "own", "own": "look"}[cur]
		if next == "" {
			next = "look"
		}
		return m.ask(fmt.Sprintf("scope %s → %s (look reads, work edits in its root, own runs anything)? type %s:", cur, next, next), "", next, func(string) tea.Cmd {
			return m.act(func(ctx context.Context) (string, error) {
				return "scope is now " + next, m.b.setDevice(ctx, map[string]any{"scope": next})
			})
		})
	}
	m.move(s, len(m.deviceRows()))
	return nil
}

// deviceRows: online first, then by name, through the `/` filter.
func (m *model) deviceRows() []device {
	var devs []device
	for _, d := range m.data.devices {
		if m.matches(scrDevices, d.Name, d.Platform, d.Scope, d.Version) {
			devs = append(devs, d)
		}
	}
	sort.SliceStable(devs, func(i, j int) bool {
		if devs[i].Online != devs[j].Online {
			return devs[i].Online
		}
		return devs[i].Name < devs[j].Name
	})
	return devs
}

// ---- usage ------------------------------------------------------------------------

func (m *model) bar(pct, w int) string {
	if pct < 0 {
		pct = 0
	}
	if pct > 100 {
		pct = 100
	}
	full := pct * w / 100
	on, off := "█", "░"
	if m.t.ascii {
		on, off = "#", "-"
	}
	st := m.t.working
	if pct >= 90 {
		st = m.t.needs
	}
	return st.Render(strings.Repeat(on, full)) + m.t.dim.Render(strings.Repeat(off, w-full))
}

func (m *model) usageView() string {
	t := m.t
	h := m.data.hr
	var b strings.Builder
	b.WriteString("\n")
	ids := make([]string, 0, len(h.Accounts))
	for id := range h.Accounts {
		ids = append(ids, id)
	}
	sort.Strings(ids)
	for _, id := range ids {
		a := h.Accounts[id]
		who := a.Email
		if who == "" {
			who = id
		}
		live := ""
		if a.Live {
			live = t.ok.Render("  ■ in use")
			if t.ascii {
				live = t.ok.Render("  + in use")
			}
		}
		b.WriteString("  " + t.heading.Render(who) + live + "\n")
		keys := make([]string, 0, len(a.Windows))
		for k := range a.Windows {
			keys = append(keys, k)
		}
		sort.Strings(keys)
		for _, k := range keys {
			w := a.Windows[k]
			b.WriteString(fmt.Sprintf("   %s %s %s  %s\n", pad(t.text.Render(strings.ToLower(w.Label)), 28), m.bar(w.Percent, 24), pad(t.text.Render(fmt.Sprintf("%3d%%", w.Percent)), 5), t.dim.Render("resets "+until(w.ResetsAt))))
		}
		b.WriteString("\n")
	}
	var stops []string
	for name, s := range h.Sentinels {
		if s != nil {
			stops = append(stops, name+" ("+s.Reason+")")
		}
	}
	sort.Strings(stops)
	if len(stops) > 0 {
		b.WriteString("  " + t.needs.Render("held: "+strings.Join(stops, ", ")) + "\n")
	}
	if len(h.Held) > 0 {
		b.WriteString("  " + t.needs.Render(fmt.Sprintf("%d sessions held for the limit", len(h.Held))) + "\n")
	}
	if h.Arbiter.Why != "" {
		b.WriteString("  " + t.dim.Render("last decision: "+h.Arbiter.Why) + "\n")
	}
	if len(ids) == 0 {
		b.WriteString("  " + t.dim.Render("no usage reported yet") + "\n")
	}
	return b.String()
}

// ---- core -------------------------------------------------------------------------

func (m *model) coreView() string {
	t := m.t
	c := m.data.core
	st := m.data.st
	row := func(k, v string) string { return "   " + pad(t.dim.Render(k), 16) + t.text.Render(v) + "\n" }
	var b strings.Builder
	b.WriteString("\n  " + t.heading.Render("huginn core") + "\n")
	b.WriteString(row("version", c.Version))
	paired := "no — huginn core pair --url https://<host>:8787"
	if c.Paired {
		paired = c.URL
	}
	b.WriteString(row("paired with", paired))
	tray := "not attached"
	if c.Tray {
		tray = "attached"
	}
	b.WriteString(row("tray", tray))
	b.WriteString(row("this device", c.Device.Status.Note))
	if st.AppdVersion != "" {
		b.WriteString("\n  " + t.heading.Render("the host") + "\n")
		b.WriteString(row("host", st.Host))
		b.WriteString(row("appd", st.AppdVersion))
		b.WriteString(row("claude", st.Claude))
		b.WriteString(row("up", fmtUptime(st.UptimeSec)))
		if len(st.Load) > 0 {
			b.WriteString(row("load", fmt.Sprintf("%.2f", st.Load[0])))
		}
		b.WriteString(row("disk", st.Disk.UsedPercent+" used, "+st.Disk.Free+" free"))
	}
	return b.String()
}

func fmtUptime(sec int64) string {
	d := sec / 86400
	h := sec % 86400 / 3600
	if d > 0 {
		return fmt.Sprintf("%dd %dh", d, h)
	}
	return fmt.Sprintf("%dh %dm", h, sec%3600/60)
}

func (m *model) coreKey(s string) tea.Cmd {
	switch s {
	case "u":
		return m.run("huginn update", "update")
	case "R":
		return m.act(func(ctx context.Context) (string, error) {
			return "huginn core is restarting", m.b.do(ctx, "POST", "/core/restart", nil, nil)
		})
	case "i":
		return m.run("huginn core install", "core", "install")
	}
	return nil
}

// ---- help & later ---------------------------------------------------------------

func (m *model) helpView() string {
	t := m.t
	k := func(key, what string) string { return "   " + pad(t.key.Render(key), 12) + t.text.Render(what) + "\n" }
	var b strings.Builder
	b.WriteString("\n  " + t.heading.Render("keys") + "\n")
	updn, enter := "↑↓ j k", "⏎"
	if t.ascii {
		updn, enter = "up/dn j k", "enter"
	}
	b.WriteString(k(updn, "move"))
	b.WriteString(k(enter, "open, or attach to a session"))
	b.WriteString(k("esc", "back"))
	b.WriteString(k("1-9", "jump to a section"))
	b.WriteString(k("/", "filter the list on screen (esc clears)"))
	b.WriteString(k(":", "go to anything, attach, or run any huginn verb"))
	b.WriteString(k("r", "refresh now"))
	b.WriteString(k("q", "quit"))
	b.WriteString("\n  " + t.heading.Render("states") + "\n")
	for _, s := range []state{stWorking, stNeeds, stWaiting, stShell, stOK, stError} {
		b.WriteString("   " + t.glyph(s) + " " + t.text.Render(stateWords[s]) + "\n")
	}
	b.WriteString("\n  " + t.dim.Render("Each screen names its one-shot command at the bottom; huginn help lists all.") + "\n")
	b.WriteString("  " + t.dim.Render("Plain huginn opens this console. HUGINN_CONSOLE=0 attaches to main instead.") + "\n")
	b.WriteString("  " + t.dim.Render("In a session, Ctrl-b d detaches and brings you back here.") + "\n")
	return b.String()
}

func (m *model) laterView() string {
	t := m.t
	x := sectionFor(m.scr)
	return "\n  " + t.heading.Render(x.name) + "\n\n   " +
		t.text.Render(fmt.Sprintf("%s arrive in the console in the next release.", strings.ToUpper(x.name[:1])+x.name[1:])) + "\n   " +
		t.dim.Render("today: "+x.verb) + "\n"
}

var _ = lipgloss.Width
