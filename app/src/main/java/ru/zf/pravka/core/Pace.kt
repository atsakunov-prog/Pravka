package ru.zf.pravka.core

import kotlin.math.abs

/**
 * Сколько идёт запрос к модели — по собственной истории. Владелец (20.09.2026):
 * «прогресс должен быть рассчитан исходя из истории. В правке: в зависимости от
 * модели и длины фразы. Во всех остальных: в зависимости от модели тоже и длины
 * обработки. По идее, эти логи все есть внутри приложения».
 *
 * Считаем по прямой `мс ≈ основание + наклон × символы`, отдельно на каждую
 * пару «дорога + модель». Почему прямая, а не среднее: у запроса есть
 * постоянная часть (сеть, кэш промпта, размышление) и часть, растущая с
 * длиной, — среднее по всем длинам врало бы и коротким, и длинным. Почему не
 * корзины по длине: на каждую корзину нужны свои замеры, а их у одного
 * человека за день десятки, не тысячи.
 *
 * Копим накопителем, а не пересчётом раз в сутки: суммы обновляются прямо на
 * ответе, и оценка всегда свежая — ночному заданию тут нечего было бы делать,
 * кроме как отставать на день.
 *
 * Старое забывается: перед каждым новым замером суммы множатся на [DECAY].
 * Владелец меняет модель на дороге одним тапом в настройках, и вчерашний Опус
 * не должен вечно тянуть оценку Сонета — но и сбрасывать всё на каждой смене
 * незачем, история той же пары «дорога + модель» остаётся ценной.
 *
 * Поверх прямой — три вещи ради точности (владелец, 26.09.2026: «не важно,
 * чтобы было дешевле, важно, чтобы было точнее»), все в [Road]: добавка
 * холодного кэша (первый запрос после часа тишины пишет голову промпта
 * заново и идёт дольше), обрезка выбросов (одна минута сети в плохом месте
 * не должна неделю тянуть прямую) и честная ошибка — насколько отсчёт
 * промахивается ДО того, как замер лёг в прямую.
 */
object Pace {

    /**
     * Во сколько раз тускнеет прошлое на каждом новом замере. 0,99 — это
     * половина веса примерно за семьдесят запросов: неделя обычной работы.
     */
    const val DECAY = 0.99

    /**
     * Меньше стольких замеров — прямую не строим, берём простое среднее.
     * Было шесть; владелец (20.09.2026): «расшифровка правки обычно быстрее,
     * чем прогресс бар». Пока идёт среднее, короткая фраза получает ожидание
     * длинной — и дуга честно отстаёт. Четыре замера набираются за пару
     * минут работы, после них длина уже учитывается.
     */
    const val MIN_FOR_LINE = 4

    /** Замеров нет совсем — столько и обещаем: короткая чистка Опусом. */
    const val BLIND_MS = 2_500L

    /** Ни один запрос не считается быстрее и дольше этого. */
    const val MIN_MS = 300L
    const val MAX_MS = 120_000L

    /**
     * Накопитель наименьших квадратов для одной пары «дорога + модель»:
     * n, суммы x (символы), y (мс), x², xy. Пять чисел на пару, и по ним
     * строится и прямая, и среднее.
     *
     * Сюда попадают ТОЛЬКО свои замеры: заводская прикидка живёт отдельно и
     * подмешивается на оценке ([mix]). Иначе на экране настроек в строке
     * «замеров столько-то» стояло бы число, в котором замеров нет.
     */
    data class Acc(
        val n: Double = 0.0,
        val sumX: Double = 0.0,
        val sumY: Double = 0.0,
        val sumXX: Double = 0.0,
        val sumXY: Double = 0.0,
    )

    /** Ещё один замер: [chars] символов входа заняли [ms] миллисекунд; прошлое тускнеет на [decay]. */
    fun add(acc: Acc, chars: Int, ms: Long, decay: Double = DECAY): Acc {
        val x = chars.coerceAtLeast(0).toDouble()
        val y = ms.coerceIn(MIN_MS, MAX_MS).toDouble()
        return Acc(
            n = acc.n * decay + 1.0,
            sumX = acc.sumX * decay + x,
            sumY = acc.sumY * decay + y,
            sumXX = acc.sumXX * decay + x * x,
            sumXY = acc.sumXY * decay + x * y,
        )
    }

