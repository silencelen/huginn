// Package proc starts the tray role from an updater or from an outgoing tray:
// detached from the caller, so it outlives it and holds none of its handles
// (the desktop app reads `self-install`'s output until the pipe closes, and a
// tray that inherited that pipe would keep it open for its whole life).
package proc

import "os/exec"

// StartDetached starts bin with args in its own session/process group, with
// stdin/stdout/stderr on the null device, and forgets it.
func StartDetached(bin string, args ...string) error {
	cmd := exec.Command(bin, args...)
	detach(cmd)
	if err := cmd.Start(); err != nil {
		// A job object that forbids breakaway refuses the flag (Windows).
		if retry := plain(cmd); retry != nil {
			if err2 := retry.Start(); err2 == nil {
				return retry.Process.Release()
			}
		}
		return err
	}
	return cmd.Process.Release()
}

// Output runs bin briefly and returns its stdout, with no console window on
// Windows (a console program started from the tray would flash one).
func Output(bin string, args ...string) (string, error) {
	cmd := exec.Command(bin, args...)
	quiet(cmd)
	b, err := cmd.Output()
	return string(b), err
}
