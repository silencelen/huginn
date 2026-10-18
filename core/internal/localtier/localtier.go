// Package localtier knows where the local-AI tier (client/huginn-local) lives
// and how its runner gets the appd token: the file the manager reads, which
// the CLI wrapper, the desktop and now the core write (docs/DESKTOP-VIA-CORE.md).
package localtier

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// Dir is the tier's directory: $HUGINN_LOCAL_DIR, else ProgramData\huginn-local
// on Windows (machine-wide: it runs LocalSystem services), else
// ~/.config/huginn-local. The manager's own localDir() says the same.
func Dir(home string) string {
	if d := os.Getenv("HUGINN_LOCAL_DIR"); d != "" {
		return d
	}
	if runtime.GOOS == "windows" {
		pd := os.Getenv("ProgramData")
		if pd == "" {
			pd = `C:\ProgramData`
		}
		return filepath.Join(pd, "huginn-local")
	}
	return filepath.Join(home, ".config", "huginn-local")
}

// TokenFile is where the tier's runner reads the appd token.
func TokenFile(dir string) string { return filepath.Join(dir, "device", "appd-token") }

// Seed writes tok to the tier's token file, owner-only, when it differs: a
// rotated daemon token must reach a stale seed (the audit found services
// 401ing under an app that worked), and an unchanged one is not rewritten.
func Seed(dir, tok string) (string, error) {
	tok = strings.TrimSpace(tok)
	if tok == "" {
		return "", errors.New("no token to hand over")
	}
	f := TokenFile(dir)
	if b, err := os.ReadFile(f); err == nil && strings.TrimSpace(string(b)) == tok {
		lock(f) // a copy written before the lock gets it now
		return f, nil
	}
	if err := os.MkdirAll(filepath.Dir(f), 0o700); err != nil {
		return "", err
	}
	tmp := f + ".tmp"
	if err := os.WriteFile(tmp, []byte(tok), 0o600); err != nil {
		return "", err
	}
	if err := os.Rename(tmp, f); err != nil {
		os.Remove(tmp)
		return "", err
	}
	lock(f)
	return f, nil
}

// lock gives the token file an ACL of its own on Windows: SYSTEM (the runner
// service), the Administrators and this account, nothing inherited. 0600
// means nothing there, and the file inherited ProgramData's "Users: read" -
// the appd token readable by every account on the PC (security review,
// 2026-10-17). Best effort: the elevated manager locks the whole tree too
// (huginn-local lockTree), and a file it created is not this account's to
// change. A var so a test can see it is asked for.
var lock = func(path string) { _ = restrictFile(path) }
