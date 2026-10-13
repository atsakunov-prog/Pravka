package ru.zf.pravka.core

import ru.zf.pravka.data.ZasechkaStore
import kotlin.math.max
import kotlin.math.min

/**
 * Профиль дня — «профиль горы» (Правка 5.0, этап 2; владелец 13.10.2026, холст
 * «Ковчег на телефоне»): очки копятся по отрезкам ленты, полезное ведёт вверх,
 * «не размечено» и дорога — вниз. Один и тот же профиль — на «Сегодня» и в
 * Засечке, поэтому счёт здесь, под тестом, а рисунок — `ui/Mountain.kt`.
 *
 * Правда очков — та же, что у «очки +84» в шапке (`DayAssembler.assemble`) и у
 * балла дня (`DayReport.balance`): цена часа категории × длительность в
 * пределах суток, идущая запись — до «сейчас», без округления по дороге.
 * Каждая запись вносит своё независимо, как в балле: накрывшая другую (дубль,
 * авто-факт поверх) не съедает её — поэтому итог профиля ровно равен баллу.
 *
 * Ноль рисуется пунктиром, только если день уходил ниже нуля ([Profile.dipped]):
 * иначе он совпадает с низом рисунка, и лишняя линия читалась бы как дно.
 */
object DayProfile {

    const val DAY_MIN = 1440f
    private const val DAY_MS = 86_400_000L
    private const val HOUR_MS = 3_600_000.0
    private const val MIN_MS = 60_000f

    /** Запись ленты для профиля: время, категория, цена её часа; [gap] — заполнитель «не размечено». */
    data class Span(
        val start: Long,
        /** Конец; 0 — запись идёт (конец — «сейчас»). */
        val end: Long,
        val category: String,
        val worth: Int,
        val gap: Boolean = false,
    )

    /**
     * Отрезок горы: минуты от начала суток, очки до и после него (накопленные с
     * полуночи), категория и цена её часа — по ним рисунок берёт цвет.
     */
    data class Seg(
        val fromMin: Float,
        val toMin: Float,
        val before: Double,
        val after: Double,
        val category: String,
        val worth: Int,
        val gap: Boolean,
        /** Идущая запись: её конец — «сейчас». */
        val open: Boolean = false,
    ) {
        val points: Double get() = after - before
    }

    /** Профиль суток целиком: отрезки по времени и числа для подписи. */
    data class Profile(
        val segs: List<Seg>,
        /** Очки на «сейчас» (у прошлого дня — на конец суток) — ровно балл дня. */
        val value: Double,
        /** Низ шкалы: ноль или самый глубокий провал. */
        val low: Double,
        /** Верх шкалы: самая высокая точка дня (не меньше единицы — у пустого дня линия не прилипает к верху). */
        val high: Double,
        /** Пик дня и минута, когда он был; null — день не поднимался выше нуля. */
        val peak: Double?,
        val peakMin: Float?,
        /** Где «сейчас» в минутах суток; null — день прошёл или ещё не начался. */
        val nowMin: Float?,
        /** Сколько минут «не размечено» внутри суток. */
        val unmarkedMin: Int,
    ) {
        /** Уходил ли день ниже нуля — тогда ноль рисуется пунктиром. */
        val dipped: Boolean get() = low < 0.0
        val empty: Boolean get() = segs.isEmpty()

        /**
         * Место значения [v] по высоте: 0 — верх рисунка, 1 — низ. Поля сверху и
         * снизу по 8 % и запас 6 % над пиком — как на холсте (`Y` в `Main.dc.html`):
         * линия не упирается в край и не режется толщиной.
         */
        fun y(v: Double): Float {
            val hi = high * HEADROOM
            val span = (hi - low).takeIf { it > 0.0 } ?: 1.0
            return (PAD + (hi - v) / span * (1 - 2 * PAD)).toFloat()
        }
    }

