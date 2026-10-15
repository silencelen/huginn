package notify

import "sort"

// Baseline is what has already been seen. Compared against the next digest, it
// is the only thing that turns a snapshot ("these two sessions are waiting")
// into an event ("this one just started waiting"). The zero value is unseeded.
type Baseline struct {
	// Seeded is false until the very first digest has been absorbed. Without
	// it, starting up announces the PAST: every session already waiting and
	// every chat that has ever finished arrive as a wave of alerts for things
	// the reader has usually already dealt with. (The phone learned this as
	// `watchSeeded`; the Electron client learned it again as `seeded`.)
	Seeded   bool
	Sessions map[string]string
	Runs     map[string]int64
	Running  map[string]bool
	// Stalled is the sessions that were sitting on a usage limit last time.
	Stalled map[string]bool
	// Laddered is the sessions the ladder had moved, name → family they were on.
	Laddered map[string]string
}

// Kind says which decision a Decision is.
type Kind string

const (
	// Attention: a session just started waiting on a question. Blocking —
	// sound, and it stays up. Uses Session.
	Attention Kind = "attention"
	// Finished: a chat finished a run. News: silent. Uses ChatID, Title, Snippet.
	Finished Kind = "finished"
	// Withdraw: the world moved on — take the notification filed under Key
	// down before the reader acts on it.
	Withdraw Kind = "withdraw"
	// LimitHit: a session just stopped on a usage limit. The most valuable
	// notice of all, because nothing else reports it: a stalled session looks
	// exactly like an idle one, and work sat untouched until morning after a
	// cap that cleared at half past midnight. Uses Session and ResetsAt — the
	// daemon's ISO instant ("" = unknown), never a clock this side formatted.
	LimitHit Kind = "limit_hit"
	// Resumed: one or more sessions picked themselves back up. One notice,
	// not N. Uses Sessions (sorted).
	Resumed Kind = "resumed"
	// Downgraded: a live session was moved down the ladder onto To. Session
	// is the undo TARGET (POST /v1/sessions/:name/headroom/undo), so the
	// toast's button needs no second lookup — why this is a name, not a count.
	Downgraded Kind = "downgraded"
	// LadderUp: a session was put back on the model it started on, its window
	// having reset. Uses Session.
	LadderUp Kind = "ladder_up"
)

// Decision is one thing to do about a digest. Only the fields Kind documents
// are set; the rest are zero.
type Decision struct {
	Kind     Kind
	Session  string
	ChatID   string
	Title    string
	Snippet  string
	Key      string
	ResetsAt string
	Sessions []string
	To       string
}

