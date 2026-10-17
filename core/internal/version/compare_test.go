package version

import "testing"

func TestNewer(t *testing.T) {
	cases := []struct {
		a, b         string
		newer, judge bool
	}{
		{"2.4.1", "2.3.6", true, true},
		{"2.3.6", "2.4.1", false, true},
		{"2.4.1", "2.4.1", false, true},
		{"2.10.0", "2.9.9", true, true},
		{"2.4.0", "2.4.0-rc1", true, true},
		{"2.4.0-rc1", "2.4.0", false, true},
		{"2.4.0-rc10", "2.4.0-rc9", true, true},
		{"2.1.2-rc2", "2.1.2-rc1", true, true},
		{"v2.4.1", "2.4.0", true, true},
		{"2.0.0-dev", "2.4.1", false, false}, // a from-source build is never judged
		{"2.4.1", "2.0.0-dev", false, false},
		{"", "2.4.1", false, false},
		{"?", "2.4.1", false, false},
		{"2.4", "2.4.1", false, false},
	}
	for _, c := range cases {
		n, ok := Newer(c.a, c.b)
		if n != c.newer || ok != c.judge {
			t.Errorf("Newer(%q, %q) = %v, %v; want %v, %v", c.a, c.b, n, ok, c.newer, c.judge)
		}
	}
}
