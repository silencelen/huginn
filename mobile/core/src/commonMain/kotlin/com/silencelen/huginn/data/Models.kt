package com.silencelen.huginn.data

import com.silencelen.huginn.ui.AppRules
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

// Wire models for huginn-appd's /v1 API. Every field the server may omit is
// nullable with a default so an older app keeps parsing a newer server (the
// devstore lesson: one missing field must not fail the whole decode).

@Serializable
data class Ping(
    val ok: Boolean = false,
    val version: String? = null,
    val host: String? = null,
    /**
     * WHICH LISTENER ANSWERED — the local end of this client's own socket, as the
     * daemon saw it. Present from appd 3.0.7; null against anything older, which
     * is why routes never depend on it.
     *
     * What it is FOR: several pinned routes can address one daemon (tailnet,
     * mesh, loopback), and two pins that land on the same listener are one path
     * wearing two names. `host` alone cannot tell them apart.
     *
     * ⚠ NOT A DIRECTORY. `/v1/ping` needs no token, so this carries only the
     * address the caller already dialled. Any LIST of the daemon's addresses
     * belongs on token-gated `/v1/status`.
     */
    val via: PingVia? = null,
)

@Serializable
data class PingVia(val addr: String? = null, val port: Int? = null)

@Serializable
data class Disk(
    val size: String? = null,
    val used: String? = null,
    val free: String? = null,
    val usedPercent: String? = null,
)

@Serializable
data class Status(
    val host: String? = null,
    val appdVersion: String? = null,
    val uptimeSec: Long = 0,
    val load: List<Double> = emptyList(),
    val cores: Int = 0,
    val claude: String? = null,
    val mempalace: String? = null,
    val disk: Disk? = null,
    val sessions: Int = 0,
    val chatsRunning: Int = 0,
    /**
     * The wrap-up phrase a soft end types into the pane, and whether the host
     * auto-ends the session once it settles. Served so clients can show the
     * exact wording instead of carrying a copy that drifts from the host.
     */
    val softEndPhrase: String? = null,
    val softEndAuto: Boolean = false,
    /**
     * Usage headroom, cheap enough to ride the ordinary status poll.
     *
     * NULL means the daemon predates 3.0.0 and has nothing to say — which is not
     * the same as "plenty left". The pill is hidden in that case rather than
     * drawn at 0 %.
     */
    val headroom: StatusHeadroom? = null,
    /**
     * The wording each selection quick action puts in the composer, held on the
     * HOST for the same reason [softEndPhrase] is: one copy, edited in one place,
     * so the phone and the desktop stage the same text.
     *
     * NULL means the daemon predates 3.0.1 and owns no templates — which is not
     * the same as four blank ones. The selection menu then offers only Quote (the
     * one verb whose text this client writes itself) and the Settings editor is
     * hidden rather than drawn over a file that does not exist.
     */
    val quickActions: QuickActions? = null,
)

/**
 * The four templates, as `/v1/status` serves them and `PATCH /v1/quick-actions`
 * takes them back.
 *
 * `explain`, `execute` and `askInNewChat` carry `{selection}` exactly once — the
 * daemon refuses zero or two — and `quote` is a LEAD-IN prepended above the quote
 * block rather than a template, empty by default. The frame itself is
 * `QuickActionRules.quoteBlock`: it is the one piece of this the client owns.
 */
@Serializable
data class QuickActions(
    val rev: Int = 0,
    val explain: String = "",
    val execute: String = "",
    val askInNewChat: String = "",
    val quote: String = "",
)

@Serializable
data class Session(
    val name: String,
    val createdAt: Long = 0,
    val activityAt: Long = 0,
    val attachedClients: Int = 0,
    val windows: Int = 0,
    /** running | attention | idle | null (no state recorded yet) */
    val state: String? = null,
    val stateSince: Long? = null,
    val cols: Int = 0,
    val rows: Int = 0,
    val windowSize: String? = null,
    val sizeLeased: Boolean = false,
    /** A soft end is pending: the session is winding down and may end itself. */
    val softEnding: Boolean = false,
    /** Context-window pressure (0-100) from the pane statusline; null if unknown. */
    val contextPercent: Int? = null,
    /** The session is compacting its context right now. */
    val compacting: Boolean = false,
    val claudeSessionId: String? = null,
    val hasTranscript: Boolean = false,
    /** Claude Code's own generated session title, far better than the tmux name. */
    val title: String? = null,
    val permissionMode: String? = null,
    /** Last couple of meaningful pane lines: what this session is doing now. */
    val preview: List<String> = emptyList(),
    val liveModel: String? = null,
    val liveMode: String? = null,
    /** Background shells still running, and the longest-running one's command. */
    val bgShells: Int = 0,
    val bgAgents: Int = 0,
    val bgTask: String? = null,
    /** Model ladder + resume state for this row; null on a pre-3.0.0 daemon. */
    val headroom: SessionHeadroom? = null,
    /**
     * Sends the daemon is holding for this session because the turn has not
     * ended yet. 0 on an older daemon, which is also the honest answer there:
     * it had no queue, so nothing was ever waiting.
     */
    val pendingSends: Int = 0,
)

@Serializable
data class CreatedSession(
    /**
     * The name tmux actually used. It can differ from the one requested — tmux
     * rewrites a '.' to '_' and still succeeds — so this is what every later
     * request must use.
     */
    val name: String = "",
)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

/** A Claude Code choice prompt, lifted off the pane so it can become buttons. */
@Serializable
data class PromptOption(
    val number: Int,
    val label: String,
    val selected: Boolean = false,
    /** Multi-select checkbox state; null on rows that are not checkboxes. */
    val checked: Boolean? = null,
    /**
     * The option's explanation, carried when the host fused the hook's exact
     * AskUserQuestion input (the pane scrape alone never has descriptions).
     */
    val description: String? = null,
    /** A TUI-added row ("Type something.", "Chat about this"), not one of the question's own options. */
    val extra: Boolean = false,
)

@Serializable
data class PanePrompt(
    val question: String = "",
    val options: List<PromptOption> = emptyList(),
    /** True when the dialog wants a SET of answers rather than one. */
    val multiSelect: Boolean = false,
    /**
     * Identifies this exact question, computed by the host. An answer carries it back
     * so the host can refuse to type into a pane that has moved on — the app must not
     * compute it, because two implementations of "which question is this" would
     * eventually disagree over a space and reject valid answers.
     */
    val fingerprint: String? = null,
    /** Which of an N-question AskUserQuestion is showing (0-based), when known. */
    val questionIndex: Int? = null,
    val questionCount: Int? = null,
    /** "hook" when the host fused the exact tool input; absent for pane-only. */
    val source: String? = null,
    /**
     * The dialog's tab-strip headers, one per sibling question, present on a
     * pane-only scrape of a multi-question AskUserQuestion. Two or more of these on
     * a pane-only prompt (no fused sidecar) means a single digit would OVER-answer:
     * the digit selects+advances and the Enter confirms the next question's default
     * too. Such a prompt is steered to the Screen tab rather than tap-answered —
     * see [com.silencelen.huginn.ui.PromptGate.paneOnlyMultiQuestion].
     */
    val headers: List<PromptHeader> = emptyList(),
)

/** One tab of a multi-question dialog's header strip. */
@Serializable
data class PromptHeader(
    val label: String = "",
    val checked: Boolean = false,
)

/**
 * A question the hook knows about but the pane scrape could not read (an exotic
 * dialog shape). Rendered as a card so the user sees SOMETHING; answering goes
 * through /answer, which re-checks the pane and refuses with reason=undetected
 * when it still cannot see a live run — the client then opens the Screen tab.
 */
@Serializable
data class DegradedAsk(
    val question: String = "",
    val header: String? = null,
    val options: List<PromptOption> = emptyList(),
    val multiSelect: Boolean = false,
    val questionIndex: Int? = null,
    val questionCount: Int? = null,
    val answerable: Boolean = false,
    val fingerprint: String? = null,
    /**
     * A multi-part AskUserQuestion (several questions in one call). Its tab-strip
     * flow can't be answered by a single button tap, so this card is read-only
     * and directs to the Screen tab.
     */
    val multiPart: Boolean = false,
)

/** A plan approval is waiting (ExitPlanMode), with the plan text when the runtime shipped it. */
@Serializable
data class PlanPending(
    val ts: Long? = null,
    val plan: String? = null,
    val planFilePath: String? = null,
)

/** What POST /v1/sessions/:name/soft-end reports back. */
@Serializable
data class SoftEndResult(
    val ok: Boolean = false,
    /** The wrap-up phrase the host actually typed into the pane. */
    val phrase: String? = null,
    /** Whether the host will auto-end the session once it settles. */
    val auto: Boolean = false,
    /** True when the session was mid-turn, so the phrase queued in the composer. */
    val queued: Boolean = false,
)

/** What POST /v1/sessions/:name/compact reports back (the "context manager" action). */
@Serializable
data class CompactResult(
    val ok: Boolean = false,
    /** The command the host typed — always "/compact". */
    val sent: String? = null,
    /** True when the session was mid-turn, so /compact queued until the turn ends. */
    val queued: Boolean = false,
)

/**
 * A session that was ended ON PURPOSE, and everything needed to bring it back.
 *
 * Deliberately NOT a flag on [Session]: the daemon never lists an archived
 * conversation under /v1/sessions, so the send-target picker, the desktop
 * palette and the home-screen widget — three surfaces that take a plain session
 * list and have no concept of state — inherit nothing and stay correct with no
 * code of their own.
 *
 * Every field is nullable-with-a-default, the house rule, so a row from a newer
 * daemon still decodes here and a row from an older one still renders.
 */
@Serializable
data class ArchivedSession(
    /** The Claude session uuid. The key for everything — never the tmux name,
     *  which is reused within hours on this host. */
    val id: String,
    /** The tmux name it had, and the name a revive asks for first. */
    val tmuxName: String? = null,
    /** Claude Code's own generated title, which is what the row leads with. */
    val title: String? = null,
    val cwd: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val permissionMode: String? = null,
    val gitBranch: String? = null,
    /**
     * The exact command that brings this conversation back, cwd included —
     * `cd '<dir>' && claude --resume <uuid>`. Built by the host, never by a
     * client: this is the one string a person copies into a terminal, and two
     * implementations of it would eventually disagree about a directory with a
     * space in it. Null when the session had no resumable id.
     */
    val resumeCommand: String? = null,
    val archivedAt: Long = 0,
    /** When the session actually died. Null while the kill has not landed. */
    val endedAt: Long? = null,
    val lastMessage: String? = null,
    val transcriptBytes: Long = 0,
    /** The copy is a TAIL of an over-cap conversation, not the whole of it. */
    val transcriptTruncated: Boolean = false,
    val revivedAt: Long? = null,
    /** The tmux name it came back under, which can differ from [tmuxName]. */
    val revivedAs: String? = null,
    /**
     * This conversation is running RIGHT NOW under [revivedAs] (or [tmuxName]).
     * Such a row offers Open, never Revive: a second Claude on one transcript
     * leaves a jsonl neither of them can read.
     */
    val live: Boolean = false,
    /**
     * There is still something to resume — the host's own copy, or Claude Code's.
     *
     * ⚠ FALSE IS THE ROW'S MOST IMPORTANT STATE. Claude Code deletes its own
     * transcripts after `cleanupPeriodDays`, and a revive past that opens a
     * blank conversation in the right directory and looks exactly like a
     * success. Recomputed by the host on every list, because the thing it
     * describes happens while nobody is watching.
     */
    val transcriptPresent: Boolean = false,
)

/**
 * GET /v1/archive.
 *
 * ALSO THE FEATURE PROBE. A daemon older than archive has no such route and
 * answers 404; both clients hide the whole Archived section on that rather than
 * parsing a version — the scratchpads precedent, for its reason: a version
 * string is a claim about what a build contains, a 404 is the route answering.
 */
@Serializable
data class ArchiveList(
    val archives: List<ArchivedSession> = emptyList(),
    /** How many rows this host keeps before the oldest is dropped. */
    val max: Int = 0,
)

/** What POST /v1/sessions/:name/archive reports back. */
@Serializable
data class ArchiveResult(
    val ok: Boolean = false,
    /** The Claude session uuid the archive will be filed under. */
    val id: String? = null,
    /** Done. False means accepted and winding down — see [pending]. */
    val archived: Boolean = false,
    /** The wrap-up phrase has been sent; the row appears when the session settles. */
    val pending: Boolean = false,
    /** "graceful" or "now". */
    val mode: String? = null,
    /** The phrase the HOST typed. Never a client copy of it. */
    val phrase: String? = null,
    /** The session was mid-turn, so the wrap-up queued behind it. */
    val queued: Boolean = false,
)

/** What POST /v1/archive/:id/revive reports back. */
@Serializable
data class ReviveResult(
    val ok: Boolean = false,
    /** The name tmux ACTUALLY used — `<name>2` when the old one was taken, and
     *  read back from tmux, which rewrites some characters and still succeeds. */
    val name: String = "",
    /**
     * Whether the CONVERSATION came back, or only the name and the directory.
     * False means there was nothing resumable left on disk and this is a fresh
     * Claude — the failure the whole feature exists to prevent, so it is said.
     */
    val resumed: Boolean = false,
    /** The host put its kept transcript back because Claude Code had swept its own. */
    val restoredTranscript: Boolean = false,
    val archive: ArchivedSession? = null,
)

