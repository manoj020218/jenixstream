package com.jenix.stream.ui.theme

import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

// ── Colors ────────────────────────────────────────────────────────────────────
val BgDeep       = Color(0xFF0A0C0F)
val BgSurface    = Color(0xFF111418)
val BgSurface2   = Color(0xFF181C22)
val BorderColor  = Color(0xFF252B34)
val Cyan         = Color(0xFF00E5FF)
val CyanDim      = Color(0xFF007A88)
val Red          = Color(0xFFFF4455)
val RedDim       = Color(0xFF3D0000)
val Green        = Color(0xFF00FF88)
val GreenDim     = Color(0xFF003D1A)
val Amber        = Color(0xFFFFAA00)
val TextPrimary  = Color(0xFFD0D8E8)
val TextSecondary= Color(0xFF8A96A8)
val TextDim      = Color(0xFF5A6578)

private val DarkColorScheme = darkColorScheme(
    primary          = Cyan,
    onPrimary        = Color.Black,
    secondary        = Green,
    onSecondary      = Color.Black,
    error            = Red,
    onError          = Color.White,
    background       = BgDeep,
    onBackground     = TextPrimary,
    surface          = BgSurface,
    onSurface        = TextPrimary,
    surfaceVariant   = BgSurface2,
    onSurfaceVariant = TextSecondary,
    outline          = BorderColor,
)

@Composable
fun JenixStreamTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColorScheme,
        typography = JenixTypography,
        content = content
    )
}

val JenixTypography = Typography(
    headlineLarge = TextStyle(fontWeight = FontWeight.Black, fontSize = 28.sp, color = TextPrimary, letterSpacing = 2.sp),
    headlineMedium = TextStyle(fontWeight = FontWeight.Bold, fontSize = 20.sp, color = TextPrimary, letterSpacing = 1.sp),
    titleLarge = TextStyle(fontWeight = FontWeight.Bold, fontSize = 16.sp, color = TextPrimary, letterSpacing = 1.sp),
    titleMedium = TextStyle(fontWeight = FontWeight.SemiBold, fontSize = 13.sp, color = TextPrimary, letterSpacing = 0.5.sp),
    bodyMedium = TextStyle(fontWeight = FontWeight.Normal, fontSize = 13.sp, color = TextSecondary),
    bodySmall = TextStyle(fontWeight = FontWeight.Normal, fontSize = 11.sp, color = TextDim),
    labelSmall = TextStyle(fontWeight = FontWeight.Medium, fontSize = 10.sp, color = TextDim, letterSpacing = 1.sp),
)
