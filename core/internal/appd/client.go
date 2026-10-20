package appd

import (
	"bufio"
	"bytes"
	"context"
	"crypto/rand"
	"crypto/tls"
	"encoding/hex"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/url"
	"os"
	"slices"
	"strings"
	"sync"
	"time"
)

const (
	DefaultHandshakeTimeout = 20 * time.Second
	DefaultProbeTimeout     = 15 * time.Second
	DefaultRequestTimeout   = 20 * time.Second

	// maxBody bounds what one response may make us hold. Work items carry a
	// prompt, never files, so this is generous.
	maxBody = 32 << 20
	// maxProbeBody: a challenge answer is a few hundred bytes; a host that
	// sends more is not huginn.
	maxProbeBody = 64 << 10
)

// Config is what a Client needs. Only URL is required.
type Config struct {
	// URL is the daemon's base url. It is normalised (NormalizeURL) and
	// refused (URLRefusal) by New.
	URL string
	// Tokens is consulted on every request, after $HUGINN_APPD_TOKEN, so a
	// token changed underneath a running client is picked up.
	Tokens TokenSource
	// Pins persists proved fingerprints. nil means a MemoryPinStore: safe, it
	// only costs a re-proof per process.
	Pins PinStore
	// HandshakeTimeout bounds connect + TLS handshake on the pinned channel.
	HandshakeTimeout time.Duration
	// ProbeTimeout bounds the whole challenge exchange.
	ProbeTimeout time.Duration
	// RequestTimeout applies when the caller's context has no deadline.
	RequestTimeout time.Duration
	// Logf, if set, hears the one event worth a log line: a re-pin.
	Logf func(format string, args ...any)
	// Headers, if set, adds headers to every request — the core's install id
	// (X-Huginn-Client) and its notify claim (X-Huginn-Notify, D14). Called per
	// request, so a claim that changes is sent as it is NOW.
	Headers func() map[string]string
	// Routes are other addresses of the SAME daemon (its LAN address, its
	// mesh address, ...), in order of preference after URL; see routes.go.
	// The certificate pin is the daemon's, so it holds on every address.
	Routes []string
	// Fixed keeps every request on URL: no switching (a pinned route, or
	// auto-switch turned off).
	Fixed bool
	// OnRoute, if set, hears every switch of the active address and every
	// change in an address's health.
	OnRoute func(RouteEvent)
}

// Client talks to one daemon address. Safe for concurrent use.
type Client struct {
	routeState
	base   *url.URL // routes[active]; read through cur()
	tokens TokenSource
	pins   PinStore
	hs     time.Duration
	probeT time.Duration
	reqT   time.Duration
	logf   func(string, ...any)
	hdrs   func() map[string]string

	mu        sync.Mutex
	pinnedKey string
	pinned    *http.Transport
	plain     *http.Transport

	// proveMu serialises re-proofs: the beat, the poll and an events POST can
	// all hit a rotated certificate at once, and one proof answers all three.
	proveMu sync.Mutex
}

// Response is a status and the whole body.
type Response struct {
	Status int
	Body   []byte
}

// New builds a client for cfg.URL.
func New(cfg Config) (*Client, error) {
	raw := NormalizeURL(cfg.URL)
	if err := URLRefusal(raw); err != nil {
		return nil, err
	}
	base, _ := url.Parse(raw)
	c := &Client{
		base: base, tokens: cfg.Tokens, pins: cfg.Pins,
		hs: cfg.HandshakeTimeout, probeT: cfg.ProbeTimeout, reqT: cfg.RequestTimeout,
		logf: cfg.Logf, hdrs: cfg.Headers,
	}
	if err := c.initRoutes(base, cfg); err != nil {
		return nil, err
	}
	if c.pins == nil {
		c.pins = &MemoryPinStore{}
	}
	if c.hs <= 0 {
		c.hs = DefaultHandshakeTimeout
	}
	if c.probeT <= 0 {
		c.probeT = DefaultProbeTimeout
	}
	if c.reqT <= 0 {
		c.reqT = DefaultRequestTimeout
	}
	if c.logf == nil {
		c.logf = func(string, ...any) {}
	}
	return c, nil
}

// URL is the normalised base url this client dials.
func (c *Client) URL() string { return strings.TrimSuffix(c.cur().String(), "/") }

func (c *Client) origin() string { b := c.cur(); return b.Scheme + "://" + b.Host }

