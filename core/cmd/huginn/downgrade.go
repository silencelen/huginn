package main

import (
	"context"
	"os"
	"os/exec"
	"path/filepath"
	"regexp"
	"strings"
	"time"

	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/version"
)

// A copy of the core must never replace a NEWER installed one. Every
// installer carries a core and installs it: Huginn Desktop's NSIS installer
// runs its bundled copy's `core self-install` unconditionally, so installing
// Desktop 2.3.6 on a machine the 2.4.1 one-liner had just set up put core
// 2.3.6 back (2026-10-16). The version check lives HERE, in the
// copy being installed, so every caller is covered - an installer, the app,
// a script - and an older binary that predates it is the only one that can
// still do it.

var versionLine = regexp.MustCompile(`(?m)^\(o> huginn (\S+) `)

// installedVersion is the version of the core installed in l, or "" when
// none is, it is this very binary, or it cannot say.
func installedVersion(l install.Layout) string {
	bin := l.Binary()
	if !fileExists(bin) {
		return ""
	}
	if exe, err := os.Executable(); err == nil {
		if same, _ := sameExecutable(exe, bin); same {
			return version.Version
		}
	}
	if l.GOOS != "windows" {
		// versions/<v> behind the `current` link names it without running it.
		if dst, err := os.Readlink(filepath.Join(l.Root, "current")); err == nil {
			return filepath.Base(dst)
		}
	}
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	out, err := exec.CommandContext(ctx, bin, "version").Output()
	if err != nil {
		return ""
	}
	if m := versionLine.FindSubmatch(out); m != nil {
		return strings.TrimSpace(string(m[1]))
	}
	return ""
}

// newerInstalled: the installed core's version when it is newer than this
// copy, else "".
func newerInstalled() string {
	l, err := install.DefaultLayout()
	if err != nil {
		return ""
	}
	have := installedVersion(l)
	if newer, ok := version.Newer(have, version.Version); ok && newer {
		return have
	}
	return ""
}

func sameExecutable(a, b string) (bool, error) {
	ra, err := filepath.EvalSymlinks(a)
	if err != nil {
		return false, err
	}
	rb, err := filepath.EvalSymlinks(b)
	if err != nil {
		return false, err
	}
	fa, err := os.Stat(ra)
	if err != nil {
		return false, err
	}
	fb, err := os.Stat(rb)
	if err != nil {
		return false, err
	}
	return os.SameFile(fa, fb), nil
}

// hasFlag removes flag from args, saying whether it was there.
func hasFlag(args []string, flag string) ([]string, bool) {
	out := args[:0:0]
	found := false
	for _, a := range args {
		if a == flag {
			found = true
			continue
		}
		out = append(out, a)
	}
	return out, found
}
