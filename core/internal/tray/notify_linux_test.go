//go:build linux && !android

package tray

import "testing"

// Model output in a body must not become markup: no clickable link that the
// notification server opens, no <img> that makes it read a local file.
func TestBodyTextEscapesMarkup(t *testing.T) {
	in := `done <a href="https://evil/login">re-auth</a> <img src="file:///etc/passwd"> & more`
	want := `done &lt;a href="https://evil/login"&gt;re-auth&lt;/a&gt; &lt;img src="file:///etc/passwd"&gt; &amp; more`
	if got := bodyText(in); got != want {
		t.Fatalf("bodyText:\n got %s\nwant %s", got, want)
	}
}
