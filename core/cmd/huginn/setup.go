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
	"github.com/silencelen/huginn/core/internal/ui"
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
	u := ui.New()
	u.Head("huginn setup ssh")

	if _, err := exec.LookPath("ssh"); err != nil {
		if runtime.GOOS == "windows" {
			u.Line(ui.Fail, "ssh", "no ssh.exe: add Windows' OpenSSH client")
			u.Note("Settings > System > Optional features > OpenSSH Client (or, as administrator: Add-WindowsCapability -Online -Name OpenSSH.Client~~~~0.0.1.0)")
		} else {
			u.Line(ui.Fail, "ssh", "no ssh client: install OpenSSH (openssh-client / openssh) and run this again")
		}
		return 1
	}
	key := filepath.Join(sshDir, "id_ed25519")
	if hn, ok := stanzaHost(cfg, alias); ok {
		u.Line(ui.OK, "ssh", fmt.Sprintf("'Host %s' -> %s is already in %s - kept as it is", alias, hn, tilde(home, cfg)))
		*host = hn
		if k := stanzaKey(cfg, alias, home); k != "" {
			key = k
		}
	} else {
		if *host == "" {
			*host = pairedHost()
		}
		if *host == "" {
			u.Line(ui.Fail, "ssh", "which host? this core is not paired")
			u.Note("run: huginn setup ssh --host <address of the huginn host>")
			return 2
		}
		os.MkdirAll(sshDir, 0o700)
		if !fileExists(key) {
			hn, _ := os.Hostname()
			c := exec.Command("ssh-keygen", "-q", "-t", "ed25519", "-N", "", "-C", "huginn@"+hn, "-f", key)
			if out, err := c.CombinedOutput(); err != nil {
				u.Line(ui.Fail, "key", "ssh-keygen failed: "+err.Error())
				u.Note(strings.TrimSpace(string(out)))
				return 1
			}
			u.Line(ui.OK, "key", tilde(home, key)+" (made now)")
		} else {
			u.Line(ui.OK, "key", tilde(home, key)+" (yours, reused)")
		}
		stanza := fmt.Sprintf("\nHost %s\n  HostName %s\n  User %s\n  IdentityFile %s\n  IdentitiesOnly yes\n  RequestTTY yes\n  ServerAliveInterval 30\n", alias, *host, *user, key)
		f, err := os.OpenFile(cfg, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o600)
		if err == nil {
			_, err = f.WriteString(stanza)
			f.Close()
		}
		if err != nil {
			u.Line(ui.Fail, "ssh", err.Error())
			return 1
		}
		u.Line(ui.OK, "ssh", fmt.Sprintf("'Host %s' -> %s, as %s (%s)", alias, *host, *user, tilde(home, cfg)))
	}

	why := sshTrust(alias)
	if why == "hostkey" && isTerminal(os.Stdin) {
		// A host this computer has never seen: ssh's own question, with the
		// host's fingerprint, answered by the person. Public-key only, so
		// nothing asks for a password.
		u.Line(ui.Warn, "trust", "the huginn host is new to this computer: ssh shows its fingerprint - answer yes if it is your host")
		c := exec.Command("ssh", "-o", "PasswordAuthentication=no", "-o", "KbdInteractiveAuthentication=no", "-o", "ConnectTimeout=15", alias, "true")
		c.Stdin, c.Stdout = os.Stdin, os.Stdout
		c.Run()
		why = sshTrust(alias)
	}
	showTrust(u, why, *host)
	if why == "denied" || why == "hostkey" {
		pub, _ := os.ReadFile(key + ".pub")
		u.Say("")
		u.Say("Authorize this computer on the huginn host. On " + *host + ", as " + *user + ", run:")
		u.Say("")
		u.Say("  echo '" + strings.TrimSpace(string(pub)) + "' >> ~/.ssh/authorized_keys")
		u.Say("")
	}
	if why != "ok" && why != "changed" && isTerminal(os.Stdin) {
		u.Prompt("Press Enter when that is done (Ctrl-C to finish later) ")
		bufio.NewReader(os.Stdin).ReadString('\n')
		why = sshTrust(alias)
		showTrust(u, why, *host)
	}
	if why != "ok" {
		u.Say("")
		u.Say("Once the host accepts the key, `huginn ls` and `huginn attach` work; check with: huginn status")
		return 1
	}
	u.Say("")
	u.Say("SSH works: `huginn ls`, `huginn attach <name>` and plain `huginn` (the console) can attach.")
	return 0
}

// sshTrust asks the host, never prompting, whether it takes this computer's
// key: ok | denied | hostkey (its key is new here) | changed (its key CHANGED)
// | "unreachable: <what ssh said>".
func sshTrust(alias string) string {
	out, err := exec.Command("ssh", "-o", "BatchMode=yes", "-o", "ConnectTimeout=10", alias, "true").CombinedOutput()
	if err == nil {
		return "ok"
	}
	s := string(out)
	switch {
	case strings.Contains(s, "IDENTIFICATION HAS CHANGED"):
		return "changed"
	case strings.Contains(s, "Permission denied"):
		return "denied"
	case strings.Contains(s, "Host key verification failed"), strings.Contains(s, "host key is known"):
		return "hostkey"
	}
	last := ""
	for _, l := range strings.Split(strings.TrimSpace(s), "\n") {
		if l = strings.TrimSpace(l); l != "" && !strings.HasPrefix(l, "Warning: Permanently added") {
			last = l
		}
	}
	if last == "" {
		last = err.Error()
	}
	return "unreachable: " + last
}

func showTrust(u *ui.Out, why, host string) {
	switch why {
	case "ok":
		u.Line(ui.OK, "trust", "the huginn host accepts this key")
	case "denied":
		u.Line(ui.Warn, "trust", "the huginn host does not know this key yet")
	case "hostkey":
		u.Line(ui.Warn, "trust", "the huginn host's own key was not accepted, so nothing was sent")
	case "changed":
		u.Line(ui.Fail, "trust", "the huginn host's key has CHANGED since this computer last saw it - nothing was sent")
		u.Note("Check with whoever runs " + host + ". If it was reinstalled: ssh-keygen -R " + host + ", then run this again.")
	default:
		u.Line(ui.Warn, "trust", "could not reach "+host+" over ssh ("+strings.TrimPrefix(why, "unreachable: ")+")")
	}
}

// stanzaKey: the IdentityFile of the `Host <alias>` stanza, ~ expanded.
func stanzaKey(cfg, alias, home string) string {
	b, err := os.ReadFile(cfg)
	if err != nil {
		return ""
	}
	in := false
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) == 0 {
			continue
		}
		if strings.EqualFold(f[0], "host") {
			in = false
			for _, h := range f[1:] {
				in = in || h == alias
			}
			continue
		}
		if in && len(f) >= 2 && strings.EqualFold(f[0], "identityfile") {
			k := f[1]
			if strings.HasPrefix(k, "~/") || strings.HasPrefix(k, `~\`) {
				k = filepath.Join(home, k[2:])
			}
			return k
		}
	}
	return ""
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
