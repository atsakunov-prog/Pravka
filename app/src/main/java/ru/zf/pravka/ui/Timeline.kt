package ru.zf.pravka.ui

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import ru.zf.pravka.core.BalanceLine
import ru.zf.pravka.core.DayAssembler
import ru.zf.pravka.core.Fmt

// Хроника (DESIGN §11.5): строки общей ленты — «Сегодня», разворот и
// Засечка. Строка — труба линии баланса 84, колонка времени 38 (вправо) и
// зазор 8, содержимое и колонка очков 30. Труба рисуется в каждой строке своим куском
// (`drawBehind`), поэтому линия и стенки выглядят непрерывными.
//
// Высота строки — НЕ меньше макетной, а не ровно она (баги №7, №10,
// 07.10.2026, владелец: «шрифт красивый в Дне, но регулярно не влезает…
// давай везде переносить по строкам, потому что для меня всё-таки важна
// информация»): текст переносится, строка растёт, рельс тянется за ней
// (`height(IntrinsicSize.Min)` — колонка рельса меряет высоту по соседям).
// Отметки режимов (еда, деньги, спорт, дела) — ТОЧКОЙ цвета режима прямо на
// рельсе во время отметки, текст рядом плашкой. Сначала (07.10, баг №7) на
// рельсе стояла монета со значком; владелец в тот же вечер (№14): «на
// таймлайне плохо выглядят эти иконки, давай просто точками… другого цвета».
// В плашке первым — число (№15: в еде калории, в деньгах деньги, в делах —
// сколько новых), оно жирнее остального.
// Текст всех строк начинается с одной линии (№19: «ровно такой же отступ, как
// и внутри плашек»): у строк без плашки содержимое сдвинуто на внутренний
// отступ плашки — [TEXT_INSET].
// Точка и линия записи — в цвете категории на радуге (`categoryFill`): с
// 10.10.2026 снова радуга вместо оттенков янтаря — «пропало понимание, чем я
// занимаюсь».
// Рельс прошлого — ЛИНИЯ БАЛАНСА, «дорога жизни» (10.10.2026, владелец:
// «вести эту линию как можно правее»): слева своя труба, время и дело —
// справа от неё (линия под цифрами времени владельцу не понравилась).
// Посередине — сплошная, ноль. Разметка — свои 28 дней к тому же часу:
// пунктир полосы — медиана («лучше 50% своих дней»), стенка — 75% («край
// туннеля»); утром дорога узкая, к вечеру расходится. За стенкой — обочина,
// туда можно выехать. Точка записи — у её времени, на балле «до»; дальше
// мягкая S-кривая уводит линию к баллу «после». Краска линии — мягкий
// светофор цены часа (`worthTint`: красный — потери, жёлтый — обычное,
// зелёный — работа и спорт), на изломе — перелив от прошлой записи к своей.
// Отметки (еда, деньги, спорт, дела) точек не ставят — линия идёт через их
// строки прямо, плашки остались. «Сейчас» — пульсирующая точка на конце
// линии, от неё вправо — черта «сейчас» с временем; ниже — пунктир ровно под
// точкой, кольца плана, дел и сна — на нём. Числа — `core/BalanceLine.kt`.

private val TIME_W = 38.dp
/** Зазор после колонки времени: плашка отметки не прилипает к цифрам. */
private val TIME_GAP = 8.dp
/** Труба линии баланса: ширина, где стенки, насколько внутрь от края ходит линия. */
private val TUBE_W = 84.dp
private val TUBE_PAD = 6.dp
private val PTS_W = 30.dp
/** Внутренний отступ плашки отметки — на столько же сдвинут текст строк без плашки. */
private val TEXT_INSET = 10.dp

/** Как рисуется рельс строки. */
sealed class Rail {
    /** Прошлое: точка 8 и сплошная 2 dp в цвете категории ([line] — цвет линии вниз). */
    data class Dot(val color: Color, val line: Color? = color) : Rail()
    /** Текущая: точка 10 цвета «сейчас» с ореолом 3 dp. */
    data class Current(val line: Color, val halo: Color) : Rail()
    /** Отметка или ждущее: просто линия на всю высоту. */
    data class Through(val color: Color) : Rail()
    /** План: полое кольцо и пунктир в цвете источника. */
    data class Ring(val color: Color, val line: Color?, val size: Dp = 10.dp) : Rail()
    /** Свободно: точечная линия. */
    data class Dotted(val color: Color) : Rail()
    /** Пунктир без кольца (заголовок группы дел). */
    data class Dashed(val color: Color) : Rail()
    data object None : Rail()
}

