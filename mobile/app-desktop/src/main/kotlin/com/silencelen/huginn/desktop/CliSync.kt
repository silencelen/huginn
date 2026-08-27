package com.silencelen.huginn.desktop

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File
import java.net.HttpURLConnection
import java.net.URI

/**
 * Keeps THIS MACHINE's huginn CLI install current alongside the app.
 *
 * The desktop and the CLI version independently and update independently — and
 * the owner updated the app, opened a terminal, and found yesterday's CLI.
 * From the person's seat, "I updated huginn on this machine" means the
 * MACHINE. So on launch the app quietly brings along whatever huginn files the
 * machine already has: the client script, and any fetched satellites (device
 * runner, local manager, llm shim).
 *
 * The rules that keep this honest:
 *  - Only files that EXIST are touched. The app never installs the CLI onto a
 *    machine that never had one — presence is the consent.
 *  - Every download is validated the way the CLI's own updater validates
 *    before it replaces anything: parse check, `.bak` of the old copy, atomic
 *    move. Same pinned repo, same trust root as `huginn update`.
 *  - The MACHINE-scope service copies (a serving box's ProgramData `bin/`) are
 *    deliberately not touched: those were installed elevated and update
 *    through `huginn local update`, which owns that elevation.
 */
object CliSync {

    private const val RAW = "https://raw.githubusercontent.com/silencelen/huginn/main/client"

    /** One line for Settings' "This install": what the last sync did. */
    private val _summary = MutableStateFlow<String?>(null)
    val summary: StateFlow<String?> = _summary.asStateFlow()

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var ran = false

    private fun home() = System.getProperty("user.home") ?: "."
    private fun isWindows() = System.getProperty("os.name")?.startsWith("Windows") == true

    /** The files an install MAY have; extensionless ones are node programs. */
    fun candidates(windows: Boolean): List<String> = buildList {
        add(if (windows) "huginn.ps1" else "huginn.sh")
        add("huginn-device")
        add("huginn-local")
        add("huginn-llm-shim")
    }

    /**
     * The version a huginn client file declares, whichever dialect it speaks.
     * Null means "not a file this sync understands" — which refuses the swap.
     */
    fun versionOf(text: String): String? =
        Regex("""\${'$'}script:HUGINN_VERSION = '([0-9][0-9.]*)'""").find(text)?.groupValues?.get(1)
            ?: Regex("""^HUGINN_VERSION='([0-9][0-9.]*)'""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)
            ?: Regex("""^const VERSION = '([0-9][0-9.]*)';""", RegexOption.MULTILINE).find(text)?.groupValues?.get(1)

    /**
     * The temp name a download is validated under. Node files MUST end .js —
     * modern node refuses to parse an unknown extension (the field lesson that
     * cost PRESTIGE its first activation attempt).
     */
    fun tmpNameFor(name: String): String =
        if (name.endsWith(".ps1") || name.endsWith(".sh")) "$name.appsync.tmp" else "$name.appsync.tmp.js"

    fun startOnce() {
        if (ran) return
        ran = true
        scope.launch {
            val r = runCatching { sync() }.getOrNull() ?: return@launch
            if (r.isNotEmpty()) _summary.value = "CLI kept current with the app: ${r.joinToString(", ")}"
        }
    }

    /** Returns the files it updated, as "name old→new". Quiet about the rest. */
    fun sync(): List<String> {
        val updated = mutableListOf<String>()
        for (name in candidates(isWindows())) {
            val dest = File(home(), ".huginn/$name")
            if (!dest.isFile) continue
            try {
                val remote = fetch("$RAW/$name") ?: continue
                val rv = versionOf(remote) ?: continue
                val lv = versionOf(dest.readText()) ?: continue
                if (rv == lv) continue
                val tmp = File(home(), ".huginn/${tmpNameFor(name)}")
                tmp.writeText(remote)
                if (!validate(name, tmp)) { tmp.delete(); continue }
                File(home(), ".huginn/$name.bak").let { bak ->
                    runCatching { dest.copyTo(bak, overwrite = true) }
                }
                if (tmp.renameTo(dest) || (dest.delete() && tmp.renameTo(dest))) {
                    dest.setExecutable(true)
                    updated += "$name $lv→$rv"
                } else {
                    tmp.delete()
                }
            } catch (_: Exception) {
                // A failed sync leaves the working copy alone; `huginn update`
                // remains the hand-driven path and always will.
            }
        }
        return updated
    }

    private fun fetch(url: String): String? {
        val conn = URI(url).toURL().openConnection() as HttpURLConnection
        conn.connectTimeout = 10_000
        conn.readTimeout = 30_000
        if (conn.responseCode != 200) return null
        return conn.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
            .takeIf { it.isNotBlank() }
    }

    private fun validate(name: String, file: File): Boolean = try {
        val cmd = when {
            name.endsWith(".ps1") -> listOf(
                "powershell.exe", "-NoProfile", "-Command",
                "\$null = [scriptblock]::Create((Get-Content -Raw '${file.absolutePath}')); exit 0",
            )
            name.endsWith(".sh") -> listOf("bash", "-n", file.absolutePath)
            else -> {
                // A machine with node satellites but no node cannot validate —
                // and could not run them either; leave everything as it is.
                val node = LocalServe.nodeBin() ?: return false
                listOf(node, "--check", file.absolutePath)
            }
        }
        val p = ProcessBuilder(cmd).redirectErrorStream(true).start()
        p.inputStream.readBytes()
        p.waitFor() == 0
    } catch (_: Exception) { false }
}
