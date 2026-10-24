package localtier

import (
	"os"
	"runtime"
	"testing"
)

func TestSeed(t *testing.T) {
	dir := t.TempDir()
	f, err := Seed(dir, " tok-1\n")
	if err != nil {
		t.Fatal(err)
	}
	b, _ := os.ReadFile(f)
	if string(b) != "tok-1" {
		t.Fatalf("seed = %q", b)
	}
	if runtime.GOOS != "windows" {
		if fi, _ := os.Stat(f); fi.Mode().Perm() != 0o600 {
			t.Fatalf("mode %v", fi.Mode().Perm())
		}
	}
	fi1, _ := os.Stat(f)
	if _, err := Seed(dir, "tok-1"); err != nil {
		t.Fatal(err)
	}
	if fi2, _ := os.Stat(f); !fi2.ModTime().Equal(fi1.ModTime()) {
		t.Fatal("an unchanged token was rewritten")
	}
	Seed(dir, "tok-2") // a rotated token reaches the stale seed
	if b, _ := os.ReadFile(f); string(b) != "tok-2" {
		t.Fatalf("after rotation = %q", b)
	}
	if _, err := Seed(dir, "  "); err == nil {
		t.Fatal("an empty token was written")
	}
}

// The token copy is locked every time it is seeded, and when it is found
// already in place (a copy written before the lock): on Windows 0600 meant
// nothing and it inherited "Users: read" (security review, 2026-10-17).
func TestSeedLocksTheTokenFile(t *testing.T) {
	var locked []string
	old := bestEffortLock
	bestEffortLock = func(p string) { locked = append(locked, p) }
	defer func() { bestEffortLock = old }()
	dir := t.TempDir()
	f, err := Seed(dir, "tok-1")
	if err != nil {
		t.Fatal(err)
	}
	if len(locked) != 1 || locked[0] != f {
		t.Fatalf("a new token file was not locked: %v", locked)
	}
	if _, err := Seed(dir, "tok-1"); err != nil {
		t.Fatal(err)
	}
	if len(locked) != 2 || locked[1] != f {
		t.Fatalf("an unchanged token file was not locked again: %v", locked)
	}
}

// ---------------------------------------------------------------- F6 fixes

// sidTrusted is the adopt-or-refuse decision for a pre-existing Windows tree:
// only this user, SYSTEM, or the Administrators group may own one we seed into.
func TestSidTrusted(t *testing.T) {
	const self = "S-1-5-21-1-2-3-1001"
	for _, c := range []struct {
		owner string
		want  bool
	}{
		{self, true},
		{sidSystem, true},
		{sidAdministrators, true},
		{"S-1-5-21-9-9-9-1050", false}, // another local account — the attacker
		{"", false},                    // no owner read is never "us"
	} {
		if got := sidTrusted(c.owner, self); got != c.want {
			t.Errorf("sidTrusted(%q) = %v, want %v", c.owner, got, c.want)
		}
	}
}

// isReparse must catch an NTFS junction, which this toolchain reports as
// ModeIrregular rather than ModeSymlink — the case the old symlink-only check
// waved through, letting a junction redirect the appd token out of the tree.
func TestIsReparse(t *testing.T) {
	if !isReparse(os.ModeSymlink) {
		t.Error("a symlink is a reparse point")
	}
	if !isReparse(os.ModeIrregular) {
		t.Error("an irregular entry (an NTFS junction) is a reparse point")
	}
	if !isReparse(os.ModeIrregular | os.ModeDir) {
		t.Error("a junction can carry the directory bit too")
	}
	if isReparse(os.ModeDir) {
		t.Error("a plain directory is not a reparse point")
	}
	if isReparse(0) {
		t.Error("a plain file is not a reparse point")
	}
}

// foreignUID is the POSIX counterpart: anyone but the current user, bar root.
func TestForeignUID(t *testing.T) {
	const self = 1000
	if foreignUID(self, self) {
		t.Error("the current user is not foreign")
	}
	if foreignUID(0, self) {
		t.Error("root is trusted (an elevated step may have created it)")
	}
	if !foreignUID(1050, self) {
		t.Error("another unprivileged uid must be refused")
	}
}

