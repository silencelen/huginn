package com.silencelen.huginn.desktop.ui.common

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import com.silencelen.huginn.data.QuickActions
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.ui.QuickActionRules
import com.silencelen.huginn.ui.SelectionAction
import kotlinx.coroutines.launch

/**
 * The four selection verbs, bound to a target's composer.
 *
 * Built here rather than in each view because the binding is the part that is
 * easy to get subtly wrong and impossible to see: which draft key the text lands
 * in, and whose scope the chat-creating call runs on.
 *
 * ⚠ THE STORE'S SCOPE, not the view's. "Ask in new chat" NAVIGATES, which tears
 * down the composition that launched it — a `rememberCoroutineScope` here would
 * be cancelled at its first suspension point and the new chat would be created,
 * or not, depending on timing, with the staging never running. Same lesson the
 * chat-deleted effect in `ChatView` carries.
 */
@Composable
fun rememberSelectionVerbs(
    store: AppStore?,
    draftKey: String,
    mode: String?,
    actions: QuickActions?,
): SelectionVerbs = remember(store, draftKey, mode, actions) {
    fun stage(action: SelectionAction, selection: String) {
        val text = QuickActionRules.textFor(action, actions, selection)
        if (text.isNotBlank()) store?.appendToDraft(draftKey, text)
    }
    SelectionVerbs(
        explain = { stage(SelectionAction.EXPLAIN, it) },
        execute = { stage(SelectionAction.EXECUTE, it) },
        quote = { stage(SelectionAction.QUOTE, it) },
        askInNewChat = { selection ->
            val text = QuickActionRules.textFor(SelectionAction.ASK_IN_NEW_CHAT, actions, selection)
            if (store != null && text.isNotBlank()) {
                store.scope.launch { store.askInNewChat(text, mode, draftKey) }
            }
        },
    )
}
