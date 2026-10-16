package main

import (
	"flag"
	"fmt"
	"net/http"
	"os"

	"github.com/silencelen/huginn/core/internal/daemon"
)

// deviceOn is `huginn device on [--scope look|work|own] [--root DIR]
// [--name N] [--act-while-locked|--no-act-while-locked] [--claude PATH]`.
func deviceOn(args []string) int {
	fs := flag.NewFlagSet("device on", flag.ContinueOnError)
	scope := fs.String("scope", "", "look, work or own")
	root := fs.String("root", "", "work root (scope work)")
	name := fs.String("name", "", "row name (default: hostname)")
	claude := fs.String("claude", "", "path to claude")
	awl := fs.Bool("act-while-locked", false, "keep act mode while the screen is locked")
	noAwl := fs.Bool("no-act-while-locked", false, "withdraw act mode while locked (default)")
	if err := fs.Parse(args); err != nil {
		return 2
	}
	on := true
	in := daemon.DeviceSettings{Enabled: &on}
	set := func(f string) bool {
		found := false
		fs.Visit(func(x *flag.Flag) { found = found || x.Name == f })
		return found
	}
	if set("scope") {
		in.Scope = scope
	}
	if set("root") {
		in.Root = root
	}
	if set("name") {
		in.Name = name
	}
	if set("claude") {
		in.Claude = claude
	}
	if *awl && *noAwl {
		fmt.Fprintln(os.Stderr, "pick one of --act-while-locked / --no-act-while-locked")
		return 2
	}
	if *awl || *noAwl {
		v := *awl
		in.ActWhileLocked = &v
	}
	var v daemon.DeviceView
	if _, err := call(http.MethodPatch, "/core/device", in, &v); err != nil {
		fmt.Fprintln(os.Stderr, "huginn device on: "+err.Error())
		return 1
	}
	printDevice(v)
	return 0
}

func deviceOff() int {
	off := false
	var v daemon.DeviceView
	if _, err := call(http.MethodPatch, "/core/device", daemon.DeviceSettings{Enabled: &off}, &v); err != nil {
		fmt.Fprintln(os.Stderr, "huginn device off: "+err.Error())
		return 1
	}
	fmt.Println("Turned off. The core removes this machine's row from huginn (and keeps trying until it lands).")
	printDevice(v)
	return 0
}

func deviceStatus() int {
	var v daemon.DeviceView
	if _, err := call(http.MethodGet, "/core/device", nil, &v); err != nil {
		fmt.Fprintln(os.Stderr, "huginn device: the core is not running ("+err.Error()+")")
		return 1
	}
	printDevice(v)
	return 0
}

func printDevice(v daemon.DeviceView) {
	s, c := v.Status, v.Settings
	str := func(p *string) string {
		if p == nil || *p == "" {
			return "-"
		}
		return *p
	}
	on := c.Enabled != nil && *c.Enabled
	fmt.Printf("  device:  %s  (%s)\n", map[bool]string{true: "on", false: "off"}[on], s.Note)
	if on || s.DeviceID != "" {
		fmt.Printf("           name %s · scope %s · root %s · act while locked %v\n", str(c.Name), str(c.Scope), str(c.Root),
			c.ActWhileLocked != nil && *c.ActWhileLocked)
		if s.DeviceID != "" {
			fmt.Printf("           id %s · enrolled %v · busy %v · %s\n", s.DeviceID, s.Enrolled, s.Busy, lockWords(s.Locked, s.LockKnown))
		}
	}
}

func lockWords(locked, known bool) string {
	switch {
	case !known:
		return "lock unknown (treated as locked)"
	case locked:
		return "locked"
	}
	return "unlocked"
}
