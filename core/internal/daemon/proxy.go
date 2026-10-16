package daemon

import (
	"bufio"
	"errors"
	"io"
	"net/http"
	"strings"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/ipc"
)

// /appd/* is the GUI's way to appd (D12): the console (and, later, the desktop)
// asks the core, and the core answers with its own token over its own proven,
// pinned route. The token never leaves this process, and there is one TLS and
// pinning implementation, not one per front end.
//
// Only /v1/ paths are forwarded. The socket already admits only this user
// (peer check) speaking this protocol (protoGate).

// maxProxyBody bounds what a front end may send through (an attachment is the
// largest thing appd takes).
const maxProxyBody = 32 << 20

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
		ipc.WriteJSON(w, http.StatusServiceUnavailable, map[string]string{"error": "huginn core is not paired with huginn: run `huginn core pair`"})
		return
	}
	if r.Method == http.MethodGet && (strings.Contains(r.Header.Get("Accept"), "text/event-stream") || r.URL.Query().Get("stream") == "1") {
		d.proxyStream(w, r, api, path)
		return
	}
	var payload []byte
	if r.Body != nil {
		b, err := io.ReadAll(io.LimitReader(r.Body, maxProxyBody+1))
		if err != nil {
			ipc.WriteJSON(w, http.StatusBadRequest, map[string]string{"error": "reading the request: " + err.Error()})
			return
		}
		if len(b) > maxProxyBody {
			ipc.WriteJSON(w, http.StatusRequestEntityTooLarge, map[string]string{"error": "request body too large"})
			return
		}
		payload = b
	}
	if len(payload) == 0 {
		payload = nil
	}
	res, err := api.Raw(r.Context(), r.Method, path, payload)
	var se *appd.StatusError
	if err != nil && !errors.As(err, &se) {
		ipc.WriteJSON(w, http.StatusBadGateway, map[string]string{"error": "reaching huginn: " + err.Error(), "kind": appd.Classify(err).String()})
		return
	}
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(res.Status)
	w.Write(res.Body)
}

// proxyStream passes server-sent events through line by line, flushing each,
// until either side goes.
func (d *Daemon) proxyStream(w http.ResponseWriter, r *http.Request, api *appd.Client, path string) {
	fl, ok := w.(http.Flusher)
	if !ok {
		ipc.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": "streaming unsupported"})
		return
	}
	res, err := api.Stream(r.Context(), path)
	if err != nil {
		var se *appd.StatusError
		if errors.As(err, &se) {
			ipc.WriteJSON(w, se.Status, map[string]string{"error": se.Body})
			return
		}
		ipc.WriteJSON(w, http.StatusBadGateway, map[string]string{"error": "reaching huginn: " + err.Error(), "kind": appd.Classify(err).String()})
		return
	}
	defer res.Body.Close()
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	w.WriteHeader(http.StatusOK)
	fl.Flush()
	br := bufio.NewReaderSize(res.Body, 64<<10)
	for {
		line, err := br.ReadBytes('\n')
		if len(line) > 0 {
			if _, werr := w.Write(line); werr != nil {
				return
			}
			if len(line) == 1 || line[0] == ':' || br.Buffered() == 0 {
				fl.Flush()
			}
		}
		if err != nil {
			fl.Flush()
			return
		}
	}
}
