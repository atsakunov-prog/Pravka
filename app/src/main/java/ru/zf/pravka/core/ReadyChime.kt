package ru.zf.pravka.core

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Звуки диктовки — числами, без Android, под тестом. Три звука:
 *
 *  · «говори» ([render]) — нажал, слушает (владелец, 29.09.2026: «мне точно
 *    нужен фидбэк… когда уже понятно, что можно говорить»);
 *  · «принял» ([renderStop]) — стоп нажат, сказанное ушло расшифровываться
 *    («когда я в конце на наушниках нажимаю кнопку… там просто вообще никаких
 *    звуков»);
 *  · «наушники отвалились» ([renderLost]) — слушает уже телефон.
 *
 * Четвёртое издание — щелчки (владелец, 29.09.2026, ночь: «звук не слышен
 * теперь. Должен быть как такой мягкий тройной быстрый щелчок»). Три прежних
 * были нотами: высокие (784–1175 Гц) — «писк», ниже (392–587 Гц) — «высоко и
 * без эха», ещё ниже и почти чистым тоном (330–440 Гц) — не слышно вовсе:
 * наушники костной проводимости низ передают слабо, а канал гарнитуры у
 * старого кодека режет всё ниже 300 Гц. Щелчок — короткий затухающий тон в
 * середине полосы (1,2–1,8 кГц), где и костная проводимость, и узкий канал
 * звучат лучше всего; мягкий — удар за 3 мс по косинусу, без шума и без
 * резкого фронта, с тихой нижней «деревянной» нотой под ним:
 *
 *  · «говори» — три щелчка подряд, чуть вверх ([READY_HZ], шаг [READY_STEP_MS]);
 *  · «принял» — два щелчка вниз ([STOP_HZ]);
 *  · «отвалились» — не щелчок, а мягкий тон, сползающий вниз
 *    ([LOST_HZ] → [LOST_END_HZ]): не спутать с двумя другими, не глядя на экран.
 *
 * Под всеми — короткий хвост комнаты (ревербератор Шрёдера): сухой щелчок
 * в ухе колет, с хвостом — «тук».
 *
 * Впереди — [LEAD_MS] тишины: наушники, только что поднявшие канал, первые
 * десятки миллисекунд звука глотают.
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

    /**
     * Пик щелчков — доля полной шкалы. Щелчок короткий, и на слух он тише тона
     * того же пика: 0,15 у тона третьего издания в наушниках не слышно.
     */
    const val CLICK_PEAK = 0.40

    /** Пик тона «отвалились»: тон длинный, ему хватает меньшего. */
    const val LOST_PEAK = 0.25

    /** «Говори»: три щелчка, чуть вверх. */
    val READY_HZ = doubleArrayOf(1_250.0, 1_450.0, 1_700.0)

    /** «Принял»: два щелчка вниз. */
    val STOP_HZ = doubleArrayOf(1_600.0, 1_250.0)

    /** «Отвалились»: мягкий тон вниз. */
    const val LOST_HZ = 880.0
    const val LOST_END_HZ = 620.0

    /** Шаг между щелчками, мс: быстро, но слышно, что их три. */
    const val READY_STEP_MS = 80
    const val STOP_STEP_MS = 100

    /** Длина звуков без тишины впереди, мс: вместе с хвостом комнаты. */
    const val READY_BODY_MS = 420
    const val STOP_BODY_MS = 380
    const val LOST_BODY_MS = 600

    /** Доля хвоста комнаты. */
    private const val ROOM_MIX = 0.18

    /** Хвост к нулю в конце, мс. */
    private const val FADE_MS = 60

    /** «Говори» целиком: 16-битные отсчёты моно. Начинается и кончается нулём — без щелчков на краях. */
    fun render(sampleRate: Int = SAMPLE_RATE): ShortArray =
        clicks(READY_HZ, READY_STEP_MS, READY_BODY_MS, sampleRate)

    /** «Принял»: два щелчка вниз. */
    fun renderStop(sampleRate: Int = SAMPLE_RATE): ShortArray =
        clicks(STOP_HZ, STOP_STEP_MS, STOP_BODY_MS, sampleRate)

    /** «Наушники отвалились»: мягкий тон, сползающий вниз. */
    fun renderLost(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val lead = sampleRate * LEAD_MS / 1000
        val dry = DoubleArray(lead + sampleRate * LOST_BODY_MS / 1000)
        val attack = sampleRate * 20 / 1000
        val tau = 0.160 * sampleRate
        val glide = sampleRate * 300 / 1000
        var phase = 0.0
        for (n in 0 until dry.size - lead) {
            val rise = if (n < attack) 0.5 - 0.5 * cos(PI * n / attack) else 1.0
            val env = rise * exp(-n / tau)
            if (env < 1e-5 && n > attack) break
            val f = LOST_HZ + (LOST_END_HZ - LOST_HZ) * minOf(1.0, n.toDouble() / glide)
            phase += 2 * PI * f / sampleRate
            dry[lead + n] = env * (sin(phase) + 0.25 * sin(2 * phase))
        }
        return finish(room(dry, sampleRate), sampleRate, LOST_PEAK)
    }

    /** Щелчки на частотах [hz] через [stepMs]. */
    private fun clicks(hz: DoubleArray, stepMs: Int, bodyMs: Int, sampleRate: Int): ShortArray {
        val lead = sampleRate * LEAD_MS / 1000
        val step = sampleRate * stepMs / 1000
        val dry = DoubleArray(lead + sampleRate * bodyMs / 1000)
        hz.forEachIndexed { i, f -> click(dry, lead + i * step, f, sampleRate) }
        return finish(room(dry, sampleRate), sampleRate, CLICK_PEAK)
    }

    /**
     * Один мягкий щелчок: удар за 3 мс по косинусу и быстрое затухание (8 мс),
     * под ним тихая нота октавой ниже, гаснущая чуть дольше, — «тук», а не «тик».
     */
    private fun click(out: DoubleArray, from: Int, freq: Double, sampleRate: Int) {
        val attack = sampleRate * 3 / 1000
        val tau = 0.008 * sampleRate
        val bodyTau = 0.014 * sampleRate
        val length = minOf(out.size - from, sampleRate * 80 / 1000)
        for (n in 0 until length) {
            val rise = if (n < attack) 0.5 - 0.5 * cos(PI * n / attack) else 1.0
            val t = n.toDouble() / sampleRate
            val tone = exp(-n / tau) * sin(2 * PI * freq * t) +
                0.45 * exp(-n / bodyTau) * sin(2 * PI * freq / 2 * t)
            out[from + n] += rise * tone
        }
    }

    /**
     * Короткий хвост комнаты: ревербератор Шрёдера — четыре гребенчатых
     * фильтра с взаимно простыми задержками (их повторы не складываются в
     * звенящий тон) и два фазовых, размывающих их в ровный хвост.
     */
    private fun room(dry: DoubleArray, sampleRate: Int): DoubleArray {
        val n = dry.size
        val out = dry.copyOf()
        val combsMs = doubleArrayOf(23.3, 29.7, 31.1, 37.1)
        val wet = DoubleArray(n)
        for (ms in combsMs) {
            val len = (sampleRate * ms / 1000).roundToInt()
            val buf = DoubleArray(len)
            var idx = 0
            var store = 0.0
            for (i in 0 until n) {
                val y = buf[idx]
                store = y * (1 - 0.4) + store * 0.4
                buf[idx] = dry[i] + store * 0.6
                idx = (idx + 1) % len
                wet[i] += y / combsMs.size
            }
        }
        for (ms in doubleArrayOf(5.0, 1.7)) allpass(wet, (sampleRate * ms / 1000).roundToInt(), 0.6)
        for (i in 0 until n) out[i] += wet[i] * ROOM_MIX
        return out
    }

    private fun allpass(x: DoubleArray, len: Int, g: Double) {
        val buf = DoubleArray(len)
        var idx = 0
        for (i in x.indices) {
            val delayed = buf[idx]
            val y = -g * x[i] + delayed
            buf[idx] = x[i] + g * y
            idx = (idx + 1) % len
            x[i] = y
        }
    }

    /** Хвост к нулю и громкость к [peak]. */
    private fun finish(out: DoubleArray, sampleRate: Int, peak: Double): ShortArray {
        val fade = sampleRate * FADE_MS / 1000
        for (i in 0 until fade) {
            val k = out.size - 1 - i
            out[k] *= i.toDouble() / fade
        }
        val max = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = peak * Short.MAX_VALUE / max
        return ShortArray(out.size) { i ->
            (out[i] * scale).roundToInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
