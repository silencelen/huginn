package com.silencelen.huginn.desktop.update

/**
 * Just enough semver to answer one question: is the version on the feed newer
 * than the one running?
 *
 * Deliberately not a full semver implementation. The only versions this ever
 * compares are ones the release script wrote out of `version.txt`, and a
 * dependency (or a hand-rolled 200-line parser) for `MAJOR.MINOR.PATCH` would be
 * more code than the thing it decides.
 *
 * The comparison is NUMERIC per component, which is the entire reason this
 * exists: `"0.10.0" > "0.9.0"` is false as strings and true as versions, and
 * a string compare here means the app stops updating the moment a minor number
 * reaches ten — silently, and looking perfectly healthy.
 */
object Semver {

    /**
     * `1.2.3` or `1.2.3-something`. The pre-release tail is captured but only
     * used to break a tie (see [compare]); build metadata after `+` is dropped,
     * as semver says it must be ignored for precedence.
     */
    private val RE = Regex("""^(\d+)\.(\d+)\.(\d+)(?:-([0-9A-Za-z.-]+))?(?:\+[0-9A-Za-z.-]+)?$""")

    data class Parsed(val major: Int, val minor: Int, val patch: Int, val pre: String?)

    /** Null when [text] is not a version — a malformed feed must not read as an update. */
    fun parse(text: String): Parsed? {
        val m = RE.matchEntire(text.trim()) ?: return null
        return Parsed(
            major = m.groupValues[1].toIntOrNull() ?: return null,
            minor = m.groupValues[2].toIntOrNull() ?: return null,
            patch = m.groupValues[3].toIntOrNull() ?: return null,
            pre = m.groupValues[4].takeIf { it.isNotEmpty() },
        )
    }

    /**
     * Negative / zero / positive, as [Comparator] does. Throws on an unparseable
     * input rather than guessing: the callers that matter ([isNewer]) turn that
     * into "no update", and a comparison that silently returned 0 for garbage
     * would make a corrupt feed indistinguishable from an up-to-date one.
     */
    fun compare(a: String, b: String): Int {
        val pa = parse(a) ?: throw IllegalArgumentException("not a version: $a")
        val pb = parse(b) ?: throw IllegalArgumentException("not a version: $b")
        if (pa.major != pb.major) return pa.major.compareTo(pb.major)
        if (pa.minor != pb.minor) return pa.minor.compareTo(pb.minor)
        if (pa.patch != pb.patch) return pa.patch.compareTo(pb.patch)
        // Same triple: a pre-release sorts BEFORE the release it leads to, so
        // 0.2.0-rc1 never counts as an update over 0.2.0.
        return when {
            pa.pre == null && pb.pre == null -> 0
            pa.pre == null -> 1
            pb.pre == null -> -1
            else -> pa.pre.compareTo(pb.pre)
        }
    }

    /**
     * True only when [candidate] is a well-formed version strictly newer than
     * [current]. Anything unparseable on either side is false — the updater's
     * failure mode has to be "does nothing", never "installs something".
     */
    fun isNewer(candidate: String, current: String): Boolean =
        runCatching { compare(candidate, current) > 0 }.getOrDefault(false)
}
