package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.data.QuickActions

/**
 * What a long-press selection turns into, and which composer it lands in.
 *
 * A PLAIN CLASS, and that is the point. `HuginnViewModel` is an
 * `AndroidViewModel`; this host has no device and no `/dev/kvm`, so nothing that
 * lives inside it can be asserted at all. Every rule that matters here — append
 * never clobbers, "Ask in new chat" stages into the NEW chat's key, a daemon with
 * no templates offers Quote alone — is a rule this class holds and the view model
 * merely delegates to, exactly as the desktop's `AppStore` does.
 *
 * NOTHING HERE SENDS. Every path ends with text in a composer for a person to
 * read and edit. That is what makes a long-press safe to put over a transcript:
 * the worst outcome of a mis-press is text to delete.
 *
 * @param draftOf reads the draft under a key — the view model's map.
 * @param setDraft writes it back, debounced by the view model.
 * @param chatKeyOf a chat id's draft key. Injected so a test does not have to
 *   know the key format, and so the format has one owner.
 */
class SelectionStaging(
    private val draftOf: (String) -> String,
    private val setDraft: (String, String) -> Unit,
    private val chatKeyOf: (String) -> String,
) {

    /**
     * Appends into a composer. NEVER CLOBBERS — the shared rule
     * ([QuickActionRules.appendToDraft]), so the phone and the desktop stage the
     * same text with the same separator.
     */
    fun append(key: String, text: String) {
        if (text.isBlank()) return
        setDraft(key, QuickActionRules.appendToDraft(draftOf(key), text))
    }

    /**
     * Stages one verb's text into [key].
     *
     * Returns the composed text, or null when there was nothing to stage — which
     * is not an error: a selection past [QuickActionRules.SELECTION_MAX], or a
     * verb whose host template is empty, both legitimately produce nothing, and
     * the bar simply closes.
     */
    fun stage(
        action: SelectionAction,
        selection: String,
        actions: QuickActions?,
        key: String,
    ): String? {
        val text = QuickActionRules.textFor(action, actions, selection)
        if (text.isBlank()) return null
        append(key, text)
        return text
    }

    /**
     * "Ask in new chat": makes a chat, stages the composed text in ITS composer,
     * hands the id back to be navigated to.
     *
     * CREATES AND STAGES; NEVER SENDS. Same contract as the desktop's
     * `AppStore.askInNewChat`, down to the failure: a creation that fails puts the
     * text into [fallbackKey] — the composer the reader is actually looking at —
     * rather than dropping it, because they selected it.
     *
     * @param create seamed so the rule this method exists for (which draft the
     *   text lands in) is assertable without a daemon.
     */
    suspend fun askInNewChat(
        selection: String,
        actions: QuickActions?,
        fallbackKey: String,
        create: suspend () -> Chat,
        onOpened: (String) -> Unit,
        onFailure: (Throwable) -> Unit = {},
    ) {
        val text = QuickActionRules.textFor(SelectionAction.ASK_IN_NEW_CHAT, actions, selection)
        if (text.isBlank()) return
        runCatching { create() }
            .onSuccess { made ->
                append(chatKeyOf(made.id), text)
                onOpened(made.id)
            }
            .onFailure { t ->
                append(fallbackKey, text)
                onFailure(t)
            }
    }
}
