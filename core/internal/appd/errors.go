package appd

import (
	"errors"
	"fmt"
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
	Body   string // at most 200 bytes, as Node reports it
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
