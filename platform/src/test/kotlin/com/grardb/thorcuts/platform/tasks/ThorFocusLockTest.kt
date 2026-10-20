package com.grardb.thorcuts.platform.tasks

import com.grardb.thorcuts.core.model.ScreenTarget
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/** The quick panel's three values, as measured 2026-10-20, and what anything else means. */
class ThorFocusLockTest {

    @Test
    fun `1 locks to the top screen and 2 to the bottom, as the quick panel writes them`() {
        assertEquals(ScreenTarget.TOP, ThorFocusLock.of(1))
        assertEquals(ScreenTarget.BOTTOM, ThorFocusLock.of(2))
    }

    @Test
    fun `0 is Auto-lock, and so is any value the quick panel never writes`() {
        for (value in listOf(0, 3, -1, Int.MAX_VALUE)) {
            assertNull(ThorFocusLock.of(value), "value $value")
        }
    }
}