private fun DrawScope.rail(r: Rail, x: Float) {
    val w2 = 2.dp.toPx()
    val dash = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))
    when (r) {
        is Rail.Dot -> {
            val top = 5.dp.toPx()
            val d = 8.dp.toPx()
            drawCircle(r.color, d / 2, Offset(x, top + d / 2))
            r.line?.let { drawLine(it, Offset(x, top + d + 2.dp.toPx()), Offset(x, size.height), w2) }
        }
        is Rail.Current -> {
            val top = 4.dp.toPx()
            val d = 10.dp.toPx()
            val c = Offset(x, top + d / 2)
            drawCircle(r.halo, d / 2 + 3.dp.toPx(), c)
            drawCircle(Ink.Now, d / 2, c)
            drawLine(r.line, Offset(x, top + d + 2.dp.toPx()), Offset(x, size.height), w2)
        }
        is Rail.Through -> drawLine(r.color, Offset(x, 0f), Offset(x, size.height), w2)
        is Rail.Ring -> {
            val d = r.size.toPx()
            val top = (if (r.size > 10.dp) 3.dp else 4.dp).toPx()
            drawCircle(r.color, d / 2 - 1.dp.toPx(), Offset(x, top + d / 2), style = Stroke(2.dp.toPx()))
            r.line?.let {
                drawLine(it, Offset(x, top + d + 3.dp.toPx()), Offset(x, size.height), w2, pathEffect = dash)
            }
        }
        is Rail.Dotted -> drawLine(
            r.color, Offset(x, 0f), Offset(x, size.height), w2, cap = StrokeCap.Round,
            pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 5.dp.toPx())),
        )
        is Rail.Dashed -> drawLine(r.color, Offset(x, 0f), Offset(x, size.height), w2, pathEffect = dash)
        Rail.None -> Unit
    }
}

/**
 * Линия баланса в строке: место в коридоре сверху и снизу строки (−1…1),
 * цвет сверху (прошлая категория) и свой, вид строки (`BalanceLine.Kind`).
 * [future] — у «сейчас»: ниже есть будущее, пунктир под точкой; [last] —
 * последняя строка хроники: линия кончается на её точке или кольце, а не
 * уходит за край; [endDot] — последняя запись прошлого дня: точка и в конце,
 * на балле, которым день закрылся; [lanes] — разметка дороги в этой строке.
 */
@Immutable
class BalanceSpec(
    val top: Float,
    val bottom: Float,
    val from: Color,
    val color: Color,
    val kind: BalanceLine.Kind,
    val future: Boolean = false,
    val last: Boolean = false,
    val endDot: Boolean = false,
    val lanes: Lanes? = null,
)

/**
 * Разметка дороги в строке, доли трубы (0…1) сверху и снизу: [mid] — медиана
 * своих дней к этому часу (пунктир полосы), [wall] — 75 % (стенка).
 */
@Immutable
class Lanes(val midTop: Float, val midBottom: Float, val wallTop: Float, val wallBottom: Float)

/** Краска дороги у строки [i] — светофор цены часа записи, к которой строка прикреплена. */
fun roadTintAt(items: List<DayAssembler.DayItem>, i: Int): Color {
    for (k in i downTo 0) {
        val e = items[k] as? DayAssembler.DayItem.Entry ?: continue
        return worthTint(e.worth)
    }
    return Ink.TimePast
}

/**
 * Линия баланса по строкам хроники. [dayStart] — начало суток (разметка
 * считается к тому же часу), [road] — свои прошлые дни; null — истории нет,
 * разметки тоже, масштаб — по самому дню.
 */
fun balanceSpecs(items: List<DayAssembler.DayItem>, dayStart: Long, road: BalanceLine.Road?): List<BalanceSpec> {
    val segs = BalanceLine.segments(items)
    val scale = BalanceLine.scale(BalanceLine.maxAbs(segs), road)
    // Прошлый день («сейчас» нет): у последней записи точка и в конце.
    val closing = if (items.none { it is DayAssembler.DayItem.Now }) items.indexOfLast { it is DayAssembler.DayItem.Entry } else -1
    fun lane(t: Long, q: Double): Float = road?.let { kotlin.math.abs(BalanceLine.frac(it.at(t - dayStart, q), scale)) } ?: 0f
    return segs.mapIndexed { i, s ->
        val color = roadTintAt(items, i)
        // Перелив на изломе: у записи сверху — цвет прошлой записи.
        val from = if (items[i] is DayAssembler.DayItem.Entry && i > 0 && items.subList(0, i).any { it is DayAssembler.DayItem.Entry }) roadTintAt(items, i - 1) else color
        BalanceSpec(
            BalanceLine.frac(s.top, scale), BalanceLine.frac(s.bottom, scale), from, color, s.kind,
            future = s.kind == BalanceLine.Kind.NOW && i < items.lastIndex,
            last = i == items.lastIndex,
            endDot = i == closing,
            lanes = road?.let {
                Lanes(
                    lane(s.tTop, BalanceLine.MID_Q), lane(s.tBottom, BalanceLine.MID_Q),
                    lane(s.tTop, BalanceLine.WALL_Q), lane(s.tBottom, BalanceLine.WALL_Q),
                )
            },
        )
    }
}

