package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.hypot

/**
 * Круг дня Засечки — циферблат часов на 12 часов (владелец, 10.10.2026: «надо
 * сделать его циферблатом… внутренняя часть будет от 0 ночью до 12:00, а
 * внешняя часть будет с 12:00 до 24»; «сделать как на часах: от 0 до 12
 * рядом цифры и рисочки»).
 *
 * Двенадцать сверху, по часовой. Запись до полудня — столбик ВНУТРЬ от
 * базового круга, после полудня — НАРУЖУ; длина столбика — сколько стоит час
 * категории (по модулю: потери видны штриховкой и цветом, а не стороной —
 * сторона теперь значит время суток). Сон и около нуля — тонкой полосой на
 * своей стороне. Запись через полдень делится на два столбика.
 *
 * Поле — 300 единиц, центр в 150 (как в DESIGN §12.3); вид масштабирует поле
 * под свой размер. Здесь только числа — рисует `ui/Dial.kt`.
 */
object DialGeometry {

    const val FIELD = 300f
    const val C = FIELD / 2
    /** Базовый круг: граница утра и вечера. */
    const val R0 = 100f
    /** Зазор от базового круга до столбика: утро и вечер на одном часе не слипаются. */
    const val GAP = 1.5f
    /** Единиц длины на очко цены часа. */
    const val SCALE = 2.0f
    /** Сон и почти-ноль — не тоньше этого. */
    const val MIN_LEN = 4f
    /** Цена часа выше 10 не удлиняет столбик: дальше риски и цифры. */
    const val MAX_WORTH = 10

    /** Риски часов: внешняя кромка, длина часовой и главной (12, 3, 6, 9), получаса. */
    const val TICK_OUT = 134f
    const val TICK_HOUR = 8f
    const val TICK_MAIN = 12f
    const val TICK_HALF = 4f
    /** Цифры 1…12 — центр на этом радиусе, снаружи рисок. */
    const val NUMBERS = 143f

    /** Полдень в минутах суток. */
    const val NOON = 720f

    /** Столбик на циферблате: углы от двенадцати по часовой, радиусы в поле 300. */
    data class Arc(val a1: Float, val a2: Float, val inner: Float, val outer: Float, val pm: Boolean)

    fun length(worth: Int): Float = maxOf(SCALE * minOf(abs(worth), MAX_WORTH), MIN_LEN)

    /** Угол часовой стрелки для минуты суток: 0 — двенадцать сверху, и в полночь, и в полдень. */
    fun angle(min: Float): Float = (min % NOON) / NOON * 360f

    /** Столбики записи [from]…[to] (минуты от начала суток): один или два, если запись через полдень. */
    fun arcs(from: Float, to: Float, worth: Int): List<Arc> = buildList {
        if (from < NOON) {
            val end = minOf(to, NOON)
            if (end > from) add(arc(from, end, worth, pm = false))
        }
        if (to > NOON) {
            val start = maxOf(from, NOON)
            if (to > start) add(arc(start - NOON, to - NOON, worth, pm = true))
        }
    }

    private fun arc(from: Float, to: Float, worth: Int, pm: Boolean): Arc {
        val len = length(worth)
        val a1 = from / NOON * 360f
        val a2 = to / NOON * 360f
        return if (pm) Arc(a1, a2, R0 + GAP, R0 + GAP + len, true)
        else Arc(a1, a2, R0 - GAP - len, R0 - GAP, false)
    }

    /** Самый глубокий край утренних столбиков — ближе к центру не заходит ничего. */
    val innerLimit: Float get() = R0 - GAP - length(MAX_WORTH)

    /** Где кончается стрелка «сейчас»: снаружи — до рисок, внутри — до края утренних столбиков. */
    fun handEnds(): Pair<Float, Float> = (innerLimit - 2f) to (TICK_OUT - 2f)

    /** Точка поля (от центра: [dx] вправо, [dy] вниз) — угол от двенадцати по часовой и радиус. */
    fun polar(dx: Float, dy: Float): Pair<Float, Float> {
        var th = Math.toDegrees(atan2(dx.toDouble(), (-dy).toDouble())).toFloat()
        if (th < 0f) th += 360f
        return th to hypot(dx, dy)
    }

    /** Попал ли тап (от центра поля) в столбик, с запасом [slack] по радиусу. */
    fun contains(arc: Arc, dx: Float, dy: Float, slack: Float = 8f): Boolean {
        val (th, r) = polar(dx, dy)
        return th >= arc.a1 && th < arc.a2 && r >= arc.inner - slack && r <= arc.outer + slack
    }
}
