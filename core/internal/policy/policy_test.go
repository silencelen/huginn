package policy

import (
	"encoding/json"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// The Go runner against the shared case matrix — the same file the Node runner
// (server/appd/test/device-policy-cases.test.js) and the Kotlin one
// (DevicePolicyCasesTest) are held to. Compared to the MATRIX rather than to
// either of them, because two implementations can be wrong in the same way and
// a table a person can read cannot be wrong quietly.

type matrixCase struct {
	Scope           string   `json:"scope"`
	Locked          bool     `json:"locked"`
	Mode            string   `json:"mode"`
	EffectiveScope  string   `json:"effectiveScope"`
	ActGranted      bool     `json:"actGranted"`
	GenerateGranted bool     `json:"generateGranted"`
	Refusal         *string  `json:"refusal"`
	Cwd             string   `json:"cwd"`
	Argv            []string `json:"argv"`
}

func loadCases(t *testing.T) []matrixCase {
	t.Helper()
	// core/internal/policy → the repo root, where the matrix is generated.
	raw, err := os.ReadFile(filepath.Join("..", "..", "..", "shared", "device-policy-cases.json"))
	if err != nil {
		t.Fatalf("the case matrix: %v", err)
	}
	var doc struct {
		Cases []matrixCase `json:"cases"`
	}
	if err := json.Unmarshal(raw, &doc); err != nil {
		t.Fatalf("the case matrix: %v", err)
	}
	return doc.Cases
}

func TestEveryCaseProducesTheArgvTheMatrixSays(t *testing.T) {
	cases := loadCases(t)
	// Pinned rather than ">= 140": a matrix that silently shrank would pass
	// every remaining row.
	if len(cases) != 144 {
		t.Fatalf("the matrix has %d cases, want 144 — regenerate and read the diff", len(cases))
	}
	for _, c := range cases {
		where := c.Scope + "/locked=" + map[bool]string{true: "true", false: "false"}[c.Locked] + "/" + c.Mode
		if got := Effective(c.Scope, c.Locked); got != c.EffectiveScope {
			t.Errorf("%s: effective %q, want %q", where, got, c.EffectiveScope)
		}
		want := ""
		if c.Refusal != nil {
			want = *c.Refusal
		}
		if got := Refusal(c.Scope, c.Locked, c.Mode); got != want {
			t.Errorf("%s: refusal %q, want %q", where, got, want)
		}
		if got := ArgvFor(Work{Mode: c.Mode}, c.Scope, c.Locked, ""); !reflect.DeepEqual(got, c.Argv) {
			t.Errorf("%s: argv %q, want %q", where, got, c.Argv)
		}
		wantCwd := map[string]string{"root": "/root-dir", "home": "/home-dir"}[c.Cwd]
		if got := CwdFor(c.Scope, c.Locked, "/root-dir", "/home-dir"); got != wantCwd {
			t.Errorf("%s: cwd %q, want %q", where, got, wantCwd)
		}
		if got := c.Mode == "act" && Allows(Effective(c.Scope, c.Locked), "act"); got != c.ActGranted {
			t.Errorf("%s: act granted %v, want %v", where, got, c.ActGranted)
		}
	}
}

func TestAModeNobodyDefinedIsRefusedAtEveryScope(t *testing.T) {
	// Inherited properties of a JS object, and the empty string. One runner once
	// read modeNeeds.constructor as "allowed everywhere"; no runner may.
	for _, mode := range []string{"constructor", "toString", "__proto__", "hasOwnProperty", "valueOf", "", "ACT", " act", "Act"} {
		for _, scope := range scopes {
			if Allows(scope, mode) {
				t.Errorf("%s allowed unknown mode %q", scope, mode)
			}
			if Refusal(scope, false, mode) == "" {
				t.Errorf("%s did not refuse unknown mode %q", scope, mode)
			}
			argv := strings.Join(ArgvFor(Work{Mode: mode}, scope, false, ""), " ")
			if !strings.Contains(argv, "--disallowedTools Bash") {
				t.Errorf("%s/%q: argv did not deny Bash: %s", scope, mode, argv)
			}
		}
	}
}

func TestAScopeNobodyDefinedIsTheFloor(t *testing.T) {
	for _, raw := range []string{"root", "constructor", "__proto__", "", "admin", "looks", "own!"} {
		if got := ParseScope(raw); got != "generate" {
			t.Errorf("ParseScope(%q) = %q, want the floor (generate)", raw, got)
		}
	}
	// Trimmed and lowercased the way String.prototype.trim and toLowerCase do,
	// including the characters strings.TrimSpace disagrees with JS about.
	for raw, want := range map[string]string{
		"OWN": "own", " work ": "work", "\tLook\n": "look", "\u00a0own\u3000": "own",
		"\ufefflook": "look", "work\u2028": "work",
		"\u0085look": "generate", // NEL is not JS whitespace; TrimSpace would have said look
	} {
		if got := ParseScope(raw); got != want {
			t.Errorf("ParseScope(%q) = %q, want %q", raw, got, want)
		}
	}
}

func TestGenerateIsExclusiveAndLockBlind(t *testing.T) {
	for _, s := range []string{"look", "work", "own"} {
		if Allows(s, "generate") {
			t.Errorf("claude scope %s satisfied generate", s)
		}
	}
	for _, m := range []string{"ask", "act"} {
		if Allows("generate", m) {
			t.Errorf("generate satisfied %s", m)
		}
	}
	if got := Effective("generate", true); got != "generate" {
		t.Errorf("a locked generate device dropped to %q", got)
	}
	// A locked generate device keeps serving, and never blames the lock for what
	// it refuses — unlocking would change nothing.
	if r := Refusal("generate", true, "generate"); r != "" {
		t.Errorf("locked generate refused generate: %q", r)
	}
	if r := Refusal("generate", true, "act"); r == refusalLocked {
		t.Errorf("generate blamed the lock for act")
	}
}

func TestTheLockIsBlamedOnlyWhenUnlockingWouldHelp(t *testing.T) {
	if r := Refusal("own", true, "act"); r != refusalLocked {
		t.Errorf("locked own/act: %q", r)
	}
	if r := Refusal("look", true, "act"); r != "this machine is set to look, which cannot run act" {
		t.Errorf("locked look/act: %q", r)
	}
	// Substitution is literal: a `$&` in the mode stays `$&` (see Refusal).
	if r := Refusal("look", false, "$&"); r != "this machine is set to look, which cannot run $&" {
		t.Errorf("refusal for mode $&: %q", r)
	}
}

func TestAGenerateArgvShedsEverythingButTheRequest(t *testing.T) {
	id := "123e4567-e89b-12d3-a456-426614174000"
	w := Work{Mode: "generate", Model: "local-x-q", Effort: "high", ResumeSessionID: id}
	got := ArgvFor(w, "generate", false, "P")
	want := append(append([]string(nil), streamFlags...), "--model", "local-x-q", "--resume", id)
	if !reflect.DeepEqual(got, want) {
		t.Errorf("granted generate argv %q, want %q", got, want)
	}
	// The claude path still carries effort and persona, in the Node runner's order.
	got = ArgvFor(Work{Mode: "act", Model: "opus", Effort: "high", ResumeSessionID: id}, "work", false, "P")
	want = append(append([]string(nil), streamFlags...),
		"--model", "opus", "--effort", "high", "--resume", id, "--append-system-prompt", "P",
		"--allowedTools", strings.Join(actAllowed, " "))
	if !reflect.DeepEqual(got, want) {
		t.Errorf("act argv %q, want %q", got, want)
	}
	// A REFUSED generate row carries the look argv, ride-alongs included.
	got = ArgvFor(Work{Mode: "generate", Effort: "high"}, "own", false, "P")
	if !strings.Contains(strings.Join(got, " "), "--disallowedTools Bash") || !contains(got, "--effort") {
		t.Errorf("refused generate argv %q", got)
	}
}

func TestAResumeIdThatIsReallyAFlagNeverReachesTheArgv(t *testing.T) {
	for _, hostile := range []string{
		"--dangerously-skip-permissions", "--allowedTools Bash", "-p", "", "  ", "not-a-uuid",
		"123e4567-e89b-12d3-a456-42661417400", "123e4567-e89b-12d3-a456-4266141740000",
		"123e4567-e89b-12d3-a456-42661417400\n", "123e4567-e89b-12d3-a456-42661417400g",
	} {
		argv := ArgvFor(Work{Mode: "ask", ResumeSessionID: hostile}, "look", false, "")
		if contains(argv, "--resume") {
			t.Errorf("resume %q reached the argv: %q", hostile, argv)
		}
	}
	// The class is the other runners' [0-9a-fA-F-]{36}, not "a UUID": all dashes
	// passes there, so it passes here.
	if !IsSessionID(strings.Repeat("-", 36)) || !IsSessionID("123E4567-E89B-12D3-A456-426614174000") {
		t.Error("IsSessionID narrower than the other runners")
	}
}

func TestActWhileLockedIsOnlyALiteralTrue(t *testing.T) {
	for _, v := range []any{nil, "true", "yes", "no", 1.0, 1, []any{true}, map[string]any{}, false} {
		if ActWhileLocked(v) {
			t.Errorf("ActWhileLocked(%#v) = true", v)
		}
		if !GateLocked(true, v) {
			t.Errorf("GateLocked(true, %#v) let the lock through", v)
		}
	}
	if !ActWhileLocked(true) || GateLocked(true, true) {
		t.Error("a literal true did not keep act through the lock")
	}
	if GateLocked(false, nil) || GateLocked(false, true) {
		t.Error("an unlocked machine reported a lock")
	}
	// Decoded from JSON the way a device.json is.
	var conf map[string]any
	_ = json.Unmarshal([]byte(`{"actWhileLocked":"true"}`), &conf)
	if ActWhileLocked(conf["actWhileLocked"]) {
		t.Error(`"true" as a string turned act-while-locked on`)
	}
}

func TestCwdIsTheRootOnlyWhileWorkIsInForce(t *testing.T) {
	cases := []struct {
		scope  string
		locked bool
		root   string
		want   string
	}{
		{"work", false, "/r", "/r"},
		{"work", false, "  /r  ", "/r"},
		{"work", false, "  ", "/h"},
		{"work", true, "/r", "/h"}, // locked work is look
		{"own", false, "/r", "/h"},
		{"look", false, "/r", "/h"},
		{"generate", false, "/r", "/h"},
		{"WORK", false, "/r", "/r"},
	}
	for _, c := range cases {
		if got := CwdFor(c.scope, c.locked, c.root, "/h"); got != c.want {
			t.Errorf("CwdFor(%q, %v, %q) = %q, want %q", c.scope, c.locked, c.root, got, c.want)
		}
	}
}

func TestTheEngineFenceRefusesGenerateWithoutAnEngine(t *testing.T) {
	if EngineRefusal("generate", false) != refusalEngine {
		t.Error("generate without an engine was not refused")
	}
	for _, c := range []struct {
		mode string
		has  bool
	}{{"generate", true}, {"ask", false}, {"act", true}, {"act", false}} {
		if r := EngineRefusal(c.mode, c.has); r != "" {
			t.Errorf("EngineRefusal(%q, %v) = %q", c.mode, c.has, r)
		}
	}
}

func TestTheScopeEchoRefusesAnOldDaemon(t *testing.T) {
	if ScopeEcho("generate", "look") == nil {
		t.Error("a generate enrolment heard as look was accepted")
	}
	if ScopeEcho(" Generate ", "generate") != nil || ScopeEcho("look", "look") != nil {
		t.Error("a faithful echo was refused")
	}
	// Only generate is fenced: the other rungs are understood by every daemon.
	if ScopeEcho("work", "look") != nil {
		t.Error("ScopeEcho fenced a claude scope")
	}
}

func TestTheArgvIsACopy(t *testing.T) {
	// Callers append the prompt after this; a slice aliasing the table would
	// let one run's argv write into the next one's stream flags.
	a := ArgvFor(Work{Mode: "generate"}, "generate", false, "")
	a[0] = "--dangerously-skip-permissions"
	if streamFlags[0] != "-p" {
		t.Fatal("ArgvFor returned the table's own backing array")
	}
}
