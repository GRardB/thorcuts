package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.BrightnessMode
import kotlin.test.Test
import kotlin.test.assertEquals

/** The tool's one piece of arithmetic (brightness spec §6), pinned at both ends. */
class BrightnessStepTest {

    @Test
    fun `a set is the level itself, clamped`() {
        assertEquals(50, targetPercent(BrightnessMode.SET, 20, 50))
        assertEquals(0, targetPercent(BrightnessMode.SET, 20, 0))
        assertEquals(100, targetPercent(BrightnessMode.SET, 20, 100))
        assertEquals(100, targetPercent(BrightnessMode.SET, 20, 150))
        assertEquals(0, targetPercent(BrightnessMode.SET, 20, -5))
    }

    @Test
    fun `a step moves from the current level and lands on the end past it`() {
        assertEquals(45, targetPercent(BrightnessMode.INCREASE, 35, 10))
        assertEquals(100, targetPercent(BrightnessMode.INCREASE, 95, 10))
        assertEquals(100, targetPercent(BrightnessMode.INCREASE, 100, 1))
        assertEquals(25, targetPercent(BrightnessMode.DECREASE, 35, 10))
        assertEquals(0, targetPercent(BrightnessMode.DECREASE, 5, 10))
        assertEquals(0, targetPercent(BrightnessMode.DECREASE, 0, 1))
    }
}