@Serializable
data class Screen(
    val width: Int = 80,
    val height: Int = 24,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val attachedClients: Int = 0,
    val altScreen: Boolean = false,
    val lines: List<String> = emptyList(),
    val scrollback: List<String> = emptyList(),
    val historySize: Int = 0,
    val windowSize: String? = null,
    val sizeLeased: Boolean = false,
    /**
     * The install id of whoever holds the pane-size lease, or null for nobody.
     *
     * Null WITH [sizeLeased] true means a holder that sent no id (a client older
     * than the named lease). Present and not this client's own id means the
     * window belongs to another live viewer and this client's geometry was not
     * applied — the one thing a "leased here" mark must not claim otherwise.
     */
    val leaseHeldBy: String? = null,
    /** True when a resize was refused because another client is attached. */
    val resizeBlocked: Boolean = false,
    val hash: String? = null,
    /** Set when a long poll expired with no change; `lines` is then empty. */
    val unchanged: Boolean = false,
    val prompt: PanePrompt? = null,
    /** Present when the hook has a question the pane scrape cannot read. */
    val ask: DegradedAsk? = null,
    /** Present while an ExitPlanMode approval is waiting. */
    val planPending: PlanPending? = null,
    /** The live status line ("Gallivanting… · 3m 15s"), the only moment-to-moment signal. */
    val spinner: String? = null,
    /** The TUI's own durable progress rows: workflow phases, "Running N agents". */
    val statusLines: List<String> = emptyList(),
    /** The per-tool row that turns over constantly; updated in place, never stacked. */
    val transientLine: String? = null,
    /** Model/mode as the pane reports them right now (the transcript lags a turn). */
    val liveModel: String? = null,
    val liveMode: String? = null,
    val liveBranch: String? = null,
    /** Context-window pressure (0-100) from the statusline; null if unknown. */
    val contextPercent: Int? = null,
    /** The session is compacting its context right now. */
    val compacting: Boolean = false,
)

/**
 * One normalized transcript event. The same shape serves a tmux session and a
 * chat, because both read the same Claude Code transcript.
 *
 * kind: user | assistant | thinking | tool | tool_result | system
 */
@Serializable
data class TranscriptEvent(
    val seq: Int = 0,
    val kind: String = "",
    val ts: Long? = null,
    val sidechain: Boolean = false,
    val text: String? = null,
    val name: String? = null,
    val input: String? = null,
    val detail: String? = null,
    val result: String? = null,
    val ok: Boolean? = null,
    /** Typed while Claude was busy: sitting in the queue, not yet delivered. */
    val queued: Boolean = false,
    /** Present on AskUserQuestion tool events: the structured question card. */
    val ask: AskData? = null,
    /**
     * The HTTP status of an API error Claude Code recorded in place of a reply —
     * 429 is the usage limit. The kind stays `assistant`, because that is what
     * the record is; this field is what lets a client draw it as a limit notice
     * instead of as something Claude said.
     */
    val apiError: Int? = null,
    /**
     * Who sent this, when it came from ANOTHER SESSION rather than from the owner.
     *
     * ⚠⚠ THE FIELD THAT STOPS A TEAMMATE READING AS THE OWNER. A `SendMessage`
     * between two Claude sessions lands in the receiver's transcript as an
     * ordinary user record — `isMeta`, `promptSource:"system"`,
     * `userType:"external"` — and a reader that looks at none of those draws it
     * as a bubble the person in front of it appears to have typed. On a Projects
     * screen, where the sessions talk constantly, that is the whole surface
     * lying. So the daemon re-kinds it as `system` and hangs the sender here
     * (decision 50): an older client that has never heard of `peer` still draws a
     * system note, which is wrong about the detail and right about the category.
     *
     * ⚠ LABEL FROM [ProjectPeer.name], NEVER FROM THE RENDERED TEXT — the
     * `@handle` form slugifies the slash (`lora-stick/docs` → `@lora-stick-docs`),
     * so the preview cannot be parsed back into an addressable name.
     */
    val peer: ProjectPeer? = null,
)

@Serializable
data class AskData(val questions: List<AskQuestion> = emptyList())

@Serializable
data class AskQuestion(
    val question: String = "",
    val header: String? = null,
    val multiSelect: Boolean = false,
    val options: List<String> = emptyList(),
)

@Serializable
data class TranscriptPage(
    val events: List<TranscriptEvent> = emptyList(),
    /**
     * Text of queued messages DELIVERED in this window but enqueued in an earlier
     * one — the bubble is already on screen, still badged as waiting, and only
     * the badge needs clearing.
     *
     * The daemon used to re-send the whole message instead, which appended a
     * second identical bubble and left the first badged forever. Empty on a cold
     * open, where the message is emitted outright because there is no earlier
     * copy to reconcile. Absent from daemons older than 2.53.0, hence the default.
     */
    val deliveredQueued: List<String> = emptyList(),
    val nextOffset: Long = 0,
    /**
     * The byte this page's first record begins at. Zero means the very start of
     * the conversation is in view; anything else is what to pass as `until` to
     * read the page before this one. Absent from daemons older than 2.54.0, where
     * it reads as 0 and history simply is not offered.
     */
    val windowStart: Long = 0,
    val truncated: Boolean = false,
    val title: String? = null,
    val permissionMode: String? = null,
    val model: String? = null,
    val gitBranch: String? = null,
    val cwd: String? = null,
    /** Effort level Claude Code stamped on the last assistant turn. */
    val effort: String? = null,
    /** The model as a person reads it, e.g. `Opus 4.8`, formatted by the server. */
    val modelDisplay: String? = null,
    val lastActivityTs: Long? = null,
    val state: String? = null,
    val claudeSessionId: String? = null,
    val running: Boolean = false,
    val mode: String? = null,
    val pending: Int = 0,
    /** What the transcript tail says is in flight; null when nothing is. */
    val activity: Activity? = null,
    /** Background shells this session still has running. */
    val tasks: List<BgTask> = emptyList(),
    val bgAgents: Int = 0,
    /**
     * The agent this page came from, echoed back by the transcript route in the
     * form it validated — `agent-` prefixed, whichever form was asked for.
     *
     * Null on a session's own page. Without it a page cannot be attributed to
     * the stream it was read from, which is exactly what a picker that can
     * switch streams mid-poll needs in order to drop a late answer for the chip
     * the reader has already left.
     */
    val agentId: String? = null,
    /** The workflow run the agent belongs to, when it is a run's member. */
    val workflowId: String? = null,
    /**
     * This page came from an ARCHIVE's kept copy, not from a live session
     * (`GET /v1/archive/:id/transcript`).
     *
     * ⚠ IT IS NOT A STYLE FLAG. There is no pane behind it, no state to poll and
     * nothing to send to: a view that offered a composer here would be offering
     * to type at a session that no longer exists. The daemon says so on every
     * window rather than leaving it to the caller to remember which route it
     * asked.
     */
    val archived: Boolean = false,
    /**
     * The kept copy is a TAIL of an over-cap conversation. Said on every window,
     * because an archive of a truncated transcript starts mid-conversation and a
     * reader who scrolled to the top would otherwise conclude that is where it
     * began.
     */
    val transcriptTruncated: Boolean = false,
)

/** Automatic account rotation state, held by the host. */
@Serializable
data class Autoswitch(
    val enabled: Boolean = false,
    val switches: Int = 0,
    val last: AutoswitchEvent? = null,
    val accounts: Int = 0,
)

@Serializable
data class AutoswitchEvent(
    val at: Long = 0,
    val fromEmail: String? = null,
    val fromPercent: Int = 0,
    val fromLabel: String? = null,
    val toEmail: String? = null,
    val toPercent: Int = 0,
)

/** Suggested next messages, generated at a turn boundary. */
@Serializable
data class Suggestions(
    val suggestions: List<String> = emptyList(),
    val forSize: Long = 0,
    val reason: String? = null,
)

/** One agent behind a fan-out, live or recently settled. */
@Serializable
data class AgentRun(
    val id: String = "",
    /** The workflow run it belongs to; null for a directly-spawned agent. */
    val workflow: String? = null,
    val task: String? = null,
    val lastLine: String? = null,
    /** The agent's own account of what it concluded, once settled. */
    val summary: String? = null,
    val active: Boolean = false,
    val updatedAt: Long = 0,
    val startedAt: Long = 0,
    val bytes: Long = 0,
    /**
     * The workflow run id, served from 3.0.0 as its own field.
     *
     * [workflow] has carried the run's DIRECTORY name since 2.x and still does;
     * this is the daemon's own id for the run and the key the stream picker
     * groups on. Absent from an older daemon, where the picker stays flat.
     */
    val workflowId: String? = null,
    /** e.g. `workflow-subagent`, from the agent's meta file when it has one. */
    val agentType: String? = null,
    /**
     * The agent's settled outcome word: `running` | `done` | `failed` |
     * `orphan` | null.
     *
     * `orphan` is a real row, not a broken one: an agent transcript with no
     * `.meta.json` beside it. NULL is also real — a cold direct agent the
     * daemon has never seen settle — which is why this is nullable rather than
     * defaulted to a word. (`stalled` was in this list and is emitted nowhere.)
     */
    val status: String? = null,
    /**
     * How deep in the fan-out; 0 is a direct child of the session.
     *
     * NULLABLE because the daemon sends an explicit `null` for an agent with no
     * `.meta.json` — the `orphan` case — and kotlinx throws on an explicit null
     * into a non-nullable field whatever the default says. A depth that is not
     * known is not depth zero.
     */
    val depth: Int? = null,
)

@Serializable
data class AgentsInfo(
    val agents: List<AgentRun> = emptyList(),
    val active: Int = 0,
    val serverTime: Long = 0,
)

/** One background shell: what it runs, and for how long so far. */
@Serializable
data class BgTask(
    val id: String = "",
    val command: String = "",
    val forSeconds: Long = 0,
)

/** In-flight work: an unresolved tool call, and how many subagents are busy. */
@Serializable
data class Activity(
    val tool: String? = null,
    val detail: String? = null,
    val sinceTs: Long? = null,
    val subagents: Int = 0,
)

@Serializable
data class Chat(
    val id: String,
    val title: String? = null,
    val mode: String = "ask",
    val model: String? = null,
    val effort: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val claudeSessionId: String? = null,
    val lastSnippet: String? = null,
    val turns: Int = 0,
    val running: Boolean = false,
    /** Messages waiting for the current run to finish. */
    val pending: Int = 0,
    /** A device id, or "local"/null for the huginn host itself. */
    val host: String? = null,
    /**
     * The machine's NAME, resolved by the daemon. A client that looked this up
     * itself would print a bare uuid for a device since unenrolled.
     */
    val hostName: String? = null,
    /**
     * A finished Round run: readable forever, closed to new messages. The daemon
     * refuses a send with 409, so this is what lets the UI say so first.
     *
     * Named `closed` rather than `sealed`: the wire field is "sealed", but that is
     * a Kotlin modifier keyword and a property called it would need backticks at
     * every use site. One @SerialName is cheaper than that everywhere.
     */
    @SerialName("sealed") val closed: Boolean = false,
    val endedAt: Long? = null,
    val roundId: String? = null,
)

@Serializable
data class ChatList(val chats: List<Chat> = emptyList())

/** One persisted transcript record. `type` is user | assistant | tool | result | error. */
@Serializable
data class Message(
    val type: String,
    val text: String? = null,
    val name: String? = null,
    val input: String? = null,
    val ts: Long = 0,
    val partial: Boolean = false,
    val ok: Boolean? = null,
    val durationMs: Long? = null,
    val costUsd: Double? = null,
    val turns: Int? = null,
)

@Serializable
data class ChatDetail(
    val id: String,
    val title: String? = null,
    val mode: String = "ask",
    val model: String? = null,
    val effort: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val claudeSessionId: String? = null,
    val lastSnippet: String? = null,
    val turns: Int = 0,
    val running: Boolean = false,
    val messages: List<Message> = emptyList(),
    val host: String? = null,
    val hostName: String? = null,
    @SerialName("sealed") val closed: Boolean = false,
    val endedAt: Long? = null,
    val roundId: String? = null,
    val roundGoalMet: Boolean? = null,
    val partialText: String? = null,
    /**
     * Where [partialText] ends in the run's event stream, so a reattach can ask
     * for what came AFTER it. Null when no run is in flight, and on daemons older
     * than 2.48.0 — in which case the seed has to be skipped rather than
     * double-counted.
     */
    val seq: Long? = null,
)

@Serializable
data class ApiError(@SerialName("error") val error: String? = null)

/** A decoded SSE frame from a chat run. */
sealed interface ChatEvent {
    data class Started(val chatId: String) : ChatEvent
    data class Delta(val text: String) : ChatEvent
    data class Assistant(val text: String) : ChatEvent
    data class ToolStart(val name: String) : ChatEvent
    data class Tool(val name: String, val input: String?) : ChatEvent
    data class Result(val ok: Boolean, val durationMs: Long?, val costUsd: Double?) : ChatEvent
    data class Failure(val text: String) : ChatEvent
    data object Done : ChatEvent
}

@Serializable
data class Account(
    val loggedIn: Boolean = false,
    val email: String? = null,
    val orgName: String? = null,
    val subscriptionType: String? = null,
    val authMethod: String? = null,
    val apiProvider: String? = null,
    val error: String? = null,
)

@Serializable
data class UsageDay(
    val date: String? = null,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val totalTokens: Long = 0,
    val costUsd: Double? = null,
)

@Serializable
data class UsageWindow(
    val days: Int = 0,
    val totalTokens: Long = 0,
    val inputTokens: Long = 0,
    val outputTokens: Long = 0,
    val cacheReadTokens: Long = 0,
    val cacheCreationTokens: Long = 0,
    val costUsd: Double? = null,
)

@Serializable
data class UsageData(
    val today: UsageDay? = null,
    val week: UsageWindow = UsageWindow(),
    val daily: List<UsageDay> = emptyList(),
)

@Serializable
data class Usage(
    val data: UsageData? = null,
    val computedAt: Long? = null,
    val stale: Boolean = false,
    val refreshing: Boolean = false,
    val error: String? = null,
    /** ccusage prices at list rates; on a Max plan that overstates the real cost. */
    val costIsEstimate: Boolean = true,
)

@Serializable
data class LoginSession(
    val ok: Boolean = false,
    val session: String = "",
    val existed: Boolean = false,
    /** Full sign-in URL, lifted off the pane where it is hard-wrapped. */
    val url: String? = null,
    val intendedEmail: String? = null,
)

/** One row of Claude's plan utilization, as `/usage` shows it. */
@Serializable
data class PlanLimit(
    val kind: String? = null,
    val group: String? = null,
    val label: String = "",
    val percent: Double = 0.0,
    val severity: String = "normal",
    val resetsAt: String? = null,
    val isActive: Boolean = false,
)

