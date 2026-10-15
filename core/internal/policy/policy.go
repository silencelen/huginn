// Package policy is what a remote request is allowed to become on this machine.
//
// The same rules as the Node runner (client/huginn-device) and the Kotlin one
// (com.silencelen.huginn.device.DevicePolicy), reading the same table
// (table_gen.go, generated from shared/device-policy.json). All three are held
// to shared/device-policy-cases.json, so a request cannot come out differently
// depending on which kind of machine it landed on.
//
// This is the enforcement. The daemon sends a request and never a tool grant;
// the machine that owns the file system decides what the request becomes.
//
// Scopes and modes stay plain strings rather than an enum: they arrive as
// strings (config files, the wire, argv), and the interesting cases are exactly
// the strings nobody defined. Every function here takes the raw value and
// floors it itself, so no caller can forget to.
package policy

import (
	"fmt"
	"strings"
	"unicode"
)

// EnrolDefault is the scope an enrolment that names none asks for. Not the floor:
// saying nothing means a read-only claude device, as it always has, while an
// UNPARSEABLE scope floors to generate (see ParseScope).
const EnrolDefault = enrolDefault

func rank(s string) int {
	for i, x := range scopes {
		if x == s {
			return i
		}
	}
	return -1
}

func contains(xs []string, s string) bool {
	for _, x := range xs {
		if x == s {
			return true
		}
	}
	return false
}

// An EXCLUSIVE scope (generate) matches only itself, both ways: a generate
// device runs only generate work, and generate work runs only on a generate
// device. Rank ordering alone would let `own` satisfy generate — and this
// machine would spawn claude to answer a local-model request, the silent engine
// substitution the policy bans in both directions.
func exclusive(s string) bool { return contains(exclusiveScopes, s) }

// ParseScope trims, lowercases and floors anything unrecognised to the lowest
// rung. Unrecognised is the NARROWEST, never the widest: a typo in a config file
// must not be a privilege escalation. The floor is generate, so a junk scope can
// run nothing a claude device runs, and no claude engine will ever serve it.
//
// The trim is ECMAScript's String.prototype.trim, not strings.TrimSpace: the two
// disagree about U+FEFF and U+0085, and " look\ufeff" must parse the same here
// as it does in the Node runner.
func ParseScope(raw string) string {
	s := strings.ToLower(jsTrim(raw))
	if rank(s) >= 0 {
		return s
	}
	return scopes[0]
}

// Effective is the scope in force right now. A locked machine drops to
// lockDropsTo — not because a lock screen is a security boundary, but because
// nobody is sitting there.
//
// Exclusive scopes ignore the drop: a generate run mutates nothing, so a lock
// has nothing to withdraw — and dropping generate to look would sideways-GRANT
// ask, a claude mode this row has no engine for.
func Effective(scope string, locked bool) string {
	s := ParseScope(scope)
	if exclusive(s) || !locked {
		return s
	}
	return lockDropsTo
}

// Allows reports whether mode can run at scope (taken as already in force; pass
// Effective's answer to account for a lock).
//
// ⚠ AN UNKNOWN MODE IS REFUSED, not mapped to a default. A Go map has no
// prototype, so the JS failure mode (modeNeeds.constructor answering with a
// function, which ranked as "allowed at every scope") cannot happen here — the
// matrix holds this runner to refusing those modes anyway, because mapping an
// unknown mode to a default scope would be no better.
func Allows(scope, mode string) bool {
	need, ok := modeNeeds[mode]
	if !ok {
		return false
	}
	s := ParseScope(scope)
	if exclusive(need) || exclusive(s) {
		return s == need
	}
	return rank(s) >= rank(need)
}

// Refusal says why a request is refused, in words a person can act on, or ""
// when it is allowed (no refusal string in the policy is empty).
//
// The lock is blamed only when unlocking would actually help — the enrolled
// scope allows the mode and only the lock-drop is in the way. Everything else
// names the scope, because the scope is what somebody would have to change.
func Refusal(scope string, locked bool, mode string) string {
	if Allows(Effective(scope, locked), mode) {
		return ""
	}
	if locked && Allows(ParseScope(scope), mode) {
		return refusalLocked
	}
	// Literal substitution of the first placeholder, as the Kotlin runner does.
	// (The Node runner's String.replace reads `$&`, `$$` and friends in the
	// REPLACEMENT, so a mode containing `$` words differently there. Only the
	// text of a refusal differs; it is a refusal either way.)
	s := strings.Replace(refusalScope, "{scope}", ParseScope(scope), 1)
	return strings.Replace(s, "{mode}", mode, 1)
}

// EngineRefusal is the engine fence — the one rule the table cannot hold,
// because it cannot see which binary sits at the far end. Generate work needs a
// local model engine; a runner without one refuses, and never falls through to
// a claude spawn with a tool-flag-free argv. "" when there is nothing to refuse.
func EngineRefusal(mode string, hasEngine bool) string {
	if mode == "generate" && !hasEngine {
		return refusalEngine
	}
	return ""
}

// Work is the part of a work item the argv is built from. Empty means absent,
// as a falsy value does in the Node runner.
type Work struct {
	Mode            string
	Model           string
	Effort          string
	ResumeSessionID string
}

