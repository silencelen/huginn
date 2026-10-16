package console

import (
	"context"
	"flag"
	"fmt"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
	"github.com/muesli/termenv"
)

var update = flag.Bool("update", false, "rewrite the golden files")

// A fake core: /core/* and /appd/v1/* on a unix socket, synthetic data shaped
// like appd 3.12's (field names from mobile/README.md; seconds vs ms as there).
func fakeCore(t *testing.T, paired bool) string {
	t.Helper()
	now := time.Now().Unix()
	sock := filepath.Join(t.TempDir(), "core.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	j := func(body string) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			if r.Header.Get("X-Huginn-Core-Proto") != "1" {
				w.WriteHeader(426)
				return
			}
			w.Header().Set("Content-Type", "application/json")
			fmt.Fprint(w, body)
		}
	}
	mux.HandleFunc("/core/status", j(fmt.Sprintf(`{"version":"2.3.0","paired":%v,"url":"https://huginn.example.net:8787","tray":true,
		"device":{"status":{"enabled":true,"enrolled":true,"note":"Waiting for work","deviceId":"d1"},"settings":{"enabled":true,"name":"a Linux laptop","scope":"work"}}}`, paired)))
	if !paired {
		mux.HandleFunc("/appd/", func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(503)
			fmt.Fprint(w, `{"error":"huginn core is not paired with huginn: run `+"`huginn core pair`"+`"}`)
		})
	} else {
		mux.HandleFunc("/appd/v1/status", j(`{"host":"huginn","appdVersion":"3.12.0","uptimeSec":786863,"load":[1.5],"claude":"2.1.300 (Claude Code)",
			"sessions":4,"disk":{"free":"9.3G","usedPercent":"91%"},"headroom":{"worstPercent":66,"worstLabel":"Current week, all models","mode":"auto"}}`))
		mux.HandleFunc("/appd/v1/sessions", j(fmt.Sprintf(`{"sessions":[
			{"name":"main","state":"idle","title":"Inbox triage","preview":["⎿ done"],"activityAt":%d,"liveModel":"Opus 5.5"},
			{"name":"widgets","state":"attention","title":"Ship 0.8.0 to a foldable?","preview":["Do you want to proceed?","❯ 1. Yes"],"activityAt":%d,"liveModel":"Opus 5.5","attachedClients":1},
			{"name":"build","state":"running","title":"Release the desktop","preview":["Running gradle…"],"activityAt":%d,"liveModel":"Sonnet 5.5","contextPercent":42},
			{"name":"scratch","state":null,"title":"","preview":["$"],"activityAt":%d},
			{"name":"osid-lead","state":"idle","title":"lead","activityAt":%d,"project":{"name":"osid","role":"lead"}}]}`, now-3600, now-120, now-30, now-86400*3, now-60)))
		mux.HandleFunc("/appd/v1/devices", j(fmt.Sprintf(`{"devices":[
			{"id":"d1","name":"a Linux laptop","platform":"linux","scope":"work","online":true,"version":"2.3.0","lastSeenSec":%d},
			{"id":"d2","name":"PC01","platform":"windows","scope":"own","online":true,"running":true,"version":"2.3.0","lastSeenSec":%d},
			{"id":"d3","name":"buildbox","platform":"linux","scope":"work","online":false,"version":"1.8.0","lastSeenSec":%d}]}`, now, now, now-7200)))
		mux.HandleFunc("/appd/v1/headroom", j(`{"mode":"auto","accounts":{"a1":{"email":"owner@example.net","live":true,"windows":{
			"session":{"percent":3,"resetsAt":"2099-01-01T00:00:00Z","label":"Current session"},
			"weekly_all":{"percent":66,"resetsAt":"2099-01-01T00:00:00Z","label":"Current week, all models"}}}},
			"sentinels":{"STOP":null},"held":[],"arbiter":{"why":"under every threshold"}}`))
		mux.HandleFunc("/appd/v1/chats", j(fmt.Sprintf(`{"chats":[
			{"id":"c1","title":"an exchange sizing","mode":"ask","running":false,"updatedAt":%d,"lastSnippet":"Half Kelly keeps the drawdown under 50%%.","turns":2},
			{"id":"c2","title":"Restart the arr stack","mode":"act","running":true,"updatedAt":%d,"lastSnippet":"Restarting sonarr…"}]}`, now-540, now-20)))
		mux.HandleFunc("/appd/v1/chats/c1", j(`{"id":"c1","title":"an exchange sizing","mode":"ask","seq":0,"messages":[
			{"type":"user","text":"How big should each bet be?"},
			{"type":"tool","name":"Read","input":"/srv/bursar/config.yaml"},
			{"type":"assistant","text":"Half Kelly keeps the drawdown under 50%, which matches your halt rule."},
			{"type":"result","ok":true,"turns":2,"costUsd":0.04}]}`))
		mux.HandleFunc("/appd/v1/chats/c1/messages", func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(202)
			fmt.Fprint(w, `{"ok":true,"running":true}`)
		})
		mux.HandleFunc("/appd/v1/chats/c1/stream", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprint(w, "event: started\ndata: {}\n\nevent: tool_start\ndata: {\"name\":\"Bash\"}\n\nevent: delta\ndata: {\"text\":\"Streamed \"}\n\nevent: delta\ndata: {\"text\":\"answer.\"}\n\nevent: done\ndata: {}\n\n")
		})
		mux.HandleFunc("/appd/v1/rounds", j(fmt.Sprintf(`{"rounds":[
			{"id":"r1","title":"Morning briefing","enabled":true,"mode":"ask","cadence":"Daily at 7:00 AM","nextRunAtSec":%d,"lastRun":{"at":%d,"chatId":"c1","status":"ok","headline":"All quiet: 0 alerts, backups green."}},
			{"id":"r2","title":"Weekly capacity","enabled":false,"mode":"ask","cadence":"Weekly on Sunday at 8:00 AM"}]}`, now+3*3600, now-20*3600)))
		mux.HandleFunc("/appd/v1/projects", j(`{"projects":[{"id":"p1","name":"dashboard-app","kind":"software","status":"active","memberCount":3,"alive":3,"busy":1,"waiting":1,"manifestSummary":"Widgets for a foldable"}]}`))
		mux.HandleFunc("/appd/v1/projects/p1/dashboard", j(`{"members":[
			{"role":"lead","name":"dashboard-app-lead","alive":true,"state":"attention","needsYou":true,"title":"Ship 0.8.0?","turns":40,"estCostUsd":3.2},
			{"role":"member","name":"dashboard-app-core","alive":true,"state":"running","turns":12,"estCostUsd":1.1}]}`))
		mux.HandleFunc("/appd/v1/archive", j(fmt.Sprintf(`{"archives":[{"id":"a1","title":"Old router migration","model":"Opus 5.5","lastMessage":"Done; the VLANs are live.","archivedAt":%d}]}`, now-86400*9)))
		mux.HandleFunc("/appd/v1/watch", func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() })
	}
	srv := &http.Server{Handler: mux}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
	return sock
}

