package main

import (
	"bytes"
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/ui"
)

// The edit `huginn setup ssh` makes once ssh has shown that another of
// huginn's addresses is the same machine (sshFallback): it changes only a
// stanza that names huginn, never one that names something else.
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

// injected is what a hostile huginn can list as one of its addresses: Go reads
// everything after '%' as an IPv6 zone, so this DIALS (to ::1), and spliced
// into a HostName line it gives `Host huginn` a ProxyCommand (security scan
// F12, 2026-10-18).
const injected = "::1%x\nProxyCommand sh -c 'touch /tmp/huginn-was-here'"

// The repoint writes only a plain host, whoever asks: a line break, a space,
// a zone or a token there would add to or break the person's ssh config.
func TestRepointStanzaRefusesAnythingButAPlainHost(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_STATE_HOME", t.TempDir())
	cfg := filepath.Join(t.TempDir(), "config")
	body := "Host huginn\n  HostName 192.168.1.20\n  User root\n"
	for _, to := range []string{
		injected,
		"192.0.2.20\nProxyCommand sh -c id",
		"192.0.2.20\r\nProxyCommand sh -c id",
		"192.0.2.20\rProxyCommand sh -c id",
		"192.0.2.20 ProxyCommand=id",
		"2001:db8::1%eth0",
		"%h.example.net",
		"",
	} {
		os.WriteFile(cfg, []byte(body), 0o600)
		if repointStanza(cfg, "huginn", "192.168.1.20", to, []string{"192.168.1.20", to}) {
			t.Errorf("repointed the stanza to %q", to)
		}
		if b, _ := os.ReadFile(cfg); string(b) != body {
			t.Errorf("%q changed the file:\n%s", to, b)
		}
	}
}

// sshAddress takes only plain addresses from huginn's list: the injected one
// answers on the port like any other, so the dial alone let it through.
func TestSSHAddressSkipsAnAddressThatIsNotPlain(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the fake core below listens on a unix socket path")
	}
	fakeSSHD(t)
	fakeCoreListing(t, []string{injected, "::1"})
	alt, all := sshAddress("192.0.2.1")
	if alt != "::1" {
		t.Fatalf("sshAddress chose %q; want the plain ::1", alt)
	}
	if len(all) != 1 || all[0] != "::1" {
		t.Fatalf("sshAddress passed on %q; an address that is not plain must not leave it (it is printed and matched)", all)
	}
}

func TestPlainHost(t *testing.T) {
	long := strings.Repeat("a", 63)
	for s, want := range map[string]bool{
		"192.168.1.20":                        true,
		"192.0.2.10":                          true,
		"100.101.102.103":                     true,
		"2001:db8::1":                         true,
		"::1":                                 true,
		"huginn":                              true,
		"huginn.example.net":                  true,
		"HUGINN-2.example.NET":                true,
		"xn--bcher-kva.example":               true,
		long + ".example":                     true,
		long + "a.example":                    false, // a 64-byte label
		strings.Repeat(long+".", 4) + "net":   false, // over 253 in all
		injected:                              false,
		"2001:db8::1%eth0":                    false, // any zone at all
		"fe80::1%25en0":                       false,
		"[2001:db8::1]":                       false,
		"192.168.1.20\nProxyCommand sh -c id": false,
		"192.168.1.20\r":                      false,
		"192.168.1.20 x":                      false,
		"192.168.1.20\t":                      false,
		"192.168.1.20#x":                      false,
		"host=x":                              false,
		`"huginn"`:                            false,
		"'huginn'":                            false,
		"%h":                                  false,
		"huginn%h":                            false,
		"huginn\x1b[2J":                       false,
		"hügin":                               false,
		"under_score":                         false,
		"-huginn":                             false,
		"huginn-":                             false,
		"huginn..example":                     false,
		".huginn":                             false,
		"huginn.":                             false,
		"1.2.3":                               false, // a number to a resolver, not a name
		"192.0.002.010":                       false,
		"999.1.1.1":                           false,
		"":                                    false,
	} {
		if got := plainHost(s); got != want {
			t.Errorf("plainHost(%q) = %v, want %v", s, got, want)
		}
	}
}

