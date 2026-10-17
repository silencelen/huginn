package com.silencelen.huginn.desktop.setup

import com.silencelen.huginn.data.RouteBook
import com.silencelen.huginn.desktop.addressEdit
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Setup's one address field edits the address in use; it does not collect
 * addresses. Run 4: a mistyped 192.166.2.117 was refused, corrected, and still
 * stayed in the book, so huginn core was handed it as a second address.
 */
class AddressEditTest {
    @Test
    fun `a corrected address replaces the mistyped one`() {
        val typo = addressEdit(RouteBook(), "192.166.2.117:8787", 1)
        val fixed = addressEdit(typo, "192.168.2.117:8787", 2)
        assertEquals(listOf("https://192.168.2.117:8787"), fixed.routes.map { it.url })
        assertEquals("https://192.168.2.117:8787", fixed.activeUrl)
    }

    @Test
    fun `an address already in the book is switched to, and nothing is lost`() {
        val two = RouteBook().add("lan", "192.168.7.117:8787", 1).add("mesh", "10.42.0.1:8787", 2)
        val moved = addressEdit(two, "10.42.0.1:8787", 3)
        assertEquals(2, moved.routes.size)
        assertEquals("https://10.42.0.1:8787", moved.activeUrl)
    }
}