    /**
     * Заводская прикидка прямой `[baseMs] + [msPerChar] × знак` весом
     * [weight] замеров — с чего дорога начинает, пока своих замеров нет.
     *
     * Почему прикидка, а не пустота: дуга без истории обещала всем [BLIND_MS]
     * — и на длинной диктовке Опусом честно врала втрое. Числа взяты из
     * настоящего журнала приложения (`core/PaceSeed.kt`).
     *
     * Устроена как два замера по краям: на нуле знаков и на [PRIOR_SPAN].
     * Прямая наименьших квадратов через две точки проходит ровно по ним, то
     * есть накопитель отдаёт обратно в точности ту прямую, которую в него
     * положили, и никакого особого случая в [estimate] не требуется.
     */
    fun prior(baseMs: Double, msPerChar: Double, weight: Double = PRIOR_WEIGHT): Acc {
        // Меньше MIN_FOR_LINE прямую не строит никто, включая саму прикидку:
        // с весом поменьше она превратилась бы в среднее по двум концам.
        val w = weight.coerceAtLeast(MIN_FOR_LINE.toDouble())
        val half = w / 2.0
        val x = PRIOR_SPAN
        val far = baseMs + msPerChar * x
        return Acc(
            n = w,
            sumX = half * x,
            sumY = half * (baseMs + far),
            sumXX = half * x * x,
            sumXY = half * x * far,
        )
    }

    /**
     * Свои замеры плюс тающая заводская прикидка — то, по чему дуга считает
     * на самом деле.
     *
     * Прикидка ТАЕТ, а не размывается сама собой: если просто подмешать её
     * раз и навсегда, она держится куда дольше, чем кажется по её весу.
     * Прямая тянется к далёким точкам сильнее, чем к близким, а точка
     * прикидки на [PRIOR_SPAN] знаках стоит с краю — на тридцати своих
     * замерах она всё ещё уводила оценку на треть. Поэтому вес прикидки
     * падает линейно и к [PRIOR_FADE_AT] замерам обнуляется: дальше дорога
     * живёт только своим.
     */
    fun mix(acc: Acc?, prior: Acc): Acc {
        val own = acc ?: Acc()
        val k = (1.0 - own.n / PRIOR_FADE_AT).coerceIn(0.0, 1.0)
        if (k <= 0.0) return own
        return Acc(
            n = own.n + prior.n * k,
            sumX = own.sumX + prior.sumX * k,
            sumY = own.sumY + prior.sumY * k,
            sumXX = own.sumXX + prior.sumXX * k,
            sumXY = own.sumXY + prior.sumXY * k,
        )
    }

    /** Вес заводской прикидки в замерах. */
    const val PRIOR_WEIGHT = 4.0

    /** Дальняя точка прикидки — длина, на которой разница моделей видна. */
    const val PRIOR_SPAN = 600.0

    /** Столько своих замеров — и прикидки больше нет. Это день работы. */
    const val PRIOR_FADE_AT = 12.0

    /** Сколько ждать на [chars] символах: по прямой, если она есть, иначе среднее. */
    fun estimate(acc: Acc?, chars: Int): Long {
        val l = line(acc) ?: return BLIND_MS
        val ms = if (l.straight) l.baseMs + l.msPerChar * chars.coerceAtLeast(0) else l.meanMs
        return ms.toLong().coerceIn(MIN_MS, MAX_MS)
    }

    /**
     * Что именно дорога знает о себе: прямая, среднее и сколько замеров за
     * этим стоит. Отдельно от [estimate], чтобы это можно было ПОКАЗАТЬ —
     * владелец спросил «ты точно рассчитал средние?», и единственный честный
     * ответ на такой вопрос — показать числа, а не пересказать их.
     */
    data class Line(
        val n: Double,
        val meanMs: Double,
        val baseMs: Double,
        val msPerChar: Double,
        /** Прямая построена; иначе всё, что есть, — среднее. */
        val straight: Boolean,
    )

    fun line(acc: Acc?): Line? {
        if (acc == null || acc.n < 1.0) return null
        val mean = acc.sumY / acc.n
        val flat = Line(acc.n, mean, mean, 0.0, straight = false)
        if (acc.n < MIN_FOR_LINE) return flat
        val varX = acc.sumXX - acc.sumX * acc.sumX / acc.n
        if (varX <= 1.0) return flat
        val slope = (acc.sumXY - acc.sumX * acc.sumY / acc.n) / varX
        // Прямая с отрицательным наклоном («чем длиннее, тем быстрее») — шум,
        // а не закон: так выглядит кэш, поймавший длинный запрос.
        if (slope <= 0.0) return flat
        val base = (acc.sumY - slope * acc.sumX) / acc.n
        return Line(acc.n, mean, base, slope, straight = true)
    }

