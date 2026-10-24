package doctor

import (
	"bytes"
	"encoding/json"
	"errors"
	"os"
	"path/filepath"
	"strings"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/skills"
	"github.com/silencelen/huginn/core/internal/store"
	"github.com/silencelen/huginn/core/internal/ui"
)

// A fake machine: a scratch home with the files a healthy Linux client has,
// commands answered from a table, a core that answers from a table, and a
// network of two addresses. Every check reads only these.
type machine struct {
	t     *testing.T
	p     Probe
	cmds  map[string]service.Result // "name arg arg" -> result
	core  map[string]any            // path -> answer; an error value refuses
	tls   map[string]string         // hostport -> fingerprint, "" = refused
	tcp   map[string]bool           // hostport -> answers
	env   map[string]string
	paths map[string]bool // LookPath answers
}

const (
	pin      = "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"
	otherPin = "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb"
	lan      = "https://192.0.2.10:8787"
	mesh     = "https://[2001:db8::10]:8787"
)

func healthy(t *testing.T) *machine {
	t.Helper()
	home := t.TempDir()
	m := &machine{t: t, cmds: map[string]service.Result{}, core: map[string]any{}, tls: map[string]string{}, tcp: map[string]bool{},
		env: map[string]string{"DISPLAY": ":0"}, paths: map[string]bool{"ssh": true, "ssh-keygen": true, "claude": true, "node": true}}
	root := filepath.Join(home, ".local", "share", "huginn")
	layout := install.Layout{GOOS: "linux", Root: root, Link: filepath.Join(home, ".local", "bin", "huginn")}
	// The installed core: versions/2.7.0, current -> it, the link to current.
	must(t, os.MkdirAll(filepath.Join(root, "versions", "2.7.0"), 0o755))
	must(t, os.WriteFile(filepath.Join(root, "versions", "2.7.0", "huginn"), []byte("#!/bin/sh\n"), 0o755))
	must(t, os.Symlink(filepath.Join("versions", "2.7.0"), filepath.Join(root, "current")))
	must(t, os.MkdirAll(filepath.Dir(layout.Link), 0o755))
	must(t, os.Symlink(filepath.Join(root, "current", "huginn"), layout.Link))
	// The CLI the core carries, at its version.
	must(t, os.MkdirAll(filepath.Join(home, ".huginn"), 0o755))
	must(t, os.WriteFile(filepath.Join(home, ".huginn", "huginn.sh"), []byte("# Version: 2.7.0\nHUGINN_VERSION='2.7.0'\n"), 0o644))
	// The unit and the tray autostart.
	cfgHome := filepath.Join(home, ".config")
	lo := service.LinuxOptions{Binary: layout.Link, Home: home, User: "alex", ConfigHome: cfgHome, Graphical: true}
	must(t, os.MkdirAll(filepath.Dir(lo.UnitPath()), 0o755))
	must(t, os.WriteFile(lo.UnitPath(), []byte("[Unit]\n"), 0o644))
	must(t, os.MkdirAll(filepath.Dir(lo.TrayPath()), 0o755))
	must(t, os.WriteFile(lo.TrayPath(), []byte("[Desktop Entry]\n"), 0o644))
	// core.json: paired on the LAN address, a mesh route, one proved pin.
	paths := store.Paths{Config: filepath.Join(cfgHome, "huginn"), State: filepath.Join(home, ".local", "state", "huginn"), Runtime: filepath.Join(home, "run", "huginn")}
	must(t, os.MkdirAll(paths.Config, 0o700))
	cfg := store.Config{V: 1, Appd: store.Appd{URL: lan, Routes: []store.Route{{URL: mesh, Kind: "mesh"}}}, Pins: map[string][]string{"tag": {pin}}}
	b, _ := store.EncodeConfig(cfg)
	must(t, os.WriteFile(paths.CoreFile(), b, 0o600))
	// ssh: the stanza and its key.
	must(t, os.MkdirAll(filepath.Join(home, ".ssh"), 0o700))
	must(t, os.WriteFile(filepath.Join(home, ".ssh", "id_ed25519"), []byte("key"), 0o600))
	must(t, os.WriteFile(filepath.Join(home, ".ssh", "config"), []byte("Host other\n  HostName 192.0.2.99\n\nHost huginn\n  HostName 192.0.2.10\n  User root\n  IdentityFile \""+filepath.Join(home, ".ssh", "id_ed25519")+"\"\n"), 0o600))
	// Claude Code and the skills, linked.
	skEnv := skills.Env{GOOS: "linux", Home: home, Root: root}
	must(t, os.MkdirAll(skEnv.ClaudeDir(), 0o755))
	if _, err := skills.Lay(skEnv); err != nil {
		t.Fatal(err)
	}
	if r := skills.Install(skEnv); !r.OK() {
		t.Fatalf("skills: %+v", r)
	}

	m.cmds["systemctl --user is-enabled huginn-core.service"] = service.Result{Stdout: "enabled\n"}
	m.cmds["systemctl --user is-active huginn-core.service"] = service.Result{Stdout: "active\n"}
	m.cmds["loginctl show-user alex --property=Linger"] = service.Result{Stdout: "Linger=yes\n"}
	m.cmds["ssh -G huginn"] = service.Result{Stdout: "user root\nhostname 192.0.2.10\nport 22\n"}
	m.cmds["ssh-keygen -F 192.0.2.10"] = service.Result{Stdout: "# Host 192.0.2.10 found: line 3\n192.0.2.10 ssh-ed25519 AAAA\n"}
	m.cmds["/usr/bin/claude --version"] = service.Result{Stdout: "2.1.306 (Claude Code)\n"} // the suffix is dropped
	m.core["/core/status"] = map[string]any{"version": "2.7.0", "pid": 4242, "paired": true, "url": lan, "pins": []string{pin}, "tray": true,
		"device": map[string]any{"status": map[string]any{"enabled": true, "deviceId": "dev-1", "enrolled": true, "busy": false, "locked": false, "lockKnown": true, "note": ""},
			"settings": map[string]any{"enabled": true, "name": "laptop", "scope": "work", "root": "/home/alex/work"}}}
	m.core["/core/routes"] = map[string]any{"autoSwitch": true, "routes": []map[string]any{{"url": lan, "active": true}, {"url": mesh, "active": false}}}
	m.core["/appd/v1/status"] = map[string]any{"host": "server.example.net", "appdVersion": "3.14.0", "claude": "2.1.306", "uptimeSec": 180000, "sessions": 3}
	m.tls["192.0.2.10:8787"] = pin
	m.tls["[2001:db8::10]:8787"] = pin
	m.tcp["192.0.2.10:22"] = true

	m.p = Probe{
		GOOS: "linux", Home: home, Version: "2.7.0", Executable: filepath.Join(root, "versions", "2.7.0", "huginn"),
		Env:    func(k string) string { return m.env[k] },
		Layout: layout, Paths: paths, Linux: &lo,
		Run:      service.RunnerFunc(m.run),
		LookPath: m.lookPath,
		Core:     m.coreCall,
		DialTLS: func(hp string, _ time.Duration) (string, time.Duration, error) {
			fp, ok := m.tls[hp]
			if !ok || fp == "" {
				return "", 0, errors.New("dial tcp " + hp + ": connection refused")
			}
			return fp, 12 * time.Millisecond, nil
		},
		DialTCP: func(hp string, _ time.Duration) (time.Duration, error) {
			if !m.tcp[hp] {
				return 0, errors.New("dial tcp " + hp + ": i/o timeout")
			}
			return 3 * time.Millisecond, nil
		},
		ClientVersion: func(string) string { return "2.7.0" },
		Skills:        func() []skills.Entry { return skills.Status(skEnv) },
		SocketPath:    filepath.Join(paths.Runtime, "core.sock"),
		LocalDir:      filepath.Join(cfgHome, "huginn-local"),
		Manager:       filepath.Join(home, ".huginn", "huginn-local"),
	}
	lo.Runner = service.RunnerFunc(m.run)
	m.p.Linux = &lo
	return m
}

