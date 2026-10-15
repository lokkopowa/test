package com.walkietalkie.ptt.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Orange = Color(0xFFE65100)
private val OrangeLight = Color(0xFFFFB74D)

@Composable
fun WalkieTheme(content: @Composable () -> Unit) {
    val colors = if (isSystemInDarkTheme()) {
        darkColorScheme(primary = OrangeLight, secondary = OrangeLight, tertiary = Color(0xFFEF5350))
    } else {
        lightColorScheme(primary = Orange, secondary = Orange, tertiary = Color(0xFFD32F2F))
    }
    MaterialTheme(colorScheme = colors, content = content)
}
