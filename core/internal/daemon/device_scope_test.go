package daemon

import (
	"context"
	"encoding/json"
	"net/http"
	"sync"
	"testing"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/appd/appdtest"
	"github.com/silencelen/huginn/core/internal/policy"
	"github.com/silencelen/huginn/core/internal/runner"
	"github.com/silencelen/huginn/core/internal/store"
)

// A device turned on with no scope chosen (the setup wizard's device step, the
// Settings toggle and `huginn device on` all send only {enabled:true}) must
// enrol as the scope Settings shows for it. ParseScope floors "" to generate,
// so it enrolled as a local-model server that could run no ask or act work
// while Settings showed Look selected.
func TestDeviceWithNoScopeEnrolsAsSettingsShowsIt(t *testing.T) {
	d := pairRig(t)
	on := true
	if _, _, err := d.st.Update(func(c *store.Config) error { return ApplyDevice(c, DeviceSettings{Enabled: &on}) }); err != nil {
		t.Fatal(err)
	}
	if cfg, _, _ := d.st.Load(); cfg.Device.Scope != "" {
		t.Fatalf("the test needs a device with no saved scope, got %q", cfg.Device.Scope)
	}
	shown := *d.deviceView(&runner.Status{}).Settings.Scope
	if shown != policy.EnrolDefault {
		t.Fatalf("Settings shows %q, want the enrolment default %q", shown, policy.EnrolDefault)
	}
	if got := policy.ParseScope(d.runnerConf().Scope); got != shown {
		t.Fatalf("the runner would enrol as %q while Settings shows %q", got, shown)
	}

	// And what the runner actually asks appd for.
	tok := "scope-token"
	var mu sync.Mutex
	asked := ""
	srv := appdtest.NewServer(t, tok, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		switch {
		case r.Method == http.MethodPost && r.URL.Path == "/v1/devices":
			var e map[string]any
			json.NewDecoder(r.Body).Decode(&e)
			mu.Lock()
			asked, _ = e["scope"].(string)
			mu.Unlock()
			w.WriteHeader(201)
			json.NewEncoder(w).Encode(map[string]any{"id": "dev1", "name": e["name"], "scope": e["scope"]})
		case r.Method == http.MethodGet:
			select { // an idle long poll
			case <-r.Context().Done():
			case <-time.After(200 * time.Millisecond):
			}
			w.Write([]byte(`{"work":null}`))
		default:
			w.Write([]byte(`{"ok":true}`))
		}
	}))
	api, err := appd.New(appd.Config{URL: srv.URL, Tokens: appd.StaticToken(tok)})
	if err != nil {
		t.Fatal(err)
	}
	defer api.Close()
	r := runner.New(runner.Hooks{
		API: api, Conf: d.runnerConf, SaveID: d.saveID,
		Lock: func(context.Context) runner.Lock { return runner.Lock{Known: true} },
		Logf: t.Logf, Home: t.TempDir(),
	})
	ctx, cancel := context.WithCancel(context.Background())
	done := make(chan struct{})
	go func() { r.Serve(ctx); close(done) }()
	defer func() { cancel(); <-done }()
	deadline := time.Now().Add(10 * time.Second)
	for {
		mu.Lock()
		got := asked
		mu.Unlock()
		if got != "" {
			if got != shown {
				t.Fatalf("the runner enrolled as %q while Settings shows %q", got, shown)
			}
			return
		}
		if time.Now().After(deadline) {
			t.Fatal("the runner never enrolled")
		}
		time.Sleep(20 * time.Millisecond)
	}
}
