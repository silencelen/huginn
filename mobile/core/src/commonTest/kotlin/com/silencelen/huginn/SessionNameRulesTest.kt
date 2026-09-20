package com.silencelen.huginn

import com.silencelen.huginn.ui.SessionNameRules
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * ⚠⚠ THE HINT WAS NARROWER THAN THE RULE (P-24).
 *
 * The New-session dialog said *"Letters, digits and underscore"* while the
 * daemon has accepted a DASH since `NAME_RE` was written — and every session in
 * this fleet is named with one (`rv-phone-1`, `rvphoneproj-docs-reviewer`). A
 * hint that forbids what the host allows is a hint people work around and then
 * stop reading.
 *
 * ⚠ AND THE DOT IS STILL REFUSED. The review read the daemon's per-session ROUTE
 * matcher (`[A-Za-z0-9_][A-Za-z0-9_.-]{0,49}`, which exists so an older session
 * carrying a dot can still be addressed) and concluded dots were legal. Creating
 * one goes through `NAME_RE`, which excludes it, because tmux silently rewrites
 * `.` to `_` and exits 0 — so the name you asked for is not the name you get.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionNameRulesTest {

    @Test
    fun `the names this fleet actually uses are accepted`() {
        assertNull(SessionNameRules.nameProblem("rv-phone-1"))
        assertNull(SessionNameRules.nameProblem("rvphoneproj-docs-reviewer"))
        assertNull(SessionNameRules.nameProblem("jtyper"))
        assertNull(SessionNameRules.nameProblem("_scratch"))
        assertNull(SessionNameRules.nameProblem("9lives"))
        assertNull(SessionNameRules.nameProblem("  padded  "), "the daemon trims before it judges")
    }

    @Test
    fun `the hint names the dash, because the dash is what everybody uses`() {
        assertTrue("dash" in SessionNameRules.HINT, SessionNameRules.HINT)
        assertTrue("underscore" in SessionNameRules.HINT, SessionNameRules.HINT)
    }

    @Test
    fun `a dot is refused, and the sentence says why`() {
        val no = SessionNameRules.nameProblem("rv.phone")
        assertTrue(no != null && "tmux rewrites it" in no, "a bare refusal sends somebody to try again: $no")
    }

    @Test
    fun `a leading dash is refused — the daemon's first character is narrower`() {
        assertTrue(SessionNameRules.nameProblem("-lead") != null)
    }

    @Test
    fun `the space, the slash and the colon are all out`() {
        for (bad in listOf("two words", "a/b", "a:b", "a!b", "a\tb")) {
            assertTrue(SessionNameRules.nameProblem(bad) != null, "'$bad' was accepted")
        }
    }

    @Test
    fun `an empty name asks for one rather than reciting the grammar`() {
        assertEquals("a session needs a name", SessionNameRules.nameProblem(""))
        assertEquals("a session needs a name", SessionNameRules.nameProblem("   "))
    }

    @Test
    fun `fifty characters fit and fifty-one do not`() {
        assertNull(SessionNameRules.nameProblem("a".repeat(SessionNameRules.MAX)))
        assertTrue(SessionNameRules.nameProblem("a".repeat(SessionNameRules.MAX + 1)) != null)
        assertEquals(50, SessionNameRules.MAX)
    }
}
