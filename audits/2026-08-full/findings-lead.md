# Lead auditor's own findings (verified by live repro)

These were found and demonstrated directly, outside the lane fan-out.

---

## L1 — HIGH — The host does not enforce the answer fingerprint it claims to enforce

**File:** `server/appd/huginn-appd.js:2754`
**Contract:** the safety property documented at `huginn-appd.js:2721-2730` and `lib/pane.js:361`

### The claim in the code

The `/v1/sessions/<name>/answer` route carries a long comment explaining why the
check must live on the host:

> Check-and-act, on the host, in one request. The phone cannot do this safely:
> between reading the pane and sending the digit it would have to trust that
> nothing changed, and the whole point of this endpoint is that something might
> have. Answered in tmux meanwhile, moved on to a different question, back to an
> idle composer — in every one of those cases a bare digit lands somewhere it was
> never meant to, and in a Claude Code pane that can accept a prompt the owner
> never saw. So the fingerprint of the question being answered comes with the
> answer, and a mismatch is refused rather than delivered hopefully.

### What the code actually does

```js
if (body.fingerprint && body.fingerprint !== live) {
  return sendJson(res, 409, { ok: false, reason: 'changed', ... });
}
```

The guard is conditional on the client *choosing* to send a fingerprint. There
are two ways to skip it, and both are producible by the shipping client stack:

1. **Key absent.** `HuginnClient.kt:291` does `fingerprint?.let { put("fingerprint", ...) }`,
   so a null fingerprint omits the key entirely. `AnswerReceiver.kt:81` calls
   `client.answerPrompt(session, option, fingerprint.ifBlank { null })` — it
   converts "I have no fingerprint" into "do not check".
2. **Key present but empty.** `lib/fcm.js:84` sends `fingerprint: String(fingerprint ?? '')`,
   so an alert with no fingerprint carries `""` over FCM. An empty string is
   falsy in JavaScript, so `body.fingerprint && ...` short-circuits and the
   comparison never runs.

`prompt.fingerprint` is `String? = null` in `Models.kt:89`, and the phone posts
answer buttons with `prompt?.fingerprint` (`WatchNotifier.kt:131`) without
requiring it to be present.

### Live repro (2026-08-04, scratch session `audit-fp-probe`, since killed)

A fake caret-marked prompt was drawn in a throwaway pane, then answered three ways:

| request body | HTTP | outcome |
|---|---|---|
| `{"option":3}` | **200** | delivered — `3` + Enter typed into the pane |
| `{"option":3,"fingerprint":"deadbeef0000"}` | 409 | refused, `reason: "changed"` |
| `{"option":2,"fingerprint":""}` | **200** | delivered |

Pane after the unguarded answer, showing the digit and the Enter landed:

```
Do you want to create fingerprint-probe.txt?

❯ 1. Yes
  2. Yes, and do not ask again
  3. No, tell Claude what to do differently

Enter to select · Esc to cancel
3
3
```

The 409 row proves the comparison is correct *when it runs*. The other two rows
prove it does not always run.

### Failure scenario

A `session_attention` notification is posted for session `andrev` asking
"Do you want to make this edit?". Before the owner taps it, that session is
answered in tmux and moves on to a different question — say a plan approval
whose option 2 is "Yes, and auto-accept edits". The tap fires `AnswerReceiver`
with an empty fingerprint extra, which becomes `null`, which omits the key. The
host finds *a* prompt on screen, skips the identity check, and types `2` +
Enter. The owner has approved something they never saw, from a lock screen.

This is precisely the harm the endpoint was built to prevent.

### Why it is not currently firing constantly

Server-side, `alertTickInner` sets `a.options` and `a.fingerprint` together
(`huginn-appd.js:1831-1832`), so today's alerts normally carry both. The bug is
that nothing *enforces* that pairing: a daemon/app version skew, the
`PROMPT_FETCH_CAP` path, a future refactor, or any script holding the bearer
token produces an unchecked digit. The daemon binds `0.0.0.0` and the bearer
token is distributed to phone, desktop and CLI, so "only our clients call this"
is not a boundary.

### Asymmetry worth noting

The **desktop already got this right**. `WindowsToastNotifier.kt:119-121`:

> The fingerprint rides on every one; with none, there are no buttons at all
> rather than buttons that answer whatever is on the pane.

The phone draws the buttons regardless and degrades to an unchecked send. One
client learned the lesson; the other did not; and the host — which the comment
says is the place the guarantee lives — enforces nothing.

### Fix

Make the host enforce its own invariant, which is where the design says it belongs:

```js
if (typeof body.fingerprint !== 'string' || !body.fingerprint) {
  return sendErr(res, 400, 'fingerprint required');
}
if (body.fingerprint !== live) {
  return sendJson(res, 409, { ok: false, reason: 'changed', ... });
}
```

Then, on the phone, match the desktop: do not attach answer actions when
`prompt?.fingerprint` is null (`SessionWatchWorker.post`), so a button that
could not be answered safely is never drawn. Note the server change alone is
enough for safety — the phone change is what keeps the failure from becoming a
dead button.

A regression test belongs in `server/appd/test/` asserting all three rows of the
table above, including the empty-string case, since that one is a JavaScript
truthiness trap that will be reintroduced by anyone rewriting the guard.

---

## L2 — MED — The systemd unit applies no hardening at all, and a working set was verified by test

**File:** `server/appd/huginn-appd.service` (identical to the live
`/etc/systemd/system/huginn-appd.service`)

The unit runs `node` as **root** with a `0.0.0.0` bind and sets no protection
directives whatsoever — no `NoNewPrivileges`, `ProtectSystem`, `ProtectHome`,
`RestrictAddressFamilies`, `LockPersonality`, or capability bounding. The
process holds OAuth credential blobs, an FCM service-account key, executes
`claude -p`, and pipes input into tmux panes, so it is the highest-privilege
component in the system running with the fewest restrictions.

This is not a finding about a specific exploit; it is a missing mitigation layer
under components that already had real vulnerabilities (see L1).

### The usual objection, tested rather than assumed

The reason to leave a unit unhardened is that hardening breaks it. So rather
than recommend a list, a second instance was run under the proposed directives
on a scratch port (`8799`, `127.0.0.1`) with a scratch data dir — the mode rule
3 of the audit brief sanctions. Result:

| directive set | starts | `/v1/ping` | tmux sessions visible | credentials readable |
|---|---|---|---|---|
| proposed set **with** `PrivateTmp=yes` | yes | 200 | **`[]` — BROKEN** | yes |
| proposed set **without** `PrivateTmp` | yes | 200 | `['huginnfullaudit','uusage']` | yes |

`PrivateTmp` is the one that must not be used: tmux's server socket lives at
`/tmp/tmux-0/default`, so a private `/tmp` makes every session invisible while
the daemon still starts and answers cleanly — a silent, total loss of the
sessions feature. That is exactly the failure mode worth knowing before someone
hardens this unit from a template.

`ProtectSystem=strict` was separately confirmed to actually bite:

```
/bin/sh: 1: cannot create /usr/local/AUDIT-PROBE: Read-only file system
REFUSED (good)
scratch write OK          # the declared ReadWritePaths still works
```

### Verified-working drop-in

```ini
[Service]
NoNewPrivileges=yes
ProtectSystem=strict
ProtectKernelTunables=yes
ProtectKernelModules=yes
ProtectControlGroups=yes
RestrictAddressFamilies=AF_UNIX AF_INET AF_INET6
RestrictSUIDSGID=yes
LockPersonality=yes
RestrictRealtime=yes
ReadWritePaths=/var/lib/huginn-appd /run /root/.claude /tmp
# NOT PrivateTmp — tmux's socket is /tmp/tmux-0/default and a private /tmp
# makes every session silently invisible.
```

Ship it as a drop-in under `/etc/systemd/system/huginn-appd.service.d/`, for the
same reason the Yggdrasil bind override is one: `deploy.sh` rewrites the unit
file and would otherwise revert it.

`ProtectHome` is deliberately absent — the daemon reads `/root/.claude`, which
is its whole account feature. Running as a non-root user is a larger change (it
would need to own the tmux server, `~/.claude`, and `/run/huginn-claude-state`)
and is out of scope for a drop-in, but worth recording as the next step.

**Verified clean-up:** the scratch unit and `/var/lib/audit-appd-scratch` were
removed; `systemctl is-active audit-appd-hardened` returns `inactive`.

---

## L3 — INFO — Positive results worth recording (these were tested, not assumed)

- **Auth covers every route class.** Unauthenticated probes of `/v1/ping`,
  `/v1/status`, `/v1/watch` (SSE), `/v1/clients`, `/v1/accounts`, `/v1/usage`,
  `/v1/sessions`, `/v1/chats`, both channel manifests, a 96 MB installer
  artifact, and `/v1/push` all returned **401**. The gate at
  `huginn-appd.js:2033` runs before routing, so it cannot be forgotten on a new
  route. Malformed headers (`Bearer`, `Bearer ` , `Basic <tok>`, a truncated
  token, a token with a trailing character) were all rejected.
- **Artifact traversal is closed.** `..`, `%2e%2e`, `%2e%2e%2fmanifest.json`,
  `....//manifest.json`, `..%5cmanifest.json` returned 400/404. `NAME_RE` in
  `lib/desktop.js:30` admits no separator, so `path.join` cannot leave the
  channel directory — the "safe by construction" comment is accurate.
- **The zero-dependency claim holds.** No `package.json`, no `node_modules`;
  every `require()` across `huginn-appd.js` and all 19 `lib/*.js` resolves to a
  Node builtin or a local file. The daemon has no third-party supply chain.
- **No credential leakage into the journal.** 30 days of `huginn-appd` logs
  contain zero occurrences of the bearer token and zero matches for
  `sk-ant-`/`refreshToken`/`accessToken`. The request logger writes method,
  path, status and duration only.
- **Contract C7 (idle-aware notify claim) is honored on the Compose desktop.**
  `Presence.kt` separates `visible` (gates polling, so a tray-minimized window
  cannot hold the tmux size lease) from `present` (gates the notification claim,
  10-minute focus grace), and rotates `streamKey` so a parked SSE re-opens
  rather than carrying a stale claim header until the 30-minute rotation.
