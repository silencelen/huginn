# The console: an interactive terminal for huginn core

**Status:** Implemented in huginn core 2.3.0. Home, Sessions, Chats, Projects, Rounds, Devices,
Usage, Archive, Settings, Core and Help are built, with the `:` palette and the `/` filter.
Some planned pieces are not built; see [Known limitations](#12-known-limitations). For current
behaviour, see the [console guide](../guide/console.md).

**Summary:** bare `huginn` in an interactive terminal opens a full-screen, keyboard-driven console
with the raven art, a home screen showing what needs you, and menus for what the desktop app does
that makes sense in a terminal. It lives inside the Go core binary, built on Bubble Tea, and reaches
the server through the core's `/appd/*` proxy, so the token never enters the console process.
Entering a session hands the real terminal to `ssh -tt`; detaching returns to the console. Every
existing `huginn <verb>` keeps working unchanged, and every screen names the one-shot command that
does the same thing.

> **Name.** "The harness" was considered and rejected: the Claude Code integration document
> ([docs/dev/claude-code-integration.md](../dev/claude-code-integration.md)) already uses that word
> for how the daemon drives `claude`. The feature is **the console**, the verb is `huginn console`,
> and bare `huginn` opens it.

## Decisions

| # | Question | Decision |
|---|---|---|
| Q1 | Name | **"the console"**, verb `huginn console`. Rejected: "harness" (clashes with the Claude Code integration document), "home". |
| Q2 | Library | **Bubble Tea, Lip Gloss and Bubbles.** The phase 0 spike measured +1.3 to 1.5 MB per platform, under the +4 MB bar set for dropping Bubbles. Rejected: Bubble Tea without Bubbles, tview. |
| Q3 | What bare `huginn` does | **Opens the console when interactive**, with `HUGINN_CONSOLE=0` to keep attaching to `main`. Rejected: console only via `huginn console` for one release; asking on first run. |
| Q4 | Attach implementation | **Ported to Go** (`core/internal/console/attach.go`), with the reconnect loop. Rejected: shelling out to the client's `huginn attach <name>`, which would keep the bash and PowerShell copies as the source of truth. |
| Q5 | Termux and macOS | **Later, as an ssh-only console.** Not built: on Termux the console is not available, and macOS is not supported. |
| Q6 | Chats in the first release | **In the parity release, rendered as plain text.** Rejected: markdown rendering with glamour (+3 MB). |
| Q7 | How the console reaches appd | **Through the core's `/appd/*` proxy** (`core/internal/daemon/proxy.go`). Rejected: reading the token file directly, which would fork the trust code. |
| Q8 | Reserve `console` as a session name in appd | **No.** appd's reserved set does not just refuse a name; it drops such sessions from the registry. The CLI already resolves verb and session-name collisions with `huginn attach <name>` (as for `update`, `list` and `solo`), and the palette reaches a session called `console` the same way. |

## 1. Goals and non-goals

**Goals**
- Bare `huginn` in an interactive terminal opens a full-screen, keyboard-driven console. It has the
  raven art, a home screen showing what needs you, and menus for everything the desktop app does
  that makes sense in a terminal: sessions, chats, projects, rounds, devices, usage and headroom,
  archive, settings and the core itself.
- **Every existing `huginn <verb>` keeps working, byte for byte.** Scripts, pipes, `huginn -p`,
  `huginn <session>` and Termux habits do not change. The console is an addition, never a
  replacement.
- Entering a session hands the real terminal to `ssh -tt ... cc <name>`, the same attach path the
  CLI uses. Detaching returns to the console, on the screen you came from.
- It is the same product as the desktop app: the same API, words, states (working · needs you ·
  waiting · shell · ok · error · info) and palette (moss and heather, with the ANSI-16 slot pins),
  plus the brand's terminal art. No emoji.
- It teaches the CLI. Every screen's footer shows the one-shot command that does the same thing
  (`same as: huginn devices`), so the console stays a door into the verbs, not a parallel world.

**Non-goals**
- Re-implementing Claude Code's TUI. A live session is always the real tmux pane over ssh, never a
  re-rendering of `/screen` in the console. The pane is the source of truth, and scraping it is
  already the most fragile part of Huginn.
- Host administration that the apps gate behind confirmation flows (signing the host out, rotating
  accounts). These can come later, behind the same confirmations.
- A mouse-first UI. Mouse clicks and scrolls may work, but nothing depends on them.
- Replacing the desktop app. The console is for people who live in a terminal, and for machines
  without a display.

## 2. Where it lives: inside the Go core binary

**Decision: a package `core/internal/console` in the `huginn` core binary**, built on **Bubble
Tea, Lip Gloss and Bubbles** (charmbracelet, all MIT, compatible with the repository's MIT license).

Why the core and not the shell clients:
- The core is the product and includes the CLI ([core.md](core.md), 2.2.0). It already owns
  `huginn` on PATH, holds the appd token, proves routes with TLS pin-by-proof, follows the appd
  watch stream, and builds for Linux, Windows and Android.
- Writing a full-screen UI twice, in bash and in PowerShell 5.1, would bring back the drift problem
  that 2.2.0 removed. One Go implementation, one test suite.
- Bubble Tea handles the parts that are hard to hand-roll: the alternate screen, raw mode, resize,
  enabling VT processing on Windows (Windows 10 1809+ conhost and Windows Terminal), key decoding,
  and **`tea.ExecProcess`**. That last one suspends the UI, runs a child with the real TTY (the
  `ssh -tt`), and resumes, which is exactly the attach-and-return behaviour the console needs.

Cost:
- The estimate was 2 to 3 MB on an ~8 MB binary. The phase 0 spike measured +1.3 to 1.5 MB per
  platform, under the +4 MB bar at which Bubbles would have been dropped.
- New third-party dependencies in a signed, auto-updated binary: pin exact versions and check their
  licenses in the build.
- `glamour` (markdown rendering for chat answers) is not included: +3 MB and a large dependency
  tree. Chat answers render as wrapped plain text, with code fences shown as indented wells.

Rejected: tview (heavier, and its widget-tree model fights the "screens plus footer" design), raw
`x/term` plus ANSI (it would rewrite half of Bubble Tea, worst of all on Windows), and a Node TUI in
`client/` (Node is optional on clients).

A later finding: Bubble Tea's package init queries the terminal (an OSC 11 background-colour request
and a cursor report) and puts it in raw mode for up to 5 s. Because the console is linked into every
`huginn` command, every command paid that cost, and a terminal that did not answer stalled and lost
type-ahead. Since 2.4.0 the core carries Bubble Tea without that init
(`core/third_party/bubbletea/HUGINN-PATCH.md`), and the console asks for the colour itself, just
before it starts.

## 3. Entry and routing

**Before the console,** bare `huginn` in both clients meant *attach to `main`*. The core binary with
no arguments forwarded to the client, which attached to `main`.

**The rule:** *bare `huginn` opens the console only when it is interactive and a core with the
console is installed. Everything else behaves as before.*

| Invocation | Before | With the console |
|---|---|---|
| `huginn` (stdin and stdout are TTYs, core with the console) | attach `main` | **opens the console** |
| `huginn` piped, redirected, in a script, `TERM=dumb`, or `HUGINN_CONSOLE=0` | attach `main` | attach `main` |
| `huginn` with no core (Termux, or a core too old to have the console) | attach `main` | attach `main` |
| `huginn console` | unknown verb, treated as a session name | opens the console (needs a TTY; otherwise prints why) |
| `huginn main`, `huginn attach [name]`, `huginn <session>` | attach | attach |
| every other `huginn <verb>` | unchanged | unchanged |

**`console` is a verb.** Any unknown word used to be a session name, so a session literally called
`console` is no longer reached by `huginn console`; `huginn attach console` and the palette reach
it, as for every other verb.

**Wiring:**
- **Core binary:** `huginn console` runs the console. With no arguments, if the process is
  interactive (a TTY on stdin and stdout, `TERM` not `dumb`, `HUGINN_CONSOLE` not `0`), it runs the
  console; otherwise it forwards to the client as before.
- **bash client**, bare `huginn`: if stdin and stdout are TTYs, `HUGINN_CONSOLE` is not `0`, a core
  binary is installed, and `<core> console --probe` exits 0, it runs `<core> console`; otherwise it
  attaches. `--probe` is a fast capability check (exit 0 means this core has the console), so a
  core without it falls back cleanly instead of printing its usage.
- **PowerShell client:** the same, using `[Console]::IsInputRedirected` and
  `[Console]::IsOutputRedirected`.
- **Loop guard:** the console never forwards to the client for its own screens. When it needs a
  client-only verb, it forwards with `HUGINN_VIA_CORE=1`, as the core does for every verb it does
  not own.

## 4. Data sources: three backends, one model

The console speaks to three things. Each screen declares which it needs and degrades with a clear
sentence, never a blank screen, when one is missing.

| Backend | Needs | Gives | Present on |
|---|---|---|---|
| **core socket** (`/core/*`) | a running core | status, device (on/off, scope, root, claude path, act-while-locked), tray summary, notify claim, events, restart | Linux, Windows |
| **appd through the core** (`/appd/*` proxy) | the core's token and proven route | the whole appd API (table below) | Linux, Windows |
| **ssh to `Host huginn`** | the SSH stanza and an authorized key | **attach** (`ssh -tt ... cc <name>`) and the verbs that exist only on the server side (`-p`, `-y`, `usage`) | everywhere a client works |

**Why the proxy and not a second token reader:** the core already owns the token, the route book,
the pins and the challenge, and the core design says the GUI reaches appd through the core. The
console was the second GUI, which made it the moment to build `/appd/*`: a pass-through with
unbuffered SSE, forwarding only to the core's own proven routes. The desktop app moved onto it in
2.5.1 ([desktop-via-core.md](desktop-via-core.md)). Reading the core's secrets file from the console
process would work, because it is the same user, but it would fork the trust code.

### appd routes per screen

| Screen | Reads | Actions |
|---|---|---|
| Home | `/v1/status`, `/v1/sessions?preview=1`, `/v1/chats`, `/v1/headroom`, `/v1/devices`, `/core/status`, `/core/events` (tray summary) | jump anywhere |
| Sessions | `/v1/sessions?preview=1`, `/v1/sessions/<n>/typing`, `.../overview` | attach (ssh), new (`POST /v1/sessions`), rename, kill (`DELETE`), soft-end, compact, archive, send a line (`.../keys` plus Enter), answer a numbered prompt (`.../answer`), undo a headroom move (`.../headroom/undo`), meta (goals, notes, auto-resume) |
| Session detail | `.../transcript?offset=` (tail), `.../agents` | as above |
| Chats | `/v1/chats`, `/v1/chats/<id>`, `.../transcript` | new ask or act (`POST /v1/chats {mode}`), send and stream (`.../messages?stream=1` SSE), reattach (`.../stream?since=`), cancel, retitle (`PATCH`), delete |
| Projects | `/v1/projects`, `.../<id>/dashboard` | new, spawn (approve plus manifest revision), message a member, adopt or drop a member, end gracefully or now |
| Rounds | `/v1/rounds`, `/v1/rounds/<id>` | new (with a schedule builder), edit (`PATCH`), delete, polish a prompt or goal (`/v1/rounds/polish`, shown as a proposal to accept) |
| Archive | `/v1/archive`, `.../<id>/transcript` | revive (then attach), retitle, forget |
| Devices | `/v1/devices`, `/core/device` | this machine: on/off, scope, root, claude path, act-while-locked (`PATCH /core/device`). Others: unenrol (`DELETE /v1/devices/<id>`, with confirmation) |
| Usage | `/v1/headroom` (timestamps in milliseconds), `/v1/plan`, `/v1/usage`, `/v1/accounts?plan=1` | headroom settings, activate an account (with confirmation), refresh a token |
| Settings | `/core/status`, `/v1/alerts`, `/v1/quick-actions` | core: tray at login, secrets backend (read-only), pairing; host: alerts mode. Desktop app and local AI link out to `huginn desktop` and `huginn local` |
| Core | `/core/status`, service status | update (`huginn core update` in a child, output streamed), restart, start, set up (`core install`; the UAC prompt on Windows) |
| Help | static | keys, the glossary of the seven state words |

The design also had Scratchpads (view, and edit through `$EDITOR` with a revision check) and Apps
(probe, open a URL) screens; they are not built.

**Refresh model:** one `GET /appd/v1/watch?stream=1` SSE stream (25 s keepalive) drives
everything. Each change signal refetches only the lists on screen. `/core/events` gives the tray
summary (attention count) and device changes. No polling loops. When a stream drops, the console
reconnects with the desktop's backoff and shows `reconnecting…` in the status line. The data never
blanks.

## 5. Screen map

```text
huginn console
+-- Home ----------- art + "what needs you" + counts per section
+-- 1 Sessions ----- list > detail (transcript tail, typing queue) > [Enter] attach via ssh > return
+-- 2 Chats -------- list > conversation (streamed answer) > composer
+-- 3 Projects ----- list > dashboard (members, state, needs-you, spend) > member > attach
+-- 4 Rounds ------- list > round (schedule, goal, last runs)
+-- 5 Devices ------ this machine (core device) + the others
+-- 6 Usage -------- accounts x three windows, holds, stop markers, the arbiter's last decision
+-- 7 Archive ------ list > transcript > revive
+-- 8 Settings ----- this terminal . this machine . core . host
+-- 9 Core --------- status, version, update, restart, tray, service
+-- ? Help
```

### Home (80 × 24)

```text
 ╭───────────────────────────────────────────────────────────────────────────╮
 │   ▄█▀█▄▄           huginn                                                 │
 │  ██████▀           thought, on call from anywhere                         │
 │  ██████            (o> core 2.3.0 · host huginn · 3 devices online        │
 │ ▄█████▀                                                                   │
 │ ████▀▀                                                                    │
 ├───────────────────────────────────────────────────────────────────────────┤
 │  needs you                                                                │
 │   ▲ docs-lead     asking: "Publish the 1.4 release notes?"   2 min        │
 │   ▲ chat  Retry policy review  answered, unread              9 min        │
 │                                                                           │
 │  1 sessions   12  ● 3 working  ▲ 1 needs you  ○ 8 waiting                 │
 │  2 chats       7                                                          │
 │  3 projects    2  ● docs  ○ api                                           │
 │  4 rounds      5  next: daily-summary in 41 min                           │
 │  5 devices     3  ● laptop  ● desktop  ○ buildbox                         │
 │  6 usage          5h 38% · week 61% · Opus                                │
 ├───────────────────────────────────────────────────────────────────────────┤
 │  ↑↓ move  ⏎ open  1-9 jump  : command  / find  ? help  q quit             │
 ╰───────────────────────────────────────────────────────────────────────────╯
```

- **Art:** the brand's raven beside the wordmark. The art is embedded from the brand files, never
  redrawn
  ([assets/brand/BRAND.md](../../assets/brand/BRAND.md)), and a test checks that the embedded copy
  matches. 7-bit twins are used when Unicode is unsafe (§9).
- **State marks, never colour alone:** `●` working, `▲` needs you, `○` waiting, `■` ok, `×` error
  (7-bit: `* ! o + x`). Colour: moss for working, gold (`brightYellow`) for needs you, `brightRed`
  for errors only, heather (`brightMagenta`) for headings in 16-colour mode, a dim tone for
  timestamps.
- **No left accent bars** on rows or cards (a house design rule). Selection is a tier-1 background
  in truecolor, reverse video in 16-colour or `NO_COLOR`, plus a `›` cursor. The needs-you row
  carries only its `▲` glyph and the word. Nothing is loud except needs you.

### Sessions: the attach round trip

1. List rows: state glyph, name, title or preview, model, age. Project sessions sit under their
   project, and the list shows "N more in projects".
2. `⏎` on a row opens the **detail**: the last ~30 transcript events, what is queued
   (`.../typing`), and a one-line composer for a quick send. Actions: attach, send, end gracefully
   (soft-end, with a 409 explained), kill (typed confirmation of the name), rename, archive.
3. Attach uses `tea.ExecProcess` with the same `ssh -tt ... cc <name>` command the CLI builds,
   **including its reconnect loop and its "failing immediately" give-up**, and the terminal title
   push and pop. The loop is ported to Go (Q4).
4. When ssh exits (detach with `Ctrl-b d`, the session ends, or the link drops and the user stops
   the reconnect), Bubble Tea restores the alternate screen. The console refetches and shows the
   same list with the cursor on the same session.
5. With no `Host huginn` stanza (a machine set up from the desktop installer), attach says that
   attaching needs SSH to the server and names `huginn setup ssh`, which writes the stanza.
   Everything else on the screen works through the core.

### Chats

The list, then the conversation with a composer at the bottom. Sending posts to
`/appd/v1/chats/<id>/messages?stream=1` and renders the SSE events: `delta` appends,
`tool_start`/`tool` show as one dim line each, `result`/`done` close the turn, and `error` is
shown as an error. Leaving while an answer streams leaves the run going; reopening reattaches with
`.../stream?since=`. A new chat asks for ask or act, with act labelled by what it can do, in the
same words as the desktop's picker.

### Settings

A single scrolling list of rows, grouped:
- **this terminal:** what bare `huginn` opens (`HUGINN_CONSOLE`), art and symbols
  (`HUGINN_ASCII`), colour (`NO_COLOR`);
- **this machine:** device on/off, scope, root, claude path, act while locked;
- **core:** tray at login, version, secrets backend (read-only);
- **host:** alerts mode, headroom thresholds;
- **links:** the desktop app, the local AI tier.

It uses the same row model as the desktop's settings (title, value, summary), so the wording is
shared. Every change goes through the core or appd and shows the server's answer, never an
optimistic local value.

## 6. Keys

| Key | Everywhere |
|---|---|
| `↑↓` / `j k`, `PgUp PgDn`, `g g` / `G` | move, page, top and bottom |
| `⏎` | open, or the primary action |
| `Esc` / `Backspace` / `h` | back (from Home, nothing; `q` quits) |
| `1`–`9` | jump to a section from anywhere (not while a text field has focus) |
| `/` | filter the current list by name or title |
| `:` | **command palette**: finds screens, sessions, chats, projects, rounds and actions; anything else typed there runs as `huginn <it>` (`:devices`, `:attach main`, `:core update`). This is the bridge to the CLI. |
| `?` | help for the current screen |
| `r` | refresh now |
| `q`, `Ctrl-c` | quit |
| `Ctrl-l` | redraw |

Each screen's footer lists its action keys. **Destructive actions** (kill, unenrol, delete a round,
forget an archived conversation, activate an account) always open a confirmation that names the
thing and its blast radius. Kill and unenrol require typing the name, matching the desktop's
dialogs.

