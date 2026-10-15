package install

import (
	"os"
	"path/filepath"
	"runtime"
	"testing"
)

func layout(t *testing.T) Layout {
	home := t.TempDir()
	l, err := LayoutFor("linux", func(k string) string { return map[string]string{"HOME": home}[k] })
	if err != nil {
		t.Fatal(err)
	}
	return l
}

func fakeBin(t *testing.T, body string) string {
	p := filepath.Join(t.TempDir(), "huginn")
	os.WriteFile(p, []byte(body), 0o755)
	return p
}

func TestInstallSwapAndPrune(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("unix layout")
	}
	l := layout(t)
	for _, v := range []string{"2.0.0", "2.1.0", "2.10.0"} {
		r, err := Install(l, fakeBin(t, "bin "+v), v)
		if err != nil {
			t.Fatal(v, err)
		}
		if !r.Changed {
			t.Fatalf("%s: nothing changed", v)
		}
		b, err := os.ReadFile(l.Binary())
		if err != nil || string(b) != "bin "+v {
			t.Fatalf("%s: the stable link runs %q (%v)", v, b, err)
		}
	}
	ents, _ := os.ReadDir(filepath.Join(l.Root, "versions"))
	var got []string
	for _, e := range ents {
		got = append(got, e.Name())
	}
	if len(got) != 2 || got[0] != "2.1.0" || got[1] != "2.10.0" {
		t.Fatalf("after prune: %v (want the current 2.10.0 and the rollback 2.1.0)", got)
	}
	if target, _ := os.Readlink(filepath.Join(l.Root, "current")); target != filepath.Join("versions", "2.10.0") {
		t.Fatalf("current -> %s", target)
	}
}

func TestReinstallSameIsNoop(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("unix layout")
	}
	l := layout(t)
	if _, err := Install(l, fakeBin(t, "x"), "2.0.0"); err != nil {
		t.Fatal(err)
	}
	// Running the INSTALLED copy's self-install again changes nothing.
	r, err := Install(l, filepath.Join(l.Root, "versions", "2.0.0", "huginn"), "2.0.0")
	if err != nil || r.Changed {
		t.Fatalf("changed=%v err=%v steps=%v", r.Changed, err, r.Steps)
	}
}

func TestForeignFileAtLinkRefused(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("unix layout")
	}
	l := layout(t)
	os.MkdirAll(filepath.Dir(l.Link), 0o755)
	os.WriteFile(l.Link, []byte("someone else's huginn"), 0o755)
	if _, err := Install(l, fakeBin(t, "x"), "2.0.0"); err == nil {
		t.Fatal("overwrote a file that is not huginn's link")
	}
	if b, _ := os.ReadFile(l.Link); string(b) != "someone else's huginn" {
		t.Fatal("the foreign file was touched")
	}
}

func TestBadVersionRefused(t *testing.T) {
	for _, v := range []string{"", "..", "../x", `a\b`} {
		if _, err := Install(layout(t), fakeBin(t, "x"), v); err == nil {
			t.Errorf("version %q accepted", v)
		}
	}
}

func TestVersionOrder(t *testing.T) {
	if !versionLess("2.9.0", "2.10.0") || versionLess("2.10.0", "2.9.0") || !versionLess("2.0.0-dev", "2.0.1") {
		t.Fatal("numeric ordering")
	}
}

func TestWindowsLayout(t *testing.T) {
	l, err := LayoutFor("windows", func(k string) string { return map[string]string{"LOCALAPPDATA": `C:\U\a\AppData\Local`}[k] })
	if err != nil || l.Binary() != filepath.Join(`C:\U\a\AppData\Local`, "huginn", "bin", "huginn.exe") {
		t.Fatalf("%v %v", l.Binary(), err)
	}
}
