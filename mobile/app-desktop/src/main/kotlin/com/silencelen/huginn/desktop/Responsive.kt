package com.silencelen.huginn.desktop

/**
 * How wide the window actually is, and what the frame does about it.
 *
 * THE DESKTOP HAD NO ANSWER TO THIS AT ALL, which is the single root under most
 * of what the 2026-09-15 design audit found. The list pane was a constant
 * ([Splitter.DEFAULT], from persisted state), so a window snapped to half a
 * screen gave 320dp of its 420 to a list and left the detail pane 40dp wide: one
 * letter per line down the whole window, a clipped tab strip, and a composer
 * text field measuring 35px between two buttons that would not yield. `Ctrl+B`
 * made the app perfectly usable — the app simply never did it itself.
 *
 * The phone has had the answer since the Fold shipped: `MainActivity` asks one
 * `BoxWithConstraints` whether it is at least 700dp across and lays out two panes
 * or one. The numbers here are that same breakpoint, arrived at from the outside,
 * so a desktop window narrowed to a phone's width gets the phone's answer — which
 * is exactly the runtime question `ADDING-A-FEATURE.md` says a parameter must
 * express and `expect`/`actual` cannot.
 *
 * All of it is pure arithmetic on dp, kept out of the composition and asserted,
 * because every one of these failures is a layout that looks deliberate. Nothing
 * here throws, logs or refuses: a frame rule that can fail is a window that can
 * fail to draw.
 */
object Responsive {

    /**
     * Below this the window is ONE pane, and the list folds itself away.
     *
     * The same 700dp the phone uses (`MainActivity` `wide = maxWidth >= 700.dp`),
     * and deliberately the same number rather than a desktop-flavoured near-miss:
     * the two clients are answering one question about one window width, and a
     * pair of thresholds a few dp apart is a pair that drifts.
     */
    const val COMPACT_BELOW_DP: Float = 700f

    /** Is this window narrow enough that the list and the detail cannot share it. */
    fun compact(windowDp: Float): Boolean = windowDp < COMPACT_BELOW_DP

    /**
     * The most of the window the list pane may ever take.
     *
     * [Splitter]'s own bounds (220..560) are about READABILITY and they bound what
     * a person drags; neither has any idea how big the window is, so a 320dp pane
     * dragged on a docked 3440 monitor is still 320dp when the same window is
     * later snapped to a third of a laptop screen — 42% of it at 768, 76% at 420.
     * This is the other half of the clamp: a ceiling expressed in the window
     * rather than in the pane.
     *
     * 0.42 rather than a third: at 900dp (the narrowest shape that still shows two
     * panes) it leaves the detail 520dp, which is a conversation; a third would
     * leave the list 300 and start eating session names for a pane nobody asked to
     * be narrower.
     */
    const val LIST_MAX_FRACTION: Float = 0.42f

    /**
     * What the list pane is really drawn at: the remembered width, capped by the
     * window, then put back inside [Splitter]'s bounds.
     *
     * The persisted number is NOT rewritten — the same rule the collapse follows.
     * A window dragged narrow for an afternoon must not quietly redefine the seam
     * the reader dragged, and widening it again has to give back exactly what was
     * there before.
     */
    fun listWidth(persisted: Float, windowDp: Float): Float =
        Splitter.clamp(minOf(Splitter.clamp(persisted), windowDp * LIST_MAX_FRACTION))

    /**
     * Whether the list pane is shut, given all three facts that can shut it.
     *
     * @param persisted what the notch and Ctrl+B wrote, the remembered answer for
     *   a window wide enough to hold an opinion.
     * @param compact the window is below [COMPACT_BELOW_DP] and cannot hold both.
     * @param revealed the reader pressed the notch (or Ctrl+B) ANYWAY while
     *   compact. In-memory and per-window on purpose: it is an answer to "show me
     *   the list on this narrow window, now", not a setting, and persisting it
     *   would be the auto-collapse writing over the very preference it exists to
     *   leave alone.
     *
     * The order matters. Compact WINS over the persisted flag, so a window that
     * was left with its list open opens narrow with the list folded; widening it
     * hands the persisted answer straight back, because nothing was written.
     */
    fun listCollapsed(persisted: Boolean, compact: Boolean, revealed: Boolean): Boolean =
        if (compact) !revealed else persisted

    // ------------------------------------------------------------- the palette

    /** The palette's comfortable measure: a search field and one-line rows. */
    const val PALETTE_MAX_DP: Float = 620f

    /** Scrim left either side of it, so the card reads as a card. */
    const val PALETTE_MARGIN_DP: Float = 32f

    /**
     * How wide to draw the palette in a window this wide.
     *
     * It was a flat `width(620.dp)`, which at 600px of window is WIDER THAN THE
     * WINDOW — the card bled off both edges, lost its corners and set its text
     * flush against the frame. A margin that cannot be honoured is not a margin,
     * so the floor is the margin itself rather than 620.
     */
    fun paletteWidth(windowDp: Float): Float =
        minOf(PALETTE_MAX_DP, (windowDp - PALETTE_MARGIN_DP).coerceAtLeast(PALETTE_MARGIN_DP))

    /** Where the palette hangs from the top of the window. */
    const val PALETTE_TOP_DP: Float = 96f

    /**
     * The palette's list height, in a window this tall.
     *
     * A flat 360dp clipped its last row mid-glyph at every shape — including a
     * 1400px window with 880px going spare — because the cap was a constant and
     * the window was not. Bounded below so the list is never a sliver, and above
     * by what is actually left under the search field.
     */
    fun paletteListHeight(windowHeightDp: Float): Float =
        (windowHeightDp - PALETTE_TOP_DP - PALETTE_MARGIN_DP - 64f)
            .coerceIn(120f, 560f)
}