## 7. Architecture

The planned package layout:

```text
core/internal/console/
  console.go        Run(opts): picks backends, builds the root model, starts the program (alt screen)
  model.go          root model: router (stack of screens), status line, toasts, palette
  theme             tokens from the brand, capability detection (§9)
  art               embedded brand art (go:embed of the brand .txt files)
  backend           /core/* over the socket; typed client over /appd/*; SSE readers; ssh attach
  screens           home, sessions, chats, projects, rounds, devices, usage, archive, settings, core, help
  attach.go         the reconnect loop ported from the shell client's attach
core/internal/daemon/proxy.go     the /appd/* pass-through
```

- **State:** one store per backend, held by the root model (Elm style: messages in, a new model
  out). Screens are pure views over the store plus local cursor and filter state, so a refetch never
  moves the cursor (rows are keyed by id or name).
- **Concurrency:** backend goroutines turn SSE events into messages. Fetches run as commands, and
  each fetch carries a generation number, so a late answer cannot overwrite a newer one.
- **The core gate applies here too:** if the core socket does not answer, the console shows one
  screen, *"huginn core is not running"*, with start and set-up actions, the same two as the
  desktop's gate. Attach over ssh still works from that screen, because it does not need the core.
- **No new trust code:** the token never enters the console process. TLS, pins and routes stay in
  the core.

