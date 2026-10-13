package ru.zf.pravka.core

/**
 * Счёт плеера тейков («Слушать» в экране «Правка», `provider/TakePlayer.kt`) —
 * без Android, под тестом: подпись времени, перемотка по доле полосы, доля
 * для полосы. Отдельно от плеера, потому что `MediaPlayer` на JVM-тестах не живёт.
 */
object Playback {

    /** «0:07», «1:05», «12:40» — минуты без ведущего нуля, секунды двумя знаками. */
    fun clock(ms: Long): String {
        val s = (ms.coerceAtLeast(0L) + 500L) / 1000L
        return "${s / 60}:" + "%02d".format(s % 60)
    }

    /** «0:07 / 0:42»; длина неизвестна — только место. */
    fun label(posMs: Long, durMs: Long): String =
        if (durMs > 0) clock(posMs.coerceAtMost(durMs)) + " / " + clock(durMs) else clock(posMs)

    /** Доля полосы для места [posMs] из [durMs]: 0…1, без длины — 0. */
    fun fraction(posMs: Long, durMs: Long): Float =
        if (durMs <= 0) 0f else (posMs.toDouble() / durMs).coerceIn(0.0, 1.0).toFloat()

    /** Куда перемотать по доле [fraction] (палец мимо полосы — к краю). */
    fun seekMs(fraction: Float, durMs: Long): Long =
        if (durMs <= 0) 0L else (fraction.coerceIn(0f, 1f) * durMs).toLong()

    /** Место внутри длины: за концом — с начала (дослушанное не стартует с хвоста). */
    fun clampMs(posMs: Long, durMs: Long): Long = when {
        posMs <= 0L -> 0L
        durMs > 0L && posMs >= durMs -> 0L
        else -> posMs
    }
}
