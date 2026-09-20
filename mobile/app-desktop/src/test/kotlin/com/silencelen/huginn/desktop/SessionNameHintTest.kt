package com.silencelen.huginn.desktop

import com.silencelen.huginn.desktop.ui.SESSION_NAME_HELP
import com.silencelen.huginn.ui.SessionNameRules
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * ⚠ TWO SHELLS, ONE RULE, SAID ONCE.
 *
 * P-24 moved the phone's New-session hint onto [SessionNameRules.HINT] because
 * the old wording forbade the dash the daemon has always allowed. The desktop
 * kept its own sentence — *"Letters, digits, _ and - ; starts with a letter or
 * digit."* — which is a THIRD account of one rule: it names the characters as
 * symbols rather than words and says nothing about the 50-character limit that
 * `NAME_RE` really does enforce.
 *
 * Two hints for one daemon rule drift the moment either is edited, and the drift
 * is invisible until somebody is refused a name one client said was fine.
 *
 * NOTE kotlin.test's argument order is (expected, actual, message).
 */
class SessionNameHintTest {

    @Test
    fun `both shells quote the host's rule from the same place`() {
        assertEquals(
            SessionNameRules.HINT,
            SESSION_NAME_HELP,
            "the desktop is explaining the naming rule in its own words again",
        )
    }
}
