// Prototype Q1 (docs/CORE-ARCHITECTURE.md §11): the core's IPC server.
//
// HTTP/1.1 over an AF_UNIX socket that only this user can open, with a peer-uid
// check on every accepted connection and a protocol-version header on every
// request. The handlers stand in for what the JVM GUI will need from the real
// core: small JSON, an SSE stream that must arrive event by event (not buffered),
// a large streamed upload, a large streamed download, and a proxy-shaped route.
//
// Not product code. The point is to prove the transport, not to start the core.
package main

import (
	"context"
	"crypto/sha256"
	"encoding/hex"
	"encoding/json"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"strconv"
	"time"
)

const proto = "1"

type ctxKey struct{}

func main() {
	if len(os.Args) != 2 {
		log.Fatal("usage: server <socket path>")
	}
	sock := os.Args[1]
	if err := os.MkdirAll(filepath.Dir(sock), 0o700); err != nil {
		log.Fatal(err)
	}
	_ = os.Remove(sock)
	ln, err := net.Listen("unix", sock)
	if err != nil {
		log.Fatal(err)
	}
	// The directory is 0700 already; the socket mode is belt and braces, and the
	// peer check below is the third layer (a socket file can be hard-linked or a
	// directory mode loosened by someone else).
	if err := os.Chmod(sock, 0o600); err != nil {
		log.Fatal(err)
	}

	mux := http.NewServeMux()
	mux.HandleFunc("GET /core/status", func(w http.ResponseWriter, r *http.Request) {
		writeJSON(w, map[string]any{"ok": true, "proto": proto, "peer": r.Context().Value(ctxKey{})})
	})
	mux.HandleFunc("GET /core/events", sse)
	mux.HandleFunc("POST /core/upload", upload)
	mux.HandleFunc("GET /core/download", download)
	// Proxy-shaped: the GUI's appd calls arrive under /appd/ and the real core
	// will forward them with the token added. Here it only echoes what it saw,
	// so the client can assert that an Authorization header it sent was stripped.
	mux.HandleFunc("/appd/", func(w http.ResponseWriter, r *http.Request) {
		r.Header.Del("Authorization")
		writeJSON(w, map[string]any{"method": r.Method, "path": r.URL.Path[len("/appd"):], "query": r.URL.RawQuery,
			"sawAuthorization": r.Header.Get("Authorization") != ""})
	})

	srv := &http.Server{
		Handler:           protoGate(mux),
		ReadHeaderTimeout: 10 * time.Second,
		ConnContext: func(ctx context.Context, c net.Conn) context.Context {
			who, err := peerIdentity(c)
			if err != nil {
				return context.WithValue(ctx, ctxKey{}, "unknown:"+err.Error())
			}
			return context.WithValue(ctx, ctxKey{}, who)
		},
	}
	log.Printf("listening on %s", sock)
	log.Fatal(srv.Serve(&peerCheckedListener{Listener: ln}))
}

// peerCheckedListener drops any connection whose peer is not this user before
// the HTTP layer reads a byte from it.
type peerCheckedListener struct {
	net.Listener
}

func (l *peerCheckedListener) Accept() (net.Conn, error) {
	for {
		c, err := l.Listener.Accept()
		if err != nil {
			return nil, err
		}
		who, err := peerIdentity(c)
		if err != nil {
			log.Printf("refused peer %s: %v", who, err)
			c.Close()
			continue
		}
		return c, nil
	}
}

func protoGate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		got := r.Header.Get("X-Huginn-Core-Proto")
		if got != proto {
			w.Header().Set("Content-Type", "application/json")
			w.WriteHeader(http.StatusUpgradeRequired)
			json.NewEncoder(w).Encode(map[string]string{"error": fmt.Sprintf("core speaks proto %s, client sent %q; run `huginn update`", proto, got)})
			return
		}
		next.ServeHTTP(w, r)
	})
}

func sse(w http.ResponseWriter, r *http.Request) {
	n, _ := strconv.Atoi(r.URL.Query().Get("n"))
	if n <= 0 {
		n = 5
	}
	gap, _ := strconv.Atoi(r.URL.Query().Get("gapms"))
	if gap <= 0 {
		gap = 200
	}
	fl, ok := w.(http.Flusher)
	if !ok {
		http.Error(w, "no flusher", 500)
		return
	}
	w.Header().Set("Content-Type", "text/event-stream")
	w.Header().Set("Cache-Control", "no-cache")
	for i := 0; i < n; i++ {
		select {
		case <-r.Context().Done():
			log.Printf("sse: client went away after %d events", i)
			return
		default:
		}
		fmt.Fprintf(w, "event: state\ndata: {\"i\":%d,\"t\":%d}\n\n", i, time.Now().UnixMilli())
		fl.Flush()
		time.Sleep(time.Duration(gap) * time.Millisecond)
	}
	fmt.Fprint(w, "event: bye\ndata: {}\n\n")
	fl.Flush()
}

func upload(w http.ResponseWriter, r *http.Request) {
	h := sha256.New()
	n, err := io.Copy(h, r.Body)
	if err != nil {
		http.Error(w, err.Error(), 400)
		return
	}
	writeJSON(w, map[string]any{"bytes": n, "sha256": hex.EncodeToString(h.Sum(nil))})
}

func download(w http.ResponseWriter, r *http.Request) {
	mb, _ := strconv.Atoi(r.URL.Query().Get("mb"))
	if mb <= 0 {
		mb = 1
	}
	w.Header().Set("Content-Type", "application/octet-stream")
	buf := make([]byte, 1<<20)
	for i := range buf {
		buf[i] = byte(i * 31)
	}
	for i := 0; i < mb; i++ {
		if _, err := w.Write(buf); err != nil {
			return
		}
	}
}

func writeJSON(w http.ResponseWriter, v any) {
	w.Header().Set("Content-Type", "application/json")
	json.NewEncoder(w).Encode(v)
}
