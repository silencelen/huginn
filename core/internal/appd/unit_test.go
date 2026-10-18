package appd

import (
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
)

// The shared docs/dev/protocol-tls.md vector: every implementation asserts this value, so
// they agree byte for byte.
func TestBoundProofVector(t *testing.T) {
	v := BoundProof(strings.Repeat("a", 64), "00112233445566778899aabbccddeeff", strings.Repeat("0", 64))
	const want = "5b9228510040befc78c4c4314d23a489b93f403581f0fc85e291e013160240f2"
	if v != want {
		t.Fatalf("BoundProof = %s, want %s", v, want)
	}
	if !ProofMatches(v, v) {
		t.Fatal("a proof does not match itself")
	}
	if !ProofMatches(strings.ToUpper(v), v) {
		t.Fatal("hex case is not a different proof")
	}
	if ProofMatches(v[:62]+"00", v) {
		t.Fatal("a one-byte difference matched")
	}
	for _, bad := range []string{"", "zz", strings.Repeat("z", 64), v[:63]} {
		if ProofMatches(bad, v) {
			t.Fatalf("malformed %q matched", bad)
		}
	}
}

func TestPinTagIsKeyedAndShort(t *testing.T) {
	a, b := PinTag(strings.Repeat("a", 64)), PinTag(strings.Repeat("b", 64))
	if len(a) != 16 || a == b {
		t.Fatalf("tags %q %q", a, b)
	}
	if strings.Contains(a, "aaaa") {
		t.Fatal("the tag leaks the token")
	}
}

func TestNormalizeURL(t *testing.T) {
	cases := map[string]string{
		"http://192.168.1.20:8787":    "https://192.168.1.20:8787",
		"http://[fd00::1]:8787":        "https://[fd00::1]:8787",
		"http://huginn.lan:8787":       "https://huginn.lan:8787",
		"http://huginn.lan:8787/":      "https://huginn.lan:8787",
		"https://10.0.0.5:8787":        "https://10.0.0.5:8787",
		"http://127.0.0.1:8787":        "http://127.0.0.1:8787",
		"http://127.9.9.9:8787":        "http://127.9.9.9:8787",
		"http://localhost:8787":        "http://localhost:8787",
		"http://LOCALHOST:8787":        "http://LOCALHOST:8787",
		"http://[::1]:8787":            "http://[::1]:8787",
		"http://[0:0:0:0:0:0:0:1]:87":  "http://[0:0:0:0:0:0:0:1]:87",
		"http://fd00::1:8787":          "http://fd00::1:8787", // unparsable: left for URLRefusal to name
		"http://127.example.com:8787":  "https://127.example.com:8787",
		"http://127.1:8787":            "https://127.1:8787", // not a full literal
		"http://x.localhost:8787":      "https://x.localhost:8787",
		"  http://10.1.1.1:8787  ":     "https://10.1.1.1:8787",
		"http://192.168.1.1:8787/base": "https://192.168.1.1:8787/base",
	}
	for in, want := range cases {
		if got := NormalizeURL(in); got != want {
			t.Errorf("NormalizeURL(%q) = %q, want %q", in, got, want)
		}
		// Idempotent: upgrading on load AND on save must not drift.
		if got := NormalizeURL(NormalizeURL(in)); got != want {
			t.Errorf("NormalizeURL not idempotent on %q: %q", in, got)
		}
	}
}

func TestURLRefusal(t *testing.T) {
	ok := []string{"https://10.0.0.5:8787", "http://127.0.0.1:8787", "https://[fd00::1]:8787", "https://huginn.lan"}
	for _, u := range ok {
		if err := URLRefusal(u); err != nil {
			t.Errorf("URLRefusal(%q) = %v", u, err)
		}
	}
	bad := map[string]string{
		"http://fd00::1:8787":    "bracketed",
		"ftp://10.0.0.1":         "not an http(s) url",
		"10.0.0.1:8787":          "",
		"https://":               "no host",
		"https://h:99999":        "port",
		"https://u:p@10.0.0.1:1": "user name",
	}
	for u, frag := range bad {
		err := URLRefusal(u)
		if err == nil {
			t.Errorf("URLRefusal(%q) = nil", u)
			continue
		}
		if !strings.Contains(err.Error(), frag) {
			t.Errorf("URLRefusal(%q) = %v, want it to say %q", u, err, frag)
		}
	}
	if _, err := New(Config{URL: "http://fd00::1:8787"}); err == nil {
		t.Fatal("New accepted an undiallable url")
	}
	c, err := New(Config{URL: "http://192.168.1.20:8787"})
	if err != nil || c.URL() != "https://192.168.1.20:8787" {
		t.Fatalf("New did not upgrade: %v %v", c, err)
	}
}