// Close drops idle connections.
func (c *Client) Close() {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pinned != nil {
		c.pinned.CloseIdleConnections()
	}
	if c.plain != nil {
		c.plain.CloseIdleConnections()
	}
}

// Do sends one token-carrying request and reads the whole answer. body, when
// non-nil, is sent as JSON. A status of 400 or above comes back as a
// *StatusError together with the Response.
func (c *Client) Do(ctx context.Context, method, path string, body any) (*Response, error) {
	var payload []byte
	if body != nil {
		var err error
		if payload, err = json.Marshal(body); err != nil {
			return nil, fmt.Errorf("%s %s: encoding the body: %w", method, path, err)
		}
	}
	return c.Raw(ctx, method, path, payload)
}

// Raw is Do with the body already encoded (JSON or nothing): the core's
// /appd/* proxy forwards what the console or GUI sent, byte for byte.
func (c *Client) Raw(ctx context.Context, method, path string, payload []byte) (*Response, error) {
	var resp *Response
	err := c.withRoutes(ctx, func() error {
		var err error
		resp, err = c.raw1(ctx, method, path, payload)
		return err
	})
	return resp, err
}

func (c *Client) raw1(ctx context.Context, method, path string, payload []byte) (*Response, error) {
	target, err := c.resolve(path)
	if err != nil {
		return nil, err
	}
	tok, err := ResolveToken(c.tokens)
	if err != nil {
		return nil, err
	}
	if _, has := ctx.Deadline(); !has {
		var cancel context.CancelFunc
		ctx, cancel = context.WithTimeout(ctx, c.reqT)
		defer cancel()
	}

	var resp *Response
	switch target.Scheme {
	case "https":
		resp, err = c.pinnedRequest(ctx, tok, method, target, payload)
	case "http":
		if err := plainRefusal(target); err != nil {
			return nil, err
		}
		resp, err = c.send(ctx, c.plainTransport(), tok, method, target, payload)
	default:
		return nil, fmt.Errorf("%q is not an http(s) url", target.String())
	}
	if err != nil {
		return nil, err
	}
	if resp.Status >= 400 {
		return resp, &StatusError{Method: method, Path: target.Path, Status: resp.Status, Body: Printable(string(resp.Body), 200)}
	}
	return resp, nil
}

// plainRefusal says why the token may not go to u over plain http, or nil.
//
// ⚠ LOOPBACK UNDER HUGINN_APPD_PLAINTEXT=local, AND NOTHING ELSE. NormalizeURL
// already dials every other http:// as https://, a loopback one included; this
// re-check is so no caller that builds a Client some other way (or keeps one
// across a change of the switch) can put the token on the wire unproved. A
// loopback port proves nothing about who holds it (security scan F8).
func plainRefusal(u *url.URL) error {
	if !IsLoopbackHost(u.Hostname()) {
		return fmt.Errorf("refusing plain http to %s: cleartext is for loopback only", u.Host)
	}
	if !PlaintextLoopback() {
		return fmt.Errorf("refusing plain http to %s: the token goes only to a daemon that has proved itself over TLS, on this machine too (%s=local turns that off, for a daemon here that has no TLS)", u.Host, PlaintextEnv)
	}
	return nil
}

// noTLSHint finishes the old-daemon sentence for an address on this machine,
// which this client used to reach in plain http: the port may be held by
// something other than huginn, and there is one way to reach an old daemon
// anyway, which is named rather than guessed at.
func (c *Client) noTLSHint() string {
	if !IsLoopbackHost(c.cur().Hostname()) {
		return ""
	}
	return fmt.Sprintf("; on this machine that is an old daemon or another program holding the port. "+
		"To send the token in plain http to a daemon here that has no TLS, run this client with %s=local", PlaintextEnv)
}

// resolve joins an absolute path onto the base url.
//
// ⚠ THE PATH MAY NOT CHANGE THE ORIGIN. The core's /appd/* proxy forwards
// paths that come from the GUI; "//evil/x" or "https://evil/x" resolved
// naively would carry the bearer to another host.
func (c *Client) resolve(path string) (*url.URL, error) {
	if !strings.HasPrefix(path, "/") || strings.HasPrefix(path, "//") || strings.ContainsAny(path, "\\\r\n") {
		return nil, fmt.Errorf("refusing request path %q: must be an absolute path on the daemon", path)
	}
	ref, err := url.Parse(path)
	if err != nil {
		return nil, fmt.Errorf("refusing request path %q: %v", path, err)
	}
	b := c.cur()
	t := b.ResolveReference(ref)
	if t.Scheme != b.Scheme || t.Host != b.Host || t.User != nil {
		return nil, fmt.Errorf("refusing request path %q: it leaves %s", path, c.origin())
	}
	return t, nil
}

