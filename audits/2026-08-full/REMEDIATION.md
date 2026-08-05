# Remediation status — 2026-08-04

The audit's findings, and what has actually been done about them. This file is
the current truth; `AUDIT-REPORT.md` is the frozen record of what was found.

**All 17 HIGH findings are addressed, and everything is released:**

| component | version | where |
|---|---|---|
| `huginn-appd` | **2.53.1** | deployed, live |
| Compose desktop | **0.3.2** | `/v1/desktop-kt` |
| Android app | **2.56.0** | devstore |
| Electron desktop | 0.4.0 | `/v1/desktop` — deliberately untouched (contract C3) |

The desktop release verified the AUMID fix is really in the installer
(`stamps AUMID com.silencelen.huginn.desktop-kt on the Start Menu shortcut`) and
the new drift guard confirmed the app's constant and the installer's agree before
building anything.

---

## Fixed and deployed

| # | finding | what shipped | verified by |
|---|---|---|---|
| L11 | plan approvals invisible on both clients | the option-run walk stops at option 1, so a numbered plan body can no longer be swallowed | a verbatim 16-step live capture that returned null before, now a fixture in the shipping gate; the old lib fails it |
| L1 | `/answer` fingerprint guard was opt-in | the host requires a non-empty fingerprint; the phone stops drawing buttons it has none for | live against the running daemon: no fingerprint → 400, empty → 400, stale → 409, correct → 200 and only that one lands |
| L9 | appd's route layer had no tests | `test/routes-answer.test.js` spawns an isolated daemon; route tests are now IN the gate | 393 tests pass, up from 385 |
| — | a queued message arrived twice (3-of-3) | re-emit only on a cold open; a resumed read reports `deliveredQueued` and the client clears the badge in place | both halves pinned by tests; reverting either lib fails them |
| L12 | aborted downloads leaked a file descriptor | `res.on('close')` destroys the read stream | scratch daemon: 5 aborts, fd count unchanged (was +5) |
| L8 | create and rename returned a name that does not exist | both routes read the name back from tmux | live: rename to `fixv.rn.dot` returns `fixv_rn_dot`, state file follows, GET on it is 200 |
| — | oversized bodies were a reset socket | drain and answer 413 instead of destroying the request | live: 300KB body → 413 with a JSON error |
| — | every sign-in reported a duplicate account (3-of-3) | compare by credentials, not by a slug that could never match | `sameAccount` is key-scheme independent |
| L13 | auto-switch could not act and only said so if asked | pushes once a day when it is at the threshold and stuck, naming what would re-arm it | the daemon's existing explanation was already correct — this makes it volunteer it |
| L28 | `server/bin` split three ways | repo takes the live `cc` and `huginn-status`; the netplan snapshots take the live title hook | all three now agree with `/usr/local/bin` |
| L32 | deploy shipped behind a syntax check | the suite is the gate, with the pass count asserted | in use: "appd tests: 393 passed" precedes every install |
| L26 | `setup.sh` downgraded working hosts and clobbered `~/.tmux.conf` | refuses to overwrite a newer installed file; backs the tmux config up | five cases exercised in a scratch harness |
| — | no way to bootstrap a fresh host | `deploy.sh` mints the bearer token when absent | the daemon requires ≥32 chars; the mint matches |
| L21 | `/run/huginn-claude-state` was world-readable | `umask 077` + `chmod 700` | live: directory is `drwx------`, files `-rw-------`, hook still writing |
| L2 | the systemd unit had no sandboxing | `systemd.d/hardening.conf`, applied to the live service | `/usr` read-only, sessions still visible, account still readable |
| L29 | `USAGE.md` said `huginn -p` has "no tools" | corrected, with the measurement | re-tested: on this host `-p` also runs **Bash** — `id -un` → `root` |
| L16 | `SECURITY.md`/`ARCHITECTURE.md` described a system with no daemon | both rewritten around the daemon | — |
| L30 | `mobile/README` named the tailnet as the boundary | corrected: the bind is `0.0.0.0`, the token is the only gate | — |
| L10 | the Windows installer never stamped the AUMID | `WinShell::SetLnkAUMI` on the Start Menu shortcut, plus an `AppUserModelId` DisplayName so the app is findable in Settings > Notifications | makensis compiles it, the compiler resolves all three WinShell exports; **Windows display itself still needs owner test 1** |
| L31 | the release gate allowed publishing a DOWNGRADE | refuses anything not strictly newer, and refuses on AUMID drift or a missing plugin | — |
| L33 | a message sent during an upload was lost on navigation | draft restored when a send is cancelled, with `ensureActive()` closing the cooperative-cancellation gap; both desktop composers | compiles; no UI-test harness exists for the composer path |
| L17 | a mouse wheel could not break the auto-scroll latch | the decision is a pure `Follow` reducer; a wheel tick unlatches like a finger, a programmatic scroll never does | 8 new tests, one per historical bug; `:ui` 7 → 15 |

