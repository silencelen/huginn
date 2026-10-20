# The local AI tier: serving local models to Huginn

**Status:** Implemented in appd 2.73.0 to 2.75.0, CLI 0.11.0 to 0.12.0, Android app 2.79.0 to
2.80.0 and desktop 0.8.15 to 0.9.0 (August 2026); persistence across logout added in CLI 1.1.0
and appd 3.1.0.
**Experimental.** Two details have moved since: the desktop app's built-in device runner was
replaced by huginn core's runner in core 2.0.0, and since core 2.5.0 the core hands the tier its
token. For current behaviour, see the [local AI guide](../guide/local-ai.md).

**Summary:** a client computer with enough GPU or CPU can serve local models to the Huginn server.
Activation (`huginn local on`, or the desktop's "Serve local AI from this PC") installs a
version-pinned `llama-server` plus `llama-swap` stack bound to loopback with a minted API key,
pulls models chosen for the machine's hardware class, and enrols the machine as a second device row
at a new **`generate`** scope. Local models appear in the chat model picker; picking one is the
host choice. A shim speaks the claude CLI's argv and stream-json contract on the outside and
authenticated OpenAI-style HTTP on the inside, so transcripts, streaming, push, cancel and queueing
reuse the existing device machinery unchanged. The design's central rule is that nothing ever
silently substitutes one engine for another.

The conduits (a delegation lane and an escalate door) were designed separately and appear here only
as named seams.

## What it is

Huginn's client package family gains a third, optional tier:

1. **CLI:** the base, required everywhere.
2. **desktop:** the Compose app.
3. **local:** *this machine serves local AI models to Huginn.* Optional and heavy (an inference
   runtime plus models is about 5 to 6 GB), and therefore never inside an installer: installers
   only make an **offer**, and activation is a runtime act of local consent.

Activation installs the stack as an unattended service bound to `127.0.0.1` with a minted API key,
pulls GGUF models chosen for the machine's hardware class, and enrols the machine as a **second
device row** (`<host>-llm`) at the **`generate`** scope. Local models then appear in the existing
model picker as `family:"local"` rows named like *"Qwen3 8B · gpubox"*; picking the row **is** the
host choice. Chats on local models are forced to ask mode. Rounds refuse the local family. The
device runner spawns `huginn-llm-shim`, which speaks the claude CLI's contract outside and
authenticated OpenAI-style HTTP inside.

One daemon, one user: Huginn stays single-user **per appd instance**. Another person installs their
own Huginn, with their own token, devices and local tier. The daemon never grows a concept of
users.

## Guardrails

These hold in every cut:

- **No silent engine substitution, in either direction.** An unknown model id is a loud 400 at the
  button. A dead local endpoint errors into the chat. Nothing ever quietly falls back from local to
  Claude or from Claude to local.
- **`generate` is the enrolment floor from the start**, never `look`, which grants
  Read/Glob/Grep/WebFetch: authority a text generator must not hold.
- **The device-declared model list is display, never routing authority.**
- **Loopback only, authenticated.** No inference endpoint on any non-loopback interface; the API key
  is minted at activation; a keyless request must get 401 or the install fails. An **adopted**
  engine clears the same gate rather than being trusted for being local.
- **Say whether it survives logout, everywhere.** A serving machine is an offer to every client on
  the daemon, so "will this still be here when nobody is signed in" is part of the offer. Status,
  `doctor`, the desktop section and the device row all answer it from one computation, `null`
  ("could not be asked") included; `doctor` fails on a user unit without linger.
- **Pinned runtimes, gated bumps.** Runtime and model versions live in a generated manifest inside
  the manager; a bump is by construction a reviewed CLI release.
- **No context egress.** The shim is not the claude CLI and loads nothing; generate-mode argv
  carries no persona flag. The serving machine never sees the server's identity or persona.
- **Drift assumed, then detected.** The shim reports `version+contenthash` and carries
  `--contract-check`, so a test can compare the installed copy with the tree.
- **Fail closed, admit failure.** Every refusal states its reason; every step of activation and
  deactivation reports individually and never claims what it did not do.

## The four cuts

### P1: gate honesty (appd 2.73.0, app 2.79.0, desktop 0.8.15)

Shipped alone and first, valuable regardless of the tier:

- The silent-null validators `validModel`/`validEffort` are **replaced** by
  `modelDecision`/`effortDecision`: absent, null or empty still means the host default; a known id
  passes; an **unknown non-empty id is a 400** naming the id and pointing at `/v1/models`. Applied at
  all four call sites (chat create and PATCH, round create and PATCH). A 400 PATCH no longer
  half-applies the rest of the body. Before this, a mistyped model silently ran on the default
  model: a miniature silent substitution, live at every site.
- Kotlin `HuginnClient.updateRound` gains `model` and `effort`. The daemon always accepted them,
  but the client could never send them, so a round's model was fixed for life. An empty string
  clears, following the goal-clearing precedent.

### P2: the `generate` policy rung (appd 2.74.0, CLI 0.11.0)

`shared/device-policy.json` (generated into each program by `scripts/gen-device-policy.js`, never
hand-edited):

- `scopes: ["generate", "look", "work", "own"]`: generate is `SCOPES[0]`, the fail-closed floor for
  junk scopes in every program.
- **`exclusiveScopes: ["generate"]`**: an exclusive rung matches only itself, both ways. A generate
  device runs *only* generate work, and generate work runs *only* on a generate device. Plain rank
  ordering would let `own` satisfy generate, and a claude engine would answer a local-model request:
  the banned substitution.
- `modeNeeds: { ask: look, act: work, generate: generate }`: **generate is a third mode carried in
  the work item only.** The chat-level wire vocabulary stays `{ask, act}`, because every ingress
  coerces unknown chat modes to ask, so a chat-level third mode would be silently rewritten by an
  older daemon.
- `enrolDefault: "look"`: an *absent* scope at registration still means a read-only claude device;
  only a *junk* scope floors to generate.
- **Lock:** exclusive scopes ignore `lockDropsTo`. A locked generate device keeps serving: a
  generate run mutates nothing, and dropping to look would grant ask sideways, a mode the row has
  no engine for.
- **Generate argv is the stream flags plus `--model` plus `--resume`, nothing else.** No tool flags
  (the shim has no tool surface; their absence is the fence), no `--append-system-prompt`, no
  `--effort`.
- **The engine fence lives in the runners**, because the policy cannot see the runner's
  configuration: the headless runner spawns the configured local engine only for generate work and
  refuses when none is set. In this cut the desktop's built-in runner refused generate
  unconditionally, since serving must survive logout and so is always the headless service. Since
  core 2.0.0 that runner no longer exists; huginn core's runner applies the same fence (generate
  work needs a configured local engine and never falls through to claude).
