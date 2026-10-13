package ru.zf.pravka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.LifeStats
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow

// Графики Общей статистики (11.10.2026) — с отчёта, который владелец принёс от
// Claude: средний день по часам стопками, ломаные недель с переключателями
// линий, столбики, стопки с линией дохода, полосы с чертой нормы. На Canvas,
// без библиотек. Что под пальцем — строкой под графиком (`Readout`), а не
// всплывашкой: на телефоне всплывашку закрывает тот же палец.
//
// Цвета групп — палитра отчёта в тёмной теме (владелец: «очень нравится»):
// десять различимых оттенков вместо краски одного режима. Это исключение из
// «акцент только краской режима» нарочно — см. docs/agreements.md.

/** Палитра отчёта: тёмная тема его `:root`. */
object LifeInk {
    val Sleep = Color(0xFF7F93B8)
    val Family = Color(0xFF3DBB92)
    val Life = Color(0xFF8A7F63)
    val Work = Color(0xFF5F86FF)
    val Sys = Color(0xFFF2B33A)
    val Sport = Color(0xFF2BC3D6)
    val Loss = Color(0xFF6E7885)
    val Solo = Color(0xFFEE5E95)
    val Gap = Color(0xFF3A4552)
    val Other = Color(0xFF9B8BC4)
    val Warn = Color(0xFFFB8B4B)
    // Сетка и оси — от фона, а не от данных: на светлой теме v2 светлая сетка 4.0 пропадала.
    val Grid: Color get() = if (Look.v4) Color(0x1FF0EADF) else Kv.line
    val Axis: Color get() = if (Look.v4) Color(0xFFA98F72) else Kv.faint

    fun of(g: LifeStats.Group): Color = when (g) {
        LifeStats.Group.SLEEP -> Sleep
        LifeStats.Group.LIFE -> Life
        LifeStats.Group.FAMILY -> Family
        LifeStats.Group.WORK -> Work
        LifeStats.Group.SYS -> Sys
        LifeStats.Group.SPORT -> Sport
        LifeStats.Group.OTHER -> Other
        LifeStats.Group.LOSS -> Loss
        LifeStats.Group.GAP -> Gap
        LifeStats.Group.SOLO -> Solo
    }
}

/** Строка под графиком: что под пальцем. */
@Immutable
class ReadoutRow(val color: Color?, val text: String)

@Immutable
class Readout(val title: String, val rows: List<ReadoutRow>)

/** Ряд данных: имя, цвет, значения (null — пропуск), скрыт ли. */
@Immutable
data class LifeSeries(val name: String, val color: Color, val data: List<Float?>, val hidden: Boolean = false)

private fun niceMax(v: Float): Float {
    if (v <= 0f) return 1f
    val p = 10.0.pow(floor(log10(v.toDouble()))).toFloat()
    val n = v / p
    val s = when {
        n <= 1f -> 1f
        n <= 2f -> 2f
        n <= 2.5f -> 2.5f
        n <= 5f -> 5f
        else -> 10f
    }
    return s * p
}

private fun ticksFor(range: Float): Int {
    for (t in intArrayOf(4, 5, 3, 2)) {
        val st = range / t
        if (kotlin.math.abs(st - Math.round(st)) < 1e-4) return t
    }
    return 4
}

private fun fmtAxis(v: Float): String =
    if (v == floor(v)) v.toInt().toString() else String.format(java.util.Locale.US, "%.1f", v).replace('.', ',')

private val dash = PathEffect.dashPathEffect(floatArrayOf(10f, 9f))

/** Подпись мелким серым на Canvas. */
private fun DrawScope.label(
    m: TextMeasurer,
    text: String,
    x: Float,
    y: Float,
    style: TextStyle,
    align: TextAlign = TextAlign.Center,
) {
    val l = m.measure(text, style)
    val left = when (align) {
        TextAlign.End, TextAlign.Right -> x - l.size.width
        TextAlign.Start, TextAlign.Left -> x
        else -> x - l.size.width / 2f
    }.coerceIn(0f, (size.width - l.size.width).coerceAtLeast(0f))
    drawText(l, topLeft = Offset(left, y - l.size.height / 2f))
}

