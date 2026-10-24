package doctor

import (
	"context"
	"crypto/tls"
	"encoding/json"
	"errors"
	"fmt"
	"io"
	"net"
	"os"
	"os/exec"
	"path/filepath"
	"runtime"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/clientkit"
	"github.com/silencelen/huginn/core/internal/daemon"
	"github.com/silencelen/huginn/core/internal/install"
	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/localtier"
	"github.com/silencelen/huginn/core/internal/service"
	"github.com/silencelen/huginn/core/internal/skills"
	"github.com/silencelen/huginn/core/internal/store"
)

// Default is the real machine. version is this binary's.
func Default(version string) (Probe, error) {
	home, err := os.UserHomeDir()
	if err != nil {
		return Probe{}, err
	}
	paths, err := store.DefaultPaths()
	if err != nil {
		return Probe{}, err
	}
	layout, _ := install.DefaultLayout()
	exe, _ := os.Executable()
	if real, err := filepath.EvalSymlinks(exe); err == nil {
		exe = real
	}
	runner := service.ExecRunner{Timeout: 20 * time.Second}
	p := Probe{
		GOOS: runtime.GOOS, Home: home, Version: version, Executable: exe,
		Env: os.Getenv, Layout: layout, Paths: paths,
		Run: runner, LookPath: exec.LookPath,
		ClientVersion: clientkit.Version,
		SocketPath:    daemon.SocketPath(paths),
		LocalDir:      localtier.Dir(home),
		Manager:       filepath.Join(home, ".huginn", "huginn-local"),
	}
	switch runtime.GOOS {
	case "linux":
		if o, err := service.LinuxDefaults(layout.Binary()); err == nil {
			o.Runner = runner
			p.Linux = &o
		}
	case "windows":
		if o, err := service.WindowsDefaults(layout.Binary()); err == nil {
			o.Runner = runner
			p.Windows = &o
		}
	}
	sock := p.SocketPath
	p.Core = func(method, path string, out any) error { return coreCall(sock, method, path, out) }
	p.DialTLS = dialTLS
	p.DialTCP = dialTCP
	if e, err := skills.DefaultEnv(); err == nil {
		p.Skills = func() []skills.Entry { return skills.Status(e) }
	}
	return p, nil
}

// coreCall is one request to the running core, with a short deadline and the
// same body bound as the CLI's `call`.
func coreCall(sock, method, path string, out any) error {
	ctx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	resp, err := ipc.Do(ctx, ipc.Client(sock), method, path, nil)
	if err != nil {
		return err
	}
	defer resp.Body.Close()
	b, err := io.ReadAll(io.LimitReader(resp.Body, 4<<20))
	if err != nil {
		return err
	}
	if resp.StatusCode >= 400 {
		var e struct {
			Error string `json:"error"`
		}
		json.Unmarshal(b, &e)
		if e.Error == "" {
			e.Error = fmt.Sprintf("HTTP %d", resp.StatusCode)
		}
		return errors.New(appd.Printable(e.Error, 300))
	}
	if out == nil {
		return nil
	}
	return json.Unmarshal(b, out)
}

// dialTLS completes a handshake and reports the leaf certificate's
// fingerprint. ⚠ InsecureSkipVerify is right here and only here: the daemon's
// certificate is self-signed, so there is no chain to verify; the doctor
// judges the leaf against the proved pins itself, and sends nothing over the
// connection. Nothing is proved by this call; it reports what answers.
func dialTLS(hostport string, timeout time.Duration) (string, time.Duration, error) {
	t0 := time.Now()
	d := &net.Dialer{Timeout: timeout}
	conn, err := tls.DialWithDialer(d, "tcp", hostport, &tls.Config{InsecureSkipVerify: true}) //nolint:gosec // see above
	if err != nil {
		return "", 0, err
	}
	defer conn.Close()
	lat := time.Since(t0)
	certs := conn.ConnectionState().PeerCertificates
	if len(certs) == 0 {
		return "", lat, errors.New("the server sent no certificate")
	}
	return appd.Fingerprint(certs[0].Raw), lat, nil
}

// dialTCP connects and closes: it proves a port answers, nothing more.
func dialTCP(hostport string, timeout time.Duration) (time.Duration, error) {
	t0 := time.Now()
	c, err := net.DialTimeout("tcp", hostport, timeout)
	if err != nil {
		return 0, err
	}
	c.Close()
	return time.Since(t0), nil
}
