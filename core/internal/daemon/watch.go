package daemon

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net/url"
	"sort"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/notify"
	"github.com/silencelen/huginn/core/internal/store"
	"github.com/silencelen/huginn/core/internal/traystate"
)

// watcher follows appd's /v1/watch stream and turns each digest into what the
// tray role shows: a Summary for the icon and menu, and Notices to post or take
// down (docs/design/core.md §4, D14). The rules are internal/notify — a
// port of the desktop's, with its tests — so the phone, the old desktop and the
// core cannot disagree about what "needs you" or "finished" means.
type watcher struct {
	d        *Daemon
	trays    atomic.Int32 // tray roles attached to /core/events right now
	senders  atomic.Int32 // of those, the ones that can show a notification
	guis     atomic.Int32 // desktop apps attached (role=gui)
	keepTray atomic.Int32 // the app's "Close to tray": 1 on, 0 off (on until it says)
	mu       sync.Mutex
	baseline notify.Baseline // survives stream reconnects: a reconnect is not news
	focused  string          // the GUI's current target key, while it is fresh
	focusAt  time.Time
}

// focusTTL: a presence report older than this is ignored, so a GUI that died
// without saying so does not suppress notifications forever.
const focusTTL = 45 * time.Second

func (w *watcher) focus() string {
	w.mu.Lock()
	defer w.mu.Unlock()
	if time.Since(w.focusAt) > focusTTL {
		return ""
	}
	return w.focused
}

func (w *watcher) setFocus(key string) {
	w.mu.Lock()
	w.focused, w.focusAt = key, time.Now()
	w.mu.Unlock()
}

// claim is the X-Huginn-Notify answer (D14): this core will show notifications
// only while a tray role is attached AND the session is unlocked — somebody is
// at the machine to see them. Otherwise appd keeps its Telegram fallback.
func (w *watcher) claim() bool {
	if w.senders.Load() == 0 {
		return false
	}
	lk := w.d.lock.read(context.Background())
	return lk.Known && !lk.Locked
}

// headers rides every request the core makes to appd.
func (w *watcher) headers() map[string]string {
	h := map[string]string{"X-Huginn-Client": w.d.installID}
	if w.claim() {
		h["X-Huginn-Notify"] = "1"
	} else {
		h["X-Huginn-Notify"] = "0"
	}
	return h
}

// run streams until ctx ends, reconnecting with backoff. The claim is a header,
// so when it changes (a tray attaches, the screen locks) the stream is
// re-opened to carry the new answer.
func (w *watcher) run(ctx context.Context, api *appd.Client) {
	backoff := time.Second
	for ctx.Err() == nil {
		claimed := w.claim()
		sctx, cancel := context.WithCancel(ctx)
		go func() { // re-open when the claim flips
			t := time.NewTicker(15 * time.Second)
			defer t.Stop()
			for {
				select {
				case <-sctx.Done():
					return
				case <-t.C:
					if w.claim() != claimed {
						cancel()
						return
					}
				}
			}
		}()
		err := w.stream(sctx, api)
		cancel()
		if ctx.Err() != nil {
			return
		}
		w.publishSummary(traystate.Summary{State: traystate.Idle, Tooltip: "Huginn — not reaching huginn", Connected: false})
		if err != nil {
			w.d.logf("watch: %v (retrying in %s)", err, backoff)
		} else {
			backoff = time.Second
		}
		select {
		case <-ctx.Done():
			return
		case <-time.After(backoff):
		}
		if backoff < 30*time.Second {
			backoff *= 2
		}
	}
}

func (w *watcher) stream(ctx context.Context, api *appd.Client) error {
	res, err := api.Stream(ctx, "/v1/watch?stream=1")
	if err != nil {
		return err
	}
	defer res.Body.Close()
	sc := bufio.NewScanner(res.Body)
	sc.Buffer(make([]byte, 64*1024), 4<<20)
	event := ""
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "event: "):
			event = strings.TrimPrefix(line, "event: ")
		case strings.HasPrefix(line, "data: ") && event == "state":
			wd, err := notify.ParseWatch([]byte(strings.TrimPrefix(line, "data: ")))
			if err == nil {
				w.digest(wd)
			}
		case line == "":
			event = ""
		}
	}
	if err := sc.Err(); err != nil && ctx.Err() == nil {
		return err
	}
	return nil
}

func (w *watcher) digest(wd notify.Watch) {
	w.mu.Lock()
	decisions, next := notify.Plan(w.baseline, wd, w.focusLocked(), true)
	w.baseline = next
	w.mu.Unlock()
	w.publishSummary(summarize(wd))
	for _, dc := range decisions {
		// ⚠ SENT, NEVER RETAINED. A notice is news of the moment it was
		// decided. Retained, the newest one was replayed to every tray that
		// connected later (the next sign-in, a reconnect): a toast hours after
		// the fact, which appd had already sent to Telegram because no tray was
		// there to show it. What is still true rides the summary above, which
		// IS retained: the icon and menu name the sessions that need you.
		w.d.hub.Send(ipc.Event{Name: traystate.EventNotice, Data: render(dc)})
	}
}

