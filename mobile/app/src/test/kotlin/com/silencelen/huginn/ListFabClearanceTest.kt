package com.silencelen.huginn

import androidx.compose.ui.unit.dp
import com.silencelen.huginn.ui.FAB_HEIGHT
import com.silencelen.huginn.ui.FAB_INSET
import com.silencelen.huginn.ui.LIST_FAB_CLEARANCE
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * EVERY LIST THAT SITS UNDER A FAB CLEARS IT.
 *
 * Chats and Sessions each carried a hand-written `bottom = 88.dp` and Rounds
 * carried `bottom = 24.dp`, which is less than the 56dp button placed 16dp above
 * it. The result on the owner's phone was a newest round permanently behind "New
 * round" with no scroll position that would move it — the list ended where the
 * button started. Three copies of a number, one of them wrong, is the shape of
 * that bug, so the number is derived once and the copies are gone.
 *
 * WHY A SOURCE GREP for the second half: there is no compose-ui-test here, and
 * the failure is a measured overlap. What can be asserted is that no list under a
 * FAB is still picking its own bottom padding.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ListFabClearanceTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    /** The three screens that place an ExtendedFloatingActionButton over a list. */
    private val fabScreens = listOf("ChatsScreen.kt", "SessionsScreen.kt", "RoundsScreen.kt")

    @Test
    fun `the clearance is the button plus a gap above and below it`() {
        assertEquals(56.dp, FAB_HEIGHT)
        assertEquals(16.dp, FAB_INSET)
        assertEquals(88.dp, LIST_FAB_CLEARANCE)
        assertTrue(
            "the clearance must exceed the button and its own inset",
            LIST_FAB_CLEARANCE > FAB_HEIGHT + FAB_INSET,
        )
    }

    @Test
    fun `no list under a FAB writes its own bottom padding`() {
        val offenders = mutableListOf<String>()
        for (name in fabScreens) {
            val f = File(mobileRoot(), "app/src/main/kotlin/com/silencelen/huginn/ui/$name")
            assertTrue("$name not found at ${f.absolutePath}", f.isFile)
            val text = f.readText()
            assertTrue("$name read as ${text.length} chars — wrong file", text.length > 2_000)
            assertTrue(
                "$name lost its FAB — this gate is now scanning the wrong files",
                text.contains("ExtendedFloatingActionButton("),
            )
            if (!text.contains("bottom = LIST_FAB_CLEARANCE")) offenders += "$name: no LIST_FAB_CLEARANCE"
            // Inside a PaddingValues only — an ordinary row's `bottom = 12.dp`
            // is padding on a row and has nothing to do with the button.
            Regex("""PaddingValues\([^)]*bottom = (\d+)\.dp""").findAll(text).forEach {
                offenders += "$name: hand-written list bottom = ${it.groupValues[1]}.dp"
            }
        }
        assertTrue(offenders.joinToString("; "), offenders.isEmpty())
    }
}
