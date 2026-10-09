package com.cutviet.editor.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Accent = Color(0xFF00E5C8)
val AccentPink = Color(0xFFFF3D6E)
val Surface1 = Color(0xFF16161A)
val Surface2 = Color(0xFF222228)
val Surface3 = Color(0xFF2E2E36)
val TextDim = Color(0xFF9A9AA6)

private val scheme = darkColorScheme(
    primary = Accent,
    onPrimary = Color(0xFF00201C),
    secondary = AccentPink,
    onSecondary = Color.White,
    background = Color.Black,
    onBackground = Color.White,
    surface = Surface1,
    onSurface = Color.White,
    surfaceVariant = Surface2,
    onSurfaceVariant = TextDim,
    surfaceContainer = Surface1,
    surfaceContainerHigh = Surface2,
    surfaceContainerHighest = Surface3,
    outline = Color(0xFF44444E),
)

@Composable
fun CutVietTheme(content: @Composable () -> Unit) {
    MaterialTheme(colorScheme = scheme, content = content)
}
