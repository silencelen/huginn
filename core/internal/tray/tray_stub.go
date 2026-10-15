//go:build android || (!linux && !windows)

package tray

import (
	"errors"
	"log"
	"time"
)

// Options for Run.
type Options struct {
	Socket string
	Logger *log.Logger
}

// Run: there is no tray role here. Termux is a client only (D15) — on a phone
// the huginn app is what notifies.
func Run(o Options) {
	if o.Logger == nil {
		o.Logger = log.Default()
	}
	o.Logger.Print("huginn core tray: no tray on this platform (on a phone the huginn app notifies)")
}

// NotifyTest is unsupported here.
func NotifyTest(time.Duration) error { return errors.New("no notifications on this platform") }
