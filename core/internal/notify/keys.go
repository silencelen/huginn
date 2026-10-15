package notify

// TargetKind is which of the two lists a notification points at. Ported from
// the desktop's Activation.kt: only the two answers a notification can have,
// so there is no "what do we do with view=settings" branch anywhere.
type TargetKind string

const (
	Chats    TargetKind = "chats"
	Sessions TargetKind = "sessions"
)

// NavTarget is a place in the app, and the key its notification is filed under.
type NavTarget struct {
	Kind TargetKind
	ID   string
}

// Key is stable across a post/withdraw pair, which is the whole job: a
// notification is taken down by key, and a key derived from anything mutable
// (the title, the question) would leave the toast up when the question changed.
//
// ⚠ BYTE-IDENTICAL to NavTarget.key in the Kotlin desktop ("chat:<id>",
// "sess:<id>"). Toasts posted by either side are withdrawn by this tag.
func (t NavTarget) Key() string {
	switch t.Kind {
	case Chats:
		return "chat:" + t.ID
	case Sessions:
		return "sess:" + t.ID
	}
	return ""
}

// SessionKey is the key an attention notice for session name is filed under.
func SessionKey(name string) string { return NavTarget{Sessions, name}.Key() }

// ChatKey is the key a finish notice for chat id is filed under.
func ChatKey(id string) string { return NavTarget{Chats, id}.Key() }

// LimitKey is the key a limit notice is filed under: the SESSION key,
// deliberately — the same one an attention notice uses — so that opening the
// session takes both down and a session cannot end up with a "needs you" and a
// "hit the limit" that outlive each other.
func LimitKey(name string) string { return SessionKey(name) }
