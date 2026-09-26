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
 * Режим вкладки — какие знаки лежат узором на плашках. Знаки — символы
 * шрифта без эмодзи-представления (цветной эмодзи не приглушить альфой):
 * письменные у Правки, циферблаты у Засечки, галочки у Дел, снаряды у
 * спорта, еда у Еды. Владелец (15.09, второй заход): «бэкграунд не вышел —
 * сделаем узор на самих плашках, поплотнее, но еле заметный».
 */
enum class ModeDecor(val glyphs: List<String>) {
    PRAVKA(listOf("¶", "✎", "§", "❝", "„", "✑", "❞", "“")),
    ZASECHKA(listOf("◔", "◑", "◕", "◴", "◵", "◶", "◷", "◐")),
    DELA(listOf("✓", "☐", "•", "→", "✓", "☐", "✓", "•")),
    SPORT(listOf("◎", "▲", "∞", "⚑", "≋", "◇", "◈", "⬡")),
    FOOD(listOf("○", "◌", "❋", "✿", "❀", "⊙", "◍", "✾")),
    MONEY(listOf("₽", "¤", "€", "$", "₽", "%", "₽", "¢")),
    SERVICE(listOf("·", "◦", "·", "◦", "·", "◦", "·", "◦")),
}

/** Режим текущей вкладки — читает `PaperCard`, чтобы положить узор на плашку. */
val LocalModeDecor = compositionLocalOf<ModeDecor?> { null }

/**
 * Узор знаков режима под содержимым плашки. Владелец (15.09, третий заход):
 * «не ровная сетка — сами пиктограммы сильно больше, повёрнуты в разные
 * стороны, как будто развалины, и чтобы было понятно, что это». Поэтому
 * знаки крупные (трёх размеров), разбросаны по редкой решётке со сдвигом и
 * большим случайным смещением, повёрнуты на ±45°, а цвет — цвет текста на
 * семь сотых прозрачности: контур читается, яркость почти как у плашки.
 * Разброс детерминирован (хеш от ряда и колонки): плашка не мерцает при
 * перерисовке. Рисуется в drawBehind, знаки промерены один раз на плашку.
 */
@Composable
fun Modifier.glyphPattern(decor: ModeDecor): Modifier {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.07f)
    val sizesSp = listOf(30, 42, 56)
    val layouts = remember(decor, ink) {
        sizesSp.map { sp -> decor.glyphs.map { measurer.measure(it, TextStyle(fontSize = sp.sp, color = ink)) } }
    }
    return this.drawBehind {
        val cellW = 104.dp.toPx()
        val cellH = 92.dp.toPx()
        fun hash(r: Int, c: Int, salt: Int): Float {
            var h = (r * 73856093) xor (c * 19349663) xor (salt * 83492791)
            h = h xor (h ushr 13); h *= 0x5bd1e995.toInt(); h = h xor (h ushr 15)
            return (h and 0x7fffffff) % 10007 / 10007f
        }
        var row = 0
        var y = -cellH * 0.45f
        while (y < size.height + cellH * 0.2f) {
            var col = 0
            var x = -cellW * 0.45f + (if (row % 2 == 0) 0f else cellW * 0.5f)
            while (x < size.width + cellW * 0.2f) {
                val sizeIdx = (hash(row, col, 1) * sizesSp.size).toInt().coerceIn(0, sizesSp.lastIndex)
                val glyph = layouts[sizeIdx][(hash(row, col, 2) * decor.glyphs.size).toInt().coerceIn(0, decor.glyphs.lastIndex)]
                val left = x + (hash(row, col, 3) - 0.5f) * cellW * 0.6f
                val top = y + (hash(row, col, 4) - 0.5f) * cellH * 0.6f
                val rot = (hash(row, col, 5) - 0.5f) * 90f
                val pivot = Offset(left + glyph.size.width / 2f, top + glyph.size.height / 2f)
                rotate(rot, pivot) { drawText(glyph, topLeft = Offset(left, top)) }
                x += cellW
                col++
            }
            y += cellH
            row++
        }
    }
}

/**
 * Вкладка целиком: режим для узора плашек, краска режима (`Kit.kt`,
 * [tint]), свет режима сверху (`ui/Glow.kt`, версия 3) и содержимое. Фон —
 * тёмный; свет лежит под содержимым и к середине экрана сходит в него.
 */
@Composable
fun ModeFrame(decor: ModeDecor, content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = decor.tint(MaterialTheme.colorScheme),
        typography = MaterialTheme.typography,
        shapes = MaterialTheme.shapes,
    ) {
        val glow = remember(decor) { GlowState() }
        CompositionLocalProvider(LocalModeDecor provides decor, LocalGlowState provides glow) {
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
