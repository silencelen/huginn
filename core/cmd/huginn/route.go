package main

import (
	"flag"
	"fmt"
	"net/http"
	"net/url"
	"os"
	"time"

	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/ui"
)

// `huginn core route [list] | add URL [--name N] [--kind K] | remove URL |
// pin URL|none | auto on|off` — the route book (the paired daemon's other
// addresses, docs/design/desktop-via-core.md), the same one the desktop shows in
// Settings > Host.
func coreRoute(args []string) int {
	sub := "list"
	if len(args) > 0 {
		sub, args = args[0], args[1:]
	}
	var v daemon.RoutesView
	var err error
	switch sub {
	case "list", "ls":
		_, err = call(http.MethodGet, "/core/routes", nil, &v)
	case "add":
		fs := flag.NewFlagSet("core route add", flag.ContinueOnError)
		name := fs.String("name", "", "a name to show for it")
		kind := fs.String("kind", "", "lan, mesh, tailnet or custom")
		if len(args) == 0 {
			fmt.Fprintln(os.Stderr, "usage: huginn core route add <https://host:8787> [--name N] [--kind lan|mesh|tailnet|custom]")
			return 2
		}
		u := args[0]
		if err := fs.Parse(args[1:]); err != nil {
			return 2
		}
		_, err = call(http.MethodPost, "/core/routes", daemon.RouteAdd{URL: u, Name: *name, Kind: *kind}, &v)
	case "remove", "rm":
		if len(args) != 1 {
			fmt.Fprintln(os.Stderr, "usage: huginn core route remove <url>")
			return 2
		}
		_, err = call(http.MethodDelete, "/core/routes?url="+url.QueryEscape(args[0]), nil, &v)
	case "pin":
		if len(args) != 1 {
			fmt.Fprintln(os.Stderr, "usage: huginn core route pin <url>|none")
			return 2
		}
		p := args[0]
		if p == "none" || p == "off" {
			p = ""
		}
		_, err = call(http.MethodPatch, "/core/routes", daemon.RoutePatch{Pinned: &p}, &v)
	case "auto":
		if len(args) != 1 || (args[0] != "on" && args[0] != "off") {
			fmt.Fprintln(os.Stderr, "usage: huginn core route auto on|off")
			return 2
		}
		on := args[0] == "on"
		_, err = call(http.MethodPatch, "/core/routes", daemon.RoutePatch{AutoSwitch: &on}, &v)
	default:
		fmt.Fprintln(os.Stderr, "usage: huginn core route [list] | add <url> [--name N] [--kind K] | remove <url> | pin <url>|none | auto on|off")
		return 2
	}
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn core route: "+err.Error())
		return 1
	}
	printRoutes(v)
	return 0
}

func printRoutes(v daemon.RoutesView) {
	u := ui.New()
	u.Head("huginn core route")
	if len(v.Routes) == 0 {
		u.Say("Not paired with huginn yet: huginn core install (or huginn core pair --url <https://host:8787>).")
		return
	}
	for _, r := range v.Routes {
		st, comp := ui.Skip, ""
		switch {
		case r.Active:
			st, comp = ui.OK, "active"
		case !r.LastFail.IsZero() && r.LastFail.After(r.LastSeen):
			st = ui.Warn
		case !r.LastSeen.IsZero():
			st = ui.OK
		}
		text := r.URL
		var tags []string
		if r.Name != "" {
			tags = append(tags, r.Name)
		}
		if r.Kind != "" {
			tags = append(tags, r.Kind)
		}
		if r.Preferred {
			tags = append(tags, "paired on")
		}
		if r.Pinned {
			tags = append(tags, "PINNED")
		}
		if len(tags) > 0 {
			text += "  (" + join(tags) + ")"
		}
		u.Line(st, comp, text)
		switch {
		case !r.LastFail.IsZero() && r.LastFail.After(r.LastSeen):
			u.Note("not answering since " + ago(r.LastFail) + ": " + r.LastError)
		case !r.LastSeen.IsZero():
			u.Note("answered " + ago(r.LastSeen))
		default:
			u.Note("not tried yet")
		}
	}
	u.Say("")
	if v.AutoSwitch {
		u.Say(u.Dim("auto-switch on: an address that does not answer hands over to the next (huginn core route auto off)"))
	} else {
		u.Say(u.Dim("auto-switch off: requests stay on the active address (huginn core route auto on)"))
	}
}

func join(s []string) string {
	out := ""
	for i, x := range s {
		if i > 0 {
			out += ", "
		}
		out += x
	}
	return out
}

func ago(t time.Time) string {
	d := time.Since(t).Round(time.Second)
	switch {
	case d < time.Minute:
		return fmt.Sprintf("%d s ago", int(d/time.Second))
	case d < time.Hour:
		return fmt.Sprintf("%d min ago", int(d/time.Minute))
	}
	return t.Local().Format("Jan 2 15:04")
}
