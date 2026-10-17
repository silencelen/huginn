package store

import (
	"errors"
	"os"
	"path/filepath"
	"reflect"
	"runtime"
	"strings"
	"testing"
	"time"
)

func envOf(m map[string]string) func(string) string { return func(k string) string { return m[k] } }

func TestPathsForLinux(t *testing.T) {
	p, err := PathsFor("linux", envOf(map[string]string{"XDG_RUNTIME_DIR": "/run/user/1000"}), "/home/s", 1000)
	if err != nil {
		t.Fatal(err)
	}
	want := Paths{Config: "/home/s/.config/huginn", State: "/home/s/.local/state/huginn", Runtime: "/run/user/1000/huginn"}
	if p != want {
		t.Fatalf("got %+v want %+v", p, want)
	}
	p, _ = PathsFor("linux", envOf(map[string]string{
		"XDG_CONFIG_HOME": "/x/cfg", "XDG_STATE_HOME": "relative/ignored",
	}), "/home/s", 1001)
	if p.Config != "/x/cfg/huginn" || p.State != "/home/s/.local/state/huginn" || p.Runtime != "/run/user/1001/huginn" {
		t.Fatalf("got %+v", p)
	}
	p, _ = PathsFor("linux", envOf(map[string]string{"HUGINN_CONFIG_DIR": "/tmp/t/"}), "/home/s", 0)
	if p.Config != "/tmp/t" || p.State != "/home/s/.local/state/huginn" {
		t.Fatalf("override: %+v", p)
	}
	if _, err := PathsFor("linux", envOf(nil), "", 0); !errors.Is(err, ErrNoHome) {
		t.Fatalf("no home: %v", err)
	}
}

func TestPathsForWindows(t *testing.T) {
	p, err := PathsFor("windows", envOf(map[string]string{
		"APPDATA": `C:\Users\s\AppData\Roaming`, "LOCALAPPDATA": `C:\Users\s\AppData\Local`,
	}), `C:\Users\s`, -1)
	if err != nil {
		t.Fatal(err)
	}
	want := Paths{Config: `C:\Users\s\AppData\Roaming\huginn`, State: `C:\Users\s\AppData\Local\huginn\state`, Runtime: `C:\Users\s\AppData\Local\huginn\run`}
	if p != want {
		t.Fatalf("got %+v", p)
	}
	p, _ = PathsFor("windows", envOf(nil), `D:\prof`, -1)
	if p.Config != `D:\prof\AppData\Roaming\huginn` || p.Runtime != `D:\prof\AppData\Local\huginn\run` {
		t.Fatalf("fallback: %+v", p)
	}
}

func testStore(t *testing.T) (*Store, string) {
	dir := t.TempDir()
	s, err := Open(Paths{Config: filepath.Join(dir, "huginn")})
	if err != nil {
		t.Fatal(err)
	}
	return s, filepath.Join(dir, "huginn")
}

func TestConfigRoundTripAndMode(t *testing.T) {
	s, dir := testStore(t)
	c, rep, err := s.Load()
	if err != nil || rep.Existed || c.V != SchemaVersion {
		t.Fatalf("fresh: %+v %+v %v", c, rep, err)
	}
	_, _, err = s.Update(func(c *Config) error {
		c.Device = Device{ID: "d1", Name: "box", Scope: "work", ActWhileLocked: true, Claude: "/usr/bin/claude"}
		c.Appd.URL = "https://huginn:8787"
		return nil
	})
	if err != nil {
		t.Fatal(err)
	}
	if err := s.SetPins("tag1", []string{"aa", "bb"}); err != nil {
		t.Fatal(err)
	}
	got, rep, err := s.Load()
	if err != nil || !rep.Existed {
		t.Fatal(err)
	}
	if got.Device.ID != "d1" || !got.Device.ActWhileLocked || got.Appd.URL != "https://huginn:8787" {
		t.Fatalf("got %+v", got)
	}
	if pins, _ := s.Pins("tag1"); !reflect.DeepEqual(pins, []string{"aa", "bb"}) {
		t.Fatalf("pins %v", pins)
	}
	if err := s.SetPins("tag1", nil); err != nil {
		t.Fatal(err)
	}
	if pins, _ := s.Pins("tag1"); len(pins) != 0 {
		t.Fatalf("pins not deleted: %v", pins)
	}
	if runtime.GOOS != "windows" {
		fi, _ := os.Stat(filepath.Join(dir, "core.json"))
		if fi.Mode().Perm() != 0o600 {
			t.Fatalf("mode %v", fi.Mode().Perm())
		}
		di, _ := os.Stat(dir)
		if di.Mode().Perm() != 0o700 {
			t.Fatalf("dir mode %v", di.Mode().Perm())
		}
	}
	// No temp files left behind.
	ents, _ := os.ReadDir(dir)
	for _, e := range ents {
		if strings.Contains(e.Name(), ".tmp-") {
			t.Fatalf("temp file left: %s", e.Name())
		}
	}
}