// notSent is a failure before any byte of the request left for this
// address: the address did not prove to be the daemon (whatever it answered
// to the proof, which never carries the token), or the pinned handshake with
// it failed. It reads as the failure it wraps; notDelivered tells it apart.
type notSent struct{ err error }

func (e notSent) Error() string { return e.err.Error() }
func (e notSent) Unwrap() error { return e.err }

// pinnedRequest is a token-carrying request over https, pinned. Proves first
// when there is no pin for this token; on a leaf that is not pinned, re-proves
// (rule 5) and retries ONCE — a certificate that changes again between the
// proof and the retry is not a rotation anybody performed.
func (c *Client) pinnedRequest(ctx context.Context, tok, method string, target *url.URL, payload []byte) (*Response, error) {
	pins := c.loadPins(tok)
	if len(pins) == 0 {
		var err error
		if pins, err = c.proveAndPin(ctx, tok, nil); err != nil {
			return nil, notSent{err}
		}
	}
	resp, err := c.send(ctx, c.pinnedTransport(tok, pins), tok, method, target, payload)
	var pm *PinMismatchError
	if !errors.As(err, &pm) {
		return resp, err
	}
	// ⚠ A FAILED RE-PROOF IS STILL "NOTHING SENT". The leaf was refused inside
	// the handshake; whatever holds the address then answered the proof with
	// a 404, a web page or nothing at all. Returned bare, that answer was not
	// a reason to try the next address, and a NAS on the home LAN address kept
	// the core off huginn while another route answered.
	if pins, err = c.proveAndPin(ctx, tok, pins); err != nil {
		return nil, notSent{err}
	}
	resp, err = c.send(ctx, c.pinnedTransport(tok, pins), tok, method, target, payload)
	if errors.As(err, &pm) {
		return nil, interceptedAt(c.origin(), fmt.Sprintf(" (certificate %s changed again after it was proved)", pm.FP))
	}
	return resp, err
}

func (c *Client) loadPins(tok string) []string {
	s, err := c.pins.LoadPins()
	if err != nil {
		// Unreadable reads as no pins: the cost is one proof, never a refusal.
		c.logf("could not read the TLS pins (%v); proving the daemon again", err)
		return nil
	}
	return s.For(tok)
}

// Prove runs the challenge against this address now and pins the result. It
// is what `routes probe` and pairing call; Do proves on its own when needed.
func (c *Client) Prove(ctx context.Context) (string, error) {
	tok, err := ResolveToken(c.tokens)
	if err != nil {
		return "", err
	}
	if c.cur().Scheme != "https" {
		return "", fmt.Errorf("%s is plain http (%s=local): there is no certificate to prove", c.URL(), PlaintextEnv)
	}
	pins, err := c.proveAndPin(ctx, tok, c.loadPins(tok))
	if err != nil {
		return "", err
	}
	return pins[0], nil
}

// proveAndPin proves the daemon and adds the fingerprint. `tried` is the pin
// set the caller was refused under (nil before the first proof): when the
// store already holds something different, another goroutine or process
// proved in the meantime and its answer is used instead of a second probe.
func (c *Client) proveAndPin(ctx context.Context, tok string, tried []string) ([]string, error) {
	c.proveMu.Lock()
	defer c.proveMu.Unlock()
	set, err := c.pins.LoadPins()
	if err != nil {
		set = PinSet{}
	}
	if cur := set.For(tok); len(cur) > 0 && !slices.Equal(cur, tried) {
		return cur, nil
	}
	fp, err := c.probe(ctx, tok, len(tried) > 0)
	if err != nil {
		return nil, err
	}
	if tried != nil {
		c.logf("daemon at %s presented a new certificate and proved it (sha256 %s); pinned", c.origin(), fp)
	}
	set = set.With(tok, fp)
	if err := c.pins.SavePins(set); err != nil {
		// The proof stands for this request; the next process just proves again.
		c.logf("could not save the TLS pin (%v); it holds for this process only", err)
	}
	return set.For(tok), nil
}

