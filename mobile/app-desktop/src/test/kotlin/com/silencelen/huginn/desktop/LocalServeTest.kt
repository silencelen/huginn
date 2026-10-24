package com.silencelen.huginn.desktop

import kotlinx.serialization.json.Json
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * The desktop's set-up door decodes what `huginn-local plan --json` says and
 * stages what the elevated side runs. Both halves are pure text contracts, so
 * both are asserted here — the classic failure being a renamed JSON field that
 * silently zeroes a card the owner is about to consent to.
 *
 * NOTE the kotlin.test argument order: (expected, actual, message).
 */
class LocalServeTest {

    @Test
    fun `client files come from a release tag, never a moving branch`() {
        assertEquals(
            "https://raw.githubusercontent.com/silencelen/huginn/cli-v2.5.8/client/huginn-local",
            LocalServe.clientFileUrl("cli-v2.5.8", "huginn-local"),
        )
        // The fetch used to read raw.githubusercontent.com/.../main/client/<file>:
        // whatever was on the default branch at that moment, reviewed or not.
        val src = File("src/main/kotlin/com/silencelen/huginn/desktop/LocalServe.kt").readText()
        assertFalse(src.contains("/main/client/"), "LocalServe must not fetch client files from main")
    }

    private val json = Json { ignoreUnknownKeys = true; explicitNulls = false }

    // A real answer from a class-C machine, abbreviated only in the sha-less
    // fields the desktop never reads.
    private val planC = """
        {"version":"0.12.3","dir":"/root/.config/huginn-local","platform":"linux",
         "elevated":true,"setup":false,"deviceName":"pc01-llm",
         "services":["huginn-local-llm","huginn-local-runner"],"cls":"C","plan":"C",
         "downloads":[
           {"kind":"llama","name":"llama-b10628-bin-ubuntu-x64.tar.gz","bytes":17000000},
           {"kind":"model","name":"Qwen3-4B-Q4_K_M.gguf","bytes":2497000000}],
         "needBytes":2514000000,
         "gate":{"ok":true,"line":"need 4.3 GiB (incl. 2 GiB headroom), have 39.0 GiB free"}}
    """.trimIndent()

    @Test
    fun `a serving plan decodes into the consent card's fields`() {
        val p = json.decodeFromString<LocalServe.Plan>(planC)
        assertEquals("C", p.cls)
        assertEquals("pc01-llm", p.deviceName)
        assertNull(p.refuse)
        assertEquals(2, p.downloads.size)
        assertEquals(2_497_000_000L, p.downloads[1].bytes)
        assertEquals(2_514_000_000L, p.needBytes)
        assertTrue(p.gate?.ok == true)
        assertFalse(p.setup)
    }

    @Test
    fun `a refusal decodes with no plan half at all`() {
        val p = json.decodeFromString<LocalServe.Plan>(
            """{"version":"0.12.3","dir":"d","platform":"linux","elevated":true,
                "setup":false,"deviceName":"x-llm","services":[],
                "refuse":"this CPU has no AVX2"}""",
        )
        assertEquals("this CPU has no AVX2", p.refuse)
        assertNull(p.cls)
        assertTrue(p.downloads.isEmpty())
        assertNull(p.gate)
    }

    @Test
    fun `unknown fields from a newer manager do not break the decode`() {
        val p = json.decodeFromString<LocalServe.Plan>(
            """{"version":"0.13.0","dir":"d","platform":"win32","elevated":false,
                "setup":true,"deviceName":"y-llm","services":[],"cls":"G8","plan":"G8",
                "futureField":{"nested":true}}""",
        )
        assertEquals("G8", p.cls)
        assertFalse(p.elevated)
        assertTrue(p.setup)
    }

    @Test
    fun `downloads are syntax-checked under a js name`() {
        // `node --check x.tmp` dies with ERR_UNKNOWN_FILE_EXTENSION on modern
        // node (esm/get_format) — reproduced on node 22.23.1 the day the first
        // Node-24 machine hit it in the field. The temp name is the fix.
        assertTrue(LocalServe.fetchTmpName("huginn-local").endsWith(".js"))
        assertTrue(LocalServe.fetchTmpName("huginn-local").startsWith("huginn-local."))
    }

