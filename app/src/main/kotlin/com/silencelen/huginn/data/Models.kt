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
)

@Serializable
data class SessionList(val sessions: List<Session> = emptyList())

@Serializable
data class Screen(
    val width: Int = 80,
    val height: Int = 24,
    val cursorX: Int = 0,
    val cursorY: Int = 0,
    val attachedClients: Int = 0,
    val altScreen: Boolean = false,
    val lines: List<String> = emptyList(),
)

@Serializable
data class Chat(
    val id: String,
    val title: String? = null,
    val mode: String = "ask",
    val createdAt: Long = 0,
    val updatedAt: Long = 0,
    val claudeSessionId: String? = null,
    val lastSnippet: String? = null,
    val turns: Int = 0,
    val running: Boolean = false,
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
