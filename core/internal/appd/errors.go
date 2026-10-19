package appd

import (
	"errors"
	"fmt"
	"strings"
	"unicode"
	"unicode/utf8"
)

// Kind is how the runner must treat a failed call. It depends on exactly
// these three, so nothing else may be added without the runner learning it.
type Kind int

const (
	KindNone Kind = iota
	// KindTransient: try again later — a daemon that is down, a blip, a 5xx,
	// and also ErrIntercepted / ErrOldDaemon (the runner keeps retrying, as the
	// Node runner does, and the error text says what is wrong).
	KindTransient
	// KindPermanent: 400/403/404/413. Retrying the same request cannot succeed
	// (after a daemon restart the run is simply gone); hammering it was a bug.
	KindPermanent
	// KindAuth: 401, or no token to send. Stop DELIVERING but keep the child:
	// deliberately not folded into permanent, which would cancel the run.
	KindAuth
)

func (k Kind) String() string {
	switch k {
	case KindTransient:
		return "transient"
	case KindPermanent:
		return "permanent"
	case KindAuth:
		return "auth"
	}
	return "none"
}

// StatusError is a response of 400 or above, with the daemon's own text.
type StatusError struct {
	Method string
	Path   string
	Status int
	Body   string // at most 200 bytes, as Node reports it, through Printable
}

func (e *StatusError) Error() string {
	return fmt.Sprintf("%s %s → %d %s", e.Method, e.Path, e.Status, e.Body)
}

// Classify files err into the runner's three buckets. nil is KindNone.
func Classify(err error) Kind {
	if err == nil {
		return KindNone
	}
	var nt *NoTokenError
	if errors.As(err, &nt) {
		return KindAuth
	}
	var se *StatusError
	if errors.As(err, &se) {
		switch se.Status {
		case 401:
			return KindAuth
		case 400, 403, 404, 413:
			return KindPermanent
		}
	}
	return KindTransient
}

// IsPermanent and IsAuth are the runner's two questions, spelled out.
func IsPermanent(err error) bool { return Classify(err) == KindPermanent }
func IsAuth(err error) bool      { return Classify(err) == KindAuth }

// Printable is text a peer sent, fit for one line of an error, a log line or
// a device's status note: at most limit bytes, cut at a character with "…"
// marking the cut. Everything a terminal acts on instead of printing is taken
// out - the C0 controls (line breaks and tabs too, so it stays one line), DEL
// and the C1 controls, which some terminals read as escape sequences - along
// with invisible format characters such as bidi overrides; runs of space fold
// to one, and bytes that are not UTF-8 become U+FFFD. Pure.
//
// ⚠ THE PEER MAY HAVE PROVED NOTHING. The challenge is answered by whoever
// holds the address, and these errors reach the terminal, the log and the
// status note the apps show.
func Printable(s string, limit int) string {
	if limit < 4 {
		limit = 4
	}
	var b strings.Builder
	space := false
	for _, r := range s { // a byte that is not UTF-8 arrives as U+FFFD
		switch {
		case r < 0x20, r == 0x7f, r >= 0x80 && r <= 0x9f, unicode.IsSpace(r):
			space = b.Len() > 0
			continue
		case unicode.Is(unicode.Cf, r):
			continue
		}
		if space {
			b.WriteByte(' ')
			space = false
		}
		b.WriteRune(r)
		if b.Len() > limit {
			break
		}
	}
	out := b.String()
	if len(out) <= limit {
		return out
	}
	cut := limit - len("…")
	for cut > 0 && !utf8.RuneStart(out[cut]) {
		cut--
	}
	return strings.TrimRight(out[:cut], " ") + "…"
}