func must(t *testing.T, err error) {
	t.Helper()
	if err != nil {
		t.Fatal(err)
	}
}

func (m *machine) run(name string, args ...string) service.Result {
	key := strings.TrimSpace(name + " " + strings.Join(args, " "))
	if r, ok := m.cmds[key]; ok {
		return r
	}
	m.t.Logf("unexpected command: %s", key)
	return service.Result{Err: errors.New("not in the fake: " + key)}
}

func (m *machine) lookPath(name string) (string, error) {
	if m.paths[name] {
		return "/usr/bin/" + name, nil
	}
	return "", errors.New("not found")
}

func (m *machine) coreCall(method, path string, out any) error {
	v, ok := m.core[path]
	if !ok {
		return errors.New("dial unix: connect: no such file or directory")
	}
	if err, isErr := v.(error); isErr {
		return err
	}
	b, _ := json.Marshal(v)
	return json.Unmarshal(b, out)
}

func byID(checks []Check) map[string]Check {
	out := map[string]Check{}
	for _, c := range checks {
		out[c.ID] = c
	}
	return out
}

func TestHealthyMachine(t *testing.T) {
	m := healthy(t)
	checks := Run(m.p)
	if Failed(checks) {
		t.Fatalf("a healthy machine failed:\n%s", JSON(checks))
	}
	ids := []string{}
	for _, c := range checks {
		ids = append(ids, c.ID)
		if c.State != OK && c.ID != "local-ai" {
			t.Errorf("%s: %s %s", c.ID, c.State, c.Detail)
		}
		if c.Comp == "" {
			t.Errorf("%s has no component", c.ID)
		}
	}
	// The local AI tier is not installed here, so its row is a skip, not ok.
	by := byID(checks)
	if by["local-ai"].State != Skip {
		t.Errorf("local-ai: %+v", by["local-ai"])
	}
	want := []string{"core.binary", "core.cli", "core.service", "core.socket", "core.tray", "core.pairing",
		"route:" + lan, "route:" + mesh, "server.status", "device", "local-ai",
		"ssh.config", "ssh.resolve", "ssh.port", "ssh.hostkey", "claude.code", "claude.skills"}
	if strings.Join(ids, " ") != strings.Join(want, " ") {
		t.Fatalf("ids:\n got %v\nwant %v", ids, want)
	}
	for id, s := range map[string]string{
		"core.binary":   "huginn 2.7.0 at ~/.local/bin/huginn",
		"core.service":  "linger on",
		"core.socket":   "pid 4242",
		"core.pairing":  lan,
		"route:" + lan:  "the active one; its certificate is the proved one",
		"server.status": "huginn-appd 3.14.0 on server.example.net, Claude Code 2.1.306, 3 sessions, up 2 d",
		"device":        "enrolled as laptop, scope work, idle, unlocked",
		"ssh.config":    "'Host huginn' -> 192.0.2.10, as root",
		"ssh.resolve":   "192.0.2.10:22",
		"ssh.port":      "no login was attempted",
		"ssh.hostkey":   "in known_hosts",
		"claude.code":   "Claude Code 2.1.306 at /usr/bin/claude",
		"claude.skills": "huginn, huginn-doctor in ~/.claude/skills",
	} {
		if !strings.Contains(by[id].Detail, s) {
			t.Errorf("%s: %q lacks %q", id, by[id].Detail, s)
		}
	}
}

