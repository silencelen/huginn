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

// upgradeStoredURLs rewrites the addresses in core.json to the form they are
// dialled in (appd.NormalizeURL), and returns "old -> new" for each one it
// changed. Nothing is written when nothing changes.
//
// ⚠ WHY, WHEN THE CLIENT UPGRADES ON ITS OWN. A core paired on the server
// itself, or adopted from the Node runner's default, holds
// http://127.0.0.1:8787 from before loopback was proved (security scan F8).
// appd.New dials it as https:// either way, so the route keeps working; but
// the route book compares the address typed with the address stored (remove,
// pin, the health beside each row), and `huginn core status` would go on
// printing http:// for a connection that is TLS. An address that ends up
// listed twice (http:// and https:// of one daemon) is kept once.
func upgradeStoredURLs(st *store.Store) ([]string, error) {
	cfg, _, err := st.Load()
	if err != nil {
		return nil, err
	}
	if len(upgradeURLs(&cfg)) == 0 { // a scratch copy: Load hands out a fresh one
		return nil, nil
	}
	var changed []string
	_, _, err = st.Update(func(c *store.Config) error {
		changed = upgradeURLs(c)
		return nil
	})
	if err != nil {
		return nil, err
	}
	return changed, nil
}

func upgradeURLs(c *store.Config) []string {
	var changed []string
	up := func(u string) string {
		n := appd.NormalizeURL(u)
		if n != u {
			changed = append(changed, u+" -> "+n)
		}
		return n
	}
	if c.Appd.URL != "" {
		c.Appd.URL = up(c.Appd.URL)
	}
	if c.Appd.Pinned != "" {
		c.Appd.Pinned = up(c.Appd.Pinned)
	}
	if len(c.Appd.Routes) == 0 {
		return changed
	}
	seen := map[string]bool{c.Appd.URL: true}
	routes := make([]store.Route, 0, len(c.Appd.Routes))
	for _, r := range c.Appd.Routes {
		r.URL = up(r.URL)
		if seen[r.URL] {
			continue
		}
		seen[r.URL] = true
		routes = append(routes, r)
	}
	c.Appd.Routes = routes
	return changed
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
