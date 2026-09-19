package com.silencelen.huginn.desktop.ui

import com.silencelen.huginn.data.ProjectLive
import com.silencelen.huginn.data.ProjectRow
import com.silencelen.huginn.desktop.ui.common.ProjectVerbs
import com.silencelen.huginn.desktop.ui.common.labelsOf
import com.silencelen.huginn.desktop.ui.common.projectMenu
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * What the two project menus offer, and the one row that must never be in either
 * of them.
 *
 * ⚠⚠ A MEMBER CANNOT BE RENAMED AND THE DAEMON WILL SAY SO WITH A 409. Its tmux
 * name is `<slug>-<role>` and its peer name is `<slug>/<role>`; both are how the
 * daemon, the lead and every sibling session address it, and the rename route
 * refuses a project member outright. An item that can only ever produce an error
 * is worse than a missing one — it teaches people the whole menu is decoration —
 * so it is ABSENT rather than disabled, which is a thing only a test can hold in
 * place. Copy `sessionMenu`'s list into the member overload by accident and
 * everything still compiles, still draws, and still fails on the one click.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class ProjectMenusTest {

    private fun verbs() = ProjectVerbs(
        open = {},
        rename = {},
        setStatus = { _, _ -> },
        delete = {},
        openMember = { _, _ -> },
        message = { _, _ -> },
        endMember = { _, _ -> },
    )

    private fun project(status: String) = ProjectRow(
        id = "6f0d2c41-0000-4000-8000-0000000000b2",
        name = "Status page flap",
        slug = "statusflap",
        kind = "software",
        status = status,
        memberCount = 4,
        alive = 3,
        busy = 1,
        waiting = 1,
        rev = 4,
    )

    private val member = ProjectLive(
        role = "db",
        name = "statusflap-db",
        claudeName = "statusflap/db",
        present = true,
        alive = true,
        status = "busy",
        state = "running",
    )

    @Test
    fun `a member row offers three verbs and Rename is not one of them`() {
        val labels = labelsOf(projectMenu(project("active"), member, verbs()))
        assertEquals(listOf("Open", "Message…", "Kill session"), labels)
        assertFalse(
            labels.any { it.startsWith("Rename") },
            "the rename route refuses a project member with a 409; the item could only ever fail: $labels",
        )
    }

    @Test
    fun `the project itself renames, because the slug is what does not move`() {
        val labels = labelsOf(projectMenu(project("active"), verbs()))
        assertTrue("Rename…" in labels, labels.toString())
        assertEquals("Open", labels.first(), labels.toString())
        assertEquals("Delete…", labels.last(), "the destructive verb is last, as in every other menu here")
    }

    /**
     * ⚠ `canTransition` ANSWERS TRUE FOR A MOVE TO WHERE YOU ALREADY ARE, which is
     * right for a save that changes nothing and wrong for a menu. Asked alone it
     * puts "Resume" on a running project and "Pause" on a paused one — two items
     * that read as the state rather than as the move.
     */
    @Test
    fun `pause and resume are one slot, and it carries the move rather than the state`() {
        val active = labelsOf(projectMenu(project("active"), verbs()))
        assertTrue("Pause" in active, active.toString())
        assertFalse("Resume" in active, "a running project cannot be resumed: $active")

        val paused = labelsOf(projectMenu(project("paused"), verbs()))
        assertTrue("Resume" in paused, paused.toString())
        assertFalse("Pause" in paused, "a paused project cannot be paused again: $paused")

        // Drafting and proposed are neither, and offering either word there would
        // be inventing a state the daemon's table does not have.
        val drafting = labelsOf(projectMenu(project("drafting"), verbs()))
        assertFalse("Pause" in drafting || "Resume" in drafting, drafting.toString())
    }

    /** `archived` is terminal in the daemon's transition table, so nothing may move it. */
    @Test
    fun `an archived project is not offered a move it cannot make`() {
        val labels = labelsOf(projectMenu(project("archived"), verbs()))
        assertFalse("Archive" in labels, "archived is terminal: $labels")
        assertFalse("Pause" in labels || "Resume" in labels, labels.toString())
        // Still deletable: forgetting the record is not a status move.
        assertTrue("Delete…" in labels, labels.toString())
    }

    @Test
    fun `the verbs are wired to the thing the row names`() {
        var opened: String? = null
        var ended: String? = null
        val v = ProjectVerbs(
            open = { opened = it.id },
            rename = {},
            setStatus = { _, _ -> },
            delete = {},
            openMember = { _, m -> opened = m.name },
            message = { _, _ -> },
            endMember = { _, m -> ended = m.name },
        )
        val row = project("active")
        projectMenu(row, v).first { it.label == "Open" }.onClick()
        assertEquals(row.id, opened, "the project row opens the project")
        projectMenu(row, member, v).first { it.label == "Open" }.onClick()
        assertEquals("statusflap-db", opened, "the member row opens the MEMBER, by its tmux name")
        projectMenu(row, member, v).first { it.label == "Kill session" }.onClick()
        assertEquals("statusflap-db", ended, "Kill addresses the tmux session, never the project")
    }
}
