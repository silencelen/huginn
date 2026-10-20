//go:build !windows

package console

import (
	"bytes"
	"io"
	"os"
	"os/signal"
	"path/filepath"
	"strings"
	"sync"
	"syscall"
	"testing"
	"time"
)

// Ctrl-C while the link is down stops the console's reconnect loop, as it
// stops the shell client's. The console runs the loop inside tea.Exec, where
// Bubble Tea's own SIGINT handler is registered and drops the signal, so these
// tests register one too: a Ctrl-C must work with it there, and it keeps the
// test process alive when nothing else hears the signal.

type syncBuffer struct {
	mu sync.Mutex
	b  bytes.Buffer
}

func (s *syncBuffer) Write(p []byte) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.Write(p)
}

func (s *syncBuffer) String() string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.b.String()
}

// fakeSSH puts first on PATH an ssh that runs body (sh) and counts its tries.
func fakeSSH(t *testing.T, body string) (tries func() int) {
	t.Helper()
	dir := t.TempDir()
	log := filepath.Join(dir, "tries")
	if err := os.WriteFile(filepath.Join(dir, "ssh"), []byte("#!/bin/sh\necho try >> '"+log+"'\n"+body+"\n"), 0o755); err != nil {
		t.Fatal(err)
	}
	t.Setenv("PATH", dir+string(os.PathListSeparator)+os.Getenv("PATH"))
	return func() int {
		b, _ := os.ReadFile(log)
		return strings.Count(string(b), "try")
	}
}

// runAttach starts the loop with SIGINT held the way Bubble Tea holds it.
// The loop is waited for before the test ends, so it never outlives the fake
// ssh (and the host is one that cannot resolve, should it ever).
func runAttach(t *testing.T) (<-chan error, *syncBuffer) {
	t.Helper()
	held := make(chan os.Signal, 8)
	signal.Notify(held, os.Interrupt)
	t.Setenv("HUGINN_NO_RECONNECT", "")
	t.Setenv("HUGINN_NO_TITLE", "1")
	a, err := newAttach("huginn.invalid", "main", false)
	if err != nil {
		t.Fatal(err)
	}
	stderr := &syncBuffer{}
	a.SetStdout(io.Discard)
	a.SetStderr(stderr)
	done, ended := make(chan error, 1), make(chan struct{})
	go func() {
		done <- a.Run()
		close(ended)
	}()
	t.Cleanup(func() {
		select {
		case <-ended:
		case <-time.After(20 * time.Second):
			t.Error("the reconnect loop is still running")
		}
		signal.Stop(held)
	})
	return done, stderr
}

func stoppedBy(t *testing.T, done <-chan error, tries func() int, what string) {
	t.Helper()
	select {
	case err := <-done:
		if err == nil || !strings.Contains(err.Error(), "stopped reconnecting") {
			t.Fatalf("Ctrl-C %s ended the loop with %v", what, err)
		}
	case <-time.After(1500 * time.Millisecond):
		t.Fatalf("Ctrl-C %s did not stop the reconnect loop (%d tries so far)", what, tries())
	}
	if n := tries(); n != 1 {
		t.Fatalf("ssh was tried %d times", n)
	}
}

func TestCtrlCWhileWaitingToReconnectStopsTheLoop(t *testing.T) {
	tries := fakeSSH(t, "exit 255") // the link is down: every try fails
	done, stderr := runAttach(t)
	for deadline := time.Now().Add(5 * time.Second); tries() < 1; time.Sleep(10 * time.Millisecond) {
		if time.Now().After(deadline) {
			t.Fatal("ssh was never tried")
		}
	}
	time.Sleep(300 * time.Millisecond) // into the wait before the next try (2 s, give or take a quarter)
	syscall.Kill(os.Getpid(), syscall.SIGINT)
	stoppedBy(t, done, tries, "during the wait")
	if !strings.Contains(stderr.String(), "(Ctrl-C to stop)") {
		t.Fatalf("the wait does not say how to stop it: %q", stderr.String())
	}
}

// A Ctrl-C while ssh is still connecting reaches ssh too (the terminal
// signals the whole foreground group): that is the person stopping, not a
// quick failure of the host to count towards "failing immediately".
func TestCtrlCWhileSSHConnectsStopsTheLoop(t *testing.T) {
	tries := fakeSSH(t, "kill -INT $PPID; kill -INT $$")
	done, _ := runAttach(t)
	stoppedBy(t, done, tries, "while ssh was connecting")
}
