package main

import (
	"io"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// `huginn uninstall --all` takes the `Host huginn` stanza out of ~/.ssh/config
// and nothing else. The installers append the stanza at the end of the file,
// so what the person (or another tool) adds later follows it: a Match block,
// a `Host=` block, a tool's marker comments. ssh ends a block at the next Host
// or Match line, and so must the uninstaller, or those lines go with it.
func TestUninstallAllTakesOnlyTheHuginnStanza(t *testing.T) {
	const theirs = "# my own config\nHost github.com\n  User git\n"
	cases := []struct{ name, after string }{
		{"nothing after it", ""},
		{"a Host block after it", "\nHost build01\n  User ci\n"},
		{"a Match block after it", "\nMatch host *.corp.example.com\n  ProxyJump bastion.corp.example.com\n  User alex\n  Include ~/.ssh/corp.d/*\n"},
		{"a commented Match block after it", "\n# the corp hosts\nMatch host *.corp.example.com\n  User alex\n"},
		{"a Match block, then Host *", "\nMatch host *.corp.example.com\n  User alex\n\nHost *\n  ServerAliveInterval 60\n"},
		{"keywords in other cases and the = form", "\nMATCH Host *.example.net\n  User ci\n\nHost=build01\n  User ci\n  HostName build01.example.net\n"},
		{"a tool's marked section", "\n# ------------START-TOOL------------\n# This section is managed by a tool. DO NOT EDIT.\nHost tool.*\n  User tool\n# ------------END-TOOL------------\n"},
		{"a comment at the end of the file", "\n# the end of my config\n"},
	}
	for _, c := range cases {
		t.Run(c.name, func(t *testing.T) {
			home := t.TempDir()
			cfg := filepath.Join(home, ".ssh", "config")
			if err := os.MkdirAll(filepath.Dir(cfg), 0o700); err != nil {
				t.Fatal(err)
			}
			// The stanza exactly as `huginn setup ssh` appends it.
			stanza := "\nHost huginn\n  HostName 192.0.2.10\n  User root\n  IdentityFile " + filepath.Join(home, ".ssh", "id_ed25519") +
				"\n  IdentitiesOnly yes\n  RequestTTY yes\n  ServerAliveInterval 30\n"
			if err := os.WriteFile(cfg, []byte(theirs+stanza+c.after), 0o600); err != nil {
				t.Fatal(err)
			}
			tl := &tally{comp: "ssh", home: home, log: io.Discard}
			sshStep(tl, home, true)
			b, err := os.ReadFile(cfg)
			if err != nil {
				t.Fatal(err)
			}
			if want := theirs + c.after; string(b) != want {
				t.Fatalf("~/.ssh/config after uninstall --all:\n%s\nwant:\n%s", b, want)
			}
			if len(tl.removed) == 0 || !strings.Contains(tl.removed[0], "'Host huginn' stanza") {
				t.Fatalf("the stanza was not reported removed: %q", tl.removed)
			}
		})
	}
}

// What is inside huginn's stanza still goes with it: a comment between its
// options, and its options in the `=` form.
func TestUninstallAllTakesTheWholeHuginnStanza(t *testing.T) {
	home := t.TempDir()
	cfg := filepath.Join(home, ".ssh", "config")
	if err := os.MkdirAll(filepath.Dir(cfg), 0o700); err != nil {
		t.Fatal(err)
	}
	body := "Host github.com\n  User git\n\nHost huginn\n  HostName=192.0.2.10\n  # my note about it\n  User root\n\nHost build01\n  User ci\n"
	if err := os.WriteFile(cfg, []byte(body), 0o600); err != nil {
		t.Fatal(err)
	}
	sshStep(&tally{comp: "ssh", home: home, log: io.Discard}, home, true)
	b, _ := os.ReadFile(cfg)
	if want := "Host github.com\n  User git\n\nHost build01\n  User ci\n"; string(b) != want {
		t.Fatalf("~/.ssh/config after uninstall --all:\n%s\nwant:\n%s", b, want)
	}
	if sshStanzaPresent(home) {
		t.Fatal("the plan still sees a 'Host huginn' stanza")
	}
}
