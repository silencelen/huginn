package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.desktop.DesktopSettings
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import java.nio.file.Files
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * Security scan F2 (2026-10-15): a core that is installed but does not confirm
 * the unpair must FAIL the removal; a machine with no core must not.
 */
class CoreUnpairTest {
    private fun withHome(block: (java.nio.file.Path) -> Unit) {
        val tmp = Files.createTempDirectory("unpair-test")
        val old = System.getProperty("user.home")
        try { System.setProperty("user.home", tmp.toString()); block(tmp) } finally {
            System.setProperty("user.home", old); tmp.toFile().deleteRecursively()
        }
    }

    private fun device(sock: java.nio.file.Path, home: java.nio.file.Path) = CoreDevice(
        CoroutineScope(Dispatchers.IO),
        DesktopSettings(home.resolve("settings.json").toFile()),
    ) { CoreClient(sock) }

    @Test
    fun `no core on this machine is a successful unpair`() = withHome { home ->
        if (System.getProperty("os.name").startsWith("Windows")) return@withHome
        val r = runBlocking { device(home.resolve("run/huginn/core.sock"), home).unpair() }
        assertTrue(r.isSuccess, "nothing holds a token, so there is nothing to fail: $r")
    }

    @Test
    fun `an installed core that does not answer fails the removal`() = withHome { home ->
        if (System.getProperty("os.name").startsWith("Windows")) return@withHome
        val bin = home.resolve(".local/bin/huginn")
        Files.createDirectories(bin.parent); Files.writeString(bin, "#!/bin/sh\n")
        val r = runBlocking { device(home.resolve("run/huginn/core.sock"), home).unpair() }
        assertTrue(r.isFailure, "an installed core that did not confirm must not count as unpaired")
        assertTrue(r.exceptionOrNull()!!.message!!.startsWith("huginn core is installed"), r.exceptionOrNull()!!.message)
    }
}
