# The local tier — serving local AI models to huginn

Status: **spec, decisions locked 2026-08-25 · implementation in progress.**
Read `docs/ADDING-A-FEATURE.md` first, as always. This document is the implementation
reference for the local tier across its four cuts (P1–P4 below). The conduits
(delegation lane, escalate door) are a later phase and appear here only as named seams.

## What it is

huginn's package family gains a third, optional tier:

1. **CLI** — the base, required everywhere (`huginn.sh` / `huginn.ps1`).
2. **desktop** — the Compose app, with the Devices runner compiled in.
3. **local** — *this machine serves local AI models to huginn.* Optional, heavy
   (an inference runtime + models ≈ 5–6 GB), and therefore never inside an installer:
   the installers only make an **offer**; activation is a runtime act of local consent.

Activation — `huginn local on` (CLI) or the desktop Settings section "Serve local AI
from this PC" — installs a **version-pinned `llama-server` + `llama-swap` stack** as an
unattended service bound to `127.0.0.1` with a minted API key, pulls GGUF models chosen
for the machine's hardware class, and enrols the machine as a **second device row**
(`<host>-llm`) at a new **`generate`** scope. Local models then appear in the existing
model picker as `family:"local"` rows named like *"Qwen3 8B · gpubox"*; picking the row
**is** the host choice. Chats on local models are forced ask-mode. Rounds refuse the
local family. The device runner spawns `huginn-llm-shim`, which speaks the claude-CLI
argv/stream-json contract on the outside and authenticated OpenAI-style HTTP on the
inside — so transcripts, SSE, push, sealing, cancel and queueing are all the existing
Devices machinery, unchanged.

One daemon, one owner: huginn stays single-user **per appd instance**. Another person
installs their own huginn, with their own token, devices, and local tier. The daemon
never grows a concept of users.

## Guardrails (must hold in every cut)

- **No silent engine substitution, in either direction.** An unknown model id is a loud
  400 at the button. A dead local endpoint errors into the chat. Nothing ever quietly
  falls back from local to Claude or Claude to local.
- **`generate` is the enrolment floor from day one** — never `look` (which grants
  Read/Glob/Grep/WebFetch, authority a text generator must not hold).
- **The device-declared model list is display, never routing authority.**
- **Loopback only, authenticated.** No inference endpoint on any non-loopback interface;
  the API key is minted at activation; a keyless request must 401 or the install fails.
- **Pinned runtimes, gated bumps.** Runtime and model versions live in a generated
  manifest inside the manager; a bump is by construction a reviewed `cli-v*` release.
- **No context egress.** The shim is not the claude CLI and loads nothing; generate-mode
  argv carries no persona flag. The serving machine never sees huginn's identity.
- **Drift assumed, then detected.** The shim reports `version+contenthash`, carries
  `--contract-check`, and joins the deployed-vs-tree gate and the sync carry list.
- **Fail closed, admit failure.** Every refusal states its reason; every step of
  activation and deactivation reports individually and never claims what it did not do.

## The four cuts

### P1 — gate honesty (appd 2.73.0 · app 2.79.0 · desktop 0.8.15)

Ships alone, first, valuable regardless of the tier:

- `validModel`/`validEffort` (silent-null validators) are **replaced** by
  `modelDecision`/`effortDecision`: absent/null/empty still means the host default; a
  known id passes; an **unknown non-empty id is a 400** naming the id and pointing at
  `/v1/models`. Applied at all four call sites (chat create/PATCH, round create/PATCH);
  a 400 PATCH no longer half-applies the rest of the body. Today a typo'd model silently
  runs on the default model — miniature silent substitution, live at every site.
