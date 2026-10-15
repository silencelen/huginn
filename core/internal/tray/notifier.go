package tray

import "github.com/silencelen/huginn/core/internal/traystate"

// Notifier posts and withdraws OS notifications. One per platform.
type Notifier interface {
	Post(n traystate.Notice) error
	Withdraw(key string) error
	// CanDeliver: a notification posted now would reach the person (a
	// notification server on the bus; a shortcut carrying the toast identity).
	// The core claims notifications from appd only for a tray that can, so a
	// tray that cannot leaves Telegram in charge.
	CanDeliver() bool
}
