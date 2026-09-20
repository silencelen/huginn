package com.silencelen.huginn.ui

/**
 * The one line a map block on the Overview is labelled with.
 *
 * ⚠ A TABLE IS NOT A SENTENCE. The daemon labels a turn with the FIRST LINE of
 * what the model said (`sessiongraph.js`: `label: clip(turn.firstText, 80)`),
 * which is the right rule for prose and the wrong one for the one block Claude
 * writes that has no first sentence at all. A reply that opens with a markdown
 * table therefore labelled its turn **`| Host | Role |`** — twice on the review's
 * screen — and a reader scanning the map for "what happened in this turn" got
 * pipes and a column heading.
 *
 * Client-side rather than daemon-side on purpose: the label is already clipped
 * to 80 characters by the time it travels, every stored graph on disk carries the
 * old shape, and this is a rendering question about a line that has ALREADY been
 * decided. The daemon stays the author; this is the reader's spelling of it.
 *
 * ⚠ AND IT NEVER CLAIMS A ROW COUNT. The block label carries one line, so "table,
 * 6 rows" would be a number invented from a line that does not contain it. The
 * header cells ARE in the line and are what a person would use to recognise the
 * table, so they are what is said.
 */
object BlockLabel {

    /** What a table block is called when its header says nothing. */
    const val BARE: String = "table"

    /** How many header cells are named before the rest become an ellipsis. */
    const val MAX_CELLS: Int = 4

    /**
     * The label to draw: the daemon's own words, or a table said in words.
     *
     * Everything that is not a pipe-table row is returned untouched — including
     * prose that merely contains a pipe, which is most shell pipelines.
     */
    fun words(label: String): String {
        val cells = tableCells(label) ?: return label
        val named = cells.filter { it.isNotEmpty() }
        if (named.isEmpty()) return BARE
        val head = named.take(MAX_CELLS).joinToString(", ")
        return if (named.size > MAX_CELLS) "$BARE: $head, …" else "$BARE: $head"
    }

    /**
     * The cells of a GFM pipe-table row, or null when this line is not one.
     *
     * A row is recognised the way the renderer's own parser recognises one: the
     * line is fenced by pipes and holds at least two cells. The alignment rule
     * (`|---|:--:|`) is a table row whose every cell is a run of dashes and
     * colons — it parses to cells that are dropped as empty, which is why it
     * lands on [BARE] rather than on "table: ---".
     *
     * `\|` is an escaped pipe INSIDE a cell, not a separator; it is the one piece
     * of GFM escaping that can change where a cell ends.
     */
    fun tableCells(line: String): List<String>? {
        val s = line.trim()
        if (s.length < 3 || !s.startsWith("|") || !s.endsWith("|")) return null
        val cells = splitCells(s)
        if (cells.size < 2) return null
        return cells.map { cell -> if (RULE.matches(cell)) "" else cell }
    }

    /** The inside of the fence, split on unescaped pipes. */
    private fun splitCells(row: String): List<String> {
        val inner = row.substring(1, row.length - 1)
        val out = ArrayList<String>()
        val cell = StringBuilder()
        var i = 0
        while (i < inner.length) {
            val c = inner[i]
            when {
                c == '\\' && i + 1 < inner.length && inner[i + 1] == '|' -> { cell.append('|'); i += 2 }
                c == '|' -> { out.add(cell.toString().trim()); cell.clear(); i++ }
                else -> { cell.append(c); i++ }
            }
        }
        out.add(cell.toString().trim())
        return out
    }

    /** An alignment cell: dashes and colons and nothing else. */
    private val RULE = Regex("^:?-{1,}:?$")
}
