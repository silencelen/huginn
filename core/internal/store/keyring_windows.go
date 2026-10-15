//go:build windows

package store

import (
	"errors"
	"fmt"
	"syscall"
	"unsafe"

	"golang.org/x/sys/windows"
)

// The Credential Manager calls, with the struct layout proven by
// proto/windows/s4ucheck.
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

const (
	credTypeGeneric         = 1
	credPersistLocalMachine = 2
	credMaxBlob             = 5 * 512 // CRED_MAX_CREDENTIAL_BLOB_SIZE
)

func keyringName() string { return BackendCredMan }

// CredManSecrets is the Windows Credential Manager backend (generic
// credentials, target "huginn/<name>", persisted for this machine).
type CredManSecrets struct{}

func (CredManSecrets) Backend() string { return BackendCredMan }

// openKeyring probes Credential Manager by reading a target that never
// exists: ERROR_NOT_FOUND means it is there; ERROR_NO_SUCH_LOGON_SESSION is
// the S4U case (no credential store in a logon with no password).
func openKeyring() (Secrets, error) {
	_, err := credRead(CredTarget(".probe"))
	if err == nil || errors.Is(err, ErrNotFound) {
		return CredManSecrets{}, nil
	}
	if errors.Is(err, windows.ERROR_NO_SUCH_LOGON_SESSION) {
		return nil, fmt.Errorf("%w: this logon session has no Credential Manager (an S4U task?)", ErrUnavailable)
	}
	return nil, fmt.Errorf("%w: Credential Manager: %v", ErrUnavailable, err)
}

func credRead(target string) (string, error) {
	t, err := windows.UTF16PtrFromString(target)
	if err != nil {
		return "", err
	}
	var out *credential
	r, _, callErr := credReadW.Call(uintptr(unsafe.Pointer(t)), credTypeGeneric, 0, uintptr(unsafe.Pointer(&out)))
	if r == 0 {
		if errors.Is(callErr, windows.ERROR_NOT_FOUND) {
			return "", fmt.Errorf("%s: %w", target, ErrNotFound)
		}
		return "", errnoOr(callErr)
	}
	defer credFree.Call(uintptr(unsafe.Pointer(out)))
	if out.CredentialBlobSize == 0 || out.CredentialBlob == nil {
		return "", fmt.Errorf("%s is empty: %w", target, ErrNotFound)
	}
	return string(unsafe.Slice(out.CredentialBlob, out.CredentialBlobSize)), nil
}

func (CredManSecrets) Get(name string) (string, error) {
	if err := ValidName(name); err != nil {
		return "", err
	}
	return credRead(CredTarget(name))
}

func (CredManSecrets) Set(name, v string) error {
	if err := ValidName(name); err != nil {
		return err
	}
	if v == "" {
		return errors.New("store: refusing to store an empty secret")
	}
	if len(v) > credMaxBlob {
		return fmt.Errorf("store: secret %s is %d bytes; Credential Manager holds at most %d", name, len(v), credMaxBlob)
	}
	target, err := windows.UTF16PtrFromString(CredTarget(name))
	if err != nil {
		return err
	}
	user, _ := windows.UTF16PtrFromString("huginn")
	blob := []byte(v)
	c := credential{Type: credTypeGeneric, TargetName: target, CredentialBlobSize: uint32(len(blob)),
		CredentialBlob: &blob[0], Persist: credPersistLocalMachine, UserName: user}
	if r, _, callErr := credWriteW.Call(uintptr(unsafe.Pointer(&c)), 0); r == 0 {
		return fmt.Errorf("store: CredWrite %s: %w", CredTarget(name), errnoOr(callErr))
	}
	return nil
}

func (CredManSecrets) Delete(name string) error {
	if err := ValidName(name); err != nil {
		return err
	}
	t, err := windows.UTF16PtrFromString(CredTarget(name))
	if err != nil {
		return err
	}
	if r, _, callErr := credDelW.Call(uintptr(unsafe.Pointer(t)), credTypeGeneric, 0); r == 0 {
		if errors.Is(callErr, windows.ERROR_NOT_FOUND) {
			return nil
		}
		return fmt.Errorf("store: CredDelete %s: %w", CredTarget(name), errnoOr(callErr))
	}
	return nil
}

func errnoOr(err error) error {
	var e syscall.Errno
	if errors.As(err, &e) && e != 0 {
		return e
	}
	if err == nil {
		return errors.New("unknown error")
	}
	return err
}