/** Точка записи — на уровне её времени. */
private val DOT_Y = 9.dp
private val DOT_R = 4.dp
private val FUTURE_INK = Ink.Cream.copy(alpha = 0.35f)
/** Разметка дороги — лёгкая: сплошная посередине заметнее стенок, пунктир полосы — тише всех. */
private val TUBE_ZERO = Ink.Cream.copy(alpha = 0.24f)
private val TUBE_WALL = Ink.Cream.copy(alpha = 0.18f)
private val TUBE_LANE = Ink.Cream.copy(alpha = 0.16f)
/** Линия баланса — главная на рельсе, чуть толще прежнего рельса в 2 dp. */
private val LINE_W = 2.5.dp

private fun DrawScope.tubeX(f: Float): Float {
    val hw = size.width / 2f - TUBE_PAD.toPx()
    return size.width / 2f + f * hw
}

private fun DrawScope.dashed(color: Color, x: Float, y0: Float, y1: Float) {
    if (y1 <= y0) return
    drawLine(color, Offset(x, y0), Offset(x, y1), LINE_W.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx())))
}

/** S-кривая через строку: от [x0] сверху к [x1] снизу, касательные на стыках вертикальны. */
private fun DrawScope.sPath(x0: Float, x1: Float): Path = Path().apply {
    moveTo(x0, 0f)
    cubicTo(x0, size.height / 2f, x1, size.height / 2f, x1, size.height)
}

/**
 * Дорога: сплошная посередине (ноль), по обе стороны — пунктир полосы
 * (медиана своих дней к этому часу) и стенка (75 %); стенки и полосы
 * тянутся S-кривыми, как и линия, — дорога расширяется по ходу дня.
 */
private fun DrawScope.tube(l: Lanes?) {
    val w = 1.dp.toPx()
    drawLine(TUBE_ZERO, Offset(size.width / 2f, 0f), Offset(size.width / 2f, size.height), w)
    if (l == null) return
    val lane = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 5.dp.toPx()))
    for (side in intArrayOf(1, -1)) {
        drawPath(sPath(tubeX(side * l.midTop), tubeX(side * l.midBottom)), TUBE_LANE, style = Stroke(w, pathEffect = lane))
        drawPath(sPath(tubeX(side * l.wallTop), tubeX(side * l.wallBottom)), TUBE_WALL, style = Stroke(w))
    }
}

/** Линия баланса строки в трубе; [rail] — кольцо плана или дела, оно встаёт на пунктир будущего. */
private fun DrawScope.balanceLine(b: BalanceSpec, rail: Rail) {
    val w = LINE_W.toPx()
    val x0 = tubeX(b.top)
    val x1 = tubeX(b.bottom)
    val dy = DOT_Y.toPx()
    when (b.kind) {
        BalanceLine.Kind.ENTRY, BalanceLine.Kind.CURRENT -> {
            val end = if (b.endDot) size.height - dy else size.height
            val path = Path().apply {
                moveTo(x0, 0f)
                lineTo(x0, dy)
                // Касательные на концах вертикальны: на стыке строк линия не ломается.
                val mid = dy + (end - dy) / 2f
                cubicTo(x0, mid, x1, mid, x1, end)
            }
            val brush = if (b.from == b.color) SolidColor(b.color)
            else Brush.verticalGradient(listOf(b.from, b.color), startY = 0f, endY = dy * 1.6f)
            drawPath(path, brush, style = Stroke(w))
            drawCircle(b.color, DOT_R.toPx(), Offset(x0, dy))
            if (b.endDot) drawCircle(b.color, DOT_R.toPx(), Offset(x1, end))
        }
        BalanceLine.Kind.THROUGH -> drawLine(b.color, Offset(x0, 0f), Offset(x0, size.height), w)
        BalanceLine.Kind.FUTURE -> {
            val ring = rail as? Rail.Ring
            if (ring != null) {
                // Кольцо плана, дела или сна — на пунктире, ровно под точкой «сейчас».
                val d = ring.size.toPx()
                val top = (if (ring.size > 10.dp) 3.dp else 4.dp).toPx()
                dashed(FUTURE_INK, x0, 0f, top - 2.dp.toPx())
                if (!b.last) dashed(FUTURE_INK, x0, top + d + 2.dp.toPx(), size.height)
                drawCircle(ring.color, d / 2 - 1.dp.toPx(), Offset(x0, top + d / 2), style = Stroke(2.dp.toPx()))
            } else dashed(FUTURE_INK, x0, 0f, if (b.last) dy else size.height)
        }
        BalanceLine.Kind.NOW -> Unit
    }
}

