package ru.zf.pravka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
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
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
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
import ru.zf.pravka.core.DayAssembler
import ru.zf.pravka.core.Fmt

// Хроника (DESIGN §11.5): строки общей ленты — «Сегодня», разворот и
// Засечка. Строка — колонка времени 42 (вправо), рельс 18, содержимое и
// колонка очков 30. Рельс рисуется в каждой строке своим куском (`drawBehind`),
// поэтому линия выглядит непрерывной. Числа — `mockups/01–03`.

private val TIME_W = 42.dp
private val RAIL_W = 18.dp
private val PTS_W = 30.dp

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
 * Строка хроники: время (одна или две строки), рельс, содержимое и очки.
 * [railTap] — тап по колонке времени и рельсу (60 dp на всю высоту строки):
 * у дела это «сделано» (DESIGN §3.8 «кольцо дела»).
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
    content: @Composable RowScope.() -> Unit,
) {
    val t = LocalPravkaType.current
    Row(
        modifier
            .fillMaxWidth()
            .height(height)
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
            Column(Modifier.width(TIME_W).padding(top = 1.dp), horizontalAlignment = Alignment.End) {
                if (time != null) {
                    Text(
                        time,
                        style = t.time.copy(fontWeight = if (timeBold) FontWeight.SemiBold else FontWeight.Normal),
                        color = timeColor,
                        maxLines = 1,
                        textAlign = TextAlign.End,
                    )
                }
                if (time2 != null) Text(time2, style = t.meta.copy(lineHeight = 15.sp), color = Ink.PlanEnd, maxLines = 1)
            }
            Box(
                Modifier
                    .width(RAIL_W)
                    .fillMaxHeight()
                    .drawBehind { rail(rail, size.width / 2f) },
            )
        }
        Row(Modifier.weight(1f).fillMaxHeight(), content = content)
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

/** Цвет группы категории (DESIGN §4.3). */
fun zFill(category: String, worth: Int): Color = ZGroup.of(category, worth).fill

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
) {
    val t = LocalPravkaType.current
    val group = ZGroup.of(e.category, e.worth)
    val color = group.fill
    val withNote = e.comment.isNotBlank()
    TimelineRow(
        height = if (withNote) 60.dp else 44.dp,
        rail = Rail.Dot(color, if (lineDown) color else null),
        time = Fmt.hm(e.start),
        points = e.points?.takeIf { it != 0 }?.let { Fmt.points(it) },
        pointsColor = pointsColor(e.points ?: 0),
        onClick = onClick,
        modifier = modifier,
        highlight = highlight,
    ) {
        Column(Modifier.weight(1f)) {
            Text(entryTitle(e.title, e.useful, e.client), style = t.body, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val second = if (e.fromYesterday) "с ${Fmt.hm(e.fullStart)} вчера · за ночь ${Fmt.dur(e.fullMinutes)}"
            else "${e.category.ifBlank { "без категории" }} · ${Fmt.dur(e.minutes)}"
            Text(second, style = t.meta, color = Ink.TextMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (withNote) {
                Text(e.comment, style = t.meta.copy(fontStyle = FontStyle.Italic), color = Ink.TextNote, maxLines = 1, overflow = TextOverflow.Ellipsis)
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
) {
    val t = LocalPravkaType.current
    val group = ZGroup.of(e.category, e.worth)
    TimelineRow(
        height = 42.dp,
        rail = Rail.Current(group.fill, Modes.Zasechka.key.copy(alpha = 0.35f)),
        time = Fmt.hm(e.start),
        timeColor = Ink.Now,
        timeBold = true,
        onClick = onClick,
        modifier = modifier,
    ) {
        Row(Modifier.weight(1f), verticalAlignment = Alignment.Top) {
            Text(e.title, style = t.bodyStrong, color = Ink.Text, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (e.category.isNotBlank() && !e.gap) {
                Spacer(Modifier.width(8.dp))
                TagChip(e.category.substringBefore(':').trim(), group.text, group.fill.copy(alpha = 0.30f))
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
fun EventChip(source: DayAssembler.Source, text: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val m = Modes.of(source.decor())
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(13.dp)
    Row(
        modifier
            .height(26.dp)
            .clip(shape)
            .background(m.key.copy(alpha = 0.32f))
            .border(1.dp, m.tint.copy(alpha = 0.29f), shape)
            .clickable(role = Role.Button, onClick = onClick)
            .padding(start = 3.dp, end = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(7.dp),
    ) {
        Coin(source.decor(), 20.dp)
        Text(text, style = t.label.copy(fontWeight = FontWeight.Normal), color = m.eventText, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

/** Строка отметки: рельс записи насквозь и капсула. */
@Composable
fun MarkRow(source: DayAssembler.Source, text: String, line: Color, onClick: () -> Unit) {
    TimelineRow(height = 34.dp, rail = Rail.Through(line)) {
        Box(Modifier.weight(1f).padding(top = 2.dp)) { EventChip(source, text, onClick) }
    }
}

/**
 * Ждёт подтверждения (DESIGN §11.5 PendingCard): капсула 36 в цвете режима,
 * монета 24, текст и маленькая клавиша «Записать» в tint.
 */
@Composable
fun PendingRow(source: DayAssembler.Source, text: String, line: Color, onOpen: () -> Unit, onConfirm: () -> Unit) {
    val m = Modes.of(source.decor())
    val t = LocalPravkaType.current
    TimelineRow(height = 42.dp, rail = Rail.Through(line)) {
        val shape = RoundedCornerShape(18.dp)
        Row(
            Modifier
                .weight(1f)
                .height(36.dp)
                .clip(shape)
                .background(m.key.copy(alpha = 0.30f))
                .border(1.dp, m.tint.copy(alpha = 0.38f), shape)
                .clickable(onClick = onOpen)
                .padding(start = 4.dp, end = 3.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Coin(source.decor(), 24.dp)
            Text(text, style = t.label.copy(fontWeight = FontWeight.Normal), color = m.eventText, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            val bs = RoundedCornerShape(14.dp)
            Box(
                Modifier
                    .height(28.dp)
                    .clip(bs)
                    .keyFace4(m.tint, bs, hi = 0.55f, low = 0f)
                    .clickable(role = Role.Button, onClick = onConfirm)
                    .padding(horizontal = 12.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text("Записать", style = t.label.copy(fontWeight = FontWeight.SemiBold), color = m.ink, maxLines = 1)
            }
        }
    }
}

/** Линия «сейчас» (DESIGN §11.5 NowLine): плашка «18:51» и линия до правого края, тающая. */
@Composable
fun NowLine(time: String, modifier: Modifier = Modifier) {
    val t = LocalPravkaType.current
    Row(modifier.fillMaxWidth().height(28.dp), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(TIME_W).wrapContentWidth(Alignment.End, unbounded = true), contentAlignment = Alignment.CenterEnd) {
            Text(
                time,
                style = t.nowBadge,
                color = Ink.NowInk,
                maxLines = 1,
                modifier = Modifier.clip(RoundedCornerShape(9.dp)).background(Ink.Now).padding(horizontal = 6.dp, vertical = 1.dp),
            )
        }
        Box(Modifier.width(RAIL_W).height(2.dp).background(Ink.Now.copy(alpha = 0.7f)))
        Box(
            Modifier
                .weight(1f)
                .height(1.5.dp)
                .background(Brush.horizontalGradient(listOf(Ink.Now.copy(alpha = 0.7f), Ink.Now.copy(alpha = 0.08f)))),
        )
    }
}

/** «свободно 1 ч 10 м» (DESIGN §11.5 FreeGap) — будущее округлено до 5 минут. */
@Composable
fun FreeRow(minutes: Int) {
    val t = LocalPravkaType.current
    TimelineRow(height = 30.dp, rail = Rail.Dotted(Ink.Cream.copy(alpha = 0.35f))) {
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
) {
    val t = LocalPravkaType.current
    val sport = Modes.Sport
    val ring = if (workout) sport.tint else Ink.PlanText
    val line = if (workout) sport.tint.copy(alpha = 0.55f) else Ink.PlanText.copy(alpha = 0.40f)
    TimelineRow(
        height = 48.dp,
        rail = Rail.Ring(ring, line),
        time = start?.let { Fmt.hm(it) },
        timeColor = Ink.PlanText,
        time2 = end?.let { Fmt.hm(it) },
        onClick = onClick,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = t.body, color = Ink.PlanText, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (workout) TagChip("Спорт · план", sport.label, sport.key.copy(alpha = 0.45f))
                else TagChip("Календарь", Ink.PlanText, Ink.Cream.copy(alpha = 0.12f))
                Spacer(Modifier.width(4.dp))
                val tail = listOf(Fmt.dur(minutes), note).filter { it.isNotBlank() }.joinToString(" · ")
                Text(tail, style = t.meta, color = Ink.TextMeta, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** «ДЕЛА НА СЕГОДНЯ · 5 · 2 Ч 25 М» (DESIGN §11.5 TaskGroupHeader). */
@Composable
fun TaskGroupRow(count: Int, minutes: Int) {
    val t = LocalPravkaType.current
    TimelineRow(height = 24.dp, rail = Rail.Dashed(Modes.Dela.tint.copy(alpha = 0.45f))) {
        Text(
            "ДЕЛА НА СЕГОДНЯ · $count · ${Fmt.dur(minutes).uppercase()}",
            style = t.overline,
            color = Color(0xFF8FB4D4),
            maxLines = 1,
            modifier = Modifier.padding(top = 4.dp),
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
) {
    val t = LocalPravkaType.current
    val dela = Modes.Dela
    TimelineRow(
        height = 42.dp,
        rail = Rail.Ring(dela.tint, dela.tint.copy(alpha = 0.45f), 12.dp),
        time = Fmt.hm(item.start),
        timeColor = if (item.first || item.fixed) Color(0xFFC9D9E6) else dela.meta,
        onClick = onOpen,
        onLongClick = onLong,
        railTap = onDone,
    ) {
        Column(Modifier.weight(1f)) {
            Text(
                item.title,
                style = t.body,
                color = Ink.PlanText.copy(alpha = if (done) 0.5f else 1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val second = listOf(item.second, Fmt.dur(item.minutes)).filter { it.isNotBlank() }.joinToString(" · ")
            Text(second, style = t.meta, color = dela.meta, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

/** Сон во время из настроек (DESIGN §11.5 SleepRow) и, если дела не влезают, — предупреждение. */
@Composable
fun SleepRow(at: Long, overflowMin: Int) {
    val t = LocalPravkaType.current
    TimelineRow(height = 40.dp, rail = Rail.Ring(Color(0xFF8A6A4E), null), time = Fmt.hm(at), timeColor = Ink.TextSecondary) {
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
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Что сделали с делом в хронике: тап по кольцу, по строке, долгое нажатие. */
enum class TaskAct { DONE, OPEN, MENU }

/**
 * Одна строка хроники по `DayItem` — для `LazyColumn` «Сегодня». [line] —
 * цвет записи, к которой прикреплены отметки (рельс насквозь), [nextIsNow] —
 * за записью сразу «сейчас», и линия вниз не идёт.
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
) {
    when (item) {
        is DayAssembler.DayItem.Entry ->
            if (item.current) CurrentRow(item, now, { onEntry(item) })
            else EntryRow(item, lineDown = !nextIsNow, onClick = { onEntry(item) })
        is DayAssembler.DayItem.Mark -> MarkRow(item.source, item.text, line) { onMark(item) }
        is DayAssembler.DayItem.Pending -> PendingRow(item.source, item.text, line, { onPending(item, false) }, { onPending(item, true) })
        is DayAssembler.DayItem.Now -> NowLine(Fmt.hm(item.at))
        is DayAssembler.DayItem.Free -> FreeRow(item.minutes)
        is DayAssembler.DayItem.Planned -> PlannedRow(item.start, item.end, item.title, item.workout, item.note, item.minutes, if (item.workout) ({ onPlanned(item) }) else null)
        is DayAssembler.DayItem.PlannedLoose -> PlannedRow(null, null, item.title, true, item.note, item.minutes, null)
        is DayAssembler.DayItem.TaskGroup -> TaskGroupRow(item.count, item.minutes)
        is DayAssembler.DayItem.Task -> TaskTimelineRow(item, { onTask(item, TaskAct.DONE) }, { onTask(item, TaskAct.OPEN) }, { onTask(item, TaskAct.MENU) })
        is DayAssembler.DayItem.Sleep -> SleepRow(item.at, item.overflowMin)
    }
}

/** Цвет рельса отметки — цвет записи, к которой она прикреплена. */
fun lineColorAt(items: List<DayAssembler.DayItem>, i: Int): Color {
    for (k in i downTo 0) {
        val e = items[k] as? DayAssembler.DayItem.Entry ?: continue
        return ZGroup.of(e.category, e.worth).fill
    }
    return Ink.TimePast
}

/** Вся хроника столбцом (Витрина, превью). */
@Composable
fun TimelineItems(items: List<DayAssembler.DayItem>, now: Long) {
    items.forEachIndexed { i, it ->
        TimelineItem(it, lineColorAt(items, i), i < items.lastIndex && items[i + 1] is DayAssembler.DayItem.Now, now)
    }
}
