package appd

import (
	"context"
	"errors"
	"fmt"
	"net"
	"net/url"
	"strings"
	"sync"
	"time"
)

// The route book (docs/design/desktop-via-core.md, owner decision 2026-10-16): one
// daemon, several addresses — its LAN address at home, its mesh address away,
// a tailnet name — and the core uses whichever answers. The desktop kept this
// list itself until the core became its only way to huginn.
//
// What switches: a request that never reached appd - the connection was
// refused, timed out or had no route, or the address answered with a
// certificate that is not the proved one (away from home, the LAN address can
// be somebody else's machine; the pin is judged inside the handshake, so
// nothing was sent to it). Such a request is tried once more on the next
// address, which becomes the active one.
// What never switches: anything after the request left - an answer from appd
// (any status), a connection that broke mid-answer (a POST may have been
// done; sending it again elsewhere could do it twice) - and a missing token,
// or the caller giving up. With ONE address, a wrong certificate stays the
// interception error it always was.
//
// Every address is judged by the same pin: the pin is appd's certificate,
// proved by the token, whatever address it is reached on.

// RouteDialTimeout bounds the TCP connect when there is another address to
// try: a dead address is given up on in seconds, not the 20 s handshake limit.
const RouteDialTimeout = 6 * time.Second

// RouteHealth is what the core knows about one address.
type RouteHealth struct {
	URL        string    `json:"url"`
	Active     bool      `json:"active"`
	LastSeenAt time.Time `json:"lastSeenAt,omitzero"`
	LastFailAt time.Time `json:"lastFailAt,omitzero"`
	LastError  string    `json:"lastError,omitempty"`
}

// RouteEvent is a switch of the active address (From/To set) or a change in
// one address's health (Health set).
type RouteEvent struct {
	From, To, Why string
	Health        *RouteHealth
}

type routeState struct {
	rmu      sync.Mutex
	routes   []*url.URL
	active   int
	fixed    bool
	health   []RouteHealth
	onRoute  func(RouteEvent)
	dialT    time.Duration
	lastNote []time.Time // last health report per address, to report a steady state rarely
	gen      uint64      // bumped by every Reroute: an index from before it means nothing
}

func (c *Client) initRoutes(base *url.URL, cfg Config) error {
	list, err := routeList(base, cfg.Routes)
	if err != nil {
		return err
	}
	c.rmu.Lock()
	defer c.rmu.Unlock()
	c.onRoute = cfg.OnRoute
	c.setRoutes(base, list, cfg.Fixed)
	return nil
}

// routeList is base and then the other addresses, normalised and refused as
// New refuses its URL, without repeats.
func routeList(base *url.URL, others []string) ([]*url.URL, error) {
	list := []*url.URL{base}
	seen := map[string]bool{base.String(): true}
	for _, r := range others {
		raw := NormalizeURL(r)
		if err := URLRefusal(raw); err != nil {
			return nil, fmt.Errorf("route %s: %w", r, err)
		}
		u, _ := url.Parse(raw)
		if seen[u.String()] {
			continue
		}
		seen[u.String()] = true
		list = append(list, u)
	}
	return list, nil
}

// setRoutes makes list the address book, on its first address. What is known
// about an address that stays in the book is kept. rmu held.
func (c *Client) setRoutes(base *url.URL, list []*url.URL, fixed bool) {
	was := map[string]int{}
	for i, h := range c.health {
		was[h.URL] = i
	}
	health := make([]RouteHealth, len(list))
	lastNote := make([]time.Time, len(list))
	for i, u := range list {
		health[i].URL = trimURL(u)
		if j, ok := was[health[i].URL]; ok {
			health[i], lastNote[i] = c.health[j], c.lastNote[j]
		}
	}
	c.base, c.routes, c.health, c.lastNote = base, list, health, lastNote
	c.active, c.fixed, c.dialT = 0, fixed, 0
	if len(list) > 1 && !fixed {
		c.dialT = RouteDialTimeout
	}
	c.gen++
}