// probe proves the daemon at this address holds tok, over a connection whose
// leaf we hash ourselves, and returns the proved fingerprint.
//
// ⚠ THE PROBE NEVER CARRIES THE TOKEN AND IS NEVER REUSED (rule 3): a raw
// one-shot connection that accepts any certificate, one request written by
// hand with no Authorization, closed after the answer. It never touches an
// http.Transport, so it cannot end up in a pool a bearer request draws from.
// ⚠ THE FINGERPRINT IS THE ONE OUR HANDSHAKE SAW (rule 1). `tls.fp` in the body
// is whatever the far end wants us to believe; it is read for a hint only.
// ⚠ AND THE LEGACY `proof` IS NOT EVEN DECODED (rule 2): it proves the daemon,
// not this channel, and a relay would forward it untouched.
// ⚠ pinned: this client holds a pin for tok, so the daemon at this token has
// proved itself over TLS before. An answer with no TLS, or no `tls` proof, is
// then somebody else (an interception), never "a daemon older than 3.11".
// ⚠ NOTHING THE PEER WRITES REACHES AN ERROR UNCLEANED: it has proved
// nothing, and the error reaches a terminal (Printable).
func (c *Client) probe(ctx context.Context, tok string, pinned bool) (string, error) {
	nb := make([]byte, 32)
	if _, err := rand.Read(nb); err != nil {
		return "", err
	}
	nonce := hex.EncodeToString(nb)

	pctx, cancel := context.WithTimeout(ctx, c.probeT)
	defer cancel()
	base := c.cur()
	addr := hostPort(base)
	var d net.Dialer
	raw, err := d.DialContext(pctx, "tcp", addr)
	if err != nil {
		return "", c.dialErr(pctx, ctx, addr, c.probeT, err)
	}
	tc := tls.Client(raw, &tls.Config{
		InsecureSkipVerify: true, // ⚠ ONLY because the proof below is the check, and this channel carries nothing
		ServerName:         sni(base.Hostname()),
		NextProtos:         []string{"http/1.1"},
		MinVersion:         tls.VersionTLS12,
	})
	defer tc.Close()
	// The handshake and the exchange share one deadline; a peer that stalls
	// anywhere fails the probe instead of holding the caller.
	stop := context.AfterFunc(pctx, func() { tc.Close() })
	defer stop()
	if dl, ok := pctx.Deadline(); ok {
		_ = tc.SetDeadline(dl)
	}
	if err := tc.HandshakeContext(pctx); err != nil {
		return "", c.handshakeErr(pctx, ctx, addr, c.probeT, err, true, pinned)
	}
	cs := tc.ConnectionState()
	if len(cs.PeerCertificates) == 0 {
		return "", interceptedAt(c.origin(), " (no certificate)")
	}
	seen := Fingerprint(cs.PeerCertificates[0].Raw)

	u := *base
	u.Path, u.RawPath, u.RawQuery, u.Fragment = "/v1/challenge", "", "nonce="+nonce, ""
	req, err := http.NewRequest(http.MethodGet, u.String(), nil)
	if err != nil {
		return "", err
	}
	req.Close = true
	req.Header.Set("Accept", "application/json")
	// No Authorization header, ever: this channel trusts anyone.
	if err := req.Write(tc); err != nil {
		return "", c.handshakeErr(pctx, ctx, addr, c.probeT, err, false, pinned)
	}
	res, err := http.ReadResponse(bufio.NewReader(tc), req)
	if err != nil {
		return "", c.handshakeErr(pctx, ctx, addr, c.probeT, err, false, pinned)
	}
	body, err := io.ReadAll(io.LimitReader(res.Body, maxProbeBody))
	res.Body.Close()
	if err != nil {
		return "", fmt.Errorf("challenge at %s: reading the answer: %w", c.origin(), err)
	}
	if res.StatusCode != http.StatusOK {
		said := Printable(string(body), 200)
		if said != "" {
			said = ": " + said
		}
		return "", fmt.Errorf("challenge at %s answered %d%s", c.origin(), res.StatusCode, said)
	}
	var ans struct {
		TLS json.RawMessage `json:"tls"`
	}
	if err := json.Unmarshal(body, &ans); err != nil {
		return "", fmt.Errorf("challenge at %s did not answer JSON", c.origin())
	}
	t := bytes.TrimSpace(ans.TLS)
	if len(t) == 0 || t[0] != '{' {
		// A missing (or non-object) `tls` over https is NOT PROVED, full stop
		// (rule 2): the legacy proof beside it is never a substitute.
		if pinned {
			return "", interceptedAt(c.origin(), " (it answered with no TLS proof, and the daemon pinned for this token gives one)")
		}
		return "", fmt.Errorf("%s: %w%s", c.origin(), ErrOldDaemon, c.noTLSHint())
	}
	var tb struct {
		V     int    `json:"v"`
		FP    string `json:"fp"`
		Proof string `json:"proof"`
	}
	if err := json.Unmarshal(t, &tb); err != nil {
		return "", interceptedAt(c.origin(), "")
	}
	if tb.V != ProofVersion {
		return "", fmt.Errorf("%s: %w (tls.v = %d)", c.origin(), ErrProofVersion, tb.V)
	}
	if !ProofMatches(lowerHex(tb.Proof), BoundProof(tok, nonce, seen)) {
		hint := ""
		if lowerHex(tb.FP) == seen {
			hint = tokenHint
		}
		return "", interceptedAt(c.origin(), hint)
	}
	return seen, nil
}