- **Stale notify claims cannot permanently suppress Telegram.** `lib/clients.js`
  judges liveness by per-mechanism freshness (3 min for a stream, 22 min for the
  alarm) rather than a sticky claim, and `pruneClients` is actually called
  (`huginn-appd.js:1446`).
- **The two update channels use different manifest schemas** — Electron's is
  `{linux:{appImage,deb}, windows}`, Compose's is `{artifacts:{linux-x64,windows-x64}}`.
  That is an accidental but real second line of defence for contract C3: a
  cross-channel publish would not merely be wrong, it would fail to parse.

---

## L4 — MED — `:core` imports Compose, contradicting the contributor contract that says it cannot

**Files:** `docs/ADDING-A-FEATURE.md:20` vs
`mobile/core/src/commonMain/kotlin/com/silencelen/huginn/ui/TerminalGrid.kt:3`,
`.../ui/Markdown.kt:3-6`
**Contract:** C1

`docs/ADDING-A-FEATURE.md:20` states the rule flatly:

> `:core` cannot import Compose or Android. `:ui` cannot import Android.

`:core` does import Compose:

```kotlin
// core/src/commonMain/kotlin/com/silencelen/huginn/ui/TerminalGrid.kt
import androidx.compose.ui.graphics.Color
// core/src/commonMain/kotlin/com/silencelen/huginn/ui/Markdown.kt
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
```

`:ui` is clean of Android except in `androidMain`, which is the correct place for
it (`CellPainter.android.kt`), so that half of the rule holds.

**Why it matters rather than being pedantry:** the imports are compose-ui *data
types*, not the Compose runtime, so nothing is broken today. But the doc is the
contract a future contributor reads before deciding where to put a file, and it
is now false. Someone obeying it will not know that `:core` already depends on
Compose, and someone extending `:core` on the strength of it will be surprised.
It also means `:core` is not consumable without a Compose dependency, which was
the stated point of having the layer.

**Fix:** pick one and make both sides agree. Either move `TerminalGrid.kt` and
`Markdown.kt` into `:ui` (they live in a `ui/` package inside `:core` already,
which suggests they were always meant to), or amend the doc to say what is
actually true — `:core` may use Compose *data types* but not the Compose runtime
or any `@Composable`. The second is cheaper and probably correct; the first is
cleaner. Not deciding is the only wrong answer, because the rule is currently
unenforceable as written.

**Related accuracy note:** the audit brief and the contract summary both say
there are "only 4 expect/actual (HTTP engine, IO dispatcher, glyph blit ×2)".
There are in fact **2** expect/actual pairs, both in
`core/.../data/Platform.kt` (`huginnHttpEngine`, `huginnIoDispatcher`). The
glyph blit is not expect/actual at all — it is a source-set split
(`CellPainter.android.kt` / the JVM sibling), which is a *better* mechanism. The
count in the docs should be corrected to 2, since "how many expect/actual exist"
is the metric the project uses to police this contract.

---

## L5 — LOW (debt, not divergence) — Task #20 duplicates still exist, and are byte-for-byte equivalent

**Files:** `mobile/app/.../ui/SessionControls.kt:44,176,179,190`,
`mobile/app/.../ui/SessionScreen.kt:518`, `mobile/app/.../ui/Common.kt`
shadowing `mobile/core/.../ui/ModelLabels.kt`,
`mobile/core/.../ui/TranscriptGroups.kt:96`, `mobile/ui/.../work/WorkViews.kt`
**Contract:** C1

Confirmed still open. The phone keeps private copies of `WorkStrip`, `WorkSheet`,
`AgentRow`, `plannedAgents`, `prettyModel`/`prettyEffort`/`modelOptions`/
`FALLBACK_MODELS`, and `AutoScrollToNewest`/`JumpToNewest`.

**The question worth answering was whether they have DIVERGED, and they have
not.** Compared directly:

| symbol | `:core`/`:ui` | `:app` | equivalent? |
|---|---|---|---|
| `FALLBACK_MODELS` | `ModelLabels.kt:17` | `SessionControls.kt:44` | identical list |
| `ModelLabels.effort` / `prettyEffort` | `ModelLabels.kt:29` | `SessionControls.kt:176` | identical expression |
| `ModelLabels.model` / `prettyModel` | `ModelLabels.kt:38` | `SessionControls.kt:190` | identical behaviour |
| `ModelLabels.options` / `modelOptions` | `ModelLabels.kt:40` | `SessionControls.kt:179` | identical expression |
| `plannedAgents` | `TranscriptGroups.kt:96` | `SessionScreen.kt:518` | identical regex `(\d+)\s*/\s*(\d+)\s+agents?\s+done`, identical `maxOrNull()` |

So **there is no phone-vs-desktop behavioural difference from these shadows
today**, which is the useful fact: the deletion is safe mechanical work, not a
behaviour-changing refactor. The risk is purely that the next fix lands in one
copy — the failure mode the contract exists to prevent, and the reason this
should not sit much longer.

`:core/ui/ModelLabels.kt:11-12` already carries the note naming exactly which
phone symbols to delete, so the work is scoped and understood; it is waiting on
someone touching the phone next. Deleting each duplicate and swapping the import
must land in one commit per symbol, since they are public top-levels in the same
package and a half-swap will not compile.

---

## L6 — INFO — Fix-wave verification (2026-08-01→04): the ones checked directly all held

Each was verified against current code rather than taken on trust.

| fix | status | evidence |
|---|---|---|
| `detectPrompt` footer staleness (contract C2) | **HELD — live-verified** | see L7 below |
| 0xFFFF modifier junk in live typing | held | `TermKeys.kt:41-70` — modifiers now send nothing, with the CHAR_UNDEFINED reasoning recorded in the comment |
| `IC` key rejected by the host | **held — exhaustively tested** | `IC` is in `NAMED_KEYS` (`huginn-appd.js`), and every key either client can emit now passes `validKey` (below) |
| token wipe via `renameTo` (contract C10) | held | `DesktopSettings.kt:378-401` uses `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)`, with "THIS COST THE OWNER HIS TOKEN" in the comment |
| updater's 4-hour sulk | held | `DesktopUpdater.kt:92-104` — exponential backoff from `RETRY_MS` capped at `RETRY_MAX_MS`, and `waitOrTokenChange` ends the wait when the token changes |

### The `/keys` protocol contract, tested exhaustively

The `IC` bug was a client emitting a key the host silently refused — and because
keystrokes are batched into one request, a single refused key took every
character typed alongside it down with it. So rather than confirm `IC` alone,
the daemon's real `validKey` and `NAMED_KEYS` were lifted out of
`huginn-appd.js` and run against the complete vocabulary the desktop mapper can
produce:

```
desktop named keys emitted: BSpace BTab DC Down End Enter Escape Home IC Left NPage PPage Right Tab Up
plus C-a..C-z, M-a..M-z, F1..F12
REJECTED BY HOST          : NONE — every key either client can emit is accepted
host accepts, none emits  : Space
```

and the validator still refuses what it should:

```
"C-A" false   "M-Z" false   "F13" false   "F0"  false   "C-1"  false
"enter" false "Enter " false "IC " false   ""    false   "C-aa" false
```

`Space` is accepted but never emitted (both clients send a literal space as
text) — a harmless dead entry, not a defect.

---

## L7 — INFO — Contract C2 verified against a LIVE Claude *permission* prompt

> **Scope correction, added after L11 was confirmed:** this verifies the
> *permission-dialog* shape only. Plan-approval dialogs are still broken — see
> **L11**. The two results are consistent: permission dialogs draw their question
> at indent-1, which stops the option-run walk, while plan approvals use indent-3,
> which does not. Do not read L7 as "C2 is fine".

This is the contract whose failure on 2026-08-03 blinded both clients at once,
so it was verified by running a real session rather than by reading the regex.

A throwaway session was booted (`tmux new-session -d -s audit-prompt-live -c <tmpdir>
'claude --setting-sources "" --model haiku'`), driven into manual mode, and asked
to write a file. The pane was captured **in the same command as the assertion**,
because a capture taken after the prompt resolves shows nothing and reads as a
failed reproduction.

**Positive case — a real permission prompt:**

```
 Do you want to create probe.txt?
 ❯ 1. Yes
   2. Yes, allow all edits during this session (shift+tab)
   3. No
 Esc to cancel · Tab to amend
```
```
detectPrompt → { q: "Do you want to create probe.txt?",
                 opts: ["1:Yes <=SEL", "2:Yes, allow all edits during this session (shift+tab)", "3:No"],
                 fp: "a9d83c5ddcb6" }
```

The footer here is `Esc to cancel · Tab to amend` — a phrase that appears in
**none** of the four hard-coded strings the old implementation matched. Under the
pre-2.52.2 code this prompt would have gone undetected; the absence-of-chrome
rule catches it. The fix is structurally right, not just patched for the
reported case.

**Negative case — an idle pane with composer and mode hint present:** returns
`null` (correct). No false positive.

**A real trust dialog** was also detected correctly (options and fingerprint
right), though its `question` came out as `"Security guide"` — the extractor
takes the nearest non-option line above the run, and that dialog's layout puts a
link label there. Cosmetic, once per folder, noted rather than filed.

**Sibling regexes verified live on a real working session at the same time:**

```
parseStatusLine → {"model":"Opus 5","branch":"main","mode":"auto"}
parseSpinner    → "Leavening… · 23m 21s · ↓ 73.4k tokens"
detectPrompt    → null   (correct: the session is working, not asking)
```

So the three pane readers that clients depend on moment to moment are all
currently correct against live output.

---

## L8 — HIGH (independently confirmed by live repro) — Rename reports success with a name that does not exist, and orphans the transcript mapping

**File:** `server/appd/huginn-appd.js:2679-2691`
**Found by:** the `appd-injection` lane; **verified here** by live repro.

tmux silently rewrites `.` to `_` in a session name and still exits 0. The
rename route takes tmux's success at face value, then moves the state file to
the name the *client asked for* rather than the name tmux *used*, and returns
that same non-existent name to the client.

### Live repro (2026-08-04, scratch sessions, since cleaned up)

