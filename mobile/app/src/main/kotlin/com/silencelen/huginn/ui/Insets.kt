package com.silencelen.huginn.ui

import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * THE SYSTEM BARS, WRITTEN DOWN ONCE.
 *
 * ⚠⚠ THE REVIEW FOUND THE SAME MISSING LINE ON SIX SCREENS. Overview, Status,
 * the archived transcript, Host & sign-in, Devices and the Apps card each ended
 * with their last row under the Android navigation bar, and in landscape the
 * conversation ran to x=2483 of 2520 — under the back/home/recents strip on the
 * right edge — while the nav rail was cut off at the bottom. Six screens with
 * six chances to forget is not six bugs; it is one missing vocabulary.
 *
 * ⚠ THE INSET STILL HAS EXACTLY ONE OWNER, and the frame is what makes that
 * true rather than each screen guessing. `MainActivity` pays the SIDE inset once
 * for every pane, and CONSUMES the bottom one whenever it is drawing a
 * `NavigationBar` (a Material bar pays its own). So a screen can call
 * [systemNavPadding] unconditionally and get the right answer on a tab, on a
 * pushed destination, and on the two-pane landscape layout, without knowing
 * which of those it is in — which is the knowledge `ListFabClearanceTest` exists
 * because we got wrong in both directions.
 *
 * WHY NOT `navigationBarsPadding()` DIRECTLY: it is the same call, and that is
 * the point. Named here, "does this screen owe the system bars anything" has one
 * spelling to grep for and one place to change when an OEM puts the bar
 * somewhere new. The keyboard is the same: [keyboardPadding] for a column,
 * [imeAndSystemNavPadding] for a single surface.
 */

/**
 * The system navigation, wherever the device is putting it — the bar along the
 * bottom in portrait, the strip down an edge in landscape.
 *
 * Never a top inset: that one belongs to the status bar and the app bar pays it.
 */
@Composable
fun systemNavInsets(): WindowInsets =
    WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal + WindowInsetsSides.Bottom)

/** Only the side the bar takes when the phone is held sideways. The FRAME's to pay. */
@Composable
fun sideNavInsets(): WindowInsets =
    WindowInsets.navigationBars.only(WindowInsetsSides.Horizontal)

/** Only the bottom. What a [androidx.compose.material3.NavigationBar] has already eaten. */
@Composable
fun bottomNavInsets(): WindowInsets =
    WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)

/**
 * Keeps a surface clear of the system navigation.
 *
 * Put it on the SCROLL CONTAINER, not on a row inside one: a scrolling list wants
 * its last row to come to rest above the bar, and a padding inside the scroll
 * that the reader can scroll past is not an inset, it is a gap.
 */
@Composable
fun Modifier.systemNavPadding(): Modifier = this.windowInsetsPadding(systemNavInsets())

/**
 * Keeps a surface clear of the system navigation AND of the soft keyboard.
 *
 * ⚠ THE ORDER IS LOAD-BEARING and it is why this is a helper rather than two
 * calls at each site. `imePadding()` CONSUMES the inset it applies, so the
 * navigation padding after it adds only what the keyboard did not already
 * cover — which is zero while the keyboard is up and the full bar when it is
 * down. Written the other way round the two stack into a band of dead space.
 *
 * For ONE surface that is the whole screen (a scroll container, an editor).
 * A column that ends in a composer and carries a card above it pays the
 * keyboard on the column instead — see [keyboardPadding].
 */
@Composable
fun Modifier.imeAndSystemNavPadding(): Modifier = this.imePadding().systemNavPadding()

/**
 * Keeps a whole COLUMN above the soft keyboard: the keyboard and nothing else.
 *
 * ⚠⚠ THE KEYBOARD WAS PAID BY THE LAST ROW, AND A COLUMN MEASURES THAT ROW
 * LAST. The conversation paid it on the composer row alone
 * ([imeAndSystemNavPadding]), while the question card above the composer was
 * capped from the column's FULL height — so with the keyboard up the card's cap
 * and the composer's keyboard padding together overflowed the column, and its
 * bottom was laid out under the keyboard: an "Other…" field, its Answer button
 * and the composer itself, with typed text landing in a field nobody could see
 * (Fold cover screen, 2026-11-03). Paid on the column, the column ENDS at the
 * keyboard, and everything in it is measured from the room that is really there.
 *
 * Pair it with [systemNavPadding] on the bottom row, never with
 * [imeAndSystemNavPadding]: this CONSUMES the keyboard's inset, and while the
 * keyboard is up its inset already covers the navigation bar, so the row pays
 * the bar only while the keyboard is down. No doubled band, and no gap when it
 * closes.
 */
fun Modifier.keyboardPadding(): Modifier = this.imePadding()
