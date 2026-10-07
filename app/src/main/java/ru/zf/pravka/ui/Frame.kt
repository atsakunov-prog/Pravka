package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.painter.Painter
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.rememberVectorPainter
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

// Каркас вкладки: шапка с названием и служебными значками справа и фон с
// разбросанными знаками режима. Владелец (15.09.2026): «справа наверху в
// каждой вкладке — шестерёнка настроек именно этого режима, кнопка
// статистики и доллар; название красиво, полоской; пояснения под названием
// убрать — и так понятно». Один composable на все вкладки, чтобы шапки не
// расползлись, как расползались заголовки до этого.

/**
 * Режим экрана — его цвета (`ui/Tokens.kt`, `Modes.of`), свет и монета.
 * До 4.0 у режима был ещё узор знаков на плашках; DESIGN 4.0 знаки режима на
 * фоне запрещает («вещь, а не обои»), узор снят 07.10.2026.
 * [TODAY] — «Сегодня» и Общая статистика: нейтральный крем, цвета режимов
 * там — только метки источника.
 */
enum class ModeDecor {
    PRAVKA,
    ZASECHKA,
    DELA,
    SPORT,
    FOOD,
    MONEY,
    TODAY,
    SERVICE,
}

/** Режим текущего экрана — для деталей, которым мало цветов (`LocalMode`). */
val LocalModeDecor = compositionLocalOf<ModeDecor?> { null }

/**
 * Экран режима целиком: цвета режима (`LocalMode`, краска Material —
 * [tint]), свет режима сверху (`ui/Glow.kt`) и содержимое. Фон — ночь
 * `#100F0D`; свет лежит под содержимым и к середине экрана сходит в неё.
 */
@Composable
fun ModeFrame(decor: ModeDecor, content: @Composable () -> Unit) {
    val mode = Modes.of(decor)
    MaterialTheme(
        colorScheme = decor.tint(MaterialTheme.colorScheme),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        val glow = remember(decor) { GlowState() }
        // Приглушение света — своё у каждого экрана: общее на всё приложение
        // (как было) оставляло режим тусклым после сжатой шапки «Сегодня», а в
        // карусели (07.10.2026) гасило и соседа на время взмаха.
        val dim = remember(decor) { mutableFloatStateOf(1f) }
        CompositionLocalProvider(
            LocalModeDecor provides decor,
            LocalMode provides mode,
            LocalGlowState provides glow,
            LocalGlowDim provides dim,
        ) {
            Box(Modifier.fillMaxSize()) {
                ModeGlowLayer(decor)
                content()
            }
        }
    }
}

/**
 * Шапка экрана — Правка 4.0 (DESIGN §11.2 ModeHeader): «‹», монета режима
 * (у служебных экранов — значок), название Literata 24, второй тон — под
 * названием ([titleExtra] — главный выбор, тап — [onTitleExtra]), справа —
 * значки без капсулы. Строка состояния — над шапкой (`statusBarsPadding`).
 */
@Composable
fun TabHeader(
    title: String,
    icon: Painter? = null,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    glyph: ImageVector? = null,
    titleExtra: String? = null,
    onTitleExtra: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val decor = LocalModeDecor.current
    ModeHeader(
        title = title,
        decor = decor,
        onBack = onBack,
        subtitle = titleExtra ?: subtitle,
        onSubtitle = onTitleExtra,
        glyph = glyph,
        actions = actions,
        modifier = Modifier.statusBarsPadding(),
    )
}

/** Значок действия в шапке — 22 dp в цели 44, без капсулы. */
@Composable
fun HeaderAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null) =
    HeaderIcon(icon, description, onClick, tint)

/** «$» — стоимость Claude (attach_money, DESIGN §9). */
@Composable
fun CostAction(onClick: () -> Unit) = HeaderIcon(Glyphs.Dollar, "стоимость Claude", onClick)

/** Шестерёнка: настройки именно этого режима. */
@Composable
fun SettingsAction(onClick: () -> Unit) = HeaderIcon(Glyphs.Gear, "настройки режима", onClick)

