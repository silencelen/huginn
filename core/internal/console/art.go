package console

import (
	_ "embed"
	"strings"
)

// The brand's own terminal renderings (BRAND.md §3: never redraw the mark).
// art/ is a copy of assets/brand/explorations/round5; TestArtMatchesBrand
// fails when they drift.

//go:embed art/perch-upright.txt
var perchUni string

//go:embed art/perch-upright-ascii.txt
var perchASCII string

func perch(ascii bool) []string {
	s := perchUni
	if ascii {
		s = perchASCII
	}
	return strings.Split(strings.TrimRight(s, "\n"), "\n")
}

// raven1 is the one-row raven (BRAND.md: `(o>`).
func raven1(st state) string {
	switch st {
	case stNeeds:
		return "(!>"
	case stWaiting:
		return "(->"
	}
	return "(o>"
}
