package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's conversations follow their newest message with the SHARED latch.
 *
 * ⚠ THE BUG (owner, 2026-10-23, found on the desktop): opening a conversation
 * jumped to the newest message, then the status strip and the suggestion bar
 * loaded in and left it short of the bottom. The fix went into the shared latch
 * (`FollowNewest` / `Follow.relaid` in `:ui`) — and the phone did not get it,
 * because it followed with its own copy, `AutoScrollToNewest` in `ui/Common.kt`,
 * which scrolled only when content changed. Its work strip, its suggestions and
 * its keyboard all take height from under the list the same way.
 *
 * The latch is tested where it lives: the rule in `:ui`'s `FollowTest`, and the
 * phone's exact wiring against a real LazyColumn in `:app-desktop`'s
 * `FollowNewestPinTest` (this module renders no Compose on the JVM). What THIS
 * test holds is the part that let the phone miss the fix: that both of its
 * conversation screens use the shared latch, land the "New messages" pill with
 * the same scroll, and that no copy of either is left in the app.
 *
 * NOTE JUnit's argument order: (message, condition).
 */
class PhoneFollowTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun screen(name: String): String =
        File(root(), "app/src/main/kotlin/com/silencelen/huginn/ui/$name").readText()

    @Test
    fun `both conversation screens follow with the shared latch`() {
        for (name in listOf("ChatScreen.kt", "SessionScreen.kt")) {
            val src = screen(name)
            assertTrue("$name does not follow with the shared FollowNewest", "FollowNewest(" in src)
            assertFalse("$name still follows with the phone's own copy", "AutoScrollToNewest(" in src)
            assertTrue("$name's pill does not land with the shared scrollToNewest", "scrollToNewest(" in src)
            assertFalse("$name's pill lands with the phone's own scroll", "jumpToTail(" in src)
        }
    }

    @Test
    fun `the app keeps no copy of the latch`() {
        val copies = File(root(), "app/src/main/kotlin").walk()
            .filter { it.isFile && it.extension == "kt" }
            .filter { f -> f.readText().let { "fun AutoScrollToNewest(" in it || "fun LazyListState.jumpToTail(" in it } }
            .map { it.name }
            .toList()
        assertTrue("a private copy of the follow latch drifts from the shared one: $copies", copies.isEmpty())
    }
}
