package com.silencelen.huginn.data

import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope

/**
 * The upgrade from a store written before the route book existed. Nothing else:
 * routes are the user's own list ([RouteBook]), and this app ships with no
 * address of its own.
 */
object AppdRoutes {
    /**
     * The id a migrated address gets. Fixed rather than minted so the migration
     * table is a table — the same input gives the same book.
     */
    const val MIGRATED_ID: String = "custom"

    /**
     * The `addedAt` a migrated pin gets: ZERO, meaning "it was already here".
     *
     * Not the clock, and deliberately. The phone's store has no open-and-upgrade
     * moment — DataStore is a flow of preferences — so [migrate] runs on every
     * READ until the first save writes the book down. A wall-clock timestamp
     * would make two reads of the same unchanged bytes produce two different
     * books, which breaks flow equality and would have every collector rebuild
     * on every emission.
     */
    const val MIGRATED_AT: Long = 0

    fun normalize(url: String): String = url.trim().trimEnd('/')

    /**
     * Turns what an older install stored into a route book, with no user action.
     *
     * | stored | becomes |
     * |---|---|
     * | `base_url` the guard allows | the only pin, active, named after its own host |
     * | `base_url` the guard refuses | NOT pinned: carried back as [RouteBook.droppedUrl], nothing active |
     * | `base_url` absent (fresh install) | an EMPTY book; the first address saved becomes pin #1 |
     * | `appd_route_pinned` = true | `autoSwitch = false` — the same refusal to move, under its new name |
     *
     * An upgrade never ADDS an address the user did not have. Builds before this
     * one appended two addresses compiled into the app; an address belongs in a
     * person's list only when that person put it there.
     */
    fun migrate(storedBaseUrl: String?, routePinned: Boolean, now: Long = MIGRATED_AT): RouteBook {
        // The guard's normalize, not this file's: `192.168.1.20:8787` (the old
        // client prepended the scheme) and `HTTP://…` were storable and worked.
        val stored = storedBaseUrl?.let { RouteGuard.normalize(it) }?.takeIf { it.isNotBlank() }
            ?: return RouteBook(autoSwitch = !routePinned)

        // The old "Base URL" was free text with no validation, so an address the
        // guard refuses could be stored and could even have worked. It is named,
        // never silently replaced, and nothing becomes active in its place.
        if (!RouteGuard.isAllowed(stored)) {
            return RouteBook(activeId = null, autoSwitch = !routePinned, droppedUrl = stored).normalized()
        }
        val pin = PinnedRoute(
            id = MIGRATED_ID,
            name = RouteBook.defaultName(stored),
            url = stored,
            kind = RouteGuard.kindOf(stored),
            addedAt = now,
        )
        return RouteBook(routes = listOf(pin), activeId = pin.id, autoSwitch = !routePinned).normalized()
    }
}

/**
 * Which pinned route this client should be talking to.
 *
 * ORDERED PREFERENCE, NOT FASTEST. The owner's list IS the preference, and the
 * first healthy route in it wins. Ranking by round-trip time is what makes a
 * client flap between two paths that both work, which is the failure the old
 * "current route first" comment existed to prevent and which gets worse, not
 * better, as the list grows.
 *
 * Pure but for the injected [probe], so the ordering, the hysteresis and the
 * short-circuit are unit-testable without a network.
 */
object RouteResolver {

    /**
     * How long a route that answered keeps the connection even when a route
     * above it in the list also answers.
     *
     * A switch is a reconnect: the watch stream drops, the poll restarts, and
     * every open pane refetches. Paying that to move from a working path to
     * a marginally-preferred one is a bad trade, so the active route only loses
     * its place by FAILING.
     */
    const val HYSTERESIS_MS: Long = 60_000

    /**
     * Consecutive failed calls on the active route before the client looks for
     * another one. Three rather than one because a single timeout is ordinary —
     * a phone changing cells, a laptop waking — and re-resolving on each of them
     * would turn a blip into a reconnect.
     */
    const val FAILURES_BEFORE_REPROBE: Int = 3

    /** What a resolution decided. Each case is a different thing to tell the reader. */
    sealed interface Choice {
        /** Nothing is pinned yet. The connect flow, not an error. */
        data object Empty : Choice

        /** The owner pinned this by hand; auto-switching was not consulted. */
        data class Pinned(val route: PinnedRoute) : Choice