## 8. Details

- **Minimum size:** 60 × 18. Below it, the console says how big the window needs to be and points
  at the verbs (`huginn help`).
- **Empty states name a next step:** "no rounds yet" plus the command that creates one.
- **Errors are sentences:** the server's own error text, plus what to do. Never a stack trace, and
  never a bare HTTP code.
- **The equivalent command in every footer:** `same as: huginn projects`.

## 9. Terminals and platforms

| Concern | Design |
|---|---|
| **Windows Terminal** | Full: truecolor, Unicode art. Detected by `WT_SESSION`. |
| **conhost (a PowerShell 5.1 window)** | Bubble Tea enables VT mode (Windows 10 1809+). Default to the **7-bit art** and the 16-colour palette unless `WT_SESSION` or `TERM_PROGRAM` says otherwise: conhost's default fonts break half-blocks and braille. The attach child is the Windows OpenSSH `ssh.exe`, which the client already requires, with the same `-tt`. |
| **Termux** | Huginn core does not run on Termux, so neither does the console; bare `huginn` attaches to `main`. An ssh-only console was planned (Q5) and is not built. |
| **macOS** | Not supported. |
| **tmux inside tmux** | Attaching from a console that is itself inside tmux gives nested tmux, which works; detach is `Ctrl-b Ctrl-b d`. |
| **Colour** | Truecolor when `COLORTERM` is `truecolor` or `24bit`. Otherwise ANSI-16 using the brand slot pins (`brightRed` error, `brightCyan` success, `brightYellow` needs you, `brightMagenta` info and headings). With `NO_COLOR` set, no colour at all; states still read by glyph and word. |
| **Unicode** | `HUGINN_ASCII=1`, or a non-UTF-8 locale (`LANG`/`LC_ALL` without UTF-8 on Unix, a code page other than 65001 on Windows), selects the 7-bit glyphs `* ! o + x` and the 7-bit art. |
| **Accessibility** | No information carried by colour alone, and every screen has a non-interactive `huginn <verb>` equivalent. A line-oriented `huginn console --plain` mode for screen readers was planned and is not built. |
| **Resize** | Bubble Tea's window-size message; layouts recompute, and lists keep the selected row visible. |
| **Slow links** | Every fetch has a timeout and a visible loading row; stale data stays on screen with its age; the attach path keeps `ConnectTimeout=10` and `ServerAliveInterval=15`. |

