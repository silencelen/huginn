package service

import (
	"encoding/base64"
	"flag"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"testing"
	"unicode/utf16"
)

var update = flag.Bool("update", false, "rewrite golden files")

func golden(t *testing.T, name, got string) {
	t.Helper()
	p := filepath.Join("testdata", name)
	if *update {
		os.MkdirAll("testdata", 0o755)
		if err := os.WriteFile(p, []byte(got), 0o644); err != nil {
			t.Fatal(err)
		}
		return
	}
	want, err := os.ReadFile(p)
	if err != nil {
		t.Fatalf("%v (run go test -update to create)", err)
	}
	if string(want) != got {
		t.Fatalf("%s mismatch:\n--- got\n%s\n--- want\n%s", name, got, want)
	}
}

func TestUnitTextGolden(t *testing.T) {
	golden(t, "huginn-core.service", UnitText("/home/s/.local/bin/huginn", "/home/s"))
	golden(t, "huginn-core-odd.service", UnitText(`/home/o'b r/100%/$x/"q"\huginn`, "/home/o'b r"))
}

func TestTrayDesktopGolden(t *testing.T) {
	golden(t, "huginn-core-tray.desktop", TrayDesktopEntry("/home/s/.local/bin/huginn"))
}

func TestDesktopExecQuote(t *testing.T) {
	for in, want := range map[string]string{
		"/usr/bin/huginn": `"/usr/bin/huginn"`,
		"/a b/huginn":     `"/a b/huginn"`,
		`/a"b`:            `"/a\\"b"`,
		"/a$b":            `"/a\\$b"`,
		`/a\b`:            `"/a\\\\b"`,
		"/100%/x":         `"/100%%/x"`,
		"/back`tick":      "\"/back\\\\`tick\"",
	} {
		if got := desktopExecQuote(in); got != want {
			t.Errorf("%q: got %s want %s", in, got, want)
		}
	}
}

func TestSystemdExecQuote(t *testing.T) {
	if got := systemdExecQuote(`/a b/%i/$HOME/"x"\y`); got != `"/a b/%%i/$$HOME/\"x\"\\y"` {
		t.Fatal(got)
	}
}

func TestTaskXMLGolden(t *testing.T) {
	bin := `C:\Users\O'Brien & Co\AppData\Local\huginn\bin\huginn.exe`
	s4u := TaskXML(bin, `HOME\alex`, LogonS4U)
	golden(t, "task-s4u.xml", s4u)
	pw := TaskXML(bin, `DESKTOP-01\alex`, LogonPassword)
	if !strings.Contains(pw, "<LogonType>Password</LogonType>") || strings.Contains(pw, "S4U") {
		t.Fatal("password task")
	}
	if TaskXML(bin, "u", "") != TaskXML(bin, "u", LogonS4U) {
		t.Fatal("default logon is S4U")
	}
	info, err := ParseTaskXML(s4u)
	if err != nil {
		t.Fatal(err)
	}
	if info.LogonType != "S4U" || info.UserID != `HOME\alex` || info.Command != bin || info.Arguments != "core run" || info.Enabled != "true" {
		t.Fatalf("%+v", info)
	}
	for _, must := range []string{"<BootTrigger>", "<ExecutionTimeLimit>PT0S</ExecutionTimeLimit>",
		"<MultipleInstancesPolicy>IgnoreNew</MultipleInstancesPolicy>", "<Count>999</Count>", "<Interval>PT1M</Interval>",
		`<WorkingDirectory>C:\Users\O&#39;Brien &amp; Co\AppData\Local\huginn\bin</WorkingDirectory>`} {
		if !strings.Contains(s4u, must) {
			t.Errorf("missing %s", must)
		}
	}
}

func TestParseTaskXMLConsoleOutput(t *testing.T) {
	// schtasks /Query /XML prints in the console code page under a UTF-16 declaration.
	out := "\ufeff<?xml version=\"1.0\" encoding=\"UTF-16\"?>\r\n<Task version=\"1.2\" xmlns=\"http://schemas.microsoft.com/windows/2004/02/mit/task\"><Principals><Principal id=\"Author\"><UserId>S-1-5-21-1-2-3-1001</UserId><LogonType>Password</LogonType></Principal></Principals><Actions Context=\"Author\"><Exec><Command>C:\\h\\huginn.exe</Command><Arguments>core run</Arguments></Exec></Actions></Task>"
	info, err := ParseTaskXML(out)
	if err != nil || info.LogonType != "Password" || info.Command != `C:\h\huginn.exe` {
		t.Fatalf("%+v %v", info, err)
	}
}

