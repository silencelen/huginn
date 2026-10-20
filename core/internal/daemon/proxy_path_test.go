package daemon

import (
	"net/http"
	"sync"
	"testing"
)

// The front ends escape every path segment they build from a name or an id
// (HuginnClient.seg), so that a name can never change the route: the chat id
// "../sessions/main?" is ONE segment, "..%2Fsessions%2Fmain%3F". The core's
// /appd proxy must hand appd those bytes as they arrived. A decoded path that
// the appd client then parsed again turned DELETE /v1/chats/<that id> into
// DELETE /v1/sessions/main (a hard end of the CLI's session), where the same
// request sent straight to appd is a harmless 404.
func TestProxyForwardsEscapedSegmentsAsTheyCame(t *testing.T) {
	var mu sync.Mutex
	var got string
	front, _ := proxyRig(t, func(w http.ResponseWriter, r *http.Request) {
		mu.Lock()
		got = r.Method + " " + r.RequestURI
		mu.Unlock()
		w.Write([]byte(`{}`))
	})
	for _, c := range []struct{ method, sent, want string }{
		// A chat id from a crafted huginn:// link, then the chat's Delete.
		{"DELETE", "/appd/v1/chats/..%2Fsessions%2Fmain%3F", "DELETE /v1/chats/..%2Fsessions%2Fmain%3F"},
		{"DELETE", "/appd/v1/chats/..%2Fsessions%2Fmain%23", "DELETE /v1/chats/..%2Fsessions%2Fmain%23"},
		// A session name carrying "?": the live view's pane-lease release.
		{"DELETE", "/appd/v1/sessions/main%3F/size", "DELETE /v1/sessions/main%3F/size"},
		{"DELETE", "/appd/v1/sessions/VICTIM%3Fx=/size", "DELETE /v1/sessions/VICTIM%3Fx=/size"},
		// An address is one segment (forgetting a reported app route).
		{"DELETE", "/appd/v1/apps/routes/https%3A%2F%2F192.0.2.10%3A8787", "DELETE /v1/apps/routes/https%3A%2F%2F192.0.2.10%3A8787"},
		// What was never escaped is forwarded as it was: a real query, a space.
		{"GET", "/appd/v1/sessions/main/screen?wait=25000", "GET /v1/sessions/main/screen?wait=25000"},
		{"GET", "/appd/v1/sessions/a%20b/overview", "GET /v1/sessions/a%20b/overview"},
	} {
		mu.Lock()
		got = ""
		mu.Unlock()
		req, err := http.NewRequest(c.method, front.URL+c.sent, nil)
		if err != nil {
			t.Fatal(err)
		}
		res, err := http.DefaultClient.Do(req)
		if err != nil {
			t.Fatal(err)
		}
		res.Body.Close()
		mu.Lock()
		seen := got
		mu.Unlock()
		if seen != c.want {
			t.Errorf("%s %s reached appd as %q, want %q", c.method, c.sent, seen, c.want)
		}
	}
}
