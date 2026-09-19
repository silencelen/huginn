package com.silencelen.huginn.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.silencelen.huginn.data.Account
import com.silencelen.huginn.data.Autoswitch
import com.silencelen.huginn.data.LoginSession
import com.silencelen.huginn.data.LoginState
import com.silencelen.huginn.data.SavedAccount
import com.silencelen.huginn.ui.HeadroomRules
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

/**
 * The saved Claude logins on the host, and the three-step flow that adds one.
 *
 * MOVED HERE FROM THE DESKTOP, LOGIC UNCHANGED (`SettingsView.kt:380-651`). It
 * already carried the discipline the phone's own `SavedAccountRow` did not — the
 * state dot instead of a row tint, the freshness word only when it is not
 * `fresh`, the daemon's own sentence rather than a hopeful one — so the phone
 * gets that by calling this rather than by having it copied into it.
 *
 * THE SIGN-IN CANNOT HAPPEN IN THIS PROCESS. The daemon runs `claude` on huginn,
 * the browser step is Anthropic's, and the code comes back through the daemon's
 * login session. So all this does is start it, open the URL, carry the pasted
 * code back — and REPORT THE OUTCOME HONESTLY. Duplicate and mismatch are the
 * two answers a hopeful UI hides, and both matter: a duplicate means the switch
 * you are about to make changes nothing, and a mismatch means the token now
 * saved belongs to somebody other than the account you were adding.
 *
 * WHY AN IO INTERFACE RATHER THAN A DOZEN CALLBACKS. The vocabulary — which
 * status word means what, which 409 is worth showing verbatim — is the part
 * that must not be duplicated, and it can only live here if this file sees the
 * daemon's answers rather than pre-composed strings. So the shells hand over
 * their client and nothing else.
 */
interface AccountsIo {
    suspend fun account(): Account

    /** With the weekly plan figure: it is what says which login to switch to. */
    suspend fun savedAccounts(): List<SavedAccount>
    suspend fun autoswitch(): Autoswitch

    /** Answers a STATUS WORD, not a boolean — see [HeadroomRules.refreshWords]. */
    suspend fun refreshAccount(slug: String): String
    suspend fun activateAccount(slug: String)
    suspend fun forgetAccount(slug: String)
    suspend fun startLogin(email: String?): LoginSession
    suspend fun submitLoginCode(code: String): LoginState
}

/**
 * @param io the shell's client, as the small surface this editor needs.
 * @param openLink hands a URL to the platform's browser; false if there is none
 *   to hand it to, which changes what the reader is told to do next.
 * @param showAutoswitch the "autoswitch on · N accounts · last: …" line. True
 *   preserves today's desktop exactly; the redesign moves that sentence into
 *   *Usage & headroom*, where the threshold and margin are also editable, so a
 *   shell that has done its half passes false rather than rendering it twice.
 */
