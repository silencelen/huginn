package main

import (
	"encoding/json"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"

	"github.com/silencelen/huginn/core/internal/daemon"
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
	sshd, err := net.Listen("tcp", "[::1]:0")
	if err != nil {
		t.Skip("no IPv6 loopback here: ", err)
	}
	defer sshd.Close()
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

// fakeCoreListing serves `/appd/v1/status` listing addrs, on the socket
// `call` dials, under a fresh HOME.
func fakeCoreListing(t *testing.T, addrs []string) {
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
	body, _ := json.Marshal(map[string]any{"addresses": addrs})
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.URL.Path != "/appd/v1/status" {
			http.NotFound(w, r)
			return
		}
		w.Header().Set("Content-Type", "application/json")
		w.Write(body)
	})}
	go srv.Serve(ln)
	t.Cleanup(func() { srv.Close() })
}
