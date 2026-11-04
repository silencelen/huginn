package com.silencelen.huginn.notify

/**
 * A CHAT's Claude is waiting on you, decided without a Context.
 *
 * The daemon pushes `{kind: "chat_attention", subject: <chat id>, title, text,
 * options: []}` when a chat's Claude has asked for a new session
 * (`start_session`) and the request is parked on the chat's card. The tool call
 * BLOCKS until the owner answers (up to an hour), so the chat is stopped on
 * you — that is "needs you", not news.
 *
 * Before this kind existed the push fell through to the generic post: the
 * shared slot every unknown kind lands in, and a tap that opened whatever the
 * app last showed rather than the chat.
 *
 * ## What it may carry, and what it never does
 *
 * - The BLOCKING channel ("Needs you"), never "Finished": silencing chat news
 *   must not silence a chat that has stopped until you answer.
 * - A tap opens THAT chat — [PostArgs.openChat], the deep link a finished chat
 *   has — where the card shows the directory and the whole prompt.
 * - NO reply box and NO buttons. Start answers with a prompt Claude wrote, and
 *   the card is where every character of it is shown (security scan
 *   2026-11-02, F1); the shade can show none of that, and free text from a lock
 *   screen is the power the owner's rule keeps off it. The wire's `options` is
 *   read for nothing.
 * - Withheld while the reader is looking at that chat ([shows]): the card is in
 *   front of them already.
 * - Its own slot ([keyFor]), apart from the chat's finished thread, so neither
 *   replaces the other. It comes DOWN when the chat is opened (read is
 *   dismissed, MainActivity) and when the chat finishes ([withdraws]) — by
 *   push or by the watch cycle — because a run that ended is not waiting on
 *   anyone. Nothing re-derives it from an observation, so nothing re-posts it.
 */
object ChatAttention {

    /** The push kind (appd, r3). */
    const val KIND: String = "chat_attention"

    /** The kind that ends the wait: the run that asked has finished. */
    const val KIND_FINISHED: String = "chat_finished"

    /**
     * The slot. Not `chat:<id>`, which is the chat's finished thread (the reply
     * box's MessagingStyle history), and not a bare id; a tmux session name
     * cannot contain `:`, so no session's slot can collide with it either.
     */
    fun keyFor(chatId: String): String = "chat-ask:$chatId"

    data class Notice(
        val key: String,
        val title: String,
        val text: String,
        val chatId: String,
    )

    /**
     * Exactly how a [Notice] is handed to [SessionWatchWorker.post]. The nulls
     * and empties are doors held shut, asserted by test: no `replyChat` (no
     * free text), no `answers`/`fingerprint` (no buttons), no `session`, and
     * `isResult` false (the blocking channel).
     */
    data class PostArgs(
        val title: String,
        val text: String,
        val key: String,
        val openChat: String,
        val session: String? = null,
        val replyChat: String? = null,
        val answers: List<SessionWatchWorker.Companion.AnswerOption> = emptyList(),
        val fingerprint: String? = null,
        val isResult: Boolean = false,
    )

    /**
     * One push turned into a [Notice]; null for any other kind, and for one with
     * no chat id — a tap that could open nothing. The daemon's own sentence is
     * kept when it sent one; it knows the chat and the directory.
     */
    fun fromPush(kind: String, title: String, text: String, subject: String?): Notice? {
        if (kind != KIND) return null
        val id = subject?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        return Notice(
            key = keyFor(id),
            title = title.ifBlank { "A chat needs you" },
            text = text.ifBlank { "Claude asked to start a new session. Open the chat to read the request." },
            chatId = id,
        )
    }

    fun postArgs(n: Notice): PostArgs = PostArgs(title = n.title, text = n.text, key = n.key, openChat = n.chatId)

    /** Posted unless that chat is on screen in front of the reader. */
    fun shows(n: Notice, onScreen: (String) -> Boolean = { Foreground.showsChat(it) }): Boolean = !onScreen(n.chatId)

    /** The slot a push takes DOWN: the chat's "needs you", once that chat has finished. */
    fun withdraws(kind: String, subject: String?): String? =
        if (kind == KIND_FINISHED) subject?.trim()?.takeIf { it.isNotEmpty() }?.let(::keyFor) else null
}
