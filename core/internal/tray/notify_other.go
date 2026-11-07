//go:build android || (!linux && !windows)

package tray

import (
	"log"

	"github.com/silencelen/huginn/core/internal/traystate"
)

type noNotifier struct{}

func newNotifier(*log.Logger, func(string)) Notifier { return noNotifier{} }
func (noNotifier) Post(traystate.Notice) error       { return nil }
func (noNotifier) Withdraw(string) error             { return nil }
func (noNotifier) CanDeliver() bool                  { return false }