// Reroute makes first and others this client's addresses, first preferred,
// with no switching when fixed - what New does with Config.URL, Routes and
// Fixed - for the same daemon: its route book was edited, or it was paired
// again on another address.
//
// ⚠ IN PLACE, UNDER WHATEVER IS RUNNING. The core used to rebuild the client
// and restart the device runner for a route edit; a job running then had its
// delivery cut off and its answer lost, and the edit's IPC call waited for the
// job. Everything holding this client (the runner and its job, the watch
// stream, the /appd proxy) uses the new book from its next request; a request
// already on its way finishes where it went.
func (c *Client) Reroute(first string, others []string, fixed bool) error {
	raw := NormalizeURL(first)
	if err := URLRefusal(raw); err != nil {
		return err
	}
	base, _ := url.Parse(raw)
	list, err := routeList(base, others)
	if err != nil {
		return err
	}
	c.rmu.Lock()
	defer c.rmu.Unlock()
	c.setRoutes(base, list, fixed)
	return nil
}

func (c *Client) dialTimeout() time.Duration {
	c.rmu.Lock()
	defer c.rmu.Unlock()
	return c.dialT
}

func trimURL(u *url.URL) string { return strings.TrimSuffix(u.String(), "/") }

// cur is the active address.
func (c *Client) cur() *url.URL {
	c.rmu.Lock()
	defer c.rmu.Unlock()
	if len(c.routes) == 0 {
		return c.base
	}
	return c.routes[c.active]
}

// Routes is a snapshot of every address and what is known about it.
func (c *Client) Routes() []RouteHealth {
	c.rmu.Lock()
	defer c.rmu.Unlock()
	out := make([]RouteHealth, len(c.health))
	copy(out, c.health)
	for i := range out {
		out[i].Active = i == c.active
	}
	return out
}

// withRoutes runs attempt on the active address and, when it never reached
// appd there and another address is allowed, once more on the next one.
func (c *Client) withRoutes(ctx context.Context, attempt func() error) error {
	i, gen := c.activeIndex()
	err := attempt()
	c.note(i, gen, err)
	if !c.notDelivered(ctx, err) {
		return err
	}
	next, gen, ok := c.switchFrom(i, gen, err)
	if !ok {
		return err
	}
	err = attempt()
	c.note(next, gen, err)
	return err
}

func (c *Client) activeIndex() (int, uint64) {
	c.rmu.Lock()
	defer c.rmu.Unlock()
	return c.active, c.gen
}

// notDelivered: the request never reached appd at this address, so another
// address may be tried without doing anything twice.
func (c *Client) notDelivered(ctx context.Context, err error) bool {
	if err == nil || ctx.Err() != nil {
		return false
	}
	var se *StatusError
	if errors.As(err, &se) || errors.Is(err, errBodySent) || Classify(err) == KindAuth {
		return false
	}
	var oe *net.OpError
	if errors.As(err, &oe) && oe.Op == "dial" {
		return true
	}
	// This package's own wording for a connect or handshake that ran out of
	// time (dialErr, handshakeErr): both happen before a request byte exists.
	m := err.Error()
	for _, s := range []string{"did not finish within", "connection refused", "no route to host", "network is unreachable"} {
		if strings.Contains(m, s) {
			return true
		}
	}
	// The address did not prove to be this daemon: a certificate that is not
	// the pinned one, a proof that fails or is answered with anything else (a
	// 404, a web page, a stall), a handshake that could not be finished, or no
	// TLS at all ("older than 3.11" - on an address that is somebody else's
	// machine, that is what they say). The proof never carries the token, so
	// nothing was sent.
	var pm *PinMismatchError
	var ns notSent
	if errors.As(err, &pm) || errors.Is(err, ErrIntercepted) || errors.Is(err, ErrOldDaemon) || errors.As(err, &ns) {
		c.rmu.Lock()
		several := len(c.routes) > 1
		c.rmu.Unlock()
		return several // see the top of this file
	}
	return false
}

