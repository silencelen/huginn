package store

import (
	"bytes"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"sync"
	"time"
)

// SchemaVersion is core.json's "v". Bump it on an incompatible change; a file
// with a NEWER version is refused rather than rewritten (a rolled-back binary
// must not silently strip fields a newer one wrote).
const SchemaVersion = 1

// Config is core.json. No secret ever lives here: the appd token is in
// Secrets, and pins are keyed by a TAG of the token, never the token.
type Config struct {
	V            int                 `json:"v"`
	Device       Device              `json:"device"`
	Appd         Appd                `json:"appd"`
	Pins         map[string][]string `json:"pins,omitempty"`
	Secrets      string              `json:"secretsBackend,omitempty"`
	MigratedFrom *Migration          `json:"migratedFrom,omitempty"`
	// InstallID is this core's X-Huginn-Client: how appd tells this install
	// apart from the phone and the desktop app when it decides who is still
	// listening for notifications. Minted once, kept for the life of the install.
	InstallID string `json:"installId,omitempty"`
}

// Device is this machine's device row as the core minted or adopted it (D13).
type Device struct {
	// Enabled: "Available to huginn". UnenrolOwed: turned off while a row was
	// registered — the runner DELETEs it before forgetting the id (Unenrol.kt).
	Enabled        bool   `json:"enabled"`
	UnenrolOwed    bool   `json:"unenrolOwed,omitempty"`
	ID             string `json:"id,omitempty"`
	Name           string `json:"name,omitempty"`
	Scope          string `json:"scope,omitempty"`
	Root           string `json:"root,omitempty"`
	ActWhileLocked bool   `json:"actWhileLocked"`
	Claude         string `json:"claude,omitempty"`
	LLMSlug        string `json:"llmSlug,omitempty"`
	LLM            string `json:"llm,omitempty"`  // generate engine (huginn local writes it)
	Node           string `json:"node,omitempty"` // node for .js engines / npm shims
}

// Appd is how the core reaches the daemon.
type Appd struct {
	URL string `json:"url,omitempty"`
}

// Migration records the one-time import (§10 P1), so it is never re-run over
// settings the owner has since changed, and so `huginn core status` can say
// where this machine's id came from.
type Migration struct {
	At           time.Time `json:"at"`
	Sources      []string  `json:"sources"`
	DeviceID     string    `json:"deviceId,omitempty"`
	DeviceIDFrom string    `json:"deviceIdFrom,omitempty"`
	Dropped      []string  `json:"dropped,omitempty"` // other ids seen, to be deleted via the API (Q7)
}

// LoadReport says what Load found, beyond the config itself.
type LoadReport struct {
	Path    string
	Existed bool
	// Corrupt is set when core.json did not parse. The bytes were moved to
	// CorruptPath (0600) and an empty config was returned; the caller must
	// tell a person, because whatever was in it is no longer in force.
	Corrupt     bool
	CorruptPath string
	ParseError  string
}

// ErrNewerSchema: core.json was written by a newer core.
var ErrNewerSchema = errors.New("store: core.json was written by a newer huginn; update this one instead of overwriting it")

// Store is core.json plus a lock. Every Update re-reads the file under an
// exclusive OS file lock before applying the change, so `huginn device on`
// and a running `huginn core run` cannot lose each other's writes — the
// Node runner's dropped-pin bug (saveConf over a stale copy) by construction.
type Store struct {
	path string
	mu   sync.Mutex
}

// Open returns the store for paths.Config, creating the directory (0700).
// It does not read; call Load.
func Open(p Paths) (*Store, error) {
	if p.Config == "" {
		return nil, ErrNoHome
	}
	if err := os.MkdirAll(p.Config, 0o700); err != nil {
		return nil, fmt.Errorf("store: create %s: %w", p.Config, err)
	}
	return &Store{path: p.CoreFile()}, nil
}

// Path is core.json's location.
func (s *Store) Path() string { return s.path }

// Load reads core.json. A missing or empty file is a zero Config (V set).
func (s *Store) Load() (Config, LoadReport, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	unlock, err := lockFile(s.path + ".lock")
	if err != nil {
		return Config{}, LoadReport{Path: s.path}, err
	}
	defer unlock()
	return s.loadLocked()
}