/**
 * Extra usage — the credits that keep working past a plan limit.
 *
 * `usedCredits` and `monthlyLimit` are MINOR units at [decimalPlaces], not whole
 * currency: 10055 with two places is $100.55. The daemon only sends this block
 * for an account that has had credits switched on at some point, so its presence
 * is the "is there anything to show" answer; [isEnabled] is the different,
 * narrower question of whether they are on right now.
 */
@Serializable
data class ExtraUsage(
    val utilization: Double? = null,
    val usedCredits: Double? = null,
    val monthlyLimit: Double? = null,
    val currency: String = "USD",
    val spendLimitReached: Boolean = false,
    val isEnabled: Boolean = false,
    val creditsEverEnabled: Boolean? = null,
    val decimalPlaces: Int? = null,
    /** Why they are off — e.g. `org_level_disabled_until`. */
    val disabledReason: String? = null,
    val userDisabled: Boolean? = null,
)

/**
 * What extra usage has actually cost, in minor units.
 *
 * Kept as an integer and an exponent the whole way rather than divided into a
 * Double, because a cent has no exact binary fraction and this is the one figure
 * on the screen that IS a bill rather than an estimate.
 */
@Serializable
data class Spend(
    val usedMinor: Long? = null,
    val limitMinor: Long? = null,
    val exponent: Int = 2,
    val currency: String = "USD",
    val percent: Double? = null,
    /** Claude's own word — the limit rows colour by the same vocabulary. */
    val severity: String? = null,
    val enabled: Boolean = false,
    val disabledReason: String? = null,
    val canPurchaseCredits: Boolean = false,
    val canToggle: Boolean = false,
)

@Serializable
data class Plan(
    val limits: List<PlanLimit> = emptyList(),
    val extraUsage: ExtraUsage? = null,
    val spend: Spend? = null,
    val fetchedAt: Long? = null,
    val error: String? = null,
    /**
     * WHOSE usage these bars are, resolved by the daemon from the credentials it
     * read them with and cached alongside them.
     *
     * It travels with the numbers on purpose: the plan cache is not keyed on the
     * account, so an identity fetched separately would caption the new account's
     * bars with the old email for a whole TTL after a switch. Null on a daemon
     * older than 3.0.0 — see [com.silencelen.huginn.ui.PlanFormat.accountCaption].
     */
    val account: PlanAccount? = null,
)

/** Who a [Plan]'s numbers belong to. */
@Serializable
data class PlanAccount(
    val email: String? = null,
    val accountUuid: String? = null,
    val slug: String? = null,
    val subscriptionType: String? = null,
)

/** A saved login this host can switch to. */
@Serializable
data class SavedAccount(
    val slug: String,
    val email: String? = null,
    val orgName: String? = null,
    val savedAt: Long? = null,
    val isActive: Boolean = false,
    val subscriptionType: String? = null,
    /** Weekly all-models utilization, when it could be read for this account. */
    val weeklyPercent: Double? = null,
    val sessionPercent: Double? = null,
    /** The email was confirmed from this profile's own token, not inferred. */
    val verified: Boolean = false,
    /** Another saved profile is the same account, so switching changes nothing. */
    val duplicateOf: Boolean = false,
    /**
     * The daemon's word for how usable this profile's token is right now:
     * `fresh` · `expiring` · `expired` · `unrefreshable`.
     *
     * One vocabulary, the daemon's, extended from the one [verified] and
     * [duplicateOf] already render — a client that re-derived "expired" from
     * [expiresAt] would be a second opinion about the same token. Null on a
     * daemon older than 3.0.0: say nothing rather than guess `fresh`.
     */
    val freshness: String? = null,
    /** When the access token stops working, epoch millis. */
    val expiresAt: Long? = null,
    /** When the REFRESH token stops working: past this, only a re-login helps. */
    val refreshTokenExpiresAt: Long? = null,
    val refresh: AccountRefresh? = null,
)

/** What the daemon's background token refresh last did for one profile. */
@Serializable
data class AccountRefresh(
    /** Epoch MILLISECONDS. */
    val lastAt: Long? = null,
    /**
     * The daemon's own status WORD. THE WHOLE VOCABULARY, from
     * `lib/oauth-refresh.js`:
     *
     * `refreshed` · `not_needed` · `active_skipped` · `no_refresh_token` ·
     * `not_refreshable` · `refresh_token_expired` · `known_dead_refresh_token` ·
     * `account_on_hold` · `lock_busy` · `lock_timeout` · `lock_error` ·
     * `lock_compromised` · `refresh_failed` · `no_such_profile`.
     *
     * The three words this kdoc used to claim — `ok`, `invalid_grant` and "a
     * transport error word" — are emitted by nothing.
     */
    val lastStatus: String? = null,
    /** Epoch MILLISECONDS. */
    val nextAt: Long? = null,
    /**
     * When the refresh token was found to be dead for good. Epoch MILLISECONDS.
     * Set once by an `invalid_grant` and carried forward rather than re-derived.
     */
    val deadAt: Long? = null,
)

/**
 * The answer to an on-demand refresh of one saved profile.
 *
 * `ok` is not the whole answer and never was: `not_needed` and `active_skipped`
 * are both successes that did nothing, and a reader told only "ok" would press
 * the button again. The [status] word is what the surface shows.
 */
@Serializable
data class AccountRefreshed(
    val ok: Boolean = false,
    val slug: String? = null,
    /** The status WORD, the same vocabulary as [AccountRefresh.lastStatus]. */
    val status: String = "",
    val refresh: AccountRefresh? = null,
)

/**
 * What `POST /v1/sessions/:name/headroom/undo` did.
 *
 * [applied] and [queued] are the whole point: the picker cannot be opened
 * inside a running turn, so a mid-turn undo is held to the next turn boundary
 * like any other automated send. The route answers `ok:true` either way, and a
 * toast built on `ok` alone told the reader a session was back on its own model
 * while it was still running on the one it was moved to.
 */
@Serializable
data class UndoResult(
    val ok: Boolean = false,
    /** The model change is ON THE PANE. Absent from a pre-fix daemon. */
    val applied: Boolean = false,
    /** Held for the turn boundary; it will land without anyone doing anything. */
    val queued: Boolean = false,
    /** The family it goes back to. */
    val to: String? = null,
    /** `confirmed` · `delivery_unconfirmed` · `queued` · `dropped: <why>` */
    val delivery: String? = null,
)

@Serializable
data class SavedAccounts(val accounts: List<SavedAccount> = emptyList())

// --------------------------------------------------------------- headroom
//
// Usage headroom (appd 3.0.0). One daemon subsystem, one arbiter: the client
// reads what it decided and configures it, and decides nothing about limits
// itself. Every field is nullable-or-defaulted, so a 2.85.0 daemon — which has
// no `/v1/headroom` at all — degrades to "no pill, no marks" rather than to a
// failed decode.

/** One usage window of one account, as the daemon last read it. */
@Serializable
data class HeadroomWindow(
    val percent: Double = 0.0,
    val resetsAt: String? = null,
    /** Claude's own word; the meter colours by the same vocabulary as a PlanLimit. */
    val severity: String = "normal",
    val label: String = "",
)

/**
 * The three windows of one account, exactly as `headroom.json` keys them.
 *
 * A TYPED TRIPLE rather than a `Map<String, HeadroomWindow>`: the three keys are
 * a closed set the daemon and both clients already name individually
 * ([HeadroomRules.windowKeyWords], the reset records, the sentinels), and a map
 * would turn every one of those into a lookup that can miss. Each is nullable
 * because an account whose plan was never read has no figure for it — and a
 * missing window must never read as 0 %.
 */
@Serializable
data class HeadroomWindows(
    val session: HeadroomWindow? = null,
    @SerialName("weekly_all") val weeklyAll: HeadroomWindow? = null,
    @SerialName("weekly_fable") val weeklyFable: HeadroomWindow? = null,
)

/**
 * One saved login's headroom row, as `/v1/headroom.accounts` serves it.
 *
 * [live] is the whole reason this is decoded at all: the pill reports the WORST
 * window anywhere, and the session-usage fill under the Status icon reports the
 * 5-hour window of the account that is actually signed in. Those are different
 * numbers on any host with more than one profile, and reading the second off the
 * first is how a bar at 31 % gets painted from a week at 92 %.
 *
 * `red` (the per-window RED clocks) is deliberately not decoded: it is the
 * arbiter's bookkeeping, nothing on a client renders it, and a shape pinned here
 * would break the first time the daemon adds a word to it.
 */
@Serializable
data class HeadroomAccount(
    val email: String? = null,
    /** When this row was last read. Epoch MILLISECONDS. */
    val readAt: Long = 0,
    /** This is the credentials file's account — the one work actually runs on. */
    val live: Boolean = false,
    val windows: HeadroomWindows = HeadroomWindows(),
)

/** The single worst window across every account: what the pill reports. */
@Serializable
data class HeadroomWorst(
    val slug: String? = null,
    val email: String? = null,
    /** `session` | `weekly_all` | `weekly_fable` */
    val window: String? = null,
    val percent: Double = 0.0,
    val label: String = "",
    val resetsAt: String? = null,
)

/** A model move the daemon made on a live session, and whether it landed. */
@Serializable
data class HeadroomLadder(
    val from: String? = null,
    /** The family it moved TO; null means no move has been made. */
    val to: String? = null,
    /**
     * Epoch MILLISECONDS.
     *
     * ⚠ Every timestamp on `/v1/headroom` is milliseconds — this, [Headroom.serverTime],
     * [HeadroomSession.headsUpAt], [HeadroomHeld.since], the stall clocks and the
     * arbiter's. The payload used to mix both units with no field-name tell.
     */
    val at: Long = 0,
    /** `confirmed` | `delivery_unconfirmed` — a picker that never appeared. */
    val delivery: String? = null,
)

/**
 * What a session stalled on a usage limit is waiting for, as the daemon
 * recorded it.
 *
 * The whole record rather than the [HeadroomSession.stalled] flag, because the
 * flag cannot write the sentence: [text] is the only place Claude Code's own
 * clock time ("resets 10:10pm") is ever written down, and [why] is the only
 * place a REFUSAL to resume ("auto-resume is off for this session") is. Both
 * are rendered verbatim — see [com.silencelen.huginn.ui.HeadroomRules].
 *
 * Every field is nullable: the daemon seeds a blank stall on every session it
 * has ever seen and only fills what it has learned.
 */
@Serializable
data class HeadroomStall(
    /** When the 429 was noticed. Epoch MILLISECONDS. */
    val at: Long? = null,
    /** `session` | `weekly_all` | `weekly_fable` */
    val window: String? = null,
    /**
     * When that window comes back. Epoch MILLISECONDS on this route — NOT the
     * ISO string the `/v1/watch` digest carries for the same fact.
     */
    val resetsAt: Long? = null,
    /** `endpoint` | `text` — how the reset instant was learned. */
    val resetsAtSource: String? = null,
    /** When it was picked back up, epoch MILLISECONDS; null while still stalled. */
    val resumedAt: Long? = null,
    /** `native` | `human` | `appd` | `rerun` — who resumed it. */
    val how: String? = null,
    val attempts: Int = 0,
    /** Claude Code's own auto-continue was armed when the limit hit. */
    val nativeArmed: Boolean = false,
    /** The arbiter's one-line reason, for or against resuming. Verbatim. */
    val why: String? = null,
    /** The limit sentence Claude Code printed, carrying its own clock time. */
    val text: String? = null,
)

/** A `/model` change Claude Code made by itself, which the arbiter defers to. */
@Serializable
data class HeadroomNativeSwitch(
    /** Epoch MILLISECONDS; null when none has been seen. */
    val seenAt: Long? = null,
    val to: String? = null,
)

/** One usage window coming back, as the arbiter saw it happen. */
@Serializable
data class HeadroomReset(
    val slug: String? = null,
    /** `session` | `weekly_all` | `weekly_fable` */
    val window: String? = null,
    /** The instant the window was due back, ISO-8601 — not an epoch. */
    val resetsAt: String? = null,
    /** What the window read once it had reset. */
    val percent: Double? = null,
    /** When the arbiter noticed. Epoch MILLISECONDS. */
    val seenAt: Long? = null,
    /** The tick that recorded it. Epoch MILLISECONDS. */
    val at: Long? = null,
)

/** One session as the headroom subsystem sees it. */
@Serializable
data class HeadroomSession(
    val name: String = "",
    val claudeSessionId: String? = null,
    val family: String? = null,
    val ladder: HeadroomLadder? = null,
    val autoResume: Boolean = true,
    val stalled: Boolean = false,
    /** When the heads-up note was typed into the pane. Epoch MILLISECONDS. */
    val headsUpAt: Long? = null,
    /** The stall record behind [stalled]; null when the session is not stalled. */
    val stall: HeadroomStall? = null,
    /** Always present on 3.0.0, with both halves null when nothing was seen. */
    val nativeSwitch: HeadroomNativeSwitch? = null,
)

/** A subagent spawn the hook gate is holding because a sentinel is armed. */
@Serializable
data class HeadroomHeld(
    val agentId: String = "",
    val agentType: String? = null,
    /** Epoch MILLISECONDS, like every other timestamp on this route. */
    val since: Long = 0,
)

/** Account rotation, migrated out of `autoswitch.json` and into these settings. */
@Serializable
data class AccountSwitch(
    val enabled: Boolean = false,
    val threshold: Int = 95,
    val margin: Int = 20,
)

/**
 * The owner-editable half of headroom.
 *
 * The defaults here mirror the daemon's, so a settings form has something
 * sensible to draw before the first `/v1/headroom` answers. They are NOT the
 * validation authority: `validateSettings` on the daemon is, and a disagreement
 * between the two is a bug in this file rather than a second opinion.
 */
