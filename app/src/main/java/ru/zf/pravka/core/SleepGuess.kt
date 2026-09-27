package ru.zf.pravka.core

import java.util.Calendar
import java.util.TimeZone

/**
 * Ночь по экрану телефона — без Android, чтобы гонять JVM-тестом.
 *
 * Сон в ленту приезжает из телефона: самый длинный ночной разрыв
 * «погас — включился». Первый детектор брал ровно один разрыв, и этого
 * хватало, пока ночь была цельной. Но ночь режется: взгляд на часы в три,
 * уведомление, засветившее экран на секунду, «поднял — посмотрел — положил».
 * Каждый такой миг ломал ночь на два куска, и ни один не дотягивал до трёх
 * часов — детектор честно не находил сна, а после полудня сдавался до
 * завтра. Владелец (27.09.2026): «сон почему-то перестал синхронизироваться».
 *
 * Здесь ночь СШИВАЕТСЯ: включение экрана короче [BLIP_MS] в ночные часы
 * (с [NIGHT_FROM_HOUR] до [NIGHT_TO_HOUR]) не кончает сон. Вечером, до
 * десяти, миг не сшивается нарочно: «телефон лежал два часа, взял на семь
 * минут, лёг» — иначе сон начался бы с того, как телефон положили на стол.
 *
 * Отдельно — подсказка «когда проснулся» ([wakeHint]): если ночь всё-таки не
 * нашлась (Android не отдал событий экрана), сон приезжает от Garmin по
 * длительности, и ему нужен только момент подъёма.
 */
object SleepGuess {

    /** Короче трёх часов — не ночь. */
    const val MIN_SLEEP_MS = 3 * 3_600_000L

    /** Взгляд на телефон ночью короче этого — сон продолжается. */
    const val BLIP_MS = 10 * 60_000L

    /** Ночные часы, в которые взгляд сшивается: с 22:00 до 10:00. */
    const val NIGHT_FROM_HOUR = 22
    const val NIGHT_TO_HOUR = 10

    /** Ночь кончается не раньше трёх утра и не позже двух дня: остальное — не сон. */
    const val WAKE_FROM_HOUR = 3
    const val WAKE_TO_HOUR = 14

    /** Для подсказки «проснулся»: тишина короче этого — не остаток ночи. */
    const val WAKE_QUIET_MS = 45 * 60_000L

    /** Отрезок времени, ms. У включённого экрана — когда горел. */
    data class Span(val start: Long, val end: Long) {
        val ms: Long get() = (end - start).coerceAtLeast(0L)
    }

    /**
     * Найденная ночь: [start]–[end], [pieces] — из скольких разрывов экрана
     * сшита (1 — цельная), [stitchedMs] — сколько минут экрана внутри неё
     * приняты за взгляды, а не за подъём.
     */
    data class Night(val start: Long, val end: Long, val pieces: Int, val stitchedMs: Long) {
        val ms: Long get() = end - start
    }

    /** Итог с диагностикой: [night] пуста — [longestMs] говорит, чего не хватило. */
    data class Verdict(val night: Night?, val longestMs: Long, val gaps: Int)

    /**
     * [screenOn] — отрезки, когда экран горел, по порядку, внутри окна
     * [windowStart]…[now]; экран горит прямо сейчас — последний отрезок
     * кончается в [now] (так его и строит `PhoneSweeper`). Считаются только
     * ЗАКРЫТЫЕ разрывы — от гашения до следующего включения: тишина после
     * последнего гашения ещё идёт (владелец, может быть, спит) и ночью быть
     * не может.
     */
    /**
     * Отбой, если автопилот его видел ([bedtime] — момент, когда телефон лёг
     * на зарядку с погашенным экраном и затих; 0 — не видел): ночь — та, что
     * его накрывает, и начинается она не раньше него. Телефон мог лежать
     * тихо с десяти вечера, но в 23:10 его поставили на зарядку — значит, в
     * 23:10 владелец ещё не спал.
     */
    fun guess(
        screenOn: List<Span>,
        windowStart: Long,
        now: Long,
        zone: TimeZone = TimeZone.getDefault(),
        bedtime: Long = 0L,
    ): Verdict {
        val on = screenOn.filter { it.end > it.start && it.start <= now }.sortedBy { it.start }
        // Закрытые разрывы: от конца одного включения до начала следующего.
        // Начало окна тоже граница — до первого включения экран не горел.
        val gaps = ArrayList<Span>()
        var quietFrom = windowStart
        for (s in on) {
            if (s.start > quietFrom) gaps.add(Span(quietFrom, s.start))
            quietFrom = maxOf(quietFrom, s.end)
        }
        if (gaps.isEmpty()) return Verdict(null, 0L, 0)

        // Сшивание: миг экрана между двумя разрывами короче BLIP_MS в ночные
        // часы — та же ночь.
        val runs = ArrayList<Night>()
        var runStart = gaps[0].start
        var runEnd = gaps[0].end
        var pieces = 1
        var stitched = 0L
        for (i in 1 until gaps.size) {
            val g = gaps[i]
            val blipMs = g.start - runEnd
            if (blipMs in 0 until BLIP_MS && nightHour(hourOf(runEnd, zone))) {
                runEnd = g.end
                pieces++
                stitched += blipMs
            } else {
                runs.add(Night(runStart, runEnd, pieces, stitched))
                runStart = g.start
                runEnd = g.end
                pieces = 1
                stitched = 0L
            }
        }
        runs.add(Night(runStart, runEnd, pieces, stitched))

        val longest = runs.maxOf { it.ms }
        val valid = runs.filter { it.ms >= MIN_SLEEP_MS && wakeHour(hourOf(it.end, zone)) }
        // Отбой известен — ночь та, что его накрывает, с началом не раньше него.
        val anchored = if (bedtime > 0L) {
            valid.firstOrNull { it.start <= bedtime && bedtime < it.end }
                ?.let { it.copy(start = maxOf(it.start, bedtime)) }
                ?.takeIf { it.ms >= MIN_SLEEP_MS }
        } else null
        val night = anchored ?: valid.maxByOrNull { it.ms }
        return Verdict(night, longest, gaps.size)
    }

    /**
     * Когда проснулся — по экрану: конец последней утренней тишины длиннее
     * [WAKE_QUIET_MS], кончившейся включением экрана в утренние часы. Нужна,
     * когда ночь целиком не нашлась, а Garmin знает, сколько спал: сон тогда
     * отсчитывается назад от подъёма. 0 — подсказки нет.
     */
    fun wakeHint(
        screenOn: List<Span>,
        windowStart: Long,
        zone: TimeZone = TimeZone.getDefault(),
    ): Long {
        val on = screenOn.filter { it.end > it.start }.sortedBy { it.start }
        var quietFrom = windowStart
        var best = 0L
        for (s in on) {
            val quiet = s.start - quietFrom
            if (quiet >= WAKE_QUIET_MS && wakeHour(hourOf(s.start, zone))) best = s.start
            quietFrom = maxOf(quietFrom, s.end)
        }
        return best
    }

    private fun nightHour(h: Int): Boolean = h >= NIGHT_FROM_HOUR || h < NIGHT_TO_HOUR

    private fun wakeHour(h: Int): Boolean = h in WAKE_FROM_HOUR until WAKE_TO_HOUR

    private fun hourOf(ms: Long, zone: TimeZone): Int {
        val c = Calendar.getInstance(zone)
        c.timeInMillis = ms
        return c.get(Calendar.HOUR_OF_DAY)
    }
}
