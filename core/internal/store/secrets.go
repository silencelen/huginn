package store

import (
	"errors"
	"fmt"
	"os"
	"regexp"
	"strings"
)

// Secrets is where credentials live (D11). Values are opaque strings; Get on
// an absent name returns an error wrapping ErrNotFound.
type Secrets interface {
	Get(name string) (string, error)
	Set(name, v string) error
	Delete(name string) error
	Backend() string
}

// Backend names, as recorded in core.json's secretsBackend.
const (
	BackendSecretService = "secret-service"
	BackendCredMan       = "credential-manager"
	BackendFile          = "file"
)

var (
	ErrNotFound    = errors.New("secret not found")
	ErrUnavailable = errors.New("secret backend unavailable")
	// ErrLocked: the keyring is there but locked, and unlocking it needs a
	// prompt the core (a service with no UI) cannot show.
	ErrLocked = errors.New("keyring is locked")
)

// SecretToken is the appd bearer token's name.
const SecretToken = "appd-token"

var nameRE = regexp.MustCompile(`^[a-z0-9][a-z0-9._-]{0,63}$`)

// ValidName reports whether name may be used as a secret name. Restricted so
// the file backend can use it as a filename with no traversal and every
// backend stores the same set of names.
func ValidName(name string) error {
	if !nameRE.MatchString(name) || strings.Contains(name, "..") {
		return fmt.Errorf("store: %q is not a valid secret name", name)
	}
	return nil
}

// Selection is which backend OpenSecrets chose, and why — always printable.
type Selection struct {
	Backend  string
	Why      string
	FellBack bool // the keyring was tried and was not usable
}

// SecretsOptions steer OpenSecrets.
type SecretsOptions struct {
	// Dir is the file fallback's directory (Paths.SecretsDir()).
	Dir string
	// Recorded is core.json's secretsBackend. Once a backend holds the token
	// the choice is STICKY: a keyring that is merely unavailable this time
	// (locked at boot, bus not up yet) must not send the core off to an empty
	// file store and report "no token". Empty = choose now.
	Recorded string
	// Force is "file" or "keyring" (also read from HUGINN_SECRETS when
	// empty); it beats Recorded.
	Force string
}

// OpenSecrets picks a backend: forced, else recorded, else the OS keyring if
// it answers, else the file fallback.
func OpenSecrets(o SecretsOptions) (Secrets, Selection) {
	force := o.Force
	if force == "" {
		force = strings.TrimSpace(os.Getenv("HUGINN_SECRETS"))
	}
	file := func() Secrets { return NewFileSecrets(o.Dir) }
	switch force {
	case BackendFile:
		return file(), Selection{Backend: BackendFile, Why: "file store requested (" + o.Dir + ")"}
	case "keyring":
		k, err := openKeyring()
		if err != nil {
			return unavailableSecrets{name: keyringName(), err: err},
				Selection{Backend: keyringName(), Why: "keyring requested but not usable: " + err.Error()}
		}
		return k, Selection{Backend: k.Backend(), Why: "keyring requested"}
	}
	switch o.Recorded {
	case BackendFile:
		return file(), Selection{Backend: BackendFile, Why: "recorded in core.json: file store (" + o.Dir + ")"}
	case BackendSecretService, BackendCredMan:
		k, err := openKeyring()
		if err != nil {
			return unavailableSecrets{name: o.Recorded, err: err},
				Selection{Backend: o.Recorded, Why: "recorded in core.json, but not usable right now: " + err.Error()}
		}
		return k, Selection{Backend: k.Backend(), Why: "recorded in core.json"}
	}
	k, err := openKeyring()
	if err == nil {
		return k, Selection{Backend: k.Backend(), Why: "the OS keyring answered"}
	}
	return file(), Selection{Backend: BackendFile, FellBack: true,
		Why: "keyring not usable (" + err.Error() + "); using the file store " + o.Dir}
}

// unavailableSecrets is a recorded backend that cannot be reached: every call
// says so, rather than quietly answering from somewhere else.
type unavailableSecrets struct {
	name string
	err  error
}

func (u unavailableSecrets) Get(string) (string, error) { return "", u.wrap() }
func (u unavailableSecrets) Set(string, string) error   { return u.wrap() }
func (u unavailableSecrets) Delete(string) error        { return u.wrap() }
func (u unavailableSecrets) Backend() string            { return u.name }
func (u unavailableSecrets) wrap() error {
	if errors.Is(u.err, ErrUnavailable) || errors.Is(u.err, ErrLocked) {
		return fmt.Errorf("%s: %w", u.name, u.err)
	}
	return fmt.Errorf("%s: %w: %v", u.name, ErrUnavailable, u.err)
}

// CredTarget is the Windows Credential Manager target for name. Pure.
func CredTarget(name string) string { return "huginn/" + name }

// SecretServiceLabel and SecretServiceAttrs are the item label and lookup
// attributes the Linux Secret Service backend uses for name. Pure.
func SecretServiceLabel(name string) string { return "huginn " + name }

func SecretServiceAttrs(name string) map[string]string {
	return map[string]string{"application": "huginn", "name": name}
}