@Serializable
data class HeadroomSettings(
    val headsUpPct: Int = 85,
    val ladderPct: Int = 92,
    val ladderUpBelowPct: Int = 50,
    val stopPct: Int = 70,
    val stopFablePct: Int = 88,
    val clearBelowPct: Int = 50,
    val cooldownMs: Long = 1_800_000,
    val ladder: List<String> = listOf("fable", "opus", "sonnet"),
    /**
     * ⚠ COPIED VERBATIM from `server/appd/lib/headroom.js` `defaults()`. The
     * daemon REJECTS an empty [defaultModel] and a [headsUpText] with no
     * `{pct}` in it, so the three that used to read `""`, `"continue"` and `""`
     * made a settings form saved straight from its own defaults answer 400.
     */
    val defaultModel: String = "claude-fable-5-1",
    val autoResume: Boolean = true,
    val resumePhrase: String =
        "Your usage limit has reset. Continue the task you were working on when " +
            "the limit was reached; do not repeat work that is already complete.",
    val headsUpText: String =
        "[huginn headroom] You are at {pct}% of this account's Fable weekly limit. " +
            "Write a short handoff note now (what is done, what is next, which files " +
            "matter), then continue. huginn will move this session to {next} at {ladderPct}%.",
    val accountSwitch: AccountSwitch = AccountSwitch(),

    /**
     * Keep one 5-hour window always rotating by sending a tiny request whenever
     * none is running.
     *
     * ⚠ FALSE, AND THE DEFAULT IS LOAD-BEARING. This is the only setting in the
     * product that spends the owner's quota with nobody asking, and a client
     * whose default said `true` would draw the toggle ON against a daemon that
     * has it off — then save that reading back on the next edit and switch it on
     * for real. The daemon's default is false for the same reason.
     */
    val keepAwake: Boolean = false,
    /** ⚠ COPIED VERBATIM from `lib/keepawake.js` `DEFAULT_MODEL`. */
    val keepAwakeModel: String = "claude-haiku-4-5-20251001",
    /** `"HH:MM-HH:MM"` in the HOST's local time, or null for none. */
    val keepAwakeQuietHours: String? = null,
)

/**
 * What keep-awake has been doing, as `/v1/status` and `/v1/headroom` report it.
 *
 * Every field nullable-with-default, like the rest of the wire models: a daemon
 * older than this feature answers nothing here, and the Status line degrades to
 * its window half rather than to a row of zeroes claiming nothing was spent.
 */
@Serializable
data class KeepAwakeStatus(
    val enabled: Boolean = false,
    val model: String? = null,
    val quietHours: String? = null,
    /** Epoch MILLISECONDS of the last ping, or 0 for never. */
    val lastAt: Long = 0,
    /**
     * That instant as `HH:MM`, formatted BY THE DAEMON.
     *
     * `:core` is commonMain and has no timezone database; the one time a shell
     * turned an instant into a wall clock by hand it printed UTC as if it were
     * local. The host knows what time it is where the machine is, so it says so
     * and this is rendered verbatim — the same rule [HeadroomStall]'s
     * "resets 10:10pm" already follows.
     */
    val lastAtClock: String? = null,
    val keptAwakeToday: Int = 0,
    val keptAwakeTotal: Int = 0,
    /** `ok` | `hold` | `retry` — what the last attempt did. */
    val lastOutcome: String? = null,
    /**
     * Why the daemon did or did not ping on its last pass.
     *
     * Not rendered on the one-line Status summary, but it is the answer to "it
     * is switched on and nothing is happening" — a window already running, a red
     * week, an armed sentinel, quiet hours — and without it the settings screen
     * has nothing to say about a feature that is deliberately idle.
     */
    val why: String? = null,
)

/** Everything `/v1/headroom` reports. */
@Serializable
data class Headroom(
    /** `ok` | `warn` | `red` | `exhausted` */
    val mode: String = "ok",
    val worst: HeadroomWorst? = null,
    /**
     * Every saved login the daemon reads, by slug — including the one that is
     * [HeadroomAccount.live]. [worst] is the worst window across all of them and
     * answers "what will stop work"; this answers "where is the account I am
     * working on right now", which is what the session-usage fill draws.
     */
    val accounts: Map<String, HeadroomAccount> = emptyMap(),
    val sessions: List<HeadroomSession> = emptyList(),
    /**
     * Armed sentinels by name. The VALUE is left as raw JSON: a sentinel is
     * either null (not armed) or an object whose fields are for a human to read,
     * and pinning a shape here would break the client the first time the daemon
     * adds a word to it.
     */
    val sentinels: Map<String, JsonElement?> = emptyMap(),
    val held: List<HeadroomHeld> = emptyList(),
    /**
     * Windows that have come back, newest last, the daemon's last twenty.
     *
     * The other half of a stall: a session sitting on a limit is waiting for one
     * of these, and the arbiter will not resume it until the window it stalled
     * on appears here.
     */
    val resets: List<HeadroomReset> = emptyList(),
    /** Arbiter bookkeeping, raw: it is displayed, never branched on. */
    val arbiter: JsonObject? = null,
    /** Keep-awake's own bookkeeping, beside the arbiter's for the same reason. */
    val keepAwake: KeepAwakeStatus? = null,
    val settings: HeadroomSettings? = null,
    /** The host clock. Epoch MILLISECONDS, like every other `at` on this route. */
    val serverTime: Long = 0,
)

/**
 * The headroom summary carried on `/v1/status` — enough for a pill, no more.
 *
 * Separate from [Headroom] because it rides a poll that every client already
 * makes: the full picture costs a second request and only the Status pane and
 * the settings form need it.
 */
@Serializable
data class StatusHeadroom(
    val worstPercent: Double? = null,
    val worstLabel: String? = null,
    val nextResetAt: String? = null,
    val mode: String = "ok",
    val sentinels: List<String> = emptyList(),
    /** Subagent spawns held by the gate right now. */
    val paused: Int = 0,
    /**
     * Is a 5-hour window running at all?
     *
     * ⚠ NULLABLE, and the three-valued-ness is the point: `null` is a daemon too
     * old to have been asked, which is not the same answer as `false`. Read as a
     * plain Boolean, an older host would report "no window running" forever and
     * the Status line would say so with total confidence.
     *
     * The daemon derives it from ONE field — the session row's `resets_at` comes
     * back null while no window is running — not from the percentage, which is
     * also 0 in plenty of situations where a window is very much open.
     */
    val windowRunning: Boolean? = null,
    /** When the running window ends. Null when none is running, or on an old daemon. */
    val windowResetsAt: String? = null,
    val keepAwake: KeepAwakeStatus? = null,
)

/**
 * The headroom cell on a session list row.
 *
 * Deliberately not [HeadroomSession]: this is what `/v1/sessions` can answer
 * without walking a transcript, and the list route must stay cheap.
 */
@Serializable
data class SessionHeadroom(
    val family: String? = null,
    /** The family it was moved to, or null if it is still on its own model. */
    val ladder: String? = null,
    val autoResume: Boolean = true,
    val stalled: Boolean = false,
)

// ------------------------------------------------------------ send queue
//
// The daemon holds a session send until the turn it would land in has ended.
// Both models are additive: a pre-queue daemon answers `{"ok":true}` and every
// field below falls to its default.

/**
 * What a send actually did.
 *
 * `sendKeys` used to return Unit, so the compat question is only what an old
 * `{"ok":true}` decodes to: queued 0, position 0, delivered false. That is why
 * [landed] does not read [delivered] alone — nothing queued is nothing waiting,
 * which is exactly what the old daemon meant.
 */
@Serializable
data class SendKeysResult(
    val ok: Boolean = false,
    /** How many sends are waiting for this session, this one included. */
    val queued: Int = 0,
    /** This send's place in that queue; 0 when it was not queued at all. */
    val position: Int = 0,
    /** Both gates were open: the text is already on the pane. */
    val delivered: Boolean = false,
    /**
     * WHY the send is waiting, when it is — `turn` | `modal` | `starting` | null,
     * the same word `/typing` reports (appd 3.1.2).
     *
     * Without it the "queued" line was seeded from a COUNT and nothing else, so
     * for the first poll interval every client fell back to "will send when Claude
     * finishes its turn" — which for a session still starting is a turn that has
     * not begun. Two seconds of the wrong sentence, on the one wait a reader is
     * most likely to see, and then a silent correction. Null against an older
     * daemon, which is the old behaviour exactly.
     */
    val blockedBy: String? = null,
    /**
     * The daemon RECOGNISED this send as one it already has, and did not take it
     * a second time (appd 3.5.1).
     *
     * ⚠ AN ANSWER, NOT A FAILURE. The P1 behind it: three `/keys` POSTs from one
     * tap put the owner's message on the pane three times. The daemon now drops
     * an identical human text that is already pending — or that it delivered
     * within the last 30 seconds — and says so, and a client that read the drop
     * as nothing having happened would leave the composer looking as though the
     * message had vanished, which is the exact complaint the send queue exists
     * for. The text IS going to arrive; this says it is already on its way.
     *
     * False from every older daemon, which is the old behaviour exactly.
     */
    val duplicate: Boolean = false,
) {
    /** Nothing is waiting on this send — it landed, or there is no queue to wait in. */
    val landed: Boolean get() = delivered || queued <= 0
}

/** What the daemon is holding for one session, and why. */
@Serializable
data class TypingState(
    val queued: Int = 0,
    val delivering: Boolean = false,
    val lastError: String? = null,
    /** `turn` | `modal` | null — what the queue is waiting on. */
    val blockedBy: String? = null,
    val serverTime: Long = 0,
)

/**
 * A model the host offers: the installed CLI's Claude models, and — when the
 * client asks with `?local=1` — the `family:"local"` rows served by enrolled
 * machines. All additions are nullable-with-default, so an older daemon's rows
 * parse to exactly the old behaviour.
 */
@Serializable
data class ModelChoice(
    val id: String = "",
    val display: String = "",
    val family: String = "",
    /** False when the serving machine has not checked in. */
    val available: Boolean = true,
    /** The device a local row runs on. Display only — the id already carries it. */
    val host: String? = null,
)

@Serializable
data class ModelList(val models: List<ModelChoice> = emptyList())

/** Where an uploaded image landed on huginn. */
@Serializable
data class UploadResult(
    val ok: Boolean = false,
    val path: String = "",
    val bytes: Long = 0,
    val ext: String? = null,
    /**
     * Whether Claude's Read tool can display this, as decided by the host. False
     * for archives, databases, router backups — things a shell can inspect but
     * Read renders as mojibake. It changes what the outgoing message ASKS for.
     */
    val readable: Boolean = true,
)

/** One chat's state in the watch digest. */
@Serializable
data class WatchChat(
    val running: Boolean = false,
    val pending: Int = 0,
    val title: String? = null,
    /**
     * Completed runs, counted rather than flagged. `running` going false is an edge,
     * and anything looking on a schedule can miss one — with a ten-minute background
     * check, a chat that started and finished in between was never seen running at
     * all. A counter that is higher than last time cannot be missed that way.
     */
    val finishedRuns: Long = 0,
    /** The last thing Claude said, so a finish notification can carry the answer. */
    val snippet: String? = null,
)

/** The change signal a watching client parks on. */
@Serializable
data class Watch(
    val hash: String = "",
    val sessions: Map<String, String?> = emptyMap(),
    val chats: Map<String, WatchChat> = emptyMap(),
    val changed: Boolean = false,
    val serverTime: Long = 0,
    /**
     * How many pushes the host believes it has sent this install. Compared against
     * what actually arrived, it is the only signal that separates a quiet night
     * from a broken delivery path — see [com.silencelen.huginn.notify.Heartbeat].
     */
    /**
     * NULLABLE on purpose: absent means "this response does not carry the tally",
     * which is not the same as zero. Defaulting it to 0 made every SSE state
     * frame overwrite the app's real count and disabled deficit detection.
     */
    val pushesSent: Long? = null,
    /**
     * Which epoch [pushesSent] is counted in — appd 3.0.5 and later.
     *
     * A random string minted when this install's counter is created or reset. It
     * survives daemon restarts AND token rotations, and the rotation is what
     * actually made the phone read "1274 of 916 pushes arrived": the daemon
     * recreates the install row on rotation, so its count restarts while the
     * phone's keeps climbing. NULLABLE because a pre-3.0.5 daemon sends none —
     * see [com.silencelen.huginn.notify.PushTally], which keeps a `received >
     * sent` guard for exactly that host.
     */
    val pushEpoch: String? = null,
    /**
     * The headroom facts an alert turns on, inside the hash.
     *
     * ⚠ The daemon's digest is an explicit field list; a field that is not named
     * there evaporates silently, which is how a notification stops firing with
     * nothing to see. Null on a daemon older than 3.0.0.
     */
    val headroom: WatchHeadroom? = null,
)

/** The headroom half of a watch digest: only facts a notification acts on. */
@Serializable
data class WatchHeadroom(
    val mode: String = "ok",
    /** Session names currently stalled on a limit. */
    val stalled: List<String> = emptyList(),
    /**
     * The same sessions, each against the instant its window resets.
     *
     * [stalled] alone says WHICH sessions are sitting on a limit, which is enough
     * to decide that a notification is due but not enough to write it: "hit the
     * limit" with no reset time is the one sentence that leaves the reader with
     * the question they opened it to answer. A name missing from here is still
     * stalled — the notice is just shorter.
     */
    val stalls: Map<String, String?> = emptyMap(),
    /**
     * Sessions the ladder has MOVED, name → the family they are running on now.
     *
     * [lastLadderAt] is a clock, and a clock cannot name a session or say what it
     * was moved to — so an Undo built from it would have nothing to undo. This map
     * is what makes "downgraded" an event with a subject: a name appearing is a
     * move down, a name leaving is a move back up, and the value is the words the
     * toast says.
     */
    val laddered: Map<String, String> = emptyMap(),
    /**
     * The last resume the arbiter landed, epoch MILLISECONDS.
     *
     * ⚠ NULLABLE, and not because the daemon still sends null — it sends 0 as of
     * the Wave 1 fix round. It is nullable because the digest seeded this field
     * from `normalizeHeadroomState`'s `lastResumeAt: null` on every daemon that
     * had never resumed anything, and kotlinx throws on an explicit null into a
     * non-nullable field whatever its default is. That throw failed the WHOLE
     * `/v1/watch` decode — the watch loop, every notification decision and the
     * headroom toasts — on a fresh install, which is the one install nobody
     * tests against. A field an older daemon can null is nullable here forever.
     */
    val lastResumeAt: Long? = null,
    val lastLadderAt: Long = 0,
    /** Armed sentinel names, e.g. `STOP-FABLE`. */
    val sentinels: List<String> = emptyList(),
)

