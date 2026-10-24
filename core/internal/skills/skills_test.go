package skills

import (
	"bytes"
	"io/fs"
	"os"
	"path/filepath"
	"reflect"
	"strings"
	"testing"
)

// The embedded skills are skills/ at the repository root, as committed, in
// both directions: every embedded file has its source, and every source file
// is embedded. core/build.sh refuses to build on the same drift.
func TestEmbeddedMatchesSkills(t *testing.T) {
	for _, name := range []string{"huginn", "huginn-doctor"} {
		src := filepath.Join("..", "..", "..", "skills", name)
		emb, err := Files(name)
		if err != nil {
			t.Fatalf("%s: %v", name, err)
		}
		seen := map[string]bool{}
		err = filepath.WalkDir(src, func(p string, d fs.DirEntry, err error) error {
			if err != nil || d.IsDir() {
				return err
			}
			rel, _ := filepath.Rel(src, p)
			seen[rel] = true
			want, _ := os.ReadFile(p)
			if got, ok := emb[rel]; !ok || !bytes.Equal(got, want) {
				t.Errorf("core/internal/skills/files/%s/%s differs from skills/%s/%s: copy skills/%s into core/internal/skills/files/ and commit both", name, rel, name, rel, name)
			}
			return nil
		})
		if err != nil {
			t.Fatalf("%s: %v", src, err)
		}
		for rel := range emb {
			if !seen[rel] {
				t.Errorf("core/internal/skills/files/%s/%s has no source in skills/%s", name, rel, name)
			}
		}
	}
	if got := Names(); !reflect.DeepEqual(got, []string{"huginn", "huginn-doctor"}) {
		t.Fatalf("Names() = %v", got)
	}
}

// Claude Code loads a skill by its SKILL.md front matter: the name must be the
// directory's, and the description is what makes it load, so it must be there.
func TestFrontMatterNamesTheDirectory(t *testing.T) {
	for _, name := range Names() {
		f, err := Files(name)
		if err != nil {
			t.Fatal(err)
		}
		b, ok := f["SKILL.md"]
		if !ok {
			t.Fatalf("%s has no SKILL.md", name)
		}
		lines := strings.SplitN(string(b), "\n", 4)
		if len(lines) < 4 || lines[0] != "---" || lines[1] != "name: "+name || !strings.HasPrefix(lines[2], "description: ") || len(lines[2]) < 60 {
			t.Fatalf("%s/SKILL.md front matter: %q", name, lines[:min(3, len(lines))])
		}
	}
}

func linuxEnv(t *testing.T) Env {
	t.Helper()
	return Env{GOOS: "linux", Home: t.TempDir(), Root: filepath.Join(t.TempDir(), "share", "huginn")}
}

func TestLayWritesAndPrunes(t *testing.T) {
	e := linuxEnv(t)
	changed, err := Lay(e)
	if err != nil || !changed {
		t.Fatalf("first Lay: changed=%v err=%v", changed, err)
	}
	if _, err := os.Stat(filepath.Join(e.Dir(), "huginn", "SKILL.md")); err != nil {
		t.Fatal(err)
	}
	if changed, _ := Lay(e); changed {
		t.Fatal("a second Lay changed something")
	}
	// A file from an older release goes; an edited file is put back.
	stale := filepath.Join(e.Dir(), "huginn", "old-topic.md")
	os.WriteFile(stale, []byte("old"), 0o644)
	os.WriteFile(filepath.Join(e.Dir(), "huginn", "verbs.md"), []byte("edited"), 0o644)
	if changed, _ := Lay(e); !changed {
		t.Fatal("Lay did not repair the tree")
	}
	if _, err := os.Stat(stale); !os.IsNotExist(err) {
		t.Fatal("the stale file stayed")
	}
	want, _ := Files("huginn")
	if b, _ := os.ReadFile(filepath.Join(e.Dir(), "huginn", "verbs.md")); !bytes.Equal(b, want["verbs.md"]) {
		t.Fatal("the edited file was not put back")
	}
}

