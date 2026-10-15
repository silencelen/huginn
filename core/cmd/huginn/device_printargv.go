package main

import (
	"bytes"
	"encoding/json"
	"fmt"
	"io"
	"os"
	"unicode/utf8"

	"github.com/silencelen/huginn/core/internal/policy"
)

// device dispatches `huginn device …`.
func device(args []string) int {
	if len(args) == 0 {
		return deviceStatus()
	}
	switch args[0] {
	case "print-argv":
		return printArgv(args[1:], os.Stdout, os.Stderr)
	case "on", "enrol", "enroll":
		return deviceOn(args[1:])
	case "off":
		return deviceOff()
	case "status":
		return deviceStatus()
	}
	usage()
	return 2
}

// printArgv is `huginn device print-argv <scope> <locked> <mode> [extraJson]`:
// this runner driven over the shared case matrix from the outside, so
// server/appd/test/device-policy-cases.test.js can hold it to the same table as
// the Node and Kotlin runners (HUGINN_DEVICE_BIN=<this binary>).
//
// The stdout is byte-for-byte what `node client/huginn-device print-argv` writes
// — same keys, same order, JSON.stringify's escaping — so the two can be diffed
// rather than merely compared. extraJson is {model, effort, resumeSessionId,
// persona, hasEngine}, merged into the work item so the generate-mode exclusions
// can be driven too.
func printArgv(args []string, stdout, stderr io.Writer) int {
	if len(args) < 3 {
		fmt.Fprintln(stderr, "usage: huginn device print-argv <scope> <locked> <mode> [extraJson]")
		return 2
	}
	scope, mode := args[0], args[2]
	locked := args[1] == "true" || args[1] == "1"
	var extra printArgvExtra
	if len(args) > 3 && args[3] != "" {
		if err := extra.parse(args[3]); err != nil {
			fmt.Fprintf(stderr, "huginn device print-argv: extraJson: %v\n", err)
			return 2
		}
	}
	w := policy.Work{Mode: mode, Model: extra.model, Effort: extra.effort, ResumeSessionID: extra.resumeSessionID}
	cwd := "home"
	if policy.CwdFor(scope, locked, "/root-dir", "/home-dir") == "/root-dir" {
		cwd = "root"
	}
	var b bytes.Buffer
	b.WriteString(`{"effectiveScope":`)
	jsQuote(&b, policy.Effective(scope, locked))
	b.WriteString(`,"refusal":`)
	jsQuoteOrNull(&b, policy.Refusal(scope, locked, mode))
	b.WriteString(`,"engineRefusal":`)
	jsQuoteOrNull(&b, policy.EngineRefusal(mode, extra.hasEngine))
	b.WriteString(`,"argv":[`)
	for i, a := range policy.ArgvFor(w, scope, locked, extra.persona) {
		if i > 0 {
			b.WriteByte(',')
		}
		jsQuote(&b, a)
	}
	b.WriteString(`],"cwd":`)
	jsQuote(&b, cwd)
	b.WriteByte('}')
	if _, err := stdout.Write(b.Bytes()); err != nil {
		return 1
	}
	return 0
}

type printArgvExtra struct {
	model, effort, resumeSessionID, persona string
	hasEngine                               bool
}

// parse reads the keys by their exact names. encoding/json's struct decoding
// matches keys case-insensitively, which would let {"MODEL": …} ride where the
// Node runner ignores it. A null is absent; a non-string where a string belongs
// is an error rather than the Node runner's pushing of the raw value into argv.
func (e *printArgvExtra) parse(raw string) error {
	var m map[string]json.RawMessage
	if err := json.Unmarshal([]byte(raw), &m); err != nil {
		return err
	}
	for key, dst := range map[string]*string{
		"model": &e.model, "effort": &e.effort, "resumeSessionId": &e.resumeSessionID, "persona": &e.persona,
	} {
		v, ok := m[key]
		if !ok {
			continue
		}
		var s *string
		if err := json.Unmarshal(v, &s); err != nil {
			return fmt.Errorf("%s: %w", key, err)
		}
		if s != nil {
			*dst = *s
		}
	}
	// hasEngine === true in the Node runner: only a literal true.
	var he bool
	if v, ok := m["hasEngine"]; ok && json.Unmarshal(v, &he) != nil {
		he = false
	}
	e.hasEngine = he
	return nil
}

func jsQuoteOrNull(b *bytes.Buffer, s string) {
	if s == "" {
		b.WriteString("null")
		return
	}
	jsQuote(b, s)
}

// jsQuote writes s the way JSON.stringify does, which is not encoding/json's
// way: that escapes <, >, & and U+2028/U+2029, and JSON.stringify writes them
// raw. Invalid UTF-8 (only reachable from raw argv) becomes U+FFFD per byte.
func jsQuote(b *bytes.Buffer, s string) {
	const hex = "0123456789abcdef"
	b.WriteByte('"')
	for i := 0; i < len(s); {
		r, size := utf8.DecodeRuneInString(s[i:])
		i += size
		switch r {
		case '"':
			b.WriteString(`\"`)
		case '\\':
			b.WriteString(`\\`)
		case '\b':
			b.WriteString(`\b`)
		case '\f':
			b.WriteString(`\f`)
		case '\n':
			b.WriteString(`\n`)
		case '\r':
			b.WriteString(`\r`)
		case '\t':
			b.WriteString(`\t`)
		default:
			if r < 0x20 {
				b.WriteString(`\u00`)
				b.WriteByte(hex[r>>4])
				b.WriteByte(hex[r&0xf])
			} else {
				b.WriteRune(r)
			}
		}
	}
	b.WriteByte('"')
}