```
POST /v1/sessions/audit-rename-src/rename  {"name":"audit.rn.dot"}
  -> HTTP 200  {"ok":true,"name":"audit.rn.dot"}

tmux actually named it        : audit_rn_dot
daemon moved the state file to: audit.rn.dot        <- a name nothing has

GET /v1/sessions/audit.rn.dot/screen   -> 404       <- the name the API returned
GET /v1/sessions/audit_rn_dot/screen   -> 200       <- the name that exists
ls /run/huginn-claude-state/audit_rn_dot -> No such file or directory
```

### Two distinct consequences

1. **The client navigates to a dead session.** The API returned `audit.rn.dot`
   with `ok:true`; every subsequent call on that name 404s. On the phone this is
   the "listed but 404s on tap" failure that was already fixed once for dashed
   names — the same symptom returning through a different door.
2. **The session→transcript mapping is orphaned.** `/run/huginn-claude-state/<name>`
   is how a tmux session is tied to its Claude transcript. After the rename the
   live session `audit_rn_dot` has no state file at all, so the Conversation view
   — the app's *primary* surface, per the v2 architecture decision — has nothing
   to read until the `huginn-claude-title` hook happens to rewrite it on the next
   tool call. A session that is idle when renamed stays unmapped indefinitely.

Note the state file is also what `fs.watch(STATE_DIR)` uses for instant alert
detection, so an orphaned session loses fast alerting too.

### Fix

Read the name back from tmux instead of assuming it took the one requested, and
key everything off that:

```js
const r = await run('tmux', ['rename-session', '-t', `=${from}`, to]);
if (r.err) return sendErr(res, 500, `tmux: ${r.stderr.trim()}`);
// tmux rewrites '.' to '_' and still exits 0 — ask what it actually used.
const q = await run('tmux', ['display-message', '-p', '-t', `=${to}`, '#S']);
const actual = (q.stdout || '').trim() || to;
try { fs.renameSync(path.join(STATE_DIR, from), path.join(STATE_DIR, actual)); } catch { }
if (leases.has(from)) { leases.set(actual, leases.get(from)); leases.delete(from); }
return sendJson(res, 200, { ok: true, name: actual });
```

Rejecting `.` outright at the route would also work and is simpler, but reading
the name back is strictly better: it is correct for any *future* character tmux
decides to rewrite, which is the class of bug rather than the instance. The
route regex already admits `.` (`[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}`), which is
why this is reachable at all.

### Related, lower severity

`/run/huginn-claude-state/` currently holds 18 state files while only 2 tmux
sessions exist — leftovers from long-dead sessions (`permprobe`, `pprobe`,
`promptprobe`, `test`, and several retired agent sessions). Nothing iterates the
directory to build the session list (that comes from tmux), so these are inert,
and the directory is on tmpfs so a reboot clears it. Worth a sweep at startup
for tidiness only — filed as INFO, not a defect.

---

## L9 — HIGH (process) — The entire route layer of appd is untested, and that is why L1 survived

**Files:** `server/appd/huginn-appd.js` (3,202 lines, no test file);
`server/appd/lib/gtoken.js` (94 lines, no test file)
**Contract:** the "find the next TermKeys" question in the audit brief

`TermKeys` shipped broken because it had no test file. The successor is not a
module — it is the daemon's whole HTTP surface.

```
appd tests            : 385 pass, 0 fail, 0 skipped
tests that load huginn-appd.js or start the server : NONE
lib/*.js  (tested)    : 3,468 lines across 19 modules, 18 of which have a test
huginn-appd.js        : 3,202 lines, 76 top-level functions, ~50 routes — 0 tests
```

Verified by grep: no file under `test/` requires `huginn-appd`, calls
`http.createServer`, or calls `listen(`. The 385 tests cover the *extracted pure
logic* — which is the half that was easy to test and is already the best-tested
code in the project (`pane` 67 tests, `alerts` 56, `transcript` 55). Everything
that is *hard* to test is untested: the auth gate (`authorized`), body limits
(`readBody`), input validators (`validKey`, `validModel`, `validEffort`,
`canonName`), the chat-run machinery, alert enrichment, and every route handler.

**This is not an abstract coverage complaint — it is the direct cause of the two
HIGH findings in this audit.** Both L1 (the `/answer` fingerprint guard being
opt-in) and L8 (rename returning a name that 404s) live in `huginn-appd.js`.
Both would have been caught by a single route-level test asserting the documented
behaviour. `385 pass` reads as thorough and is why nobody looked.

`lib/gtoken.js` is the second gap and the sharper one per line: 94 lines that
hand-roll a service-account JWT exchange — RSA signing with the FCM key, claim
construction, expiry — with no test at all. A silent break there disables push
for the whole household, and push is the delivery route that reaches a sleeping
phone.

### Fix

Add an integration harness. It is cheap, because the daemon is already designed
for it: `HUGINN_APPD_PORT`, `HUGINN_APPD_BIND`, `HUGINN_APPD_DATA` and
`HUGINN_APPD_TOKEN_FILE` let a test spawn a fully isolated instance with no
effect on the running one. That mechanism was used repeatedly during this audit
(see L2) and works.

A first harness plus the L1 regression test is provided beside this report at
`server/appd/test-integration/` — see `AUDIT-REPORT.md` for how to run it and
why it is deliberately outside the `test/*.test.js` glob for now.

---

## L10 — HIGH — The Windows installer never stamps the AUMID, so desktop toasts are very likely dropped silently

**Files:** `mobile/app-desktop/packaging/huginn-desktop-kt.nsi:160-162` (creates the
shortcut, stamps nothing) vs
`mobile/app-desktop/.../notify/WindowsToastNotifier.kt:24-29,155-161`
**Contract:** the code's own stated precondition

`WindowsToastNotifier` documents two hard requirements, and is explicit that one
of them cannot be satisfied at runtime:

> 1. **An AUMID that matches an installed Start Menu shortcut.** Windows files a
>    toast under the calling application's identity, and drops it on the floor —
>    silently, no error — when that identity matches no shortcut. This is the
>    exact reason no notification ever appeared in the Electron client's field use
>    before `setAppUserModelId` was added. **The NSIS installer must stamp
>    [AUMID] onto the shortcut it creates; nothing this process does at runtime
>    can substitute for that.**

and on the constant itself:

> MUST equal the `System.AppUserModel.ID` the NSIS installer stamps on the Start
> Menu shortcut. If the two ever disagree, Windows drops every toast without an
> error and the client looks silent rather than broken.

**Nothing stamps it.** Verified across the whole chain:

- `huginn-desktop-kt.nsi:161` — `CreateShortCut "$SMPROGRAMS\...\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"`, and nothing else touches the `.lnk`. Setting `System.AppUserModel.ID` needs an NSIS plugin (`WinShell::SetLnkAUMI`), and this file deliberately avoids plugins so it stays buildable by a stock Linux `makensis` (its own comment at lines 86-87).
- `scripts/release-desktop.sh` — no AUMID anywhere; the `jpackage` invocation (line 222) passes no shortcut or identity flags.
- Runtime — no `SetCurrentProcessExplicitAppUserModelID` exists in the codebase, consistent with the comment saying runtime cannot substitute.

### The scheme half IS done — checked before filing

`SchemeRegistrar.kt` writes `HKCU\Software\Classes\huginn` (`URL Protocol`,
`shell\open\command`) at every start, and it is genuinely called
(`Main.kt:101`). So requirement 2 holds. `SchemeRegistrar.kt:25-26` states the
pairing plainly:

> on the Start Menu shortcut — the two are one feature: without the AUMID the
> toast is dropped, without the scheme its buttons do nothing.

One half is implemented, the other is not, and the file that says so is the one
implementing the half that works.

### Why the "fails closed" safety net does not catch this

`createOrNull` probes once at startup by constructing a WinRT notifier for the
AUMID, and treats a throw as "this path would have swallowed notifications".
That covers *WinRT missing* and *PowerShell blocked* — but constructing a
`ToastNotifier` for an unregistered AUMID **succeeds** on Windows; the drop
happens later, at display time, with no error and a zero exit code. So the probe
passes, `healthy` stays true, every `post` returns success, and the toasts go
nowhere. The failure is invisible from the app's side, which is precisely why
this has survived: it sits in the "unproven on real Windows" bucket and produces
no symptom the dev loop can see.

### Impact

Desktop notifications and lock-screen-style answer buttons — the reason the
desktop client has an always-on layer at all — most likely do not work on the
owner's machine at all. Because Telegram fallback is suppressed while an
attended desktop claims the notify route (contract C7, and `Presence` correctly
claims it when the owner is at the desk), the likely net effect is **worse than
no desktop notifier**: the desktop silently suppresses the fallback that would
have reached him, and then drops the toast.

### Honest limit on this claim

That Windows drops an unmatched-AUMID toast is asserted twice by this codebase,
matches the documented Windows behaviour, and is exactly the Electron client's
recorded field failure — but it cannot be *executed* here, because there is no
Windows machine in this loop. What is **statically certain** is that no AUMID is
stamped anywhere. Step 1 of the owner test script (`OWNER-WINDOWS-TESTS.md`)
confirms or refutes it in about a minute.

### Fix

Stamp the shortcut. Two workable routes:

1. **`WinShell` NSIS plugin** (preferred): drop the plugin into the makensis
   plugin dir the release script already controls, then
   `WinShell::SetLnkAUMI "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "com.silencelen.huginn.desktop-kt"`
   immediately after `CreateShortCut`. Costs the plugin-free property the file
   currently prizes — worth it, since the feature is dead without it.
2. **Plugin-free:** have the app create/repair its own Start Menu shortcut on
   first run through the PowerShell bridge it already spawns for toasts, setting
   the property-store AUMID there. Keeps `makensis` stock and puts the stamp next
   to the constant that must match it, at the cost of a first-run side effect.

Either way, add the check to the release script's post-build verification so the
two identities cannot drift apart again — that drift is what the constant's own
comment warns about.

---

## L11 — HIGH (confirmed independently, live-proven by the lane) — Plan-approval dialogs are still invisible on both clients

**File:** `server/appd/lib/pane.js:195` (the step-2 option-run walk)
**Contract:** C2 — one prompt detector feeds every client
**Found by:** the `appd-pane-detect` lane, which drove a real 16-step plan-mode
session and got `null`. **Reproduced here independently.**

