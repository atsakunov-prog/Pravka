package ru.zf.pravka.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.unit.dp

// Правка 4.0 (07.10.2026): цвета — из `ui/Tokens.kt` (DESIGN §4.1), шрифты —
// Literata и Golos Text (`ui/Type.kt`). Вторичный текст тёплый, а не серый:
// серое на тёплой ночи читалось грязью; ошибки — тем же тёплым `warn`, что
// предупреждения «Сегодня», — новых цветов для состояний нет (DESIGN §3.3).
//
// Правка 5.0 (13.10.2026): с завода — вид v2 Ковчега (`ui/Look.kt`), светлый
// или тёмный, как в системе или выбором; 4.0 — за переключателем «Вид 4.0».
private val DarkColors4 = darkColorScheme(
    primary = Color(0xFFF5925A),
    onPrimary = Color(0xFF2E1608),
    primaryContainer = Color(0xFFEA580C),
    onPrimaryContainer = Color(0xFFFFD2B5),
    secondary = Color(0xFFC9B9A2),
    onSecondary = Color(0xFF100F0D),
    secondaryContainer = Color(0xFF2A221B),
    onSecondaryContainer = Color(0xFFF0EADF),
    tertiary = Color(0xFFF1E8D8),
    onTertiary = Color(0xFF2A1E12),
    tertiaryContainer = Color(0xFF2A221B),
    onTertiaryContainer = Color(0xFFF0EADF),
    background = Color(0xFF100F0D),
    onBackground = Color(0xFFF0EADF),
    surface = Color(0xFF100F0D),
    onSurface = Color(0xFFF0EADF),
    surfaceVariant = Color(0xFF2A231C),
    onSurfaceVariant = Color(0xFFC9B9A2),
    surfaceTint = Color.Transparent,
    surfaceContainerLowest = Color(0xFF17130F),
    surfaceContainerLow = Color(0xFF1E1914),
    surfaceContainer = Color(0xFF221C16),
    surfaceContainerHigh = Color(0xFF2A231C),
    surfaceContainerHighest = Color(0xFF312922),
    outline = Color(0xFF6E5844),
    outlineVariant = Color(0xFF3A2F25),
    error = Color(0xFFF27A45),
    onError = Color(0xFF2A1E12),
    errorContainer = Color(0xFF4A2214),
    onErrorContainer = Color(0xFFFFD9C7),
    scrim = Color(0xFF080706),
)

/**
 * Схема Material для вида v2 из токенов [KvPalette]: старые экраны говорят на
 * языке M3 (`colorScheme.primary`, `surfaceVariant`…), и через неё они
 * переезжают в новый вид без правки каждого. `surface` — панель, а не фон:
 * листы, меню и поля M3 ложатся белыми панелями на тёплый фон, как в вебе.
 */
private fun kvScheme(p: KvPalette, dark: Boolean): ColorScheme {
    val base = if (dark) darkColorScheme() else lightColorScheme()
    return base.copy(
        primary = p.accent,
        onPrimary = p.accentInk,
        primaryContainer = p.accentSoft,
        onPrimaryContainer = p.accent,
        inversePrimary = p.accent,
        secondary = p.muted,
        onSecondary = p.panel,
        secondaryContainer = p.panel2,
        onSecondaryContainer = p.text,
        tertiary = p.accent,
        onTertiary = p.accentInk,
        tertiaryContainer = p.accentSoft,
        onTertiaryContainer = p.accent,
        background = p.bg,
        onBackground = p.text,
        surface = p.panel,
        onSurface = p.text,
        surfaceVariant = p.panel2,
        onSurfaceVariant = p.muted,
        surfaceTint = Color.Transparent,
        surfaceBright = p.panel,
        surfaceDim = p.bg,
        surfaceContainerLowest = p.panel,
        surfaceContainerLow = p.panel,
        surfaceContainer = p.panel,
        surfaceContainerHigh = p.panel2,
        surfaceContainerHighest = p.panel2,
        inverseSurface = p.text,
        inverseOnSurface = p.bg,
        outline = p.check,
        outlineVariant = p.line,
        error = p.bad,
        onError = if (dark) Color(0xFF141414) else Color.White,
        errorContainer = p.bad.copy(alpha = 0.12f).compositeOver(p.panel),
        onErrorContainer = p.bad,
        scrim = Color(0xFF14120E),
    )
}


private val PravkaShapes4 = Shapes(
    extraSmall = RoundedCornerShape(7.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(22.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

/** Формы v2: метки 5, кнопки и поля 8, панели 10, листы и меню 12. */
private val KvShapes = Shapes(
    extraSmall = RoundedCornerShape(KvShape.Tag),
    small = RoundedCornerShape(KvShape.Control),
    medium = RoundedCornerShape(KvShape.Panel),
    large = RoundedCornerShape(12.dp),
    extraLarge = RoundedCornerShape(12.dp),
)

/**
 * Тема приложения. Вид 4.0 — всегда ночь (версия 3, владелец 26.09.2026:
 * «зафиксируй тёмную тему везде, потому что у Марианны светлая тема, и
 * выглядит это не очень красиво»): стекло, свечение и фаски рисовались и
 * проверялись только на тёмном. Вид v2 (Правка 5.0) — плоский, его светлая
 * и тёмная темы — те же, что у веба Ковчега, поэтому тема телефона снова в
 * деле: «как в системе», «светлая» или «тёмная» (`Look.theme`).
 *
 * Тёмность решается здесь — только композиция знает тему системы — и
 * пишется в `Look.dark` до того, как её прочтёт хоть один экран: тема — корень
 * дерева. Текст без своего цвета берёт `onBackground` (раньше — чёрный по
 * умолчанию Compose, на ночи он просто не был виден).
 */
@Composable
fun PravkaTheme(content: @Composable () -> Unit) {
    val system = isSystemInDarkTheme()
    val v4 = Look.v4
    val dark = when {
        v4 -> true
        Look.theme == Look.Theme.DARK -> true
        Look.theme == Look.Theme.LIGHT -> false
        else -> system
    }
    if (Snapshot.withoutReadObservation { Look.dark } != dark) Look.dark = dark
    val type = remember(v4) { if (v4) PravkaType() else PravkaType.v2() }
    // Схема M3 — от текущей палитры: с этапа 7 её цвета может поменять Свод (`ui.look`, `Look.tokens`).
    val palette = if (dark) Look.paletteDark else Look.paletteLight
    val colors = if (v4) DarkColors4 else remember(palette, dark) { kvScheme(palette, dark) }
    MaterialTheme(
        colorScheme = colors,
        typography = remember(type) { pravkaM3Typography(type) },
        shapes = if (v4) PravkaShapes4 else KvShapes,
    ) {
        CompositionLocalProvider(
            LocalPravkaType provides type,
            LocalContentColor provides colors.onBackground,
            content = content,
        )
    }
}
