package daemon

import (
	"net/http"
	"os"

	"github.com/silencelen/huginn/core/internal/ipc"
	"github.com/silencelen/huginn/core/internal/localtier"
	"github.com/silencelen/huginn/core/internal/store"
)

// POST /core/local/seed hands the local-AI tier its appd token (owner
// decision 2026-10-16, docs/design/desktop-via-core.md): the core writes the file
// the tier's runner reads, from its own secrets store, so the token never
// passes through the desktop. The answer names the address the tier should
// be turned on with (`huginn-local on --url`): the one the core uses now.
//
// It writes where the desktop and the CLI wrapper always wrote, as this user;
// the manager's own elevated step (Windows services) reads it from there.

// LocalSeed is the answer.
type LocalSeed struct {
	URL       string `json:"url"`
	Dir       string `json:"dir"`
	TokenFile string `json:"tokenFile"`
}

func (d *Daemon) localHandlers(mux *http.ServeMux) {
	mux.HandleFunc("POST /core/local/seed", func(w http.ResponseWriter, r *http.Request) {
		api := d.apiClient()
		if api == nil {
			ipc.WriteJSON(w, http.StatusConflict, map[string]string{"error": "huginn core is not paired with huginn: pair it first", "kind": "unpaired"})
			return
		}
		tok, err := d.secrets.Get(store.SecretToken)
		if err != nil || tok == "" {
			ipc.WriteJSON(w, http.StatusConflict, map[string]string{"error": "huginn core holds no appd token (" + d.secrets.Backend() + ")"})
			return
		}
		home, _ := os.UserHomeDir()
		dir := localtier.Dir(home)
		f, err := localtier.Seed(dir, tok)
		if err != nil {
			ipc.WriteJSON(w, http.StatusInternalServerError, map[string]string{"error": "handing the local tier its token: " + err.Error()})
			return
		}
		ipc.WriteJSON(w, http.StatusOK, LocalSeed{URL: api.URL(), Dir: dir, TokenFile: f})
	})
}
