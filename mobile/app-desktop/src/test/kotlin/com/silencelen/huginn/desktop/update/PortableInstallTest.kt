package com.silencelen.huginn.desktop.update

import com.silencelen.huginn.desktop.notify.SchemeRegistrar
import com.silencelen.huginn.update.GhAsset
import com.silencelen.huginn.update.GhRelease
import com.silencelen.huginn.update.ReleaseFeed
import com.silencelen.huginn.update.ReleaseFeedException
import kotlinx.coroutines.test.runTest
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.Paths
import java.util.zip.GZIPOutputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * A tar writer small enough to read: ustar headers by hand, so a test can build
 * the archives a real `tar` refuses to make (absolute names, `..`, escaping
 * links) without needing `-P` or a hostile build.
 */
internal object TestTar {
    data class E(val name: String, val type: Char = '0', val data: ByteArray = ByteArray(0), val link: String = "", val mode: Int = 0b110_100_100)

    fun file(name: String, text: String = "x", exec: Boolean = false) =
        E(name, '0', text.toByteArray(), mode = if (exec) 0b111_101_101 else 0b110_100_100)
    fun dir(name: String) = E(name, '5', mode = 0b111_101_101)
    fun symlink(name: String, to: String) = E(name, '2', link = to, mode = 0b111_111_111)
    fun hardlink(name: String, to: String) = E(name, '1', link = to)

    fun header(e: E, nameOverride: String? = null, size: Int = e.data.size, corrupt: Boolean = false): ByteArray {
        val h = ByteArray(512)
        fun put(off: Int, len: Int, s: String) { val b = s.toByteArray(); System.arraycopy(b, 0, h, off, minOf(b.size, len)) }
        put(0, 100, nameOverride ?: e.name)
        put(100, 8, "%07o".format(e.mode) + "\u0000")
        put(108, 8, "0000000\u0000"); put(116, 8, "0000000\u0000")
        put(124, 12, "%011o".format(size) + "\u0000")
        put(136, 12, "%011o".format(1_700_000_000L) + "\u0000")
        h[156] = e.type.code.toByte()
        put(157, 100, e.link)
        put(257, 6, "ustar\u0000"); put(263, 2, "00")
        for (i in 148 until 156) h[i] = ' '.code.toByte()
        var sum = 0; for (b in h) sum += b.toInt() and 0xff
        if (corrupt) sum += 1
        put(148, 8, "%06o".format(sum) + "\u0000 ")
        return h
    }

    private fun pad(n: Int) = ByteArray((512 - n % 512) % 512)

    fun build(entries: List<E>, raw: (ByteArrayOutputStream) -> Unit = {}): ByteArray {
        val out = ByteArrayOutputStream()
        raw(out)
        for (e in entries) {
            out.write(header(e))
            if (e.type == '0') { out.write(e.data); out.write(pad(e.data.size)) }
        }
        out.write(ByteArray(1024))
        return out.toByteArray()
    }

    fun gz(tar: ByteArray): ByteArray = ByteArrayOutputStream().also { b -> GZIPOutputStream(b).use { it.write(tar) } }.toByteArray()

    /** A well-formed release tarball for [version]. */
    fun release(version: String, withLauncher: Boolean = true, top: String = "huginn-desktop-kt-$version"): ByteArray {
        val es = mutableListOf(dir("$top/"), dir("$top/bin/"), dir("$top/lib/"), file("$top/lib/app.jar", "jar-$version"))
        if (withLauncher) es += file("$top/bin/huginn-desktop-kt", "#!/bin/sh\necho huginn $version\n", exec = true)
        es += symlink("$top/lib/link-inside", "../bin/huginn-desktop-kt")
        return gz(build(es))
    }
}

class TarSafetyTest {

    private fun check(vararg es: TestTar.E): Result<String> {
        val f = File.createTempFile("tar", ".tar.gz")
        try {
            f.writeBytes(TestTar.gz(TestTar.build(es.toList())))
            return PortableInstall.checkTar(f)
        } finally { f.delete() }
    }

