# Changelog

All notable changes to the **terminal client + server core** (`client/`, `server/`)
are documented here. The other components version independently and keep their own
changelogs: the Android app ([`mobile/CHANGELOG.md`](mobile/CHANGELOG.md)), the
Compose desktop client ([`mobile/app-desktop/CHANGELOG.md`](mobile/app-desktop/CHANGELOG.md)),
and the deprecated Electron client ([`desktop/CHANGELOG.md`](desktop/CHANGELOG.md)).
Format loosely follows [Keep a Changelog](https://keepachangelog.com/); versions use
[SemVer](https://semver.org/).

## [Unreleased]

## [0.10.3] - 2026-08-25

### Fixed — one oversized line could lose a remote run's entire answer

A device runs claude with `--output-format stream-json --verbose
--include-partial-messages`, so a SINGLE line can carry a whole tool_result or a
whole final answer — a Read of a big file, a chatty Bash, a long report. Over the
daemon's body cap the POST came back 413, and:

1. the **whole batch** was lost, not just the oversized line;
2. if the terminal frame was in that batch it could **never** be delivered, because
   the retry is identically 413 — so the chat sat `running` forever and the machine
   was blocked from every other job;
3. the chat was then blamed on "no word for 5 minutes".

Silent, permanent, and reserved for the runs with the most to say. The runner now
keeps itself under the limit rather than discovering it: no batch is ever built too
large, an oversized line is **shrunk in place** (keeping its event type, with a
marker where the content was) rather than dropped, and **the terminal frame is
posted alone**, so whatever else happens the ending can land. The daemon's events
route also accepts a megabyte now, which helps a runner too old to know better.

- **A permanent rejection is no longer retried forever.** Any error re-queued the
  batch, so after a daemon restart — when the run is simply gone — the runner
  hammered a 404 twice a second for as long as its child lived. A 400/403/404/413
  now ends the job.
- **The runner is importable**, so the size rules can be tested at all. Until now the
  one part of the file that could silently destroy output was the part nothing could
  reach without starting a runner; `scripts/test-client.sh` drives it directly.

## [0.10.2] - 2026-08-25

### Fixed — the headless runner, which had no gate at all

Every one of these has the same shape: the runner reported success, or the wrong
reason, on a machine with **nobody sitting at it**. That is the one place a
misleading message costs the most, because there is no human to notice the advice
is useless. `client/huginn-device` now has a section in `scripts/test-client.sh`.

- **The generated systemd unit dropped `HUGINN_DEVICE_DIR`.** It relocates BOTH
  `device.json` and `appd-token`, so `HUGINN_DEVICE_DIR=/etc/huginn huginn-device on`
  followed by the very next line the tool prints installed a service reading
  `~/.config/huginn`: no config, no token. `serve()` treats that as transient and
  loops at 15 s forever, so the process never exits, `Restart=always` never fires,
  and **systemd reports the unit perfectly healthy while it does nothing at all**.
  The unit pinned `HOME` with a six-line comment about why a wrong one is fatal,
  and then dropped the variable that moves the same two files.
- **A missing work root was reported as "claude is not on this machine's PATH".**
  `spawn()` raises ENOENT for a missing executable AND for a missing cwd, and the
  handler assumed the first. So a deleted root made every job on the device fail
  forever, with the one instruction the operator had already followed. The cwd is
  checked before the spawn now, and the PATH message no longer prints the machine's
  private config path into a chat that syncs to a phone.
- **`huginn device off` said "Removed from huginn." and exited 0 when the DELETE
  failed**, having already thrown away `conf.id` — the only handle that could ever
  remove the row. So exactly when someone decommissions a machine (host asleep, VPN
  down, wrong url) the device stayed enrolled, a restart enrolled a SECOND row for
  the same box, and the stale one kept being offered work for thirty days. It now
  keeps the id, says what went wrong, and exits non-zero so a script can tell.
- **Every token-file problem read as "no appd token — put one in <path>"**, pointing
  at a file that was, in most cases, sitting right there. EACCES, EISDIR, an empty
  file and a file holding one captured newline are now named individually. The
  newline case is the nasty one: `huginn.sh` guarded the fetched token with `[ -s ]`,
  which a newline passes, so a botched `ssh huginn 'cat /etc/huginn-appd/token'`
  installed cleanly and then the runner insisted no token existed. That guard now
  requires non-whitespace content.
- **`huginn-device on` silently ignored `--flag=value` and unknown flags.** The
  parser matched only the space-separated form by exact string equality and the
  if/else chain had no else, so `--scope=own --root=/srv/build` enrolled at the
  default scope with no root and printed `Enrolled flagbox as "work"` — the word
  root never appeared in the output. Both forms are parsed now and anything
  unrecognised is refused with exit 2.
- **The host and the machine disagreed about where work runs.** `root` is only
  honoured at `work` scope (an `own` run starts in the account home), but it was
  accepted, stored and advertised regardless, so `huginn devices` on the host named
  a directory that ran nothing. Both ends changed: the runner says so at enrolment,
  and the host's line says where work actually starts.

## [0.10.1] - 2026-08-24

### Fixed
- **`huginn-device unit` printed the wrong shape of unit for the machines it is for.** It only
  offered a systemd *user* unit — one person's session, which needs `loginctl enable-linger` to
  survive a logout and is not up until somebody has logged in at least once. That is exactly
  wrong for a headless box, which is the whole reason the headless runner exists. `unit --system`
  now prints a system unit, and the user variant says which one you probably want and why.
- Both units set `HOME` explicitly. A system unit derives it from `User=`, which is right until
  somebody changes `User=` — and the runner finds *both* its own config and Claude's credentials
  under it, so a wrong HOME is a machine that enrols and then cannot log in. That reads as a
  broken Claude login rather than a wrong unit file, which is a long afternoon.

## [0.10.0] - 2026-08-24

### Added
- **`huginn device` — this machine, offered to Huginn.** `huginn devices` (plural) is the host's
  list of machines; `huginn device` (singular) is the one you are typing on. `on` enrols it,
  `off` withdraws it, `status` says what it offers and what the daemon sees, `unit` prints a
  systemd unit. This is the headless half of the desktop app's "Give Huginn access to this PC"
  toggle — for a server, a Pi or a build box, which has no desktop app and nobody sitting at it.
  - Enrolment takes the bearer token and the daemon's address over the **ssh link this machine
    has already been trusted on**. Nothing is widened: anyone who can ssh to the host can read
    that token anyway. What it removes is a credential pasted by hand between two terminals.
  - The address comes from `$SSH_CONNECTION`'s third field — the one this machine *just* reached
    the host on — rather than guessing on its behalf between a LAN address, a tailnet name and
    whatever `hostname` says.
- **`client/huginn-device`** — the runner. Node, no dependencies, in the daemon's own style. Node
  because a device must hold a long poll open, stream a child's stdout, batch it upward every
  half second *and* notice a cancel arriving in the reply to one of those batches; shell can be
  beaten into that shape, but not into one anybody should debug at 3am. The dependency is free:
  `claude` is itself a Node program, so any machine that qualifies as a device already has it.
  Fetched on demand rather than carried inside `huginn.sh`, because most devices are clients and
  never offer themselves — and validated with `node --check` before it is installed, since a
  truncated download that a service then restarts every ten seconds is worse than no runner.

### Changed
- **One policy, three programs.** What a remote request may do to a machine now lives once, in
  `shared/device-policy.json`. The Kotlin runner in the desktop app reads a generated table, the
  headless runner has the policy compiled into it by the same generator, and the daemon takes
  the scope lattice from it too. `scripts/gen-device-policy.js` expands all of that plus
  `shared/device-policy-cases.json` — every `(scope, locked, mode)` and the exact argv it must
  produce. **Both runners are asserted against the matrix rather than against each other**, since
  two implementations can be wrong in the same way and a table a person can read cannot be wrong
  quietly. The failure this prevents is silent: a runner that granted `Bash` where the policy
  says `look` would behave perfectly right up until the day it mattered.
- A headless machine reports **no lock state**, because the honest answer is that nobody is ever
  sitting at it. The desktop runner drops to read-only while the screen is locked; there is no
  equivalent here and none is faked. So the scope in the config file is the scope in force at
  3am — which is why the default is `work` and not `own`, and why `huginn device on` says so out
  loud when you widen it.

## [0.9.0] - 2026-08-24

### Added
- **`huginn rounds` and `huginn devices`.** The phone and the desktop could both see the host's
  scheduled work and the machines enrolled to run chats; from a terminal neither existed. On a
  product whose premise is that the terminal is the real surface, that was the wrong gap to
  leave open.

  `rounds` prints each round's cadence, when it next goes out, what it last found and the items
  it raised -- including **DID NOT FINISH** when a run admitted it missed its goal, which is the
  one fact a cheerful headline can hide. `devices` prints each machine's platform, its enrolled
  scope and what it will actually do right now (they differ while a machine is locked), whether
  it is reachable, and the folder a `work` run starts in -- labelled plainly as not a sandbox.

  **Both are rendered ON THE HOST**, by `huginn-rounds` and `huginn-devices` beside the existing
  `huginn-status`, and both clients just run them over ssh. That keeps one implementation of
  what a round looks like instead of one per client -- these two files have already drifted over
  a single version constant, and this has far more fields to drift over. It also keeps the
  property that the bearer token never leaves the host: the clients reach appd by ssh'ing here
  and reading `/etc/huginn-appd/token` locally, and nothing about these verbs teaches a laptop
  a new credential.


## [0.8.3] - 2026-08-16

### Added
- **`huginn desktop` — the download link for the latest Huginn Desktop build.** Prints the
  version, both platform installers with their size and sha256, and marks the one this machine
  can run; `huginn desktop windows` / `huginn desktop linux` print that url bare, so it composes
  (`curl -fLO "$(huginn desktop linux)"`). Until now the only way to get the installer onto a new
  machine was to know the tag naming and hand-build a GitHub URL.

  The link points at the **GitHub release** (`desktop-v<version>`), which is where the installed
  client's own updater fetches from — so the link and the self-update path are the same source
  and cannot drift apart. Filenames are read from the release's `manifest.json` asset (the sha256
  authority the updater verifies against), not guessed from a naming convention. It is
  deliberately **not** the daemon's `/v1/desktop-kt`: that serves the same bytes, but every route
  on it needs the host's bearer token and a browser cannot send one. So this is also the one verb
  that works from a machine that cannot reach the host at all — a GitHub fetch, no ssh.

  Tag-filtered rather than read from `/releases/latest`: four components publish into one feed
  (`v*`, `app-v*`, `appd-v*`, `desktop-v*`) and "latest" is whichever shipped last, usually not
  the desktop.

## [0.8.2] - 2026-08-14

### Security
- **`huginn update` on Windows no longer fetches replacement code from `$HUGINN_HOST`.** The scp
  fallback pulled `huginn.ps1` from whichever host the client was pointed at, and that file is
  then loaded into the shell — so `HUGINN_HOST`, which answers "which box do I drive", was also
  answering "whose code do I run". A typo, a second host or a test alias silently became a code
  source. It now uses the pinned `HUGINN_UPDATE_HOST` (default `huginn`) and announces the host
  when overridden. This shipped for `huginn.sh` in 0.6.1; the PowerShell client never got it.
  `test-client.sh` now asserts both clients pin it, so the two cannot diverge again.

## [0.8.1] - 2026-08-14

### Added
- **`huginn -p` and `-y` can now use the host's skills.** Both modes grant `Skill`, so a
  one-shot query can load `remote-ops`, `incident-triage`, `mempalace-ops` and the rest
  instead of re-deriving what they already document. A skill is markdown instructions, not
  a capability: invoking one cannot exceed the tools already granted, and `-p` keeps its
  deny list (`Bash Edit Write NotebookEdit`), so the ask/act line has not moved.
  Skills are cwd-scoped and resolve because the host runs these in `$HUGINN_WORKDIR`
  (`/root/netplan`) — verified by a headless run outside the project seeing none of them.

## [0.8.0] - 2026-08-14

### Added
- **`huginn end <name>` — a soft end.** Asks Claude to wrap up and commit, and (when
  auto-end is on for the host) ends the session once it goes idle. `huginn kill` remains
  the hard end. This is a daemon feature — it types into the pane and watches state — so
  there is no tmux fallback.
- **A shared appd reach helper in both clients.** The bearer token is root-only on the
  host, so the call runs there over the ssh alias and only the result comes back; the
  token never touches a client device.

### Changed
- **`huginn kill` now prefers the daemon's `DELETE /v1/sessions/<name>`**, which also
  clears the orphaned `/run` state file and releases the pane lease that a bare
  `tmux kill-session` leaves behind (Claude's SessionEnd hook never fires on a kill).
  Falls back to tmux when the daemon is unreachable — kill must work even when appd is down.
- **Windows parity.** `huginn.ps1` gains `end`, the appd helper, the DELETE-preferring
  `kill`, and the ask/act tool fence, so both clients expose the same verbs at the same
  version. The two version constants and this changelog head are now the release gate.

### Fixed
- **`huginn -p` never actually denied anything.** The deny-list was assembled into a
  remote shell variable and then expanded unquoted, so `claude` received `'Bash`, `Edit`,
  `Write`, `NotebookEdit'` — literal quote characters, no valid tool name — and the fence
  the code documents as "the real fence" was inert. `--allowedTools` only auto-approves,
  so a read-only `-p` query could still be granted Bash. The flag is now built client-side
  and interpolated so its quoting is bash syntax on the host. Anyone who ran
  `huginn update` after the soft-end work landed has the broken form; update again.

### Infrastructure
- **A GitHub release pipeline** (`scripts/github-release.sh`): one repo, four
  independently-versioned components, so tags are namespaced — `vX.Y.Z` is the
  CLI/server core, `app-vX.Y.Z` the Android app, `desktop-vX.Y.Z` the Compose
  desktop client, `appd-vX.Y.Z` the daemon. Notes are cut from the component's
  changelog section (a release with no changelog section is refused); artifacts
  attach with display labels; runs entirely locally via `gh` — no Actions.
  `ship.sh` and `release-desktop.sh` now mirror every release to GitHub
  automatically (best-effort — a GitHub hiccup never fails a shipped release;
  `HUGINN_NO_GH_RELEASE=1` skips).

## [0.7.1] - 2026-08-10

### Changed
- **No hardcoded deployment values anywhere in the tree** (part of preparing the repo
  to go public). The one-shots' remote working directory now follows the host's
  `$HUGINN_WORKDIR` (default `$HOME`) instead of a hardcoded project directory; `cc`'s
  default matches. `huginn-status`'s memory-node probe is opt-in via
  `HUGINN_MEMPALACE_HOST`/`HUGINN_MEMPALACE_MARKER` and prints nothing when unset.
  Alongside (appd 2.55.0): the daemon's working directory, memory-node probe, and
  out-of-band alert script are environment-driven (`HUGINN_APPD_WORKDIR`,
  `HUGINN_APPD_MEMPALACE_HOST`/`_MARKER`, `HUGINN_APPD_TELEGRAM_SCRIPT`) with generic
  defaults — a deployment's real values belong in a systemd drop-in
  (`server/appd/systemd.d/`). `ship.sh` reads its publish target from the environment
  or a gitignored `.shiprc`; `release-desktop.sh` and `deploy.sh` derive the daemon
  address from `tailscale ip -4` instead of a literal.

## [0.7.0] - 2026-08-10

### Added
- **The raven.** Huginn's brand is now one dark raven mark everywhere — and the CLI
  got its share: a small ASCII raven on the help screen in both clients. The canonical
  vector lives at [`assets/brand/raven.svg`](assets/brand/raven.svg); the Android and
  desktop apps shipped matching icons the same day (launcher/status-bar, window/taskbar/
  tray/installer).

### Changed
- The two clients' version numbers are aligned again (`huginn.ps1` had drifted to
  reporting 0.6.0 while `huginn.sh` was 0.6.1).

## [0.6.1] - 2026-08-10

### Fixed
- **`huginn update` no longer trusts whichever host you point at.** The `scp` fallback
  used to fetch replacement *code* from `$HUGINN_HOST` — a user-settable variable that
  answers "which box do I drive", routinely repointed at test boxes or mistyped. Fetching
  now comes from a **pinned** `$HUGINN_UPDATE_HOST` (default: the `huginn` alias), which
  never follows `$HUGINN_HOST`; overriding it must be deliberate, and the update names the
  host it trusted on every run.

## [0.6.0] - 2026-07-24

Fixes found by an adversarially-verified review of the huginn node.

### Fixed
- **Exact tmux targets (`-t =name`).** tmux resolves targets by exact match, then
  *prefix*, then glob — and a unique prefix resolves silently, so `huginn kill andvari`
  could destroy a session named `andvariautofill`. Kill, rename, and the reconnect
  client-count all anchor with `=` now; a typo fails loudly instead of hitting a
  neighbor.
- **Reconnect no longer resurrects a killed session.** Killing a session from one device
  while another's reconnect loop was mid-backoff used to recreate it (with a brand-new
  `claude` quietly burning quota). The loop checks `has-session` first and exits cleanly
  when the session is gone.