/** Статистика — Общая статистика (donut_small, DESIGN §9). */
@Composable
fun StatsAction(onClick: () -> Unit) = HeaderIcon(Glyphs.Stats, "статистика", onClick)

/** Выгрузка: стрелка из лотка. */
@Composable
fun ExportAction(onClick: () -> Unit) = HeaderIcon(Glyphs.Export, "выгрузка", onClick)

// ---------------------------------------------------------------------------
// Край ленты под шапкой
// ---------------------------------------------------------------------------

/**
 * Сколько ленты растворяется у верхнего края, когда её листают, dp. Владелец
 * (27.09.2026): «когда я листаю вниз приложение, то там наверху тогда резкая
 * полоса». Лента обрезается ровно по своему краю под шапкой, и плашка,
 * уходящая вверх, срезалась ножом поверх света. Теперь край — не нож, а
 * туман: верхние [SCROLL_FADE_DP] ленты сходят в прозрачность, и плашка
 * уходит под шапку в свет, как у Gemini.
 */
const val SCROLL_FADE_DP = 40

/**
 * Растворение верха ленты по её состоянию. Пока лента стоит в начале, края нет
 * вовсе: пилюля первой строкой не должна тонуть в тумане. Туман набирается
 * вместе с первыми [SCROLL_FADE_DP] прокрутки — без щелчка.
 */
fun Modifier.scrollFade(state: LazyListState): Modifier = scrollFade {
    if (state.firstVisibleItemIndex > 0) Float.MAX_VALUE else state.firstVisibleItemScrollOffset.toFloat()
}

fun Modifier.scrollFade(state: ScrollState): Modifier = scrollFade { state.value.toFloat() }

/**
 * Прокрутка колонки с растворением верха — замена `verticalScroll(rememberScrollState())`
 * на экранах вкладок: одна строка вместо двух в каждом.
 */
@Composable
fun Modifier.fadingScroll(): Modifier {
    val state = rememberScrollState()
    return this.scrollFade(state).verticalScroll(state)
}

/**
 * Туман рисуется маской поверх ленты (`DstIn`) — ей нужен свой буфер
 * (`Offscreen`), иначе маска вырезала бы и свет с фоном под лентой. Буфер
 * заводится только пока лента сдвинута: в покое слой рисуется прямо.
 * Состояние читается в фазах слоя и рисования — прокрутка не пересобирает экран.
 */
private fun Modifier.scrollFade(scrolled: () -> Float): Modifier = this
    .graphicsLayer {
        compositingStrategy = if (scrolled() > 0f) CompositingStrategy.Offscreen else CompositingStrategy.Auto
    }
    .drawWithContent {
        drawContent()
        val fade = SCROLL_FADE_DP.dp.toPx()
        val k = (scrolled() / fade).coerceIn(0f, 1f)
        if (k <= 0f) return@drawWithContent
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black.copy(alpha = 1f - k),
                1f to Color.Black,
                startY = 0f,
                endY = fade,
            ),
            size = Size(size.width, fade),
            blendMode = BlendMode.DstIn,
        )
    }

/**
 * Низ ленты тает под строкой «сказать» (DESIGN §10, маски макетов: лента
 * сходит в прозрачность над строкой): последние [fade] dp перед зоной
 * строки [bar] — в прозрачность, сама зона — пустая. Буфер слоя — только
 * ради маски `DstIn`.
 */
fun Modifier.bottomFade(bar: androidx.compose.ui.unit.Dp = 80.dp, fade: androidx.compose.ui.unit.Dp = 36.dp): Modifier = this
    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
    .drawWithContent {
        drawContent()
        val b = bar.toPx()
        val f = fade.toPx()
        val h = size.height
        drawRect(
            Brush.verticalGradient(
                0f to Color.Black,
                ((h - b - f) / h).coerceIn(0f, 1f) to Color.Black,
                ((h - b) / h).coerceIn(0f, 1f) to Color.Transparent,
                1f to Color.Transparent,
            ),
            blendMode = BlendMode.DstIn,
        )
    }
