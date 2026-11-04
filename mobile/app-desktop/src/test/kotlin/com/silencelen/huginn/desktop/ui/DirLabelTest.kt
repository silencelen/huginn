package com.silencelen.huginn.desktop.ui

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * The Recent list's label rule: whole when it fits, otherwise cut in the MIDDLE,
 * keeping the first segment and as many of the last as there is room for. The
 * dialog drawing it is [RecentDirsMenuTest].
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class DirLabelTest {

    // The pair the dropdown drew as one row on desktop 2.10.0, in placeholder form.
    private val a = "/root/projects/example/assets/src/voice/models/aspen-large"
    private val b = "/root/projects/example/assets/src/voice/models/aspen-small"

    @Test
    fun `a path that fits is left alone`() {
        assertEquals("/root/netplan", DirLabel.fit("/root/netplan", 36))
        assertEquals(a, DirLabel.fit(a, a.length))
        assertEquals("/root/netplan", DirLabel.fit("  /root/netplan  ", 36), "trimmed like the field trims it")
    }

    @Test
    fun `a long path keeps its first segment and its last ones`() {
        assertEquals("/root/…/assets/src/voice/models/aspen-large", DirLabel.fit(a, 44))
        assertEquals("/root/…/src/voice/models/aspen-large", DirLabel.fit(a, 36))
        assertEquals("/root/…/aspen-large", DirLabel.fit(a, 20))
    }

    @Test
    fun `two paths that differ only at the end stay different`() {
        for (budget in listOf(16, 20, 28, 36, 44)) {
            val la = DirLabel.fit(a, budget)
            val lb = DirLabel.fit(b, budget)
            assertNotEquals(la, lb, "at $budget characters")
            assertTrue(la.endsWith("aspen-large") || la.endsWith("large"), "$la at $budget lost its end")
        }
    }

    @Test
    fun `the first segment goes before the last one does, and a long last one keeps its end`() {
        assertEquals("…/aspen-large", DirLabel.fit(a, 14))
        assertEquals("…large", DirLabel.fit(a, 6))
        assertEquals("…/a-very-long-final-directory-name", DirLabel.fit("/srv/a-very-long-final-directory-name", 34))
        assertEquals("…directory-name", DirLabel.fit("/srv/a-very-long-final-directory-name", 15))
    }

    @Test
    fun `no label is longer than its budget once the shortest form fits`() {
        val paths = listOf(a, b, "/root/netplan", "/x", "/", "relative/dir/with/parts", "~/projects/one/two/three", "/srv/a/b")
        for (p in paths) {
            val shortest = DirLabel.forms(p).last().length
            for (budget in 1..64) {
                val label = DirLabel.fit(p, budget)
                assertTrue(label.length <= maxOf(budget, shortest), "\"$p\" at $budget came out as \"$label\"")
            }
        }
    }

    @Test
    fun `the forms are longest first and every one is the path or a cut of it`() {
        val forms = DirLabel.forms(a).toList()
        assertEquals(a, forms.first())
        assertEquals(forms.sortedByDescending { it.length }, forms, "a shorter form came before a longer one")
        for (f in forms.drop(1)) {
            assertTrue(DirLabel.ELLIPSIS in f, "\"$f\" is shorter but does not say it was cut")
            assertTrue(a.endsWith(f.substringAfterLast(DirLabel.ELLIPSIS)), "\"$f\" is not the end of the path")
        }
    }

    @Test
    fun `a measured fit takes the longest form the room holds`() {
        // The menu measures in pixels; any predicate works the same way.
        val label = DirLabel.fit(a) { it.length <= 30 }
        assertEquals(DirLabel.fit(a, 30), label)
        assertEquals("…e", DirLabel.fit(a) { false }, "nothing fits: the shortest form there is")
    }
}
