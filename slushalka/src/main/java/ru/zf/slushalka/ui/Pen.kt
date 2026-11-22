package ru.zf.slushalka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDown
import androidx.compose.ui.input.pointer.isPrimaryPressed
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.unit.dp

// Перо (23.11.2026, владелец: «можно сделать поддержку pen, там классная
// ручка»). Как карандашом в бумажной книге: провёл под строкой - маркер,
// обвёл кусок - выделил его и дальше как с выделенным пальцем (спросить,
// заметка, словарь), ластиком или с зажатой кнопкой пера - стёр маркер.
// Касание пером без движения - обычный тап: листает и зовёт панели.
// Палец мимо пера идёт к страницам как прежде.

/** Живой росчерк пера: точки в координатах корня, пока перо на бумаге. */
class PenInk {
    val points = mutableStateListOf<Offset>()
    var erasing by mutableStateOf(false)
}

/** Что нарисовало перо, когда его подняли. */
data class PenStroke(val points: List<Offset>, val erase: Boolean) {
    val bounds: Rect
        get() = Rect(
            points.minOf { it.x }, points.minOf { it.y },
            points.maxOf { it.x }, points.maxOf { it.y },
        )

    /**
     * Обвёл: конец вернулся к началу, и обведено больше строки. Тогда это
     * выделение куска, а не подчёркивание.
     */
    fun closed(line: Float): Boolean {
        val b = bounds
        val span = maxOf(b.width, b.height)
        return (points.first() - points.last()).getDistance() < span * 0.35f &&
            b.height > line * 1.2f && b.width > line * 1.5f
    }

    /** Черта по полю сверху вниз: отметить строки, мимо которых прошла. */
    fun vertical(line: Float): Boolean {
        val b = bounds
        return b.height > line * 2f && b.width < line * 1.2f
    }
}

/**
 * Ловит перо раньше страниц (первым проходом) и забирает жест себе, только
 * когда перо поехало: касание без движения уходит дальше обычным тапом. Пока
 * перо пишет, палец и ладонь на экране тоже съедаются - иначе ладонь,
 * лёгшая на страницу, листала бы её.
 */
fun Modifier.penInput(
    enabled: Boolean,
    ink: PenInk,
    onStroke: (PenStroke) -> Unit,
): Modifier = composed {
    if (!enabled) return@composed this
    var origin by remember { mutableStateOf(Offset.Zero) }
    val stroke by rememberUpdatedState(onStroke)
    this
        .onGloballyPositioned { origin = it.positionInRoot() }
        .pointerInput(Unit) {
            // Касание пером дрожит на пиксель-другой: восемь точек - уже штрих, а не тап.
            val slop = 8.dp.toPx()
            awaitPointerEventScope {
                while (true) {
                    val down = awaitPointerEvent(PointerEventPass.Initial)
                    val first = down.changes.firstOrNull {
                        it.changedToDown() && (it.type == PointerType.Stylus || it.type == PointerType.Eraser)
                    } ?: continue
                    val id = first.id
                    var erase = first.type == PointerType.Eraser ||
                        down.buttons.isSecondaryPressed || down.buttons.isPrimaryPressed
                    val points = mutableListOf(first.position)
                    var claimed = false
                    while (true) {
                        val event = awaitPointerEvent(PointerEventPass.Initial)
                        val change = event.changes.firstOrNull { it.id == id } ?: break
                        if (change.type == PointerType.Eraser ||
                            event.buttons.isSecondaryPressed || event.buttons.isPrimaryPressed
                        ) erase = true
                        if (!claimed && (change.position - first.position).getDistance() > slop) {
                            claimed = true
                            ink.points.clear()
                            ink.points.add(first.position + origin)
                        }
                        if (claimed) {
                            event.changes.forEach { it.consume() }
                            if (change.pressed) {
                                points.add(change.position)
                                ink.points.add(change.position + origin)
                                ink.erasing = erase
                            }
                        }
                        if (!change.pressed) break
                    }
                    ink.points.clear()
                    if (claimed && points.size > 1) stroke(PenStroke(points.map { it + origin }, erase))
                }
            }
        }
}

/**
 * Росчерк пера поверх страницы, пока перо пишет. Ластик - пунктиром: видно,
 * что стирает, а не пишет. Рисует только себя, страницы не трогает.
 */
@Composable
fun PenInkLayer(ink: PenInk, color: Color) {
    var origin by remember { mutableStateOf(Offset.Zero) }
    Canvas(Modifier.fillMaxSize().onGloballyPositioned { origin = it.positionInRoot() }) {
        if (ink.points.size < 2) return@Canvas
        val path = Path()
        ink.points.forEachIndexed { i, root ->
            val p = root - origin
            if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y)
        }
        drawPath(
            path,
            color = color,
            style = Stroke(
                width = 2.5.dp.toPx(),
                cap = StrokeCap.Round,
                join = StrokeJoin.Round,
                pathEffect = if (ink.erasing) PathEffect.dashPathEffect(floatArrayOf(10f, 8f)) else null,
            ),
        )
    }
}
