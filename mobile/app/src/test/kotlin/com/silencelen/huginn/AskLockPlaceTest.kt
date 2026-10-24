package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ THE ASK SHEET ASKS FOR THE UNLOCK EVERY TIME IT COMES BACK, NOT ONCE.
 *
 * It weighed the lock in onCreate and kept the answer in a `remember`. But
 * tapping the ask bar brings a sheet left open behind Home forward without
 * creating it again, so onCreate never ran a second time: unlocked once, it came
 * back unlocked however long it had been away, while the app's front door had
 * locked a minute after it was left — free text to Claude, Act mode included,
 * from whoever held the phone. The decision itself is AskLockTest's; this holds
 * the activity to making it in the right places.
 *
 * There is no compose-ui-test in this module and no instrumentation in this
 * build, so the wiring is held against the source, as AppLockPlaceTest holds
 * MainActivity's.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class AskLockPlaceTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun askActivity(): String {
        val rel = "app/src/main/kotlin/com/silencelen/huginn/widget/AskActivity.kt"
        val f = File(rel).takeIf { it.isAbsolute } ?: File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > 2_000)
        return text
    }

    private fun slice(text: String, from: String, to: String, max: Int): String {
        val s = text.substringAfter(from, "").substringBefore(to, "")
        assertTrue("'$from' read as ${s.length} chars — wrong slice", s.length in 1..max)
        return s
    }

    @Test
    fun `the lock is decided every time the sheet comes to the foreground`() {
        val text = askActivity()
        assertTrue("the sheet has no onStart: the lock is weighed once, at creation", "override fun onStart()" in text)
        val onStart = slice(text, "override fun onStart()", "override fun onStop()", 1_500)
        assertTrue("onStart does not ask the rule", "askSheetLocks(" in onStart)
        assertTrue("onStart does not weigh the grace", "AppLock.lastAwayAt" in onStart)
        assertTrue("a lock decided in onStart does not reach the screen", "locked.value = true" in onStart)
        assertTrue("nor the rest of the app", "AppLock.lockedNow = true" in onStart)
    }

    @Test
    fun `the grace is stamped when the sheet leaves, and only while it is open`() {
        val text = askActivity()
        assertTrue("the sheet has no onStop: leaving it never starts the grace", "override fun onStop()" in text)
        val onStop = slice(text, "override fun onStop()", "\n    }\n", 600)
        assertTrue(
            "a lock left up in the background must not refresh its own grace",
            "if (!locked.value) AppLock.lastAwayAt = SystemClock.elapsedRealtime()" in onStop,
        )
    }

    @Test
    fun `the lock lives on the activity, not in the composition`() {
        val text = askActivity()
        assertTrue("the lock is not the activity's", "private val locked = mutableStateOf(" in text)
        val content = slice(text, "setContent {", "override fun onStart()", 5_000)
        assertTrue("the composition does not read the activity's lock", "if (locked.value)" in content)
        assertFalse("the lock is decided once and kept in composition again", "by remember" in content)
        assertFalse("the lock is weighed at creation again", "startLocked" in text)
    }

    @Test
    fun `send is refused while locked, before the chat is created and before the question is queued`() {
        val submit = slice(askActivity(), "private suspend fun submit(", "\n    }\n", 2_000)
        val first = submit.indexOf("requireUnlocked()")
        assertTrue("submit does not check the lock", first >= 0)
        assertTrue("the chat is created before the lock is checked", first < submit.indexOf("createChat("))
        val queue = slice(submit, "queue = {", "discard = {", 300)
        val check = queue.indexOf("requireUnlocked()")
        assertTrue(
            "the question is queued without a check right before it",
            check >= 0 && check < queue.indexOf("queueMessage("),
        )
    }
}
