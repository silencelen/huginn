package daemon

import (
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/ipc"
)

// /appd/* is the front ends' way to appd (D12, docs/DESKTOP-VIA-CORE.md): the
// console and the desktop ask the core, and the core answers with its own
// token over its own proven, pinned route. The token never leaves this
// process, and there is one TLS and pinning implementation, not one per front
// end.
//
// It is TRANSPARENT, because the desktop sends everything through it:
//   - any method; the request body is streamed (up to appd's own 128 MiB
//     upload limit) with its own Content-Type;
//   - the response is copied as it arrives, flushed as it goes, whatever it
//     is: an event stream (GET or POST), a 25 s long poll, an image;
//   - appd's status, body and headers come back as appd sent them;
//   - a caller's X-Huginn-Client and X-Huginn-Notify reach appd as its own,
//     so appd still tells the desktop from the core (pane lease, notify claim);
//   - no fixed timeout: the caller's connection ends a request.
//
// Only /v1/ paths are forwarded. The socket already admits only this user
// (peer check) speaking this protocol (protoGate).

func (d *Daemon) apiClient() *appd.Client {
	d.mu.Lock()
	defer d.mu.Unlock()
	return d.api
}

func (d *Daemon) proxy(w http.ResponseWriter, r *http.Request) {
	path := strings.TrimPrefix(r.URL.Path, "/appd")
	if !strings.HasPrefix(path, "/v1/") {
		ipc.WriteJSON(w, http.StatusNotFound, map[string]string{"error": "the core forwards /appd/v1/... only"})
		return
	}
	if r.URL.RawQuery != "" {
		path += "?" + r.URL.RawQuery
	}
	api := d.apiClient()
	if api == nil {
		ipc.WriteJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "huginn core is not paired with huginn: run `huginn core pair`", "kind": "unpaired"})
		return
	}
	var body io.Reader
	if r.Body != nil && r.Body != http.NoBody && (r.ContentLength != 0 || len(r.TransferEncoding) > 0) {
		if r.ContentLength > appd.MaxForwardBody {
			ipc.WriteJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "request body too large"})
			return
		}
		body = http.MaxBytesReader(w, r.Body, appd.MaxForwardBody)
	}
	res, err := api.Forward(r.Context(), r.Method, path, r.Header, body)
	if err != nil {
		var mb *http.MaxBytesError
		if errors.As(err, &mb) {
			ipc.WriteJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "request body too large"})
			return
		}
		if r.Context().Err() != nil {
			return // the caller went; nobody to answer
		}
		ipc.WriteJSON(w, http.StatusBadGateway, map[string]string{"error": "reaching huginn: " + err.Error(), "kind": linkKind(err)})
		return
	}
	defer res.Body.Close()
	for k, v := range res.Header {
		if appd.ForwardableResponseHeader(k) {
			w.Header()[k] = v
		}
	}
	w.WriteHeader(res.StatusCode)
	copyFlushing(w, res.Body)
}

// linkKind names why appd could not be reached, for the front end's banner.
func linkKind(err error) string {
	var pm *appd.PinMismatchError
	switch {
	case errors.As(err, &pm), errors.Is(err, appd.ErrIntercepted), errors.Is(err, appd.ErrOldDaemon),
		strings.Contains(err.Error(), "tls handshake"):
		return "tls"
	case appd.IsAuth(err):
		return "auth"
	}
	return "unreachable"
}

// copyFlushing copies src to w, flushing after every read: an event stream's
// line, a long poll's answer and a download's chunks all leave as they come.
func copyFlushing(w http.ResponseWriter, src io.Reader) {
	fl, _ := w.(http.Flusher)
	buf := make([]byte, 32<<10)
	for {
		n, err := src.Read(buf)
		if n > 0 {
			if _, werr := w.Write(buf[:n]); werr != nil {
				return
			}
			if fl != nil {
				fl.Flush()
			}
		}
		if err != nil {
			return
		}
	}
}
