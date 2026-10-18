package localtier

import (
	"os"
	"runtime"
	"testing"
)

func TestSeed(t *testing.T) {
	dir := t.TempDir()
	f, err := Seed(dir, " tok-1\n")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(f)
	if string(b) != "tok-1" {
		t.Fatalf("seed = %q", b)
	}
	if runtime.GOOS != "windows" {
		if fi, _ := os.Stat(f); fi.Mode().Perm() != 0o600 {
			t.Fatalf("mode %v", fi.Mode().Perm())
		}
	}
	fi1, _ := os.Stat(f)
	if _, err := Seed(dir, "tok-1"); err != nil {
		t.Fatal(err)
	}
	if fi2, _ := os.Stat(f); !fi2.ModTime().Equal(fi1.ModTime()) {
		t.Fatal("an unchanged token was rewritten")
	}
	Seed(dir, "tok-2") // a rotated token reaches the stale seed
	if b, _ := os.ReadFile(f); string(b) != "tok-2" {
		t.Fatalf("after rotation = %q", b)
	}
	if _, err := Seed(dir, "  "); err == nil {
		t.Fatal("an empty token was written")
	}
}

// The token copy is locked every time it is seeded, and when it is found
// already in place (a copy written before the lock): on Windows 0600 meant
// nothing and it inherited "Users: read" (security review, 2026-10-17).
func TestSeedLocksTheTokenFile(t *testing.T) {
	var locked []string
	old := lock
	lock = func(p string) { locked = append(locked, p) }
	defer func() { lock = old }()
	dir := t.TempDir()
	f, err := Seed(dir, "tok-1")
	if err != nil {
		t.Fatal(err)
	}
	if len(locked) != 1 || locked[0] != f {
		t.Fatalf("a new token file was not locked: %v", locked)
	}
	if _, err := Seed(dir, "tok-1"); err != nil {
		t.Fatal(err)
	}
	if len(locked) != 2 || locked[1] != f {
		t.Fatalf("an unchanged token file was not locked again: %v", locked)
	}
}
