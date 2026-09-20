package com.silencelen.huginn.ui

import androidx.compose.animation.animateContentSize
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.silencelen.huginn.data.App
import com.silencelen.huginn.data.AppForm

/**
 * The things huginn makes and hosts itself, and how to open them.
 *
 * A reading surface: a list of addresses with an icon, a liveness mark and a
 * verdict about whether the device in your hand can reach them — and the row
 * IS the link, opened in the host's browser through whichever handoff the shell
 * owns. It is deliberately NOT the Devices surface it resembles: a device is
 * another machine that enrols and decides for itself what it will do; an app is
 * a URL. No shared key, no shared lifecycle, no shared security story.
 *
 * ⚠⚠ THE LIST-LEVEL APPROVAL CARD IS GONE (decision 55). One card carrying every
 * unit's rebind made a reader work out which two lines were theirs; the fix now
 * lives inline on the row that needs it, disclosed only when that row is
 * actually failing. What survives of the card is its one discipline — no button,
 * one Copy, and [AppRules.FIX_NEVER_RUN] saying who runs them.
 *
 * ⚠ EVERY ROW SAYS WHERE IT WAS SEEN FROM, until the retrofit has been applied.
 * A page bound to the host's own address is genuinely up and genuinely
 * unreachable from the phone in your hand, and a row that said "up" full stop
 * would be lying by omission to the one person who would then tap it.
 */
@Composable
fun AppsView(
    apps: List<App>,
    nowMs: Long,
    onOpen: (App) -> Unit,
    modifier: Modifier = Modifier,
    header: String? = "APPS",
    /** Whether the owner has run the retrofit, so the rows can drop the caveat. */
    retrofitApplied: Boolean = false,
    /** The one-line transition note, or null. Built by [AppRules.retrofitNote]. */
    note: String? = null,
    /** Null hides the control on a shell with nowhere to run a probe from. */
    onProbe: ((App) -> Unit)? = null,
    onEdit: ((App) -> Unit)? = null,
    /** Hands a failing row's fix lines to the shell's clipboard. */
    onCopyFix: ((String) -> Unit)? = null,
    /**
     * Whether THIS view owns the scroll.
     *
     * ⚠ EXACTLY ONE OWNER PER SHELL, and the default is "not me" because the
     * desktop's is already there. The phone hosted this column in a plain
     * `Box(fillMaxSize())` and the page could not scroll at all. The desktop pane
     * wraps the same view in `ReadingPane`, which IS a `verticalScroll`, so a
     * view that scrolled unconditionally would nest two of them there — which
     * Compose answers by swallowing the gesture rather than by throwing.
     */
    scroll: Boolean = false,
) {
    val scrollState = rememberScrollState()
    Column(modifier.fillMaxWidth().let { if (scroll) it.verticalScroll(scrollState) else it }) {
        if (header != null) {
            Text(
                header,
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, top = 6.dp, bottom = 2.dp),
            )
        }
        if (apps.isEmpty()) {
            Text(
                APPS_EMPTY,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, top = 4.dp, bottom = 12.dp),
            )
        } else {
            apps.forEach { a ->
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                AppRow(
                    app = a,
                    nowMs = nowMs,
                    retrofitApplied = retrofitApplied,
                    onOpen = { onOpen(a) },
                    onProbe = onProbe,
                    onEdit = onEdit,
                    onCopyFix = onCopyFix,
                )
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
        }
        // ⚠ ONE LINE, AND ONLY DURING THE TRANSITION. The commands that would
        // clear it are on the rows; this says the job as a whole is outstanding
        // and names the addresses the verdicts were measured against.
        note?.takeIf { it.isNotBlank() }?.let {
            Spacer(Modifier.height(8.dp))
            Text(
                it,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 10.dp),
            )
        }
    }
}