func (s *Store) loadLocked() (Config, LoadReport, error) {
	rep := LoadReport{Path: s.path}
	b, err := os.ReadFile(s.path)
	if errors.Is(err, fs.ErrNotExist) {
		return Config{V: SchemaVersion}, rep, nil
	}
	if err != nil {
		// Unreadable is NOT corrupt: moving a file we could not read aside
		// would lose it for a permissions problem.
		return Config{}, rep, fmt.Errorf("store: read %s: %w", s.path, err)
	}
	rep.Existed = true
	if len(bytes.TrimSpace(b)) == 0 {
		return Config{V: SchemaVersion}, rep, nil
	}
	cfg, perr := ParseConfig(b)
	if errors.Is(perr, ErrNewerSchema) {
		return Config{}, rep, perr
	}
	if perr != nil {
		rep.Corrupt, rep.ParseError = true, perr.Error()
		rep.CorruptPath = s.path + ".corrupt"
		// Rename, not copy: the salvage keeps the original's mode (0600) and
		// nothing else ever re-reads it as config.
		if err := os.Rename(s.path, rep.CorruptPath); err != nil {
			return Config{}, rep, fmt.Errorf("store: %s does not parse (%v) and could not be moved aside: %w", s.path, perr, err)
		}
		return Config{V: SchemaVersion}, rep, nil
	}
	return cfg, rep, nil
}

// ParseConfig decodes core.json bytes. Pure.
func ParseConfig(b []byte) (Config, error) {
	var c Config
	if err := json.Unmarshal(b, &c); err != nil {
		return Config{}, err
	}
	if c.V > SchemaVersion {
		return Config{}, ErrNewerSchema
	}
	if c.V == 0 {
		c.V = SchemaVersion
	}
	return c, nil
}

// EncodeConfig is the exact bytes Save writes. Pure.
func EncodeConfig(c Config) ([]byte, error) {
	c.V = SchemaVersion
	b, err := json.MarshalIndent(c, "", "  ")
	if err != nil {
		return nil, err
	}
	return append(b, '\n'), nil
}

// Update applies fn to the CURRENT on-disk config and writes the result
// atomically. A corrupt file is moved aside first (reported in LoadReport);
// fn returning an error writes nothing.
func (s *Store) Update(fn func(*Config) error) (Config, LoadReport, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	unlock, err := lockFile(s.path + ".lock")
	if err != nil {
		return Config{}, LoadReport{Path: s.path}, err
	}
	defer unlock()
	cfg, rep, err := s.loadLocked()
	if err != nil {
		return Config{}, rep, err
	}
	if err := fn(&cfg); err != nil {
		return Config{}, rep, err
	}
	b, err := EncodeConfig(cfg)
	if err != nil {
		return Config{}, rep, err
	}
	if err := WriteFileAtomic(s.path, b, 0o600); err != nil {
		return Config{}, rep, err
	}
	return cfg, rep, nil
}

// Pins returns the pin set stored under tag (the appd package's PinStore).
func (s *Store) Pins(tag string) ([]string, error) {
	c, _, err := s.Load()
	if err != nil {
		return nil, err
	}
	return append([]string(nil), c.Pins[tag]...), nil
}

// SetPins replaces the pin set under tag; nil/empty deletes it.
func (s *Store) SetPins(tag string, pins []string) error {
	_, _, err := s.Update(func(c *Config) error {
		if len(pins) == 0 {
			delete(c.Pins, tag)
			return nil
		}
		if c.Pins == nil {
			c.Pins = map[string][]string{}
		}
		c.Pins[tag] = append([]string(nil), pins...)
		return nil
	})
	return err
}

// PinStore is what the appd package needs; *Store implements it. Pins are
// opaque here: the tag scheme, ordering and cap belong to appd.
type PinStore interface {
	Pins(tag string) ([]string, error)
	SetPins(tag string, pins []string) error
}

var _ PinStore = (*Store)(nil)

// WriteFileAtomic writes b to path through a temp file in the same directory
// and a rename, so a reader never sees half a file and a crash leaves the old
// one. The temp file is created with mode before any byte is written.
func WriteFileAtomic(path string, b []byte, mode os.FileMode) error {
	dir := filepath.Dir(path)
	tmp, err := os.CreateTemp(dir, "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return fmt.Errorf("store: write %s: %w", path, err)
	}
	name := tmp.Name()
	ok := false
	defer func() {
		if !ok {
			tmp.Close()
			os.Remove(name)
		}
	}()
	if err := tmp.Chmod(mode); err != nil && !isWindows() {
		return fmt.Errorf("store: chmod %s: %w", name, err)
	}
	if _, err := tmp.Write(b); err != nil {
		return fmt.Errorf("store: write %s: %w", name, err)
	}
	if err := tmp.Sync(); err != nil {
		return fmt.Errorf("store: sync %s: %w", name, err)
	}
	if err := tmp.Close(); err != nil {
		return fmt.Errorf("store: close %s: %w", name, err)
	}
	// os.Rename replaces on Windows too (MoveFileEx MOVEFILE_REPLACE_EXISTING)
	// — the desktop lost a token to File.renameTo not doing so.
	if err := os.Rename(name, path); err != nil {
		return fmt.Errorf("store: replace %s: %w", path, err)
	}
	ok = true
	return nil
}
