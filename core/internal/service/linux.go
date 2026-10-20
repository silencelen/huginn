package service

import (
	"errors"
	"fmt"
	"io/fs"
	"os"
	"path/filepath"
	"regexp"
	"strings"
)

// Names shared by both platforms.
const (
	UnitName      = "huginn-core.service"
	TrayDesktop   = "huginn-core-tray.desktop"
	TaskName      = "Huginn Core"
	TrayTaskName  = "Huginn Tray"
	TrayShortcut  = "Huginn.lnk"
	AUMID         = "com.silencelen.huginn.desktop-kt"
	staleTaskFile = "huginn-core-task.xml"
)

// Linger is logind's "keep this user's services running with nobody logged
// in" — three answers, as huginn-local has them: on; off (and, after an
// install, enabling it was refused); and unknown (no logind to ask).
type Linger string

const (
	LingerOn      Linger = "on"
	LingerOff     Linger = "off"
	LingerUnknown Linger = "unknown"
)

// ParseLinger reads `loginctl show-user <u> --property=Linger`. Pure.
func ParseLinger(r Result) Linger {
	if !r.OK() {
		return LingerUnknown
	}
	m := regexp.MustCompile(`(?im)^\s*Linger\s*=\s*(\w+)`).FindStringSubmatch(r.Stdout)
	if m == nil {
		return LingerUnknown
	}
	switch strings.ToLower(m[1]) {
	case "yes", "true", "1":
		return LingerOn
	default:
		return LingerOff
	}
}

// systemdExecQuote quotes one ExecStart word: always double-quoted, with
// C-style escapes for `\` and `"`, `%` doubled (specifiers) and `$` doubled
// (variable expansion). Pure.
func systemdExecQuote(s string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "%", "%%", "$", "$$")
	return `"` + r.Replace(s) + `"`
}

