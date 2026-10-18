// Package store holds the core's per-user configuration (core.json), its
// secrets (OS keyring, 0600-file fallback) and the one-time import of what the
// desktop and the Node runner left on disk. See docs/design/core.md
// D11, D13 and §7.1.
package store

import (
	"errors"
	"os"
	"path/filepath"
	"runtime"
	"strconv"
	"strings"
)

// Paths are the three per-user directories the core owns (§7.1).
//
//	            Linux                                   Windows
//	Config      ${XDG_CONFIG_HOME:-~/.config}/huginn    %APPDATA%\huginn
//	State       ${XDG_STATE_HOME:-~/.local/state}/huginn %LOCALAPPDATA%\huginn\state
//	Runtime     $XDG_RUNTIME_DIR/huginn                 %LOCALAPPDATA%\huginn\run
//
// HUGINN_CONFIG_DIR replaces Config (tests, and a second profile on one
// account); it does not move State or Runtime.
type Paths struct {
	Config  string
	State   string
	Runtime string
}

// CoreFile is core.json's path.
func (p Paths) CoreFile() string { return filepath.Join(p.Config, "core.json") }

// SecretsDir is the file-fallback secrets directory.
func (p Paths) SecretsDir() string { return filepath.Join(p.Config, "secrets") }

// DefaultPaths resolves Paths for this process.
func DefaultPaths() (Paths, error) {
	home, _ := os.UserHomeDir()
	return PathsFor(runtime.GOOS, os.Getenv, home, os.Getuid())
}

// ErrNoHome is returned when neither the environment nor a home directory
// gives anywhere to put the config.
var ErrNoHome = errors.New("store: no home directory and no XDG/APPDATA variables to place huginn's files in")

// PathsFor is DefaultPaths as a pure function of its inputs, so the Windows
// answer can be asserted on Linux. uid is used only for the Linux runtime-dir
// fallback (/run/user/<uid>, which is what systemd would have set).
func PathsFor(goos string, env func(string) string, home string, uid int) (Paths, error) {
	get := func(k string) string { return strings.TrimSpace(env(k)) }
	var p Paths
	if goos == "windows" {
		appData := get("APPDATA")
		if appData == "" && home != "" {
			appData = home + `\AppData\Roaming`
		}
		local := get("LOCALAPPDATA")
		if local == "" && home != "" {
			local = home + `\AppData\Local`
		}
		if appData == "" || local == "" {
			return Paths{}, ErrNoHome
		}
		p = Paths{
			Config:  appData + `\huginn`,
			State:   local + `\huginn\state`,
			Runtime: local + `\huginn\run`,
		}
	} else {
		cfg := get("XDG_CONFIG_HOME")
		if !strings.HasPrefix(cfg, "/") { // the XDG spec: relative values are ignored
			cfg = ""
		}
		if cfg == "" {
			if home == "" {
				return Paths{}, ErrNoHome
			}
			cfg = home + "/.config"
		}
		st := get("XDG_STATE_HOME")
		if !strings.HasPrefix(st, "/") {
			st = ""
		}
		if st == "" {
			if home == "" {
				return Paths{}, ErrNoHome
			}
			st = home + "/.local/state"
		}
		rt := get("XDG_RUNTIME_DIR")
		if !strings.HasPrefix(rt, "/") {
			rt = ""
		}
		if rt == "" && uid >= 0 {
			rt = "/run/user/" + strconv.Itoa(uid)
		}
		p = Paths{Config: cfg + "/huginn", State: st + "/huginn"}
		if rt != "" {
			p.Runtime = rt + "/huginn"
		}
	}
	if o := get("HUGINN_CONFIG_DIR"); o != "" {
		p.Config = strings.TrimRight(o, `/\`)
	}
	return p, nil
}