// Plan is the notification router's decisions, with no OS, no network and no
// clock. focused is the NavTarget key of what the reader is looking at
// ("sess:<name>" / "chat:<id>"), or "" for nothing.
//
// Every rule here was bought with a real failure, on one client or another:
//
//   - Finishes come from the finishedRuns COUNTER, via FinishedSince — the same
//     rule the phone uses, so the clients cannot drift about what "finished"
//     means. A running-flag edge misses a run that starts and ends between two
//     observations, and with a watch stream that can be reconnecting that is
//     ordinary rather than exotic.
//   - The first observation seeds and notifies nothing (Baseline.Seeded).
//   - Leaving `attention` withdraws, whether it was answered here, answered in
//     tmux, or the session died. A "needs you" that outlives its question is
//     worse than no notification: the reader opens it to find nothing waiting.
//   - The focused target is suppressed: its prompt is already on screen. The
//     BASELINE STILL ADVANCES for a suppressed alert, so it is consumed rather
//     than deferred — navigating away later must not make a seen question buzz.
//   - enabled=false suppresses posts but never withdraws. Turning
//     notifications off must not strand whatever is currently on the screen.
//
// Within each group decisions are in sorted key order (the Kotlin followed the
// digest's map order); the order BETWEEN groups is the contract.
func Plan(prev Baseline, w Watch, focused string, enabled bool) ([]Decision, Baseline) {
	runsNow := make(map[string]int64, len(w.Chats))
	runningNow := map[string]bool{}
	for id, c := range w.Chats {
		runsNow[id] = c.FinishedRuns
		if c.Running {
			runningNow[id] = true
		}
	}
	stalledNow := map[string]bool{}
	ladderedNow := map[string]string{}
	var stalls map[string]*string
	if hr := w.Headroom; hr != nil {
		for _, n := range hr.Stalled {
			stalledNow[n] = true
		}
		for n, to := range hr.Laddered {
			ladderedNow[n] = to
		}
		stalls = hr.Stalls
	}
	sessionsNow := make(map[string]string, len(w.Sessions))
	for n, s := range w.Sessions {
		sessionsNow[n] = s
	}
	// A daemon with no headroom block leaves Stalled and Laddered EMPTY, which
	// is the same baseline it has always had: nothing appears, nothing
	// disappears, and no headroom decision is ever reached.
	next := Baseline{
		Seeded:   true,
		Sessions: sessionsNow,
		Runs:     runsNow,
		Running:  runningNow,
		Stalled:  stalledNow,
		Laddered: ladderedNow,
	}

	// Absorbed silently — even with notifications DISABLED: otherwise switching
	// them on months later replays the whole history in one burst, which is
	// exactly the failure seeding exists to prevent.
	if !prev.Seeded {
		return nil, next
	}

	var out []Decision

	// --- withdrawals first, so a key that is both taken down and re-posted in
	// one digest ends up posted. (One session cannot be in two states at once,
	// so today that is theoretical; the ORDER is not, and a later rule that
	// makes it possible should not have to rediscover it.)
	for _, name := range sortedKeys(prev.Sessions) {
		if prev.Sessions[name] != StateAttention {
			continue
		}
		// Not in the digest at all counts: killed, renamed, or the daemon
		// restarted. A "needs you" filed under a session that no longer
		// exists has to come down too.
		if w.Sessions[name] != StateAttention {
			out = append(out, Decision{Kind: Withdraw, Key: SessionKey(name)})
		}
	}

	// A session that stopped being stalled is one the reader must not still be
	// looking at a "hit the limit" for: it resumed, was typed into, or ended.
	// Filed under the SESSION key, so opening the session takes it down like
	// any other — and a session that stalls, resumes and stalls again cannot
	// leave two notices standing.
	for _, name := range sortedKeys(prev.Stalled) {
		if !stalledNow[name] {
			out = append(out, Decision{Kind: Withdraw, Key: LimitKey(name)})
		}
	}

	// --- attention edges
	for _, name := range sortedKeys(w.Sessions) {
		if w.Sessions[name] != StateAttention || prev.Sessions[name] == StateAttention {
			continue
		}
		if !enabled || focused == SessionKey(name) {
			continue
		}
		out = append(out, Decision{Kind: Attention, Session: name})
	}

	// --- finishes, by counter (see the doc comment)
	if enabled {
		for _, id := range FinishedSince(prev.Runs, runsNow, prev.Running, runningNow) {
			if focused == ChatKey(id) {
				continue
			}
			c := w.Chats[id] // a vanished chat has no title or snippet
			out = append(out, Decision{Kind: Finished, ChatID: id, Title: c.Title, Snippet: c.Snippet})
		}
	}

	// --- headroom edges, all EDGES rather than states: the digest is a
	// snapshot, and every one of these would otherwise re-fire on every poll
	// for as long as the condition lasted.
	if enabled {
		for _, name := range sortedKeys(stalledNow) {
			if prev.Stalled[name] {
				continue
			}
			at := ""
			if p := stalls[name]; p != nil {
				at = *p
			}
			out = append(out, Decision{Kind: LimitHit, Session: name, ResetsAt: at})
		}

		// ONE notice for however many resumed. Three sessions coming back
		// together is one event — the window reset — and three toasts about it
		// is the same news three times. A session that ENDED while stalled is
		// withdrawn above, never announced as resumed.
		var resumed []string
		for _, name := range sortedKeys(prev.Stalled) {
			if _, live := w.Sessions[name]; live && !stalledNow[name] {
				resumed = append(resumed, name)
			}
		}
		if len(resumed) > 0 {
			out = append(out, Decision{Kind: Resumed, Sessions: resumed})
		}

		for _, name := range sortedKeys(ladderedNow) {
			// A CHANGE of rung counts as well as an arrival: fable → opus →
			// sonnet is two moves, and only reporting the first would leave
			// the reader believing a session is still on opus.
			to := ladderedNow[name]
			if was, ok := prev.Laddered[name]; ok && was == to {
				continue
			}
			out = append(out, Decision{Kind: Downgraded, Session: name, To: to})
		}

		for _, name := range sortedKeys(prev.Laddered) {
			if _, still := ladderedNow[name]; still {
				continue
			}
			// Gone from the digest entirely is not a ladder-up: the session
			// ended, and "back on Fable" about something that no longer exists
			// is a notification with nowhere to go.
			if _, live := w.Sessions[name]; !live {
				continue
			}
			out = append(out, Decision{Kind: LadderUp, Session: name})
		}
	}

	return out, next
}

// FinishedSince is which chats have finished since the previous observation,
// sorted. Ported from :core WatchCycle.finishedSince.
//
// Two ways to notice, because each misses what the other catches. The run
// counter sees a chat that began and ended entirely inside one gap — invisible
// to the running set. The edge (was running, now is not — including a chat
// that vanished) covers a chat whose count was never recorded.
//
// A chat with no previous count contributes nothing by counter: absent a
// baseline, its counter says only how many times it has ever run, and
// announcing that would turn a first look into a burst about history.
func FinishedSince(runsBefore, runsNow map[string]int64, previouslyRunning, running map[string]bool) []string {
	set := map[string]bool{}
	for id, now := range runsNow {
		if before, ok := runsBefore[id]; ok && before < now {
			set[id] = true
		}
	}
	for id, was := range previouslyRunning {
		if was && !running[id] {
			set[id] = true
		}
	}
	return sortedKeys(set)
}

func sortedKeys[V any](m map[string]V) []string {
	keys := make([]string, 0, len(m))
	for k := range m {
		keys = append(keys, k)
	}
	sort.Strings(keys)
	return keys
}
