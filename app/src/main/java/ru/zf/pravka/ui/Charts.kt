package ru.zf.pravka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.abs

// Графики «Отчёта» — на Canvas, без библиотек: донат, полоса суток, стопки по
// дням, столбики со знаком, ломаная с базовой линией, центрированные полосы,
// плитка KPI. Один язык на все карточки: тонкие линии, скруглённые углы,
// подписи мелким серым; цвета приходят снаружи (радуга категорий, тёплая
// палитра телефона) — сами графики про цвет ничего не решают.

/** Кусок доната или стопки: подпись, значение, цвет. */
data class ChartSlice(val label: String, val value: Float, val color: Color)

/** Один день столбиком: подпись снизу и куски стопки снизу вверх. */
data class StackedColumn(val label: String, val parts: List<ChartSlice>, val faded: Boolean = false) {
    val total: Float get() = parts.sumOf { it.value.toDouble() }.toFloat()
}

/** Отрезок полосы суток: доли от 0 до 1 и цвет. */
data class StripSegment(val startFrac: Float, val endFrac: Float, val color: Color)

private fun dash() = PathEffect.dashPathEffect(floatArrayOf(10f, 8f))

/**
 * Донат: доли по кругу от двенадцати часов по часовой стрелке. Между кусками
 * зазор в полтора градуса, чтобы соседние оттенки радуги не слипались; пустой
 * донат — серое кольцо. В центре — что угодно (часы, балл).
 */
@Composable
fun DonutChart(
    slices: List<ChartSlice>,
    size: Dp = 168.dp,
    thickness: Dp = 24.dp,
    center: @Composable BoxScope.() -> Unit = {},
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(Modifier.size(size), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(size)) {
            val stroke = thickness.toPx()
            val inset = stroke / 2f
            val arcSize = Size(this.size.width - stroke, this.size.height - stroke)
            val topLeft = Offset(inset, inset)
            val total = slices.sumOf { it.value.toDouble() }.toFloat()
            if (total <= 0f) {
                drawArc(track, 0f, 360f, false, topLeft, arcSize, style = Stroke(stroke))
                return@Canvas
            }
            val gap = if (slices.size > 1) 1.5f else 0f
            var angle = -90f
            for (s in slices) {
                val sweep = s.value / total * 360f
                if (sweep <= 0f) continue
                drawArc(
                    color = s.color,
                    startAngle = angle + gap / 2f,
                    sweepAngle = (sweep - gap).coerceAtLeast(0.6f),
                    useCenter = false,
                    topLeft = topLeft,
                    size = arcSize,
                    style = Stroke(stroke),
                )
                angle += sweep
            }
        }
        center()
    }
}

/** Кружок цвета, подпись и значение справа — строка легенды. */
@Composable
fun LegendRow(color: Color, label: String, value: String, sub: String? = null, dim: Boolean = false) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).background(color, CircleShape))
        Spacer(Modifier.width(8.dp))
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        if (sub != null) {
            Text(
                sub,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(end = 8.dp),
            )
        }
        Text(
            value,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = if (dim) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
        )
    }
}

/**
 * Сутки одной полосой: 24 часа слева направо, отрезки цветом категории, пустое
 * — не размечено или ещё не наступило. [nowFrac] — тонкая риска «сейчас».
 */
@Composable
fun DayStripChart(
    segments: List<StripSegment>,
    height: Dp = 16.dp,
    nowFrac: Float? = null,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val ink = MaterialTheme.colorScheme.onSurface
    Canvas(Modifier.fillMaxWidth().height(height)) {
        val w = size.width
        val h = size.height
        val r = CornerRadius(h / 4f)
        drawRoundRect(track, size = size, cornerRadius = r)
        for (s in segments) {
            val x0 = (s.startFrac.coerceIn(0f, 1f) * w)
            val x1 = (s.endFrac.coerceIn(0f, 1f) * w)
            if (x1 - x0 < 0.5f) continue
            drawRect(s.color, Offset(x0, 0f), Size(x1 - x0, h))
        }
        if (nowFrac != null && nowFrac in 0f..1f) {
            val x = nowFrac * w
            drawLine(ink, Offset(x, -2f), Offset(x, h + 2f), strokeWidth = 2f)
        }
    }
}

