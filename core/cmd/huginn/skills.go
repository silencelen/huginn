package main

import (
	"bufio"
	"fmt"
	"os"
	"os/exec"
	"path/filepath"
	"strings"

	"github.com/silencelen/huginn/core/internal/skills"
	"github.com/silencelen/huginn/core/internal/ui"
	"golang.org/x/term"
)

// `huginn skills [status|install|remove]`: the Claude Code skills the core
// carries (internal/skills), in ~/.claude/skills. `core self-install` lays
// them under the install root on every update and refreshes the ones already
// in ~/.claude/skills; putting them there the first time is the installer's
// question (skillsOffer) or `huginn skills install`.

// skillsDeclined is the state marker a "no" to the question leaves, so no
// later `huginn core install` asks again. `huginn skills install` removes it;
// `huginn skills remove` writes it.
func skillsDeclined() string { return filepath.Join(paths().State, "skills-declined") }

func skillsCmd(args []string) int {
	sub := "status"
	if len(args) > 0 {
		sub = args[0]
	}
	e, err := skills.DefaultEnv()
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn skills: "+err.Error())
		return 1
	}
	u := ui.New()
	switch sub {
	case "status":
		return skillsStatus(u, e)
	case "install":
		return skillsInstall(u, e)
	case "remove":
		return skillsRemove(u, e)
	case "offer":
		// Hidden: the installer's question, run by the PowerShell client after
		// its elevated `core install --report` step (that step cannot ask).
		return skillsOffer(u, e, false)
	}
	fmt.Fprintln(os.Stderr, "usage: huginn skills [status|install|remove]")
	return 2
}

func skillsLinkDir(e skills.Env) string { return tilde(e.Home, e.LinkDir()) }

// skillsStatus: one line per skill, then whether Claude Code is here. Exit 0
// when every skill is installed as this version lays it, 1 otherwise.
func skillsStatus(u *ui.Out, e skills.Env) int {
	all := true
	for _, en := range skills.Status(e) {
		switch en.State {
		case skills.StateLinked:
			u.Line(ui.OK, "skills", en.Name+" -> "+tilde(e.Home, en.Target))
		case skills.StateCopied:
			u.Line(ui.OK, "skills", en.Name+": huginn's copy in "+tilde(e.Home, en.Path))
		case skills.StateStale:
			all = false
			u.Line(ui.Warn, "skills", en.Name+": huginn's, but not this version's (huginn skills install refreshes it)")
		case skills.StateForeign:
			all = false
			u.Line(ui.Warn, "skills", en.Name+": "+tilde(e.Home, en.Path)+" is "+en.Detail+" - left alone")
		default:
			all = false
			u.Line(ui.Skip, "skills", en.Name+": not installed (huginn skills install)")
		}
	}
	if skills.ClaudePresent(e, exec.LookPath) {
		u.Line(ui.OK, "claude", "Claude Code is on this machine; a new session loads the skills")
	} else {
		u.Line(ui.Skip, "claude", "Claude Code is not on this machine (no claude on PATH, no ~/.claude), so nothing loads them yet")
	}
	if all {
		return 0
	}
	return 1
}

// skillsInstall lays the skills under the install root and links or copies
// them into ~/.claude/skills; what the person wrote there stays.
func skillsInstall(u *ui.Out, e skills.Env) int {
	if _, err := skills.Lay(e); err != nil {
		u.Line(ui.Fail, "skills", "could not lay the skills down under "+tilde(e.Home, e.Dir())+": "+err.Error())
		return 1
	}
	os.Remove(skillsDeclined())
	rc := skillsLink(u, e)
	if !skills.ClaudePresent(e, exec.LookPath) {
		u.Note("Claude Code was not found here (no claude on PATH, no ~/.claude); the skills load once it is installed.")
	}
	return rc
}

// skillsLink is the Install call and its lines.
func skillsLink(u *ui.Out, e skills.Env) int {
	r := skills.Install(e)
	how := "linked into"
	if e.GOOS == "windows" {
		how = "copied into"
	}
	switch {
	case len(r.Done) > 0:
		u.Line(ui.OK, "skills", strings.Join(r.Done, ", ")+" "+how+" "+skillsLinkDir(e)+" (a new Claude Code session loads them)")
	case r.OK() && len(r.Kept) == 0:
		u.Line(ui.OK, "skills", strings.Join(skills.Names(), ", ")+" are already in "+skillsLinkDir(e))
	}
	for _, k := range r.Kept {
		u.Line(ui.Warn, "skills", k)
	}
	for _, err := range r.Errors {
		u.Line(ui.Fail, "skills", err)
	}
	if !r.OK() {
		return 1
	}
	return 0
}