/** Сетка со шкалой слева: [hi] — верх, [lo] — низ. Возвращает y(v). */
private fun DrawScope.grid(
    m: TextMeasurer,
    style: TextStyle,
    left: Float,
    top: Float,
    bottom: Float,
    lo: Float,
    hi: Float,
    ticks: Int,
    fmt: (Float) -> String,
): (Float) -> Float {
    val y: (Float) -> Float = { v -> top + (1f - (v - lo) / (hi - lo)) * (bottom - top) }
    for (k in 0..ticks) {
        val v = lo + (hi - lo) * k / ticks
        val yy = y(v)
        drawLine(LifeInk.Grid, Offset(left, yy), Offset(size.width, yy), strokeWidth = 1f)
        label(m, fmt(v), left - 6.dp.toPx(), yy, style, TextAlign.End)
    }
    return y
}

/** Строка выбранного под графиком. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReadoutLine(readout: Readout?, hint: String, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    Column(modifier.fillMaxWidth().heightIn(min = 22.dp).padding(top = 6.dp)) {
        if (readout == null) {
            Text(hint, style = t.caption, color = Ink.TextMeta)
            return@Column
        }
        Text(readout.title, style = t.label.copy(fontWeight = FontWeight.SemiBold), color = Ink.TextStrong)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            readout.rows.forEach { r ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (r.color != null) {
                        Box(Modifier.size(8.dp).clip(RoundedCornerShape(2.dp)).background(r.color))
                        Spacer(Modifier.width(5.dp))
                    }
                    Text(r.text, style = t.caption, color = Ink.Text)
                }
            }
        }
    }
}

/** Легенда квадратиками: подпись и цвет. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun LifeLegend(items: List<Pair<String, Color>>, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    FlowRow(
        modifier.fillMaxWidth().padding(top = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(12.dp),
        verticalArrangement = Arrangement.spacedBy(3.dp),
    ) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
                Spacer(Modifier.width(5.dp))
                Text(label, style = t.caption, color = Ink.TextSecondary)
            }
        }
    }
}

/** Переключатели линий: тап — показать или спрятать. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun SeriesToggles(series: List<LifeSeries>, onToggle: (Int) -> Unit, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    FlowRow(
        modifier.fillMaxWidth().padding(bottom = 8.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        series.forEachIndexed { i, s ->
            val alpha = if (s.hidden) 0.45f else 1f
            Row(
                Modifier
                    .clip(RoundedCornerShape(50))
                    .border(1.dp, Ink.Text.copy(alpha = 0.18f * alpha), RoundedCornerShape(50))
                    .tap({ onToggle(i) }, label = s.name)
                    .padding(horizontal = 10.dp, vertical = 5.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Box(Modifier.size(9.dp).clip(CircleShape).background(s.color.copy(alpha = alpha)))
                Spacer(Modifier.width(6.dp))
                Text(s.name, style = t.caption, color = Ink.Text.copy(alpha = alpha))
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Средний день по часам
// ---------------------------------------------------------------------------

/**
 * 24 столбика по 60 минут: из чего сложился каждый час в среднем. Порядок
 * стопки — отчёта (сон снизу, соло сверху). [window] — рамка часов (окно
 * 10–17), [nowHour] — час «сейчас» отмечен точкой под столбиком. Тап по
 * часу — его раскладка строкой ниже, крупное сверху. Точка над столбиком —
 * час «сейчас».
 */