// pinnedTransport is the transport for exactly this (token, pin set).
//
// ⚠ NO KEEP-ALIVE REUSE ACROSS A PIN CHANGE. A pooled connection was judged
// against the pins of the moment it was opened; after a re-proof, and above
// all after a token change (whose new token has proved NOTHING about that
// peer), it must not carry another request. So the transport is keyed by the
// token's tag and the exact set, and replaced — idle connections closed — the
// moment either differs.
func (c *Client) pinnedTransport(tok string, pins []string) *http.Transport {
	key := PinTag(tok) + "|" + strings.Join(pins, ",")
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.pinned != nil && c.pinnedKey == key {
		return c.pinned
	}
	if c.pinned != nil {
		c.pinned.CloseIdleConnections()
	}
	snapshot := slices.Clone(pins)
	c.pinned = &http.Transport{
		// ⚠ NO PROXY: $HTTPS_PROXY would hand the connection to somebody else's
		// box. The pin would still hold, but nothing here should ever route
		// through a third party.
		Proxy:          nil,
		DialTLSContext: c.dialPinned(snapshot),
		// Only https goes through this transport; refuse rather than dial a
		// cleartext connection if anything ever asks it to.
		DialContext: func(context.Context, string, string) (net.Conn, error) {
			return nil, errors.New("the pinned transport does not dial plain connections")
		},
		ForceAttemptHTTP2:   false,
		MaxIdleConnsPerHost: 4,
		IdleConnTimeout:     30 * time.Second,
	}
	c.pinnedKey = key
	return c.pinned
}

// dialPinned opens a TLS connection whose leaf is in pins, or none at all.
//
// ⚠⚠ THE LEAF IS JUDGED INSIDE THE HANDSHAKE, BEFORE ANY REQUEST BYTE EXISTS
// ON THIS CONNECTION. VerifyConnection runs while the handshake is still in
// progress — before the client's Finished in TLS 1.3, before the key exchange
// in TLS 1.2 — and an error aborts it with an alert. The transport gets the
// conn only after HandshakeContext returns, so a refused peer never receives
// one byte of application data: no headers, no Authorization. (This is what
// Node's JudgedAgent had to build by hand; the test "before any byte" holds
// it to that.) InsecureSkipVerify only turns off the CA/hostname check, which
// the pin replaces (rule 4): the cert is named `huginn-appd` and dialled by IP.
//
// ⚠ THE HANDSHAKE HAS A DEADLINE (Node scan F1). A peer that accepts TCP and
// never finishes TLS would otherwise hold the request — and the serve loop
// waiting on it — for as long as the caller's context allows, which for a
// background context is forever.
func (c *Client) dialPinned(pins []string) func(context.Context, string, string) (net.Conn, error) {
	return func(ctx context.Context, network, addr string) (net.Conn, error) {
		hctx, cancel := context.WithTimeout(ctx, c.hs)
		defer cancel()
		// With other addresses to try, a dead one is given up on sooner.
		d := net.Dialer{Timeout: c.dialT}
		raw, err := d.DialContext(hctx, network, addr)
		if err != nil {
			return nil, c.dialErr(hctx, ctx, addr, c.hs, err)
		}
		host, _, _ := net.SplitHostPort(addr)
		var refused *PinMismatchError
		tc := tls.Client(raw, &tls.Config{
			InsecureSkipVerify: true, // ⚠ ONLY because VerifyConnection below is the check
			VerifyConnection: func(cs tls.ConnectionState) error {
				if len(cs.PeerCertificates) == 0 {
					refused = &PinMismatchError{}
					return refused
				}
				fp := Fingerprint(cs.PeerCertificates[0].Raw)
				if slices.Contains(pins, fp) {
					return nil
				}
				refused = &PinMismatchError{FP: fp}
				return refused
			},
			ServerName: sni(host),
			NextProtos: []string{"http/1.1"},
			MinVersion: tls.VersionTLS12,
			// No ClientSessionCache, so no resumption: a ticket cached under
			// one pin set must never shortcut a handshake under another.
		})
		if err := tc.HandshakeContext(hctx); err != nil {
			raw.Close()
			if refused != nil {
				return nil, refused
			}
			// Before any request byte, like the refusal above (an old router
			// that speaks only TLS 1.1 ends up here).
			return nil, notSent{c.handshakeErr(hctx, ctx, addr, c.hs, err, true, true)}
		}
		return tc, nil
	}
}

