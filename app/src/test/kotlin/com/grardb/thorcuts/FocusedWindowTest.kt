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
}
