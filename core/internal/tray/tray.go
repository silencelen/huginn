//go:build (linux && !android) || windows

// Package tray is the tray role: `huginn core tray`, started at graphical
// login. It draws the icon and menu and posts OS notifications, all decided by
// `huginn core run` and read from its /core/events stream — the role holds no
// token, no appd connection and no rules of its own (docs/CORE-ARCHITECTURE.md
// §3.1). Why a separate process: a boot-time Windows task runs in session 0,
// which has no tray and cannot show a toast; and a crash in UI code must not
// take the device runner down.
package tray

import (
	"bufio"
	"bytes"
	"context"
	_ "embed"
	"encoding/binary"
	"encoding/json"
	"fmt"
	"log"
	"net/http"
	"net/url"
	"os"
	"runtime"
	"strings"
	"sync"
	"time"

	"fyne.io/systray"

	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/proc"
	"github.com/silencelen/huginn/core/internal/traystate"
	"github.com/silencelen/huginn/core/internal/version"
)

//go:embed icons/idle.png
var iconIdle []byte

//go:embed icons/working.png
var iconWorking []byte

//go:embed icons/attention.png
var iconAttention []byte

// Options for Run.
type Options struct {
	Socket string
	Lock   string // one tray per user: a second `core tray` waits briefly, then leaves
	Logger *log.Logger
}

type role struct {
	o        Options
	notifier Notifier
	lock     *os.File

	mu        sync.Mutex
	open      *systray.MenuItem
	rows      []*systray.MenuItem
	rowTarget []string
	working   *systray.MenuItem
	last      string // icon state shown, to skip redundant SetIcon calls
}

// Run shows the tray until the person quits it from the menu. It never returns
// early for a core that is down: it says so and keeps reconnecting.
func Run(o Options) {
	if o.Logger == nil {
		o.Logger = log.Default()
	}
	r := &role{o: o}
	if o.Lock != "" {
		// Wait out an outgoing tray: an update starts the new one as the old one
		// quits, and the desktop or an updater may start one at the same moment.
		deadline := time.Now().Add(15 * time.Second)
		for {
			f, ok := tryLock(o.Lock)
			if ok {
				r.lock = f
				break
			}
			if time.Now().After(deadline) {
				o.Logger.Printf("tray: another huginn tray is running for this user — leaving it be")
				return
			}
			time.Sleep(500 * time.Millisecond)
		}
	}
	r.notifier = newNotifier(o.Logger)
	systray.Run(r.ready, func() {})
}

func (r *role) ready() {
	systray.SetTitle("huginn")
	r.setIcon(traystate.Idle)
	systray.SetTooltip("huginn — connecting to huginn core…")
	r.open = systray.AddMenuItem("Open Huginn", "Open the huginn window")
	systray.AddSeparator()
	for i := 0; i < traystate.MenuCap; i++ {
		it := systray.AddMenuItem("", "")
		it.Hide()
		r.rows = append(r.rows, it)
	}
	r.rowTarget = make([]string, traystate.MenuCap)
	r.working = systray.AddMenuItem("", "")
	r.working.Disable()
	r.working.Hide()
	systray.AddSeparator()
	quit := systray.AddMenuItem("Quit tray", "Hide this icon (huginn core keeps running)")

	go func() {
		for range r.open.ClickedCh {
			OpenURL("huginn://open")
		}
	}()
	for i, it := range r.rows {
		go func(i int, it *systray.MenuItem) {
			for range it.ClickedCh {
				r.mu.Lock()
				target := r.rowTarget[i]
				r.mu.Unlock()
				if target != "" {
					OpenURL("huginn://open?view=sessions&id=" + url.QueryEscape(target))
				}
			}
		}(i, it)
	}
	go func() {
		<-quit.ClickedCh
		systray.Quit()
	}()
	go r.follow()
}

func (r *role) setIcon(state string) {
	r.mu.Lock()
	defer r.mu.Unlock()
	if r.last == state {
		return
	}
	r.last = state
	b := iconIdle
	switch state {
	case traystate.Working:
		b = iconWorking
	case traystate.Attention:
		b = iconAttention
	}
	if runtime.GOOS == "windows" {
		b = icoFromPNG(b)
	}
	systray.SetIcon(b)
}

// follow reads /core/events for the life of the tray, reconnecting with a short
// backoff when the core is down or restarts (an update restarts it).
func (r *role) follow() {
	c := ipc.Client(r.o.Socket)
	backoff := time.Second
	for {
		err := r.stream(c)
		r.show(traystate.Summary{State: traystate.Idle, Tooltip: "huginn — huginn core is not running"})
		if err != nil {
			r.o.Logger.Printf("tray: core events: %v (retrying in %s)", err, backoff)
		}
		time.Sleep(backoff)
		if backoff < 15*time.Second {
			backoff *= 2
		}
	}
}

