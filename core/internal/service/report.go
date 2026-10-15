// Package service installs, removes and reports on the core's boot service
// (D2/D3): a systemd --user unit plus linger on Linux, the "Huginn Core"
// scheduled task on Windows, and the tray role's login autostart on both.
//
// Every flow is a list of steps run in order that STOPS AT THE FIRST FAILURE
// and reports each step as done, unchanged, skipped, warning, FAILED or not
// done — so nothing ever prints "removed" over a failure, and a person can
// see exactly what is and is not on the machine. Text/XML generation is pure
// and golden-tested; every OS command goes through an injectable Runner.
package service

import (
	"fmt"
	"strings"
)

// State of one step.
type State string

const (
	Done      State = "done"
	Unchanged State = "unchanged"
	Skipped   State = "skipped"
	Warning   State = "warning" // done, but with a consequence a person must hear
	Failed    State = "FAILED"
	NotDone   State = "not done" // never attempted, because an earlier step failed
)

// Step is one line of a report.
type Step struct {
	Name   string
	State  State
	Detail string
}

// Report is the outcome of a flow.
type Report struct {
	Action string
	Steps  []Step
	Notes  []string
}

// OK: no step failed.
func (r Report) OK() bool {
	for _, s := range r.Steps {
		if s.State == Failed {
			return false
		}
	}
	return true
}

// FailedStep is the failing step's name, or "".
func (r Report) FailedStep() string {
	for _, s := range r.Steps {
		if s.State == Failed {
			return s.Name
		}
	}
	return ""
}

// String renders the report for a terminal.
func (r Report) String() string {
	var b strings.Builder
	fmt.Fprintf(&b, "huginn core %s\n", r.Action)
	for _, s := range r.Steps {
		line := fmt.Sprintf("  %-9s  %s", s.State, s.Name)
		if s.Detail != "" {
			line += " — " + s.Detail
		}
		b.WriteString(strings.TrimRight(line, " ") + "\n")
	}
	if f := r.FailedStep(); f != "" {
		var done []string
		for _, s := range r.Steps {
			if s.State == Done || s.State == Warning {
				done = append(done, s.Name)
			}
		}
		fmt.Fprintf(&b, "%s stopped at %q.", r.Action, f)
		if len(done) == 0 {
			b.WriteString(" Nothing was changed.\n")
		} else {
			fmt.Fprintf(&b, " Already done: %s.\n", strings.Join(done, "; "))
		}
	}
	for _, n := range r.Notes {
		b.WriteString(n + "\n")
	}
	return b.String()
}

// step is one planned action. fn returns the state it reached and a detail;
// returning Failed stops the flow.
type step struct {
	name string
	fn   func() (State, string)
}

func run(action string, steps []step) Report {
	r := Report{Action: action}
	failed := false
	for _, s := range steps {
		if failed {
			r.Steps = append(r.Steps, Step{Name: s.name, State: NotDone})
			continue
		}
		st, detail := s.fn()
		r.Steps = append(r.Steps, Step{Name: s.name, State: st, Detail: detail})
		if st == Failed {
			failed = true
		}
	}
	return r
}
