package com.grardb.thorcuts

import com.grardb.thorcuts.core.model.Action
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.Scope
import com.grardb.thorcuts.core.model.ScreenTarget
import com.grardb.thorcuts.core.model.Shortcut
import com.grardb.thorcuts.core.model.Trigger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull

class EditorDraftTest {

    private val x = Control(Device.PAD, EvdevType.KEY, 307, 1)

    private fun base(scope: ScopeKind = ScopeKind.EVERYWHERE, apps: Set<String> = emptySet()) =
        EditorDraft(
            id = "s",
            modifiers = setOf(ModifierKey.AYN),
            buttons = setOf(x),
            scope = scope,
            apps = apps,
        )

    @Test
    fun `everywhere round-trips to a plain global`() {
        val s = base().toShortcut()
        assertEquals(Scope.Global(), s.scope)
        assertEquals(ScopeKind.EVERYWHERE, EditorDraft.of(s).scope)
    }

    @Test
    fun `except round-trips to a global with those exclusions`() {
        val s = base(ScopeKind.EXCEPT, setOf("com.b", "com.a")).toShortcut()
        assertEquals(Scope.Global(setOf("com.a", "com.b")), s.scope)
        val d = EditorDraft.of(s)
        assertEquals(ScopeKind.EXCEPT, d.scope)
        assertEquals(setOf("com.a", "com.b"), d.apps)
    }

    @Test
    fun `only-in round-trips to an app list`() {
        val s = base(ScopeKind.ONLY, setOf("com.a")).toShortcut()
        assertEquals(Scope.Apps(setOf("com.a")), s.scope)
        val d = EditorDraft.of(s)
        assertEquals(ScopeKind.ONLY, d.scope)
        assertEquals(setOf("com.a"), d.apps)
    }

    @Test
    fun `except or only with no app chosen is refused`() {
        for (kind in listOf(ScopeKind.EXCEPT, ScopeKind.ONLY)) {
            val e = assertFailsWith<IllegalArgumentException> { base(kind).toShortcut() }
            assertEquals("Choose at least one app", e.message)
        }
    }

    @Test
    fun `the enabled flag is kept and the id trimmed`() {
        val original = Shortcut(
            "keep", Trigger(setOf(ModifierKey.AYN), setOf(x)), Action.SwapScreens, enabled = false,
        )
        val s = EditorDraft.of(original).copy(id = "  keep  ").toShortcut()
        assertEquals("keep", s.id)
        assertFalse(s.enabled)
    }

    @Test
    fun `a non-numeric timer throws its message`() {
        val e = assertFailsWith<IllegalArgumentException> {
            base().copy(timer = true, holdMs = "x").toShortcut()
        }
        assertEquals("The timer must be a number of milliseconds", e.message)
    }

    @Test
    fun `the screen choices read in sentence case, the focused one explained`() {
        assertEquals("Top screen", ScreenTarget.TOP.choiceLabel())
        assertEquals("Bottom screen", ScreenTarget.BOTTOM.choiceLabel())
        assertEquals(
            "Focused screen — the one you last touched or that last took input",
            ScreenTarget.FOCUSED.choiceLabel(),
        )
        assertEquals("the focused screen", ScreenTarget.FOCUSED.label())   // mid-sentence
    }

    @Test
    fun `the model's own messages pass through`() {
        val noMod = assertFailsWith<IllegalArgumentException> {
            base().copy(modifiers = emptySet()).toShortcut()
        }
        assertEquals("A trigger must start with a modifier", noMod.message)
        val bareHome = base().copy(modifiers = setOf(ModifierKey.HOME), buttons = emptySet())
        val home = assertFailsWith<IllegalArgumentException> { bareHome.toShortcut() }
        assertEquals("Home alone cannot be a shortcut: it would no longer go home", home.message)
    }

    @Test
    fun `the scope line reads for the three shapes`() {
        assertNull(Scope.Global().line())
        assertEquals(
            "Everywhere except: com.a, com.b",
            Scope.Global(setOf("com.b", "com.a")).line(),
        )
        assertEquals("Applies only in: com.a", Scope.Apps(setOf("com.a")).line())
    }

    @Test
    fun `the scope line names the apps it knows and keeps the id of one it does not`() {
        val labels = mapOf("com.b" to "Bitwarden", "com.a" to "Zelda")
        assertEquals(
            "Everywhere except: Bitwarden, Zelda",
            Scope.Global(setOf("com.b", "com.a")).line(labels),
        )
        assertEquals(
            "Applies only in: Bitwarden, com.zzz",
            Scope.Apps(setOf("com.zzz", "com.b")).line(labels),
        )
    }

    @Test
    fun `an action that aims at a screen trails an ellipsis in the editor, not in the list`() {
        assertEquals("Close the app on…", ActionKind.CLOSE.choiceLabel)
        assertEquals("Go home on…", ActionKind.HOME.choiceLabel)
        assertEquals("Swap the screens", ActionKind.SWAP.choiceLabel)
        assertEquals(
            "Close the app on the top screen",
            Action.CloseScreen(ScreenTarget.TOP).label(),
        )
    }

    @Test
    fun `trigger text describes a recorded combo, or says there is none`() {
        assertEquals("AYN + X", base().triggerText)
        assertEquals(
            Copy.NOT_RECORDED,
            base().copy(modifiers = emptySet(), buttons = emptySet()).triggerText,
        )
    }
}
