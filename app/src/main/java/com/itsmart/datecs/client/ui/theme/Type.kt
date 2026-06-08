package com.itsmart.datecs.client.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

val MonoFont = FontFamily.Monospace

val DatecsTypography = Typography(
    headlineLarge = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 20.sp,
        color = AppColors.TextPrimary
    ),
    headlineMedium = TextStyle(
        fontWeight = FontWeight.Bold,
        fontSize = 16.sp,
        color = AppColors.TextPrimary
    ),
    titleMedium = TextStyle(
        fontWeight = FontWeight.W700,
        fontSize = 13.sp,
        letterSpacing = 0.8.sp
    ),
    bodyLarge = TextStyle(
        fontSize = 14.sp,
        color = AppColors.TextPrimary
    ),
    bodyMedium = TextStyle(
        fontSize = 12.sp,
        color = AppColors.TextSecondary
    ),
    bodySmall = TextStyle(
        fontSize = 10.5.sp,
        color = AppColors.TextTertiary
    ),
    labelMedium = TextStyle(
        fontWeight = FontWeight.W700,
        fontSize = 10.5.sp,
        letterSpacing = 0.6.sp,
        color = AppColors.TextSecondary
    ),
)
