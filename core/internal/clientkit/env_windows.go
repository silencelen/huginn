//go:build windows

package clientkit

import (
	"os"
	"os/exec"
	"path/filepath"

	"golang.org/x/sys/windows"
)

// DefaultEnv is this machine. $PROFILE lives under the KNOWN Documents
// folder, which OneDrive and folder redirection move away from ~\Documents.
func DefaultEnv() Env {
	home, _ := os.UserHomeDir()
	docs, err := windows.KnownFolderPath(windows.FOLDERID_Documents, 0)
	if err != nil || docs == "" {
		docs = filepath.Join(home, "Documents")
	}
	profiles := []string{filepath.Join(docs, "WindowsPowerShell", "Microsoft.PowerShell_profile.ps1")}
	ps7 := filepath.Join(docs, "PowerShell", "Microsoft.PowerShell_profile.ps1")
	if _, err := exec.LookPath("pwsh.exe"); err == nil {
		profiles = append(profiles, ps7)
	} else if _, err := os.Stat(ps7); err == nil {
		profiles = append(profiles, ps7)
	}
	return Env{GOOS: "windows", Home: home, PSProfiles: profiles}
}
