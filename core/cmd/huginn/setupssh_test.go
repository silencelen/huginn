package main

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// `huginn setup ssh` points `Host huginn` at another of huginn's own addresses
// when the one it names does not carry ssh from here (seen 2026-10-17),
// and never edits a stanza that names something else.
func TestRepointStanzaOnlyForHuginnsOwnAddress(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_STATE_HOME", t.TempDir())
	cfg := filepath.Join(t.TempDir(), "config")
	body := "Host other\n  HostName 192.168.1.20\n\nHost huginn\n  HostName 192.168.1.20\n  User root\n"
	os.WriteFile(cfg, []byte(body), 0o600)
	huginns := []string{"192.168.1.20", "192.168.10.20"}

	if !repointStanza(cfg, "huginn", "192.168.1.20", "192.168.10.20", huginns) {
		t.Fatal("a stanza naming one of huginn's own addresses was not repointed")
	}
	b, _ := os.ReadFile(cfg)
	got := string(b)
	if !strings.Contains(got, "Host huginn\n  HostName 192.168.10.20\n") {
		t.Fatalf("the huginn stanza was not changed:\n%s", got)
	}
	if !strings.Contains(got, "Host other\n  HostName 192.168.1.20\n") {
		t.Fatalf("another stanza was touched:\n%s", got)
	}

	os.WriteFile(cfg, []byte("Host huginn\n  HostName my-jump-box\n"), 0o600)
	if repointStanza(cfg, "huginn", "my-jump-box", "192.168.10.20", huginns) {
		t.Fatal("a stanza naming a host that is not huginn was edited")
	}
	if b, _ := os.ReadFile(cfg); string(b) != "Host huginn\n  HostName my-jump-box\n" {
		t.Fatalf("the person's own stanza changed: %q", string(b))
	}
}
