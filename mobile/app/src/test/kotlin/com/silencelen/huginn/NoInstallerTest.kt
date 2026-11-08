package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠ THIS APP INSTALLS NOTHING — not even itself.
 *
 * Play Protect flagged each new sideloaded build ("this app may be harmful"),
 * and the strongest downloader-like signal the APK carried was
 * REQUEST_INSTALL_PACKAGES, held only for the in-app self-updater: a path the
 * private store app already covers, since it put this app on the phone and
 * installs each release from the same GitHub feed (2026-11-08). The app still
 * CHECKS for a release; what it does with one is open the store, or the
 * release page ([com.silencelen.huginn.update.UpdateHandoff]).
 *
 * Held against the sources the APK is built from, so neither the permission
 * nor an installer intent comes back with a refactor. The merged manifest is
 * checked at release with `aapt2 dump permissions`; this is the gate that runs
 * on every test. Comments are skipped: a comment may still say why.
 *
 * JUnit4 (this module's convention): `assertTrue(message, condition)` — message
 * FIRST.
 */
class NoInstallerTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun read(rel: String, floor: Int): String {
        val f = File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > floor)
        return text
    }

    private val xmlComment = Regex("""<!--.*?-->""", RegexOption.DOT_MATCHES_ALL)

    /** The manifest with its comments removed: what the build reads. */
    private fun manifest(): String =
        read("app/src/main/AndroidManifest.xml", 2_000).replace(xmlComment, "")

    @Test
    fun `the manifest asks for no install permission`() {
        assertFalse(
            "REQUEST_INSTALL_PACKAGES is back in the manifest — the store app installs, this app does not",
            "REQUEST_INSTALL_PACKAGES" in manifest(),
        )
    }

    /**
     * Each of these is a way to put an APK in front of the system installer, and
     * none of them has a reason to exist here any more. `package-archive` is the
     * MIME type an ACTION_VIEW install is keyed on.
     */
    private val installerSigns = listOf(
        "package-archive",
        "ACTION_INSTALL_PACKAGE",
        "canRequestPackageInstalls",
        "REQUEST_INSTALL_PACKAGES",
        "MANAGE_UNKNOWN_APP_SOURCES",
        "PackageInstaller",
    )

    @Test
    fun `no source under the app builds an installer intent`() {
        val src = File(root(), "app/src/main")
        assertTrue("app/src/main not found at ${src.absolutePath}", src.isDirectory)
        val files = src.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        assertTrue("walked only ${files.size} Kotlin files — wrong tree", files.size > 50)
        val hits = mutableListOf<String>()
        for (f in files) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.trimStart()
                if (code.startsWith("//") || code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                installerSigns.firstOrNull { it in code }?.let { hits += "${f.relativeTo(root())}:${i + 1}: $it" }
            }
        }
        assertTrue("installer code in the app's sources:\n" + hits.joinToString("\n"), hits.isEmpty())
    }

    @Test
    fun `the FileProvider stays for shared files and exposes no update cache`() {
        // AndroidSentFiles hands a file Claude sent to another app through it.
        assertTrue("the FileProvider is gone — files Claude sends can no longer be opened", "androidx.core.content.FileProvider" in manifest())
        val paths = read("app/src/main/res/xml/file_paths.xml", 200).replace(xmlComment, "")
        assertFalse("file_paths.xml still exposes the downloaded-APK cache", "updates" in paths)
    }

    /**
     * Android 11+ answers null from getLaunchIntentForPackage for a package the
     * manifest does not `<queries>` — the same silent null that once removed the
     * dictation mic. Without this entry the hand-off would send every phone to the
     * release page and call the store absent.
     */
    @Test
    fun `the manifest can see the store app`() {
        val queries = manifest().substringAfter("<queries>", "").substringBefore("</queries>", "")
        assertTrue("no <queries> block", queries.isNotBlank())
        assertTrue(
            "the store app's package is not declared under <queries>",
            Regex("""<package\s+android:name="com\.silence\.devstore"\s*/>""").containsMatchIn(queries),
        )
    }
}
