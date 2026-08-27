package com.silencelen.huginn.desktop

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The launch-time CLI sync replaces shell code on the reader's machine, so its
 * two pure judgements — "what version is this file" and "what name may a
 * download be validated under" — are asserted here. A wrong version parse
 * either updates forever or never; a wrong temp name re-trips the node
 * unknown-extension refusal that cost PRESTIGE its first activation.
 */
class CliSyncTest {

    @Test
    fun everyClientDialectsVersionIsRead() {
        assertEquals("0.12.6", CliSync.versionOf("# x\n\$script:HUGINN_VERSION = '0.12.6'\n"))
        assertEquals("0.12.6", CliSync.versionOf("# x\nHUGINN_VERSION='0.12.6'\n"))
        assertEquals("0.12.6", CliSync.versionOf("'use strict';\nconst VERSION = '0.12.6';\n"))
    }

    @Test
    fun aFileWithNoVersionRefusesTheSwapByReturningNull() {
        assertNull(CliSync.versionOf("<html>rate limited</html>"), "an error page must never be installed")
        assertNull(CliSync.versionOf(""))
    }

    @Test
    fun nodeDownloadsAreValidatedUnderAJsName() {
        assertTrue(CliSync.tmpNameFor("huginn-device").endsWith(".js"))
        assertTrue(CliSync.tmpNameFor("huginn-local").endsWith(".js"))
        // The shells keep their own suffixes — bash and powershell do not care.
        assertTrue(CliSync.tmpNameFor("huginn.sh").endsWith(".tmp"))
        assertTrue(CliSync.tmpNameFor("huginn.ps1").endsWith(".tmp"))
    }

    @Test
    fun theCandidateSetMatchesThePlatformsShell() {
        assertTrue("huginn.ps1" in CliSync.candidates(windows = true))
        assertTrue("huginn.sh" in CliSync.candidates(windows = false))
        assertTrue("huginn-llm-shim" in CliSync.candidates(windows = true))
    }
}
