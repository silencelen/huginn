package appd

import (
	"slices"
	"sync"
)

// PinSet is the pins for ONE paired daemon, tagged with the token that proved
// them. JSON field names match the `tls` object client/huginn-device keeps in
// device.json ({"token": <PinTag>, "pins": [...]}) so a migration can read it
// as-is. Tag is a keyed tag of the token, never the token.
type PinSet struct {
	Tag  string   `json:"token"`
	Pins []string `json:"pins"`
}

// For is the pins that belong to token, newest first; none when the set was
// proved under a different token (rule 7) or holds anything not shaped like a
// fingerprint (a hand-edited file is not a reason to trust garbage).
func (s PinSet) For(token string) []string {
	if token == "" || s.Tag != PinTag(token) {
		return nil
	}
	out := make([]string, 0, len(s.Pins))
	for _, f := range s.Pins {
		if isFingerprint(f) {
			out = append(out, f)
		}
	}
	return out
}

// With is the set after fp was proved under token: fp first, a re-proved pin
// moved to the front rather than duplicated, at most MaxPins. A different
// token starts the set over — the old daemon's pins are not this one's.
func (s PinSet) With(token, fp string) PinSet {
	fp = lowerHex(fp)
	out := []string{fp}
	for _, f := range s.For(token) {
		if f != fp && len(out) < MaxPins {
			out = append(out, f)
		}
	}
	return PinSet{Tag: PinTag(token), Pins: out}
}

// PinStore persists the pin set. The core's store implements it; the client
// loads before every token-carrying request (so another process's re-proof is
// seen) and saves after every successful proof.
type PinStore interface {
	LoadPins() (PinSet, error)
	SavePins(PinSet) error
}

// MemoryPinStore is a PinStore that lives as long as the process. Safe for
// concurrent use. Losing it costs one re-proof, never a refusal.
type MemoryPinStore struct {
	mu  sync.Mutex
	set PinSet
}

func (m *MemoryPinStore) LoadPins() (PinSet, error) {
	m.mu.Lock()
	defer m.mu.Unlock()
	return PinSet{Tag: m.set.Tag, Pins: slices.Clone(m.set.Pins)}, nil
}

func (m *MemoryPinStore) SavePins(s PinSet) error {
	m.mu.Lock()
	defer m.mu.Unlock()
	m.set = PinSet{Tag: s.Tag, Pins: slices.Clone(s.Pins)}
	return nil
}
