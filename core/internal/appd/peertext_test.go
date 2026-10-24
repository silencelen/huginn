package appd

// What a peer writes reaches an error, and errors reach the terminal, the log
// and the status note the apps show. The challenge is answered by whoever
// holds the address, before it has proved anything, so its body is cleaned
// and clipped first. On the old code the body
// went into the error as sent: escape sequences, line breaks and all.

import (
	"context"
	"crypto/tls"
	"errors"
	"io"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
	"unicode"
	"unicode/utf8"
)

// hostile is a body a peer can send: an OSC 52 clipboard write, a screen
// clear, a line break that forges a log line, an 8-bit CSI, a bidi override,
// bytes that are not UTF-8, and then a great deal more.
var hostile = "\x1b]52;c;ZXZpbA==\x07\x1b[2J\r\n2026/10/18 forged log line\x9b31m\u202eevil\xff\xfe" +
	strings.Repeat("A", 60<<10)

// shownSafely fails t when s holds anything a terminal would act on.
func shownSafely(t *testing.T, what, s string) {
	t.Helper()
	if !utf8.ValidString(s) {
		t.Errorf("%s is not UTF-8: %q", what, s)
	}
	for _, r := range s {
		if r < 0x20 || r == 0x7f || (r >= 0x80 && r <= 0x9f) || unicode.Is(unicode.Cf, r) {
			t.Errorf("%s holds %U: %q", what, r, s)
			return
		}
	}
}

func TestPrintable(t *testing.T) {
	for in, want := range map[string]string{
		"":                                  "",
		"not found":                         "not found",
		"  two\n lines\r\n\tand tabs ":      "two lines and tabs",
		"\x1b[2Jclear":                      "[2Jclear",
		"bell\x07 del\x7f c1\u009b":         "bell del c1",
		"bidi \u202eoverride\u200b":         "bidi override",
		"bad \xff byte":                     "bad \ufffd byte",
		"\u00fcn\u00efc\u00f6d\u00e9 stays": "\u00fcn\u00efc\u00f6d\u00e9 stays",
	} {
		if got := Printable(in, 200); got != want {
			t.Errorf("Printable(%q) = %q, want %q", in, got, want)
		}
	}
	long := strings.Repeat("\u00e9", 300) // two bytes each: a cut can fall inside one
	got := Printable(long, 200)
	if len(got) > 200 || !utf8.ValidString(got) || !strings.HasSuffix(got, "\u2026") {
		t.Fatalf("clipped to %d bytes, valid %v: %q", len(got), utf8.ValidString(got), got)
	}
	if got := Printable(strings.Repeat("a", 200), 200); got != strings.Repeat("a", 200) {
		t.Fatalf("exactly max was cut: %q", got)
	}
	shownSafely(t, "Printable(hostile)", Printable(hostile, 200))
}

// A peer that answers the challenge with an error and a hostile body.
func TestChallengeBodyIsCleanedAndClipped(t *testing.T) {
	c := mintCert(t, "not-huginn")
	srv := httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
		_, _ = io.WriteString(w, hostile)
	}))
	srv.Listener = tls.NewListener(srv.Listener, certCfg(c))
	srv.Start()
	t.Cleanup(srv.Close)

	cl := newTestClient(t, "https://"+srv.Listener.Addr().String(), nil, nil)
	_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
	if err == nil || !strings.Contains(err.Error(), "answered 500") {
		t.Fatalf("want the challenge's refusal, got %v", err)
	}
	shownSafely(t, "the error", err.Error())
	if n := len(err.Error()); n > 400 {
		t.Fatalf("the error carries %d bytes of the peer's body", n)
	}
	if !strings.Contains(err.Error(), "forged log line") {
		t.Fatalf("the body's text should still be shown, cleaned: %v", err)
	}
}

// A status error's body is shown the same way, whoever sent it.
func TestStatusErrorBodyIsCleaned(t *testing.T) {
	c := mintCert(t, "good")
	f := fakeDaemon(t, certCfg(c), goodAnswer(testToken, c), func(f *fake) {
		f.other = func(w http.ResponseWriter, r *http.Request) {
			w.WriteHeader(http.StatusBadRequest)
			_, _ = io.WriteString(w, hostile)
		}
	})
	cl := newTestClient(t, f.url(), nil, nil)
	_, err := cl.Do(context.Background(), "GET", "/v1/ping", nil)
	var se *StatusError
	if !errors.As(err, &se) || se.Status != http.StatusBadRequest {
		t.Fatalf("want a 400 StatusError, got %v", err)
	}
	if len(se.Body) > 200 {
		t.Fatalf("the body kept %d bytes", len(se.Body))
	}
	shownSafely(t, "the status error", err.Error())

	res, err := cl.Stream(context.Background(), "/v1/watch?stream=1")
	if err == nil {
		res.Body.Close()
		t.Fatal("a 400 stream opened")
	}
	shownSafely(t, "the stream's status error", err.Error())
}