@Composable
fun HoursChart(
    profile: LifeStats.HourProfile,
    modifier: Modifier = Modifier,
    height: Dp = 190.dp,
    window: IntRange? = null,
    nowHour: Int? = null,
    hint: String = "Нажми на час — раскладка ниже.",
) {
    val m = rememberTextMeasurer()
    val axis = TextStyle(fontSize = 10.sp, color = LifeInk.Axis)
    val bold = TextStyle(fontSize = 11.sp, color = Ink.TextStrong, fontWeight = FontWeight.SemiBold)
    var sel by remember(profile) { mutableStateOf<Int?>(null) }
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).clipToBounds().pointerInput(profile) {
                detectTapGestures { o ->
                    val cw = size.width / 24f
                    val i = (o.x / cw).toInt().coerceIn(0, 23)
                    sel = if (sel == i) null else i
                }
            },
        ) {
            val top = if (window != null) 26.dp.toPx() else if (nowHour != null) 8.dp.toPx() else 6.dp.toPx()
            val bottom = size.height - 18.dp.toPx()
            val ph = bottom - top
            val cw = size.width / 24f
            val gap = minOf(3.dp.toPx(), cw * 0.14f)
            for (h in 0 until 24) {
                val row = profile.hours.getOrNull(h).orEmpty()
                var y = bottom
                val dim = sel != null && sel != h
                for (g in LifeStats.Group.entries) {
                    val v = row[g] ?: continue
                    if (v <= 0.0) continue
                    val hh = (v / 60.0).toFloat() * ph
                    y -= hh
                    val c = LifeInk.of(g)
                    drawRect(
                        if (dim) c.copy(alpha = 0.35f) else c,
                        Offset(h * cw + gap / 2f, y),
                        Size(cw - gap, hh),
                    )
                }
                if (h % 3 == 0) label(m, "$h", h * cw + cw / 2f, bottom + 9.dp.toPx(), axis)
            }
            if (window != null) {
                val x1 = window.first * cw - 1f
                val x2 = (window.last + 1) * cw + 1f
                drawRoundRect(
                    Ink.TextStrong,
                    Offset(x1, top - 4.dp.toPx()),
                    Size(x2 - x1, ph + 8.dp.toPx()),
                    CornerRadius(7.dp.toPx()),
                    style = Stroke(2.dp.toPx()),
                )
                label(m, "окно ${window.first}–${window.last + 1}", (x1 + x2) / 2f, top - 14.dp.toPx(), bold)
            }
            if (nowHour != null) {
                // Над столбиком, а не под ним: снизу точка садилась на подпись часа.
                drawCircle(Ink.Now, 2.5.dp.toPx(), Offset(nowHour * cw + cw / 2f, top - 3.dp.toPx()))
            }
            sel?.let { h ->
                drawRect(Ink.TextStrong, Offset(h * cw + gap / 2f - 1f, top - 1f), Size(cw - gap + 2f, ph + 2f), style = Stroke(1.5f))
            }
        }
        val h = sel
        ReadoutLine(
            h?.let {
                val row = profile.hours.getOrNull(it).orEmpty().entries
                    .filter { e -> e.value >= 1.0 }
                    .sortedByDescending { e -> e.value }
                    .take(6)
                Readout("$it:00–${it + 1}:00", row.map { e -> ReadoutRow(LifeInk.of(e.key), "${e.key.label} ${Math.round(e.value)} мин") })
            },
            hint,
        )
    }
}

// ---------------------------------------------------------------------------
// Полоса суток и список к ней
// ---------------------------------------------------------------------------

/** Полоса 100 %: доли кусков, скруглённая. */
@Composable
fun ShareBar(parts: List<Pair<Float, Color>>, modifier: Modifier = Modifier, height: Dp = 30.dp) {
    val total = parts.sumOf { it.first.toDouble() }.toFloat().coerceAtLeast(0.0001f)
    Row(modifier.fillMaxWidth().height(height).clip(RoundedCornerShape(9.dp))) {
        parts.filter { it.first > 0f }.forEach { (v, c) ->
            Box(Modifier.weight(v / total).fillMaxWidth().height(height).background(c))
        }
    }
}

