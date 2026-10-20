package com.silencelen.huginn

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * ⚠⚠ THE APP LOCK HIDES THE APP; IT DOES NOT LEAVE IT.
 *
 * The lock takes the whole app tree out of composition, on purpose. Three things
 * went with it, every time the phone came back from a pocket:
 *
 *  * THE PLACE. rememberSaveable state comes back after an activity rebuild, not
 *    after a subtree re-enters composition, so every unlock landed on the
 *    sessions list with the open screen, tab and scroll gone.
 *  * THE WORK. The open screen's teardown took the lock for the reader leaving:
 *    the staged photo was thrown away, and a chat just created and not yet
 *    written in was deleted on the host.
 *  * THE TARGET, twice over. A notification, share or widget target stood for
 *    the life of the activity, so every unlock ran it again, back to something
 *    dealt with hours before; and one that arrived while the lock was up lived
 *    only in a field, so a fold behind the unlock prompt lost it.
 *
 * There is no compose-ui-test in this module and no instrumentation in this
 * build, so the decisions are held against the source, as `PhoneRoundThreeTest`
 * holds the rest of the shell.
 *
 * NOTE this module is on org.junit, whose argument order is (message, expected,
 * actual) — the REVERSE of the kotlin.test order `:core` and `:ui` use.
 */
class AppLockPlaceTest {

    private fun root(): File =
        generateSequence(File("").absoluteFile) { it.parentFile }
            .firstOrNull { File(it, "settings.gradle.kts").isFile }
            ?: error("cannot find the gradle root from ${File("").absolutePath}")

    private fun mainActivity(): String {
        val rel = "app/src/main/kotlin/com/silencelen/huginn/MainActivity.kt"
        val f = File(rel).takeIf { it.isAbsolute } ?: File(root(), rel)
        assertTrue("$rel not found at ${f.absolutePath}", f.isFile)
        val text = f.readText()
        // A grep that matches nothing exits 0. Assert the floor before the finding.
        assertTrue("$rel read as ${text.length} chars — wrong file", text.length > 2_000)
        return text
    }

    private fun slice(text: String, from: String, to: String, max: Int): String {
        val s = text.substringAfter(from, "").substringBefore(to, "")
        assertTrue("'$from' read as ${s.length} chars — wrong slice", s.length in 1..max)
        return s
    }

    private fun content() = slice(mainActivity(), "setContent {", "override fun onStart()", 12_000)

    // ------------------------------------------------------------- the place

    @Test
    fun `the app's saved state is held while the lock is up`() {
        val content = content()
        val holder = content.indexOf("rememberSaveableStateHolder()")
        val branch = content.indexOf("if (locked.value)")
        val provider = content.indexOf("SaveableStateProvider(")
        val app = content.indexOf("HuginnApp(")
        assertTrue("nothing holds the app's saved state across the lock", holder >= 0 && provider >= 0)
        assertTrue("the holder sits inside the branch the lock replaces, and goes with it", holder < branch)
        assertTrue("the app is not inside the held state", provider < app)
    }

    // -------------------------------------------------------------- the work

    @Test
    fun `a teardown under the lock keeps the staged photo and the new chat`() {
        val text = mainActivity()
        val stillHere = slice(text, "private fun rememberStillHere()", "\n}\n", 600)
        assertTrue("the lock still reads as the reader leaving", "lockShowing" in stillHere)
        assertTrue(
            "the tell is not this activity's own lock",
            "internal val lockShowing: Boolean get() = locked.value" in text,
        )
    }

    // ------------------------------------------------------------ the target

    @Test
    fun `a target is acted on once, not on every unlock`() {
        val text = mainActivity()
        val effect = slice(text, "LaunchedEffect(target) {", "val chats by vm.chats", 6_000)
        assertTrue("the target is never handed back once acted on", "onTargetHandled(t)" in effect)
        assertTrue(
            "the cold-start restore can override a target that has landed",
            "restored = true" in effect,
        )
        assertTrue(
            "the activity does not take a handled target down, or takes down a newer one",
            "if (openTarget.value?.seq == handled.seq) openTarget.value = null" in content(),
        )
    }

    @Test
    fun `a target the lock kept waiting survives a rebuild, every field of it`() {
        val text = mainActivity()
        assertTrue(
            "a restored activity drops a target that was never acted on",
            "else restoreTarget(savedInstanceState)" in slice(text, "override fun onCreate(", "setContent {", 6_000),
        )
        val save = slice(text, "override fun onSaveInstanceState(outState: Bundle)", "private fun restoreTarget(", 2_000)
        val restore = slice(text, "private fun restoreTarget(saved: Bundle)", "override fun onStart()", 2_000)
        // Every field, so a field added to OpenTarget later cannot be lost here
        // without this failing.
        val fields = OpenTarget::class.java.declaredFields
            .filterNot { java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .map { it.name }
        assertTrue("OpenTarget read as $fields — wrong class", "shareImage" in fields && fields.size >= 7)
        for (name in fields) {
            assertTrue("the saved target drops $name", "\"$name\"" in save)
            assertTrue("the restored target drops $name", "\"$name\"" in restore)
        }
    }
}