func TestEncodeUTF16(t *testing.T) {
	b := EncodeUTF16("aé")
	if len(b) != 6 || b[0] != 0xFF || b[1] != 0xFE || b[2] != 'a' || b[3] != 0 || b[4] != 0xE9 || b[5] != 0 {
		t.Fatalf("% x", b)
	}
}

func decodeEncoded(t *testing.T, enc string) string {
	raw, err := base64.StdEncoding.DecodeString(enc)
	if err != nil {
		t.Fatal(err)
	}
	u := make([]uint16, len(raw)/2)
	for i := range u {
		u[i] = uint16(raw[2*i]) | uint16(raw[2*i+1])<<8
	}
	return string(utf16.Decode(u))
}

func TestShortcutScript(t *testing.T) {
	lnk := `C:\Users\O'Brien\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\Huginn.lnk`
	exe := `C:\Users\O'Brien\AppData\Local\huginn\bin\huginn.exe`
	s := ShortcutScript(lnk, exe)
	golden(t, "tray-shortcut.ps1", s)
	if !strings.Contains(s, `[HuginnLnk.Maker]::Make('C:\Users\O''Brien\AppData\Roaming\Microsoft\Windows\Start Menu\Programs\Startup\Huginn.lnk', 'C:\Users\O''Brien\AppData\Local\huginn\bin\huginn.exe', 'core tray', 'C:\Users\O''Brien\AppData\Local\huginn\bin', 'Huginn', 'com.silencelen.huginn.desktop-kt')`) {
		t.Fatal("Make call")
	}
	if got := decodeEncoded(t, EncodedCommand(s)); got != s {
		t.Fatal("EncodedCommand does not round-trip")
	}
}

