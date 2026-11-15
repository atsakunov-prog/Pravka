package ru.zf.slushalka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.max
import kotlin.math.min

/**
 * Где на экране лежит каждый абзац и как он разложен по строкам.
 *
 * Нужно ради «обвести и спросить»: палец рисует овал поверх текста, а
 * спросить надо про слова, а не про пиксели. Каждый нарисованный `Text`
 * читалки сообщает сюда свои границы (`onGloballyPositioned`) и раскладку
 * (`onTextLayout`); по ним прямоугольник обводки превращается в диапазон
 * знаков книги. Ключ - смещение начала куска в тексте: у абзаца оно одно, у
 * куска абзаца на странице - своё.
 */
class TextHits {
    private class Hit(val start: Int) {
        var bounds: Rect? = null
        var layout: TextLayoutResult? = null
    }

    private val map = HashMap<Int, Hit>()

    /**
     * Сколько раз текст на экране сдвинулся или переложился. Читают его только
     * ручки выделения: им надо ехать вместе с текстом при прокрутке и
     * перелистывании, а сам реестр - не состояние Compose.
     */
    val moves = androidx.compose.runtime.mutableIntStateOf(0)

    fun place(start: Int, bounds: Rect) {
        val hit = map.getOrPut(start) { Hit(start) }
        if (hit.bounds != bounds) {
            hit.bounds = bounds
            moves.intValue++
        }
    }

    fun layout(start: Int, layout: TextLayoutResult) {
        map.getOrPut(start) { Hit(start) }.layout = layout
        moves.intValue++
    }

    fun forget(start: Int) {
        map.remove(start)
    }

    /**
     * Границы строк (верх, низ) всех кусков на экране в координатах корня -
     * для листания тапом в прокрутке: страница вперёд начинается с первой
     * строки, которая не влезла целиком, а не с середины строки.
     */
    fun lines(): List<Pair<Float, Float>> {
        val out = ArrayList<Pair<Float, Float>>()
        for (h in map.values) {
            val b = h.bounds ?: continue
            val l = h.layout ?: continue
            for (i in 0 until l.lineCount) out += (b.top + l.getLineTop(i)) to (b.top + l.getLineBottom(i))
        }
        out.sortBy { it.first }
        return out
    }

    /**
     * Знак книги под точкой (в координатах корня) - для выделения тапами.
     *
     * [prefer] отличает куски на экране от соседних страниц пейджера: при
     * растворении следующая страница лежит на том же месте прозрачной, и без
     * этого двойной тап выделял бы слово на ней, а не на видимой.
     */
    fun charAt(root: Offset, prefer: (Int) -> Boolean = { true }): Int? {
        var fallback: Int? = null
        for (h in map.values) {
            val b = h.bounds ?: continue
            val l = h.layout ?: continue
            if (!b.contains(root)) continue
            val at = h.start + l.getOffsetForPosition(root - b.topLeft)
            if (prefer(h.start)) return at
            if (fallback == null) fallback = at
        }
        return fallback
    }

    /**
     * Где на экране стоит знак книги [at] - прямоугольник курсора перед ним, в
     * координатах корня. Для ручек выделения: они висят у начала и у конца
     * выделенного. [prefer] - как в [charAt]: кусок на видимой странице, а не на
     * соседней прозрачной. null - знак не на экране.
     */
    fun cursorAt(at: Int, prefer: (Int) -> Boolean = { true }): Rect? {
        var fallback: Rect? = null
        for (h in map.values) {
            val b = h.bounds ?: continue
            val l = h.layout ?: continue
            val len = l.layoutInput.text.length
            if (at < h.start || at > h.start + len) continue
            val r = l.getCursorRect((at - h.start).coerceIn(0, len)).translate(b.topLeft)
            if (prefer(h.start)) return r
            if (fallback == null) fallback = r
        }
        return fallback
    }

