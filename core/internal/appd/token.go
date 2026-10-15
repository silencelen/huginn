package appd

import (
	"errors"
	"fmt"
	"io"
	"io/fs"
	"os"
	"strings"
)

// TokenEnv is read before any TokenSource, as client/huginn-device does.
const TokenEnv = "HUGINN_APPD_TOKEN"

// TokenReason says WHY there is no token. Each has its own advice; folding
// them into one ("put one in <path>") once sent people to fix a file that was
// sitting right there, every 15 seconds, forever.
type TokenReason int

const (
	TokenNoSource    TokenReason = iota + 1 // nothing configured to read one from
	TokenMissing                            // the file is not there (or a dangling link)
	TokenUnreadable                         // permissions
	TokenIsDirectory                        // the path is a directory
	TokenEmpty                              // the file has zero bytes
	TokenWhitespace                         // the file holds only whitespace
	TokenReadError                          // any other read failure
)

// NoTokenError is "there is no token to send", with the reason. Classify
// files it as KindAuth: the runner stops delivering, it does not retry faster.
type NoTokenError struct {
	Reason TokenReason
	Why    string
}

func (e *NoTokenError) Error() string { return "no appd token — " + e.Why }

// TokenSource yields the appd token. It returns a *NoTokenError (or any
// error) when it has none; a returned token is used as-is after trimming.
// The store's keyring/0600-file implementation lives elsewhere; FileTokenSource
// and StaticToken are the two this package needs.
type TokenSource interface {
	Token() (string, error)
}

// StaticToken is a token already in hand (tests, a GUI handing one over).
type StaticToken string

func (s StaticToken) Token() (string, error) {
	t := strings.TrimSpace(string(s))
	if t == "" {
		return "", &NoTokenError{Reason: TokenEmpty, Why: "the token is empty"}
	}
	return t, nil
}

// FileTokenSource reads the token from a file on every call, so a token
// changed on disk is picked up by the next request.
type FileTokenSource struct{ Path string }

// maxTokenFile bounds the read: a token is 64 characters, and a path pointed
// at something huge by mistake must not be slurped every request.
const maxTokenFile = 64 * 1024

func (f FileTokenSource) Token() (string, error) {
	p := f.Path
	if p == "" {
		return "", &NoTokenError{Reason: TokenNoSource, Why: "no token file configured"}
	}
	// Stat first: reading a directory fails differently on every OS, and
	// "is a directory" is advice a person can act on.
	if st, err := os.Stat(p); err == nil && st.IsDir() {
		return "", &NoTokenError{Reason: TokenIsDirectory, Why: p + " is a directory, not a file"}
	}
	fh, err := os.Open(p)
	if err != nil {
		return "", tokenOpenError(p, err)
	}
	defer fh.Close()
	raw, err := io.ReadAll(io.LimitReader(fh, maxTokenFile))
	if err != nil {
		return "", tokenOpenError(p, err)
	}
	t := strings.TrimSpace(string(raw))
	if t == "" {
		if len(raw) == 0 {
			return "", &NoTokenError{Reason: TokenEmpty, Why: p + " is empty"}
		}
		// ⚠ The nastiest one: a botched `ssh huginn 'cat …/token'` captures a
		// lone newline, which passes `[ -s ]` guards, and then every request
		// says "no token" about a file that is plainly there.
		plural := "s"
		if len(raw) == 1 {
			plural = ""
		}
		return "", &NoTokenError{Reason: TokenWhitespace, Why: fmt.Sprintf(
			"%s holds only whitespace (%d byte%s) — a capture that caught a newline and nothing else", p, len(raw), plural)}
	}
	return t, nil
}

func tokenOpenError(p string, err error) error {
	switch {
	case errors.Is(err, fs.ErrNotExist):
		return &NoTokenError{Reason: TokenMissing, Why: "no token file at " + p}
	case errors.Is(err, fs.ErrPermission):
		return &NoTokenError{Reason: TokenUnreadable, Why: "cannot read " + p + " (permissions)"}
	default:
		return &NoTokenError{Reason: TokenReadError, Why: fmt.Sprintf("cannot read %s: %v", p, err)}
	}
}

// ResolveToken is the token to send: $HUGINN_APPD_TOKEN (trimmed) first, then
// src. Always a *NoTokenError when there is none, never a bare "".
func ResolveToken(src TokenSource) (string, error) {
	if env := strings.TrimSpace(os.Getenv(TokenEnv)); env != "" {
		return env, nil
	}
	if src == nil {
		return "", &NoTokenError{Reason: TokenNoSource, Why: "no token source configured and " + TokenEnv + " is unset"}
	}
	t, err := src.Token()
	if err != nil {
		var nt *NoTokenError
		if errors.As(err, &nt) {
			return "", err
		}
		return "", &NoTokenError{Reason: TokenReadError, Why: err.Error()}
	}
	if t = strings.TrimSpace(t); t == "" {
		return "", &NoTokenError{Reason: TokenEmpty, Why: "the token source returned an empty token"}
	}
	return t, nil
}