    /** Поле сверху и снизу — доля высоты. */
    const val PAD = 0.08
    /** Запас над пиком. */
    const val HEADROOM = 1.06

    /**
     * Профиль суток [dayStart] на миг [now]. Записи режутся по суткам и по
     * «сейчас» (идущая — до него), идут по времени начала, очки копятся.
     */
    fun of(spans: List<Span>, dayStart: Long, now: Long, dayMs: Long = DAY_MS): Profile {
        val dayEnd = dayStart + dayMs
        val cut = min(max(now, dayStart), dayEnd)
        var bal = 0.0
        var low = 0.0
        var high = 0.0
        var peak: Double? = null
        var peakMin: Float? = null
        var unmarked = 0L
        val segs = ArrayList<Seg>()
        for (s in spans.sortedBy { it.start }) {
            val open = s.end == 0L
            val a = max(s.start, dayStart)
            val b = min(if (open) cut else s.end, cut)
            if (b <= a) continue
            val pts = s.worth * (b - a) / HOUR_MS
            val before = bal
            bal += pts
            val from = (a - dayStart) / MIN_MS
            val to = (b - dayStart) / MIN_MS
            segs += Seg(from, to, before, bal, s.category, s.worth, s.gap, open)
            low = min(low, bal)
            high = max(high, bal)
            if (bal > 0.0 && (peak == null || bal > peak)) {
                peak = bal
                peakMin = to
            }
            if (s.gap) unmarked += b - a
        }
        val nowMin = if (now in dayStart until dayEnd) (now - dayStart) / MIN_MS else null
        return Profile(
            segs = segs,
            value = bal,
            low = low,
            high = max(high, 1.0),
            peak = peak,
            peakMin = peakMin,
            nowMin = nowMin,
            unmarkedMin = ((unmarked + 30_000L) / 60_000L).toInt(),
        )
    }

    /** Записи ленты → отрезки профиля: цена часа — из справочника категорий, «не размечено» — заполнитель. */
    fun spans(entries: List<ZasechkaStore.Entry>, worthOf: (String) -> Int): List<Span> =
        entries.map { e ->
            Span(
                start = e.start,
                end = if (e.open) 0L else e.end,
                category = e.category,
                worth = worthOf(e.category),
                gap = e.source == "gap" || e.category.trim().equals(UNMARKED, ignoreCase = true),
            )
        }

    /** Профиль прямо из ленты — так его зовут «Сегодня» и Засечка. */
    fun ofEntries(
        entries: List<ZasechkaStore.Entry>,
        categories: List<ZasechkaStore.Category>,
        dayStart: Long,
        now: Long,
    ): Profile {
        val worthBy = categories.associate { it.name.trim().lowercase() to it.value }
        val pool = entries.filter { it.start < dayStart + DAY_MS && (it.open || it.end > dayStart) }
        return of(spans(pool, { worthBy[it.trim().lowercase()] ?: 0 }), dayStart, now)
    }

    /**
     * Подпись под профилем: «пик +97 в 16:08 · не размечено 2 ч 2 м» — без
     * самого значения (оно стоит крупно рядом). Пусто — сказать нечего.
     */
    fun caption(p: Profile): String = listOfNotNull(
        // Пик, равный нынешнему, — не новость: «сейчас» и есть вершина.
        p.peak?.takeIf { it.round() != p.value.round() }?.let { pk ->
            val at = p.peakMin!!.toInt()
            "пик ${Fmt.points(pk.round())} в ${if (at >= 1440) "24:00" else Fmt.hmOfMin(at)}"
        },
        p.unmarkedMin.takeIf { it > 0 }?.let { "не размечено ${Fmt.dur(it)}" },
    ).joinToString(" · ")

    /** Очки целым числом — как в шапке «очки +84». */
    fun Double.round(): Int = kotlin.math.round(this).toInt()

    /** Имя заполнителя дыр в ленте (`ZasechkaStore.GAP_CATEGORY`). */
    const val UNMARKED = "Не размечено"
}