/** State of an in-progress sign-in, read off the login session's pane. */
@Serializable
data class LoginState(
    val session: String = "login",
    val running: Boolean = false,
    val awaitingCode: Boolean = false,
    val done: Boolean = false,
    val url: String? = null,
    val message: String? = null,
    /** Who the new token actually belongs to, resolved from the token itself. */
    val email: String? = null,
    val intendedEmail: String? = null,
    /** The captured account is one already stored: the same login twice. */
    val duplicate: Boolean = false,
    /** Signed in as somebody other than the account that was being added. */
    val mismatch: Boolean = false,
)

/** Host-side alerting: reaches the phone with the app closed. */
@Serializable
data class Alerts(
    val enabled: Boolean = false,
    /**
     * `fallback` — only when no phone has checked in recently; `always` — every
     * time. Fallback by default: two notifications for one event teaches you to
     * dismiss both without reading, and then the one that mattered is gone too.
     */
    val mode: String = "fallback",
    val delivered: Int = 0,
    val lastAt: Long? = null,
    /** telegram | none */
    val channel: String = "none",
    /** Whether the host currently believes a phone is listening. */
    val appOnline: Boolean = false,
    /** Whether the host holds an FCM credential at all. */
    val pushConfigured: Boolean = false,
    val pushDevices: Int = 0,
    val pushed: Int = 0,
)

/** One phone, as the host has seen it. */
@Serializable
data class ClientInfo(
    val id: String = "",
    /** stream | poll | heartbeat — which mechanism last checked in. */
    val kind: String? = null,
    val notify: Boolean? = null,
    val lastAt: Long = 0,
    val ageSeconds: Long = 0,
    val checkIns: Long = 0,
    val fresh: Boolean = false,
    /** The window this client's own mechanism is judged against. */
    val expectedWithinSeconds: Long = 0,
)

/**
 * The host's own record of who is listening — the evidence that background
 * delivery is working, gathered by a machine that never sleeps.
 */
@Serializable
data class ClientsInfo(
    val clients: List<ClientInfo> = emptyList(),
    val appOnline: Boolean = false,
    val serverTime: Long = 0,
)

/**
 * The outcome of answering from a notification.
 *
 * A refusal is a 409 carrying `reason`: `gone` when there is no question on screen any
 * more, `changed` when the session is asking something else. Both are ordinary — the
 * tap was correct when it was offered — so they are reported, never retried.
 */
@Serializable
data class AnswerResult(
    val ok: Boolean = false,
    val option: Int = 0,
    val label: String? = null,
    val labels: List<String>? = null,
    val reason: String? = null,
    val error: String? = null,
)

@Serializable
data class PushRegistration(
    val ok: Boolean = false,
    val configured: Boolean = false,
    val devices: Int = 0,
    val rotated: Boolean = false,
)

/** One device huginn can push to. Never carries the token itself. */
@Serializable
data class PushDevice(
    val installId: String = "",
    val model: String? = null,
    val seenAt: Long = 0,
    val failures: Int = 0,
    val tokenTail: String = "",
    /** The same epoch [Watch.pushEpoch] carries, per device. Null before 3.0.5. */
    val pushEpoch: String? = null,
)

@Serializable
data class PushStatus(
    /** Whether the host has an FCM credential — separate from whether a phone registered. */
    val configured: Boolean = false,
    val projectId: String? = null,
    val sender: String? = null,
    val devices: List<PushDevice> = emptyList(),
    val pushed: Int = 0,
)

/** What arrives on the watching connection. */
sealed interface WatchEvent {
    data class State(val watch: Watch) : WatchEvent
    /** A keepalive. Carries nothing but the fact that the path is still open. */
    data object Alive : WatchEvent
    /** The server retired a long-lived stream; reconnect at once, do not back off. */
    data object Rotated : WatchEvent
    data class Failure(val message: String) : WatchEvent
}

// ------------------------------------------------------------------- rounds

/**
 * A Round's cadence. Structured rather than a cron string so it can be rendered
 * and picked; the human-readable form is [Round.cadence], produced by the daemon.
 */
@Serializable
data class RoundSchedule(
    /** daily | weekly | monthly | interval */
    val kind: String = "daily",
    /** "HH:MM", 24-hour, in [tz]. Absent for `interval`. */
    val at: String? = null,
    val tz: String? = null,
    /** 0 = Sunday. `weekly` only. */
    val days: List<Int> = emptyList(),
    /** 1-31. `monthly` only. */
    val dates: List<Int> = emptyList(),
    val everyMinutes: Int? = null,
)

/** One thing a Round found that needs a decision or an action. */
@Serializable
data class RoundItem(
    val title: String = "",
    val detail: String = "",
    /** The next step, so acting on an item does not start from a blank page. */
    val suggest: String = "",
)

@Serializable
data class RoundRun(
    /** Epoch SECONDS, unlike [Round.nextRunAt]. */
    val at: Long = 0,
    /**
     * Whether the run reached the Round's stated goal. Null = the Round set no
     * goal, or the run did not say — which is NOT the same as failing, and must
     * not be rendered as one.
     */
    val goalMet: Boolean? = null,
    /**
     * What the run actually claimed, before an unmet goal promoted [status].
     * Carried so "it said ok but had not finished" stays visible.
     */
    val reportedStatus: String? = null,
    /** The chat this run happened in. Openable like any other. */
    val chatId: String? = null,
    /** ok | attention | action | unknown */
    val status: String = "unknown",
    val headline: String = "",
    val items: List<RoundItem> = emptyList(),
    /**
     * How many items the run actually reported, which is NOT [items].size once
     * the daemon's cap of 20 bites. A round that found 500 things showed "20
     * items" on the line directly under a headline saying 500, and the number an
     * operator acts on was the wrong one of the two on the same screen.
     *
     * 0 from a daemon too old to send it — [itemCountWords] falls back to the
     * list length, which is what that daemon meant by the field anyway.
     */
    val itemsTotal: Int = 0,
    /**
     * When somebody read this report and said they had dealt with it, in epoch
     * SECONDS. Null until they do.
     *
     * ⚠ ON THE RUN, NOT ON THE ROUND, and that is the whole design. [Round.lastRun]
     * is replaced wholesale when a Round fires again, so next week's report
     * arrives un-acknowledged for free. Held on the Round it would need code to
     * remember to clear it, and that code would eventually not run — leaving a
     * Round permanently quiet about findings nobody had seen.
     */
    val acknowledgedAt: Long? = null,
    /** The run produced no usable report block; the headline is a quote instead. */
    val malformed: Boolean = false,
    val manual: Boolean = false,
    val durationSec: Long? = null,
)

@Serializable
data class Round(
    val id: String,
    val title: String = "",
    val prompt: String = "",
    /**
     * What "done" means for this Round, as a completion test. Empty is legitimate:
     * a Round that reports on something has no finish line to cross.
     */
    val goal: String = "",
    val enabled: Boolean = true,
    val mode: String = "ask",
    val model: String? = null,
    val effort: String? = null,
    val schedule: RoundSchedule = RoundSchedule(),
    /** always | attention | never */
    val notifyWhen: String = "attention",
    val catchUp: Boolean = false,
    val timeoutSec: Long = 900,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /**
     * Epoch MILLIseconds — the daemon schedules in ms even though its record
     * timestamps are in seconds. Mixing the two silently renders "in 55 years".
     */
    val nextRunAt: Long? = null,
    val currentChatId: String? = null,
    val lastRun: RoundRun? = null,
    val runs: List<RoundRun> = emptyList(),
    /**
     * "Sundays at 7:00 PM", rendered BY THE DAEMON. The zone rules it fires by
     * live there, so a client that re-derived this could disagree with the thing
     * actually holding the schedule.
     */
    val cadence: String = "",
    val running: Boolean = false,
    /**
     * Which machine it runs on: a device id, or "local" for the huginn host.
     *
     * The daemon has always sent this and these two fields were simply missing
     * here, so every Round read as local however it was actually placed — a
     * Round on a Device is the one thing neither feature could do alone, and it
     * was invisible in both clients.
     */
    val host: String = "local",
    /**
     * The device's name, resolved BY THE DAEMON — for the same reason as
     * [cadence]. A client that looked it up itself would print a bare uuid for a
     * device that has since been unenrolled, rather than saying so.
     */
    val hostName: String? = null,
)

@Serializable
data class RoundList(val rounds: List<Round> = emptyList())

@Serializable
data class RoundRunStarted(val ok: Boolean = false, val chatId: String? = null)

/**
 * An AI-written improvement to ONE field of a Round somebody is drafting.
 *
 * AI DRAFTS, HUMAN ACCEPTS. This is a proposal and never an edit: the editor shows
 * [polished] beside the field and waits for a person to take it or throw it away.
 * Nothing here is persisted — a polish that was discarded should leave no trace,
 * which is why [RoundDraft] has no field for it.
 *
 * [error] arrives with HTTP 200, deliberately. A model being unavailable is not a
 * broken daemon, and the person is mid-sentence in a text field: it deserves one
 * quiet line, not the failure path a 5xx would take.
 */
@Serializable
data class PolishResult(
    val polished: String? = null,
    /** Something true about the answer worth saying, e.g. that it had to be trimmed. */
    val note: String? = null,
    val error: String? = null,
)

// -------------------------------------------------------------- scratchpads

/**
 * One of the user's own pages on the host.
 *
 * [content] is absent from a list row and present on a fetched pad, which is what
 * makes the list cheap enough to poll — so it defaults to empty rather than being
 * nullable, and [size] is how a picker tells a written page from a blank one
 * without asking for the text.
 */
@Serializable
data class Scratchpad(
    val id: String,
    val name: String = "",
    val content: String = "",
    /**
     * The revision this copy was read at. Every save carries it back, and a save
     * based on a stale one is refused with the server's copy — which is the whole
     * reason two devices can autosave the same page without one silently erasing
     * the other's paragraph.
     */
    val rev: Int = 0,
    /** The undeletable, unrenameable page a reference falls back to. */
    val main: Boolean = false,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    /** Characters, from a list row. Zero on a fetched pad is [content] being empty. */
    val size: Int = 0,
)

@Serializable
data class ScratchpadList(val pads: List<Scratchpad> = emptyList())

/**
 * The answer to a save: what the server now holds, and whether it took ours.
 *
 * A conflict is NOT an exception. It is the expected outcome of the other device
 * having saved first, it arrives with everything needed to recover (the current
 * text and its rev), and the editor's response to it is a quiet line rather than
 * an error — so surfacing it as a throw would make the ordinary case travel the
 * failure path.
 */
data class ScratchpadSave(val pad: Scratchpad, val conflict: Boolean)

// ------------------------------------------------------------------ devices

/**
 * Another machine that can run a chat in its context.
 *
 * [scope] is what it is enrolled to do; [effectiveScope] is what it will do right
 * now — they differ when the machine is locked. Show both: "own, read-only while
 * locked" is a different situation from "enrolled read-only", and collapsing them
 * makes the second look like a bug.
 */
@Serializable
data class Device(
    val id: String,
    val name: String = "",
    /** windows | linux | macos | other */
    val platform: String = "other",
    /** look | work | own */
    val scope: String = "look",
    val effectiveScope: String = "look",
    val locked: Boolean = false,
    /** Where a `work`-scoped run starts. Not a sandbox — see DevicePolicy. */
    val root: String? = null,
    val version: String? = null,
    val online: Boolean = false,
    /** Epoch MILLIseconds, like Round.nextRunAt and unlike the record timestamps. */
    val lastSeen: Long? = null,
    val registeredAt: Long? = null,
    /**
     * The daemon's grouping key for the physical box: rows sharing it are one
     * machine wearing two credentials (claude work + local-AI serving) and
     * render as ONE device with capability facets. Grouping and display only —
     * authority stays per-row.
     */
    val machine: String? = null,
    val running: Boolean = false,
    val queued: Int = 0,
    /** The daemon-minted slug inside a serving row's model ids. Display only. */
    val llmSlug: String? = null,
    /**
     * What a serving machine says it serves, for DISPLAY ONLY. Routing authority
     * is GET /v1/models; nothing may branch on this list.
     */
    val models: List<DeviceModel> = emptyList(),
    /**
     * Whether a SERVING machine keeps serving with nobody logged in.
     *
     * Three states, and the third is the reason this is nullable: the daemon
     * omits the field entirely for a row whose runner never said, and a client
     * that read absence as `false` would label every machine enrolled before
     * this facet — including the Windows ones, which have always run as
     * LocalSystem services — as stopping at logout. Null renders nothing.
     */
    val persistent: Boolean? = null,
    /**
     * True when this daemon has not heard the device ASK FOR WORK since it
     * started, so whether it is free is not something it can currently say.
     *
     * ⚠ Reachable is not the same as free, and the daemon used to answer the
     * second question with the first. Its map of running remote work is
     * in-memory, so restarting it wipes that — while the far machine is still
     * running its claude and is single-job: it will not poll again until that
     * child exits, minutes or hours later.
     */
    val awaitingPoll: Boolean = false,
    /**
     * Whether a lock screen withdraws anything on this machine — its owner's
     * "Keep act mode while locked" answer, reported so a reader can tell a box
     * running unattended on purpose from one nobody has locked.
     *
     * Three states, like [persistent] and for the same reason: the daemon omits
     * the field for a row whose runner never said, and reading absence as
     * `false` would claim every machine enrolled before this existed had been
     * asked and had declined. Null renders nothing.
     *
     * DISPLAY ONLY here. [locked] and [effectiveScope] already carry the
     * authority; this is the sentence that explains why they agree.
     */
    val actWhileLocked: Boolean? = null,
)

/** One entry of a serving machine's advertised catalog. Display only. */
@Serializable
data class DeviceModel(
    val slug: String = "",
    val display: String = "",
)

@Serializable
data class DeviceList(val devices: List<Device> = emptyList())