// The JSON a script reads: the four fields, nothing else, and fix only where
// there is one.
func TestJSONShape(t *testing.T) {
	m := healthy(t)
	delete(m.core, "/core/status")
	var rows []map[string]any
	if err := json.Unmarshal(JSON(Run(m.p)), &rows); err != nil {
		t.Fatal(err)
	}
	for _, row := range rows {
		for k := range row {
			if k != "id" && k != "state" && k != "detail" && k != "fix" {
				t.Fatalf("unexpected field %q in %v", k, row)
			}
		}
		if row["id"] == "core.socket" {
			if row["state"] != "fail" || !strings.Contains(row["fix"].(string), "huginn core start") {
				t.Fatalf("core.socket: %v", row)
			}
		}
		if row["state"] == "ok" {
			if _, has := row["fix"]; has {
				t.Fatalf("an ok row carries a fix: %v", row)
			}
		}
	}
	if string(JSON(nil)) != "[]\n" {
		t.Fatalf("empty: %q", JSON(nil))
	}
}

// The core down: the socket fails with the start command, and everything that
// needs the core is a skip, not a failure of its own.
func TestCoreNotRunning(t *testing.T) {
	m := healthy(t)
	delete(m.core, "/core/status")
	m.cmds["systemctl --user is-active huginn-core.service"] = service.Result{Stdout: "failed\n", Code: 3}
	checks := Run(m.p)
	by := byID(checks)
	if !Failed(checks) {
		t.Fatal("not failed")
	}
	if by["core.service"].State != Fail || !strings.Contains(by["core.service"].Detail, "is failed") || !strings.Contains(by["core.service"].Fix, "journalctl --user -u huginn-core.service") {
		t.Errorf("core.service: %+v", by["core.service"])
	}
	if by["core.socket"].State != Fail || !strings.Contains(by["core.socket"].Detail, "not answering on ~/run/huginn/core.sock") {
		t.Errorf("core.socket: %+v", by["core.socket"])
	}
	for _, id := range []string{"core.tray", "server.status", "device"} {
		if by[id].State != Skip {
			t.Errorf("%s with the core down: %+v", id, by[id])
		}
	}
	// Pairing is read off core.json when the core cannot say.
	if by["core.pairing"].State != Warn || !strings.Contains(by["core.pairing"].Detail, lan) {
		t.Errorf("core.pairing: %+v", by["core.pairing"])
	}
	// Routes are still dialled: they do not need the core.
	if by["route:"+lan].State != OK {
		t.Errorf("route: %+v", by["route:"+lan])
	}
}

