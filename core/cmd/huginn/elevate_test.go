package main

import (
	"encoding/base64"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf16"
)

func TestEncodePS(t *testing.T) {
	b, err := base64.StdEncoding.DecodeString(encodePS("& 'C:\\Program Files\\x' 'a''b'"))
	if err != nil || len(b)%2 != 0 {
		t.Fatal(err)
	}
	u := make([]uint16, len(b)/2)
	for i := range u {
		u[i] = uint16(b[2*i]) | uint16(b[2*i+1])<<8
	}
	if got := string(utf16.Decode(u)); got != "& 'C:\\Program Files\\x' 'a''b'" {
		t.Fatalf("round trip = %q", got)
	}
}

// The elevated step's script, run for real where pwsh exists: both streams
// reach the log, a quote in an argument survives, and the exit code comes
// back as the script's own.
func TestElevatedInnerRuns(t *testing.T) {
	pwsh, err := exec.LookPath("pwsh")
	if err != nil {
		t.Skip("no pwsh")
	}
	log := filepath.Join(t.TempDir(), "it's.log")
	inner := elevatedInner("sh", []string{"-c", "echo out; echo \"err it's\" >&2; exit 3"}, log)
	err = exec.Command(pwsh, "-NoProfile", "-NonInteractive", "-EncodedCommand", encodePS(inner)).Run()
	ee, ok := err.(*exec.ExitError)
	if !ok || ee.ExitCode() != 3 {
		t.Fatalf("exit = %v, want 3", err)
	}
	b, _ := os.ReadFile(log)
	got := strings.TrimPrefix(string(b), "\ufeff")
	if !strings.Contains(got, "out") || !strings.Contains(got, "err it's") || strings.Contains(got, "\x00") {
		t.Fatalf("log = %q", got)
	}
}
