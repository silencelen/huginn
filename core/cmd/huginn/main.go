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
	default:
		usage()
		os.Exit(2)
	}
}

func usage() {
	fmt.Fprintln(os.Stderr, `usage: huginn version
       huginn core run | install [--logon s4u|password] | uninstall | status | pair --url URL
       huginn device on [--scope look|work|own] [--root DIR] [--name N] [--act-while-locked] | off | status`)
}
