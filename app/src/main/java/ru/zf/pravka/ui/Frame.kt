package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
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
 * Режим вкладки — какие знаки разбросать по фону. Знаки — не картинки, а
 * символы шрифта: письменные принадлежности у Правки, часы у Засечки, галочки
 * у Дел, снаряды у спорта, еда у Еды. Рисуются один раз, полупрозрачными,
 * поверх лежат плашки с текстом.
 */
enum class ModeDecor(val glyphs: List<String>, val emoji: List<String>) {
    PRAVKA(listOf("✒", "✎", "¶", "§", "❝", "✍", "„", "…"), listOf("🖋", "📝", "✏️")),
    ZASECHKA(listOf("◔", "◑", "◕", "⧗", "⌛", "◷", "⏱", "◴"), listOf("⏰", "⌚", "🕰")),
    DELA(listOf("✓", "☐", "☑", "✔", "•", "→", "☐", "✓"), listOf("📋", "🗒", "📌")),
    SPORT(listOf("⚡", "◎", "▲", "∞", "⚑", "≋", "◇", "⚡"), listOf("🏋", "🚴", "🏃", "🥋")),
    FOOD(listOf("○", "◌", "◍", "◐", "⊙", "◌", "○", "◔"), listOf("🥗", "🍳", "🥑", "☕")),
    SERVICE(listOf("·", "◦", "·", "◦", "·", "◦", "·", "◦"), listOf()),
}

/**
 * Где лежат знаки: доли ширины и высоты, размер в sp, поворот. Позиции
 * подобраны руками так, чтобы верх (шапка) и центр (первая плашка)
 * оставались почти чистыми, а знаки уходили к краям.
 */
private data class Spot(val x: Float, val y: Float, val size: Int, val rot: Float)

private val SPOTS = listOf(
    Spot(0.04f, 0.12f, 34, -14f), Spot(0.86f, 0.09f, 28, 12f),
    Spot(0.70f, 0.24f, 22, -6f), Spot(0.08f, 0.36f, 26, 18f),
    Spot(0.90f, 0.42f, 30, -20f), Spot(0.30f, 0.52f, 20, 8f),
    Spot(0.62f, 0.60f, 36, -10f), Spot(0.05f, 0.70f, 24, 22f),
    Spot(0.84f, 0.78f, 26, -8f), Spot(0.40f, 0.86f, 30, 14f),
    Spot(0.12f, 0.92f, 22, -16f),
)

/** Фон вкладки: знаки режима, чуть заметные. Ничего не считает, не мигает. */
@Composable
fun ModeBackdrop(decor: ModeDecor) {
    val ink = MaterialTheme.colorScheme.primary
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val w = maxWidth
        val h = maxHeight
        SPOTS.forEachIndexed { i, spot ->
            // Эмодзи — цветные, их держим ещё бледнее, чем знаки шрифта.
            val useEmoji = decor.emoji.isNotEmpty() && i % 4 == 3
            val text = if (useEmoji) decor.emoji[(i / 4) % decor.emoji.size]
            else decor.glyphs[i % decor.glyphs.size]
            Text(
                text,
                fontSize = spot.size.sp,
                color = if (useEmoji) Color.Unspecified else ink,
                modifier = Modifier
                    .offset(x = w * spot.x, y = h * spot.y)
                    .graphicsLayer {
                        alpha = if (useEmoji) 0.10f else 0.13f
                        rotationZ = spot.rot
                    },
            )
        }
    }
}

/** Вкладка целиком: фон со знаками, поверх — содержимое. */
@Composable
fun ModeFrame(decor: ModeDecor, content: @Composable () -> Unit) {
    Box(Modifier.fillMaxSize()) {
        ModeBackdrop(decor)
        content()
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
