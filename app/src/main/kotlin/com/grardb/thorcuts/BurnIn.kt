package com.grardb.thorcuts

import android.view.Window
import android.view.WindowManager
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.delay

/**
 * AMOLED burn-in mitigation for a screen that sits lit for a long time.
 *
 * This app's diagnostics screen is parked on the Thor's AMOLED panel for stretches
 * of an hour or more while the device tasks are verified, showing almost the same
 * pixels throughout. Burn-in is driven by **luminance x time on the same subpixel**,
 * so there are three levers and this file holds two of them:
 *
 *  1. Darkness — a true-black background means most pixels are OFF. Handled by the
 *     colour scheme in [MainActivity], plus [DIM_TEXT] here for the foreground,
 *     since Material 3's default dark `onSurface` is near-white and therefore the
 *     brightest thing on screen.
 *  2. Luminance — [dimWindow] lowers the brightness of THIS APP'S WINDOW ONLY. It
 *     does not touch the user's system brightness setting, and it is restored by
 *     Android as soon as another window takes focus.
 *  3. Time on the same subpixel — [rememberBurnInShift] walks the whole layout
 *     around by a few pixels on a slow cycle, so no pixel holds one value for long.
 *     This is the same trick OLED televisions use for static channel logos.
 *
 * None of this keeps the screen awake; the app holds no wake lock and sets no
 * FLAG_KEEP_SCREEN_ON, so the system timeout still applies.
 */

/** Window brightness while this app is focused. Low enough to cut luminance
 *  substantially, high enough to read in a normally lit room. */
private const val DIM_BRIGHTNESS = 0.3f

/** How long the layout rests at each position. */
private const val SHIFT_INTERVAL_MS = 45_000L

/** Grid the layout walks around. 4 x 3 at 45s = a 9-minute cycle. */
private const val SHIFT_COLUMNS = 4
private const val SHIFT_ROWS = 3

/**
 * Foreground colour for this screen: a mid grey rather than Material's near-white
 * `onSurface`. A large luminance reduction for no loss of legibility on black.
 */
val DIM_TEXT = androidx.compose.ui.graphics.Color(0xFF9A9A9A)

/** A slightly brighter grey, for the one or two values worth drawing the eye to. */
val DIM_TEXT_EMPHASIS = androidx.compose.ui.graphics.Color(0xFFC4C4C4)

/**
 * Dim [window] to [DIM_BRIGHTNESS]. Call from the activity's onCreate.
 *
 * This is a per-window override: Android applies it only while this window has
 * focus and restores the normal brightness afterwards, so it cannot leave the
 * device dimmed.
 */
fun dimWindow(window: Window) {
    window.attributes = window.attributes.apply {
        screenBrightness = DIM_BRIGHTNESS
    }
}

/**
 * A small offset that changes every [SHIFT_INTERVAL_MS], walking a
 * [SHIFT_COLUMNS] x [SHIFT_ROWS] grid of 1dp steps.
 *
 * Kept to a few dp so it never pushes content against an edge — the screen's own
 * padding is far larger than the maximum excursion.
 */
@Composable
fun rememberBurnInShift(): DpOffset {
    var step by remember { mutableIntStateOf(0) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(SHIFT_INTERVAL_MS)
            step = (step + 1) % (SHIFT_COLUMNS * SHIFT_ROWS)
        }
    }
    return DpOffset(
        x = (step % SHIFT_COLUMNS).dp,
        y = (step / SHIFT_COLUMNS).dp,
    )
}