On the vendored plugin: `packaging/plugins/x86-unicode/WinShell.dll` is a 3 KB
binary checked into the tree. Provenance was verified independently, not taken on
trust — byte-identical (sha256 `9be85b98…`) to electron-builder's own bundled
copy on this host, exporting exactly `SetLnkAUMI` / `UninstAppUserModelId` /
`UninstShortcut`, and already running on the owner's machine inside the Electron
client. Hand-rolling the COM sequence was rejected because it could only be
reasoned about here, never run, and a mistake in it fails silently — exactly like
the bug it would replace.

Note on L2: **not** `PrivateTmp`. tmux's socket is `/tmp/tmux-0/default`, so a
private `/tmp` makes every session invisible while the daemon still answers
`/v1/ping` — the one directive a hardening template hands you, and the one that
breaks this service. `/root` (not just `/root/.claude`) must be writable because
an account switch renames `~/.claude.json.huginn-tmp` over the config.

## Corrections to the audit itself

- **L29 was understated.** The audit proved `-p` could read files. It can also
  execute shell commands as root on this host, because `permissions.defaultMode`
  is `auto`. Measured while writing the fix.
- **L13 was overstated.** The daemon's `idleBecause` was already accurate and
  served; what was missing was that it never volunteered the problem.
- **The em-dash prohibition is Artists' Adventure only** — a literal reading of
  the audit brief would have filed 51 false findings against huginn, whose own
  README uses 16.
- **expect/actual count is 2, not 4.**

## Still open

- **~60 med/low from the 2026-07-28 mobile audit**, plus 16 of the 29 re-verified
  in this audit. None is urgent; they are listed in `findings-lanes.md` in
  severity order.
- **`SessionView.kt` follow-ups are DONE** (they were flagged as out-of-scope by
  the agent that fixed `ChatView`): both the lost-message fix and the wheel signal
  are applied there too.
- **Task #20** — the phone's duplicate copies of `WorkStrip`/`prettyModel`/etc.
  Confirmed still present and confirmed NOT diverged, so the deletion is safe
  mechanical work. A second instance was found: the reattach rule (contract C5)
  is implemented independently in both shells (L22) and the desktop's copy is the
  untested one.
- **Electron cutover** — the >1-day-of-use gate has long passed; sequence it
  after the AUMID question is settled so the install path does not move while
  two unknowns are open.
- **Compose desktop keyring** — assessed, not rediscovered; the plaintext-0600
  token is a documented trade-off with the same exposure as the SSH key beside it.

## Sequencing note

The daemon went out BEFORE the clients, which is only safe because the stricter
`/answer` rule cannot orphan an installed app: a detected prompt always has ≥2
options and `promptFingerprint` returns null only when there are none, so options
imply a fingerprint and the shipped 2.55.0 could not draw a button the new host
would reject. That was checked before deploying, not after.

## Needs the owner

`OWNER-WINDOWS-TESTS.md`, ten minutes on PRESTIGE. Test 1 (does the Start Menu
shortcut carry the AUMID) decides whether the desktop's whole notification layer
works and whether it has been suppressing the Telegram fallback.
