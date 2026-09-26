package ru.zf.pravka.core

/**
 * Где стоит пилюля диктовки (`trigger/DictationPill.kt`) — арифметикой, без
 * Android, под JVM-тестом.
 *
 * Владелец (26.09.2026), глядя на Gemini: «она будет вылезать не рядом с
 * кнопкой… всплывать над клавиатурой, если клавиатура включена, или внизу
 * экрана, если ничего не включено». Отсюда [floor] — пол, на который пилюля
 * встаёт, и [bottom] — место на нём посередине.
 *
 * Посередине — пока это никого не накрывает. Кнопка, которая сейчас пишет,
 * стоит на экране, и накрыть её нельзя ни видом (окно пилюли добавлено позже
 * и лежит поверх), ни касанием (у «З», «Д» и «Е» пилюля ловит тап): «стоп»
 * должен попадать всегда. Поэтому кнопки и «отмена» — препятствия: пилюля
 * сперва ужимается вбок от них, а если места вбок не хватает, поднимается
 * над ними. Диск у правого края внизу — обычное дело, так что это не угол,
 * а ежедневный случай.
 *
 * [beside] — прежнее место сбоку от кнопки: выбор «Снизу · У кнопки» в
 * «Кнопках на экране» обязан откатываться одним движением.
 */
object PillGeometry {

    /** Прямоугольник на экране, пиксели; правый и нижний края — не включительно. */
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
    }

    /** Место пилюли: левый верхний угол и ширина (высота своя, постоянная). */
    data class Spot(val x: Int, val y: Int, val width: Int)

    /**
     * Пол — нижний край пилюли: над клавиатурой, если она открыта, иначе над
     * полосой навигации. [imeBottom] и [navBottom] — сколько снизу занимает
     * каждая (0 — нет); клавиатура своей высотой уже включает навигацию под
     * собой, поэтому берётся большая из двух, а не сумма.
     */
    fun floor(screenH: Int, imeBottom: Int, navBottom: Int, gap: Int): Int =
        screenH - maxOf(imeBottom, navBottom, 0) - gap

    /**
     * Пилюля на полу посередине. [wantW] — желанная ширина (настройка
     * владельца), [side] — поля слева и справа, [minW] — уже этого не
     * ужимается, а поднимается над препятствием.
     */
    fun bottom(
        screenW: Int,
        floor: Int,
        wantW: Int,
        h: Int,
        side: Int,
        gap: Int,
        minW: Int,
        obstacles: List<Box>,
    ): Spot {
        val full = (screenW - 2 * side).coerceAtLeast(1)
        val width = wantW.coerceIn(1, full)
        var y = floor - h
        // Каждый подъём ставит пилюлю над самым высоким мешающим — дальше
        // мешать может только то, что стоит ещё выше. Препятствий единицы,
        // так что круг ограничен их числом.
        repeat(obstacles.size + 1) {
            val top = y
            val blockers = obstacles.filter { it.top - gap < top + h && it.bottom + gap > top }
            val lane = widestLane(screenW, side, gap, blockers)
            if (blockers.isEmpty() || lane.second - lane.first >= minOf(minW, width)) {
                val w = minOf(width, lane.second - lane.first)
                // Посередине экрана, если влезает в свободную полосу; иначе —
                // как можно ближе к середине внутри неё.
                val centred = (screenW - w) / 2
                val x = centred.coerceIn(lane.first, (lane.second - w).coerceAtLeast(lane.first))
                return Spot(x, top.coerceAtLeast(0), w)
            }
            y = blockers.minOf { it.top } - gap - h
        }
        return Spot((screenW - width) / 2, y.coerceAtLeast(0), width)
    }

    /**
     * Самая широкая свободная полоса по горизонтали между [side] и
     * `screenW − side`, если вычесть из неё препятствия (каждое с зазором
     * [gap]). Возвращает (начало, конец).
     */
    fun widestLane(screenW: Int, side: Int, gap: Int, blockers: List<Box>): Pair<Int, Int> {
        var best = side to side
        var cursor = side
        val end = screenW - side
        for (b in blockers.sortedBy { it.left }) {
            val stop = minOf(b.left - gap, end)
            if (stop - cursor > best.second - best.first) best = cursor to stop
            cursor = maxOf(cursor, b.right + gap)
        }
        if (end - cursor > best.second - best.first) best = cursor to end
        return best
    }

    /**
     * Прежнее место — сбоку от кнопки, на той стороне, где есть место, по
     * центру кнопки по высоте. Ширина — настройка, но кнопка остаётся видна.
     */
    fun beside(screenW: Int, screenH: Int, button: Box, wantW: Int, h: Int, gap: Int, margin: Int): Spot {
        val width = minOf(wantW, (screenW - button.width - margin).coerceAtLeast(1))
        val y = (button.top - (h - button.height) / 2).coerceIn(0, (screenH - h).coerceAtLeast(0))
        val centre = button.left + button.width / 2
        val x = if (centre < screenW / 2) button.right + gap else button.left - width - gap
        return Spot(x.coerceIn(0, (screenW - width).coerceAtLeast(0)), y, width)
    }
}
