package com.silencelen.huginn.ui

/**
 * Where one character, as a person sees it, ends and the next begins: Unicode's
 * extended grapheme clusters (UAX #29), as far as a phone keyboard types them.
 *
 * Live typing needs this because a Backspace is a CHARACTER, not a UTF-16 unit.
 * A keyboard deletes an emoji whole, Claude Code's prompt steps back one grapheme
 * per Backspace, and 👍🏽 is four units — so counting units sent four Backspaces
 * and erased three characters nobody deleted.
 *
 * Covered: surrogate pairs; combining and enclosing marks, variation selectors,
 * ZWNJ and the rest of Extend; spacing marks; emoji ZWJ sequences, skin tones,
 * keycaps and tag flags; regional-indicator flags; CR LF. Not covered, each
 * falling back to a cluster per code point: Hangul conjoining jamo (keyboards
 * commit whole syllables), Prepend, the Unicode 15.1 Indic conjunct rule, and
 * combining marks outside the BMP — common Kotlin has no category for those.
 * Pure rather than a platform segmenter on purpose: the JDK's before 20 does not
 * know emoji sequences, and a rule that differs between the phone and the tests
 * run against it is a rule nobody has tested.
 */
internal object Graphemes {

    /** The offsets at which each cluster of [s] starts, then `s.length`. */
    fun boundaries(s: String): List<Int> {
        val out = mutableListOf(0)
        var prev = Kind.OTHER
        // UAX #29's GB11: ExtPict Extend* ZWJ × ExtPict. [pict] is "the text so
        // far ends in ExtPict Extend*", [pictZwj] "...and then a ZWJ".
        var pict = false
        var pictZwj = false
        // Regional indicators pair up into flags (GB12/13), so how many came just
        // before decides whether the next one starts a new flag.
        var ri = 0
        var i = 0
        while (i < s.length) {
            val cp = codePointAt(s, i)
            val kind = kindOf(cp)
            if (i > 0 && breaksBefore(prev, kind, pictZwj, ri)) out += i
            pictZwj = pict && kind == Kind.ZWJ
            pict = kind == Kind.PICT || (pict && kind == Kind.EXTEND)
            ri = if (kind == Kind.RI) ri + 1 else 0
            prev = kind
            i += if (cp > 0xFFFF) 2 else 1
        }
        if (s.isNotEmpty()) out += s.length
        return out
    }

    /** Where the last cluster of [s] starts: what one Backspace removes. */
    fun lastStart(s: String): Int = boundaries(s).let { if (it.size < 2) 0 else it[it.size - 2] }

    private enum class Kind { CR, LF, CONTROL, EXTEND, ZWJ, SPACING_MARK, RI, PICT, OTHER }

    private fun breaksBefore(prev: Kind, next: Kind, pictZwj: Boolean, ri: Int): Boolean = when {
        prev == Kind.CR && next == Kind.LF -> false
        prev == Kind.CR || prev == Kind.LF || prev == Kind.CONTROL -> true
        next == Kind.CR || next == Kind.LF || next == Kind.CONTROL -> true
        next == Kind.EXTEND || next == Kind.ZWJ || next == Kind.SPACING_MARK -> false
        pictZwj && next == Kind.PICT -> false
        prev == Kind.RI && next == Kind.RI -> ri % 2 == 0
        else -> true
    }

    private fun kindOf(cp: Int): Kind = when {
        cp == 0x0D -> Kind.CR
        cp == 0x0A -> Kind.LF
        cp == 0x200D -> Kind.ZWJ
        cp == 0x200C -> Kind.EXTEND // ZWNJ: a format character that extends
        cp in 0x1F1E6..0x1F1FF -> Kind.RI
        cp in 0x1F3FB..0x1F3FF -> Kind.EXTEND // skin tones
        cp in 0xE0020..0xE007F -> Kind.EXTEND // tags, as in the subdivision flags
        cp in 0xE0100..0xE01EF -> Kind.EXTEND // variation selectors 17-256
        cp > 0xFFFF -> if (pictographic(cp)) Kind.PICT else Kind.OTHER
        else -> when (Char(cp).category) {
            // Includes VS16 (U+FE0F) and the keycap (U+20E3).
            CharCategory.NON_SPACING_MARK, CharCategory.ENCLOSING_MARK -> Kind.EXTEND
            CharCategory.COMBINING_SPACING_MARK -> Kind.SPACING_MARK
            // The live field's zero-width-space sentinel is one of these, and so
            // is its own cluster: nothing typed after it joins it.
            CharCategory.CONTROL, CharCategory.FORMAT, CharCategory.LINE_SEPARATOR,
            CharCategory.PARAGRAPH_SEPARATOR, CharCategory.SURROGATE -> Kind.CONTROL
            else -> if (pictographic(cp)) Kind.PICT else Kind.OTHER
        }
    }

    /**
     * Extended_Pictographic, by the blocks emoji live in rather than the exact
     * list: it only decides whether a ZWJ joins two symbols, and a ZWJ between
     * two non-emoji symbols from these blocks is not something anyone types.
     */
    private fun pictographic(cp: Int): Boolean =
        cp in 0x1F000..0x1FAFF || cp in 0x1FC00..0x1FFFD ||
            cp in 0x2600..0x27BF || cp in 0x2B00..0x2BFF ||
            cp in 0x2190..0x21FF || cp in 0x2300..0x23FF || cp in 0x2900..0x297F ||
            cp in 0x25A0..0x25FF || cp == 0x00A9 || cp == 0x00AE || cp == 0x203C ||
            cp == 0x2049 || cp == 0x2122 || cp == 0x2139 || cp == 0x24C2 ||
            cp == 0x3030 || cp == 0x303D || cp == 0x3297 || cp == 0x3299

    private fun codePointAt(s: String, i: Int): Int {
        val hi = s[i]
        if (hi.isHighSurrogate() && i + 1 < s.length) {
            val lo = s[i + 1]
            if (lo.isLowSurrogate()) return ((hi.code - 0xD800) shl 10) + (lo.code - 0xDC00) + 0x10000
        }
        return hi.code
    }
}
