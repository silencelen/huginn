package store

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"strings"
)

// FileSecrets keeps one secret per file under Dir: directory 0700, files
// 0600; on Windows both carry a protected DACL granting only the current user
// and SYSTEM (OwnerOnlySDDL). The fallback for boxes with no usable keyring:
// headless Linux, no session bus, an S4U task with no Credential Manager.
type FileSecrets struct{ Dir string }

// NewFileSecrets returns the file store rooted at dir. Nothing is created
// until the first Set.
func NewFileSecrets(dir string) *FileSecrets { return &FileSecrets{Dir: dir} }

func (f *FileSecrets) Backend() string { return BackendFile }

func (f *FileSecrets) path(name string) (string, error) {
	if err := ValidName(name); err != nil {
		return "", err
	}
	if f.Dir == "" {
		return "", fmt.Errorf("store: file secrets: %w: no directory", ErrUnavailable)
	}
	return filepath.Join(f.Dir, name), nil
}

// Get returns the secret with surrounding whitespace trimmed (a token file
// captured with `cat … > file` carries a newline). Empty reads as not found.
func (f *FileSecrets) Get(name string) (string, error) {
	p, err := f.path(name)
	if err != nil {
		return "", err
	}
	b, err := os.ReadFile(p)
	if errors.Is(err, fs.ErrNotExist) {
		return "", fmt.Errorf("%s: %w", p, ErrNotFound)
	}
	if err != nil {
		return "", fmt.Errorf("store: read %s: %w", p, err)
	}
	v := strings.TrimSpace(string(b))
	if v == "" {
		return "", fmt.Errorf("%s is empty: %w", p, ErrNotFound)
	}
	return v, nil
}

func (f *FileSecrets) Set(name, v string) error {
	p, err := f.path(name)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(f.Dir, 0o700); err != nil {
		return fmt.Errorf("store: create %s: %w", f.Dir, err)
	}
	if !isWindows() {
		// MkdirAll leaves an existing dir's mode alone; this one must be ours only.
		if err := os.Chmod(f.Dir, 0o700); err != nil {
			return fmt.Errorf("store: chmod %s: %w", f.Dir, err)
		}
	}
	if err := restrictToUser(f.Dir, true); err != nil {
		return err
	}
	if err := WriteFileAtomic(p, []byte(v), 0o600); err != nil {
		return err
	}
	return restrictToUser(p, false)
}

// Delete removes the secret; an absent one is success (the wanted state).
func (f *FileSecrets) Delete(name string) error {
	p, err := f.path(name)
	if err != nil {
		return err
	}
	if err := os.Remove(p); err != nil && !errors.Is(err, fs.ErrNotExist) {
		return fmt.Errorf("store: remove %s: %w", p, err)
	}
	return nil
}

// OwnerOnlySDDL is the protected DACL the Windows file store applies: full
// control for sid and for SYSTEM, nothing inherited, nobody else. Pure.
func OwnerOnlySDDL(sid string, dir bool) string {
	inh := ""
	if dir {
		inh = "OICI" // object + container inherit: new files get the same ACL
	}
	return fmt.Sprintf("D:P(A;%s;FA;;;%s)(A;%s;FA;;;SY)", inh, sid, inh)
}