    private fun refused(r: Result<String>, why: String) {
        assertTrue(r.isFailure, "expected a refusal for $why, got ${r.getOrNull()}")
    }

    @Test
    fun `a well formed release lists to its one top dir, internal links allowed`() {
        val f = File.createTempFile("tar", ".tar.gz")
        f.writeBytes(TestTar.release("1.2.3"))
        assertEquals("huginn-desktop-kt-1.2.3", PortableInstall.checkTar(f).getOrThrow())
        f.delete()
    }

    @Test
    fun `absolute paths are refused`() =
        refused(check(TestTar.dir("top/"), TestTar.file("/etc/passwd")), "absolute")

    @Test
    fun `dot-dot anywhere is refused, even when it would stay inside`() {
        refused(check(TestTar.dir("top/"), TestTar.file("top/../../evil")), "climb")
        refused(check(TestTar.dir("top/"), TestTar.file("top/a/../b")), "inner ..")
    }

    @Test
    fun `symlinks that escape the top dir are refused`() {
        refused(check(TestTar.dir("top/"), TestTar.symlink("top/x", "../../etc")), "relative escape")
        refused(check(TestTar.dir("top/"), TestTar.symlink("top/x", "../outside")), "sibling escape")
        refused(check(TestTar.dir("top/"), TestTar.symlink("top/x", "/etc/passwd")), "absolute target")
        refused(check(TestTar.dir("top/"), TestTar.symlink("top/x", "")), "empty target")
    }

    @Test
    fun `hardlinks outside the top dir are refused`() {
        refused(check(TestTar.dir("top/"), TestTar.hardlink("top/x", "other/file")), "hardlink to sibling")
        refused(check(TestTar.dir("top/"), TestTar.hardlink("top/x", "/etc/shadow")), "absolute hardlink")
        refused(check(TestTar.dir("top/"), TestTar.hardlink("top/x", "top/../../x")), "climbing hardlink")
        assertTrue(check(TestTar.dir("top/"), TestTar.file("top/a"), TestTar.hardlink("top/b", "top/a")).isSuccess)
    }

    @Test
    fun `more than one top level entry is refused`() {
        refused(check(TestTar.dir("a/"), TestTar.dir("b/")), "two top dirs")
        refused(check(TestTar.dir("a/"), TestTar.file("a/x"), TestTar.file("loose")), "a loose file")
        refused(check(TestTar.file("top")), "a top that is a file")
        refused(check(TestTar.dir("./")), "the root entry")
    }

    @Test
    fun `devices, fifos and corrupt headers are refused`() {
        refused(check(TestTar.dir("top/"), TestTar.E("top/dev", '3')), "char device")
        refused(check(TestTar.dir("top/"), TestTar.E("top/fifo", '6')), "fifo")
        val f = File.createTempFile("tar", ".tar.gz")
        val tar = ByteArrayOutputStream().apply {
            write(TestTar.header(TestTar.dir("top/"), corrupt = true)); write(ByteArray(1024))
        }.toByteArray()
        f.writeBytes(TestTar.gz(tar))
        refused(PortableInstall.checkTar(f), "bad checksum")
        f.writeBytes(TestTar.gz(ByteArray(1024)))
        refused(PortableInstall.checkTar(f), "empty archive")
        f.writeBytes(byteArrayOf(1, 2, 3))
        refused(PortableInstall.checkTar(f), "not gzip")
        f.delete()
    }

