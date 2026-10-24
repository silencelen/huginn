package runner

import (
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"runtime"
	"strings"
)

// engine names a run's engine in the words a person reads and the config key
// they would edit. ⚠ Both terminal handlers once hardcoded "claude", so a
// generate device's local-engine failure named a program the machine does not run.
func engine(mode string) (label, key string) {
	if mode == "generate" {
		return "the local engine", "llm"
	}
	return "claude", "claude"
}

var errLine = regexp.MustCompile(`Cannot find module|Error:|error:`)

// errWords is the most useful 200 characters of a child's stderr. ⚠ THE HEAD,
// NOT THE TAIL: a missing engine file names itself in its FIRST line and spends
// the rest on a stack. The first line mentioning an error wins, else the first.
func errWords(stderr string) string {
	t := strings.TrimSpace(stderr)
	if t == "" {
		return ""
	}
	lines := strings.Split(t, "\n")
	pick := lines[0]
	for _, l := range lines {
		if errLine.MatchString(l) {
			pick = l
			break
		}
	}
	return truncRunes(strings.TrimSpace(pick), 200)
}

func truncRunes(s string, n int) string {
	r := []rune(s)
	if len(r) > n {
		return string(r[:n])
	}
	return s
}

// notFoundText — ⚠ no config PATH in it: it lands in a chat that syncs to a
// phone, and a machine's private layout is not worth publishing for a sentence
// that is just as actionable without it.
func notFoundText(mode, host string) string {
	label, key := engine(mode)
	return fmt.Sprintf(`%s was not found on %s (set "%s" to its full path in this machine's huginn config)`, label, host, key)
}

func exitedText(mode, host string, code int, stderr string) string {
	label, _ := engine(mode)
	if w := errWords(stderr); w != "" {
		return fmt.Sprintf("%s exited %d on %s: %s", label, code, host, w)
	}
	return fmt.Sprintf("%s exited %d on %s", label, code, host)
}

// execFor turns an engine path into a spawn. A .js engine runs through node.
// ⚠ AND SO DOES AN NPM SHIM: `npm i -g @anthropic-ai/claude-code` on Windows
// leaves `claude.cmd` on PATH, and the shim only runs the cli.js beside it, so
// run that. ⚠⚠ Go's os/exec does NOT refuse a .cmd the way Node does: it runs
// it through cmd.exe, quoting for CommandLineToArgvW only, so `opus&calc.exe`
// in a model name ran a second command. A
// .cmd/.bat with no cli.js beside it is therefore REFUSED (ok=false), never
// spawned. The native claude.exe needs none of this.
func execFor(engineBin string, argv []string, node string) (string, []string, bool) {
	if strings.HasSuffix(engineBin, ".js") {
		return node, append([]string{engineBin}, argv...), true
	}
	if isBatch(engineBin) {
		cli := filepath.Join(filepath.Dir(engineBin), "node_modules", "@anthropic-ai", "claude-code", "cli.js")
		if fi, err := os.Stat(cli); err == nil && fi.Mode().IsRegular() {
			return node, append([]string{cli}, argv...), true
		}
		return "", nil, false
	}
	return engineBin, argv, true
}

func isBatch(p string) bool {
	low := strings.ToLower(p)
	return strings.HasSuffix(low, ".cmd") || strings.HasSuffix(low, ".bat")
}

// resolveEngine finds the engine the way exec would (PATH, PATHEXT) BEFORE the
// batch check: the default bare "claude" is itself what resolves to claude.cmd.
func resolveEngine(bin string) string {
	if p, err := exec.LookPath(bin); err == nil {
		return p
	}
	return bin // not found: exec reports it, and notFoundText says so
}

func batchRefusal(mode, host string) string {
	label, key := engine(mode)
	return fmt.Sprintf(`%s on %s is a .cmd/.bat shim this machine will not run (no cli.js beside it to run through node) — set "%s" to the engine's .exe in this machine's huginn config`, label, host, key)
}

// The ride-along values a work item may carry, checked HERE: the device builds
// its own argv, and an honest daemon's validation is not this machine's fence.
// Effort is appd's own enum (lib/projects.js EFFORTS); a model is a plain
// identifier — no shell metacharacters, no spaces, no leading dash.
var modelRe = regexp.MustCompile(`^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$`)

var efforts = map[string]bool{"low": true, "medium": true, "high": true, "xhigh": true, "max": true}

func rideAlongRefusal(model, effort string) string {
	if model != "" && !modelRe.MatchString(model) {
		return fmt.Sprintf("this machine refused the work: %q is not a model name it accepts", truncRunes(model, 40))
	}
	if effort != "" && !efforts[effort] {
		return fmt.Sprintf("this machine refused the work: %q is not an effort level it accepts", truncRunes(effort, 40))
	}
	return ""
}

var nonKey = regexp.MustCompile(`[^a-z0-9-]+`)
var dashes = regexp.MustCompile(`-+`)

// MachineKey is the box a row belongs to, normalised the way the daemon
// normalises: rows sharing it fold into one machine. Grouping and display only,
// never authority.
func MachineKey(hostname string) string {
	m := nonKey.ReplaceAllString(strings.ToLower(hostname), "-")
	m = strings.Trim(dashes.ReplaceAllString(m, "-"), "-")
	if len(m) > 40 {
		m = m[:40]
	}
	return m
}

// Platform is the wire word for this OS.
func Platform() string {
	switch runtime.GOOS {
	case "windows":
		return "windows"
	case "darwin":
		return "macos"
	case "linux":
		return "linux"
	}
	return "other"
}
