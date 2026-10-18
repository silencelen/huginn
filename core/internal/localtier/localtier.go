// Package localtier knows where the local-AI tier (client/huginn-local) lives
// and how its runner gets the appd token: the file the manager reads, which
// the CLI wrapper, the desktop and now the core write (docs/design/desktop-via-core.md).
package localtier

import (
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"runtime"
	"strings"
)

// Well-known SIDs that may own a pre-existing tree we are willing to seed the
// token into: the elevated manager owns it for the Administrators group, and a
// LocalSystem service may have created a child.
const (
	sidSystem         = "S-1-5-18"
	sidAdministrators = "S-1-5-32-544"
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
//
// The token is root-equivalent on the paired huginn host, so it is never a
// Users-readable file for even an instant (security review, 2026-10-18, F6):
//
//   - the containing directories are made owner-only (SYSTEM + the current
//     user; on POSIX 0700) BEFORE the token is written, and a pre-existing tree
//     that this user, SYSTEM or the Administrators group does not own is
//     REFUSED rather than reused — Seed runs unelevated and cannot wrest a
//     foreign-owned tree back, so it declines instead of trusting it;
//   - the token is written through a RANDOM temp name created O_EXCL inside
//     that protected directory (so it inherits the owner-only DACL, or 0600 on
//     POSIX) and renamed over the target, which also defeats a pre-planted
//     fixed-name temp and an attacker handle held open on the old file;
//   - a foreign-owned token file already in place is refused for the same
//     reason a foreign directory is.
//
// Making the directory owner-only is mandatory: if that ACL cannot be applied
// the seed fails. Re-applying the file's own ACL afterwards is best effort —
// once the elevated manager owns the tree a file is not this account's to
// change, and the protected directory is what keeps the token private.
func Seed(dir, tok string) (string, error) {
	tok = strings.TrimSpace(tok)
	if tok == "" {
		return "", errors.New("no token to hand over")
	}
	f := TokenFile(dir)
	devDir := filepath.Dir(f)

	// Own-or-refuse, and make both levels owner-only, before any secret byte.
	if err := secureSeedDir(dir); err != nil {
		return "", err
	}
	if err := secureSeedDir(devDir); err != nil {
		return "", err
	}
	// A token file a different account left in place would keep its owner and
	// ACL under a plain write; refuse it (ENOENT is fine).
	if err := refuseForeignFile(f); err != nil {
		return "", err
	}

	// Unchanged fast-path: the content already matches, so do not rewrite it —
	// just make sure a copy written before the lock is locked now.
	if b, err := os.ReadFile(f); err == nil && strings.TrimSpace(string(b)) == tok {
		bestEffortLock(f)
		return f, nil
	}

	tmp, err := writeTokenTemp(devDir, tok)
	if err != nil {
		return "", err
	}
	if err := os.Rename(tmp, f); err != nil {
		os.Remove(tmp)
		return "", err
	}
	bestEffortLock(f)
	return f, nil
}

// bestEffortLock applies the file's own protected ACL and swallows the error:
// the containing directory (secureSeedDir) is the mandatory fence, and on a
// tree the elevated manager owns this unprivileged process cannot rewrite a
// child's ACL. A var so a test can see it is asked for.
var bestEffortLock = func(path string) { _ = lock(path) }

// lock gives the token file an ACL of its own on Windows: SYSTEM (the runner
// service), the Administrators and this account, nothing inherited. 0600
// means nothing there on Windows, and a file that inherited ProgramData's
// "Users: read" was the appd token readable by every account on the PC
// (security review, 2026-10-17). A var so a test can see it is asked for.
var lock = func(path string) error { return restrictFile(path) }

// sidTrusted reports whether a pre-existing tree owned by ownerSid may be
// adopted: it must belong to the current user, to SYSTEM, or to the local
// Administrators group (the elevated manager owns it for that group). Pure.
func sidTrusted(ownerSid, selfSid string) bool {
	switch ownerSid {
	case selfSid, sidSystem, sidAdministrators:
		return ownerSid != "" // an empty owner is never "us"
	default:
		return false
	}
}

// foreignUID reports whether a POSIX entry owned by ownerUID is one we must
// refuse to seed into: anyone other than the current user, except root, which
// an elevated step may legitimately have created and handed back. Pure.
func foreignUID(ownerUID, selfUID int) bool {
	return ownerUID != selfUID && ownerUID != 0
}

// foreignOwnerErr is the one sentence a refusal reads with, in one place.
func foreignOwnerErr(path, owner string) error {
	return fmt.Errorf("localtier: %s is owned by %s, not this user, SYSTEM or the Administrators — refusing to seed the appd token into a tree another account controls", path, owner)
}