- **Fast-fail on instant failures**, bounded by duration rather than exit code: three
  consecutive sub-5-second exits stop the loop and point at the real error instead of
  scrolling it away with endless "link dropped" retries.
- **Safer self-update in both clients**: syntax-check before install, `.bak` of the
  previous version, `gh` exit code checked, no BOM written on the PowerShell path,
  `scp -o BatchMode=yes` so a keyless device fails instead of prompting mid-update.
- PowerShell 5.1: `-p`/`-y` one-shots base64-encode the remote script (5.1 mangles
  embedded quotes when marshalling native arguments, which silently dropped the persona
  *and* the tool grant).
- Session names validated on `kill`/`rename`; the bash reconnect backoff no longer
  overshoots its 15s cap.

### Changed
- Jittered reconnect backoff (±25%) — every tab shares one tunnel, and an unjittered
  schedule made all of them re-handshake on the identical second after a relay flap.
- `ConnectTimeout=10`, so an unreachable host reports promptly instead of appearing
  frozen for minutes.

## [0.5.0] - 2026-07-01

### Added
- **Date-shortcut keywords for `huginn usage`.** `huginn usage today | yesterday | week | month`
  expand to the right `ccusage -s/-u` date range without hand-typing `YYYYMMDD` — e.g.
  `huginn usage today` instead of `huginn usage -s 20260701`. `week` is a rolling 7 days,
  `month` is month-to-date. An optional report-type word after the keyword switches the
  grouping (`huginn usage week session`, `huginn usage month blocks`); default is `daily`.
  Date math runs server-side via GNU `date`, so behavior is identical regardless of the
  client OS/date flavor. Tab completion (bash + PowerShell) offers the new keywords after
  `usage`/`cost`/`ccusage`.