@Composable
fun AccountsEditor(
    io: AccountsIo,
    openLink: (String) -> Boolean,
    modifier: Modifier = Modifier,
    showAutoswitch: Boolean = true,
    intro: String? = "Saved Claude logins on the host. The active one serves every chat and session.",
) {
    val scope = rememberCoroutineScope()
    var current by remember { mutableStateOf<Account?>(null) }
    var saved by remember { mutableStateOf<List<SavedAccount>>(emptyList()) }
    var autoswitch by remember { mutableStateOf<Autoswitch?>(null) }
    var loaded by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }

    var loginEmail by remember { mutableStateOf("") }
    var loginUrl by remember { mutableStateOf<String?>(null) }
    var loginCode by remember { mutableStateOf("") }
    var loginNote by remember { mutableStateOf<String?>(null) }
    var forgetting by remember { mutableStateOf<SavedAccount?>(null) }

    suspend fun reload() {
        runCatching { io.account() }.onSuccess { current = it }
        runCatching { io.savedAccounts() }.onSuccess { saved = it }
        runCatching { io.autoswitch() }.onSuccess { autoswitch = it }
        loaded = true
    }

    LaunchedEffect(Unit) { reload() }

    // Cap before fill.
    Column(modifier.widthIn(max = SETTINGS_READING_WIDTH).fillMaxWidth()) {
        intro?.let { EditorNote(it, maxLines = 2) }

        val who = current
        Text(
            when {
                !loaded -> "Loading…"
                who == null || !who.loggedIn -> "Signed in: nobody"
                else -> "Signed in: ${who.email ?: "unknown"}" + (who.subscriptionType?.let { " · $it" } ?: "")
            },
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 8.dp),
        )
        if (showAutoswitch) {
            EditorNote(autoswitchLine(autoswitch), Modifier.padding(top = 2.dp), maxLines = 2)
        }

        saved.forEach { a ->
            Row(
                // Cap before fill — the other order hands this row fixed
                // constraints and the cap can only coerce into them. Measured at
                // 1338px on the desktop before the cap was put in front.
                Modifier.widthIn(max = 760.dp).fillMaxWidth().padding(top = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                // A dot, not a row tint or an accent bar: "active" is one bit and
                // it reads at a glance in the same vernacular as the rail's
                // liveness.
                SettingsStateDot(
                    if (a.isActive) MaterialTheme.colorScheme.primary
                    else MaterialTheme.colorScheme.outlineVariant,
                )
                Column(Modifier.weight(1f)) {
                    Text(
                        buildString {
                            append(a.email ?: a.slug)
                            if (!a.verified) append(" (unconfirmed)")
                            if (a.duplicateOf) append(" (duplicate)")
                        },
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = if (a.isActive) FontWeight.SemiBold else FontWeight.Normal,
                    )
                    val bits = listOfNotNull(
                        a.weeklyPercent?.let { "${it.roundToInt()}% of week" },
                        a.subscriptionType,
                        // `fresh` is omitted: it is the ordinary state, and a word
                        // on every row for the case that needs no attention is how
                        // the one row that DOES need it stops standing out. Null —
                        // an older daemon — says nothing rather than guessing.
                        a.freshness?.takeIf { it != "fresh" },
                        if (a.isActive) "active" else null,
                    )
                    if (bits.isNotEmpty()) EditorNote(bits.joinToString(" · "))
                }
                // A token that has expired but whose REFRESH token has not is one
                // request away from working. Offered only for that state:
                // `unrefreshable` needs a re-login and `fresh` needs nothing, and
                // a button that is always there teaches nothing.
                if (a.freshness == "expired") {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                // The daemon answers with a STATUS WORD, not a
                                // boolean — `refresh_token_expired`, `lock_busy`
                                // and `refresh_failed` want three different things
                                // from the reader — so it is shown verbatim. The
                                // one exception is `active_skipped`.
                                runCatching { io.refreshAccount(a.slug) }
                                    .fold(
                                        onSuccess = { word ->
                                            loginNote = "${a.email ?: a.slug}: ${HeadroomRules.refreshWords(word)}"
                                        },
                                        onFailure = { loginNote = it.message ?: "could not refresh" },
                                    )
                                reload()
                                busy = false
                            }
                        },
                    ) { Text("Refresh") }
                }
                if (!a.isActive) {
                    TextButton(
                        enabled = !busy,
                        onClick = {
                            scope.launch {
                                busy = true
                                runCatching { io.activateAccount(a.slug) }
                                    // THE DAEMON'S OWN SENTENCE, verbatim. A 409
                                    // here carries the reason — "its login expired
                                    // on <date> — sign in again" — and the string
                                    // this replaced ("could not switch") threw that
                                    // away and left the reader with the one
                                    // question they pressed the button to answer.
                                    .onFailure { loginNote = it.message ?: "could not switch" }
                                reload()
                                busy = false
                            }
                        },
                    ) { Text("Use") }
                }
                TextButton(enabled = !busy, onClick = { forgetting = a }) {
                    Text("Forget", color = MaterialTheme.colorScheme.error)
                }
            }
        }
        if (loaded && saved.isEmpty()) {
            EditorNote("No saved logins on the host yet.", Modifier.padding(top = 8.dp))
        }

        // The three steps, stated. A sign-in that leaves the app for a browser and
        // comes back through a paste is not self-evident, and the step marker is
        // the difference between "nothing happened" and "it is waiting for you".
        val step = if (loginUrl == null) 1 else 3
        EditorNote(
            "1 · Start sign-in    2 · Approve in the browser    3 · Paste the code" +
                "        (now: step $step)",
            Modifier.padding(top = 14.dp),
            // ⚠ THREE LINES, BECAUSE THE POINT OF THE SENTENCE IS AT ITS END.
            // `EditorNote` defaults to one, and at one line a phone cut this at
            // "3 · Paste the co…" — losing the step marker, which is the entire
            // difference between "nothing happened" and "it is waiting for you".
            // Same fix as the autoswitch note above it.
            maxLines = 3,
        )

        val pendingUrl = loginUrl
        if (pendingUrl == null) {
            FieldAndVerb {
                OutlinedTextField(
                    value = loginEmail,
                    onValueChange = { loginEmail = it },
                    label = { Text("email to add (optional)") },
                    singleLine = true,
                    modifier = Modifier.widthIn(min = 280.dp, max = 400.dp),
                )
                Button(
                    enabled = !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            loginNote = "Starting sign-in on the host…"
                            runCatching { io.startLogin(loginEmail.trim().ifBlank { null }) }
                                .onSuccess { s ->
                                    val link = s.url
                                    if (link.isNullOrBlank()) {
                                        loginNote = "The host did not produce a sign-in URL — check the login tmux session."
                                    } else {
                                        loginUrl = link
                                        loginNote = if (openLink(link)) {
                                            "Approve the sign-in in the browser, then paste the code here."
                                        } else {
                                            "No browser could be opened here — copy the link, approve it, then paste the code."
                                        }
                                    }
                                }
                                .onFailure { loginNote = it.message ?: "could not start sign-in" }
                            busy = false
                        }
                    },
                ) { Text("Add login") }
            }
            EditorNote(
                "Naming the account aims the authorize page at it; leave it blank to use whatever session the browser carries.",
                Modifier.padding(top = 4.dp),
                maxLines = 2,
            )
        } else {
            FieldAndVerb {
                OutlinedTextField(
                    value = loginCode,
                    onValueChange = { loginCode = it },
                    label = { Text("paste the code from the browser") },
                    singleLine = true,
                    modifier = Modifier.widthIn(min = 280.dp, max = 400.dp),
                )
                Button(
                    enabled = loginCode.isNotBlank() && !busy,
                    onClick = {
                        scope.launch {
                            busy = true
                            loginNote = "Checking…"
                            runCatching { io.submitLoginCode(loginCode.trim()) }
                                .onSuccess { s ->
                                    loginNote = when {
                                        s.duplicate ->
                                            "Already saved: ${s.email ?: "that account"} — the same login twice, so switching to it changes nothing."
                                        s.mismatch ->
                                            "Signed in as ${s.email ?: "someone else"}, not ${s.intendedEmail ?: "the intended account"}."
                                        s.done -> "Added ${s.email ?: "account"}."
                                        else -> s.message ?: "Still waiting on the host."
                                    }
                                    if (s.done) {
                                        loginUrl = null
                                        loginCode = ""
                                        loginEmail = ""
                                        reload()
                                    }
                                }
                                .onFailure { loginNote = it.message ?: "could not submit the code" }
                            busy = false
                        }
                    },
                ) { Text("Submit code") }
                TextButton(onClick = { loginUrl = null; loginCode = ""; loginNote = null }) { Text("Cancel") }
            }
            Row(
                Modifier.padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                EditorNote(pendingUrl, Modifier.weight(1f))
                val clipboard = LocalClipboardManager.current
                TextButton(onClick = { clipboard.setText(AnnotatedString(pendingUrl)) }) { Text("Copy link") }
                TextButton(onClick = { openLink(pendingUrl) }) { Text("Open again") }
            }
        }

        loginNote?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(top = 8.dp).widthIn(max = 760.dp),
            )
        }
    }

    val victim = forgetting
    if (victim != null) {
        AlertDialog(
            onDismissRequest = { forgetting = null },
            title = { Text("Forget saved login") },
            text = {
                Text(
                    "Remove ${victim.email ?: victim.slug} from the host's saved logins? " +
                        "Signing in again re-adds it.",
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    forgetting = null
                    scope.launch {
                        runCatching { io.forgetAccount(victim.slug) }
                            .onFailure { loginNote = it.message ?: "could not forget that login" }
                        reload()
                    }
                }) { Text("Forget", color = MaterialTheme.colorScheme.error) }
            },
            dismissButton = { TextButton(onClick = { forgetting = null }) { Text("Cancel") } },
        )
    }
}

