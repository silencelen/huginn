//go:build linux

package store

import "testing"

func TestSessionBusAddress(t *testing.T) {
	no := func(string) bool { return false }
	yes := func(string) bool { return true }
	if a, _ := SessionBusAddress(envOf(map[string]string{"DBUS_SESSION_BUS_ADDRESS": "unix:path=/x"}), no); a != "unix:path=/x" {
		t.Fatal(a)
	}
	if a, _ := SessionBusAddress(envOf(map[string]string{"DBUS_SESSION_BUS_ADDRESS": "autolaunch:", "XDG_RUNTIME_DIR": "/run/user/1000"}), yes); a != "unix:path=/run/user/1000/bus" {
		t.Fatal(a)
	}
	if _, err := SessionBusAddress(envOf(map[string]string{"XDG_RUNTIME_DIR": "/run/user/1000"}), no); err == nil {
		t.Fatal("no bus socket should be unavailable")
	}
}
