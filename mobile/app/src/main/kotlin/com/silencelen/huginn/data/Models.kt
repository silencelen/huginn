package com.silencelen.huginn.data

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

// Wire models for huginn-appd's /v1 API. Every field the server may omit is
// nullable with a default so an older app keeps parsing a newer server (the
// devstore lesson: one missing field must not fail the whole decode).

@Serializable
data class Ping(val ok: Boolean = false, val version: String? = null, val host: String? = null)

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
    /** True when a resize was refused because another client is attached. */
    val resizeBlocked: Boolean = false,
    val hash: String? = null,
    /** Set when a long poll expired with no change; `lines` is then empty. */
    val unchanged: Boolean = false,
    val prompt: PanePrompt? = null,
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
    val nextOffset: Long = 0,
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
    val partialText: String? = null,
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

@Serializable
data class ExtraUsage(
    val utilization: Double? = null,
    val usedCredits: Double? = null,
    val monthlyLimit: Double? = null,
    val currency: String = "USD",
    val spendLimitReached: Boolean = false,
)

@Serializable
data class Plan(
    val limits: List<PlanLimit> = emptyList(),
    val extraUsage: ExtraUsage? = null,
    val fetchedAt: Long? = null,
    val error: String? = null,
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
)

@Serializable
data class SavedAccounts(val accounts: List<SavedAccount> = emptyList())

/** A model the installed CLI offers, discovered on the host. */
@Serializable
data class ModelChoice(
    val id: String = "",
    val display: String = "",
    val family: String = "",
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

@Serializable
data class PushTestResult(
    val ok: Boolean = false,
    val sent: Int = 0,
    val dead: Int = 0,
    val failed: Int = 0,
    val error: String? = null,
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