func (w *watcher) focusLocked() string {
	if time.Since(w.focusAt) > focusTTL {
		return ""
	}
	return w.focused
}

func (w *watcher) publishSummary(s traystate.Summary) {
	w.d.hub.Publish(ipc.Event{Name: traystate.EventTray, Data: s})
}

// summarize is the tray's view of a digest (the desktop's TrayModel.summarize).
func summarize(wd notify.Watch) traystate.Summary {
	var attention []string
	working := 0
	for name, st := range wd.Sessions {
		switch st {
		case notify.StateAttention:
			attention = append(attention, name)
		case notify.StateRunning:
			working++
		}
	}
	for _, c := range wd.Chats {
		if c.Running {
			working++
		}
	}
	sort.Strings(attention)
	s := traystate.Summary{State: traystate.Idle, Tooltip: "Huginn — idle", Working: working, Connected: true}
	switch {
	case len(attention) > 0:
		s.State = traystate.Attention
		if len(attention) == 1 {
			s.Tooltip = "Huginn — " + attention[0] + " needs you"
		} else {
			s.Tooltip = fmt.Sprintf("Huginn — %d sessions need you", len(attention))
		}
	case working > 0:
		s.State = traystate.Working
		s.Tooltip = fmt.Sprintf("Huginn — %d working", working)
	}
	if len(attention) > traystate.MenuCap {
		attention = attention[:traystate.MenuCap]
	}
	s.Attention = attention
	return s
}

func openURL(kind notify.TargetKind, id string) string {
	return "huginn://open?view=" + url.QueryEscape(string(kind)) + "&id=" + url.QueryEscape(id)
}

func trunc(s string, n int) string {
	if r := []rune(s); len(r) > n {
		return string(r[:n])
	}
	return s
}

// render is a decision in the words the desktop's NotifyRouter uses.
func render(dc notify.Decision) traystate.Notice {
	switch dc.Kind {
	case notify.Withdraw:
		return traystate.Notice{Action: "withdraw", Key: dc.Key}
	case notify.Attention:
		return traystate.Notice{Action: "post", Key: notify.SessionKey(dc.Session), Urgent: true,
			Title: dc.Session + " needs you", Body: "Waiting for your answer", URL: openURL(notify.Sessions, dc.Session)}
	case notify.Finished:
		title, body := trunc(dc.Title, 60), trunc(dc.Snippet, 200)
		if title == "" {
			title = "Chat finished"
		}
		if body == "" {
			body = "huginn has answered"
		}
		return traystate.Notice{Action: "post", Key: notify.ChatKey(dc.ChatID), Title: title, Body: body, URL: openURL(notify.Chats, dc.ChatID)}
	case notify.LimitHit:
		body := "Resumes when the limit resets"
		if t, err := time.Parse(time.RFC3339, dc.ResetsAt); err == nil {
			body = "Resumes at " + t.Local().Format("15:04")
		}
		return traystate.Notice{Action: "post", Key: notify.LimitKey(dc.Session), Title: dc.Session + " hit the usage limit", Body: body, URL: openURL(notify.Sessions, dc.Session)}
	case notify.Resumed:
		return traystate.Notice{Action: "post", Key: "resumed", Title: "Usage limit reset", Body: trunc("Resumed: "+strings.Join(dc.Sessions, ", "), 190)}
	case notify.Downgraded:
		return traystate.Notice{Action: "post", Key: notify.SessionKey(dc.Session), Title: dc.Session + " moved to " + dc.To,
			Body: "Its Fable window ran out; it goes back when the window resets.", URL: openURL(notify.Sessions, dc.Session)}
	case notify.LadderUp:
		return traystate.Notice{Action: "post", Key: notify.SessionKey(dc.Session), Title: dc.Session + " is back on its own model", Body: "Its window reset.", URL: openURL(notify.Sessions, dc.Session)}
	}
	return traystate.Notice{}
}

// installID reads, or mints once, this core's X-Huginn-Client id.
func installID(st *store.Store) string {
	cfg, _, err := st.Load()
	if err == nil && cfg.InstallID != "" {
		return cfg.InstallID
	}
	b := make([]byte, 12)
	rand.Read(b)
	id := "core-" + hex.EncodeToString(b)
	st.Update(func(c *store.Config) error {
		if c.InstallID == "" {
			c.InstallID = id
		} else {
			id = c.InstallID
		}
		return nil
	})
	return id
}