/** Строки «подпись — число» с квадратиком цвета, тонкая черта между ними. */
@Composable
fun ValueList(rows: List<Triple<String, String, Color?>>, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    Column(modifier.fillMaxWidth().padding(top = 8.dp)) {
        rows.forEachIndexed { i, (label, value, color) ->
            Row(Modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                if (color != null) {
                    Box(Modifier.size(9.dp).clip(RoundedCornerShape(2.dp)).background(color))
                    Spacer(Modifier.width(7.dp))
                }
                Text(label, style = t.body, color = Ink.Text, modifier = Modifier.weight(1f))
                Text(value, style = t.bodyStrong, color = Ink.TextStrong)
            }
            if (i < rows.lastIndex) Hairline(alpha = 0.07f)
        }
    }
}

// ---------------------------------------------------------------------------
// Ломаные
// ---------------------------------------------------------------------------

/**
 * Ломаные по общей шкале: точки, пропуски рвут линию. [bands] — подсвеченные
 * отрезки по индексу; [endLabels] — последнее значение подписью у конца.
 */
@Composable
fun LinesChart(
    labels: List<String>,
    series: List<LifeSeries>,
    tip: (Int) -> Readout,
    modifier: Modifier = Modifier,
    height: Dp = 210.dp,
    yMin: Float? = null,
    yMax: Float? = null,
    yFmt: (Float) -> String = ::fmtAxis,
    dots: Boolean = true,
    xEvery: Int? = null,
    endLabels: Boolean = false,
    endFmt: (Float) -> String = ::fmtAxis,
    bands: List<IntRange> = emptyList(),
    hint: String = "Нажми на точку — числа ниже.",
) {
    val m = rememberTextMeasurer()
    val axis = TextStyle(fontSize = 10.sp, color = LifeInk.Axis)
    var sel by remember(labels.size) { mutableStateOf<Int?>(null) }
    val n = labels.size
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(n) {
                detectTapGestures { o ->
                    val left = 36.dp.toPx()
                    val right = size.width - 12.dp.toPx()
                    if (n == 0) return@detectTapGestures
                    val i = if (n == 1) 0 else Math.round((o.x - left) / (right - left) * (n - 1)).coerceIn(0, n - 1)
                    sel = if (sel == i) null else i
                }
            },
        ) {
            if (n == 0) return@Canvas
            val left = 36.dp.toPx()
            val right = size.width - 12.dp.toPx()
            val top = 10.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val vis = series.filter { !it.hidden }
            val lo = yMin ?: 0f
            val hi = yMax ?: niceMax(max(1f, vis.flatMap { s -> s.data.filterNotNull() }.maxOrNull() ?: 1f))
            val y = grid(m, axis, left, top, bottom, lo, hi, ticksFor(hi - lo), yFmt)
            fun x(i: Int) = if (n == 1) (left + right) / 2f else left + (right - left) * i / (n - 1)
            for (b in bands) {
                val x1 = x(b.first)
                val x2 = x(b.last)
                drawRect(LifeInk.Work.copy(alpha = 0.10f), Offset(x1, top), Size(max(2f, x2 - x1), bottom - top))
            }
            val every = xEvery ?: max(1, Math.ceil(n / max(2.0, ((right - left) / 44.dp.toPx()).toDouble())).toInt())
            labels.forEachIndexed { i, l -> if (i % every == 0) label(m, l, x(i), bottom + 10.dp.toPx(), axis) }
            sel?.let { i -> drawLine(Ink.TextMeta, Offset(x(i), top), Offset(x(i), bottom), strokeWidth = 1f) }
            vis.forEachIndexed { si, s ->
                val path = Path()
                var pen = false
                s.data.forEachIndexed { i, v ->
                    if (v == null) {
                        pen = false
                        return@forEachIndexed
                    }
                    val p = Offset(x(i), y(v.coerceIn(lo, hi)))
                    if (pen) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                    pen = true
                }
                drawPath(path, s.color, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                if (dots) s.data.forEachIndexed { i, v -> if (v != null) drawCircle(s.color, 3.4.dp.toPx(), Offset(x(i), y(v.coerceIn(lo, hi)))) }
                if (endLabels) {
                    val li = s.data.indexOfLast { it != null }
                    if (li >= 0) {
                        val v = s.data[li]!!
                        val st = TextStyle(fontSize = 12.sp, color = s.color, fontWeight = FontWeight.SemiBold)
                        label(m, endFmt(v), x(li) - 6.dp.toPx(), y(v) + (if (si % 2 == 1) 16.dp.toPx() else -16.dp.toPx()), st, TextAlign.End)
                    }
                }
            }
        }
        ReadoutLine(sel?.let(tip), hint)
    }
}

