package com.silencelen.huginn.desktop.ui.settings

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.Device
import com.silencelen.huginn.data.Headroom
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.data.Status
import com.silencelen.huginn.desktop.AppStore
import com.silencelen.huginn.desktop.update.UpdateState
import com.silencelen.huginn.settings.SettingsProbe
import com.silencelen.huginn.settings.SettingsSearch
import com.silencelen.huginn.ui.HeadroomRules
import com.silencelen.huginn.ui.groupByMachine

/**
 * What the Settings list pane knows about the live world — the answers behind
 * every category's one-line summary, and behind the question "does this drawer
 * exist on this host at all".
 *
 * A PLAIN DATA CLASS, filled by the shell from flows it already collects, so the
 * two decisions that used to be made inline in a 1200-line scroll are now
 * functions with inputs: [desktopProbe] decides what is REACHABLE and
 * [SettingsSummaries] decides what each drawer SAYS. Both are pure and both are
 * asserted, which is the only way a summary line that quietly reads the wrong
 * number gets caught — it is drawn at 11sp under a title, where nobody proofreads
 * it, and "2 saved logins" over one login never throws.
 */
data class SettingsFacts(
    val account: Account? = null,
    val savedAccounts: List<SavedAccount> = emptyList(),
    val status: Status? = null,
    val headroom: Headroom? = null,
    val devices: List<Device> = emptyList(),
    val route: String = "",
    val tokenSet: Boolean = false,
    /** This window has been attended recently — the other half of "claiming". */
    val present: Boolean = false,
    val notifyEnabled: Boolean = true,
    val closeToTray: Boolean = true,
    /**
     * Whether this machine HAS a system tray. `Main.kt` has always consulted it
     * (`if (closeToTray && isTraySupported) hide else quit()`); the summary and
     * the row copy did not, so a box with no tray read "close to tray on" over a
     * window that quits when you close it.
     */
    val traySupported: Boolean = true,
    val deviceEnabled: Boolean = false,
    val update: UpdateState = UpdateState.Idle,
    val installedVersion: String = "",
)

/**
 * What this shell can reach, asked of the live world.
 *
 * ⚠ THE DAEMON IS THE GATE FOR MOST OF IT, and deliberately: quick actions, the
 * headroom tier, the fleet and the local-serve enrolment are all things the HOST
 * holds, so a client that drew their rows against a dead or pre-3.0 daemon would
 * be offering controls whose Save can only 404 — the "category that opens onto an
 * apology" the redesign exists to prevent.
 *
 * ⚠ AND THE LOCAL DRAWERS ARE NOT GATED ON IT, which is the honest half. Close to
 * tray, the keyboard sheet, the log path, `Copy diagnostics` and the updater are
 * facts about THIS computer, and the moment they matter most is the moment the
 * daemon is the thing that is broken. So an unreachable host leaves five drawers
 * — host, privacy, appearance, updates, about — rather than the catalog's
 * all-false three, because an all-false probe is not the same thing as this
 * shell's probe against a dead daemon.
 */
fun desktopProbe(f: SettingsFacts): SettingsProbe {
    // ONE question behind four fields: has this host answered at all. A client
    // that asked four different ways would show three quarters of a drawer while
    // the fourth quarter 404s.
    val hostAnswers = f.status != null
    return SettingsProbe(
        headroom = f.headroom != null,
        quickActions = f.status?.quickActions != null,
        alerts = hostAnswers,
        // The local-AI door enrols this machine AT the daemon (`LocalServe.enable`
        // takes the base URL and the token), so there is nothing to set up
        // against a host that is not there.
        localServe = hostAnswers,
        // App lock is the phone's device credential. There is no desktop half and
        // inventing one is a feature, not a reorganisation.
        appLockAvailable = false,
        enrolable = hostAnswers,
        savedAccounts = f.savedAccounts.size,
        diagnostics = true,
        selfUpdate = true,
    )
}

/**
 * The live one-liner under each category title.
 *
 * WHY THE SHELL OWNS THESE and the catalog does not: the catalog says which
 * drawers exist, and the shell is where the account, the percentage and the
 * machine count actually are. Null means "say nothing" — an empty line under a
 * title is worse than none, and a zero invented to fill it is worse than either.
 */
object SettingsSummaries {

    /**
     * Past this many characters an address is cut to its local part.
     *
     * A 280dp list row ellipsises from the END, which eats the DOMAIN — the half
     * that says which household this is. `jacob@…` keeps the half that identifies.
     */
    const val SHORT_EMAIL_MAX: Int = 18

    /** `jacob@monahanhosting.com` in a 280dp list row: `jacob@…`. */
    fun shortEmail(email: String?): String? {
        val raw = email?.trim()?.takeIf { it.isNotEmpty() } ?: return null
        if (raw.length <= SHORT_EMAIL_MAX) return raw
        val at = raw.indexOf('@')
        if (at <= 0) return raw.take(SHORT_EMAIL_MAX) + "…"
        return raw.take(at + 1) + "…"
    }