func TestUpdateErrorWritesNothing(t *testing.T) {
	s, _ := testStore(t)
	s.Update(func(c *Config) error { c.Device.ID = "keep"; return nil })
	boom := errors.New("boom")
	if _, _, err := s.Update(func(c *Config) error { c.Device.ID = "lost"; return boom }); !errors.Is(err, boom) {
		t.Fatal(err)
	}
	c, _, _ := s.Load()
	if c.Device.ID != "keep" {
		t.Fatalf("got %q", c.Device.ID)
	}
}

func TestCorruptConfigMovedAside(t *testing.T) {
	s, dir := testStore(t)
	os.WriteFile(filepath.Join(dir, "core.json"), []byte("{not json"), 0o600)
	c, rep, err := s.Load()
	if err != nil {
		t.Fatal(err)
	}
	if !rep.Corrupt || rep.CorruptPath != filepath.Join(dir, "core.json.corrupt") || rep.ParseError == "" {
		t.Fatalf("report %+v", rep)
	}
	if c.Device.ID != "" {
		t.Fatal("corrupt config should load empty")
	}
	b, err := os.ReadFile(rep.CorruptPath)
	if err != nil || string(b) != "{not json" {
		t.Fatalf("salvage: %q %v", b, err)
	}
	if _, err := os.Stat(filepath.Join(dir, "core.json")); !os.IsNotExist(err) {
		t.Fatal("corrupt original should be gone")
	}
}

func TestEmptyConfigIsFresh(t *testing.T) {
	s, dir := testStore(t)
	os.WriteFile(filepath.Join(dir, "core.json"), []byte("  \n"), 0o600)
	_, rep, err := s.Load()
	if err != nil || rep.Corrupt {
		t.Fatalf("%+v %v", rep, err)
	}
}

func TestNewerSchemaRefused(t *testing.T) {
	s, dir := testStore(t)
	os.WriteFile(filepath.Join(dir, "core.json"), []byte(`{"v":99}`), 0o600)
	if _, _, err := s.Load(); !errors.Is(err, ErrNewerSchema) {
		t.Fatalf("got %v", err)
	}
	if _, _, err := s.Update(func(*Config) error { return nil }); !errors.Is(err, ErrNewerSchema) {
		t.Fatalf("update got %v", err)
	}
	b, _ := os.ReadFile(filepath.Join(dir, "core.json"))
	if string(b) != `{"v":99}` {
		t.Fatal("newer file was rewritten")
	}
}

func TestEncodeConfigGolden(t *testing.T) {
	b, err := EncodeConfig(Config{Device: Device{ID: "x", Scope: "look"}, Appd: Appd{URL: "https://h"}})
	if err != nil {
		t.Fatal(err)
	}
	want := `{
  "v": 1,
  "device": {
    "enabled": false,
    "id": "x",
    "scope": "look",
    "actWhileLocked": false
  },
  "appd": {
    "url": "https://h"
  }
}
`
	if string(b) != want {
		t.Fatalf("got\n%s", b)
	}
}

