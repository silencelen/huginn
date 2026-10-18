// Package clientkit is the shell client the core carries (docs/design/core.md
// §13, owner rule 2026-10-15: "the core includes the CLI"). Every way the core
// arrives on a machine — the one-liners, `huginn core update`, the desktop's
// bundled core — ends in `core self-install`, which lays the client down from
// here. So the CLI is always the core's own version, and a machine with a core
// always has the CLI.
//
// files/ is a copy of client/huginn.sh, huginn.ps1, huginn-local and huginn-llm-shim. core/build.sh
// refreshes it before every build, and TestEmbeddedMatchesClient fails when it
// drifts. (The .txt names keep .gitattributes from giving the copy CRLF.)
package clientkit

import (
	"bufio"
	"bytes"
	_ "embed"
	"os"
	"path/filepath"
	"strings"
)

//go:embed files/huginn.sh.txt
var shClient []byte

//go:embed files/huginn.ps1.txt
var ps1Client []byte

//go:embed files/huginn-local.txt
var localManager []byte

//go:embed files/huginn-llm-shim.txt
var llmShim []byte

// satellites are the CLI's node programs. They are refreshed only where they
// are already installed: `huginn local on` fetches them, and a machine that
// never asked for the local-AI tier is not given one.
var satellites = map[string][]byte{"huginn-local": localManager, "huginn-llm-shim": llmShim}

// LocalManager is the huginn-local this core carries: what `huginn uninstall`
// runs when ~/.huginn holds none (an earlier uninstall removed the CLI after
// the local tier refused to go, run 7), so the tier can still be removed.
func LocalManager() []byte { return localManager }

// The lines install.sh / install.ps1 write. Matched WHOLE-LINE on removal, so
// a line somebody wrote by hand is reported, never rewritten.
const (
	RCLine      = `[ -f ~/.huginn/huginn.sh ] && source ~/.huginn/huginn.sh`
	ProfileLine = `[ -n "${BASH_VERSION-}${ZSH_VERSION-}" ] && [ -f ~/.huginn/huginn.sh ] && . ~/.huginn/huginn.sh`
	PSLine      = `if (Test-Path "$HOME\.huginn\huginn.ps1") { . "$HOME\.huginn\huginn.ps1" }`
)

// Env is what Install and Remove need to know about this machine, so the
// Windows answers can be asserted on Linux.
type Env struct {
	GOOS  string
	Home  string
	Shell string // $SHELL
	// PSProfiles: the PowerShell profile files to wire (Windows PowerShell 5.1
	// and PowerShell 7 under Documents). Windows only.
	PSProfiles []string
}

// Dir is ~/.huginn.
func (e Env) Dir() string { return filepath.Join(e.Home, ".huginn") }

// Script is the installed client.
func (e Env) Script() string {
	if e.GOOS == "windows" {
		return filepath.Join(e.Dir(), "huginn.ps1")
	}
	return filepath.Join(e.Dir(), "huginn.sh")
}

func (e Env) content() []byte {
	if e.GOOS == "windows" {
		return ps1Client
	}
	return shClient
}

// Install writes the client (when it differs) and wires it into the shell's
// startup files (when it is not there yet). It never touches SSH: pointing the
// client at a huginn host is `huginn` setup, not a property of the core.
func Install(e Env) ([]string, error) {
	var steps []string
	if e.Home == "" {
		return nil, os.ErrNotExist
	}
	want := e.content()
	if have, err := os.ReadFile(e.Script()); err != nil || !bytes.Equal(have, want) {
		if err := os.MkdirAll(e.Dir(), 0o755); err != nil {
			return steps, err
		}
		if err == nil {
			os.WriteFile(e.Script()+".bak", have, 0o644) // `huginn update` kept one too
		}
		tmp := e.Script() + ".tmp"
		if err := os.WriteFile(tmp, want, 0o644); err != nil {
			return steps, err
		}
		if err := os.Rename(tmp, e.Script()); err != nil {
			os.Remove(tmp)
			return steps, err
		}
		steps = append(steps, "installed the huginn CLI: "+e.Script())
	}
	for name, want := range satellites {
		p := filepath.Join(e.Dir(), name)
		have, err := os.ReadFile(p)
		if err != nil || bytes.Equal(have, want) {
			continue
		}
		os.WriteFile(p+".bak", have, 0o644)
		tmp := p + ".tmp"
		if err := os.WriteFile(tmp, want, 0o755); err != nil {
			return steps, err
		}
		if err := os.Rename(tmp, p); err != nil {
			os.Remove(tmp)
			return steps, err
		}
		steps = append(steps, "updated "+p)
	}
	for _, w := range e.wiring() {
		added, err := wire(w.file, w.line, w.create)
		if err != nil {
			steps = append(steps, "could not add the CLI to "+w.file+": "+err.Error())
			continue
		}
		if added {
			steps = append(steps, "the CLI loads in new shells: "+w.file)
		}
	}
	return steps, nil
}