    @Test
    fun `a GNU long name or a pax path cannot smuggle a different name past the check`() {
        // The innocent ustar name is "top/ok"; the long-name record says "../evil".
        val evil = "top/../../evil".toByteArray()
        val tar = ByteArrayOutputStream().apply {
            write(TestTar.header(TestTar.dir("top/")))
            write(TestTar.header(TestTar.E("././@LongLink", 'L'), size = evil.size)); write(evil); write(ByteArray(512 - evil.size))
            write(TestTar.header(TestTar.file("top/ok"))); write("x".toByteArray()); write(ByteArray(511))
            write(ByteArray(1024))
        }.toByteArray()
        val f = File.createTempFile("tar", ".tar.gz")
        f.writeBytes(TestTar.gz(tar))
        refused(PortableInstall.checkTar(f), "GNU long name with ..")

        val rec = "path=/etc/cron.d/x\n"
        val line = "${rec.length + 3} $rec".let { l -> if (l.length == rec.length + 3) l else "${rec.length + 4} $rec" }
        val pax = line.toByteArray()
        val tar2 = ByteArrayOutputStream().apply {
            write(TestTar.header(TestTar.dir("top/")))
            write(TestTar.header(TestTar.E("top/PaxHeader", 'x'), size = pax.size)); write(pax); write(ByteArray(512 - pax.size))
            write(TestTar.header(TestTar.file("top/ok"))); write("x".toByteArray()); write(ByteArray(511))
            write(ByteArray(1024))
        }.toByteArray()
        f.writeBytes(TestTar.gz(tar2))
        refused(PortableInstall.checkTar(f), "pax path override")
        f.delete()
    }
}

class LinuxInstallTest {

    private fun tmp(): File = Files.createTempDirectory("portable").toFile()

    /** R with the marker, versions/<v>/bin/huginn-desktop-kt and current -> versions/<v>. */
    private fun layout(version: String = "1.0.0", marker: Boolean = true): File {
        val r = File(tmp(), "huginn-desktop")
        val bin = File(r, "versions/$version/bin").apply { mkdirs() }
        File(bin, "huginn-desktop-kt").apply { writeText("#!/bin/sh\necho old\n"); setExecutable(true) }
        File(r, "versions/$version/lib").mkdirs()
        if (marker) File(r, LinuxInstall.MARKER).writeText("")
        Files.createSymbolicLink(File(r, "current").toPath(), Paths.get("versions", version))
        return r
    }

    private val arch = "NAME=\"Arch Linux\"\nID=arch\n"
    private val omarchy = "NAME=\"Omarchy\"\nID=omarchy\nID_LIKE=arch\n"
    private val ubuntu = "NAME=\"Ubuntu\"\nID=ubuntu\nID_LIKE=debian\n"

    @Test
    fun `a launcher inside a marked, writable versions tree is portable`() {
        val r = layout()
        val m = LinuxInstall.detect(File(r, "versions/1.0.0/bin/huginn-desktop-kt").path, arch)
        assertEquals(LinuxInstall.Kind.PORTABLE, m.kind)
        assertEquals(r.canonicalFile, m.root?.canonicalFile)
        assertEquals(File(r, "versions/1.0.0").canonicalFile, m.versionDir?.canonicalFile)
        // Through the stable symlink too: canonicalised to the versioned dir.
        val viaCurrent = LinuxInstall.detect(File(r, "current/bin/huginn-desktop-kt").path, arch)
        assertEquals(LinuxInstall.Kind.PORTABLE, viaCurrent.kind)
        assertEquals(File(r, "versions/1.0.0").canonicalFile, viaCurrent.versionDir?.canonicalFile)
        r.parentFile.deleteRecursively()
    }

    @Test
    fun `the same shape without the marker is not portable`() {
        val r = layout(marker = false)
        val m = LinuxInstall.detect(File(r, "versions/1.0.0/bin/huginn-desktop-kt").path, ubuntu)
        assertEquals(LinuxInstall.Kind.DEB, m.kind)
        r.parentFile.deleteRecursively()
    }

    @Test
    fun `an opt install is named by its distro, a gradle run is dev`() {
        val opt = "/opt/huginn-desktop-kt/bin/huginn-desktop-kt"
        assertEquals(LinuxInstall.Kind.ARCH, LinuxInstall.detect(opt, arch).kind)
        assertEquals(LinuxInstall.Kind.ARCH, LinuxInstall.detect(opt, omarchy).kind)
        assertEquals(LinuxInstall.Kind.DEB, LinuxInstall.detect(opt, ubuntu).kind)
        assertEquals(LinuxInstall.Kind.OTHER, LinuxInstall.detect(opt, "ID=fedora\n").kind)
        assertEquals(LinuxInstall.Kind.OTHER, LinuxInstall.detect(opt, null).kind)
        assertEquals(LinuxInstall.Kind.DEV, LinuxInstall.detect(null, arch).kind)
        assertEquals(LinuxInstall.Distro.ARCH, LinuxInstall.distro("ID=endeavouros\nID_LIKE=\"arch\"\n"))
        assertEquals(LinuxInstall.Distro.DEBIAN, LinuxInstall.distro("ID=linuxmint\nID_LIKE=\"ubuntu debian\"\n"))
    }