func TestNotInstalled(t *testing.T) {
	m := healthy(t)
	os.Remove(m.p.Layout.Link)
	os.Remove(filepath.Join(m.p.Home, ".huginn", "huginn.sh"))
	by := byID(Run(m.p))
	if by["core.binary"].State != Fail || by["core.binary"].Fix != "huginn core install" {
		t.Errorf("core.binary: %+v", by["core.binary"])
	}
	if by["core.cli"].State != Fail || !strings.Contains(by["core.cli"].Fix, "self-install") {
		t.Errorf("core.cli: %+v", by["core.cli"])
	}
}

func TestCLIVersionMismatch(t *testing.T) {
	m := healthy(t)
	os.WriteFile(filepath.Join(m.p.Home, ".huginn", "huginn.sh"), []byte("HUGINN_VERSION='2.6.7'\n"), 0o644)
	by := byID(Run(m.p))
	if by["core.cli"].State != Warn || !strings.Contains(by["core.cli"].Detail, "CLI 2.6.7") || by["core.cli"].Fix != "huginn update" {
		t.Errorf("core.cli: %+v", by["core.cli"])
	}
	// An older installed core than this command: say both versions.
	os.MkdirAll(filepath.Join(m.p.Layout.Root, "versions", "2.6.0"), 0o755)
	os.WriteFile(filepath.Join(m.p.Layout.Root, "versions", "2.6.0", "huginn"), []byte("#!/bin/sh\n"), 0o755)
	os.Remove(filepath.Join(m.p.Layout.Root, "current"))
	os.Symlink(filepath.Join("versions", "2.6.0"), filepath.Join(m.p.Layout.Root, "current"))
	by = byID(Run(m.p))
	if by["core.binary"].State != Warn || !strings.Contains(by["core.binary"].Detail, "this command is huginn 2.7.0, the installed core is 2.6.0") {
		t.Errorf("core.binary: %+v", by["core.binary"])
	}
}

func TestLingerOff(t *testing.T) {
	m := healthy(t)
	m.cmds["loginctl show-user alex --property=Linger"] = service.Result{Stdout: "Linger=no\n"}
	by := byID(Run(m.p))
	if by["core.service"].State != Warn || !strings.Contains(by["core.service"].Detail, "linger is off") || !strings.Contains(by["core.service"].Fix, "loginctl enable-linger alex") {
		t.Errorf("core.service: %+v", by["core.service"])
	}
}

