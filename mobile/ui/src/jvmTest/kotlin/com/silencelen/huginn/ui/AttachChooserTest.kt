package com.silencelen.huginn.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * What the composer's clip button offers, and when it offers nothing to choose
 * from at all.
 *
 * The rest of this change is layout. THIS is the part that can be wrong without
 * looking wrong: a "Notes page" row against a daemon that has no pages route is a
 * menu entry whose only outcome is a 404, and a chooser drawn over a single row
 * is an extra press on the way to the only thing behind it.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class AttachChooserTest {

    private fun row(id: String) = AttachRow(id, id, onPick = {})

    private val desktopOwn = listOf(AttachRow("local-file", "Local file", onPick = {}))
    private val phoneOwn = listOf(row("take-photo"), row("photo-library"), row("local-file"))

    @Test
    fun `no pages route means no page row`() {
        // An older daemon 404s the list, both clients collapse that to an empty
        // list, and this is the only form the composer ever sees the probe in.
        val rows = AttachChooser.rows(desktopOwn, padsAvailable = false, onNotesPage = {})
        assertEquals(listOf("local-file"), rows.map { it.id })
    }

    @Test
    fun `pages add one row, last, and the shell's own are untouched`() {
        val rows = AttachChooser.rows(phoneOwn, padsAvailable = true, onNotesPage = {})
        assertEquals(
            listOf("take-photo", "photo-library", "local-file", AttachChooser.NOTES_PAGE),
            rows.map { it.id },
        )
    }

    @Test
    fun `the page row is quiet copy — sentence case, no emoji`() {
        // House rule, and worth pinning: this row sits beside three system-picker
        // rows that all read as plain nouns, and a 📄 here would be the only one.
        val page = AttachChooser.rows(desktopOwn, padsAvailable = true, onNotesPage = {}).last()
        assertEquals("Notes page", page.label)
        assertTrue(page.label.all { it.code < 128 }, page.label)
    }

    @Test
    fun `the page row runs the caller's action, not one of the shell's`() {
        var opened = false
        val rows = AttachChooser.rows(desktopOwn, padsAvailable = true, onNotesPage = { opened = true })
        rows.last().onPick()
        assertTrue(opened, "the page row must open the page picker")
    }

    @Test
    fun `one row is not a menu`() {
        // The pads-unavailable desktop: the clip button has to behave exactly as
        // it did before the chooser existed, which means going straight to the
        // file dialog rather than opening a popup with one entry in it.
        val rows = AttachChooser.rows(desktopOwn, padsAvailable = false, onNotesPage = {})
        val sole = AttachChooser.direct(rows)
        assertEquals("local-file", sole?.id)

        var picked = false
        val armed = AttachChooser.rows(
            listOf(AttachRow("local-file", "Local file", onPick = { picked = true })),
            padsAvailable = false,
            onNotesPage = {},
        )
        AttachChooser.direct(armed)?.onPick()
        assertTrue(picked, "the sole row's action must be what the button press runs")
    }

    @Test
    fun `two or more rows are a menu`() {
        // Both the desktop with pages and the phone in every state. A non-null
        // here would send the press somewhere the person never chose.
        assertNull(AttachChooser.direct(AttachChooser.rows(desktopOwn, true) {}))
        assertNull(AttachChooser.direct(AttachChooser.rows(phoneOwn, false) {}))
        assertNull(AttachChooser.direct(AttachChooser.rows(phoneOwn, true) {}))
    }

    @Test
    fun `no rows at all is not a direct action either`() {
        // Nothing renders this today, but `singleOrNull` over an empty list is the
        // one place the rule could quietly return something to press.
        assertNull(AttachChooser.direct(emptyList()))
    }
}
