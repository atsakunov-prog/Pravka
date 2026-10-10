package ru.zf.pravka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.Fmt
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

// Засечка Правки 4.0 (07.10.2026, DESIGN §11.6, §12.3): циферблат суток,
// плашка времени, полоса по категориям, строки ленты и итоги. Циферблат
// заменяет полосу баланса −5…+10 (`RainbowScoreBar` ушёл из набора): польза
// растёт наружу от базового круга, потери — внутрь, сон и почти-ноль — тонкой
// дугой по самому кругу. Геометрия — ровно §12.3 в поле 300.
// Краска — радуга категорий (`categoryFill`, 10.10.2026): сектор, черта
// строки и полоса итога — цветом своей категории, полоса плашки и легенда —
// областью радуги группы (`ZGroup`).

/** Сектор циферблата: запись ленты внутри суток. */
class DialSector(
    val id: Long,
    /** Минуты от начала суток. */
    val fromMin: Float,
    val toMin: Float,
    /** Очки за час категории. */
    val worth: Int,
    val color: Color,
    val current: Boolean,
    val hatched: Boolean = false,
)

private const val FIELD = 300f
private const val R0 = 100f

/** Точка на круге радиуса [r] (в поле 300) для минуты суток [min]: 00 внизу, по часовой. */
private fun pt(c: Float, r: Float, min: Float): Offset {
    val th = Math.toRadians(min / 1440.0 * 360.0)
    return Offset((c - r * sin(th)).toFloat(), (c + r * cos(th)).toFloat())
}

/**
 * Циферблат суток (DESIGN §12.3). [size] — 308 на сложенном, 256 на
 * развороте. [nowMin] — стрелка «сейчас» (null — прошлый день); от неё до
 * конца суток — точечная дуга. В центре — балл, «балл дня», сравнение и место.
 */
