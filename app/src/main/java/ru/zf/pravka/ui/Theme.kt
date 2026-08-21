package ru.zf.pravka.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Editorial identity: paper, ink, and the proofreader's warm pens - the
// red-orange "П", the amber "З", gold and terracotta in the charts, plus one
// голубой accent (tertiary) for contrast. Serif headings carry the same
// language as the launcher icon and the floating buttons.

private val Accent = Color(0xFFEA580C)
private val AccentDeep = Color(0xFF9A3412)
private val AccentBright = Color(0xFFFB923C)

private val Paper = Color(0xFFF7F3EA)
private val PaperCard = Color(0xFFFEFCF6)
private val PaperDim = Color(0xFFEFE9DC)
private val Ink = Color(0xFF241F19)
private val InkSoft = Color(0xFF6E6659)
private val PaperLine = Color(0xFFDDD5C4)
private val Sand = Color(0xFFF6E3C3)
private val Wave = Color(0xFF0E7490)
private val WavePale = Color(0xFFD3ECF4)
private val WaveDeep = Color(0xFF0F4C5C)

private val NightBg = Color(0xFF181511)
private val NightCard = Color(0xFF231F19)
private val NightCardHigh = Color(0xFF2A251E)
private val NightText = Color(0xFFECE5D8)
private val NightTextSoft = Color(0xFFA79D8C)
private val NightLine = Color(0xFF3B342A)
private val WaveBright = Color(0xFF6CC3DD)

private val LightColors = lightColorScheme(
    primary = Accent,
    onPrimary = Color(0xFFFFF8F0),
    primaryContainer = Color(0xFFFAE1CB),
    onPrimaryContainer = AccentDeep,
    secondary = InkSoft,
    onSecondary = Paper,
    secondaryContainer = Sand,
    onSecondaryContainer = Color(0xFF5C3A10),
    tertiary = Wave,
    onTertiary = Color(0xFFF4FBFD),
    tertiaryContainer = WavePale,
    onTertiaryContainer = WaveDeep,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = PaperDim,
    onSurfaceVariant = InkSoft,
    surfaceContainerLowest = PaperCard,
    surfaceContainerLow = PaperCard,
    surfaceContainer = PaperDim,
    surfaceContainerHigh = PaperCard,
    surfaceContainerHighest = PaperCard,
    outline = Color(0xFFB4AA97),
    outlineVariant = PaperLine,
    error = Color(0xFFA8261B),
    onError = Color(0xFFFFF8F0),
    errorContainer = Color(0xFFF6D9D2),
    onErrorContainer = Color(0xFF701408),
)

private val DarkColors = darkColorScheme(
    primary = AccentBright,
    onPrimary = Color(0xFF381603),
    primaryContainer = Color(0xFF6B2E0B),
    onPrimaryContainer = Color(0xFFFAD6B4),
    secondary = NightTextSoft,
    onSecondary = NightBg,
    secondaryContainer = Color(0xFF42351F),
    onSecondaryContainer = Color(0xFFEEDFC2),
    tertiary = WaveBright,
    onTertiary = Color(0xFF06333F),
    tertiaryContainer = Color(0xFF0B4A5A),
    onTertiaryContainer = Color(0xFFC9EAF4),
    background = NightBg,
    onBackground = NightText,
    surface = NightBg,
    onSurface = NightText,
    surfaceVariant = NightCardHigh,
    onSurfaceVariant = NightTextSoft,
    surfaceContainerLowest = NightCard,
    surfaceContainerLow = NightCard,
    surfaceContainer = NightCardHigh,
    surfaceContainerHigh = NightCard,
    surfaceContainerHighest = NightCard,
    outline = Color(0xFF6B6252),
    outlineVariant = NightLine,
    error = Color(0xFFE57366),
    onError = Color(0xFF3A0D06),
    errorContainer = Color(0xFF5F1A0F),
    onErrorContainer = Color(0xFFF6CFC6),
)

// Serif headings (Noto Serif on Pixel) against a sans body: the same
// editorial contrast as the launcher mark.
private fun pravkaTypography(): Typography {
    val base = Typography()
    val serif = FontFamily.Serif
    return base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.8.sp),
    )
}

private val PravkaShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun PravkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        typography = pravkaTypography(),
        shapes = PravkaShapes,
        content = content,
    )
}
