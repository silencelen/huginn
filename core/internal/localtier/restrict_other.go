//go:build !windows

package localtier

import (
	"os"
	"strconv"
	"syscall"
)

// restrictFile: the 0600 the file was written with is the whole ACL here.
func restrictFile(string) error { return nil }

// secureSeedDir makes dir exist and be owner-only (0700), and refuses a
// pre-existing dir that is a symlink or owned by another unprivileged user.
// On POSIX the tier dir lives in the user's own HOME, so a foreign owner is
// the only interesting attack and it is declined rather than chmod'd.
func secureSeedDir(dir string) error {
	if fi, err := os.Lstat(dir); err == nil {
		if fi.Mode()&os.ModeSymlink != 0 {
			return foreignOwnerErr(dir, "a symlink")
		}
		if err := refuseForeignStat(dir, fi); err != nil {
			return err
		}
	}
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	// MkdirAll leaves an existing dir's mode alone; this one must be ours only.
	return os.Chmod(dir, 0o700)
}

// refuseForeignFile declines a token file a different account left in place.
// An absent file is fine — that is the common case.
func refuseForeignFile(path string) error {
	fi, err := os.Lstat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		return foreignOwnerErr(path, "a symlink")
	}
	return refuseForeignStat(path, fi)
}

func refuseForeignStat(path string, fi os.FileInfo) error {
	st, ok := fi.Sys().(*syscall.Stat_t)
	if !ok {
		return nil // no uid to check on this filesystem
	}
	if foreignUID(int(st.Uid), os.Getuid()) {
		return foreignOwnerErr(path, "uid "+strconv.Itoa(int(st.Uid)))
	}
	return nil
}

// writeTokenTemp writes tok to a random temp file (O_EXCL, mode 0600) inside
// the already-protected dir and returns its path for the caller to rename.
func writeTokenTemp(dir, tok string) (string, error) {
	f, err := os.CreateTemp(dir, ".appd-token-*") // random name, O_EXCL, 0600
	if err != nil {
		return "", err
	}
	name := f.Name()
	ok := false
	defer func() {
		if !ok {
			f.Close()
			os.Remove(name)
		}
	}()
	if err := f.Chmod(0o600); err != nil { // belt and braces vs the umask
		return "", err
	}
	if _, err := f.WriteString(tok); err != nil {
		return "", err
	}
	if err := f.Sync(); err != nil {
		return "", err
	}
	if err := f.Close(); err != nil {
		return "", err
	}
	ok = true
	return name, nil
}
