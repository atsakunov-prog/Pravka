package ru.zf.pravka.core

import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Звук «говори» — колокольчик в наушники в тот миг, когда всё поднялось и
 * распознаватель слышит (владелец, 29.09.2026: «иногда я просто нажимаю на
 * кнопку — ничего не происходит в наушниках… я могу не смотреть даже на
 * телефон, поэтому мне точно нужен фидбэк… очень мелодичный, приятный звук,
 * когда уже понятно, что можно говорить»). Числами, без Android, под тестом.
 *
 * Звук синтезируется, а не лежит файлом: две ноты вверх на квинту — соль и
 * ре следующей октавы, как «динь-дон» наоборот (вверх звучит как «да,
 * слушаю», вниз — как «всё»). Тембр — синус с тихими второй и третьей
 * гармониками, мягкий, как у маримбы, а не писк. Частоты выбраны под канал
 * наушников: он звонковый, узкий (у старого кодека до 4 кГц, у нового до
 * 8 кГц), и всё, что выше, срезается — основной тон и обе гармоники
 * укладываются даже в узкий.
 *
 * Впереди — [LEAD_MS] тишины: наушники, только что поднявшие канал, первые
 * десятки миллисекунд звука глотают, и без запаса колокольчик звучал бы с
 * отрезанным ударом.
 */
object ReadyChime {

    /** Когда звенеть — выбор владельца в «Микрофоне и распознавании». */
    enum class Mode(val key: String) {
        /** Только когда слушают наушники: на телефоне хватает пилюли и вибрации. С завода. */
        HEADSET("headset"),

        /** Всегда: в наушники, если слушают они, иначе — телефоном. */
        ALWAYS("always"),

        OFF("off");

        companion object {
            fun fromKey(key: String?): Mode = entries.firstOrNull { it.key == key } ?: HEADSET
        }
    }

    /** Звенеть ли сейчас: слушают наушники ([headset]) или телефон. */
    fun shouldPlay(mode: Mode, headset: Boolean): Boolean = when (mode) {
        Mode.HEADSET -> headset
        Mode.ALWAYS -> true
        Mode.OFF -> false
    }

    /** Частота дискретизации: родная для канала наушников, остальным её пересчитает система. */
    const val SAMPLE_RATE = 16_000

    /** Тишина впереди, мс: наушники после подъёма канала глотают начало звука. */
    const val LEAD_MS = 80

    /** Длина звука без тишины впереди, мс. */
    const val BODY_MS = 520

    /** Пик громкости — доля полной шкалы: слышно, но не бьёт по уху в звонковой громкости. */
    const val PEAK = 0.34

    private const val LOW_HZ = 783.99    // соль второй октавы
    private const val HIGH_HZ = 1174.66  // ре третьей — квинта вверх

    /** Вторая нота вступает через столько после первой, мс. */
    private const val STEP_MS = 120

    /**
     * Звук целиком: 16-битные отсчёты моно. Начинается и кончается нулём —
     * без щелчков на краях.
     */
    fun render(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val lead = sampleRate * LEAD_MS / 1000
        val body = sampleRate * BODY_MS / 1000
        val step = sampleRate * STEP_MS / 1000
        val out = DoubleArray(lead + body)
        note(out, from = lead, freq = LOW_HZ, decayMs = 140.0, gain = 0.85, sampleRate = sampleRate)
        note(out, from = lead + step, freq = HIGH_HZ, decayMs = 190.0, gain = 1.0, sampleRate = sampleRate)
        // Хвост к нулю за последние 40 мс: затухание к концу ещё не ноль.
        val fade = sampleRate * 40 / 1000
        for (i in 0 until fade) {
            val k = out.size - 1 - i
            out[k] *= i.toDouble() / fade
        }
        val max = out.maxOf { kotlin.math.abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = PEAK * Short.MAX_VALUE / max
        return ShortArray(out.size) { i -> (out[i] * scale).roundToInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort() }
    }

    /** Одна нота: удар за 6 мс, дальше затухание, как у бруска маримбы. */
    private fun note(out: DoubleArray, from: Int, freq: Double, decayMs: Double, gain: Double, sampleRate: Int) {
        val attack = sampleRate * 6 / 1000
        val tau = decayMs / 1000.0 * sampleRate
        for (n in 0 until out.size - from) {
            val env = (if (n < attack) n.toDouble() / attack else 1.0) * exp(-n / tau)
            if (env < 1e-4 && n > attack) break
            val t = n.toDouble() / sampleRate
            val tone = sin(2 * PI * freq * t) +
                0.18 * sin(2 * PI * 2 * freq * t) +
                0.06 * sin(2 * PI * 3 * freq * t)
            out[from + n] += gain * env * tone
        }
    }
}