    // ------------------------------------------------ persistence (decision 33)

    @Test
    fun `turning on from here asks Linux for system units`() {
        // ⚠ THE BUG THIS FEATURE IS. Without --system the manager writes a
        // systemd USER unit, which dies at logout — so every Linux machine set
        // up from this door stopped serving the moment its owner logged out,
        // under a card that said "Installs two always-on services". Windows
        // needs no flag: WinSW installs LocalSystem services by construction,
        // which is exactly what --system asks Linux for.
        assertEquals(
            listOf("on", "--yes", "--url", "http://100.64.0.1:8787", "--system"),
            LocalServe.enableArgs("http://100.64.0.1:8787", windows = false),
        )
        assertEquals(
            listOf("on", "--yes", "--url", "http://100.64.0.1:8787"),
            LocalServe.enableArgs("http://100.64.0.1:8787", windows = true),
        )
    }

    private fun status(
        persistent: Boolean?,
        systemUnits: Boolean? = null,
        setup: Boolean = true,
    ) = LocalServe.Status(setup = setup, persistent = persistent, systemUnits = systemUnits)

    @Test
    fun `the copy rule says the true thing about logging out on each OS`() {
        assertEquals(
            "Serves while you are logged out — Windows services (LocalSystem).",
            LocalServe.persistenceCopy(status(true, systemUnits = true), windows = true).line,
        )
        assertEquals(
            "Serves while you are logged out — system services.",
            LocalServe.persistenceCopy(status(true, systemUnits = true), windows = false).line,
        )
        // Persistent WITHOUT system units is the linger fallback, and it is a
        // different sentence: what keeps it alive there is a logind setting
        // somebody can turn off, not the unit scope.
        assertTrue(
            LocalServe.persistenceCopy(status(true, systemUnits = false), windows = false)
                .line.contains("linger"),
        )
    }

    @Test
    fun `only the broken state offers an action, and unknown never borrows the reassuring line`() {
        val stops = LocalServe.persistenceCopy(status(false, systemUnits = false), windows = false)
        assertEquals("Make it permanent", stops.action)
        assertTrue(stops.line.contains("Stops when you log out"), stops.line)

        // ⚠ null is "could not tell", not "yes". A machine with no logind to
        // ask has done nothing wrong — but claiming it serves while logged out
        // is the same lie in a quieter voice, and it would carry no action to
        // fix it either.
        val unknown = LocalServe.persistenceCopy(status(null), windows = false)
        assertNull(unknown.action)
        assertFalse(unknown.line.contains("Serves while you are logged out"), unknown.line)
        assertFalse(unknown.line.contains("Stops when you log out"), unknown.line)

        // Nothing at all to say about a machine that is not set up.
        assertEquals("", LocalServe.persistenceCopy(status(null, setup = false), windows = false).line)
        assertNull(LocalServe.persistenceCopy(status(true, setup = false), windows = true).action)
    }

    @Test
    fun `the consent card never promises always-on where it cannot deliver`() {
        assertTrue(LocalServe.consentServicesCopy("win32", null).contains("UAC"))
        val elevated = LocalServe.consentServicesCopy("linux", "pkexec")
        assertTrue(elevated.contains("logged out"), elevated)
        assertTrue(elevated.contains("password once"), elevated)
        // A Linux box with neither pkexec nor sudo gets the honest version —
        // the old copy said "two always-on services" here and was simply wrong.
        val bare = LocalServe.consentServicesCopy("linux", null)
        assertFalse(bare.contains("always-on"), bare)
        assertTrue(bare.contains("linger"), bare)
        assertTrue(bare.contains("may stop serving when you log out"), bare)
    }