// Linux: a link per skill into the install root; a skill the person wrote is
// never touched; Refresh touches only Huginn's; Remove takes only Huginn's.
func TestLinkInstallRefreshRemove(t *testing.T) {
	e := linuxEnv(t)
	if _, err := Lay(e); err != nil {
		t.Fatal(err)
	}
	// Nothing of Huginn's yet: an update refreshes nothing, and installs nothing.
	if r := Refresh(e); len(r.Done) != 0 || !r.OK() {
		t.Fatalf("Refresh on a bare home did %+v", r)
	}
	if AnyOurs(e) {
		t.Fatal("AnyOurs on a bare home")
	}
	// The person's own skill under one of our names.
	mine := filepath.Join(e.LinkDir(), "huginn-doctor")
	os.MkdirAll(mine, 0o755)
	os.WriteFile(filepath.Join(mine, "SKILL.md"), []byte("---\nname: huginn-doctor\n---\nmine\n"), 0o644)

	r := Install(e)
	if !r.OK() || !reflect.DeepEqual(r.Done, []string{"huginn"}) || len(r.Kept) != 1 || !strings.Contains(r.Kept[0], "your own skill") {
		t.Fatalf("Install: %+v", r)
	}
	target, err := os.Readlink(filepath.Join(e.LinkDir(), "huginn"))
	if err != nil || target != filepath.Join(e.Dir(), "huginn") {
		t.Fatalf("link -> %q (%v)", target, err)
	}
	if b, _ := os.ReadFile(filepath.Join(mine, "SKILL.md")); !strings.Contains(string(b), "mine") {
		t.Fatal("the person's skill was replaced")
	}
	st := Status(e)
	if st[0].State != StateLinked || st[1].State != StateForeign {
		t.Fatalf("Status: %+v", st)
	}
	if !AnyOurs(e) {
		t.Fatal("AnyOurs after Install")
	}
	// Installing again is a no-op.
	if r := Install(e); !r.OK() || len(r.Done) != 0 {
		t.Fatalf("second Install: %+v", r)
	}
	// A link into the root but at an old path (a previous layout) is ours, and
	// a refresh points it at this version's directory.
	os.Remove(filepath.Join(e.LinkDir(), "huginn"))
	os.Symlink(filepath.Join(e.Root, "old", "huginn"), filepath.Join(e.LinkDir(), "huginn"))
	if s := inspect(e, "huginn"); s.State != StateStale {
		t.Fatalf("an old link: %+v", s)
	}
	if r := Refresh(e); !r.OK() || !reflect.DeepEqual(r.Done, []string{"huginn"}) {
		t.Fatalf("Refresh: %+v", r)
	}
	if target, _ := os.Readlink(filepath.Join(e.LinkDir(), "huginn")); target != filepath.Join(e.Dir(), "huginn") {
		t.Fatalf("refreshed link -> %q", target)
	}
	// A link to somewhere else entirely is the person's.
	os.Remove(filepath.Join(e.LinkDir(), "huginn"))
	os.Symlink(t.TempDir(), filepath.Join(e.LinkDir(), "huginn"))
	if s := inspect(e, "huginn"); s.State != StateForeign {
		t.Fatalf("a foreign link: %+v", s)
	}
	r = Remove(e)
	if !r.OK() || len(r.Done) != 0 || len(r.Kept) != 2 {
		t.Fatalf("Remove with two foreign entries: %+v", r)
	}
	// Ours again, then removed: the link goes, what it pointed at stays.
	os.Remove(filepath.Join(e.LinkDir(), "huginn"))
	Install(e)
	r = Remove(e)
	if !r.OK() || !reflect.DeepEqual(r.Done, []string{"huginn"}) {
		t.Fatalf("Remove: %+v", r)
	}
	if _, err := os.Lstat(filepath.Join(e.LinkDir(), "huginn")); !os.IsNotExist(err) {
		t.Fatal("the link stayed")
	}
	if _, err := os.Stat(filepath.Join(e.Dir(), "huginn", "SKILL.md")); err != nil {
		t.Fatal("Remove deleted the install root's copy through the link")
	}
	if _, err := os.Stat(filepath.Join(mine, "SKILL.md")); err != nil {
		t.Fatal("Remove deleted the person's skill")
	}
}