// The repoint replaces the HostName VALUE, even where that value also
// appears inside the keyword.
func TestRepointStanzaReplacesTheValueNotTheKeyword(t *testing.T) {
	t.Setenv("HOME", t.TempDir())
	t.Setenv("XDG_CONFIG_HOME", t.TempDir())
	t.Setenv("XDG_STATE_HOME", t.TempDir())
	cfg := filepath.Join(t.TempDir(), "config")
	os.WriteFile(cfg, []byte("Host huginn\n  hostname name\n"), 0o600)
	if !repointStanza(cfg, "huginn", "name", "192.0.2.20", []string{"name", "192.0.2.20"}) {
		t.Fatal("not repointed")
	}
	if b, _ := os.ReadFile(cfg); string(b) != "Host huginn\n  hostname 192.0.2.20\n" {
		t.Fatalf("got %q", string(b))
	}
}

// fakeSSHD stands a listener in for sshd on [::1], so "::1" answers on the
// port sshAddress knocks on.
func fakeSSHD(t *testing.T) {
	t.Helper()
	sshd, err := net.Listen("tcp", "[::1]:0")
	if err != nil {
		t.Skip("no IPv6 loopback here: ", err)
	}
	t.Cleanup(func() { sshd.Close() })
	go func() {
		for {
			c, err := sshd.Accept()
			if err != nil {
				return
			}
			c.Close()
		}
	}()
	_, port, _ := net.SplitHostPort(sshd.Addr().String())
	was := sshPort
	sshPort = port
	t.Cleanup(func() { sshPort = was })
}

// A listed address that takes connections on port 22 is not the same machine
// because huginn says so: `Host huginn` moves to it only when ssh finds there
// the host key known for the address it names now (security review,
// 2026-10-18). On the old code the stanza moved on the list's word alone.
func TestSSHFallbackMovesTheEntryOnlyForTheSameHostKey(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the fake core below listens on a unix socket path")
	}
	fakeSSHD(t)
	// 127.0.0.1 refuses at once: the fake sshd listens on ::1 only.
	fakeCoreListing(t, []string{"127.0.0.1", "::1"})
	cfg := filepath.Join(t.TempDir(), "config")
	body := "Host huginn\n  HostName 127.0.0.1\n  User root\n"
	type ask struct{ alias, old, addr string }
	for _, sameKey := range []bool{false, true} {
		os.WriteFile(cfg, []byte(body), 0o600)
		var asked []ask
		was := sshSameHost
		sshSameHost = func(alias, old, addr string) bool {
			asked = append(asked, ask{alias, old, addr})
			return sameKey
		}
		var out bytes.Buffer
		moved := sshFallback(ui.For(&out, false, false), cfg, "huginn", "127.0.0.1", false)
		sshSameHost = was
		b, _ := os.ReadFile(cfg)
		if !sameKey {
			if moved != "" || string(b) != body {
				t.Fatalf("moved to %q, whose host key is not the one known for 127.0.0.1:\n%s", moved, b)
			}
			if !strings.Contains(out.String(), "huginn setup ssh --host ::1") || strings.Contains(out.String(), "same huginn") {
				t.Fatalf("want the addresses and the command that moves the entry, and no word that it is the same machine:\n%s", out.String())
			}
		} else if moved != "::1" || !strings.Contains(string(b), "Host huginn\n  HostName ::1\n") {
			t.Fatalf("the same host key, and the entry did not move (%q):\n%s", moved, b)
		}
		if len(asked) != 1 || asked[0] != (ask{"huginn", "127.0.0.1", "::1"}) {
			t.Fatalf("sameKey=%v: ssh was asked %+v; want once, for ::1 under 127.0.0.1's key", sameKey, asked)
		}
	}

	// A stanza that names something other than huginn is the person's own:
	// ssh is not even asked.
	os.WriteFile(cfg, []byte("Host huginn\n  HostName my-jump-box\n"), 0o600)
	was := sshSameHost
	sshSameHost = func(string, string, string) bool {
		t.Error("ssh asked about a stanza that is not huginn's")
		return true
	}
	defer func() { sshSameHost = was }()
	var out bytes.Buffer
	if moved := sshFallback(ui.For(&out, false, false), cfg, "huginn", "my-jump-box", false); moved != "" {
		t.Fatalf("moved the person's own stanza to %q", moved)
	}
	if b, _ := os.ReadFile(cfg); string(b) != "Host huginn\n  HostName my-jump-box\n" {
		t.Fatalf("the person's own stanza changed: %q", b)
	}
}

