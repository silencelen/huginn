// huginn — the core binary. P1 scope: `huginn core …` and `huginn device …`;
// the interactive front (attach, remote verbs) stays in huginn.sh / huginn.ps1
// until P3. See docs/CORE-ARCHITECTURE.md.
package main

import (
	"fmt"
	"os"

	"github.com/silencelen/huginn/core/internal/version"
)

func main() {
	if len(os.Args) < 2 {
		// Bare `huginn` in an interactive terminal is the console; piped, in a
		// script, TERM=dumb or HUGINN_CONSOLE=0 it stays what it always was
		// (the client attaches to main).
		if consoleWanted() {
			os.Exit(runConsole(nil))
		}
		if code, ok := forwardToClient(nil); ok {
			os.Exit(code)
		}
		usage()
		os.Exit(2)
	}
	switch os.Args[1] {
	case "version", "--version":
		fmt.Printf("(o> huginn %s (core proto %s)\n", version.Version, version.Proto)
	case "device":
		os.Exit(device(os.Args[2:]))
	case "core":
		os.Exit(core(os.Args[2:]))
	case "console":
		os.Exit(runConsole(os.Args[2:]))
	case "uninstall":
		os.Exit(uninstall(os.Args[2:]))
	case "setup":
		os.Exit(setup(os.Args[2:]))
	default:
		if code, ok := forwardToClient(os.Args[1:]); ok {
			os.Exit(code)
		}
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `usage: huginn version
       huginn core run | install [--logon s4u|password] | uninstall | status | pair --url URL | self-install
       huginn device on [--scope look|work|own] [--root DIR] [--name N] [--act-while-locked] | off | status
       huginn console                     the interactive console (plain "huginn" in a terminal opens it)
       huginn setup ssh [--host H] [--user U]   SSH to the huginn host, for attaching and the session verbs
       huginn uninstall [--all] [--yes]   the whole stack: desktop, local AI, device, core, CLI
Every other verb (sessions, attach, projects, usage, update) is the shell client's:
install it with the one-liner in the README, and the core hands those verbs to it.`)
}
