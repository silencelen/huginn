// Package ipc is the core's local API: HTTP/1.1 over an AF_UNIX socket that
// only this user can open (docs/CORE-ARCHITECTURE.md §5).
//
// Three layers keep other users out, because each one alone has a way to fail:
// the runtime directory is 0700 (Windows: a user-only ACL), the socket file is
// 0600, and every accepted connection's PEER is checked against our own uid/SID
// before the HTTP layer reads a byte (a mode can be loosened or a socket
// hard-linked; the kernel's view of the peer cannot be argued with).
//
// There is deliberately no TCP listener anywhere: loopback has no identity, which
// is why the desktop's old single-instance socket needed a shared secret.
package ipc

import (
	"bytes"
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"log"
	"net"
	"net/http"
	"os"
	"path/filepath"
	"runtime"
	"time"

	"github.com/silencelen/huginn/core/internal/version"
)

// ProtoHeader carries version.Proto on every request.
const ProtoHeader = "X-Huginn-Core-Proto"

type peerKey struct{}

// Peer returns the identity the peer check recorded for this request
// ("uid=1000 pid=…" / "pid=… sid=…").
func Peer(r *http.Request) string {
	s, _ := r.Context().Value(peerKey{}).(string)
	return s
}

// Server is the core's IPC endpoint.
type Server struct {
	Path string
	Mux  *http.ServeMux
	Log  *log.Logger
	srv  *http.Server
	ln   net.Listener
}

// New prepares a server for the socket at path; register handlers on Mux, then Serve.
func New(path string, logger *log.Logger) *Server {
	if logger == nil {
		logger = log.New(os.Stderr, "ipc: ", log.LstdFlags)
	}
	return &Server{Path: path, Mux: http.NewServeMux(), Log: logger}
}

// ErrInUse means another live core already answers on the socket: a second
// core must not steal it (one core per user is the single-instance rule).
var ErrInUse = errors.New("another huginn core is already serving this socket")

// Listen creates the runtime directory and the socket. A stale socket file (no
// one answering) is removed; a live one is ErrInUse.
func (s *Server) Listen() error {
	dir := filepath.Dir(s.Path)
	if err := os.MkdirAll(dir, 0o700); err != nil {
		return err
	}
	if runtime.GOOS != "windows" {
		// MkdirAll does not tighten a directory that already existed.
		if err := os.Chmod(dir, 0o700); err != nil {
			return err
		}
	}
	if _, err := os.Stat(s.Path); err == nil {
		if c, err := net.DialTimeout("unix", s.Path, time.Second); err == nil {
			c.Close()
			return ErrInUse
		}
		if err := os.Remove(s.Path); err != nil {
			return fmt.Errorf("remove stale socket: %w", err)
		}
	}
	ln, err := net.Listen("unix", s.Path)
	if err != nil {
		return err
	}
	if runtime.GOOS != "windows" {
		if err := os.Chmod(s.Path, 0o600); err != nil {
			ln.Close()
			return err
		}
	}
	s.ln = &peerCheckedListener{Listener: ln, log: s.Log}
	return nil
}

// Serve blocks until Shutdown. Call Listen first.
func (s *Server) Serve() error {
	if s.ln == nil {
		return errors.New("ipc: Serve before Listen")
	}
	s.srv = &http.Server{
		Handler:           protoGate(s.Mux),
		ReadHeaderTimeout: 10 * time.Second,
		ConnContext: func(ctx context.Context, c net.Conn) context.Context {
			if pc, ok := c.(*checkedConn); ok {
				return context.WithValue(ctx, peerKey{}, pc.who)
			}
			return ctx
		},
	}
	err := s.srv.Serve(s.ln)
	if errors.Is(err, http.ErrServerClosed) {
		return nil
	}
	return err
}

// Shutdown stops accepting, lets in-flight requests finish until ctx ends, and
// removes the socket file.
func (s *Server) Shutdown(ctx context.Context) error {
	var err error
	if s.srv != nil {
		err = s.srv.Shutdown(ctx)
	}
	os.Remove(s.Path)
	return err
}

type checkedConn struct {
	net.Conn
	who string
}

// peerCheckedListener drops any connection whose peer is not this user before
// the HTTP layer reads a byte from it.
type peerCheckedListener struct {
	net.Listener
	log *log.Logger
}

func (l *peerCheckedListener) Accept() (net.Conn, error) {
	for {
		c, err := l.Listener.Accept()
		if err != nil {
			return nil, err
		}
		who, err := peerIdentity(c)
		if err != nil {
			l.log.Printf("refused peer %s: %v", who, err)
			c.Close()
			continue
		}
		return &checkedConn{Conn: c, who: who}, nil
	}
}

// protoGate answers a missing or different protocol version with 426 and the
// fix, so a mismatched GUI/core pair fails loudly instead of misbehaving.
func protoGate(next http.Handler) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if got := r.Header.Get(ProtoHeader); got != version.Proto {
			WriteJSON(w, http.StatusUpgradeRequired, map[string]string{
				"error": fmt.Sprintf("this huginn core speaks protocol %s, the client sent %q; run `huginn update`", version.Proto, got),
				"core":  version.Version,
			})
			return
		}
		next.ServeHTTP(w, r)
	})
}

// WriteJSON writes v with the given status.
func WriteJSON(w http.ResponseWriter, status int, v any) {
	w.Header().Set("Content-Type", "application/json")
	w.WriteHeader(status)
	json.NewEncoder(w).Encode(v)
}

// Client returns an *http.Client that dials the socket at path, for the CLI
// front and tests. Requests must still carry ProtoHeader (Do adds it).
func Client(path string) *http.Client {
	return &http.Client{Transport: &http.Transport{
		DialContext: func(ctx context.Context, _, _ string) (net.Conn, error) {
			var d net.Dialer
			return d.DialContext(ctx, "unix", path)
		},
	}}
}

// Do sends one request to the core with the protocol header set. path is
// "/core/…" or "/appd/…".
func Do(ctx context.Context, c *http.Client, method, path string, body any) (*http.Response, error) {
	var rd io.Reader
	if body != nil {
		b, err := json.Marshal(body)
		if err != nil {
			return nil, err
		}
		rd = bytes.NewReader(b)
	}
	req, err := http.NewRequestWithContext(ctx, method, "http://huginn-core"+path, rd)
	if err != nil {
		return nil, err
	}
	if body != nil {
		req.Header.Set("Content-Type", "application/json")
	}
	req.Header.Set(ProtoHeader, version.Proto)
	return c.Do(req)
}
