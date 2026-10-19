package main

import (
	"io"
	"net/http"
	"runtime"
	"strings"
	"testing"
	"unicode"
)

// call reads at most maxCallBody of an answer. /appd/v1/status reaches it
// through the core's proxy, which passes on whatever appd sends for as long
// as appd sends it; on the old code call read all of it into memory.
func TestCallRefusesAnAnswerLargerThanItsBound(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("the fake core listens on a unix socket path")
	}
	fakeCore(t, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("Content-Type", "application/json")
		switch r.URL.Path {
		case "/appd/v1/status": // a JSON string that never ends
			io.WriteString(w, `{"addresses":["`)
			chunk := strings.Repeat("a", 64<<10)
			for n := 0; n <= maxCallBody; n += len(chunk) {
				if _, err := io.WriteString(w, chunk); err != nil {
					return
				}
			}
		case "/core/status":
			io.WriteString(w, `{"Version":"2.6.2"}`)
		case "/core/device":
			w.WriteHeader(http.StatusBadGateway)
			io.WriteString(w, `{"error":"reaching huginn: \u001b]52;c;ZXZpbA==\u0007\u001b[2J\r\nforged line"}`)
		}
	}))

	var st struct{ Addresses []string }
	_, err := call(http.MethodGet, "/appd/v1/status", nil, &st)
	if err == nil || !strings.Contains(err.Error(), "larger than") {
		t.Fatalf("an answer over %d bytes: got %v", maxCallBody, err)
	}

	var cs struct{ Version string }
	if _, err := call(http.MethodGet, "/core/status", nil, &cs); err != nil || cs.Version != "2.6.2" {
		t.Fatalf("a small answer: %+v %v", cs, err)
	}

	code, err := call(http.MethodGet, "/core/device", nil, nil)
	if code != http.StatusBadGateway || err == nil || !strings.Contains(err.Error(), "forged line") {
		t.Fatalf("an error answer: %d %v", code, err)
	}
	for _, r := range err.Error() {
		if r < 0x20 || r == 0x7f || unicode.Is(unicode.Cf, r) {
			t.Fatalf("the error text holds %U: %q", r, err.Error())
		}
	}
}
