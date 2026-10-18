// Package traystate is the contract between the core (`huginn core run`, which
// watches appd and decides) and the tray role (`huginn core tray`, which shows):
// two event kinds on /core/events. Types only, so both sides agree by
// construction (docs/CORE-ARCHITECTURE.md §3.1).
package traystate

// Event names on /core/events.
const (
	EventTray   = "tray"   // a Summary: what the icon and menu show now
	EventNotice = "notice" // a Notice: post or withdraw one OS notification
	// EventCoreTray is {"attached": bool}: whether a tray role is attached. The
	// desktop GUI stands its own tray and notifier down while it is true.
	EventCoreTray = "coretray"
	// EventGUI is a GUI: whether the desktop app is attached, and its "Close
	// to tray" setting. The tray then carries the app's items (owner rule
	// 2026-10-17: one icon, one menu).
	EventGUI = "coregui"
	// EventGUICmd is a GUICmd for the desktop app, sent by the tray through
	// POST /core/gui. Never retained: a stored "quit" would close every app
	// that connects later.
	EventGUICmd = "gui.cmd"
	// EventTrayCmd is {"cmd":"hide"} for the tray, sent by the desktop app's
	// Settings switch through POST /core/tray. Never retained, for the same
	// reason as EventGUICmd.
	EventTrayCmd = "tray.cmd"
)

// GUI is the desktop app as the tray needs to know it.
type GUI struct {
	Attached    bool `json:"attached"`
	CloseToTray bool `json:"closeToTray"`
}

// GUI commands.
const (
	CmdOpen        = "open"        // bring the window up; URL, when set, is a huginn:// target
	CmdQuit        = "quit"        // quit the app (huginn core keeps running)
	CmdCloseToTray = "closeToTray" // set "Close to tray" to Value
)

// GUICmd is one command for the desktop app.
type GUICmd struct {
	Cmd   string `json:"cmd"`
	Value *bool  `json:"value,omitempty"`
	URL   string `json:"url,omitempty"`
}

// States of the icon, the brand's state ladder (BRAND.md §2).
const (
	Idle      = "idle"
	Working   = "working"
	Attention = "attention"
)

// Summary is everything the tray shows, decided by the core.
type Summary struct {
	State     string   `json:"state"`
	Tooltip   string   `json:"tooltip"`
	Attention []string `json:"attention"` // sessions waiting on an answer, capped for a menu
	Working   int      `json:"working"`
	Connected bool     `json:"connected"` // false: the core cannot reach huginn right now
}

// Notice is one OS notification to post or take down.
type Notice struct {
	Action string `json:"action"` // "post" | "withdraw"
	Key    string `json:"key"`    // stable per thing (a session, a chat): a re-post replaces, a withdraw removes
	Title  string `json:"title,omitempty"`
	Body   string `json:"body,omitempty"`
	Urgent bool   `json:"urgent,omitempty"` // "needs you": sound, stays up
	URL    string `json:"url,omitempty"`    // huginn://open?... opened on click
}

// MenuCap is how many "needs you" rows the menu carries before it stops
// being a menu (the desktop's TrayModel.MENU_ATTENTION_CAP).
const MenuCap = 6
