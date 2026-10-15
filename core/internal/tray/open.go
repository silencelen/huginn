package tray

import (
	"os/exec"
	"runtime"
	"strings"
)

// OpenURL hands a huginn:// URL to the OS, which routes it to the desktop app
// (the scheme handler the app registers). Only huginn:// is ever opened: the
// tray must not become a way to launch arbitrary URLs or programs.
func OpenURL(u string) {
	if !strings.HasPrefix(u, "huginn://") {
		return
	}
	var cmd *exec.Cmd
	switch runtime.GOOS {
	case "windows":
		// rundll32's protocol handler, not `cmd /c start`: no shell parses the URL.
		cmd = exec.Command("rundll32.exe", "url.dll,FileProtocolHandler", u)
	default:
		cmd = exec.Command("xdg-open", u)
	}
	if cmd.Start() == nil {
		go cmd.Wait()
	}
}
