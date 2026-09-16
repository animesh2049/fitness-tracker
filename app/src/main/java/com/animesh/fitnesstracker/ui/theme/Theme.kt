package com.animesh.fitnesstracker.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val DarkScheme = darkColorScheme(
    primary = Tokens.Accent,
    onPrimary = Tokens.AccentInk,
    primaryContainer = Tokens.AccentSurface,
    onPrimaryContainer = Tokens.AccentText,
    secondary = Tokens.Warning,
    onSecondary = Tokens.WarningInk,
    tertiary = Tokens.Muted,
    background = Tokens.Ground,
    onBackground = Tokens.Text,
    surface = Tokens.Ground,
    onSurface = Tokens.Text,
    surfaceVariant = Tokens.Surface,
    onSurfaceVariant = Tokens.Muted,
    surfaceContainer = Tokens.Surface,
    surfaceContainerHigh = Tokens.Surface2,
    surfaceContainerHighest = Tokens.Surface2,
    surfaceContainerLow = Tokens.Surface,
    surfaceContainerLowest = Tokens.Ground,
    outline = Tokens.Border,
    outlineVariant = Tokens.BorderStrong,
    error = Tokens.Danger,
    onError = Tokens.AccentInk,
    inverseSurface = Tokens.Text,
    inverseOnSurface = Tokens.Ground
)

val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(8.dp),
    medium = RoundedCornerShape(10.dp),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(14.dp)
)

@Composable
fun WorkoutTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content
    )
}
