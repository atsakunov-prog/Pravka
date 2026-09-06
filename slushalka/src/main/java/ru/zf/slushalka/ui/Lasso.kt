package ru.zf.slushalka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
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

    fun place(start: Int, bounds: Rect) {
        map.getOrPut(start) { Hit(start) }.bounds = bounds
    }

    fun layout(start: Int, layout: TextLayoutResult) {
        map.getOrPut(start) { Hit(start) }.layout = layout
    }

    fun forget(start: Int) {
        map.remove(start)
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