// render drives the model to a loaded frame at w×h, in plain ASCII and no
// colour, so the golden files read as text.
func render(t *testing.T, sock string, w, h int, keys ...string) string {
	t.Helper()
	os.Setenv("HUGINN_ASCII", "1")
	lipgloss.SetColorProfile(termenv.Ascii)
	m := newModel(context.Background(), Options{Socket: sock, Proto: "1", Version: "2.3.0", Home: t.TempDir()})
	m.Update(tea.WindowSizeMsg{Width: w, Height: h})
	m.Update(m.fetch()())
	for _, k := range keys {
		var msg tea.KeyMsg
		switch k {
		case "enter":
			msg = tea.KeyMsg{Type: tea.KeyEnter}
		case "esc":
			msg = tea.KeyMsg{Type: tea.KeyEsc}
		case "down":
			msg = tea.KeyMsg{Type: tea.KeyDown}
		case "up":
			msg = tea.KeyMsg{Type: tea.KeyUp}
		default:
			msg = tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(k)}
		}
		_, cmd := m.Update(msg)
		drain(m, cmd)
	}
	return m.View()
}

// Ages move with the clock; the goldens hold them as written at record time,
// so they compare with relative ages masked.
func golden(t *testing.T, name, got string) {
	t.Helper()
	p := filepath.Join("testdata", name+".golden")
	if *update {
		os.WriteFile(p, []byte(got), 0o644)
		return
	}
	want, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("%s: %v (run go test -update)", p, err)
	}
	if string(want) != got {
		t.Errorf("%s differs (go test ./internal/console -update to accept):\n--- got\n%s\n--- want\n%s", name, got, want)
	}
}

