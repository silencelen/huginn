package clientkit

import (
	"bytes"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The embedded client is client/ as committed. core/build.sh refreshes the
// copy; this catches a commit that changed one and not the other.
func TestEmbeddedMatchesClient(t *testing.T) {
	for emb, src := range map[string]string{"files/huginn.sh.txt": "../../../client/huginn.sh", "files/huginn.ps1.txt": "../../../client/huginn.ps1",
		"files/huginn-local.txt": "../../../client/huginn-local", "files/huginn-llm-shim.txt": "../../../client/huginn-llm-shim"} {
		a, err := os.ReadFile(emb)
		if err != nil {
			t.Fatal(err)
		}
		b, err := os.ReadFile(src)
		if err != nil {
			t.Fatal(err)
		}
		if !bytes.Equal(a, bytes.ReplaceAll(b, []byte("\r"), nil)) {
			t.Errorf("%s differs from %s: run `core/build.sh` (or copy it) and commit both", emb, src)
		}
	}
}

func TestInstallAndRemoveUnix(t *testing.T) {
	home := t.TempDir()
	e := Env{GOOS: "linux", Home: home, Shell: "/bin/zsh"}
	os.WriteFile(filepath.Join(home, ".profile"), []byte("export X=1"), 0o644) // no trailing newline
	steps, err := Install(e)
	if err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(e.Script()); !bytes.Equal(got, shClient) {
		t.Fatal("client not written")
	}
	for _, f := range []string{".bashrc", ".zshrc", ".profile"} {
		b, _ := os.ReadFile(filepath.Join(home, f))
		n := 0
		for _, l := range strings.Split(string(b), "\n") {
			if strings.Contains(l, ".huginn/huginn.sh") {
				n++
			}
		}
		if n != 1 {
			t.Fatalf("%s not wired once: %q", f, b)
		}
	}
	if b, _ := os.ReadFile(filepath.Join(home, ".profile")); string(b) != "export X=1\n"+ProfileLine+"\n" {
		t.Fatalf(".profile = %q", b)
	}
	if len(steps) != 4 {
		t.Fatalf("steps = %q", steps)
	}
	// Idempotent: nothing to do the second time.
	if steps, _ := Install(e); len(steps) != 0 {
		t.Fatalf("second install did %q", steps)
	}
	// A hand-written line survives removal and is reported.
	os.WriteFile(filepath.Join(home, ".zshrc"), []byte("alias a=b\nsource ~/.huginn/huginn.sh  # mine\n"), 0o644)
	removed, left := Remove(e)
	if _, err := os.Stat(e.Dir()); !os.IsNotExist(err) {
		t.Fatal("~/.huginn still there")
	}
	if b, _ := os.ReadFile(filepath.Join(home, ".bashrc")); len(b) != 0 {
		t.Fatalf(".bashrc = %q", b)
	}
	if b, _ := os.ReadFile(filepath.Join(home, ".profile")); string(b) != "export X=1\n" {
		t.Fatalf(".profile = %q", b)
	}
	if b, _ := os.ReadFile(filepath.Join(home, ".zshrc")); !strings.Contains(string(b), "# mine") {
		t.Fatal("hand-written line removed")
	}
	if len(removed) != 3 || len(left) != 1 {
		t.Fatalf("removed %q left %q", removed, left)
	}

	// The installer's line AND a hand-written one in the same file: the
	// first goes, the second is still reported.
	Install(e)
	rc := filepath.Join(home, ".bashrc")
	b, _ := os.ReadFile(rc)
	os.WriteFile(rc, append(b, []byte("[ -f ~/.huginn/huginn.sh ] && . ~/.huginn/huginn.sh  # mine\n")...), 0o644)
	removed, left = Remove(e)
	if b, _ := os.ReadFile(rc); strings.Contains(string(b), RCLine+"\n") || !strings.Contains(string(b), "# mine") {
		t.Fatalf(".bashrc after remove = %q", b)
	}
	found := false
	for _, l := range left {
		found = found || strings.Contains(l, "hand-edited huginn line in "+rc)
	}
	if !found {
		t.Fatalf("hand-written line beside the installer's not reported: removed %q left %q", removed, left)
	}
}

func TestInstallWindowsProfiles(t *testing.T) {
	home := t.TempDir()
	p51 := filepath.Join(home, "Docs", "WindowsPowerShell", "Microsoft.PowerShell_profile.ps1")
	p7 := filepath.Join(home, "Docs", "PowerShell", "Microsoft.PowerShell_profile.ps1")
	e := Env{GOOS: "windows", Home: home, PSProfiles: []string{p51, p7}}
	if _, err := Install(e); err != nil {
		t.Fatal(err)
	}
	if got, _ := os.ReadFile(e.Script()); !bytes.Equal(got, ps1Client) || !strings.HasSuffix(e.Script(), "huginn.ps1") {
		t.Fatal("ps1 client not written")
	}
	if b, _ := os.ReadFile(p51); string(b) != PSLine+"\r\n" {
		t.Fatalf("5.1 profile = %q", b)
	}
	if _, err := os.Stat(p7); !os.IsNotExist(err) {
		t.Fatal("created a PowerShell 7 profile that did not exist")
	}
	removed, _ := Remove(e)
	if b, _ := os.ReadFile(p51); len(b) != 0 || len(removed) != 2 {
		t.Fatalf("after remove: profile %q removed %q", b, removed)
	}
}

// A satellite is refreshed where it exists and never created where it does not.
func TestSatellites(t *testing.T) {
	home := t.TempDir()
	e := Env{GOOS: "linux", Home: home}
	os.MkdirAll(e.Dir(), 0o755)
	os.WriteFile(filepath.Join(e.Dir(), "huginn-local"), []byte("old"), 0o755)
	if _, err := Install(e); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(filepath.Join(e.Dir(), "huginn-local")); !bytes.Equal(b, localManager) {
		t.Fatal("huginn-local not refreshed")
	}
	if _, err := os.Stat(filepath.Join(e.Dir(), "huginn-llm-shim")); !os.IsNotExist(err) {
		t.Fatal("huginn-llm-shim created on a machine that never had it")
	}
}

// The version the carried client declares is read the same way from both
// scripts, and it is the one client/huginn.sh carries (core/build.sh holds a
// release to the same constant).
func TestScriptVersion(t *testing.T) {
	if got := ScriptVersion([]byte("# x\nHUGINN_VERSION='2.6.7'\n")); got != "2.6.7" {
		t.Fatalf("sh: %q", got)
	}
	if got := ScriptVersion([]byte("# x\r\n$script:HUGINN_VERSION = '2.6.7'\r\n")); got != "2.6.7" {
		t.Fatalf("ps1: %q", got)
	}
	if got := ScriptVersion([]byte("nothing")); got != "" {
		t.Fatalf("absent: %q", got)
	}
	sh, ps := Version("linux"), Version("windows")
	if sh == "" || sh != ps {
		t.Fatalf("the carried clients disagree or declare nothing: sh %q ps1 %q", sh, ps)
	}
}
