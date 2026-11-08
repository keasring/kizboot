package org.kiz.boot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val KizColorScheme = darkColorScheme(
    primary = KizColors.accent,
    onPrimary = Color.Black,
    primaryContainer = KizColors.accentDim,
    onPrimaryContainer = Color.White,
    secondary = KizColors.accent,
    onSecondary = Color.Black,
    secondaryContainer = KizColors.accentDim,
    onSecondaryContainer = Color.White,
    tertiary = KizColors.accent,
    background = KizColors.background,
    onBackground = KizColors.textPrimary,
    surface = KizColors.surface,
    onSurface = KizColors.textPrimary,
    surfaceVariant = KizColors.surfaceVariant,
    onSurfaceVariant = KizColors.textSecondary,
    outline = KizColors.outlineDim,
    error = KizColors.danger,
    onError = Color.Black,
    surfaceContainer = KizColors.surface,
    surfaceContainerHigh = KizColors.surfaceVariant,
    surfaceContainerHighest = KizColors.surfaceVariant
)

/** Приложение всегда работает в тёмной теме. */
@Composable
fun KizBootTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = KizColorScheme,
        typography = KizTypography,
        content = content
    )
}