func TestFileSecrets(t *testing.T) {
	dir := filepath.Join(t.TempDir(), "secrets")
	f := NewFileSecrets(dir)
	if _, err := f.Get(SecretToken); !errors.Is(err, ErrNotFound) {
		t.Fatalf("absent: %v", err)
	}
	if err := f.Set(SecretToken, "tok123"); err != nil {
		t.Fatal(err)
	}
	if v, err := f.Get(SecretToken); err != nil || v != "tok123" {
		t.Fatalf("%q %v", v, err)
	}
	if runtime.GOOS != "windows" {
		di, _ := os.Stat(dir)
		fi, _ := os.Stat(filepath.Join(dir, SecretToken))
		if di.Mode().Perm() != 0o700 || fi.Mode().Perm() != 0o600 {
			t.Fatalf("modes %v %v", di.Mode().Perm(), fi.Mode().Perm())
		}
	}
	os.WriteFile(filepath.Join(dir, SecretToken), []byte("tok456\n"), 0o600)
	if v, _ := f.Get(SecretToken); v != "tok456" {
		t.Fatalf("trim: %q", v)
	}
	os.WriteFile(filepath.Join(dir, SecretToken), []byte("\n"), 0o600)
	if _, err := f.Get(SecretToken); !errors.Is(err, ErrNotFound) {
		t.Fatalf("whitespace: %v", err)
	}
	if err := f.Delete(SecretToken); err != nil {
		t.Fatal(err)
	}
	if err := f.Delete(SecretToken); err != nil {
		t.Fatalf("absent delete: %v", err)
	}
	for _, bad := range []string{"../x", "", "A", "a/b", `a\b`, "a..b", ".hidden"} {
		if err := f.Set(bad, "v"); err == nil {
			t.Fatalf("name %q accepted", bad)
		}
	}
}

func TestOpenSecretsSelection(t *testing.T) {
	dir := t.TempDir()
	t.Setenv("HUGINN_SECRETS", "")
	s, sel := OpenSecrets(SecretsOptions{Dir: dir, Force: BackendFile})
	if s.Backend() != BackendFile || sel.Backend != BackendFile || sel.FellBack {
		t.Fatalf("force: %+v", sel)
	}
	s, sel = OpenSecrets(SecretsOptions{Dir: dir, Recorded: BackendFile})
	if s.Backend() != BackendFile || !strings.Contains(sel.Why, "recorded") {
		t.Fatalf("recorded: %+v", sel)
	}
	t.Setenv("HUGINN_SECRETS", "file")
	_, sel = OpenSecrets(SecretsOptions{Dir: dir, Recorded: BackendSecretService})
	if sel.Backend != BackendFile {
		t.Fatalf("env force beats recorded: %+v", sel)
	}
	// With no session bus the keyring is not usable and the file store wins
	// (not asserted when a real bus is present: then the keyring may answer).
	t.Setenv("HUGINN_SECRETS", "")
	t.Setenv("DBUS_SESSION_BUS_ADDRESS", "")
	t.Setenv("XDG_RUNTIME_DIR", t.TempDir())
	if runtime.GOOS == "linux" {
		_, sel = OpenSecrets(SecretsOptions{Dir: dir})
		if sel.Backend != BackendFile || !sel.FellBack || !strings.Contains(sel.Why, "no session bus") {
			t.Fatalf("fallback: %+v", sel)
		}
		// A RECORDED keyring that is unreachable stays the keyring, failing loudly.
		s, sel = OpenSecrets(SecretsOptions{Dir: dir, Recorded: BackendSecretService})
		if sel.Backend != BackendSecretService {
			t.Fatalf("sticky: %+v", sel)
		}
		if _, err := s.Get(SecretToken); !errors.Is(err, ErrUnavailable) {
			t.Fatalf("sticky get: %v", err)
		}
	}
}

func TestPureNames(t *testing.T) {
	if got := OwnerOnlySDDL("S-1-5-21-1-2-3-1001", true); got != "D:P(A;OICI;FA;;;S-1-5-21-1-2-3-1001)(A;OICI;FA;;;SY)" {
		t.Fatal(got)
	}
	if got := OwnerOnlySDDL("S-1-5-21-1-2-3-1001", false); got != "D:P(A;;FA;;;S-1-5-21-1-2-3-1001)(A;;FA;;;SY)" {
		t.Fatal(got)
	}
	if CredTarget("appd-token") != "huginn/appd-token" || SecretServiceLabel("appd-token") != "huginn appd-token" {
		t.Fatal("names")
	}
	if !reflect.DeepEqual(SecretServiceAttrs("x"), map[string]string{"application": "huginn", "name": "x"}) {
		t.Fatal("attrs")
	}
}