/**
 * One job for a device.
 *
 * Note what is absent: no tool list, no permissions, no scope. This is a REQUEST.
 * The device turns it into an argv using its own policy, which is the whole
 * security story — see DevicePolicy.
 */
@Serializable
data class DeviceWork(
    val id: String,
    val chatId: String,
    val prompt: String = "",
    val mode: String = "ask",
    val model: String? = null,
    val effort: String? = null,
    val resumeSessionId: String? = null,
    val roundId: String? = null,
    val issuedAt: Long = 0,
)

/** The long poll's answer: a job, or nothing this time round. */
@Serializable
data class WorkEnvelope(val work: DeviceWork? = null)

@Serializable
data class BeatResult(
    val ok: Boolean = false,
    val effectiveScope: String = "look",
    /**
     * The daemon telling the device to stop the run in flight. It rides the beat
     * as well as the events ack because a long QUIET tool posts no event batches —
     * so the ack channel a Stop would otherwise arrive on is silent for minutes,
     * and the 60s beat is the only thing still reaching the runner.
     */
    val cancel: Boolean = false,
)

/**
 * The daemon's answer to a batch of results.
 *
 * [cancel] is the one thing a device needs told mid-run. It kills its own child
 * and posts a terminal frame, so the ending is still something the device SAYS
 * rather than something the daemon infers from a dropped connection.
 */
@Serializable
data class EventsAck(
    val ok: Boolean = false,
    val done: Boolean = false,
    val cancel: Boolean = false,
)

// --------------------------------------------------------- session overview

/**
 * A session's ledger, read off its whole transcript by the daemon.
 *
 * Deliberately its own fetch rather than fields on [Session]: the list is polled
 * by everything, and this costs a walk of a file that reaches thirty megabytes.
 */
@Serializable
data class GraphTokens(
    val input: Long = 0,
    val output: Long = 0,
    val cacheRead: Long = 0,
    val cacheCreation: Long = 0,
) {
    /** What the session WROTE: new context plus generation, not re-reads. */
    val written: Long get() = input + output + cacheCreation
    val all: Long get() = written + cacheRead
}

@Serializable
data class ToolCount(val name: String = "", val count: Int = 0)

/** One model's share of the estimate. The daemon sorts these by spend, biggest first. */
@Serializable
data class ModelCost(val model: String = "", val usd: Double = 0.0)

/**
 * What this session's tokens WOULD have billed at Anthropic's published API list
 * rates. Nothing here was charged — the account is on a subscription — which is
 * why every renderer of it carries the caption from `OverviewFormat.costStat`.
 *
 * Broken down per model because a run that used opus for the work and haiku for a
 * subagent has no single blended rate. [unpricedTokens] are the tokens the
 * daemon's rate table could not price at all — a model it has never seen is
 * counted separately rather than rounded to a neighbouring family, so a total
 * that is missing spend says so instead of looking complete.
 */
@Serializable
data class EstCost(
    val usd: Double = 0.0,
    val byModel: List<ModelCost> = emptyList(),
    val unpricedTokens: Long = 0,
)

@Serializable
data class GraphTotals(
    val wallMs: Long = 0,
    val startedAt: Long? = null,
    val lastActivityTs: Long? = null,
    val turns: Int = 0,
    val userMessages: Int = 0,
    val toolCalls: Int = 0,
    val errors: Int = 0,
    val tokens: GraphTokens = GraphTokens(),
    val agentCount: Int = 0,
    val agentTokens: GraphTokens = GraphTokens(),
    /** Null only when no record in the transcript carried usage at all. */
    val estCost: EstCost? = null,
    /** The share of [estCost] that came from agent files; 0 when a run fanned out to nothing. */
    val agentEstCostUsd: Double? = null,
    val activeAgents: Int = 0,
    val compactions: Int = 0,
    val droppedTokens: Long = 0,
    val filesTouched: Int = 0,
    val models: List<String> = emptyList(),
    val efforts: List<String> = emptyList(),
)

/**
 * Burn rate over the two windows worth having.
 *
 * [tokensPerMin10] and [tokensPerMin60] count WRITTEN tokens; the `all` pair adds
 * cache reads, which are an order of magnitude larger and would drown the number
 * a person is actually watching.
 */
@Serializable
data class GraphRate(
    val tokensPerMin10: Long = 0,
    val tokensPerMin60: Long = 0,
    val allTokensPerMin10: Long = 0,
    val allTokensPerMin60: Long = 0,
    val lastActivityTs: Long? = null,
    val activeRecently: Boolean = false,
)

/**
 * One block on the map.
 *
 * [kind] is `user`, `action`, `response` or `compact`. Unknown kinds are drawn as
 * plain blocks rather than dropped — a newer daemon inventing a fifth is not a
 * reason for the map to develop a hole.
 */
@Serializable
data class GraphNode(
    val id: String = "",
    val kind: String = "action",
    val ts: Long? = null,
    val endTs: Long? = null,
    val durMs: Long = 0,
    val label: String = "",
    val detail: String? = null,
    val tokens: GraphTokens = GraphTokens(),
    val toolCalls: Int = 0,
    val tools: List<ToolCount> = emptyList(),
    val files: Int = 0,
    val errors: Int = 0,
    /** Agent ids that branched from this block. */
    val agents: List<String> = emptyList(),
    val models: List<String> = emptyList(),
    val pre: Long? = null,
    val post: Long? = null,
    val dropped: Long? = null,
)

/**
 * A branch: an agent, where it left the spine and where it came back.
 *
 * [status] is `done`, `running`, `failed`, `stalled` or `orphan`. The last two are
 * not failures — a workflow member whose run journal never recorded a result, and
 * an agent whose join did not survive a compaction — but they are not "done"
 * either, and saying so is the difference between a map and a guess.
 */
@Serializable
data class GraphAgent(
    val id: String = "",
    val agentType: String? = null,
    val description: String? = null,
    val summary: String? = null,
    val spawnNodeId: String? = null,
    val spawnTs: Long? = null,
    val mergeNodeId: String? = null,
    val mergeTs: Long? = null,
    val status: String = "done",
    val tokens: GraphTokens = GraphTokens(),
    val toolCalls: Int = 0,
    val durMs: Long = 0,
    val updatedAt: Long = 0,
    val workflowId: String? = null,
    val depth: Int = 1,
)

@Serializable
data class GraphWorkflow(
    val id: String = "",
    val nodeId: String? = null,
    val ts: Long? = null,
    val members: Int = 0,
)

/**
 * Where the client got to. TWO numbers, because a fan-out grows in two places: a
 * parent writes nothing while six agents run, so a parent-size cursor reports
 * "unchanged" for exactly the stretch worth watching.
 */
@Serializable
data class GraphCursor(val size: Long = 0, val agentBytes: Long = 0)

/** The goals and notes a person keeps against a run. */
@Serializable
data class SessionMeta(
    val goals: String = "",
    val notes: String = "",
    val updatedAt: Long = 0,
    /**
     * Per-session override of the global auto-resume setting.
     *
     * NULL is a third state, not a default: it means "follow the global", and it
     * is why this is a nullable Boolean rather than a Boolean with a default.
     * Writing it requires sending an explicit JSON null.
     */
    val autoResume: Boolean? = null,
)

@Serializable
data class SessionOverview(
    val name: String = "",
    val claudeSessionId: String? = null,
    val sessionId: String? = null,
    val generatedAt: Long = 0,
    val totals: GraphTotals = GraphTotals(),
    val rate: GraphRate = GraphRate(),
    val cursor: GraphCursor = GraphCursor(),
    val meta: SessionMeta = SessionMeta(),
)

@Serializable
data class SessionMetaSaved(
    val ok: Boolean = false,
    val claudeSessionId: String? = null,
    val meta: SessionMeta = SessionMeta(),
)

@Serializable
data class SessionGraph(
    val v: Int = 1,
    val name: String = "",
    val sessionId: String? = null,
    val generatedAt: Long = 0,
    /** True when the cursor matched: everything else is absent and the held copy stands. */
    val unchanged: Boolean = false,
    val totals: GraphTotals = GraphTotals(),
    val rate: GraphRate = GraphRate(),
    val nodes: List<GraphNode> = emptyList(),
    val agents: List<GraphAgent> = emptyList(),
    val workflows: List<GraphWorkflow> = emptyList(),
    val cursor: GraphCursor = GraphCursor(),
    val meta: SessionMeta = SessionMeta(),
)

// ----------------------------------------------------------------- projects
//
// A PROJECT is a durable cluster of tmux sessions with roles: a lead that sizes
// the work and proposes the cluster, members that do it, and a dashboard that
// sums what they have done. The daemon owns membership, the names and the
// personas; Claude Code owns the messaging between them.
//
// ⚠⚠ THERE ARE TWO NAMES PER SESSION AND THEY ARE NOT INTERCHANGEABLE.
// [ProjectMember.name] is the TMUX name (`<slug>-<role>`) — the handle every
// other route on this daemon addresses a session by. [ProjectMember.claudeName]
// is the PEER name (`<slug>/<role>`) — what `--name` was given, what a peer's
// `SendMessage` addresses, and what a peer message is labelled with. A slash is
// not a tmux name character, which is exactly why the daemon carries both.
//
// ⚠ EVERY FIELD HERE IS NULLABLE OR DEFAULTED, INCLUDING THE IDS, so a daemon
// that has moved on leaves a row that still draws rather than a decode that
// takes the whole list with it. A renamed field is caught by the fixtures in
// ApiContractTest — which are generated from the daemon's OWN tests — and never
// by a decode failure on a phone.
//
// ⚠ EVERY CLOCK ON THESE ROUTES IS EPOCH SECONDS. `Math.floor(Date.now()/1000)`
// is the only stamp lib/projects.js and the routes ever write.

/**
 * A session addressed by its Claude Code peer name — the sender of a peer
 * message, or the subject of an idle notice.
 *
 * ⚠ THIS IS THE TRANSCRIPT'S PEER, NOT A PROJECT'S LEAD. It is what
 * [TranscriptEvent.peer] carries, and the daemon builds it in `lib/transcript.js`
 * from a record whose only address of record is a unix socket path: [name] is
 * the `--name <slug>/<role>` form, [pid] the kernel-verified peer pid, and
 * [sessionId] is null unless the daemon could map that pid to a session.
 */
@Serializable
data class ProjectPeer(
    val name: String = "",
    val sessionId: String? = null,
    /** The verified peer pid. Present on a native peer message, null on an idle notice. */
    val pid: Int? = null,
)

/**
 * The lead: the session that is briefed, sizes the work, and proposes the rest.
 *
 * One type for both shapes the daemon emits, because the second is the first
 * minus two fields plus one: a [Project]'s own lead carries [spawnedAt] and
 * [endedAt], a [ProjectRow]'s carries [present] instead. Modelled together so a
 * caller never has to ask which lead it is holding.
 */
@Serializable
data class ProjectLead(
    val role: String = "lead",
    /** The TMUX name: `<slug>-lead`. */
    val name: String = "",
    /** The PEER name: `<slug>/lead`. What SendMessage addresses. */
    val claudeName: String = "",
    val sessionId: String? = null,
    val spawnedAt: Long? = null,
    val endedAt: Long? = null,
    /** Row-only: whether the lead's tmux session is live. Null on a stored project. */
    val present: Boolean? = null,
)

/**
 * One member of a project's cluster, as the STORED RECORD holds it.
 *
 * This is membership, not liveness — it says what was created and how, and it is
 * what `POST …/spawn` answers with. What a member is DOING is [ProjectLive] and
 * [ProjectDashboardMember]; a row here with no live row beside it is a member
 * whose session the daemon has not observed.
 */
@Serializable
data class ProjectMember(
    val role: String = "",
    /** The TMUX name: `<slug>-<role>`. */
    val name: String = "",
    /** The PEER name: `<slug>/<role>`. */
    val claudeName: String = "",
    val sessionId: String? = null,
    /** Where this member was launched. Null means the project's own directory. */
    val cwd: String? = null,
    val model: String? = null,
    val effort: String? = null,
    val mode: String? = null,
    /** The WHOLE first message this session was given. */
    val firstPrompt: String? = null,
    val spawnedAt: Long? = null,
    val endedAt: Long? = null,
)

/**
 * What both LIVE shapes carry, so one set of rules serves the list and the
 * dashboard.
 *
 * ⚠ TWO INDEPENDENT VOCABULARIES MEET HERE AND THEY ARE NOT THE SAME WORDS.
 * [status] is Claude Code's own native registry — `busy` | `idle` | `waiting` —
 * and the daemon passes an unrecognised word through as NULL rather than
 * guessing. [state] is the title hook's session vocabulary — `running` |
 * `attention` | `idle` — which every session row in this app already draws.
 * [ProjectRules.stateWord] is the one place either becomes a mark.
 */
interface ProjectMemberState {
    val role: String
    val name: String
    val claudeName: String
    val sessionId: String?
    /** True for the lead's own row. The daemon's rollup counts exclude it. */
    val lead: Boolean
    /** The tmux session exists. */
    val present: Boolean
    /** The `claude` process is alive — pid + procStart, never a timestamp. */
    val alive: Boolean
    /** `busy` | `idle` | `waiting` | null. An unknown word is null, never a guess. */
    val status: String?
    /** What a `waiting` session is waiting for, in the registry's own words. */
    val waitingFor: String?
    /** The daemon's own promotion: native `waiting`, or the hook's `attention`. */
    val needsYou: Boolean
    /** `running` | `attention` | `idle` | null — the session vocabulary. */
    val state: String?
    val pendingSends: Int
    val headroom: SessionHeadroom?
    val title: String?
    val endedAt: Long?
    val spawnedAt: Long?
}

/**
 * One member as `GET /v1/projects/:id` reports it live: the membership row
 * joined to the tmux session and to Claude Code's own registry.
 *
 * ⚠ THE JOIN IS BY SESSION ID AND THE LOOKUP IS BY TMUX NAME — never by the
 * native row's `tmux` field, which is inherited `$TMUX` and is wrong for a
 * nested launch and for every `claude -p`. That is the daemon's rule; this is
 * the shape it produces.
 */
