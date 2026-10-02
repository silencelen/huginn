package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.isRoot
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Chat
import com.silencelen.huginn.desktop.ui.common.ChatVerbs
import com.silencelen.huginn.desktop.ui.common.Selection
import com.silencelen.huginn.ui.theme.HuginnTheme
import com.silencelen.huginn.ui.theme.MonoStyleDesktop
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The chat list's "+ Ask / + Act / + Local" in the REAL app theme, at the widths
 * the list pane can be and with the multi-select label showing.
 *
 * ⚠ THE BUG (desktop breaker, 2026-10-02): the tier was chosen from the PANE
 * width alone, but the header's left side grows a "12 selected" label while a
 * multi-selection is live. At 320 dp — the splitter's default width — selecting
 * just two chats clipped the third button to "+ L", and twelve to "+". The same
 * squeeze the tiers were added for on 2026-10-01, back again.
 *
 * Measured the way a reader sees it: every "+ …" label that is drawn must be
 * drawn whole (no width overflow), and the Local verb must be reachable in some
 * form — word, icon, or the `+` menu.
 */
@OptIn(ExperimentalTestApi::class)
class NewChatHeaderFitTest {
    private val chats = (1..123).map { Chat(id = "c$it", title = "Chat number $it") }
    private val verbs = ChatVerbs({}, {}, {}, {}, {})

    private class Seen(val text: String, val overflowed: Boolean)

    private fun header(width: Int, selected: Int): Pair<List<Seen>, List<String>> {
        val selection = Selection(chats.take(selected).map { it.id }.toSet())
        val texts = mutableListOf<Seen>()
        val descriptions = mutableListOf<String>()
        runComposeUiTest {
            setContent {
                HuginnTheme(darkTheme = true, monoStyle = MonoStyleDesktop, rootSurface = true) {
                    // The outer box takes the window's constraints; the inner one is
                    // the list pane at exactly the width under test.
                    Box(Modifier.fillMaxSize()) {
                        Box(Modifier.width(width.dp)) {
                            ChatsList(
                                chats = chats, loaded = true, activeId = null, selection = selection,
                                onSelect = {}, onOpen = {}, onNew = {}, onNewLocal = {}, verbs = verbs,
                            )
                        }
                    }
                }
            }
            waitForIdle()
            fun walk(n: SemanticsNode) {
                n.config.getOrNull(SemanticsProperties.Text)?.forEach { t ->
                    val layouts = mutableListOf<TextLayoutResult>()
                    n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(layouts)
                    // Drawn narrower than the text's own one-line width = clipped.
                    // (`didOverflowWidth` cannot say this: with softWrap off the
                    // paragraph is laid out at the constraint, not at the text.)
                    texts += Seen(t.text, layouts.any { it.size.width + 1 < it.multiParagraph.intrinsics.maxIntrinsicWidth })
                }
                n.config.getOrNull(SemanticsProperties.ContentDescription)?.let { descriptions += it }
                n.children.forEach(::walk)
            }
            onAllNodes(isRoot(), useUnmergedTree = true).fetchSemanticsNodes().forEach(::walk)
        }
        return texts to descriptions
    }

    @Test
    fun `no new-chat verb is ever clipped, with or without a selection`() {
        val bad = mutableListOf<String>()
        for (selected in listOf(0, 2, 12, 123)) for (w in listOf(220, 250, 280, 300, 319, 320, 330, 340, 360, 380, 420)) {
            val (texts, descriptions) = header(w, selected)
            texts.filter { it.text.startsWith("+ ") && it.overflowed }
                .forEach { bad += "w=$w sel=$selected: '${it.text}' is clipped" }
            val localReachable = texts.any { it.text == "+ Local" && !it.overflowed } ||
                "New chat on the local AI" in descriptions || "New chat" in descriptions
            if (!localReachable) bad += "w=$w sel=$selected: no way to reach Local (texts=${texts.map { it.text }} desc=$descriptions)"
        }
        assertTrue(bad.isEmpty(), bad.joinToString("\n"))
    }

    @Test
    fun `the words still show where they fit — the fix is not 'always icons'`() {
        for ((w, selected) in listOf(320 to 0, 420 to 0, 420 to 12)) {
            val (texts, _) = header(w, selected)
            assertTrue(texts.any { it.text == "+ Local" && !it.overflowed }, "w=$w sel=$selected: ${texts.map { it.text }}")
        }
    }
}
