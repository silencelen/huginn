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
import com.silencelen.huginn.ui.Follow
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
        /** The phone's work strip, ABOVE the list (SessionScreen). */
        val top: MutableState<Boolean>,
        /** The phone's composer grown by the keyboard or by typing, below the list. */
        val composerTall: MutableState<Boolean>,
        /** How many EARLIER rows have been loaded above row 0 — the window's start, as the views pass it. */
        val earlier: MutableState<Int>,
        /** What the latch reported at the last frame: the pill's two readings. */
        val follow: () -> Follow.State,
    )

    /**
     * @param growsAtOnce the newest message measures taller a moment after the
     *   conversation opened on it — an image decoding, a block laid out late.
     * @param scrollbar the desktop's own bar over the list, wired to the latch
     *   the way both conversation views wire it.
     * @param phone the phone's wiring instead (ChatScreen, SessionScreen): no
     *   wheel to count and no scrollbar, a strip that can arrive above the list,
     *   and a composer under it that the keyboard pushes up.
     */
    private fun ComposeUiTest.conversation(
        rows: Int = 40,
        growsAtOnce: Boolean = false,
        scrollbar: Boolean = false,
        phone: Boolean = false,
    ): Conversation {
        val bar = mutableStateOf(false)
        val tall = mutableStateOf(false)
        val count = mutableStateOf(rows)
        val top = mutableStateOf(false)
        val composerTall = mutableStateOf(false)
        val earlier = mutableStateOf(0)
        var follow = Follow.State()
        lateinit var list: LazyListState
        setContent {
            list = rememberLazyListState()
            val scrolls = remember { mutableStateOf(0) }
            val thumb = remember { MutableInteractionSource() }
            val loaded = earlier.value
            val n = count.value + loaded
            // `prepends` is the window's start, which only moves when an earlier
            // page lands: the session views pass `windowStart`, this passes the
            // count of rows above row 0, which moves the same way.
            follow = if (phone) {
                // Exactly the phone's call: everything else left at its default.
                FollowNewest(list, itemCount = n, revision = n, key = "chat", prepends = loaded)
            } else {
                FollowNewest(
                    list, itemCount = n, revision = n, key = "chat", scrolls = scrolls,
                    scrollbar = if (scrollbar) thumb else null, prepends = loaded,
                )
            }
            // After the first frames, so it lands on a list that has already
            // been put at its newest — that is what "late" means here.
            if (growsAtOnce) LaunchedEffect(Unit) { delay(300); tall.value = true }
            Column(Modifier.size(420.dp, 600.dp)) {
                if (top.value) Box(Modifier.fillMaxWidth().height(40.dp))
                Box(Modifier.weight(1f).testTag("pane")) {
                    LazyColumn(
                        Modifier.fillMaxSize().testTag("transcript")
                            .let { if (phone) it else it.onScrollInput { scrolls.value++ } },
                        state = list,
                    ) {
                        // Keyed by the row's own identity: an earlier page is the
                        // rows -loaded..-1, which keeps row 0 where it was.
                        items(n, key = { "row${it - loaded}" }) { i ->
                            val h = if (i == n - 1 && tall.value) 400.dp else 56.dp
                            Text("message ${i - loaded}", Modifier.height(h))
                        }
                    }
                    if (scrollbar) PaneScrollbar(list, thumb)
                }
                // The work strip and the suggestion chips, arriving after the jump.
                if (bar.value) Box(Modifier.fillMaxWidth().height(96.dp))
                if (phone) Box(Modifier.fillMaxWidth().height(if (composerTall.value) 260.dp else 56.dp))
            }
        }
        waitForIdle()
        return Conversation(list, bar, tall, count, top, composerTall, earlier) { follow }
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

    // ------------------------------------------------------- the phone's wiring

    /**
     * THE PHONE, wired as ChatScreen and SessionScreen wire it. It kept its own copy
     * of this latch (`AutoScrollToNewest`) until 2026-10-23, and the copy scrolled
     * only when content changed — so the session's work strip arriving above the
     * list, the keyboard opening under it, and the suggestions after a turn each
     * left the newest message below the fold.
     */
    @Test
    fun `on the phone, a strip above, the keyboard and the suggestions all leave it on the newest`() = runComposeUiTest {
        val c = conversation(phone = true)
        assertFalse(c.list.canScrollForward, "the conversation opens at the newest")

        c.top.value = true
        waitForIdle()
        assertFalse(c.list.canScrollForward, "the work strip above the list pushed the newest under the fold")

        c.composerTall.value = true
        waitForIdle()
        assertFalse(c.list.canScrollForward, "the keyboard opening pushed the newest under the fold")

        c.bar.value = true
        waitForIdle()
        assertFalse(c.list.canScrollForward, "the suggestions arriving pushed the newest under the fold")
    }

    /** And the other half on the phone: a finger that went up stays up. */
    @Test
    fun `on the phone, a reader who swiped up stays where they went`() = runComposeUiTest {
        val c = conversation(phone = true)
        onNodeWithTag("transcript").performTouchInput { swipeDown() }
        waitForIdle()
        assertTrue(c.list.canScrollForward, "the swipe took them back into older messages")
        val reading = c.list.firstVisibleItemIndex

        c.top.value = true
        c.composerTall.value = true
        c.bar.value = true
        waitForIdle()
        assertEquals(reading, c.list.firstVisibleItemIndex, "the strips and the keyboard pulled them to the newest")

        c.rows.value = c.rows.value + 1
        waitForIdle()
        assertEquals(reading, c.list.firstVisibleItemIndex, "a new message pulled them to the newest")
    }

    // ------------------------------------------- BUG 6: earlier pages (2026-10-23)

    /**
     * ⚠ THE BUG: "Load earlier messages" grew the list, the growth read as new
     * content, and a reader at the top got a "New messages" pill for pages that
     * landed ABOVE them. The views now pass the window's start as `prepends`;
     * a growth that comes with it is history, and the reader stays on the row
     * they were reading with nothing announced.
     */
    @Test
    fun `an earlier page landing above a reader raises no pill and moves nothing`() = runComposeUiTest {
        val c = conversation()
        onNodeWithTag("transcript").performTouchInput { swipeDown() }
        waitForIdle()
        assertTrue(c.list.canScrollForward, "the swipe took them back into older messages")
        assertTrue(c.follow().away, "and the pill reads Back to bottom")
        val readingKey = c.list.layoutInfo.visibleItemsInfo.first().key

        c.earlier.value = 20 // the page before row 0 lands
        waitForIdle()
        assertFalse(c.follow().unseen, "history above the reader was announced as new messages below them")
        assertEquals(readingKey, c.list.layoutInfo.visibleItemsInfo.first().key, "the row they were reading moved")
        assertTrue(c.list.canScrollForward, "they were pulled to the newest")

        // And a genuinely new row after that is still announced, not jumped to.
        c.rows.value = c.rows.value + 1
        waitForIdle()
        assertTrue(c.follow().unseen, "a real arrival below the fold is still news")
        assertEquals(readingKey, c.list.layoutInfo.visibleItemsInfo.first().key)
    }

    /**
     * The other half: a short transcript that fits the screen is FOLLOWING when
     * the reader asks for history. The page lands above; the list fills the
     * viewport from its end, so the newest stays in view with the nearest
     * history above it — nothing is announced, and the latch stays armed for the
     * next real message, which the reader (still at the tail) should be taken to.
     */
    @Test
    fun `an earlier page on a transcript that fitted keeps the newest in view and the latch armed`() = runComposeUiTest {
        val c = conversation(rows = 4)
        assertFalse(c.list.canScrollForward, "four rows fit; the conversation sits at its newest")
        assertFalse(c.follow().away)

        c.earlier.value = 30
        waitForIdle()
        assertTrue(c.list.canScrollBackward, "the history is above, where it was asked for")
        assertFalse(c.list.canScrollForward, "and the newest is still in view")
        assertFalse(c.follow().unseen, "history above was announced as new messages below")
        assertFalse(c.follow().away)

        c.rows.value = c.rows.value + 1
        waitForIdle()
        assertFalse(c.list.canScrollForward, "a reader still at the tail is taken to the new message")
        assertFalse(c.follow().unseen)
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