    @Test
    fun `outside paths go through current, never a versioned dir`() {
        val r = layout()
        val real = File(r, "versions/1.0.0/bin/huginn-desktop-kt").path
        val stable = LinuxInstall.stableLauncher(real, LinuxInstall.detect(real, null))!!
        assertTrue(stable.endsWith("/current/bin/huginn-desktop-kt"), stable)
        assertFalse(stable.contains("versions"), stable)
        assertEquals("/opt/huginn-desktop-kt/bin/huginn-desktop-kt",
            LinuxInstall.stableLauncher("/opt/huginn-desktop-kt/bin/huginn-desktop-kt"))
        r.parentFile.deleteRecursively()
    }

    @Test
    fun `portableRoot honours XDG_DATA_HOME`() {
        assertEquals("/x/data/huginn-desktop", LinuxInstall.portableRoot({ if (it == "XDG_DATA_HOME") "/x/data" else null }, "/home/u")!!.path)
        assertEquals("/home/u/.local/share/huginn-desktop", LinuxInstall.portableRoot({ null }, "/home/u")!!.path)
    }

    @Test
    fun `manual hints name the distro's command and the self-updating install`() {
        val f = File("/c/huginn-desktop-kt-1.2.0-1-x86_64.pkg.tar.zst")
        val a = LinuxInstall.manualHint(LinuxInstall.Kind.ARCH, f)
        assertTrue(a.contains("sudo pacman -U ${f.path}") && a.contains("huginn desktop install"), a)
        val d = LinuxInstall.manualHint(LinuxInstall.Kind.DEB, File("/c/x.deb"))
        assertTrue(d.contains("sudo apt install /c/x.deb") && d.contains("huginn desktop install"), d)
        assertTrue(LinuxInstall.manualHint(LinuxInstall.Kind.OTHER, f).contains("huginn desktop install"))
    }

    @Test
    fun `platform keys narrow by install kind and leave the old key alone`() {
        assertEquals("linux-x64-portable", UpdatePlatform.forInstall("linux-x64", LinuxInstall.Kind.PORTABLE))
        assertEquals("linux-x64-arch", UpdatePlatform.forInstall("linux-x64", LinuxInstall.Kind.ARCH))
        assertEquals("linux-x64", UpdatePlatform.forInstall("linux-x64", LinuxInstall.Kind.DEB))
        assertEquals("linux-x64", UpdatePlatform.forInstall("linux-x64", LinuxInstall.Kind.DEV))
        assertEquals("windows-x64", UpdatePlatform.forInstall("windows-x64", LinuxInstall.Kind.PORTABLE))
    }

    @Test
    fun `the per-user desktop entry is stable, quoted and claims the scheme`() {
        val e = SchemeRegistrar.linuxDesktopEntry("/home/a b/.local/share/huginn-desktop/current/bin/huginn-desktop-kt")
        assertTrue(e.contains("Exec=\"/home/a b/.local/share/huginn-desktop/current/bin/huginn-desktop-kt\" %u"), e)
        assertTrue(e.contains("Icon=/home/a b/.local/share/huginn-desktop/current/lib/huginn-desktop-kt.png"), e)
        assertTrue(e.contains("MimeType=x-scheme-handler/huginn;"), e)
        assertEquals("huginn-desktop-kt.desktop", SchemeRegistrar.LINUX_DESKTOP_FILE)
    }
}

class PortableApplyTest {

    private fun tmp(): File = Files.createTempDirectory("portable").toFile()