        /**
         * The client is not moving. [route] is where it stays — which is what
         * every caller has always printed — and the subclass says why.
         *
         * ⚠ SEALED RATHER THAN A SECOND TOP-LEVEL CASE, on purpose. [Candidate]
         * is new, and a brand-new `Choice` branch would break the exhaustive
         * `when` in both shells; nested under [Stay] the shells keep compiling
         * and keep doing the SAFE thing (nothing) until they grow a branch for
         * it. A client that never learns about candidates is a client that never
         * auto-adopts one, which is the whole point.
         */
        sealed class Stay(val route: PinnedRoute) : Choice {

            /** The active route is still the right one. */
            class Here(route: PinnedRoute) : Stay(route)

            /**
             * Another route answered, and this client will not take it without a
             * person saying so: it is a plain-http address nobody added by hand
             * (see [PinnedRoute.byHand]), and moving there means handing that
             * host the daemon's bearer in cleartext.
             *
             * @param route where the connection stays in the meantime.
             * @param candidate the route that answered, to be offered.
             */
            class Candidate(route: PinnedRoute, val candidate: PinnedRoute) : Stay(route)
        }

        /** A different route answered and the active one did not. */
        data class Switched(val route: PinnedRoute) : Choice

        /**
         * Nothing answered. ⚠ The caller must LEAVE THE SETTING ALONE — blanking
         * it turns "no connectivity" into "this app looks unconfigured".
         */
        data object NoRoute : Choice
    }

    data class Outcome(val choice: Choice, val health: Map<String, RouteHealth>)

    /**
     * @param health the per-route cache from the last resolution, carried in and
     *   handed back updated. Used for the state dots and for the hysteresis
     *   above — never to rank.
     * @param force a manual "find the live route": probes even when the active
     *   route answered moments ago, so the dots refresh. It still cannot move
     *   off a healthy active route, which is what makes the answer *"Still on
     *   X"* rather than a surprise reconnect.
     * @param probe true when HUGINN **PROVED** it is at that URL — not merely
     *   that something answered, and since decision 58 not merely that something
     *   answered in huginn's shape either. `HuginnClient.probe` is that answer:
     *   it is the token-proving challenge, because a probe that counts any HTTP
     *   responder is how a stranger on the LAN gets preferred over a live daemon
     *   (kcore #59/#78) and a probe that counts a forgeable header is how one
     *   gets handed the bearer 129 times in two minutes (D-1).
     */
    suspend fun resolve(
        book: RouteBook,
        health: Map<String, RouteHealth> = emptyMap(),
        now: Long = 0,
        force: Boolean = false,
        probe: suspend (PinnedRoute) -> Boolean,
    ): Outcome = resolveProving(book, health, now, force) {
        if (probe(it)) RouteProof.PROVEN else RouteProof.NONE
    }

    /**
     * What one probe learned. Three answers, because [ANSWERED] is a real state
     * and is NOT a degree of success.
     */
    enum class RouteProof {
        /** Nothing, or somebody else's web page. */
        NONE,

        /**
         * Something huginn-SHAPED is there and did not prove it holds the token:
         * a daemon older than 3.6.0, or an impostor. Treated as a failure for
         * every purpose except the sentence on the row.
         */
        ANSWERED,

        /** The challenge matched. The only answer that may move the bearer. */
        PROVEN,
    }

    /**
     * The same resolution, with the probe allowed to distinguish "nothing there"
     * from "something there that cannot prove itself" (decision 58).
     *
     * ⚠ ONLY [RouteProof.PROVEN] COUNTS AS HEALTHY. The extra state changes what
     * a row SAYS and nothing about what is chosen — an address this client cannot
     * identify is an address it will not hand the bearer to, whether the reason is
     * an old daemon or a fake, because from here those are the same bytes.
     */
    suspend fun resolveProving(
        book: RouteBook,
        health: Map<String, RouteHealth> = emptyMap(),
        now: Long = 0,
        force: Boolean = false,
        probe: suspend (PinnedRoute) -> RouteProof,
    ): Outcome {
        if (book.routes.isEmpty()) return Outcome(Choice.Empty, health)
        val active = book.active
        if (!book.autoSwitch) {
            val pinned = active ?: return Outcome(Choice.Empty, health)
            // ⚠ REFUSING TO MOVE IS NOT REFUSING TO LOOK. A pinned book used to
            // return here before `force` was read, so "Find live route" probed
            // NOTHING: the dots and the "last reached" line froze for the life of
            // the pin — "never tried" for a hand-made pin, a stale green for a
            // route that has since died. A manual ask sweeps and reports; the pin
            // is still the answer.
            if (!force) return Outcome(Choice.Pinned(pinned), health)
            return Outcome(Choice.Pinned(pinned), sweep(book, health, now, probe).first)
        }

        val activeWasFresh = active != null && isFresh(health[active.id], now)
        // The cheap half of the hysteresis: a route that answered seconds ago is
        // not re-interrogated on every start, only on a manual ask.
        if (!force && active != null && activeWasFresh) return Outcome(Choice.Stay.Here(active), health)

        val (next, answered) = sweep(book, health, now, probe)
        val healthy = book.routes.filter { it.id in answered }
        if (healthy.isEmpty()) return Outcome(Choice.NoRoute, next)

        // The expensive half of the hysteresis: a route that has been working
        // keeps the connection even when something above it in the list answers.
        // A route that has just come back from the dead does NOT — after a real
        // outage the list settles back to the owner's preference.
        if (active != null && activeWasFresh && healthy.any { it.id == active.id }) {
            return Outcome(Choice.Stay.Here(active), next)
        }

        val pick = healthy.first()
        if (pick.id == active?.id) return Outcome(Choice.Stay.Here(pick), next)
        // ⚠ A MOVE HANDS THE NEXT REQUEST'S BEARER TO THAT HOST. Over https the
        // host stops mattering (RouteGuard's rule); over plain http it matters
        // entirely, and a route nobody typed — the built-ins an upgrade seeds,
        // whose literals are in a public repo — is not evidence of anything. The
        // connection stays where it is and the answer NAMES the route, for a
        // person to adopt once.
        if (!adoptable(pick)) {
            return Outcome(Choice.Stay.Candidate(route = active ?: pick, candidate = pick), next)
        }
        return Outcome(Choice.Switched(pick), next)
    }

