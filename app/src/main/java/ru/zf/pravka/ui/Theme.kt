package ru.zf.pravka.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
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

private val AccentBright = Color(0xFFFB923C)

// Ночь: текст живёт в плашках, фон под ними — тёмный (владелец, 15.09, второй
// заход: «плашки должны быть на более тёмном фоне»); сами плашки на три тона
// светлее фона, и на них — едва заметный узор знаков режима (ui/Frame.kt).
//
// 20.09.2026 плашки подняли и почистили: «кнопки внизу темноватые — выглядит
// как будто они немного грязные (и плашки тоже)». Грязь была не в узоре, а в
// самом тоне: тёмный, тёплый и почти бесцветный — та самая зона, где глаз
// читает не «дерево», а «пыль». Фон ушёл глубже, плашки поднялись и стали
// чуть нейтральнее, линии посветлели; заодно у плашек появилась фаска
// (`ui/Blocks.kt`), та же, что у кнопок на стекле, — она и делает половину
// работы, потому что грязным выглядит не цвет, а плоскость.
// Версия 3 (26.09.2026): фон ещё на ступень глубже — на нём читается свечение
// режима (`ui/Glow.kt`), а плашки остаются на три тона светлее.
private val NightBg = Color(0xFF100F0D)
private val NightCard = Color(0xFF332F2A)
private val NightCardHigh = Color(0xFF3D3934)
private val NightText = Color(0xFFF0EADF)
private val NightTextSoft = Color(0xFFB2A896)
private val NightLine = Color(0xFF4C463C)
private val WaveBright = Color(0xFF6CC3DD)

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

/**
 * Тема приложения — всегда ночь (версия 3, владелец 26.09.2026: «зафиксируй
 * тёмную тему везде, потому что у Марианны светлая тема, и выглядит это не
 * очень красиво»). Светлая схема жила с первых сборок, но всё, что сделано
 * после, — плашки с фаской и зерном, пилюля, свечение режима, стекло диска —
 * рисовалось и проверялось на тёмном; на бумажном фоне те же слои читались
 * грязью. Системная тема телефона приложение больше не перекрашивает.
 */
@Composable
fun PravkaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = DarkColors,
        typography = pravkaTypography(),
        shapes = PravkaShapes,
        content = content,
    )
}
