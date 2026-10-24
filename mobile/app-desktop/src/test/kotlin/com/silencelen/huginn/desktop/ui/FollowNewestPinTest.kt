package com.silencelen.huginn.desktop.ui

import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.Text
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ComposeUiTest
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performMouseInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.runComposeUiTest
import androidx.compose.ui.test.swipeDown
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.desktop.ui.common.PaneScrollbar
import com.silencelen.huginn.ui.FollowNewest
import com.silencelen.huginn.ui.onScrollInput
import kotlinx.coroutines.delay
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The follow latch against a real `LazyColumn`, with a strip that arrives under
 * it after the conversation has opened — the shape of both conversation views
 * (`ChatView`, `SessionView`): the list in a weighted box, the work strip and the
 * suggestion chips below it, the composer last.
 *
 * ⚠ THE BUG (owner, 2026-10-23): "when a user opens a conversation they chat
 * correctly starts from the bottom (newest at bottom), but then the status and
 * chat suggestion bars load in causing the user to have to scroll a bit down to
 * lock it in to the bottom." The pure rule is `FollowTest` in `:ui`; this is the
 * wiring, measured the way a reader sees it: can the list still scroll further
 * toward the newest?
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
@OptIn(ExperimentalTestApi::class)
class FollowNewestPinTest {

    private class Conversation(
        val list: LazyListState,
        val bar: MutableState<Boolean>,
        val tall: MutableState<Boolean>,
        val rows: MutableState<Int>,
    )

    /**
     * @param growsAtOnce the newest message measures taller a moment after the
     *   conversation opened on it — an image decoding, a block laid out late.
     * @param scrollbar the desktop's own bar over the list, wired to the latch
     *   the way both conversation views wire it.
     */
    private fun ComposeUiTest.conversation(
        rows: Int = 40,
        growsAtOnce: Boolean = false,
        scrollbar: Boolean = false,
    ): Conversation {
        val bar = mutableStateOf(false)
        val tall = mutableStateOf(false)
        val count = mutableStateOf(rows)
        lateinit var list: LazyListState
        setContent {
            list = rememberLazyListState()
            val scrolls = remember { mutableStateOf(0) }
            val thumb = remember { MutableInteractionSource() }
            val n = count.value
            FollowNewest(
                list, itemCount = n, revision = n, key = "chat", scrolls = scrolls,
                scrollbar = if (scrollbar) thumb else null,
            )
            // After the first frames, so it lands on a list that has already
            // been put at its newest — that is what "late" means here.
            if (growsAtOnce) LaunchedEffect(Unit) { delay(300); tall.value = true }
            Column(Modifier.size(420.dp, 600.dp)) {
                Box(Modifier.weight(1f).testTag("pane")) {
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("transcript").onScrollInput { scrolls.value++ },
                        state = list,
                    ) {
                        items(n, key = { "row$it" }) { i ->
                            val h = if (i == n - 1 && tall.value) 400.dp else 56.dp
                            Text("message $i", Modifier.height(h))
                        }
                    }
                    if (scrollbar) PaneScrollbar(list, thumb)
                }
                // The work strip and the suggestion chips, arriving after the jump.
                if (bar.value) Box(Modifier.fillMaxWidth().height(96.dp))
            }
        }
        waitForIdle()
        return Conversation(list, bar, tall, count)
    }

    @Test
    fun `a strip arriving under an opened conversation leaves it on the newest`() = runComposeUiTest {
        val c = conversation()
        assertFalse(c.list.canScrollForward, "the conversation opens at the newest")

        c.bar.value = true
        waitForIdle()
        assertFalse(c.list.canScrollForward, "the strip took the newest rows out of view")

        // And going away again (the turn started, the chips gave way to typing).
        c.bar.value = false
        waitForIdle()
        assertFalse(c.list.canScrollForward, "the strip leaving moved the newest away")
    }

    @Test
    fun `a reader who scrolled up is not pulled back down when a strip arrives`() = runComposeUiTest {
        val c = conversation()
        onNodeWithTag("transcript").performTouchInput { swipeDown() }
        waitForIdle()
        assertTrue(c.list.canScrollForward, "the swipe took them back into older messages")
        val reading = c.list.firstVisibleItemIndex

        c.bar.value = true
        waitForIdle()
        assertTrue(c.list.canScrollForward, "the strip yanked a reader who had scrolled up back to the newest")
        assertEquals(reading, c.list.firstVisibleItemIndex, "the message they were reading moved")
    }

    @Test
    fun `the newest message laying out late is followed to its end`() = runComposeUiTest {
        val c = conversation(growsAtOnce = true)
        assertTrue(c.tall.value, "the late layout happened")
        assertFalse(c.list.canScrollForward, "the newest message grew under the fold and was left there")
    }

    /**
     * The scrollbar's thumb moves the list without one event the list sees. A
     * reader who dragged it up to read was still "following", so the next
     * message, or a strip arriving, put them back at the bottom mid-sentence.
     */
    @Test
    fun `a reader who dragged the scrollbar up is not pulled back down`() = runComposeUiTest {
        val c = conversation(scrollbar = true)
        onNodeWithTag("pane").performMouseInput {
            // The thumb sits at the bottom of the bar, on the pane's right edge.
            val x = width - 4.dp.toPx()
            moveTo(Offset(x, height - 12.dp.toPx()))
            press()
            moveTo(Offset(x, height / 3f))
            release()
        }
        waitForIdle()
        assertTrue(c.list.canScrollForward, "the thumb took them back into older messages")
        val reading = c.list.firstVisibleItemIndex

        c.bar.value = true
        waitForIdle()
        assertEquals(reading, c.list.firstVisibleItemIndex, "a strip arriving pulled a scrollbar reader to the newest")

        c.rows.value = c.rows.value + 1
        waitForIdle()
        assertEquals(reading, c.list.firstVisibleItemIndex, "a new message pulled a scrollbar reader to the newest")
    }

    @Test
    fun `a card opened long after it arrived is not followed to its bottom`() = runComposeUiTest {
        val c = conversation()
        mainClock.advanceTimeBy(5_000)
        waitForIdle()
        val top = c.list.firstVisibleItemIndex to c.list.firstVisibleItemScrollOffset

        c.tall.value = true // the reader opened the last card
        waitForIdle()
        assertEquals(top, c.list.firstVisibleItemIndex to c.list.firstVisibleItemScrollOffset, "the page moved under the card they opened")
    }
}
