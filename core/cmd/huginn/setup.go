package main

import (
	"bufio"
	"flag"
	"fmt"
	"net/url"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"strings"

	"github.com/silencelen/huginn/core/internal/store"
)

// `huginn setup ssh [--host H] [--user U]`: what the installer one-liners do
// for the SSH side, for a machine that got huginn some other way (the desktop
// installer, a distro package). The CLI's session verbs and attaching reach
// the host over ssh to `Host huginn`; the core and the apps use the token.
//
// It never touches a key it did not make, and never edits a `Host huginn`
// stanza that is already there.
func setup(args []string) int {
	if len(args) == 0 || args[0] != "ssh" {
		fmt.Fprintln(os.Stderr, "usage: huginn setup ssh [--host HOST] [--user USER]")
		return 2
	}
	fs := flag.NewFlagSet("setup ssh", flag.ContinueOnError)
	host := fs.String("host", "", "the huginn host's address (default: the host this core is paired with)")
	user := fs.String("user", envOr("HUGINN_USER", "root"), "the ssh user on the host")
	if err := fs.Parse(args[1:]); err != nil {
		return 2
	}
	home, _ := os.UserHomeDir()
	sshDir := filepath.Join(home, ".ssh")
	cfg := filepath.Join(sshDir, "config")
	alias := envOr("HUGINN_HOST", "huginn")

	if hn, ok := stanzaHost(cfg, alias); ok {
		fmt.Printf("SSH is already set up: 'Host %s' -> %s in %s\n", alias, hn, cfg)
		return testSSH(alias)
	}
	if *host == "" {
		*host = pairedHost()
	}
	if *host == "" {
		fmt.Fprintln(os.Stderr, "huginn setup ssh: which host? this core is not paired; run: huginn setup ssh --host <address of the huginn host>")
		return 2
	}
	if _, err := exec.LookPath("ssh"); err != nil {
		if runtime.GOOS == "windows" {
			fmt.Fprintln(os.Stderr, "huginn setup ssh: no ssh.exe. Add Windows' OpenSSH client: Settings > System > Optional features > OpenSSH Client (or, as administrator: Add-WindowsCapability -Online -Name OpenSSH.Client~~~~0.0.1.0)")
		} else {
			fmt.Fprintln(os.Stderr, "huginn setup ssh: no ssh client; install OpenSSH (openssh-client / openssh) and run this again")
		}
		return 1
	}
	os.MkdirAll(sshDir, 0o700)
	key := filepath.Join(sshDir, "id_ed25519")
	if !fileExists(key) {
		hn, _ := os.Hostname()
		c := exec.Command("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "huginn "+hn, "-f", key)
		c.Stdout, c.Stderr = os.Stdout, os.Stderr
		if err := c.Run(); err != nil {
			fmt.Fprintln(os.Stderr, "huginn setup ssh: ssh-keygen failed: "+err.Error())
			return 1
		}
		fmt.Println("  made a key: " + key)
	} else {
		fmt.Println("  using your key: " + key)
	}
	stanza := fmt.Sprintf("\nHost %s\n  HostName %s\n  User %s\n  IdentityFile %s\n  IdentitiesOnly yes\n  RequestTTY yes\n  ServerAliveInterval 30\n", alias, *host, *user, key)
	f, err := os.OpenFile(cfg, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn setup ssh: "+err.Error())
		return 1
	}
	_, err = f.WriteString(stanza)
	f.Close()
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn setup ssh: "+err.Error())
		return 1
	}
	fmt.Printf("  added 'Host %s' -> %s@%s to %s\n", alias, *user, *host, cfg)
	pub, _ := os.ReadFile(key + ".pub")
	fmt.Println()
	fmt.Println("Now authorize this computer on the huginn host. On the host, as " + *user + ", run:")
	fmt.Println()
	fmt.Printf("  echo '%s' >> ~/.ssh/authorized_keys\n", strings.TrimSpace(string(pub)))
	fmt.Println()
	if term := os.Stdin; isTerminal(term) {
		fmt.Print("Press Enter when that is done (Ctrl-C to finish later) ")
		bufio.NewReader(os.Stdin).ReadString('\n')
		return testSSH(alias)
	}
	fmt.Println("Then check it with: huginn status")
	return 0
}

func envOr(k, def string) string {
	if v := os.Getenv(k); v != "" {
		return v
	}
	return def
}

// stanzaHost: the HostName of an exact `Host <alias>` stanza, if there is one.
func stanzaHost(cfg, alias string) (string, bool) {
	b, err := os.ReadFile(cfg)
	if err != nil {
		return "", false
	}
	in, found, hn := false, false, ""
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) == 0 {
			continue
		}
		if strings.EqualFold(f[0], "host") {
			in = false
			for _, h := range f[1:] {
				if h == alias {
					in, found = true, true
				}
			}
			continue
		}
		if in && len(f) >= 2 && strings.EqualFold(f[0], "hostname") && hn == "" {
			hn = f[1]
		}
	}
	if hn == "" {
		hn = alias
	}
	return hn, found
}

// pairedHost: the host part of the appd address this core is paired with.
func pairedHost() string {
	st, err := store.Open(paths())
	if err != nil {
		return ""
	}
	c, _, err := st.Load()
	if err != nil || c.Appd.URL == "" {
		return ""
	}
	u, err := url.Parse(c.Appd.URL)
	if err != nil {
		return ""
	}
	return u.Hostname()
}

func testSSH(alias string) int {
	fmt.Print("  checking ssh " + alias + " … ")
	out, err := exec.Command("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", alias, "true").CombinedOutput()
	if err != nil {
		fmt.Println("not yet")
		if s := strings.TrimSpace(string(out)); s != "" {
			fmt.Println("  " + s)
		}
		fmt.Println("  When the key is authorized on the host, `huginn status` and `huginn ls` work.")
		return 1
	}
	fmt.Println("ok")
	fmt.Println("  SSH works: `huginn ls`, `huginn attach <name>` and plain `huginn` (the console) can attach.")
	return 0
}