func TestPinSet(t *testing.T) {
	tok := strings.Repeat("c", 64)
	var s PinSet
	fps := []string{}
	for _, n := range "12345" {
		fps = append(fps, strings.Repeat(string(n), 64))
	}
	for _, f := range fps {
		s = s.With(tok, f)
	}
	want := []string{fps[4], fps[3], fps[2], fps[1]}
	if got := s.For(tok); !equal(got, want) {
		t.Fatalf("newest first, capped at %d: got %v", MaxPins, got)
	}
	s = s.With(tok, fps[2])
	if got := s.For(tok); !equal(got, []string{fps[2], fps[4], fps[3], fps[1]}) {
		t.Fatalf("re-proving moves to front: %v", got)
	}
	if s.Tag == tok || strings.Contains(s.Tag, tok) {
		t.Fatal("the pin set holds the token")
	}

	// Pins belong to the token: a different one sees none, and its first pin
	// replaces the set rather than merging into it.
	other := strings.Repeat("d", 64)
	if got := s.For(other); len(got) != 0 {
		t.Fatalf("another token sees pins: %v", got)
	}
	s = s.With(other, strings.Repeat("b", 64))
	if got := s.For(other); !equal(got, []string{strings.Repeat("b", 64)}) {
		t.Fatalf("new token's set: %v", got)
	}
	if got := s.For(tok); len(got) != 0 {
		t.Fatalf("old token's pins survived a re-pair: %v", got)
	}

	// A hand-edited store with garbage in it yields only well-formed pins.
	g := PinSet{Tag: PinTag(tok), Pins: []string{"nope", strings.Repeat("A", 64), strings.Repeat("e", 64)}}
	if got := g.For(tok); !equal(got, []string{strings.Repeat("e", 64)}) {
		t.Fatalf("garbage pins kept: %v", got)
	}

	var m MemoryPinStore
	_ = m.SavePins(s)
	l, _ := m.LoadPins()
	l.Pins[0] = "mutated"
	l2, _ := m.LoadPins()
	if l2.Pins[0] == "mutated" {
		t.Fatal("MemoryPinStore hands out its own slice")
	}
}

func TestTokenReasons(t *testing.T) {
	t.Setenv(TokenEnv, "")
	dir := t.TempDir()
	write := func(name, body string) string {
		p := filepath.Join(dir, name)
		if err := os.WriteFile(p, []byte(body), 0o600); err != nil {
			t.Fatal(err)
		}
		return p
	}
	cases := []struct {
		path string
		want TokenReason
		frag string
	}{
		{filepath.Join(dir, "absent"), TokenMissing, "no token file at"},
		{dir, TokenIsDirectory, "is a directory"},
		{write("empty", ""), TokenEmpty, "is empty"},
		{write("nl", "\n"), TokenWhitespace, "only whitespace (1 byte)"},
		{write("ws", " \t\r\n"), TokenWhitespace, "(4 bytes)"},
	}
	dangling := filepath.Join(dir, "dangling")
	if err := os.Symlink(filepath.Join(dir, "nowhere"), dangling); err == nil {
		cases = append(cases, struct {
			path string
			want TokenReason
			frag string
		}{dangling, TokenMissing, "no token file at"})
	}
	for _, c := range cases {
		_, err := ResolveToken(FileTokenSource{Path: c.path})
		var nt *NoTokenError
		if !errors.As(err, &nt) || nt.Reason != c.want || !strings.Contains(err.Error(), c.frag) {
			t.Errorf("%s: got %v (%#v), want reason %d containing %q", c.path, err, nt, c.want, c.frag)
		}
		if Classify(err) != KindAuth {
			t.Errorf("%s: no token must classify as auth, got %v", c.path, Classify(err))
		}
	}

	if os.Geteuid() != 0 {
		p := write("locked", "tok")
		_ = os.Chmod(p, 0)
		_, err := ResolveToken(FileTokenSource{Path: p})
		var nt *NoTokenError
		if !errors.As(err, &nt) || nt.Reason != TokenUnreadable {
			t.Errorf("unreadable: %v", err)
		}
	} else {
		t.Log("running as root: the permissions case cannot be produced, skipped")
	}

	good := write("good", "  sekrit\n")
	if tok, err := ResolveToken(FileTokenSource{Path: good}); err != nil || tok != "sekrit" {
		t.Fatalf("good file: %q %v", tok, err)
	}
	// The environment wins over the source, trimmed.
	t.Setenv(TokenEnv, " fromenv \n")
	if tok, _ := ResolveToken(FileTokenSource{Path: filepath.Join(dir, "absent")}); tok != "fromenv" {
		t.Fatalf("env not first: %q", tok)
	}
	t.Setenv(TokenEnv, "")
	if _, err := ResolveToken(nil); Classify(err) != KindAuth {
		t.Fatalf("nil source: %v", err)
	}
}