// skillsRemove takes Huginn's links or copies out of ~/.claude/skills and
// remembers the no, so an update or a re-run of the installer does not put
// them back. The install root's copies stay with the core.
func skillsRemove(u *ui.Out, e skills.Env) int {
	r := skills.Remove(e)
	os.MkdirAll(filepath.Dir(skillsDeclined()), 0o700)
	os.WriteFile(skillsDeclined(), []byte("huginn skills remove\n"), 0o600)
	switch {
	case len(r.Done) > 0:
		u.Line(ui.OK, "skills", "removed "+strings.Join(r.Done, ", ")+" from "+skillsLinkDir(e)+" (huginn skills install puts them back)")
	case r.OK() && len(r.Kept) == 0:
		u.Line(ui.Skip, "skills", "nothing of huginn's in "+skillsLinkDir(e))
	}
	for _, k := range r.Kept {
		u.Note("kept " + k)
	}
	for _, err := range r.Errors {
		u.Line(ui.Fail, "skills", err)
	}
	if !r.OK() {
		return 1
	}
	return 0
}

// skillsOffer is the installer's step: `Install Huginn skills for Claude
// Code? [Y/n]`, asked where setup asks its other questions, and only when
// there is something to ask (skills.Decide): Huginn's skills already there are
// refreshed without a word; HUGINN_SKILLS=1|0 answers up front; a no is
// remembered; a machine without Claude Code is told how to add them later;
// with no terminal to ask on the answer is no, as for every installer
// question, but not remembered, so the next run from a terminal asks. compact:
// inside an installer's block, where an unchanged refresh prints nothing.
func skillsOffer(u *ui.Out, e skills.Env, compact bool) int {
	if _, err := skills.Lay(e); err != nil {
		u.Line(ui.Warn, "skills", "could not lay the Claude Code skills down: "+err.Error())
		return 1
	}
	preset := os.Getenv("HUGINN_SKILLS")
	tty := u.TTY() && term.IsTerminal(int(os.Stdin.Fd()))
	decline := func(why string) {
		os.MkdirAll(filepath.Dir(skillsDeclined()), 0o700)
		os.WriteFile(skillsDeclined(), []byte(why+"\n"), 0o600)
	}
	switch skills.Decide(preset, skills.AnyOurs(e), fileExists(skillsDeclined()), skills.ClaudePresent(e, exec.LookPath), tty) {
	case skills.DecideRefresh:
		r := skills.Refresh(e)
		for _, err := range r.Errors {
			u.Line(ui.Warn, "skills", err)
		}
		// Name what was refreshed, not every skill: one of the names can be the
		// person's own skill, which Refresh leaves alone.
		if len(r.Done) > 0 {
			u.Line(ui.OK, "skills", strings.Join(r.Done, ", ")+" for Claude Code in "+skillsLinkDir(e))
		} else if !compact {
			u.Line(ui.OK, "skills", "Claude Code skills current in "+skillsLinkDir(e))
		}
		if !r.OK() {
			return 1
		}
	case skills.DecideInstall:
		os.Remove(skillsDeclined())
		return skillsLink(u, e)
	case skills.DecideDeclined:
		if preset != "" {
			decline("HUGINN_SKILLS=" + preset)
			u.Line(ui.Skip, "skills", "not installed for Claude Code (HUGINN_SKILLS="+preset+"); huginn skills install adds them any time")
		} else if !compact {
			u.Line(ui.Skip, "skills", "not installed for Claude Code (declined before); huginn skills install adds them any time")
		}
	case skills.DecideNoClaude:
		u.Line(ui.Skip, "skills", "Claude Code is not on this machine; huginn skills install adds its skills once it is")
	case skills.DecideAsk:
		u.Prompt("Install Huginn skills for Claude Code? [Y/n] ")
		ans, _ := bufio.NewReader(os.Stdin).ReadString('\n')
		a := strings.ToLower(strings.TrimSpace(ans))
		if a == "n" || a == "no" {
			decline("answered no")
			u.Line(ui.Skip, "skills", "not installed (huginn skills install, any time)")
			return 0
		}
		os.Remove(skillsDeclined())
		return skillsLink(u, e)
	case skills.DecideNoTTY:
		u.Line(ui.Skip, "skills", "not installed for Claude Code: no terminal to ask on (huginn skills install adds them)")
	}
	return 0
}