@Serializable
data class ProjectLive(
    override val role: String = "",
    override val name: String = "",
    override val claudeName: String = "",
    override val sessionId: String? = null,
    override val present: Boolean = false,
    override val alive: Boolean = false,
    override val status: String? = null,
    override val waitingFor: String? = null,
    /** The native registry's own bridge id, when this session has one. */
    val bridgeSessionId: String? = null,
    /** The name the native registry knows this session by — usually [claudeName]. */
    val nativeName: String? = null,
    override val needsYou: Boolean = false,
    override val state: String? = null,
    val stateSince: Long? = null,
    override val pendingSends: Int = 0,
    override val headroom: SessionHeadroom? = null,
    override val title: String? = null,
    override val endedAt: Long? = null,
    override val spawnedAt: Long? = null,
    override val lead: Boolean = false,
    /** When the daemon took this observation. */
    val checkedAt: Long = 0,
) : ProjectMemberState

/** One session the lead's manifest asks for. The daemon re-validates every field. */
@Serializable
data class ManifestSession(
    val role: String = "",
    /** The WHOLE first message this session will receive. */
    val firstPrompt: String = "",
    /** Null means the project's own directory; anything else is inside it. */
    val cwd: String? = null,
    /** `fable` | `opus` | `sonnet` | `haiku` | null. An unknown word is already null. */
    val model: String? = null,
    /** `low` | `medium` | `high` | `xhigh` | `max` | null. */
    val effort: String? = null,
    /** `ask` | `act` | `auto` | `plan` | null. */
    val mode: String? = null,
)

/**
 * The lead's proposal, as the card shows it and as Spawn quotes it back.
 *
 * ⚠ THE PROPOSAL IS STRUCTURED, AND THE STRUCTURE IS THE DAEMON'S. The lead
 * writes a tagged fenced block; `lib/projects.js` parses it, refuses anything
 * that decides what gets created, and emits [sessions]. A client that re-parsed
 * the block would be a second opinion about the thing the owner is approving —
 * so this carries the parsed result and nothing here re-reads a fence.
 *
 * ⚠ THE TAG IS DELIBERATELY NOT MODELLED. It is the anti-injection secret that
 * lives in the lead's system prompt; the client has no use for it and a field
 * for it is a field something eventually renders.
 *
 * [rev] is what a Spawn quotes back so a card that has been sitting on a lock
 * screen cannot approve a plan the owner never saw. [spawnedRev] is the rev that
 * was last carried out, so "this proposal is already running" is answerable.
 */
@Serializable
data class ProjectManifest(
    val rev: Int = 0,
    val receivedAt: Long? = null,
    /** The cluster's own label — one of `ProjectRules.KINDS`, or null. Nothing branches on it. */
    val type: String? = null,
    /** The paragraph the lead wrote about what this cluster is for. */
    val scope: String = "",
    /** The one line the card leads with. One line by the daemon's rule, not ours. */
    val summary: String? = null,
    val sessions: List<ManifestSession> = emptyList(),
    /**
     * The lead wrote a proposal block WITHOUT this project's tag, so the daemon
     * ignored it. Said on the card: an untagged block looks to its author like a
     * proposal that was made and to the owner like nothing happened.
     */
    val untaggedSeen: Boolean = false,
    /** The rev a spawn was last carried out at. 0 until one has been. */
    val spawnedRev: Int = 0,
)

/**
 * A cluster of sessions with roles, the brief that started it and the proposal
 * that will fill it.
 *
 * [rev] is the record's own revision and is what a PATCH quotes back; it has
 * nothing to do with [ProjectManifest.rev], which counts proposals.
 */
@Serializable
data class Project(
    val id: String = "",
    val name: String = "",
    /** The tmux and peer namespace. Derived from the name once, and it NEVER moves. */
    val slug: String = "",
    /** One of `ProjectRules.KINDS`. */
    val kind: String = "",
    /** One of `ProjectRules.STATUSES`. */
    val status: String = "",
    /** The paragraph the owner typed; the whole first message the lead gets. */
    val brief: String = "",
    val cwd: String = "",
    val lead: ProjectLead? = null,
    val members: List<ProjectMember> = emptyList(),
    val manifest: ProjectManifest? = null,
    /** Why this project ended, in the daemon's words. Set when it archived itself. */
    val endedReason: String? = null,
    val endedAt: Long? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val rev: Int = 0,
)

/**
 * A project as the TREE draws it: the record's identity plus the live counts,
 * already rolled up by the daemon.
 *
 * ⚠ [alive], [busy] AND [waiting] EXCLUDE THE LEAD. The daemon counts members
 * only — the lead is always there and counting it would make every project read
 * as one session busier than it is. [memberCount] is the stored membership, so
 * `memberCount - alive` is the members whose process is not answering.
 *
 * Every field has a definite value: a row that decoded is a row that renders.
 */
@Serializable
data class ProjectRow(
    val id: String = "",
    val name: String = "",
    val slug: String = "",
    val kind: String = "",
    val status: String = "",
    val cwd: String = "",
    val memberCount: Int = 0,
    val alive: Int = 0,
    val busy: Int = 0,
    val waiting: Int = 0,
    val lead: ProjectLead? = null,
    val manifestRev: Int = 0,
    /**
     * The rev a spawn was last carried out at, beside the rev being proposed.
     *
     * `manifestRev > spawnedRev` is "this proposal is still waiting for an
     * answer", which a list could otherwise only learn by GETting every project —
     * one call per row to draw one list. Read through
     * [ProjectRules.alreadySpawned].
     *
     * ⚠ 0 IS "NOT SPAWNED", AND THAT IS THE SAFE DEFAULT. A daemon older than
     * this field sends nothing; reading the absence as "already done" would hide
     * Spawn on a proposal nobody has answered.
     */
    val spawnedRev: Int = 0,
    val manifestSummary: String? = null,
    val untaggedSeen: Boolean = false,
    val endedReason: String? = null,
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val rev: Int = 0,
)

/**
 * `GET /v1/projects` — the rows and the cap.
 *
 * ⚠ ALSO THE FEATURE PROBE — see [HuginnClient.projects], which turns the 404
 * from a daemon that has never heard of projects into a null rather than into an
 * error a screen would have to explain.
 *
 * Archived projects are left out unless `?all=1` was asked for.
 */
@Serializable
data class ProjectList(
    val projects: List<ProjectRow> = emptyList(),
    /** How many projects one host keeps. On the wire so a client can say what it is. */
    val max: Int = 0,
)

/**
 * `GET /v1/projects/:id` — the whole record, the row the tree draws, and every
 * member's live state in one answer.
 *
 * ⚠ AN ENVELOPE, AND IT IS DECODED ONCE. The route used to SPREAD the project
 * into the top level (`{...project, row, live}`), which reserves two words in
 * the project's own namespace without saying so: the day a project gains a field
 * called `row` or `live` — neither is a strange name for one — the spread
 * overwrites the daemon's own and the tree draws a project out of whatever the
 * record happened to hold, with nothing to see in the diff. Three named keys
 * cannot collide, so this decodes whole instead of being assembled out of two
 * passes over the same body (`lib/projects.js`, and the daemon's own
 * "the detail route answers {project, row, live}" test).
 */
@Serializable
data class ProjectDetail(
    val project: Project = Project(),
    val row: ProjectRow? = null,
    val live: List<ProjectLive> = emptyList(),
)

/**
 * One member as the dashboard sees it: the live join plus its share of the spend.
 *
 * ⚠ THE AGENT COUNT, NOT THE AGENT RUNS. An agent id is scoped to the session
 * that spawned it, so a project-wide list of them would be a list of handles
 * that address nothing from here. The daemon sends [agentCount] and no ids, and
 * that is a decision rather than an omission.
 */
@Serializable
data class ProjectDashboardMember(
    override val role: String = "",
    override val name: String = "",
    override val claudeName: String = "",
    override val sessionId: String? = null,
    override val lead: Boolean = false,
    override val present: Boolean = false,
    override val alive: Boolean = false,
    override val status: String? = null,
    override val waitingFor: String? = null,
    override val needsYou: Boolean = false,
    override val state: String? = null,
    val stateSince: Long? = null,
    override val pendingSends: Int = 0,
    override val headroom: SessionHeadroom? = null,
    override val title: String? = null,
    val turns: Int = 0,
    val tokens: GraphTokens = GraphTokens(),
    /** Null when nothing this member ran could be priced. */
    val estCostUsd: Double? = null,
    val agentCount: Int = 0,
    val lastActivityTs: Long? = null,
    override val endedAt: Long? = null,
    override val spawnedAt: Long? = null,
) : ProjectMemberState

/**
 * The cluster's pace, as the dashboard route reports it.
 *
 * ⚠ TOKENS PER MINUTE, MEASURED OVER A 10- OR 60-MINUTE WINDOW — the same unit
 * a single session's [GraphRate] reports, and the same spelling. The members'
 * rates are ADDED, and a sum of per-minute rates is still per minute. It was
 * briefly `tokensPer10m` here, which read as "tokens per 10 minutes" and was
 * wrong by a factor of ten to anyone who believed it — while the dashboard
 * beside it rendered the number as "N tokens/min over 10m", which is the tell.
 *
 * ⚠ STILL NOT [GraphRate]. Two types remain, because this one has no `all` pair
 * and no `lastActivityTs`; only the lie in the spelling is gone.
 */
@Serializable
data class ProjectRate(
    val activeRecently: Boolean = false,
    val tokensPerMin10: Long = 0,
    val tokensPerMin60: Long = 0,
)

/**
 * `GET /v1/projects/:id/dashboard`.
 *
 * [totals] is the members' own overviews SUMMED BY THE DAEMON, in exactly the
 * additive `GraphTotals` shape a single session's overview carries — which is
 * why the dashboard can reuse `StatsHeader` instead of growing a second
 * vocabulary for one set of facts.
 *
 * ⚠ `wallMs` IN THERE IS A SPAN, NOT A SUM. Twelve sessions running for an hour
 * each took an hour, not twelve, so the daemon reports the distance from the
 * earliest start to the latest activity.
 *
 * ⚠ THE CLOCK IS `generatedAt`, NOT `updatedAt`. It is when this poll was
 * answered, which is the only honest thing to say about a rollup.
 */
@Serializable
data class ProjectDashboard(
    val project: ProjectRow? = null,
    val generatedAt: Long = 0,
    val totals: GraphTotals? = null,
    val rate: ProjectRate? = null,
    val members: List<ProjectDashboardMember> = emptyList(),
)

/** One role a spawn could not create, with the daemon's own sentence about why. */
@Serializable
data class SpawnFailure(
    val role: String = "",
    /**
     * Shown VERBATIM. "duplicate session: half-mid" and "persona could not be
     * written" are different problems with different fixes, and a client's
     * summary of either helps nobody.
     */
    val reason: String = "",
)

/**
 * `POST /v1/projects/:id/spawn` — HTTP 200, whatever happened.
 *
 * ⚠⚠ [ok] IS FALSE ON A 200 AND THAT IS THE NORMAL CASE. Spawning is a loop over
 * tmux: the second of three roles failing does not un-spawn the first, so the
 * daemon carries on, creates the rest, and answers with both lists. A client
 * that read the status code as the verdict would report a working cluster as a
 * failure — and a card that collapsed this to one boolean would lose which role
 * to retry.
 */
@Serializable
data class SpawnResult(
    val ok: Boolean = false,
    val spawned: List<ProjectMember> = emptyList(),
    val failed: List<SpawnFailure> = emptyList(),
    /** The project as it now stands — `active` once anything came up. */
    val project: Project? = null,
)

/**
 * The answer to a Spawn: what happened, or a refusal that stopped the whole
 * thing before any session was made.
 *
 * ⚠ THE TWO 409s THAT MATTER, AND THEY ARE BOTH STATES RATHER THAN ERRORS. The
 * headroom arbiter's STOP sentinel is armed (spawning twelve sessions into a red
 * usage window is how a cluster dies half-born), or the manifest moved under the
 * card — which comes back carrying the CURRENT project so the card can redraw
 * itself around the plan that is actually on offer.
 */
data class SpawnOutcome(
    val result: SpawnResult?,
    val refusal: String?,
    /** The current project, when the refusal was a stale manifest rev. */
    val project: Project? = null,
) {
    val ok: Boolean get() = refusal == null
    val spawned: List<ProjectMember> get() = result?.spawned.orEmpty()
    val failed: List<SpawnFailure> get() = result?.failed.orEmpty()
}

/**
 * `POST /v1/projects/:id/message` — a line typed into one member, addressed from
 * another, through the send queue.
 *
 * ⚠ THIS IS NOT HOW THE SESSIONS TALK. A native `SendMessage` goes process to
 * process over a unix socket and starts a turn with no keypress; the daemon
 * neither sees nor routes it. This route is appd TYPING, which is exactly why
 * the answer carries the queue's own facts.
 */
@Serializable
data class ProjectMessageResult(
    val ok: Boolean = false,
    /** The peer names, echoed back: `<slug>/<role>`. */
    val to: String = "",
    val from: String = "",
    val delivered: Boolean = false,
    /** How many sends are waiting for that member, this one included. */
    val queued: Int = 0,
    /** `turn` | `modal` | `starting` | null — what the queue is waiting on. */
    val blockedBy: String? = null,
    /** Set when the queue dropped the message instead of holding it. */
    val dropped: String? = null,
)

/**
 * `DELETE /v1/projects/:id` — the record is always removed; the sessions are
 * only ended if that was asked for.
 *
 * ⚠ [mode] IS `none` BY DEFAULT AND MUST READ THAT WAY. A delete that silently
 * killed twelve live sessions is not a delete anybody meant, so the daemon ends
 * nothing unless told to and says which it did.
 */
@Serializable
data class ProjectDeleted(
    val ok: Boolean = false,
    /** The tmux names that were actually ended. */
    val ended: List<String> = emptyList(),
    /** `graceful` | `now` | `none`. */
    val mode: String = "none",
)