    // ---- Дорога целиком: прямая, холодный кэш, выбросы, честная ошибка ----

    /**
     * КАК дорога считает — то, что ночная калибровка (`core/PaceTune.kt`)
     * подбирает по её же истории (владелец, 26.09.2026: «раз в день ночью она
     * может запускать этот расчёт»). [decay] — забывание на замер; [clip] —
     * резать ли выбросы; [cold] — учить ли добавку холодного кэша; [shift] —
     * сдвиг обещания, мс. Сдвиг нужен потому, что прямая учит СРЕДНЕЕ, а
     * среднее тянет длинный хвост медленных ответов: отсчёт точнее всего,
     * когда промахивается одинаково часто в обе стороны, — на медиане.
     * Заводское — [Tune] по умолчанию: так дорога и считала до калибровки.
     */
    data class Tune(
        val decay: Double = DECAY,
        val clip: Boolean = true,
        val cold: Boolean = true,
        val shift: Double = 0.0,
    ) {
        val factory: Boolean get() = this == Tune()
    }

    /**
     * Всё, что прогноз знает об одной тройке «дорога + модель + усилие».
     *
     * [acc] — тёплые замеры (холодные ложатся сюда за вычетом своей добавки,
     * чтобы прямая была одна). [coldN]/[coldSum] — сколько сверху прямой
     * берёт холодный кэш, тем же забыванием; [warmN] — сколько за этим
     * тёплых замеров: добавку не от чего отсчитать, если тёплых нет вовсе.
     * [errN]/[errAbs]/[errBias] —
     * промах отсчёта по модулю и со знаком (плюс — ответ пришёл ПОЗЖЕ
     * обещанного), считанный до того, как замер попал в прямую: иначе это
     * мерило бы, как прямая помнит тот же самый замер. [lastAt] — когда
     * дорога ходила последний раз: по нему видно, жив ли ещё кэш.
     */
    data class Road(
        val acc: Acc = Acc(),
        val coldN: Double = 0.0,
        val coldSum: Double = 0.0,
        val warmN: Double = 0.0,
        val errN: Double = 0.0,
        val errAbs: Double = 0.0,
        val errBias: Double = 0.0,
        val lastAt: Long = 0L,
        val tune: Tune = Tune(),
    ) {
        /** Средний промах отсчёта по модулю, мс; null — промахов ещё не мерили. */
        val miss: Double? get() = if (errN < 1.0) null else errAbs / errN

        /** Средний промах со знаком, мс: плюс — ответ приходит позже нуля на кнопке. */
        val bias: Double? get() = if (errN < 1.0) null else errBias / errN
    }

    /**
     * Голова промпта живёт в кэше час с последнего запроса (`ttl: 1h` в
     * транспорте). Двумя минутами раньше — уже считаем холодным: часы
     * телефона и сервера не обязаны совпадать до секунды, а обещать
     * холодному тёплое хуже, чем наоборот.
     */
    const val COLD_AFTER_MS = 58L * 60_000

    /** Больше этого холодный кэш не добавляет: дальше это уже не кэш, а сбой. */
    const val COLD_MAX_MS = 8_000.0

    /**
     * Добавка холодного кэша поджата к нулю на столько замеров: один
     * холодный запрос, случайно медленный по другой причине, не должен
     * сразу приписать всем холодным лишние три секунды.
     */
    private const val COLD_SHRINK = 2.0

    /**
     * Столько тёплых замеров нужно, чтобы добавке верить наполовину. У
     * дороги, где кэш холодный всегда (еда раз в несколько часов), отсчитать
     * добавку не от чего: прямая и добавка учились бы на одних и тех же
     * замерах и делили бы одно время между собой как придётся. Там добавки
     * нет, и прямая учит холодное время целиком — его она и обещает.
     */
    private const val WARM_SHRINK = 3.0

    /** Промах считаем по последним двум десяткам запросов: видно, как прогноз учится. */
    const val ERR_DECAY = 0.95

    /** Выбросы режем, когда промахов набралось столько, что «обычный» известен. */
    const val CLIP_AFTER = 5.0
    private const val CLIP_K = 3.0
    private const val CLIP_FRAC = 0.5
    private const val CLIP_MIN_MS = 1_500.0

    /** Холодный ли кэш у дороги в миг [now]: не ходила ни разу или молчала дольше часа. */
    fun coldLikely(road: Road?, now: Long): Boolean =
        road == null || road.lastAt <= 0L || now - road.lastAt >= COLD_AFTER_MS

