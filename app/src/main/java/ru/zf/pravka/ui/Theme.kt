package ru.zf.pravka.ui

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

// Правка 4.0 (07.10.2026): цвета — из `ui/Tokens.kt` (DESIGN §4.1), шрифты —
// Literata и Golos Text (`ui/Type.kt`). Вторичный текст тёплый, а не серый:
// серое на тёплой ночи читалось грязью; ошибки — тем же тёплым `warn`, что
// предупреждения «Сегодня», — новых цветов для состояний нет (DESIGN §3.3).
private val DarkColors = darkColorScheme(
    primary = Modes.Pravka.tint,
    onPrimary = Modes.Pravka.ink,
    primaryContainer = Modes.Pravka.key,
    onPrimaryContainer = Modes.Pravka.value,
    secondary = Ink.TextSecondary,
    onSecondary = Ink.Bg,
    secondaryContainer = Color(0xFF2A221B),
    onSecondaryContainer = Ink.Text,
    tertiary = Ink.Cream,
    onTertiary = Ink.NowInk,
    tertiaryContainer = Color(0xFF2A221B),
    onTertiaryContainer = Ink.Text,
    background = Ink.Bg,
    onBackground = Ink.Text,
    surface = Ink.Bg,
    onSurface = Ink.Text,
    surfaceVariant = Color(0xFF2A231C),
    onSurfaceVariant = Ink.TextSecondary,
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF17130F),
    surfaceContainerLow = Color(0xFF1E1914),
    surfaceContainer = Color(0xFF221C16),
    surfaceContainerHigh = Color(0xFF2A231C),
    surfaceContainerHighest = Color(0xFF312922),
    outline = Color(0xFF6E5844),
    outlineVariant = Color(0xFF3A2F25),
    error = Ink.Warn,
    onError = Ink.NowInk,
    errorContainer = Color(0xFF4A2214),
    onErrorContainer = Color(0xFFFFD9C7),
    scrim = Color(0xFF080706),
)

private val PravkaShapes = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
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
    val type = remember { PravkaType() }
    MaterialTheme(
        colorScheme = DarkColors,
        typography = remember(type) { pravkaM3Typography(type) },
        shapes = PravkaShapes,
    ) {
        CompositionLocalProvider(LocalPravkaType provides type, content = content)
    }
}
