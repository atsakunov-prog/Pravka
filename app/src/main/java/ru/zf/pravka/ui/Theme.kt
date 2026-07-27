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

// Editorial identity: paper, ink, and the proofreader's red pen.
// The wide "П" mark, serif headings and the vermilion accent carry the same
// language as the launcher icon and the floating button.

private val Vermilion = Color(0xFFC13B2A)
private val VermilionDeep = Color(0xFF8F2517)
private val VermilionBright = Color(0xFFE0654E)

private val Paper = Color(0xFFF7F3EA)
private val PaperCard = Color(0xFFFEFCF6)
private val PaperDim = Color(0xFFEFE9DC)
private val Ink = Color(0xFF241F19)
private val InkSoft = Color(0xFF6E6659)
private val PaperLine = Color(0xFFDDD5C4)
private val Ochre = Color(0xFF8F6A1E)
private val OchrePale = Color(0xFFF0E3C8)

private val NightBg = Color(0xFF181511)
private val NightCard = Color(0xFF231F19)
private val NightCardHigh = Color(0xFF2A251E)
private val NightText = Color(0xFFECE5D8)
private val NightTextSoft = Color(0xFFA79D8C)
private val NightLine = Color(0xFF3B342A)
private val OchreBright = Color(0xFFD4A54F)

private val LightColors = lightColorScheme(
    primary = Vermilion,
    onPrimary = Color(0xFFFFF8F0),
    primaryContainer = Color(0xFFF6DCD3),
    onPrimaryContainer = VermilionDeep,
    secondary = InkSoft,
    onSecondary = Paper,
    secondaryContainer = PaperDim,
    onSecondaryContainer = Ink,
    tertiary = Ochre,
    onTertiary = Paper,
    tertiaryContainer = OchrePale,
    onTertiaryContainer = Color(0xFF57400F),
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
    primary = VermilionBright,
    onPrimary = Color(0xFF33110A),
    primaryContainer = Color(0xFF5C1D11),
    onPrimaryContainer = Color(0xFFF6C9BD),
    secondary = NightTextSoft,
    onSecondary = NightBg,
    secondaryContainer = NightCardHigh,
    onSecondaryContainer = NightText,
    tertiary = OchreBright,
    onTertiary = Color(0xFF2E2205),
    tertiaryContainer = Color(0xFF4A3A12),
    onTertiaryContainer = Color(0xFFEFDBAE),
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