    fun of(categoryId: String, f: SettingsFacts): String? = when (categoryId) {
        "host" -> join(
            shortEmail(f.account?.email?.takeIf { f.account.loggedIn }),
            countWords(f.savedAccounts.size, "saved login"),
        )

        "usage" -> {
            val h = f.headroom ?: return null
            val worst = h.worst?.let {
                HeadroomRules.windowWords(it.label) + " " + HeadroomRules.percentWords(it.percent)
            }
            // A ladder of one family has nothing to step DOWN to, which is the
            // whole point of the row — so it reads as absent rather than as set.
            val ladder = h.settings?.let { if (it.ladder.size >= 2) "ladder set" else "no ladder" }
            join(worst, ladder)
        }

        "chats" -> {
            val q = f.status?.quickActions ?: return null
            val set = listOf(q.explain, q.execute, q.askInNewChat, q.quote).count { it.isNotBlank() }
            countWords(set, "quick action")
        }

        // Why the claim is off is the half a reader needs: "off" is also what a
        // bug looks like, and the two reasons it can be off are different facts.
        "notify" -> when {
            !f.notifyEnabled -> "off · Telegram"
            f.present -> "claiming · this window"
            else -> "not claiming · Telegram stays live"
        }

        "devices" -> {
            // MACHINES, not credential rows: a box that both runs work and serves
            // local models holds two enrolments and is one device to the person
            // reading this line. Same count as the rail badge.
            val groups = groupByMachine(f.devices)
            if (groups.isEmpty()) "no machines yet"
            else join(
                countWords(groups.size, "machine"),
                groups.count { it.serving.isNotEmpty() }.takeIf { it > 0 }?.let { "$it serving" },
            )
        }

        "privacy" -> if (f.tokenSet) "token saved here" else "no token saved"

        // ⚠ THE SETTING IS NOT THE BEHAVIOUR. With no tray there is nothing to
        // close TO, whatever the toggle says, and the app already behaves that way
        // — only this line and the row beneath it claimed otherwise.
        "appearance" -> when {
            !f.traySupported -> "closing quits (no system tray here)"
            f.closeToTray -> "close to tray on"
            else -> "closing quits"
        }

        "updates" -> join(f.installedVersion.takeIf { it.isNotBlank() }, updateWords(f.update))

        "about" -> join(
            f.installedVersion.takeIf { it.isNotBlank() },
            f.status?.appdVersion?.takeIf { it.isNotBlank() }?.let { "appd $it" },
        )

        else -> null
    }

    /** The updater's state in three or four words, never its whole sentence. */
    private fun updateWords(state: UpdateState): String = when (state) {
        UpdateState.Idle -> "not checked"
        UpdateState.Checking -> "checking…"
        is UpdateState.UpToDate -> "up to date"
        is UpdateState.Downloading -> "downloading ${state.version}…"
        is UpdateState.Ready -> "${state.version} ready to install"
        is UpdateState.Error -> "check failed"
    }

    /** `2 saved logins`, `1 saved login`, and nothing at all for none. */
    private fun countWords(n: Int, noun: String): String? =
        if (n <= 0) null else "$n $noun${if (n == 1) "" else "s"}"

    /** The list's own separator, dropping whatever there is nothing to say about. */
    private fun join(vararg parts: String?): String? =
        parts.filterNot { it.isNullOrBlank() }.joinToString(" · ").takeIf { it.isNotEmpty() }
}

/**
 * The two Settings panes' shared answer to "which drawer, and what is marked".
 *
 * IT LIVES ABOVE BOTH PANES because on this shell they are two separate call
 * sites in the frame — the list rides the animated, clipped pane behind the seam,
 * the page rides the detail column — and a selection held in either one would be
 * a selection the other could not see.
 *
 * The selected category is written through to
 * [com.silencelen.huginn.desktop.DesktopSettings] so it survives a restart; the
 * ARRIVAL MARK is not, because it is a property of how a drawer was reached
 * rather than of the drawer, and a highlight restored from disk would mark a row
 * nobody searched for.
 */
@Stable
class SettingsPaneState(initial: String?, private val persist: (String) -> Unit) {

    var selected: String? by mutableStateOf(initial?.takeIf { it.isNotBlank() })
        private set

    /** What is typed in the search field. Not persisted: a query is not a setting. */
    var query: String by mutableStateOf("")

    private var highlight: String? by mutableStateOf(null)
    private var highlightFor: String? by mutableStateOf(null)

    /**
     * The two facts the list needs that nothing else in this app already
     * collects: who is signed in, and how many logins the host has saved.
     *
     * Held here rather than fetched per pane because the list and the page are
     * two compositions of the same screen, and asking the daemon the same
     * question twice per frame is how a summary line becomes a poll.
     */
    var account: Account? by mutableStateOf(null)
        private set
    var savedAccounts: List<SavedAccount> by mutableStateOf(emptyList())
        private set

    /**
     * Asks once, when Settings opens. FAILURE IS SILENT AND SEPARATE per call:
     * a host that cannot answer `/v1/account` leaves the summary shorter, which
     * is the correct outcome — the connection fields on the *Host* page are
     * where an unreachable daemon is reported, and a second red line under a
     * category title would be noise on the screen you go to in order to fix it.
     */
    suspend fun loadAccounts(store: AppStore) {
        runCatching { store.client.account() }.onSuccess { account = it }
        // Without the plan figure: this is a COUNT, and `withPlan` makes the
        // daemon read every saved profile's usage to answer it.
        runCatching { store.client.savedAccounts() }.onSuccess { savedAccounts = it }
    }

    /** A drawer opened from the list. Clears any arrival mark. */
    fun open(categoryId: String) {
        highlight = null
        highlightFor = null
        select(categoryId)
    }

    /**
     * A drawer opened from a search hit, with the row that matched marked.
     *
     * The pair comes from [com.silencelen.huginn.ui.settings.SettingsScaffoldRules.open]
     * rather than from two reads here, so marking a row in a category that does
     * not hold it stays unrepresentable.
     */
    fun openHit(hit: SettingsSearch.Hit) {
        highlight = hit.item.id
        highlightFor = hit.category.id
        select(hit.category.id)
    }

    /** The row to mark in [categoryId], or null when this is not the arrival. */
    fun markFor(categoryId: String): String? =
        if (highlightFor != null && highlightFor == categoryId) highlight else null

    private fun select(categoryId: String) {
        selected = categoryId
        persist(categoryId)
    }
}