func (r *role) stream(c *http.Client) error {
	resp, err := ipc.Do(context.Background(), c, http.MethodGet, "/core/events?role=tray", nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	if resp.StatusCode != http.StatusOK {
		return fmt.Errorf("core answered %d", resp.StatusCode)
	}
	if r.handoff(c) {
		return nil
	}
	sc := bufio.NewScanner(resp.Body)
	sc.Buffer(make([]byte, 64*1024), 1<<20)
	event := ""
	for sc.Scan() {
		line := sc.Text()
		switch {
		case strings.HasPrefix(line, "event: "):
			event = strings.TrimPrefix(line, "event: ")
		case strings.HasPrefix(line, "data: "):
			r.handle(event, []byte(strings.TrimPrefix(line, "data: ")))
		case line == "":
			event = ""
		}
	}
	return sc.Err()
}

// handoff: the core restarted onto a newer binary than this tray's (an
// update). Start the tray from the stable path and quit, so the icon and the
// notifier follow the core's version. Only when the stable binary IS the
// core's version: a mismatch the swap cannot fix (a hand-run older core) must
// not loop. (Asked of the binary itself, not compared by file: Windows renames
// a running .exe aside, and this process's own path then names the new file.)
func (r *role) handoff(c *http.Client) bool {
	ctx, cancel := context.WithTimeout(context.Background(), 5*time.Second)
	defer cancel()
	resp, err := ipc.Do(ctx, c, http.MethodGet, "/core/status", nil)
	if err != nil {
		return false
	}
	var st struct {
		Version string `json:"version"`
	}
	err = json.NewDecoder(resp.Body).Decode(&st)
	resp.Body.Close()
	if err != nil || st.Version == "" || st.Version == version.Version {
		return false
	}
	l, err := install.DefaultLayout()
	if err != nil {
		return false
	}
	stable := l.Binary()
	out, err := proc.Output(stable, "version")
	if err != nil || !strings.Contains(out, " "+st.Version+" ") {
		return false
	}
	r.o.Logger.Printf("tray: huginn core is %s, this tray %s — handing over to %s", st.Version, version.Version, stable)
	if r.lock != nil {
		r.lock.Close()
		r.lock = nil
	}
	if err := proc.StartDetached(stable, "core", "tray"); err != nil {
		r.o.Logger.Printf("tray: starting the new tray: %v (staying)", err)
		if r.o.Lock != "" {
			r.lock, _ = tryLock(r.o.Lock)
		}
		return false
	}
	systray.Quit()
	return true
}

func (r *role) handle(event string, data []byte) {
	switch event {
	case traystate.EventTray:
		var s traystate.Summary
		if json.Unmarshal(data, &s) == nil {
			r.show(s)
		}
	case traystate.EventNotice:
		var n traystate.Notice
		if json.Unmarshal(data, &n) != nil {
			return
		}
		var err error
		if n.Action == "withdraw" {
			err = r.notifier.Withdraw(n.Key)
		} else {
			err = r.notifier.Post(n)
		}
		if err != nil {
			r.o.Logger.Printf("tray: notification %s %s: %v", n.Action, n.Key, err)
		}
	}
}

func (r *role) show(s traystate.Summary) {
	r.setIcon(s.State)
	tip := s.Tooltip
	if tip == "" {
		tip = "huginn"
	}
	systray.SetTooltip(tip)
	r.mu.Lock()
	defer r.mu.Unlock()
	for i, it := range r.rows {
		if i < len(s.Attention) && i < traystate.MenuCap {
			r.rowTarget[i] = s.Attention[i]
			it.SetTitle(s.Attention[i] + " needs you")
			it.Show()
		} else {
			r.rowTarget[i] = ""
			it.Hide()
		}
	}
	if s.Working > 0 {
		r.working.SetTitle(fmt.Sprintf("%d working", s.Working))
		r.working.Show()
	} else {
		r.working.Hide()
	}
}

// icoFromPNG wraps a 64x64 PNG in a one-image ICO container: Windows' tray
// wants ICO bytes, and Vista+ reads a PNG payload inside one.
func icoFromPNG(png []byte) []byte {
	var b bytes.Buffer
	binary.Write(&b, binary.LittleEndian, []uint16{0, 1, 1}) // reserved, type=icon, count=1
	b.Write([]byte{64, 64, 0, 0})                            // width, height, palette, reserved
	binary.Write(&b, binary.LittleEndian, []uint16{1, 32})   // planes, bits per pixel
	binary.Write(&b, binary.LittleEndian, []uint32{uint32(len(png)), 22})
	b.Write(png)
	return b.Bytes()
}

// NotifyTest posts one sample notice through this platform's real notifier,
// waits, then withdraws it — `huginn core notify-test`, for proving delivery on
// a machine without sending anything through appd (which would also reach the
// phone). Returns the first error.
func NotifyTest(hold time.Duration) error {
	n := newNotifier(log.Default())
	err := n.Post(traystate.Notice{Action: "post", Key: "test:notify", Urgent: true,
		Title: "huginn notification test", Body: "If you can read this, huginn core can reach you here.", URL: "huginn://open"})
	if err != nil {
		return err
	}
	time.Sleep(hold)
	return n.Withdraw("test:notify")
}
