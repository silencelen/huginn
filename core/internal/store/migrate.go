package store

import (
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"os"
	"sort"
	"strings"
	"time"
)

// The one-time import of what today's clients left on disk (§10 P1, Q7):
//
//	desktop      ${XDG_CONFIG_HOME:-~/.config}/huginn-desktop-kt/settings.json
//	             (same relative path under %USERPROFILE% on Windows — the JVM's
//	             user.home — with XDG_CONFIG_HOME honoured there too, exactly as
//	             DesktopSettings.defaultFile() reads it)
//	Node runner  ${HUGINN_DEVICE_DIR:-~/.config/huginn}/device.json + appd-token
//
// Everything here is pure except ReadMigration. Nothing here modifies a
// source file: Plan.Wipe says what the caller should remove, and when.

// MigrationSources are the files the import reads.
type MigrationSources struct {
	DesktopSettings string
	RunnerConf      string
	RunnerToken     string
}

// MigrationSourcesFor resolves the source paths. Pure.
func MigrationSourcesFor(goos string, env func(string) string, home string) MigrationSources {
	sep := "/"
	if goos == "windows" {
		sep = `\`
	}
	base := env("XDG_CONFIG_HOME")
	if strings.TrimSpace(base) == "" {
		base = home + sep + ".config"
	}
	dev := env("HUGINN_DEVICE_DIR")
	if dev == "" {
		dev = home + sep + ".config" + sep + "huginn"
	}
	return MigrationSources{
		DesktopSettings: base + sep + "huginn-desktop-kt" + sep + "settings.json",
		RunnerConf:      dev + sep + "device.json",
		RunnerToken:     dev + sep + "appd-token",
	}
}

// MigrationInput is the raw bytes of each source; nil means absent.
type MigrationInput struct {
	Sources     MigrationSources
	Desktop     []byte
	RunnerConf  []byte
	RunnerToken []byte
	// DesktopCorruptExists: settings.json.corrupt is present (it may hold a
	// plaintext token; it is reported for wiping, never read).
	DesktopCorruptExists bool
}

// ReadMigration reads the sources. An absent file is nil, not an error; any
// other read failure is returned (an unreadable source must not read as
// "nothing to import").
func ReadMigration(src MigrationSources) (MigrationInput, error) {
	in := MigrationInput{Sources: src}
	read := func(p string) ([]byte, error) {
		b, err := os.ReadFile(p)
		if errors.Is(err, fs.ErrNotExist) {
			return nil, nil
		}
		if err == nil && b == nil {
			b = []byte{}
		}
		return b, err
	}
	var err error
	if in.Desktop, err = read(src.DesktopSettings); err != nil {
		return in, err
	}
	if in.RunnerConf, err = read(src.RunnerConf); err != nil {
		return in, err
	}
	if in.RunnerToken, err = read(src.RunnerToken); err != nil {
		return in, err
	}
	if _, err := os.Stat(src.DesktopSettings + ".corrupt"); err == nil {
		in.DesktopCorruptExists = true
	}
	return in, nil
}

// Candidate sources.
const (
	SourceDesktop = "desktop"
	SourceRunner  = "runner"
)

// SourcePins are pins exactly as a source stored them. The two clients tag
// pin sets with DIFFERENT schemes (desktop: sha256("huginn-tls-pins:"+token)
// [:16] as "owner"; runner: HMAC(token, "huginn-tls-v1:pinset")[:16]), so they
// are not interchangeable with each other or with whatever the core's appd
// package keys by. Dropping them costs one re-proof per daemon.
type SourcePins struct {
	Scheme string // "desktop-owner" | "runner-pinset"
	Tag    string
	Pins   []string
}

// Candidate is one source's view of this machine.
type Candidate struct {
	Source string
	Path   string

	DeviceID string
	// Enabled: the source was actively running a device under DeviceID
	// (desktop deviceEnabled; runner: device.json carries an id).
	Enabled bool
	// UnenrolPending: the desktop turned its device off and appd never
	// confirmed the row gone — that id is owed a DELETE, not adoption.
	UnenrolPending bool

	Name           string
	Scope          string
	Root           string
	ActWhileLocked bool
	Claude         string
	LLM            string // runner only: the generate-engine binary
	LLMSlug        string
	URL            string
	Token          string
	Pins           *SourcePins
}

// WipeItem is something the caller should remove once the import is safe.
type WipeItem struct {
	Path string
	// Keys are JSON keys to remove from the file; nil means the whole file.
	Keys []string
	// When is the condition, as a sentence the caller can print.
	When string
}

// Plan is what PlanImport found.
type Plan struct {
	Candidates []Candidate
	Problems   []string
	Wipe       []WipeItem
}

// desktopStored mirrors DesktopSettings.Stored — only the fields the core
// imports or wipes.
type desktopStored struct {
	BaseURL              string `json:"baseUrl"`
	Token                string `json:"token"`
	TLSPins              string `json:"tlsPins"`
	DeviceEnabled        any    `json:"deviceEnabled"`
	DeviceID             string `json:"deviceId"`
	DeviceUnenrolPending any    `json:"deviceUnenrolPending"`
	DeviceScope          string `json:"deviceScope"`
	DeviceActWhileLocked any    `json:"deviceActWhileLocked"`
	DeviceRoot           string `json:"deviceRoot"`
	DeviceClaudePath     string `json:"deviceClaudePath"`
}

// runnerConf mirrors huginn-device's device.json (loadConf/saveConf).
type runnerConf struct {
	ID             string `json:"id"`
	Name           string `json:"name"`
	Scope          string `json:"scope"`
	Root           string `json:"root"`
	Claude         string `json:"claude"`
	URL            string `json:"url"`
	ActWhileLocked any    `json:"actWhileLocked"`
	LLM            string `json:"llm"`
	LLMSlug        string `json:"llmSlug"`
	TLS            *struct {
		Token string   `json:"token"`
		Pins  []string `json:"pins"`
	} `json:"tls"`
}

// Runner DEFAULTS (huginn-device): an absent key ran as these.
const (
	runnerDefaultScope  = "work"
	runnerDefaultClaude = "claude"
	runnerDefaultURL    = "http://127.0.0.1:8787"
	desktopDefaultScope = "look"
)

// DesktopDeviceKeys are the settings.json keys the desktop's runner used.
var DesktopDeviceKeys = []string{
	"deviceEnabled", "deviceId", "deviceUnenrolPending", "deviceScope",
	"deviceActWhileLocked", "deviceRoot", "deviceClaudePath",
}

// DesktopCredentialKeys are the settings.json keys holding the token and the
// pins proved under it.
var DesktopCredentialKeys = []string{"token", "tlsPins"}

// isTrue is the literal-true rule both clients use for actWhileLocked: only
// a JSON true widens anything; "true", 1 and absent are all false.
func isTrue(v any) bool { b, ok := v.(bool); return ok && b }

// PlanImport turns the raw sources into candidates and a wipe list. Pure.
func PlanImport(in MigrationInput) Plan {
	var p Plan
	if in.Desktop != nil {
		var d desktopStored
		if err := json.Unmarshal(in.Desktop, &d); err != nil {
			p.Problems = append(p.Problems, fmt.Sprintf("%s does not parse (%v); nothing imported from it", in.Sources.DesktopSettings, err))
		} else {
			c := Candidate{
				Source:         SourceDesktop,
				Path:           in.Sources.DesktopSettings,
				DeviceID:       strings.TrimSpace(d.DeviceID),
				Enabled:        isTrue(d.DeviceEnabled) && strings.TrimSpace(d.DeviceID) != "",
				UnenrolPending: isTrue(d.DeviceUnenrolPending),
				Scope:          d.DeviceScope,
				Root:           d.DeviceRoot,
				ActWhileLocked: isTrue(d.DeviceActWhileLocked),
				Claude:         d.DeviceClaudePath,
				URL:            strings.TrimSpace(d.BaseURL),
				Token:          strings.TrimSpace(d.Token),
			}
			if c.Scope == "" {
				c.Scope = desktopDefaultScope
			}
			if d.TLSPins != "" {
				var tp struct {
					Owner string   `json:"owner"`
					Pins  []string `json:"pins"`
				}
				if json.Unmarshal([]byte(d.TLSPins), &tp) == nil && len(tp.Pins) > 0 {
					c.Pins = &SourcePins{Scheme: "desktop-owner", Tag: tp.Owner, Pins: tp.Pins}
				}
			}
			p.Candidates = append(p.Candidates, c)
			p.Wipe = append(p.Wipe,
				WipeItem{Path: in.Sources.DesktopSettings, Keys: DesktopDeviceKeys,
					When: "once the core has enrolled under the adopted id, with the desktop app NOT running (it rewrites the whole file from memory on its next save)"},
				WipeItem{Path: in.Sources.DesktopSettings, Keys: DesktopCredentialKeys,
					When: "in P2, once the desktop reaches appd through the core — the P1 desktop still uses its own token"},
			)
		}
	}
	if in.DesktopCorruptExists {
		p.Wipe = append(p.Wipe, WipeItem{Path: in.Sources.DesktopSettings + ".corrupt",
			When: "with the desktop's credentials (P2) — it may hold an old plaintext token"})
	}
	if in.RunnerConf != nil || in.RunnerToken != nil {
		c := Candidate{Source: SourceRunner, Path: in.Sources.RunnerConf,
			Scope: runnerDefaultScope, Claude: runnerDefaultClaude, URL: runnerDefaultURL}
		if in.RunnerConf != nil {
			var r runnerConf
			if err := json.Unmarshal(in.RunnerConf, &r); err != nil {
				p.Problems = append(p.Problems, fmt.Sprintf("%s does not parse (%v); only its token is imported", in.Sources.RunnerConf, err))
			} else {
				c.DeviceID = strings.TrimSpace(r.ID)
				c.Enabled = c.DeviceID != ""
				c.Name = r.Name
				if r.Scope != "" {
					c.Scope = r.Scope
				}
				c.Root = r.Root
				if r.Claude != "" {
					c.Claude = r.Claude
				}
				if r.URL != "" {
					c.URL = strings.TrimSpace(r.URL)
				}
				c.ActWhileLocked = isTrue(r.ActWhileLocked)
				c.LLM = r.LLM
				c.LLMSlug = r.LLMSlug
				if r.TLS != nil && len(r.TLS.Pins) > 0 {
					c.Pins = &SourcePins{Scheme: "runner-pinset", Tag: r.TLS.Token, Pins: r.TLS.Pins}
				}
			}
		}
		if in.RunnerToken != nil {
			c.Token = strings.TrimSpace(string(in.RunnerToken))
			if c.Token == "" {
				p.Problems = append(p.Problems, in.Sources.RunnerToken+" is empty or whitespace")
			}
		}
		p.Candidates = append(p.Candidates, c)
		when := "once the Node runner's service is stopped and removed and the core holds the token (the runner reads both files on every beat)"
		if in.RunnerConf != nil {
			p.Wipe = append(p.Wipe, WipeItem{Path: in.Sources.RunnerConf, When: when})
		}
		if in.RunnerToken != nil {
			p.Wipe = append(p.Wipe, WipeItem{Path: in.Sources.RunnerToken, When: when})
		}
	}
	return p
}

// Choice is Plan.Choose's answer.
type Choice struct {
	// Device is the candidate whose device row is adopted, with Token, URL
	// and Claude filled from the other source where it had none. Zero (no
	// Source) when no source was running a device.
	Device Candidate
	// Dropped are other ids found: rows to DELETE through the API (Q7),
	// including an id the desktop still owed an unenrol.
	Dropped []string
	// Why is one printable sentence.
	Why string
	// Conflicts are differing values the fill did not paper over.
	Conflicts []string
}

// Choose picks the device id to adopt (Q7: the one appd saw most recently).
// lastSeen comes from appd (GET /v1/devices/<id>); an id appd does not know
// is zero time. Ties go to the runner, the source that was already the
// always-on one. Pure.
func (p Plan) Choose(lastSeen map[string]time.Time) Choice {
	var ch Choice
	var active []Candidate
	for _, c := range p.Candidates {
		if c.Enabled {
			active = append(active, c)
		}
	}
	rank := func(c Candidate) int {
		if c.Source == SourceRunner {
			return 1
		}
		return 0
	}
	sort.SliceStable(active, func(i, j int) bool {
		ti, tj := lastSeen[active[i].DeviceID], lastSeen[active[j].DeviceID]
		if !ti.Equal(tj) {
			return ti.After(tj)
		}
		return rank(active[i]) > rank(active[j])
	})
	switch len(active) {
	case 0:
		ch.Why = "no source was running a device; the core will enrol fresh"
	case 1:
		ch.Device = active[0]
		ch.Why = fmt.Sprintf("adopting %s from the %s (the only source running a device)", active[0].DeviceID, active[0].Source)
	default:
		ch.Device = active[0]
		if active[0].DeviceID == active[1].DeviceID {
			ch.Why = fmt.Sprintf("adopting %s (both sources ran the same id)", active[0].DeviceID)
		} else {
			ch.Why = fmt.Sprintf("adopting %s from the %s: appd saw it most recently", active[0].DeviceID, active[0].Source)
		}
	}
	// Every other id any source holds — active or owed an unenrol — is a row
	// to delete; the adopted one never is.
	seen := map[string]bool{ch.Device.DeviceID: true}
	for _, c := range p.Candidates {
		if c.DeviceID != "" && !seen[c.DeviceID] {
			ch.Dropped = append(ch.Dropped, c.DeviceID)
			seen[c.DeviceID] = true
		}
	}
	// Fill credentials/addresses from the other sources, in candidate order.
	base := ch.Device
	if base.Source == "" {
		// No device: still import token + url from whichever has one.
		for _, c := range p.Candidates {
			if base.Token == "" {
				base.Token = c.Token
			}
			if base.URL == "" {
				base.URL = c.URL
			}
		}
		if base.Token != "" || base.URL != "" {
			ch.Device = Candidate{Token: base.Token, URL: base.URL}
		}
		return ch
	}
	for _, c := range p.Candidates {
		if c.Source == base.Source {
			continue
		}
		if base.Token == "" {
			base.Token = c.Token
		} else if c.Token != "" && c.Token != base.Token {
			ch.Conflicts = append(ch.Conflicts, fmt.Sprintf("the %s holds a different appd token; keeping the %s's", c.Source, base.Source))
		}
		if base.URL == "" {
			base.URL = c.URL
		} else if c.URL != "" && c.URL != base.URL {
			ch.Conflicts = append(ch.Conflicts, fmt.Sprintf("the %s dials %s; keeping %s", c.Source, c.URL, base.URL))
		}
		if base.Claude == "" || base.Claude == runnerDefaultClaude {
			if c.Claude != "" && c.Claude != runnerDefaultClaude {
				base.Claude = c.Claude
			}
		}
	}
	ch.Device = base
	return ch
}

// Apply writes a choice into cfg and records the migration. Token is NOT
// written (it belongs in Secrets); pins are not either (see SourcePins).
func (ch Choice) Apply(cfg *Config, sources []string, at time.Time) {
	d := ch.Device
	if d.Source != "" {
		cfg.Device = Device{
			ID:             d.DeviceID,
			Name:           d.Name,
			Scope:          d.Scope,
			Root:           d.Root,
			ActWhileLocked: d.ActWhileLocked,
			Claude:         d.Claude,
			LLMSlug:        d.LLMSlug,
		}
	}
	if d.URL != "" {
		cfg.Appd.URL = d.URL
	}
	cfg.MigratedFrom = &Migration{
		At:           at.UTC(),
		Sources:      sources,
		DeviceID:     d.DeviceID,
		DeviceIDFrom: d.Source,
		Dropped:      ch.Dropped,
	}
}