@Composable
fun Dial(
    sectors: List<DialSector>,
    nowMin: Float?,
    score: String,
    modifier: Modifier = Modifier,
    size: Dp = 308.dp,
    compare: (@Composable () -> Unit)? = null,
    place: String? = null,
    onSector: ((Long) -> Unit)? = null,
    wide: Boolean = false,
) {
    val t = LocalPravkaType.current
    val measurer = rememberTextMeasurer()
    val labelStyle = t.dialLabel.copy(fontSize = if (wide) 11.sp else 10.5.sp, color = Ink.TextNote)
    val labels = remember(labelStyle) { listOf("00", "06", "12", "18").map { measurer.measure(it, labelStyle) } }
    Box(
        modifier
            .size(size)
            .semantics { contentDescription = "Сутки по часам: снаружи круга — время с пользой, внутрь — потери. Балл $score" }
            .then(
                if (onSector != null) Modifier.pointerInput(sectors) {
                    detectTapGestures { o ->
                        val k = this.size.width / FIELD
                        val c = FIELD / 2
                        val x = o.x / k - c
                        val y = o.y / k - c
                        val r = hypot(x, y)
                        // Обратное к pt(): x = −r·sin θ, y = r·cos θ.
                        var th = Math.toDegrees(atan2(-x.toDouble(), y.toDouble()))
                        if (th < 0) th += 360.0
                        val min = (th / 360.0 * 1440.0).toFloat()
                        sectors.firstOrNull { s ->
                            val (inner, outer) = radii(s.worth)
                            min >= s.fromMin && min < s.toMin && r >= inner - 8 && r <= outer + 8
                        }?.let { onSector(it.id) }
                    }
                } else Modifier
            ),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxSize()) {
            val k = this.size.width / FIELD
            val c = FIELD / 2
            fun p(r: Float, min: Float) = pt(c, r, min).let { Offset(it.x * k, it.y * k) }
            // Базовый круг.
            drawCircle(Modes.Zasechka.key.copy(alpha = 0.10f), R0 * k, Offset(c * k, c * k), style = Stroke(1.dp.toPx()))
            // Секторы.
            for (s in sectors) {
                val (inner, outer) = radii(s.worth)
                val a1 = s.fromMin / 1440f * 360f + 0.35f
                val a2 = s.toMin / 1440f * 360f - 0.35f
                if (a2 <= a1) continue
                val path = sectorPath(c * k, inner * k, outer * k, a1, a2)
                if (s.hatched) {
                    clipPath(path) { hatch(s.color, 0.30f, period = 5.dp) }
                } else {
                    drawPath(path, s.color)
                }
                if (s.current) drawPath(path, Ink.Now, style = Stroke(1.2.dp.toPx() * k.coerceAtMost(1.2f)))
            }
            // Остаток дня — точечная дуга по базовому кругу.
            if (nowMin != null && nowMin < 1440f) {
                val a1 = nowMin / 1440f * 360f
                drawArc(
                    Modes.Zasechka.key.copy(alpha = 0.35f),
                    startAngle = a1 + 90f,
                    sweepAngle = 360f - a1,
                    useCenter = false,
                    topLeft = Offset((c - R0) * k, (c - R0) * k),
                    size = Size(2 * R0 * k, 2 * R0 * k),
                    style = Stroke(1.5.dp.toPx(), cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(1.5f * k, 4f * k))),
                )
            }
            // Риски часов: каждые два часа, главные — 00/06/12/18.
            for (h in 0 until 24 step 2) {
                val main = h % 6 == 0
                val m = h * 60f
                drawLine(
                    Color(0xFFFFD6AA).copy(alpha = if (main) 0.55f else 0.22f),
                    p(R0 - 9, m), p(R0 - if (main) 13 else 11, m),
                    strokeWidth = 1.4f * k, cap = StrokeCap.Round,
                )
            }
            // Подписи 00 / 06 / 12 / 18 — на R0 + 43.
            listOf(0f, 360f, 720f, 1080f).forEachIndexed { i, m ->
                val q = p(R0 + 43, m)
                val l = labels[i]
                drawText(l, topLeft = Offset(q.x - l.size.width / 2f, q.y - l.size.height / 2f))
            }
            // Стрелка «сейчас» — от R0 − 20 до R0 + 40, с точкой.
            if (nowMin != null) {
                val a = p(R0 - 20, nowMin)
                val b = p(R0 + 40, nowMin)
                drawLine(Ink.Now, a, b, strokeWidth = 1.6f * k, cap = StrokeCap.Round)
                drawCircle(Ink.Now, 3.2f * k, b)
            }
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(score, style = if (wide) t.displayXLWide else t.displayXL, color = PointsPlus)
            Text("балл дня", style = t.label, color = Modes.Zasechka.label, modifier = Modifier.padding(top = 2.dp))
            if (compare != null) Box(Modifier.padding(top = 8.dp)) { compare() }
            if (place != null) Text(place, style = t.label, color = Modes.Zasechka.label)
        }
    }
}

/** Внутренний и внешний радиус сектора (DESIGN §12.3). */
private fun radii(worth: Int): Pair<Float, Float> {
    val d = 3.4f * worth
    return if (worth >= 0) (R0 - 2f) to maxOf(R0 + d, R0 + 2f)
    else minOf(R0 + d, R0 - 2f) to (R0 + 2f)
}

/** Кольцевой сектор: внешняя дуга по часовой, внутренняя — обратно. Угол arcTo = θ + 90. */
private fun sectorPath(c: Float, inner: Float, outer: Float, a1: Float, a2: Float): Path = Path().apply {
    val sweep = a2 - a1
    arcTo(Rect(Offset(c, c), outer), a1 + 90f, sweep, forceMoveTo = true)
    arcTo(Rect(Offset(c, c), inner), a2 + 90f, -sweep, forceMoveTo = false)
    close()
}

/** «▲ 13 к пн, 28 сент» — дельта балла к тому же дню неделю назад. */
@Composable
fun DialCompare(delta: Int, against: String) {
    val t = LocalPravkaType.current
    Text(
        buildAnnotatedString {
            withStyle(SpanStyle(color = if (delta >= 0) PointsPlus else Ink.Warn, fontWeight = FontWeight.SemiBold)) {
                append(if (delta >= 0) "▲ $delta" else "▼ ${-delta}")
            }
            withStyle(SpanStyle(color = Modes.Zasechka.label)) { append(" к $against") }
        },
        style = t.label,
    )
}

// ---------------------------------------------------------------------------
// Полоса и легенда
// ---------------------------------------------------------------------------

/** Отрезок полосы: доля, группа (цвет и штриховка). */
class BarPart(val weight: Float, val group: ZGroup)

