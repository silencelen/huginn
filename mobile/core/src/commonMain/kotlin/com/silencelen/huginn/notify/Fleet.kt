package com.silencelen.huginn.notify

import com.silencelen.huginn.data.Watch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One session as a glanceable surface shows it: the name and the state word the
 * host reported ("running" / "attention" / "idle", null for a pane with no
 * Claude in it).
 */
@Serializable
data class FleetSession(val name: String, val state: String? = null)

/**
 * The fleet at one observation, reduced to what fits on a home-screen widget.
 *
 * Derived from a [Watch] rather than fetched separately: the watch digest is the
 * one shape every observation path already produces, so a widget fed from it can
 * never disagree with the notifications about what the fleet looks like.
 */
@Serializable
data class FleetSnapshot(
    /** Ranked: needing-you first, then working, then everything else. */
    val sessions: List<FleetSession> = emptyList(),
    val chatsRunning: Int = 0,
    /** Device wall-clock at the observation, so staleness is displayable. */
    val asOf: Long = 0,
) {
    val attention: Int get() = sessions.count { it.state == Fleet.ATTENTION }
    val running: Int get() = sessions.count { it.state == Fleet.RUNNING }
    val quiet: Int get() = sessions.size - attention - running
}

/**
 * What one observation means to a glanceable surface — the rules, with no
 * Android and no I/O, in the same spirit as [WatchCycle]. The Android widget
 * renders this; a desktop overview can render the same thing.
 */
object Fleet {
    const val ATTENTION = "attention"
    const val RUNNING = "running"

    /**
     * A stored snapshot may be read by a NEWER app than the one that wrote it
     * (the widget renders before the first post-update observation lands), so
     * unknown fields must never make yesterday's cache unreadable.
     */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * Ranks the fleet the way a glance wants it: sessions that stopped and are
     * waiting on a person come first, then the ones actually working, then the
     * quiet remainder — alphabetical within each band so the order holds still
     * between observations instead of reshuffling under the reader's finger.
     */
    fun snapshot(watch: Watch, atMs: Long): FleetSnapshot {
        val ranked = watch.sessions.entries
            .map { FleetSession(it.key, it.value) }
            .sortedWith(
                compareBy(
                    { band(it.state) },
                    { it.name.lowercase() },
                )
            )
        return FleetSnapshot(
            sessions = ranked,
            chatsRunning = watch.chats.count { it.value.running },
            asOf = atMs,
        )
    }

    private fun band(state: String?): Int = when (state) {
        ATTENTION -> 0
        RUNNING -> 1
        else -> 2
    }

    fun encode(snapshot: FleetSnapshot): String =
        json.encodeToString(FleetSnapshot.serializer(), snapshot)

    /** Null for absent or unparseable — the widget shows "no data yet", never crashes. */
    fun decode(encoded: String?): FleetSnapshot? {
        if (encoded.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(FleetSnapshot.serializer(), encoded) }.getOrNull()
    }
}