/** Часы под полосой суток: 0 · 6 · 12 · 18 · 24. */
@Composable
fun HourTicks() {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        for (t in listOf("0", "6", "12", "18", "24")) {
            Text(t, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Столбики по дням, стопкой снизу вверх. Высота — от максимума колонок или от
 * цели, если она выше; цель — пунктир. [highlight] — колонка жирной подписью
 * (сегодня); бледная колонка (`faded`) — день, который ещё идёт. Над каждой
 * колонкой — её сумма, если [valueText] задан.
 */
@Composable
fun StackedColumns(
    columns: List<StackedColumn>,
    height: Dp = 128.dp,
    target: Float? = null,
    targetColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    valueText: ((Float) -> String)? = null,
    highlight: Int = -1,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val baseLine = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = columns.size
            if (n == 0) return@Canvas
            val topPad = if (valueText != null) 16.dp.toPx() else 4.dp.toPx()
            val plotH = size.height - topPad - 2f
            val peak = maxOf(columns.maxOf { it.total }, target ?: 0f).coerceAtLeast(1f)
            val slot = size.width / n
            val barW = slot * 0.62f
            drawLine(baseLine, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            columns.forEachIndexed { i, col ->
                val x = slot * i + (slot - barW) / 2f
                var y = size.height - 1f
                val alpha = if (col.faded) 0.45f else 1f
                for (p in col.parts) {
                    if (p.value <= 0f) continue
                    val hPx = p.value / peak * plotH
                    y -= hPx
                    drawRoundRect(
                        p.color.copy(alpha = alpha * p.color.alpha),
                        Offset(x, y),
                        Size(barW, (hPx - 1f).coerceAtLeast(1f)),
                        CornerRadius(2.dp.toPx()),
                    )
                }
                if (valueText != null && col.total > 0f) {
                    val text = valueText(col.total)
                    val layout = measurer.measure(text, labelStyle)
                    drawText(
                        layout,
                        topLeft = Offset(
                            x + barW / 2f - layout.size.width / 2f,
                            (y - layout.size.height - 2f).coerceAtLeast(0f),
                        ),
                    )
                }
            }
            if (target != null && target > 0f) {
                val ty = size.height - 1f - target / peak * plotH
                drawLine(targetColor, Offset(0f, ty), Offset(size.width, ty), strokeWidth = 1.5f, pathEffect = dash())
            }
        }
        Row(Modifier.fillMaxWidth()) {
            columns.forEachIndexed { i, col ->
                Text(
                    col.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == highlight) FontWeight.Bold else FontWeight.Normal,
                    color = if (i == highlight) MaterialTheme.colorScheme.onSurface else labelColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Столбики со знаком: плюс вверх от нулевой линии, минус вниз, цвет по значению
 * ([colorOf]). Пустой день (null) — пропуск. [hollow] — контуром, не заливкой:
 * день, который ещё идёт. Подписи — только непустые (например, понедельники).
 */
@Composable
fun SignedColumns(
    values: List<Float?>,
    colorOf: (Float) -> Color,
    labels: List<String>,
    height: Dp = 120.dp,
    hollow: Set<Int> = emptySet(),
    valueText: ((Float) -> String)? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val zeroLine = MaterialTheme.colorScheme.outline
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size
            if (n == 0) return@Canvas
            val present = values.filterNotNull()
            val posPeak = (present.maxOrNull() ?: 0f).coerceAtLeast(0f)
            val negPeak = (present.minOrNull() ?: 0f).coerceAtMost(0f)
            val pad = if (valueText != null) 14.dp.toPx() else 4f
            val span = (posPeak - negPeak).coerceAtLeast(1f)
            val plotH = size.height - 2 * pad
            val zeroY = pad + posPeak / span * plotH
            val slot = size.width / n
            val barW = slot * 0.66f
            drawLine(zeroLine, Offset(0f, zeroY), Offset(size.width, zeroY), strokeWidth = 1f)
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val x = slot * i + (slot - barW) / 2f
                val hPx = abs(v) / span * plotH
                val top = if (v >= 0f) zeroY - hPx else zeroY
                val color = colorOf(v)
                val rect = Size(barW, hPx.coerceAtLeast(1.5f))
                if (i in hollow) {
                    drawRoundRect(color, Offset(x, top), rect, CornerRadius(2.dp.toPx()), style = Stroke(2f))
                } else {
                    drawRoundRect(color, Offset(x, top), rect, CornerRadius(2.dp.toPx()))
                }
                if (valueText != null) {
                    val layout = measurer.measure(valueText(v), labelStyle)
                    val ty = if (v >= 0f) top - layout.size.height - 1f else top + rect.height + 1f
                    drawText(layout, topLeft = Offset(x + barW / 2f - layout.size.width / 2f, ty.coerceIn(0f, size.height - layout.size.height)))
                }
            }
        }
        Row(Modifier.fillMaxWidth()) {
            labels.forEach { l ->
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    color = labelColor,
                    maxLines = 1,
                    modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                )
            }
        }
    }
}

/**
 * Ломаная с точками. Пропуски (null) рвут линию, а не тянут её через ноль.
 * [baseline] — пунктир «обычно так» (медиана или среднее за окно); шкала от
 * нуля, если [fromZero], иначе от минимума с запасом — вес и HRV живут в узком
 * коридоре, и от нуля там ничего не разглядеть.
 */
@Composable
fun LineChart(
    values: List<Float?>,
    color: Color,
    height: Dp = 72.dp,
    baseline: Float? = null,
    fromZero: Boolean = false,
    labels: List<String>? = null,
    valueText: ((Float) -> String)? = null,
) {
    val measurer = rememberTextMeasurer()
    val labelColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 10.sp, color = labelColor)
    val axis = MaterialTheme.colorScheme.outlineVariant
    Column(Modifier.fillMaxWidth()) {
        Canvas(Modifier.fillMaxWidth().height(height)) {
            val n = values.size
            val present = values.filterNotNull()
            drawLine(axis, Offset(0f, size.height - 1f), Offset(size.width, size.height - 1f), strokeWidth = 1f)
            if (n == 0 || present.isEmpty()) return@Canvas
            val pad = 6.dp.toPx()
            var low = if (fromZero) 0f else present.min()
            var high = present.max()
            if (baseline != null) {
                low = minOf(low, baseline)
                high = maxOf(high, baseline)
            }
            if (!fromZero) {
                val margin = ((high - low) * 0.15f).coerceAtLeast(0.5f)
                low -= margin
                high += margin
            }
            val span = (high - low).coerceAtLeast(0.01f)
            val plotH = size.height - 2 * pad
            fun yOf(v: Float) = pad + (1f - (v - low) / span) * plotH
            fun xOf(i: Int) = if (n == 1) size.width / 2f else size.width * i / (n - 1).toFloat()
            if (baseline != null) {
                val by = yOf(baseline)
                drawLine(labelColor, Offset(0f, by), Offset(size.width, by), strokeWidth = 1.2f, pathEffect = dash())
            }
            var previous: Offset? = null
            values.forEachIndexed { i, v ->
                if (v == null) {
                    previous = null
                    return@forEachIndexed
                }
                val p = Offset(xOf(i), yOf(v))
                previous?.let { drawLine(color, it, p, strokeWidth = 3f) }
                drawCircle(color, radius = 3.5f, center = p)
                previous = p
            }
            if (valueText != null) {
                val lastIndex = values.indexOfLast { it != null }
                if (lastIndex >= 0) {
                    val v = values[lastIndex]!!
                    val layout = measurer.measure(valueText(v), labelStyle.copy(color = color, fontWeight = FontWeight.SemiBold))
                    val x = (xOf(lastIndex) - layout.size.width).coerceAtLeast(0f)
                    val y = (yOf(v) - layout.size.height - 4f).coerceAtLeast(0f)
                    drawText(layout, topLeft = Offset(x, y))
                }
            }
        }
        if (labels != null) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                labels.forEach { l ->
                    Text(l, style = MaterialTheme.typography.labelSmall, color = labelColor, maxLines = 1)
                }
            }
        }
    }
}

