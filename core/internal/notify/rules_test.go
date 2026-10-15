package notify

// Ported 1:1 from mobile/app-desktop/src/test/kotlin/.../notify/NotifyRulesTest.kt
// (23 cases). Every one is a rule that exists because a client got it wrong in
// the field. Pure, so none of this needs a tray, a socket or a daemon.

import (
	"reflect"
	"testing"
)

type wopt func(*Watch)

func sess(kv ...string) wopt {
	return func(w *Watch) {
		w.Sessions = map[string]string{}
		for i := 0; i < len(kv); i += 2 {
			w.Sessions[kv[i]] = kv[i+1]
		}
	}
}

func chats(m map[string]Chat) wopt { return func(w *Watch) { w.Chats = m } }

func withHR(h Headroom) wopt { return func(w *Watch) { w.Headroom = &h } }

func watch(opts ...wopt) Watch {
	w := Watch{Hash: "h", Sessions: map[string]string{}, Chats: map[string]Chat{}}
	for _, o := range opts {
		o(&w)
	}
	return w
}

func hr() Headroom { return Headroom{Mode: "ok"} }

func str(s string) *string { return &s }

type plan struct {
	d    []Decision
	base Baseline
}

func step(prev Baseline, w Watch, focused string, enabled bool) plan {
	d, b := Plan(prev, w, focused, enabled)
	return plan{d, b}
}

func on(prev Baseline, w Watch) plan { return step(prev, w, "", true) }

func seed(w Watch) Baseline { return on(Baseline{}, w).base }

func eq(t *testing.T, want, got []Decision) {
	t.Helper()
	if len(want) == 0 && len(got) == 0 {
		return
	}
	if !reflect.DeepEqual(want, got) {
		t.Fatalf("decisions:\n want %+v\n  got %+v", want, got)
	}
}

func has(ds []Decision, d Decision) bool {
	for _, x := range ds {
		if reflect.DeepEqual(x, d) {
			return true
		}
	}
	return false
}

func count(ds []Decision, k Kind) int {
	n := 0
	for _, x := range ds {
		if x.Kind == k {
			n++
		}
	}
	return n
}

func att(s string) Decision { return Decision{Kind: Attention, Session: s} }
func wd(k string) Decision  { return Decision{Kind: Withdraw, Key: k} }

// -------------------------------------------------------------- seeding

func TestFirstObservationAnnouncesNothing(t *testing.T) {
	// Without this, launching fires a wave of alerts about the PAST.
	p := on(Baseline{}, watch(sess("a", "attention", "b", "running"),
		chats(map[string]Chat{"c1": {Running: true, FinishedRuns: 12}})))
	eq(t, nil, p.d)
	if !p.base.Seeded {
		t.Fatal("not seeded")
	}
	if !reflect.DeepEqual(map[string]int64{"c1": 12}, p.base.Runs) {
		t.Fatalf("runs %v", p.base.Runs)
	}
}

func TestSeedingHappensEvenWithNotificationsOff(t *testing.T) {
	// Otherwise switching them on months later replays the whole history.
	p := step(Baseline{}, watch(sess("a", "attention")), "", false)
	if !p.base.Seeded {
		t.Fatal("not seeded")
	}
	eq(t, nil, p.d)
}

// ------------------------------------------------------------- attention

func TestSessionEnteringAttentionIsAnnouncedOnce(t *testing.T) {
	base := seed(watch(sess("a", "running")))
	first := on(base, watch(sess("a", "attention")))
	eq(t, []Decision{att("a")}, first.d)
	// Still waiting on the next digest is not a new event.
	eq(t, nil, on(first.base, watch(sess("a", "attention"))).d)
}

func TestSessionLeavingAttentionWithdraws(t *testing.T) {
	base := seed(watch(sess("a", "attention")))
	eq(t, []Decision{wd("sess:a")}, on(base, watch(sess("a", "idle"))).d)
}

func TestSessionVanishingFromDigestWithdraws(t *testing.T) {
	// Killed, renamed, or the daemon restarted.
	base := seed(watch(sess("a", "attention")))
	eq(t, []Decision{wd("sess:a")}, on(base, watch(sess())).d)
}

func TestWithdrawalsArePlannedBeforePosts(t *testing.T) {
	base := seed(watch(sess("a", "attention", "b", "idle")))
	eq(t, []Decision{wd("sess:a"), att("b")}, on(base, watch(sess("a", "idle", "b", "attention"))).d)
}

// -------------------------------------------------------------- finishes