## [0.4.1] - 2026-06-23

### Fixed
- **Session names are case-insensitive.** `huginn Test` and `huginn test` now resolve to
  the same tmux session instead of silently creating two. Names are lowercased before any
  tmux-facing call in both clients, and host-side `cc` lowercases too as a backstop for
  older clients.

## [0.4.0] - 2026-06-18

### Added
- **Live session-state in the tab title.** The terminal tab now shows a state icon in
  front of the session name that tracks what Claude is doing, so across many tabs you
  can see at a glance which session needs you:
  - 🔄 **working** — a prompt is being processed / a tool is running
  - ✋ **needs you** — stopped for a permission or input request
  - ✅ **waiting** — turn finished (or just launched), waiting for your next prompt

  Driven entirely host-side by Claude Code hooks (`server/claude-hooks.json` →
  `huginn-claude-title`). State is pushed to the tab by wrapping an OSC-2 title in tmux's
  passthrough escape (needs `allow-passthrough on`, now shipped in `tmux.conf`), which
  tmux forwards to every attached client — so a mirrored phone and desktop both update.
  `setup.sh` installs the hook script and merges the hooks into `~/.claude/settings.json`
  (idempotently, preserving any hooks you already have). Headless `claude -p` and cron
  runs are unaffected — the hook no-ops when there's no tmux. Clients are untouched; this
  is a server-side feature (the client-version bump to 0.4.0 is just to keep `huginn
  version` aligned with the release).