func TestMigrationSources(t *testing.T) {
	got := MigrationSourcesFor("linux", envOf(nil), "/home/s")
	want := MigrationSources{
		DesktopSettings: "/home/s/.config/huginn-desktop-kt/settings.json",
		RunnerConf:      "/home/s/.config/huginn/device.json",
		RunnerToken:     "/home/s/.config/huginn/appd-token",
	}
	if got != want {
		t.Fatalf("%+v", got)
	}
	got = MigrationSourcesFor("linux", envOf(map[string]string{"XDG_CONFIG_HOME": "/c", "HUGINN_DEVICE_DIR": "/l/device"}), "/home/s")
	if got.DesktopSettings != "/c/huginn-desktop-kt/settings.json" || got.RunnerToken != "/l/device/appd-token" {
		t.Fatalf("%+v", got)
	}
	got = MigrationSourcesFor("windows", envOf(nil), `C:\Users\s`)
	if got.DesktopSettings != `C:\Users\s\.config\huginn-desktop-kt\settings.json` || got.RunnerConf != `C:\Users\s\.config\huginn\device.json` {
		t.Fatalf("%+v", got)
	}
}

const desktopSample = `{
  "baseUrl": "https://192.168.2.117:8787",
  "token": "desktoptoken",
  "tlsPins": "{\"owner\":\"0123456789abcdef\",\"pins\":[\"aa\"]}",
  "deviceEnabled": true,
  "deviceId": "dev-desktop",
  "deviceUnenrolPending": false,
  "deviceScope": "own",
  "deviceActWhileLocked": true,
  "deviceRoot": "C:\\work",
  "deviceClaudePath": "C:\\Users\\s\\.local\\bin\\claude.exe",
  "windowX": 10
}`

const runnerSample = `{
  "url": "https://huginn:8787",
  "name": "ragnar",
  "scope": "work",
  "root": "/srv/work",
  "claude": "claude",
  "actWhileLocked": "true",
  "id": "dev-runner",
  "llmSlug": "ragnar-llm",
  "tls": {"token": "fedcba9876543210", "pins": ["bb", "cc"]}
}`

func srcs() MigrationSources {
	return MigrationSourcesFor("linux", envOf(nil), "/home/s")
}

func TestPlanImportBoth(t *testing.T) {
	p := PlanImport(MigrationInput{Sources: srcs(), Desktop: []byte(desktopSample),
		RunnerConf: []byte(runnerSample), RunnerToken: []byte("runnertoken\n")})
	if len(p.Problems) != 0 || len(p.Candidates) != 2 {
		t.Fatalf("%+v", p)
	}
	d, r := p.Candidates[0], p.Candidates[1]
	if d.Source != SourceDesktop || !d.Enabled || d.DeviceID != "dev-desktop" || d.Scope != "own" ||
		!d.ActWhileLocked || d.Root != `C:\work` || d.Token != "desktoptoken" || d.URL != "https://192.168.2.117:8787" ||
		d.Pins == nil || d.Pins.Tag != "0123456789abcdef" {
		t.Fatalf("desktop %+v", d)
	}
	// "true" (a string) is NOT true: only a literal JSON true widens.
	if r.Source != SourceRunner || r.ActWhileLocked || r.DeviceID != "dev-runner" || r.Token != "runnertoken" ||
		r.LLMSlug != "ragnar-llm" || r.Name != "ragnar" || r.Pins.Scheme != "runner-pinset" {
		t.Fatalf("runner %+v", r)
	}
	// Wipe: two desktop key sets, two runner files.
	if len(p.Wipe) != 4 || p.Wipe[0].Keys[0] != "deviceEnabled" || p.Wipe[1].Keys[0] != "token" || p.Wipe[3].Path != srcs().RunnerToken {
		t.Fatalf("wipe %+v", p.Wipe)
	}

	now := time.Now()
	ch := p.Choose(map[string]time.Time{"dev-desktop": now, "dev-runner": now.Add(-time.Hour)})
	if ch.Device.Source != SourceDesktop || !reflect.DeepEqual(ch.Dropped, []string{"dev-runner"}) {
		t.Fatalf("newer desktop: %+v", ch)
	}
	if len(ch.Conflicts) != 2 { // different token, different url
		t.Fatalf("conflicts %v", ch.Conflicts)
	}
	ch = p.Choose(map[string]time.Time{"dev-runner": now})
	if ch.Device.Source != SourceRunner || !reflect.DeepEqual(ch.Dropped, []string{"dev-desktop"}) {
		t.Fatalf("newer runner: %+v", ch)
	}
	// Runner keeps its token; claude "claude" (default) is filled from the desktop's explicit path.
	if ch.Device.Token != "runnertoken" || ch.Device.Claude != `C:\Users\s\.local\bin\claude.exe` {
		t.Fatalf("fill %+v", ch.Device)
	}
	// Tie (appd knows neither): the runner wins.
	if ch := p.Choose(nil); ch.Device.Source != SourceRunner {
		t.Fatalf("tie: %+v", ch)
	}

	var cfg Config
	ch.Apply(&cfg, []string{srcs().RunnerConf}, now)
	if cfg.Device.ID != "dev-runner" || cfg.Appd.URL != "https://huginn:8787" || cfg.MigratedFrom.DeviceIDFrom != SourceRunner ||
		!reflect.DeepEqual(cfg.MigratedFrom.Dropped, []string{"dev-desktop"}) {
		t.Fatalf("apply %+v %+v", cfg, cfg.MigratedFrom)
	}
}