func TestRunThatBeganAndEndedBetweenTwoLooksIsStillFound(t *testing.T) {
	// The whole reason finishes come from a COUNTER: this chat was never
	// observed running, so an edge detector sees nothing at all.
	base := seed(watch(chats(map[string]Chat{"c": {FinishedRuns: 4}})))
	p := on(base, watch(chats(map[string]Chat{"c": {FinishedRuns: 5, Title: "T", Snippet: "S"}})))
	eq(t, []Decision{{Kind: Finished, ChatID: "c", Title: "T", Snippet: "S"}}, p.d)
}

func TestRunSeenGoingFromRunningToNotIsFoundByTheEdge(t *testing.T) {
	base := seed(watch(chats(map[string]Chat{"c": {Running: true}})))
	p := on(base, watch(chats(map[string]Chat{"c": {}})))
	eq(t, []Decision{{Kind: Finished, ChatID: "c"}}, p.d)
}

func TestChatWithNoPreviousCountContributesNothing(t *testing.T) {
	base := seed(watch())
	eq(t, nil, on(base, watch(chats(map[string]Chat{"new": {FinishedRuns: 99}}))).d)
}

func TestChatThatMerelyKeepsRunningSaysNothing(t *testing.T) {
	base := seed(watch(chats(map[string]Chat{"c": {Running: true, FinishedRuns: 1}})))
	eq(t, nil, on(base, watch(chats(map[string]Chat{"c": {Running: true, FinishedRuns: 1}}))).d)
}

// ----------------------------------------------------------- suppression

func TestNothingFiresForTheFocusedTarget(t *testing.T) {
	base := seed(watch(sess("a", "idle")))
	eq(t, nil, step(base, watch(sess("a", "attention")), NavTarget{Sessions, "a"}.Key(), true).d)

	chatBase := seed(watch(chats(map[string]Chat{"c": {FinishedRuns: 1}})))
	eq(t, nil, step(chatBase, watch(chats(map[string]Chat{"c": {FinishedRuns: 2}})), NavTarget{Chats, "c"}.Key(), true).d)
}

func TestSuppressedAlertIsConsumedNotDeferred(t *testing.T) {
	// Navigating away later must not make an already-seen question buzz.
	base := seed(watch(sess("a", "idle")))
	sup := step(base, watch(sess("a", "attention")), SessionKey("a"), true)
	eq(t, nil, sup.d)
	eq(t, nil, on(sup.base, watch(sess("a", "attention"))).d)
}

func TestLookingAtOneSessionDoesNotSuppressAnother(t *testing.T) {
	base := seed(watch(sess("a", "idle", "b", "idle")))
	p := step(base, watch(sess("a", "attention", "b", "attention")), SessionKey("a"), true)
	eq(t, []Decision{att("b")}, p.d)
}

func TestDisabledSuppressesPostsButNeverWithdrawals(t *testing.T) {
	base := seed(watch(sess("a", "attention", "b", "idle")))
	p := step(base, watch(sess("a", "idle", "b", "attention")), "", false)
	eq(t, []Decision{wd("sess:a")}, p.d)
}

func TestKeysNameTheListTheyBelongTo(t *testing.T) {
	if SessionKey("a") != "sess:a" || ChatKey("a") != "chat:a" {
		t.Fatalf("keys: %q %q", SessionKey("a"), ChatKey("a"))
	}
}

// ------------------------------------------------------------- headroom
//
// All EDGES. The digest is a snapshot: each would re-fire on every poll for as
// long as its condition lasted if it were read as a state.

var live = []string{"a", "idle", "b", "idle"}

func TestJustStalledIsAnnouncedWithResetTime(t *testing.T) {
	base := seed(watch(sess(live...), withHR(hr())))
	h := hr()
	h.Stalled = []string{"a"}
	h.Stalls = map[string]*string{"a": str("2026-09-15T10:30:00Z")}
	p := on(base, watch(sess(live...), withHR(h)))
	eq(t, []Decision{{Kind: LimitHit, Session: "a", ResetsAt: "2026-09-15T10:30:00Z"}}, p.d)

	// And NOT again on the next digest saying the same thing.
	h2 := hr()
	h2.Stalled = []string{"a"}
	eq(t, nil, on(p.base, watch(sess(live...), withHR(h2))).d)
}

