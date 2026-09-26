package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

/**
 * Свечение режима во вкладке (версия 3, 26.09.2026) — числами, без Android,
 * под JVM-тестом. Рисует по ним `ui/Glow.kt`.
 *
 * Владелец, увидев макет «в одежде Gemini», где к цвету режима подмешивались
 * соседние (к оранжевому — малиновый и золото, к янтарю — цвет категории):
 * «вот у нас плашка сейчас — такая вот красная, в Правке оранжевая, и там не
 * примешиваются дополнительные цвета… давай вот эти переливы между цветами и
 * будем делать такими, а именно сохраним цветность каждого направления и
 * сделаем вот это свечение». Поэтому свечение — ОДИН оттенок: все тона ниже
 * выводятся из цвета кнопки режима только насыщенностью и яркостью, оттенок
 * (hue) не трогается никогда. Перелив — светлый тон справа, сам цвет слева и
 * глубокий посередине: у Gemini это два разных цвета, у нас — одна краска в
 * трёх светах. Тест сторожит оттенок.
 *
 * Тёмные чернила кнопок (синие «Д» #2A5D82, фиолетовые «₽») на ночном фоне
 * светом не читаются — яркость тона поднимается до [MIN_VALUE], а оттенок и
 * насыщенность остаются кнопкиными: это тот же синий, только зажжённый.
 */
object ModeGlow {

    /** Сила свечения с завода (ползунок «Свечение режима», 0 — выключено). */
    const val DEFAULT = 0.5f

    /** Пока Claude работает во вкладке, свет ярче — один плавный переход, без мерцания. */
    const val BUSY_BOOST = 1.5f

    /** Как долго свет меняет силу, мс: дольше — не успевает за ответом, короче — вспышка. */
    const val FADE_MS = 700

    /** Высота свечения — доля экрана, но не выше [MAX_HEIGHT_DP]: на развороте Fold не заливать полэкрана. */
    const val HEIGHT_FRACTION = 0.5f
    const val MAX_HEIGHT_DP = 460

    /** Самая яркая точка — при силе 1. Выше — пятно краски, а не свет. */
    const val PEAK = 0.9f

    /** Нижняя граница яркости тона: тёмные чернила кнопок зажигаются до неё. */
    const val MIN_VALUE = 0.82f

    /** Нижняя граница насыщенности: бледный тон на ночи читается серой дымкой. */
    const val MIN_SAT = 0.5f

    /** Альфа главного пятна при силе [strength] (0..1). */
    fun alpha(strength: Float, busy: Boolean): Float {
        val s = strength.coerceIn(0f, 1f)
        if (s <= 0f) return 0f
        return min(PEAK, s * (if (busy) BUSY_BOOST else 1f) * PEAK)
    }

    /** Главный тон — цвет кнопки режима, зажжённый до [MIN_VALUE]. */
    fun tone(accent: Int): Int {
        val (h, s, v) = hsv(accent)
        return fromHsv(h, max(s, MIN_SAT), max(v, MIN_VALUE))
    }

    /** Светлый тон — правое пятно: тот же оттенок, меньше насыщенности, больше света. */
    fun light(accent: Int): Int {
        val (h, s, v) = hsv(tone(accent))
        return fromHsv(h, s * 0.72f, min(1f, v * 1.1f))
    }

    /** Глубокий тон — середина: тот же оттенок, вдвое темнее. */
    fun deep(accent: Int): Int {
        val (h, s, v) = hsv(tone(accent))
        return fromHsv(h, s, v * 0.5f)
    }

    // ---- HSV без android.graphics.Color: числа должны проверяться на JVM ----

    data class Hsv(val h: Float, val s: Float, val v: Float)

    fun hsv(color: Int): Hsv {
        val r = (color shr 16 and 0xFF) / 255f
        val g = (color shr 8 and 0xFF) / 255f
        val b = (color and 0xFF) / 255f
        val mx = max(r, max(g, b))
        val mn = min(r, min(g, b))
        val d = mx - mn
        val h = when {
            d == 0f -> 0f
            mx == r -> 60f * (((g - b) / d) % 6f)
            mx == g -> 60f * ((b - r) / d + 2f)
            else -> 60f * ((r - g) / d + 4f)
        }.let { if (it < 0f) it + 360f else it }
        val s = if (mx == 0f) 0f else d / mx
        return Hsv(h, s, mx)
    }

    fun fromHsv(h: Float, s: Float, v: Float): Int {
        val hh = ((h % 360f) + 360f) % 360f
        val c = v * s
        val x = c * (1f - abs((hh / 60f) % 2f - 1f))
        val m = v - c
        val (r, g, b) = when {
            hh < 60f -> Triple(c, x, 0f)
            hh < 120f -> Triple(x, c, 0f)
            hh < 180f -> Triple(0f, c, x)
            hh < 240f -> Triple(0f, x, c)
            hh < 300f -> Triple(x, 0f, c)
            else -> Triple(c, 0f, x)
        }
        fun ch(f: Float) = ((f + m) * 255f + 0.5f).toInt().coerceIn(0, 255)
        return (0xFF shl 24) or (ch(r) shl 16) or (ch(g) shl 8) or ch(b)
    }
}