// systemdEnvQuote quotes one Environment= assignment (no $ expansion there,
// but specifiers still apply). Pure.
func systemdEnvQuote(kv string) string {
	r := strings.NewReplacer(`\`, `\\`, `"`, `\"`, "%", "%%")
	return `"` + r.Replace(kv) + `"`
}

// UnitText is the whole systemd --user unit. Pure.
func UnitText(binary, home string) string {
	var b strings.Builder
	b.WriteString("# Written by `huginn core install`; removed by `huginn core uninstall`.\n")
	b.WriteString("[Unit]\n")
	b.WriteString("Description=huginn core (device runner and appd link)\n")
	b.WriteString("Documentation=https://github.com/silencelen/huginn/blob/main/docs/install/clients.md\n")
	b.WriteString("\n[Service]\n")
	b.WriteString("Type=simple\n")
	b.WriteString("ExecStart=" + systemdExecQuote(binary) + " core run\n")
	b.WriteString("Restart=always\n")
	b.WriteString("RestartSec=10\n")
	b.WriteString("Environment=" + systemdEnvQuote("HOME="+home) + "\n")
	b.WriteString("\n[Install]\n")
	b.WriteString("WantedBy=default.target\n")
	return b.String()
}

// desktopExecQuote quotes one Exec= argument per the Desktop Entry spec:
// inside double quotes `"`, "`", `$` and `\` are backslash-escaped, then the
// value-level escape doubles every backslash again, and `%` (field codes)
// is doubled. Pure.
func desktopExecQuote(s string) string {
	inner := strings.NewReplacer(`"`, `\"`, "`", "\\`", "$", `\$`, `\`, `\\`).Replace(s)
	quoted := `"` + inner + `"`
	return strings.NewReplacer(`\`, `\\`, "%", "%%").Replace(quoted)
}

// TrayDesktopEntry is the tray role's freedesktop autostart entry. Pure.
// Hidden=false and X-GNOME-Autostart-enabled=true both, because desktops
// disagree about which one means off (as the desktop's Autostart.kt).
func TrayDesktopEntry(binary string) string {
	var b strings.Builder
	b.WriteString("[Desktop Entry]\n")
	b.WriteString("Type=Application\n")
	b.WriteString("Name=Huginn\n")
	b.WriteString("Comment=huginn tray: notifications and the window launcher\n")
	b.WriteString("Exec=" + desktopExecQuote(binary) + " core tray\n")
	b.WriteString("Terminal=false\n")
	b.WriteString("Hidden=false\n")
	b.WriteString("NoDisplay=false\n")
	b.WriteString("X-GNOME-Autostart-enabled=true\n")
	return b.String()
}

// GraphicalSession: does this environment have a display to put a tray on?
func GraphicalSession(env func(string) string) bool {
	return env("DISPLAY") != "" || env("WAYLAND_DISPLAY") != ""
}

// LinuxOptions are the inputs of the Linux flows.
type LinuxOptions struct {
	Binary     string // absolute path to the huginn binary the unit starts (the stable ~/.local/bin/huginn)
	Home       string
	User       string
	ConfigHome string // ${XDG_CONFIG_HOME:-~/.config}
	Graphical  bool   // GraphicalSession(os.Getenv)
	ForceTray  bool   // write the tray autostart even with no graphical session
	NoTray     bool
	Runner     Runner
	// KeepRunning: the running core is in the middle of a device job, so it
	// is started if it is down but never restarted (the caller asks it to
	// restart itself once the job is done).
	KeepRunning bool
}

func (o LinuxOptions) UnitPath() string {
	return filepath.Join(o.ConfigHome, "systemd", "user", UnitName)
}

func (o LinuxOptions) TrayPath() string {
	return filepath.Join(o.ConfigHome, "autostart", TrayDesktop)
}

func checkBinary(p string, isWin bool) (State, string) {
	abs := filepath.IsAbs(p) || (isWin && len(p) > 2 && p[1] == ':')
	if !abs {
		return Failed, fmt.Sprintf("%q is not an absolute path", p)
	}
	fi, err := os.Stat(p)
	if err != nil {
		return Failed, err.Error()
	}
	if !fi.Mode().IsRegular() {
		return Failed, p + " is not a file"
	}
	if !isWin && fi.Mode().Perm()&0o111 == 0 {
		return Failed, p + " is not executable"
	}
	return Done, p
}

// writeIfChanged writes text atomically; Unchanged when it is already there.
func writeIfChanged(path, text string, mode os.FileMode) (State, string) {
	if b, err := os.ReadFile(path); err == nil && string(b) == text {
		return Unchanged, path
	}
	if err := os.MkdirAll(filepath.Dir(path), 0o755); err != nil {
		return Failed, err.Error()
	}
	tmp, err := os.CreateTemp(filepath.Dir(path), "."+filepath.Base(path)+".tmp-*")
	if err != nil {
		return Failed, err.Error()
	}
	name := tmp.Name()
	_, werr := tmp.WriteString(text)
	cerr := tmp.Close()
	if werr == nil {
		werr = cerr
	}
	if werr == nil {
		werr = os.Chmod(name, mode)
	}
	if werr == nil {
		werr = os.Rename(name, path)
	}
	if werr != nil {
		os.Remove(name)
		return Failed, werr.Error()
	}
	return Done, path
}

func removeIfPresent(path string) (State, string) {
	err := os.Remove(path)
	switch {
	case err == nil:
		return Done, path
	case errors.Is(err, fs.ErrNotExist):
		return Skipped, "not there"
	default:
		return Failed, err.Error()
	}
}

func cmdStep(r Runner, name string, args ...string) (State, string) {
	res := r.Run(name, args...)
	if !res.OK() {
		return Failed, res.Why()
	}
	return Done, ""
}

// InstallLinux writes and starts the unit, asks for linger, and writes the
// tray autostart. The linger answer is returned too.
func InstallLinux(o LinuxOptions) (Report, Linger) {
	r := o.Runner
	linger := LingerUnknown
	unitChanged := false
	steps := []step{
		{"check binary", func() (State, string) { return checkBinary(o.Binary, false) }},
		{"write " + UnitName, func() (State, string) {
			st, d := writeIfChanged(o.UnitPath(), UnitText(o.Binary, o.Home), 0o644)
			unitChanged = st == Done
			return st, d
		}},
		{"systemctl --user daemon-reload", func() (State, string) {
			st, d := cmdStep(r, "systemctl", "--user", "daemon-reload")
			if st == Failed {
				d += " (is there a user manager? systemd --user needs a login or linger)"
			}
			return st, d
		}},
		{"systemctl --user enable " + UnitName, func() (State, string) {
			return cmdStep(r, "systemctl", "--user", "enable", UnitName)
		}},
		{"(re)start " + UnitName, func() (State, string) {
			if o.KeepRunning {
				// ⚠ A restart stops the whole unit, so the claude of a job in
				// progress dies with it, halfway (the unit has the default
				// KillMode). `start` leaves a running core alone.
				st, d := cmdStep(r, "systemctl", "--user", "start", UnitName)
				if st == Done {
					d = "left running: it is in the middle of a job"
				}
				return st, d
			}
			st, d := cmdStep(r, "systemctl", "--user", "restart", UnitName)
			if st == Done && !unitChanged {
				d = "restarted onto the current binary"
			}
			return st, d
		}},
		{"linger for " + o.User, func() (State, string) {
			linger = ParseLinger(r.Run("loginctl", "show-user", o.User, "--property=Linger"))
			if linger == LingerOn {
				return Unchanged, "already on — huginn core runs with nobody logged in"
			}
			res := r.Run("loginctl", "enable-linger", o.User)
			after := ParseLinger(r.Run("loginctl", "show-user", o.User, "--property=Linger"))
			if res.OK() && after == LingerOn {
				linger = LingerOn
				return Done, "enabled — huginn core runs with nobody logged in"
			}
			if res.OK() && after == LingerUnknown {
				linger = LingerUnknown
				return Warning, "enable-linger exited 0 but logind could not confirm it"
			}
			if res.Err != nil && linger == LingerUnknown {
				return Warning, "unknown — no loginctl here (" + res.Why() + "); huginn core may stop when you log out"
			}
			linger = LingerOff
			why := res.Why()
			if why == "" {
				why = "still off afterwards"
			}
			return Warning, "refused (" + why + ") — huginn core runs only while you are logged in"
		}},
		{"tray autostart", func() (State, string) { return EnsureTrayLinux(o) }},
	}
	rep := run("install", steps)
	if rep.OK() && linger != LingerOn {
		rep.Notes = append(rep.Notes, "Linger is "+string(linger)+": the service starts at your next login, not at boot. "+
			"An administrator can run `loginctl enable-linger "+o.User+"`.")
	}
	return rep, linger
}

// EnsureTrayLinux writes the tray role's login autostart (install, and an
// update from a core that predates the tray).
func EnsureTrayLinux(o LinuxOptions) (State, string) {
	if o.NoTray {
		return Skipped, "skipped (--no-tray)"
	}
	if !o.Graphical && !o.ForceTray {
		return Skipped, "not set up: this shell has no graphical session (an ssh shell?) - huginn core install --tray adds it anyway"
	}
	return writeIfChanged(o.TrayPath(), TrayDesktopEntry(o.Binary), 0o644)
}

// UninstallLinux stops, disables and removes the unit and the tray entry.
// Linger is left as it is: other user services may depend on it.
func UninstallLinux(o LinuxOptions) Report {
	r := o.Runner
	present := false
	if _, err := os.Stat(o.UnitPath()); err == nil {
		present = true
	}
	steps := []step{
		{"stop and disable " + UnitName, func() (State, string) {
			if !present {
				return Skipped, "no unit installed"
			}
			return cmdStep(r, "systemctl", "--user", "disable", "--now", UnitName)
		}},
		{"remove " + UnitName, func() (State, string) {
			if !present {
				return Skipped, "not there"
			}
			return removeIfPresent(o.UnitPath())
		}},
		{"systemctl --user daemon-reload", func() (State, string) {
			if !present {
				return Skipped, ""
			}
			return cmdStep(r, "systemctl", "--user", "daemon-reload")
		}},
		{"remove tray autostart", func() (State, string) { return removeIfPresent(o.TrayPath()) }},
	}
	rep := run("uninstall", steps)
	if rep.OK() {
		rep.Notes = append(rep.Notes, "Linger was left as it is.")
	}
	return rep
}

// LinuxStatus is what StatusLinux found.
type LinuxStatus struct {
	UnitPath  string
	Installed bool
	Enabled   string // systemctl is-enabled's word, or "unknown"
	Active    string // systemctl is-active's word, or "unknown"
	Linger    Linger
	Tray      bool
}

func firstWord(r Result) string {
	if r.Err != nil {
		return "unknown"
	}
	f := strings.Fields(r.Stdout)
	if len(f) == 0 {
		return "unknown"
	}
	return f[0]
}

// StatusLinux reports without changing anything.
func StatusLinux(o LinuxOptions) LinuxStatus {
	s := LinuxStatus{UnitPath: o.UnitPath()}
	_, err := os.Stat(s.UnitPath)
	s.Installed = err == nil
	s.Enabled = firstWord(o.Runner.Run("systemctl", "--user", "is-enabled", UnitName))
	s.Active = firstWord(o.Runner.Run("systemctl", "--user", "is-active", UnitName))
	s.Linger = ParseLinger(o.Runner.Run("loginctl", "show-user", o.User, "--property=Linger"))
	_, err = os.Stat(o.TrayPath())
	s.Tray = err == nil
	return s
}

func (s LinuxStatus) String() string {
	inst := "not installed"
	if s.Installed {
		inst = "installed (" + s.UnitPath + ")"
	}
	tray := "no"
	if s.Tray {
		tray = "yes"
	}
	return fmt.Sprintf("service   %s\nenabled   %s\nactive    %s\nlinger    %s\ntray at login %s\n",
		inst, s.Enabled, s.Active, s.Linger, tray)
}
