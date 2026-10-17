package com.grardb.thorcuts

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonColors
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * The accent: buttons, the switches' on-state, the rail's selected entry. Material 3's
 * baseline purple until the owner's colour arrives (spec §7), when this constant and
 * [AccentOnDark] (the same hue, lightened for a dark background) change together.
 */
val Accent = Color(0xFF6750A4)
val AccentOnDark = Color(0xFFD0BCFF)

private val LightScheme = lightColorScheme(primary = Accent)
private val DarkScheme = darkColorScheme(primary = AccentOnDark)

/** 8dp on buttons and pieces (`small`), 12dp on cards (`medium`): the web library's look, not
 *  Material 3's pill (the owner's choice). */
private val ThorcutsShapes = Shapes(
    extraSmall = RoundedCornerShape(4.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(12.dp),
    large = RoundedCornerShape(16.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Light, or dark when the system is: Material's own two palettes under the one accent. */
@Composable
fun ThorcutsTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        shapes = ThorcutsShapes,
        content = content,
    )
}

/** A filled button with the theme's `small` corners: Material's `Button` takes its pill from
 *  its own defaults, not from `MaterialTheme.shapes`, so the shape is passed here once. */
@Composable
fun PrimaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit,
) {
    Button(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        content = content,
    )
}

/** An outlined button with the same corners; [colors] for Delete's error-coloured text. */
@Composable
fun SecondaryButton(
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    colors: ButtonColors = ButtonDefaults.outlinedButtonColors(),
    content: @Composable RowScope.() -> Unit,
) {
    OutlinedButton(
        onClick = onClick,
        modifier = modifier,
        enabled = enabled,
        shape = MaterialTheme.shapes.small,
        colors = colors,
        content = content,
    )
}
