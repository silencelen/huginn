package doctor

import (
	"fmt"

	"github.com/silencelen/huginn/core/internal/ui"
)

// Render prints the report the way the installers print theirs: one line per
// check, the fix under a line that is not ok, and a closing count.
func Render(u *ui.Out, checks []Check) {
	u.Head("huginn doctor")
	fails, warns := 0, 0
	for _, c := range checks {
		st := ui.OK
		switch c.State {
		case Warn:
			st, warns = ui.Warn, warns+1
		case Fail:
			st, fails = ui.Fail, fails+1
		case Skip:
			st = ui.Skip
		}
		u.Line(st, c.Comp, c.Detail)
		if c.State != OK && c.Fix != "" {
			u.Note("try: " + c.Fix)
		}
	}
	u.Say("")
	switch {
	case fails > 0:
		u.Say(fmt.Sprintf("%d of %d checks failed; start with the first one that did.", fails, len(checks)))
	case warns > 0:
		u.Say(fmt.Sprintf("Nothing failed; %d check(s) could use a look.", warns))
	default:
		u.Say("Everything huginn needs on this machine looks fine.")
	}
}
