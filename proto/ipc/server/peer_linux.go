package main

import (
	"errors"
	"fmt"
	"net"
	"os"

	"golang.org/x/sys/unix"
)

// peerIdentity reads SO_PEERCRED: the uid of the process on the other end, as
// the kernel saw it at connect time. It cannot be forged by the client. A peer
// that is not this user is an error.
func peerIdentity(c net.Conn) (string, error) {
	uc, ok := c.(*net.UnixConn)
	if !ok {
		return "", errors.New("not a unix conn")
	}
	raw, err := uc.SyscallConn()
	if err != nil {
		return "", err
	}
	var cred *unix.Ucred
	var serr error
	if err := raw.Control(func(fd uintptr) {
		cred, serr = unix.GetsockoptUcred(int(fd), unix.SOL_SOCKET, unix.SO_PEERCRED)
	}); err != nil {
		return "", err
	}
	if serr != nil {
		return "", serr
	}
	who := fmt.Sprintf("uid=%d pid=%d", cred.Uid, cred.Pid)
	if int(cred.Uid) != os.Getuid() {
		return who, errors.New("peer is another user")
	}
	return who, nil
}
