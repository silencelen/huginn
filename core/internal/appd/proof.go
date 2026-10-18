// Package appd is the core's client for huginn-appd: TLS pinned by a
// token-bound proof (docs/dev/protocol-tls.md is the contract), token resolution, and the
// small typed API the device runner speaks.
//
// ⚠ THE TOKEN IS ROOT ON THE HOST, AND THIS CLIENT SENDS IT ON EVERY REQUEST.
// appd (3.11+) serves https on its usual port with a certificate it minted
// itself. There is no CA to check a self-signed certificate against, so the
// client checks the DAEMON instead, with the token it already holds and never
// sending it:
//
//	probe: TLS, any certificate, F = sha256(leaf DER); GET /v1/challenge?nonce=N
//	       with NO Authorization; accept iff tls.proof == HMAC(token, v1:N:F)
//	pin:   F joins the pin set; every request that carries the token goes over
//	       a connection whose leaf is IN that set, or it does not go at all.
//
// None of this is optional: there is no setting that re-enables cleartext to a
// remote host, and a failed proof never falls back to anything. A daemon on
// this machine is proved the same way (loopback has no identity: anything can
// hold the port while appd restarts); only HUGINN_APPD_PLAINTEXT=local, for a
// daemon here that has no TLS, keeps a loopback http:// plain (PlaintextEnv).
// The reference implementations are client/huginn-device (Node) and
// mobile/core DaemonTls.kt.
package appd

import (
	"crypto/hmac"
	"crypto/sha256"
	"encoding/hex"
	"errors"
	"fmt"
	"strings"
)

const (
	// ProofPrefix is the domain separator of the bound proof. ':' never occurs
	// in a valid nonce, so one proof can never stand in for another.
	ProofPrefix = "huginn-tls-v1:"
	// ProofVersion is the only `tls.v` this client understands.
	ProofVersion = 1
	// MaxPins is how many proved fingerprints are kept per token, newest first.
	MaxPins = 4

	// InterceptedText and OldDaemonText are the sentences TLS.md tells every
	// client to say, word for word, so a user sees the same thing everywhere.
	InterceptedText = "this address could not prove it is your huginn daemon; someone may be intercepting the connection"
	OldDaemonText   = "the daemon is older than 3.11 and has no TLS; update it"

	// tokenHint is display-only (Kotlin's TOKEN_HINT): the far end reported the
	// fingerprint we saw but its proof did not match, which is what a WRONG
	// TOKEN looks like. It never changes the verdict.
	tokenHint = " (if the token was changed recently, check it first)"
)

var (
	// ErrIntercepted: an address failed the proof, or the certificate changed
	// again between a successful re-proof and the retry. Never retried as-is
	// and never downgraded.
	ErrIntercepted = errors.New(InterceptedText)
	// ErrOldDaemon: the port did not answer TLS, or answered the challenge with
	// no `tls` object. Refused, never downgraded to plain HTTP (rule 8).
	ErrOldDaemon = errors.New(OldDaemonText)
	// ErrProofVersion: a `tls` object with a version this client does not know.
	// Not proved; the fix is to update the client.
	ErrProofVersion = errors.New("the daemon answered with a TLS proof version this client does not know; update huginn")
)

// BoundProof is the channel-bound proof, byte for byte what appd's
// lib/tls.js computes: HMAC-SHA256(token, "huginn-tls-v1:<nonce>:<fp>"), hex.
func BoundProof(token, nonce, fp string) string {
	m := hmac.New(sha256.New, []byte(token))
	m.Write([]byte(ProofPrefix + nonce + ":" + fp))
	return hex.EncodeToString(m.Sum(nil))
}

// ProofMatches compares a proof from the wire with the expected one in
// constant time. Anything that is not 64 hex characters is false (and never
// panics); hex case does not make a different proof.
func ProofMatches(got, want string) bool {
	if len(got) != 64 || len(want) != 64 {
		return false
	}
	a, err := hex.DecodeString(got)
	if err != nil {
		return false
	}
	b, err := hex.DecodeString(want)
	if err != nil {
		return false
	}
	// hmac.Equal is constant time for equal lengths, which both are by now.
	return hmac.Equal(a, b)
}

// PinTag names which token a pin set belongs to: HMAC(token,
// "huginn-tls-v1:pinset")[:16]. Keyed, so a pin file people edit and paste
// never holds anything that helps recover the token itself.
//
// ⚠ PINS BELONG TO THE TOKEN, NOT THE ADDRESS (rule 7). One daemon has one
// certificate however it is reached; a DIFFERENT token is a different daemon
// (or a re-pair), and a pin proved by the old token proves nothing about it.
func PinTag(token string) string {
	m := hmac.New(sha256.New, []byte(token))
	m.Write([]byte(ProofPrefix + "pinset"))
	return hex.EncodeToString(m.Sum(nil))[:16]
}

// Fingerprint is the lowercase hex sha256 of a certificate's DER.
func Fingerprint(der []byte) string {
	s := sha256.Sum256(der)
	return hex.EncodeToString(s[:])
}

// isFingerprint: 64 lowercase hex characters, the only shape a pin may have.
func isFingerprint(s string) bool {
	if len(s) != 64 {
		return false
	}
	for _, c := range s {
		if !(c >= '0' && c <= '9' || c >= 'a' && c <= 'f') {
			return false
		}
	}
	return true
}

// PinMismatchError is the pinned channel's refusal: the leaf this handshake
// saw is not in the pin set. It aborts the handshake, so it always arrives
// before a byte of the request has been written. Do() never returns it — it
// either re-proves past it or turns it into ErrIntercepted.
type PinMismatchError struct{ FP string }

func (e *PinMismatchError) Error() string {
	fp := e.FP
	if fp == "" {
		fp = "(none)"
	}
	return fmt.Sprintf("certificate %s is not pinned", fp)
}

// interceptedAt wraps ErrIntercepted with the origin, as Node prints it.
func interceptedAt(origin, extra string) error {
	return fmt.Errorf("%s: %w%s", origin, ErrIntercepted, extra)
}

func lowerHex(s string) string { return strings.ToLower(strings.TrimSpace(s)) }