The 2026-08-03 fix cured the *footer-phrase* staleness. It did not cure this,
and **the same blindness is live in deployed 2.52.2 right now**: when Claude
writes an ordinary numbered plan and asks to proceed, the approval dialog gets no
card on the phone, no card on the desktop, no options in the notification, and no
lock-screen answer. The owner has to answer through the raw Screen tab — exactly
the experience that prompted the last fix.

### Mechanism

Step 2 treats any line with two or more leading spaces as an option's
description line and keeps climbing. A plan-approval question is drawn at
indent-3, so the walk climbs straight past it and starts collecting the plan's
own numbered steps as options. The run then fails the `opts[k].number !== k + 1`
contiguity check and the whole prompt is discarded.

Permission dialogs are immune only by accident: their question sits at indent-1,
and fewer than two leading spaces breaks the walk. That is why L7's live
permission-prompt test passed while this fails.

### Reproduction — a three-way control that isolates the cause

Same dialog every time; only the plan body above it changes:

```
NUMBERED plan body        *** NULL — no card on phone OR desktop ***
DASH-bullet plan body     DETECTED (3 options: 1,2,3)
numbered body, but pushed >24 lines above the dialog   DETECTED
```

Rows 2 and 3 are what make this airtight: the dialog itself is perfectly
detectable, and the *only* thing that breaks it is a numbered list within the
24-line window. The lane's live run confirms the same on a real session (a
16-step plan with "Would you like to proceed?" on screen → `null`; the identical
dialog with a dash-bulleted plan → detected).

### Why this is HIGH rather than MED

Numbered steps are the *default* shape of a Claude plan — asking for a plan and
getting "1. … 2. … 3. …" is the common case, not an edge case. It is worse on the
phone, where a leased short pane means `floor = lastContent - 24` covers nearly
the entire screen, so almost any plan body lands inside the window. And it fails
silently in the direction that matters: the owner is not told the card is
missing, the work simply stops until he notices.

### Fix

The lane's suggestion is right and minimal — stop the collection when the option
just prepended is number 1, since that is the run's own start:

```js
opts.unshift({ ... });
firstIdx = j;
if (Number(m[2]) === 1) break;   // the run starts at 1; anything above is content
```

A dialog's options always begin at 1, so nothing above option 1 can belong to the
run. This also removes the dependence on indentation heuristics entirely, which
is what made permission and plan dialogs behave differently for no principled
reason.

Regression fixtures for both shapes (numbered → detected, and the dash control)
are in `server/appd/test-integration/pane-plan-approval.test.js`, skipped until
the fix lands. **Use a verbatim live capture for the committed fixture** — my
first attempt at this used an invented box-drawn dialog and produced a false
positive, because real Claude Code prompts are not box-drawn. Fixtures for this
detector must be captured, never written.

---

## L12 — MED (confirmed by measurement) — Every aborted release-artifact download leaks a file descriptor permanently

**File:** `server/appd/huginn-appd.js:2828-2838` (`serveDesktopArtifact`)
**Found by:** the `appd-route-auth` and `appd-uploads-channels` lanes;
**measured here** on a scratch daemon.

```js
const stream = fs.createReadStream(found.file);
stream.pipe(res);
stream.on('error', () => { try { res.destroy(); } catch { } });
```

`pipe` propagates the *stream's* end to the response, but nothing propagates the
*response's* early close back to the stream. When a client disconnects
mid-transfer the `ReadStream` is never destroyed, so its file descriptor stays
open for the life of the process.

### Measurement (scratch daemon on port 8801, 40 MB artifact, since removed)

```
fds before                  : 22
after 5 aborted downloads   : 27      (+5 — exactly one per abort)
  open handles on artifact  : 5
after 5s settle             : 27      (not transient; nothing reclaims them)
after 2 COMPLETE downloads  : 27      (+0 — the normal path closes correctly)
```

The control matters: completed downloads leak nothing, so this is specifically
the abort path, and the fix cannot regress the common case.

### Why it matters here specifically

This route serves ~96 MB installers to the self-updater. An interrupted update,
a client quit mid-download, a laptop closing its lid, or a flaky link each cost
one descriptor, and the daemon runs for weeks between restarts. Two secondary
effects: the held fd **pins the inode**, so pruning an old release frees the
directory entry but not the disk; and once the process hits its descriptor
limit, *every* subsequent operation fails — including tmux capture and
credential reads — so the failure surfaces far from its cause.

### Fix

One line, using the plumbing Node already provides:

```js
const stream = fs.createReadStream(found.file);
res.on('close', () => stream.destroy());     // client went away — let the fd go
stream.on('error', () => { try { res.destroy(); } catch { } });
stream.pipe(res);
```

`stream.pipeline(stream, res, cb)` is the more idiomatic form and handles both
directions plus the error path; either is correct. Worth checking the upload
path for the mirror-image problem at the same time — it streams in the other
direction and was written to the same pattern.

---

## L13 — MED→HIGH (live state, supporting the `autoswitch-1723` lane finding) — Autoswitch is enabled and structurally cannot fire

**Files:** `server/appd/huginn-appd.js:1723`, `server/appd/lib/autoswitch.js`
**Evidence:** the deployed store, read read-only (metadata only, no values printed)

```
/var/lib/huginn-appd/autoswitch.json : {enabled: True, lastSwitchAt: 0, switches: 0, last: None}

accounts/79c777a4-…json   uuid=yes  lastPlan: present   <- the ACTIVE account
accounts/e12d3fa9-…json   uuid=yes  lastPlan: ABSENT
accounts/964aefae83ccf2ba.json  uuid=NO   lastPlan: ABSENT

GET /v1/accounts (live):
  79c777a4-…  active=True   plan=ABSENT
  e12d3fa9-…  active=False  plan=ABSENT
  964aefae83ccf2ba  active=False  plan=ABSENT
```

Autoswitch has been **enabled** since it was turned on and has performed
**zero** switches (`switches: 0`, `lastSwitchAt: 0`). Neither candidate has plan
data, and the documented rule is that a candidate with unknown headroom is never
chosen — candidates are priced with their *stored* access tokens, and an expired
stored token reports no numbers. Both stored tokens have aged out, so the pool of
eligible candidates is empty and stays empty.

The result is a safety feature the owner believes is armed — it rotates away
from an account at 95% usage — that cannot act. It fails in the quiet direction:
nothing errors, the toggle still reads on, and the first evidence would be
hitting a limit that autoswitch was supposed to have avoided.

**Fix direction:** the daemon deliberately implements no OAuth refresh flow
(contract C8 — single-use refresh tokens on a live daemon can permanently lose a
login), so refreshing candidates in the background is not an option. What it can
do is be *honest*: surface "no eligible candidates — stored tokens have expired,
sign in again to re-arm" in the Settings row next to the toggle, and include it in
the weekly status. An armed switch with an empty candidate pool should not look
identical to an armed switch with a full one.

---

## L14 — MED (live state) — A fingerprint-keyed account record survives that contract C8 says should have been reconciled

**File:** `/var/lib/huginn-appd/accounts/964aefae83ccf2ba.json`,
`server/appd/lib/accounts.js`
**Contract:** C8 — identity is the network-verified `accountUuid` first, the
refresh-token fingerprint only as a fallback

Two of the three stored records are uuid-keyed and carry the full shape
(`accountUuid`, `orgName`, `taggedId`, `lastPlan`). The third is keyed by a
16-hex refresh-token fingerprint and is missing all four:

```
79c777a4-…json            keys= accountUuid,credentials,email,firstSeen,lastPlan,oauthAccount,orgName,savedAt,slug,taggedId
e12d3fa9-…json            keys= accountUuid,credentials,email,firstSeen,lastPlan,oauthAccount,orgName,savedAt,slug,taggedId
964aefae83ccf2ba.json     keys= credentials,email,firstSeen,oauthAccount,savedAt,slug          <- no accountUuid
```

This is the residue of the pre-uuid keying scheme. Because refresh tokens rotate,
a fingerprint-keyed record can never be matched to its uuid-keyed twin by
identity — which is the exact failure that once filed one login as 13 accounts,
in miniature and frozen. It will sit in the account list forever, it cannot be
priced (no `lastPlan`, no uuid to resolve against), and it is one of the two
"candidates" autoswitch is choosing between in L13.

Worth noting the reconciliation gap is *narrow*, not general: the two modern
records are correct, so the current code path is right. This is one stale record
that predates it and has no path back. **Do not delete it blind** — it holds a
real credential blob for `964aefae…`; the safe move is to activate it once so the
daemon can resolve its uuid from the network and re-key it, or confirm it
duplicates one of the other two and then remove it deliberately.

---

## L15 — INFO — The CLI and its netplan mirror are clean (first audit of this component)

`client/` had never been audited. Result: nothing to report as a defect.

- `shellcheck -S warning` on `huginn.sh` (265 lines), `install.sh`, and
  `termux-detach-button.sh` reports **zero** warnings or errors. At `-S style`
  there are 5 notes in `huginn.sh` and 1 in `install.sh` — style only.
- **The netplan mirror has not drifted.** `scripts/active/huginn-cli/huginn.sh`
  and `huginn.ps1` are byte-identical to the canonical copies in this repo. That
  is worth stating explicitly because the mirror is a known drift hazard and is
  distributed to the owner's phone and laptop.
- The CLI holds no secrets. It shells out over SSH and uses `BatchMode` so it
  cannot stall on an interactive password prompt mid-command — the right call for
  something invoked from a phone.

The lane sweep covers `server/bin`, `provision/` and `huginn.ps1` in depth; this
is the mechanical check the lane's reasoning sits on top of.

---

## L16 — HIGH (docs) — `SECURITY.md` and `ARCHITECTURE.md` describe a system that no longer exists, and understate the attack surface

**Files:** `docs/SECURITY.md:9`, `docs/ARCHITECTURE.md:3,33`
**Contract:** "the docs are the next contributor's contract"

Both documents describe pre-daemon huginn — the SSH-and-tmux project. They are
not merely stale; the two load-bearing claims are the opposite of true, and both
are claims a person would rely on when deciding how to expose this system.

```
ARCHITECTURE.md:3   "Huginn is deliberately small — a few shell scripts and a tmux config."
ARCHITECTURE.md:33  "SSH is the only transport. No daemon, no web server, no ports beyond SSH."
SECURITY.md:9       "There is no extra auth layer. Huginn is SSH + tmux;
                     your SSH posture *is* your security posture."
```

