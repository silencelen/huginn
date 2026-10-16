package service

import (
	"bytes"
	"encoding/base64"
	"encoding/binary"
	"encoding/xml"
	"fmt"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf16"
)

// LogonType is how the boot task runs as the user (§11 Q2, still open).
type LogonType string

const (
	// LogonS4U: no stored password; no network credentials and (expected) no
	// Credential Manager — the store falls back to the secrets file.
	LogonS4U LogonType = "S4U"
	// LogonPassword: the password is stored by Task Scheduler; works
	// everywhere, breaks when the password changes.
	LogonPassword LogonType = "Password"
)

func xmlText(s string) string {
	var b bytes.Buffer
	xml.EscapeText(&b, []byte(s))
	return b.String()
}

// windowsParent is everything before the last separator (either kind) — not
// filepath.Dir, which on Linux does not know `\`. Pure.
func windowsParent(p string) string {
	i := strings.LastIndexAny(p, `\/`)
	if i <= 0 {
		return p
	}
	if i == 2 && p[1] == ':' { // C:\huginn.exe → C:\
		return p[:3]
	}
	return p[:i]
}

// TaskXML is the "Huginn Core" task definition for schtasks /Create /XML.
// user is DOMAIN\name (or a SID). Pure; the file is written UTF-16 by
// EncodeUTF16 because schtasks rejects the declaration otherwise.
func TaskXML(binary, user string, logon LogonType) string {
	if logon == "" {
		logon = LogonS4U
	}
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-16"?>` + "\r\n")
	b.WriteString(`<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">` + "\r\n")
	b.WriteString("  <RegistrationInfo>\r\n")
	b.WriteString("    <Author>huginn</Author>\r\n")
	b.WriteString("    <Description>huginn core: keeps this machine available to huginn from boot, with nobody signed in. Written by `huginn core install`.</Description>\r\n")
	b.WriteString(`    <URI>\` + xmlText(TaskName) + "</URI>\r\n")
	b.WriteString("  </RegistrationInfo>\r\n")
	// ⚠ THREE TRIGGERS, NOT ONE (measured on PC01 2026-10-14). A boot
	// trigger alone fired 13 s after power-on, LogonUserS4U failed (0x80070057:
	// S4U for a DOMAIN account needs a DC, and the network was not up yet), and
	// Task Scheduler never tried again — the core stayed down even after a
	// sign-in. So: boot (delayed), a repeat every 5 minutes for ever, and the
	// user's own logon. IgnoreNew drops a start while one runs, and a second
	// `core run` exits 0 when a core already serves the socket, so the repeat
	// is a self-heal for every way the core can be down, not a duplicate.
	b.WriteString("  <Triggers>\r\n")
	b.WriteString("    <BootTrigger>\r\n")
	b.WriteString("      <Enabled>true</Enabled>\r\n")
	b.WriteString("      <Delay>PT30S</Delay>\r\n")
	b.WriteString("    </BootTrigger>\r\n")
	b.WriteString("    <TimeTrigger>\r\n")
	b.WriteString("      <Repetition>\r\n")
	b.WriteString("        <Interval>PT5M</Interval>\r\n")
	b.WriteString("        <StopAtDurationEnd>false</StopAtDurationEnd>\r\n")
	b.WriteString("      </Repetition>\r\n")
	b.WriteString("      <StartBoundary>2026-01-01T00:00:00</StartBoundary>\r\n")
	b.WriteString("      <Enabled>true</Enabled>\r\n")
	b.WriteString("    </TimeTrigger>\r\n")
	b.WriteString("    <LogonTrigger>\r\n")
	b.WriteString("      <Enabled>true</Enabled>\r\n")
	b.WriteString("      <UserId>" + xmlText(user) + "</UserId>\r\n")
	b.WriteString("    </LogonTrigger>\r\n")
	b.WriteString("  </Triggers>\r\n")
	b.WriteString("  <Principals>\r\n")
	b.WriteString(`    <Principal id="Author">` + "\r\n")
	b.WriteString("      <UserId>" + xmlText(user) + "</UserId>\r\n")
	b.WriteString("      <LogonType>" + xmlText(string(logon)) + "</LogonType>\r\n")
	b.WriteString("      <RunLevel>LeastPrivilege</RunLevel>\r\n")
	b.WriteString("    </Principal>\r\n")
	b.WriteString("  </Principals>\r\n")
	b.WriteString("  <Settings>\r\n")
	b.WriteString("    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n")
	b.WriteString("    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\r\n")
	b.WriteString("    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\r\n")
	b.WriteString("    <AllowHardTerminate>true</AllowHardTerminate>\r\n")
	b.WriteString("    <StartWhenAvailable>true</StartWhenAvailable>\r\n")
	b.WriteString("    <RunOnlyIfNetworkAvailable>false</RunOnlyIfNetworkAvailable>\r\n")
	b.WriteString("    <IdleSettings>\r\n")
	b.WriteString("      <StopOnIdleEnd>false</StopOnIdleEnd>\r\n")
	b.WriteString("      <RestartOnIdle>false</RestartOnIdle>\r\n")
	b.WriteString("    </IdleSettings>\r\n")
	b.WriteString("    <AllowStartOnDemand>true</AllowStartOnDemand>\r\n")
	b.WriteString("    <Enabled>true</Enabled>\r\n")
	b.WriteString("    <Hidden>false</Hidden>\r\n")
	b.WriteString("    <RunOnlyIfIdle>false</RunOnlyIfIdle>\r\n")
	b.WriteString("    <WakeToRun>false</WakeToRun>\r\n")
	b.WriteString("    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\r\n")
	b.WriteString("    <Priority>7</Priority>\r\n")
	b.WriteString("    <RestartOnFailure>\r\n")
	b.WriteString("      <Interval>PT1M</Interval>\r\n")
	b.WriteString("      <Count>999</Count>\r\n")
	b.WriteString("    </RestartOnFailure>\r\n")
	b.WriteString("  </Settings>\r\n")
	b.WriteString(`  <Actions Context="Author">` + "\r\n")
	b.WriteString("    <Exec>\r\n")
	b.WriteString("      <Command>" + xmlText(binary) + "</Command>\r\n")
	b.WriteString("      <Arguments>core run</Arguments>\r\n")
	b.WriteString("      <WorkingDirectory>" + xmlText(windowsParent(binary)) + "</WorkingDirectory>\r\n")
	b.WriteString("    </Exec>\r\n")
	b.WriteString("  </Actions>\r\n")
	b.WriteString("</Task>\r\n")
	return b.String()
}

// TrayTaskXML is the "Huginn Tray" task: the tray at the user's own logon, in
// their session (InteractiveToken), at normal priority. It exists because the
// Startup folder runs only after Windows has worked through every Run-key app
// one by one, each allowed 30 s: on PC01 (2026-10-16) the tray appeared
// 3.5 minutes after sign-in. A logon task starts at once. The Startup shortcut
// stays as the fallback (Energy Saver can hold tasks back); the one-tray lock
// makes the second start leave quietly.
func TrayTaskXML(binary, user string) string {
	var b strings.Builder
	b.WriteString(`<?xml version="1.0" encoding="UTF-16"?>` + "\r\n")
	b.WriteString(`<Task version="1.2" xmlns="http://schemas.microsoft.com/windows/2004/02/mit/task">` + "\r\n")
	b.WriteString("  <RegistrationInfo>\r\n")
	b.WriteString("    <Author>huginn</Author>\r\n")
	b.WriteString("    <Description>huginn tray: the huginn icon and notifications, at sign-in. Written by huginn core.</Description>\r\n")
	b.WriteString(`    <URI>\` + xmlText(TrayTaskName) + "</URI>\r\n")
	b.WriteString("  </RegistrationInfo>\r\n")
	b.WriteString("  <Triggers>\r\n")
	b.WriteString("    <LogonTrigger>\r\n")
	b.WriteString("      <Enabled>true</Enabled>\r\n")
	b.WriteString("      <UserId>" + xmlText(user) + "</UserId>\r\n")
	// ⚠ NOT AT THE INSTANT OF SIGN-IN: Explorer is still building the
	// taskbar then, and a tray started in that second added a blank icon and
	// never finished (PC01, 2026-10-16). Ten seconds is still minutes
	// ahead of the Startup folder.
	b.WriteString("      <Delay>PT10S</Delay>\r\n")
	b.WriteString("    </LogonTrigger>\r\n")
	b.WriteString("  </Triggers>\r\n")
	b.WriteString("  <Principals>\r\n")
	b.WriteString(`    <Principal id="Author">` + "\r\n")
	b.WriteString("      <UserId>" + xmlText(user) + "</UserId>\r\n")
	b.WriteString("      <LogonType>InteractiveToken</LogonType>\r\n")
	b.WriteString("      <RunLevel>LeastPrivilege</RunLevel>\r\n")
	b.WriteString("    </Principal>\r\n")
	b.WriteString("  </Principals>\r\n")
	b.WriteString("  <Settings>\r\n")
	b.WriteString("    <MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>\r\n")
	b.WriteString("    <DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>\r\n")
	b.WriteString("    <StopIfGoingOnBatteries>false</StopIfGoingOnBatteries>\r\n")
	b.WriteString("    <AllowStartOnDemand>true</AllowStartOnDemand>\r\n")
	b.WriteString("    <Enabled>true</Enabled>\r\n")
	b.WriteString("    <ExecutionTimeLimit>PT0S</ExecutionTimeLimit>\r\n")
	b.WriteString("    <Priority>5</Priority>\r\n")
	b.WriteString("  </Settings>\r\n")
	// Through `conhost --headless`: huginn.exe is a console program (it is the
	// CLI too), so started directly by the task it flashed a console window at
	// every sign-in before the tray detached from it (PC01, 2026-10-16).
	// A headless conhost gives it a console nobody sees; Windows has started
	// every pseudo-console this way since 1809.
	b.WriteString(`  <Actions Context="Author">` + "\r\n")
	b.WriteString("    <Exec>\r\n")
	b.WriteString("      <Command>%SystemRoot%\\System32\\conhost.exe</Command>\r\n")
	b.WriteString("      <Arguments>" + xmlText(`--headless "`+binary+`" core tray --at-login`) + "</Arguments>\r\n")
	b.WriteString("      <WorkingDirectory>" + xmlText(windowsParent(binary)) + "</WorkingDirectory>\r\n")
	b.WriteString("    </Exec>\r\n")
	b.WriteString("  </Actions>\r\n")
	b.WriteString("</Task>\r\n")
	return b.String()
}

// EnsureTrayTaskWindows registers the "Huginn Tray" logon task (no admin
// needed: it runs as this user, in their session). A refusal is a Warning —
// the Startup shortcut still starts the tray, only later.
func EnsureTrayTaskWindows(o WindowsOptions) (State, string) {
	if o.NoTray {
		return Skipped, "not requested"
	}
	if err := os.MkdirAll(o.StateDir, 0o700); err != nil {
		return Warning, err.Error()
	}
	p := filepath.Join(o.StateDir, "huginn-tray-task.xml")
	if err := os.WriteFile(p, EncodeUTF16(TrayTaskXML(o.Binary, o.User)), 0o600); err != nil {
		return Warning, err.Error()
	}
	defer os.Remove(p)
	res := o.Runner.Run("schtasks", "/Create", "/TN", TrayTaskName, "/XML", p, "/F")
	if !res.OK() {
		return Warning, "the logon task was refused (" + res.Why() + "); the Startup shortcut starts the tray instead, a little later"
	}
	return Done, "the logon task \"" + TrayTaskName + "\" starts it seconds after sign-in"
}

// TrayTaskRegistered: the "Huginn Tray" task exists.
func TrayTaskRegistered(o WindowsOptions) bool {
	return o.Runner.Run("schtasks", "/Query", "/TN", TrayTaskName).OK()
}

// PasswordRegisterScript registers the task from its XML with a stored password
// read from STDIN — the password is never in this script, which travels in argv.
// Pure.
func PasswordRegisterScript(xmlPath, user string) string {
	return "$ErrorActionPreference = 'Stop'\n" +
		"$pw = [Console]::In.ReadLine()\n" +
		"Register-ScheduledTask -TaskName " + psQuote(TaskName) +
		" -Xml (Get-Content -Raw -LiteralPath " + psQuote(xmlPath) + ")" +
		" -User " + psQuote(user) + " -Password $pw -Force | Out-Null\n"
}

// EncodeUTF16 is s as UTF-16LE with a BOM. Pure.
func EncodeUTF16(s string) []byte {
	u := utf16.Encode([]rune(s))
	out := make([]byte, 2+2*len(u))
	out[0], out[1] = 0xFF, 0xFE
	for i, c := range u {
		binary.LittleEndian.PutUint16(out[2+2*i:], c)
	}
	return out
}

func psQuote(s string) string { return "'" + strings.ReplaceAll(s, "'", "''") + "'" }

// shellLinkCS stamps System.AppUserModel.ID onto a .lnk: IShellLinkW →
// IPropertyStore.SetValue(PKEY_AppUserModel_ID) → Commit → IPersistFile.Save,
// the same four-interface dance the NSIS WinShell plugin does. WScript.Shell
// cannot set the AUMID, and without it toasts from the tray role would not
// carry the desktop's identity.
const shellLinkCS = `using System;
using System.Runtime.InteropServices;
using System.Runtime.InteropServices.ComTypes;
namespace HuginnLnk {
  [ComImport, Guid("000214F9-0000-0000-C000-000000000046"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IShellLinkW {
    void GetPath(IntPtr f, int c, IntPtr fd, uint fl);
    void GetIDList(out IntPtr p);
    void SetIDList(IntPtr p);
    void GetDescription(IntPtr s, int c);
    void SetDescription([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetWorkingDirectory(IntPtr s, int c);
    void SetWorkingDirectory([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetArguments(IntPtr s, int c);
    void SetArguments([MarshalAs(UnmanagedType.LPWStr)] string s);
    void GetHotkey(out short h);
    void SetHotkey(short h);
    void GetShowCmd(out int c);
    void SetShowCmd(int c);
    void GetIconLocation(IntPtr s, int c, out int i);
    void SetIconLocation([MarshalAs(UnmanagedType.LPWStr)] string s, int i);
    void SetRelativePath([MarshalAs(UnmanagedType.LPWStr)] string s, uint r);
    void Resolve(IntPtr h, uint f);
    void SetPath([MarshalAs(UnmanagedType.LPWStr)] string s);
  }
  [ComImport, Guid("886D8EEB-8CF2-4446-8D02-CDBA1DBDCF99"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
  interface IPropertyStore {
    void GetCount(out uint c);
    void GetAt(uint i, out PropertyKey k);
    void GetValue(ref PropertyKey k, IntPtr v);
    void SetValue(ref PropertyKey k, ref PropVariant v);
    void Commit();
  }
  [StructLayout(LayoutKind.Sequential, Pack = 4)]
  public struct PropertyKey { public Guid fmtid; public uint pid; }
  [StructLayout(LayoutKind.Explicit, Size = 24)]
  public struct PropVariant { [FieldOffset(0)] public ushort vt; [FieldOffset(8)] public IntPtr p; }
  [ComImport, Guid("00021401-0000-0000-C000-000000000046")]
  class CShellLink {}
  public static class Maker {
    public static void Make(string lnk, string exe, string args, string dir, string desc, string aumid) {
      var link = (IShellLinkW)new CShellLink();
      link.SetPath(exe);
      link.SetArguments(args);
      link.SetShowCmd(7); // SW_SHOWMINNOACTIVE: the console the tray role detaches from never takes focus
      link.SetWorkingDirectory(dir);
      link.SetDescription(desc);
      var store = (IPropertyStore)link;
      var key = new PropertyKey { fmtid = new Guid("9F4C2855-9F79-4B39-A8D0-E1D42DE1D5F3"), pid = 5 };
      var pv = new PropVariant { vt = 31, p = Marshal.StringToCoTaskMemUni(aumid) };
      try { store.SetValue(ref key, ref pv); store.Commit(); }
      finally { Marshal.FreeCoTaskMem(pv.p); }
      ((IPersistFile)link).Save(lnk, true);
    }
  }
}`

// ShortcutScript is the PowerShell that writes the tray role's Startup
// shortcut (`huginn.exe core tray`, AUMID stamped). Single-quoted literals
// with doubled apostrophes, as Autostart.kt. Pure.
func ShortcutScript(lnkPath, exePath string) string {
	var b strings.Builder
	b.WriteString("$ErrorActionPreference='Stop'\n")
	b.WriteString("Add-Type -TypeDefinition @'\n" + shellLinkCS + "\n'@\n")
	fmt.Fprintf(&b, "New-Item -ItemType Directory -Force -Path %s | Out-Null\n", psQuote(windowsParent(lnkPath)))
	fmt.Fprintf(&b, "[HuginnLnk.Maker]::Make(%s, %s, 'core tray', %s, 'Huginn', %s)\n",
		psQuote(lnkPath), psQuote(exePath), psQuote(windowsParent(exePath)), psQuote(AUMID))
	fmt.Fprintf(&b, "if (-not (Test-Path -LiteralPath %s)) { throw 'the shortcut was not written' }\n", psQuote(lnkPath))
	return b.String()
}

// EncodedCommand is script in the form powershell -EncodedCommand takes
// (base64 of UTF-16LE, no BOM), which sidesteps every command-line quoting
// rule between Go and PowerShell. Pure.
func EncodedCommand(script string) string {
	return base64.StdEncoding.EncodeToString(EncodeUTF16(script)[2:])
}

// WindowsOptions are the inputs of the Windows flows. Paths are real local
// paths (the flows touch the filesystem through them); script and XML text
// use them verbatim.
type WindowsOptions struct {
	Binary    string // %LOCALAPPDATA%\huginn\bin\huginn.exe
	User      string // DOMAIN\name
	AppData   string // %APPDATA%, for the Startup folder
	StateDir  string // where the task XML is staged
	Logon     LogonType
	Password  string // LogonPassword only; passed to schtasks /RP
	NoTray    bool
	Runner    Runner
	StartupFn func(appData string) string // tests override the Startup-folder join
}

// StartupLnk is the tray shortcut's path.
func (o WindowsOptions) StartupLnk() string {
	if o.StartupFn != nil {
		return o.StartupFn(o.AppData)
	}
	return filepath.Join(o.AppData, "Microsoft", "Windows", "Start Menu", "Programs", "Startup", TrayShortcut)
}

func accessHint(d string) string {
	l := strings.ToLower(d)
	if strings.Contains(l, "access is denied") || strings.Contains(l, "0x80070005") {
		return d + " — registering a boot task needs one elevated (UAC) run: run `huginn core install` from an administrator prompt"
	}
	return d
}

// InstallWindows registers and starts the task and writes the tray shortcut.
func InstallWindows(o WindowsOptions) Report {
	r := o.Runner
	logon := o.Logon
	if logon == "" {
		logon = LogonS4U
	}
	xmlPath := filepath.Join(o.StateDir, staleTaskFile)
	steps := []step{
		{"check binary", func() (State, string) { return checkBinary(o.Binary, true) }},
		{"check logon type", func() (State, string) {
			switch logon {
			case LogonS4U:
				return Done, "S4U (no stored password; secrets use the file store)"
			case LogonPassword:
				if o.Password == "" {
					return Failed, "a stored-password task needs the account's password"
				}
				return Done, "stored password"
			}
			return Failed, fmt.Sprintf("unknown logon type %q", logon)
		}},
		{"write task definition", func() (State, string) {
			if err := os.MkdirAll(o.StateDir, 0o700); err != nil {
				return Failed, err.Error()
			}
			if err := os.WriteFile(xmlPath, EncodeUTF16(TaskXML(o.Binary, o.User, logon)), 0o600); err != nil {
				return Failed, err.Error()
			}
			return Done, xmlPath
		}},
		{"register task \"" + TaskName + "\"", func() (State, string) {
			var res Result
			if logon == LogonPassword {
				// ⚠ NEVER `schtasks /RP <password>`: argv is recorded by
				// process-creation auditing and readable by other processes while
				// it runs (security scan F3, 2026-10-15). The password goes to
				// PowerShell on STDIN and into Register-ScheduledTask from there.
				ir, ok := r.(InputRunner)
				if !ok {
					os.Remove(xmlPath)
					return Failed, "this runner cannot pass a password on stdin"
				}
				res = ir.RunInput(o.Password+"\n", "powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
					"-EncodedCommand", EncodedCommand(PasswordRegisterScript(xmlPath, o.User)))
			} else {
				res = r.Run("schtasks", "/Create", "/TN", TaskName, "/XML", xmlPath, "/F")
			}
			os.Remove(xmlPath)
			if !res.OK() {
				return Failed, accessHint(res.Why())
			}
			return Done, string(logon)
		}},
		{"stop a running core", func() (State, string) {
			// ⚠ An UPDATE must end the running instance: self-install renamed its
			// .exe aside, so without this the OLD version keeps serving (from
			// huginn.exe.old) until the next reboot, while status reports the new
			// one installed. AFTER the registration on purpose: a registration
			// that fails (no admin) must leave the running core alone, not stop
			// it. Not running / no task yet is fine.
			res := r.Run("schtasks", "/End", "/TN", TaskName)
			if !res.OK() {
				return Unchanged, "none running"
			}
			return Done, "ended the running instance; it restarts below"
		}},
		{"start task", func() (State, string) { return cmdStep(r, "schtasks", "/Run", "/TN", TaskName) }},
		{"tray shortcut", func() (State, string) { return EnsureTrayWindows(o) }},
	}
	rep := run("install", steps)
	if rep.OK() && logon == LogonS4U {
		rep.Notes = append(rep.Notes, "S4U task: it retries every 5 minutes, so a boot before the network (and the domain controller) is up costs minutes, not the day. The appd token lives in the user-only secrets file.")
	}
	return rep
}

// EnsureTrayWindows writes the tray role's Startup shortcut (install, and an
// update from a core that predates the tray).
func EnsureTrayWindows(o WindowsOptions) (State, string) {
	if o.NoTray {
		return Skipped, "not requested"
	}
	lnk := o.StartupLnk()
	res := o.Runner.Run("powershell", "-NoProfile", "-NonInteractive", "-ExecutionPolicy", "Bypass",
		"-EncodedCommand", EncodedCommand(ShortcutScript(lnk, o.Binary)))
	if !res.OK() {
		return Failed, res.Why()
	}
	if _, err := os.Stat(lnk); err != nil {
		return Failed, "powershell reported success but nothing is at " + lnk
	}
	if st, d := EnsureTrayTaskWindows(o); st != Done {
		return Done, lnk + " (" + d + ")"
	}
	return Done, lnk + " and the logon task \"" + TrayTaskName + "\""
}

// UninstallWindows stops and deletes the task and removes the shortcut.
func UninstallWindows(o WindowsOptions) Report {
	r := o.Runner
	present := r.Run("schtasks", "/Query", "/TN", TaskName).OK()
	steps := []step{
		{"stop task", func() (State, string) {
			if !present {
				return Skipped, "no task registered"
			}
			res := r.Run("schtasks", "/End", "/TN", TaskName)
			if !res.OK() {
				// Not running is fine; a real failure surfaces at /Delete.
				return Warning, res.Why()
			}
			return Done, ""
		}},
		{"delete task \"" + TaskName + "\"", func() (State, string) {
			if !present {
				return Skipped, "not there"
			}
			res := r.Run("schtasks", "/Delete", "/TN", TaskName, "/F")
			if !res.OK() {
				return Failed, accessHint(res.Why())
			}
			return Done, ""
		}},
		{"remove tray shortcut", func() (State, string) { return removeIfPresent(o.StartupLnk()) }},
		{"delete task \"" + TrayTaskName + "\"", func() (State, string) {
			if !r.Run("schtasks", "/Query", "/TN", TrayTaskName).OK() {
				return Skipped, "not there"
			}
			if res := r.Run("schtasks", "/Delete", "/TN", TrayTaskName, "/F"); !res.OK() {
				return Warning, res.Why()
			}
			return Done, ""
		}},
	}
	return run("uninstall", steps)
}

// TaskInfo is what ParseTaskXML read back from `schtasks /Query /XML`.
type TaskInfo struct {
	LogonType string
	UserID    string
	Command   string
	Arguments string
	Enabled   string
}

var xmlDecl = regexp.MustCompile(`^\s*(\x{FEFF})?<\?xml[^>]*\?>`)

// ParseTaskXML reads the fields status reports. schtasks prints the XML in
// the console code page while still declaring UTF-16, so the declaration is
// dropped before decoding. Pure.
func ParseTaskXML(s string) (TaskInfo, error) {
	s = xmlDecl.ReplaceAllString(s, "")
	var t struct {
		Principals struct {
			Principal struct {
				UserID    string `xml:"UserId"`
				LogonType string `xml:"LogonType"`
			} `xml:"Principal"`
		} `xml:"Principals"`
		Settings struct {
			Enabled string `xml:"Enabled"`
		} `xml:"Settings"`
		Actions struct {
			Exec struct {
				Command   string `xml:"Command"`
				Arguments string `xml:"Arguments"`
			} `xml:"Exec"`
		} `xml:"Actions"`
	}
	if err := xml.Unmarshal([]byte(s), &t); err != nil {
		return TaskInfo{}, err
	}
	return TaskInfo{
		LogonType: t.Principals.Principal.LogonType,
		UserID:    t.Principals.Principal.UserID,
		Command:   t.Actions.Exec.Command,
		Arguments: t.Actions.Exec.Arguments,
		Enabled:   t.Settings.Enabled,
	}, nil
}

// WindowsStatus is what StatusWindows found.
type WindowsStatus struct {
	Registered bool
	Task       TaskInfo
	Problem    string
	Tray       bool
}

// StatusWindows reports without changing anything.
func StatusWindows(o WindowsOptions) WindowsStatus {
	var s WindowsStatus
	res := o.Runner.Run("schtasks", "/Query", "/TN", TaskName, "/XML")
	if res.OK() {
		s.Registered = true
		if t, err := ParseTaskXML(res.Stdout); err == nil {
			s.Task = t
		} else {
			s.Problem = "task XML did not parse: " + err.Error()
		}
	}
	_, err := os.Stat(o.StartupLnk())
	s.Tray = err == nil
	return s
}

func (s WindowsStatus) String() string {
	var b strings.Builder
	if !s.Registered {
		b.WriteString("task      not registered\n")
	} else {
		fmt.Fprintf(&b, "task      %q registered (logon %s, user %s, enabled %s)\n", TaskName, s.Task.LogonType, s.Task.UserID, s.Task.Enabled)
		fmt.Fprintf(&b, "runs      %s %s\n", s.Task.Command, s.Task.Arguments)
	}
	if s.Problem != "" {
		b.WriteString("problem   " + s.Problem + "\n")
	}
	tray := "no"
	if s.Tray {
		tray = "yes"
	}
	b.WriteString("tray at login " + tray + "\n")
	return b.String()
}