/**
 * Полоса по категориям (DESIGN §11.7 StackedBar): 12 dp, промежутки 2 dp,
 * концы скруглены 4, середина — 2. Потери и «впереди» — штриховкой.
 */
@Composable
fun StackedBar(parts: List<BarPart>, modifier: Modifier = Modifier, height: Dp = 12.dp) {
    val shown = parts.filter { it.weight > 0f }
    Row(modifier.fillMaxWidth().height(height), horizontalArrangement = Arrangement.spacedBy(2.dp)) {
        shown.forEachIndexed { i, p ->
            val first = i == 0
            val last = i == shown.lastIndex
            Box(
                Modifier
                    .weight(p.weight)
                    .height(height)
                    .clip(RoundedCornerShape(topStart = if (first) 4.dp else 2.dp, bottomStart = if (first) 4.dp else 2.dp, topEnd = if (last) 4.dp else 2.dp, bottomEnd = if (last) 4.dp else 2.dp))
                    .drawBehind {
                        when (p.group) {
                            ZGroup.LOSS -> hatch(p.group.fill, 0.30f, period = 5.dp)
                            ZGroup.AHEAD -> hatch(p.group.fill, 0.07f, period = 6.dp, stripeAlpha = 0.34f)
                            else -> drawRect(p.group.fill)
                        }
                    },
            )
        }
    }
}

/** Пункт легенды (DESIGN §11.7 LegendItem): квадрат 11 (радиус 3), подпись и значение жирным. */
@Composable
fun LegendItem(group: ZGroup, label: String, value: String) {
    val t = LocalPravkaType.current
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Box(
            Modifier
                .size(11.dp)
                .clip(RoundedCornerShape(3.dp))
                .drawBehind {
                    when (group) {
                        ZGroup.LOSS -> hatch(group.fill, 0.30f, period = 5.dp)
                        ZGroup.AHEAD -> hatch(group.fill, 0.08f, period = 6.dp, stripeAlpha = 0.40f)
                        else -> drawRect(group.fill)
                    }
                },
        )
        Text(label, style = t.label, color = Modes.Zasechka.label, maxLines = 1)
        Text(
            value,
            style = t.label.copy(fontWeight = FontWeight.SemiBold),
            color = when (group) {
                ZGroup.LOSS -> Ink.Warn
                ZGroup.AHEAD -> ZGroup.AHEAD.text
                else -> Ink.LegendValue
            },
            maxLines = 1,
        )
    }
}

/** Состояние плашки времени: день идёт, до подъёма, день закрыт (после сна), прошлый день. */
enum class TimePlateState { AWAKE, BEFORE_WAKE, CLOSED, PAST }

/**
 * Плашка времени (DESIGN §11.6 TimePlate): навигатор дня, «11 ч 46 м»
 * крупно, «с подъёма в 07:05 · сон 7 ч 25 м», справа «4 ч 40 м / до сна в
 * 23:30», полоса 12 dp и легенда. [nav] = false — без навигатора (разворот
 * «Сегодня»).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun TimePlate(
    big: String,
    sub: String,
    right: String?,
    rightSub: String?,
    parts: List<BarPart>,
    legend: List<Triple<ZGroup, String, String>>,
    modifier: Modifier = Modifier,
    nav: (@Composable () -> Unit)? = null,
    wide: Boolean = false,
) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(24.dp)
    Column(
        modifier
            .fillMaxWidth()
            .glass(shape, Modes.Zasechka.glass)
            .padding(start = 16.dp, end = 16.dp, top = if (nav != null) 4.dp else 14.dp, bottom = 14.dp),
    ) {
        if (nav != null) Box(Modifier.padding(horizontal = 0.dp)) { nav() }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp), verticalAlignment = Alignment.Top) {
            Column(Modifier.weight(1f)) {
                Text(big, style = if (wide) t.displayLWide else t.displayL, color = Modes.Zasechka.value, maxLines = 1)
                Text(sub, style = t.label, color = Modes.Zasechka.label, modifier = Modifier.padding(top = 2.dp))
            }
            if (right != null) {
                Column(Modifier.padding(top = 6.dp), horizontalAlignment = Alignment.End) {
                    Text(right, style = t.valueS, color = Ink.Text, maxLines = 1)
                    if (rightSub != null) Text(rightSub, style = t.meta, color = Modes.Zasechka.label, maxLines = 1)
                }
            }
        }
        if (parts.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            StackedBar(parts)
        }
        if (legend.isNotEmpty()) {
            Spacer(Modifier.height(12.dp))
            FlowRow(horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                legend.forEach { (g, l, v) -> LegendItem(g, l, v) }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Лента и итоги Засечки
// ---------------------------------------------------------------------------

/**
 * Строка ленты внутри Засечки (DESIGN §11.6 ZasechkaEntryRow, `screens/04`):
 * 46 dp, цветная черта 3×30 слева, название (★ сразу после него), вторая
 * строка «17:45–18:30 · Семья · 45 м» с категорией в её цвете, очки справа.
 * Текущая — жирным, «с 18:30 · Еда · идёт 21 м».
 */
