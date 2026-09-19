package com.silencelen.huginn.ui

/**
 * WHAT AN EMPTY CHAT SCREEN IS LOOKING AT — and it is two completely different
 * things wearing one face.
 *
 * The walk opened a 53-day-old chat whose list row still showed its last answer
 * ("VM 107 (silencelenH) — ~30% CPU of 4 cores…") and got the brand-new-chat
 * placeholder: "Ask mode · Reasoning and memory, no tools." Nothing was broken;
 * Claude Code had swept its own transcript, so the daemon answered 409
 * "transcript not found for this chat" — the same status it uses for
 * "chat has not run yet" — and the client read both as "nothing here yet".
 *
 * A chat that HAS run and whose messages are gone must say so. The alternative
 * is the screen telling a reader their conversation never happened while the
 * list two taps away still quotes it.
 *
 * ⚠ THE FLAG IS NOT "started". A first send sets `started` before a single token
 * arrives, so deciding on it alone would tell someone sending their opening
 * message that its history is missing. GONE needs BOTH: the chat ran, AND the
 * daemon refused the transcript.
 */
data class ChatEmptyCopy(val title: String, val body: String)

/**
 * Whether an empty chat screen is an absence or a loss.
 *
 * @param hasRun the daemon's own pin condition — turns, or a Claude session id.
 * @param transcriptRefused the transcript route answered 409: it has nothing to
 *   give. A chat still loading, or one that came back with a real page, is
 *   neither of these.
 */
fun chatMessagesGone(hasRun: Boolean, transcriptRefused: Boolean): Boolean =
    hasRun && transcriptRefused

/** The two lines an empty chat draws. [mode] is the daemon's, "ask" or "act". */
fun chatEmptyCopy(messagesGone: Boolean, mode: String): ChatEmptyCopy = when {
    messagesGone -> ChatEmptyCopy(
        "This chat's messages are no longer on the host",
        // Names the cause, because it is not a fault and not a thing to retry:
        // Claude Code deletes its own transcripts on its own schedule. And it
        // says where the one surviving line is, since the reader has just come
        // from a row that showed it.
        "Claude Code has swept its transcript. The list keeps its last line; the conversation itself is gone.",
    )
    mode == "act" -> ChatEmptyCopy("Act mode", "Runs on the host with tools: files, commands, the web.")
    else -> ChatEmptyCopy("Ask mode", "Reasoning and memory, no tools.")
}
