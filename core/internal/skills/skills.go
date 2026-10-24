// Package skills is the Claude Code skills huginn core carries and installs:
// `huginn` (what Huginn is and how its verbs and files work on this machine)
// and `huginn-doctor` (the runbook for "huginn cannot be reached"). The model
// is Omarchy's: a skill is a directory with SKILL.md and topic files beside
// it, laid down under the core's install root and linked into
// ~/.claude/skills/<name>, where Claude Code loads it.
//
// files/ is a copy of skills/huginn and skills/huginn-doctor at the repository
// root (embed cannot reach outside the package). TestEmbeddedMatchesSkills
// fails when the two differ, and core/build.sh refuses to build on the same
// drift, so the binary always carries the commit's skills.
//
// What goes into ~/.claude/skills is Huginn's to replace and remove ONLY when
// Huginn put it there: a symlink whose target is under the install root, or a
// directory carrying the Marker file. Anything else at that name is the
// person's own skill and is left alone, and said so.
package skills

import (
	"bytes"
	"embed"
	"errors"
	"io/fs"
	"os"
	"path/filepath"
	"sort"
	"strings"
)

//go:embed all:files
var tree embed.FS

// Marker is the file a copied skill directory carries, so Refresh and Remove
// know the copy is Huginn's (Windows: a symlink needs a privilege, so the
// skills are copied there).
const Marker = ".huginn-skill"

const markerText = "This directory was put here by huginn core (huginn skills install).\n" +
	"It is replaced on every update and removed by `huginn skills remove`, so edits here are lost.\n"

// Env is this machine, so the Windows answers can be asserted on Linux.
type Env struct {
	GOOS string
	Home string
	// Root is the core's install root: ~/.local/share/huginn on Linux,
	// %LOCALAPPDATA%\huginn on Windows (install.Layout.Root).
	Root string
}

// Dir is where the core lays the skills: <Root>/skills/<name>/.
func (e Env) Dir() string { return filepath.Join(e.Root, "skills") }

// ClaudeDir is Claude Code's per-user directory.
func (e Env) ClaudeDir() string { return filepath.Join(e.Home, ".claude") }

// LinkDir is where Claude Code loads skills from.
func (e Env) LinkDir() string { return filepath.Join(e.ClaudeDir(), "skills") }

// copies: Windows copies the skill directory; everywhere else links it.
func (e Env) copies() bool { return e.GOOS == "windows" }

func (e Env) check() error {
	if e.Home == "" || e.Root == "" {
		return errors.New("skills: no home or install root to work in")
	}
	return nil
}

// Names are the skills the core carries, in list order.
func Names() []string {
	ents, err := fs.ReadDir(tree, "files")
	if err != nil {
		return nil
	}
	var out []string
	for _, e := range ents {
		if e.IsDir() {
			out = append(out, e.Name())
		}
	}
	sort.Strings(out)
	return out
}

// Files is one skill's files, by path relative to its directory.
func Files(name string) (map[string][]byte, error) {
	root := "files/" + name
	out := map[string][]byte{}
	err := fs.WalkDir(tree, root, func(p string, d fs.DirEntry, err error) error {
		if err != nil {
			return err
		}
		if d.IsDir() {
			return nil
		}
		b, err := tree.ReadFile(p)
		if err != nil {
			return err
		}
		out[filepath.FromSlash(strings.TrimPrefix(p, root+"/"))] = b
		return nil
	})
	if err != nil {
		return nil, err
	}
	if len(out) == 0 {
		return nil, errors.New("skills: no such skill " + name)
	}
	return out, nil
}

// Lay writes every skill into <Root>/skills/<name>/, replacing files that
// differ and removing files that are no longer part of the skill. Reports
// whether anything changed. The CLI's satellites are laid the same way: this
// is what `core self-install` does on every install and update.
func Lay(e Env) (bool, error) {
	if err := e.check(); err != nil {
		return false, err
	}
	changed := false
	for _, name := range Names() {
		dir := filepath.Join(e.Dir(), name)
		c, err := writeTree(dir, name, false)
		if err != nil {
			return changed, err
		}
		changed = changed || c
	}
	return changed, nil
}

