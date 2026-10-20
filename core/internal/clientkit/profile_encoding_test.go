package clientkit

import (
	"bytes"
	"encoding/binary"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"unicode/utf16"
)

// Windows PowerShell 5.1 writes a profile made with `>`, `>>` or Out-File as
// UTF-16LE with a BOM, and PowerShell reads the whole file in the encoding its
// BOM names. The CLI's line has to go into such a profile in that encoding: as
// ASCII bytes it reads as a line of garbage that errors in every new window
// and loads nothing.

var (
	bomLE = []byte{0xFF, 0xFE}
	bomBE = []byte{0xFE, 0xFF}
)

func utf16File(bom []byte, text string) []byte {
	var o binary.AppendByteOrder = binary.LittleEndian
	if bytes.Equal(bom, bomBE) {
		o = binary.BigEndian
	}
	b := append([]byte{}, bom...)
	for _, u := range utf16.Encode([]rune(text)) {
		b = o.AppendUint16(b, u)
	}
	return b
}

// asPowerShellReadsIt decodes a profile by its BOM, as PowerShell does.
func asPowerShellReadsIt(t *testing.T, b []byte) string {
	t.Helper()
	var o binary.ByteOrder
	switch {
	case bytes.HasPrefix(b, bomLE):
		o = binary.LittleEndian
	case bytes.HasPrefix(b, bomBE):
		o = binary.BigEndian
	default:
		return string(b)
	}
	if len(b)%2 != 0 {
		t.Fatalf("a UTF-16 profile of %d bytes: a byte is out of step", len(b))
	}
	u := make([]uint16, (len(b)-2)/2)
	for i := range u {
		u[i] = o.Uint16(b[2+2*i:])
	}
	return string(utf16.Decode(u))
}

func psProfile(t *testing.T, content []byte) (Env, string) {
	t.Helper()
	home := t.TempDir()
	p := filepath.Join(home, "Documents", "WindowsPowerShell", "Microsoft.PowerShell_profile.ps1")
	if err := os.MkdirAll(filepath.Dir(p), 0o755); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(p, content, 0o644); err != nil {
		t.Fatal(err)
	}
	return Env{GOOS: "windows", Home: home, PSProfiles: []string{p}}, p
}

// lines is text with CRLF as LF: uninstall writes the lines it keeps with LF
// whatever the encoding (it always has), and PowerShell reads either.
func lines(text string) string { return strings.ReplaceAll(text, "\r\n", "\n") }

func hasStep(steps []string, s string) bool {
	for _, x := range steps {
		if x == s {
			return true
		}
	}
	return false
}

const theirProfile = "Import-Module posh-git\r\nSet-PSReadLineOption -EditMode Emacs\r\n"

func TestUTF16ProfileGetsTheLineInItsOwnEncoding(t *testing.T) {
	for name, bom := range map[string][]byte{"UTF-16LE": bomLE, "UTF-16BE": bomBE} {
		t.Run(name, func(t *testing.T) {
			e, p := psProfile(t, utf16File(bom, theirProfile))
			steps, err := Install(e)
			if err != nil {
				t.Fatal(err)
			}
			b, _ := os.ReadFile(p)
			if !bytes.HasPrefix(b, bom) {
				t.Fatalf("the profile lost its BOM: % x", b[:4])
			}
			if got, want := asPowerShellReadsIt(t, b), theirProfile+PSLine+"\r\n"; got != want {
				t.Fatalf("PowerShell reads the profile as:\n%q\nwant:\n%q", got, want)
			}
			if !hasStep(steps, "the CLI loads in new shells: "+p) {
				t.Fatalf("steps = %q", steps)
			}
			// Again: the line is there now, so nothing changes.
			steps, _ = Install(e)
			if again, _ := os.ReadFile(p); !bytes.Equal(again, b) || hasStep(steps, "the CLI loads in new shells: "+p) {
				t.Fatalf("a second install changed the profile: %q, steps %q", asPowerShellReadsIt(t, again), steps)
			}
			// Uninstall takes the line out again and leaves the person's own,
			// in their encoding.
			removed, _ := Remove(e)
			b, _ = os.ReadFile(p)
			if !bytes.HasPrefix(b, bom) || lines(asPowerShellReadsIt(t, b)) != lines(theirProfile) {
				t.Fatalf("after uninstall PowerShell reads: %q", asPowerShellReadsIt(t, b))
			}
			if !hasStep(removed, "the CLI line in "+p) {
				t.Fatalf("removed = %q", removed)
			}
		})
	}
}

