package com.silencelen.huginn.desktop.core

import kotlinx.coroutines.runBlocking
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The bundled core really becomes an installed one — including from a 0644
 * file, which is what the Linux packager leaves in app-resources (measured on
 * createDistributable). Builds the real Go core; skipped without Go.
 */
class CoreSetupTest {
    @Test
    fun `a bundled 0644 core self-installs into the per-user layout and runs`() = runBlocking {
        if (!System.getProperty("os.name").startsWith("Linux")) return@runBlocking
        var root: File? = File(System.getProperty("user.dir")).absoluteFile
        while (root != null && !File(root, "core/go.mod").isFile) root = root.parentFile
        val go = listOf("/usr/local/go/bin/go", "/usr/bin/go").firstOrNull { File(it).canExecute() }
        if (root == null || go == null) return@runBlocking

        val tmp = Files.createTempDirectory("core-setup-test")
        val res = Files.createDirectories(tmp.resolve("resources"))
        val bundled = res.resolve("huginn")
        val b = ProcessBuilder(go, "build", "-o", bundled.toString(), "./cmd/huginn").directory(File(root, "core"))
            .redirectErrorStream(true).start()
        val out = b.inputStream.bufferedReader().readText()
        check(b.waitFor(300, TimeUnit.SECONDS) && b.exitValue() == 0) { out }
        Files.setPosixFilePermissions(bundled, PosixFilePermissions.fromString("rw-r--r--"))

        val oldHome = System.getProperty("user.home")
        val oldRes = System.getProperty("compose.application.resources.dir")
        try {
            System.setProperty("user.home", tmp.resolve("home").toString())
            System.setProperty("compose.application.resources.dir", res.toString())
            val installed = CoreSetup.ensureInstalled().getOrThrow()
            assertEquals(tmp.resolve("home/.local/bin/huginn"), installed)
            val v = ProcessBuilder(installed.toString(), "version").redirectErrorStream(true).start()
            val said = v.inputStream.bufferedReader().readText()
            assertTrue(v.waitFor(10, TimeUnit.SECONDS) && said.startsWith("(o> huginn "), said)
            // Second call: already installed, nothing to do, same answer.
            assertEquals(installed, CoreSetup.ensureInstalled().getOrThrow())
        } finally {
            System.setProperty("user.home", oldHome)
            if (oldRes == null) System.clearProperty("compose.application.resources.dir") else System.setProperty("compose.application.resources.dir", oldRes)
            tmp.toFile().deleteRecursively()
        }
    }
}