// writeTree makes dir hold exactly skill name's files (plus the Marker when
// marker is set), writing only what differs. Reports whether it changed anything.
func writeTree(dir, name string, marker bool) (bool, error) {
	want, err := Files(name)
	if err != nil {
		return false, err
	}
	if marker {
		want[Marker] = []byte(markerText)
	}
	if err := os.MkdirAll(dir, 0o755); err != nil {
		return false, err
	}
	changed := false
	for rel, b := range want {
		p := filepath.Join(dir, rel)
		if have, err := os.ReadFile(p); err == nil && bytes.Equal(have, b) {
			continue
		}
		if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
			return changed, err
		}
		tmp := p + ".tmp"
		if err := os.WriteFile(tmp, b, 0o644); err != nil {
			return changed, err
		}
		if err := os.Rename(tmp, p); err != nil {
			os.Remove(tmp)
			return changed, err
		}
		changed = true
	}
	// Files that are no longer the skill's (a topic file renamed in a release).
	filepath.WalkDir(dir, func(p string, d fs.DirEntry, err error) error {
		if err != nil || d.IsDir() {
			return nil
		}
		rel, rerr := filepath.Rel(dir, p)
		if rerr != nil {
			return nil
		}
		if _, ok := want[rel]; !ok {
			if os.Remove(p) == nil {
				changed = true
			}
		}
		return nil
	})
	return changed, nil
}

// current reports whether dir holds exactly what skill name's copy should
// (its files, byte for byte, and the Marker).
func current(dir, name string) bool {
	want, err := Files(name)
	if err != nil {
		return false
	}
	want[Marker] = []byte(markerText)
	for rel, b := range want {
		have, err := os.ReadFile(filepath.Join(dir, rel))
		if err != nil || !bytes.Equal(have, b) {
			return false
		}
	}
	return true
}

// States of what sits at ~/.claude/skills/<name>.
const (
	StateLinked  = "linked"  // a link into the install root (Linux)
	StateCopied  = "copied"  // Huginn's copy, current (Windows)
	StateStale   = "stale"   // Huginn's, but not this version's content or target
	StateForeign = "foreign" // something that is not Huginn's: the person's own skill
	StateMissing = "missing"
)

// Entry is one skill's state on this machine.
type Entry struct {
	Name   string `json:"name"`
	State  string `json:"state"`
	Path   string `json:"path"`             // ~/.claude/skills/<name>
	Target string `json:"target,omitempty"` // the link's target, when it is a link
	Detail string `json:"detail,omitempty"`
}

// Ours: Huginn made what is at the entry (and may replace or remove it).
func (en Entry) Ours() bool {
	return en.State == StateLinked || en.State == StateCopied || en.State == StateStale
}

// Installed: the skill is there, as this version lays it.
func (en Entry) Installed() bool { return en.State == StateLinked || en.State == StateCopied }

// Status says, per skill, what is at ~/.claude/skills/<name>.
func Status(e Env) []Entry {
	var out []Entry
	for _, name := range Names() {
		out = append(out, inspect(e, name))
	}
	return out
}

func inspect(e Env, name string) Entry {
	en := Entry{Name: name, Path: filepath.Join(e.LinkDir(), name)}
	fi, err := os.Lstat(en.Path)
	if err != nil {
		en.State = StateMissing
		return en
	}
	want := filepath.Join(e.Dir(), name)
	switch {
	case fi.Mode()&os.ModeSymlink != 0:
		t, err := os.Readlink(en.Path)
		if err != nil {
			en.State, en.Detail = StateForeign, "a link that cannot be read"
			return en
		}
		en.Target = t
		abs := t
		if !filepath.IsAbs(abs) {
			abs = filepath.Join(e.LinkDir(), t)
		}
		abs = filepath.Clean(abs)
		if !underRoot(abs, e.Root) {
			en.State, en.Detail = StateForeign, "a link to "+t+", which is not huginn's"
			return en
		}
		if abs != want || e.copies() {
			en.State, en.Detail = StateStale, "a link to "+t
			return en
		}
		en.State = StateLinked
	case fi.IsDir():
		if _, err := os.Stat(filepath.Join(en.Path, Marker)); err != nil {
			en.State, en.Detail = StateForeign, "a directory without huginn's marker: your own skill"
			return en
		}
		if !e.copies() || !current(en.Path, name) {
			en.State, en.Detail = StateStale, "huginn's copy, not this version's"
			return en
		}
		en.State = StateCopied
	default:
		en.State, en.Detail = StateForeign, "a file, not a skill directory"
	}
	return en
}

func underRoot(p, root string) bool {
	root = filepath.Clean(root)
	return p == root || strings.HasPrefix(p, root+string(filepath.Separator))
}

// Report is what Install, Refresh and Remove did.
type Report struct {
	Done   []string // skills installed, refreshed or removed
	Kept   []string // what was left alone, and why
	Errors []string
}

// OK: nothing failed.
func (r Report) OK() bool { return len(r.Errors) == 0 }

// Install puts every skill into ~/.claude/skills (making the directory):
// a link to <Root>/skills/<name> on Linux, a copy with the Marker on Windows.
// Replaces only what Huginn made; the person's own skill at that name stays,
// and is named in Kept.
func Install(e Env) Report {
	return place(e, false)
}