    @Test
    fun `status decodes the three persistence fields and the adopted flag`() {
        // A renamed or dropped field here silently turns the section's honest
        // line back into the old lie, with nothing on screen looking wrong.
        val s = json.decodeFromString<LocalServe.Status>(
            """{"setup":true,"mode":"managed","class":"C","deviceName":"box-llm",
                "systemUnits":false,"linger":false,"persistent":false,"adopted":false,
                "services":{"llm":"active","runner":"active"}}""",
        )
        assertEquals(false, s.persistent)
        assertEquals(false, s.linger)
        assertEquals(false, s.systemUnits)
        assertFalse(s.adopted)

        // A manager older than the facet sends none of them, and the tri-state
        // must survive that as null rather than defaulting to a claim.
        val old = json.decodeFromString<LocalServe.Status>(
            """{"setup":true,"mode":"managed","deviceName":"box-llm"}""",
        )
        assertNull(old.persistent)
        assertNull(old.linger)
        assertNull(old.systemUnits)

        val adopted = json.decodeFromString<LocalServe.Status>(
            """{"setup":true,"mode":"adapter","adopted":true,"persistent":true,"systemUnits":true}""",
        )
        assertTrue(adopted.adopted)
    }

    @Test
    fun `the plan carries how the one elevation prompt would happen`() {
        val p = json.decodeFromString<LocalServe.Plan>(
            """{"version":"1.0.0","dir":"d","platform":"linux","elevated":true,"setup":false,
                "deviceName":"x-llm","services":[],"cls":"C","plan":"C",
                "elevation":"pkexec","persistent":true}""",
        )
        assertEquals("pkexec", p.elevation)
        assertEquals(true, p.persistent)
        // No elevator on this machine: null, which is what makes the consent
        // card drop the "password once" promise.
        val bare = json.decodeFromString<LocalServe.Plan>(
            """{"version":"1.0.0","dir":"d","platform":"linux","elevated":true,"setup":false,
                "deviceName":"x-llm","services":[],"cls":"C","plan":"C"}""",
        )
        assertNull(bare.elevation)
        assertNull(bare.persistent)
    }

    @Test
    fun `the elevated cmd quotes all three paths and redirects everything to the log`() {
        val text = LocalServe.elevatedCmdText(
            "C:\\Program Files\\nodejs\\node.exe",
            File("C:\\Users\\o o\\.huginn\\huginn-local"),
            listOf("on", "--yes", "--url", "http://100.64.0.1:8787"),
            File("C:\\ProgramData\\huginn-local\\activate.log"),
        )
        assertTrue(text.startsWith("@echo off\r\n"), text)
        // All three paths may carry spaces (Program Files always does); each
        // must be quoted, and stderr must not be lost.
        assertTrue("\"C:\\Program Files\\nodejs\\node.exe\"" in text, text)
        assertTrue("\"C:\\Users\\o o\\.huginn\\huginn-local\"" in text, text)
        assertTrue("> \"C:\\ProgramData\\huginn-local\\activate.log\" 2>&1" in text, text)
        assertTrue(" \"on\" \"--yes\" \"--url\" \"http://100.64.0.1:8787\" " in text, text)
        assertTrue(text.endsWith("\r\n"), "cmd files end their line DOS-style")
    }

    // ------------------------------------------- what a saved address can do
    //
    // The Base URL is typed by a person and only lightly shaped by RouteGuard,
    // which accepts & | ^ % ( ) < > ' , ; over https. It is then interpolated
    // into a .cmd that runs under a UAC grant, so every one of those characters
    // is cmd.exe syntax executing as LocalSystem, laundered as the owner's own
    // click. `&calc`, `|more` and `&reg` were all reproduced running as separate
    // commands under wine's cmd from the shipped line.

    @Test
    fun `every argument is quoted, so a poisoned address cannot become a second command`() {
        val text = LocalServe.elevatedCmdText(
            "C:\\node.exe",
            File("C:\\huginn-local"),
            listOf("on", "--yes", "--url", "https://huginn.example&hostname"),
            File("C:\\activate.log"),
        )
        assertTrue("\"https://huginn.example&hostname\"" in text, text)
        // Not as its own command: nothing outside quotes may separate a line.
        assertFalse(" &hostname" in text, text)
    }

