package main

import (
	"fmt"
	"os"
	"runtime"

	"golang.org/x/term"

	"github.com/silencelen/huginn/core/internal/console"
	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/version"
)

// interactive: both ends of this process are a terminal a person is at.
func interactive() bool {
	return term.IsTerminal(int(os.Stdin.Fd())) && term.IsTerminal(int(os.Stdout.Fd())) && os.Getenv("TERM") != "dumb"
}

// consoleWanted: bare `huginn` opens the console here.
func consoleWanted() bool {
	return os.Getenv("HUGINN_CONSOLE") != "0" && runtime.GOOS != "android" && interactive()
}

func runConsole(args []string) int {
	if len(args) > 0 && args[0] == "--probe" {
		// The clients ask before they hand bare `huginn` over: exit 0 = this
		// core has the console.
		return 0
	}
	if runtime.GOOS == "android" {
		fmt.Fprintln(os.Stderr, "huginn console needs huginn core, which does not run on Termux: use the verbs (huginn help)")
		return 2
	}
	if !interactive() {
		fmt.Fprintln(os.Stderr, "huginn console needs a terminal (stdin and stdout); for scripts use the verbs: huginn help")
		return 2
	}
	bin, _ := os.Executable()
	err := console.Run(console.Options{Socket: daemon.SocketPath(paths()), Proto: version.Proto, Version: version.Version, Binary: bin})
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn console: "+err.Error())
		return 1
	}
	return 0
}

func isTerminal(f *os.File) bool { return term.IsTerminal(int(f.Fd())) }
