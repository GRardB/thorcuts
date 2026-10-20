package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.BrightnessMode

/**
 * The level the tool writes (brightness spec §6): [percent] itself for SET, [from] stepped
 * by it for INCREASE / DECREASE, clamped to 0..100 — a step past the end lands on the end.
 * Pure and top-level, so the tool's one piece of arithmetic is host-tested; the tool itself
 * (reflection, uid 0) is the Android boundary.
 */
fun targetPercent(mode: BrightnessMode, from: Int, percent: Int): Int = when (mode) {
    BrightnessMode.SET -> percent
    BrightnessMode.INCREASE -> from + percent
    BrightnessMode.DECREASE -> from - percent
}.coerceIn(0, 100)