    private fun layout(version: String = "1.0.0"): File {
        val r = File(tmp(), "huginn-desktop")
        val bin = File(r, "versions/$version/bin").apply { mkdirs() }
        File(bin, "huginn-desktop-kt").apply { writeText("#!/bin/sh\necho old\n"); setExecutable(true) }
        File(r, LinuxInstall.MARKER).writeText("")
        Files.createSymbolicLink(File(r, "current").toPath(), Paths.get("versions", version))
        return r
    }

    private fun tarball(bytes: ByteArray): File = File.createTempFile("rel", ".tar.gz").apply { writeBytes(bytes) }

    private fun currentName(r: File) = Files.readSymbolicLink(File(r, "current").toPath()).toString()

    @Test
    fun `apply unpacks beside, swaps current atomically and keeps new plus previous`() {
        val r = layout("1.0.0")
        val t1 = tarball(TestTar.release("1.1.0"))
        val launcher = PortableInstall.apply(r, t1, "1.1.0", running = File(r, "versions/1.0.0")).getOrThrow()
        assertEquals(File(r, "current/bin/huginn-desktop-kt").path, launcher.path)
        assertEquals("versions/1.1.0", currentName(r)) // relative, like the CLI writes
        assertTrue(File(r, "current/bin/huginn-desktop-kt").canExecute())
        assertTrue(Files.isSymbolicLink(File(r, "versions/1.1.0/lib/link-inside").toPath()))
        assertEquals(setOf("1.0.0", "1.1.0"), File(r, "versions").list()!!.toSet())

        // A stale temp dir from an interrupted run is swept by the next prune.
        File(r, "versions/.tmp-0.9.0").mkdirs()
        val t2 = tarball(TestTar.release("1.2.0"))
        PortableInstall.apply(r, t2, "1.2.0", running = File(r, "versions/1.1.0")).getOrThrow()
        assertEquals("versions/1.2.0", currentName(r))
        assertEquals(setOf("1.1.0", "1.2.0"), File(r, "versions").list()!!.toSet(), "1.0.0 pruned, the running 1.1.0 kept")
        assertFalse(File(r, ".current.tmp-${ProcessHandle.current().pid()}").exists())
        r.parentFile.deleteRecursively(); t1.delete(); t2.delete()
    }

    @Test
    fun `the running version is never pruned even when it is not current`() {
        val r = layout("1.0.0")
        PortableInstall.apply(r, tarball(TestTar.release("1.1.0")), "1.1.0", running = File(r, "versions/1.0.0")).getOrThrow()
        // Still running 1.0.0 (never restarted) when 1.2.0 lands.
        PortableInstall.apply(r, tarball(TestTar.release("1.2.0")), "1.2.0", running = File(r, "versions/1.0.0")).getOrThrow()
        assertEquals(setOf("1.0.0", "1.1.0", "1.2.0"), File(r, "versions").list()!!.toSet())
        r.parentFile.deleteRecursively()
    }

    @Test
    fun `every failure leaves current where it was`() {
        val cases = listOf(
            "no launcher" to (TestTar.release("1.1.0", withLauncher = false) to "1.1.0"),
            "wrong top dir" to (TestTar.release("1.1.0", top = "something-else") to "1.1.0"),
            "unsafe link" to (TestTar.gz(TestTar.build(listOf(
                TestTar.dir("huginn-desktop-kt-1.1.0/"),
                TestTar.symlink("huginn-desktop-kt-1.1.0/x", "../../../../etc"),
            ))) to "1.1.0"),
            "not gzip" to ("garbage".toByteArray() to "1.1.0"),
            "bad version" to (TestTar.release("1.1.0") to "1.1.0/../../x"),
        )
        for ((why, c) in cases) {
            val r = layout("1.0.0")
            val res = PortableInstall.apply(r, tarball(c.first), c.second)
            assertTrue(res.isFailure, "expected failure: $why")
            assertEquals("versions/1.0.0", currentName(r), "current moved on: $why")
            assertTrue(File(r, "current/bin/huginn-desktop-kt").canExecute(), "the old launcher is gone: $why")
            assertEquals(listOf("1.0.0"), File(r, "versions").list()!!.toList(), "debris left behind: $why")
            r.parentFile.deleteRecursively()
        }
    }