## 10. Testing

- **Golden snapshots** of every screen at several sizes (60×18, 80×24, 100×30), with ANSI
  stripped so diffs read as text, plus a test that every frame fits the terminal at four sizes.
- **A fake appd:** a Go `httptest` server replaying fixtures in the shapes of the real API
  (millisecond and second fields included on purpose), driving each screen's empty, normal and
  error states. Fixtures live in `core/internal/console/testdata/`.
- **Real-appd end-to-end:** `core/test/e2e.sh` starts a real appd, a core and a fake claude, and
  drives the console against them.
- **Routing tests** in `scripts/test-client.sh`: bare `huginn` with a TTY and a stub core that
  answers `console --probe` calls the core; with stdout redirected, it attaches; with
  `HUGINN_CONSOLE=0`, it attaches; with an old core (the probe fails), it attaches. The same four
  for the PowerShell client. A session named `console` stays reachable with `huginn attach console`.
- **Mutation check on the gate:** make the TTY check always true and confirm the redirected-stdout
  test fails. Tests are verified by making them fail.

## 11. Phased plan

| Phase | Ships |
|---|---|
| **0 Spike** | Bubble Tea in the core: measure the binary size on Linux, Windows and Android; check rendering in conhost, Windows Terminal and Termux; prove the `ExecProcess` ssh round trip on Windows and Linux. Go or no-go on the library. |
| **1 Foundation** | The `/appd/*` proxy in the core with tests; the console skeleton (router, theme, art, status line, palette, help); routing in the core and both clients; the core-gate screen. |
| **2 MVP**: *"I can live in it"* | Home, Sessions (list, detail, attach and return, new, rename, kill, soft-end, send), Devices (this machine and the others), Usage, Core (status, update, restart). Goldens, fake appd, end-to-end. |
| **3 Parity** | Chats with streaming, the Projects dashboard, Rounds, Archive with revive, Settings. |
| **4 Reach** | `--plain` mode, the ssh-only console for Termux, remembered layout, mouse. |

