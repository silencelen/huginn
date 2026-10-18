package console

import (
	"bufio"
	"bytes"
	"context"
	"encoding/json"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"strings"
	"time"
)

// backend reaches everything through the core's socket: /core/* for the core
// itself, /appd/* for huginn (the core adds its token and proves the route;
// the token never enters this process). Only attaching uses ssh.
type backend struct {
	hc     *http.Client // requests, with a timeout
	stream *http.Client // event streams, without one
	proto  string
}

const base = "http://huginn-core"

func newBackend(sock, proto string) *backend {
	dial := func(ctx context.Context, _, _ string) (net.Conn, error) {
		var d net.Dialer
		return d.DialContext(ctx, "unix", sock)
	}
	return &backend{
		hc:     &http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{DialContext: dial}},
		stream: &http.Client{Transport: &http.Transport{DialContext: dial}},
		proto:  proto,
	}
}

// apiError is the server's own sentence, never a bare status.
type apiError struct {
	Status int
	Msg    string
}

func (e *apiError) Error() string { return e.Msg }

func (b *backend) do(ctx context.Context, method, path string, body, out any) error {
	var rd io.Reader
	if body != nil {
		j, err := json.Marshal(body)
		if err != nil {
			return err
		}
		rd = bytes.NewReader(j)
	}
	req, err := http.NewRequestWithContext(ctx, method, base+path, rd)
	if err != nil {
		return err
	}
	req.Header.Set("X-Huginn-Core-Proto", b.proto)
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	res, err := b.hc.Do(req)
	if err != nil {
		return err
	}
	defer res.Body.Close()
	raw, _ := io.ReadAll(io.LimitReader(res.Body, 16<<20))
	if res.StatusCode >= 400 {
		var e struct {
			Error string `json:"error"`
		}
		json.Unmarshal(raw, &e)
		if e.Error == "" {
			e.Error = strings.TrimSpace(string(raw))
		}
		if e.Error == "" {
			e.Error = http.StatusText(res.StatusCode)
		}
		return &apiError{Status: res.StatusCode, Msg: clean(e.Error)}
	}
	if out != nil && len(raw) > 0 {
		err := json.Unmarshal(raw, out)
		scrub(out) // every string, before any screen shows it (F25); a partial decode is shown too
		return err
	}
	return nil
}

func (b *backend) get(ctx context.Context, path string, out any) error {
	return b.do(ctx, http.MethodGet, path, nil, out)
}

// ---- shapes (appd 3.12; see docs/reference/api.md § API) --------------------------

type coreStatus struct {
	Version string `json:"version"`
	Paired  bool   `json:"paired"`
	URL     string `json:"url"`
	Tray    bool   `json:"tray"`
	Device  struct {
		Status struct {
			Enabled  bool   `json:"enabled"`
			Enrolled bool   `json:"enrolled"`
			Busy     bool   `json:"busy"`
			Locked   bool   `json:"locked"`
			Note     string `json:"note"`
			DeviceID string `json:"deviceId"`
		} `json:"status"`
		Settings struct {
			Enabled        *bool   `json:"enabled"`
			Name           *string `json:"name"`
			Scope          *string `json:"scope"`
			Root           *string `json:"root"`
			ActWhileLocked *bool   `json:"actWhileLocked"`
		} `json:"settings"`
	} `json:"device"`
}

type appdStatus struct {
	Host        string    `json:"host"`
	AppdVersion string    `json:"appdVersion"`
	UptimeSec   int64     `json:"uptimeSec"`
	Load        []float64 `json:"load"`
	Claude      string    `json:"claude"`
	Sessions    int       `json:"sessions"`
	ChatsRun    int       `json:"chatsRunning"`
	Disk        struct {
		Free        string `json:"free"`
		UsedPercent string `json:"usedPercent"`
	} `json:"disk"`
	Headroom struct {
		WorstPercent int    `json:"worstPercent"`
		WorstLabel   string `json:"worstLabel"`
		NextResetAt  string `json:"nextResetAt"`
		Mode         string `json:"mode"`
	} `json:"headroom"`
}

type session struct {
	Name            string   `json:"name"`
	State           *string  `json:"state"`
	Title           string   `json:"title"`
	Preview         []string `json:"preview"`
	ActivityAt      int64    `json:"activityAt"`
	CreatedAt       int64    `json:"createdAt"`
	AttachedClients int      `json:"attachedClients"`
	LiveModel       string   `json:"liveModel"`
	ContextPercent  int      `json:"contextPercent"`
	PendingSends    int      `json:"pendingSends"`
	SoftEnding      bool     `json:"softEnding"`
	Project         *struct {
		Name string `json:"name"`
		Role string `json:"role"`
	} `json:"project"`
}

func (s session) state() state {
	if s.State == nil {
		return stShell
	}
	return sessionState(*s.State)
}

type device struct {
	ID          string `json:"id"`
	Name        string `json:"name"`
	Platform    string `json:"platform"`
	Scope       string `json:"scope"`
	Online      bool   `json:"online"`
	Locked      bool   `json:"locked"`
	Version     string `json:"version"`
	LastSeenSec int64  `json:"lastSeenSec"`
	Running     bool   `json:"running"`
	Queued      int    `json:"queued"`
}

