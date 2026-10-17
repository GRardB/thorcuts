package com.grardb.thorcuts

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class UiStateTest {

    private fun shortcut(id: String, scope: Scope) =
        Shortcut(id, Trigger(setOf(ModifierKey.AYN)), Action.SwapScreens, scope)

    private val plain = shortcut("plain", Scope.Global())
    private val except = shortcut("except", Scope.Global(setOf("com.a")))
    private val only = shortcut("only", Scope.Apps(setOf("com.a")))

    @Test
    fun `the notice is for a per-app scope while the service is off`() {
        assertFalse(needsMonitor(emptyList(), monitor = false))
        assertFalse(needsMonitor(listOf(plain), monitor = false))
        assertTrue(needsMonitor(listOf(plain, except), monitor = false))
        assertTrue(needsMonitor(listOf(only), monitor = false))
        assertFalse(needsMonitor(listOf(except, only), monitor = true))
    }

    private val apps = listOf(
        InstalledApp("com.retroarch", "RetroArch", icon = null),
        InstalledApp("org.ppsspp.ppsspp", "PPSSPP", icon = null),
        InstalledApp("com.android.settings", "Settings", icon = null),
    )

    @Test
    fun `a blank query is every app, and a query matches the label or the package`() {
        assertEquals(apps, filterApps(apps, ""))
        assertEquals(apps, filterApps(apps, "   "))
        assertEquals(listOf(apps[0]), filterApps(apps, "retro"))      // the label, any case
        assertEquals(listOf(apps[1]), filterApps(apps, "PPSS"))
        assertEquals(listOf(apps[2]), filterApps(apps, "android."))   // the package
        assertEquals(listOf(apps[1], apps[2]), filterApps(apps, " s "))  // trimmed: "s"
        assertEquals(emptyList(), filterApps(apps, "zzz"))
    }
}
