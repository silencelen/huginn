package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * No string the app ships may name a private address or a tailnet host.
 *
 * The store that delivers this APK scans its strings and treats any private
 * IPv4 address or `.ts.net` name as an endpoint the app talks to; a build that
 * carries one is held at the last good version. App 3.12.0 was held for one
 * example in a setup hint ("for example 192.168.1.10"). Examples use names
 * under example.net instead.
 *
 * Reads the sources the APK is built from: string literals only, so comments
 * may still describe real layouts.
 */
class NoPrivateEndpointStringsTest {

    private fun mobileRoot(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private val literal = Regex(""""(?:[^"\\\n]|\\.)*"""")
    private val privateV4 = Regex(
        """\b(?:10\.\d{1,3}|192\.168|172\.(?:1[6-9]|2\d|3[01]))\.\d{1,3}\.\d{1,3}\b""",
    )
    private val tailnetHost = Regex("""[A-Za-z0-9-]+\.[A-Za-z0-9-]+\.ts\.net""")

    @Test
    fun `no shipped string literal names a private address or a tailnet host`() {
        val root = mobileRoot()
        val dirs = listOf("app/src/main", "ui/src/commonMain", "ui/src/androidMain", "core/src/commonMain", "core/src/androidMain")
            .map { File(root, it) }.filter { it.isDirectory }
        val hits = mutableListOf<String>()
        for (dir in dirs) {
            dir.walkTopDown().filter { it.isFile && (it.extension == "kt" || it.extension == "xml") }.forEach { f ->
                f.readLines().forEachIndexed { i, line ->
                    val code = line.trimStart()
                    if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                    val strings = if (f.extension == "xml") listOf(line) else literal.findAll(line).map { it.value }.toList()
                    for (s in strings) {
                        val hit = privateV4.find(s) ?: tailnetHost.find(s)
                        if (hit != null) hits += "${f.relativeTo(root)}:${i + 1}: ${hit.value}"
                    }
                }
            }
        }
        assertTrue("private endpoints in shipped strings:\n" + hits.joinToString("\n"), hits.isEmpty())
    }
}
