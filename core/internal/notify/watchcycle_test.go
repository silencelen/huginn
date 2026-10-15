package notify

// Ported 1:1 from mobile/core/src/commonTest/kotlin/.../WatchCycleTest.kt (10
// cases): the rule that decides whether a chat finished while nobody was
// looking. "Started and finished between two observations" is the normal case,
// and the obvious implementation — diffing the running set — reports nothing.

import (
	"reflect"
	"testing"
)

func set(ids ...string) map[string]bool {
	m := map[string]bool{}
	for _, id := range ids {
		m[id] = true
	}
	return m
}

func TestFinishedSince(t *testing.T) {
	cases := []struct {
		name                string
		before, now         map[string]int64
		wasRunning, running map[string]bool
		want                []string
	}{
		{"seen running and then not has finished",
			map[string]int64{"c1": 0}, map[string]int64{"c1": 0}, set("c1"), set(), []string{"c1"}},
		{"ran entirely between two looks still counts",
			map[string]int64{"c1": 3}, map[string]int64{"c1": 4}, set(), set(), []string{"c1"}},
		{"several runs inside one gap report the chat once",
			map[string]int64{"c1": 1}, map[string]int64{"c1": 5}, set(), set(), []string{"c1"}},
		{"finished and immediately started again is reported",
			map[string]int64{"c1": 1}, map[string]int64{"c1": 2}, set("c1"), set("c1"), []string{"c1"}},
		{"still running with no completed run is not reported",
			map[string]int64{"c1": 2}, map[string]int64{"c1": 2}, set("c1"), set("c1"), nil},
		{"nothing happening reports nothing",
			map[string]int64{"c1": 7}, map[string]int64{"c1": 7}, set(), set(), nil},
		// The trap: a chat with no baseline has a counter describing its whole
		// history; treating that as news announces every chat ever run.
		{"never seen before is not announced on its history",
			map[string]int64{}, map[string]int64{"old-chat": 42}, set(), set(), nil},
		{"deleted chat that was running is still reported",
			map[string]int64{"c1": 1}, map[string]int64{}, set("c1"), set(), []string{"c1"}},
		{"two chats finishing at once are both reported",
			map[string]int64{"c1": 0, "c2": 0}, map[string]int64{"c1": 1, "c2": 0}, set("c2"), set(), []string{"c1", "c2"}},
		{"counter that went backwards is not a finish",
			map[string]int64{"c1": 5}, map[string]int64{"c1": 2}, set(), set(), nil},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			got := FinishedSince(c.before, c.now, c.wasRunning, c.running)
			if len(c.want) == 0 && len(got) == 0 {
				return
			}
			if !reflect.DeepEqual(c.want, got) {
				t.Fatalf("want %v got %v", c.want, got)
			}
		})
	}
}
