package com.grardb.thorcuts

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class FocusedWindowTest {

    @Test
    fun `the focused window's package, once an event has named it`() {
        val packages = mutableMapOf(1 to "com.a", 2 to "com.b")
        assertEquals("com.b", FocusedWindow.pick(listOf(1, 2), 2, packages))
    }

    @Test
    fun `null while the focused window's package is not known yet`() {
        val packages = mutableMapOf(1 to "com.a")
        assertNull(FocusedWindow.pick(listOf(1, 2), 2, packages))
    }

    @Test
    fun `null when nothing holds focus`() {
        val packages = mutableMapOf(1 to "com.a")
        assertNull(FocusedWindow.pick(listOf(1), null, packages))
    }

    @Test
    fun `windows that are gone are forgotten, the rest kept`() {
        val packages = mutableMapOf(1 to "com.a", 2 to "com.b", 3 to "com.c")
        FocusedWindow.pick(listOf(1, 3), 1, packages)
        assertEquals(mapOf(1 to "com.a", 3 to "com.c"), packages)
    }

    @Test
    fun `the active window wins over a focused one on the other display`() {
        // The Thor (MEASURED 2026-10-16): each screen keeps a focused window, so both report
        // isFocused; the top-focused display's window alone is active, and it is the one the
        // controller's input reaches. Picking by isFocused chose the top screen every time.
        val top = FocusedWindow.Candidate(1, isActive = false, isFocused = true)
        val bottom = FocusedWindow.Candidate(2, isActive = true, isFocused = true)
        assertEquals(2, FocusedWindow.choose(listOf(top, bottom)))
    }

    @Test
    fun `a focused window is chosen when none is active, and null when none is focused`() {
        val a = FocusedWindow.Candidate(1, isActive = false, isFocused = false)
        val b = FocusedWindow.Candidate(2, isActive = false, isFocused = true)
        assertEquals(2, FocusedWindow.choose(listOf(a, b)))
        assertNull(FocusedWindow.choose(listOf(a)))
    }
}
