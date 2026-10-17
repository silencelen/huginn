package daemon

import (
	"context"
	"encoding/json"
	"fmt"
	"os"
	"runtime"
	"strings"
	"time"

	"github.com/silencelen/huginn/core/internal/appd"
	"github.com/silencelen/huginn/core/internal/store"
)

// firstStartImport adopts this machine's existing device row (the Node runner's
// device.json or the desktop's settings.json) so the core does NOT mint a
// second row for a machine huginn already knows (§11 Q7). Returns one line for
// the log and /core/status.
//
// ⚠ IT DELETES NOTHING, here or on the daemon. The source files are rewritten by
// their owners (the desktop saves its whole settings from memory), and a
// dropped id may still belong to a runner that is live at this moment — the old
// desktop, or the Node service that `huginn core install` stops. Dropped ids are
// recorded; an unwatched row the daemon stops hearing from is pruned by appd.
func (d *Daemon) firstStartImport(ctx context.Context) string {
	home, _ := os.UserHomeDir()
	src := store.MigrationSourcesFor(runtime.GOOS, os.Getenv, home)
	in, err := store.ReadMigration(src)
	if err != nil {
		return "import skipped: " + err.Error()
	}
	plan := store.PlanImport(in)
	if len(plan.Candidates) == 0 {
		return "nothing to import (no Node runner or desktop settings on this account)"
	}
	ch := plan.Choose(d.lastSeen(ctx, plan))
	var sources []string
	for _, c := range plan.Candidates {
		sources = append(sources, c.Source+":"+c.Path)
	}
	if tok := strings.TrimSpace(ch.Device.Token); tok != "" {
		if err := d.secrets.Set(store.SecretToken, tok); err != nil {
			return "import stopped: saving the token: " + err.Error()
		}
	} else {
		// No device was running: still take a token + url so the core is paired.
		for _, c := range plan.Candidates {
			if c.Token != "" && c.URL != "" {
				if err := d.secrets.Set(store.SecretToken, c.Token); err == nil {
					ch.Device.URL, ch.Device.Token = c.URL, c.Token
				}
				break
			}
		}
	}
	_, _, err = d.st.Update(func(c *store.Config) error {
		ch.Apply(c, sources, time.Now())
		c.Appd.URL = appd.NormalizeURL(c.Appd.URL)
		c.Secrets = d.secrets.Backend()
		return nil
	})
	if err != nil {
		return "import stopped: writing core.json: " + err.Error()
	}
	msg := ch.Why
	if len(ch.Dropped) > 0 {
		msg += fmt.Sprintf("; other ids seen (not deleted): %s", strings.Join(ch.Dropped, ", "))
	}
	return msg
}

// lastSeen asks appd when it last heard from each candidate id, with that
// candidate's own url and token. An unreachable daemon is zero time for all,
// and Choose then prefers the runner.
func (d *Daemon) lastSeen(ctx context.Context, plan store.Plan) map[string]time.Time {
	out := map[string]time.Time{}
	for _, c := range plan.Candidates {
		if c.DeviceID == "" || c.URL == "" || c.Token == "" {
			continue
		}
		cl, err := appd.New(appd.Config{URL: c.URL, Tokens: appd.StaticToken(c.Token)})
		if err != nil {
			continue
		}
		cctx, cancel := context.WithTimeout(ctx, 15*time.Second)
		dev, err := cl.Get(cctx, c.DeviceID)
		cancel()
		cl.Close()
		if err != nil {
			continue
		}
		var raw struct {
			LastSeen int64 `json:"lastSeen"`
		}
		if json.Unmarshal(dev.Raw, &raw) == nil && raw.LastSeen > 0 {
			out[c.DeviceID] = time.UnixMilli(raw.LastSeen)
		}
	}
	return out
}
