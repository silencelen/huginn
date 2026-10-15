//go:build windows

package store

import (
	"fmt"

	"golang.org/x/sys/windows"
)

// restrictToUser replaces path's DACL with OwnerOnlySDDL for the current
// process user, protected from inheritance.
func restrictToUser(path string, dir bool) error {
	tu, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return fmt.Errorf("store: current user SID: %w", err)
	}
	sd, err := windows.SecurityDescriptorFromString(OwnerOnlySDDL(tu.User.Sid.String(), dir))
	if err != nil {
		return fmt.Errorf("store: build ACL: %w", err)
	}
	dacl, _, err := sd.DACL()
	if err != nil {
		return fmt.Errorf("store: build ACL: %w", err)
	}
	err = windows.SetNamedSecurityInfo(path, windows.SE_FILE_OBJECT,
		windows.DACL_SECURITY_INFORMATION|windows.PROTECTED_DACL_SECURITY_INFORMATION,
		nil, nil, dacl, nil)
	if err != nil {
		return fmt.Errorf("store: restrict %s to the current user: %w", path, err)
	}
	return nil
}
