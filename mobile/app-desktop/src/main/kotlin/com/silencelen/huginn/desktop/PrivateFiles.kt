package com.silencelen.huginn.desktop

import java.io.File
import java.nio.ByteBuffer
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileSystems
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.nio.file.attribute.PosixFilePermission
import java.nio.file.attribute.PosixFilePermissions

/**
 * Files that hold a secret, and the directory they live in: OWNER-ONLY FROM THE
 * MOMENT THEY EXIST.
 *
 * ⚠ RESTRICTING AFTERWARDS IS NOT THE SAME THING. The settings file (the daemon
 * bearer, which is root-equivalent on the host), its `.corrupt` salvage and the
 * single-instance secret used to be written with the process umask (0644 at the
 * usual 022) inside a 0755 directory and narrowed to 0600 only after the bytes
 * were in. Another local account watching the directory could open the file in
 * that window, and an open descriptor keeps reading after a chmod. So here the
 * mode is part of the create call itself, the directory is 0700, and the secret
 * reaches its real name only by an atomic rename of a file that was never wider.
 *
 * On a filesystem without POSIX permissions (Windows) none of that applies and
 * the old behaviour stands: a plain write, then the same move. The files sit
 * under the user's own profile there, and `File.setWritable(false, false)` would
 * set the READ-ONLY attribute rather than clear a bit — a read-only destination
 * cannot be replaced, which is half of how the owner once lost his token.
 */
object PrivateFiles {

    private val OWNER_RW: Set<PosixFilePermission> = PosixFilePermissions.fromString("rw-------")
    private val OWNER_RWX: Set<PosixFilePermission> = PosixFilePermissions.fromString("rwx------")

    /** True where the default filesystem has POSIX modes at all. */
    val posix: Boolean by lazy { FileSystems.getDefault().supportedFileAttributeViews().contains("posix") }

    /**
     * Makes [dir] exist, owner-only. Created 0700 (with any missing parents, as
     * the XDG spec asks); an existing one that is wider is narrowed, so an
     * install made before this rule gets it too.
     */
    fun dir(dir: File) {
        val p = dir.toPath()
        if (!posix) {
            dir.mkdirs()
            return
        }
        if (Files.isDirectory(p)) {
            runCatching {
                if (Files.getPosixFilePermissions(p) != OWNER_RWX) Files.setPosixFilePermissions(p, OWNER_RWX)
            }
            return
        }
        Files.createDirectories(p, PosixFilePermissions.asFileAttribute(OWNER_RWX))
    }

    /**
     * Puts [bytes] at [target], replacing it, by way of [tmp] in the same
     * directory.
     *
     * [tmp] is created FRESH — anything already at that name is removed first and
     * the new file is opened CREATE_NEW — with mode 0600 given to the create call,
     * so there is no instant at which it is readable by anyone else and no older
     * inode, whatever its mode or links, ever receives the secret. Then it is
     * moved over [target] atomically where the filesystem can, and plainly where
     * it cannot: a replaced file beats one that never changes.
     *
     * [created] is called once [tmp] exists — on POSIX while it is still empty,
     * before a byte is written — for a test to look at it.
     */
    fun replace(
        target: File,
        bytes: ByteArray,
        tmp: File = File(target.absoluteFile.parentFile, target.name + ".tmp"),
        created: (Path) -> Unit = {},
    ) {
        val t = tmp.toPath()
        if (posix) {
            Files.deleteIfExists(t)
            Files.newByteChannel(
                t,
                setOf(StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE),
                PosixFilePermissions.asFileAttribute(OWNER_RW),
            ).use { ch ->
                created(t)
                val buf = ByteBuffer.wrap(bytes)
                while (buf.hasRemaining()) ch.write(buf)
            }
        } else {
            Files.write(t, bytes)
            created(t)
        }
        try {
            Files.move(t, target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(t, target.toPath(), StandardCopyOption.REPLACE_EXISTING)
        }
        // A rename keeps the mode it was created with; this only matters if the
        // fallback above had to copy instead.
        if (posix) runCatching { Files.setPosixFilePermissions(target.toPath(), OWNER_RW) }
    }
}