### Changed
- **Dropped the `huginn:` tab-title prefix.** Tabs now read `<session>` (and `<icon>
  <session>` while Claude runs) instead of `huginn:<session>` — the state icon already
  signals it's a huginn session, so the prefix was redundant.

## [0.3.0] - 2026-06-17

### Added
- **Session-name tab completion.** Completing the first word now offers live session
  names (from `tmux ls` on the host) alongside the subcommands, so a bare
  `huginn <Tab>` lists everything you can attach to; `huginn kill`, `solo`, `rename`,
  and `mv` complete to existing session names for their argument. The session list is
  cached in-memory for ~5s so repeated `<Tab>` doesn't `ssh` on every keystroke
  (`BatchMode`/`ConnectTimeout` keep a missing key or slow link from hanging the
  prompt). Both clients.

### Fixed
- **Reject malformed session names.** A session name must be letters, digits, and
  underscore only (no `-`, `*`, spaces, etc.). This stops a typo'd flag like
  `huginn --hlp` from falling through to the attach path and spawning a junk tmux
  session. Enforced client-side (immediate, friendly error) and again server-side in
  `cc` (defense in depth for older clients).

## [0.2.0] - 2026-06-17

### Added
- **Auto-reconnecting attach.** When the link drops (laptop sleep, Wi-Fi flap) the
  client transparently re-attaches the still-running session — the work lives in
  tmux on the host, so only the ssh client died, not the session. It reconnects on
  any non-zero ssh exit (a clean `Alt-d` detach or normal exit returns `0` and
  stops the loop) and uses SSH keepalives so a half-open socket after sleep dies in
  ~45s instead of hanging. On reconnect it chooses **mirror vs solo dynamically** —
  mirror if another device is still attached, otherwise solo (full-screen, evicting
  the stale "ghost" client the dead link left behind). Opt out: `HUGINN_NO_RECONNECT=1`.
