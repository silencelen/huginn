package com.silencelen.huginn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.RandomAccessFile
import java.net.HttpURLConnection
import java.net.URI

/**
 * The desktop's door to the local-AI tier on THIS machine.
 *
 * One implementation, two doors: `huginn local` in a terminal and this section
 * both drive the same fetched manager (`~/.huginn/huginn-local`), which owns
 * the pins, the services and the enrolment. This file deliberately holds no
 * serving state of its own — the app is a door, not a supervisor, so a crash
 * or quit here can neither orphan nor misreport the service.
 *
 * Setting up from here is the same consent the terminal takes, in the same
 * order: `plan` (read-only — class, models, disk gate) is shown to a human,
 * and only their explicit click runs `on --yes`. On Windows the services
 * install as LocalSystem, so the turn-on runs elevated behind ONE UAC prompt;
 * its output crosses the elevation boundary through a log file this side
 * tails, because a RunAs child's stdio cannot. Serving still can never be
 * flipped from another machine — this door exists only on the machine itself.
 */
object LocalServe {

    @Serializable
    data class Services(val llm: String? = null, val runner: String? = null)

    @Serializable
    data class Engine(val reachable: Boolean = false, val models: List<String> = emptyList(), val base: String? = null)

    @Serializable
    data class Status(
        val setup: Boolean = false,
        val mode: String? = null,
        @SerialName("class") val cls: String? = null,
        val deviceName: String? = null,
        val llmSlug: String? = null,
        val services: Services = Services(),
        val engine: Engine = Engine(),
        val models: List<String> = emptyList(),
        val defaultModel: String? = null,
        val shimVersion: String? = null,
        val sessions: Int = 0,
    )

    @Serializable
    data class PlanDownload(val kind: String = "", val name: String = "", val bytes: Long = 0)

    @Serializable
    data class PlanGate(val ok: Boolean = false, val line: String = "")

    /** What `huginn-local plan --json` answers — a decision, not an action. */
    @Serializable
    data class Plan(
        val version: String = "",
        val dir: String = "",
        val platform: String = "",
        val elevated: Boolean = true,
        val setup: Boolean = false,
        val deviceName: String = "",
        val refuse: String? = null,
        val cls: String? = null,
        val plan: String? = null,
        val note: String? = null,
        val downloads: List<PlanDownload> = emptyList(),
        val needBytes: Long = 0,
        val gate: PlanGate? = null,
    )

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    private fun isWindows() = System.getProperty("os.name")?.startsWith("Windows") == true

    private fun huginnDir(): File = File(System.getProperty("user.home") ?: ".", ".huginn")

    fun managerFile(): File = File(huginnDir(), "huginn-local")

    /** The manager's own default data dir, mirrored — never invented here. */
    fun localDataDir(): File = if (isWindows()) {
        File(System.getenv("ProgramData") ?: "C:\\ProgramData", "huginn-local")
    } else {
        File(System.getProperty("user.home") ?: ".", ".config/huginn-local")
    }

    /**
     * Find a node binary the way a person at a terminal would. A GUI app's
     * PATH is whatever the desktop session had at login — an fnm/nvm shell
     * hook, a fresh MSI, or a per-user install can all leave `node` visible
     * in every terminal and invisible here (field-found on the first desktop
     * activation attempt). Every candidate is PROVEN by running `--version`,
     * never assumed from existence.
     */
    fun findNode(): String? {
        val home = System.getProperty("user.home")
        val candidates = buildList {
            add("node")
            if (isWindows()) {
                System.getenv("NVM_SYMLINK")?.let { add(File(it, "node.exe").path) }
                System.getenv("ProgramFiles")?.let { add(File(it, "nodejs\\node.exe").path) }
                System.getenv("ProgramFiles(x86)")?.let { add(File(it, "nodejs\\node.exe").path) }
                System.getenv("LOCALAPPDATA")?.let { add(File(it, "Programs\\nodejs\\node.exe").path) }
            } else {
                add("/usr/local/bin/node")
                add("/usr/bin/node")
                add("/opt/homebrew/bin/node")
                home?.let { add("$it/.nvm/current/bin/node") }
            }
        }
        for (c in candidates) {
            try {
                val p = ProcessBuilder(c, "--version").redirectErrorStream(true).start()
                p.inputStream.readBytes()
                if (p.waitFor() == 0) return c
            } catch (_: Exception) { /* next candidate */ }
        }
        return null
    }

