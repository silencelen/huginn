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
	"encoding/binary"
	"errors"
	"os"
	"path/filepath"
	"regexp"
	"strings"
	"unicode/utf16"
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
	o, ok := utf16Order(b)
	if !ok {
		return false, errUTF32
	}
	mended := false
	if o != nil {
		b, mended = mend(b, line)
	}
	text := decodeText(b, o)
	if strings.Contains(text, ".huginn/huginn.sh") || strings.Contains(text, `.huginn\huginn.ps1`) {
		if mended {
			return true, replaceFile(file, b)
		}
		return false, nil
	}
	sep := ""
	if text != "" && !strings.HasSuffix(text, "\n") {
		sep = "\n"
	}
	nl := "\n"
	if strings.HasSuffix(file, ".ps1") {
		nl = "\r\n"
		if sep != "" {
			sep = "\r\n"
		}
	}
	add := encodeText(sep+line+nl, o)
	if mended {
		return true, replaceFile(file, append(b, add...))
	}
	if err := os.MkdirAll(filepath.Dir(file), 0o755); err != nil {
		return false, err
	}
	f, err := os.OpenFile(file, os.O_APPEND|os.O_CREATE|os.O_WRONLY, 0o644)
	if err != nil {
		return false, err
	}
	defer f.Close()
	_, err = f.Write(add)
	return err == nil, err
}

// ⚠ A startup file is read and written in its own encoding. Windows
// PowerShell 5.1 writes UTF-16LE with a BOM (its `>`, `>>` and Out-File), and
// PowerShell reads a whole script in the encoding its BOM names: a line
// appended as ASCII bytes reads as one line of CJK-looking garbage, which
// errors in every new window and loads nothing (2.6.x did that).

var errUTF32 = errors.New("it is saved as UTF-32, which huginn does not edit: add the line by hand")

// utf16Order is the byte order a UTF-16 BOM names, or nil: no BOM, or UTF-8's,
// where the bytes are the text. ok is false for UTF-32, whose little-endian
// BOM starts like UTF-16LE's.
func utf16Order(b []byte) (o binary.ByteOrder, ok bool) {
	switch {
	case bytes.HasPrefix(b, []byte{0xFF, 0xFE, 0, 0}), bytes.HasPrefix(b, []byte{0, 0, 0xFE, 0xFF}):
		return nil, false
	case bytes.HasPrefix(b, []byte{0xFF, 0xFE}):
		return binary.LittleEndian, true
	case bytes.HasPrefix(b, []byte{0xFE, 0xFF}):
		return binary.BigEndian, true
	}
	return nil, true
}

// decodeText is the file's text: after its BOM, decoded, when o is set.
func decodeText(b []byte, o binary.ByteOrder) string {
	if o == nil {
		return string(b)
	}
	u := make([]uint16, (len(b)-2)/2)
	for i := range u {
		u[i] = o.Uint16(b[2+2*i:])
	}
	return string(utf16.Decode(u))
}

// encodeText is s in the encoding o names, without a BOM.
func encodeText(s string, o binary.ByteOrder) []byte {
	if o == nil {
		return []byte(s)
	}
	u := utf16.Encode([]rune(s))
	b := make([]byte, 2*len(u))
	for i, c := range u {
		o.PutUint16(b[2*i:], c)
	}
	return b
}

// mend takes out of a UTF-16 file the line 2.6.x appended to it as ASCII
// bytes: line+"\r\n", after the "\r\n" it put first when the file did not end
// in one. ASCII bytes in a row are no UTF-16 text anybody wrote.
func mend(b []byte, line string) ([]byte, bool) {
	bad := []byte(line + "\r\n")
	i := bytes.Index(b, bad)
	if i < 0 {
		return b, false
	}
	j := i + len(bad)
	if i >= 4 && string(b[i-2:i]) == "\r\n" {
		i -= 2
	}
	return append(b[:i:i], b[j:]...), true
}

// replaceFile writes data over file through a temporary file, keeping its mode.
func replaceFile(file string, data []byte) error {
	mode := os.FileMode(0o644)
	if fi, err := os.Stat(file); err == nil {
		mode = fi.Mode().Perm()
	}
	tmp := file + ".huginn-tmp"
	if err := os.WriteFile(tmp, data, mode); err != nil {
		os.Remove(tmp)
		return err
	}
	return os.Rename(tmp, file)
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
	// In the file's own encoding, as wire wrote it (see utf16Order).
	o, ok := utf16Order(b)
	if !ok {
		return false, false, nil // UTF-32: wire never writes to one
	}
	if o != nil {
		for _, l := range lines {
			var m bool
			if b, m = mend(b, l); m {
				hit = true
			}
		}
	}
	var out bytes.Buffer
	sc := bufio.NewScanner(strings.NewReader(decodeText(b, o)))
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
	data := out.Bytes()
	if o != nil {
		data = append(b[:2:2], encodeText(out.String(), o)...) // its BOM, then the text
	}
	tmp := file + ".huginn-uninstall"
	if err := os.WriteFile(tmp, data, mode); err != nil {
		os.Remove(tmp)
		return false, other, err
	}
	return true, other, os.Rename(tmp, file)
}

// versionRE reads the client's version constant: HUGINN_VERSION='x.y.z' in
// huginn.sh, $script:HUGINN_VERSION = 'x.y.z' in huginn.ps1.
var versionRE = regexp.MustCompile(`(?m)^\$?(?:script:)?HUGINN_VERSION\s*=\s*'([^']+)'`)

// ScriptVersion is the version a shell client's text declares, or "".
func ScriptVersion(b []byte) string {
	m := versionRE.FindSubmatch(b)
	if m == nil {
		return ""
	}
	return string(m[1])
}

// Version is the version of the client this core carries for goos: what the
// installed script must say to match (`huginn doctor` compares them).
func Version(goos string) string {
	return ScriptVersion(Env{GOOS: goos}.content())
}