- **Named terminal tabs.** The attach renames the terminal tab/window to
  `huginn:<session>` — so `huginn costtracking` gives you a `huginn:costtracking`
  tab in Windows Terminal (and iTerm/Termux) — and restores the previous title on
  exit. Works because tmux's default `set-titles off` shields the outer terminal
  from the inner TUI's own title escapes. Opt out: `HUGINN_NO_TITLE=1`.
- **`huginn usage`** (aliases `cost`, `ccusage`) — Claude Code token/cost report via
  [ccusage](https://github.com/ryoppippi/ccusage); e.g. `huginn usage monthly`,
  `session`, `blocks --live`.
- **`huginn update`** — self-update the client in place from the repo. Pulls
  `client/huginn.{ps1,sh}` via `gh` (works for a private repo) and falls back to
  `scp` from the host's `/usr/local/share/huginn-cli/` mirror, then re-sources itself.
- **`huginn version`** — print the installed client version (and target host).
- **`rcc` alias** in both clients (parity with `rclaude`).

### Fixed
- **Clients are pure ASCII.** The `huginn update` `scp` fallback delivers the file
  with no BOM; Windows PowerShell 5.1 then decodes it as the system ANSI code page,
  which mangled the old box-drawing/em-dash characters and desynced the parser
  (the help here-string was read as code). Both clients are ASCII-only now, so they
  parse identically regardless of transport (`gh` vs `scp`) or PowerShell version.
- **`cc` defaults `TERM`** (`export TERM="${TERM:-xterm-256color}"`). Windows OpenSSH
  has no `TERM` env var, so it opens the session with `TERM` unset and tmux aborts with
  `open terminal failed: not a terminal` — even though the PTY is allocated correctly.
  Defaulting it server-side fixes every Windows client at once, with no per-device config.
- **Attach forces a PTY** (`ssh -t` → `ssh -tt`) for `huginn`, `huginn <name>`, and
  `huginn solo` — belt-and-suspenders for clients that don't allocate a PTY by default.
- `huginn.sh` `-p`/`-y` now single-quote-escape the prompt (parity with the PowerShell
  client) — prompts containing `'` no longer break the remote command.

### Changed
- `-p`/`-y` are **persona-aware**: when the host carries
  `/usr/local/share/huginn-cli/persona.md`, one-shots inject it via
  `--append-system-prompt` and enable memory tools; on a generic host they degrade
  to a plain `claude -p` headless query.
- Usage guards on `-p`/`-y`/`rename`/`kill` print a clear message instead of issuing a
  malformed remote command; `-p`/`-y` are folded into one branch in both clients.
- `setup.sh` now requires root and says so clearly.
- Installers accept a configurable SSH user (`-User` / `HUGINN_USER`, default `root`)
  to support running the node as a non-root user.

## [0.1.0] - 2026-06-08

Initial public release.

### Added
- **Server**: `setup.sh` (installs Node + Claude Code + tmux + scripts), the `cc`
  session launcher (attach-or-create, plus `solo` mode), `huginn-status`, and a
  multi-device-tuned `tmux.conf`.
- **Client command** `huginn` for **Windows PowerShell** (`huginn.ps1`) and
  **bash/Termux** (`huginn.sh`): subcommands `solo`, `list`, `status`, `rename`,
  `kill`, `-p`/`-y` one-shots, `help`, tab-completion, and a `rclaude` alias.
- **Installers** (`install.ps1`, `install.sh`) — set up the SSH alias, key, and profile.
- **Multi-device ergonomics**: mirrored sessions (`window-size smallest`),
  `Alt-d` detach, `Alt-o` detach-others, scrollback, status-bar keybind hints,
  and an optional Termux one-tap detach button.
- **Provisioning**: Proxmox LXC template + a generic "any Debian/Ubuntu host" guide.
- **Docs**: README, Setup, Usage, Architecture, FAQ, Security model, Contributing.

[Unreleased]: https://github.com/silencelen/huginn/compare/v0.7.1...HEAD
[0.7.1]: https://github.com/silencelen/huginn/compare/v0.7.0...v0.7.1
[0.7.0]: https://github.com/silencelen/huginn/compare/v0.6.1...v0.7.0
[0.6.1]: https://github.com/silencelen/huginn/compare/v0.6.0...v0.6.1
[0.6.0]: https://github.com/silencelen/huginn/compare/v0.5.0...v0.6.0
[0.5.0]: https://github.com/silencelen/huginn/compare/v0.4.1...v0.5.0
[0.4.1]: https://github.com/silencelen/huginn/compare/v0.4.0...v0.4.1
[0.4.0]: https://github.com/silencelen/huginn/compare/v0.3.0...v0.4.0
[0.3.0]: https://github.com/silencelen/huginn/compare/v0.2.0...v0.3.0
[0.2.0]: https://github.com/silencelen/huginn/compare/v0.1.0...v0.2.0
[0.1.0]: https://github.com/silencelen/huginn/releases/tag/v0.1.0
