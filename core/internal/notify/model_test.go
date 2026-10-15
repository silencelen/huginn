package notify

import (
	"os"
	"os/exec"
	"path/filepath"
	"reflect"
	"testing"
)

// builderDigest runs appd's OWN digest builder (server/appd/lib/watch.js
// digest()) on the given inputs and wraps it exactly as the `event: state`
// frame does in huginn-appd.js (`{...d, changed, serverTime, serverTimeSec,
// pushesSent, pushEpoch}`), so the wire shape is the builder's, not a guess.
func builderDigest(t *testing.T, sessions, chats, headroom string) []byte {
	t.Helper()
	if _, err := exec.LookPath("node"); err != nil {
		t.Skip("node is not installed; this test runs appd's real digest builder")
	}
	lib, err := filepath.Abs(filepath.Join("..", "..", "..", "server", "appd", "lib", "watch.js"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(lib); err != nil {
		t.Skipf("no digest builder at %s: %v", lib, err)
	}
	js := `const {digest}=require(process.argv[1]);
const d=digest(JSON.parse(process.argv[2]),JSON.parse(process.argv[3]),JSON.parse(process.argv[4]));
process.stdout.write(JSON.stringify({...d,changed:true,serverTime:1,serverTimeSec:1,pushesSent:null,pushEpoch:null}));`
	out, err := exec.Command("node", "-e", js, lib, sessions, chats, headroom).Output()
	if err != nil {
		t.Fatalf("node digest: %v", err)
	}
	return out
}

func TestParseWatchFromTheRealBuilder(t *testing.T) {
	raw := builderDigest(t,
		`[{"name":"a","state":"attention"},{"name":"b","state":"running"},{"name":"n","state":null}]`,
		`[{"id":"c1","running":true,"pending":2,"finishedRuns":3,"title":"T","snippet":"S","createdAt":9},
		  {"id":"c2","running":false,"finishedRuns":0,"title":null,"snippet":null}]`,
		`{"mode":"red","stalled":["b"],"stalls":{"b":"2026-09-15T10:30:00.000Z","x":null},
		  "laddered":{"a":"opus"},"lastResumeAt":null,"lastLadderAt":5,"sentinels":["STOP-FABLE"]}`)
	w, err := ParseWatch(raw)
	if err != nil {
		t.Fatalf("parse %s: %v", raw, err)
	}
	if len(w.Hash) != 16 {
		t.Fatalf("hash %q", w.Hash)
	}
	if !reflect.DeepEqual(map[string]string{"a": "attention", "b": "running", "n": ""}, w.Sessions) {
		t.Fatalf("sessions %#v", w.Sessions)
	}
	if _, ok := w.Sessions["n"]; !ok {
		t.Fatal("a null-state session must still be PRESENT (resume/ladder-up read presence)")
	}
	want := map[string]Chat{
		"c1": {Running: true, FinishedRuns: 3, Title: "T", Snippet: "S"},
		"c2": {},
	}
	if !reflect.DeepEqual(want, w.Chats) {
		t.Fatalf("chats %#v", w.Chats)
	}
	h := w.Headroom
	if h == nil || h.Mode != "red" || !reflect.DeepEqual([]string{"b"}, h.Stalled) ||
		!reflect.DeepEqual(map[string]string{"a": "opus"}, h.Laddered) {
		t.Fatalf("headroom %#v", h)
	}
	if p := h.Stalls["b"]; p == nil || *p != "2026-09-15T10:30:00.000Z" {
		t.Fatalf("stalls[b] %v", p)
	}
	if p, ok := h.Stalls["x"]; !ok || p != nil {
		t.Fatalf("stalls[x] must be present and null: %v %v", p, ok)
	}
}

// Two builder digests through Plan: the seed absorbs everything, and the next
// one produces every edge the wire can carry.
func TestPlanOverRealBuilderDigests(t *testing.T) {
	first, err := ParseWatch(builderDigest(t,
		`[{"name":"a","state":"running"},{"name":"b","state":"attention"},{"name":"s","state":"idle"}]`,
		`[{"id":"c1","running":true,"finishedRuns":1},{"id":"c2","running":false,"finishedRuns":4}]`,
		`{"mode":"ok","stalled":["s"],"stalls":{"s":null},"laddered":{"a":"opus"}}`))
	if err != nil {
		t.Fatal(err)
	}
	d, base := Plan(Baseline{}, first, "", true)
	if len(d) != 0 {
		t.Fatalf("seed announced %+v", d)
	}
	second, err := ParseWatch(builderDigest(t,
		`[{"name":"a","state":"attention"},{"name":"b","state":"idle"},{"name":"s","state":"idle"}]`,
		`[{"id":"c1","running":false,"finishedRuns":2,"title":"T","snippet":"done"},{"id":"c2","running":false,"finishedRuns":5}]`,
		`{"mode":"ok","stalled":[],"stalls":{},"laddered":{"a":"sonnet"}}`))
	if err != nil {
		t.Fatal(err)
	}
	d, _ = Plan(base, second, ChatKey("c2"), true)
	want := []Decision{
		{Kind: Withdraw, Key: "sess:b"},
		{Kind: Withdraw, Key: "sess:s"},
		{Kind: Attention, Session: "a"},
		{Kind: Finished, ChatID: "c1", Title: "T", Snippet: "done"}, // c2 is focused
		{Kind: Resumed, Sessions: []string{"s"}},
		{Kind: Downgraded, Session: "a", To: "sonnet"},
	}
	if !reflect.DeepEqual(want, d) {
		t.Fatalf("decisions:\n want %+v\n  got %+v", want, d)
	}
}

func TestParseWatchFromAnOldDaemonHasNoHeadroom(t *testing.T) {
	w, err := ParseWatch([]byte(`{"hash":"x","sessions":{"a":"idle"},"chats":{}}`))
	if err != nil || w.Headroom != nil {
		t.Fatalf("%+v %v", w, err)
	}
}