    /**
     * Probes every pin and folds the answers into the health map.
     *
     * ONE budget for the whole list, not one per route. Sequentially probing
     * eight pins at three seconds each is twenty-four seconds of a dead app
     * before it tries the address that works.
     *
     * @return the updated health map and the ids that answered.
     */
    private suspend fun sweep(
        book: RouteBook,
        health: Map<String, RouteHealth>,
        now: Long,
        probe: suspend (PinnedRoute) -> RouteProof,
    ): Pair<Map<String, RouteHealth>, Set<String>> {
        val results = coroutineScope {
            val started = book.routes.map { route ->
                async {
                    val began = kotlin.time.TimeSource.Monotonic.markNow()
                    val proof = probe(route)
                    route.id to (proof to began.elapsedNow().inWholeMilliseconds)
                }
            }
            started.map { it.await() }
        }.toMap()

        val next = health.toMutableMap()
        for ((id, r) in results) {
            val (proof, rtt) = r
            val was = next[id] ?: RouteHealth()
            next[id] = when (proof) {
                RouteProof.PROVEN -> was.copy(lastOkAt = now, lastRttMs = rtt)
                // ⚠ A FAILURE *AND* A NOTE. Both stamps, because everything that
                // chooses reads `lastFailAt` and only the row reads the other.
                RouteProof.ANSWERED -> was.copy(lastFailAt = now, lastUnprovenAt = now)
                RouteProof.NONE -> was.copy(lastFailAt = now)
            }
        }
        return next to results.filterValues { it.first == RouteProof.PROVEN }.keys
    }

    /**
     * Records that ORDINARY traffic just worked on [id].
     *
     * ⚠ THE ROW'S "LAST REACHED" HAD ONE WRITER AND IT WAS [resolve]. Nothing
     * else ever touched the health map, so a route the client had been talking
     * to since breakfast still read "never reached" until somebody pressed Find
     * live route — the one line on that page whose whole job is to say which
     * path is working.
     *
     * ⚠⚠ AND IT WRITES [RouteHealth.lastSeenAt], NEVER `lastOkAt`. See that
     * field: `lastOkAt` feeds the hysteresis, and feeding it from traffic would
     * silently disable the three-failures re-probe.
     *
     * A no-op when there is nothing to record — no active route, no clock — and
     * when the witness on file is already newer, so a late callback cannot move
     * the clock backwards.
     */
    fun touch(health: Map<String, RouteHealth>, id: String?, now: Long): Map<String, RouteHealth> {
        if (id.isNullOrBlank() || now <= 0) return health
        val was = health[id] ?: RouteHealth()
        if (was.lastSeenAt >= now) return health
        return health + (id to was.copy(lastSeenAt = now))
    }

    /** Whether this client may move to [route] without being told to. */
    private fun adoptable(route: PinnedRoute): Boolean =
        route.byHand || route.url.trim().startsWith("https://", ignoreCase = true)

    private fun isFresh(h: RouteHealth?, now: Long): Boolean =
        h != null && h.lastOkAt > 0 && now - h.lastOkAt in 0 until HYSTERESIS_MS
}