/**
 * Строка хроники: время (одна или две строки), рельс, содержимое и очки.
 * [railTap] — тап по трубе и колонке времени (на всю высоту строки): у дела
 * это «сделано» (DESIGN §3.8 «кольцо дела»).
 */
@Composable
fun TimelineRow(
    height: Dp,
    rail: Rail,
    modifier: Modifier = Modifier,
    time: String? = null,
    timeColor: Color = Ink.TimePast,
    timeBold: Boolean = false,
    time2: String? = null,
    points: String? = null,
    pointsColor: Color = PointsPlus,
    onClick: (() -> Unit)? = null,
    onLongClick: (() -> Unit)? = null,
    railTap: (() -> Unit)? = null,
    highlight: Boolean = false,
    /** Время мелким вторым тоном (отметка: время прихода еды или траты). */
    timeSmall: Boolean = false,
    /** Сдвиг содержимого: у строк без плашки — внутренний отступ плашки, у плашек — 0. */
    inset: Dp = TEXT_INSET,
    /** Линия баланса в трубе (null — рельс посередине трубы). */
    balance: BalanceSpec? = null,
    content: @Composable RowScope.() -> Unit,
) {
    val t = LocalPravkaType.current
    val timeTop = if (timeSmall) 8.dp else 1.dp
    Row(
        modifier
            .fillMaxWidth()
            .heightIn(min = height)
            .height(IntrinsicSize.Min)
            .then(if (highlight) Modifier.clip(RoundedCornerShape(12.dp)).background(Ink.Now.copy(alpha = 0.06f)) else Modifier)
            .then(
                when {
                    onClick != null && onLongClick != null -> Modifier.combinedClickable(onClick = onClick, onLongClick = onLongClick)
                    onClick != null -> Modifier.clickable(onClick = onClick)
                    else -> Modifier
                }
            ),
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .then(if (railTap != null) Modifier.clickable(role = Role.Checkbox, onClickLabel = "сделано", onClick = railTap) else Modifier),
        ) {
            Box(
                Modifier
                    .width(TUBE_W)
                    .fillMaxHeight()
                    .drawBehind {
                        tube(balance?.lanes)
                        if (balance != null) balanceLine(balance, rail) else rail(rail, size.width / 2f)
                    },
            )
            Column(Modifier.width(TIME_W).padding(top = timeTop), horizontalAlignment = Alignment.End) {
                if (time != null) {
                    Text(
                        time,
                        style = if (timeSmall) t.meta.copy(lineHeight = 14.sp) else t.time.copy(fontWeight = if (timeBold) FontWeight.SemiBold else FontWeight.Normal),
                        color = timeColor,
                        maxLines = 1,
                        softWrap = false,
                        textAlign = TextAlign.End,
                    )
                }
                if (time2 != null) Text(time2, style = t.meta.copy(lineHeight = 15.sp), color = Ink.PlanEnd, maxLines = 1)
            }
            Spacer(Modifier.width(TIME_GAP))
        }
        Row(Modifier.weight(1f).fillMaxHeight().padding(start = inset), content = content)
        if (points != null) {
            Text(
                points,
                style = t.points,
                color = pointsColor,
                textAlign = TextAlign.End,
                maxLines = 1,
                modifier = Modifier.width(PTS_W).padding(top = 0.dp),
            )
        }
    }
}

/**
 * Запись прошлого (DESIGN §11.5 EntryRow): название · клиент вторым тоном ·
 * ★полезность сразу после названия; вторая строка — категория · длительность;
 * третья — заметка курсивом. Справа очки.
 */
@Composable
fun EntryRow(
    e: DayAssembler.DayItem.Entry,
    lineDown: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    highlight: Boolean = false,
    balance: BalanceSpec? = null,
) {
    val t = LocalPravkaType.current
    val color = categoryFill(e.category)
    val withNote = e.comment.isNotBlank()
    TimelineRow(
        height = if (withNote) 60.dp else 44.dp,
        rail = if (balance != null) Rail.None else Rail.Dot(color, if (lineDown) color else null),
        balance = balance,
        time = Fmt.hm(e.start),
        points = e.points?.takeIf { it != 0 }?.let { Fmt.points(it) },
        pointsColor = pointsColor(e.points ?: 0),
        onClick = onClick,
        modifier = modifier,
        highlight = highlight,
    ) {
        Column(Modifier.weight(1f).padding(bottom = 7.dp)) {
            Text(entryTitle(e.title, e.useful, e.client), style = t.body, color = Ink.Text)
            val second = if (e.fromYesterday) "с ${Fmt.hm(e.fullStart)} вчера · за ночь ${Fmt.dur(e.fullMinutes)}"
            else "${e.category.ifBlank { "без категории" }} · ${Fmt.dur(e.minutes)}"
            Text(second, style = t.meta, color = Ink.TextMeta)
            if (withNote) {
                Text(e.comment, style = t.meta.copy(fontStyle = FontStyle.Italic), color = Ink.TextNote)
            }
        }
    }
}

