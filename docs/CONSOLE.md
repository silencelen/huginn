# huginn console: the interactive terminal for huginn core (design)

Status: **MVP built on main (2026-10-15), unreleased; targets CLI/core 2.3.0.** Owner decisions (2026-10-15):
Q1 "console" · Q3 bare `huginn` opens it when interactive (HUGINN_CONSOLE=0 opts out) · Q6 Chats in the
parity release as plain text · harness before the setup polish. Defaults taken: Q2 Bubble Tea + Lip Gloss
+ Bubbles (phase 0 measured +1.3–1.5 MB per platform, under the +4 MB bar) · Q4 attach ported to Go
(`internal/console/attach.go`) · Q7 `/appd/*` proxy built (`internal/daemon/proxy.go`).
Built: Home, Sessions (attach/return, new, send, end, rename, kill), Devices, Usage, Core, Help, the
core-down gate; goldens at 60×18/80×24/100×30, a fits-the-terminal test at four sizes, client routing
tests (mutation-checked), and `core/test/e2e.sh` drives the console against a real core and appd.
Not yet: Chats, Projects, Rounds, Archive, Settings, Scratchpads, Apps (the parity release), the
`--plain`, and a Windows hardware run. Since built: the `:` palette, the `/` filter, the parity
screens, and PowerShell routing tests. **Decided against:** reserving `console` in appd. appd's
RESERVED set does not just refuse a name, it drops such sessions from the registry (mergeLive,
restorePlan), and the CLI already resolves verb/name collisions with `huginn attach <name>` (as for
update, list, solo); the palette reaches a session called `console` the same way.
Owner request: "huginn cli harness … launched with the command `huginn` … ascii art and menus to navigate
huginn's extensive features … the current `huginn *` commands remain functional."

> **Name.** `docs/HARNESS.md` already exists and means *the Claude Code harness* (how appd drives `claude`).
> Calling this feature "the harness" would give two unrelated documents one name. This doc uses
> **"the console"** (the verb is `huginn console`, and bare `huginn` opens it). See open question Q1.

---

## 1. Goals and non-goals

**Goals**
- Bare `huginn` in an interactive terminal opens a full-screen, keyboard-driven console. It has the raven
  art, a home screen showing what needs you, and menus for everything the desktop app does that makes
  sense in a terminal: sessions, chats, projects, rounds, devices, usage/headroom, archive, scratchpads,
  apps, settings, and the core itself.
- **Every existing `huginn <verb>` keeps working, byte for byte.** Scripts, pipes, `huginn -p`, `huginn
  <session>` and Termux muscle memory don't change. The console is an addition, never a replacement.
- Entering a session hands the real terminal to `ssh -tt … cc <name>` (the attach path today). Detaching
  returns you to the console, on the screen you came from.
- It's the same product as the desktop. It uses the same API, words, states (working · needs you ·
  waiting · shell · ok · error · info) and palette (moss/heather, the ANSI-16 slot pins), plus the brand's
  terminal art files. No emoji.
- It teaches the CLI. Every screen's footer shows the one-shot command that does the same thing
  (`same as: huginn devices`), so the console stays a door into the verbs, not a parallel world.

**Non-goals (v1)**
- Re-implementing Claude Code's TUI. A live session is ALWAYS the real tmux pane via ssh, never a
  re-rendering of `/screen` in the console. The pane is the source of truth, and scraping it is
  already the most fragile part of huginn (docs/HARNESS.md).
- Host administration that the apps also gate behind confirmation flows (sign the host out, rotate
  accounts). Those can come later, behind the same confirmations.
- A mouse-first UI. Mouse clicks and scrolls work if the library gives them free, but nothing depends
  on them.
- Replacing the desktop app. The console is for people who live in a terminal, and for machines without a
  display.

---

## 2. Where it lives: inside the Go core binary