- Daemon: an exclusivity-aware `scopeCovers()` replaces `scopeAtLeast`; `effectiveScope` exempts
  exclusive scopes from the lock drop; the work item learns mode generate; scopes are normalized
  (trimmed and lowercased) at registration. `huginn-device on --scope generate` is refused
  ("generate enrolment is created by `huginn local on`").
- The policy case matrix grows from 48 to 144 rows (hostile scopes plus generate); two hand-rolled
  UI policy fragments (the round editor, the Devices buttons) move onto `DevicePolicy.allows`.

### P3: the manager, the shim, the daemon surface (appd 2.75.0, CLI 0.12.0)

**`huginn local on|off|status|unit|update|doctor`** follows the `huginn device` grammar exactly:
consent, fetch pinned, validate, install, enrol. The **manager** (`client/huginn-local`, Node) owns:

- **Hardware classes:** G16 (two GPUs of at least 7.5 GiB; detected and reported, but installs the
  G8 plan), G8 (one GPU of at least 7.5 GiB: an 8B-class Q4 model, pinned to one card by UUID), C
  (x86 with AVX2 and at least 7.5 GiB RAM: a 3 to 4B Q4 model), and A (arm64 with at least 7 GiB: a
  3B Q4 model, batch use only), plus an embedding model in every class. Below the floor, a refusal
  names the floor. The chosen device and class are always printed. `--class` may narrow, never
  widen.
- **A disk gate before any download:** what is needed plus 2 GiB headroom, both numbers printed.
- **The pin manifest** (`shared/local-runtime.json`, generated into the manager by
  `scripts/gen-local-manifest.js` between markers and checked with `--check`): the llama.cpp release
  with per-platform assets and sha256s, the llama-swap version, WinSW on Windows, and the model
  catalog per class (Hugging Face repo, file, size, license). Everything is sha256-verified before
  unpacking; models are pulled from Hugging Face by exact file.
- **Services:** Linux systemd units (system and user variants) pinning `HOME` and
  `HUGINN_LOCAL_DIR`; on Windows a pinned **WinSW** runs llama-swap and the runner as LocalSystem
  under `%ProgramData%\huginn-local`, alive with nobody signed in, which is the point. llama-swap
  listens on `127.0.0.1:8748`; its children get loopback ports.
- **Health, honestly:** the model-list **body** must be non-empty (never just a TCP connect), and a
  keyless request must get 401.
- **Enrolment:** the device runner (`huginn-device on`) with its own `HUGINN_DEVICE_DIR`,
  `--name <host>-llm`, `--scope generate`, and the shim as its engine. The runner **aborts unless the
  daemon echoes `scope:"generate"`**, because an old daemon would silently floor the scope.