    @Test
    fun `a tree without the marker is not ours to rewrite`() {
        val r = layout("1.0.0")
        File(r, LinuxInstall.MARKER).delete()
        assertTrue(PortableInstall.apply(r, tarball(TestTar.release("1.1.0")), "1.1.0").isFailure)
        assertEquals("versions/1.0.0", currentName(r))
        r.parentFile.deleteRecursively()
    }

    @Test
    fun `the relaunch waits for the old process to exit before starting the new one`() {
        val d = tmp()
        val stamp = File(d, "started")
        val launcher = File(d, "launch").apply { writeText("#!/bin/sh\ndate +%s%N > '${stamp.path}'\n"); setExecutable(true) }
        val old = ProcessBuilder("sleep", "1").start()
        val armedAt = System.nanoTime()
        val waiter = ProcessBuilder("sh", "-c", PortableInstall.RELAUNCH_SCRIPT, "t", old.pid().toString(), launcher.path).start()
        Thread.sleep(500)
        assertFalse(stamp.exists(), "relaunched while the old process was still alive")
        assertTrue(waiter.waitFor(10, java.util.concurrent.TimeUnit.SECONDS))
        assertTrue(stamp.exists(), "never relaunched")
        assertFalse(old.isAlive)
        assertTrue(System.nanoTime() - armedAt >= 900_000_000L)
        d.deleteRecursively()
    }
}

/** The updater's portable lane end to end, with GitHub and the apply faked. */
class PortableUpdaterTest {

    private class Feed(val releases: List<GhRelease>, val texts: Map<String, String>) : ReleaseFeed {
        override val repo = "t/r"
        override suspend fun list(perPage: Int) = releases
        override suspend fun getText(url: String) = texts[url] ?: throw ReleaseFeedException(404, url)
    }

    private class Http(val files: Map<String, ByteArray>) : UpdateHttp {
        override suspend fun download(url: String, dest: File, onProgress: (Long, Long) -> Unit) {
            dest.parentFile?.mkdirs(); dest.writeBytes(files[url] ?: throw UpdateHttpException(404, url))
        }
    }

    private fun setup(payload: ByteArray, key: String, file: String): Pair<Feed, Http> {
        val m = "https://dl/manifest.json"
        val a = "https://dl/$file"
        val man = """{"version":"1.1.0","notes":"n","artifacts":{
            "linux-x64":{"file":"x.deb","sha256":"${"0".repeat(64)}","size":1},
            "$key":{"file":"$file","sha256":"${Sha256.ofBytes(payload)}","size":${payload.size}}}}"""
        val rel = GhRelease(tagName = "desktop-v1.1.0", assets = listOf(GhAsset("manifest.json", m, 1), GhAsset(file, a, payload.size.toLong()), GhAsset("x.deb", "https://dl/x.deb", 1)))
        return Feed(listOf(rel), mapOf(m to man)) to Http(mapOf(a to payload))
    }

    private fun cache(): File = Files.createTempDirectory("cache").toFile()

    @Test
    fun `a portable install fetches the tarball, applies it and arms the relaunch`() = runTest {
        val payload = TestTar.release("1.1.0")
        val (feed, http) = setup(payload, "linux-x64-portable", "huginn-desktop-kt-1.1.0-linux-x64.tar.gz")
        val root = File("/r"); val running = File("/r/versions/1.0.0")
        val applied = mutableListOf<String>(); val relaunched = mutableListOf<File>()
        val u = DesktopUpdater(
            "1.0.0", "linux-x64", feed, http, "desktop-v", cache(), isWindows = false,
            linux = LinuxInstall.Mode(LinuxInstall.Kind.PORTABLE, root, running),
            portableApply = { r, t, v, run -> applied += "$r|${t.name}|$v|$run"; Result.success(File("/r/current/bin/huginn-desktop-kt")) },
            relauncher = { relaunched += it; true },
        )
        val s = u.check()
        assertTrue(s is UpdateState.Ready && s.installable, "got $s")
        assertEquals("huginn-desktop-kt-1.1.0-linux-x64.tar.gz", s.file.name)
        assertEquals("", s.hint)
        assertTrue(installThenQuit(install = u::install, quit = {}))
        assertEquals(listOf("/r|huginn-desktop-kt-1.1.0-linux-x64.tar.gz|1.1.0|/r/versions/1.0.0"), applied)
        assertEquals(listOf(File("/r/current/bin/huginn-desktop-kt")), relaunched)
    }

