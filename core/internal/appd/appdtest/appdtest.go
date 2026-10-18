// Package appdtest is a fake huginn-appd for the tests of packages that drive
// the appd client (the device runner, the core's /appd proxy, pairing): HTTPS
// on httptest's own certificate, with /v1/challenge answered by the
// channel-bound proof of docs/dev/protocol-tls.md for one token, the way appd
// 3.11 and later answer it.
//
// ⚠ WHY NOT httptest.NewServer. Those suites used a plain-http fake on
// loopback, because the client used to send the token to a loopback http://
// address without asking who was there. It no longer does (security scan F8:
// any account can hold a loopback port while appd restarts), so a fake has to
// prove itself exactly as appd does, and the suites now run on the proved,
// pinned path every real request takes.
//
// Imported by tests only; nothing in a shipped binary links it.
package appdtest

import (
	"encoding/json"
	"net/http"
	"net/http/httptest"
	"testing"

	"github.com/silencelen/huginn/core/internal/appd"
)

// Version is the appd version the fake reports: the first that speaks TLS.
const Version = "3.11.0"

// NewServer serves h over TLS at https://127.0.0.1:<port>. GET /v1/challenge
// is answered here, with the bound proof under token over the server's own
// certificate; every other request goes to h. Closed when t ends.
func NewServer(t testing.TB, token string, h http.Handler) *httptest.Server {
	t.Helper()
	var srv *httptest.Server
	srv = httptest.NewUnstartedServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method == http.MethodGet && r.URL.Path == "/v1/challenge" {
			fp := appd.Fingerprint(srv.Certificate().Raw)
			nonce := r.URL.Query().Get("nonce")
			w.Header().Set("Content-Type", "application/json")
			_ = json.NewEncoder(w).Encode(map[string]any{
				"version": Version,
				"tls":     map[string]any{"v": appd.ProofVersion, "fp": fp, "proof": appd.BoundProof(token, nonce, fp)},
			})
			return
		}
		h.ServeHTTP(w, r)
	}))
	srv.StartTLS()
	t.Cleanup(srv.Close)
	return srv
}
