package com.silencelen.huginn.desktop.core

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The real thing across the language line: this app's [CoreClient] (OkHttp over
 * [CoreSocket]) against the real Go `huginn core run`, built from this tree.
 * Skipped where Go is not installed. Linux only — the AF_UNIX path on Windows
 * is proven by proto/windows on a real machine.
 */
class CoreClientTest {
    private var core: Process? = null
    private lateinit var tmp: Path
    private lateinit var sock: Path

    private fun repoRoot(): File? {
        var d: File? = File(System.getProperty("user.dir")).absoluteFile
        while (d != null && !File(d, "core/go.mod").isFile) d = d.parentFile
        return d
    }

    @BeforeTest
    fun start() {
        val root = repoRoot()
        val go = listOf("/usr/local/go/bin/go", "/usr/bin/go").firstOrNull { File(it).canExecute() }
        if (root == null || go == null || !System.getProperty("os.name").startsWith("Linux")) return
        tmp = Files.createTempDirectory("core-client-test")
        val bin = tmp.resolve("huginn").toString()
        val build = ProcessBuilder(go, "build", "-o", bin, "./cmd/huginn").directory(File(root, "core"))
            .redirectErrorStream(true).start()
        val out = build.inputStream.bufferedReader().readText()
        check(build.waitFor(300, TimeUnit.SECONDS) && build.exitValue() == 0) { "go build failed:\n$out" }
        val run = tmp.resolve("run").also { Files.createDirectories(it) }
        sock = run.resolve("huginn/core.sock")
        core = ProcessBuilder(bin, "core", "run").apply {
            environment().apply {
                put("HOME", tmp.toString()); put("XDG_CONFIG_HOME", tmp.resolve("cfg").toString())
                put("XDG_STATE_HOME", tmp.resolve("state").toString()); put("XDG_RUNTIME_DIR", run.toString())
                remove("HUGINN_APPD_TOKEN")
            }
            redirectErrorStream(true); redirectOutput(tmp.resolve("core.log").toFile())
        }.start()
        repeat(100) { if (Files.exists(sock)) return; Thread.sleep(50) }
        fail("the core never opened its socket: " + tmp.resolve("core.log").toFile().readText())
    }

    @AfterTest
    fun stop() {
        core?.destroy(); core?.waitFor(5, TimeUnit.SECONDS)
        if (::tmp.isInitialized) tmp.toFile().deleteRecursively()
    }

    @Test
    fun `device view, patch and the events stream all speak to the real core`() = runBlocking {
        if (core == null) return@runBlocking // no Go here: nothing to prove against
        val c = CoreClient(sock)

        val first = c.device()
        assertEquals(false, first.settings.enabled)
        assertTrue(first.status.note.contains("Not paired"), first.status.note)

        val patched = c.patchDevice(CoreDeviceSettings(scope = "own", root = "/srv/work"))
        assertEquals("own", patched.settings.scope)
        assertEquals("/srv/work", patched.settings.root)

        // The stream replays the newest "device" event first — the patch above.
        val ev = withTimeout(5_000) { c.events().first { it.first == "device" } }
        val view = CoreClient.json.decodeFromString(CoreDeviceView.serializer(), ev.second)
        assertEquals("own", view.settings.scope)

        // A refusal comes back as the core's own words, not a bare status.
        val err = runCatching { c.patchDevice(CoreDeviceSettings(scope = "generate")) }.exceptionOrNull()
        assertNotNull(err)
        assertTrue(err.message!!.contains("huginn local"), err.message)
    }

    @Test
    fun `the socket path matches the Go side`() {
        assertEquals(Path.of("/run/x/huginn/core.sock"), CorePaths.socket({ if (it == "XDG_RUNTIME_DIR") "/run/x" else null }, "Linux"))
        assertEquals(Path.of("C:\\Users\\a\\AppData\\Local", "huginn", "run", "core.sock"),
            CorePaths.socket({ if (it == "LOCALAPPDATA") "C:\\Users\\a\\AppData\\Local" else null }, "Windows 11"))
        // A relative XDG value is ignored (the spec, and the core), falling back to /run/user/<uid>.
        val fallback = CorePaths.socket({ if (it == "XDG_RUNTIME_DIR") "relative/dir" else null }, "Linux")
        assertFalse(fallback.toString().startsWith("relative"))
    }
}