@Composable
fun ZasechkaEntryRow(
    title: String,
    useful: Int,
    category: String,
    worth: Int,
    time: String,
    duration: String,
    points: Int?,
    current: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    client: String = "",
    divider: Boolean = true,
    trailing: (@Composable () -> Unit)? = null,
) {
    val t = LocalPravkaType.current
    Column(modifier.fillMaxWidth()) {
        if (divider) Box(Modifier.fillMaxWidth().height(1.dp).background(Color(0xFFFFBE78).copy(alpha = 0.08f)))
        Row(
            // Не ровно 46, а не меньше: длинное название переносится (баг №10).
            Modifier.fillMaxWidth().heightIn(min = 46.dp).clickable(onClick = onClick).padding(vertical = 5.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Box(Modifier.width(3.dp).height(30.dp).clip(RoundedCornerShape(2.dp)).background(categoryFill(category)))
            Column(Modifier.weight(1f)) {
                Text(
                    entryTitle(title, useful, client),
                    style = if (current) t.bodyStrong else t.body,
                    color = Ink.Text,
                )
                Text(
                    buildAnnotatedString {
                        append(time)
                        if (category.isNotBlank()) {
                            append(" · ")
                            withStyle(SpanStyle(color = categoryText(category))) { append(category) }
                        }
                        append(" · ")
                        if (current) {
                            append("идёт ")
                            withStyle(SpanStyle(color = Ink.Now)) { append(duration) }
                        } else append(duration)
                    },
                    style = t.meta,
                    color = Ink.TextMeta,
                )
            }
            if (points != null && points != 0) {
                Text(Fmt.points(points), style = t.points, color = pointsColor(points), maxLines = 1)
            }
            trailing?.invoke()
        }
    }
}

/**
 * Итог по категории (DESIGN §11.6 CategoryRow): название, полоса в цвете
 * категории на радуге (доля от самой длинной), время, доля дня и очки.
 * Потери — штриховкой.
 */
@Composable
fun CategoryRow(name: String, worth: Int, minutes: Int, share: String, points: Int, fraction: Float) {
    val t = LocalPravkaType.current
    val color = categoryFill(name)
    val loss = ZGroup.of(name, worth) == ZGroup.LOSS
    Row(Modifier.fillMaxWidth().height(36.dp), verticalAlignment = Alignment.CenterVertically) {
        FitText(name, style = t.body, color = Ink.Text, modifier = Modifier.weight(1f), minSize = 12f)
        Box(Modifier.width(48.dp).height(6.dp).padding(start = 8.dp)) {
            Box(
                Modifier
                    .fillMaxWidth(fraction.coerceIn(0.04f, 1f))
                    .height(6.dp)
                    .clip(RoundedCornerShape(3.dp))
                    .drawBehind { if (loss) hatch(color, 0.30f) else drawRect(color) },
            )
        }
        Text(Fmt.dur(minutes), style = t.label.copy(fontWeight = FontWeight.SemiBold), color = Ink.LegendValue, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(56.dp))
        Text(share, style = t.meta, color = Ink.TextMeta, textAlign = TextAlign.End, maxLines = 1, modifier = Modifier.width(36.dp))
        Text(
            if (points == 0) "" else Fmt.points(points),
            style = t.points,
            color = pointsColor(points),
            textAlign = TextAlign.End,
            maxLines = 1,
            modifier = Modifier.width(34.dp),
        )
    }
}
