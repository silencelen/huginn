package main

import (
	"fmt"
	"os"

	"github.com/silencelen/huginn/core/internal/doctor"
	"github.com/silencelen/huginn/core/internal/ui"
	"github.com/silencelen/huginn/core/internal/version"
)

// `huginn doctor [--json]`: a deterministic health report of this machine's
// huginn, no Claude involved (internal/doctor). Lines for a person, or the
// stable JSON array for a script; exit 1 when any check failed, 0 otherwise.
func doctorCmd(args []string) int {
	asJSON := false
	for _, a := range args {
		switch a {
		case "--json":
			asJSON = true
		default:
			fmt.Fprintln(os.Stderr, "usage: huginn doctor [--json]")
			return 2
		}
	}
	p, err := doctor.Default(version.Version)
	if err != nil {
		fmt.Fprintln(os.Stderr, "huginn doctor: "+err.Error())
		return 1
	}
	checks := doctor.Run(p)
	if asJSON {
		os.Stdout.Write(doctor.JSON(checks))
	} else {
		doctor.Render(ui.New(), checks)
	}
	if doctor.Failed(checks) {
		return 1
	}
	return 0
}
