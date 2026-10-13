package com.silencelen.huginn.ui

import androidx.compose.material3.darkColorScheme
import androidx.compose.ui.graphics.Color
import com.silencelen.huginn.ui.theme.verbInk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * THE TWO REDS, DEFINED ONCE.
 *
 * "Kill session" and "Wrap up" both end a session and only one of them can lose
 * work, so they are drawn as a pair in two weights of the same red rather than as
 * a red row and an ordinary one — which is the arrangement people picked the
 * wrong verb out of. The pairing is a decision, so it is a function of the scheme
 * rather than a literal inside a composable, and this is what stops the phone and
 * the desktop each inventing their own lighter red.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class VerbInkTest {

    private val scheme = darkColorScheme(error = Color(0xFFE8736D), onSurface = Color(0xFFE8E2DA))

    @Test
    fun `the hard verb is the theme's error, untouched`() {
        assertEquals(scheme.error, verbInk(VerbTone.DESTRUCTIVE, scheme))
    }

    @Test
    fun `the soft verb is the quiet ink, not a lighter red`() {
        // PR 2 (2026-10-13): "Wrap up" asks Claude to finish; it ends nothing by
        // force, so it takes the secondary ink. Red, at any alpha, is for Kill.
        val soft = verbInk(VerbTone.SOFT, scheme)
        assertEquals(scheme.onSurfaceVariant, soft)
        assertNotEquals(verbInk(VerbTone.DESTRUCTIVE, scheme), soft, "two verbs drawn identically are one verb twice")
        assertNotEquals(verbInk(VerbTone.PLAIN, scheme), soft, "soft is quieter than plain, not the same")
    }

    @Test
    fun `a plain verb is not red at all`() {
        // Half a menu in red says nothing. Everything that is not an ending verb
        // — including Archive, which ends a session and keeps every part of it —
        // takes the menu's ordinary ink.
        assertEquals(scheme.onSurface, verbInk(VerbTone.PLAIN, scheme))
        assertNotEquals(scheme.error, verbInk(VerbTone.PLAIN, scheme))
    }

    @Test
    fun `the token follows the scheme rather than being a fixed colour`() {
        // Light mode has its own error red (a darker one), and a hard-coded token
        // would be a phone-at-night colour printed on a white background.
        val light = darkColorScheme(error = Color(0xFFB3261E), onSurface = Color(0xFF1B1817), onSurfaceVariant = Color(0xFF535B56))
        assertEquals(light.error, verbInk(VerbTone.DESTRUCTIVE, light))
        assertEquals(light.onSurfaceVariant, verbInk(VerbTone.SOFT, light))
        assertNotEquals(verbInk(VerbTone.SOFT, scheme), verbInk(VerbTone.SOFT, light))
    }
}
