package com.silencelen.huginn

import com.silencelen.huginn.data.ProjectDashboardMember
import com.silencelen.huginn.data.ProjectList
import com.silencelen.huginn.data.ProjectPendingChange
import com.silencelen.huginn.ui.PendingChangeRules
import com.silencelen.huginn.ui.ProjectRules
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `pendingChange` on a project row (appd 3.17), and what the card says about it.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class PendingChangeTest {
    private val json = Json { ignoreUnknownKeys = true; isLenient = true }

    private val rowWire = """
        {"projects":[{"id":"p1","name":"LoRa","slug":"lora","kind":"hardware","status":"active","cwd":"/srv/lora",
          "memberCount":3,"alive":3,"busy":1,"waiting":0,"manifestRev":2,"spawnedRev":2,"rev":7,
          "pendingChange":{"rev":3,"token":"9f2c4e1a7b3d5f60","summary":"Add a tester, drop the docs role",
            "added":[{"role":"tester","firstPrompt":"Write the bench tests.\nStart with the radio.","cwd":null,"model":"sonnet","effort":"high","mode":"act"}],
            "removed":[{"role":"docs"}],
            "changed":[{"role":"firmware","firstPrompt":"Switch to the power budget."}],
            "problem":null,"receivedAt":1730000000}}],"max":8}
    """.trimIndent()

    private fun change(): ProjectPendingChange = json.decodeFromString<ProjectList>(rowWire).projects[0].pendingChange!!

    @Test
    fun `a row decodes its pending change whole`() {
        val c = assertNotNull(json.decodeFromString<ProjectList>(rowWire).projects[0].pendingChange)
        assertEquals(3, c.rev)
        assertEquals("9f2c4e1a7b3d5f60", c.token, "the content token Approve hands back")
        assertEquals("Add a tester, drop the docs role", c.summary)
        assertEquals("tester", c.added.single().role)
        assertEquals("sonnet", c.added.single().model)
        assertEquals("docs", c.removed.single().role)
        assertEquals("firmware", c.changed.single().role)
        assertNull(c.problem)
        assertEquals(1730000000L, c.receivedAt)
    }

    @Test
    fun `a row without the field, or with null, has no change`() {
        val none = json.decodeFromString<ProjectList>("""{"projects":[{"id":"p1","name":"x","slug":"x"}]}""").projects[0]
        assertNull(none.pendingChange, "an older daemon never sends it")
        val explicit = json.decodeFromString<ProjectList>("""{"projects":[{"id":"p1","pendingChange":null}]}""").projects[0]
        assertNull(explicit.pendingChange)
    }

    @Test
    fun `the card's lines name every role and the first line of an added prompt`() {
        val lines = PendingChangeRules.lines(change())
        assertEquals(
            listOf(
                "Added",
                "  tester  —  Write the bench tests.  (sonnet · high · act)",
                "Removed (asked to wrap up)",
                "  docs",
                "Re-roled",
                "  firmware",
            ),
            lines,
        )
        assertEquals("1 added · 1 removed · 1 re-roled", PendingChangeRules.countWords(change()))
        assertEquals("Add a tester, drop the docs role", PendingChangeRules.headline(change()))
    }

    @Test
    fun `an empty section is left out and a missing summary gets the stock headline`() {
        val c = ProjectPendingChange(rev = 1, removed = listOf(com.silencelen.huginn.data.PendingRemoved("docs")))
        assertEquals(listOf("Removed (asked to wrap up)", "  docs"), PendingChangeRules.lines(c))
        assertEquals("1 removed", PendingChangeRules.countWords(c))
        assertEquals("The lead proposes a change to the roster.", PendingChangeRules.headline(c))
        assertEquals("nothing changes", PendingChangeRules.countWords(ProjectPendingChange()))
    }

    @Test
    fun `the first line of a prompt is the first non-empty one, clipped`() {
        assertEquals("Second.", PendingChangeRules.firstLine("\n\n  Second.\nThird."))
        assertEquals("", PendingChangeRules.firstLine(null))
        assertEquals("aaaa…", PendingChangeRules.firstLine("aaaaaaaa", max = 5))
    }

    @Test
    fun `a member asked to wrap up reads as winding down while it still runs, and ended once gone`() {
        val winding = ProjectDashboardMember(role = "docs", name = "lora-docs", present = true, alive = true, endedAt = 10)
        assertTrue(PendingChangeRules.windingDown(winding))
        assertEquals("winding down", ProjectRules.memberWords(winding))
        val gone = winding.copy(present = false, alive = false)
        assertFalse(PendingChangeRules.windingDown(gone))
        assertEquals("ended", ProjectRules.memberWords(gone))
        assertFalse(PendingChangeRules.windingDown(winding.copy(endedAt = null)))
    }
}
