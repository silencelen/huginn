package com.silencelen.huginn

import com.silencelen.huginn.ui.CardRoom
import com.silencelen.huginn.ui.FieldInView
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WITH THE KEYBOARD UP, EVERYTHING IN A CONVERSATION IS LAID OUT ABOVE IT.
 *
 * Driving the Fold's cover screen (1080x2520, Samsung keyboard) on 2026-11-03:
 * a tall AskUserQuestion card, "Other…", tap the field — and the field, its
 * Answer button and the composer were all laid out UNDER the keyboard, so
 * typed text landed in a field nobody could see and the answer read as lost.
 * A start_session card left the composer a sliver; in a chat, "Decline, and
 * tell Claude" sat at y 1643-1696 with the keyboard from ~1540, and a tap on it
 * typed a letter. The project page put Re-role's field under the keyboard too.
 *
 * Two causes. The keyboard's inset was paid by the composer ROW, the last
 * thing the column measures, while the card above was capped at 0.7 of the
 * column's FULL height and measured first — together they overflowed the
 * column, and its bottom went under the keyboard. Now the column pays the
 * keyboard (`keyboardPadding`) and the bottom is measured bottom-up
 * (`ComposerStack`: composer, bar, then the card inside [CardRoom.cap]).
 *
 * WHY HALF OF THIS IS A SOURCE GREP: there is no compose-ui-test and no
 * emulator on this host, the same reason `SystemInsetsTest` and
 * `ListFabClearanceTest` grep. The arithmetic and the measuring order are pure
 * and asserted directly; the greps hold the screens to using them.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class KeyboardLayoutTest {

    // ------------------------------------------------------------ the rules

    /** Cover-screen numbers at 2.625 px/dp: 120 dp of floor, 56 dp of room below a field. */
    private val floorPx = 315
    private val belowPx = 147f

    @Test
    fun `the composer is measured first and the card can never squeeze it`() {
        for (room in 0..2600 step 37) {
            for (composerWants in listOf(0, 130, 258, 450, 900)) {
                for (barWants in listOf(0, 126)) {
                    for (cardWants in listOf(0, 300, 1100, 4000)) {
                        val order = mutableListOf<String>()
                        val h = CardRoom.bottomUp(
                            room = room,
                            floor = floorPx,
                            composer = { max -> order += "composer"; minOf(composerWants, max) },
                            bar = { max -> order += "bar"; minOf(barWants, max) },
                            card = { max -> order += "card"; minOf(cardWants, max) },
                        )
                        assertEquals("the measuring order", listOf("composer", "bar", "card"), order)
                        assertEquals(
                            "the composer was squeezed (room $room, it wanted $composerWants, card $cardWants)",
                            minOf(composerWants, room), h.composer,
                        )
                        assertTrue("the stack overflowed its room: ${h.total} > $room", h.total <= room)
                    }
                }
            }
        }
    }

    @Test
    fun `the card takes 70 percent of the room left above the composer`() {
        // Keyboard UP on the cover screen: the column ends at the keyboard
        // (1003 px), less the work strip (90) is 913; the composer is one line
        // (130 px, the keyboard pays the bar). 0.7 of the 783 left: 548 px, and
        // the transcript keeps 235 px. The old rule never saw the keyboard: the
        // column stayed 1898 px tall, the card took 0.7 of it (~1320 px) first,
        // and the composer row had to find 130 px plus the keyboard's 985 in the
        // ~570 left — so its bottom, and the card's, went under the keyboard.
        assertEquals(548, CardRoom.cap(room = 913, below = 130, floor = floorPx))
        // Keyboard DOWN: 1898 px of room, a composer of 258 with the nav bar.
        assertEquals(1148, CardRoom.cap(room = 1898, below = 258, floor = floorPx))
        // A six-line draft with an attachment row is the composer's business:
        // the card's share shrinks, the composer's height does not.
        val tall = 450
        assertTrue(CardRoom.cap(room = 913, below = tall, floor = floorPx) + tall <= 913)
        assertEquals("the owner's share stands", 0.7f, CardRoom.SHARE, 0f)
    }

    @Test
    fun `under the floor the card takes all of the free room, and none is none`() {
        // 300 px free: 70% (210) is under the 315 px floor, so the card gets all
        // 300 — a 30% transcript sliver of 90 px shows nothing anyway.
        assertEquals(300, CardRoom.cap(room = 430, below = 130, floor = floorPx))
        // Exactly where 70% passes the floor, the share takes over again.
        assertEquals(315, CardRoom.cap(room = 130 + 450, below = 130, floor = floorPx))
        assertEquals(350, CardRoom.cap(room = 130 + 500, below = 130, floor = floorPx))
        // No room, or a composer taller than the room: no card at all.
        assertEquals(0, CardRoom.cap(room = 130, below = 130, floor = floorPx))
        assertEquals(0, CardRoom.cap(room = 100, below = 130, floor = floorPx))
        // An unbounded column caps nothing (and never overflows a Constraints).
        assertEquals(Int.MAX_VALUE, CardRoom.cap(room = Int.MAX_VALUE, below = 130, floor = floorPx))
    }

    @Test
    fun `a focused field near the bottom comes into view with its button under it`() {
        // A 548 px card viewport; the "Tell Claude why" field (56 dp = 147 px)
        // at 420..567 is clipped by 19 px. The platform scrolls 19 and stops at
        // its edge, leaving "Decline, and tell Claude" below the fold; with the
        // room below asked for, it scrolls 166 and the button is on screen.
        assertEquals(19f, FieldInView.scrollBy(420f, 147f, 548f, below = 0f), 0.01f)
        assertEquals(166f, FieldInView.scrollBy(420f, 147f, 548f, below = belowPx), 0.01f)
        // Visible with its button already: nothing moves.
        assertEquals(0f, FieldInView.scrollBy(100f, 147f, 548f, below = belowPx), 0.01f)
        // Visible, button just under the fold: only the difference.
        assertEquals(46f, FieldInView.scrollBy(300f, 147f, 548f, below = belowPx), 0.01f)
    }

    @Test
    fun `the extra room never pushes the field's own top out, and the rest is the platform's rule`() {
        // A field almost as tall as the viewport: the room shrinks to what fits
        // and the field's top lands at the viewport's top, not above it.
        assertEquals(40f, FieldInView.scrollBy(40f, 500f, 548f, below = belowPx), 0.01f)
        // Above the viewport: back to its top edge.
        assertEquals(-60f, FieldInView.scrollBy(-60f, 147f, 548f, below = belowPx), 0.01f)
        // Taller than the viewport and spanning it: left alone, as the platform does.
        assertEquals(0f, FieldInView.scrollBy(-10f, 900f, 548f, below = belowPx), 0.01f)
        // Taller and below: the nearer edge, as the platform does.
        assertEquals(100f, FieldInView.scrollBy(100f, 900f, 548f, below = belowPx), 0.01f)
        // With no room asked for, a child that fits is the platform's own rule.
        for (offset in listOf(-200f, -1f, 0f, 10f, 401f, 402f, 700f)) {
            val platform = when {
                offset < 0f -> offset
                offset + 147f > 548f -> offset + 147f - 548f
                else -> 0f
            }
            assertEquals("offset $offset", platform, FieldInView.scrollBy(offset, 147f, 548f, below = 0f), 0.01f)
        }
    }

    // -------------------------------------------------------- the screens

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun source(relative: String, atLeast: Int = 5_000): String {
        val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/$relative")
        assertTrue("$relative not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$relative read as ${text.length} chars — wrong file", text.length > atLeast)
        return text
    }

    /** The two screens that end in a composer with a card pinned above it. */
    private val conversations = listOf("ui/SessionScreen.kt", "ui/ChatScreen.kt")

    @Test
    fun `the conversation column pays the keyboard and its composer row only the bar`() {
        for (name in conversations) {
            val text = source(name)
            assertTrue("$name lost its composer — this gate is scanning the wrong file", text.contains("Composer("))
            assertTrue(
                "$name: the column does not pay the keyboard, so its bottom is laid out under it",
                Regex("""Column\(Modifier\.fillMaxSize\(\)\.keyboardPadding\(\)\)""").containsMatchIn(text),
            )
            assertTrue(
                "$name: the composer row must pay the navigation bar alone",
                text.contains("rowModifier = Modifier.systemNavPadding()"),
            )
            // One owner for the keyboard: a row that pays it again under a
            // column that already did is the doubled band Insets.kt warns about.
            assertFalse(
                "$name: something besides the column still pays the keyboard",
                text.contains("imeAndSystemNavPadding()") || text.contains("imePadding()"),
            )
        }
    }

    @Test
    fun `the card is laid out by the stack, never capped from the column before the composer`() {
        for (name in conversations) {
            val text = source(name)
            val stack = text.indexOf("ComposerStack(")
            assertTrue("$name does not lay its bottom out with ComposerStack", stack > 0)
            assertFalse(
                "$name caps the card at a share of the whole column again",
                Regex("""maxHeight\s*\*\s*0\.7f""").containsMatchIn(text),
            )
            val card = text.indexOf("QuestionCard(", stack)
            val composer = text.indexOf("Composer(", stack)
            assertTrue("$name: the card is not inside the stack", card > stack)
            assertTrue("$name: the composer is not inside the stack", composer > card)
            assertTrue(
                "$name: a focused field in the card is not brought into view with its button",
                text.indexOf("RoomBelowFocused {", stack) in (stack + 1) until card,
            )
        }
    }

    @Test
    fun `the project page pays the keyboard and the bar outside its own scroll`() {
        val text = source("ui/ProjectDashboardScreen.kt")
        assertTrue("this gate is scanning the wrong file", text.contains("ProjectDashboardView("))
        // `modifier` is the FIRST link of the view's `.verticalScroll(...)`
        // chain, so the padding passed here sits outside the scroll: the
        // viewport ends at the keyboard and its last row rests above the bar.
        assertTrue(
            "Re-role's and Add member's fields are laid out under the keyboard again",
            text.contains("modifier = Modifier.fillMaxSize().imeAndSystemNavPadding()"),
        )
        assertTrue(
            "a focused field on the project page comes into view without its button",
            text.indexOf("RoomBelowFocused {") in 1 until text.indexOf("ProjectDashboardView("),
        )
    }

    @Test
    fun `the vocabulary says which is which`() {
        val text = source("ui/Insets.kt", atLeast = 2_000)
        assertTrue(
            "keyboardPadding must CONSUME the keyboard's inset, so the row's nav padding adds nothing while it is up",
            text.contains("fun Modifier.keyboardPadding(): Modifier = this.imePadding()"),
        )
        assertTrue(text.contains("fun Modifier.imeAndSystemNavPadding(): Modifier = this.imePadding().systemNavPadding()"))
    }
}