func TestWindowsParent(t *testing.T) {
	for in, want := range map[string]string{
		`C:\a\b.exe`: `C:\a`, `C:\b.exe`: `C:\`, `b.exe`: `b.exe`, `C:/x/y.exe`: `C:/x`,
	} {
		if got := windowsParent(in); got != want {
			t.Errorf("%s: %s", in, got)
		}
	}
}

func TestParseLinger(t *testing.T) {
	cases := []struct {
		r    Result
		want Linger
	}{
		{Result{Stdout: "Linger=yes\n"}, LingerOn},
		{Result{Stdout: "Linger=no\n"}, LingerOff},
		{Result{Stdout: "garbage"}, LingerUnknown},
		{Result{Code: 1, Stderr: "Failed to get user: No such user"}, LingerUnknown},
		{Result{Err: os.ErrNotExist}, LingerUnknown},
	}
	for _, c := range cases {
		if got := ParseLinger(c.r); got != c.want {
			t.Errorf("%+v: %s", c.r, got)
		}
	}
}

// fake is a scripted Runner: the first rule whose prefix matches the joined
// command line answers; everything else succeeds with empty output.
type fake struct {
	calls  []string
	rules  []rule
	stdins []string
}

// RunInput records stdin separately from argv: the F3 test asserts a password
// is in one and never in the other.
func (f *fake) RunInput(stdin string, name string, args ...string) Result {
	f.stdins = append(f.stdins, stdin)
	return f.Run(name, args...)
}

type rule struct {
	prefix string
	res    Result
	do     func(args []string)
}

func (f *fake) Run(name string, args ...string) Result {
	line := strings.Join(append([]string{name}, args...), " ")
	f.calls = append(f.calls, line)
	for _, r := range f.rules {
		if strings.HasPrefix(line, r.prefix) {
			if r.do != nil {
				r.do(args)
			}
			return r.res
		}
	}
	return Result{}
}

func (f *fake) called(prefix string) bool {
	for _, c := range f.calls {
		if strings.HasPrefix(c, prefix) {
			return true
		}
	}
	return false
}

func linuxOpts(t *testing.T, f *fake) LinuxOptions {
	dir := t.TempDir()
	bin := filepath.Join(dir, "bin", "huginn")
	os.MkdirAll(filepath.Dir(bin), 0o755)
	os.WriteFile(bin, []byte("#!/bin/sh\n"), 0o755)
	return LinuxOptions{Binary: bin, Home: dir, User: "alex", ConfigHome: filepath.Join(dir, ".config"), Graphical: true, Runner: f}
}

func states(r Report) string {
	var s []string
	for _, st := range r.Steps {
		s = append(s, string(st.State))
	}
	return strings.Join(s, ",")
}

func TestInstallLinuxHappy(t *testing.T) {
	f := &fake{rules: []rule{{prefix: "loginctl show-user", res: Result{Stdout: "Linger=yes\n"}}}}
	o := linuxOpts(t, f)
	rep, l := InstallLinux(o)
	if !rep.OK() || l != LingerOn {
		t.Fatalf("%s", rep)
	}
	if states(rep) != "done,done,done,done,done,unchanged,done" {
		t.Fatal(states(rep))
	}
	b, _ := os.ReadFile(o.UnitPath())
	if string(b) != UnitText(o.Binary, o.Home) {
		t.Fatal("unit content")
	}
	if f.called("loginctl enable-linger") {
		t.Fatal("linger already on: must not ask again")
	}
	if b, _ := os.ReadFile(o.TrayPath()); !strings.Contains(string(b), " core tray") {
		t.Fatal("tray entry")
	}
	// Second run: the unit is unchanged but the service is still restarted.
	rep, _ = InstallLinux(o)
	if rep.Steps[1].State != Unchanged || !f.called("systemctl --user restart") {
		t.Fatalf("%s", rep)
	}
}

func TestInstallLinuxLingerRefused(t *testing.T) {
	f := &fake{rules: []rule{
		{prefix: "loginctl show-user", res: Result{Stdout: "Linger=no\n"}},
		{prefix: "loginctl enable-linger", res: Result{Code: 1, Stderr: "Could not enable linger: Access denied\n"}},
	}}
	o := linuxOpts(t, f)
	o.Graphical = false
	rep, l := InstallLinux(o)
	if !rep.OK() || l != LingerOff {
		t.Fatalf("%s", rep)
	}
	if rep.Steps[5].State != Warning || !strings.Contains(rep.Steps[5].Detail, "Access denied") || !strings.Contains(rep.Steps[5].Detail, "only while you are logged in") {
		t.Fatalf("%+v", rep.Steps[5])
	}
	if rep.Steps[6].State != Skipped || !strings.Contains(rep.Steps[6].Detail, "no graphical session") {
		t.Fatalf("%+v", rep.Steps[6])
	}
	if len(rep.Notes) == 0 || !strings.Contains(rep.Notes[0], "next login") {
		t.Fatalf("notes %v", rep.Notes)
	}
	if _, err := os.Stat(o.TrayPath()); err == nil {
		t.Fatal("tray written with no graphical session")
	}
	o.ForceTray = true
	rep, _ = InstallLinux(o)
	if rep.Steps[6].State != Done {
		t.Fatalf("%s", rep)
	}
}

func TestInstallLinuxNoLogind(t *testing.T) {
	f := &fake{rules: []rule{{prefix: "loginctl", res: Result{Err: os.ErrNotExist}}}}
	rep, l := InstallLinux(linuxOpts(t, f))
	if !rep.OK() || l != LingerUnknown || !strings.Contains(rep.Steps[5].Detail, "unknown") {
		t.Fatalf("%s", rep)
	}
}

func TestInstallLinuxStopsAtFirstFailure(t *testing.T) {
	f := &fake{rules: []rule{{prefix: "systemctl --user daemon-reload", res: Result{Code: 1, Stderr: "Failed to connect to bus: No medium found\n"}}}}
	o := linuxOpts(t, f)
	rep, _ := InstallLinux(o)
	if rep.OK() || states(rep) != "done,done,FAILED,not done,not done,not done,not done" {
		t.Fatalf("%s", states(rep))
	}
	if f.called("systemctl --user enable") || f.called("loginctl") {
		t.Fatal("ran past the failure")
	}
	s := rep.String()
	if !strings.Contains(s, `install stopped at "systemctl --user daemon-reload"`) || !strings.Contains(s, "Already done: check binary; write huginn-core.service") {
		t.Fatal(s)
	}
	if strings.Contains(s, "next login") {
		t.Fatal("linger note printed over a failure")
	}
}

func TestInstallLinuxBadBinary(t *testing.T) {
	f := &fake{}
	o := linuxOpts(t, f)
	o.Binary = "huginn"
	rep, _ := InstallLinux(o)
	if rep.OK() || rep.Steps[0].State != Failed || len(f.calls) != 0 {
		t.Fatalf("%s", rep)
	}
	if !strings.Contains(rep.String(), "Nothing was changed.") {
		t.Fatal(rep.String())
	}
	if _, err := os.Stat(o.UnitPath()); err == nil {
		t.Fatal("unit written for a bad binary")
	}
}

func TestUninstallLinux(t *testing.T) {
	f := &fake{rules: []rule{{prefix: "systemctl --user disable", res: Result{Code: 1, Stderr: "Failed to connect to bus\n"}}}}
	o := linuxOpts(t, f)
	os.MkdirAll(filepath.Dir(o.UnitPath()), 0o755)
	os.WriteFile(o.UnitPath(), []byte(UnitText(o.Binary, o.Home)), 0o644)
	os.MkdirAll(filepath.Dir(o.TrayPath()), 0o755)
	os.WriteFile(o.TrayPath(), []byte("x"), 0o644)
	rep := UninstallLinux(o)
	if rep.OK() || states(rep) != "FAILED,not done,not done,not done" {
		t.Fatalf("%s", rep)
	}
	if _, err := os.Stat(o.UnitPath()); err != nil {
		t.Fatal("unit removed over a failure")
	}
	if strings.Contains(rep.String(), "Linger was left") {
		t.Fatal("success note printed over a failure")
	}
	f.rules = nil
	rep = UninstallLinux(o)
	if !rep.OK() || states(rep) != "done,done,done,done" {
		t.Fatalf("%s", rep)
	}
	rep = UninstallLinux(o)
	if !rep.OK() || states(rep) != "skipped,skipped,skipped,skipped" {
		t.Fatalf("again: %s", rep)
	}
}

func TestStatusLinux(t *testing.T) {
	f := &fake{rules: []rule{
		{prefix: "systemctl --user is-enabled", res: Result{Stdout: "enabled\n"}},
		{prefix: "systemctl --user is-active", res: Result{Stdout: "inactive\n", Code: 3}},
		{prefix: "loginctl show-user", res: Result{Stdout: "Linger=no\n"}},
	}}
	o := linuxOpts(t, f)
	s := StatusLinux(o)
	if s.Installed || s.Enabled != "enabled" || s.Active != "inactive" || s.Linger != LingerOff || s.Tray {
		t.Fatalf("%+v", s)
	}
	if !strings.Contains(s.String(), "linger    off") {
		t.Fatal(s.String())
	}
}

func winOpts(t *testing.T, f *fake) WindowsOptions {
	dir := t.TempDir()
	bin := filepath.Join(dir, "huginn", "bin", "huginn.exe")
	os.MkdirAll(filepath.Dir(bin), 0o755)
	os.WriteFile(bin, []byte("MZ"), 0o644)
	return WindowsOptions{Binary: bin, User: `HOME\alex`, AppData: filepath.Join(dir, "Roaming"),
		StateDir: filepath.Join(dir, "huginn", "state"), Runner: f}
}

func TestInstallWindowsHappy(t *testing.T) {
	f := &fake{}
	o := winOpts(t, f)
	var xmlSeen, trayXML []byte
	f.rules = []rule{
		{prefix: "schtasks /Create /TN Huginn Tray", do: func(args []string) { trayXML, _ = os.ReadFile(args[4]) }},
		{prefix: "schtasks /Create", do: func(args []string) { xmlSeen, _ = os.ReadFile(args[4]) }},
		{prefix: "powershell", do: func([]string) {
			os.MkdirAll(filepath.Dir(o.StartupLnk()), 0o755)
			os.WriteFile(o.StartupLnk(), []byte("lnk"), 0o644)
		}},
	}
	rep := InstallWindows(o)
	if !rep.OK() || states(rep) != "done,done,done,done,done,done,done" {
		t.Fatalf("%s", rep)
	}
	if string(xmlSeen) != string(EncodeUTF16(TaskXML(o.Binary, o.User, LogonS4U))) {
		t.Fatal("staged XML")
	}
	// The tray also gets its logon task (the Startup folder runs minutes late).
	if string(trayXML) != string(EncodeUTF16(TrayTaskXML(o.Binary, o.User))) {
		t.Fatal("tray logon task XML not registered")
	}
	if _, err := os.Stat(filepath.Join(o.StateDir, "huginn-tray-task.xml")); err == nil {
		t.Fatal("tray task XML left behind")
	}
	if _, err := os.Stat(filepath.Join(o.StateDir, staleTaskFile)); err == nil {
		t.Fatal("staged XML left behind")
	}
	// An update ENDS the running core before re-registering it (else the old
	// .exe keeps serving from huginn.exe.old until the next reboot).
	if f.calls[0] != "schtasks /Create /TN Huginn Core /XML "+filepath.Join(o.StateDir, staleTaskFile)+" /F" {
		t.Fatal(f.calls[0])
	}
	if f.calls[1] != "schtasks /End /TN Huginn Core" {
		t.Fatal(f.calls[1])
	}
	if !strings.Contains(strings.Join(rep.Notes, " "), "S4U") {
		t.Fatal(rep.Notes)
	}
}

func TestInstallWindowsPassword(t *testing.T) {
	f := &fake{}
	o := winOpts(t, f)
	o.Logon = LogonPassword
	rep := InstallWindows(o)
	if rep.OK() || rep.FailedStep() != "check logon type" || len(f.calls) != 0 {
		t.Fatalf("%s", rep)
	}
	o.Password = "s3cret-pw"
	o.NoTray = true
	rep = InstallWindows(o)
	if !rep.OK() {
		t.Fatalf("%s %v", rep, f.calls)
	}
	// The password is on stdin, and in NO argv of any command run.
	for _, c := range f.calls {
		if strings.Contains(c, "s3cret-pw") || strings.Contains(c, "/RP") {
			t.Fatalf("password in argv: %s", c)
		}
	}
	if len(f.stdins) != 1 || f.stdins[0] != "s3cret-pw\n" || !strings.HasPrefix(f.calls[0], "powershell ") {
		t.Fatalf("stdins=%q calls=%v", f.stdins, f.calls)
	}
	script := PasswordRegisterScript(`C:\x\task.xml`, `HOME\alex`)
	if strings.Contains(script, "s3cret") || !strings.Contains(script, "[Console]::In.ReadLine()") ||
		!strings.Contains(script, `RegisterTaskDefinition('Huginn Core', $def, 6, 'HOME\alex', $pw, 1)`) {
		t.Fatal(script)
	}
}

// Module Logging (event 4103) writes every value a cmdlet parameter is
// bound to into the PowerShell log, in cleartext, so the password may go to
// no cmdlet at all: only to the ReadLine that takes it off stdin and to the
// Task Scheduler COM call. No -Password, no pipeline, no Verb-Noun on its way.
func TestPasswordScriptGivesThePasswordToNoCmdlet(t *testing.T) {
	script := PasswordRegisterScript(`C:\Users\O'Brien\AppData\Local\huginn\state\task.xml`, `HOME\o'brien`)
	if regexp.MustCompile(`(?i)-Password\b|Register-ScheduledTask|SecureString|PSCredential|Get-Credential`).MatchString(script) {
		t.Fatalf("the password script binds the password to a cmdlet:\n%s", script)
	}
	cmdlet := regexp.MustCompile(`\b[A-Za-z]+-[A-Za-z]+\b`)
	uses := 0
	for _, line := range strings.Split(script, "\n") {
		if !strings.Contains(line, "$pw") {
			continue
		}
		uses++
		com := strings.HasPrefix(line, `$null = $ts.GetFolder('\').RegisterTaskDefinition(`) && !strings.Contains(line, "|") && !cmdlet.MatchString(line)
		if line != "$pw = [Console]::In.ReadLine()" && !com {
			t.Errorf("the password reaches something other than ReadLine and the COM call: %s", line)
		}
	}
	if uses != 2 {
		t.Errorf("$pw is on %d lines, want 2 (read, register):\n%s", uses, script)
	}
	// Same task as before: the staged XML, created or replaced (6, what
	// -Force did), as the user, with a stored password (TASK_LOGON_PASSWORD).
	for _, want := range []string{
		`$def.XmlText = Get-Content -Raw -LiteralPath 'C:\Users\O''Brien\AppData\Local\huginn\state\task.xml'`,
		`RegisterTaskDefinition('Huginn Core', $def, 6, 'HOME\o''brien', $pw, 1)`,
	} {
		if !strings.Contains(script, want) {
			t.Errorf("script lacks %s:\n%s", want, script)
		}
	}
	if got := decodeEncoded(t, EncodedCommand(script)); got != script {
		t.Fatal("EncodedCommand does not round-trip")
	}
	golden(t, "task-password.ps1", script)
}

func TestInstallWindowsAccessDenied(t *testing.T) {
	f := &fake{rules: []rule{{prefix: "schtasks /Create", res: Result{Code: 1, Stderr: "ERROR: Access is denied.\r\n"}}}}
	o := winOpts(t, f)
	rep := InstallWindows(o)
	if rep.OK() || states(rep) != "done,done,done,FAILED,not done,not done,not done" {
		t.Fatalf("%s", states(rep))
	}
	// A failed registration must leave a running core ALONE (no /End).
	if !strings.Contains(rep.Steps[3].Detail, "administrator prompt") || f.called("schtasks /Run") || f.called("powershell") || f.called("schtasks /End") {
		t.Fatalf("%s", rep)
	}
	if _, err := os.Stat(filepath.Join(o.StateDir, staleTaskFile)); err == nil {
		t.Fatal("staged XML left behind after a failure")
	}
}

func TestInstallWindowsShortcutNotWritten(t *testing.T) {
	f := &fake{} // powershell "succeeds" but writes nothing
	rep := InstallWindows(winOpts(t, f))
	if rep.OK() || rep.FailedStep() != "tray shortcut" || !strings.Contains(rep.Steps[6].Detail, "nothing is at") {
		t.Fatalf("%s", rep)
	}
}

func TestUninstallWindows(t *testing.T) {
	f := &fake{rules: []rule{{prefix: "schtasks /Query", res: Result{Code: 1, Stderr: "ERROR: The system cannot find the file specified."}}}}
	o := winOpts(t, f)
	rep := UninstallWindows(o)
	if !rep.OK() || states(rep) != "skipped,skipped,skipped,skipped" {
		t.Fatalf("%s", rep)
	}
	os.MkdirAll(filepath.Dir(o.StartupLnk()), 0o755)
	os.WriteFile(o.StartupLnk(), []byte("lnk"), 0o644)
	f.rules = []rule{
		{prefix: "schtasks /End", res: Result{Code: 1, Stderr: "ERROR: The task is not running."}},
		{prefix: "schtasks /Delete", res: Result{Code: 1, Stderr: "ERROR: Access is denied."}},
	}
	rep = UninstallWindows(o)
	if rep.OK() || states(rep) != "warning,FAILED,not done,not done" {
		t.Fatalf("%s", rep)
	}
	if _, err := os.Stat(o.StartupLnk()); err != nil {
		t.Fatal("shortcut removed over a failure")
	}
	f.rules = f.rules[:1]
	rep = UninstallWindows(o)
	if !rep.OK() || states(rep) != "warning,done,done,done" {
		t.Fatalf("%s", rep)
	}
	if !f.called("schtasks /Delete /TN Huginn Tray") {
		t.Fatal("uninstall left the tray's logon task")
	}
}

func TestTrayTaskXML(t *testing.T) {
	x := TrayTaskXML(`C:\Users\O'Brien\AppData\Local\huginn\bin\huginn.exe`, `HOME\o'brien`)
	for _, want := range []string{"<LogonTrigger>", "<LogonType>InteractiveToken</LogonType>", "<Command>%SystemRoot%\\System32\\conhost.exe</Command>",
		"<Arguments>--headless &#34;C:\\Users\\O&#39;Brien\\AppData\\Local\\huginn\\bin\\huginn.exe&#34; core tray --at-login</Arguments>",
		"<DisallowStartIfOnBatteries>false</DisallowStartIfOnBatteries>", "<UserId>HOME\\o&#39;brien</UserId>", "<Priority>5</Priority>", "<Delay>PT10S</Delay>"} {
		if !strings.Contains(x, want) {
			t.Errorf("tray task XML lacks %s:\n%s", want, x)
		}
	}
}

func TestStatusWindows(t *testing.T) {
	f := &fake{}
	o := winOpts(t, f)
	f.rules = []rule{{prefix: "schtasks /Query", res: Result{Stdout: TaskXML(o.Binary, o.User, LogonS4U)}}}
	s := StatusWindows(o)
	if !s.Registered || s.Task.LogonType != "S4U" || s.Task.Command != o.Binary || s.Tray {
		t.Fatalf("%+v", s)
	}
}