/** «Созвон: бюджет на IV квартал ★5 · Бета Групп» — ★ сразу после названия и не обрезается раньше клиента. */
fun entryTitle(title: String, useful: Int, client: String) = buildAnnotatedString {
    append(title)
    if (useful > 0) withStyle(SpanStyle(color = PointsPlus)) { append(" ★$useful") }
    if (client.isNotBlank()) withStyle(SpanStyle(color = Ink.TextMeta)) { append(" · $client") }
}

/**
 * Текущая запись (DESIGN §11.5 CurrentRow): время и название жирным, чип
 * категории и «идёт 21 м» янтарём. [stop] — кнопка «стоп» справа (разворот).
 */
@Composable
fun CurrentRow(
    e: DayAssembler.DayItem.Entry,
    now: Long,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    stop: (() -> Unit)? = null,
    balance: BalanceSpec? = null,
) {
    val t = LocalPravkaType.current
    val color = categoryFill(e.category)
    TimelineRow(
        height = 42.dp,
        // Ореол «сейчас» — в цвете самой категории: видно, чем занят, ещё до текста.
        // С линией баланса точки сверху нет: она пульсирует на конце линии, у «сейчас».
        rail = if (balance != null) Rail.None else Rail.Current(color, color.copy(alpha = 0.35f)),
        balance = balance,
        time = Fmt.hm(e.start),
        timeColor = Ink.Now,
        timeBold = true,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(Modifier.weight(1f).padding(bottom = 7.dp), verticalAlignment = Alignment.Top) {
            Text(e.title, style = t.bodyStrong, color = Ink.Text, modifier = Modifier.weight(1f, fill = false))
            if (e.category.isNotBlank() && !e.gap) {
                Spacer(Modifier.width(8.dp))
                TagChip(e.category.substringBefore(':').trim(), categoryText(e.category), color.copy(alpha = 0.30f))
            }
            Spacer(Modifier.width(6.dp))
            Text(
                "идёт ${Fmt.dur(((now - e.start) / 60_000L).toInt())}",
                style = t.label.copy(fontWeight = FontWeight.SemiBold),
                color = Modes.Zasechka.tint,
                maxLines = 1,
            )
        }
        if (stop != null) {
            Spacer(Modifier.width(6.dp))
            StopKey(stop)
        }
    }
}

/** Монета режима по источнику отметки. */
fun DayAssembler.Source.decor(): ModeDecor = when (this) {
    DayAssembler.Source.SPORT -> ModeDecor.SPORT
    DayAssembler.Source.FOOD -> ModeDecor.FOOD
    DayAssembler.Source.MONEY -> ModeDecor.MONEY
    DayAssembler.Source.DELA -> ModeDecor.DELA
}

/**
 * Отметка в ленте (DESIGN §11.5 EventChip): монета 20 и текст в цветах
 * режима — капсула 26 dp. Тап открывает режим на этой записи.
 */