- Kotlin `HuginnClient.updateRound` gains `model`/`effort` (the daemon always accepted
  them; the client could never send them — a Round's model was unchangeable for life).
  Empty string clears, per the goal-clearing precedent.

### P2 — the `generate` policy rung (appd 2.74.0 · cli 0.11.0) — its own release

`shared/device-policy.json` (via `scripts/gen-device-policy.js`, never hand-edited):

- `scopes: ["generate", "look", "work", "own"]` — generate is `SCOPES[0]`, the
  fail-closed floor for junk scopes in all three programs.
- **`exclusiveScopes: ["generate"]`** — an exclusive rung matches only itself, both
  ways: a generate device runs *only* generate work, and generate work runs *only* on a
  generate device. Plain rank ordering would let `own` satisfy generate and a claude
  engine would answer a local-model request — the banned substitution.
- `modeNeeds: { ask: look, act: work, generate: generate }` — **generate is a third
  mode carried in the WORK ITEM only**; the chat-level wire vocabulary stays
  `{ask, act}` (every ingress coerces unknown chat modes to ask, so a chat-level third
  mode would be silently rewritten by an older daemon).
- `enrolDefault: "look"` — an *absent* scope at registration still means a read-only
  claude device; only *junk* floors to generate.
- **Lock:** exclusive scopes ignore `lockDropsTo` — a locked generate device keeps
  serving (a generate run mutates nothing, and dropping to look would sideways-grant
  ask, a mode the row has no engine for).
- **Generate argv = streamFlags + `--model` + `--resume`, nothing else.** No tool flags
  (the shim has no tool surface; absence is the fence), no `--append-system-prompt`,
  no `--effort`.
- The engine fence lives in the runners (the policy cannot see `conf` contents): the
  headless runner spawns `conf.llm` only for generate and refuses when unset; the
  desktop Compose runner refuses generate unconditionally (serving must survive
  logout, so it is always the headless service). Both say `refusals.engine`.
- Daemon: `scopeCovers()` (exclusivity-aware) replaces exported `scopeAtLeast`;
  `effectiveScope` exemption for exclusive scopes; `workItem` learns mode generate;
  scope normalization (trim+lowercase) at registration. `huginn-device on --scope
  generate` is refused ("generate enrolment is created by `huginn local on`").
- Case matrix grows 48 → 144 rows (hostile scopes + generate); two hand-rolled UI
  policy fragments (Round editor, Devices buttons) move onto `DevicePolicy.allows`.
- Pre-deploy: audit every enrolled device's stored scope (all ∈ {look, work, own},
  none generate, no junk) and back up the device registry.

### P3 — the manager, the shim, the daemon surface (appd 2.75.0 · cli 0.12.0)

**`huginn local on|off|status|unit|update|doctor`** — the `huginn device` grammar
exactly: consent → fetch pinned → validate → install → enrol. All five parity sites.
The fetched **manager** (`client/huginn-local`, Node) owns:

- **Hardware classes**: G16 (2 GPUs ≥7.5 GiB — detection-only in manifest v1),
  G8 (one ≥7.5 GiB GPU → 8B-class Q4, pinned to one card by UUID), C (x86 AVX2 ≥7.5 GiB
  RAM → 3–4B Q4), A (arm64 ≥7 GiB → 3B Q4, batch-only), plus an embed model in every
  class. Below floor → refusal naming the floor. The chosen device and class are always
  printed. `--class` may narrow, never widen.
- **Disk gate before any download** (need + 2 GiB headroom, both numbers printed).
- **The pin manifest** (`shared/local-runtime.json` → generated into the manager by
  `scripts/gen-local-manifest.js` between markers, `--check` gated): llama.cpp release +
  per-platform assets + sha256s, llama-swap version, WinSW (Windows), and the model
  catalog per class (HF repo/file/bytes/license). Everything sha256-verified before
  unpack; models pulled from Hugging Face by exact file.
- **Services**: Linux systemd units (system + user variants) pinning `HOME` and
  `HUGINN_LOCAL_DIR`; Windows **WinSW** (pinned) running llama-swap and the runner as
  LocalSystem under `%ProgramData%\huginn-local` — alive with nobody logged in, which is
  the point. llama-swap listens on `127.0.0.1:8748`; children get loopback `${PORT}`s.
- **Health, honestly**: the model-list **body** must be nonempty (never a TCP connect),
  and a keyless request must 401.
- **Enrolment**: `huginn-device on` with its own `HUGINN_DEVICE_DIR`, `--name
  <host>-llm`, `--scope generate`, `conf.claude` = the shim. The runner **aborts unless
  the daemon echoes `scope:"generate"`** (an old daemon would silently floor it).
- **Adapter mode**: wrap an existing Ollama/LM Studio endpoint (loopback only, model
  list read from the body) instead of installing the stack. Detect, never auto-activate.
  Never bundle LM Studio (ToS) or GPU drivers (EULA) — detect and instruct.
- **`off`**: stop runner first, then llm; deregister with the device-off honesty;
  models kept by default, deleted only behind an explicit typed confirmation.
  **`update`**: manager+shim from the house channels; runtime/model bumps only via a
  manifest change riding a `cli-v*` release. **`status --json`** feeds the desktop.

