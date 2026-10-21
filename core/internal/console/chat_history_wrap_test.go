package console

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"strings"
	"testing"

	"github.com/charmbracelet/lipgloss"
	"github.com/muesli/termenv"
)

// A chat's frame is redrawn for every streamed token, and the whole history
// used to be laid out again each time: the work of a frame grew with the
// length of the conversation, not with what changed. These count the layout
// calls (wrapText) a stream of tokens costs, and hold the frames to what a
// full layout draws.

func longChat(t *testing.T, n int) *model {
	t.Helper()
	os.Setenv("HUGINN_ASCII", "1")
	lipgloss.SetColorProfile(termenv.Ascii)
	m := newModel(context.Background(), Options{Socket: "/nonexistent", Proto: "1", Home: t.TempDir()})
	m.w, m.h = 100, 30
	m.loaded = true
	m.data.core.Paired = true
	m.scr = scrChat
	m.chat = chatState{id: "c1"}
	m.chat.d.ID, m.chat.d.Title, m.chat.d.Mode = "c1", "Long chat", "ask"
	for i := 0; i < n; i++ {
		if i%2 == 0 {
			m.chat.d.Messages = append(m.chat.d.Messages, chatMsg{Type: "user", Text: fmt.Sprintf("question %d", i)})
		} else {
			m.chat.d.Messages = append(m.chat.d.Messages, chatMsg{Type: "assistant", Text: strings.Repeat(fmt.Sprintf("answer %d goes on. ", i), 12)})
		}
	}
	m.chat.busy, m.chat.gen = true, 1
	return m
}

func countWraps(t *testing.T) *int {
	t.Helper()
	n := 0
	orig := wrapText
	wrapText = func(w int, s string) string { n++; return orig(w, s) }
	t.Cleanup(func() { wrapText = orig })
	return &n
}

func delta(text string) chatEvent {
	b, _ := json.Marshal(map[string]string{"text": text})
	return chatEvent{id: "c1", gen: 1, event: "delta", data: b}
}

func TestAStreamedTokenDoesNotLayOutTheHistoryAgain(t *testing.T) {
	const history, tokens = 400, 25
	m := longChat(t, history)
	wraps := countWraps(t)
	m.View()
	first := *wraps
	if first < history/2 {
		t.Fatalf("the first frame laid out %d texts; the history alone has %d", first, history/2)
	}
	*wraps = 0
	for i := 0; i < tokens; i++ {
		m.onChatEvent(delta(fmt.Sprintf("tok%d ", i)))
		m.View()
	}
	if *wraps > tokens {
		t.Fatalf("%d streamed tokens over a %d-message history laid out %d texts; want at most one each (the answer)", tokens, history, *wraps)
	}
}

// The frames are the frames a full layout draws, through a resize, a send and
// a reload.
func TestAChatFrameMatchesAFullLayout(t *testing.T) {
	m := longChat(t, 40)
	fresh := func() string {
		m.chat.hist = nil
		return m.View()
	}
	check := func(when string) {
		t.Helper()
		got := m.View()
		if want := fresh(); got != want {
			t.Fatalf("%s: the frame differs from a full layout\n--- got\n%s\n--- want\n%s", when, got, want)
		}
	}
	m.View()
	m.onChatEvent(delta("streaming "))
	check("while streaming")
	m.w = 72
	check("after a resize")
	m.chat.scroll = 15
	check("scrolled back")
	m.chat.scroll = 0
	m.chat.d.Messages = append(m.chat.d.Messages, chatMsg{Type: "assistant", Text: "a reload brought one more"})
	m.Update(chatLoaded{d: m.chat.d})
	check("after a reload")
	m.chat.busy = false
	m.send("and one more question")
	m.stopFollow()
	check("after a send")
}
