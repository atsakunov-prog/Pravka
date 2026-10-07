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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
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
        CompositionLocalProvider(
            LocalModeDecor provides decor,
            LocalMode provides mode,
            LocalGlowState provides glow,
        ) {
            Box(Modifier.fillMaxSize()) {
                ModeGlowLayer(decor)
                content()
            }
        }
    }
}

/**
 * Шапка вкладки: пиктограмма режима в плашке краской режима, название с
 * засечками, справа — значки действий. Служебные экраны под
 * «Ещё» дают [onBack] — тогда слева стоит «‹».
 */
@Composable
fun TabHeader(
    title: String,
    icon: Painter? = null,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    glyph: ImageVector? = null,
    /**
     * Второй тон названия — главный выбор вкладки, как «Pro Extended ⌄» у
     * Gemini (версия 3): у Правки — модель чистки, у Денег — «Личное · ЗФ».
     * Только там, где выбор настоящий; тап — [onTitleExtra].
     */
    titleExtra: String? = null,
    onTitleExtra: (() -> Unit)? = null,
    actions: (@Composable RowScope.() -> Unit)? = null,
) {
    val badge = icon ?: glyph?.let { rememberVectorPainter(it) }
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Icon(
                    Glyphs.Back,
                    contentDescription = "назад",
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(24.dp),
                )
            }
        }
        if (badge != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    badge,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            // Полоски под названием больше нет (владелец, 24.09.2026:
            // «подчёркивания под названиями какие-то странные»). Короткая
            // черта одной длины под словами разной длины читалась ссылкой
            // или опечаткой вёрстки; режим теперь держит значок в краске
            // режима слева, а название — просто название.
            if (titleExtra == null) {
                Text(
                    title,
                    style = MaterialTheme.typography.headlineSmall,
                    maxLines = 1,
                )
            } else {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(12.dp))
                        .then(if (onTitleExtra != null) Modifier.clickable(onClick = onTitleExtra) else Modifier),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                    )
                    Spacer(Modifier.width(8.dp))
                    Text(
                        titleExtra,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Normal,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false),
                    )
                    if (onTitleExtra != null) {
                        Icon(
                            Glyphs.ChevronDown,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(start = 2.dp).size(16.dp),
                        )
                    }
                }
            }
            if (subtitle != null) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    modifier = Modifier.padding(top = 3.dp),
                )
            }
        }
        if (actions != null) HeaderCapsule(actions)
    }
}

/**
 * Значки шапки — в одной стеклянной капсуле, как карандаш с тремя точками
 * у Gemini (версия 3). Не прихоть: тонкие серые значки на свете режима
 * тонут, капсула даёт им подложку — тёмное стекло с той же фаской, что у
 * плашек, светлой сверху и тёмной снизу.
 */
@Composable
private fun HeaderCapsule(actions: @Composable RowScope.() -> Unit) {
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .clip(shape)
            .background(CAPSULE_GLASS)
            .border(
                1.dp,
                Brush.verticalGradient(
                    0f to Color.White.copy(alpha = 0.12f),
                    0.5f to Color.Transparent,
                    1f to Color.Black.copy(alpha = 0.3f),
                ),
                shape,
            )
            .padding(horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
        content = actions,
    )
}

/** Тёмное стекло капсулы: ночь фона на просвет — свет режима под ней чуть виден. */
private val CAPSULE_GLASS = Color(0x8C0C0B09)

/** Значок действия в шапке: штриховая пиктограмма цветом второго плана. */
@Composable
fun HeaderAction(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color? = null) {
    IconButton(onClick = onClick) {
        Icon(
            icon,
            contentDescription = description,
            tint = tint ?: MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Доллар в шапке — стоимость обращений к API. Знак шрифтом, а не картинкой: он и есть надпись. */
@Composable
fun CostAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Text(
            "$",
            fontSize = 20.sp,
            fontFamily = FontFamily.Serif,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Шестерёнка: настройки именно этого режима. Тем же штрихом, что остальные значки. */
@Composable
fun SettingsAction(onClick: () -> Unit) = HeaderAction(Glyphs.Gear, "настройки режима", onClick)

/** Статистика: круговая диаграмма — круглая, в пару шестерёнке, и не путается с часами Засечки. */
@Composable
fun StatsAction(onClick: () -> Unit) = HeaderAction(Glyphs.Stats, "статистика", onClick)

/** Выгрузка: стрелка из лотка. */
@Composable
fun ExportAction(onClick: () -> Unit) = HeaderAction(Glyphs.Export, "выгрузка", onClick)

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
const val SCROLL_FADE_DP = 24

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
