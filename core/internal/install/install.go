// Package install puts the running huginn binary into the per-user layout
// (docs/CORE-ARCHITECTURE.md §7.1) — the one implementation behind the
// bootstrap scripts, the desktop's "Set up huginn core" button and the
// packages, so they cannot drift apart.
//
//	Linux:   ${XDG_DATA_HOME:-~/.local/share}/huginn/versions/<v>/huginn
//	         …/huginn/current -> versions/<v>        (relative, swapped atomically)
//	         ~/.local/bin/huginn -> …/huginn/current/huginn
//	Windows: %LOCALAPPDATA%\huginn\bin\huginn.exe     (a running copy is renamed aside)
//	         and that bin directory on the user PATH
//
// ⚠ Services point at the STABLE path (~/.local/bin/huginn, or the Windows bin
// path), never into versions/<v>: an update swaps `current`, and a unit pinned to
// a version directory would keep starting the old binary forever.
package install

import (
	"errors"
	"fmt"
	"io"
	"os"
	"path/filepath"
	"runtime"
	"sort"
	"strings"
)

// Layout is where a per-user install lives.
type Layout struct {
	GOOS string
	Root string // Linux: …/share/huginn   Windows: %LOCALAPPDATA%\huginn
	Link string // Linux: ~/.local/bin/huginn  Windows: unused
}

// DefaultLayout for this user.
func DefaultLayout() (Layout, error) {
	return LayoutFor(runtime.GOOS, os.Getenv)
}

// LayoutFor is pure, for tests.
func LayoutFor(goos string, env func(string) string) (Layout, error) {
	if goos == "windows" {
		la := env("LOCALAPPDATA")
		if la == "" {
			return Layout{}, errors.New("LOCALAPPDATA is not set")
		}
		return Layout{GOOS: goos, Root: filepath.Join(la, "huginn")}, nil
	}
	home := env("HOME")
	if home == "" {
		return Layout{}, errors.New("HOME is not set")
	}
	data := env("XDG_DATA_HOME")
	if !filepath.IsAbs(data) {
		data = filepath.Join(home, ".local", "share")
	}
	return Layout{GOOS: goos, Root: filepath.Join(data, "huginn"), Link: filepath.Join(home, ".local", "bin", "huginn")}, nil
}

// Binary is the stable path services and shells should run.
func (l Layout) Binary() string {
	if l.GOOS == "windows" {
		return filepath.Join(l.Root, "bin", "huginn.exe")
	}
	return l.Link
}

// Result says what Install did, one line per step.
type Result struct {
	Steps   []string
	Binary  string
	Changed bool
}

// keep is how many version directories survive a prune (the running one and
// the one before it, so a bad update can be rolled back by hand).
const keep = 2

// Install copies src (normally os.Executable()) in as version v.
func Install(l Layout, src, v string) (Result, error) {
	if l.GOOS == "windows" {
		return installWindows(l, src)
	}
	return installUnix(l, src, v)
}

