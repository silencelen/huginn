//go:build windows

package tray

import (
	"crypto/sha256"
	"encoding/hex"
	"fmt"
	"log"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"

	"github.com/silencelen/huginn/core/internal/traystate"
)

// AUMID MUST equal the AppUserModelID on a Start Menu (or Startup) shortcut,
// or Windows drops every toast without an error. It is the desktop's
// (WindowsToastNotifier.AUMID / the NSIS installer), and `huginn core install`
// stamps the tray's own Startup shortcut with it too.
const AUMID = "com.silencelen.huginn.desktop-kt"

const group = "huginn"

// toastScript drives WinRT through PowerShell. A script FILE, and the toast XML
// in another file, so no text from a notice is ever on a command line (a quote
// in a session name cannot become a syntax error or an injection).
const toastScript = `param(
  [Parameter(Mandatory=$true)][string]$Action,
  [string]$Aumid,
  [string]$XmlPath,
  [string]$Tag,
  [string]$Group = 'huginn'
)
$ErrorActionPreference = 'Stop'
try {
  [void][Windows.UI.Notifications.ToastNotificationManager, Windows.UI.Notifications, ContentType=WindowsRuntime]
  [void][Windows.Data.Xml.Dom.XmlDocument, Windows.Data.Xml.Dom, ContentType=WindowsRuntime]
  switch ($Action) {
    'show' {
      $xml = New-Object Windows.Data.Xml.Dom.XmlDocument
      $xml.LoadXml([IO.File]::ReadAllText($XmlPath, [Text.Encoding]::UTF8))
      $toast = New-Object Windows.UI.Notifications.ToastNotification $xml
      $toast.Tag = $Tag
      $toast.Group = $Group
      [Windows.UI.Notifications.ToastNotificationManager]::CreateToastNotifier($Aumid).Show($toast)
    }
    'remove' { [Windows.UI.Notifications.ToastNotificationManager]::History.Remove($Tag, $Group, $Aumid) }
    default { exit 2 }
  }
  exit 0
} catch { exit 1 }
`

type toastNotifier struct {
	log    *log.Logger
	dir    string
	script string
}

func newNotifier(l *log.Logger) Notifier {
	dir := filepath.Join(os.Getenv("LOCALAPPDATA"), "huginn", "state", "toast")
	os.MkdirAll(dir, 0o700)
	script := filepath.Join(dir, "huginn-toast.ps1")
	os.WriteFile(script, []byte(toastScript), 0o600)
	return &toastNotifier{log: l, dir: dir, script: script}
}

// tagFor: a toast tag is limited to 64 characters; a key is not.
func tagFor(key string) string {
	h := sha256.Sum256([]byte(key))
	return hex.EncodeToString(h[:8])
}

func xmlEsc(s string) string {
	return strings.NewReplacer("&", "&amp;", "<", "&lt;", ">", "&gt;", `"`, "&quot;", "'", "&apos;").Replace(s)
}

func (t *toastNotifier) run(args ...string) error {
	cmd := exec.Command("powershell.exe", append([]string{"-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass", "-WindowStyle", "Hidden", "-File", t.script}, args...)...)
	cmd.SysProcAttr = &syscall.SysProcAttr{HideWindow: true}
	if out, err := cmd.CombinedOutput(); err != nil {
		return fmt.Errorf("toast %v: %v %s", args[:2], err, strings.TrimSpace(string(out)))
	}
	return nil
}

func (t *toastNotifier) Post(n traystate.Notice) error {
	var b strings.Builder
	launch := n.URL
	if launch == "" {
		launch = "huginn://open"
	}
	if n.Urgent {
		b.WriteString(`<toast activationType="protocol" launch="` + xmlEsc(launch) + `" scenario="reminder">`)
	} else {
		b.WriteString(`<toast activationType="protocol" launch="` + xmlEsc(launch) + `">`)
	}
	b.WriteString(`<visual><binding template="ToastGeneric"><text>` + xmlEsc(n.Title) + `</text><text>` + xmlEsc(n.Body) + `</text></binding></visual>`)
	if n.Urgent {
		// scenario=reminder needs an action to stay up; "Open" is the honest one.
		b.WriteString(`<actions><action content="Open" activationType="protocol" arguments="` + xmlEsc(launch) + `"/></actions>`)
	}
	b.WriteString(`</toast>`)
	f, err := os.CreateTemp(t.dir, "toast-*.xml")
	if err != nil {
		return err
	}
	defer os.Remove(f.Name())
	f.WriteString(b.String())
	f.Close()
	return t.run("-Action", "show", "-Aumid", AUMID, "-XmlPath", f.Name(), "-Tag", tagFor(n.Key), "-Group", group)
}

func (t *toastNotifier) Withdraw(key string) error {
	return t.run("-Action", "remove", "-Aumid", AUMID, "-Tag", tagFor(key), "-Group", group)
}

// CanDeliver: Windows shows a toast for an AUMID only when a Start Menu or
// Startup shortcut carries it (the core's Huginn.lnk, or the desktop app's
// Start Menu entry). Without one, Show succeeds and nothing appears.
func (t *toastNotifier) CanDeliver() bool {
	base := filepath.Join(os.Getenv("APPDATA"), "Microsoft", "Windows", "Start Menu", "Programs")
	for _, pat := range []string{
		filepath.Join(base, "Startup", "Huginn*.lnk"),
		filepath.Join(base, "Huginn*.lnk"),
		filepath.Join(base, "*", "Huginn*.lnk"),
	} {
		if m, _ := filepath.Glob(pat); len(m) > 0 {
			return true
		}
	}
	return false
}