// Refresh re-links or re-copies the skills Huginn already put there, and
// nothing else: what an update does.
func Refresh(e Env) Report {
	return place(e, true)
}

func place(e Env, onlyOurs bool) Report {
	var r Report
	if err := e.check(); err != nil {
		r.Errors = append(r.Errors, err.Error())
		return r
	}
	for _, en := range Status(e) {
		switch {
		case en.State == StateForeign:
			r.Kept = append(r.Kept, en.Name+": "+en.Path+" is "+en.Detail+" - left alone")
			continue
		case en.Installed():
			continue // nothing to do; counts as done below only when installing
		case onlyOurs && !en.Ours():
			continue
		}
		if err := os.MkdirAll(e.LinkDir(), 0o755); err != nil {
			r.Errors = append(r.Errors, en.Name+": "+err.Error())
			continue
		}
		if en.Ours() {
			if err := removeOurs(en); err != nil {
				r.Errors = append(r.Errors, en.Name+": "+err.Error())
				continue
			}
		}
		var err error
		if e.copies() {
			_, err = writeTree(en.Path, en.Name, true)
		} else {
			err = os.Symlink(filepath.Join(e.Dir(), en.Name), en.Path)
		}
		if err != nil {
			r.Errors = append(r.Errors, en.Name+": "+err.Error())
			continue
		}
		r.Done = append(r.Done, en.Name)
	}
	return r
}

// removeOurs takes a link or Huginn's copy away; never a foreign entry.
func removeOurs(en Entry) error {
	if !en.Ours() {
		return errors.New(en.Path + " is not huginn's")
	}
	if en.Target != "" {
		return os.Remove(en.Path) // the link itself, never what it points at
	}
	return os.RemoveAll(en.Path)
}

// Remove takes out of ~/.claude/skills what Huginn put there, and names what
// it left. The copies under <Root>/skills stay: they go with the core.
func Remove(e Env) Report {
	var r Report
	if err := e.check(); err != nil {
		r.Errors = append(r.Errors, err.Error())
		return r
	}
	for _, en := range Status(e) {
		switch {
		case en.State == StateMissing:
		case en.State == StateForeign:
			r.Kept = append(r.Kept, en.Name+": "+en.Path+" is "+en.Detail+" - left alone")
		default:
			if err := removeOurs(en); err != nil {
				r.Errors = append(r.Errors, en.Name+": "+err.Error())
				continue
			}
			r.Done = append(r.Done, en.Name)
		}
	}
	return r
}

// AnyOurs: at least one skill in ~/.claude/skills is Huginn's (so an update
// refreshes, and setup asks nothing).
func AnyOurs(e Env) bool {
	for _, en := range Status(e) {
		if en.Ours() {
			return true
		}
	}
	return false
}

// ClaudePresent: Claude Code is on this machine - `claude` on PATH, or its
// ~/.claude directory exists. lookPath is exec.LookPath, or a test's stand-in.
func ClaudePresent(e Env, lookPath func(string) (string, error)) bool {
	if lookPath != nil {
		if _, err := lookPath("claude"); err == nil {
			return true
		}
	}
	fi, err := os.Stat(e.ClaudeDir())
	return err == nil && fi.IsDir()
}

// Decision is what setup does about the skills on this machine.
type Decision int

const (
	// DecideRefresh: Huginn's skills are already there; refresh them, ask nothing.
	DecideRefresh Decision = iota
	// DecideInstall: put them there (the preset said yes).
	DecideInstall
	// DecideDeclined: leave them out (the preset said no, or the person declined before).
	DecideDeclined
	// DecideNoClaude: Claude Code is not on this machine; nothing to install into.
	DecideNoClaude
	// DecideAsk: ask the person.
	DecideAsk
	// DecideNoTTY: nobody to ask; leave them out this time, without recording a no.
	DecideNoTTY
)

// Decide is setup's rule, pure. preset is HUGINN_SKILLS (1|y|yes, 0|n|no, or
// empty); installed is AnyOurs; declinedBefore is the state marker a no left;
// claudePresent is ClaudePresent; tty says whether a person can be asked. The
// no-terminal answer is the installers' rule for every question: a no, but
// one that is not remembered, so the next run from a terminal asks.
func Decide(preset string, installed, declinedBefore, claudePresent, tty bool) Decision {
	switch strings.ToLower(strings.TrimSpace(preset)) {
	case "1", "y", "yes", "true":
		return DecideInstall
	case "0", "n", "no", "false":
		return DecideDeclined
	}
	switch {
	case installed:
		return DecideRefresh
	case declinedBefore:
		return DecideDeclined
	case !claudePresent:
		return DecideNoClaude
	case tty:
		return DecideAsk
	}
	return DecideNoTTY
}
