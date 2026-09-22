package ru.zf.slushalka.ui

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

// Тот же бумажно-чернильный язык, что у Правки, но своя краска: у слушалки
// синие сумерки вместо оранжевого пера. Тёмная тема здесь не украшение -
// книгу чаще всего слушают вечером и в постели.

private val Ink = Color(0xFF1E1B17)
private val Paper = Color(0xFFF7F3EA)
private val PaperCard = Color(0xFFFEFCF6)
private val PaperDim = Color(0xFFEDE7DA)
private val InkSoft = Color(0xFF6E6659)
private val Dusk = Color(0xFF3F5B8C)
private val DuskDeep = Color(0xFF25395C)
private val DuskPale = Color(0xFFD9E3F3)
private val Amber = Color(0xFFC2762B)

private val NightBg = Color(0xFF14161B)
private val NightCard = Color(0xFF1D2027)
private val NightCardHigh = Color(0xFF242832)
private val NightText = Color(0xFFE7E3DA)
private val NightTextSoft = Color(0xFF9A9689)
private val NightLine = Color(0xFF343945)
private val DuskBright = Color(0xFF8FB0E4)

private val LightColors = lightColorScheme(
    primary = Dusk,
    onPrimary = Color(0xFFF7FAFF),
    primaryContainer = DuskPale,
    onPrimaryContainer = DuskDeep,
    secondary = InkSoft,
    onSecondary = Paper,
    secondaryContainer = Color(0xFFF0E6D3),
    onSecondaryContainer = Color(0xFF5A3F14),
    tertiary = Amber,
    onTertiary = Color(0xFFFFFBF4),
    tertiaryContainer = Color(0xFFF7E4C6),
    onTertiaryContainer = Color(0xFF5A3A0B),
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
    outline = Color(0xFFB2A896),
    outlineVariant = Color(0xFFDBD3C2),
    error = Color(0xFFA8261B),
    onError = Color(0xFFFFF8F0),
)

private val DarkColors = darkColorScheme(
    primary = DuskBright,
    onPrimary = Color(0xFF0E1B2E),
    primaryContainer = Color(0xFF2A3F63),
    onPrimaryContainer = Color(0xFFD3E1F7),
    secondary = NightTextSoft,
    onSecondary = NightBg,
    secondaryContainer = Color(0xFF39332A),
    onSecondaryContainer = Color(0xFFEDE0C8),
    tertiary = Color(0xFFE2A863),
    onTertiary = Color(0xFF3A2205),
    tertiaryContainer = Color(0xFF54390F),
    onTertiaryContainer = Color(0xFFF7DFBB),
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
    outline = Color(0xFF6A6558),
    outlineVariant = NightLine,
    error = Color(0xFFE57366),
    onError = Color(0xFF3A0D06),
)

/**
 * Для электронной книги: только чёрный, белый и два серых. Цвет на e-ink
 * превращается в бледно-серое, и синие кнопки и кремовые карточки там почти
 * не видны - контраст держит чёрное на белом.
 */
private val EinkColors = lightColorScheme(
    primary = Color.Black,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFE6E6E6),
    onPrimaryContainer = Color.Black,
    secondary = Color(0xFF333333),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFEDEDED),
    onSecondaryContainer = Color.Black,
    tertiary = Color.Black,
    onTertiary = Color.White,
    tertiaryContainer = Color(0xFFE6E6E6),
    onTertiaryContainer = Color.Black,
    background = Color.White,
    onBackground = Color.Black,
    surface = Color.White,
    onSurface = Color.Black,
    surfaceVariant = Color(0xFFF0F0F0),
    onSurfaceVariant = Color(0xFF2E2E2E),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color.White,
    surfaceContainer = Color(0xFFF2F2F2),
    surfaceContainerHigh = Color(0xFFF5F5F5),
    surfaceContainerHighest = Color(0xFFF5F5F5),
    outline = Color(0xFF555555),
    outlineVariant = Color(0xFF999999),
    error = Color.Black,
    onError = Color.White,
)

private fun typography(): Typography {
    val base = Typography()
    val serif = FontFamily.Serif
    return base.copy(
        displaySmall = base.displaySmall.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        headlineLarge = base.headlineLarge.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        headlineMedium = base.headlineMedium.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        headlineSmall = base.headlineSmall.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        titleLarge = base.titleLarge.copy(fontFamily = serif, fontWeight = FontWeight.Bold),
        titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
        bodyLarge = base.bodyLarge.copy(lineHeight = 25.sp),
        labelMedium = base.labelMedium.copy(letterSpacing = 0.8.sp),
    )
}

private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(14.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun SlushalkaTheme(eink: Boolean = false, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = when {
            eink -> EinkColors
            isSystemInDarkTheme() -> DarkColors
            else -> LightColors
        },
        typography = typography(),
        shapes = AppShapes,
        content = content,
    )
}
