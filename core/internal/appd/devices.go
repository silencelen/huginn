package appd

import (
	"context"
	"encoding/json"
	"fmt"
	"net/url"
	"time"
)

// The device API, with JSON field names exactly as client/huginn-device sends
// and reads them (appd lib/devices.js validates the other side).

// PollTimeout is the client timeout on the long poll: the daemon holds it for
// up to pollWaitSeconds, so the client must outwait that by a margin.
const PollTimeout = 40 * time.Second

// pollWaitSeconds is the `wait` the poll asks for. A var only so the end-to-end
// test does not sit through 25 s per poll.
var pollWaitSeconds = 25

// Model is one entry of a serving machine's display-only catalog.
type Model struct {
	Slug    string `json:"slug"`
	Display string `json:"display,omitempty"`
}

// Enrolment is POST /v1/devices.
type Enrolment struct {
	ID       string  `json:"id,omitempty"`
	Name     string  `json:"name"`
	Platform string  `json:"platform"` // windows | macos | linux | other
	Scope    string  `json:"scope"`
	Root     *string `json:"root"` // null, never omitted, when unset
	Version  string  `json:"version"`
	Locked   bool    `json:"locked"`
	Machine  string  `json:"machine,omitempty"`
	// Models: display-only, never routing authority. nil is omitted; an empty
	// non-nil slice is sent as [] (Node sends any array it has).
	Models []Model `json:"models,omitzero"`
	// Persistent: whether the machine serves with NOBODY logged in. ABSENT when
	// unknown and absent must stay absent all the way to the daemon: `false` is
	// a claim about a box, and defaulting it would label every pre-facet
	// machine wrongly.
	Persistent *bool `json:"persistent,omitempty"`
	// ActWhileLocked: ⚠ ALWAYS A REAL BOOLEAN, unlike Persistent. Absence would
	// leave the daemon pre-checking against a stale `true` after somebody turned
	// the setting OFF; a narrowing that does not travel is not a narrowing.
	ActWhileLocked bool `json:"actWhileLocked"`
}

// Device is the daemon's view of a row (enrol reply, GET /v1/devices/:id).
// Unknown fields are ignored; Raw keeps the whole answer for callers that
// need more.
type Device struct {
	ID             string          `json:"id"`
	Name           string          `json:"name"`
	Platform       string          `json:"platform"`
	Scope          string          `json:"scope"`
	EffectiveScope string          `json:"effectiveScope"`
	Locked         bool            `json:"locked"`
	Root           *string         `json:"root"`
	Version        *string         `json:"version"`
	Online         bool            `json:"online"`
	Machine        string          `json:"machine,omitempty"`
	LlmSlug        string          `json:"llmSlug,omitempty"`
	Models         []Model         `json:"models,omitempty"`
	Persistent     *bool           `json:"persistent,omitempty"`
	ActWhileLocked *bool           `json:"actWhileLocked,omitempty"`
	Running        bool            `json:"running,omitempty"`
	Queued         int             `json:"queued,omitempty"`
	Raw            json.RawMessage `json:"-"`
}

// Beat is POST /v1/devices/:id/beat.
type Beat struct {
	Locked         bool   `json:"locked"`
	Scope          string `json:"scope"`
	Version        string `json:"version"`
	Persistent     *bool  `json:"persistent,omitempty"` // same tri-state as Enrolment
	ActWhileLocked bool   `json:"actWhileLocked"`       // always sent; see Enrolment
}

// BeatReply: cancel rides the beat so a Stop reaches a run inside a quiet
// tool call that is not posting event batches.
type BeatReply struct {
	OK             bool   `json:"ok"`
	EffectiveScope string `json:"effectiveScope"`
	Cancel         bool   `json:"cancel"`
}

// WorkItem is one job (appd lib/devices.js workItem).
type WorkItem struct {
	ID                string          `json:"id"`
	ChatID            string          `json:"chatId"`
	Prompt            string          `json:"prompt"`
	Mode              string          `json:"mode"` // ask | act | generate
	Model             *string         `json:"model"`
	Effort            *string         `json:"effort"`
	ResumeSessionID   *string         `json:"resumeSessionId"`
	RoundID           *string         `json:"roundId"`
	ResumedAfterLimit bool            `json:"resumedAfterLimit"`
	IssuedAt          int64           `json:"issuedAt"`
	Raw               json.RawMessage `json:"-"`
}

