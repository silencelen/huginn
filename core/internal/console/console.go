// Package console is `huginn console`: the interactive terminal for huginn
// core. Bare `huginn` opens it in an interactive terminal; every `huginn <verb>`
// keeps working as before (docs/CONSOLE.md).
package console

import (
	"context"
	"fmt"

	tea "github.com/charmbracelet/bubbletea"
)

// Run opens the console and returns when the person quits.
func Run(o Options) error {
	if o.Home == "" {
		o.Home = homeDir()
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p := tea.NewProgram(newModel(ctx, o), tea.WithAltScreen())
	if _, err := p.Run(); err != nil {
		return fmt.Errorf("console: %w", err)
	}
	return nil
}