    /**
     * Знаки книги под прямоугольником (в координатах корня экрана). Внутри
     * каждого задетого куска берётся от знака под левым верхним углом до знака
     * под правым нижним: обвёл три строки - получил с начала первой задетой
     * до конца последней, как и ждёшь от овала.
     */
    fun select(rect: Rect): IntRange? {
        var from = Int.MAX_VALUE
        var to = -1
        for (h in map.values) {
            val b = h.bounds ?: continue
            val l = h.layout ?: continue
            if (!b.overlaps(rect)) continue
            val x1 = (rect.left - b.left).coerceIn(0f, b.width)
            val y1 = (rect.top - b.top).coerceIn(0f, b.height)
            val x2 = (rect.right - b.left).coerceIn(0f, b.width)
            val y2 = (rect.bottom - b.top).coerceIn(0f, b.height)
            val a = l.getOffsetForPosition(Offset(x1, y1))
            val z = l.getOffsetForPosition(Offset(x2, y2))
            from = min(from, h.start + min(a, z))
            to = max(to, h.start + max(a, z))
        }
        return if (to > from) from..to else null
    }
}

/**
 * Ручки выделения (16.11.2026): капли у начала и у конца выделенного, как в
 * любой читалке. Тянешь - край едет за пальцем по целым словам. До них кусок
 * для заметки выделялся только серией тапов: слово, фраза или абзац, и ни
 * буквой больше - владелец так и не нашёл, как записать мысль к месту.
 *
 * Слой во весь экран без своих жестов: тапы мимо капель проходят к странице.
 */
@Composable
fun SelectionHandles(
    selection: IntRange,
    hits: TextHits,
    plain: String,
    prefer: (Int) -> Boolean,
    color: androidx.compose.ui.graphics.Color,
    onChange: (IntRange) -> Unit,
) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    // Ручки едут вместе с текстом: перечитать места, когда он сдвинулся.
    @Suppress("UNUSED_VARIABLE") val moved = hits.moves.intValue
    val current by androidx.compose.runtime.rememberUpdatedState(selection)
    Box(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        for (isStart in listOf(true, false)) {
            val at = if (isStart) selection.first else selection.last
            val r = hits.cursorAt(at, prefer) ?: continue
            // key: у каждой капли своё состояние перетаскивания, даже когда
            // начальная ушла с экрана и в цикле осталась одна конечная.
            androidx.compose.runtime.key(isStart) {
            Handle(
                cursor = Rect(r.left - origin.x, r.top - origin.y, r.right - origin.x, r.bottom - origin.y),
                color = color,
                onDrag = { point ->
                    val root = point + origin
                    val hit = hits.charAt(root, prefer) ?: return@Handle
                    val sel = current
                    val next = if (isStart) {
                        var s = hit.coerceAtMost(sel.last - 1)
                        while (s > 0 && plain[s - 1].isLetterOrDigit()) s--
                        s..sel.last
                    } else {
                        var e = hit.coerceAtLeast(sel.first + 1).coerceAtMost(plain.length)
                        while (e < plain.length && plain[e].isLetterOrDigit()) e++
                        sel.first..e
                    }
                    if (next.last > next.first && next != sel) onChange(next)
                },
            )
            }
        }
    }
}

/**
 * Одна капля: черта ровно на границе знака во всю высоту строки и круг под
 * строкой. [cursor] - в координатах слоя.
 *
 * Рисунок едет за выделением сразу, а место под палец на время перетаскивания
 * замирает там, где его взяли: иначе ход пальца мерился бы от места, которое
 * само уезжает, и капля прыгала бы. Выделение тянется за серединой строки
 * плюс ход пальца - палец держит каплю под строкой и не закрывает слово.
 */
