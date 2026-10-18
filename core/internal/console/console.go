// Package console is `huginn console`: the interactive terminal for huginn
// core. Bare `huginn` opens it in an interactive terminal; every `huginn <verb>`
// keeps working as before (docs/design/console.md).
package console

import (
	"context"
	"fmt"

	tea "github.com/charmbracelet/bubbletea"
	"github.com/charmbracelet/lipgloss"
)

// Run opens the console and returns when the person quits.
func Run(o Options) error {
	if o.Home == "" {
		o.Home = homeDir()
	}
	// Ask the terminal for its background now, before the program owns the
	// terminal and would swallow the answer: bubbletea's own init did this for
	// every command, so huginn's copy leaves it to the console
	// (third_party/bubbletea/HUGINN-PATCH.md).
	_ = lipgloss.HasDarkBackground()
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()
	p := tea.NewProgram(newModel(ctx, o), tea.WithAltScreen())
	if _, err := p.Run(); err != nil {
		return fmt.Errorf("console: %w", err)
	}
	return nil
}