// An entry this setup run just wrote, from the paired core's address, follows
// huginn's list without a host-key proof: nothing of the person's is moved, and
// the install on a client whose VLAN reaches ssh on another address stays one
// step (owner decision, 2026-10-19). A stanza that names something other than
// huginn is still never moved.
func TestSSHFallbackMovesItsOwnNewEntryWithoutAKeyProof(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the fake core below listens on a unix socket path")
	}
	fakeSSHD(t)
	fakeCoreListing(t, []string{"127.0.0.1", "::1"})
	cfg := filepath.Join(t.TempDir(), "config")
	os.WriteFile(cfg, []byte("Host huginn\n  HostName 127.0.0.1\n  User root\n"), 0o600)
	was := sshSameHost
	sshSameHost = func(string, string, string) bool {
		t.Error("its own new entry needs no host-key proof")
		return false
	}
	defer func() { sshSameHost = was }()
	var out bytes.Buffer
	moved := sshFallback(ui.For(&out, false, false), cfg, "huginn", "127.0.0.1", true)
	b, _ := os.ReadFile(cfg)
	if moved != "::1" || !strings.Contains(string(b), "Host huginn\n  HostName ::1\n") {
		t.Fatalf("this run's own entry did not follow huginn's list (%q):\n%s\n%s", moved, b, out.String())
	}
	os.WriteFile(cfg, []byte("Host huginn\n  HostName my-jump-box\n"), 0o600)
	if moved := sshFallback(ui.For(&out, false, false), cfg, "huginn", "my-jump-box", true); moved != "" {
		t.Fatalf("moved a stanza that does not name huginn to %q", moved)
	}
}

// stubSSH puts an `ssh` on PATH that records its arguments, one per line, in
// the returned file, then runs script.
func stubSSH(t *testing.T, script string) string {
	t.Helper()
	dir := t.TempDir()
	argv := filepath.Join(dir, "argv")
	stub := "#!/bin/sh\nprintf '%s\\n' \"$@\" > '" + argv + "'\n" + script + "\n"
	if err := os.WriteFile(filepath.Join(dir, "ssh"), []byte(stub), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	return argv
}

// sshSameHost is ssh's own verdict: its exit status, under strict checking
// of the key known for the old address. Nothing ssh prints counts, since a
// far end can make it print anything (a bad version line, a banner).
func TestSSHSameHostIsSSHsExitStatus(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the stub ssh is a shell script")
	}
	argv := stubSSH(t, "echo 'Host key verification failed.' >&2; exit 255")
	if sshSameHost("huginn", "192.168.1.20", "192.0.2.30") {
		t.Fatal("a refused host key read as the same machine")
	}
	b, _ := os.ReadFile(argv)
	args := strings.Split(strings.TrimSuffix(string(b), "\n"), "\n")
	opts := map[string]bool{}
	for i := 0; i+1 < len(args); i++ {
		if args[i] == "-o" {
			opts[args[i+1]] = true
		}
	}
	for _, want := range []string{"BatchMode=yes", "StrictHostKeyChecking=yes", "HostKeyAlias=192.168.1.20", "HostName=192.0.2.30", "UpdateHostKeys=no"} {
		if !opts[want] {
			t.Errorf("ssh was not given -o %s: %q", want, args)
		}
	}
	if n := len(args); n < 2 || args[n-2] != "huginn" || args[n-1] != "true" {
		t.Errorf("want `huginn true` last: %q", args)
	}

	stubSSH(t, "echo 'root@192.0.2.30: Permission denied (publickey).' >&2; echo 'Host key for 192.168.1.20 matches' >&2; exit 255")
	if sshSameHost("huginn", "192.168.1.20", "192.0.2.30") {
		t.Fatal("text on stderr was taken as a verdict")
	}
	stubSSH(t, "exit 0")
	if !sshSameHost("huginn", "192.168.1.20", "192.0.2.30") {
		t.Fatal("ssh got in under the old address's key, and it did not count")
	}

	argv = stubSSH(t, "exit 0")
	for _, bad := range [][2]string{{"192.168.1.20", injected}, {"%h.example.net", "192.0.2.30"}} {
		if sshSameHost("huginn", bad[0], bad[1]) {
			t.Errorf("%q: a host that is not plain was taken", bad)
		}
	}
	if _, err := os.Stat(argv); err == nil {
		t.Fatal("ssh was run with a host that is not plain")
	}
}

