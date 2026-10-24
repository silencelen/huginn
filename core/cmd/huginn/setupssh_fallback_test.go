package main

// Where `Host huginn` goes when the address it names does not answer ssh
// from here, driven through `huginn setup ssh` itself. huginn's address list
// says where the server is on its own networks, not what answers those
// addresses from this one, so ssh's host-key proof decides: for an entry this
// run has just written as much as for one that was already there. On the old
// code a fresh entry followed the list on the strength of a TCP answer on
// port 22, and only one answering address was ever offered to the proof.

import (
	"net"
	"os"
	"path/filepath"
	"runtime"
	"strings"
	"testing"
)

// setupHome readies a fresh HOME for `huginn setup ssh`: huginn listing addrs
// on the core's socket, a key already in place (so ssh-keygen is not run), an
// ssh on PATH that reaches nothing, and what setup prints going to a file.
// It returns the ssh config's path and a reader for what was printed.
func setupHome(t *testing.T, addrs []string) (string, func() string) {
	t.Helper()
	fakeCoreListing(t, addrs)
	t.Setenv("HUGINN_HOST", "huginn")
	t.Setenv("HUGINN_SETUP", "")
	t.Setenv("HUGINN_USER", "root")
	sshDir := filepath.Join(os.Getenv("HOME"), ".ssh")
	if err := os.MkdirAll(sshDir, 0o700); err != nil {
		t.Fatal(err)
	}
	os.WriteFile(filepath.Join(sshDir, "id_ed25519"), []byte("not a key\n"), 0o600)
	os.WriteFile(filepath.Join(sshDir, "id_ed25519.pub"), []byte("ssh-ed25519 AAAA alex@example.net\n"), 0o644)
	stubSSH(t, "echo 'ssh: connect to host 192.0.2.1 port 22: Connection timed out' >&2; exit 255")
	printed, err := os.CreateTemp(t.TempDir(), "stderr")
	if err != nil {
		t.Fatal(err)
	}
	was := os.Stderr
	os.Stderr = printed
	t.Cleanup(func() { os.Stderr = was; printed.Close() })
	return filepath.Join(sshDir, "config"), func() string {
		b, _ := os.ReadFile(printed.Name())
		return string(b)
	}
}

// fakeSSHDOn stands a listener in for sshd on each of hosts, all on one port,
// so every one of them answers where sshAddresses knocks.
func fakeSSHDOn(t *testing.T, hosts ...string) {
	t.Helper()
	for try := 0; try < 20; try++ {
		var lns []net.Listener
		port := "0"
		for _, h := range hosts {
			ln, err := net.Listen("tcp", net.JoinHostPort(h, port))
			if err != nil {
				if len(lns) == 0 {
					t.Skipf("cannot listen on %s here: %v", h, err)
				}
				break
			}
			lns = append(lns, ln)
			_, port, _ = net.SplitHostPort(ln.Addr().String())
		}
		if len(lns) < len(hosts) { // the port was taken on a later host
			for _, ln := range lns {
				ln.Close()
			}
			continue
		}
		for _, ln := range lns {
			t.Cleanup(func() { ln.Close() })
			go func() {
				for {
					c, err := ln.Accept()
					if err != nil {
						return
					}
					c.Close()
				}
			}()
		}
		was := sshPort
		sshPort = port
		t.Cleanup(func() { sshPort = was })
		return
	}
	t.Skip("no port free on every one of ", hosts)
}

type proofAsk struct{ alias, old, addr string }

// proveOnly stands in for ssh's host-key proof: it records what it was asked
// and finds the host key known for old only at addr.
func proveOnly(t *testing.T, addr string) *[]proofAsk {
	t.Helper()
	var asked []proofAsk
	was := sshSameHost
	sshSameHost = func(alias, old, a string) bool {
		asked = append(asked, proofAsk{alias, old, a})
		return a == addr
	}
	t.Cleanup(func() { sshSameHost = was })
	return &asked
}

// A first `setup ssh` writes `Host huginn` for the address it was given; when
// that does not answer ssh, the entry moves to another of huginn's addresses
// only when ssh finds there the host key known for the first. Answering on
// port 22 is not enough, however new the entry.
func TestSetupSSHMovesItsNewEntryOnlyForTheSameHostKey(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the stub ssh is a shell script, and the fake core listens on a unix socket path")
	}
	for _, sameKey := range []bool{false, true} {
		name := map[bool]string{false: "another machine answers", true: "the same host key"}[sameKey]
		t.Run(name, func(t *testing.T) {
			fakeSSHD(t)
			cfg, printed := setupHome(t, []string{"192.0.2.1", "::1"})
			proved := "192.0.2.99" // nowhere
			if sameKey {
				proved = "::1"
			}
			asked := proveOnly(t, proved)

			setup([]string{"ssh", "--host", "192.0.2.1"})
			b, _ := os.ReadFile(cfg)
			if !strings.Contains(string(b), "Host huginn\n") {
				t.Fatalf("no entry was written:\n%s", b)
			}
			if len(*asked) != 1 || (*asked)[0] != (proofAsk{"huginn", "192.0.2.1", "::1"}) {
				t.Fatalf("ssh was asked %+v; want once, for ::1 under 192.0.2.1's key\n%s", *asked, printed())
			}
			if !sameKey {
				if !strings.Contains(string(b), "  HostName 192.0.2.1\n") {
					t.Fatalf("the new entry followed huginn's list with nothing to show it is the same machine:\n%s\n%s", b, printed())
				}
				if !strings.Contains(printed(), "huginn setup ssh --host ::1") {
					t.Fatalf("want the command that moves the entry:\n%s", printed())
				}
				return
			}
			if !strings.Contains(string(b), "  HostName ::1\n") || !strings.Contains(printed(), "has the host key this computer knows for 192.0.2.1") {
				t.Fatalf("the same host key, and the entry did not move:\n%s\n%s", b, printed())
			}
		})
	}
}

// Every address of huginn's that answers on port 22 is offered to the proof,
// in huginn's order, and the entry moves to the first that passes: a machine
// answering an earlier address cannot keep it from the server at a later one.
func TestSetupSSHOffersEveryAnsweringAddressInHuginnsOrder(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the stub ssh is a shell script, and the fake core listens on a unix socket path")
	}
	fakeSSHDOn(t, "127.0.0.1", "::1")
	cfg, printed := setupHome(t, []string{"192.0.2.1", "127.0.0.1", "::1"})
	os.WriteFile(cfg, []byte("Host huginn\n  HostName 192.0.2.1\n  User root\n"), 0o600)
	asked := proveOnly(t, "::1")

	setup([]string{"ssh"})
	want := []proofAsk{{"huginn", "192.0.2.1", "127.0.0.1"}, {"huginn", "192.0.2.1", "::1"}}
	if len(*asked) != len(want) || (*asked)[0] != want[0] || (*asked)[1] != want[1] {
		t.Fatalf("ssh was asked %+v; want %+v\n%s", *asked, want, printed())
	}
	if b, _ := os.ReadFile(cfg); string(b) != "Host huginn\n  HostName ::1\n  User root\n" {
		t.Fatalf("the entry did not move to the address that proved itself:\n%s\n%s", b, printed())
	}
}
