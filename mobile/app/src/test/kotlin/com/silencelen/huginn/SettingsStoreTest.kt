package com.silencelen.huginn

import com.silencelen.huginn.data.lockEnabledOrLocked
import com.silencelen.huginn.data.orEmptyOnIoFailure
import androidx.datastore.core.CorruptionException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.FileNotFoundException
import java.io.IOException

/**
 * What the settings store does when the device cannot read it.
 *
 * The store itself needs a Context; the two rules that turn an unreadable file
 * from a permanent crash-on-launch into a recoverable one do not. This source
 * set has no Robolectric, which is why they are stated as functions rather than
 * left inline in the reader.
 */
class SettingsStoreTest {

    @Test
    fun `an unparseable store reads as empty rather than throwing`() = runTest {
        // CorruptionException is what a garbage .preferences_pb produces, and it
        // extends IOException — which is why the catch, not the corruption
        // handler, is the load-bearing half.
        val flow = flow<String> { throw CorruptionException("bad magic") }
        assertEquals(listOf("empty"), flow.orEmptyOnIoFailure("empty").toList())
    }

    @Test
    fun `an unreadable store path reads as empty too`() = runTest {
        // With the path itself unreadable, stores with and without a
        // ReplaceFileCorruptionHandler both throw the identical
        // FileNotFoundException — the handler catches CorruptionException only.
        val flow = flow<String> { throw FileNotFoundException("/data/.../huginn_settings.preferences_pb") }
        assertEquals("empty", flow.orEmptyOnIoFailure("empty").first())
    }

    @Test
    fun `a good store is untouched`() = runTest {
        assertEquals(listOf("a", "b"), flowOf("a", "b").orEmptyOnIoFailure("empty").toList())
    }

    @Test
    fun `anything that is not an IO failure still throws`() = runTest {
        val flow = flow<String> { throw IllegalStateException("a programming error") }
        assertThrows(IllegalStateException::class.java) {
            kotlinx.coroutines.runBlocking { flow.orEmptyOnIoFailure("empty").first() }
        }
    }

    @Test
    fun `the startup lock read fails CLOSED`() = runTest {
        assertTrue("an unreadable store must lock, not open", lockEnabledOrLocked { throw IOException() })
        assertTrue(lockEnabledOrLocked { true })
        assertEquals(false, lockEnabledOrLocked { false })
    }
}
