package ru.zf.pravka.core

import kotlin.math.atan2
import kotlin.math.sqrt

// Тап по донату (баг №5, 07.10.2026, владелец: «во всех пай-чартах надо иметь
// возможность нажать на часть, и он тогда подсветит, что это такое: либо
// всплывающим, либо укажет рядом в списке»). Здесь — только геометрия: какой
// кусок под пальцем. Доли идут от двенадцати часов по часовой стрелке, как
// их рисует `ui/Charts.kt` (`DonutChart`).

object DonutHit {

    /**
     * Кусок под точкой ([dx], [dy] — от центра, ось y вниз) или null: палец в
     * дырке ([inner] и ближе) или за кольцом дальше [outer]. Пустые доли не
     * ловятся.
     */
    fun index(values: List<Float>, dx: Float, dy: Float, inner: Float, outer: Float): Int? {
        val r = sqrt(dx * dx + dy * dy)
        if (r < inner || r > outer) return null
        val total = values.filter { it > 0f }.sum()
        if (total <= 0f) return null
        // Угол от двенадцати часов по часовой: atan2 от оси «вверх».
        var deg = Math.toDegrees(atan2(dx.toDouble(), -dy.toDouble())).toFloat()
        if (deg < 0f) deg += 360f
        val at = deg / 360f * total
        var acc = 0f
        var lastLive = -1
        for ((i, v) in values.withIndex()) {
            if (v <= 0f) continue
            lastLive = i
            acc += v
            if (at < acc) return i
        }
        return lastLive.takeIf { it >= 0 }
    }
}