func (c *Client) plainTransport() *http.Transport {
	c.mu.Lock()
	defer c.mu.Unlock()
	if c.plain == nil {
		c.plain = &http.Transport{
			Proxy:       nil, // loopback means loopback, not via a proxy
			DialContext: (&net.Dialer{Timeout: c.hs}).DialContext,
			DialTLSContext: func(context.Context, string, string) (net.Conn, error) {
				return nil, errors.New("the loopback transport never dials TLS unpinned")
			},
			MaxIdleConnsPerHost: 4,
			IdleConnTimeout:     30 * time.Second,
		}
	}
	return c.plain
}

// noRedirect: a redirect would send the request somewhere it did not ask
// about. The daemon never redirects, so one is reported as-is.
func noRedirect(*http.Request, []*http.Request) error { return http.ErrUseLastResponse }

func (c *Client) send(ctx context.Context, t *http.Transport, tok, method string, target *url.URL, payload []byte) (*Response, error) {
	var rd io.Reader
	if payload != nil {
		rd = bytes.NewReader(payload)
	}
	req, err := http.NewRequestWithContext(ctx, method, target.String(), rd)
	if err != nil {
		return nil, err
	}
	c.stamp(req)
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Accept", "application/json")
	if payload != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	hc := &http.Client{Transport: t, CheckRedirect: noRedirect}
	res, err := hc.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			// url.Error repeats the full URL; keep the cause, which carries
			// our own typed errors through Unwrap.
			return nil, fmt.Errorf("%s %s: %w", method, target.Path, ue.Err)
		}
		return nil, err
	}
	defer res.Body.Close()
	body, err := io.ReadAll(io.LimitReader(res.Body, maxBody+1))
	if err != nil {
		return nil, fmt.Errorf("%s %s: reading the answer: %w", method, target.Path, err)
	}
	if len(body) > maxBody {
		return nil, fmt.Errorf("%s %s: answer larger than %d bytes", method, target.Path, maxBody)
	}
	return &Response{Status: res.StatusCode, Body: body}, nil
}

// dialErr names a connect that ran out of time as such.
func (c *Client) dialErr(hctx, parent context.Context, addr string, limit time.Duration, err error) error {
	if hctx.Err() != nil && parent.Err() == nil {
		return fmt.Errorf("connecting to %s did not finish within %v", addr, limit)
	}
	return err
}

// handshakeErr turns a failed handshake into something a person can act on.
//
// A pre-3.11 daemon's port speaks only HTTP: our ClientHello gets a plain
// "400 Bad Request" back, which crypto/tls reports as a RecordHeaderError.
// That is refused as an old daemon (rule 8) — never answered by retrying in
// cleartext.
//
// ⚠ UNLESS A PIN IS IN HAND (pinned). The daemon at this token has proved
// itself over TLS, so an answer that is not TLS is somebody else holding the
// address: an interception (rule 5). Calling it an old daemon to update would
// send the person after the wrong thing. Either way nothing was sent.
func (c *Client) handshakeErr(hctx, parent context.Context, addr string, limit time.Duration, err error, inHandshake, pinned bool) error {
	var rh tls.RecordHeaderError
	if errors.As(err, &rh) {
		if pinned {
			return interceptedAt(c.origin(), fmt.Sprintf(" (%s did not answer TLS, and the daemon pinned for this token does)", addr))
		}
		return fmt.Errorf("%s: %w (%s did not answer TLS%s)", c.origin(), ErrOldDaemon, addr, c.noTLSHint())
	}
	// Either clock may ring first: the context, or the conn deadline set from it.
	if (hctx.Err() != nil || errors.Is(err, os.ErrDeadlineExceeded)) && parent.Err() == nil {
		if inHandshake {
			return fmt.Errorf("tls handshake with %s did not finish within %v", addr, limit)
		}
		return fmt.Errorf("challenge at %s did not answer within %v", addr, limit)
	}
	return err
}