**Decision (recommended): a new package `core/internal/console` in the `huginn` core binary**, built on
**Bubble Tea + Lip Gloss + Bubbles** (charmbracelet, all MIT; compatible with the repo's MIT license).

Why the core and not the shell clients:
- The core is the product and includes the CLI (owner rule 2026-10-15). It already owns `huginn` on
  PATH, holds the appd token (store / file secrets), proves routes (TLS pin-by-proof), follows the appd
  watch stream (`internal/daemon/watch.go`), and runs on Linux, Windows and Android/Termux (arm64 build).
- Writing a full-screen UI twice, in bash and in PowerShell 5.1, would be the drift problem the
  2.2.0 work just removed. One Go implementation, one test suite.
- Bubble Tea handles the parts that are hard to hand-roll: alt-screen, raw mode, resize, Windows VT
  enabling (Windows 10 1809+ conhost and Windows Terminal), key decoding, and **`tea.ExecProcess`**. That
  last one suspends the UI, runs a child with the real TTY (our `ssh -tt`), and resumes, which is
  exactly the attach-and-return behaviour we need.

Cost:
- About 2–3 MB on an ~8 MB binary (bubbletea + lipgloss + bubbles + their deps: x/ansi, termenv,
  reflow, uniseg). It's acceptable, but **measure it in phase 0**. If it lands above +4 MB, drop
  `bubbles` and use only our own list and viewport components.
- New third-party deps in a binary that is Authenticode-signed and auto-updated. Pin exact versions,
  vendor-check licenses in `core/build.sh`, and add them to the Dependabot scope.
- `glamour` (markdown rendering for chat answers) is NOT in v1: +3 MB and a big dependency tree. Chat answers
  render as wrapped plain text with code fences shown as indented wells. Reconsider in phase 3.

Rejected: tview (heavier; widget-tree model fights our "screens + footer" design), raw x/term + ANSI
(we'd rewrite half of Bubble Tea, worst of all on Windows), and a Node TUI in `client/` (Node is
optional on clients today).

---

## 3. Entry and routing (nothing users rely on breaks)

**Today:** bare `huginn` in BOTH clients means *attach to `main`*.
- bash: `huginn() { … case "$1" in "") _huginn_attach "$H"` (client/huginn.sh).
- PowerShell: the same, via `_Huginn-Attach`.
- The core binary with no args forwards to the client (`forwardToClient(nil)` → attach main).

**New rule:** *bare `huginn` opens the console only when it is interactive and a core with the console is
installed. Everything else behaves exactly as today.*

| Invocation | Today | New |
|---|---|---|
| `huginn` (stdin AND stdout are TTYs, core ≥ console version) | attach `main` | **opens the console** |
| `huginn` piped, redirected, in a script, `TERM=dumb`, or `HUGINN_CONSOLE=0` | attach `main` | attach `main` (unchanged) |
| `huginn` on a machine with no core (macOS, a Termux without the core binary, an old core) | attach `main` | attach `main` (unchanged) |
| `huginn console` | (unknown verb → treated as a session name!) | opens the console (needs a TTY; otherwise prints why) |
| `huginn main`, `huginn attach [name]`, `huginn <session>` | attach | attach (unchanged) |
| every other `huginn <verb>` | unchanged | unchanged |

⚠ **`console` is a new verb.** Today any unknown word is a session name, so a session literally called
`console` is no longer reached by `huginn console`; `huginn attach console` (and the palette) reach it,
as for every other verb. Not added to appd's RESERVED set (see the status note above). (The earlier `update/solo/list/uninstall` session-name collision
taught us this; see scripts/test-client.sh §"the daemon makes sessions called update…".)

**Wiring:**
- **Core binary** (`cmd/huginn/main.go`): `case "console":` → `console.Run()`. With no args: if
  `isInteractive()` (x/term `IsTerminal` on fd 0 and 1, `TERM != dumb`, `HUGINN_CONSOLE != 0`) →
  `console.Run()`; else the current `forwardToClient(nil)`.
- **bash client**, `"")` branch: if `[ -t 0 ] && [ -t 1 ] && [ "${HUGINN_CONSOLE:-1}" != 0 ]` and
  `core="$(_huginn_core_bin)"` and `"$core" console --probe` exits 0 → `"$core" console`; else
  `_huginn_attach "$H"`. `--probe` is a fast capability check (exit 0 = this core has the console), so
  a 2.2.0 core without it falls back cleanly instead of printing its usage.
- **PowerShell client**: the same, with `[Console]::IsInputRedirected` / `IsOutputRedirected` and
  `_Huginn-CoreBin`.
- **Loop guard:** the console never forwards to the client for its own screens. When it needs a client-only
  verb (desktop install, local tier), it runs `forwardToClient` with `HUGINN_VIA_CORE=1` as today.
