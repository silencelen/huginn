package com.silencelen.huginn.settings

/**
 * ONE search rule over [SettingsCatalog], shared by both shells so a query that
 * finds a setting on the desktop finds the same setting on the phone.
 *
 * Pure: no Compose import, no state, no clock. That is what makes the rule
 * assertable, and the rule is the part that goes wrong — a search that is
 * "obviously right" is how you end up with a fuzzy matcher that ranks *Ladder*
 * above *Token* for "tok".
 *
 * THE RULE, in one paragraph. Lowercase the query and collapse its whitespace.
 * Every TERM must prefix-match some token in the item's title, its keywords, or
 * its category's title — AND across terms so a second word narrows rather than
 * widens, OR across fields so it does not matter which of the three carries the
 * word. Rank by how little help the match needed: the title exactly, then the
 * title alone, then a keyword, then the category; ties stay in catalog order, so
 * the list never reshuffles between two queries that matched the same way.
 *
 * ⚠ AN UNAVAILABLE ITEM NEVER RETURNS. A hit that opens a category onto a row
 * that is not drawn there is worse than no hit: it teaches the reader that the
 * search lies, and they stop using it. The same probe that hides the row hides
 * the hit.
 *
 * BLANK IS NOT A WILDCARD. An empty query returns nothing, because the category
 * list underneath it is already the answer to "show me everything".
 */
object SettingsSearch {

    /** How many rows a result list is worth. Past this it is a second index. */
    const val DEFAULT_LIMIT: Int = 12

    /**
     * Why a match ranked where it did. Lower sorts first; the names are the
     * reason rather than the number, because the number is meaningless in a
     * failure message.
     */
    object Rank {
        /** The title IS the query. */
        const val EXACT_TITLE: Int = 0
        /** The title alone carried every term. */
        const val TITLE: Int = 1
        /** A keyword was needed. */
        const val KEYWORD: Int = 2
        /** Only the category title carried a term. */
        const val CATEGORY: Int = 3
    }

    /** One result: the setting, the drawer to open, and why it ranked there. */
    data class Hit(val item: SettingsItem, val category: SettingsCategory, val rank: Int)

    /** Lowercase, collapse runs of whitespace, trim. The whole normalisation. */
    fun normalise(query: String): String =
        query.lowercase().split(' ', '\t', '\n', '\r').filter { it.isNotEmpty() }.joinToString(" ")

    /**
     * Words, from anything. Split on everything that is not a letter or digit,
     * so "Base URL" and "base-url" and "Base/URL" all yield the same two.
     */
    private fun tokens(text: String): List<String> =
        text.lowercase()
            .split(*NON_WORD)
            .filter { it.isNotEmpty() }

    private val NON_WORD: CharArray = charArrayOf(
        ' ', '\t', '\n', '\r', '-', '_', '.', ',', '/', '\\', ':', ';', '(', ')', '[', ']',
        '{', '}', '\'', '"', '?', '!', '&', '+', '*', '=', '%', '#', '@', '<', '>', '|', '`', '~',
    )

    /** Every term prefix-matches at least one of [pool]. AND across terms. */
    private fun allTermsHit(terms: List<String>, pool: List<String>): Boolean =
        terms.all { term -> pool.any { it.startsWith(term) } }

    /**
     * The matching settings, best first.
     *
     * @param query what was typed. Blank returns empty.
     * @param probe the live world; anything it hides is excluded.
     * @param surface the shell asking.
     * @param limit how many to return. [DEFAULT_LIMIT] unless a caller has a
     *   reason, and no caller has had one yet.
     */
    fun hits(
        query: String,
        probe: SettingsProbe,
        surface: Surface,
        limit: Int = DEFAULT_LIMIT,
    ): List<Hit> {
        val normalised = normalise(query)
        if (normalised.isEmpty() || limit <= 0) return emptyList()
        val terms = normalised.split(' ')

        val out = ArrayList<Hit>()
        for (category in SettingsCatalog.categories) {
            if (!category.surface.matches(surface)) continue
            val categoryTokens = tokens(category.title)
            for (item in category.items) {
                if (!item.surface.matches(surface)) continue
                if (!item.availability(probe)) continue

                val titleTokens = tokens(item.title)
                val keywordTokens = item.keywords.flatMap { tokens(it) }

                val rank = when {
                    normalise(item.title) == normalised -> Rank.EXACT_TITLE
                    allTermsHit(terms, titleTokens) -> Rank.TITLE
                    allTermsHit(terms, titleTokens + keywordTokens) -> Rank.KEYWORD
                    allTermsHit(terms, titleTokens + keywordTokens + categoryTokens) -> Rank.CATEGORY
                    else -> continue
                }
                out += Hit(item, category, rank)
            }
        }

        // sortedBy is STABLE in the standard library, so catalog order survives
        // inside a rank — which is the whole reason the ranks are coarse.
        return out.sortedBy { it.rank }.take(limit)
    }
}