- **Adapter mode:** wrap an existing **llama-swap**, Ollama or LM Studio endpoint (loopback only,
  model list read from the body) instead of installing the stack. Detect, never auto-activate. Never
  bundle LM Studio (its terms of service) or GPU drivers (their EULAs): detect and instruct.
  `--adapter llama-swap` adopts a hand-configured llama-swap rather than managing it: it clears the
  same keyless-completions gate a managed install does, it is refused on top of a managed install
  (they would share `local.json` and `api-key`), and `off` takes only the runner down. An adopted
  engine is never stopped, uninstalled or stripped of its key.
- **`off`:** stop the runner first, then the engine; deregister with the same honesty as
  `huginn device off`; then **the credentials go with the row**: `local.json`, `api-key` and
  `device/appd-token` are removed, because a tier that is off has no business keeping a live bearer
  token on disk. Models are kept by default and deleted only behind an explicit typed confirmation:
  `--purge-models` for the models and sessions, `--purge` for the whole tier including the runtime,
  the unit files and the tier directory itself. `--purge` refuses to delete a `HUGINN_LOCAL_DIR`
  that resolves to a home directory or a filesystem root.
- **`update`:** the manager and shim update through the client's own update path; runtime and model
  bumps come only through a manifest change in a CLI release.
- **`status --json`** feeds the desktop.