// --host moves an existing entry: the person named the address, which is the
// consent sshFallback asks for. A host that is not plain is refused.
func TestSetupSSHHostMovesAnExistingEntry(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the stub ssh is a shell script")
	}
	home := t.TempDir()
	t.Setenv("HOME", home)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(home, ".config"))
	t.Setenv("XDG_STATE_HOME", filepath.Join(home, ".local", "state"))
	t.Setenv("HUGINN_HOST", "huginn")
	t.Setenv("HUGINN_SETUP", "")
	stubSSH(t, "echo 'Permission denied (publickey).' >&2; exit 255")
	cfg := filepath.Join(home, ".ssh", "config")
	os.MkdirAll(filepath.Dir(cfg), 0o700)
	body := "Host other\n  HostName 192.168.1.20\n\nHost huginn\n  HostName 192.168.1.20\n  User root\n"
	os.WriteFile(cfg, []byte(body), 0o600)
	quiet(t)

	if rc := setup([]string{"ssh", "--host", "192.168.1.20\nProxyCommand id"}); rc != 2 {
		t.Fatalf("a host that is not plain: rc %d", rc)
	}
	if b, _ := os.ReadFile(cfg); string(b) != body {
		t.Fatalf("a host that is not plain changed the file:\n%s", b)
	}
	setup([]string{"ssh", "--host", "192.0.2.30"})
	b, _ := os.ReadFile(cfg)
	if want := "Host other\n  HostName 192.168.1.20\n\nHost huginn\n  HostName 192.0.2.30\n  User root\n"; string(b) != want {
		t.Fatalf("got\n%s\nwant\n%s", b, want)
	}
}

// quiet sends what the code under test prints to stderr nowhere, for the
// rest of the test.
func quiet(t *testing.T) {
	t.Helper()
	null, err := os.OpenFile(os.DevNull, os.O_WRONLY, 0)
	if err != nil {
		t.Fatal(err)
	}
	was := os.Stderr
	os.Stderr = null
	t.Cleanup(func() { os.Stderr = was; null.Close() })
}

// fakeCoreListing serves `/appd/v1/status` listing addrs, on the socket
// `call` dials, under a fresh HOME.
func fakeCoreListing(t *testing.T, addrs []string) {
	t.Helper()
	body, _ := json.Marshal(map[string]any{"addresses": addrs})
	fakeCore(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/appd/v1/status" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write(body)
	}))
}

// fakeCore serves h on the socket `call` dials, under a fresh HOME.
func fakeCore(t *testing.T, h http.Handler) {
	t.Helper()
	dir := t.TempDir()
	t.Setenv("HOME", dir)
	t.Setenv("XDG_CONFIG_HOME", filepath.Join(dir, "config"))
	t.Setenv("XDG_STATE_HOME", filepath.Join(dir, "state"))
	t.Setenv("XDG_RUNTIME_DIR", filepath.Join(dir, "run"))
	sock := daemon.SocketPath(paths())
	if err := os.MkdirAll(filepath.Dir(sock), 0o700); err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: h}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
}
