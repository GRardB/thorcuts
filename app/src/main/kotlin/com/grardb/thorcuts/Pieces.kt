package com.grardb.thorcuts

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.grardb.thorcuts.core.model.Control
import com.grardb.thorcuts.core.model.ThorControls
import com.grardb.thorcuts.core.model.Trigger
import com.grardb.thorcuts.core.model.TriggerStyle

/** The four face colours, fixed in both themes; the gray is the theme's `surfaceVariant`. */
private val Red = Color(0xFFD32F2F)
private val Yellow = Color(0xFFF9A825)
private val Blue = Color(0xFF1976D2)
private val Green = Color(0xFF388E3C)

private val PIECE_HEIGHT = 28.dp

/** The drawable for a glyph, and how far to turn it: the one arrow serves four directions. */
private fun Glyph.resource(): Int = when (this) {
    Glyph.HOME -> R.drawable.ic_glyph_home
    Glyph.BACK -> R.drawable.ic_glyph_undo
    Glyph.PLAY -> R.drawable.ic_glyph_play
    Glyph.SQUARE -> R.drawable.ic_glyph_square
    Glyph.UP, Glyph.DOWN, Glyph.LEFT, Glyph.RIGHT -> R.drawable.ic_glyph_arrow
    Glyph.TIMER -> R.drawable.ic_glyph_timer
}

private fun Glyph.rotation(): Float = when (this) {
    Glyph.RIGHT -> 90f
    Glyph.DOWN -> 180f
    Glyph.LEFT -> 270f
    else -> 0f
}

/** One piece as [PieceSpec] describes it: 28dp tall, a circle or an 8dp-cornered rectangle,
 *  its glyph and/or label on the fill; the control's name for a screen reader. */
@Composable
fun Piece(spec: PieceSpec) {
    val (fill, content) = when (spec.fill) {
        PieceFill.GRAY ->
            MaterialTheme.colorScheme.surfaceVariant to MaterialTheme.colorScheme.onSurfaceVariant
        PieceFill.RED -> Red to Color.White
        PieceFill.YELLOW -> Yellow to Color.Black
        PieceFill.BLUE -> Blue to Color.White
        PieceFill.GREEN -> Green to Color.White
    }
    val circle = spec.shape == PieceShape.CIRCLE
    val shape = if (circle) CircleShape else MaterialTheme.shapes.small
    val size = if (circle) Modifier.width(PIECE_HEIGHT) else Modifier.widthIn(min = PIECE_HEIGHT)
    Row(
        modifier = Modifier
            .height(PIECE_HEIGHT)
            .then(size)
            .background(fill, shape)
            .padding(horizontal = if (circle) 0.dp else 10.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterHorizontally),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        spec.glyph?.let { g ->
            Icon(
                painter = painterResource(g.resource()),
                contentDescription = spec.name,
                tint = content,
                modifier = Modifier.size(16.dp).rotate(g.rotation()),
            )
        }
        spec.label?.let {
            Text(it, color = content, style = MaterialTheme.typography.labelLarge, maxLines = 1)
        }
    }
}

/** The pieces of [controls], in that order, wrapping; a "+" between them when [joined]. */
@Composable
private fun Pieces(controls: List<PieceSpec>, joined: Boolean, trailing: PieceSpec? = null) {
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(4.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        controls.forEachIndexed { i, spec ->
            if (joined && i > 0) Plus()
            Piece(spec)
        }
        trailing?.let {
            Box(Modifier.width(4.dp))
            Piece(it)
        }
    }
}

@Composable
private fun Plus() {
    Box(modifier = Modifier.height(PIECE_HEIGHT), contentAlignment = Alignment.Center) {
        Text(
            "+",
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}

/** A trigger as pieces: the modifiers in the fixed order, then the buttons in the table's
 *  order (the lists `describe` joins), "+" between; a timer piece last on a HOLD trigger. */
@Composable
fun Combo(trigger: Trigger) {
    val specs = ThorControls.modifiers(trigger).map { pieceOf(it) } +
        ThorControls.buttons(trigger).map { pieceOf(it) }
    val timer = trigger.holdMs?.takeIf { trigger.style == TriggerStyle.HOLD }?.let { ms ->
        PieceSpec(
            "timer $ms ms", PieceShape.RECT, PieceFill.GRAY, label = "$ms ms", glyph = Glyph.TIMER,
        )
    }
    Pieces(specs, joined = true, trailing = timer)
}

/** What is held, or was pressed, in the order given — the recorder's live row, and a draft
 *  that is not yet a trigger. No "+": nothing is a combo yet. */
@Composable
fun HeldPieces(controls: List<Control>) {
    Pieces(controls.map { pieceOf(it) }, joined = false)
}