Measured against the running host:

```
daemon running : active — huginn-appd, 3,202 lines + 19 library modules, as root
listening on   : 0.0.0.0:8787          (tailnet AND LAN, deliberately — via a drop-in)
auth layer     : bearer token, /etc/huginn-appd/token (mode 600)
```

`SECURITY.md` is explicitly "the *threat model & hardening* doc". Its threat
model omits, entirely:

- the bearer token — now a second credential of equal power to an SSH key, copied
  onto a phone, a Windows desktop, and any client the owner adds;
- the `0.0.0.0` bind, which puts the daemon on the LAN and the Yggdrasil mesh, not
  only the tailnet — a deliberate choice with no documented rationale for a reader;
- that a phone or desktop holding that token can **type into a root Claude Code
  pane** (`/keys`, `/answer`), which is arbitrary code execution by design;
- the account store: multiple full OAuth credential blobs under
  `/var/lib/huginn-appd/accounts`, plus an FCM service-account key in
  `/etc/huginn-appd`;
- the upload spool, which accepts any file type up to 128 MB;
- that there is **no authentication rate limit** on the daemon, so the bearer
  token is the single unthrottled gate.

Six of the seven files in `docs/` never mention `huginn-appd` at all; only
`DESKTOP-MIGRATION.md` does. The daemon is the largest and highest-privilege
component in the system and it is absent from the architecture doc's diagram and
from the security doc's model.

