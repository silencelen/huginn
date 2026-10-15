// Prototype Q2 (docs/CORE-ARCHITECTURE.md §11): what does a boot-time task
// running as the user actually get? Run under the logon type being tested
// (S4U or stored password); writes one JSON line of facts to the path given.
// Not product code.
package main

import (
	"encoding/json"
	"fmt"
	"net/http"
	"os"
	"time"
	"unsafe"

	"golang.org/x/sys/windows"
)

var (
	advapi32   = windows.NewLazySystemDLL("advapi32.dll")
	credWriteW = advapi32.NewProc("CredWriteW")
	credReadW  = advapi32.NewProc("CredReadW")
	credDelW   = advapi32.NewProc("CredDeleteW")
	credFree   = advapi32.NewProc("CredFree")
)

type credential struct {
	Flags              uint32
	Type               uint32
	TargetName         *uint16
	Comment            *uint16
	LastWritten        windows.Filetime
	CredentialBlobSize uint32
	CredentialBlob     *byte
	Persist            uint32
	AttributeCount     uint32
	Attributes         uintptr
	TargetAlias        *uint16
	UserName           *uint16
}

const credTypeGeneric, credPersistLocal = 1, 2

func credRoundTrip() string {
	target, _ := windows.UTF16PtrFromString("huginn-core-proto/s4ucheck")
	user, _ := windows.UTF16PtrFromString("huginn")
	blob := []byte("probe-" + time.Now().Format(time.RFC3339Nano))
	c := credential{Type: credTypeGeneric, TargetName: target, CredentialBlobSize: uint32(len(blob)),
		CredentialBlob: &blob[0], Persist: credPersistLocal, UserName: user}
	if r, _, err := credWriteW.Call(uintptr(unsafe.Pointer(&c)), 0); r == 0 {
		return "write failed: " + err.Error()
	}
	var out *credential
	if r, _, err := credReadW.Call(uintptr(unsafe.Pointer(target)), credTypeGeneric, 0, uintptr(unsafe.Pointer(&out))); r == 0 {
		return "read failed: " + err.Error()
	}
	got := string(unsafe.Slice(out.CredentialBlob, out.CredentialBlobSize))
	credFree.Call(uintptr(unsafe.Pointer(out)))
	credDelW.Call(uintptr(unsafe.Pointer(target)), credTypeGeneric, 0)
	if got != string(blob) {
		return "read back a different value"
	}
	return "ok"
}

func dpapiRoundTrip() string {
	in := []byte("dpapi-probe")
	var din, dout windows.DataBlob
	din.Size, din.Data = uint32(len(in)), &in[0]
	if err := windows.CryptProtectData(&din, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &dout); err != nil {
		return "protect failed: " + err.Error()
	}
	var back windows.DataBlob
	err := windows.CryptUnprotectData(&dout, nil, nil, 0, nil, windows.CRYPTPROTECT_UI_FORBIDDEN, &back)
	windows.LocalFree(windows.Handle(unsafe.Pointer(dout.Data)))
	if err != nil {
		return "unprotect failed: " + err.Error()
	}
	defer windows.LocalFree(windows.Handle(unsafe.Pointer(back.Data)))
	if string(unsafe.Slice(back.Data, back.Size)) != string(in) {
		return "round trip mismatch"
	}
	return "ok"
}

func httpsGet() string {
	c := &http.Client{Timeout: 15 * time.Second}
	r, err := c.Get("https://api.github.com/repos/silencelen/huginn/releases?per_page=1")
	if err != nil {
		return "failed: " + err.Error()
	}
	r.Body.Close()
	return fmt.Sprintf("HTTP %d", r.StatusCode)
}

func main() {
	if len(os.Args) != 2 {
		fmt.Fprintln(os.Stderr, "usage: s4ucheck <out.json>")
		os.Exit(2)
	}
	var sess uint32
	windows.ProcessIdToSessionId(windows.GetCurrentProcessId(), &sess)
	tu, _ := windows.GetCurrentProcessToken().GetTokenUser()
	elevated := windows.GetCurrentProcessToken().IsElevated()
	home, _ := os.UserHomeDir()
	_, homeErr := os.Stat(home)
	facts := map[string]any{
		"at":         time.Now().Format(time.RFC3339),
		"user":       os.Getenv("USERNAME"),
		"domain":     os.Getenv("USERDOMAIN"),
		"sid":        tu.User.Sid.String(),
		"session":    sess,
		"elevated":   elevated,
		"home":       home,
		"homeOK":     homeErr == nil,
		"localapp":   os.Getenv("LOCALAPPDATA"),
		"credman":    credRoundTrip(),
		"dpapi":      dpapiRoundTrip(),
		"https":      httpsGet(),
		"claudeHome": statOK(home + `\.claude`),
	}
	b, _ := json.Marshal(facts)
	os.WriteFile(os.Args[1], append(b, '\n'), 0o600)
}

func statOK(p string) bool { _, err := os.Stat(p); return err == nil }