type wiring struct {
	file, line string
	create     bool // write the file when it does not exist
}

// wiring is install.sh's rule: ~/.bashrc always, ~/.zshrc when it exists or
// zsh is the login shell, ~/.profile only when it exists. Windows: each
// PowerShell profile (creating the 5.1 one, the one every Windows has).
func (e Env) wiring() []wiring {
	if e.GOOS == "windows" {
		var w []wiring
		for i, p := range e.PSProfiles {
			w = append(w, wiring{p, PSLine, i == 0})
		}
		return w
	}
	zsh := strings.HasSuffix(e.Shell, "/zsh")
	return []wiring{
		{filepath.Join(e.Home, ".bashrc"), RCLine, true},
		{filepath.Join(e.Home, ".zshrc"), RCLine, zsh},
		{filepath.Join(e.Home, ".profile"), ProfileLine, false},
	}
}

// wire appends line to file unless any line already loads the client (a
// hand-written one included, as install.sh checks).
func wire(file, line string, create bool) (bool, error) {
	b, err := os.ReadFile(file)
	if os.IsNotExist(err) {
		if !create {
			return false, nil
		}
	} else if err != nil {
		return false, err
	}
	if bytes.Contains(b, []byte(".huginn/huginn.sh")) || bytes.Contains(b, []byte(`.huginn\huginn.ps1`)) {
		return false, nil
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		return false, err
	}
	f, err := os.OpenFile(file, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return false, err
	}
	defer f.Close()
	sep := ""
	if len(b) > 0 && b[len(b)-1] != '\n' {
		sep = "\n"
	}
	nl := "\n"
	if strings.HasSuffix(file, ".ps1") {
		nl = "\r\n"
		if sep != "" {
			sep = "\r\n"
		}
	}
	_, err = f.WriteString(sep + line + nl)
	return err == nil, err
}

// Remove takes the client out: ~/.huginn (all of it is the client's: install
// laid it out, the device runner and local-AI manager live there too — the
// caller runs their own teardown first) and the startup lines Install wrote.
// It returns what went and what was left on purpose.
func Remove(e Env) (removed, left []string) {
	if fi, err := os.Stat(e.Dir()); err == nil && fi.IsDir() {
		if err := os.RemoveAll(e.Dir()); err != nil {
			left = append(left, e.Dir()+" - "+err.Error())
		} else {
			removed = append(removed, e.Dir())
		}
	}
	files := []string{filepath.Join(e.Home, ".bashrc"), filepath.Join(e.Home, ".zshrc"), filepath.Join(e.Home, ".profile")}
	lines := []string{RCLine, ProfileLine}
	if e.GOOS == "windows" {
		files, lines = e.PSProfiles, []string{PSLine}
	}
	for _, f := range files {
		hit, other, err := dropLines(f, lines)
		if err != nil {
			left = append(left, "the CLI line in "+f+" - could not rewrite it ("+err.Error()+")")
			continue
		}
		if hit {
			removed = append(removed, "the CLI line in "+f)
		}
		// Both can be true: the installer's line went, a line of the
		// person's own stays, and they must hear it does.
		if other {
			left = append(left, "a hand-edited huginn line in "+f+" - not the one the installer wrote, so it was left")
		}
	}
	return removed, left
}

// dropLines removes every whole-line match of lines from file. other: the file
// still mentions the client in a line that is not one of them.
func dropLines(file string, lines []string) (hit, other bool, err error) {
	b, err := os.ReadFile(file)
	if os.IsNotExist(err) {
		return false, false, nil
	} else if err != nil {
		return false, false, err
	}
	var out bytes.Buffer
	sc := bufio.NewScanner(bytes.NewReader(b))
	sc.Buffer(make([]byte, 1<<20), 1<<24)
	for sc.Scan() {
		raw := sc.Text()
		t := strings.TrimRight(raw, "\r")
		match := false
		for _, l := range lines {
			if t == l {
				match = true
			}
		}
		if match {
			hit = true
			continue
		}
		if strings.Contains(t, ".huginn/huginn.sh") || strings.Contains(t, `.huginn\huginn.ps1`) {
			other = true
		}
		out.WriteString(raw + "\n")
	}
	if err := sc.Err(); err != nil {
		return false, false, err
	}
	if !hit {
		return false, other, nil
	}
	fi, _ := os.Stat(file)
	mode := os.FileMode(0o644)
	if fi != nil {
		mode = fi.Mode().Perm()
	}
	tmp := file + ".huginn-uninstall"
	if err := os.WriteFile(tmp, out.Bytes(), mode); err != nil {
		os.Remove(tmp)
		return false, other, err
	}
	return true, other, os.Rename(tmp, file)
}
