package version

import (
	"strconv"
	"strings"
)

// Newer reports whether release a is newer than release b. Both are
// X.Y.Z with an optional -prerelease, which is older than its release
// (2.4.0-rc1 < 2.4.0). ok is false when either side does not read as a
// release - a from-source build (2.0.0-dev), an empty or "?" version - and
// then the caller must not judge: nothing is refused on a guess.
func Newer(a, b string) (newer, ok bool) {
	pa, oka := parse(a)
	pb, okb := parse(b)
	if !oka || !okb {
		return false, false
	}
	for i := 0; i < 3; i++ {
		if pa.n[i] != pb.n[i] {
			return pa.n[i] > pb.n[i], true
		}
	}
	switch {
	case pa.pre == pb.pre:
		return false, true
	case pa.pre == "":
		return true, true
	case pb.pre == "":
		return false, true
	}
	return lessPre(pb.pre, pa.pre), true
}

type parsed struct {
	n   [3]int
	pre string
}

func parse(v string) (parsed, bool) {
	var p parsed
	v = strings.TrimPrefix(strings.TrimSpace(v), "v")
	core, pre, _ := strings.Cut(v, "-")
	if strings.Contains(pre, "dev") {
		return p, false
	}
	f := strings.Split(core, ".")
	if len(f) != 3 {
		return p, false
	}
	for i, s := range f {
		n, err := strconv.Atoi(s)
		if err != nil || n < 0 {
			return p, false
		}
		p.n[i] = n
	}
	p.pre = pre
	return p, true
}

// lessPre orders prereleases by their dot fields, numbers as numbers and a
// trailing number inside a field too (rc9 < rc10).
func lessPre(a, b string) bool {
	fa, fb := strings.Split(a, "."), strings.Split(b, ".")
	for i := 0; i < len(fa) && i < len(fb); i++ {
		if fa[i] == fb[i] {
			continue
		}
		wa, na := splitNum(fa[i])
		wb, nb := splitNum(fb[i])
		if wa == wb && na >= 0 && nb >= 0 {
			return na < nb
		}
		return fa[i] < fb[i]
	}
	return len(fa) < len(fb)
}

func splitNum(s string) (string, int) {
	i := len(s)
	for i > 0 && s[i-1] >= '0' && s[i-1] <= '9' {
		i--
	}
	if i == len(s) {
		return s, -1
	}
	n, _ := strconv.Atoi(s[i:])
	return s[:i], n
}