    @Test
    fun `an argument carrying cmd syntax that quoting cannot contain is refused`() {
        // %VAR% expands INSIDE double quotes, ^ is cmd's own escape, and a
        // double quote ends the quoting the fix depends on. None of the three
        // can be made safe by wrapping, so the step refuses to be staged.
        listOf(
            "https://huginn.example%PATH%",
            "https://huginn.example^",
            "https://huginn.example\"",
        ).forEach { poisoned ->
            assertFalse(LocalServe.cmdArgSafe(poisoned), "must not be staged: $poisoned")
            assertFailsWith<IllegalArgumentException>("must refuse: $poisoned") {
                LocalServe.elevatedCmdText(
                    "C:\\node.exe",
                    File("C:\\huginn-local"),
                    listOf("on", "--yes", "--url", poisoned),
                    File("C:\\activate.log"),
                )
            }
        }
        assertTrue(LocalServe.cmdArgSafe("https://huginn.example:8787"))
        assertTrue(LocalServe.cmdArgSafe("https://huginn.example&hostname"))
    }

    // ------------------------------------------------ token seeding

    @Test
    fun `the elevated cmd hands the manager the staged token path, after the lock not before`() {
        // The token is root-equivalent on the host. The unelevated app must not
        // write it into ProgramData (inherited Users-read until lockTree); it
        // stages it owner-only and the elevated manager reads it from
        // HUGINN_SEED_TOKEN_FILE and writes it AFTER lockTree.
        val text = LocalServe.elevatedCmdText(
            "C:\\node.exe",
            File("C:\\huginn-local"),
            listOf("on", "--yes", "--url", "https://huginn.example:8787"),
            File("C:\\activate.log"),
            File("C:\\Users\\o\\AppData\\Local\\Temp\\.huginn-seed-42.tmp"),
        )
        assertTrue(
            "set \"HUGINN_SEED_TOKEN_FILE=C:\\Users\\o\\AppData\\Local\\Temp\\.huginn-seed-42.tmp\"\r\n" in text,
            text,
        )
        // The set line precedes the node line, so the env is in force for it.
        assertTrue(text.indexOf("HUGINN_SEED_TOKEN_FILE") < text.indexOf("node.exe"), text)
        // With no staged token (e.g. the via-core path), no set line at all.
        val none = LocalServe.elevatedCmdText(
            "C:\\node.exe", File("C:\\huginn-local"),
            listOf("off"), File("C:\\activate.log"),
        )
        assertFalse(none.contains("HUGINN_SEED_TOKEN_FILE"), none)
    }

    @Test
    fun `a staging path carrying cmd syntax is refused, not interpolated`() {
        assertFailsWith<IllegalArgumentException> {
            LocalServe.elevatedCmdText(
                "C:\\node.exe", File("C:\\huginn-local"),
                listOf("on", "--yes"), File("C:\\activate.log"),
                File("C:\\Temp\\%PATH%.tmp"),
            )
        }
    }

    @Test
    fun `the POSIX token write is 0600 at creation and replaces atomically`() {
        if (System.getProperty("os.name")?.startsWith("Windows") == true) return
        val dir = java.nio.file.Files.createTempDirectory("ls-seed").toFile()
        val tok = File(File(dir, "device"), "appd-token")
        val old = try {
            // A wide umask is exactly when writeText-then-chmod is briefly
            // group/other-readable; the fix creates with the mode.
            LocalServe.writeOwnerOnlyPosix(tok, "first-token")
            java.nio.file.Files.getPosixFilePermissions(tok.toPath())
        } catch (e: UnsupportedOperationException) {
            return // not a POSIX filesystem
        }
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            old,
        )
        assertEquals("first-token", tok.readText())
        // A rotated token replaces it and stays 0600.
        LocalServe.writeOwnerOnlyPosix(tok, "second-token")
        assertEquals("second-token", tok.readText())
        assertEquals(
            setOf(
                java.nio.file.attribute.PosixFilePermission.OWNER_READ,
                java.nio.file.attribute.PosixFilePermission.OWNER_WRITE,
            ),
            java.nio.file.Files.getPosixFilePermissions(tok.toPath()),
        )
        // No temp residue in device\.
        val residue = File(dir, "device").listFiles()?.filter { it.name.contains(".tmp") } ?: emptyList()
        assertTrue(residue.isEmpty(), "left a temp behind: $residue")
    }
}
