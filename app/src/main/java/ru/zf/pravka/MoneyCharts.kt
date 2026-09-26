package ru.zf.pravka

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.MoneyCategories

// Цвета и два графика Денег поверх общего набора `ui/Charts.kt`.
//
// Цвет держится за ГРУППОЙ категорий (Дом, Дети, Жизнь, Здоровье, ЗФ), а не за
// местом в рейтинге: неделя, где Дети обогнали Дом, не должна перекрашивать
// донат. Пять слотов — первые пять проверенной категориальной палитры в
// фиксированном порядке (своя ступень для тёмной темы, не «инверсия»); тридцать
// категорий в один донат не влезают, поэтому донат — по группам, а категории
// — строками с полосой цвета своей группы.
//
// «Ушло» и «пришло» — красным и зелёным по просьбе владельца («зелёным и
// красным с двух сторон»), и всегда рядом со словом и знаком: цвет не
// единственный носитель смысла.

private val GROUP_ORDER = listOf(
    MoneyCategories.G_HOME, MoneyCategories.G_KIDS, MoneyCategories.G_LIFE,
    MoneyCategories.G_HEALTH, MoneyCategories.G_ZF,
)
private val LIGHT = listOf(0xFF2A78D6, 0xFFEB6834, 0xFF1BAF7A, 0xFFEDA100, 0xFFE87BA4)
private val DARK = listOf(0xFF3987E5, 0xFFD95926, 0xFF199E70, 0xFFC98500, 0xFFD55181)

@Composable
internal fun moneyDark(): Boolean = MaterialTheme.colorScheme.background.luminance() < 0.4f

@Composable
internal fun groupColor(group: String): Color {
    val i = GROUP_ORDER.indexOf(group)
    if (i < 0) return MaterialTheme.colorScheme.outline
    return Color((if (moneyDark()) DARK else LIGHT)[i])
}

@Composable
internal fun moneyCategoryColor(key: String): Color = groupColor(MoneyCategories.of(key)?.group ?: "")

@Composable
internal fun spentColor(): Color = if (moneyDark()) Color(0xFFE66767) else Color(0xFFE34948)

@Composable
internal fun incomeColor(): Color = if (moneyDark()) Color(0xFF3FB950) else Color(0xFF008300)

/**
 * Месяцы парами столбиков на одной шкале: «ушло» и «пришло» рядом. Одна ось
 * — обе величины в рублях; подпись месяца снизу, сумма «ушло» — над столбиком
 * (только она: число над каждой меткой — шум).
 */
@Composable
internal fun MonthPairs(
    labels: List<String>,
    spent: List<Long>,
    income: List<Long>,
    valueText: (Long) -> String,
    height: Dp = 132.dp,
    highlight: Int = -1,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val base = MaterialTheme.colorScheme.outlineVariant
    val red = spentColor()
    val green = incomeColor()
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = labels.size
            if (n == 0) return@Canvas
            val top = 16.dp.toPx()
            val plotH = size.height - top - 1f
            val peak = (spent + income).maxOrNull()?.coerceAtLeast(1L)?.toFloat() ?: 1f
            val slot = size.width / n
            val barW = slot * 0.3f
            val gap = 2.dp.toPx()
            drawLine(base, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            for (i in 0 until n) {
                val cx = slot * i + slot / 2f
                val hs = spent[i] / peak * plotH
                val hi = income[i] / peak * plotH
                val alpha = if (highlight >= 0 && i != highlight) 0.7f else 1f
                if (hs > 0f) drawRoundRect(red.copy(alpha = alpha), Offset(cx - gap / 2 - barW, size.height - 1f - hs), Size(barW, hs), CornerRadius(2.dp.toPx()))
                if (hi > 0f) drawRoundRect(green.copy(alpha = alpha), Offset(cx + gap / 2, size.height - 1f - hi), Size(barW, hi), CornerRadius(2.dp.toPx()))
                if (spent[i] > 0) {
                    val layout = measurer.measure(valueText(spent[i]), labelStyle)
                    drawText(layout, topLeft = Offset(cx - layout.size.width / 2f, (size.height - 1f - maxOf(hs, hi) - layout.size.height - 2f).coerceAtLeast(0f)))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEachIndexed { i, l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    color = if (i == highlight) MaterialTheme.colorScheme.onSurface else labelColor,
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }
}

/** Тонкая полоса доли: трек и заливка цветом группы. */
@Composable
internal fun ShareBar(fraction: Float, color: Color, modifier: Modifier = Modifier) {
    Box(
        modifier
            .fillMaxWidth()
            .height(5.dp)
            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(3.dp)),
    ) {
        Box(
            Modifier
                .fillMaxWidth(fraction.coerceIn(0f, 1f))
                .height(5.dp)
                .background(color, RoundedCornerShape(3.dp)),
        )
    }
}
