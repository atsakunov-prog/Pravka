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
 *  · «говори» ([render]) — всё поднялось и слышит (владелец, 29.09.2026:
 *    «очень мелодичный, приятный звук, когда уже понятно, что можно говорить»);
 *  · «принял» ([renderStop]) — стоп нажат, сказанное ушло расшифровываться
 *    (владелец, 29.09.2026: «когда я в конце на наушниках нажимаю кнопку… там
 *    просто вообще никаких звуков… должно быть, что он принял и расшифровывает»);
 *  · «наушники отвалились» ([renderLost]) — слушает уже телефон.
 *
 * Второе издание (владелец, 29.09.2026: «очень высокий звук. Чуть
 * помелодичнее и чуть тише. И с таким эхом. Посмотри, как сделано в разных
 * приложениях»). Первое было соль–ре второй-третьей октавы (784 и 1175 Гц) —
 * в наушниках костной проводимости это писк. У помощников звук начала
 * записи — короткое восхождение в средней октаве, мягкий тембр, хвост
 * пространства; конец — то же самое вниз (Siri, Google Ассистент, диктовка
 * Apple). Отсюда:
 *
 *  · регистр на октаву ниже: «говори» — соль, си, ре первой-второй октавы
 *    (392–587 Гц), трезвучие вверх. Все три ноты — из одного аккорда, поэтому
 *    эхо, накрывающее следующую ноту, звучит аккордом, а не кашей;
 *  · «принял» — ре вниз к соль (квинта вниз): зеркало «говори», закрытие;
 *  · «отвалились» — одна нота ми, сползающая на полутон: не спутать с
 *    двумя другими, не глядя на экран;
 *  · тембр — как у вибрафона: основной тон и три тихих обертона, и верхние
 *    гаснут быстрее нижних — удар звонкий, хвост чистый. Атака 12 мс по
 *    косинусу, а не 6 мс по прямой: мягче вступление, без «пика»;
 *  · эхо — два-три повтора через [ECHO_MS], каждый тише и глуше предыдущего
 *    (в петле повтора — фильтр верхов), и под ними короткий хвост зала
 *    (четыре гребенчатых фильтра и два фазовых — ревербератор Шрёдера);
 *  · тише: пик [PEAK] — 0,24 полной шкалы вместо 0,34 (−3 дБ).
 *
 * Третье издание (владелец, 29.09.2026, ночь: «сделай какой-то спокойнее и
 * тише звук»): две ноты вместо трёх — ми и ля первой октавы (кварта вверх,
 * «принял» — она же вниз), удар мягче (25 мс вместо 12), обертонов почти нет
 * — ближе к тихому «бом», чем к звоночку, эхо — один-два слабых повтора, пик
 * 0,15 шкалы (ещё −4 дБ).
 *
 * Частоты — под канал наушников: он узкий (у старого кодека до 4 кГц, у
 * нового до 8 кГц), и всё, что звучит здесь, укладывается даже в узкий.
 *
 * Впереди — [LEAD_MS] тишины: наушники, только что поднявшие канал, первые
 * десятки миллисекунд звука глотают, и без запаса удар первой ноты пропадал бы.
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

    /** Пик громкости — доля полной шкалы: слышно в тишине, не бьёт по уху в звонковой громкости. */
    const val PEAK = 0.15

    /** «Говори»: ми и ля — кварта вверх, спокойно. */
    val READY_HZ = doubleArrayOf(329.63, 440.00)

    /** «Принял»: ля вниз к ми — зеркало «говори». */
    val STOP_HZ = doubleArrayOf(440.00, 329.63)

    /** «Отвалились»: до-диез первой октавы, к концу — на полутон ниже (до). */
    const val LOST_HZ = 277.18
    const val LOST_END_HZ = 261.63

    /** Длина звуков без тишины впереди, мс: вместе с эхом и хвостом зала. */
    const val READY_BODY_MS = 1_000
    const val STOP_BODY_MS = 1_000
    const val LOST_BODY_MS = 1_200

    /** Шаг между нотами, мс: не торопливо. */
    private const val READY_STEP_MS = 150
    private const val STOP_STEP_MS = 150

    /** Эхо: задержка повтора, доля первого повтора, сколько остаётся от каждого следующего. */
    const val ECHO_MS = 200
    private const val ECHO_MIX = 0.25
    private const val ECHO_FEEDBACK = 0.30

    /** Сколько верхов срезает каждый повтор эха: 0 — ничего, 1 — всё. */
    private const val ECHO_DAMP = 0.55

    /** Доля хвоста зала. */
    private const val ROOM_MIX = 0.15

    /** Хвост к нулю в конце, мс: затухание к концу ещё не ноль. */
    private const val FADE_MS = 120

    /** «Говори» целиком: 16-битные отсчёты моно. Начинается и кончается нулём — без щелчков. */
    fun render(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val lead = sampleRate * LEAD_MS / 1000
        val step = sampleRate * READY_STEP_MS / 1000
        val dry = DoubleArray(lead + sampleRate * READY_BODY_MS / 1000)
        // Вторая нота дольше: на ней «слушаю» и держится.
        note(dry, lead, READY_HZ[0], decayMs = 260.0, gain = 0.85, sampleRate = sampleRate)
        note(dry, lead + step, READY_HZ[1], decayMs = 340.0, gain = 1.0, sampleRate = sampleRate)
        return finish(space(dry, sampleRate), sampleRate)
    }

    /** «Принял»: две ноты вниз. */
    fun renderStop(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val lead = sampleRate * LEAD_MS / 1000
        val step = sampleRate * STOP_STEP_MS / 1000
        val dry = DoubleArray(lead + sampleRate * STOP_BODY_MS / 1000)
        note(dry, lead, STOP_HZ[0], decayMs = 240.0, gain = 0.85, sampleRate = sampleRate)
        note(dry, lead + step, STOP_HZ[1], decayMs = 320.0, gain = 1.0, sampleRate = sampleRate)
        return finish(space(dry, sampleRate), sampleRate)
    }

    /** «Наушники отвалились»: одна низкая нота, сползающая на полутон. */
    fun renderLost(sampleRate: Int = SAMPLE_RATE): ShortArray {
        val lead = sampleRate * LEAD_MS / 1000
        val dry = DoubleArray(lead + sampleRate * LOST_BODY_MS / 1000)
        note(
            dry, lead, LOST_HZ, endFreq = LOST_END_HZ, glideMs = 420,
            decayMs = 380.0, gain = 1.0, sampleRate = sampleRate,
        )
        return finish(space(dry, sampleRate), sampleRate)
    }

    /**
     * Эхо и зал поверх сухого звука. Повторы эха глушатся в петле (фильтр
     * верхов), как в настоящем помещении: каждый следующий — дальше и
     * мягче. Зал — ревербератор Шрёдера: четыре гребенчатых фильтра с
     * взаимно простыми задержками (чтобы их повторы не складывались в
     * звенящий тон) и два фазовых, размывающих их в ровный хвост.
     */
    private fun space(dry: DoubleArray, sampleRate: Int): DoubleArray {
        val n = dry.size
        val out = dry.copyOf()
        // Эхо: y = x(t−d)·mix + y(t−d)·feedback, повтор проходит фильтр верхов.
        val d = sampleRate * ECHO_MS / 1000
        val echo = DoubleArray(n)
        var lp = 0.0
        for (i in d until n) {
            val input = dry[i - d] * ECHO_MIX + echo[i - d] * ECHO_FEEDBACK
            lp += (1 - ECHO_DAMP) * (input - lp)
            echo[i] = lp
        }
        // Зал.
        val combsMs = doubleArrayOf(29.7, 37.1, 41.1, 43.7)
        val room = DoubleArray(n)
        for (ms in combsMs) {
            val len = (sampleRate * ms / 1000).roundToInt()
            val buf = DoubleArray(len)
            var idx = 0
            var store = 0.0
            for (i in 0 until n) {
                val y = buf[idx]
                store = y * (1 - 0.35) + store * 0.35
                buf[idx] = dry[i] + store * 0.72
                idx = (idx + 1) % len
                room[i] += y / combsMs.size
            }
        }
        for (ms in doubleArrayOf(5.0, 1.7)) allpass(room, (sampleRate * ms / 1000).roundToInt(), 0.6)
        for (i in 0 until n) out[i] += echo[i] + room[i] * ROOM_MIX
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

    /** Хвост к нулю и громкость к [PEAK]. */
    private fun finish(out: DoubleArray, sampleRate: Int): ShortArray {
        val fade = sampleRate * FADE_MS / 1000
        for (i in 0 until fade) {
            val k = out.size - 1 - i
            out[k] *= i.toDouble() / fade
        }
        val max = out.maxOf { abs(it) }.takeIf { it > 0 } ?: 1.0
        val scale = PEAK * Short.MAX_VALUE / max
        return ShortArray(out.size) { i ->
            (out[i] * scale).roundToInt().coerceIn(-Short.MAX_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Одна нота, как мягкий брусок вибрафона: удар за 25 мс по косинусу, дальше
     * затухание; обертоны тише основного тона и гаснут быстрее — к хвосту
     * остаётся почти чистый тон. [endFreq] — за [glideMs] тон плавно уходит
     * туда (фаза копится, а не считается от времени: иначе глиссандо щёлкало бы).
     */
    private fun note(
        out: DoubleArray,
        from: Int,
        freq: Double,
        decayMs: Double,
        gain: Double,
        sampleRate: Int,
        endFreq: Double = freq,
        glideMs: Int = 0,
    ) {
        val attack = sampleRate * 25 / 1000
        val tau = decayMs / 1000.0 * sampleRate
        val glide = (sampleRate * glideMs / 1000).coerceAtLeast(1)
        val length = out.size - from
        var phase = 0.0
        for (n in 0 until length) {
            val rise = if (n < attack) 0.5 - 0.5 * cos(PI * n / attack) else 1.0
            val env = rise * exp(-n / tau)
            if (env < 1e-5 && n > attack) break
            val f = if (endFreq == freq) freq else freq + (endFreq - freq) * minOf(1.0, n.toDouble() / glide)
            phase += 2 * PI * f / sampleRate
            var tone = 0.0
            for (k in PARTIALS.indices) {
                // Обертон k гаснет в (1 + 0,9·k) раз быстрее основного тона.
                val partialEnv = exp(-n * 0.9 * k / tau)
                tone += PARTIALS[k] * partialEnv * sin((k + 1) * phase)
            }
            out[from + n] += gain * env * tone
        }
    }

    /** Громкость основного тона и обертонов 2×, 3×: почти чистый тон — «бом», а не звоночек. */
    private val PARTIALS = doubleArrayOf(1.0, 0.12, 0.03)
}