// IsSessionID is the --resume gate: a UUID-shaped string or nothing.
// `--resume` takes its value optionally, so a string beginning with "--" is not
// a session id, it is the next FLAG. This machine builds its own argv — that is
// the whole security model — so it validates here rather than assuming the far
// end did. Same class as the other runners' /^[0-9a-fA-F-]{36}$/.
func IsSessionID(s string) bool {
	if len(s) != 36 {
		return false
	}
	for i := 0; i < len(s); i++ {
		c := s[i]
		if !(c == '-' || '0' <= c && c <= '9' || 'a' <= c && c <= 'f' || 'A' <= c && c <= 'F') {
			return false
		}
	}
	return true
}

// ArgvFor is the argv a work item becomes on this machine (after the engine
// binary). It is built for refused rows too, on purpose: the refusal is the
// first fence and the runner never spawns, but if it ever did, the argv it would
// have used must still be the read-only one. Two fences, both asserted.
func ArgvFor(w Work, scope string, locked bool, persona string) []string {
	eff := Effective(scope, locked)
	argv := append([]string(nil), streamFlags...)
	// A GRANTED generate argv is the bare stream flags plus the request: no tool
	// flags (the shim has no tool surface — absence is the fence), no --effort,
	// and NO persona — huginn's context never rides to a serving box. A REFUSED
	// generate row falls through and carries the look argv: were it ever spawned,
	// the engine would be claude.
	if w.Mode == "generate" && Allows(eff, "generate") {
		if w.Model != "" {
			argv = append(argv, "--model", w.Model)
		}
		if IsSessionID(w.ResumeSessionID) {
			argv = append(argv, "--resume", w.ResumeSessionID)
		}
		return argv
	}
	act := w.Mode == "act" && Allows(eff, "act")
	if w.Model != "" {
		argv = append(argv, "--model", w.Model)
	}
	if w.Effort != "" {
		argv = append(argv, "--effort", w.Effort)
	}
	if IsSessionID(w.ResumeSessionID) {
		argv = append(argv, "--resume", w.ResumeSessionID)
	}
	if persona != "" {
		argv = append(argv, "--append-system-prompt", persona)
	}
	allowed, denied := lookAllowed, lookDenied
	if act {
		allowed, denied = actAllowed, actDenied
	}
	argv = append(argv, "--allowedTools", strings.Join(allowed, " "))
	// Denied as well as un-granted: Claude Code's safe-Bash classification is
	// content-dependent, so an un-granted Bash is a fence that works and then
	// does not. Deny is deterministic.
	if len(denied) > 0 {
		argv = append(argv, "--disallowedTools", strings.Join(denied, " "))
	}
	return argv
}

// CwdFor is where a run starts: the declared root only while `work` is the
// scope in force (a locked work machine is look, and starts at home), else home.
//
// ⚠ WORK IS NOT A JAIL. The difference between `work` and `own` is the directory
// a run starts in, not a kernel boundary — a granted Bash can leave any
// directory. The real fences are the scope gate and the lock rule.
func CwdFor(scope string, locked bool, root, home string) string {
	if r := jsTrim(root); Effective(scope, locked) == "work" && r != "" {
		return r
	}
	return home
}

// ActWhileLocked reads the "keep act mode while locked" setting from a decoded
// JSON value. ⚠ ONLY A REAL true TURNS IT ON: an absent key, "yes", "true", 1 —
// anything a hand-edited device.json might hold — is off. The failure mode of
// guessing is a machine that runs Bash unattended because somebody typed
// `"actWhileLocked": "no"`. The result is what the daemon is sent, always as a
// boolean: absence there would mean a stale true kept after it was turned off.
func ActWhileLocked(v any) bool {
	b, ok := v.(bool)
	return ok && b
}

// GateLocked is the lock the policy is shown: locked AND NOT actWhileLocked.
// A transform in front of the table, not a row in it — the case matrix still
// decides (scope, locked, mode) alone. It widens the lock and nothing else: a
// look machine with the setting on is still a look machine.
func GateLocked(locked bool, actWhileLocked any) bool {
	return locked && !ActWhileLocked(actWhileLocked)
}

// ScopeEcho is the fence against an old daemon at enrolment. An appd without the
// generate rung files an unknown scope as its own floor, which would silently
// enrol a SERVING machine as a read-only CLAUDE device — the exact misenrolment
// the exclusive scope exists to prevent. The scope asked for must be the scope
// the daemon heard, or nothing is offered.
func ScopeEcho(asked, answered string) error {
	if ParseScope(asked) == "generate" && answered != "generate" {
		return fmt.Errorf("the daemon did not recognise scope \"generate\" (it answered \"%s\") — update huginn-appd before enrolling a serving machine", answered)
	}
	return nil
}

// jsTrim is ECMAScript's String.prototype.trim: WhiteSpace (TAB, VT, FF, SP,
// NBSP, ZWNBSP, any Zs) and LineTerminator (LF, CR, LS, PS).
func jsTrim(s string) string {
	return strings.TrimFunc(s, func(r rune) bool {
		switch r {
		case '\t', '\v', '\f', ' ', '\u00a0', '\ufeff', '\n', '\r', '\u2028', '\u2029':
			return true
		}
		return unicode.Is(unicode.Zs, r)
	})
}
