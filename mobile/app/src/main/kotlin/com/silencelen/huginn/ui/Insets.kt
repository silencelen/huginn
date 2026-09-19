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
 * somewhere new.
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
 */
@Composable
fun Modifier.imeAndSystemNavPadding(): Modifier = this.imePadding().systemNavPadding()