// switchFrom moves the active address off failed (an index), to the first
// other address that has not failed more recently than it answered, else
// simply the next one. false: switching is not allowed or there is nowhere
// to go.
func (c *Client) switchFrom(failed int, gen uint64, why error) (int, uint64, bool) {
	c.rmu.Lock()
	if c.fixed || len(c.routes) < 2 {
		c.rmu.Unlock()
		return 0, 0, false
	}
	if c.gen != gen || c.active != failed {
		// The book was edited, or another request already moved it: once
		// more on what is active now.
		i, g := c.active, c.gen
		c.rmu.Unlock()
		return i, g, true
	}
	next := -1
	for k := 1; k < len(c.routes); k++ {
		j := (failed + k) % len(c.routes)
		h := c.health[j]
		if h.LastFailAt.IsZero() || h.LastSeenAt.After(h.LastFailAt) {
			next = j
			break
		}
	}
	if next < 0 {
		next = (failed + 1) % len(c.routes)
	}
	from, to := trimURL(c.routes[failed]), trimURL(c.routes[next])
	c.active = next
	on := c.onRoute
	c.rmu.Unlock()
	c.logf("huginn did not answer at %s (%v); switching to %s", from, why, to)
	if on != nil {
		on(RouteEvent{From: from, To: to, Why: why.Error()})
	}
	return next, gen, true
}

// note records how one attempt on address i of book gen went. A failure that
// is appd's own answer (a status) still proves the address works.
func (c *Client) note(i int, gen uint64, err error) {
	var se *StatusError
	ok := err == nil || errors.As(err, &se)
	link := !ok && c.notDelivered(context.Background(), err)
	if !ok && !link {
		return // not about the address (the token, the caller, an interception)
	}
	c.rmu.Lock()
	if gen != c.gen || i >= len(c.health) {
		c.rmu.Unlock()
		return // about an address of a book edited since
	}
	h := &c.health[i]
	now := time.Now()
	changed := false
	if ok {
		changed = h.LastSeenAt.IsZero() || h.LastFailAt.After(h.LastSeenAt) || now.Sub(c.lastNote[i]) > time.Minute
		h.LastSeenAt = now
		h.LastError = ""
	} else {
		changed = true
		h.LastFailAt = now
		h.LastError = err.Error()
	}
	snap := *h
	snap.Active = i == c.active
	if changed {
		c.lastNote[i] = now
	}
	on := c.onRoute
	c.rmu.Unlock()
	if changed && on != nil {
		on(RouteEvent{Health: &snap})
	}
}

// TryPreferred moves back to the first address when it answers again: a
// pinned TLS handshake, nothing sent. The daemon calls it now and then while
// it is on another address; it never runs in front of a request.
func (c *Client) TryPreferred(ctx context.Context) bool {
	c.rmu.Lock()
	if c.fixed || len(c.routes) < 2 || c.active == 0 {
		c.rmu.Unlock()
		return false
	}
	pref, gen := c.routes[0], c.gen
	c.rmu.Unlock()
	tok, err := ResolveToken(c.tokens)
	if err != nil || pref.Scheme != "https" {
		return false
	}
	pins := c.loadPins(tok)
	if len(pins) == 0 {
		return false
	}
	conn, err := c.dialPinned(pins)(ctx, "tcp", hostPort(pref))
	if err != nil {
		c.note(0, gen, err)
		return false
	}
	conn.Close()
	c.note(0, gen, nil)
	c.rmu.Lock()
	if c.gen != gen {
		c.rmu.Unlock()
		return false // edited meanwhile, and a new book starts on its first address
	}
	from := trimURL(c.routes[c.active])
	c.active = 0
	on := c.onRoute
	c.rmu.Unlock()
	c.logf("huginn answers at %s again; switching back from %s", trimURL(pref), from)
	if on != nil {
		on(RouteEvent{From: from, To: trimURL(pref), Why: "the preferred address answers again"})
	}
	return true
}
