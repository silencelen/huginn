package appd

import (
	"fmt"
	"net"
	"net/url"
	"os"
	"regexp"
	"strconv"
	"strings"
)

// PlaintextEnv is the ONE switch that lets this client send the token over
// plain HTTP, and only to a daemon on this machine: HUGINN_APPD_PLAINTEXT=local.
// Unset (the default), empty or any other value, the token goes only over a
// TLS channel the daemon has proved, loopback included.
//
// ⚠ IT MIRRORS THE DAEMON'S OWN SWITCH. appd is told who may speak plain HTTP
// to it by the same variable (server/appd/lib/tls.js plaintextPolicy: `local`
// answers plain HTTP from loopback only, `any` from everywhere). On a client
// `local` means the other half: a loopback http:// url stays plain http and
// carries the token with nothing checking who answers. `any` means nothing
// here, because no client setting may put the token in cleartext on a wire.
//
// ⚠ AND IT IS OFF BY DEFAULT, UNLIKE THE DAEMON'S. Loopback has no identity:
// any account on the machine can bind the daemon's port while appd restarts,
// and a client that sent the token in the clear to whatever answered there
// handed that account root on the host (security scan F8). appd serves TLS on
// that same port, so the default is the proof and the pin used everywhere
// else; this switch exists only for a daemon that cannot do TLS (one older
// than 3.11), and must be set in the environment of the process that dials
// (for huginn core, its service), never typed into a url.
const PlaintextEnv = "HUGINN_APPD_PLAINTEXT"

// PlaintextLoopback reports whether this process runs with
// HUGINN_APPD_PLAINTEXT=local (see PlaintextEnv). Read on every call, as the
// token's variable is.
func PlaintextLoopback() bool {
	return strings.EqualFold(strings.TrimSpace(os.Getenv(PlaintextEnv)), "local")
}

// IsLoopbackHost reports whether host is this machine: `localhost`,
// 127.0.0.0/8 and ::1 (bracketed or not, written out or not). It is the only
// host plain HTTP may ever reach, and only under PlaintextEnv: a loopback
// address never leaves the box, but nothing proves who is listening on it.
//
// ⚠ NOT `*.localhost` and not a name that merely starts with 127: nothing
// guarantees a given resolver sends those to loopback, and a bearer in
// cleartext is not the place to find out. Only literals and the bare name.
func IsLoopbackHost(host string) bool {
	h := strings.ToLower(strings.TrimSpace(host))
	h = strings.TrimSuffix(strings.TrimPrefix(h, "["), "]")
	if h == "localhost" {
		return true
	}
	// ParseIP rejects "127.1" and names, so only a full literal qualifies.
	ip := net.ParseIP(h)
	return ip != nil && ip.IsLoopback()
}

// bareIPv6 spots an authority with more than one ':' and no brackets — the
// shape a wrapper builds out of $SSH_CONNECTION, which no URL parser can read.
var (
	bareIPv6     = regexp.MustCompile(`^[^\[/]*:[^/]*:`)
	schemePrefix = regexp.MustCompile(`(?i)^[a-z]+://`)
)

// URLRefusal says why u cannot be dialled, or nil.
//
// ⚠ CHECKED BEFORE IT IS WRITTEN DOWN (Node urlRefusal): a store that saved
// an undiallable address would retry it forever with a message naming
// nothing, so the refusal names the address and, for the realistic case, the
// fix.
func URLRefusal(u string) error {
	p, err := url.Parse(strings.TrimSpace(u))
	if err != nil {
		rest := schemePrefix.ReplaceAllString(u, "")
		if bareIPv6.MatchString(rest) {
			return fmt.Errorf("%q is not a url this client can dial — an IPv6 address must be bracketed, e.g. https://[fd00::1]:8787", u)
		}
		return fmt.Errorf("%q is not a url this client can dial", u)
	}
	if p.Scheme != "http" && p.Scheme != "https" {
		return fmt.Errorf("%q is not an http(s) url", u)
	}
	if p.Hostname() == "" {
		return fmt.Errorf("%q has no host", u)
	}
	if port := p.Port(); port != "" {
		if n, err := strconv.Atoi(port); err != nil || n < 1 || n > 65535 {
			return fmt.Errorf("%q has no valid port", u)
		}
	}
	if p.User != nil {
		// The token is the credential; a userinfo part would be a second one
		// riding in a string that gets logged and displayed.
		return fmt.Errorf("%q carries a user name or password; remove it", u)
	}
	return nil
}

// NormalizeURL is the url this client actually dials, and the form a store
// should write back (TLS.md rule 6): every http:// becomes https:// on the
// same host and port, a daemon on this machine included, and every https://
// stays as it is. The one exception is a loopback http:// under
// HUGINN_APPD_PLAINTEXT=local (PlaintextEnv), which stays plain.
// Unparsable input comes back untouched so URLRefusal still gets to name it.
//
// ⚠ AN UPGRADE, NOT A REFUSAL. Every client before 3.11 stored
// http://<lan-ip>:8787, and a core paired on the server itself stored
// http://127.0.0.1:8787 until loopback was proved too; refusing those would
// strand them, while upgrading reaches the same daemon on the same port (it
// sniffs the first byte for TLS).
// ⚠ LOOPBACK IS UPGRADED TOO (security scan F8). It used to stay plain, on the
// theory that nothing on the box can read a loopback packet; but whoever binds
// the port while appd restarts is sent the token, and any account can.
// ⚠ AND THERE IS NO OPT-OUT FOR ANY OTHER HOST, AND THERE MUST NEVER BE ONE:
// an "allow plain http" switch is one a phishing page can talk somebody into
// flipping, and its only effect is to put a root-equivalent token on the wire.
// Call it on load AND on save, so the user sees https:// where routes live.
func NormalizeURL(u string) string {
	t := strings.TrimSpace(u)
	p, err := url.Parse(t)
	if err != nil || p.Scheme != "http" || p.Host == "" {
		return t
	}
	if IsLoopbackHost(p.Hostname()) && PlaintextLoopback() {
		return t
	}
	p.Scheme = "https"
	return strings.TrimSuffix(p.String(), "/")
}
