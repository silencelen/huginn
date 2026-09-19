package com.silencelen.huginn.ui

import com.silencelen.huginn.data.Device

/**
 * What a MACHINE is, as opposed to what a credential row is.
 *
 * ⚠⚠ ONE REACHABILITY RULE, because there were two and they disagreed on screen.
 * A box that both runs claude work and serves local models holds two enrolments
 * on purpose — authority is decided per row, and the serving credential must
 * never gain claude reach — and the two rows go offline independently: a serving
 * row beats from a service that is always up, a claude row only while somebody
 * has the client open. The Devices card read its state off the claude row and
 * said "not reachable · last seen yesterday"; the rail counted `rows.any {
 * it.online }` and said "4 devices · 4 reachable". Same machine, same second.
 *
 * In `:core` rather than beside either drawing of it, for the ordinary reason:
 * both shells and three surfaces ask this question, and a rule that lives at a
 * call site is a rule that exists once per call site.
 */
object DeviceRules {

    /** The rows that decide — the machine's claude enrolment, not its serving one. */
    private fun deciding(rows: List<Device>): List<Device> {
        val claude = rows.filter { it.scope != SERVING_SCOPE }
        // A box that ONLY serves has no claude row, and calling it permanently
        // offline would be a different lie: there its serving row is all there is.
        return claude.ifEmpty { rows }
    }

    /** The wire word for a serving enrolment. Display and grouping only — see [Device.scope]. */
    const val SERVING_SCOPE: String = "generate"

    /**
     * Could huginn reach this machine right now?
     *
     * The question a person is asking when they look at a fleet list is "could
     * something run there", so the claude row answers it and the `-llm` row does
     * not get a vote — except on a machine that has nothing else.
     */
    fun reachable(rows: List<Device>): Boolean = deciding(rows).any { it.online }
}