func installUnix(l Layout, src, v string) (Result, error) {
	var r Result
	r.Binary = l.Link
	if strings.ContainsAny(v, `/\`) || v == "" || v == "." || v == ".." {
		return r, fmt.Errorf("refusing version %q", v)
	}
	vdir := filepath.Join(l.Root, "versions", v)
	dst := filepath.Join(vdir, "huginn")
	same, err := sameFile(src, dst)
	if err != nil {
		return r, err
	}
	if !same {
		if err := os.MkdirAll(vdir, 0o755); err != nil {
			return r, err
		}
		if err := copyAtomic(src, dst, 0o755); err != nil {
			return r, err
		}
		r.Steps = append(r.Steps, "installed "+dst)
		r.Changed = true
	} else {
		r.Steps = append(r.Steps, "already installed: "+dst)
	}
	// current -> versions/<v>, swapped by renaming a fresh symlink over it.
	cur := filepath.Join(l.Root, "current")
	want := filepath.Join("versions", v)
	if got, _ := os.Readlink(cur); got != want {
		tmp := cur + ".tmp"
		os.Remove(tmp)
		if err := os.Symlink(want, tmp); err != nil {
			return r, err
		}
		if err := os.Rename(tmp, cur); err != nil {
			os.Remove(tmp)
			return r, fmt.Errorf("swapping current: %w", err)
		}
		r.Steps = append(r.Steps, "current -> "+want)
		r.Changed = true
	}
	// ~/.local/bin/huginn -> <root>/current/huginn
	target := filepath.Join(cur, "huginn")
	if got, err := os.Readlink(l.Link); err != nil || got != target {
		if fi, err := os.Lstat(l.Link); err == nil && fi.Mode()&os.ModeSymlink == 0 {
			return r, fmt.Errorf("%s exists and is not huginn's link — move it aside first", l.Link)
		}
		if err := os.MkdirAll(filepath.Dir(l.Link), 0o755); err != nil {
			return r, err
		}
		tmp := l.Link + ".tmp"
		os.Remove(tmp)
		if err := os.Symlink(target, tmp); err != nil {
			return r, err
		}
		if err := os.Rename(tmp, l.Link); err != nil {
			os.Remove(tmp)
			return r, err
		}
		r.Steps = append(r.Steps, l.Link+" -> "+target)
		r.Changed = true
	}
	r.Steps = append(r.Steps, prune(filepath.Join(l.Root, "versions"), v)...)
	return r, nil
}

// prune removes version directories beyond `keep`: the current one always
// stays, plus the newest of the others (the rollback target). Versions compare
// numerically where they parse (2.10.0 > 2.9.0).
func prune(dir, current string) []string {
	ents, err := os.ReadDir(dir)
	if err != nil {
		return nil
	}
	var others []string
	for _, e := range ents {
		if e.IsDir() && e.Name() != current {
			others = append(others, e.Name())
		}
	}
	sort.Slice(others, func(i, j int) bool { return versionLess(others[j], others[i]) }) // newest first
	var out []string
	for i, v := range others {
		if i < keep-1 {
			continue
		}
		if err := os.RemoveAll(filepath.Join(dir, v)); err == nil {
			out = append(out, "pruned "+v)
		}
	}
	return out
}

func versionLess(a, b string) bool {
	pa, pb := splitVersion(a), splitVersion(b)
	for i := 0; i < len(pa) && i < len(pb); i++ {
		if pa[i] != pb[i] {
			return pa[i] < pb[i]
		}
	}
	if len(pa) != len(pb) {
		return len(pa) < len(pb)
	}
	return a < b
}

func splitVersion(v string) []int {
	v = strings.SplitN(v, "-", 2)[0]
	var out []int
	for _, p := range strings.Split(v, ".") {
		n := 0
		for _, c := range p {
			if c < '0' || c > '9' {
				break
			}
			n = n*10 + int(c-'0')
		}
		out = append(out, n)
	}
	return out
}

func sameFile(a, b string) (bool, error) {
	fa, err := os.Stat(a)
	if err != nil {
		return false, err
	}
	fb, err := os.Stat(b)
	if errors.Is(err, os.ErrNotExist) {
		return false, nil
	}
	if err != nil {
		return false, err
	}
	return os.SameFile(fa, fb), nil
}

// copyAtomic writes dst via a temp file in the same directory and a rename,
// so a half-copied binary is never at dst.
func copyAtomic(src, dst string, mode os.FileMode) error {
	in, err := os.Open(src)
	if err != nil {
		return err
	}
	defer in.Close()
	tmp, err := os.CreateTemp(filepath.Dir(dst), ".huginn-*")
	if err != nil {
		return err
	}
	defer os.Remove(tmp.Name())
	if _, err := io.Copy(tmp, in); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Chmod(mode); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Sync(); err != nil {
		tmp.Close()
		return err
	}
	if err := tmp.Close(); err != nil {
		return err
	}
	return os.Rename(tmp.Name(), dst)
}
