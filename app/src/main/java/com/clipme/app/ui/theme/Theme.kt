package com.clipme.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

val Ink = Color(0xFF0D0F0E)
val InkElevated = Color(0xFF161918)
val InkSoft = Color(0xFF1E211F)
val Lime = Color(0xFFB8FF00)
val PinkCam = Color(0xFFFF4D8D)
val TextPrimary = Color(0xFFF2F4F1)
val TextSecondary = Color(0xFF9BA29A)
val TextFaint = Color(0xFF626A62)
val LiveRed = Color(0xFFFF2E4D)
val TwitchPurple = Color(0xFF9146FF)
val YtRed = Color(0xFFFF0033)
val Danger = Color(0xFFFF4D4D)
val Good = Color(0xFF3DDC84)

private val ClipMeColors = darkColorScheme(
    primary = Lime,
    onPrimary = Ink,
    secondary = PinkCam,
    onSecondary = Ink,
    tertiary = TwitchPurple,
    background = Ink,
    onBackground = TextPrimary,
    surface = Ink,
    onSurface = TextPrimary,
    surfaceVariant = InkElevated,
    onSurfaceVariant = TextSecondary,
    error = Danger,
    onError = Ink
)

@Composable
fun ClipMeTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = ClipMeColors,
        typography = Typography(),
        content = content
    )
}
