package com.walkietalkie.ptt.desktop.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color

/** Palette "radio tattica": scura, con accenti arancio, verde acqua per chi parla e rosso per SOS. */
object WalkieColors {
    val BgTop = Color(0xFF0B1020)
    val BgBottom = Color(0xFF18132A)
    val Glass = Color(0x14FFFFFF)
    val GlassStrong = Color(0x24FFFFFF)
    val Stroke = Color(0x1FFFFFFF)
    val Orange = Color(0xFFFF7A1A)
    val Amber = Color(0xFFFFB547)
    val Teal = Color(0xFF2DD4BF)
    val Red = Color(0xFFFF3B4E)
    val RedDark = Color(0xFFB0102A)
    val Text = Color(0xFFE8ECF5)
    val Muted = Color(0xFF8A93A8)

    val background = Brush.verticalGradient(listOf(BgTop, BgBottom))
    val accent = Brush.linearGradient(listOf(Orange, Amber))
    val sos = Brush.linearGradient(listOf(Red, RedDark))

    private val avatarPalette = listOf(
        Color(0xFFFF7A1A), Color(0xFF2DD4BF), Color(0xFF8B5CF6), Color(0xFF38BDF8),
        Color(0xFFF472B6), Color(0xFFA3E635), Color(0xFFFACC15), Color(0xFFFB7185),
    )

    /** Ogni persona ha sempre lo stesso colore, ricavato dal nome. */
    fun forName(name: String): Color = avatarPalette[Math.floorMod(name.hashCode(), avatarPalette.size)]
}

@Composable
fun WalkieTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = WalkieColors.Orange,
            onPrimary = Color.White,
            secondary = WalkieColors.Teal,
            tertiary = WalkieColors.Red,
            background = WalkieColors.BgTop,
            surface = WalkieColors.BgTop,
            surfaceVariant = Color(0xFF1C2238),
            onSurface = WalkieColors.Text,
            onSurfaceVariant = WalkieColors.Muted,
            outline = WalkieColors.Muted,
            error = WalkieColors.Red,
        ),
        content = content,
    )
}
