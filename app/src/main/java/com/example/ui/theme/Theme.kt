package com.example.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp

private val JarvisDarkColorScheme = darkColorScheme(
    primary = ElectricCyan,
    onPrimary = ObsidianVoid,
    primaryContainer = SurfaceElevated,
    onPrimaryContainer = ElectricCyan,
    secondary = ElectricViolet,
    onSecondary = HudWhite,
    secondaryContainer = SurfaceGlass,
    onSecondaryContainer = ElectricViolet,
    tertiary = RoseMagenta,
    onTertiary = ObsidianVoid,
    background = ObsidianVoid,
    onBackground = HudWhite,
    surface = MidnightAbyss,
    onSurface = HudWhite,
    surfaceVariant = SurfaceGlass,
    onSurfaceVariant = HudMuted,
    outline = BorderGlass,
    error = CrimsonPulse,
    onError = HudWhite
)

private val JarvisShapes = Shapes(
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(20.dp),
    large = RoundedCornerShape(28.dp),
    extraLarge = RoundedCornerShape(36.dp)
)

@Composable
fun JarvisTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = JarvisDarkColorScheme,
        typography = JarvisTypography,
        shapes = JarvisShapes,
        content = content
    )
}