// The one failure that must never be softened: an address answers with a
// certificate that is not the proved one.
func TestRouteCertificateNotPinned(t *testing.T) {
	m := healthy(t)
	m.tls["[2001:db8::10]:8787"] = otherPin
	m.tls["192.0.2.10:8787"] = ""
	checks := Run(m.p)
	by := byID(checks)
	if !Failed(checks) {
		t.Fatal("not failed")
	}
	if c := by["route:"+mesh]; c.State != Fail || !strings.Contains(c.Detail, "bbbbbbbbbbbbbbbb..., not the proved one") || !strings.Contains(c.Fix, "huginn core pair --url "+mesh) {
		t.Errorf("mesh: %+v", c)
	}
	if c := by["route:"+lan]; c.State != Fail || !strings.Contains(c.Detail, "connection refused") {
		t.Errorf("lan: %+v", c)
	}
	// No pin proved yet is a warning, not a failure.
	m.tls["192.0.2.10:8787"] = pin
	m.tls["[2001:db8::10]:8787"] = pin
	cfg := store.Config{V: 1, Appd: store.Appd{URL: lan}}
	b, _ := store.EncodeConfig(cfg)
	os.WriteFile(m.p.Paths.CoreFile(), b, 0o600)
	m.core["/core/status"].(map[string]any)["pins"] = []string{}
	by = byID(Run(m.p))
	if c := by["route:"+lan]; c.State != Warn || !strings.Contains(c.Detail, "no certificate has been proved yet") {
		t.Errorf("unproved: %+v", c)
	}
}

func TestNotPaired(t *testing.T) {
	m := healthy(t)
	st := m.core["/core/status"].(map[string]any)
	st["paired"], st["url"], st["pins"] = false, "", []string{}
	cfg := store.Config{V: 1}
	b, _ := store.EncodeConfig(cfg)
	os.WriteFile(m.p.Paths.CoreFile(), b, 0o600)
	by := byID(Run(m.p))
	if c := by["core.pairing"]; c.State != Fail || !strings.Contains(c.Fix, "huginn core pair --url https://<host>:8787") {
		t.Errorf("pairing: %+v", c)
	}
	if c := by["route"]; c.State != Skip {
		t.Errorf("routes with nothing to dial: %+v", c)
	}
	if c := by["server.status"]; c.State != Skip || c.Detail != "not paired" {
		t.Errorf("server: %+v", c)
	}
}

func TestServerDownBehindAReachableRoute(t *testing.T) {
	m := healthy(t)
	m.core["/appd/v1/status"] = errors.New("reaching huginn: 502 Bad Gateway")
	by := byID(Run(m.p))
	if c := by["server.status"]; c.State != Fail || !strings.Contains(c.Detail, "502") || !strings.Contains(c.Fix, "systemctl status huginn-appd") {
		t.Errorf("server: %+v", c)
	}
}

func TestDeviceStates(t *testing.T) {
	m := healthy(t)
	st := m.core["/core/status"].(map[string]any)
	dev := st["device"].(map[string]any)
	dev["settings"].(map[string]any)["enabled"] = false
	if c := byID(Run(m.p))["device"]; c.State != Skip || !strings.Contains(c.Fix, "huginn device on") {
		t.Errorf("not offered: %+v", c)
	}
	dev["settings"].(map[string]any)["enabled"] = true
	dev["status"].(map[string]any)["enrolled"] = false
	dev["status"].(map[string]any)["note"] = "Not reaching huginn: dial tcp: connection refused"
	if c := byID(Run(m.p))["device"]; c.State != Warn || !strings.Contains(c.Detail, "not enrolled: Not reaching huginn") {
		t.Errorf("not enrolled: %+v", c)
	}
	dev["status"].(map[string]any)["enrolled"] = true
	dev["status"].(map[string]any)["lockKnown"] = false
	if c := byID(Run(m.p))["device"]; c.State != OK || !strings.Contains(c.Detail, "lock state unknown (counts as locked)") {
		t.Errorf("lock unknown: %+v", c)
	}
}

