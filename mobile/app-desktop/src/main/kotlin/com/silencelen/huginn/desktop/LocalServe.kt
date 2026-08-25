package com.silencelen.huginn.desktop

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * The desktop's door to the local-AI tier on THIS machine.
 *
 * One implementation, two doors: `huginn local` in a terminal and this section
 * both drive the same fetched manager (`~/.huginn/huginn-local`), which owns
 * the pins, the services and the enrolment. This file deliberately holds no
 * serving state of its own — the app is a door, not a supervisor, so a crash
 * or quit here can neither orphan nor misreport the service. When the manager
 * has not been fetched yet, the honest answer is the terminal instruction, not
 * a switch wired to nothing.
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

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    fun managerFile(): File =
        File(System.getProperty("user.home") ?: ".", ".huginn/huginn-local")

    /** `node <manager> status --json`, or null with a reason when it cannot run. */
    suspend fun status(): Result<Status> = withContext(Dispatchers.IO) {
        runCatching {
            val mgr = managerFile()
            check(mgr.isFile) { "not fetched" }
            val p = ProcessBuilder("node", mgr.absolutePath, "status", "--json")
                .redirectErrorStream(true).start()
            val out = p.inputStream.bufferedReader().readText()
            check(p.waitFor() == 0) { out.take(300).ifBlank { "status failed" } }
            json.decodeFromString<Status>(out.lineSequence().first { it.trim().startsWith("{") })
        }
    }

    /**
     * Run a manager verb, streaming its own words to the section — raw, so a
     * lying line would at least be an inspectable lie. Returns the exit code.
     */
    suspend fun run(vararg args: String, onLine: (String) -> Unit): Int = withContext(Dispatchers.IO) {
        val mgr = managerFile()
        if (!mgr.isFile) { onLine("the manager is not fetched — run: huginn local on"); return@withContext 2 }
        try {
            val p = ProcessBuilder("node", mgr.absolutePath, *args)
                .redirectErrorStream(true).start()
            p.inputStream.bufferedReader().forEachLine { onLine(it) }
            p.waitFor()
        } catch (e: Exception) {
            onLine("could not run the manager: ${e.message}")
            2
        }
    }
}
