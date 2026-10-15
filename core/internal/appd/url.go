package appd

import (
	"fmt"
	"net"
	"net/url"
	"regexp"
	"strconv"
	"strings"
)

// IsLoopbackHost reports whether host is this machine — the one place plain
// HTTP may go, because it never leaves the box: `localhost`, 127.0.0.0/8 and
// ::1 (bracketed or not, written out or not).
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
// should write back (TLS.md rule 6): a remote http:// becomes https:// on the
// same host and port; loopback http:// and every https:// stay as they are.
// Unparsable input comes back untouched so URLRefusal still gets to name it.
//
// ⚠ AN UPGRADE, NOT A REFUSAL. Every client before 3.11 stored
// http://<lan-ip>:8787; refusing that would strand them, while upgrading
// reaches the same daemon on the same port (it sniffs the first byte for TLS).
// ⚠ AND THERE IS NO OPT-OUT, AND THERE MUST NEVER BE ONE: an "allow plain
// http" switch is one a phishing page can talk somebody into flipping, and its
// only effect is to put a root-equivalent token on the wire.
// Call it on load AND on save, so the user sees https:// where routes live.
func NormalizeURL(u string) string {
	t := strings.TrimSpace(u)
	p, err := url.Parse(t)
	if err != nil || p.Scheme != "http" || p.Host == "" || IsLoopbackHost(p.Hostname()) {
		return t
	}
	p.Scheme = "https"
	return strings.TrimSuffix(p.String(), "/")
}