    @Volatile
    private var cachedNode: String? = null

    /** The proven node binary, cached once found; null means genuinely absent. */
    fun nodeBin(): String? {
        cachedNode?.let { return it }
        val found = findNode()
        if (found != null) cachedNode = found
        return found
    }

    private const val NODE_MISSING =
        "node was not found on this machine — install Node.js LTS " +
            "(nodejs.org, or on Windows: winget install OpenJS.NodeJS.LTS), then try again"

    /**
     * Fetch the manager, the shim and the device runner when absent — the same
     * three files, from the same trust root, as the CLI's `huginn local on`
     * (the pinned public repo over TLS), and with the same gate: a download
     * that fails `node --check` is refused, never installed.
     */
    suspend fun fetchManager(onLine: (String) -> Unit): Boolean = withContext(Dispatchers.IO) {
        // NOT `?: run {}`: a bare `run` in this object is the member verb below.
        val node = nodeBin()
        if (node == null) { onLine(NODE_MISSING); return@withContext false }
        for (f in listOf("huginn-local", "huginn-llm-shim", "huginn-device")) {
            val dest = File(huginnDir(), f)
            if (dest.isFile && dest.length() > 0) continue
            dest.parentFile?.mkdirs()
            val tmp = File(huginnDir(), "$f.tmp")
            onLine("fetching $f…")
            try {
                val conn = URI("https://raw.githubusercontent.com/silencelen/huginn/main/client/$f")
                    .toURL().openConnection() as HttpURLConnection
                conn.connectTimeout = 10_000
                conn.readTimeout = 30_000
                check(conn.responseCode == 200) { "answered ${conn.responseCode}" }
                conn.inputStream.use { input -> tmp.outputStream().use { input.copyTo(it) } }
                check(tmp.length() > 0) { "empty download" }
                val chk = ProcessBuilder(node, "--check", tmp.absolutePath)
                    .redirectErrorStream(true).start()
                val chkOut = chk.inputStream.bufferedReader().readText()
                check(chk.waitFor() == 0) { "failed its syntax check: ${chkOut.take(120)}" }
                check(tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) { "could not move into place" }
                dest.setExecutable(true)
            } catch (e: Exception) {
                tmp.delete()
                onLine("could not fetch $f — ${e.message}")
                return@withContext false
            }
        }
        true
    }

    /** `node <manager> status --json`, or a reason when it cannot answer. */
    suspend fun status(): Result<Status> = withContext(Dispatchers.IO) {
        runCatching {
            val mgr = managerFile()
            check(mgr.isFile) { "not fetched" }
            val node = checkNotNull(nodeBin()) { NODE_MISSING }
            val p = ProcessBuilder(node, mgr.absolutePath, "status", "--json")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { out.take(300).ifBlank { "status failed" } }
            json.decodeFromString<Status>(out.lineSequence().first { it.trim().startsWith("{") })
        }
    }

    /** Read-only: what turning on would install HERE. Fetches the manager if absent. */
    suspend fun plan(onLine: (String) -> Unit): Result<Plan> = withContext(Dispatchers.IO) {
        runCatching {
            // Not "any machine that can run claude has node": the native claude
            // build needs no node at all — field-proven by the first machine
            // this ran on. Absence gets the install instruction, by name.
            val node = checkNotNull(nodeBin()) { NODE_MISSING }
            check(fetchManager(onLine)) { "the manager could not be fetched" }
            val p = ProcessBuilder(node, managerFile().absolutePath, "plan", "--json")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { out.take(300).ifBlank { "plan failed" } }
            json.decodeFromString<Plan>(out.lineSequence().first { it.trim().startsWith("{") })
        }
    }

    /**
     * Turn on, after a human has read the plan. Seeds the daemon token the way
     * the CLI wrapper does (the manager itself never fetches credentials),
     * then runs `on --yes` — directly on Linux (user units need no root),
     * elevated on Windows (LocalSystem services do).
     */
    suspend fun enable(baseUrl: String, token: String, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        if (token.isBlank()) { onLine("no daemon token — connect this app to huginn first"); return@withContext 2 }
        if (baseUrl.isBlank()) { onLine("no daemon address — connect this app to huginn first"); return@withContext 2 }
        try {
            val dev = File(localDataDir(), "device")
            dev.mkdirs()
            val tok = File(dev, "appd-token")
            if (!tok.isFile || tok.readText().isBlank()) {
                tok.writeText(token)
                try {
                    java.nio.file.Files.setPosixFilePermissions(
                        tok.toPath(),
                        setOf(
                            java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                            java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
                        ),
                    )
                } catch (_: Exception) { /* not a POSIX filesystem */ }
            }
        } catch (e: Exception) {
            onLine("could not seed the daemon token: ${e.message}")
            return@withContext 2
        }
        val args = listOf("on", "--yes", "--url", baseUrl)
        if (isWindows()) runElevatedWindows(args, onLine) else run(*args.toTypedArray(), onLine = onLine)
    }