**The drift is confined to `docs/`, which narrows the fix.** The top-level
`README.md` is current: it lists `server/appd/` ("the phone daemon: sessions,
chats, push, prompts-as-buttons over the tailnet"), the four Kotlin modules, and
the Electron client marked deprecated. So a reader arriving at the repo sees the
real system and is then handed a link to `docs/ARCHITECTURE.md` that tells them
there is no daemon. The two documents disagree with each other, and the wrong one
is the one the README sends people to for detail.

**Severity note:** no vulnerability follows from this directly — the code's
posture is genuinely decent (see L3). It is HIGH because the document whose *job*
is to let someone reason about exposure now describes a strictly smaller system
than the one running, and the hardening checklist a reader would follow leaves the
entire daemon surface untouched. Someone acting on `SECURITY.md` today would
secure SSH and consider themselves done.

### Fix

`SECURITY.md` needs a second "What's exposed" section covering the daemon: the
token as a credential (where it lives, who has copies, how to rotate it), the
bind and why it is `0.0.0.0`, the fact that a token holder can execute code
through a pane, the credential and FCM key locations, and the absence of rate
limiting. `ARCHITECTURE.md` needs the daemon and the two clients in its diagram,
and lines 3 and 33 deleted — "a few shell scripts and a tmux config" is now
~33k lines of Kotlin and ~11k of JavaScript.

Cheapest honest interim fix, if a full rewrite is not happening today: add a
prominent note at the top of both files saying they describe the SSH/tmux core
only, and pointing at `mobile/README.md` and this audit for the daemon and client
surface. A reader who knows the doc is partial is in a completely different
position from one who does not.

---

## L17 — MED — `:ui` is the shared renderer and is effectively untested; the auto-scroll latch is untestable by construction

**Files:** `mobile/ui/src/commonMain/.../` (9 files, 1,772 lines, 1 test file)

`:ui` is the module both apps draw from — the same pixels on phone and desktop by
design. Its coverage:

| file | lines | tested |
|---|---|---|
| `TranscriptView.kt` | 554 | **no** |
| `work/WorkViews.kt` | 253 | **no** |
| `TerminalCanvas.kt` | 217 | yes — 7 tests, the only ones in the module |
| `MarkdownText.kt` | 186 | **no** |
| `Follow.kt` | 178 | **no** |
| `theme/Theme.kt` | 174 | **no** |
| `CellPainter.jvm.kt` / `.android.kt` | 153 | **no** |
| `SuggestionChips.kt` | 57 | **no** |

Eight of nine files have no test. A bug in any of them lands on both clients at
once — the same "one source feeds every client" property that makes `detectPrompt`
dangerous (contract C2) applies here, without `detectPrompt`'s 67 tests.

### The sharp end: `Follow.kt`

The auto-scroll latch has caused **three separate bugs** in this project, and its
own history is why the rule "auto-scroll must be a latch, not a per-arrival
geometry test" exists. It has no test — and it cannot have a unit test as
written, because the entire file is `@Composable`: the latch lives inside
`FollowNewest`, entangled with `LazyListState`, `snapshotFlow` and
`DragInteraction`.

So the recommendation is not "write a test" but **extract the decision, the way
this codebase already does everywhere else**. `LocalEcho`, the `VoiceLoop`
reducer, `TranscriptGroups`, `reattachPlan`, `Heartbeat.intervalFor`,
`WatchCycle.finishedSince`, `backFrom` and `destToKey` are all pure functions
lifted out of UI precisely so they could be tested — and each was lifted *after*
a bug. `Follow.kt` is the same shape and has not had its turn.

A pure `FollowState` reducer taking `(atTail, contentGrew, userDragged)` and
returning `following: Boolean` would be a dozen lines and would pin all three
historical bugs at once. The composable then becomes a thin caller.

**And the fourth bug is already here.** The `:ui` lane independently found it and
it is confirmed by reading `Follow.kt:73-82`:

```kotlin
listState.interactionSource.interactions.collect { i ->
    when (i) {
        is DragInteraction.Start -> following = false      // the ONLY way to stop following
        ...
```

`DragInteraction` is emitted by touch drags and scrollbar drags — **a mouse wheel
emits none**. On the phone that is correct and is the whole point of the latch
(programmatic scrolls emit no drag either, which is what made the geometry test
wrong). On the **desktop**, where the wheel is the primary scroll gesture, the
latch can never be broken: scroll up to read something in a live conversation and
the next token yanks the view back to the tail.

The rule "only a finger breaks the latch" was correct on the platform it was
written for and became wrong when `:ui` was shared with a mouse-driven client —
the exact hazard contract C1 creates when logic moves into a shared module. The
fix is one more input to the reducer (a wheel/scroll-delta signal that also means
"the user took control"), which is another reason to extract it rather than patch
the composable.

`TranscriptView.kt` (554 lines) is the other candidate worth splitting — its
grouping decisions already live in `:core/TranscriptGroups.kt` and are tested;
what remains is layout, which is legitimately hard to unit test and reasonably
left alone.

---

## L18 — INFO — The build gate is correct (a suspected C11 violation that is not one)

`mobile/scripts/build.sh:91` pipes the test runner into `tee`, which is the
shape contract C11 warns about — `| tail` masking an exit code and turning red
into green. It is worth recording that this one is **fine**, so nobody "fixes" it:

```bash
node --test "$APPD_DIR"/test/*.test.js | tee "$NODE_LOG"
NODE_RC="${PIPESTATUS[0]}"                                  # the RUNNER's status, not tee's
NODE_COUNT="$(grep -oE '^# tests [0-9]+' "$NODE_LOG" | grep -oE '[0-9]+' || echo 0)"
[ "$NODE_RC" = 0 ]        || { echo "[build] server tests failed" >&2; exit 1; }
[ "${NODE_COUNT:-0}" -gt 0 ] || { echo "[build] server tests ran ZERO tests — refusing." >&2; exit 1; }
```

It handles **both** C11 traps deliberately: `PIPESTATUS[0]` recovers the real
exit status, and the test *count* is asserted separately because a glob matching
nothing exits 0 having run nothing. The missing-directory case is an error rather
than a skip, with a comment recording that an earlier `-d` guard once turned the
gate green while testing nothing. The Kotlin gate above it asserts a minimum
count the same way. `release-desktop.sh:135` uses the unpiped
`> "$NODE_LOG" 2>&1 || {` form.

One dead-code nit, not a defect: the script sets `set -euo pipefail` at line 9,
so a failing pipeline aborts at line 91 and the `NODE_RC` check never runs. The
build still fails, correctly — just without printing "[build] server tests
failed". The `PIPESTATUS` handling is redundant belt-and-braces today and becomes
load-bearing if `set -e` is ever removed. Leave it.

**Also confirmed:** both gates glob `test/*.test.js` explicitly, so the new
`test-integration/` directory added by this audit is **not** picked up by the
release path and cannot affect shipping.

---

## L19 — INFO — Electron's two fixed HIGHs are intact, and one of them proves L1 is a real regression

The deprecated client (`desktop/`, 0.4.0, still installed on the owner's Windows
machine) was checked for security only. Both previously-fixed HIGHs hold, and the
source is real TypeScript in the repo — 92 files, 13,715 lines under `src/` and
`test/`. (My first LOC pass counted `desktop/out/*.js`, which is build output;
the 36k figure in the audit brief's system map is measuring the same artifact.)

**Fix 1 — `huginn://answer` is fingerprint-mandatory.** `src/main/notify/activation.ts:37-45`:

```ts
// The fingerprint is MANDATORY here, and this is the whole security story
// ... a local process, or a web page the owner clicks through. Without a fingerprint
if (fingerprint === null || fingerprint === '') return null
return { kind: 'answer', session, option, fingerprint }
```

**This is the strongest possible corroboration of finding L1.** The Electron
client explicitly rejects *both* a missing fingerprint and an **empty string** —
it knows the exact JavaScript truthiness trap. The daemon, in the same feature,
one hop away, writes `if (body.fingerprint && ...)` and skips its check for both
of those inputs. The project already learned this lesson, in this feature, and
the host never got it. L1 is not a theoretical hardening request; it is the same
bug, unfixed on the side that matters most.

**Fix 2 — the update feed is pinned.** `src/main/updater.ts:16-23`:

```ts
/** The update feed is PINNED, deliberately not derived from the (user-editable) ... */
const FEED_URL = 'http://100.97.198.90:8787/v1/desktop'
```

Hard-coded, not read from settings. Contract C4 holds; the baseUrl → token +
update-feed RCE chain remains broken.

**Fix 3 — `setAppUserModelId` is present.** `src/main/index.ts:31`:

```ts
if (process.platform === 'win32') app.setAppUserModelId('com.silencelen.huginn.desktop')
```

### And this is what makes L10 concrete

Compare the two clients on Windows notification identity:

| | Electron 0.4.0 (deprecated) | Compose 0.3.1 (the future) |
|---|---|---|
| process AUMID set at runtime | **yes** — `setAppUserModelId` | **no** — no equivalent call exists |
| Start Menu shortcut carries the AUMID | yes — electron-builder's NSIS does this automatically | **no** — the hand-written `.nsi` only calls `CreateShortCut` |
| `huginn://` scheme registered | yes | yes — `SchemeRegistrar` at runtime |

The Electron client has both halves of the identity requirement, which is exactly
why its notifications began working once `setAppUserModelId` was added — the
event `WindowsToastNotifier`'s own comment cites. The Compose client, which
replaced it, has **neither**. Moving from a hand-rolled installer to
electron-builder's was a silent loss of a step nobody had to think about before.

This is the clearest argument for prioritising the owner's Windows test 1: the
predecessor demonstrably needed this to work, and the successor does not do it.

---

## L20 — INFO — `release-desktop.sh` is the best-defended script in the project

Reviewed end to end because the packaging lane hit the auth outage. It gets right
almost everything contract C11 and C3 exist to protect, and the reasoning is
written down at each gate:

- **Version refusal, both directions.** It refuses without a `## <version>`
  section in the changelog ("a human wrote a note about this version, or it is
  not a release"), and refuses if the channel already serves that version —
  "a client that has downloaded and verified 0.2.0 would find different bytes
  under the same version and the same hash claim."
- **Tests are the FULL shared suite, count-asserted.** It runs `:core`, `:ui`,
  `:app` and `:app-desktop`, not just the desktop module, with the comment that a
  release running only `:app-desktop:test` would be "26 tests over an untested
  application — the exact failure `scripts/build.sh` was hardened against twice."
  The count is checked rather than the exit code, because a task with no sources
  exits 0 having run nothing. The appd suite is run unpiped
  (`> "$NODE_LOG" 2>&1 || {`).
- **Atomic staging in the right order** (line 327: *"stage (artifacts first,
  manifest last, atomic)"*). Every artifact is written to `<name>.tmp` and
  `mv`'d into place, then the manifest last, also via tmp+rename — so a client
  can never see a manifest naming a file that is not fully there, and never a
  partial file.
- **The wine chain asserts artifacts, not exit codes** — it checks the launcher
  exists and that `file` reports `PE32+ executable`, which is the documented trap
  (`packageMsi` on Linux exits 0 producing nothing).
- **It actually installs and launches the build under wine** and probes it with a
  scratch `$PROBE_HOME`, then cleans up.
- **Channel separation is explicit**: `CHANNEL_DIR=/var/lib/huginn-appd/desktop-kt`,
  `FEED=/v1/desktop-kt`, with the cutover procedure described in the header as a
  deliberate future step (contract C3).
- **Prune keeps 2 versions** and deletes by parsed version rather than mtime.

Two observations rather than defects:

1. **`KEEP=2` explains the live state.** `desktop-kt/` holds 0.3.0 and 0.3.1;
   `desktop/` holds 0.3.0 and 0.4.0. That is the prune working, not accumulation.
2. **Prune could delete a version mid-download** (L12's fd leak is the other half
   of this: the daemon holds the inode open, so an in-flight download actually
   survives — the two bugs cancel). Worth a thought if the fd leak is fixed:
   deleting an artifact a client is fetching becomes a truncated download. Since
   `KEEP=2` retains the immediately-previous version, the realistic exposure is
   small.

The one real packaging finding is **L10** — the `.nsi` never stamps the AUMID —
which this script cannot catch because it verifies the installer *runs*, not that
Windows will file its notifications correctly.

---

## L21 — INFO/LOW — `server/bin` reviewed (first audit): sound, with one exposure worth noting

`shellcheck -S warning` is clean on all three (`cc`, `huginn-claude-title`,
`huginn-status`).

`huginn-claude-title` is the load-bearing one — it writes
`/run/huginn-claude-state/<session>`, the mapping that ties a tmux session to its
Claude transcript, and therefore the app's entire Conversation view. It is
carefully written:

- **Atomic**: `jq ... > "$sess.tmp" && mv -f "$sess.tmp" "$sess"`, so a reader
  never sees a half-written file (contract C10), with a plain-write fallback if
  `jq` is unavailable — the documented graceful degradation to the bare-word
  format.
- **Cannot break the session that runs it.** `set -u`, every command
  `2>/dev/null`, and `exit 0` on every path. The header explains why: *"stays
  SILENT on stdout (UserPromptSubmit would inject stdout into the prompt) and
  always exits 0 (a non-zero exit on UserPromptSubmit/Stop can block the turn)."*
  This is exactly the right failure posture for a hook.
- **No-ops outside tmux**, so headless `claude -p` and cron runs are unaffected.
- No user-controlled string reaches a shell; the payload goes through `jq` and
  the session name comes from `tmux display-message`.

### The one finding: the state directory is world-readable

```
drwxr-xr-x root root /run/huginn-claude-state
-rw-r--r-- root root adbpredictive
```

Each file contains `{state, sessionId, transcript, cwd, ts}` — including the
**absolute transcript path and working directory** of every session. On this host
that is a low-value leak (huginn is single-user and the paths are under
`/root/.claude`, which is `700`). But it is free to fix, and it is the kind of
detail that stops being harmless the moment a non-root user exists — the file
names alone enumerate every session, and `cwd` leaks the project layout.

`mkdir -p "$STATE_DIR"` inherits the default umask; adding `chmod 700
"$STATE_DIR"` after it (and writing the files `600`) costs one line. Worth
pairing with the L8 note that the directory currently holds 18 state files for
2 live sessions — stale entries from dead sessions, inert because the session
list comes from tmux, and cleared on reboot since `/run` is tmpfs.

---

## L22 — MED — The reattach rule (contract C5) is implemented twice, in two shells, with no shared source

**Files:** `mobile/app/.../ui/HuginnViewModel.kt:72` (`reattachPlan`) and
`mobile/app-desktop/.../ChatController.kt:198` (`reattachFlow`)
**Contract:** C1 (lowest module that can hold it) × C5 (seed XOR replay)

C5 exists because doing both — seeding from `partialText` *and* replaying from
zero — rendered a chat answer twice, and kept doubling as the block streamed.
The rule is four lines of pure decision-making. It lives in **both shells**,
independently:

```kotlin
// phone — HuginnViewModel.kt:72
internal fun reattachPlan(meta: ChatDetail?): Reattach? {
    if (meta?.running != true) return null
    val seq = meta.seq ?: return Reattach(seed = "", since = 0)
    return Reattach(seed = meta.partialText ?: "", since = seq)
}
```
```kotlin
// desktop — ChatController.kt:198
private fun reattachFlow(): Flow<ChatEvent>? {
    val d = _detail.value ?: return null
    if (!d.running) return null
    val seq = d.seq
    return if (seq != null) { _partial.value = d.partialText ?: ""; client.streamChat(chatId, since = seq) }
    else                    { _partial.value = "";                  client.streamChat(chatId, since = 0) }
}
```

**They agree today** — both honour seed-XOR-replay and both handle the
no-`seq` (pre-2.48.0 daemon) case correctly. So this is not a live bug. It is
the precise structural setup for the next one, and the codebase already says so
out loud. The desktop function's own doc comment:

> One function because there are two callers ... and the seed rule must be
> identical in both. **It was written twice first, and the second copy is exactly
> where a double-rendered answer would come from.**

That lesson was applied *within* the desktop file and not *across* the two
shells, which is the larger version of the same mistake.

Note the asymmetry in protection: the phone's copy is pure and **tested**
(`app/src/test/.../ReattachPlanTest.kt`, covering the seq, no-seq, not-running,
and null-partial cases). The desktop's copy is entangled with `_partial`,
`client` and `Flow`, so it is **not** unit-tested. The tested one is not the one
that will drift silently.

**Fix:** promote the decision — not the plumbing — into `:core`. `reattachPlan`
is already a pure function of `ChatDetail`; move it verbatim, move
`ReattachPlanTest` to `commonTest`, and have `reattachFlow` call it:

```kotlin
val plan = reattachPlan(_detail.value) ?: return null
_partial.value = plan.seed
return client.streamChat(chatId, since = plan.since)
```

Both shells then share one rule and one test, and the desktop gains the coverage
it currently lacks. This is the same move the project already made for
`LocalEcho`, `VoiceLoop`, `TranscriptGroups`, `WatchCycle` and `Heartbeat` — and
it belongs on the task #20 list, which is otherwise only about display code.

---

## L23 — INFO — The upload path does NOT have L12's leak (checked, because it is the mirror image)

L12 recommends checking the streaming-in path for the same defect as the
streaming-out one. It does not have it, and the contrast is instructive:

```js
const out = fs.createWriteStream(file, { mode: 0o600 });
const stop = (err) => { failed = err; try { req.destroy(); } catch { } out.destroy(); reject(err); };
req.on('error', stop);
out.on('error', stop);
```

`stop` destroys **both** ends on any failure, the partial file is `unlink`ed in
the `catch`, and the cap is enforced as bytes arrive rather than after a full
receive. Backpressure is handled explicitly (`if (!out.write(chunk)) req.pause()`
with `out.on('drain', () => req.resume())`). Files are created `0600`.

The difference between the two routes is that uploads were written with an
explicit `await new Promise` around the whole transfer — which forces the author
to name every termination path — while the artifact route uses `stream.pipe(res)`,
which silently handles only the happy one. Worth remembering when the next
streaming route is written: `pipe` is the shorter spelling and the one that
leaks.

One genuine (pre-existing, already reported by the route lane) consequence of the
`stop` design: `req.destroy()` kills the socket before the 413 can be written, so
an over-cap upload reaches the client as a connection reset rather than "that file
is too large". Same root cause as the `readBodyRaw` finding — destroying the
request instead of draining it.

---

## L24 — HIGH (live state — this is L10's consequence, happening now)

**Measured on the running daemon, 2026-08-04:**

```
FRESH clients (the ones that decide whether Telegram is suppressed):
  kind=stream     notify=True  age=86s   checkIns=454   ua=ktor-client      <- a Compose client
  kind=heartbeat  notify=True  age=504s  checkIns=1935  ua=okhttp/4.12.0    <- the phone

claiming the notification route right now: 2
=> Telegram fallback is currently SUPPRESSED for new alerts

journal, last 6 hours:  7 × "alerts: held session…"
```

Seven alerts in the last six hours were **held** rather than sent to Telegram,
because a client said it would handle them.

For the phone that is correct — FCM works and is measured (17–86 ms in every
device state). For the **`ktor-client` stream**, it is correct only if the
Compose client actually displays what it claims to. On Linux it does. On
Windows, per **L10**, it almost certainly does not: the AUMID is never stamped,
so Windows drops the toast silently while `Presence` keeps the claim true and
`healthy` keeps reading true.

So the two findings compose into a live failure mode: **an attended Windows
desktop suppresses the household Telegram fallback and then silently drops the
notification.** Contract C7 exists precisely to prevent "claiming while nobody is
looking"; what L10 adds is a client that is genuinely *there* and genuinely
*cannot show it*, which the claim model has no way to express.

The daemon's own defence is sound as far as it goes: `held` is logged rather
than dropped ("months from now, 'why did Telegram stay silent' needs an answer"),
and the repeat guard is only kept when a push actually delivered. The gap is that
`notify=1` is a client's *assertion*, never verified.

### Two fixes, and they are independent

1. **Stamp the AUMID** (L10) — the real fix.
2. **Make the claim falsifiable.** The desktop already knows whether its notifier
   came up: `Diagnostics` prints `desktop notifier windows-toast` or `NOT WIRED`,
   and `WindowsToastNotifier.failed` flips on the first failed post. Send
   `X-Huginn-Notify: 0` when the notifier is absent or has failed, instead of
   sending `1` whenever `notifyEnabled && present`. The daemon needs no change —
   `clients.js` already treats `notify === false` as "connected but muted: not a
   route". A client that cannot deliver should not be able to say it can.

**Immediate mitigation, if the owner's test 1 fails:** turn desktop notifications
off in Settings. That drops the claim, and Telegram resumes at once.

---

## L25 — INFO — Contract C8's credential handling verified, including a correction to my own first reading

My first grep for writes to `~/.claude/.credentials.json` came back empty and I
nearly recorded "read-only, verified". That was **wrong**, and the way it was
wrong is worth writing down: `lib/accounts.js` writes the file through an
injected `this.credentialsPath`, so a grep for the literal filename finds only
the two read sites in `huginn-appd.js`. Account switching obviously must write
it; a clean grep should have been suspicious rather than reassuring.

What the code actually does (`accounts.js:485-506`) is careful:

```js
const current = this.readActive();
if (current && !sameAccount(current, rec.credentials)) {
  this.save(activeEmail || null, current);        // snapshot the OUTGOING account first
}
const tmp = `${this.credentialsPath}.huginn-tmp`;
fs.writeFileSync(tmp, JSON.stringify(rec.credentials), { mode: 0o600 });
fs.renameSync(tmp, this.credentialsPath);          // atomic swap
this.writeOauthAccount(rec.oauthAccount ?? null);  // identity moves WITH the tokens
```

Every hazard the account-store history records is addressed:

- **Snapshot-before-swap**, so a running `claude` writing refreshed tokens back
  cannot strand the outgoing account (the 2.4.0 credential-loss bug).
- **Atomic** tmp+rename at `0600`, so a crash mid-switch cannot leave a truncated
  credentials file and no active login (contract C10).
- The outgoing snapshot is **keyed by its own fingerprint**, so a wrong email
  handed in cannot overwrite a different profile — the precise mechanism of the
  2.4.0 data loss.
- **The identity block moves with the tokens**, and a profile with no stored
  block deletes the stale one rather than leaving it: "wrong is worse than
  absent."

So C8's real rule is not "never written" but "written only by `activate`, atomically,
after snapshotting". The audit brief's shorthand ("read-only, always") is a
useful safety instinct but not literally true, and anyone verifying it by grep
will reach the wrong conclusion the same way I did.

Related, and still true: the daemon implements **no OAuth refresh flow** — no
code path exchanges a refresh token — which is the deliberate choice that keeps a
bug from permanently losing a login. That is what makes L13 (autoswitch with no
priceable candidates) a design consequence rather than an oversight.

---

## L26 — LOW/MED — Setup and deploy: two rough edges in the first-install path

`provision/` turned out to be **documentation only** (`proxmox-lxc.md`,
`generic-host.md`) — no executable code, so the injection and idempotency
questions do not apply there. The executable install path is `server/setup.sh`
and `server/appd/deploy.sh`, both clean under `shellcheck -S warning`. Two
issues:

### 1. `setup.sh` overwrites `~/.tmux.conf` unconditionally (LOW)

```bash
cp "$HERE/tmux.conf" "$TARGET_HOME/.tmux.conf"
```

No backup, no merge, no check. Anyone with an existing tmux configuration loses
it silently by running the documented setup step. The contrast is right next to
it: the Claude hooks merge is *carefully* idempotent — it strips prior
`huginn-claude-title` hooks, preserves every other hook, and writes via a temp
file — with a comment explaining the care. The tmux config got none of it.

Fix: `cp -n` with a message, or back up to `~/.tmux.conf.pre-huginn` when the
target exists and differs.

### 2. `deploy.sh` cannot bootstrap a host that has no token yet (MED for a fresh install)

```bash
TOKEN="$(cat /etc/huginn-appd/token)"
```

`set -euo pipefail` is on, so on a host without that file the script aborts —
*after* it has already copied the new code and restarted the service. Worse, the
daemon itself exits at startup with `FATAL: cannot read token file … — run
deploy.sh first`, which points at the script that just failed. **Nothing in the
repository generates the token** — no `openssl rand`, no `/dev/urandom` read in
`server/`, `docs/`, or `provision/`. Verified by grep.

So the documented install path has a gap: on a fresh host the daemon says "run
deploy.sh", deploy.sh reads a file only a human knows to create, and the required
length (≥32 chars, enforced at `huginn-appd.js:149`) is stated nowhere a
first-time reader would look.

Fix: have `deploy.sh` mint one when absent, which also documents the shape:

```bash
if [ ! -s /etc/huginn-appd/token ]; then
  install -d -m 0700 /etc/huginn-appd
  openssl rand -hex 32 > /etc/huginn-appd/token
  chmod 600 /etc/huginn-appd/token
  echo "[deploy] minted a new bearer token at /etc/huginn-appd/token"
fi
```

Note this matters more than it looks given L16: `SECURITY.md` never mentions the
token at all, so its creation, its required entropy, and its rotation are
currently undocumented everywhere.

**Good, for the record:** `deploy.sh` copies to `/opt/huginn-appd` rather than
running from the repo ("a mid-pull repo never serves half a version"),
`node --check`s before installing, and proves the daemon came back by pinging it
and grepping for `"ok":true` rather than trusting `systemctl restart`.

---

## L27 — INFO — Owner-taste check, and one rule the audit brief mis-scoped

**Left accent bars on cards — clean.** The owner's standing rule ("screams ai
generation") is honoured: no `drawLine`/`Divider`/narrow-width start-aligned bar
pattern exists on any card in `:ui`, `:app`, or `:app-desktop`.

**Em dashes in displayed copy — NOT a defect here, and the brief is wrong to
imply it is.** A literal reading of the brief's usability lane ("no em dashes in
display copy") would file 51 violations across the Kotlin modules. It should not,
because that rule is **Artists' Adventure-specific**, not a global owner
preference. The evidence is unambiguous:

- The owner's own `README.md` for this project contains **16** em dashes.
- `mobile/app-desktop/CHANGELOG.md`, which the owner reads on every release,
  contains **23**.
- The owner has been actively using the app and giving detailed UI feedback
  ("looking good, still needs polishing") without ever raising it.

So the em dashes in strings like `"Waiting for your answer — buttons below, or on
the Screen tab"` are consistent with the project's established voice. Filing them
would have been 51 false findings, and "fixing" them would have made the copy
worse.

Recording this because a future audit reading the same brief will reach the same
wrong conclusion. **The em-dash prohibition applies to AA displayed text only;
huginn uses them freely and deliberately.**

The genuinely open usability items are the ones the owner named himself on
2026-07-31 — no right-click context menus (partly addressed since:
`app-desktop/ui/common/Menus.kt` now exists), no tooltips (confirmed absent on
**both** clients), plainer empty states, and phone-sized padding on desktop list
rows. Those are polish he has already flagged, not audit discoveries.

---

## L28 — HIGH (confirmed, and worse than either lane described) — `server/bin` is split three ways, and the newest copy of each file lives somewhere different

**Files:** `/opt/huginn/server/bin/{cc,huginn-claude-title}`,
`/root/netplan/scripts/active/huginn-server/{cc,huginn-claude-title}`,
`/usr/local/bin/{cc,huginn-claude-title}` (the live ones)

The CLI lane flagged `setup.sh` shipping a regressed `cc`; the docs lane flagged
the netplan copy of `huginn-claude-title` missing a whole section. **Both are
true, and they point in OPPOSITE directions** — which is the finding:

| file | huginn repo | netplan mirror | **installed (live)** | newest is |
|---|---|---|---|---|
| `cc` | 25 lines (2026-06-17) | 36 lines (2026-07-24) | **36 lines** | the **netplan** copy |
| `huginn-claude-title` | 103 lines | 70 lines | **103 lines** | the **huginn repo** copy |

Neither location is authoritative for both files. The live `/usr/local/bin` is
the only place where both are current.

### What running the documented setup would do

`server/setup.sh:38` does `install -m 0755 "$HERE/bin/cc" /usr/local/bin/cc`
unconditionally. Running it today — the command `provision/generic-host.md` tells
a new host to run — **silently reverts three fixes** that are live right now:

1. **Exact tmux targeting.** The live copy uses `-t "=$SESSION"`; the repo copy
   uses `-t "$SESSION"`. tmux resolves a target by exact, then **prefix**, then
   glob — so with the repo copy, `cc jt solo` would force-detach the real client
   of a session named `jtyper`. The live copy's comment documents exactly this.
2. **Session-name lowercasing** (`SESSION="${SESSION,,}"`), so `Test` and `test`
   map to one session rather than two.
3. **Auto-launching Claude with a workdir guard.** The live copy opens in
   `~/netplan`, falls back to `$HOME` when that path is unavailable (so a stalled
   mount cannot make the client's reconnect loop churn forever), and starts
   `claude` as the window's first program — which is what stopped the
   `61: command not found` spam from device-attribute replies hitting an idle
   bash prompt.

All three are regressions the repo has never received. This is not a
hypothetical: it is the difference between the file in git and the file running.

### Fix

Reconcile in the direction of the live system, then keep one source:

1. Copy the live `/usr/local/bin/cc` into `server/bin/cc` (it is the newest and
   its behaviour is proven).
2. Copy the repo's `huginn-claude-title` into the netplan mirror, or better,
   delete the netplan `huginn-server/` copies and have that mirror pull from the
   huginn repo the way `huginn-cli/` already does — the CLI mirror was verified
   byte-identical (L15), so the pattern works; it just was not applied to
   `huginn-server/`.
3. Make `setup.sh` refuse to overwrite a *newer* installed file, or at minimum
   diff and warn. An install script that silently downgrades a working host is
   the failure mode here, and it applies to `huginn-status` and `tmux.conf` too
   (see L26 on `tmux.conf` being clobbered unconditionally).

---

## L29 — HIGH (docs, proven by execution) — `USAGE.md` tells the owner `huginn -p` has no tools; it reads any file on the host

**Files:** `docs/USAGE.md:14,73` (and the matching claim in `FAQ.md`)
**Found by:** the docs lane; **proven here by running it.**

```
docs/USAGE.md:14  | `huginn -p "question"` | one-shot **headless** query — reasoning only (no tools) |
docs/USAGE.md:73  `huginn -p "..."` runs a single prompt and prints the answer (no tools — safe for
                   quick questions). `huginn -y "..."` allows tools (bash/files/web) …
```

**Executed on this host just now:**

```
$ claude -p 'Use the Read tool to read /etc/hostname and reply with ONLY its contents.'
huginn

$ cat /etc/hostname
huginn
```

A bare `claude -p` — no `--allowedTools`, no grant of any kind — used the Read
tool and returned the file's contents. The documentation's central safety claim
about this command is false.

### Why the doc is wrong, mechanically

`--allowedTools` **auto-approves; it does not restrict.** In `-p` mode the
read-only tools (Read, Glob, Grep) are permitted by default with no grant at all.
The CLI's `-p` path (`huginn.sh:220`) passes `--allowedTools` when a persona
exists and otherwise runs a bare `claude -p` — either way, reading is available.
The real `-p`/`-y` distinction is **mutation** (Bash/Edit/Write), not tool access.

### Why this is HIGH rather than a doc nit

The sentence is a *security instruction*. It tells the owner that `-p` is "safe
for quick questions", and the natural inference — the one the wording invites —
is that an untrusted or careless prompt cannot reach the filesystem. It can. On a
host holding OAuth credential blobs, an FCM service-account key, and every
project on the box, "no tools" is precisely the wrong mental model, and it is
stated twice.

This compounds L16: `SECURITY.md` omits the daemon surface entirely, and
`USAGE.md` understates the headless surface. The two documents a person would
read before deciding what is safe to run both describe a smaller system than the
one they have.

### Fix

Say what is true:

> `huginn -p "..."` runs a single prompt and prints the answer. It can **read**
> files on the host (Read/Glob/Grep are available by default in headless mode)
> but cannot run commands or edit anything. `huginn -y "..."` additionally allows
> mutation (bash/files/web) for "go do it" tasks.

And, since this reasoning has now been re-derived twice from scratch, put the
rule itself somewhere durable: **never describe `--allowedTools` as a fence.**

---

## L30 — HIGH (docs) — `mobile/README.md` states the wrong network boundary

**File:** `mobile/README.md:149`

> `huginn-appd` binds **huginn's Tailscale address only** (`tailscale ip -4`) on
> port **8787** … everything the daemon exposes is equivalent to root on huginn,
> **so the tailnet is the network boundary** and the token is the authorization one.

It does not. The live daemon binds `0.0.0.0` — deliberately, via a systemd
drop-in added for the Yggdrasil LAN gateway, so the phone can reach it over the
mesh as well as the tailnet:

```
$ ss -ltn | grep 8787      →  0.0.0.0:8787
/etc/systemd/system/huginn-appd.service.d/override.conf:
    Environment=HUGINN_APPD_BIND=0.0.0.0
```

The bind change is correct and intentional — it should not be "fixed". The
**documentation** is the defect, and specifically the sentence that names the
tailnet as *the network boundary*. After the drop-in, there is no network
boundary: the LAN and the Yggdrasil mesh both reach it, and **the bearer token is
the only gate** — with no rate limiting, on a service the same paragraph
correctly describes as "equivalent to root on huginn".

That is a materially different security posture from the one documented, and it
is the paragraph a person reads when deciding how much to trust the token. The
drop-in's own comment is honest about the trade ("Access stays gated by the
bearer token"); the README was never updated to match.

**Fix:** replace the claim with the truth — binds `0.0.0.0` so both the tailnet
and the Yggdrasil LAN route work; the bearer token is the *only* gate; treat it
as a root-equivalent credential. Same edit belongs in `SECURITY.md` (L16), which
does not mention the daemon at all.

---

## L31 — MED — The release version gate is one-sided: it refuses an equal version, not a lower one

**File:** `mobile/scripts/release-desktop.sh:95`

```bash
if [ "$LIVE" = "$VERSION" ]; then
  echo "REFUSING: $VERSION is already live on $BASE_URL$FEED" >&2; exit 1
fi
```

String equality only. Publishing **0.2.9 over a live 0.3.1** passes the gate.
The intent — stated in the comment directly above — is that a client which has
downloaded and verified a version must never find different bytes under it; a
*downgrade* violates the spirit just as squarely, and additionally hands every
running client an "update" that moves it backwards.

Realistic trigger: `version.txt` is edited by hand (it is a one-line file, and it
currently holds an unreleased 0.3.2 — see the standing items), or a release is
cut from an older checkout. Neither is exotic.

**Fix:** compare semantically and refuse anything not strictly greater:

```bash
node -e 'const [a,b]=process.argv.slice(1).map(v=>v.split(".").map(Number));
  const gt=a.some((n,i)=>n!==b[i]) && a.reduce((r,n,i)=>r??(n===b[i]?null:n>b[i]),null);
  process.exit(gt?0:1)' "$VERSION" "$LIVE" \
  || { echo "REFUSING: $VERSION is not newer than the live $LIVE" >&2; exit 1; }
```

---

## L32 — MED — `deploy.sh` ships appd to production with a syntax check as its only gate

**File:** `server/appd/deploy.sh:9`

```bash
node --check "$SRC/huginn-appd.js"     # the entire gate
install -m 0644 "$SRC"/lib/*.js "$DEST/lib/"
systemctl restart huginn-appd
```

The 385-test suite exists and is fast (~2 s), and both *client* release scripts
run it and assert its count. The script that puts the **daemon** into production
runs none of it. `node --check` is syntax only — as the codebase already
learned: *"`node --check` vouches for syntax only — a lost import 500s at request
time."*

It is also `huginn-appd.js`-only: `lib/*.js` are copied without even a syntax
check, and that is where 19 of the 20 modules live.

The post-restart ping (`grep -q '"ok":true'`) is good and catches a daemon that
fails to boot — but a broken `detectPrompt` or a broken transcript reader pings
perfectly while silently blinding both clients, which is exactly the class of bug
this audit found twice (L11, and the 2026-08-03 incident).

**Fix:** run the suite before installing, and assert the count rather than the
exit code, matching what `build.sh` and `release-desktop.sh` already do:

```bash
node --test "$SRC"/test/*.test.js > "$LOG" 2>&1 || { tail -40 "$LOG"; echo "REFUSING: appd tests failed" >&2; exit 1; }
N=$(grep -oE '^# pass [0-9]+' "$LOG" | grep -oE '[0-9]+')
[ "${N:-0}" -gt 0 ] || { echo "REFUSING: appd tests ran ZERO tests" >&2; exit 1; }
```

Add `node --check` over `lib/*.js` too, or drop it as redundant once the suite runs.

---

## L33 — HIGH (confirmed by reading, three independent conditions all hold) — A message sent while an attachment is uploading is lost if you navigate away

**File:** `mobile/app-desktop/.../ui/ChatView.kt:350-360` (and the same shape on
the phone, `ChatScreen.kt`)
**Found by:** the `:app-desktop` lane; verified here.

```kotlin
val submit: () -> Unit = {
    if (canSend) {
        val body = draft
        onSent()                       // 1. draft cleared SYNCHRONOUSLY
        scope.launch {                 // 2. the actual send, on the VIEW's scope
            val marker = attachments.take()   // 3. SUSPENDS while the upload finishes
            val full = composeMessage(body, marker)
            if (full.isNotEmpty()) onSend(full)
        }
    }
}
```

Three conditions have to hold for this to lose a message, and all three do:

1. **`onSent()` clears the draft before anything is sent** — verified above.
2. **`scope` is `rememberCoroutineScope()`** (`ChatView.kt:109`), so it is
   cancelled when the composition leaves. The phone uses the same
   (`ChatScreen.kt:94`).
3. **`attachments.take()` is a `suspend fun`** (`AttachmentController.kt:144`)
   that waits for an in-flight upload — so the coroutine is parked precisely
   during the window that matters.

**Failure:** attach a photo or a 100 MB backup, type a message, hit Send, then
switch to another chat or session while the upload is still going. The composer
is already empty, the coroutine is cancelled at `take()`, `onSend` is never
called. The message is gone with no error — and because the draft was cleared,
there is nothing to retry from. Larger attachments make the window wider; the
128 MB cap makes it seconds long.

This is the same class as the already-fixed "sending while the attachment was
still Uploading dropped it" bug — that fix made the send *await* the upload,
which closed the drop but moved the vulnerable moment into a cancellable scope.

**Fix:** do the send on a scope that outlives the view. The chat already has one
— `ChatController` is constructed with a scope and owns the run — so route the
submit through the controller rather than launching in the composable. Failing
that, clear the draft only *after* `onSend` returns, so a cancelled send leaves
the text where the user can see and resend it. Clearing first is only safe when
the work is not cancellable.
