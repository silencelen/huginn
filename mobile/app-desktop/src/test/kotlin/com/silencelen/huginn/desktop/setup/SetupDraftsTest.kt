package com.silencelen.huginn.desktop.setup

import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * THE TOKEN THE PROBE ACTUALLY TRIES.
 *
 * ⚠ "Try the token" READ THE SAVED ONE, NOT THE FIELD. The step's field is
 * ordinary local state until the separate "Save token" button commits it, so on
 * a fresh install — field visibly full of masked dots, nothing saved yet — the
 * primary button answered *"no token saved yet — paste the one from the huginn
 * host above"*. The reader has pasted it. It is on screen. The one control the
 * step offers says it is not there.
 *
 * So the draft is what gets tried, and it is committed BEFORE the probe rather
 * than after it: a token that turns out to work must be the one the next request
 * carries, and a token that does not is still the one the reader typed and must
 * still be in the field when the failure is read.
 */
class SetupDraftsTest {

    @AfterTest
    fun clear() = SetupDrafts.clear()

    @Test
    fun `the field wins over what was saved earlier`() {
        assertEquals("pasted", SetupDrafts.tokenToUse(draft = "pasted", saved = "older"))
    }

    @Test
    fun `an untouched field falls back to the saved token`() {
        assertEquals("saved", SetupDrafts.tokenToUse(draft = "", saved = "saved"))
        assertEquals("saved", SetupDrafts.tokenToUse(draft = "   ", saved = "saved"))
    }

    @Test
    fun `a pasted token keeps no surrounding whitespace`() {
        // Pasting out of a terminal or a password manager brings a newline with
        // it far more often than not, and a bearer with a trailing newline is a
        // 401 that looks exactly like a wrong token.
        assertEquals("pasted", SetupDrafts.tokenToUse(draft = "  pasted\n", saved = ""))
    }

    @Test
    fun `nothing anywhere is still nothing`() {
        assertEquals("", SetupDrafts.tokenToUse(draft = "", saved = ""))
    }

    @Test
    fun `the holder is a draft rather than a store`() {
        SetupDrafts.token = "typed"
        assertEquals("typed", SetupDrafts.tokenToUse(draft = SetupDrafts.token, saved = "saved"))
        SetupDrafts.clear()
        assertEquals("saved", SetupDrafts.tokenToUse(draft = SetupDrafts.token, saved = "saved"))
    }
}