**`huginn-llm-shim`** (`client/huginn-llm-shim`, Node, importable): accepts the argv
`argvFor()` produces; emits exactly what `handleClaudeEvent` reads — `system/init`
immediately (minted uuid, `version+contenthash`), `stream_event` text deltas, `assistant`
frames (chunked ≤100 KB — no oversized line is ever emitted), one terminal `result` with
`is_error`/`duration_ms`/`total_cost_usd: 0`/`num_turns: 1`. `--resume` replays a
size-capped per-session JSONL. A 120 s no-token watchdog aborts with an honest error.
An unknown `--model` is an error result, never a substitution. Failures after startup
are error *results* (exit 0); nonzero exit is reserved for contract-level failure.
`--contract-check` emits a canned transcript for the drift gate.

**Daemon surface** (appd 2.75.0):

- **Ids**: `local-<llmSlug>-<modelSlug>` (fits `^[a-z0-9-]{2,60}$`). `llmSlug` is
  **minted by the daemon** at first generate registration, persisted, echoed — daemon
  and shim agree by handshake, never parallel derivation. Same model on two hosts =
  two distinct rows.
- `GET /v1/models?local=1` unions per-request rows from the device registry (no cache —
  `available` is time-dependent): `{id, display, family:"local", available, host}`.
  Old clients never send the param, so they never see the rows.
- **Creation gates**: a local-family model forces `host` to its device and `mode` to
  ask (act → 400; offline → 409 with the machine named; a disagreeing client-supplied
  host → 400). PATCH to act → 409. Model changes across hosts/engines → 409 ("start a
  new chat"). Rounds refuse the local family at build, patch, **and** fire (defensive,
  records a skipped run).
- Registration accepts `models: [{slug, display}]` (≤16, cleaned, display-only,
  generate-scope only); the transcript route forks on *file existence* instead of
  `meta.host`; `costUsd` is nulled for local-family runs (usage/autoswitch/accounts
  never see them).

### P4 — client polish + the offer (app 2.80.0 · desktop 0.9.0)

- `ModelLabels.options(models, site)` in `:core` — one function, four picker sites,
  typed rows (group headers, disabled-with-reason). The **Local** group appears only at
  CHAT sites; SESSION pickers exclude it (a session chip types `/model` keystrokes into
  a live pane — a local row there is a fake control). Effort hidden for local; act
  disabled with "local models are Ask-only"; unavailable rows visible-but-unpickable
  with the daemon's reason. The phone's duplicate label tables die in the same pass.
- Desktop Settings **"Serve local AI from this PC"**: mirrors the device section; shells
  out to the *same* fetched manager (one implementation, two doors), streams its output,
  polls `status --json` while visible, holds no serving state of its own. A below-floor
  refusal snaps the switch back off with the reason. Never remotely flippable — by
  construction and in its own copy.
- The offer: one line at the end of `install.sh`/`install.ps1`; a one-time dismissible
  card on desktop first launch. Both roads lead to the same activation. Never twice.
- Devices screen: a generate row says what it is ("serves local models · …"), hides
  Ask/Act here, keeps Forget. Round editor host chips filter generate devices out.
- The P6 escalate door gets a named seam (`EscalateSeam` in `:core`, drawn nowhere
  while unavailable) — attachment point: after the mode chip in the chat options row,
  local chats only, user-driven only.

## Test discipline

Port ranges (each file's header is the registry; the width makes the range):
`routes-modelgate.test.js` **10000–10049** (P1) · `routes-localmodels.test.js`
**10050–10099** (P3) · `scripts/test-llm-shim.js` **18790–18799**. Every new test is
verified by making it fail once (stash the implementation, run, restore). Floors in
`scripts/test-floors.env` are bumped to *measured* counts, never arithmetic. Gates run
unpiped. `test-client.sh` gains the local-tier section: version agreement, manifest
`--check`, unit env pinning, unknown-flag refusal, disk-gate refusal, off-honesty,
contract replay through a `handleClaudeEvent`-shaped verifier, and two new
deployed-vs-tree lines.

## Release map

| Cut | appd | cli | app | desktop |
|-----|------|-----|-----|---------|
| P1 gate honesty | 2.73.0 | — | 2.79.0 | 0.8.15 |
| P2 policy rung | 2.74.0 | 0.11.0 | — | — |
| P3 manager + shim + surface | 2.75.0 | 0.12.0 | — | — |
| P4 picker + serving section + offer | — | — | 2.80.0 | 0.9.0 |

Every client-facing change is additive behind opt-in; the two coupled edges (the policy
lattice, the llmSlug handshake) ship daemon-first with a device-side fail-closed check.
