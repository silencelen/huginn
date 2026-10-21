package notify

// End to end against a REAL appd from this tree (server/appd), started the way
// core/internal/appd/e2e_test.go does it: scratch dirs, a throwaway token, a
// PRIVATE tmux socket, a kernel-picked port, loopback plain HTTP. It reads
// `event: state` frames off /v1/watch?stream=1, feeds them to Plan, and flips a
// session into `attention` by writing its state file, which is what Claude's
// hook does in production.
//
// SAFETY: the daemon and the private tmux server are killed in Cleanup,
// including on a failed assertion — a stray appd blocks the release gates.

import (
	"bufio"
	"context"
	"crypto/rand"
	"encoding/hex"
	"fmt"
	"net"
	"net/http"
	"os"
	"os/exec"
	"path/filepath"
	"strings"
	"syscall"
	"testing"
	"time"
)

func TestWatchStreamFromRealAppd(t *testing.T) {
	if testing.Short() {
		t.Skip("-short: skipping the real-appd test")
	}
	for _, bin := range []string{"node", "tmux"} {
		if _, err := exec.LookPath(bin); err != nil {
			t.Skipf("%s is not installed; this test needs a real appd", bin)
		}
	}
	script, err := filepath.Abs(filepath.Join("..", "..", "..", "server", "appd", "huginn-appd.js"))
	if err != nil {
		t.Fatal(err)
	}
	if _, err := os.Stat(script); err != nil {
		t.Skipf("no appd at %s: %v", script, err)
	}

	tmp := t.TempDir()
	data, state, claude := filepath.Join(tmp, "appd-data"), filepath.Join(tmp, "appd-state"), filepath.Join(tmp, "appd-claude")
	for _, d := range []string{data, state, claude} {
		if err := os.MkdirAll(d, 0o700); err != nil {
			t.Fatal(err)
		}
	}
	b := make([]byte, 32)
	if _, err := rand.Read(b); err != nil {
		t.Fatal(err)
	}
	tok := hex.EncodeToString(b)
	tokFile := filepath.Join(tmp, "appd-token")
	if err := os.WriteFile(tokFile, []byte(tok+"\n"), 0o600); err != nil {
		t.Fatal(err)
	}
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	port := ln.Addr().(*net.TCPAddr).Port
	ln.Close()
	sock := fmt.Sprintf("huginn-notify-%d", os.Getpid())
	t.Cleanup(func() { _ = exec.Command("tmux", "-L", sock, "kill-server").Run() })

	// The session exists BEFORE appd's first listing, with no state file: the
	// seed frame must carry it with a null state.
	const name = "notifye2e"
	if out, err := exec.Command("tmux", "-L", sock, "new-session", "-d", "-s", name, "sleep", "600").CombinedOutput(); err != nil {
		t.Fatalf("tmux new-session: %v %s", err, out)
	}

	env := []string{}
	for _, kv := range os.Environ() {
		if !strings.HasPrefix(kv, "HUGINN_APPD_") && !strings.HasPrefix(kv, "HUGINN_FCM_KEY=") {
			env = append(env, kv)
		}
	}
	// The push key is not HUGINN_APPD_-prefixed, and unset it falls back to the
	// live key on a huginn host: point it at nothing, like the Node suites do.
	env = append(env,
		"HUGINN_FCM_KEY=/nonexistent/huginn-test-fcm-service-account.json",
		fmt.Sprintf("HUGINN_APPD_PORT=%d", port),
		"HUGINN_APPD_BIND=127.0.0.1",
		"HUGINN_APPD_DATA="+data,
		"HUGINN_APPD_TOKEN_FILE="+tokFile,
		"HUGINN_APPD_STATE_DIR="+state,
		"HUGINN_APPD_CLAUDE_DIR="+claude,
		"HUGINN_APPD_TMUX_SOCKET="+sock,
		"HUGINN_APPD_WORKDIR="+tmp,
	)
	logPath := filepath.Join(tmp, "appd.log")
	logf, err := os.Create(logPath)
	if err != nil {
		t.Fatal(err)
	}
	cmd := exec.Command("node", script)
	cmd.Env = env
	cmd.Stdout, cmd.Stderr = logf, logf
	if err := cmd.Start(); err != nil {
		t.Fatal(err)
	}
	exited := make(chan struct{})
	go func() { _ = cmd.Wait(); logf.Close(); close(exited) }()
	t.Cleanup(func() {
		_ = cmd.Process.Signal(syscall.SIGTERM)
		select {
		case <-exited:
		case <-time.After(3 * time.Second):
			_ = cmd.Process.Kill()
			<-exited
		}
	})
	logTail := func() string {
		b, _ := os.ReadFile(logPath)
		if len(b) > 1500 {
			b = b[len(b)-1500:]
		}
		return string(b)
	}

	base := fmt.Sprintf("http://127.0.0.1:%d", port)
	hc := &http.Client{Timeout: time.Second}
	for i := 0; ; i++ {
		select {
		case <-exited:
			t.Fatalf("appd exited at start (server-side):\n%s", logTail())
		default:
		}
		if r, err := hc.Get(base + "/v1/ping"); err == nil {
			r.Body.Close()
			break
		}
		if i > 300 {
			t.Fatalf("appd did not start (server-side):\n%s", logTail())
		}
		time.Sleep(100 * time.Millisecond)
	}

	ctx, cancel := context.WithTimeout(context.Background(), 60*time.Second)
	defer cancel()
	req, _ := http.NewRequestWithContext(ctx, http.MethodGet, base+"/v1/watch?stream=1", nil)
	req.Header.Set("Authorization", "Bearer "+tok)
	resp, err := http.DefaultClient.Do(req)
	if err != nil {
		t.Fatal(err)
	}
	defer resp.Body.Close()
	if resp.StatusCode != 200 {
		t.Fatalf("watch stream: HTTP %d\n%s", resp.StatusCode, logTail())
	}
	rd := bufio.NewReader(resp.Body)
	// nextState returns the data of the next `event: state` frame, skipping
	// keepalive comments.
	nextState := func() []byte {
		t.Helper()
		var ev, dat string
		for {
			line, err := rd.ReadString('\n')
			if err != nil {
				t.Fatalf("stream: %v\n%s", err, logTail())
			}
			line = strings.TrimRight(line, "\r\n")
			switch {
			case line == "":
				if ev == "state" {
					return []byte(dat)
				}
				ev, dat = "", ""
			case strings.HasPrefix(line, "event: "):
				ev = strings.TrimPrefix(line, "event: ")
			case strings.HasPrefix(line, "data: "):
				dat += strings.TrimPrefix(line, "data: ")
			}
		}
	}

	raw := nextState()
	w, err := ParseWatch(raw)
	if err != nil {
		t.Fatalf("parse real frame %s: %v", raw, err)
	}
	if w.Hash == "" || w.Headroom == nil || w.Chats == nil {
		t.Fatalf("real frame missing fields: %s", raw)
	}
	if st, ok := w.Sessions[name]; !ok || st == StateAttention {
		t.Fatalf("seed frame: session %q missing or already waiting: %s", name, raw)
	}
	d, bl := Plan(Baseline{}, w, "", true)
	if len(d) != 0 {
		t.Fatalf("seed announced %+v", d)
	}

	// What Claude's Notification hook writes: the session now waits on a human.
	stamp := fmt.Sprintf(`{"state":"attention","ts":%d}`, time.Now().UnixMilli())
	if err := os.WriteFile(filepath.Join(state, name), []byte(stamp), 0o600); err != nil {
		t.Fatal(err)
	}
	raw = nextState()
	if w, err = ParseWatch(raw); err != nil {
		t.Fatal(err)
	}
	d, bl = Plan(bl, w, "", true)
	if len(d) != 1 || d[0].Kind != Attention || d[0].Session != name {
		t.Fatalf("attention frame %s gave %+v", raw, d)
	}

	// Killing the session withdraws its notice by the byte-identical key.
	if out, err := exec.Command("tmux", "-L", sock, "kill-session", "-t", "="+name).CombinedOutput(); err != nil {
		t.Fatalf("tmux kill-session: %v %s", err, out)
	}
	raw = nextState()
	if w, err = ParseWatch(raw); err != nil {
		t.Fatal(err)
	}
	d, _ = Plan(bl, w, "", true)
	if len(d) != 1 || d[0].Kind != Withdraw || d[0].Key != "sess:"+name {
		t.Fatalf("kill frame %s gave %+v", raw, d)
	}
}