- **Opt-out for muscle memory:** `HUGINN_CONSOLE=0` in the profile, or a console setting "bare `huginn`
  opens: console / attach main" (stored in core.json, read by the clients through `huginn core get
  console.default`, cached). Q3 asks whether the default flips.

---

## 4. Data sources: three backends, one model

The console speaks to three things. Each screen declares which it needs, and degrades with a clear
sentence (never a blank screen) when one is missing.

| Backend | What it needs | Gives | Present on |
|---|---|---|---|
| **core socket** (`/core/*`) | a running core (same rule as the desktop's `CoreGate`) | status, device (on/off/scope/root/claude path/act-while-locked), tray summary, notify claim, events SSE, update/restart | Linux, Windows |
| **appd via the core** (`/appd/*` proxy, D12, *to build*: phase 1) | the core's token and proven route | the whole appd API (table below) | Linux, Windows |
| **ssh to `Host huginn`** | the SSH stanza and an authorized key | **attach** (`ssh -tt … cc <name>`), the verbs that exist only on the host side (`-p`, `-y`, `usage` via ccusage) | everywhere a client works, including macOS and Termux |

**Why the proxy and not a second token reader:** the core already owns the token, the route book, the
pins and the challenge. D12 says the GUI reaches appd through the core. The console is the second GUI,
which is the moment to build `/appd/*` (pass-through, SSE unbuffered, only to the core's own proven
routes). The desktop moves onto it later (planned in §13). Don't read `secrets/appd-token` from the
console process. It's the same user, so it would work, but it would fork the trust code.

**Termux / macOS / no core:** the console isn't available (bare `huginn` = attach `main`, as today).
Phase 4 can add an **ssh-only console** for those platforms: sessions list, attach, kill, rename, all
over the existing ssh verbs. Q5 asks whether that's wanted.

### appd routes per screen (from mobile/README.md § API, appd 3.12)

| Screen | Reads | Actions |
|---|---|---|
| Home | `/v1/status`, `/v1/sessions?preview=1`, `/v1/chats`, `/v1/headroom`, `/v1/devices`, `/core/status`, `/core/events` (tray summary) | jump anywhere |
| Sessions | `/v1/sessions?preview=1`, `/v1/sessions/<n>/typing`, `…/overview` | attach (ssh), new (`POST /v1/sessions`), rename, kill (`DELETE`), soft-end, compact, archive, send a line (`…/keys` + Enter), answer a numbered prompt (`…/answer`), undo a ladder move (`…/headroom/undo`), meta (goals/notes/autoResume) |
| Session detail | `…/transcript?offset=` (tail), `…/agents`, `…/graph` (later) | as above |
| Chats | `/v1/chats`, `/v1/chats/<id>`, `…/transcript` | new ask/act (`POST /v1/chats {mode}`), send + stream (`…/messages?stream=1` SSE), reattach (`…/stream?since=`), cancel, retitle (`PATCH`), delete |
| Projects | `/v1/projects`, `…/<id>/dashboard` | new (`POST`), spawn (approve + manifestRev), message member, adopt/drop member, end graceful/now |
| Rounds | `/v1/rounds`, `/v1/rounds/<id>` | new (`POST`, schedule builder), edit (`PATCH`), delete, polish a prompt/goal (`/v1/rounds/polish`, shown as a proposal to accept) |
| Archive | `/v1/archive`, `…/<id>/transcript` | revive (`…/revive` → attach), retitle, forget |
| Devices | `/v1/devices`, `/core/device` | this machine: on/off/scope/root/claude path/act-while-locked (`PATCH /core/device`). Others: unenrol (`DELETE /v1/devices/<id>`, confirm) |
| Usage | `/v1/headroom` (⚠ ms timestamps), `/v1/plan`, `/v1/usage`, `/v1/accounts?plan=1` | headroom settings (`PATCH /v1/headroom/settings`), activate account (confirm), refresh token |
| Scratchpads | `/v1/scratchpads` | view; edit opens `$EDITOR` on a temp file, saves with `rev` (409 → adopt and show diff) |
| Apps | `/v1/apps` | probe; open URL (print / xdg-open / start) |
| Settings | `/core/status`, core.json, `/v1/alerts`, `/v1/quick-actions` (via `/v1/status`) | core: tray autostart, bare-`huginn` default, secrets backend (read-only), pairing (re-pair = `huginn core pair`); host: alerts mode. Desktop/local tier: links out to `huginn desktop …` / `huginn local …` |
| Core | `/core/status`, service status | update (`huginn core update` in a child, output streamed in a pane), restart, start, set up (`core install`; on Windows the UAC prompt) |
| Help | static | keys, glossary of the seven state words, links |

**Refresh model:** one `GET /appd/v1/watch?stream=1` SSE (25 s keepalive) drives everything. Each change
signal refetches only the lists on screen (the digest says which). `/core/events` gives the tray
summary (attention count) and device changes. No polling loops. When a stream drops, reconnect with the
same backoff the desktop uses (3 s) and show `reconnecting…` in the status line. The data never blanks.

---

## 5. Screen map

```
huginn console
├── Home ─────────── art + "what needs you" + counts per section
├── 1 Sessions ───── list ▸ detail (tail of transcript, typing queue) ▸ [Enter] attach via ssh ▸ return
├── 2 Chats ──────── list ▸ conversation (streamed answer) ▸ composer (one line, Alt-Enter for newline)
├── 3 Projects ───── list ▸ dashboard (members, state, needs-you, spend) ▸ member ▸ attach
├── 4 Rounds ─────── list ▸ round (schedule, goal, last runs) ▸ edit form
├── 5 Devices ────── this machine (core device) + the fleet
├── 6 Usage ──────── accounts × three windows, held[], STOP sentinels, arbiter's last decision
├── 7 Archive ────── list ▸ transcript ▸ revive
├── 8 Settings ───── core · this machine · host alerts · bare-huginn default
├── 9 Core ───────── status, version, update, restart, tray, service
│   Scratchpads, Apps (reachable from the palette and from Home)
└── ? Help
```

### Home (80 × 24)

```
 ╭──────────────────────────────────────────────────────────────────────────╮
 │   ▄█▀█▄▄           huginn                                                 │
 │  ██████▀           thought, on call from anywhere                         │
 │  ██████            (o> core 2.3.0 · host huginn · 3 devices online         │
 │ ▄█████▀                                                                   │
 │ ████▀▀                                                                    │
 ├──────────────────────────────────────────────────────────────────────────┤
 │  needs you                                                                │
 │   ▲ widgetshub-lead   asking: "Ship 0.8.0 to the Fold?"     2 min         │
 │   ▲ chat  Kalshi sizing  answered, unread                   9 min         │
 │                                                                           │
 │  1 sessions   12  ● 3 working  ▲ 1 needs you  ○ 8 waiting                 │
 │  2 chats       7                                                          │
 │  3 projects    2  ● widgetshub  ○ osid                                    │
 │  4 rounds      5  next: briefing in 41 min                                │
 │  5 devices     3  ● stigo  ● PRESTIGE  ○ brokkr                           │
 │  6 usage          5h 38% · week 61% · Opus                                │
 ├──────────────────────────────────────────────────────────────────────────┤
 │  ↑↓ move  ⏎ open  1-9 jump  : command  / find  ? help  q quit             │
 ╰──────────────────────────────────────────────────────────────────────────╯
```

- Art: `round5/raven-5row.txt` (the perch's bird, 9 × 10 cells) beside the hairline wordmark when the
  width allows. Under 60 columns: the one-row `(o>` only. Under 34: the narrow mark
  `wordmark/word-06-pagga`. Use the brand files, never redraw (BRAND.md §1). 7-bit twins (`-ascii.txt`)
  when Unicode is unsafe (§9).
- **Brand state marks, never colour alone:** `●` working, `▲` needs you, `○` waiting, `■` ok, `×` error
  (7-bit `* ! o + x`). Colour: moss for working, gold `brightYellow` for needs-you, `brightRed` error only,
  heather `brightMagenta` for headings in 16-colour mode, onSurfaceVariant for timestamps.
- **No left accent bars** on rows or cards (owner taste). Selection is a tier-1 background
  (`surfaceContainerLow` #1C1F1D in truecolor, reverse video in 16-colour/NO_COLOR) plus a `›` cursor.
  The needs-you row carries only its `▲` glyph and the word. Nothing loud except needs-you.
- Motion: the wingbeat spinner (`anim-ascii/anim-01-spinner-1line`) only while connecting, and the
  landing/takeoff frames (`anim-02-flap-3line`) on attach/return, played once, ≤ 1 s. A reduced-motion
  setting and `HUGINN_REDUCED_MOTION=1` show the resting frame. No looping animation at idle.

### Sessions: the attach round trip

1. List rows: state glyph · name · title/preview · model · age. Project sessions sit under their project
   (owner rule 2026-09-19), and the list shows "N more in projects".
2. `⏎` on a row opens the **detail**: last ~30 transcript events (`…/transcript?offset=` tail), what is
   queued (`…/typing`), and a one-line composer for a quick send. Actions: `a` attach, `s` send, `e` end
   gracefully (soft-end, with the 409 explained), `k` kill (typed confirmation of the name), `r` rename,
   `x` archive, `u` undo the ladder move.
3. `a` (or `⏎` again) → **attach**. Bubble Tea `tea.ExecProcess(exec.Command("ssh", "-tt", …, "cc",
   name))`, the same argv `_huginn_attach` builds today, *including its reconnect loop and its
   "failing immediately" give-up*. Port that loop into Go as `attach.Run` (or call the client's verb via
   `forwardToClient(["attach", name])`, Q4), and the terminal title push/pop.
4. When ssh exits (detach `Ctrl-b d`, the session ends, or the link drops and the user stops the
   reconnect), Bubble Tea restores the alt screen. The console refetches and shows the same list with
   the cursor on the same session.
5. No `Host huginn` stanza (a desktop-only machine): `a` says *"attaching needs SSH to the host. Set it
   up with `huginn setup ssh`"* (a verb to add: generate a key, show it, write the stanza; today get.sh
   does this inline). Everything else on the screen works through the core.

### Chats

The list, then the conversation with a composer at the bottom. Sending posts to
`/appd/v1/chats/<id>/messages?stream=1` and renders the SSE events (`delta` appends, `tool_start`/`tool`
show as one dim line each, `result`/`done` close the turn, `error` in coral). `Esc` while streaming
leaves the stream running and goes back (reattach with `…/stream?since=`); `Ctrl-x` cancels. New chat
asks ask/act (act is labelled with what it can do, the same wording as the desktop's picker).

### Settings

A single scrolling list of rows, grouped:
- **this machine:** device on/off, scope, root, claude path, act while locked, bare `huginn` opens;
- **core:** tray at login, version, update channel, secrets backend (read-only);
- **host:** alerts mode, headroom thresholds;
- **links:** desktop app, local AI tier.

Same row model as the desktop's SettingsCatalog (title, value, summary), so the wording is shared. Every
change goes through the core/appd and shows the server's answer, never an optimistic local value.

---

## 6. Keys

| Key | Everywhere |
|---|---|
| `↑↓` / `j k`, `PgUp PgDn`, `g g` / `G` | move, page, top/bottom |
| `⏎` | open / primary action |
| `Esc` / `Backspace` / `h` | back (from Home: nothing; `q` quits) |
| `1`–`9` | jump to a section from anywhere (not while a text field has focus) |
| `/` | filter the current list (fuzzy, by name/title) |
| `:` | **command palette**: type any `huginn` verb (`:devices`, `:attach main`, `:core update`), and screens and sessions by name. This is the bridge to the CLI. |
| `?` | help overlay for the current screen |
| `r` | refresh now |
| `q`, `Ctrl-c` | quit (`Ctrl-c` while streaming a chat cancels the *view*, not the run) |
| `Ctrl-l` | redraw |

The per-screen action keys are listed in each screen's footer. **Destructive actions** (kill, unenrol, delete a
round, forget an archive, activate an account) always open a confirmation that names the thing and its
blast radius. Kill and unenrol require typing the name, matching the desktop's dialogs.

---

## 7. Architecture

```
core/internal/console/
  console.go        Run(opts): picks backends, builds the root model, tea.NewProgram(alt-screen)
  model.go          root model: router (stack of screens), status line, toasts, palette
  theme/            tokens from assets/brand (generated, like Theme.kt), capability detection (§9)
  art/              embedded brand art (go:embed of the brand .txt files; asciicheck'ed in CI)
  backend/
    core.go         /core/* over the socket (reuses ipc.Client)
    appd.go         typed client over the core's /appd/* proxy (generated request/response structs,
                    shared field names with mobile/README.md; ms-vs-seconds fields named explicitly)
    events.go       SSE readers: /core/events, /appd/v1/watch?stream=1, chat streams
    ssh.go          attach + host-side verbs; resolves `Host huginn` from ~/.ssh/config
  screens/          home, sessions, session, chats, chat, projects, project, rounds, round,
                    devices, usage, archive, scratchpads, apps, settings, corepage, help
  widgets/          list (virtualised), table, form, confirm, composer, viewport, palette
core/internal/daemon/proxy.go     /appd/* pass-through (D12), phase 1
core/internal/attach/             the reconnect loop ported from _huginn_attach (if Q4 = port)
```

- **State:** one store per backend, held by the root model (Elm style: messages in, new model out).
  Screens are pure views over the store plus local cursor/filter state, so a refetch never moves the
  cursor (rows are keyed by id/name).
- **Concurrency:** backend goroutines turn SSE events into `tea.Msg`. Fetches run as `tea.Cmd`, and
  each fetch carries a generation number so a late answer can't overwrite a newer one.
- **The core-gate rule applies here too:** if the core socket doesn't answer, the console shows one screen,
  *"huginn core is not running"*, with `s` start / `i` set up (the same two actions as the desktop's
  `CoreGate`). Attach via ssh still works from that screen, because it doesn't need the core.
- **No new trust code:** the token never enters the console process. TLS, pins and routes stay in the core.
- **Logging:** `~/.local/state/huginn/console.log` (Windows: `%LOCALAPPDATA%\huginn\state`), rotated,
  never containing message text or tokens.

---

## 8. Small things that make it feel professional

- **Remembers where you were:** the last screen and selection persist in the State dir. `huginn` opens
  on Home, but `Esc` from Home offers "back to <last screen>" for a second.
- **Wide and narrow layouts:** ≥ 120 columns shows list | detail side by side (like the desktop's
  split). Under that, a stacked layout. The minimum supported size is **60 × 18**. Under it, the console
  prints one sentence ("make the window at least 60 × 18, or use `huginn ls`") and exits 0.
- **Empty states with a next step:** "no rounds yet. `n` creates one, or `huginn rounds new`".
- **Errors as sentences:** the server's own error text, plus what to do. Never a stack trace and never
  a bare HTTP code.
- **Equivalent command in every footer:** `same as: huginn projects show widgetshub`.

---

## 9. Terminals and platforms

| Concern | Plan |
|---|---|
| **Windows Terminal** | Full: truecolor, Unicode art, mouse. Detected by `WT_SESSION`. |
| **conhost (PowerShell 5.1 window)** | Bubble Tea enables VT mode (Win10 1809+). Default to the **7-bit art** and 16-colour palette unless `WT_SESSION`/`TERM_PROGRAM` says otherwise: conhost's default fonts break half-blocks and braille. Ctrl-c works with the console mode Bubble Tea sets. The attach child is the Windows OpenSSH `ssh.exe` (already a client requirement), with the same `-tt`. |
| **Termux (Android)** | The core arm64 binary exists, but the core service doesn't run there (D15). Phase 4 ssh-only console (Q5). The touch keyboard has no Esc: show an on-screen `‹ back` row and accept `Backspace`; the Termux extra-keys row already has Esc/arrows. |
| **macOS** | No core build today (build.sh: linux, windows, android). Bare `huginn` stays attach until a darwin build is added (an easy add to `TARGETS`; signing/notarization is the real cost). |
| **tmux inside tmux** | Attaching from a console already running inside tmux gives nested tmux. That works today. The console warns once ("you are inside tmux; detach is Ctrl-b Ctrl-b d") and never refuses. |
| **Colour** | Truecolor when `COLORTERM=truecolor/24bit`. Otherwise ANSI-16 using the brand slot pins (brightRed error, brightCyan success, brightYellow needs-you, brightMagenta info/headings). `NO_COLOR` set → no colour at all; states still read by glyph and word. |
| **Unicode** | `HUGINN_ASCII=1`, or a non-UTF-8 locale (`LANG/LC_ALL` without UTF-8 on Unix; code page ≠ 65001 on Windows), → 7-bit glyphs `* ! o + x` and the `-ascii.txt` art. |
| **Accessibility** | Screen readers and full-screen TUIs mix poorly. Provide **`huginn console --plain`**: no alt-screen, line-oriented, every list printed as text with numbers, actions taken by typing a number or verb. Also: no information carried by colour alone, reduced motion, and a promise that every screen has a non-interactive `huginn <verb>` equivalent. |
| **Resize** | Bubble Tea `WindowSizeMsg`. Layouts recompute; virtualised lists keep the selected row visible. |
| **Slow links** | Every fetch has a timeout and a visible "loading" row, stale data stays on screen with its age, and the attach path keeps the existing `ConnectTimeout=10`, `ServerAliveInterval=15`. |

---

## 10. Testing

- **Golden snapshots:** Bubble Tea's `teatest` drives the model with key messages and captures final
  frames. Goldens are recorded per screen at **80×24, 120×40 and 60×18**, in three render modes (truecolor,
  16-colour, NO_COLOR+ASCII). That's 3 sizes × 3 modes per screen. ANSI is stripped in the NO_COLOR goldens
  so diffs read as text.
- **Fake appd:** a Go `httptest` server replaying fixtures captured from the real appd (shapes from
  mobile/README.md; ms-vs-seconds fields included on purpose). It drives every screen's empty, normal,
  error and 409 states. The fixtures live in `core/internal/console/testdata/`, and a test asserts they still parse
  against the live appd schema (run against `server/appd` in CI, like `device-policy-cases.test.js`).
- **Real-appd E2E:** extend `core/test/e2e.sh` (it already starts a real appd, a core and a fake claude).
  Run the console headless with a scripted key sequence (`teatest` with a real backend), plus open a
  chat, send, see the streamed answer, and attach to a session with ssh replaced by a stub that prints
  and exits 0, asserting the console comes back.
- **Routing tests in `scripts/test-client.sh`:** bare `huginn` with a TTY and a stub core that answers
  `console --probe` → the core is called; with stdout redirected → `_huginn_attach`; with
  `HUGINN_CONSOLE=0` → attach; with an old core (probe exits 2) → attach. The same four for the
  PowerShell client under pwsh.
- **Reserved word:** `console` is refused as a new session name by appd and both clients, and an existing
  session named `console` is still reachable with `huginn attach console`.
- **Mutation check on the gate:** make the TTY check always true and confirm the redirected-stdout test
  FAILS (the house rule: verify tests by making them fail).

---

## 11. Phased plan

| Phase | Ships | Rough size |
|---|---|---|
| **0 Spike** (½ day) | Bubble Tea in the core: measure binary size on linux/windows/android, check conhost + Windows Terminal + Termux rendering, prove `ExecProcess` ssh round trip on PRESTIGE and stigo. Go/no-go on the library. | 300 lines, throwaway |
| **1 Foundation** | `/appd/*` proxy in the core (D12) + tests; `console` package skeleton (router, theme, art, status line, palette, help); routing in core + both clients + reserved word; core-gate screen. | ~1.5k Go + 0.3k client/tests |
| **2 MVP**: *"I can live in it"* | Home, Sessions (list, detail, attach/return, new, rename, kill, soft-end, send, answer), Devices (this machine + fleet), Usage (read-only), Core page (status, update, restart). Goldens + fake appd + E2E. | ~3k Go + ~1.5k tests |
| **3 Parity** | Chats with streaming, Projects dashboard + spawn/message, Rounds CRUD + polish, Archive + revive, Settings, Scratchpads (`$EDITOR`), Apps. | ~3.5k Go + ~2k tests |
| **4 Reach** | `--plain` mode, ssh-only console for Termux/macOS (Q5), darwin core build, remembered layout, mouse. | ~1.5k |

Total to parity is about **8–9k lines of Go plus 3–4k lines of tests**: roughly two to three focused weeks of
sessions. Phase 2 alone is the useful milestone (about a week). Ship it as CLI/core **2.3.0** with the
console behind bare `huginn` (Q3), and phase 3 as 2.4.0.

---

## 12. Open questions (crisp forks, recommended default first)

1. **Name.** (a) **"the console", verb `huginn console`** (recommended; no clash with docs/HARNESS.md);
   (b) "harness", which needs docs/HARNESS.md renamed to CLAUDE-HARNESS.md first; (c) "home".
2. **Library.** (a) **Bubble Tea + Lip Gloss + Bubbles, after the phase-0 size check** (recommended);
   (b) Bubble Tea without Bubbles (own widgets, smaller); (c) tview.
3. **Bare `huginn` default.** (a) **opens the console when interactive, with `HUGINN_CONSOLE=0` / a setting
   to keep attach-main** (recommended; this is what you asked for); (b) console only via `huginn console`
   for one release, then flip; (c) ask once on first run and remember.
4. **Attach implementation.** (a) **port `_huginn_attach`'s reconnect loop into Go (`internal/attach`)**,
   one implementation for both platforms, which later lets the clients delegate attach to the core too
   (recommended); (b) the console shells out to the client's `huginn attach <name>` (no port, but the
   bash/PowerShell copies stay the source of truth).
5. **Termux / macOS.** (a) **later (phase 4) ssh-only console** (recommended); (b) never: those keep the
   verbs only; (c) build darwin + Termux core service support first.
6. **Chats in v1.** (a) **phase 3, with plain-text rendering** (recommended); (b) in the MVP;
   (c) with glamour markdown (+3 MB).
7. **The `/appd/*` proxy.** (a) **build it now as phase 1** (D12; the desktop moves onto it later)
   (recommended); (b) the console reads the token file directly (faster to build, but forks the trust code).

---

## Appendix A: full uninstall → reinstall → re-enrol, as a user sees it today (2.2.0)

Written for the owner's planned run on each platform. The **rough edges** are what a public user would
trip on.

### Linux: per-user (portable desktop + core + CLI)

1. `huginn uninstall` (or `--all`): lists what goes, asks you to type `uninstall`, then removes, top
   down: the desktop app (portable tree, settings, cache, autostart), the local tier (if present), the device
   row (via the core), the core (user unit, tray autostart, binary, config, state) and the CLI with its
   `.bashrc` line. Then prints Removed / Left behind. A new shell has no `huginn`.
2. Reinstall with the one-liner: `curl -fsSL …/client/get.sh | sh -s -- <host>`. It fetches the client from
   the newest release, shows an SSH key to **authorize by hand on the host**, waits for Enter, then
   offers `huginn core install` (pairs over ssh, user unit + linger) and `huginn desktop install`.
3. Re-enrol: `huginn device on` (or the desktop's setup step). A new device row appears.

Rough edges:
- **Manual key authorization** (append to the host's `authorized_keys`) is the biggest hurdle for a public
  user, and the CLI (ssh key) and the app/core (bearer token) pair two different ways. An appd-minted
  short-lived **enrol code** (shown on an existing device, typed on the new one) would make both one step.
- `loginctl enable-linger` stays enabled after uninstall. Say so in "Left behind", or undo it if
  `core install` turned it on (record that in core.json).
- `get.sh` installs the client from the release and then `core install` re-lays the same client
  (2.2.0 self-install). Harmless, but get.sh could simply install the core and let it lay the CLI down.
- The fish handoff (Omarchy) means the CLI only appears via the core binary's forwarding. That's fine, but
  `get.sh` should also say "your shell is fish: `huginn` works through the core binary".
- After uninstall, the running shell still has the function (the message says so). Unavoidable.

### Linux: Debian `.deb` / Arch package (`/opt/huginn-desktop-kt`)

1. `huginn uninstall` asks sudo for `apt-get purge` / `pacman -R` when it has a terminal. Without one, it
   prints the command. Then the per-user core and CLI go as above.
2. Reinstall: `sudo apt install ./huginn-desktop-kt_*.deb` or `sudo pacman -U …pkg.tar.zst`. The first
   launch copies the bundled core into the per-user layout, and **the new 2.2.0 gate screen** asks to "Set up
   huginn core" (user unit; no prompt). Then first-run setup (address + token) pairs the app.

Rough edges:
- The package carries the core but not a way to install the CLI's SSH side. A package-only user has
  `huginn` (core + forwarded CLI), but session verbs fail until the SSH stanza exists, with an ssh error,
  not a sentence. Add a `huginn setup ssh` verb and have the forwarded client detect the missing
  stanza and say so.
- `apt remove` (no purge) keeps settings by Debian convention. `huginn uninstall` purges. Document the
  difference.

### Windows

1. `huginn uninstall` (PowerShell or cmd): runs the desktop's own uninstaller silently (`/S _?=`),
   retires the device, deletes the "Huginn Core" task (**UAC prompt** when needed), stops the tray, removes the
   PATH entry, schedules removal of `%LOCALAPPDATA%\huginn` (the running exe lives there), and removes
   `~\.huginn` and the profile line.
2. Reinstall: `irm …/client/get.ps1 | iex` (or `curl … get.sh | sh`), or the desktop
   installer `.exe`. The installer runs the bundled core's self-install. The gate screen asks to set it up
   (**one UAC prompt** to register the S4U task), then setup pairs.

Rough edges (most important for a public release):
- **Authenticode is chained to the private `jnet-ODIN-CA`.** Outside jnet.ad, SmartScreen and Defender
  treat the installer and `huginn.exe` as an *unknown publisher*. A public user sees the "Windows
  protected your PC" wall. That's the single biggest professional-polish gap. The fix is a
  publicly trusted signing certificate.
- **Two UAC prompts in the worst case** (uninstall task delete, reinstall task register). Both are
  explained in the gate/uninstall text, but the uninstall prompt appears mid-run. Show "Windows will ask
  for administrator approval to remove the boot task" before the confirmation, not after.
- The PowerShell profile line goes into the **5.1** profile (and PowerShell 7's if pwsh exists). A user who
  installs pwsh later gets no profile line, which is covered by the core binary's forwarding but invisible
  to them.
- The tray icon lands in the Win11 overflow `^` menu by design (decided 2026-10-15). The gate/first-run
  text should say "pin it from the ^ menu".
- `ssh.exe` must exist (Windows OpenSSH client, an optional feature on older builds). get.ps1 should check
  for it and say how to add it.

### Cross-platform polish for the reinstall story
- One **"what happened" summary** at the end of every install, like uninstall's Removed / Left behind:
  what was installed, where, what runs at boot, and the one next command.
- Every refusal names its fix (house style). Audit get.sh/get.ps1 for bare `exit 1` paths.
- The README overhaul (owner's later ask) should lead with: what huginn is (one paragraph + the hero art),
  the one-line install per platform, the "first five minutes" (pair, attach, phone), then links: SETUP, USAGE,
  FAQ, SECURITY, the console, uninstall.