// A token file a different account planted must be refused, not reused — the
// old seed truncated whatever was at the fixed path, keeping its owner and ACL.
func TestSeedRefusesForeignTokenFile(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("owner is a uid here; the SID path is exercised on Windows")
	}
	if os.Getuid() != 0 {
		t.Skip("needs root to chown a file to another uid")
	}
	dir := t.TempDir()
	f := TokenFile(dir)
	if err := os.MkdirAll(dirOf(f), 0o700); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(f, []byte("planted"), 0o644); err != nil {
		t.Fatal(err)
	}
	if err := os.Chown(f, 12345, 12345); err != nil {
		t.Fatalf("chown: %v", err)
	}
	if _, err := Seed(dir, "real-token"); err == nil {
		t.Fatal("seed adopted a token file owned by another account")
	}
	// And the attacker's file was not overwritten with the real token.
	if b, _ := os.ReadFile(f); string(b) != "planted" {
		t.Fatalf("the foreign file was rewritten: %q", b)
	}
}

// A device dir a different account planted must be refused too.
func TestSeedRefusesForeignDir(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("owner is a uid here; the SID path is exercised on Windows")
	}
	if os.Getuid() != 0 {
		t.Skip("needs root to chown a dir to another uid")
	}
	dir := t.TempDir()
	dev := dirOf(TokenFile(dir))
	if err := os.MkdirAll(dev, 0o777); err != nil {
		t.Fatal(err)
	}
	if err := os.Chown(dev, 12345, 12345); err != nil {
		t.Fatalf("chown: %v", err)
	}
	if _, err := Seed(dir, "real-token"); err == nil {
		t.Fatal("seed adopted a device dir owned by another account")
	}
}

// A symlinked token path (the classic redirect) is refused rather than followed.
func TestSeedRefusesSymlinkedTokenFile(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("POSIX symlink semantics")
	}
	dir := t.TempDir()
	f := TokenFile(dir)
	if err := os.MkdirAll(dirOf(f), 0o700); err != nil {
		t.Fatal(err)
	}
	target := dir + "/elsewhere"
	if err := os.WriteFile(target, []byte("x"), 0o600); err != nil {
		t.Fatal(err)
	}
	if err := os.Symlink(target, f); err != nil {
		t.Fatal(err)
	}
	if _, err := Seed(dir, "real-token"); err == nil {
		t.Fatal("seed wrote through a symlinked token path")
	}
	if b, _ := os.ReadFile(target); string(b) != "x" {
		t.Fatalf("the symlink target was written through: %q", b)
	}
}

// The old seed used a FIXED temp name (device/appd-token.tmp) and os.WriteFile,
// so an attacker-planted file at that path was truncated and reused. The new
// seed uses a random O_EXCL temp: a file at the old fixed name is left alone,
// and the seed still succeeds.
func TestSeedIgnoresAPlantedFixedTemp(t *testing.T) {
	dir := t.TempDir()
	dev := dirOf(TokenFile(dir))
	if err := os.MkdirAll(dev, 0o700); err != nil {
		t.Fatal(err)
	}
	planted := TokenFile(dir) + ".tmp" // the old fixed temp name
	if err := os.WriteFile(planted, []byte("attacker"), 0o600); err != nil {
		t.Fatal(err)
	}
	f, err := Seed(dir, "real-token")
	if err != nil {
		t.Fatal(err)
	}
	if b, _ := os.ReadFile(f); string(b) != "real-token" {
		t.Fatalf("token = %q", b)
	}
	// The old fixed-name file is untouched — the random temp never reused it.
	if b, _ := os.ReadFile(planted); string(b) != "attacker" {
		t.Fatalf("the planted fixed-name temp was reused: %q", b)
	}
}

// On POSIX the token lands 0600 even under a wide-open umask, because the temp
// is created O_EXCL with the mode (never chmod'd after a default-umask write).
func TestSeedTokenIs0600UnderWideUmask(t *testing.T) {
	if runtime.GOOS == "windows" {
		t.Skip("mode bits are a POSIX concept")
	}
	old := syscallUmask(0)
	defer syscallUmask(old)
	dir := t.TempDir()
	f, err := Seed(dir, "tok")
	if err != nil {
		t.Fatal(err)
	}
	fi, err := os.Stat(f)
	if err != nil {
		t.Fatal(err)
	}
	if fi.Mode().Perm() != 0o600 {
		t.Fatalf("token mode = %v, want 0600", fi.Mode().Perm())
	}
}

func dirOf(p string) string {
	for i := len(p) - 1; i >= 0; i-- {
		if p[i] == '/' || p[i] == '\\' {
			return p[:i]
		}
	}
	return "."
}