@Composable
fun EventChip(source: DayAssembler.Source, text: String, onClick: () -> Unit, modifier: Modifier = Modifier, coin: Boolean = true) {
    val m = Modes.of(source.decor())
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier
            .heightIn(min = 26.dp)
            .clip(shape)
            .background(m.key.copy(alpha = 0.32f))
            .border(1.dp, m.tint.copy(alpha = 0.29f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = if (coin) 3.dp else 10.dp, end = 10.dp, top = 3.dp, bottom = 3.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        if (coin) Coin(source.decor(), 20.dp)
        Text(leadBold(text), style = t.label.copy(fontWeight = FontWeight.Normal), color = m.eventText)
    }
}

/** «419 ккал · Творог…» — первое (число) жирнее остального. */
private fun leadBold(text: String) = buildAnnotatedString {
    val cut = text.indexOf(" · ")
    if (cut <= 0) {
        append(text)
        return@buildAnnotatedString
    }
    withStyle(SpanStyle(fontWeight = FontWeight.SemiBold)) { append(text.substring(0, cut)) }
    append(text.substring(cut))
}

/**
 * Строка отметки: рельс записи насквозь (точки нет — 10.10.2026, владелец:
 * «уберём точки с едой, по деньгам… оставим только плашки»), слева — время
 * отметки мелким, справа — плашка с текстом (переносится, без монеты).
 */
@Composable
fun MarkRow(source: DayAssembler.Source, text: String, line: Color, onClick: () -> Unit, at: Long? = null, balance: BalanceSpec? = null) {
    TimelineRow(
        height = 34.dp,
        rail = if (balance != null) Rail.None else Rail.Through(line),
        balance = balance,
        time = at?.let { Fmt.hm(it) },
        timeColor = Ink.TextMeta,
        timeSmall = true,
        inset = 0.dp,
    ) {
        Box(Modifier.weight(1f).padding(top = 2.dp, bottom = 6.dp)) { EventChip(source, text, onClick, coin = false) }
    }
}

/**
 * Ждёт подтверждения (DESIGN §11.5 PendingCard): капсула 36 в цвете режима,
 * монета 24, текст и маленькая клавиша «Записать» в tint.
 */
@Composable
fun PendingRow(source: DayAssembler.Source, text: String, line: Color, onOpen: () -> Unit, onConfirm: () -> Unit, at: Long? = null, balance: BalanceSpec? = null) {
    val m = Modes.of(source.decor())
    val t = LocalPravkaType.current
    TimelineRow(
        height = 42.dp,
        rail = if (balance != null) Rail.None else Rail.Through(line),
        balance = balance,
        time = at?.let { Fmt.hm(it) },
        timeColor = Ink.TextMeta,
        timeSmall = true,
        inset = 0.dp,
    ) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .weight(1f)
                .padding(bottom = 6.dp)
                .heightIn(min = 36.dp)
                .clip(shape)
                .background(m.key.copy(alpha = 0.30f))
                .border(1.dp, m.tint.copy(alpha = 0.38f), shape)
                .clickable(onClick = onOpen)
                .padding(start = TEXT_INSET, end = 3.dp, top = 3.dp, bottom = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(leadBold(text), style = t.label.copy(fontWeight = FontWeight.Normal), color = m.eventText, modifier = Modifier.weight(1f))
            val bs = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .height(28.dp)
                    .clip(bs)
                    .keyFace4(m.tint, bs)
                    .clickable(role = Role.Button, onClick = onConfirm)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Записать", style = t.label.copy(fontWeight = FontWeight.SemiBold), color = m.ink, maxLines = 1)
            }
        }
    }
}

/**
 * Линия «сейчас» (DESIGN §11.5 NowLine): конец линии баланса — точка
 * «сейчас» пульсирует на балле дня (владелец: «немножко пульсирующей»), от неё
 * вправо через колонку времени с плашкой «18:51» идёт черта, тающая к краю;
 * ниже, если есть будущее, — пунктир ровно под точкой. Без [balance] — точка
 * посередине трубы.
 */