/**
 * One app: its icon, its name, what it is, whether it answers — and, when it
 * does not answer where your devices are, why and what to run.
 *
 * ⚠⚠ THE ROW IS THE LINK. Pressing anywhere on it hands the address to a
 * browser, so there is no Open verb; and a row whose address the rules refuse is
 * not pressable at all ([AppRules.openable]) rather than pressable and silently
 * inert. The verbs that remain — Why, Check now, Edit — are buttons inside it
 * and consume their own press.
 */
@Composable
fun AppRow(
    app: App,
    nowMs: Long,
    retrofitApplied: Boolean,
    onOpen: () -> Unit,
    onProbe: ((App) -> Unit)? = null,
    onEdit: ((App) -> Unit)? = null,
    onCopyFix: ((String) -> Unit)? = null,
) {
    var open by remember(app.id) { mutableStateOf(false) }
    val expandable = AppRules.expandable(app)
    val openable = AppRules.openable(app)
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(enabled = openable, onClick = onOpen)
            // Height only: the desktop's list pane re-measures on every frame of
            // a width animation. See DisclosureHeightOnlyTest.
            .animateContentSize()
            .padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AppIcon(app)
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    AppRules.label(app),
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.Medium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    AppRules.subtitle(app),
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            ReachDot(app)
        }
        Spacer(Modifier.height(2.dp))
        Text(
            AppRules.rowWords(app, nowMs, retrofitApplied),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.outline,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        Spacer(Modifier.height(2.dp))
        Row(horizontalArrangement = Arrangement.Start) {
            // ⚠ ONLY A ROW WITH SOMETHING TO SAY OPENS. On a row where
            // everything answered this would be an empty drawer with a chevron on
            // it — and the verb changes with the reason, because "Why" over an
            // explanation of a check that never ran reads as an accusation.
            if (expandable) RowVerb(if (open) "Hide" else AppRules.expandVerb(app)) { open = !open }
            onProbe?.let { probe -> RowVerb("Check now") { probe(app) } }
            onEdit?.let { edit -> RowVerb("Edit") { edit(app) } }
        }
        if (expandable && open) AppFixPanel(app, onCopyFix)
    }
}

/**
 * The failing row's disclosure: which address did not answer, why, and the exact
 * lines that would fix it.
 *
 * ⚠⚠ NO ACTION BUTTON, ON PURPOSE AND ON BOTH SIDES. There is no route that
 * applies this: the lines rebind a systemd unit on this host and sometimes add
 * firewall lines on a different machine entirely, and neither is a thing a
 * daemon or a phone has any business doing. The only control is COPY. Without
 * [AppRules.FIX_NEVER_RUN] a panel with one control reads as one somebody forgot
 * to finish, and somebody would eventually finish it — which is why that
 * sentence and the verbatim lines are both asserted by `AppFixCopyTest`.
 */