// Events is POST /v1/devices/:id/work/:wid/events. A mid-run batch sends Lines
// and Locked only; the terminal frame sends Lines: [] with Done and travels
// ALONE (an oversized tail must never take the ending down with it).
type Events struct {
	Lines    []string `json:"lines"` // never null: an empty batch is []
	Done     bool     `json:"done,omitempty"`
	ExitCode *int     `json:"exitCode,omitempty"` // absent == null at the daemon
	Error    string   `json:"error,omitempty"`
	Locked   bool     `json:"locked"`
}

// EventsReply: Duplicate means the daemon had already applied this exact
// batch (a replay after a lost response); Cancel is the stop button.
type EventsReply struct {
	OK        bool `json:"ok"`
	Duplicate bool `json:"duplicate"`
	Cancel    bool `json:"cancel"`
	Done      bool `json:"done"`
}

func (c *Client) doJSON(ctx context.Context, method, path string, body, out any) error {
	r, err := c.Do(ctx, method, path, body)
	if err != nil {
		return err
	}
	if out == nil || len(r.Body) == 0 {
		return nil
	}
	if err := json.Unmarshal(r.Body, out); err != nil {
		return fmt.Errorf("%s %s: the daemon answered something that is not JSON: %w", method, path, err)
	}
	return nil
}

func devPath(id string, rest string) string { return "/v1/devices/" + url.PathEscape(id) + rest }

// Enrol registers (or re-registers under e.ID) this machine.
func (c *Client) Enrol(ctx context.Context, e Enrolment) (*Device, error) {
	var raw json.RawMessage
	if err := c.doJSON(ctx, "POST", "/v1/devices", e, &raw); err != nil {
		return nil, err
	}
	return decodeDevice("POST /v1/devices", raw)
}

// Get is GET /v1/devices/:id.
func (c *Client) Get(ctx context.Context, id string) (*Device, error) {
	var raw json.RawMessage
	if err := c.doJSON(ctx, "GET", devPath(id, ""), nil, &raw); err != nil {
		return nil, err
	}
	return decodeDevice("GET /v1/devices/:id", raw)
}

// Delete is DELETE /v1/devices/:id (unenrol).
func (c *Client) Delete(ctx context.Context, id string) error {
	return c.doJSON(ctx, "DELETE", devPath(id, ""), nil, nil)
}

// Beat is the 60-second liveness report.
func (c *Client) Beat(ctx context.Context, id string, b Beat) (*BeatReply, error) {
	var r BeatReply
	if err := c.doJSON(ctx, "POST", devPath(id, "/beat"), b, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

// PollWork is the long poll. nil, nil means "no work this time". It always
// runs under PollTimeout (tighter if ctx says so), never the default 20 s.
func (c *Client) PollWork(ctx context.Context, id string, locked bool) (*WorkItem, error) {
	ctx, cancel := context.WithTimeout(ctx, PollTimeout)
	defer cancel()
	l := 0
	if locked {
		l = 1
	}
	var r struct {
		Work json.RawMessage `json:"work"`
	}
	if err := c.doJSON(ctx, "GET", devPath(id, fmt.Sprintf("/work?wait=%d&locked=%d", pollWaitSeconds, l)), nil, &r); err != nil {
		return nil, err
	}
	if len(r.Work) == 0 || string(r.Work) == "null" {
		return nil, nil
	}
	var w WorkItem
	if err := json.Unmarshal(r.Work, &w); err != nil {
		return nil, fmt.Errorf("GET /v1/devices/:id/work: unreadable work item: %w", err)
	}
	w.Raw = r.Work
	return &w, nil
}

// PostEvents delivers one batch (or the terminal frame) for a run.
func (c *Client) PostEvents(ctx context.Context, id, workID string, ev Events) (*EventsReply, error) {
	if ev.Lines == nil {
		ev.Lines = []string{}
	}
	var r EventsReply
	if err := c.doJSON(ctx, "POST", devPath(id, "/work/"+url.PathEscape(workID)+"/events"), ev, &r); err != nil {
		return nil, err
	}
	return &r, nil
}

func decodeDevice(what string, raw json.RawMessage) (*Device, error) {
	var d Device
	if len(raw) > 0 {
		if err := json.Unmarshal(raw, &d); err != nil {
			return nil, fmt.Errorf("%s: unreadable device: %w", what, err)
		}
	}
	d.Raw = raw
	return &d, nil
}