// ---------------------------------------------------------------------------
// Столбики
// ---------------------------------------------------------------------------

/** Пунктир-ориентир: значение и подпись справа. */
@Immutable
class RefLine(val value: Float, val label: String)

/**
 * Столбики одного ряда: значение над столбиком, если места хватает; цвет —
 * [colorOf] (белок ниже цели — тёплым предупреждением). null — пропуск.
 */
@Composable
fun BarsChart(
    labels: List<String>,
    values: List<Float?>,
    colorOf: (Int, Float) -> Color,
    tip: (Int) -> Readout,
    modifier: Modifier = Modifier,
    height: Dp = 180.dp,
    yMax: Float? = null,
    valueFmt: ((Float) -> String)? = ::fmtAxis,
    refs: List<RefLine> = emptyList(),
    xEvery: Int? = null,
    fill: Float = 0.66f,
    hint: String = "Нажми на столбик — числа ниже.",
) {
    val m = rememberTextMeasurer()
    val axis = TextStyle(fontSize = 10.sp, color = LifeInk.Axis)
    val bold = TextStyle(fontSize = 11.sp, color = Ink.TextStrong, fontWeight = FontWeight.SemiBold)
    var sel by remember(labels.size) { mutableStateOf<Int?>(null) }
    val n = labels.size
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(n) {
                detectTapGestures { o ->
                    val left = 34.dp.toPx()
                    if (n == 0) return@detectTapGestures
                    val i = ((o.x - left) / ((size.width - left - 8.dp.toPx()) / n)).toInt().coerceIn(0, n - 1)
                    sel = if (sel == i) null else i
                }
            },
        ) {
            if (n == 0) return@Canvas
            val left = 34.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 16.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val present = values.filterNotNull()
            val hi = yMax ?: niceMax(max(1f, (present + refs.map { it.value }).maxOrNull() ?: 1f))
            val y = grid(m, axis, left, top, bottom, 0f, hi, ticksFor(hi), ::fmtAxis)
            val bw = (right - left) / n
            val w = max(2f, bw * fill)
            values.forEachIndexed { i, v ->
                if (v == null) return@forEachIndexed
                val cx = left + bw * (i + 0.5f)
                val c = colorOf(i, v)
                val yy = y(v.coerceAtMost(hi))
                drawRoundRect(
                    if (sel != null && sel != i) c.copy(alpha = 0.4f) else c,
                    Offset(cx - w / 2f, yy),
                    Size(w, (bottom - yy).coerceAtLeast(0f)),
                    CornerRadius(minOf(4.dp.toPx(), w / 3f)),
                )
                if (valueFmt != null && bw >= 24.dp.toPx()) label(m, valueFmt(v), cx, yy - 8.dp.toPx(), bold)
            }
            for (r in refs) {
                val yy = y(r.value)
                drawLine(Ink.TextStrong.copy(alpha = 0.7f), Offset(left, yy), Offset(right, yy), strokeWidth = 1.2.dp.toPx(), pathEffect = dash)
                // Подпись — слева над чертой: справа её закрывают свежие столбики.
                label(m, r.label, left + 4.dp.toPx(), yy - 8.dp.toPx(), axis.copy(color = Ink.TextStrong), TextAlign.Start)
            }
            val every = xEvery ?: max(1, Math.ceil(n / max(2.0, ((right - left) / 50.dp.toPx()).toDouble())).toInt())
            labels.forEachIndexed { i, l -> if (i % every == 0) label(m, l, left + bw * (i + 0.5f), bottom + 10.dp.toPx(), axis) }
        }
        ReadoutLine(sel?.let(tip), hint)
    }
}