/**
 * The 409 body `POST /v1/projects` refuses with: the sentence, and which of the
 * three refusals it is.
 *
 * ⚠ THREE REFUSALS SHARE THAT STATUS AND THEY HAVE THREE DIFFERENT FIXES —
 * `untrusted-cwd` (trust the directory in Claude Code once), `slug-taken` (pick
 * another name) and `name-taken` (go and end the tmux session squatting the
 * lead's name). No client can tell them apart from a sentence, so [reason] is
 * the discriminator to branch on while [error] stays the thing a person reads,
 * because it is also the instruction.
 */
@Serializable
data class ProjectRefusal(
    val error: String? = null,
    /**
     * `untrusted-cwd` · `slug-taken` · `name-taken`, or NULL.
     *
     * ⚠ NULL IS A REAL ANSWER: a daemon older than this field sends the sentence
     * and nothing else, and a newer one may invent a fourth word. Either way the
     * sentence is still the whole fix, so an unrecognised refusal must stay
     * showable rather than be mapped onto one of these.
     */
    val reason: String? = null,
)

/**
 * The answer to `POST /v1/projects`: the project, or the daemon's refusal.
 *
 * ⚠ A 409 IS AN ANSWER HERE, NOT A THROW — the [ScratchpadSave] precedent, for
 * the same reason. The commonest refusal is a working directory Claude Code has
 * not been trusted in, and the daemon's sentence about it ("open it once with
 * `claude` there and accept the folder-trust question, then create the project")
 * is the entire fix. Thrown, it would arrive on the failure path as a red line
 * with no project attached; answered, the sheet keeps everything the person
 * typed and shows what to do under the field.
 *
 * [reason] is which of the three it was, so the sheet can put the sentence under
 * the directory field or the name field rather than at the bottom of the form.
 * See [ProjectRefusal].
 */
data class ProjectCreated(
    val project: Project?,
    val refusal: String?,
    val reason: String? = null,
) {
    val ok: Boolean get() = project != null
}

/**
 * The answer to a project edit: what the server now holds, and whether it took
 * ours.
 *
 * ⚠ THE 409 HAS TWO SHAPES AND ONLY ONE OF THEM IS A CONFLICT. A stale `rev`
 * comes back as the CURRENT PROJECT, bare, for the editor to adopt — the
 * saveScratchpad contract. An illegal status move ("an active project cannot
 * become proposed") comes back as an `{error}` with no project in it, and that
 * is a refusal of the request rather than a race. [conflict] is the one that
 * says which.
 */
data class ProjectSave(
    val project: Project?,
    val conflict: Boolean,
    val refusal: String? = null,
) {
    val ok: Boolean get() = !conflict && refusal == null
}

// --------------------------------------------------------------------- apps
//
// An APP is something huginn makes and hosts itself — armap, the jtyper
// trainer, the board view, the BTC sim — with a name, an icon, a note and a
// liveness probe. It is not a Device: a device is another machine that enrols
// and decides for itself what it will do. There is no shared key, no shared
// lifecycle and no shared security story, so there is no shared model either.
//
// ⚠ THIS WAS CALLED "CONSOLES" UNTIL THE 3.6 TRAIN (owner decision 53). The
// wire moved to `/v1/apps*` and `/v1/consoles*` stays as an alias for ONE
// release (decision 56); [HuginnClient.apps] asks for the new name first and
// falls back to the old one, so a client can be ahead of its daemon exactly
// once. Nothing here is named Console any more — a type alias would have kept
// the old word alive in the palette, the store and every stack trace, which is
// the opposite of a rename.

/**
 * One address among the ones huginn itself answers on, and whether the app
 * answered there too.
 *
 * ⚠ THE ADDRESS IS THE CLIENT'S, NOT THE APP'S. These are the addresses DEVICES
 * ARRIVE ON — the `via` local addresses seen on live connections, plus the
 * tailnet one. The rule the owner set (decision 54) is that if a device can
 * reach huginn to read the Apps page, it must be able to reach the app, and
 * this is the per-address evidence for that.
 */
@Serializable
data class AppAddress(
    val addr: String = "",
    val ok: Boolean = false,
    /** Why it did not answer. Absent on one that did. */
    val error: String? = null,
)

/**
 * Whether the app answers where a phone or a laptop would ask for it — and, when
 * it does not, the exact lines that would fix it.
 *
 * ⚠⚠ [ok] IS A TRI-STATE, LIKE [App.up] AND FOR THE SAME REASON. `false` means
 * the check ran and at least one address did not answer; `null` means no check
 * has produced a verdict, which after a daemon restart is every row. "Needs
 * retrofit" about a row nobody has checked is an accusation.
 *
 * ⚠ [fix] IS PER ROW NOW (decision 55). It used to be one list-level approval
 * card covering the whole registry; it is the failing row's own disclosure,
 * because the rebind is per unit and a card showing four units' commands made
 * the reader work out which two were theirs.
 */
@Serializable
data class AppReachability(
    /** true reachable · false needs retrofit · NULL not checked yet. */
    val ok: Boolean? = null,
    /** Epoch SECONDS; 0 when nothing has been checked. */
    val checkedAt: Long = 0,
    val addresses: List<AppAddress> = emptyList(),
    /** Shown VERBATIM, in this order. Never re-derived, never reformatted. */
    val fix: List<String> = emptyList(),
    /**
     * What the daemon has to say about the check itself, or `""`.
     *
     * ⚠ THIS IS WHY [ok] IS NULL, WHEN IT IS. A null verdict on this daemon means
     * the PROBE SET WAS EMPTY — no usable bind address and no tailnet address to
     * try — and "not checked yet" on its own leaves the reader waiting for a
     * check that is never going to run. The daemon's own sentence ("no address to
     * probe yet") is the only thing that says so.
     */
    val note: String = "",
)

/**
 * One app this host serves.
 *
 * ⚠⚠ [up] IS A TRI-STATE AND THE THIRD ONE IS LOAD-BEARING. `false` means the
 * probe ran and nothing answered; `null` means no probe has produced a verdict —
 * and the daemon keeps probe state IN MEMORY ONLY, so every restart puts every
 * row back to null. Folding that to `false` would draw four outages on a host
 * where nothing is wrong. See [AppRules.reachabilityWords], which refuses to say
 * "not answering" about it.
 *
 * ⚠ [lastProbeAt] IS 0, NOT NULL, WHEN NOTHING HAS BEEN PROBED — the daemon's
 * own default. `0` is the same "no stamp" every time-word helper here already
 * renders as nothing; what it must never become is a date in 1970.
 *
 * ⚠ [icon] IS A CLAIM, NOT A URL. The favicon is fetched and cached by the
 * DAEMON on probe and served back through `GET /v1/apps/:id/icon` under the
 * bearer, so a client cannot point an image at it — it fetches the bytes the way
 * the transcript fetches a thumbnail. False means there is none and the route
 * would 404, which is the row's cue to draw its initial-letter tile without
 * spending a request to find that out.
 */
@Serializable
data class App(
    val id: String = "",
    val name: String = "",
    val url: String = "",
    /** One of `AppRules.KINDS`. An unknown word becomes `other`, never null. */
    val kind: String? = null,
    val notes: String? = null,
    /**
     * The systemd unit behind it, named by the fix lines.
     *
     * ⚠ THE DAEMON ALWAYS SENDS A STRING and uses `""` for "none" — so every
     * reader here has to treat blank as absent rather than only null. Nullable
     * anyway, because the pre-rename body omits the key entirely.
     */
    val unit: String? = null,
    /** Epoch SECONDS. */
    val addedAt: Long = 0,
    /** The revision a PATCH quotes back, so two clients cannot silently overwrite. */
    val version: Int = 1,
    /** true up · false not answering · NULL no verdict yet. Never collapse the third. */
    val up: Boolean? = null,
    /** Epoch SECONDS; 0 when nothing has been probed. */
    val lastProbeAt: Long = 0,
    val latencyMs: Int? = null,
    /** The status the probe saw. A 401 or 403 page is UP: something answered. */
    val httpStatus: Int? = null,
    /** Whether the daemon holds a favicon for this row. See the KDoc. */
    val icon: Boolean = false,
    /**
     * When the cached favicon BYTES last CHANGED, in epoch SECONDS (appd 3.5.2).
     * `0` when there is no icon.
     *
     * ⚠ THIS IS THE CACHE KEY, AND [version] WAS THE WRONG ONE. A client caches
     * the decoded picture per row — it has to; rows are drawn in a list that
     * recycles — and keyed the cache on `version`, which is the ROW's edit
     * history and never moves for a refetch. The daemon re-fetches a favicon on
     * its own schedule (hourly, on probe), so a site that changed its icon
     * served the old picture for the life of the process, with nothing in the
     * logs and no way to make it let go short of a reinstall. This moves ONLY
     * when the picture does — not even on a refetch that came back identical,
     * which the file's mtime cannot say.
     *
     * ⚠ NULLABLE BECAUSE OF THE ALIAS, NOT BECAUSE OF THE VALUE. `/v1/apps` rows
     * always carry it (POST 201, PATCH 200, probe 200, and inside a 409 body
     * under `app`); the `/v1/consoles` alias kept for one release answers the
     * 3.4 shape and OMITS it entirely. Absent and 0 mean the same thing to a
     * reader — see [iconStamp] — and they should, because a client talking to
     * the alias is a client whose pictures simply do not refresh.
     */
    val iconAt: Long? = null,
    val reachable: AppReachability = AppReachability(),
) {
    /**
     * [iconAt] with the daemon's own "no stamp" answer folded in.
     *
     * ⚠ 0, NOT NULL, AND IT IS A CACHE KEY LIKE ANY OTHER. Against a daemon that
     * does not send the field every row keys on 0 and behaves exactly as an
     * id-only key did — one fetch per row per process — which is the old
     * behaviour, not a new bug. What it must never become is a date in 1970.
     */
    val iconStamp: Long get() = iconAt ?: 0
}

/**
 * `GET /v1/apps` — the registry, its caps, and how far the retrofit has got.
 *
 * ⚠ ALSO THE FEATURE PROBE. A daemon older than apps answers 404 at BOTH names
 * and [HuginnClient.apps] returns null for it — `appsAvailable = false`, the
 * `padsAvailable` pattern — rather than throwing at a screen that would then
 * have to explain the absence of something nobody asked for.
 *
 * ⚠ THERE IS NO APPROVAL CARD ANY MORE. [retrofitApplied] is the marker file's
 * one remaining fact, kept for the transition so the page can say in one line
 * that the job is outstanding; the commands themselves live on the rows that
 * need them.
 */
@Serializable
data class AppList(
    val apps: List<App> = emptyList(),
    val max: Int = 0,
    /** The daemon's closed vocabulary for `kind`, so an editor can offer it. */
    val kinds: List<String> = emptyList(),
    val retrofitApplied: Boolean = false,
    /** The addresses this host's clients arrive on — what a row is probed against. */
    val clientAddresses: List<String> = emptyList(),
)

/**
 * The answer to an add.
 *
 * ⚠⚠ A 422 IS AN ANSWER, NOT A FAILURE, and it is the shape the whole add flow
 * turns on. The daemon refused because the app does not answer where this
 * person's devices arrive (decision 54) — they are going to run [reachable]'s
 * fix lines and press Add again, so the form stays open with what they typed
 * still in it. An exception would have taken the address with it.
 *
 * A 400 (not an address this registry may hold) and a 409 (that name is taken)
 * ARE refusals of the request and still throw: neither is a state of the world
 * the form can wait out.
 */
data class AppCreate(
    val app: App? = null,
    val refusal: String? = null,
    val reachable: AppReachability? = null,
    /**
     * The row already holding this id, on a 409.
     *
     * Carried rather than dropped so a caller that wants to say "that is this
     * one" can; the form only needs [refusal].
     */
    val existing: App? = null,
) {
    val ok: Boolean get() = app != null && refusal == null
}

/** The 422 body of an add: the refusal, and the prerequisite that failed. */
@Serializable
data class AppRefusal(
    val error: String? = null,
    val reachable: AppReachability? = null,
)

/**
 * The answer to an app edit: what the server now holds, and whether it took ours.
 *
 * A conflict is NOT an exception — the [ScratchpadSave] shape, for its reason.
 * It is the expected outcome of the other client having saved first, it arrives
 * carrying the current row and its version, and that is everything the editor
 * needs to adopt it.
 */
data class AppSave(val app: App, val conflict: Boolean, val refusal: String? = null)

/** The 409 body of an app edit: the refusal, and the row as the host holds it. */
@Serializable
data class AppConflict(
    val error: String? = null,
    val app: App? = null,
)

/**
 * What has been typed into the add form, and what the daemon said about it.
 *
 * ⚠⚠ A PLAIN VALUE, HELD BY THE DIALOG, SO "A REFUSAL KEEPS WHAT YOU TYPED" IS A
 * RULE WITH A TEST rather than a property of how somebody happened to write a
 * `remember`. Both shells draw their own fields — an `AlertDialog` on the phone,
 * a `DialogField` column on the desktop — and both feed this one holder, so the
 * 422 behaviour cannot drift between them.
 */
data class AppForm(
    val name: String = "",
    val url: String = "",
    val kind: String? = null,
    val notes: String = "",
    val unit: String = "",
    /** The daemon's sentence, when it refused. */
    val refusal: String? = null,
    /** Its fix lines, verbatim. */
    val fix: List<String> = emptyList(),
    val addresses: List<AppAddress> = emptyList(),
    /** What the daemon said about the check itself, or `""`. */
    val note: String = "",
) {
    /** Enough typed to be worth sending. The daemon re-checks all of it. */
    val sendable: Boolean
        get() = name.isNotBlank() && url.isNotBlank() && AppRules.urlProblem(url) == null

    /** Adopts a refusal WITHOUT touching a single typed field. */
    fun refused(answer: AppCreate): AppForm = copy(
        refusal = answer.refusal,
        fix = answer.reachable?.fix.orEmpty(),
        addresses = answer.reachable?.addresses.orEmpty(),
        note = answer.reachable?.note.orEmpty(),
    )

    /**
     * Drops the refusal, for the next keystroke. An error about text that has
     * since been changed is an error about nothing.
     */
    fun cleared(): AppForm =
        copy(refusal = null, fix = emptyList(), addresses = emptyList(), note = "")
}