    /** Сколько сверху прямой берёт холодный кэш у этой дороги, мс. */
    fun coldExtra(road: Road?): Double =
        if (road == null || !road.tune.cold) 0.0 else extraOf(road.coldN, road.coldSum, road.warmN)

    private fun extraOf(n: Double, sum: Double, warmN: Double): Double =
        (sum / (n + COLD_SHRINK)).coerceIn(0.0, COLD_MAX_MS) * (warmN / (warmN + WARM_SHRINK))

    /** Сколько обещать запросу на [chars] знаках, ушедшему в миг [now]. */
    fun expect(road: Road?, prior: Acc, chars: Int, now: Long): Long {
        val warm = estimate(mix(road?.acc, prior), chars)
        val extra = if (coldLikely(road, now)) coldExtra(road) else 0.0
        return (warm + extra + (road?.tune?.shift ?: 0.0)).toLong().coerceIn(MIN_MS, MAX_MS)
    }

    /**
     * Ответ пришёл: запрос на [chars] знаках шёл [ms] и закончился в [now].
     * [cache]: true — голова прочитана из кэша, false — только записана
     * (холодный), null — кэша на этом запросе нет.
     *
     * Холодный замер учит добавку (сколько он лёг выше прямой), а в прямую
     * идёт без неё. У дороги, где кэш холодный всегда (еда раз в несколько
     * часов), добавки нет ([WARM_SHRINK]), а прямая учит всё время целиком —
     * и обещает ровно столько, сколько идёт.
     */
    fun learn(road: Road?, prior: Acc, chars: Int, ms: Long, cache: Boolean?, now: Long): Road {
        val r = road ?: Road()
        val t = r.tune
        val y = ms.coerceIn(MIN_MS, MAX_MS).toDouble()
        // Обещано было в миг ухода, а не прихода: холодным кэш считался по нему.
        val promised = expect(road, prior, chars, now - ms).toDouble()
        val err = y - promised
        val warmPred = estimate(mix(r.acc, prior), chars).toDouble()
        // Холодный замер отдельно — только если дорога учит добавку.
        val coldHere = cache == false && t.cold
        val here = warmPred + if (coldHere) coldExtra(r) else 0.0
        val kept = if (t.clip) clip(y, here, r) else y
        var coldN = r.coldN
        var coldSum = r.coldSum
        val warmN = r.warmN * t.decay + if (coldHere) 0.0 else 1.0
        val warmY = if (coldHere) {
            coldN = coldN * t.decay + 1.0
            coldSum = coldSum * t.decay + (kept - warmPred)
            kept - extraOf(coldN, coldSum, warmN)
        } else {
            kept
        }
        return Road(
            acc = add(r.acc, chars, warmY.toLong(), t.decay),
            coldN = coldN,
            coldSum = coldSum,
            warmN = warmN,
            errN = r.errN * ERR_DECAY + 1.0,
            errAbs = r.errAbs * ERR_DECAY + abs(err),
            errBias = r.errBias * ERR_DECAY + err,
            lastAt = now,
            tune = t,
        )
    }

    /**
     * Выброс не тащит прямую: замер дальше трёх обычных промахов от
     * ожидаемого (но не ближе половины ожидаемого и полутора секунд)
     * ложится на край коридора. Настоящая перемена — модель стала
     * медленнее — всё равно дойдёт: промахи растут, и коридор с ними.
     */
    private fun clip(y: Double, here: Double, r: Road): Double {
        if (r.errN < CLIP_AFTER) return y
        val typical = r.errAbs / r.errN
        val bound = maxOf(CLIP_K * typical, CLIP_FRAC * here, CLIP_MIN_MS)
        return y.coerceIn((here - bound).coerceAtLeast(MIN_MS.toDouble()), here + bound)
    }

    /**
     * Замеры, снятые без длины (до 26.09.2026 дороги режимов слали «0
     * знаков» на каждом запросе), — на [chars] знаков весом [weight]: их
     * среднее честное, но длины за ним не было. Поставленные на типичную
     * длину, они держат прямую там, где она и была, а наклон дают новые
     * замеры с настоящей длиной.
     */
    fun relocate(acc: Acc, chars: Double, weight: Double): Acc {
        if (acc.n < 1.0) return acc
        val w = minOf(weight, acc.n)
        val mean = acc.sumY / acc.n
        return Acc(
            n = w,
            sumX = w * chars,
            sumY = w * mean,
            sumXX = w * chars * chars,
            sumXY = w * chars * mean,
        )
    }
}