func TestClassify(t *testing.T) {
	cases := map[int]Kind{400: KindPermanent, 403: KindPermanent, 404: KindPermanent, 413: KindPermanent,
		401: KindAuth, 409: KindTransient, 429: KindTransient, 500: KindTransient, 502: KindTransient}
	for st, want := range cases {
		err := error(&StatusError{Method: "POST", Path: "/x", Status: st})
		if got := Classify(err); got != want {
			t.Errorf("%d: %v, want %v", st, got, want)
		}
		// Wrapping does not change the class.
		if got := Classify(errors.Join(errors.New("ctx"), err)); got != want {
			t.Errorf("wrapped %d: %v", st, got)
		}
	}
	if Classify(nil) != KindNone || Classify(ErrIntercepted) != KindTransient || Classify(ErrOldDaemon) != KindTransient {
		t.Fatal("tls errors / nil misclassified")
	}
	if got := (&StatusError{Method: "GET", Path: "/v1/x", Status: 404, Body: "no such device"}).Error(); got != "GET /v1/x → 404 no such device" {
		t.Fatalf("message shape: %q", got)
	}
}

func equal(a, b []string) bool {
	if len(a) != len(b) {
		return false
	}
	for i := range a {
		if a[i] != b[i] {
			return false
		}
	}
	return true
}

// The wire shapes the daemon reads, field for field as client/huginn-device
// sends them: root null not omitted, persistent ABSENT when unknown,
// actWhileLocked ALWAYS a real boolean, events lines never null.
func TestWireShapes(t *testing.T) {
	enc := func(v any) string {
		b, err := json.Marshal(v)
		if err != nil {
			t.Fatal(err)
		}
		return string(b)
	}
	f := false
	cases := []struct {
		got, want string
	}{
		{enc(Enrolment{Name: "n", Platform: "linux", Scope: "look", Version: "v"}),
			`{"name":"n","platform":"linux","scope":"look","root":null,"version":"v","locked":false,"actWhileLocked":false}`},
		{enc(Enrolment{ID: "i", Name: "n", Platform: "other", Scope: "generate", Version: "v", Machine: "m",
			Models: []Model{}, Persistent: &f, ActWhileLocked: true}),
			`{"id":"i","name":"n","platform":"other","scope":"generate","root":null,"version":"v","locked":false,"machine":"m","models":[],"persistent":false,"actWhileLocked":true}`},
		{enc(Beat{Scope: "own", Version: "v"}), `{"locked":false,"scope":"own","version":"v","actWhileLocked":false}`},
		{enc(Beat{Scope: "own", Version: "v", Persistent: &f}), `{"locked":false,"scope":"own","version":"v","persistent":false,"actWhileLocked":false}`},
		{enc(Events{Lines: []string{"a"}, Locked: true}), `{"lines":["a"],"locked":true}`},
	}
	code := 3
	cases = append(cases, struct{ got, want string }{
		enc(Events{Lines: []string{}, Done: true, ExitCode: &code, Error: "boom"}),
		`{"lines":[],"done":true,"exitCode":3,"error":"boom","locked":false}`})
	for _, c := range cases {
		if c.got != c.want {
			t.Errorf("\n got %s\nwant %s", c.got, c.want)
		}
	}
}