func hostPort(u *url.URL) string {
	if p := u.Port(); p != "" {
		return net.JoinHostPort(u.Hostname(), p)
	}
	if u.Scheme == "http" {
		return net.JoinHostPort(u.Hostname(), "80")
	}
	return net.JoinHostPort(u.Hostname(), "443")
}

// sni: an IP literal is not a server name (and Go would not send it anyway).
func sni(host string) string {
	if net.ParseIP(host) != nil {
		return ""
	}
	return host
}

// stamp adds the caller's headers. Authorization is set AFTER this, so a hook
// can never replace or remove the token header.
func (c *Client) stamp(req *http.Request) {
	if c.hdrs == nil {
		return
	}
	for k, v := range c.hdrs() {
		if strings.EqualFold(k, "Authorization") {
			continue
		}
		req.Header.Set(k, v)
	}
}

// Stream opens a token-carrying GET and returns the response with its body
// OPEN, for server-sent events (`/v1/watch?stream=1`). The same TLS rules as
// Do: proved and pinned over https (re-proved once on a rotated leaf), plain
// http only to loopback under HUGINN_APPD_PLAINTEXT=local. No request timeout
// — the caller's ctx ends it, and the caller closes the body. A status of 400
// or above is returned as a *StatusError with the body already closed.
func (c *Client) Stream(ctx context.Context, path string) (*http.Response, error) {
	var res *http.Response
	err := c.withRoutes(ctx, func() error {
		var err error
		res, err = c.stream1(ctx, path)
		return err
	})
	return res, err
}

func (c *Client) stream1(ctx context.Context, path string) (*http.Response, error) {
	target, err := c.resolve(path)
	if err != nil {
		return nil, err
	}
	tok, err := ResolveToken(c.tokens)
	if err != nil {
		return nil, err
	}
	var t *http.Transport
	switch target.Scheme {
	case "https":
		pins := c.loadPins(tok)
		if len(pins) == 0 {
			if pins, err = c.proveAndPin(ctx, tok, nil); err != nil {
				return nil, notSent{err}
			}
		}
		res, err := c.openStream(ctx, c.pinnedTransport(tok, pins), tok, target)
		var pm *PinMismatchError
		if !errors.As(err, &pm) {
			return res, err
		}
		if pins, err = c.proveAndPin(ctx, tok, pins); err != nil {
			return nil, notSent{err} // see pinnedRequest
		}
		res, err = c.openStream(ctx, c.pinnedTransport(tok, pins), tok, target)
		if errors.As(err, &pm) {
			return nil, interceptedAt(c.origin(), fmt.Sprintf(" (certificate %s changed again after it was proved)", pm.FP))
		}
		return res, err
	case "http":
		if err := plainRefusal(target); err != nil {
			return nil, err
		}
		t = c.plainTransport()
	default:
		return nil, fmt.Errorf("%q is not an http(s) url", target.String())
	}
	return c.openStream(ctx, t, tok, target)
}

func (c *Client) openStream(ctx context.Context, t *http.Transport, tok string, target *url.URL) (*http.Response, error) {
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, target.String(), nil)
	if err != nil {
		return nil, err
	}
	c.stamp(req)
	req.Header.Set("Authorization", "Bearer "+tok)
	req.Header.Set("Accept", "text/event-stream")
	hc := &http.Client{Transport: t, CheckRedirect: noRedirect}
	res, err := hc.Do(req)
	if err != nil {
		var ue *url.Error
		if errors.As(err, &ue) {
			return nil, fmt.Errorf("GET %s: %w", target.Path, ue.Err)
		}
		return nil, err
	}
	if res.StatusCode >= 400 {
		b, _ := io.ReadAll(io.LimitReader(res.Body, 4<<10))
		res.Body.Close()
		return nil, &StatusError{Method: http.MethodGet, Path: target.Path, Status: res.StatusCode, Body: Printable(string(b), 200)}
	}
	return res, nil
}