**`huginn-llm-shim`** (`client/huginn-llm-shim`, Node, importable) accepts the argv the policy
produces and emits exactly what the daemon's event handler reads: `system/init` immediately (a
minted uuid, `version+contenthash`), `stream_event` text deltas, `assistant` frames (chunked at
100 KB or less, so no oversized line is ever emitted), and one terminal `result` with `is_error`,
`duration_ms`, `total_cost_usd: 0` and `num_turns: 1`. `--resume` replays a size-capped per-session
JSONL. A 120 s watchdog aborts with an honest error when the engine streams nothing for that long
(a reasoning model's thinking counts). An unknown `--model` is an error
result, never a substitution. Failures after startup are error *results* (exit 0); a non-zero exit
is reserved for contract-level failure. `--contract-check` emits a canned transcript for the drift
test.

**Daemon surface** (appd 2.75.0):

- **Ids:** `local-<llmSlug>-<modelSlug>` (fits `^[a-z0-9-]{2,60}$`). `llmSlug` is **minted by the
  daemon** at the first generate registration, persisted and echoed, so the daemon and the shim
  agree by handshake, never by deriving the same thing in parallel. The same model on two hosts is
  two distinct rows.
- `GET /v1/models?local=1` unions per-request rows from the device registry, uncached because
  `available` depends on time: `{id, display, family:"local", available, host}`. Old clients never
  send the parameter, so they never see the rows.
- **Creation gates:** a local-family model forces `host` to its device and `mode` to ask (act gets
  400; an offline device gets 409 naming the machine; a disagreeing client-supplied host gets 400).
  A PATCH to act gets 409. Model changes across hosts or engines get 409 ("start a new chat").
  Rounds refuse the local family at build, at patch **and** at fire (defensive; the round records a
  skipped run).
- Registration accepts `models: [{slug, display}]` (at most 16, cleaned, display only, generate
  scope only); the transcript route forks on whether the file exists instead of on `meta.host`;
  `costUsd` is null for local-family runs, so usage, automatic account switching and accounts never
  see them.

### P4: client polish and the offer (app 2.80.0, desktop 0.9.0)

- `ModelLabels.options(models, site)` in `:core`: one function for the four picker sites, with typed
  rows (group headers, disabled-with-reason). The **Local** group appears only at chat sites;
  session pickers exclude it, because a session's model chip types `/model` keystrokes into a live
  pane, where a local row would be a fake control. Effort is hidden for local models; act is
  disabled with "local models are Ask-only"; unavailable rows are visible but not pickable, with the
  daemon's reason. The phone's duplicate label tables were removed in the same pass.
- The desktop's **"Serve local AI from this PC"** mirrors the device section. It shells out to the
  *same* manager (one implementation, two doors), streams its output, polls `status --json` while
  visible, and holds no serving state of its own. A below-floor refusal snaps the switch back off
  with the reason. It can never be flipped remotely, by construction and in its own wording.
- **The offer:** one line at the end of the installers, and a one-time dismissible card on the
  desktop's first launch. Both lead to the same activation, and neither is shown twice.
- The Devices screen says what a generate row is ("serves local models"), hides Ask and Act for it,
  and keeps Forget. The round editor's host chips filter generate devices out.
- The escalate door gets a named seam (`EscalateSeam` in `:core`, drawn nowhere while unavailable).
  Its attachment point is after the mode chip in the chat options row, for local chats only, and
  user-driven only.

## Persistence (CLI 1.1.0)

The requirement: other clients connected to the same server can use a serving machine for as long
as it runs. The routing already allowed that (any client holding the daemon's bearer token can pick
a `local-<llmSlug>-<modelSlug>` row), and Windows already did it (WinSW runs both services as
LocalSystem, in session 0). On Linux it was **false, and said nowhere**: user units stopped at
logout.

- **Linux uses system units behind one elevation prompt**, the Windows shape. `huginn local on
  --system` re-runs the whole verb under `pkexec` (then `sudo`), carrying `HOME`, `HUGINN_LOCAL_DIR`
  and `HUGINN_LOCAL_USER` explicitly because both elevators wipe the environment. An elevated run
  hands the tier directory back to the user before it starts anything that runs as the user, and
  again however it ends: a root-owned 0600 `api-key` is a model server that answers 401 to its own
  runner, and a root-owned `local.json` reads as "not set up". The desktop passes `--system` and
  lets the manager own the elevation, so one implementation stays behind both doors.
- **Declined or unavailable elevation falls back to linger**, out loud. `loginctl enable-linger` is
  attempted for user units on every Linux install, and the result is printed either way;
  `org.freedesktop.login1.set-self-linger` usually needs no password in an active session. An
  elevated run that started and then failed is that failure, not a declined prompt (the elevated run
  leaves a mark to say it started), so nothing falls back over what it wrote as root.
- **`huginn local persist`** migrates an existing user install. The user half runs unelevated first
  (root's `--user` bus is root's own, and would exit 0 having stopped nothing), then the `/etc` half
  takes the one prompt. If the prompt is declined, it puts the user units back and enables linger
  instead.
- **Everything reports it truthfully.** The manager's status gains `systemUnits`, `linger`,
  `persistent` (all three-valued: `null` means "this machine could not be asked") and `adopted`;
  `status` prints a persistence line; `doctor` fails on a user unit without linger; the consent
  card's "installs two always-on services" wording branches per OS and per whether an elevator
  exists (`plan --json` carries `elevation`).
- **A `persistent` facet on the generate device row**, nullable on the wire (absent from an older
  CLI, and absent all the way to the client), renders in the shared device description as "always
  on" or "only while someone is logged in", so the phone and the desktop get it from one label. It
  rides the heartbeat as well as the enrolment, because `persist` changes a machine whose healthy
  runner never re-enrols.

**Not in this cut:** routing rounds to a persistent machine, GPU serving on Linux (still refused:
there is no pinned CUDA asset for Linux), per-client identity, and automatic fallback to Claude.

## After the cuts

- **huginn core (2.0.0)** replaced the desktop's built-in runner. The generate row's runner is still
  the Node device runner that the manager installs as a service.
- **The desktop through the core (core 2.5.0, desktop 2.5.1):** the desktop no longer holds the
  server token, so the core hands the tier its token (`POST /core/local/seed`) and names the address
  to turn it on with ([desktop-via-core.md](desktop-via-core.md)).
- **Setup (2.4.2):** the one-line installers ask whether to serve local AI only where
  `huginn local plan` finds a usable GPU and the tier is not set up. `huginn uninstall` asks about
  the tier separately, with its size, and removes it elevated on Windows.

## Testing

- Each new test file reserves its own port range in its header (`routes-modelgate.test.js`
  10000 to 10049, `routes-localmodels.test.js` 10050 to 10099, `scripts/test-llm-shim.js` 18790 to
  18799).
- Every new test is verified by making it fail once (stash the implementation, run, restore).
- Test-count floors are raised to *measured* counts, never computed ones.
- `scripts/test-client.sh` has a local-tier section: version agreement, the manifest `--check`, unit
  environment pinning, unknown-flag refusal, disk-gate refusal, off honesty, and a contract replay
  through a verifier shaped like the daemon's event handler.

## Release map

| Cut | appd | CLI | Android app | desktop |
|-----|------|-----|-------------|---------|
| P1 gate honesty | 2.73.0 | — | 2.79.0 | 0.8.15 |
| P2 policy rung | 2.74.0 | 0.11.0 | — | — |
| P3 manager, shim, daemon surface | 2.75.0 | 0.12.0 | — | — |
| P4 pickers, serving section, offer | — | — | 2.80.0 | 0.9.0 |
| Persistence | 3.1.0 (`persistent` facet) | 1.1.0 | — | — |

Every client-facing change is additive and opt-in. The two coupled edges (the policy lattice and the
`llmSlug` handshake) ship daemon first, with a fail-closed check on the device side.
