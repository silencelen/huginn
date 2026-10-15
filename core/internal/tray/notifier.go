package tray

import "github.com/silencelen/huginn/core/internal/traystate"

// Notifier posts and withdraws OS notifications. One per platform.
type Notifier interface {
	Post(n traystate.Notice) error
	Withdraw(key string) error
}