func TestScreens(t *testing.T) {
	sock := fakeCore(t, true)
	cases := []struct {
		name string
		w, h int
		keys []string
	}{
		{"home-100x30", 100, 30, nil},
		{"home-80x24", 80, 24, nil},
		{"home-60x18", 60, 18, nil},
		{"sessions-100x30", 100, 30, []string{"1"}},
		{"sessions-down-80x24", 80, 24, []string{"1", "down"}},
		{"devices-100x30", 100, 30, []string{"5"}},
		{"usage-100x30", 100, 30, []string{"6"}},
		{"core-100x30", 100, 30, []string{"9"}},
		{"help-80x24", 80, 24, []string{"?"}},
		{"chats-100x30", 100, 30, []string{"2"}},
		{"chat-100x30", 100, 30, []string{"2", "enter"}},
		{"projects-100x30", 100, 30, []string{"3"}},
		{"project-100x30", 100, 30, []string{"3", "enter"}},
		{"rounds-100x30", 100, 30, []string{"4"}},
		{"archive-100x30", 100, 30, []string{"7"}},
		{"settings-100x30", 100, 30, []string{"8"}},
		{"palette-100x30", 100, 30, []string{":"}},
		{"palette-typed-80x24", 80, 24, []string{":", "d", "e", "v"}},
		{"too-small", 50, 12, nil},
	}
	for _, c := range cases {
		golden(t, c.name, render(t, sock, c.w, c.h, c.keys...))
	}
}

func TestNotPairedSaysHow(t *testing.T) {
	got := render(t, fakeCore(t, false), 100, 30)
	if !strings.Contains(got, "huginn core pair") {
		t.Fatalf("an unpaired core's home does not say how to pair:\n%s", got)
	}
}

func TestGateWhenCoreIsDown(t *testing.T) {
	got := render(t, filepath.Join(t.TempDir(), "nothing.sock"), 100, 30)
	for _, want := range []string{"huginn core is not answering", "start it", "huginn core install", "attach to the main session"} {
		if !strings.Contains(got, want) {
			t.Fatalf("gate screen lacks %q:\n%s", want, got)
		}
	}
}

func TestEveryFrameFits(t *testing.T) {
	sock := fakeCore(t, true)
	for _, sz := range [][2]int{{60, 18}, {80, 24}, {120, 40}, {200, 60}} {
		for _, k := range []string{"", "1", "5", "6", "9", "?"} {
			var keys []string
			if k != "" {
				keys = []string{k}
			}
			out := render(t, sock, sz[0], sz[1], keys...)
			lines := strings.Split(out, "\n")
			if len(lines) > sz[1] {
				t.Errorf("%dx%d key %q: %d lines, more than the terminal", sz[0], sz[1], k, len(lines))
			}
			for i, l := range lines {
				if lipgloss.Width(l) > sz[0] {
					t.Errorf("%dx%d key %q line %d is %d wide", sz[0], sz[1], k, i, lipgloss.Width(l))
				}
			}
		}
	}
}

func TestArtMatchesBrand(t *testing.T) {
	for _, f := range []string{"perch-upright.txt", "perch-upright-ascii.txt"} {
		a, _ := os.ReadFile(filepath.Join("art", f))
		b, err := os.ReadFile(filepath.Join("..", "..", "..", "assets", "brand", "explorations", "round5", f))
		if err != nil || string(a) != string(b) {
			t.Errorf("art/%s differs from assets/brand/explorations/round5/%s: copy it again", f, f)
		}
	}
}

// drain runs a command chain synchronously (loaders return a message; batches
// and the background watch are skipped), so a key that opens a screen has its
// data in the frame.
func drain(m *model, cmd tea.Cmd) {
	for i := 0; cmd != nil && i < 5; i++ {
		done := make(chan tea.Msg, 1)
		go func() { done <- cmd() }()
		var msg tea.Msg
		select {
		case msg = <-done:
		case <-time.After(400 * time.Millisecond): // a cursor blink, not a loader
			return
		}
		switch msg.(type) {
		case nil, tea.BatchMsg:
			return
		}
		_, cmd = m.Update(msg)
	}
}

