// Package notify decides what to notify about, from appd's /v1/watch digest.
//
// A pure port of the desktop's NotifyRules.kt and :core's WatchCycle.kt: no
// OS, no network, no clock. The core feeds it each `event: state` frame of
// `/v1/watch?stream=1` and hands the decisions to whatever posts toasts.
package notify

import "encoding/json"

// The daemon's words for a session's state (Fleet.kt / appd readSessionState).
const (
	// StateAttention is "this session is waiting on a human".
	StateAttention = "attention"
	StateRunning   = "running"
)

// Watch is the part of appd's watch digest the rules read. Field names match
// server/appd/lib/watch.js digest() exactly; everything else on the frame
// (changed, serverTime, pushesSent, pushEpoch, …) is ignored.
type Watch struct {
	Hash string `json:"hash"`
	// Sessions is tmux name → state. appd sends null for a session with no
	// state file; that decodes to "" here, and is not "attention" either way.
	// A name being PRESENT matters on its own (resume / ladder-up need it).
	Sessions map[string]string `json:"sessions"`
	Chats    map[string]Chat   `json:"chats"`
	// Headroom is nil on a daemon older than 3.0.0, which leaves every
	// headroom baseline empty and no headroom decision is ever reached.
	Headroom *Headroom `json:"headroom"`
}

// Chat is one chat's entry in the digest.
type Chat struct {
	Running bool `json:"running"`
	// FinishedRuns counts completed runs rather than flagging them. `running`
	// going false is an edge, and anything looking on a schedule can miss one;
	// a counter that is higher than last time cannot be missed that way.
	FinishedRuns int64 `json:"finishedRuns"`
	// Title and Snippet are payload, not part of the hash; null decodes to "".
	Title   string `json:"title"`
	Snippet string `json:"snippet"`
}

// Headroom is the headroom half of the digest: only facts a notification acts on.
type Headroom struct {
	Mode string `json:"mode"`
	// Stalled names the sessions currently stalled on a limit.
	Stalled []string `json:"stalled"`
	// Stalls maps the same sessions to the ISO instant their window resets;
	// null = stalled, reset time unknown. A name missing here is still stalled.
	Stalls map[string]*string `json:"stalls"`
	// Laddered is session → the model family the ladder moved it onto.
	Laddered map[string]string `json:"laddered"`
}

// ParseWatch decodes one digest (a /v1/watch body or one SSE `state` frame's data).
func ParseWatch(data []byte) (Watch, error) {
	var w Watch
	err := json.Unmarshal(data, &w)
	return w, err
}
