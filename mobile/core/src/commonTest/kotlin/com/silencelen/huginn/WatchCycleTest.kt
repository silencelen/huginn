package com.silencelen.huginn

import com.silencelen.huginn.data.SessionProject
import com.silencelen.huginn.notify.WatchCycle
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.Test

/**
 * The rule that decides whether a chat finished while nobody was looking.
 *
 * Worth its own tests because the background check runs every ten minutes, so "a
 * chat that started and finished between two observations" is the normal case rather
 * than an edge case — and the obvious implementation, diffing the set of running
 * chats, silently reports nothing at all for it.
 */
class WatchCycleTest {

    @Test
    fun `a chat seen running and then not has finished`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = mapOf("c1" to 0L),
            runsNow = mapOf("c1" to 0L),
            previouslyRunning = setOf("c1"),
            running = emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `a chat that ran entirely between two looks still counts as finished`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = mapOf("c1" to 3L),
            runsNow = mapOf("c1" to 4L),
            // Never observed running: the set diff alone would report nothing.
            previouslyRunning = emptySet(),
            running = emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `several runs inside one gap report the chat once, not once per run`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 1L), mapOf("c1" to 5L), emptySet(), emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `a chat that finished and immediately started again is reported`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 1L), mapOf("c1" to 2L),
            previouslyRunning = setOf("c1"),
            running = setOf("c1"),          // still running, so no edge to see
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `a chat still running with no completed run is not reported`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 2L), mapOf("c1" to 2L), setOf("c1"), setOf("c1"),
        )
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `nothing happening reports nothing`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 7L), mapOf("c1" to 7L), emptySet(), emptySet(),
        )
        assertTrue(finished.isEmpty())
    }

    /**
     * The trap this guards: a chat with no recorded baseline has a counter describing
     * its whole history. Treating that as news would make the first look after an
     * install announce every chat ever run.
     */
    @Test
    fun `a chat never seen before is not announced on its history`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = emptyMap(),
            runsNow = mapOf("old-chat" to 42L),
            previouslyRunning = emptySet(),
            running = emptySet(),
        )
        assertTrue(finished.isEmpty())
    }

    @Test
    fun `a deleted chat that was running is still reported as finished`() {
        val finished = WatchCycle.finishedSince(
            runsBefore = mapOf("c1" to 1L),
            runsNow = emptyMap(),               // gone from the snapshot entirely
            previouslyRunning = setOf("c1"),
            running = emptySet(),
        )
        assertEquals(setOf("c1"), finished)
    }

    @Test
    fun `two chats finishing at once are both reported`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 0L, "c2" to 0L),
            mapOf("c1" to 1L, "c2" to 0L),
            previouslyRunning = setOf("c2"),
            running = emptySet(),
        )
        assertEquals(setOf("c1", "c2"), finished)
    }

    @Test
    fun `a counter that somehow went backwards is not a finish`() {
        val finished = WatchCycle.finishedSince(
            mapOf("c1" to 5L), mapOf("c1" to 2L), emptySet(), emptySet(),
        )
        assertTrue(finished.isEmpty())
    }

    // ------------------------------------------------- the needs-you title

    /**
     * A project session's "needs you" is named by its project (owner decision,
     * 2026-11-06), in the daemon's own `session_attention` words, so the watch
     * cycle and the push say the same thing about one question. The tag is the
     * session row's `project`, exactly as `lib/projects.js` writes it.
     */
    @Test
    fun `a project session needs-you is named by its project and role`() {
        val member = SessionProject(id = "p1", name = "Vault rewrite", slug = "vault", role = "guide", lead = false)
        assertEquals("Vault rewrite · guide needs you", WatchCycle.needsYouTitle("vault-guide", member))
        // The lead is `lead`, said by the tag's flag rather than by its role word.
        val lead = SessionProject(id = "p1", name = "Vault rewrite", slug = "vault", role = "lead", lead = true)
        assertEquals("Vault rewrite · lead needs you", WatchCycle.needsYouTitle("vault-lead", lead))
        assertEquals("Vault rewrite · lead needs you", WatchCycle.needsYouTitle("vault-lead", lead.copy(role = "")))
        // Every other session keeps its own name.
        assertEquals("build needs you", WatchCycle.needsYouTitle("build", null))
    }

    @Test
    fun `a half-named project tag falls back to the session's own name`() {
        val member = SessionProject(id = "p1", name = "Vault rewrite", slug = "vault", role = "guide")
        assertEquals("vault-guide needs you", WatchCycle.needsYouTitle("vault-guide", member.copy(name = " ")))
        assertEquals("vault-guide needs you", WatchCycle.needsYouTitle("vault-guide", member.copy(role = "")))
        // kotlin.test: (expected, actual, message).
        assertEquals(
            "Vault rewrite · guide needs you",
            WatchCycle.needsYouTitle("vault-guide", member.copy(name = " Vault rewrite ", role = " guide ")),
            "trimmed, not doubled",
        )
    }
}
