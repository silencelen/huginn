package com.silencelen.huginn.desktop

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.PosixFilePermissions
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse

/**
 * Owner-only from the moment a file exists. POSIX only: on Windows there are no
 * modes to assert and the helper keeps the old plain write.
 */
class PrivateFilesTest {

    private val root: File = Files.createTempDirectory("private-files").toFile()

    @AfterTest
    fun cleanup() {
        root.deleteRecursively()
    }

    private fun mode(f: File): String = PosixFilePermissions.toString(Files.getPosixFilePermissions(f.toPath()))

    @Test
    fun `the temp file is 0600 before a byte of the secret is in it`() {
        if (!PrivateFiles.posix) return
        val target = File(root, "settings.json")
        var seen: String? = null
        PrivateFiles.replace(target, "the-token".toByteArray(), created = { p ->
            // The instant that used to be 0644: created, nothing written yet.
            seen = PosixFilePermissions.toString(Files.getPosixFilePermissions(p)) + " " + Files.size(p)
        })
        assertEquals("rw------- 0", seen)
        assertEquals("rw-------", mode(target))
        assertEquals("the-token", target.readText())
        assertFalse(File(root, "settings.json.tmp").exists(), "the temp file must be swapped in, not left")
    }

    @Test
    fun `an existing file is replaced with the new bytes and stays 0600`() {
        if (!PrivateFiles.posix) return
        val target = File(root, "instance.lock")
        PrivateFiles.replace(target, "1\nfirst\n".toByteArray())
        PrivateFiles.replace(target, "2\nsecond\n".toByteArray())
        assertEquals("2\nsecond\n", target.readText())
        assertEquals("rw-------", mode(target))
    }

    @Test
    fun `a stale temp file is never written through`() {
        // Whatever sits at the temp name — a crash's leftover, at whatever mode it
        // was given — is removed, never opened: the secret goes into a new inode.
        if (!PrivateFiles.posix) return
        val target = File(root, "settings.json")
        val stale = File(root, "settings.json.tmp").apply { writeText("old") }
        Files.setPosixFilePermissions(stale.toPath(), PosixFilePermissions.fromString("rw-r--r--"))
        val observer = File(root, "observer")
        Files.createLink(observer.toPath(), stale.toPath())
        PrivateFiles.replace(target, "the-token".toByteArray())
        assertEquals("old", observer.readText())
        assertEquals("rw-r--r--", mode(observer))
        assertEquals("the-token", target.readText())
    }

    @Test
    fun `a new directory is 0700, parents included`() {
        if (!PrivateFiles.posix) return
        val dir = File(root, "config/huginn-desktop-kt")
        PrivateFiles.dir(dir)
        assertEquals("rwx------", mode(dir))
        assertEquals("rwx------", mode(dir.parentFile))
    }

    @Test
    fun `an existing directory that is wider is narrowed`() {
        if (!PrivateFiles.posix) return
        val dir = File(root, "huginn-desktop-kt").apply { mkdirs() }
        Files.setPosixFilePermissions(dir.toPath(), PosixFilePermissions.fromString("rwxr-xr-x"))
        PrivateFiles.dir(dir)
        assertEquals("rwx------", mode(dir))
    }
}