@Composable
private fun Handle(
    cursor: Rect,
    color: androidx.compose.ui.graphics.Color,
    onDrag: (Offset) -> Unit,
) {
    val density = androidx.compose.ui.platform.LocalDensity.current
    val touch = with(density) { 48.dp.toPx() }
    val radius = with(density) { 7.dp.toPx() }
    val stroke = with(density) { 2.dp.toPx() }
    Canvas(Modifier.fillMaxSize()) {
        drawLine(color, Offset(cursor.left, cursor.top), Offset(cursor.left, cursor.bottom + radius * 0.5f), strokeWidth = stroke)
        drawCircle(color, radius = radius, center = Offset(cursor.left, cursor.bottom + radius * 1.3f))
    }
    val live by androidx.compose.runtime.rememberUpdatedState(cursor)
    val drag by androidx.compose.runtime.rememberUpdatedState(onDrag)
    var frozen by remember { mutableStateOf<Rect?>(null) }
    var moved by remember { mutableStateOf(Offset.Zero) }
    val anchor = frozen ?: cursor
    Box(
        Modifier
            .offset {
                androidx.compose.ui.unit.IntOffset(
                    (anchor.left - touch / 2).toInt(),
                    (anchor.bottom - touch * 0.1f).toInt(),
                )
            }
            .size(48.dp)
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { frozen = live; moved = Offset.Zero },
                    onDragEnd = { frozen = null },
                    onDragCancel = { frozen = null },
                    onDrag = { change, delta ->
                        change.consume()
                        moved += delta
                        val a = frozen ?: return@detectDragGestures
                        drag(Offset(a.left, a.center.y) + moved)
                    },
                )
            },
    )
}

/** Обводка редко ложится точно по границам слов - раздвигаем до целых. */
fun snapToWords(plain: String, range: IntRange): IntRange {
    var s = range.first.coerceIn(0, plain.length)
    var e = range.last.coerceIn(s, plain.length)
    while (s > 0 && plain[s - 1].isLetterOrDigit()) s--
    while (e < plain.length && plain[e].isLetterOrDigit()) e++
    return s..e
}

/**
 * Слой «обвести»: ловит один росчерк пальцем, рисует его и отдаёт наверх
 * прямоугольник, в который росчерк вписан (в координатах корня). Лежит ниже
 * панелей читалки, чтобы кнопки на них оставались живыми.
 */
@Composable
fun LassoLayer(
    palette: ReaderPalette,
    onDone: (Rect) -> Unit,
) {
    var points by remember { mutableStateOf(listOf<Offset>()) }
    var origin by remember { mutableStateOf(Offset.Zero) }

    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned { origin = it.positionInRoot() }
            .pointerInput(Unit) {
                detectDragGestures(
                    onDragStart = { points = listOf(it) },
                    onDrag = { change, _ -> points = points + change.position },
                    onDragCancel = { points = emptyList() },
                    onDragEnd = {
                        val pts = points
                        points = emptyList()
                        if (pts.size < 3) return@detectDragGestures
                        val left = pts.minOf { it.x }
                        val right = pts.maxOf { it.x }
                        val top = pts.minOf { it.y }
                        val bottom = pts.maxOf { it.y }
                        // Случайное касание - не обводка.
                        if (right - left < 24f && bottom - top < 24f) return@detectDragGestures
                        onDone(Rect(left + origin.x, top + origin.y, right + origin.x, bottom + origin.y))
                    },
                )
            },
    ) {
        Canvas(Modifier.fillMaxSize()) {
            if (points.size >= 2) {
                val path = Path().apply {
                    moveTo(points[0].x, points[0].y)
                    for (p in points.drop(1)) lineTo(p.x, p.y)
                }
                drawPath(
                    path,
                    color = palette.fg.copy(alpha = 0.55f),
                    style = Stroke(width = 6.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round),
                )
            }
        }
        Text(
            "Обведи пальцем, о чём спросить",
            color = palette.bg,
            fontSize = 13.sp,
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 56.dp)
                .clip(RoundedCornerShape(20.dp))
                .background(palette.fg.copy(alpha = 0.88f))
                .padding(horizontal = 14.dp, vertical = 8.dp),
        )
    }
}
