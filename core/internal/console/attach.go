package console

import (
	"fmt"
	"io"
	"math/rand"
	"os"
	"os/exec"
	"regexp"
	"strings"
	"time"
)

// attach is `huginn attach <name>` in Go: the same `ssh -tt <host> cc <name>`
// the shell clients run, with their reconnect loop, so the console hands the
// real terminal to the session and takes it back when it ends (Bubble Tea
// suspends around it; see tea.Exec). Kept behaviourally identical to
// client/huginn.sh's _huginn_attach:
//   - reconnect on any non-zero exit (drops on Termux/Windows OpenSSH do not
//     reliably return 255), unless HUGINN_NO_RECONNECT;
//   - give up after three attempts that die in under 5 s (a remote error, not
//     a drop), saying so;
//   - never resurrect a session that was killed meanwhile; mirror when another
//     client is attached, else take it solo (evicts our own ghost);
//   - jittered backoff, 2 s doubling to 15 s;
//   - push the terminal title, set it to the session, pop it on the way out.
type attach struct {
	host, session string
	solo          bool
	stdin         io.Reader
	stdout        io.Writer
	stderr        io.Writer
}

var validName = regexp.MustCompile(`^[a-z0-9_][a-z0-9_-]{0,49}$`)

func newAttach(host, session string, solo bool) (*attach, error) {
	session = strings.ToLower(session)
	if !validName.MatchString(session) {
		return nil, fmt.Errorf("%q is not a session name", session)
	}
	return &attach{host: host, session: session, solo: solo}, nil
}

func (a *attach) SetStdin(r io.Reader)  { a.stdin = r }
func (a *attach) SetStdout(w io.Writer) { a.stdout = w }
func (a *attach) SetStderr(w io.Writer) { a.stderr = w }

func (a *attach) Run() error {
	if a.stdout == nil {
		a.stdout = os.Stdout
	}
	if a.stderr == nil {
		a.stderr = os.Stderr
	}
	title := os.Getenv("HUGINN_NO_TITLE") == ""
	if title {
		fmt.Fprintf(a.stdout, "\033[22;0t\033]0;%s\007", a.session)
		defer fmt.Fprint(a.stdout, "\033[23;0t")
	}
	remote := "cc " + a.session
	if a.solo {
		remote += " solo"
	}
	tgt := "=" + a.session
	delay, quick := 2.0, 0
	for {
		t0 := time.Now()
		cmd := exec.Command("ssh", "-tt", "-o", "ConnectTimeout=10", "-o", "ServerAliveInterval=15", "-o", "ServerAliveCountMax=3", a.host, remote)
		cmd.Stdin, cmd.Stdout, cmd.Stderr = a.stdin, a.stdout, a.stderr
		err := cmd.Run()
		if err == nil || os.Getenv("HUGINN_NO_RECONNECT") != "" {
			return err
		}
		if _, isExit := err.(*exec.ExitError); !isExit {
			return fmt.Errorf("could not run ssh: %w (attaching needs the OpenSSH client and a 'Host %s' entry)", err, a.host)
		}
		if time.Since(t0) < 5*time.Second {
			quick++
			if quick >= 3 {
				return fmt.Errorf("%s is failing immediately (%d attempts, last: %v); the remote error is printed above", a.host, quick, err)
			}
		} else {
			quick = 0
		}
		fmt.Fprintf(a.stderr, "\r\nhuginn: link to %s dropped (%v) - reconnecting in %.0fs...\r\n", a.host, err, delay)
		time.Sleep(time.Duration(delay * (0.75 + rand.Float64()*0.5) * float64(time.Second)))
		remote = "tmux has-session -t " + tgt + " 2>/dev/null || { echo 'huginn: session " + a.session + " no longer exists on " + a.host +
			"'; exit 0; }; if [ \"$(tmux list-clients -t " + tgt + " 2>/dev/null | wc -l)\" -ge 2 ]; then cc " + a.session + "; else cc " + a.session + " solo; fi"
		if delay *= 2; delay > 15 {
			delay = 15
		}
	}
}

// sshHost is the alias the clients use: HUGINN_HOST, else `huginn`.
func sshHost() string {
	if h := os.Getenv("HUGINN_HOST"); h != "" {
		return h
	}
	return "huginn"
}

// hasSSHHost: a `Host <alias>` stanza exists, or the alias is an address.
func hasSSHHost(home, alias string) bool {
	if strings.ContainsAny(alias, ".:@") {
		return true
	}
	b, err := os.ReadFile(home + "/.ssh/config")
	if err != nil {
		return false
	}
	for _, line := range strings.Split(string(b), "\n") {
		f := strings.Fields(line)
		if len(f) >= 2 && strings.EqualFold(f[0], "host") {
			for _, h := range f[1:] {
				if h == alias {
					return true
				}
			}
		}
	}
	return false
}