@Composable
fun NowLine(time: String, modifier: Modifier = Modifier, balance: BalanceSpec? = null) {
    val t = LocalPravkaType.current
    val b = balance ?: BalanceSpec(0f, 0f, Ink.TimePast, Ink.TimePast, BalanceLine.Kind.NOW)
    val pulse = rememberInfiniteTransition(label = "now")
    val p by pulse.animateFloat(
        0f, 1f,
        infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Reverse),
        label = "now-pulse",
    )
    val nowLine = Ink.Now.copy(alpha = 0.7f)
    Row(modifier.fillMaxWidth().height(30.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(
            Modifier
                .width(TUBE_W)
                .fillMaxHeight()
                .drawBehind {
                    tube(b.lanes)
                    val x = tubeX(b.top)
                    val cy = size.height / 2f
                    if (balance != null) drawLine(b.color, Offset(x, 0f), Offset(x, cy), LINE_W.toPx())
                    if (b.future) dashed(FUTURE_INK, x, cy, size.height)
                    drawLine(nowLine, Offset(x, cy), Offset(size.width, cy), 1.5.dp.toPx())
                    // Ореол дышит в цвете идущей категории, ядро — «сейчас».
                    drawCircle(b.color.copy(alpha = 0.50f - 0.35f * p), 6.dp.toPx() + 5.dp.toPx() * p, Offset(x, cy))
                    drawCircle(Ink.Now, 5.dp.toPx(), Offset(x, cy))
                },
        )
        Box(
            Modifier
                .width(TIME_W)
                .fillMaxHeight()
                .drawBehind { drawLine(nowLine, Offset(0f, size.height / 2f), Offset(size.width, size.height / 2f), 1.5.dp.toPx()) }
                .wrapContentWidth(Alignment.End, unbounded = true),
            contentAlignment = Alignment.CenterEnd,
        ) {
            Text(
                time,
                style = t.nowBadge,
                color = Ink.NowInk,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Ink.Now).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Box(Modifier.width(TIME_GAP).height(1.5.dp).background(nowLine))
        Box(
            Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(Brush.horizontalGradient(listOf(nowLine, Ink.Now.copy(alpha = 0.08f)))),
        )
    }
}

/** «свободно 1 ч 10 м» (DESIGN §11.5 FreeGap) — будущее округлено до 5 минут. */
@Composable
fun FreeRow(minutes: Int, balance: BalanceSpec? = null) {
    val t = LocalPravkaType.current
    TimelineRow(height = 30.dp, rail = if (balance != null) Rail.None else Rail.Dotted(Ink.Cream.copy(alpha = 0.35f)), balance = balance) {
        Text("свободно ${Fmt.durFuture(minutes)}", style = t.meta, color = Ink.FreeText, modifier = Modifier.padding(top = 6.dp))
    }
}

/**
 * План (DESIGN §11.5 PlannedRow): календарь — кремовый чип «Календарь»,
 * тренировка — «Спорт · план»; длительность и подсказка («можно тяжёлое»).
 * У плана во второй строке времени — конец.
 */
@Composable
fun PlannedRow(
    start: Long?,
    end: Long?,
    title: String,
    workout: Boolean,
    note: String,
    minutes: Int,
    onClick: (() -> Unit)?,
    balance: BalanceSpec? = null,
) {
    val t = LocalPravkaType.current
    val sport = Modes.Sport
    val ring = if (workout) sport.tint else Ink.PlanText
    val line = if (workout) sport.tint.copy(alpha = 0.55f) else Ink.PlanText.copy(alpha = 0.40f)
    TimelineRow(
        height = 48.dp,
        // С линией баланса своя линия кольцам не нужна: пунктир — под точкой «сейчас».
        rail = Rail.Ring(ring, if (balance != null) null else line),
        balance = balance,
        time = start?.let { Fmt.hm(it) },
        timeColor = Ink.PlanText,
        time2 = end?.let { Fmt.hm(it) },
        onClick = onClick,
    ) {
        Column(Modifier.weight(1f).padding(bottom = 7.dp)) {
            Text(title, style = t.body, color = Ink.PlanText)
            Row(verticalAlignment = Alignment.Top) {
                if (workout) TagChip("Спорт · план", sport.label, sport.key.copy(alpha = 0.45f))
                else TagChip("Календарь", Ink.PlanText, Ink.Cream.copy(alpha = 0.12f))
                Spacer(Modifier.width(4.dp))
                val tail = listOf(Fmt.dur(minutes), note).filter { it.isNotBlank() }.joinToString(" · ")
                Text(tail, style = t.meta, color = Ink.TextMeta)
            }
        }
    }
}

/** «ДЕЛА НА СЕГОДНЯ · 5 · 2 Ч 25 М» (DESIGN §11.5 TaskGroupHeader). */
@Composable
fun TaskGroupRow(count: Int, minutes: Int, balance: BalanceSpec? = null) {
    val t = LocalPravkaType.current
    TimelineRow(height = 24.dp, rail = if (balance != null) Rail.None else Rail.Dashed(Modes.Dela.tint.copy(alpha = 0.45f)), balance = balance) {
        Text(
            "ДЕЛА НА СЕГОДНЯ · $count · ${Fmt.dur(minutes).uppercase()}",
            style = t.overline,
            color = Color(0xFF8FB4D4),
            modifier = Modifier.padding(top = 4.dp, bottom = 4.dp),
        )
    }
}

/**
 * Дело в будущем (DESIGN §11.5 TaskRow): кольцо-галочка, «Кто: действие»,
 * вторая строка «#61 · Бета Групп · 45 м». Тап по кольцу и времени —
 * сделано, по строке — открыть, долгое — «▶ начать в Засечке» и «поправить».
 */
@Composable
fun TaskTimelineRow(
    item: DayAssembler.DayItem.Task,
    onDone: () -> Unit,
    onOpen: () -> Unit,
    onLong: () -> Unit,
    done: Boolean = false,
    balance: BalanceSpec? = null,
) {
    val t = LocalPravkaType.current
    val dela = Modes.Dela
    TimelineRow(
        height = 42.dp,
        rail = Rail.Ring(dela.tint, if (balance != null) null else dela.tint.copy(alpha = 0.45f), 12.dp),
        balance = balance,
        time = Fmt.hm(item.start),
        timeColor = if (item.first || item.fixed) Color(0xFFC9D9E6) else dela.meta,
        onClick = onOpen,
        onLongClick = onLong,
        railTap = onDone,
    ) {
        Column(Modifier.weight(1f).padding(bottom = 7.dp)) {
            Text(
                item.title,
                style = t.body,
                color = Ink.PlanText.copy(alpha = if (done) 0.5f else 1f),
            )
            val second = listOf(item.second, Fmt.dur(item.minutes)).filter { it.isNotBlank() }.joinToString(" · ")
            Text(second, style = t.meta, color = dela.meta)
        }
    }
}

/** Сон во время из настроек (DESIGN §11.5 SleepRow) и, если дела не влезают, — предупреждение. */
@Composable
fun SleepRow(at: Long, overflowMin: Int, balance: BalanceSpec? = null) {
    val t = LocalPravkaType.current
    TimelineRow(height = 40.dp, rail = Rail.Ring(Color(0xFF8A6A4E), null), time = Fmt.hm(at), timeColor = Ink.TextSecondary, balance = balance) {
        Text(
            buildAnnotatedString {
                append("Сон")
                if (overflowMin > 0) {
                    withStyle(SpanStyle(color = Ink.Warn, fontWeight = FontWeight.SemiBold, fontSize = 12.sp)) {
                        append(" · дела не влезают на ${Fmt.durFuture(overflowMin)}")
                    }
                }
            },
            style = t.body,
            color = Ink.TextSecondary,
            modifier = Modifier.padding(bottom = 7.dp),
        )
    }
}

/** Что сделали с делом в хронике: тап по кольцу, по строке, долгое нажатие. */
enum class TaskAct { DONE, OPEN, MENU }

/**
 * Одна строка хроники по `DayItem` — для `LazyColumn` «Сегодня» и ленты
 * Засечки. [line] — цвет записи, к которой прикреплены отметки (рельс
 * насквозь), [nextIsNow] — за записью сразу «сейчас», и линия вниз не идёт;
 * [balance] — линия баланса этой строки (`balanceSpecs`).
 */
@Composable
fun TimelineItem(
    item: DayAssembler.DayItem,
    line: Color,
    nextIsNow: Boolean,
    now: Long,
    onEntry: (DayAssembler.DayItem.Entry) -> Unit = {},
    onMark: (DayAssembler.DayItem.Mark) -> Unit = {},
    onPending: (DayAssembler.DayItem.Pending, Boolean) -> Unit = { _, _ -> },
    onTask: (DayAssembler.DayItem.Task, TaskAct) -> Unit = { _, _ -> },
    onPlanned: (DayAssembler.DayItem.Planned) -> Unit = {},
    balance: BalanceSpec? = null,
    onStop: (() -> Unit)? = null,
) {
    val b = balance
    when (item) {
        is DayAssembler.DayItem.Entry ->
            if (item.current) CurrentRow(item, now, { onEntry(item) }, stop = onStop, balance = b)
            else EntryRow(item, lineDown = !nextIsNow, onClick = { onEntry(item) }, balance = b)
        is DayAssembler.DayItem.Mark -> MarkRow(item.source, item.text, line, { onMark(item) }, at = item.at, balance = b)
        is DayAssembler.DayItem.Pending -> PendingRow(item.source, item.text, line, { onPending(item, false) }, { onPending(item, true) }, at = item.at, balance = b)
        is DayAssembler.DayItem.Now -> NowLine(Fmt.hm(item.at), balance = b)
        is DayAssembler.DayItem.Free -> FreeRow(item.minutes, balance = b)
        is DayAssembler.DayItem.Planned -> PlannedRow(item.start, item.end, item.title, item.workout, item.note, item.minutes, if (item.workout) ({ onPlanned(item) }) else null, balance = b)
        is DayAssembler.DayItem.PlannedLoose -> PlannedRow(null, null, item.title, true, item.note, item.minutes, null, balance = b)
        is DayAssembler.DayItem.TaskGroup -> TaskGroupRow(item.count, item.minutes, balance = b)
        is DayAssembler.DayItem.Task -> TaskTimelineRow(item, { onTask(item, TaskAct.DONE) }, { onTask(item, TaskAct.OPEN) }, { onTask(item, TaskAct.MENU) }, balance = b)
        is DayAssembler.DayItem.Sleep -> SleepRow(item.at, item.overflowMin, balance = b)
    }
}

/**
 * Дыра без записи между записями ленты Засечки: «··· 25 м без записи», тап —
 * «что это было». Линия баланса идёт через неё прямо: балл за дыру не копится.
 */
@Composable
fun HoleRow(minutes: Int, balance: BalanceSpec?, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    TimelineRow(height = 28.dp, rail = Rail.None, balance = balance, onClick = onClick) {
        Text("···  ${Fmt.dur(minutes)} без записи", style = t.meta, color = Ink.TextNote, modifier = Modifier.padding(top = 5.dp))
    }
}

/** Цвет рельса отметки — цвет записи, к которой она прикреплена. */
fun lineColorAt(items: List<DayAssembler.DayItem>, i: Int): Color {
    for (k in i downTo 0) {
        val e = items[k] as? DayAssembler.DayItem.Entry ?: continue
        return categoryFill(e.category)
    }
    return Ink.TimePast
}

/** Вся хроника столбцом (Витрина, превью): истории нет — без разметки, масштаб по самому дню. */
@Composable
fun TimelineItems(items: List<DayAssembler.DayItem>, now: Long) {
    val specs = remember(items) { balanceSpecs(items, 0L, null) }
    items.forEachIndexed { i, it ->
        TimelineItem(it, lineColorAt(items, i), i < items.lastIndex && items[i + 1] is DayAssembler.DayItem.Now, now, balance = specs[i])
    }
}
