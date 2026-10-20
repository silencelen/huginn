package com.silencelen.huginn

import androidx.compose.runtime.saveable.SaverScope
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ A SHARE STILL WAITING FOR ITS DESTINATION SURVIVES A FOLD OR A ROTATE.
 *
 * The "Share photo to" sheet was driven by a plain `remember`, the target that
 * opened it was a field of the activity being replaced, and readTarget had
 * already stripped the share out of the intent. Unfolding the phone to see more
 * destinations (the owner's device is a Fold) dropped the share with no word,
 * and it had to be shared again from the other app.
 *
 * A photo's `Uri` cannot be built on this JVM (android.jar is stubs), so the
 * round trip is asserted with the words; the photo rides the same list.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class ShareRebuildTest {

    private val anything = SaverScope { true }

    @Test
    fun `a shared paragraph comes back from the rebuild as the same share`() {
        val share = OpenTarget(session = null, chat = null, seq = 3, shareText = "Release notes\nhttps://example.net/notes")
        val saved = with(PendingShareSaver) { anything.save(share) }
        assertNotNull("nothing was kept across the rebuild", saved)
        val back = PendingShareSaver.restore(saved!!)
        assertEquals("Release notes\nhttps://example.net/notes", back?.shareText)
        assertNull(back?.shareImage)
    }

    @Test
    fun `a restored share is only a share, never a place to go`() {
        val share = OpenTarget(session = null, chat = null, seq = 9, shareText = "x")
        val back = PendingShareSaver.restore(with(PendingShareSaver) { anything.save(share) }!!)
        assertNull(back?.session)
        assertNull(back?.chat)
        assertNull(back?.project)
        assertFalse(back?.newChat ?: true)
    }

    @Test
    fun `no share waiting keeps nothing`() {
        assertNull(with(PendingShareSaver) { anything.save(null) })
    }

    // ------------------------------------------------------------- the wiring

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun mainActivity(): String {
        val rel = "app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt"
        val f = File(rel).takeIf { it.isAbsolute } ?: File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > 2_000)
        return text
    }

    @Test
    fun `the share sheet's state is kept across a rebuild`() {
        val decl = mainActivity().lines().firstOrNull { "var pendingShare by" in it }
        assertNotNull("pendingShare is gone from the shell", decl)
        assertTrue(
            "the share sheet is held in a plain remember, which a fold or a rotate drops",
            "rememberSaveable(stateSaver = PendingShareSaver)" in decl!!,
        )
    }
}