func TestPlanImportDesktopOffPendingUnenrol(t *testing.T) {
	desk := `{"baseUrl":"https://h:8787","token":"t","deviceEnabled":false,"deviceId":"old","deviceUnenrolPending":true}`
	p := PlanImport(MigrationInput{Sources: srcs(), Desktop: []byte(desk)})
	ch := p.Choose(nil)
	if ch.Device.Source != "" || ch.Device.Token != "t" || ch.Device.URL != "https://h:8787" {
		t.Fatalf("%+v", ch)
	}
	if !reflect.DeepEqual(ch.Dropped, []string{"old"}) {
		t.Fatalf("dropped %v", ch.Dropped)
	}
	if !p.Candidates[0].UnenrolPending || p.Candidates[0].Scope != "look" {
		t.Fatalf("%+v", p.Candidates[0])
	}
}

func TestPlanImportRunnerDefaultsAndBadJSON(t *testing.T) {
	p := PlanImport(MigrationInput{Sources: srcs(), RunnerConf: []byte(`{"id":"r1"}`)})
	r := p.Candidates[0]
	if r.Scope != "work" || r.Claude != "claude" || r.URL != "http://127.0.0.1:8787" || !r.Enabled {
		t.Fatalf("defaults %+v", r)
	}
	p = PlanImport(MigrationInput{Sources: srcs(), Desktop: []byte("{"), RunnerToken: []byte(" ")})
	if len(p.Problems) != 2 {
		t.Fatalf("problems %v", p.Problems)
	}
	if ch := p.Choose(nil); ch.Device.DeviceID != "" {
		t.Fatalf("%+v", ch)
	}
}

func TestReadMigration(t *testing.T) {
	home := t.TempDir()
	src := MigrationSourcesFor(runtime.GOOS, envOf(nil), home)
	in, err := ReadMigration(src)
	if err != nil || in.Desktop != nil || in.RunnerConf != nil || in.RunnerToken != nil {
		t.Fatalf("empty home: %+v %v", in, err)
	}
	os.MkdirAll(filepath.Dir(src.DesktopSettings), 0o700)
	os.WriteFile(src.DesktopSettings, []byte(desktopSample), 0o600)
	os.WriteFile(src.DesktopSettings+".corrupt", []byte("x"), 0o600)
	os.MkdirAll(filepath.Dir(src.RunnerToken), 0o700)
	os.WriteFile(src.RunnerToken, []byte{}, 0o600)
	in, err = ReadMigration(src)
	if err != nil || in.Desktop == nil || in.RunnerToken == nil || len(in.RunnerToken) != 0 || !in.DesktopCorruptExists {
		t.Fatalf("%+v %v", in, err)
	}
	// Sources are read-only to the import.
	b, _ := os.ReadFile(src.DesktopSettings)
	if string(b) != desktopSample {
		t.Fatal("source modified")
	}
}

// A desktop caught mid-setup has saved the address it was typed but not yet a
// token. Importing the address alone made a core that called itself paired,
// reached nothing, and so was never paired properly (PRESTIGE, 2026-10-16).
// Its device settings still come across.
func TestImportTakesNoAddressWithoutItsToken(t *testing.T) {
	desk := `{"baseUrl":"https://huginn:8787","token":"","deviceEnabled":true,"deviceId":"dev-desk","deviceScope":"own","deviceActWhileLocked":true}`
	p := PlanImport(MigrationInput{Sources: srcs(), Desktop: []byte(desk)})
	ch := p.Choose(nil)
	var cfg Config
	ch.Apply(&cfg, []string{srcs().DesktopSettings}, time.Now())
	if cfg.Appd.URL != "" {
		t.Fatalf("an address with no token was imported: %q", cfg.Appd.URL)
	}
	if !cfg.Device.Enabled || cfg.Device.Scope != "own" {
		t.Fatalf("the device settings did not come across: %+v", cfg.Device)
	}
}