    @Test
    fun `a tampered file, a failed apply or a failed relaunch never quits`() = runTest {
        val payload = TestTar.release("1.1.0")
        val (feed, http) = setup(payload, "linux-x64-portable", "huginn-desktop-kt-1.1.0-linux-x64.tar.gz")
        fun mk(apply: Result<File>, relaunch: Boolean) = DesktopUpdater(
            "1.0.0", "linux-x64", feed, http, "desktop-v", cache(), isWindows = false,
            linux = LinuxInstall.Mode(LinuxInstall.Kind.PORTABLE, File("/r"), null),
            portableApply = { _, _, _, _ -> apply }, relauncher = { relaunch },
        )
        val tampered = mk(Result.success(File("/x")), true)
        val s = tampered.check() as UpdateState.Ready
        s.file.appendText("!")
        var quits = 0
        assertFalse(installThenQuit(install = tampered::install, quit = { quits++ }))
        assertTrue((tampered.state.value as UpdateState.Error).message.contains("sha256"))

        val failing = mk(Result.failure(IllegalStateException("refused the archive: nope")), true)
        failing.check()
        assertFalse(installThenQuit(install = failing::install, quit = { quits++ }))
        assertTrue((failing.state.value as UpdateState.Error).message.contains("nope"))

        val stuck = mk(Result.success(File("/x")), false)
        stuck.check()
        assertFalse(installThenQuit(install = stuck::install, quit = { quits++ }))
        assertTrue((stuck.state.value as UpdateState.Error).message.contains("installed"))
        assertEquals(0, quits)
    }

    @Test
    fun `a pacman install parks the arch package with a pacman hint and stays not installable`() = runTest {
        val payload = "pkg".toByteArray()
        val (feed, http) = setup(payload, "linux-x64-arch", "huginn-desktop-kt-1.1.0-1-x86_64.pkg.tar.zst")
        var applied = false
        val u = DesktopUpdater(
            "1.0.0", "linux-x64", feed, http, "desktop-v", cache(), isWindows = false,
            linux = LinuxInstall.Mode(LinuxInstall.Kind.ARCH),
            portableApply = { _, _, _, _ -> applied = true; Result.success(File("/x")) },
        )
        val s = u.check()
        assertTrue(s is UpdateState.Ready, "got $s")
        assertFalse(s.installable)
        assertEquals("huginn-desktop-kt-1.1.0-1-x86_64.pkg.tar.zst", s.file.name)
        assertTrue(s.hint.contains("sudo pacman -U") && s.hint.contains("huginn desktop install"), s.hint)
        assertFalse(u.install()); assertFalse(applied)
    }

    @Test
    fun `a deb install keeps the old key and gets an apt hint`() = runTest {
        val payload = "deb".toByteArray()
        val m = "https://dl/manifest.json"
        val man = """{"version":"1.1.0","notes":"n","artifacts":{"linux-x64":{"file":"x.deb","sha256":"${Sha256.ofBytes(payload)}","size":3}}}"""
        val feed = Feed(listOf(GhRelease(tagName = "desktop-v1.1.0", assets = listOf(GhAsset("manifest.json", m, 1), GhAsset("x.deb", "https://dl/x.deb", 3)))), mapOf(m to man))
        val u = DesktopUpdater(
            "1.0.0", "linux-x64", feed, Http(mapOf("https://dl/x.deb" to payload)), "desktop-v", cache(), isWindows = false,
            linux = LinuxInstall.Mode(LinuxInstall.Kind.DEB),
        )
        val s = u.check()
        assertTrue(s is UpdateState.Ready && !s.installable, "got $s")
        assertNotNull(s.hint.takeIf { it.contains("sudo apt install") && it.contains("huginn desktop install") }, s.hint)
    }
}