/** The one sentence about rotation, until the shells move it into *Usage*. */
internal fun autoswitchLine(a: Autoswitch?): String {
    if (a == null) return "autoswitch: unknown"
    if (!a.enabled) return "autoswitch off — a login that runs out stays the active one"
    val last = a.last ?: return "autoswitch on · ${a.accounts} accounts · nothing switched yet"
    return "autoswitch on · ${a.accounts} accounts · last: ${last.fromEmail ?: "?"} (${last.fromPercent}%) → " +
        "${last.toEmail ?: "?"} (${last.toPercent}%)"
}

/**
 * A text field with its verb beside it — and, when there is no room beside it,
 * underneath it.
 *
 * ⚠ THE ROW THAT CANNOT SHRINK IS THE ONE THAT BREAKS. Each of these pairs is a
 * field with a `widthIn(min = 280.dp)` next to a Button, and a Row measures the
 * field first: at 420dp of window the field takes its minimum and the button is
 * handed the remainder, which is nothing. It does not disappear — it renders as
 * a 32px-wide stripe with one letter per line, which reads as a rendering bug
 * rather than as a window that is too narrow. "Add login" did exactly that.
 *
 * So the row becomes a COLUMN under the width where both fit, explicitly and
 * with a measured threshold, rather than by handing the problem to a flow
 * layout: the two controls are always a field and its verb, and which line the
 * verb sits on should be a decision this file makes.
 */
@Composable
private fun FieldAndVerb(top: Dp = 6.dp, content: @Composable () -> Unit) {
    BoxWithConstraints(Modifier.padding(top = top)) {
        if (maxWidth < FIELD_ROW_STACK_BELOW) {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) { content() }
        } else {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) { content() }
        }
    }
}

/**
 * Where a field and its button stop fitting on one line: the field's own 280dp
 * minimum, the 8dp gap, and the widest verb here ("Submit code"), with enough
 * left that the button is a button rather than a sliver.
 */
private val FIELD_ROW_STACK_BELOW = 420.dp
