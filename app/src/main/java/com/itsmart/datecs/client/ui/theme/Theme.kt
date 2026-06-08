package com.itsmart.datecs.client.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable

private val DatecsColorScheme = lightColorScheme(
    primary = AppColors.Primary,
    onPrimary = androidx.compose.ui.graphics.Color.White,
    primaryContainer = AppColors.PrimaryLight,
    secondary = AppColors.Accent,
    onSecondary = androidx.compose.ui.graphics.Color.White,
    background = AppColors.Background,
    surface = AppColors.Card,
    onBackground = AppColors.TextPrimary,
    onSurface = AppColors.TextPrimary,
    error = AppColors.Danger,
    onError = androidx.compose.ui.graphics.Color.White,
)

@Composable
fun DatecsServiceTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DatecsColorScheme,
        typography = DatecsTypography,
        content = content
    )
}
