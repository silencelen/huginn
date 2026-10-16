//go:build !windows

package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
)

// A verb the core does not own reaches the shell client with its arguments
// intact, its exit code comes back, and a client started by the core is
// never forwarded again (the loop guard).
func TestForwardToClient(t *testing.T) {
	if _, err := exec.LookPath("bash"); err != nil {
		t.Skip("no bash")
	}
	home := t.TempDir()
	t.Setenv("HOME", home)
	if _, ok := forwardToClient([]string{"ls"}); ok {
		t.Fatal("forwarded with no client installed")
	}
	out := filepath.Join(home, "out")
	os.MkdirAll(filepath.Join(home, ".huginn"), 0o755)
	stub := `huginn() { printf '%s|' "$@" > "$HOME/out"; printf 'via=%s' "$HUGINN_VIA_CORE" >> "$HOME/out"; return 7; }` + "\n"
	if err := os.WriteFile(filepath.Join(home, ".huginn", "huginn.sh"), []byte(stub), 0o644); err != nil {
		t.Fatal(err)
	}
	code, ok := forwardToClient([]string{"attach", "my session", "--x=$HOME", "*"})
	if !ok || code != 7 {
		t.Fatalf("forward: ok=%v code=%d, want ok and the client's 7", ok, code)
	}
	b, _ := os.ReadFile(out)
	if got, want := string(b), "attach|my session|--x=$HOME|*|via=1"; got != want {
		t.Fatalf("client saw %q, want %q", got, want)
	}
	t.Setenv(viaCore, "1")
	if _, ok := forwardToClient([]string{"ls"}); ok {
		t.Fatal("a client the core started was forwarded again")
	}
	if !strings.Contains(clientScript(), ".huginn") {
		t.Fatal("clientScript did not find ~/.huginn/huginn.sh")
	}
}