/**
 * Стопки по периодам и, если есть, линия поверх (доход над тратами).
 * Скрытые ряды не рисуются и не входят в шкалу.
 */
@Composable
fun StackBarsChart(
    labels: List<String>,
    stacks: List<LifeSeries>,
    tip: (Int) -> Readout,
    modifier: Modifier = Modifier,
    line: LifeSeries? = null,
    height: Dp = 220.dp,
    yMax: Float? = null,
    yFmt: (Float) -> String = ::fmtAxis,
    refs: List<RefLine> = emptyList(),
    xEvery: Int? = null,
    fill: Float = 0.66f,
    hint: String = "Нажми на столбик — числа ниже.",
) {
    val m = rememberTextMeasurer()
    val axis = TextStyle(fontSize = 10.sp, color = LifeInk.Axis)
    var sel by remember(labels.size) { mutableStateOf<Int?>(null) }
    val n = labels.size
    Column(modifier.fillMaxWidth()) {
        Canvas(
            Modifier.fillMaxWidth().height(height).pointerInput(n) {
                detectTapGestures { o ->
                    val left = 40.dp.toPx()
                    if (n == 0) return@detectTapGestures
                    val i = ((o.x - left) / ((size.width - left - 8.dp.toPx()) / n)).toInt().coerceIn(0, n - 1)
                    sel = if (sel == i) null else i
                }
            },
        ) {
            if (n == 0) return@Canvas
            val left = 40.dp.toPx()
            val right = size.width - 8.dp.toPx()
            val top = 12.dp.toPx()
            val bottom = size.height - 20.dp.toPx()
            val vis = stacks.filter { !it.hidden }
            fun total(i: Int) = vis.sumOf { (it.data.getOrNull(i) ?: 0f).toDouble() }.toFloat()
            var peak = (0 until n).maxOfOrNull { total(it) } ?: 0f
            if (line != null && !line.hidden) peak = max(peak, line.data.filterNotNull().maxOrNull() ?: 0f)
            refs.forEach { peak = max(peak, it.value) }
            val hi = yMax ?: niceMax(max(1f, peak))
            val y = grid(m, axis, left, top, bottom, 0f, hi, ticksFor(hi), yFmt)
            val bw = (right - left) / n
            val w = max(2f, bw * fill)
            for (i in 0 until n) {
                var acc = 0f
                val dim = sel != null && sel != i
                for (s in vis) {
                    val v = s.data.getOrNull(i) ?: continue
                    if (v <= 0f) continue
                    val y0 = y(acc.coerceAtMost(hi))
                    val y1 = y((acc + v).coerceAtMost(hi))
                    drawRect(if (dim) s.color.copy(alpha = 0.4f) else s.color, Offset(left + bw * (i + 0.5f) - w / 2f, y1), Size(w, (y0 - y1).coerceAtLeast(0f)))
                    acc += v
                }
            }
            for (r in refs) {
                val yy = y(r.value)
                drawLine(Ink.TextStrong.copy(alpha = 0.7f), Offset(left, yy), Offset(right, yy), strokeWidth = 1.2.dp.toPx(), pathEffect = dash)
                // Подпись — слева над чертой: справа её закрывают свежие столбики.
                label(m, r.label, left + 4.dp.toPx(), yy - 8.dp.toPx(), axis.copy(color = Ink.TextStrong), TextAlign.Start)
            }
            if (line != null && !line.hidden) {
                val path = Path()
                var pen = false
                line.data.forEachIndexed { i, v ->
                    if (v == null) {
                        pen = false
                        return@forEachIndexed
                    }
                    val p = Offset(left + bw * (i + 0.5f), y(v.coerceAtMost(hi)))
                    if (pen) path.lineTo(p.x, p.y) else path.moveTo(p.x, p.y)
                    pen = true
                }
                drawPath(path, line.color, style = Stroke(2.6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
                line.data.forEachIndexed { i, v -> if (v != null) drawCircle(line.color, 3.6.dp.toPx(), Offset(left + bw * (i + 0.5f), y(v.coerceAtMost(hi)))) }
            }
            val every = xEvery ?: max(1, Math.ceil(n / max(2.0, ((right - left) / 44.dp.toPx()).toDouble())).toInt())
            labels.forEachIndexed { i, l -> if (i % every == 0) label(m, l, left + bw * (i + 0.5f), bottom + 10.dp.toPx(), axis) }
        }
        ReadoutLine(sel?.let(tip), hint)
    }
}

// ---------------------------------------------------------------------------
// Горизонтальные полосы
// ---------------------------------------------------------------------------

/** Полоса с подписью над ней: [frac] 0..1 заливки, [marker] — черта (норма). */
@Immutable
class HBar(val label: String, val value: String, val frac: Float, val color: Color)

@Composable
fun HBars(rows: List<HBar>, modifier: Modifier = Modifier, marker: Float? = null, thick: Dp = 10.dp) {
    val t = LocalPravkaType.current
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        rows.forEach { r ->
            Column(Modifier.fillMaxWidth()) {
                Row(Modifier.fillMaxWidth().padding(bottom = 4.dp)) {
                    Text(r.label, style = t.body, color = Ink.Text, modifier = Modifier.weight(1f))
                    Text(r.value, style = t.label, color = Ink.TextSecondary)
                }
                Canvas(Modifier.fillMaxWidth().height(thick)) {
                    val rad = CornerRadius(size.height / 2f)
                    drawRoundRect(Ink.Text.copy(alpha = 0.08f), Offset.Zero, size, rad)
                    val w = size.width * r.frac.coerceIn(0f, 1f)
                    if (w > 0f) drawRoundRect(r.color, Offset.Zero, Size(w, size.height), rad)
                    if (marker != null) {
                        val x = size.width * marker.coerceIn(0f, 1f)
                        drawLine(Ink.TextStrong.copy(alpha = 0.6f), Offset(x, 0f), Offset(x, size.height), strokeWidth = 2.dp.toPx())
                    }
                }
            }
        }
    }
}

/** Плитка-цифра: крупное число и подпись под ним. */
@Composable
fun BigStat(value: String, caption: String, modifier: Modifier = Modifier, color: Color = Ink.TextStrong) {
    val t = LocalPravkaType.current
    Column(modifier) {
        Text(value, style = t.valueXL.copy(fontSize = 28.sp), color = color, maxLines = 1)
        Text(caption, style = t.meta, color = Ink.TextSecondary)
    }
}

/** «Что видно» — серая плашка с чертой слева: вывод словами из чисел. */
@Composable
fun WhyNote(title: String, text: String, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(topStart = 4.dp, bottomStart = 4.dp, topEnd = 14.dp, bottomEnd = 14.dp))
            .background(Ink.Text.copy(alpha = 0.06f))
            .drawBehind { drawRect(Ink.TextStrong.copy(alpha = 0.55f), size = Size(3.dp.toPx(), size.height)) }
            .padding(start = 16.dp, end = 14.dp, top = 10.dp, bottom = 10.dp),
    ) {
        Text(title, style = t.bodyStrong, color = Ink.TextStrong)
        Text(text, style = t.body, color = Ink.Text)
    }
}
