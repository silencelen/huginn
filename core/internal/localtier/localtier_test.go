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
