package com.pocketllm.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Text
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/*
 * Monochrome design language: paper white, ink black and a handful of greys.
 * Structure comes from hairline borders, soft shadows and generous space rather
 * than colour, in the spirit of ChatGPT with a touch of Claude's editorial calm.
 */

private val Paper = Color(0xFFFFFFFF)
private val Mist = Color(0xFFF4F4F5)
private val Ash = Color(0xFFEDEDED)
private val Line = Color(0xFFE5E5E5)
private val Smoke = Color(0xFF6E6E73)
private val Graphite = Color(0xFF3A3A3C)
private val Ink = Color(0xFF0D0D0F)

private val Night = Color(0xFF0E0E10)
private val NightRaised = Color(0xFF171719)
private val NightMist = Color(0xFF232326)
private val NightLine = Color(0xFF303033)
private val NightSmoke = Color(0xFFC7C7CC)
private val NightAsh = Color(0xFFEAEAEC)
private val Snow = Color(0xFFF7F7F8)

private val LightColors = lightColorScheme(
    primary = Ink,
    onPrimary = Paper,
    primaryContainer = Mist,
    onPrimaryContainer = Ink,
    inversePrimary = NightAsh,

    secondary = Graphite,
    onSecondary = Paper,
    secondaryContainer = Mist,
    onSecondaryContainer = Ink,

    tertiary = Smoke,
    onTertiary = Paper,
    tertiaryContainer = Mist,
    onTertiaryContainer = Ink,

    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Mist,
    onSurfaceVariant = Smoke,
    surfaceTint = Ink,
    inverseSurface = Ink,
    inverseOnSurface = Snow,

    surfaceBright = Paper,
    surfaceDim = Color(0xFFE8E8EA),
    surfaceContainerLowest = Paper,
    surfaceContainerLow = Color(0xFFFCFCFC),
    surfaceContainer = Color(0xFFF8F8F9),
    surfaceContainerHigh = Color(0xFFF2F2F3),
    surfaceContainerHighest = Ash,

    outline = Line,
    outlineVariant = Ash,

    error = Graphite,
    onError = Paper,
    errorContainer = Mist,
    onErrorContainer = Ink,

    scrim = Color.Black,
)

private val DarkColors = darkColorScheme(
    primary = NightAsh,
    onPrimary = Night,
    primaryContainer = NightMist,
    onPrimaryContainer = NightAsh,
    inversePrimary = Ink,

    secondary = NightSmoke,
    onSecondary = Night,
    secondaryContainer = NightMist,
    onSecondaryContainer = NightAsh,

    tertiary = NightSmoke,
    onTertiary = Night,
    tertiaryContainer = NightRaised,
    onTertiaryContainer = NightAsh,

    background = Night,
    onBackground = NightAsh,
    surface = Night,
    onSurface = NightAsh,
    surfaceVariant = NightRaised,
    onSurfaceVariant = NightSmoke,
    surfaceTint = NightAsh,
    inverseSurface = Snow,
    inverseOnSurface = Night,

    surfaceBright = Color(0xFF2C2C2F),
    surfaceDim = Night,
    surfaceContainerLowest = Color(0xFF09090A),
    surfaceContainerLow = Color(0xFF131315),
    surfaceContainer = Color(0xFF1A1A1C),
    surfaceContainerHigh = Color(0xFF222224),
    surfaceContainerHighest = Color(0xFF2A2A2D),

    outline = NightLine,
    outlineVariant = NightMist,

    error = Color(0xFFF2F2F3),
    onError = Night,
    errorContainer = NightRaised,
    onErrorContainer = NightAsh,

    scrim = Color.Black,
)

private val Base = Typography()

private val AppTypography = Typography(
    // Serif display type gives the empty state and screen titles a Claude-like voice.
    displaySmall = Base.displaySmall.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-1).sp,
    ),
    headlineMedium = Base.headlineMedium.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.75).sp,
    ),
    headlineSmall = Base.headlineSmall.copy(
        fontFamily = FontFamily.Serif,
        fontWeight = FontWeight.Normal,
        letterSpacing = (-0.5).sp,
    ),
    titleLarge = Base.titleLarge.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.3).sp,
    ),
    titleMedium = Base.titleMedium.copy(
        fontWeight = FontWeight.SemiBold,
        letterSpacing = (-0.15).sp,
    ),
    titleSmall = Base.titleSmall.copy(fontWeight = FontWeight.SemiBold),
    bodyLarge = Base.bodyLarge.copy(lineHeight = 24.sp, letterSpacing = 0.sp),
    bodyMedium = Base.bodyMedium.copy(lineHeight = 22.sp, letterSpacing = 0.sp),
    labelLarge = Base.labelLarge.copy(letterSpacing = 0.1.sp),
    labelMedium = Base.labelMedium.copy(letterSpacing = 0.2.sp),
    labelSmall = Base.labelSmall.copy(letterSpacing = 0.4.sp),
)

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(18.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(30.dp),
)

@Composable
fun PocketLlmTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkColors else LightColors,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}

/** Uppercase section label used across the app for a quiet, editorial rhythm. */
@Composable
fun SectionLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        modifier = modifier,
        style = TextStyle(
            fontFamily = FontFamily.SansSerif,
            fontWeight = FontWeight.SemiBold,
            fontSize = 11.sp,
            letterSpacing = 1.2.sp,
        ),
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}
