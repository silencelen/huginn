package main

import (
	"encoding/json"
	"os"
	"os/exec"
	"os/signal"
	"path/filepath"
	"runtime"
)

// The core binary owns the name `huginn` on PATH (~/.local/bin/huginn,
// %LOCALAPPDATA%\huginn\bin\huginn.exe), but most verbs — sessions, attach,
// projects, usage, update — live in the shell client (~/.huginn/huginn.sh,
// huginn.ps1), which a shell normally loads as a function that shadows the
// binary. Where it is not loaded (fish, cmd.exe, a PowerShell without the
// profile, a script) `huginn ls` reached the core and got its usage. So the
// core hands every verb it does not own to the installed client.

// viaCore marks a client started by the core, so a client that ever reached
// the binary for an unknown verb would stop instead of looping.
const viaCore = "HUGINN_VIA_CORE"

// clientScript is the installed shell client for this platform, or "".
func clientScript() string {
	home, err := os.UserHomeDir()
	if err != nil {
		return ""
	}
	name := "huginn.sh"
	if runtime.GOOS == "windows" {
		name = "huginn.ps1"
	}
	p := filepath.Join(home, ".huginn", name)
	if fi, err := os.Stat(p); err != nil || fi.IsDir() {
		return ""
	}
	return p
}

// clientCommand is how to run verb args through the client at script.
func clientCommand(script string, args []string) (*exec.Cmd, error) {
	if runtime.GOOS == "windows" {
		// Arguments travel as JSON in the environment: a -Command string would
		// re-parse them as PowerShell. The person's execution policy applies.
		b, err := json.Marshal(args)
		if err != nil {
			return nil, err
		}
		cmd := exec.Command("powershell.exe", "-NoProfile", "-NoLogo", "-Command",
			`. $env:HUGINN_CLIENT_SCRIPT; $a = @(ConvertFrom-Json $env:HUGINN_CLIENT_ARGS); huginn @a; exit $LASTEXITCODE`)
		cmd.Env = append(os.Environ(), viaCore+"=1", "HUGINN_CLIENT_SCRIPT="+script, "HUGINN_CLIENT_ARGS="+string(b))
		return cmd, nil
	}
	bash, err := exec.LookPath("bash")
	if err != nil {
		return nil, err
	}
	cmd := exec.Command(bash, append([]string{"-c", `. "$0" && huginn "$@"`, script}, args...)...)
	cmd.Env = append(os.Environ(), viaCore+"=1")
	return cmd, nil
}

// forwardToClient runs args through the shell client. ok is false when there
// is none to run (or this is already a client the core started).
func forwardToClient(args []string) (code int, ok bool) {
	if os.Getenv(viaCore) != "" {
		return 0, false
	}
	script := clientScript()
	if script == "" {
		return 0, false
	}
	cmd, err := clientCommand(script, args)
	if err != nil {
		return 0, false
	}
	cmd.Stdin, cmd.Stdout, cmd.Stderr = os.Stdin, os.Stdout, os.Stderr
	// Ctrl-C belongs to the client (an attached session, a prompt): the core
	// waits for it rather than dying first and leaving it orphaned.
	signal.Ignore(os.Interrupt)
	if err := cmd.Run(); err != nil {
		if ee, isExit := err.(*exec.ExitError); isExit {
			return ee.ExitCode(), true
		}
		return 1, true
	}
	return 0, true
}
