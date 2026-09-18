package com.silencelen.huginn.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The pure half of the shared chip row.
 *
 * NOTE the argument order: kotlin.test is `assertEquals(expected, actual, message)`
 * — the REVERSE of JUnit's.
 */
class AttachChipRowTest {

    private fun item(
        id: String,
        label: String = id,
        state: AttachChipState = AttachChipState.READY,
        bytes: Long? = null,
    ) = AttachChipItem(id = id, label = label, image = false, state = state, bytes = bytes)

    @Test
    fun `chips draw in attach order and collapse past the visible cap`() {
        val items = (1..9).map { item("a-$it") }
        assertEquals(
            listOf("a-1", "a-2", "a-3", "a-4", "a-5", "a-6"),
            AttachChips.shown(items).map { it.id },
            "order is the order they were attached in",
        )
        assertEquals(3, AttachChips.overflow(items))
        assertEquals(0, AttachChips.overflow(items.take(6)), "exactly at the cap is not overflow")
    }

    @Test
    fun `the chip says name, size and state`() {
        assertEquals("shot.jpg  12 KB", AttachChips.chipText(item("a", "shot.jpg", bytes = 12_800)))
        assertEquals(
            "shot.jpg…",
            AttachChips.chipText(item("a", "shot.jpg", state = AttachChipState.UPLOADING)),
        )
        assertEquals(
            "big.zip — failed",
            AttachChips.chipText(item("a", "big.zip", state = AttachChipState.FAILED)),
        )
        assertEquals(
            "third.png — waiting",
            AttachChips.chipText(item("a", "third.png", state = AttachChipState.QUEUED)),
        )
    }

    @Test
    fun `an unknown size says nothing rather than zero`() {
        // "0 B" is a claim that somebody looked and the file was empty, which is
        // a different fact from "the size is not known yet".
        assertNull(AttachChips.sizeWords(null))
        assertNull(AttachChips.sizeWords(-1))
        assertEquals("0 B", AttachChips.sizeWords(0), "a genuinely empty file may say so")
        assertEquals("999 B", AttachChips.sizeWords(999))
        assertEquals("1 KB", AttachChips.sizeWords(1024))
        assertEquals("1.5 MB", AttachChips.sizeWords(1_572_864))
        assertEquals("48 MB", AttachChips.sizeWords(50_331_648))
        assertTrue(AttachChips.sizeWords(3L * 1024 * 1024 * 1024)!!.endsWith(" GB"))
    }
}
