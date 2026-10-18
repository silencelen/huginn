package com.silencelen.huginn

import com.silencelen.huginn.data.DaemonTls

/**
 * Runs [block] with `HUGINN_APPD_PLAINTEXT=local` in force ([on]) or not, and
 * puts the switch back afterwards, so a developer's environment cannot change
 * what a loopback test means and one test cannot leak the switch into another.
 */
internal inline fun <T> withPlaintextLoopback(on: Boolean = true, block: () -> T): T {
    val was = DaemonTls.plaintextLoopback
    DaemonTls.plaintextLoopback = on
    try {
        return block()
    } finally {
        DaemonTls.plaintextLoopback = was
    }
}