type window struct {
	Percent  int    `json:"percent"`
	ResetsAt string `json:"resetsAt"`
	Severity string `json:"severity"`
	Label    string `json:"label"`
}

type headroom struct {
	Mode     string `json:"mode"`
	Accounts map[string]struct {
		Email   string            `json:"email"`
		Live    bool              `json:"live"`
		Windows map[string]window `json:"windows"`
	} `json:"accounts"`
	Sentinels map[string]*struct {
		Reason string `json:"reason"`
	} `json:"sentinels"`
	Held    []json.RawMessage `json:"held"`
	Arbiter struct {
		Why string `json:"why"`
	} `json:"arbiter"`
}

type counts struct{ chats, rounds, projects int }

// ---- calls -------------------------------------------------------------------

func (b *backend) coreStatus(ctx context.Context) (coreStatus, error) {
	var s coreStatus
	return s, b.get(ctx, "/core/status", &s)
}

func (b *backend) appdStatus(ctx context.Context) (appdStatus, error) {
	var s appdStatus
	return s, b.get(ctx, "/appd/v1/status", &s)
}

func (b *backend) sessions(ctx context.Context) ([]session, error) {
	var r struct {
		Sessions []session `json:"sessions"`
	}
	return r.Sessions, b.get(ctx, "/appd/v1/sessions?preview=1", &r)
}

func (b *backend) devices(ctx context.Context) ([]device, error) {
	var r struct {
		Devices []device `json:"devices"`
	}
	return r.Devices, b.get(ctx, "/appd/v1/devices", &r)
}

func (b *backend) headroom(ctx context.Context) (headroom, error) {
	var h headroom
	return h, b.get(ctx, "/appd/v1/headroom", &h)
}

func (b *backend) counts(ctx context.Context) counts {
	var c counts
	var ch struct {
		Chats []json.RawMessage `json:"chats"`
	}
	if b.get(ctx, "/appd/v1/chats", &ch) == nil {
		c.chats = len(ch.Chats)
	}
	var ro struct {
		Rounds []json.RawMessage `json:"rounds"`
	}
	if b.get(ctx, "/appd/v1/rounds", &ro) == nil {
		c.rounds = len(ro.Rounds)
	}
	var pr struct {
		Projects []json.RawMessage `json:"projects"`
	}
	if b.get(ctx, "/appd/v1/projects", &pr) == nil {
		c.projects = len(pr.Projects)
	}
	return c
}

func esc(name string) string { return url.PathEscape(name) }

func (b *backend) newSession(ctx context.Context, name string) error {
	return b.do(ctx, http.MethodPost, "/appd/v1/sessions", map[string]string{"name": name}, nil)
}

func (b *backend) killSession(ctx context.Context, name string) error {
	return b.do(ctx, http.MethodDelete, "/appd/v1/sessions/"+esc(name), nil, nil)
}

func (b *backend) renameSession(ctx context.Context, from, to string) error {
	return b.do(ctx, http.MethodPost, "/appd/v1/sessions/"+esc(from)+"/rename", map[string]string{"name": to}, nil)
}

// sendLine types text into the session and presses Enter (a SEND, not a draft).
func (b *backend) sendLine(ctx context.Context, name, text string) (string, error) {
	var r struct {
		Queued    bool    `json:"queued"`
		Delivered bool    `json:"delivered"`
		BlockedBy *string `json:"blockedBy"`
		Duplicate bool    `json:"duplicate"`
	}
	err := b.do(ctx, http.MethodPost, "/appd/v1/sessions/"+esc(name)+"/keys", map[string]any{"text": text, "keys": []string{"Enter"}}, &r)
	switch {
	case err != nil:
		return "", err
	case r.Duplicate:
		return "already sent", nil
	case r.Delivered:
		return "sent", nil
	case r.BlockedBy != nil:
		return "queued: the session is busy (" + *r.BlockedBy + ")", nil
	}
	return "queued", nil
}

func (b *backend) softEnd(ctx context.Context, name string) error {
	return b.do(ctx, http.MethodPost, "/appd/v1/sessions/"+esc(name)+"/soft-end", map[string]any{"auto": true}, nil)
}

func (b *backend) setDevice(ctx context.Context, change map[string]any) error {
	return b.do(ctx, http.MethodPatch, "/core/device", change, nil)
}

// watch follows appd's change signal through the core and calls changed for
// each new hash, until ctx ends or the stream drops (the caller reconnects).
func (b *backend) watch(ctx context.Context, changed func()) error {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, base+"/appd/v1/watch?stream=1", nil)
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
	if res.StatusCode >= 400 {
		return fmt.Errorf("watch: %s", res.Status)
	}
	sc := bufio.NewScanner(res.Body)
	sc.Buffer(make([]byte, 1<<20), 8<<20)
	last := ""
	for sc.Scan() {
		line := sc.Text()
		if !strings.HasPrefix(line, "data:") {
			continue
		}
		var d struct {
			Hash string `json:"hash"`
		}
		if json.Unmarshal([]byte(strings.TrimSpace(line[5:])), &d) == nil && d.Hash != "" && d.Hash != last {
			last = d.Hash
			changed()
		}
	}
	return sc.Err()
}
