package ru.zf.pravka.ui

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Settings
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.R

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
    SERVICE(listOf("·", "◦", "·", "◦", "·", "◦", "·", "◦")),
}

/** Режим текущей вкладки — читает `PaperCard`, чтобы положить узор на плашку. */
val LocalModeDecor = compositionLocalOf<ModeDecor?> { null }

/**
 * Узор знаков режима под содержимым плашки: плотная решётка со сдвигом
 * чётных рядов и лёгким поворотом, цветом текста на пять сотых
 * прозрачности — разница с плашкой минимальная, глазом читается как
 * фактура бумаги, не как рисунок. Рисуется в drawBehind, без композиции:
 * знаки промерены один раз на плашку.
 */
@Composable
fun Modifier.glyphPattern(decor: ModeDecor): Modifier {
    val measurer = rememberTextMeasurer()
    val ink = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.055f)
    val style = TextStyle(fontSize = 17.sp, color = ink)
    val layouts = remember(decor, ink) { decor.glyphs.map { measurer.measure(it, style) } }
    return this.drawBehind {
        val cellW = 44.dp.toPx()
        val cellH = 38.dp.toPx()
        var row = 0
        var y = -cellH * 0.35f
        while (y < size.height) {
            val shift = if (row % 2 == 0) 0f else cellW / 2f
            var col = 0
            var x = -cellW * 0.4f + shift
            while (x < size.width) {
                val layout = layouts[(row * 5 + col * 3) % layouts.size]
                val rot = ((row * 13 + col * 7) % 5 - 2) * 7f
                val pivot = Offset(x + layout.size.width / 2f, y + layout.size.height / 2f)
                rotate(rot, pivot) { drawText(layout, topLeft = Offset(x, y)) }
                x += cellW
                col++
            }
            y += cellH
            row++
        }
    }
}

/** Вкладка целиком: режим для узора плашек и содержимое. Фон — чистый, тёмный. */
@Composable
fun ModeFrame(decor: ModeDecor, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalModeDecor provides decor) {
        Box(Modifier.fillMaxSize()) { content() }
    }
}

/**
 * Шапка вкладки: пиктограмма режима в плашке, название с засечками и короткая
 * полоска акцента под ним, справа — значки действий. Служебные экраны под
 * «Ещё» дают [onBack] — тогда слева стоит «‹».
 */
@Composable
fun TabHeader(
    title: String,
    icon: Painter? = null,
    onBack: (() -> Unit)? = null,
    subtitle: String? = null,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = if (onBack != null) 4.dp else 16.dp, end = 4.dp, top = 8.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (onBack != null) {
            IconButton(onClick = onBack) {
                Text(
                    "‹",
                    style = MaterialTheme.typography.headlineMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
        if (icon != null) {
            Box(
                Modifier
                    .size(36.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.onPrimaryContainer,
                    modifier = Modifier.size(20.dp),
                )
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                title,
                style = MaterialTheme.typography.headlineSmall,
                maxLines = 1,
            )
            // Полоска под названием — вместо строки пояснений.
            Box(
                Modifier
                    .padding(top = 3.dp)
                    .width(if (subtitle == null) 44.dp else 28.dp)
                    .height(3.dp)
                    .background(MaterialTheme.colorScheme.primary, RoundedCornerShape(2.dp)),
            )
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
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(0.dp)) {
            actions()
        }
    }
}

/** Значок действия в шапке: штриховая пиктограмма цветом второго плана. */
@Composable
fun HeaderAction(iconRes: Int, description: String, onClick: () -> Unit, tint: Color? = null) {
    IconButton(onClick = onClick) {
        Icon(
            painterResource(iconRes),
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

/** Шестерёнка: настройки именно этого режима. */
@Composable
fun SettingsAction(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(
            Icons.Filled.Settings,
            contentDescription = "настройки режима",
            tint = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.size(22.dp),
        )
    }
}

/** Статистика: столбики. */
@Composable
fun StatsAction(onClick: () -> Unit) = HeaderAction(R.drawable.ic_stats, "статистика", onClick)

/** Выгрузка: стрелка из лотка. */
@Composable
fun ExportAction(onClick: () -> Unit) = HeaderAction(R.drawable.ic_export, "выгрузка", onClick)
