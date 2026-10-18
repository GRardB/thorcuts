package com.grardb.thorcuts

import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.Device
import com.grardb.thorcuts.core.model.EvdevType
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.ThorControls
import kotlin.test.Test
import kotlin.test.assertEquals

class PieceSpecTest {

    private fun padKey(code: Int) = Control(Device.PAD, EvdevType.KEY, code, 1)
    private fun hat(code: Int, value: Int) = Control(Device.PAD, EvdevType.ABS, code, value)

    private fun rect(name: String, label: String = name) =
        PieceSpec(name, PieceShape.RECT, PieceFill.GRAY, label = label)

    private fun circle(name: String, fill: PieceFill = PieceFill.GRAY) =
        PieceSpec(name, PieceShape.CIRCLE, fill, label = name)

    @Test
    fun `the five modifiers, as a modifier and as the control it is`() {
        // AYN carries a word, so it stays a rectangle; the four glyphs are circles, like the
        // buttons they are on the Thor
        assertEquals(rect("AYN"), pieceOf(ModifierKey.AYN))
        val glyphs = mapOf(
            ModifierKey.HOME to ("Home" to Glyph.HOME),
            ModifierKey.BACK to ("Back" to Glyph.BACK),
            ModifierKey.START to ("Start" to Glyph.PLAY),
            ModifierKey.SELECT to ("Select" to Glyph.SQUARE),
        )
        for ((m, expected) in glyphs) {
            val (name, glyph) = expected
            val expectedSpec = PieceSpec(name, PieceShape.CIRCLE, PieceFill.GRAY, glyph = glyph)
            assertEquals(expectedSpec, pieceOf(m))
            assertEquals(pieceOf(m), pieceOf(m.control))      // the control form maps the same
        }
        assertEquals(pieceOf(ModifierKey.SELECT), pieceOf(padKey(314)))
    }

    @Test
    fun `the face buttons are coloured circles`() {
        assertEquals(circle("A", PieceFill.RED), pieceOf(padKey(304)))
        assertEquals(circle("B", PieceFill.YELLOW), pieceOf(padKey(305)))
        assertEquals(circle("X", PieceFill.BLUE), pieceOf(padKey(307)))
        assertEquals(circle("Y", PieceFill.GREEN), pieceOf(padKey(308)))
    }

    @Test
    fun `the shoulders are gray rectangles and the stick clicks gray circles`() {
        for ((code, name) in mapOf(310 to "L1", 311 to "R1", 312 to "L2", 313 to "R2")) {
            assertEquals(rect(name), pieceOf(padKey(code)))
        }
        assertEquals(circle("L3"), pieceOf(padKey(317)))
        assertEquals(circle("R3"), pieceOf(padKey(318)))
    }

    @Test
    fun `the D-pad is a gray rectangle with an arrow`() {
        fun arrow(name: String, glyph: Glyph) =
            PieceSpec(name, PieceShape.RECT, PieceFill.GRAY, glyph = glyph)
        assertEquals(arrow("D-pad up", Glyph.UP), pieceOf(hat(ThorControls.HAT0Y, -1)))
        assertEquals(arrow("D-pad down", Glyph.DOWN), pieceOf(hat(ThorControls.HAT0Y, 1)))
        assertEquals(arrow("D-pad left", Glyph.LEFT), pieceOf(hat(ThorControls.HAT0X, -1)))
        assertEquals(arrow("D-pad right", Glyph.RIGHT), pieceOf(hat(ThorControls.HAT0X, 1)))
    }

    @Test
    fun `Volume + is shortened, and a control the table does not know keeps its name`() {
        val volumeUp = Control(Device.GPIO, EvdevType.KEY, 115, 1)
        assertEquals(rect("Volume +", "Vol +"), pieceOf(volumeUp))
        assertEquals(rect("key 999"), pieceOf(padKey(999)))
        assertEquals(rect("axis 9 at 16384"), pieceOf(hat(9, 16384)))
    }
}
