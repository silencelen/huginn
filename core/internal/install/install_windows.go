//go:build windows

package install

import (
	"fmt"
	"os"
	"path/filepath"
	"strings"
	"syscall"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
	"golang.org/x/sys/windows/registry"
)

// installWindows copies src to %LOCALAPPDATA%\huginn\bin\huginn.exe.
//
// ⚠ A RUNNING .exe CANNOT BE OVERWRITTEN, but it CAN be renamed: the copy that
// is the boot service right now is moved to huginn.exe.old, the new one takes
// its name, and the .old is deleted on a later run once nothing holds it.
func installWindows(l Layout, src string) (Result, error) {
	var r Result
	bin := filepath.Join(l.Root, "bin")
	dst := filepath.Join(bin, "huginn.exe")
	r.Binary = dst
	pruneAside(bin) // best effort: earlier updates' leftovers, unless still running
	same, err := sameFile(src, dst)
	if err != nil {
		return r, err
	}
	if !same {
		if err := os.MkdirAll(bin, 0o755); err != nil {
			return r, err
		}
		if _, err := os.Stat(dst); err == nil {
			// ⚠ The aside name must be FREE. A huginn.exe.old that an older
			// process still runs from (a console window left open across two
			// updates) cannot be deleted, and Windows refuses to rename over
			// it: PRESTIGE's `huginn update` failed with "Access is denied"
			// (2026-10-16). So a held .old gets a unique sibling instead.
			aside := dst + ".old"
			if _, err := os.Stat(aside); err == nil {
				aside = fmt.Sprintf("%s.old-%d", dst, time.Now().UnixNano())
			}
			if err := os.Rename(dst, aside); err != nil {
				return r, err
			}
		}
		if err := copyAtomic(src, dst, 0o755); err != nil {
			return r, err
		}
		r.Steps = append(r.Steps, "installed "+dst)
		r.Changed = true
	} else {
		r.Steps = append(r.Steps, "already installed: "+dst)
	}
	added, err := ensureUserPath(bin)
	if err != nil {
		return r, err
	}
	if added {
		r.Steps = append(r.Steps, bin+" added to your PATH (new terminals see it)")
		r.Changed = true
	}
	return r, nil
}

// ensureUserPath appends dir to HKCU\Environment\Path. ⚠ Not `setx`: setx
// truncates the value at 1024 characters and silently eats the rest of a long
// PATH. The value keeps its registry type (REG_EXPAND_SZ stays expandable).
func ensureUserPath(dir string) (bool, error) {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Environment`, registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		return false, err
	}
	defer k.Close()
	cur, typ, err := k.GetStringValue("Path")
	if err != nil && err != registry.ErrNotExist {
		return false, err
	}
	for _, p := range strings.Split(cur, ";") {
		if strings.EqualFold(strings.TrimRight(p, `\`), strings.TrimRight(dir, `\`)) {
			return false, nil
		}
	}
	next := dir
	if cur != "" {
		next = strings.TrimRight(cur, ";") + ";" + dir
	}
	if typ == registry.EXPAND_SZ || err == registry.ErrNotExist {
		err = k.SetExpandStringValue("Path", next)
	} else {
		err = k.SetStringValue("Path", next)
	}
	if err != nil {
		return false, err
	}
	broadcastEnvChange()
	return true, nil
}

// broadcastEnvChange tells Explorer (and so new terminals) that the user
// environment changed.
func broadcastEnvChange() {
	user32 := windows.NewLazySystemDLL("user32.dll")
	send := user32.NewProc("SendMessageTimeoutW")
	env, _ := syscall.UTF16PtrFromString("Environment")
	const hwndBroadcast, wmSettingChange, smtoAbortIfHung = 0xffff, 0x001A, 0x0002
	var res uintptr
	send.Call(hwndBroadcast, wmSettingChange, 0, uintptr(unsafe.Pointer(env)), smtoAbortIfHung, 5000, uintptr(unsafe.Pointer(&res)))
}

// RemoveUserPath takes dir out of HKCU\Environment\Path (ensureUserPath's
// inverse); false when it was not there.
func RemoveUserPath(dir string) (bool, error) {
	k, err := registry.OpenKey(registry.CURRENT_USER, `Environment`, registry.QUERY_VALUE|registry.SET_VALUE)
	if err != nil {
		return false, err
	}
	defer k.Close()
	cur, typ, err := k.GetStringValue("Path")
	if err == registry.ErrNotExist {
		return false, nil
	} else if err != nil {
		return false, err
	}
	var keep []string
	hit := false
	for _, p := range strings.Split(cur, ";") {
		if strings.EqualFold(strings.TrimRight(p, `\`), strings.TrimRight(dir, `\`)) {
			hit = true
			continue
		}
		if p != "" {
			keep = append(keep, p)
		}
	}
	if !hit {
		return false, nil
	}
	next := strings.Join(keep, ";")
	if typ == registry.EXPAND_SZ {
		err = k.SetExpandStringValue("Path", next)
	} else {
		err = k.SetStringValue("Path", next)
	}
	if err != nil {
		return false, err
	}
	broadcastEnvChange()
	return true, nil
}

// pruneAside removes huginn.exe.old and huginn.exe.old-* left by earlier
// updates; a file a process still runs from is kept for the next run.
func pruneAside(bin string) {
	matches, _ := filepath.Glob(filepath.Join(bin, "huginn.exe.old*"))
	for _, m := range matches {
		os.Remove(m)
	}
}