/**
 * Центрированные полосы — треугольник ценности: шире там, где больше минут.
 * Слева ступень, справа минуты; тонкая тёмная черта под полосой — база
 * (тот же день неделю назад), чтобы форму было с чем сравнить.
 */
@Composable
fun CenteredBars(
    rows: List<CenteredBarRow>,
    height: Dp = 22.dp,
) {
    val max = rows.maxOfOrNull { maxOf(it.value, it.ref ?: 0f) }?.coerceAtLeast(1f) ?: 1f
    val track = MaterialTheme.colorScheme.surfaceVariant
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        for (r in rows) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.width(74.dp)) {
                    Text(r.label, style = MaterialTheme.typography.labelMedium, color = r.color, maxLines = 1)
                    if (r.sub.isNotBlank()) {
                        Text(
                            r.sub,
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Box(
                    Modifier.weight(1f).height(height).background(track, RoundedCornerShape(4.dp)),
                    contentAlignment = Alignment.Center,
                ) {
                    val frac = (r.value / max).coerceIn(0f, 1f)
                    if (frac > 0f) {
                        Box(
                            Modifier
                                .fillMaxWidth(frac.coerceAtLeast(0.01f))
                                .fillMaxHeight()
                                .background(r.color, RoundedCornerShape(4.dp)),
                        )
                    }
                    if (r.ref != null && r.ref > 0f) {
                        val rf = (r.ref / max).coerceIn(0f, 1f)
                        Box(
                            Modifier
                                .fillMaxWidth(rf.coerceAtLeast(0.01f))
                                .height(3.dp)
                                .align(Alignment.BottomCenter)
                                .background(MaterialTheme.colorScheme.onSurface.copy(alpha = 0.55f)),
                        )
                    }
                }
                Text(
                    r.valueText,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.width(64.dp).padding(start = 8.dp),
                    maxLines = 1,
                )
            }
        }
    }
}