func TestLocalAI(t *testing.T) {
	m := healthy(t)
	os.MkdirAll(m.p.LocalDir, 0o700)
	// The directory exists but the manager is gone.
	if c := byID(Run(m.p))["local-ai"]; c.State != Warn || !strings.Contains(c.Detail, "manager") {
		t.Errorf("no manager: %+v", c)
	}
	os.WriteFile(m.p.Manager, []byte("#!/usr/bin/env node\n"), 0o755)
	m.cmds["/usr/bin/node "+m.p.Manager+" status --json"] = service.Result{Stdout: `{"setup":true,"mode":"managed","class":"C","persistent":false,"engine":{"reachable":true},"models":["qwen3-4b","nomic-embed"]}`}
	if c := byID(Run(m.p))["local-ai"]; c.State != Warn || !strings.Contains(c.Detail, "stops when you sign out") || c.Fix != "huginn local persist" {
		t.Errorf("not persistent: %+v", c)
	}
	m.cmds["/usr/bin/node "+m.p.Manager+" status --json"] = service.Result{Stdout: `{"setup":true,"mode":"managed","class":"C","persistent":true,"engine":{"reachable":false},"models":["qwen3-4b"]}`}
	if c := byID(Run(m.p))["local-ai"]; c.State != Fail || !strings.Contains(c.Detail, "model server is not answering") {
		t.Errorf("engine down: %+v", c)
	}
	m.cmds["/usr/bin/node "+m.p.Manager+" status --json"] = service.Result{Stdout: `{"setup":true,"mode":"managed","class":"C","persistent":true,"engine":{"reachable":true},"models":["qwen3-4b"]}`}
	if c := byID(Run(m.p))["local-ai"]; c.State != OK || !strings.Contains(c.Detail, "managed (class C) serving qwen3-4b") {
		t.Errorf("serving: %+v", c)
	}
	// No node: the tier cannot be asked, and that is said.
	delete(m.paths, "node")
	if c := byID(Run(m.p))["local-ai"]; c.State != Warn || !strings.Contains(c.Detail, "node is not on PATH") {
		t.Errorf("no node: %+v", c)
	}
}

func TestSSHChain(t *testing.T) {
	m := healthy(t)
	// No stanza: the config check fails with setup ssh, -G resolves the bare
	// name to itself, and nothing else is tried against a made-up address.
	os.WriteFile(filepath.Join(m.p.Home, ".ssh", "config"), []byte("Host other\n  HostName 192.0.2.99\n"), 0o600)
	m.cmds["ssh -G huginn"] = service.Result{Stdout: "hostname huginn\nport 22\n"}
	by := byID(Run(m.p))
	if c := by["ssh.config"]; c.State != Fail || c.Fix != "huginn setup ssh" {
		t.Errorf("ssh.config: %+v", c)
	}
	if c := by["ssh.resolve"]; c.State != Warn || !strings.Contains(c.Detail, "resolves huginn to itself") {
		t.Errorf("ssh.resolve: %+v", c)
	}
	if by["ssh.port"].State != Skip || by["ssh.hostkey"].State != Skip {
		t.Errorf("a name that resolves to itself is dialled or looked up: %+v %+v", by["ssh.port"], by["ssh.hostkey"])
	}
	// The stanza back, but the key it names is gone, port 22 silent, and the
	// host key unknown.
	os.WriteFile(filepath.Join(m.p.Home, ".ssh", "config"), []byte("Host huginn\n  HostName 192.0.2.10\n  Port 2222\n  IdentityFile ~/.ssh/huginn_key\n"), 0o600)
	m.cmds["ssh -G huginn"] = service.Result{Stdout: "hostname 192.0.2.10\nport 2222\nuser alex\n"}
	m.cmds["ssh-keygen -F [192.0.2.10]:2222"] = service.Result{Code: 1}
	by = byID(Run(m.p))
	if c := by["ssh.config"]; c.State != Warn || !strings.Contains(c.Detail, "its key ~/.ssh/huginn_key does not exist") {
		t.Errorf("ssh.config: %+v", c)
	}
	if c := by["ssh.resolve"]; c.State != OK || !strings.Contains(c.Detail, "192.0.2.10:2222") {
		t.Errorf("ssh.resolve: %+v", c)
	}
	if c := by["ssh.port"]; c.State != Fail || !strings.Contains(c.Fix, "huginn setup ssh --host ADDRESS") {
		t.Errorf("ssh.port: %+v", c)
	}
	if c := by["ssh.hostkey"]; c.State != Warn || !strings.Contains(c.Detail, "[192.0.2.10]:2222 is not in known_hosts yet") {
		t.Errorf("ssh.hostkey: %+v", c)
	}
	// No ssh at all.
	delete(m.paths, "ssh")
	by = byID(Run(m.p))
	if c := by["ssh.resolve"]; c.State != Fail || !strings.Contains(c.Detail, "no ssh client") {
		t.Errorf("no ssh: %+v", c)
	}
	if by["ssh.port"].State != Skip || by["ssh.hostkey"].State != Skip {
		t.Errorf("after no ssh: %+v %+v", by["ssh.port"], by["ssh.hostkey"])
	}
}