// Windows: a copy with the marker; a stale copy is refreshed; a directory
// without the marker is the person's.
func TestCopyMode(t *testing.T) {
	e := Env{GOOS: "windows", Home: t.TempDir(), Root: filepath.Join(t.TempDir(), "huginn")}
	if _, err := Lay(e); err != nil {
		t.Fatal(err)
	}
	r := Install(e)
	if !r.OK() || !reflect.DeepEqual(r.Done, []string{"huginn", "huginn-doctor"}) {
		t.Fatalf("Install: %+v", r)
	}
	p := filepath.Join(e.LinkDir(), "huginn")
	if fi, err := os.Lstat(p); err != nil || !fi.IsDir() {
		t.Fatalf("not a directory: %v", err)
	}
	if _, err := os.Stat(filepath.Join(p, Marker)); err != nil {
		t.Fatal("no marker in the copy")
	}
	for _, s := range Status(e) {
		if s.State != StateCopied {
			t.Fatalf("Status: %+v", s)
		}
	}
	// An older release's copy: one file differs.
	os.WriteFile(filepath.Join(p, "SKILL.md"), []byte("older"), 0o644)
	if s := inspect(e, "huginn"); s.State != StateStale {
		t.Fatalf("a stale copy: %+v", s)
	}
	if r := Refresh(e); !r.OK() || !reflect.DeepEqual(r.Done, []string{"huginn"}) {
		t.Fatalf("Refresh: %+v", r)
	}
	if s := inspect(e, "huginn"); s.State != StateCopied {
		t.Fatalf("after Refresh: %+v", s)
	}
	// Without the marker it is the person's: Refresh and Remove leave it.
	os.Remove(filepath.Join(p, Marker))
	if s := inspect(e, "huginn"); s.State != StateForeign {
		t.Fatalf("no marker: %+v", s)
	}
	if r := Refresh(e); len(r.Done) != 0 {
		t.Fatalf("Refresh touched a foreign directory: %+v", r)
	}
	r = Remove(e)
	if !r.OK() || !reflect.DeepEqual(r.Done, []string{"huginn-doctor"}) || len(r.Kept) != 1 {
		t.Fatalf("Remove: %+v", r)
	}
	if _, err := os.Stat(filepath.Join(p, "SKILL.md")); err != nil {
		t.Fatal("Remove deleted the person's directory")
	}
}

func TestDecide(t *testing.T) {
	cases := []struct {
		preset                           string
		installed, declined, claude, tty bool
		want                             Decision
	}{
		{"1", false, true, false, false, DecideInstall}, // the preset wins over everything
		{"yes", false, false, false, false, DecideInstall},
		{"0", true, false, true, true, DecideDeclined},
		{"n", false, false, true, true, DecideDeclined},
		{"", true, false, true, true, DecideRefresh}, // already there: refresh, ask nothing
		{"", true, true, false, false, DecideRefresh},
		{"", false, true, true, true, DecideDeclined},   // declined once: not asked again
		{"", false, false, false, true, DecideNoClaude}, // no Claude Code: nothing to install into
		{"", false, false, true, true, DecideAsk},
		{"", false, false, true, false, DecideNoTTY},   // nobody to ask: a no, not remembered
		{"maybe", false, false, true, true, DecideAsk}, // an unknown preset is no preset
	}
	for _, c := range cases {
		if got := Decide(c.preset, c.installed, c.declined, c.claude, c.tty); got != c.want {
			t.Errorf("Decide(%q, %v, %v, %v, %v) = %v, want %v", c.preset, c.installed, c.declined, c.claude, c.tty, got, c.want)
		}
	}
}

func TestClaudePresent(t *testing.T) {
	e := linuxEnv(t)
	notFound := func(string) (string, error) { return "", os.ErrNotExist }
	if ClaudePresent(e, notFound) {
		t.Fatal("present on a bare home")
	}
	if !ClaudePresent(e, func(string) (string, error) { return "/usr/bin/claude", nil }) {
		t.Fatal("claude on PATH not seen")
	}
	os.MkdirAll(e.ClaudeDir(), 0o755)
	if !ClaudePresent(e, notFound) {
		t.Fatal("~/.claude not seen")
	}
}
