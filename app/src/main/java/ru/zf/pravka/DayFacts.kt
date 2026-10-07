package ru.zf.pravka

import ru.zf.pravka.core.DayReport
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.BarPart
import ru.zf.pravka.ui.DialSector
import ru.zf.pravka.ui.TimePlateState
import ru.zf.pravka.ui.ZGroup
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

// Числа дня Засечки для Правки 4.0 (07.10.2026): циферблат, плашка времени и
// итоги по категориям — одни и те же на «Сегодня» (разворот), в Засечке и в
// Общей статистике. Считает `DayReport` (тот же балл, что в ленте всегда), а
// здесь только сборка под детали набора (`ui/Dial.kt`).

internal class DayFacts(
    val dayStart: Long,
    val now: Long,
    /** Балл дня (накопленное текущей записи входит). */
    val score: Int,
    /** Балл того же дня недели неделю назад до того же часа — для «▲ 13 к пн, 28 сент». */
    val weekAgo: Int?,
    val weekAgoDate: LocalDate,
    /** Место среди 28 дней (этот и 27 до него, все обрезаны до того же часа). */
    val rank: Int?,
    val wake: Long?,
    /** Полная ночь (с вечера), мс. */
    val nightMs: Long,
    val bedtime: Long,
    val state: TimePlateState,
    /** Минуты по группам с подъёма (без сна). */
    val groups: Map<ZGroup, Int>,
    val aheadMin: Int,
    val sectors: List<DialSector>,
    /** Итоги по категориям внутри дня: имя, цена часа, минуты, очки. */
    val categories: List<CatRow>,
    val emptyCategories: Int,
) {
    class CatRow(val name: String, val worth: Int, val minutes: Int, val points: Int)

    val awakeMin: Int get() = groups.values.sum()

    /** «11 ч 46 м» — с подъёма; до подъёма — «до подъёма», закрытый день — бодрствование целиком. */
    val big: String get() = when (state) {
        TimePlateState.BEFORE_WAKE -> "до подъёма"
        else -> Fmt.dur(awakeMin)
    }

    val sub: String get() = buildString {
        if (wake != null) append("с подъёма в ${Fmt.hm(wake)}") else append("подъём не найден")
        if (nightMs > 0) append(" · сон ${Fmt.durMs(nightMs)}")
        if (state == TimePlateState.CLOSED) append(" · день закрыт")
    }

    val barParts: List<BarPart> get() = buildList {
        add(BarPart((groups[ZGroup.LOSS] ?: 0).toFloat(), ZGroup.LOSS))
        for (g in listOf(ZGroup.WORK, ZGroup.SPORT, ZGroup.FAMILY, ZGroup.LIFE)) add(BarPart((groups[g] ?: 0).toFloat(), g))
        if (aheadMin > 0) add(BarPart(aheadMin.toFloat(), ZGroup.AHEAD))
    }

    val legend: List<Triple<ZGroup, String, String>> get() = buildList {
        for (g in listOf(ZGroup.WORK, ZGroup.SPORT, ZGroup.FAMILY, ZGroup.LIFE, ZGroup.LOSS)) {
            val m = groups[g] ?: 0
            if (m > 0) add(Triple(g, g.label, Fmt.dur(m)))
        }
        if (aheadMin > 0) add(Triple(ZGroup.AHEAD, ZGroup.AHEAD.label, Fmt.durFuture(aheadMin)))
    }

    val nowMin: Float? get() = if (now in dayStart until dayStart + DAY) (now - dayStart) / 60_000f else null

    companion object {
        const val DAY = 86_400_000L

        fun of(
            all: List<ZasechkaStore.Entry>,
            categories: List<ZasechkaStore.Category>,
            day: LocalDate,
            now: Long,
            bedtimeMin: Int,
            zone: ZoneId = ZoneId.systemDefault(),
        ): DayFacts {
            val dayStart = day.atStartOfDay(zone).toInstant().toEpochMilli()
            val worthBy = categories.associate { it.name.trim().lowercase() to it.value }
            val worthOf: (String) -> Int = { worthBy[it.trim().lowercase()] ?: 0 }
            val pool = all.filter { it.start < dayStart + DAY && (it.open || it.end > dayStart - 35 * DAY) }
            val window = DayReport.dayWindow(dayStart, now)
            val score = DayReport.balance(pool, window, now, worthOf).net
            val hasPast = pool.any { it.start < dayStart - DAY }
            val weekAgo = if (hasPast) DayReport.balance(pool, window.shifted(7), now, worthOf).net else null
            val sameHour = (1..27).map { k -> DayReport.balance(pool, window.shifted(k), now, worthOf).net.toDouble() }
            val rank = if (hasPast) DayReport.rank(score.toDouble(), sameHour) else null
            val rhythm = DayReport.rhythm(pool, dayStart, now)
            val nightMs = DayReport.nightSleepMs(pool, dayStart, now)
            val bedtime = dayStart + bedtimeMin * 60_000L
            val today = now in dayStart until dayStart + DAY
            val dayEnd = minOf(now, dayStart + DAY)
            val wake = rhythm.wakeMs
            val state = when {
                !today && now >= dayStart + DAY -> TimePlateState.PAST
                today && wake == null -> TimePlateState.BEFORE_WAKE
                today && rhythm.bedMs != null && rhythm.bedMs < now -> TimePlateState.CLOSED
                else -> TimePlateState.AWAKE
            }
            // С подъёма: всё, кроме сна, от подъёма (или начала дня) до «сейчас» или конца дня.
            val from = wake ?: dayStart
            val groups = HashMap<ZGroup, Int>()
            for (e in pool) {
                if (DayReport.isSleep(e.category)) continue
                val ms = e.durationMsIn(from, dayEnd, now)
                if (ms <= 0) continue
                val g = ZGroup.of(e.category, worthOf(e.category))
                groups[g] = (groups[g] ?: 0) + (ms / 60_000L).toInt()
            }
            val aheadMin = if (today && state != TimePlateState.CLOSED) ((bedtime - now) / 60_000L).toInt().coerceAtLeast(0) else 0
            // Секторы — записи внутри суток.
            val sectors = pool
                .filter { it.start < dayStart + DAY && (it.open || it.end > dayStart) }
                .sortedBy { it.start }
                .mapNotNull { e ->
                    val s = maxOf(e.start, dayStart)
                    val en = if (e.open) dayEnd else minOf(e.end, dayStart + DAY)
                    if (en <= s) return@mapNotNull null
                    val w = worthOf(e.category)
                    val g = ZGroup.of(e.category, w)
                    DialSector(
                        id = e.id,
                        fromMin = (s - dayStart) / 60_000f,
                        toMin = (en - dayStart) / 60_000f,
                        worth = w,
                        color = g.fill,
                        current = e.open && today,
                        hatched = g == ZGroup.LOSS,
                    )
                }
            // Итоги — по категориям внутри дня (сон — только его часть внутри суток).
            val slices = DayReport.byCategory(pool, window, now, worthOf)
                .filter { it.category.isNotBlank() }
            val cats = slices.map { CatRow(it.category, it.worth, it.minutes.toInt(), it.points) }
            val used = slices.map { it.category.trim().lowercase() }.toSet()
            val empty = categories.count { it.name.trim().lowercase() !in used && !it.name.equals("Не размечено", true) }
            return DayFacts(
                dayStart, now, score, weekAgo, day.minusDays(7), rank, wake, nightMs, bedtime, state,
                groups, aheadMin, sectors, cats, empty,
            )
        }

        fun localDay(ms: Long, zone: ZoneId = ZoneId.systemDefault()): LocalDate =
            Instant.ofEpochMilli(ms).atZone(zone).toLocalDate()
    }
}