// A UTF-16 profile that already loads the CLI is left exactly as it is.
func TestUTF16ProfileThatLoadsTheCLIIsLeftAlone(t *testing.T) {
	before := utf16File(bomLE, theirProfile+PSLine+"\r\n")
	e, p := psProfile(t, before)
	if _, err := Install(e); err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); !bytes.Equal(b, before) {
		t.Fatalf("the profile changed; PowerShell reads: %q", asPowerShellReadsIt(t, b))
	}
}

// With no newline at its end, the line still goes on a line of its own.
func TestUTF16ProfileWithoutAFinalNewline(t *testing.T) {
	e, p := psProfile(t, utf16File(bomLE, "Import-Module posh-git"))
	if _, err := Install(e); err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(p)
	if got, want := asPowerShellReadsIt(t, b), "Import-Module posh-git\r\n"+PSLine+"\r\n"; got != want {
		t.Fatalf("PowerShell reads the profile as:\n%q\nwant:\n%q", got, want)
	}
}

// 2.6.x appended the line to a UTF-16 profile as ASCII bytes. The next install
// takes those bytes out (they are the line as garbage, not anything the
// person wrote) and leaves one line that works; uninstall takes them out too.
func TestUTF16ProfileWithTheLineAppendedAsASCIIIsMended(t *testing.T) {
	cases := map[string]struct{ before, want string }{
		"it had no line":         {theirProfile, theirProfile + PSLine + "\r\n"},
		"it had the line before": {theirProfile + PSLine + "\r\n", theirProfile + PSLine + "\r\n"},
	}
	for name, c := range cases {
		t.Run(name, func(t *testing.T) {
			damaged := append(utf16File(bomLE, c.before), []byte("\r\n"+PSLine+"\r\n")...)
			e, p := psProfile(t, damaged)
			if got := asPowerShellReadsIt(t, damaged); !strings.Contains(got, "਍") {
				t.Fatalf("the fixture is not the damage it means to be: %q", got)
			}
			steps, err := Install(e)
			if err != nil {
				t.Fatal(err)
			}
			b, _ := os.ReadFile(p)
			if got := asPowerShellReadsIt(t, b); got != c.want {
				t.Fatalf("PowerShell reads the profile as:\n%q\nwant:\n%q", got, c.want)
			}
			if !hasStep(steps, "the CLI loads in new shells: "+p) {
				t.Fatalf("steps = %q", steps)
			}
		})
	}
	t.Run("uninstall", func(t *testing.T) {
		damaged := append(utf16File(bomLE, theirProfile), []byte("\r\n"+PSLine+"\r\n")...)
		e, p := psProfile(t, damaged)
		removed, _ := Remove(e)
		b, _ := os.ReadFile(p)
		if got := asPowerShellReadsIt(t, b); lines(got) != lines(theirProfile) {
			t.Fatalf("after uninstall PowerShell reads: %q", got)
		}
		if !hasStep(removed, "the CLI line in "+p) {
			t.Fatalf("removed = %q", removed)
		}
	})
}

// UTF-8, with or without a BOM, still takes the line as it always did.
func TestUTF8ProfileStillTakesTheLine(t *testing.T) {
	for name, bom := range map[string][]byte{"with a BOM": {0xEF, 0xBB, 0xBF}, "without": nil} {
		t.Run(name, func(t *testing.T) {
			e, p := psProfile(t, append(append([]byte{}, bom...), theirProfile...))
			if _, err := Install(e); err != nil {
				t.Fatal(err)
			}
			if b, _ := os.ReadFile(p); string(b) != string(bom)+theirProfile+PSLine+"\r\n" {
				t.Fatalf("profile = %q", b)
			}
		})
	}
}

// UTF-32 is not written to at all: the person is told to add the line.
func TestUTF32ProfileIsLeftAlone(t *testing.T) {
	before := []byte{0xFF, 0xFE, 0, 0, 'x', 0, 0, 0, '\r', 0, 0, 0, '\n', 0, 0, 0}
	e, p := psProfile(t, before)
	steps, err := Install(e)
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(p); !bytes.Equal(b, before) {
		t.Fatalf("a UTF-32 profile was changed: % x", b)
	}
	found := false
	for _, s := range steps {
		found = found || strings.HasPrefix(s, "could not add the CLI to "+p)
	}
	if !found {
		t.Fatalf("steps = %q", steps)
	}
}
