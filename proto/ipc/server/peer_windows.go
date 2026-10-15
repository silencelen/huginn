package main

import (
	"errors"
	"fmt"
	"net"
	"unsafe"

	"golang.org/x/sys/windows"
)

// SIO_AF_UNIX_GETPEERPID (afunix.h): the pid of the process on the other end.
const sioAfUnixGetPeerPid = 0x58000100

// peerIdentity resolves the peer's pid, opens its token and compares the
// token's user SID with ours. AF_UNIX on Windows has no SO_PEERCRED; this is
// the documented equivalent. A peer that is not this user is an error.
func peerIdentity(c net.Conn) (string, error) {
	uc, ok := c.(*net.UnixConn)
	if !ok {
		return "", errors.New("not a unix conn")
	}
	raw, err := uc.SyscallConn()
	if err != nil {
		return "", err
	}
	var pid uint32
	var ierr error
	if err := raw.Control(func(fd uintptr) {
		var ret uint32
		ierr = windows.WSAIoctl(windows.Handle(fd), sioAfUnixGetPeerPid, nil, 0,
			(*byte)(unsafe.Pointer(&pid)), uint32(unsafe.Sizeof(pid)), &ret, nil, 0)
	}); err != nil {
		return "", err
	}
	if ierr != nil {
		return "", fmt.Errorf("SIO_AF_UNIX_GETPEERPID: %w", ierr)
	}
	who := fmt.Sprintf("pid=%d", pid)
	h, err := windows.OpenProcess(windows.PROCESS_QUERY_LIMITED_INFORMATION, false, pid)
	if err != nil {
		return who, fmt.Errorf("OpenProcess: %w", err)
	}
	defer windows.CloseHandle(h)
	var tok windows.Token
	if err := windows.OpenProcessToken(h, windows.TOKEN_QUERY, &tok); err != nil {
		return who, fmt.Errorf("OpenProcessToken: %w", err)
	}
	defer tok.Close()
	peer, err := tok.GetTokenUser()
	if err != nil {
		return who, err
	}
	me, err := windows.GetCurrentProcessToken().GetTokenUser()
	if err != nil {
		return who, err
	}
	who += " sid=" + peer.User.Sid.String()
	if !windows.EqualSid(peer.User.Sid, me.User.Sid) {
		return who, errors.New("peer is another user")
	}
	return who, nil
}
