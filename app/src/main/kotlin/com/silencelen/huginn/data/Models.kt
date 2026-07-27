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
)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

/** A Claude Code choice prompt, lifted off the pane so it can become buttons. */
@Serializable
data class PromptOption(
    val number: Int,
    val label: String,
    val selected: Boolean = false,
)

@Serializable
data class PanePrompt(
    val question: String = "",
    val options: List<PromptOption> = emptyList(),
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

/** One chat's state in the watch digest. */
@Serializable
data class WatchChat(
    val running: Boolean = false,
    val pending: Int = 0,
    val title: String? = null,
)

/** The change signal a watching client parks on. */
@Serializable
data class Watch(
    val hash: String = "",
    val sessions: Map<String, String?> = emptyMap(),
    val chats: Map<String, WatchChat> = emptyMap(),
    val changed: Boolean = false,
    val serverTime: Long = 0,
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
    val email: String? = null,
)