Phases 0 to 3 shipped together in core 2.3.0. Phase 4 is not built.

## 12. Known limitations

As of core 2.5.x:

- **No console on Termux.** `huginn console` explains that it needs huginn core, and bare `huginn`
  attaches to `main`. macOS is not supported at all.
- **No `--plain` mode.** Screen-reader users should use the `huginn <verb>` commands, which every
  screen names in its footer.
- **No Scratchpads or Apps screens.** Use the desktop or Android app.
- **The bare-`huginn` choice is an environment variable only** (`HUGINN_CONSOLE=0`); there is no
  stored setting for it.
- **One layout.** The console does not switch to a side-by-side list and detail on wide terminals,
  and does not remember the last screen between runs.
- **Attaching needs SSH.** On a machine set up from the desktop installer or a package, run
  `huginn setup ssh` once; everything else in the console works through the core without it.
- **Nested tmux.** Attaching from a console running inside tmux works, but detach is
  `Ctrl-b Ctrl-b d`.

The design also reviewed a full uninstall, reinstall and re-enrol on each platform. Most rough edges
it found were fixed in 2.3.0 to 2.5.x (`huginn setup ssh`, linger turned off again by uninstall,
the install summary, the OpenSSH check in `get.ps1`, pairing without SSH). These remain:

- **SSH key authorization is manual.** The installers print the exact `authorized_keys` line to add
  on the server; the terminal path (an SSH key) and the apps and core (the bearer token) still pair
  two different ways. A short-lived enrol code minted by the server would make both one step.
- **Windows shows an unknown publisher.** The Windows builds are signed, but not with a publicly
  trusted certificate yet, so SmartScreen warns on first run.
- **The Windows tray icon starts in the Windows 11 `^` overflow**; pin it from there.
