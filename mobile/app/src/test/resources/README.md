# Wire fixtures — what each one is, and when it was last true

Every file here is a real `huginn-appd` response body, scrubbed. `ApiContractTest`
decodes them against the models in `:core`, which is the only automated check
that the clients and the daemon still agree on the wire format.

**The scrub rule.** Every KEY and every value whose SHAPE matters — numbers,
booleans, enum-like strings, id formats, array lengths — is untouched. Free text
(session names, titles, message bodies, emails, paths, tokens) is replaced with a
neutral literal of the same shape and length class, usually
`<field scrubbed, N chars>`. A uuid stays 36 characters because a test asserts it.

**Why an assertion and not just a fixture.** `Json { ignoreUnknownKeys = true }`
means an unread field is invisible to a decode: a fixture alone proves nothing
about a name. Only an assertion on the field NAME turns a daemon-side rename into
a red test instead of a blank screen on a phone this host cannot run. Where a
field is on the wire but deliberately undecoded (`accounts[].red`,
`sentinels[].*`), the assertion is on the raw JSON.

## Capture log

Everything below was taken from the live daemon on **huginn**, **appd 3.5.1**
(`GET /v1/ping`), on **2026-09-19**, unless the row says otherwise.

| Fixture | Route | Captured | Notes |
|---|---|---|---|
| `sessions.json` | `GET /v1/sessions?preview=1` | 3.5.1 · 2026-09-19 | The preview answer. The cheap list (no `?preview=1`) is a strict subset: `title`, `preview`, `permissionMode`, `liveModel`, `liveMode`, `contextPercent` and the `bg*` trio are only filled by the preview pass. |
| `screen.json` | `GET /v1/sessions/<n>/screen` | **NOT captured** — derived from `paneScreen` in `huginn-appd.js` at 3.5.2 | ⚠ This route is deliberately never called from a working session: on older builds a plain screen read could take a pane lease. Re-derive from the daemon's source, do not GET it. |
| `transcript.json` | `GET /v1/sessions/<n>/transcript` | 3.5.1 · 2026-09-19 | |
| `transcript-limit.json` | same route, synthesized tail | 3.5.1 field set · 2026-09-19 | A 429 stall is an ordinary assistant record carrying `apiError`; the surrounding page is a real capture's shape. |
| `agent-transcript.json` | `GET /v1/sessions/<n>/agents/<id>/transcript` | 3.5.1 field set · 2026-09-19 | `readTranscript` spread + `agentId`, `workflowId`, `modelDisplay`. |
| `status.json` | `GET /v1/status` | 3.5.1 · 2026-09-19 | |
| `status-legacy.json` | synthetic | — | A pre-3.0.0 daemon with no `headroom` block. Never re-capture; it exists to prove the pill hides rather than reading 0 %. |
| `headroom.json` | `GET /v1/headroom` | 3.5.1 · 2026-09-19 | |
| `headroom-idle.json` | same route, idle host | 3.5.1 field set · 2026-09-19 | `settings` and `keepAwake` ride EVERY answer; there is no branch that omits either. |
| `plan.json` | `GET /v1/plan` | 3.5.1 · 2026-09-19 | The success case. The live capture also carried `error: "plan usage HTTP 429"` beside cached numbers — `Plan.error` is modelled, and a body can carry both. |
| `plan-legacy.json` | synthetic | — | 2.85.0, no `account` block. |
| `accounts.json` | `GET /v1/accounts?plan=1` | 3.5.1 field set · 2026-09-19 | ⚠ Captured WITHOUT `plan=1` and the four plan fields re-derived from `huginn-appd.js`: `plan=1` calls `planForCredentials` per saved profile, and polling the usage endpoint beside the daemon earns a 429. `weeklyPercent`, `sessionPercent`, `planLive`, `planAgeSec` appear only on that variant. |
| `chats.json` | `GET /v1/chats` | 3.5.1 · 2026-09-19 | Round-run chats are filtered out of this list, so `sealed`/`endedAt`/`roundId` never appear on it. |
| `devices.json` | `GET /v1/devices` | 3.5.1 · 2026-09-19 | |
| `rounds.json` | `GET /v1/rounds` | 2026-08-25, re-verified against 3.5.1 on 2026-09-19 | No drift. `lastRun.acknowledgedAt` is absent until a run is marked done, which is why the live host had none. |
| `watch.json` | `GET /v1/watch` | 3.5.1 · 2026-09-19 | |
| `watch-never-resumed.json` | synthetic | — | The fresh-install case: `lastResumeAt: null` and no `pushEpoch`. |
| `agents-all.json` | `GET /v1/sessions/<n>/agents?all=1` | 2026-09-15, re-verified against 3.5.1 on 2026-09-19 | No drift. |
| `projects.json`, `project-detail.json`, `project-dashboard.json`, `spawn-result.json` | `/v1/projects*` | generated from `lib/projects.js` · 2026-09-18 | The daemon modules' OWN output, not a capture — a rename on the daemon changes these files. |
| `apps.json`, `app-422.json`, `consoles.json` | `/v1/apps*`, `/v1/consoles*` | generated from `lib/apps.js` · 2026-09-18 | Same. `/v1/consoles*` is an alias for one release. |

## Units, because there is no field-name tell

- `/v1/headroom` and `/v1/watch`: **milliseconds**, every `at`, including
  `serverTime`, `ladder.at`, `headsUpAt`, `stall.resetsAt`, `held[].since`,
  `accounts[].readAt`, `keepAwake.lastAt` / `evaluatedAt` / `nextEligibleAt`.
- `/v1/sessions`, `/v1/chats`, `/v1/projects`, `/v1/accounts`: **seconds**.
- Instants the user reads (`resets_at`, `windowResetsAt`, `red.*.resetsAt`,
  `watch.headroom.stalls[*]`) stay **ISO strings**.
- Wall clocks (`keepAwake.lastAtClock`, the `"resets 10:10pm"` inside
  `stall.text`) are formatted **by the daemon**: `:core` is commonMain and has no
  timezone database.

## How to refresh

```sh
T=$(cat /etc/huginn-appd/token)
curl -s -H "Authorization: Bearer $T" http://127.0.0.1:8787/v1/ping      # record the version
curl -s -H "Authorization: Bearer $T" http://127.0.0.1:8787/v1/<route>   # GET only
```

GET only, never a `/keys` route, never a session's `screen` route, and never
`?plan=1` against `/v1/accounts` on a host whose daemon is doing real work.
Scrub, diff the key set and value types against the fixture, then assert the
added names in `ApiContractTest` — a fixture that grew a field nothing asserts is
a fixture that will not notice when the field goes away.
