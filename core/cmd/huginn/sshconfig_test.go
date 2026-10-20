package main

import (
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// A home with a space in its path, as a Windows profile often has
// (C:\Users\First Last): `huginn setup ssh` writes a stanza ssh can read, and
// the rest of the person's ssh config keeps working.
func TestSetupSSHQuotesAKeyPathWithASpace(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the stub ssh is a shell script")
	}
	realSSH, _ := exec.LookPath("ssh") // before the stub shadows it
	home := filepath.Join(t.TempDir(), "First Last")
	sshDir := filepath.Join(home, ".ssh")
	if err := os.MkdirAll(sshDir, 0o700); err != nil {
		t.Fatal(err)
	}
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, ".config"))
	t.Setenv("XDG_STATE_HOME", filepath.Join(home, ".local", "state"))
	t.Setenv("HUGINN_HOST", "huginn")
	t.Setenv("HUGINN_SETUP", "")
	key := filepath.Join(sshDir, "id_ed25519")
	os.WriteFile(key, []byte("not a real key\n"), 0o600)
	os.WriteFile(key+".pub", []byte("ssh-ed25519 AAAA alex@example.net\n"), 0o644)
	cfg := filepath.Join(sshDir, "config")
	os.WriteFile(cfg, []byte("Host git.example.net\n  User git\n"), 0o600)
	stubSSH(t, "echo 'Permission denied (publickey).' >&2; exit 255")
	quiet(t)

	setup([]string{"ssh", "--host", "192.0.2.10"})
	b, _ := os.ReadFile(cfg)
	if want := "\n  IdentityFile \"" + key + "\"\n"; !strings.Contains(string(b), want) {
		t.Fatalf("the stanza's key line is not the quoted path %q:\n%s", key, b)
	}
	if got := stanzaKey(cfg, "huginn", home); got != key {
		t.Fatalf("setup reads its own entry's key back as %q, want %q", got, key)
	}
	if realSSH == "" {
		t.Skip("no ssh here to parse the result with")
	}
	// ssh's own parser, which nothing here can talk it out of: -G prints the
	// configuration it would use and connects nowhere.
	for _, h := range []string{"git.example.net", "huginn"} {
		out, err := exec.Command(realSSH, "-F", cfg, "-G", h).CombinedOutput()
		if err != nil {
			t.Fatalf("ssh cannot read the config for %s any more: %v\n%s", h, err, out)
		}
		if h == "huginn" && !strings.Contains(string(out), "\nidentityfile "+key+"\n") {
			t.Fatalf("ssh reads huginn's key as something other than %q:\n%s", key, out)
		}
	}
}

// The readers take a quoted value whole, and an unquoted one as before.
func TestStanzaKeyReadsAQuotedPath(t *testing.T) {
	home := filepath.Join(t.TempDir(), "First Last")
	cfg := filepath.Join(t.TempDir(), "config")
	for body, want := range map[string]string{
		"Host huginn\n  IdentityFile \"" + home + "/.ssh/id_ed25519\"\n": home + "/.ssh/id_ed25519",
		"Host huginn\n  IdentityFile \"~/.ssh/id_ed25519\"\n":            filepath.Join(home, ".ssh/id_ed25519"),
		"Host huginn\r\n  identityfile \"C:\\Users\\First Last\\k\"\r\n": `C:\Users\First Last\k`,
		"Host huginn\n  IdentityFile /home/alex/.ssh/id_ed25519\n":       "/home/alex/.ssh/id_ed25519",
	} {
		os.WriteFile(cfg, []byte(body), 0o600)
		if got := stanzaKey(cfg, "huginn", home); got != want {
			t.Errorf("stanzaKey of %q = %q, want %q", body, got, want)
		}
	}
}

// `huginn uninstall --all` still recognises huginn's own key when its path is
// quoted, and removes it with the stanza.
func TestUninstallAllTakesAQuotedHuginnKey(t *testing.T) {
	home := filepath.Join(t.TempDir(), "First Last")
	sshDir := filepath.Join(home, ".ssh")
	if err := os.MkdirAll(sshDir, 0o700); err != nil {
		t.Fatal(err)
	}
	key := filepath.Join(sshDir, "id_ed25519_huginn")
	os.WriteFile(key, []byte("not a real key\n"), 0o600)
	os.WriteFile(key+".pub", []byte("ssh-ed25519 AAAA huginn@example.net\n"), 0o644)
	cfg := filepath.Join(sshDir, "config")
	os.WriteFile(cfg, []byte("Host git.example.net\n  User git\n\nHost huginn\n  HostName 192.0.2.10\n  IdentityFile \""+key+"\"\n"), 0o600)

	tl := &tally{}
	sshStep(tl, home, true)
	if fileExists(key) || fileExists(key+".pub") {
		t.Fatalf("huginn's own key was left behind: removed %q, kept %q", tl.removed, tl.kept)
	}
	if b, _ := os.ReadFile(cfg); strings.Contains(string(b), "huginn") || !strings.Contains(string(b), "Host git.example.net") {
		t.Fatalf("the config after --all:\n%s", b)
	}
}
