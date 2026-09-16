package com.silencelen.huginn.desktop

import java.io.File
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * BOTH SHELLS ARE BEHIND THE GUARD — asserted against the source, because the
 * hole this closes was an ABSENCE and no behavioural test can assert the absence
 * of a second code path.
 *
 * The state this replaces: the desktop had a four-host allowlist in its own
 * store and refused anything else; the phone had NOTHING — `setBaseUrl` and
 * `selectRoute` took whatever string arrived and wrote it. One bearer follows the
 * base URL on every request, so the phone's address field was a one-field path
 * to handing a root-equivalent daemon token to a stranger, for as long as that
 * field existed.
 *
 * What makes it true now is structural rather than diligent: there is no setter
 * that takes a bare address at all. Both stores persist a `RouteBook`, and every
 * way into one — `add`, `setUrl`, and `normalized()` on the load path — goes
 * through `RouteGuard`. So what this file asserts is that the OLD doors stayed
 * shut: no second allowlist, no back-door setter, no shell parsing addresses for
 * itself.
 *
 * Same technique, same reasoning and the same failure mode as [CapBeforeFillTest]
 * next door: a glob that matches nothing exits 0, so the floor is asserted before
 * the finding is.
 */
class RouteGuardReachTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun sources(module: String): List<File> {
        val dir = File(root(), "$module/src")
        return dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
    }

    private val phoneStore get() = File(root(), "app/src/main/kotlin/com/silencelen/huginn/data/SettingsStore.kt")
    private val desktopStore get() = File(root(), "app-desktop/src/main/kotlin/com/silencelen/huginn/desktop/DesktopSettings.kt")
    private val guard get() = File(root(), "core/src/commonMain/kotlin/com/silencelen/huginn/data/RouteGuard.kt")

    @Test
    fun `the files this test is about exist at all`() {
        for (f in listOf(phoneStore, desktopStore, guard)) {
            assertTrue(f.isFile, "not found, so nothing below was checked: ${f.path}")
        }
        assertTrue(sources("app").size > 20 && sources("app-desktop").size > 20, "a module was not scanned")
    }

    /**
     * There is one rule and it lives in `:core`. A shell that grew a second
     * opinion — another host list, another URL parser — is a shell that will
     * disagree with the first one exactly once, in the direction of letting
     * something through.
     */
    @Test
    fun `neither shell keeps an address rule of its own`() {
        val offences = (sources("app") + sources("app-desktop")).filter { f ->
            val text = f.readText()
            // A literal host list, or java.net.URI used to judge an address, in a
            // file that is not a test.
            !f.path.contains("/test/") && (
                Regex("""val\s+ALLOWED_HOSTS""").containsMatchIn(text) ||
                    Regex("""fun\s+isAllowedBaseUrl\s*\([^)]*\)\s*:\s*Boolean\s*\{""").containsMatchIn(text)
                )
        }.map { it.path }
        assertTrue(
            offences.isEmpty(),
            "the address rule lives in :core RouteGuard, once:\n" + offences.joinToString("\n"),
        )
    }

    /**
     * ⚠ NO BACK-DOOR SETTER. `setBaseUrl` and `selectRoute` took a bare string
     * and were the phone's unguarded path. They are gone from the contract, and
     * `base_url` is now only ever written as a projection of the active route.
     */
    @Test
    fun `no store takes a bare address any more`() {
        for (store in listOf(phoneStore, desktopStore)) {
            val text = store.readText()
            assertTrue(
                !Regex("""fun\s+setBaseUrl\s*\(""").containsMatchIn(text),
                "${store.name} still has a setter that takes a bare address",
            )
            assertTrue(
                !Regex("""fun\s+selectRoute\s*\(""").containsMatchIn(text),
                "${store.name} still has the old route setter",
            )
            assertTrue(
                Regex("""override\s+suspend\s+fun\s+setRouteBook\s*\(""").containsMatchIn(text),
                "${store.name} does not persist a RouteBook",
            )
        }
    }

    /** The refusal sentence is written once and aliased, never re-typed. */
    @Test
    fun `the refusal sentence has exactly one definition`() {
        val definitions = (sources("core") + sources("app") + sources("app-desktop")).count { f ->
            !f.path.contains("Test") &&
                Regex("""const\s+val\s+REFUSED\s*:\s*String\s*=\s*"""").containsMatchIn(f.readText())
        }
        assertTrue(definitions == 1, "the sentence is defined $definitions times; it should be defined in RouteGuard only")
    }
}