/**
 * Which shape the composer's controls take, decided by the composer's own width.
 *
 * THE OWNER WROTE THIS ONE HIMSELF: *"when in a session or chat view in huginn
 * desktop, and snapping the screen to the side so it's skinny, the chat box is
 * forced to be very tall, taking away a lot of space from the above session chat
 * or screen view. We should look at moving the items in the layout like the send
 * and interrupt buttons into symbols and/or stacked, as well as attach, to give
 * the textbox more horizontal space."*
 *
 * The mechanism behind the complaint is that a composer is one Row of fixed-size
 * controls around a single weighted field, so the FIELD absorbs every pixel the
 * buttons refuse to give up. Attach + Interrupt + Send are ~230dp of labelled
 * buttons; at 420dp of window that leaves the field about 190, its placeholder
 * wraps to five lines, and the band grows to 22% of the window for an EMPTY box.
 *
 * Under the breakpoint the buttons become their own icons on a line beneath the
 * field, and the field takes the whole width. Same controls, same keyboard, same
 * order — one row of 32dp symbols instead of 230dp of words.
 */
enum class ComposerLayout {
    /** Attach, field, actions, all on one line. The desk shape. */
    FULL,

    /** Field on its own line, the controls as icons beneath it. */
    COMPACT,

    ;

    companion object {
        /**
         * Below this many dp of COMPOSER width (not window width — the composer
         * sits inside whatever the detail pane turned out to be, which is a
         * different number at every seam position).
         *
         * 560 is where the full row stops working rather than where it starts
         * looking tight: 230dp of controls plus the ~330dp the placeholder needs
         * to stay on one line.
         */
        const val COMPACT_BELOW_DP: Float = 560f

        fun of(widthDp: Float): ComposerLayout =
            if (widthDp < COMPACT_BELOW_DP) COMPACT else FULL
    }
}

/** The composer's own geometry, apart from which shape its controls take. */
object Composer {

    /**
     * The widest the text field itself is allowed to get, however wide the pane is.
     *
     * A reading measure, for the same reason the full-width panes have one: past
     * about 900dp a line of typed instruction is 130 characters the eye has to
     * track back across. The session composer already had this and the chat
     * composer had NO cap at all, so at 1440 one of them stopped and the other
     * spanned the pane — which is what a shape shared between two files prevents.
     */
    const val FIELD_MAX_WIDTH_DP: Float = 900f

    /** A field shorter than this is not a message box. */
    const val FIELD_MIN_DP: Float = 56f

    /** What it was allowed to grow to before the window had any say: a constant. */
    const val FIELD_MAX_DP: Float = 160f

    /**
     * The most of the pane the text field may take while growing.
     *
     * The field grows with what is typed, up to a cap — and a CONSTANT cap is the
     * other half of the owner's complaint: 160dp of composer is a fifth of a
     * 768-tall window and over a quarter of what is left after the header and the
     * status line. The transcript is the reason the window is open; the composer
     * is how you answer it, and it does not get to take nearly half of it.
     */
    const val FIELD_MAX_FRACTION: Float = 0.40f

    /**
     * How tall the text field may grow, in a pane this tall.
     *
     * Never taller than [FIELD_MAX_DP] (a desk window must not get a 400dp box
     * just because it can) and never shorter than [FIELD_MIN_DP] (a cap under the
     * minimum is a field that cannot draw its own first line).
     */
    fun fieldMaxHeight(paneHeightDp: Float): Float =
        (paneHeightDp * FIELD_MAX_FRACTION).coerceIn(FIELD_MIN_DP, FIELD_MAX_DP)

    /**
     * The compact control's own square — Attach, Interrupt and Send, under the
     * breakpoint. 32 rather than Material's 40: this is a mouse target on a narrow
     * window, and the whole point of the shape is that the line under the field
     * costs as little height as it can while staying hittable.
     *
     * ⚠ ONE CONSTANT FOR EVERY CONTROL ON THAT LINE, AND THAT IS THE WHOLE POINT.
     * THE OWNER REPORTED THE ALTERNATIVE: *"the send button in desktop, when shrunk
     * to its icon due to scaling, is not aligned properly with the attachment icon
     * to its left, it should be centered vertically on the same horizontal plane as
     * the attachment button."*
     *
     * It was not. Attach stayed a `TextButton` in BOTH shapes while Send and
     * Interrupt became [CONTROL_DP] icon buttons, and a clickable M3 `Surface`
     * carries `minimumInteractiveComponentSize()` — so the labelled button reported
     * a 48dp box with its glyph centred at 24dp from the top of the line, while the
     * 32dp icon buttons sat at the TOP of that line with their glyphs at 16dp.
     * Measured on a 560px window: 8px apart, which is what the owner was looking at.
     *
     * Aligning the ROW would have hidden it rather than fixed it: two controls of
     * different sizes on one line still read as two different controls. Every
     * compact control is built from this number instead, so they cannot disagree —
     * `ComposerControlSizeTest` is the gate that says they still all use it.
     */
    const val CONTROL_DP: Float = 32f

    /** The glyph inside it. Sized to the control, not to the rail's 20dp icons. */
    const val CONTROL_GLYPH_DP: Float = 18f

    /**
     * The placeholder, which is also a keyboard lesson — and at a narrow width is
     * the thing that wraps to five lines.
     *
     * The hints are dropped rather than ellipsised under the breakpoint: half a
     * sentence about Shift+Enter teaches nothing, and the cheat sheet (F1) still
     * carries all of it.
     */
    fun placeholder(full: String, hint: String, layout: ComposerLayout): String =
        if (layout == ComposerLayout.COMPACT) full else "$full  ($hint)"
}
