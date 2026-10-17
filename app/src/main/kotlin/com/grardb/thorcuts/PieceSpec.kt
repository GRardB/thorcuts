package com.grardb.thorcuts

import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.ModifierKey
import com.grardb.thorcuts.core.model.ThorControls

/** A piece's outline: a rounded rectangle (the modifiers, the shoulders, anything else) or a
 *  circle (the face buttons, the stick clicks, the D-pad). */
enum class PieceShape { RECT, CIRCLE }

/** A piece's fill: gray for most, one colour each for A, B, X and Y. */
enum class PieceFill { GRAY, RED, YELLOW, BLUE, GREEN }

/** The glyphs drawn instead of a name: Home, Back, Start (a play triangle), Select (a square),
 *  the four D-pad directions, and the timer on a HOLD trigger's trailing piece. */
enum class Glyph { HOME, BACK, PLAY, SQUARE, UP, DOWN, LEFT, RIGHT, TIMER }

/**
 * How one control is drawn: its [name] (what a screen reader hears), its shape and fill, and
 * a [label] to print, a [glyph] to draw, or both (the timer piece: the glyph, then the hold).
 * Pure data; `Pieces.kt` draws it.
 */
data class PieceSpec(
    val name: String,
    val shape: PieceShape,
    val fill: PieceFill,
    val label: String? = null,
    val glyph: Glyph? = null,
)

private fun rect(name: String, label: String = name) =
    PieceSpec(name, PieceShape.RECT, PieceFill.GRAY, label = label)

private fun glyph(name: String, glyph: Glyph, shape: PieceShape = PieceShape.RECT) =
    PieceSpec(name, shape, PieceFill.GRAY, glyph = glyph)

private fun circle(name: String, fill: PieceFill = PieceFill.GRAY) =
    PieceSpec(name, PieceShape.CIRCLE, fill, label = name)

/** The five modifiers: AYN by name, the other four by glyph, all gray rectangles. */
fun pieceOf(m: ModifierKey): PieceSpec {
    val name = ThorControls.name(m)
    return when (m) {
        ModifierKey.AYN -> rect(name)
        ModifierKey.HOME -> glyph(name, Glyph.HOME)
        ModifierKey.BACK -> glyph(name, Glyph.BACK)
        ModifierKey.START -> glyph(name, Glyph.PLAY)
        ModifierKey.SELECT -> glyph(name, Glyph.SQUARE)
    }
}

/**
 * Any control, keyed on the name `ThorControls` gives it — the one vocabulary `:core` has, so
 * no evdev code is repeated here. A modifier's own control maps to its modifier piece first,
 * as `ThorControls.name` does; a control the table does not know is a gray rectangle with
 * whatever name it was given.
 */
fun pieceOf(c: Control): PieceSpec {
    ModifierKey.entries.firstOrNull { it.control == c }?.let { return pieceOf(it) }
    return when (val name = ThorControls.name(c)) {
        "A" -> circle(name, PieceFill.RED)
        "B" -> circle(name, PieceFill.YELLOW)
        "X" -> circle(name, PieceFill.BLUE)
        "Y" -> circle(name, PieceFill.GREEN)
        "L3", "R3" -> circle(name)
        "D-pad up" -> glyph(name, Glyph.UP, PieceShape.CIRCLE)
        "D-pad down" -> glyph(name, Glyph.DOWN, PieceShape.CIRCLE)
        "D-pad left" -> glyph(name, Glyph.LEFT, PieceShape.CIRCLE)
        "D-pad right" -> glyph(name, Glyph.RIGHT, PieceShape.CIRCLE)
        "Volume +" -> rect(name, "Vol +")
        else -> rect(name)
    }
}