func TestClaudeAndSkills(t *testing.T) {
	m := healthy(t)
	// Skills removed: a warning with the install command.
	skEnv := skills.Env{GOOS: "linux", Home: m.p.Home, Root: m.p.Layout.Root}
	skills.Remove(skEnv)
	by := byID(Run(m.p))
	if c := by["claude.skills"]; c.State != Warn || c.Fix != "huginn skills install" {
		t.Errorf("skills missing: %+v", c)
	}
	// No Claude Code at all: both are skips, and the skills row says why.
	delete(m.paths, "claude")
	os.RemoveAll(filepath.Join(m.p.Home, ".claude"))
	by = byID(Run(m.p))
	if c := by["claude.code"]; c.State != Skip {
		t.Errorf("no claude: %+v", c)
	}
	if c := by["claude.skills"]; c.State != Skip || !strings.Contains(c.Detail, "no Claude Code here") {
		t.Errorf("skills without claude: %+v", c)
	}
	// The person's own skill under our name.
	os.MkdirAll(filepath.Join(m.p.Home, ".claude", "skills", "huginn"), 0o755)
	os.WriteFile(filepath.Join(m.p.Home, ".claude", "skills", "huginn", "SKILL.md"), []byte("mine"), 0o644)
	by = byID(Run(m.p))
	if c := by["claude.skills"]; c.State != Warn || !strings.Contains(c.Detail, "your own skill") {
		t.Errorf("foreign skill: %+v", c)
	}
}

func TestTray(t *testing.T) {
	m := healthy(t)
	m.core["/core/status"].(map[string]any)["tray"] = false
	if c := byID(Run(m.p))["core.tray"]; c.State != Warn || c.Fix != "huginn core tray show" {
		t.Errorf("tray down with autostart: %+v", c)
	}
	os.Remove(m.p.Linux.TrayPath())
	if c := byID(Run(m.p))["core.tray"]; c.State != Skip || !strings.Contains(c.Detail, "--no-tray") {
		t.Errorf("tray down without autostart: %+v", c)
	}
	delete(m.env, "DISPLAY")
	if c := byID(Run(m.p))["core.tray"]; c.State != Skip || !strings.Contains(c.Detail, "no graphical session") {
		t.Errorf("headless: %+v", c)
	}
}

func TestWindowsService(t *testing.T) {
	m := healthy(t)
	m.p.GOOS = "windows"
	m.p.Linux = nil
	wo := service.WindowsOptions{Binary: `C:\Users\alex\AppData\Local\huginn\bin\huginn.exe`, User: `PC\alex`, AppData: t.TempDir(), Runner: service.RunnerFunc(m.run)}
	m.p.Windows = &wo
	m.cmds[`schtasks /Query /TN Huginn Core /XML`] = service.Result{Code: 1, Stderr: "ERROR: The system cannot find the file specified."}
	if c := byID(Run(m.p))["core.service"]; c.State != Fail || !strings.Contains(c.Detail, `"Huginn Core" task is not registered`) {
		t.Errorf("not registered: %+v", c)
	}
}

// The human report: one line per check, the fix under what is not ok, a count.
func TestRender(t *testing.T) {
	m := healthy(t)
	delete(m.core, "/core/status")
	var b bytes.Buffer
	Render(ui.For(&b, false, false), Run(m.p))
	s := b.String()
	if !strings.HasPrefix(s, "(o> huginn doctor\n") {
		t.Fatalf("head: %q", s[:40])
	}
	if !strings.Contains(s, "     x  core      huginn core is not answering") || !strings.Contains(s, "                  try: huginn core start, then huginn core status") {
		t.Fatalf("the failing row and its fix:\n%s", s)
	}
	if !strings.Contains(s, "checks failed; start with the first one that did.") {
		t.Fatalf("summary:\n%s", s)
	}
	b.Reset()
	Render(ui.For(&b, false, false), Run(healthy(t).p))
	if !strings.Contains(b.String(), "Everything huginn needs on this machine looks fine.") {
		t.Fatalf("healthy summary:\n%s", b.String())
	}
}