data class CenteredBarRow(
    val label: String,
    val sub: String,
    val value: Float,
    val ref: Float?,
    val color: Color,
    val valueText: String,
)

/**
 * Строка сравнения: две полоски друг под другом — сейчас цветом, база серым, —
 * и дельта справа. Ширина от общего максимума [max], чтобы строки читались
 * относительно друг друга.
 */
@Composable
fun CompareRow(
    label: String,
    cur: Float,
    ref: Float,
    max: Float,
    color: Color,
    curText: String,
    refText: String,
    deltaText: String,
    deltaColor: Color,
) {
    val track = MaterialTheme.colorScheme.surfaceVariant
    val refColor = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.55f)
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.width(112.dp),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Box(Modifier.fillMaxWidth().height(9.dp).background(track, RoundedCornerShape(4.dp))) {
                val f = if (max > 0f) (cur / max).coerceIn(0f, 1f) else 0f
                if (f > 0f) Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.015f)).fillMaxHeight().background(color, RoundedCornerShape(4.dp)))
            }
            Box(Modifier.fillMaxWidth().height(5.dp).background(track, RoundedCornerShape(3.dp))) {
                val f = if (max > 0f) (ref / max).coerceIn(0f, 1f) else 0f
                if (f > 0f) Box(Modifier.fillMaxWidth(f.coerceAtLeast(0.015f)).fillMaxHeight().background(refColor, RoundedCornerShape(3.dp)))
            }
        }
        Column(Modifier.width(96.dp).padding(start = 8.dp), horizontalAlignment = Alignment.End) {
            Text(curText, style = MaterialTheme.typography.bodySmall, fontWeight = FontWeight.SemiBold, maxLines = 1)
            Text(
                "$refText · $deltaText",
                style = MaterialTheme.typography.labelSmall,
                color = deltaColor,
                maxLines = 1,
            )
        }
    }
}

/**
 * Плитка KPI: подпись сверху, значение крупно, под ним дельта к базе своим
 * цветом и подсказка серым. Цвет дельты решает вызывающий: меньше телефона —
 * это плюс, меньше сна — минус, и график этого знать не может.
 */
@Composable
fun KpiTile(
    label: String,
    value: String,
    modifier: Modifier = Modifier,
    delta: String? = null,
    deltaColor: Color = MaterialTheme.colorScheme.onSurfaceVariant,
    hint: String? = null,
    valueColor: Color = MaterialTheme.colorScheme.onSurface,
) {
    Column(
        modifier
            .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f), RoundedCornerShape(12.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleLarge,
            color = valueColor,
            maxLines = 1,
        )
        if (delta != null) {
            Text(delta, style = MaterialTheme.typography.labelMedium, color = deltaColor, maxLines = 1)
        }
        if (hint != null) {
            Text(
                hint,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Ряд точек по дням: сделано — цветом, нет — контуром; подпись дня под каждой. */
@Composable
fun DotRow(labels: List<String>, on: List<Boolean>, color: Color, highlight: Int = -1) {
    val off = MaterialTheme.colorScheme.outlineVariant
    Row(Modifier.fillMaxWidth()) {
        labels.forEachIndexed { i, l ->
            Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier
                        .size(16.dp)
                        .background(if (on.getOrNull(i) == true) color else off, CircleShape),
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    l,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (i == highlight) FontWeight.Bold else FontWeight.Normal,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
        }
    }
}
