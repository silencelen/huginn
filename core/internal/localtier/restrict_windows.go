//go:build windows

package localtier

import (
	"fmt"
	"os"
	"path/filepath"
	"unsafe"

	"golang.org/x/sys/windows"
)

// ownerOnlySDDL is the protected DACL the tier's token and its directories
// carry: full control for SYSTEM (the LocalSystem runner reads the token), the
// Administrators (the elevated manager owns the tree), and the current account;
// nothing inherited, nobody else. Pure.
func ownerOnlySDDL(sid string, dir bool) string {
	inh := ""
	if dir {
		inh = "OICI" // object + container inherit: new children get the same ACL
	}
	return fmt.Sprintf("D:P(A;%s;FA;;;SY)(A;%s;FA;;;BA)(A;%s;FA;;;%s)", inh, inh, inh, sid)
}

// selfSID is the current process user's SID as a string.
func selfSID() (string, error) {
	tu, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return "", fmt.Errorf("localtier: current user SID: %w", err)
	}
	return tu.User.Sid.String(), nil
}

// ownerSID reads the owner SID of an existing path; the error is os.IsNotExist
// when the path is absent.
func ownerSID(path string) (string, error) {
	sd, err := windows.GetNamedSecurityInfo(path, windows.SE_FILE_OBJECT,
		windows.OWNER_SECURITY_INFORMATION)
	if err != nil {
		return "", err
	}
	owner, _, err := sd.Owner()
	if err != nil {
		return "", err
	}
	return owner.String(), nil
}

// setProtectedDACL replaces path's DACL with an owner-only protected one.
func setProtectedDACL(path string, dir bool) error {
	sid, err := selfSID()
	if err != nil {
		return err
	}
	sd, err := windows.SecurityDescriptorFromString(ownerOnlySDDL(sid, dir))
	if err != nil {
		return fmt.Errorf("localtier: build ACL: %w", err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return fmt.Errorf("localtier: build ACL: %w", err)
	}
	if err := windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil, nil, dacl, nil); err != nil {
		return fmt.Errorf("localtier: restrict %s: %w", path, err)
	}
	return nil
}

// restrictFile replaces a file's DACL with the protected owner-only one.
func restrictFile(path string) error {
	fi, err := os.Stat(path)
	return setProtectedDACL(path, err == nil && fi.IsDir())
}

// createDirSecure makes dir (and any missing parents) exist with the owner-only
// protected DACL, so a child created in it is never Users-readable.
func createDirSecure(dir, self string) error {
	if _, err := os.Lstat(dir); err == nil {
		return nil // already there
	}
	parent := filepath.Dir(dir)
	if parent != dir {
		if _, err := os.Lstat(parent); os.IsNotExist(err) {
			if err := createDirSecure(parent, self); err != nil {
				return err
			}
		}
	}
	sd, err := windows.SecurityDescriptorFromString(ownerOnlySDDL(self, true))
	if err != nil {
		return fmt.Errorf("localtier: build ACL: %w", err)
	}
	sa := &windows.SecurityAttributes{SecurityDescriptor: sd}
	sa.Length = uint32(unsafe.Sizeof(*sa))
	p, err := windows.UTF16PtrFromString(dir)
	if err != nil {
		return err
	}
	if err := windows.CreateDirectory(p, sa); err != nil && err != windows.ERROR_ALREADY_EXISTS {
		return fmt.Errorf("localtier: create %s: %w", dir, err)
	}
	return nil
}

// secureSeedDir makes dir exist with the owner-only protected DACL before any
// secret is written, and refuses a pre-existing dir that this user, SYSTEM or
// the Administrators do not own. A dir we own is re-locked (mandatory); one the
// elevated manager owns (SYSTEM/Administrators) is trusted and left alone,
// because this unprivileged process cannot and need not rewrite it.
func secureSeedDir(dir string) error {
	self, err := selfSID()
	if err != nil {
		return err
	}
	fi, serr := os.Lstat(dir)
	if serr != nil {
		if os.IsNotExist(serr) {
			return createDirSecure(dir, self)
		}
		return fmt.Errorf("localtier: inspect %s: %w", dir, serr)
	}
	// A reparse point (symlink/junction) where our dir should be is a redirect.
	if fi.Mode()&os.ModeSymlink != 0 {
		return foreignOwnerErr(dir, "a reparse point")
	}
	owner, oerr := ownerSID(dir)
	if oerr != nil {
		return fmt.Errorf("localtier: owner of %s: %w", dir, oerr)
	}
	if !sidTrusted(owner, self) {
		return foreignOwnerErr(dir, owner)
	}
	if owner == self {
		return setProtectedDACL(dir, true) // enforce owner-only; fail loudly
	}
	return nil // SYSTEM/Administrators: the elevated manager locked it
}

// refuseForeignFile declines a token file a different account left in place.
func refuseForeignFile(path string) error {
	fi, err := os.Lstat(path)
	if err != nil {
		if os.IsNotExist(err) {
			return nil
		}
		return err
	}
	if fi.Mode()&os.ModeSymlink != 0 {
		return foreignOwnerErr(path, "a reparse point")
	}
	owner, oerr := ownerSID(path)
	if oerr != nil {
		return oerr
	}
	self, err := selfSID()
	if err != nil {
		return err
	}
	if !sidTrusted(owner, self) {
		return foreignOwnerErr(path, owner)
	}
	return nil
}

// writeTokenTemp writes tok to a random temp file inside the already-protected
// dir (it inherits the owner-only DACL) and returns its path to rename over.
func writeTokenTemp(dir, tok string) (string, error) {
	f, err := os.CreateTemp(dir, ".appd-token-*") // random name, O_EXCL
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
