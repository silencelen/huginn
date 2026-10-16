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
		"device":{"status":{"enabled":true,"enrolled":true,"note":"Waiting for work","deviceId":"d1"},"settings":{"enabled":true,"name":"stigo","scope":"work"}}}`, paired)))
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
			{"name":"widgets","state":"attention","title":"Ship 0.8.0 to the Fold?","preview":["Do you want to proceed?","❯ 1. Yes"],"activityAt":%d,"liveModel":"Opus 5.5","attachedClients":1},
			{"name":"build","state":"running","title":"Release the desktop","preview":["Running gradle…"],"activityAt":%d,"liveModel":"Sonnet 5.5","contextPercent":42},
			{"name":"scratch","state":null,"title":"","preview":["$"],"activityAt":%d},
			{"name":"osid-lead","state":"idle","title":"lead","activityAt":%d,"project":{"name":"osid","role":"lead"}}]}`, now-3600, now-120, now-30, now-86400*3, now-60)))
		mux.HandleFunc("/appd/v1/devices", j(fmt.Sprintf(`{"devices":[
			{"id":"d1","name":"stigo","platform":"linux","scope":"work","online":true,"version":"2.3.0","lastSeenSec":%d},
			{"id":"d2","name":"PRESTIGE","platform":"windows","scope":"own","online":true,"running":true,"version":"2.3.0","lastSeenSec":%d},
			{"id":"d3","name":"brokkr","platform":"linux","scope":"work","online":false,"version":"1.8.0","lastSeenSec":%d}]}`, now, now, now-7200)))
		mux.HandleFunc("/appd/v1/headroom", j(`{"mode":"auto","accounts":{"a1":{"email":"owner@example.net","live":true,"windows":{
			"session":{"percent":3,"resetsAt":"2099-01-01T00:00:00Z","label":"Current session"},
			"weekly_all":{"percent":66,"resetsAt":"2099-01-01T00:00:00Z","label":"Current week, all models"}}}},
			"sentinels":{"STOP":null},"held":[],"arbiter":{"why":"under every threshold"}}`))
		mux.HandleFunc("/appd/v1/chats", j(`{"chats":[{},{},{}]}`))
		mux.HandleFunc("/appd/v1/rounds", j(`{"rounds":[{},{}]}`))
		mux.HandleFunc("/appd/v1/projects", j(`{"projects":[{}]}`))
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
		default:
			msg = tea.KeyMsg{Type: tea.KeyRunes, Runes: []rune(k)}
		}
		m.Update(msg)
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
		{"later-80x24", 80, 24, []string{"2"}},
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