@Composable
private fun AppFixPanel(app: App, onCopy: ((String) -> Unit)?) {
    val lines = AppRules.fixLines(app)
    val addresses = app.reachable.addresses
    Spacer(Modifier.height(6.dp))
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(Modifier.padding(horizontal = 10.dp, vertical = 8.dp)) {
            if (addresses.isNotEmpty()) {
                Text(
                    "Checked from",
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = FontWeight.SemiBold,
                )
                Spacer(Modifier.height(2.dp))
                Column(verticalArrangement = Arrangement.spacedBy(1.dp)) {
                    addresses.forEach { addr ->
                        Text(
                            AppRules.addressWords(addr),
                            style = MaterialTheme.typography.labelSmall,
                            color = if (addr.ok) MaterialTheme.colorScheme.onSurfaceVariant
                            else MaterialTheme.colorScheme.error,
                            maxLines = 2,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            // ⚠ THE DAEMON'S OWN SENTENCE ABOUT THE CHECK, under the addresses it
            // is about. On a host with no usable bind address there are no
            // addresses and no fix — this line is the whole answer.
            AppRules.reachNote(app)?.let {
                if (addresses.isNotEmpty()) Spacer(Modifier.height(4.dp))
                Text(
                    it,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (lines.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                // Monospace, one per line, in the daemon's order — comment lines
                // included, exactly as they arrived. They are going to be read
                // and then typed.
                Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    lines.forEach {
                        Text(it, style = MaterialTheme.typography.labelSmall, fontFamily = FontFamily.Monospace)
                    }
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    AppRules.FIX_NEVER_RUN,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    // Three lines: the sentence ends in the instruction, and a rule
                    // cut before its own consequence is a rule that has not been
                    // stated.
                    maxLines = 3,
                )
                // The ONLY control. See the KDoc.
                if (onCopy != null) {
                    Row(horizontalArrangement = Arrangement.Start) {
                        RowVerb("Copy fix") { onCopy(AppRules.fixText(app)) }
                    }
                }
            }
        }
    }
}

/**
 * The app's own favicon, or a letter.
 *
 * ⚠ THE TILE IS NOT A PLACEHOLDER FOR A PENDING FETCH — it is the answer when
 * [AppRules.hasIcon] is false, and no request is made at all. The daemon already
 * told us there is none; asking anyway spends a round trip that can only 404,
 * once per row, on a list that recycles.
 */
@Composable
private fun AppIcon(app: App) {
    val loader = LocalAttachmentImages.current
    // ⚠ `iconStamp`, NOT `version`. The row's revision only moves when somebody
    // EDITS the row; the daemon re-fetches the favicon on probe, hourly, with
    // nobody touching it — so a site that changed its icon kept serving the old
    // picture until the app was restarted. See AttachmentImageLoader.loadIcon.
    var bitmap by remember(app.id, app.iconStamp) { mutableStateOf<ImageBitmap?>(null) }
    val wanted = AppRules.hasIcon(app) && loader != null
    LaunchedEffect(app.id, app.iconStamp, wanted) {
        bitmap = if (wanted) loader.loadIcon(app.id, app.iconStamp) else null
    }
    val shape = RoundedCornerShape(6.dp)
    val shot = bitmap
    if (shot != null) {
        Image(
            bitmap = shot,
            contentDescription = null,
            contentScale = ContentScale.Fit,
            modifier = Modifier.size(24.dp),
        )
        return
    }
    // The letter, from the label rather than the name: a row with no name shows
    // the first letter of whatever it actually leads with.
    Surface(
        color = MaterialTheme.colorScheme.surfaceContainerHighest,
        shape = shape,
        modifier = Modifier.size(24.dp),
    ) {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Text(
                AppRules.iconInitial(app),
                style = MaterialTheme.typography.labelMedium,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/**
 * Apps as the Status screen carries them: a card, not a destination.
 *
 * The phone's bottom bar deliberately stays at four, and a list of URLs is a
 * thing you read rather than a place you work — so on the phone this is where
 * Apps lives (decision 48). The desktop uses the full [AppsView] in a pane of
 * its own.
 */
@Composable
fun AppsStatusCard(
    apps: List<App>,
    nowMs: Long,
    onOpen: (App) -> Unit,
    modifier: Modifier = Modifier,
    /** Whether the retrofit has been applied, so the rows' words can drop the caveat. */
    retrofitApplied: Boolean = false,
    /** Opens the full list. Null on a shell that has no such destination. */
    onSeeAll: (() -> Unit)? = null,
) {
    if (apps.isEmpty()) return
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().padding(start = 14.dp, end = 6.dp, top = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "APPS",
                style = MaterialTheme.typography.labelSmall,
                fontWeight = FontWeight.Medium,
                letterSpacing = 1.2.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (onSeeAll != null) {
                TextButton(onClick = onSeeAll) {
                    Text("All", style = MaterialTheme.typography.labelMedium, maxLines = 1)
                }
            }
        }
        Text(
            appsStatusWords(apps),
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 14.dp, end = 14.dp, bottom = 2.dp),
        )
        apps.forEach { a ->
            Row(
                Modifier
                    .fillMaxWidth()
                    .clickable(enabled = AppRules.openable(a)) { onOpen(a) }
                    .padding(start = 14.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                AppIcon(a)
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        AppRules.label(a),
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        AppRules.rowWords(a, nowMs, retrofitApplied),
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.outline,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                ReachDot(a)
            }
        }
    }
}

/**
 * The card's one-line summary.
 *
 * ⚠ THREE COUNTS, AND NONE OF THEM FOLDS INTO ANOTHER. "3 of 4 up" with the
 * fourth never probed is a number that sends somebody to restart a healthy
 * service — and after a daemon restart that is EVERY row, because probe state
 * lives in memory. "Needs retrofit" is a separate fact again: the app answers
 * perfectly well, just not where you are.
 */
fun appsStatusWords(apps: List<App>): String {
    if (apps.isEmpty()) return "none listed"
    val up = apps.count { AppRules.reach(it) == AppRules.Reach.UP }
    val retrofit = apps.count { AppRules.deviceReach(it) == AppRules.DeviceReach.RETROFIT }
    val unknown = apps.count { AppRules.reach(it) == AppRules.Reach.UNKNOWN }
    val parts = mutableListOf("$up of ${apps.size} up")
    if (retrofit > 0) parts += "$retrofit ${if (retrofit == 1) "needs" else "need"} retrofit"
    if (unknown > 0) parts += "$unknown not checked"
    return parts.joinToString(" · ")
}

/**
 * The liveness mark. UNKNOWN draws a hole, not a grey dot that reads as "down".
 *
 * ⚠⚠ AMBER IS FOR ATTENTION, AND "UP" IS NOT ATTENTION (P-32). Every app at
 * `HTTP 200 · reachable` carried a rune-gold dot, because `primary` is this
 * app's one accent and it is what a running session, a live lane and a selected
 * settings row are drawn in. Four healthy rows in the colour of "look at this"
 * is a page that reads as four warnings — and then the row that genuinely wants
 * a person ("up, but not from where you are") had nothing left to say it with.
 *
 * So the accent now belongs to the row that needs somebody, and a page where
 * everything answers is a quiet page. The calm mark is `onSurfaceVariant` — the
 * same muted ink the row's own status line is drawn in, visible enough to be
 * told apart from the UNKNOWN hole, which `outline` (the [SettledDot] tone) is
 * not at 8 dp against this background.
 *
 * ⚠ AND NOT `tertiary`, which is what the retrofit case used to draw. The theme
 * defines neither tertiary nor its container, so that dot was Material's own
 * baseline pink in a warm rune-gold palette — [PaletteTest]'s rule ("a role the
 * theme never chose is not a colour anyone picked"), caught one role short.
 */
@Composable
private fun ReachDot(app: App) {
    val colour = when {
        AppRules.reach(app) == AppRules.Reach.DOWN -> MaterialTheme.colorScheme.error
        // ⚠ UP BUT NOT WHERE YOU ARE IS NOT UP, to the person holding the phone.
        // This is the one row on the page that wants the accent.
        AppRules.deviceReach(app) == AppRules.DeviceReach.RETROFIT -> MaterialTheme.colorScheme.primary
        AppRules.reach(app) == AppRules.Reach.UP -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> null
    }
    if (colour == null) {
        Spacer(Modifier.size(8.dp))
        return
    }
    Surface(color = colour, shape = CircleShape, modifier = Modifier.size(8.dp)) {}
}

@Composable
internal fun RowVerb(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        contentPadding = PaddingValues(horizontal = 8.dp, vertical = 2.dp),
        modifier = Modifier.heightIn(min = 30.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** What an empty apps list says, so nothing reads as broken. */
const val APPS_EMPTY: String =
    "No apps listed. An app is something huginn makes and hosts itself — the registry is a " +
        "file on the host, and each row is probed both from there and from the addresses your " +
        "devices arrive on, because a device that can reach this page has to be able to reach it."
