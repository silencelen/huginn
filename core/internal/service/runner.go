package service

import (
	"bytes"
	"context"
	"errors"
	"os/exec"
	"strconv"
	"strings"
	"time"
)

// Result of one command. Err is set only when the command could not be run
// at all (not found, timed out); a non-zero exit is Code.
type Result struct {
	Stdout string
	Stderr string
	Code   int
	Err    error
}

// OK: it ran and exited 0.
func (r Result) OK() bool { return r.Err == nil && r.Code == 0 }

// Why is the most useful one-line explanation of a failure.
func (r Result) Why() string {
	if r.Err != nil {
		return r.Err.Error()
	}
	for _, s := range []string{r.Stderr, r.Stdout} {
		if t := strings.TrimSpace(s); t != "" {
			if i := strings.IndexByte(t, '\n'); i >= 0 {
				t = strings.TrimSpace(t[:i])
			}
			return t
		}
	}
	if r.Code != 0 {
		return "exit status " + strconv.Itoa(r.Code)
	}
	return ""
}

// Runner runs one external command. Tests inject a fake.
type Runner interface {
	Run(name string, args ...string) Result
}

// InputRunner can also feed a command's stdin — how a secret reaches a child
// without ever being in its argv (argv is in process-creation logs and visible
// to other processes while it runs).
type InputRunner interface {
	RunInput(stdin string, name string, args ...string) Result
}

// RunnerFunc adapts a function.
type RunnerFunc func(name string, args ...string) Result

func (f RunnerFunc) Run(name string, args ...string) Result { return f(name, args...) }

// ExecRunner runs real commands, each bounded by Timeout (default 60 s).
type ExecRunner struct{ Timeout time.Duration }

func (e ExecRunner) Run(name string, args ...string) Result { return e.RunInput("", name, args...) }

// RunInput runs name with stdin fed from the string (empty = no stdin).
func (e ExecRunner) RunInput(stdin string, name string, args ...string) Result {
	t := e.Timeout
	if t == 0 {
		t = 60 * time.Second
	}
	ctx, cancel := context.WithTimeout(context.Background(), t)
	defer cancel()
	cmd := exec.CommandContext(ctx, name, args...)
	cmd.WaitDelay = 2 * time.Second
	if stdin != "" {
		cmd.Stdin = strings.NewReader(stdin)
	}
	var out, errb bytes.Buffer
	cmd.Stdout, cmd.Stderr = &out, &errb
	err := cmd.Run()
	r := Result{Stdout: out.String(), Stderr: errb.String()}
	var ee *exec.ExitError
	switch {
	case ctx.Err() == context.DeadlineExceeded:
		r.Err = errors.New(name + " did not finish within " + t.String())
	case errors.As(err, &ee):
		r.Code = ee.ExitCode()
	case err != nil:
		r.Err = err
	}
	return r
}
