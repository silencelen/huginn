package com.silencelen.huginn.desktop.core

import com.silencelen.huginn.data.HuginnClient
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import java.io.File
import java.net.InetSocketAddress
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.TimeUnit
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.test.fail

/**
 * The whole way, for real (docs/design/desktop-via-core.md): this app's own link is
 * handed to a real huginn core (built from this tree), and then a real
 * HuginnClient call goes app -> CoreDaemonTransport -> the core's socket -> its
 * /appd proxy -> a fake appd on loopback. appd must see the CORE'S token and
 * THIS APP'S client id; the app sends no token at all. Skipped without Go.
 */
class ViaCoreTest {
    private var core: Process? = null
    private lateinit var tmp: Path
    private lateinit var sock: Path
    private lateinit var appd: HttpServer
    private val seen = ConcurrentLinkedQueue<Map<String, String>>()

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
        appd = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/") { ex ->
                seen += mapOf(
                    "path" to ex.requestURI.path,
                    "auth" to (ex.requestHeaders.getFirst("Authorization") ?: ""),
                    "client" to (ex.requestHeaders.getFirst("X-Huginn-Client") ?: ""),
                )
                val body = """{"host":"fakehost","appdVersion":"9.9.9"}""".toByteArray()
                ex.responseHeaders.add("Content-Type", "application/json")
                ex.sendResponseHeaders(200, body.size.toLong())
                ex.responseBody.use { it.write(body) }
            }
            start()
        }
        tmp = Files.createTempDirectory("via-core-test")
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
                // A from-source core reports 2.0.0-dev; this test is about 2.5.1's contract.
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
        if (::appd.isInitialized) appd.stop(0)
        if (::tmp.isInitialized) tmp.toFile().deleteRecursively()
    }

    @Test
    fun `this app's link moves into the core, and every call then goes through it`() = runBlocking {
        if (core == null) return@runBlocking // no Go here: nothing to prove against
        val url = "http://127.0.0.1:${appd.address.port}"
        // The core under test is a from-source build (2.0.0-dev); the version gate
        // is CoreBootTest's job, so this one hands decide() a core that reports 2.5.1.
        val real = CoreBoot.opsFor(sock)
        val ops = object : CoreBoot.Ops by real {
            override suspend fun status() = real.status().copy(version = "2.5.1")
        }
        var cleared = false
        val out = CoreBoot.decide(ops, CoreBoot.OwnLink(baseUrl = url, token = "app-token", routes = emptyList())) { cleared = true }
        assertIs<CoreBoot.Outcome.ViaCore>(out, "$out")
        assertTrue(cleared, "the app's own token goes once a request through the core worked")

        val client = HuginnClient(
            baseUrlProvider = { CoreDaemonTransport.APPD_BASE },
            tokenProvider = { "" },
            clientIdProvider = { "desktop-kt-test" },
            transport = CoreDaemonTransport(sock),
        )
        seen.clear()
        val st = client.status()
        assertEquals("9.9.9", st.appdVersion)
        val req = seen.last()
        assertEquals("/v1/status", req["path"])
        assertEquals("Bearer app-token", req["auth"], "appd sees the token the CORE now holds")
        assertEquals("desktop-kt-test", req["client"], "and this app's own client id")
    }

    @Test
    fun `one client moves onto the core mid-run, with no restart`() = runBlocking {
        if (core == null) return@runBlocking
        val url = "http://127.0.0.1:${appd.address.port}"
        // The core holds a token of its own; this app starts on its own link with another.
        CoreClient(sock).pair(url, "core-token")
        var onCore = false
        val client = HuginnClient(
            baseUrlProvider = { if (onCore) CoreDaemonTransport.APPD_BASE else url },
            tokenProvider = { if (onCore) "" else "app-token" },
            clientIdProvider = { "desktop-kt-test" },
            coreTransport = CoreDaemonTransport(sock),
            useCore = { onCore },
        )
        seen.clear()
        client.status()
        assertEquals("Bearer app-token", seen.last()["auth"], "before: this app's own link")
        onCore = true
        client.status()
        assertEquals("Bearer core-token", seen.last()["auth"], "after: the very next call goes through the core")
        assertEquals("desktop-kt-test", seen.last()["client"])
    }
}
