package ru.zf.pravka.core

import kotlin.math.abs
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * Ночная калибровка секунд на кнопке. Владелец (26.09.2026): «раз в день
 * ночью она может запускать этот расчёт… если это не такой сложный расчёт,
 * там, грубо, 3 секунды, то просто пересчитывать всю популяцию».
 *
 * Пересчитать ту же прямую по тем же замерам ночью бессмысленно: накопитель
 * `Pace` и есть эта формула, посчитанная по ходу, — вышли бы ровно те же
 * числа. Ночь нужна для того, чего на ходу не сделать: прогнать ВСЮ историю
 * дороги заново несколькими способами счёта ([candidates]) и оставить тот,
 * где отсчёт промахивался меньше всего. Промах каждого способа — честный:
 * прогон идёт по времени, и каждый запрос оценивается тем, что было известно
 * ДО него, — ровно так, как его видела бы кнопка.
 *
 * Стоит это копейки: двенадцать прогонов по нескольким тысячам замеров —
 * доли секунды; дольше читать журнал правок с диска. Поэтому считается вся
 * популяция, а не последний день.
 */
object PaceTune {

    /** Один замер истории: закончился в [at], [chars] знаков, шёл [ms], кэш — как у [Pace.learn]. */
    data class Sample(val at: Long, val chars: Int, val ms: Long, val cache: Boolean?)

    /**
     * Что дорога получила: [road] — её состояние после прогона всей истории
     * выбранным способом, [miss] — средний промах этого способа, [factoryMiss]
     * — заводского на тех же замерах, [n] — сколько замеров прогнали.
     */
    data class Result(val road: Pace.Road, val miss: Double, val factoryMiss: Double, val n: Int) {
        val tune: Pace.Tune get() = road.tune
    }

    /** Меньше стольких замеров способы не различить: дорога живёт заводским. */
    const val MIN_SAMPLES = 20

    /** Первые замеры у всех способов одинаковы — это прикидка; их промах не судит. */
    private const val SKIP = 5

    /** Судим по последним стольким: способ должен работать сейчас, а не в июле. */
    const val JUDGE_TAIL = 400

    /** Новый способ — только если он лучше заводского хотя бы на столько: шум не повод. */
    const val GAIN = 0.03

    /** Сдвиг к медиане — по стольким последним промахам и не больше этой доли ответа. */
    private const val SHIFT_TAIL = 200
    private const val SHIFT_MAX_FRAC = 0.3

    /** Забывание: полвеса за ~23, ~69 и ~138 запросов. */
    val DECAYS = listOf(0.97, Pace.DECAY, 0.995)

    /** Все способы, которые ночь пробует: забывание × выбросы × холодный кэш. */
    val candidates: List<Pace.Tune> = DECAYS.flatMap { d ->
        listOf(true, false).flatMap { clip ->
            listOf(true, false).map { cold -> Pace.Tune(decay = d, clip = clip, cold = cold) }
        }
    }

    /**
     * Прогнать [samples] (по времени) с начального [seed] способом [tune]:
     * состояние дороги в конце и промах каждого запроса (плюс — ответ пришёл
     * позже обещанного). Промах считается тем же [Pace.expect], что и кнопка.
     */
    fun replay(samples: List<Sample>, prior: Pace.Acc, seed: Pace.Road?, tune: Pace.Tune): Pair<Pace.Road, DoubleArray> {
        var road: Pace.Road? = seed?.copy(tune = tune) ?: Pace.Road(tune = tune)
        val errors = DoubleArray(samples.size)
        for ((i, s) in samples.withIndex()) {
            val promised = Pace.expect(road, prior, s.chars, s.at - s.ms)
            errors[i] = s.ms.coerceIn(Pace.MIN_MS, Pace.MAX_MS).toDouble() - promised
            road = Pace.learn(road, prior, s.chars, s.ms, s.cache, s.at)
        }
        return road!! to errors
    }

    /**
     * Лучший способ для дороги по её истории. null — замеров меньше
     * [MIN_SAMPLES]: различить способы не по чему, дорога живёт как жила.
     */
    fun tune(samples: List<Sample>, prior: Pace.Acc, seed: Pace.Road? = null): Result? {
        if (samples.size < MIN_SAMPLES) return null
        val sorted = samples.sortedBy { it.at }
        val from = maxOf(SKIP, sorted.size - JUDGE_TAIL)
        fun mae(errors: DoubleArray, shift: Double = 0.0): Double {
            var sum = 0.0
            for (i in from until errors.size) sum += abs(errors[i] - shift)
            return sum / (errors.size - from)
        }
        val factory = Pace.Tune()
        val factoryMiss = mae(replay(sorted, prior, seed, factory).second)
        var best = factory
        var bestMiss = factoryMiss
        for (c in candidates) {
            if (c == factory) continue
            val m = mae(replay(sorted, prior, seed, c).second)
            if (m < bestMiss) {
                best = c
                bestMiss = m
            }
        }
        // Выигрыш в пределах шума — не повод менять то, что работает.
        if (bestMiss > factoryMiss * (1 - GAIN)) {
            best = factory
            bestMiss = factoryMiss
        }
        val (_, errors) = replay(sorted, prior, seed, best)
        val shift = shiftFor(errors, sorted)
        if (shift != 0.0 && mae(errors, shift) < bestMiss * (1 - GAIN)) {
            best = best.copy(shift = shift)
        }
        val (road, finalErrors) = replay(sorted, prior, seed, best)
        return Result(road, mae(finalErrors), factoryMiss, sorted.size)
    }

    /**
     * Сдвиг к медиане: на сколько обещание стоит подвинуть, чтобы промахи
     * делились поровну на «раньше» и «позже». Не больше [SHIFT_MAX_FRAC]
     * типичного ответа: сдвиг лечит перекос хвоста, а не подменяет прямую.
     */
    private fun shiftFor(errors: DoubleArray, samples: List<Sample>): Double {
        val tail = maxOf(SKIP, errors.size - SHIFT_TAIL)
        if (errors.size - tail < MIN_SAMPLES / 2) return 0.0
        val e = errors.copyOfRange(tail, errors.size).sorted()
        val median = e[e.size / 2]
        val typical = samples.subList(tail, samples.size).map { it.ms.toDouble() }.sorted().let { it[it.size / 2] }
        val cap = SHIFT_MAX_FRAC * typical
        return median.coerceIn(-cap, cap)
    }

    /** За сколько запросов прошлое теряет половину веса при забывании [decay]. */
    fun halfLife(decay: Double): Int = (ln(0.5) / ln(decay)).roundToInt()

    /** Способ словами — для настроек и журнала автоматов. */
    fun describe(t: Pace.Tune): String {
        val parts = mutableListOf("полвеса за ${halfLife(t.decay)} запр.")
        parts += if (t.clip) "выбросы режет" else "выбросы не режет"
        parts += if (t.cold) "холодный кэш учит" else "холодный кэш не учит"
        if (t.shift != 0.0) parts += "сдвиг %+.1f с".format(java.util.Locale.US, t.shift / 1000.0)
        return parts.joinToString(", ")
    }

    /**
     * Пора ли калибровать: раз в сутки ночью (2–5 часов, как суточная копия
     * базы), а если телефон ночью спал — не позже чем через тридцать часов.
     */
    fun due(now: Long, lastAt: Long, hour: Int): Boolean {
        val since = now - lastAt
        if (since < 20 * HOUR_MS) return false
        return hour in 2..5 || since >= 30 * HOUR_MS
    }

    private const val HOUR_MS = 3_600_000L
}