    /** Stop serving — elevated on Windows for the same LocalSystem reason. */
    suspend fun disable(onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        if (isWindows()) runElevatedWindows(listOf("off"), onLine) else run("off", onLine = onLine)
    }

    /**
     * Run a manager verb, streaming its own words to the section — raw, so a
     * lying line would at least be an inspectable lie. Returns the exit code.
     */
    suspend fun run(vararg args: String, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val mgr = managerFile()
        if (!mgr.isFile) { onLine("the manager is not fetched — set up first"); return@withContext 2 }
        val node = nodeBin()
        if (node == null) { onLine(NODE_MISSING); return@withContext 2 }
        try {
            val p = ProcessBuilder(node, mgr.absolutePath, *args)
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
            p.waitFor()
        } catch (e: Exception) {
            onLine("could not run the manager: ${e.message}")
            2
        }
    }

    /**
     * The .cmd the elevated side runs. A file, not an inline argument string,
     * for the same reason the device runner uses one: quoting survives review,
     * and the redirect is how output crosses the elevation boundary. The node
     * path is the RESOLVED one, quoted — the elevated environment's PATH is
     * yet another PATH, and `C:\Program Files\nodejs` carries a space.
     */
    fun elevatedCmdText(node: String, mgr: File, args: List<String>, log: File): String =
        "@echo off\r\n\"$node\" \"${mgr.path}\" ${args.joinToString(" ")} > \"${log.path}\" 2>&1\r\n"

    private suspend fun runElevatedWindows(args: List<String>, onLine: (String) -> Unit): Int {
        val mgr = managerFile()
        if (!mgr.isFile) { onLine("the manager is not fetched — set up first"); return 2 }
        val node = nodeBin()
        if (node == null) { onLine(NODE_MISSING); return 2 }
        val dir = localDataDir()
        val log = File(dir, "activate.log")
        val cmd = File(dir, "activate.cmd")
        try {
            dir.mkdirs()
            log.writeText("")
            cmd.writeText(elevatedCmdText(node, mgr, args, log))
        } catch (e: Exception) {
            onLine("could not stage the elevated step: ${e.message}")
            return 2
        }
        onLine("waiting for the UAC prompt — the services install as LocalSystem…")
        val ps = try {
            ProcessBuilder(
                "powershell.exe", "-NoProfile", "-Command",
                "exit (Start-Process -Verb RunAs -PassThru -Wait -FilePath '${cmd.absolutePath}').ExitCode",
            ).redirectErrorStream(true).start()
        } catch (e: Exception) {
            onLine("could not start the elevated step: ${e.message}")
            return 2
        }
        var pos = 0L
        var sawOutput = false
        while (ps.isAlive) {
            pos = drainLog(log, pos) { sawOutput = true; onLine(it) }
            delay(500)
        }
        pos = drainLog(log, pos) { sawOutput = true; onLine(it) }
        val rc = ps.waitFor()
        // The exit code of an elevated child is advisory at best (it can be
        // unreadable across the boundary) — the section's refresh of `status`
        // is the truth. But a silent nonzero deserves one honest line.
        if (rc != 0 && !sawOutput) onLine("nothing ran — the UAC prompt was declined, or elevation failed")
        return rc
    }

    /** Read anything new past [pos]; both \r (progress) and \n end a line. */
    private fun drainLog(log: File, pos: Long, onLine: (String) -> Unit): Long {
        if (!log.isFile) return pos
        val len = log.length()
        if (len <= pos) return pos
        return try {
            RandomAccessFile(log, "r").use { raf ->
                raf.seek(pos)
                val bytes = ByteArray((len - pos).toInt())
                raf.readFully(bytes)
                String(bytes, Charsets.UTF_8)
                    .split('\n', '\r')
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .forEach(onLine)
                len
            }
        } catch (_: Exception) { pos }
    }
}
