package runner

import (
	"fmt"
	"os"
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
// leaves `claude.cmd` on PATH, and spawning a .cmd needs a shell — the one thing
// this runner will not put near a prompt. The shim only runs the cli.js beside
// it, so run that. A .cmd with no cli.js beside it is spawned as given and fails
// with the reason in the chat rather than a guess. (Go's os/exec has the same
// .cmd trap as Node; the native claude.exe needs none of this.)
func execFor(engineBin string, argv []string, node string) (string, []string) {
	if strings.HasSuffix(engineBin, ".js") {
		return node, append([]string{engineBin}, argv...)
	}
	low := strings.ToLower(engineBin)
	if strings.HasSuffix(low, ".cmd") || strings.HasSuffix(low, ".bat") {
		cli := filepath.Join(filepath.Dir(engineBin), "node_modules", "@anthropic-ai", "claude-code", "cli.js")
		if fi, err := os.Stat(cli); err == nil && fi.Mode().IsRegular() {
			return node, append([]string{cli}, argv...)
		}
	}
	return engineBin, argv
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
