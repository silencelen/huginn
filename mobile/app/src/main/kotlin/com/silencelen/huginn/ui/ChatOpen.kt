package com.silencelen.huginn.ui

import com.silencelen.huginn.data.ChatDetail
import com.silencelen.huginn.data.HuginnClient
import com.silencelen.huginn.data.TranscriptPage
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Opening a chat reads its meta and its history, and ASKS FOR BOTH AT ONCE.
 *
 * The history used to be asked for only once the meta had answered, so every open
 * paid two round trips before the conversation could appear. Neither needs the
 * other to be asked for; the page only has to be SHOWN after the meta, which
 * puts the mode, model and title over it and says whether the chat ever ran (what
 * decides what an empty answer means). So the meta goes out first and the history
 * beside it, and the page waits on this side of the wire for the meta to have
 * landed: what the reader sees, and in what order, is what it always was.
 *
 * A function of its own, and `internal`, because the ORDER is the behaviour and
 * the view model that runs it cannot be built in a JVM test.
 *
 * @param applyMeta the meta, or null when it could not be read. Runs first.
 * @param applyPage the history's outcome. Runs once [applyMeta] has run.
 * @return the history's job, which the caller cancels when the chat goes or
 *   another read replaces it; a cancelled job applies no page.
 */
internal fun CoroutineScope.openChatReads(
    client: HuginnClient,
    id: String,
    applyMeta: (ChatDetail?) -> Unit,
    applyPage: (Result<TranscriptPage>) -> Unit,
): Job {
    val metaShown = CompletableDeferred<Unit>()
    launch {
        try {
            applyMeta(runCatching { client.chat(id) }.getOrNull())
        } finally {
            metaShown.complete(Unit)
        }
    }
    return launch {
        val page = runCatching { client.chatTranscript(id) }
        metaShown.await()
        applyPage(page)
    }
}