func TestLimitNoticeIsWithdrawnWhenStallClears(t *testing.T) {
	h := hr()
	h.Stalled = []string{"a"}
	base := on(seed(watch(sess(live...), withHR(hr()))), watch(sess(live...), withHR(h))).base
	p := on(base, watch(sess(live...), withHR(hr())))
	if !has(p.d, wd("sess:a")) {
		t.Fatalf("no withdraw in %+v", p.d)
	}
	if LimitKey("a") != "sess:a" {
		t.Fatalf("limitKey %q", LimitKey("a"))
	}
}

func TestSessionsComingBackTogetherAreOneNotice(t *testing.T) {
	three := []string{"a", "idle", "b", "idle", "c", "idle"}
	h := hr()
	h.Stalled = []string{"a", "b", "c"}
	base := on(seed(watch(sess(three...), withHR(hr()))), watch(sess(three...), withHR(h))).base
	p := on(base, watch(sess(three...), withHR(hr())))
	if n := count(p.d, Resumed); n != 1 {
		t.Fatalf("resumed count %d in %+v", n, p.d)
	}
	for _, d := range p.d {
		if d.Kind == Resumed && !reflect.DeepEqual([]string{"a", "b", "c"}, d.Sessions) {
			t.Fatalf("resumed sessions %v", d.Sessions)
		}
	}
}

func TestSessionThatEndedWhileStalledIsWithdrawnNeverResumed(t *testing.T) {
	h := hr()
	h.Stalled = []string{"a"}
	base := on(seed(watch(sess("a", "idle"), withHR(hr()))), watch(sess("a", "idle"), withHR(h))).base
	p := on(base, watch(sess(), withHR(hr())))
	if count(p.d, Resumed) != 0 {
		t.Fatalf("resumed about a dead session: %+v", p.d)
	}
	if !has(p.d, wd("sess:a")) {
		t.Fatalf("no withdraw in %+v", p.d)
	}
}

func TestDowngradeCarriesTheUndoSession(t *testing.T) {
	base := seed(watch(sess(live...), withHR(hr())))
	h := hr()
	h.Laddered = map[string]string{"a": "opus"}
	eq(t, []Decision{{Kind: Downgraded, Session: "a", To: "opus"}}, on(base, watch(sess(live...), withHR(h))).d)
}

func TestSecondRungIsItsOwnEventAndGoingBackUpIsTheReverse(t *testing.T) {
	opus, sonnet := hr(), hr()
	opus.Laddered = map[string]string{"a": "opus"}
	sonnet.Laddered = map[string]string{"a": "sonnet"}
	first := on(seed(watch(sess(live...), withHR(hr()))), watch(sess(live...), withHR(opus)))
	// fable → opus → sonnet is TWO moves.
	second := on(first.base, watch(sess(live...), withHR(sonnet)))
	eq(t, []Decision{{Kind: Downgraded, Session: "a", To: "sonnet"}}, second.d)

	up := on(second.base, watch(sess(live...), withHR(hr())))
	eq(t, []Decision{{Kind: LadderUp, Session: "a"}}, up.d)

	// A session that left the digest entirely did not ladder up — it ended.
	gone := on(second.base, watch(sess(), withHR(hr())))
	if count(gone.d, LadderUp) != 0 {
		t.Fatalf("ladder-up about a dead session: %+v", gone.d)
	}
}

func TestFirstLookAtARedHostAnnouncesNoneOfIt(t *testing.T) {
	h := Headroom{Mode: "red", Stalled: []string{"a", "b"}, Laddered: map[string]string{"a": "opus"}}
	p := on(Baseline{}, watch(sess(live...), withHR(h)))
	eq(t, nil, p.d)
	if !reflect.DeepEqual(map[string]bool{"a": true, "b": true}, p.base.Stalled) {
		t.Fatalf("stalled %v", p.base.Stalled)
	}
	if !reflect.DeepEqual(map[string]string{"a": "opus"}, p.base.Laddered) {
		t.Fatalf("laddered %v", p.base.Laddered)
	}
}

func TestDaemonWithNoHeadroomBlockDecidesNothingNew(t *testing.T) {
	// The compat answer: headroom is nil on an older daemon, every headroom
	// baseline stays empty, and nothing here can appear or disappear.
	base := seed(watch(sess(live...)))
	p := on(base, watch(sess("a", "attention", "b", "idle")))
	eq(t, []Decision{att("a")}, p.d)
	if len(p.base.Stalled) != 0 || len(p.base.Laddered) != 0 {
		t.Fatalf("headroom baseline not empty: %v %v", p.base.Stalled, p.base.Laddered)
	}
}
