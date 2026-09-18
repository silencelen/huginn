package com.silencelen.huginn.desktop.setup

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * THE INSTALLER'S HALF OF THE FIRST-RUN HAND-OFF, asserted against the source.
 *
 * WHY A SOURCE TEST AND NOT A RUNTIME ONE. This chain crosses two language
 * boundaries — Kotlin to NSIS to shell — and no compiler sees any of them. The
 * installer writes `first-run.json` with `FileWrite`; the app opens it by name;
 * the release script gates on both. Rename the file on one side, spell a JSON
 * key differently, drop the components page, and every one of those still
 * builds, installs, launches and passes every other check here — the only
 * symptom is a components page whose ticks silently do nothing, which nobody
 * would attribute to the installer.
 *
 * The same argument the AUMID gate already makes in this tree, and the AUMID had
 * to learn it the hard way: 0.3.1 shipped stamping nothing and reported itself
 * healthy the whole time.
 *
 * ⚠ THE WINE HALF IS IN `release-desktop.sh`, not here. This asserts the
 * INSTRUCTIONS are in the script; the release asserts they survived compilation
 * and ran, by reading the file a silent install actually wrote.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class InstallerFirstRunTest {

    /**
     * Resolved rather than assumed: a wrong root would make every assertion here
     * pass by reading an empty string, which is the one way a gate like this
     * fails silently. (`CapBeforeFillTest` learned the same lesson.)
     */
    private val root: File = generateSequence(File("").absoluteFile) { it.parentFile }
        .firstOrNull { File(it, "settings.gradle.kts").isFile }
        ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private val nsi: String by lazy {
        File(root, "app-desktop/packaging/huginn-desktop-kt.nsi").readText()
    }

    private val release: String by lazy {
        File(root, "scripts/release-desktop.sh").readText()
    }

    @Test
    fun `the two files this suite is about were actually read`() {
        assertTrue(nsi.length > 5_000, "the .nsi read as ${nsi.length} chars — the root is wrong")
        assertTrue(release.length > 5_000, "release-desktop.sh read as ${release.length} chars")
    }

    // ----------------------------------------------------------- the page

    @Test
    fun `the installer has a components page`() {
        assertTrue(
            nsi.contains("!insertmacro MUI_PAGE_COMPONENTS"),
            "no components page — the installer cannot ask which optional features to offer",
        )
        // Before INSTFILES, or it would ask after it had already installed.
        assertTrue(
            nsi.indexOf("MUI_PAGE_COMPONENTS") < nsi.indexOf("MUI_PAGE_INSTFILES"),
            "the questions must come before the copying",
        )
    }

    @Test
    fun `every optional section exists, and installs nothing`() {
        // ⚠ EMPTY BY DESIGN, and asserted because "it only pre-answers" is a
        // promise this installer cannot otherwise keep: it declares
        // RequestExecutionLevel user, so a section that tried to install a
        // LocalSystem service or enrol a machine would fail in a way that looks
        // like the feature is broken rather than like the installer overreached.
        for (name in listOf("SEC_CLAUDE", "SEC_DEVICE", "SEC_LOCALAI", "SEC_AUTOSTART")) {
            val at = nsi.indexOf(" $name\n")
            assertTrue(at > 0, "$name is not a section in the installer")
            val body = nsi.substring(at, nsi.indexOf("SectionEnd", at))
                .lineSequence().drop(1).map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith(";") }
                .toList()
            assertEquals(emptyList(), body, "$name installs something; it may only pre-answer")
        }
        // The app itself is not optional: a components page whose top item can
        // be unticked produces an install with nothing in it.
        assertTrue(nsi.contains("SectionIn RO"), "the core section is not marked required")
        // Local AI is the expensive answer (gigabytes of weights), so it is the
        // one section that is off by default — `/o` is how NSIS says that.
        assertTrue(
            nsi.contains("Section /o \"Serve local AI models from this computer\" SEC_LOCALAI"),
            "local AI must not be ticked by default: an installer default should never be the expensive one",
        )
    }

    @Test
    fun `every section is described, so the page is not four bare checkboxes`() {
        assertTrue(nsi.contains("MUI_FUNCTION_DESCRIPTION_BEGIN"))
        for (name in listOf("SEC_CORE", "SEC_CLAUDE", "SEC_DEVICE", "SEC_LOCALAI", "SEC_AUTOSTART")) {
            assertTrue(
                nsi.contains("MUI_DESCRIPTION_TEXT \${$name}"),
                "$name has no description — a components page with unexplained items is a page people leave alone",
            )
        }
    }

    // ------------------------------------------------------ the answer file

    @Test
    fun `the installer writes the file the app opens, by the app's own name`() {
        assertTrue(
            nsi.contains("""\${FirstRun.NAME}" w"""),
            "the installer never opens ${FirstRun.NAME} for writing — the app would never see its answers",
        )
        // Beside settings.json, read in the same order DesktopSettings does:
        // XDG_CONFIG_HOME first, $PROFILE\.config as its fallback. Reading only
        // the fallback aims the write at a profile the app does not use.
        val dollar = '$'
        assertTrue(nsi.contains("""ReadEnvStr ${dollar}R3 "XDG_CONFIG_HOME""""), "XDG is not consulted")
        assertTrue(
            nsi.contains("""StrCpy ${dollar}R1 "${dollar}PROFILE\.config\${dollar}{APP_ID}""""),
            "no profile fallback",
        )
    }

    @Test
    fun `every feature key is written, spelled the way the app reads it`() {
        // A key the installer spells differently is a component somebody ticked
        // that is silently dropped — the flow simply asks again, which reads as
        // the page having done nothing at all.
        for (key in listOf("claudePath", "device", "localAi", "autostart")) {
            assertTrue(nsi.contains(""""$key": """), "the installer never writes the \"$key\" answer")
        }
        assertTrue(nsi.contains(""""source": "windows-installer""""), "the file does not say who wrote it")
    }

    @Test
    fun `the answer file is written on a first install only`() {
        // ⚠ WITHOUT THIS BRANCH every silent self-update drops a fresh answer
        // file beside a live settings.json, and install-time ticks replay over
        // configuration the owner has since changed in Settings. The self-updater
        // runs the installer for every release, so this would be permanent.
        assertTrue(
            Regex("""FileExists.*settings\.json""").containsMatchIn(nsi),
            "the answer file is written unconditionally — a self-update would re-answer the owner's settings",
        )
    }

    @Test
    fun `a silent install still answers every prompt by itself`() {
        // The self-updater runs this installer with /S. A MessageBox without a
        // /SD default blocks that path on a machine with nobody watching — and
        // the update simply never completes.
        val boxes = Regex("""MessageBox """).findAll(nsi).count()
        val defaults = Regex("""/SD """).findAll(nsi).count()
        assertTrue(boxes > 0, "the scan found no MessageBox at all — the pattern is wrong")
        assertTrue(
            defaults >= boxes,
            "$boxes MessageBox calls but only $defaults /SD defaults — a silent self-update would hang on one",
        )
    }

    // -------------------------------------------------------- the shortcut

    @Test
    fun `the uninstaller removes the Startup shortcut the app writes`() {
        // The one crossing in this feature where the two ends are different
        // PROGRAMS: the app creates it, the uninstaller removes it. Left behind
        // it points at an exe that is gone, and Windows reports a failing
        // startup item to its owner on every login, forever.
        val defined = Regex("""!define AUTOSTART_LNK\s+"([^"]+)"""").find(nsi)?.groupValues?.get(1)
        assertEquals(
            Autostart.WINDOWS_LNK,
            defined,
            "the app writes '${Autostart.WINDOWS_LNK}' into Startup and the uninstaller looks for '$defined'",
        )
        assertTrue(
            nsi.contains("""Delete "${'$'}SMSTARTUP\${'$'}{AUTOSTART_LNK}.lnk""""),
            "the uninstaller never removes the Startup shortcut",
        )
    }

    // ---------------------------------------------------------- the gates

    @Test
    fun `the release script refuses a build that lost any of this`() {
        // Source gates, in the script that ships the thing. Every one of these
        // covers a change that builds, installs and launches perfectly while the
        // feature does nothing.
        for (needle in listOf(
            "!insertmacro MUI_PAGE_COMPONENTS",
            "KT_FIRSTRUN=",
            "KT_LNK=",
            "NSI_LNK=",
            "autostart shortcut drift",
            "for key in claudePath device localAi autostart",
        )) {
            assertTrue(release.contains(needle), "release-desktop.sh has no gate for: $needle")
        }
    }

    @Test
    fun `the release script proves the answer file BEFORE the app eats it`() {
        // The app consumes the file on first launch, so a check after the launch
        // would be checking that the DELETION worked rather than that the write
        // did — and would pass against an installer that never wrote anything.
        val readAt = release.indexOf("FIRST_RUN_JSON=")
        val launchAt = release.indexOf("wine ./huginn-desktop-kt.exe")
        assertTrue(readAt > 0, "the smoke test never looks for the answer file")
        assertTrue(launchAt > 0, "the smoke test never launches the installed app")
        assertTrue(readAt < launchAt, "the answer file is read after the app has already deleted it")
        // And the other half: it must be GONE afterwards, or the installer's
        // answers replay on every launch for the life of the install.
        assertTrue(
            release.contains("survived first launch"),
            "nothing checks that first launch consumed the answer file",
        )
        // ⚠ AN `if`, NOT a trailing `[ -f x ] && { … }`: the script runs under
        // `set -euo pipefail`, where a final AND-list with a false test exits 1
        // — so the GOOD outcome would abort the release.
        assertTrue(
            release.contains("""if [ -f "${'$'}FIRST_RUN_JSON" ]; then"""),
            "the consumed-file check is not written as an if, so success would abort the release",
        )
    }
}