// Sending in a chat posts, follows the run's stream through the core, and
// shows the answer as it arrives, then the transcript.
func TestChatSendStreams(t *testing.T) {
	sock := fakeCore(t, true)
	os.Setenv("HUGINN_ASCII", "1")
	lipgloss.SetColorProfile(termenv.Ascii)
	m := newModel(context.Background(), Options{Socket: sock, Proto: "1", Version: "2.3.0", Home: t.TempDir()})
	m.Update(tea.WindowSizeMsg{Width: 100, Height: 30})
	m.Update(m.fetch()())
	drain(m, m.openChat("c1"))
	m.send("and the next one?")
	sawLive := false
	for deadline := time.Now().Add(3 * time.Second); time.Now().Before(deadline); {
		select {
		case msg := <-m.events:
			ev, ok := msg.(chatEvent)
			if !ok {
				continue
			}
			cmd := m.onChatEvent(ev)
			if strings.Contains(m.View(), "Streamed answer.") {
				sawLive = true
			}
			if ev.event == "done" {
				drain(m, cmd)
				if !sawLive {
					t.Fatalf("the streamed answer never showed:\n%s", m.View())
				}
				if m.chat.busy {
					t.Fatal("still busy after done")
				}
				return
			}
		case <-time.After(3 * time.Second):
			t.Fatal("no stream events")
		}
	}
	t.Fatal("the run never finished")
}

func keys(word string) []string {
	var k []string
	for _, r := range word {
		k = append(k, string(r))
	}
	return k
}

func TestFilter(t *testing.T) {
	sock := fakeCore(t, true)
	got := render(t, sock, 100, 30, append([]string{"1", "/"}, keys("rele")...)...)
	if !strings.Contains(got, "build") || strings.Contains(got, "widgets   ") || !strings.Contains(got, "/ rele") {
		t.Fatalf("filtering sessions by 'rele' (build's title) while typing:\n%s", got)
	}
	got = render(t, sock, 100, 30, append(append([]string{"1", "/"}, keys("rele")...), "enter")...)
	if !strings.Contains(got, "filter: rele") || strings.Contains(got, "! widgets") {
		t.Fatalf("a kept filter is not shown, or does not hold:\n%s", got)
	}
	got = render(t, sock, 100, 30, append(append([]string{"1", "/"}, keys("rele")...), "enter", "esc")...)
	if strings.Contains(got, "filter:") || !strings.Contains(got, "widgets") {
		t.Fatalf("esc did not clear the filter:\n%s", got)
	}
	got = render(t, sock, 100, 30, append([]string{"1", "/"}, keys("zzz")...)...)
	if !strings.Contains(got, "no session matches the filter") {
		t.Fatalf("an empty filtered list must say so:\n%s", got)
	}
	got = render(t, sock, 100, 30, append([]string{"2", "/"}, keys("arr")...)...)
	if !strings.Contains(got, "Restart the arr stack") || strings.Contains(got, "an exchange") {
		t.Fatalf("chats filter:\n%s", got)
	}
}

func TestPalette(t *testing.T) {
	sock := fakeCore(t, true)
	got := render(t, sock, 100, 30, append([]string{":"}, keys("widg")...)...)
	if !strings.Contains(got, "attach widgets") || !strings.Contains(got, "huginn widg") {
		t.Fatalf("palette for 'widg' should offer the session and the verb fallback:\n%s", got)
	}
	got = render(t, sock, 100, 30, append(append([]string{":"}, keys("rounds")...), "enter")...)
	if !strings.Contains(got, "Morning briefing") {
		t.Fatalf("':rounds' + enter should open Rounds:\n%s", got)
	}
	got = render(t, sock, 100, 30, append(append([]string{":"}, keys("chat an exchange")...), "enter")...)
	if !strings.Contains(got, "How big should each bet be?") {
		t.Fatalf("':chat an exchange' + enter should open that chat:\n%s", got)
	}
	// No 'Host huginn' in this test home: attaching says how to set it up.
	got = render(t, sock, 100, 30, append(append([]string{":"}, keys("attach widgets")...), "enter")...)
	if !strings.Contains(got, "attaching needs SSH") {
		t.Fatalf("attach without an ssh host must say why:\n%s", got)
	}
}

func TestSplitArgs(t *testing.T) {
	got := splitArgs(`projects msg "osid lead" 'two words' plain`)
	want := []string{"projects", "msg", "osid lead", "two words", "plain"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("splitArgs = %q", got)
	}
}
