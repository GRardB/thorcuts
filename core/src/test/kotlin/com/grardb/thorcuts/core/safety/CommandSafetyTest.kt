package com.grardb.thorcuts.core.safety

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CommandSafetyTest {

    // ---- isPackage: accept real package names ----

    @Test
    fun `accepts ordinary package names`() {
        assertTrue(CommandSafety.isPackage("com.grardb.thorcuts"))
        assertTrue(CommandSafety.isPackage("a.b"))
        assertTrue(CommandSafety.isPackage("com.foo.Bar_baz2"))
        assertTrue(CommandSafety.isPackage("org.retroarch.aarch64"))
    }

    // ---- isPackage: reject anything a shell could act on ----

    @Test
    fun `rejects shell metacharacters in package names`() {
        assertFalse(CommandSafety.isPackage("a.b; rm -rf /"))
        assertFalse(CommandSafety.isPackage("a.b && id"))
        assertFalse(CommandSafety.isPackage("a.b | id"))
        assertFalse(CommandSafety.isPackage("a.b\$(id)"))
        assertFalse(CommandSafety.isPackage("a.b`id`"))
        assertFalse(CommandSafety.isPackage("a.b'"))
        assertFalse(CommandSafety.isPackage("a.b\""))
        assertFalse(CommandSafety.isPackage("a.b\\c"))
        assertFalse(CommandSafety.isPackage("a.b>out"))
    }

    @Test
    fun `rejects newlines in package names`() {
        assertFalse(CommandSafety.isPackage("a.b\nid"))
        assertFalse(CommandSafety.isPackage("a.b\rid"))
    }

    @Test
    fun `rejects malformed package names`() {
        assertFalse(CommandSafety.isPackage(null))
        assertFalse(CommandSafety.isPackage(""))
        assertFalse(CommandSafety.isPackage("nodot"))
        assertFalse(CommandSafety.isPackage(".leading"))
        assertFalse(CommandSafety.isPackage("trailing."))
        assertFalse(CommandSafety.isPackage("a..b"))
        assertFalse(CommandSafety.isPackage("1abc.def"))
        assertFalse(CommandSafety.isPackage("a b.c"))
        assertFalse(CommandSafety.isPackage("../etc/passwd"))
        assertFalse(CommandSafety.isPackage("/a.b"))
    }

    @Test
    fun `rejects overlong package names`() {
        val long = "a." + "b".repeat(300)
        assertFalse(CommandSafety.isPackage(long))
    }

    // ---- isComponent ----

    @Test
    fun `accepts component names including inner classes`() {
        assertTrue(CommandSafety.isComponent("com.foo/com.foo.MainActivity"))
        assertTrue(CommandSafety.isComponent("com.foo/.MainActivity"))
        // Inner classes are legal and contain '$'. This is exactly why quoting matters.
        assertTrue(CommandSafety.isComponent("com.foo/com.foo.Outer\$Inner"))
    }

    @Test
    fun `rejects malformed or hostile component names`() {
        assertFalse(CommandSafety.isComponent(null))
        assertFalse(CommandSafety.isComponent(""))
        assertFalse(CommandSafety.isComponent("com.foo"))
        assertFalse(CommandSafety.isComponent("com.foo/"))
        assertFalse(CommandSafety.isComponent("/com.foo.A"))
        assertFalse(CommandSafety.isComponent("com.foo/com.foo.A B"))
        assertFalse(CommandSafety.isComponent("com.foo/com.foo.A;id"))
        assertFalse(CommandSafety.isComponent("com.foo/com.foo.A'"))
        assertFalse(CommandSafety.isComponent("com.foo/com.foo.A\nid"))
    }

    // ---- isClassName ----

    @Test
    fun `accepts a fully-qualified class name`() {
        assertTrue(CommandSafety.isClassName("com.grardb.thorcuts.MainActivity"))
        assertTrue(CommandSafety.isClassName("a.B"))
    }

    @Test
    fun `accepts an inner-class name with dollar`() {
        // Inner classes are legal and contain '$'. This is exactly why quoting matters.
        assertTrue(CommandSafety.isClassName("com.foo.Outer\$Inner"))
    }

    @Test
    fun `rejects malformed or hostile class names`() {
        assertFalse(CommandSafety.isClassName(null))
        assertFalse(CommandSafety.isClassName(""))
        assertFalse(CommandSafety.isClassName("MainActivity"))
        assertFalse(CommandSafety.isClassName(".foo.Bar"))
        assertFalse(CommandSafety.isClassName("foo.Bar."))
        assertFalse(CommandSafety.isClassName("foo..Bar"))
        assertFalse(CommandSafety.isClassName("com.1foo.Bar"))
        assertFalse(CommandSafety.isClassName("com.foo.Main Activity"))
        assertFalse(CommandSafety.isClassName("com.foo.Bar;id"))
        assertFalse(CommandSafety.isClassName("com.foo.Bar'"))
        assertFalse(CommandSafety.isClassName("com.foo.Bar\nid"))
        assertFalse(CommandSafety.isClassName("com.foo.Bar\rid"))
        assertFalse(CommandSafety.isClassName("com.foo.Bar`id`"))
        assertFalse(CommandSafety.isClassName("com.foo.Bar\$(id)"))
    }

    @Test
    fun `rejects overlong class names`() {
        val long = "a." + "B".repeat(300)
        assertFalse(CommandSafety.isClassName(long))
    }

    @Test
    fun `classNameArg quotes valid input and rejects invalid`() {
        assertEquals(
            "'com.foo.Outer\$Inner'",
            CommandSafety.classNameArg("com.foo.Outer\$Inner")
        )
        assertNull(CommandSafety.classNameArg("com.foo.Bar;id"))
        assertNull(CommandSafety.classNameArg(null))
    }

    // ---- isSingleLine ----

    @Test
    fun `detects line breaks`() {
        assertTrue(CommandSafety.isSingleLine("id"))
        assertTrue(CommandSafety.isSingleLine(""))
        assertFalse(CommandSafety.isSingleLine("id\nwhoami"))
        assertFalse(CommandSafety.isSingleLine("id\rwhoami"))
    }

    // ---- quote: must neutralise everything, including its own quote char ----

    @Test
    fun `wraps plain text in single quotes`() {
        assertEquals("'abc'", CommandSafety.quote("abc"))
        assertEquals("''", CommandSafety.quote(""))
    }

    @Test
    fun `neutralises embedded single quotes`() {
        // a'b  ->  'a'\''b'
        assertEquals("'a'\\''b'", CommandSafety.quote("a'b"))
    }

    @Test
    fun `leaves expansions inert`() {
        assertEquals("'\$(id)'", CommandSafety.quote("\$(id)"))
        assertEquals("'`id`'", CommandSafety.quote("`id`"))
        assertEquals("'a b'", CommandSafety.quote("a b"))
        assertEquals("'a;b'", CommandSafety.quote("a;b"))
    }

    // ---- the combined helpers ----

    @Test
    fun `packageArg quotes valid input and rejects invalid`() {
        assertEquals("'com.grardb.thorcuts'", CommandSafety.packageArg("com.grardb.thorcuts"))
        assertNull(CommandSafety.packageArg("a.b; id"))
        assertNull(CommandSafety.packageArg(null))
    }

    @Test
    fun `componentArg quotes valid input and rejects invalid`() {
        assertEquals(
            "'com.foo/com.foo.Outer\$Inner'",
            CommandSafety.componentArg("com.foo/com.foo.Outer\$Inner")
        )
        assertNull(CommandSafety.componentArg("com.foo/com.foo.A;id"))
    }
}
