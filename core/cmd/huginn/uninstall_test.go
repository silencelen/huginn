package main

import (
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
	"time"
)

// The uninstall log lives in the shared /tmp, where another account can make
// a name first. On the old code the name was the time alone, opened to create
// or truncate: a link planted there sent the log into a file of the person's
// own, which it truncated first.
func TestUninstallLogIsNotWrittenThroughAPlantedLink(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("making a symlink needs a privilege on Windows, and its temp dir is per user")
	}
	dir := t.TempDir()
	victim := filepath.Join(t.TempDir(), "bashrc")
	const theirs = "the person's own file\n"
	if err := os.WriteFile(victim, []byte(theirs), 0o600); err != nil {
		t.Fatal(err)
	}
	now := time.Now()
	// Every name the old code could have picked around now, and the same with
	// this code's prefix and suffix around each: all of them links.
	var planted []string
	for d := -2 * time.Second; d <= 2*time.Second; d += time.Second {
		stamp := now.Add(d).Format("20060102-150405")
		for _, name := range []string{"huginn-uninstall-" + stamp + ".log", "huginn-uninstall-" + stamp + "-.log"} {
			p := filepath.Join(dir, name)
			if err := os.Symlink(victim, p); err != nil {
				t.Fatal(err)
			}
			planted = append(planted, p)
		}
	}

	f, err := openUninstallLog(dir, now)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := f.WriteString("removed  ~/.huginn\n"); err != nil {
		t.Fatal(err)
	}
	f.Close()

	if b, _ := os.ReadFile(victim); string(b) != theirs {
		t.Fatalf("the log went through a planted link into another file: %q", b)
	}
	fi, err := os.Lstat(f.Name())
	if err != nil {
		t.Fatal(err)
	}
	if !fi.Mode().IsRegular() || filepath.Dir(f.Name()) != dir {
		t.Fatalf("the log is not a file of its own in %s: %s %v", dir, f.Name(), fi.Mode())
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("the log is %v, not 0600", fi.Mode().Perm())
	}
	for _, p := range planted {
		if p == f.Name() {
			t.Fatalf("the log took a planted name: %s", p)
		}
	}
	if b, _ := os.ReadFile(f.Name()); !strings.Contains(string(b), "removed") {
		t.Fatalf("the log has %q", b)
	}

	// Two in the same second are two files.
	g, err := openUninstallLog(dir, now)
	if err != nil {
		t.Fatal(err)
	}
	g.Close()
	if g.Name() == f.Name() {
		t.Fatal("two logs in the same second share a name")
	}
}
