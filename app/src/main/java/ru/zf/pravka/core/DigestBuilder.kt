package ru.zf.pravka.core

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.PlanStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.dayBefore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.data.dayStartMs
import ru.zf.pravka.data.shareFileIntent

// Сводка дня и недели одним текстом: таймшит, тренировки, подходы, здоровье,
// еда — то, что владелец отправляет в чат Клоду за советом.
//
// Это НЕ отчёт для чтения глазами и не замена вкладкам. Это корм для модели:
// плотный, без украшений, с числами и без воды. Поэтому здесь простой текст, а
// не таблицы и не JSON — модель читает прозу дешевле, а владелец может глянуть
// глазом по дороге.
//
// Собирается целиком на телефоне из уже имеющихся сторов: ни одного запроса в
// сеть, ни одного токена. Дорого стоит совет, а не его исходные данные.
class DigestBuilder(
    private val context: Context,
    private val zasechka: ZasechkaStore,
    private val sport: SportStore,
    private val strength: StrengthStore,
    private val food: FoodStore,
    private val plan: PlanStore,
    private val settings: Settings,
) {

    private val isoFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val humanDay = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

    /** Сводка за один день. */
    suspend fun day(date: String = dayKey(System.currentTimeMillis())): String {
        loadAll()
        return buildString {
            append("СВОДКА ЗА ДЕНЬ · ").append(date).append(" (").append(human(date)).append(")\n")
            append("Собрано Правкой на телефоне. Всё, что ниже, — факт из его данных.\n\n")
            appendPlan(date)
            appendHealth(listOf(date))
            appendWorkouts(date, date)
            appendStrength(listOf(date))
            appendGtg(listOf(date))
            appendFood(listOf(date))
            appendRibbon(date, 1)
            appendRules()
            append("\nЧто нужно: разбор плана против факта и правки на завтра.\n")
        }
    }

    /** Сводка за неделю: семь дней назад включительно. */
    suspend fun week(endDate: String = dayKey(System.currentTimeMillis())): String {
        loadAll()
        val dates = mutableListOf<String>()
        var cursor = endDate
        repeat(7) {
            dates.add(cursor)
            cursor = dayBefore(cursor)
        }
        val from = dates.last()
        return buildString {
            append("СВОДКА ЗА НЕДЕЛЮ · ").append(from).append(" — ").append(endDate).append('\n')
            append("Собрано Правкой на телефоне. Всё, что ниже, — факт из его данных.\n\n")
            appendPlanWeek(dates)
            appendHealth(dates)
            appendWorkouts(from, endDate)
            appendStrength(dates)
            appendGtg(dates)
            appendFood(dates)
            appendRibbon(endDate, 7)
            appendRules()
            append("\nЧто нужно: план против факта за неделю и правки на следующую.\n")
        }
    }

    private suspend fun loadAll() {
        runCatching { zasechka.all() }
        runCatching { sport.load() }
        runCatching { strength.load() }
        runCatching { food.load() }
        runCatching { plan.load() }
    }

    // ---- Разделы ----

    private fun StringBuilder.appendPlan(date: String) {
        val days = plan.dayOf(date)
        append("ПЛАН НА ЭТОТ ДЕНЬ\n")
        if (days.isEmpty()) {
            append("В календаре intervals на этот день ничего нет.\n\n")
            return
        }
        for (d in days) {
            append("- ").append(d.name)
            if (d.minutes > 0) append(", ").append(d.minutes).append(" мин")
            if (d.load > 0) append(", load ").append(d.load)
            append('\n')
            for (line in d.plannedLines()) append("  · ").append(line).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.appendPlanWeek(dates: List<String>) {
        append("ПЛАН НЕДЕЛИ (из календаря intervals)\n")
        var any = false
        for (date in dates.reversed()) {
            val days = plan.dayOf(date)
            if (days.isEmpty()) continue
            any = true
            append(date).append(": ")
            append(days.joinToString("; ") { d ->
                d.name + (if (d.minutes > 0) " (${d.minutes} мин)" else "")
            })
            append('\n')
        }
        if (!any) append("Календарь пуст.\n")
        append('\n')
    }

    private fun StringBuilder.appendHealth(dates: List<String>) {
        val list = sport.healthFlow.value.filter { it.date in dates }
        append("ЗДОРОВЬЕ (дата · HRV · пульс покоя · сон ч · счёт сна · шаги · вес · CTL/ATL)\n")
        if (list.isEmpty()) {
            append("Нет данных за этот период.\n\n")
            return
        }
        for (h in list.sortedBy { it.date }) {
            append(h.date).append(" · ")
            append(num(h.hrv)).append(" · ")
            append(num(h.restingHr)).append(" · ")
            append(dec(h.sleepHours)).append(" · ")
            append(num(h.sleepScore)).append(" · ")
            append(num(h.steps)).append(" · ")
            append(dec(h.weightKg)).append(" · ")
            append(dec(h.ctl)).append('/').append(dec(h.atl))
            append('\n')
        }
        val hrvBase = sport.average(14, skipDays = 0) { it.hrv.toDouble() }
        if (hrvBase > 0) append("База HRV за две недели: ").append(fmt0(hrvBase)).append('\n')
        append('\n')
    }

    private fun StringBuilder.appendWorkouts(from: String, to: String) {
        val fromMs = dayStart(from)
        val toMs = dayStart(to) + 86_400_000L
        val list = sport.workoutsFlow.value.filter { it.start in fromMs until toMs }
        append("ТРЕНИРОВКИ ИЗ INTERVALS\n")
        if (list.isEmpty()) {
            append("Ни одной за этот период.\n\n")
            return
        }
        for (w in list.sortedBy { it.start }) {
            append(dayKey(w.start)).append(' ')
            append(SportCoach.sportName(w.type)).append(' ')
            if (w.name.isNotBlank() && !w.name.equals(w.type, true)) append("«${w.name}» ")
            append(w.minutes).append(" мин")
            if (w.km >= 0.1) append(", ").append(dec(w.km)).append(" км")
            if (w.paceSecPerKm > 0) append(", ").append(pace(w.paceSecPerKm)).append("/км")
            if (w.avgHr > 0) append(", пульс ").append(w.avgHr)
            if (w.maxHr > 0) append("/").append(w.maxHr)
            if (w.avgWatts > 0) append(", ").append(w.avgWatts).append(" Вт")
            if (w.load > 0) append(", load ").append(w.load)
            if (w.feel > 0) append(", самочувствие ").append(w.feel).append("/5")
            if (w.rpe > 0) append(", RPE ").append(w.rpe)
            append('\n')
        }
        append("Сумма load: ").append(list.sumOf { it.load })
        append(", минут: ").append(list.sumOf { it.minutes }).append("\n\n")
    }

    /**
     * Подходы — то, чего нет больше нигде. Ради этого раздела сводка и
     * существует: без журнала силовых совет по прогрессии дать нельзя.
     */
    private fun StringBuilder.appendStrength(dates: List<String>) {
        val sessions = strength.sessionsFlow.value.filter { it.date in dates && !it.empty }
        append("ПОДХОДЫ (журнал Правки — этих данных нет ни в Garmin, ни в Excel)\n")
        if (sessions.isEmpty()) {
            append("Силовых за этот период не записано.\n\n")
            return
        }
        for (s in sessions.sortedBy { it.date }) {
            append(s.date).append(" — ").append(s.title.ifBlank { s.block.ifBlank { "силовая" } })
            if (s.minutes > 0) append(", ").append(s.minutes).append(" мин")
            if (s.feel in 1..5) append(", самочувствие ").append(s.feel).append("/5")
            if (s.rpe in 1..10) append(", RPE ").append(s.rpe)
            append('\n')
            for (e in s.exercises) {
                append("  · ").append(e.name).append(": ").append(e.compact())
                if (e.note.isNotBlank()) append(" — ").append(e.note)
                // Прошлый раз рядом: по нему и видно прогрессию.
                val previous = strength.lastTime(e.exerciseId, s.date)
                if (previous != null) {
                    append(" (прошлый раз ").append(previous.first.date).append(": ")
                    append(previous.second.compact()).append(")")
                }
                append('\n')
            }
            if (s.note.isNotBlank()) append("  ").append(s.note).append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.appendGtg(dates: List<String>) {
        val list = strength.gtgFlow.value.filter { it.date in dates && it.any }
        append("ЗАРЯДКА И ТУРНИК (GTG — путь к первому подтягиванию)\n")
        if (list.isEmpty()) {
            append("Отметок за этот период нет.\n")
        } else {
            for (g in list.sortedBy { it.date }) {
                append(g.date).append(": ")
                val bits = mutableListOf<String>()
                if (g.charged) bits.add("зарядка сделана")
                if (g.hangSec > 0) bits.add("вис ${g.hangSec} сек")
                if (g.negatives > 0) bits.add("негативы ${g.negatives}")
                if (g.scapular > 0) bits.add("лопаточные ${g.scapular}")
                if (g.knee.isNotBlank()) bits.add("колено ${g.knee}")
                append(bits.joinToString(", "))
                if (g.note.isNotBlank()) append(" — ").append(g.note)
                append('\n')
            }
        }
        val streak = strength.streak(dates.first())
        val best = strength.bestHang()
        append("Цепочка зарядки: ").append(streak).append(" дн. подряд")
        if (best > 0) append("; лучший вис за всё время ").append(best).append(" сек")
        append("\n\n")
    }

    private fun StringBuilder.appendFood(dates: List<String>) {
        append("ЕДА (дата · ккал · Б/Ж/У г · приёмов)\n")
        val totals = dates.map { food.dayTotal(it) }.filterNot { it.empty }
        if (totals.isEmpty()) {
            append("Дневник за этот период пуст.\n\n")
            return
        }
        for (t in totals.sortedBy { it.date }) {
            append(t.date).append(" · ").append(t.kcal).append(" · ")
            append("${t.protein}/${t.fat}/${t.carbs}").append(" · ").append(t.meals).append('\n')
        }
        val avg = totals.sumOf { it.kcal } / totals.size
        append("В среднем ").append(avg).append(" ккал по ").append(totals.size).append(" дн.")
        append("\n\n")
    }

    /**
     * Чем он вообще был занят. Тренировка не живёт в пустоте: одиннадцать
     * часов работы при пяти часах сна объясняют просадку лучше любой
     * тренировочной цифры.
     */
    private fun StringBuilder.appendRibbon(endDate: String, days: Int) {
        val entries = runCatching { zasechka.entriesFlow.value }.getOrNull().orEmpty()
        if (entries.isEmpty()) return
        append("ЧЕМ БЫЛ ЗАНЯТ (лента Засечки, часы по категориям)\n")
        val now = System.currentTimeMillis()
        val end = dayStart(endDate)
        for (back in 0 until days) {
            val start = end - back * 86_400_000L
            val stop = start + 86_400_000L
            val byCategory = entries
                .filter { it.start < stop && (if (it.open) now else it.end) > start }
                .groupBy { it.category.ifBlank { "без категории" } }
                .mapValues { (_, list) -> list.sumOf { it.durationMsIn(start, stop, now) } }
                .filterValues { it >= 30 * 60_000L }
                .toList()
                .sortedByDescending { it.second }
                .take(8)
            if (byCategory.isEmpty()) continue
            append(dayKey(start)).append(": ")
            append(byCategory.joinToString(", ") { (c, ms) -> "$c ${dec(ms / 3_600_000.0)} ч" })
            append('\n')
        }
        append('\n')
    }

    private fun StringBuilder.appendRules() {
        val r = plan.rulesFlow.value
        if (!r.known) return
        append("ЕГО ЖЕЛЕЗНЫЕ ПРАВИЛА (из блока «").append(r.blockTitle).append("» в Notion)\n")
        if (r.runHrCeiling > 0) append("- потолок лёгкого бега ").append(r.runHrCeiling).append('\n')
        if (r.greyZoneLow > 0 && r.greyZoneHigh > 0) {
            append("- серая зона ").append(r.greyZoneLow).append('–').append(r.greyZoneHigh)
                .append(" — не занимать\n")
        }
        if (r.cadenceMin > 0) append("- каденс ").append(r.cadenceMin).append("+\n")
        if (r.runsPerWeekMax > 0) append("- пробежек в неделю не больше ").append(r.runsPerWeekMax).append('\n')
        if (r.hoursBetweenRuns > 0) append("- между пробежками ").append(r.hoursBetweenRuns).append(" ч\n")
        if (r.rampNeedsPositiveTsb) append("- тест только на плюсовом TSB\n")
        if (r.cancelOrder.isNotBlank()) append("- отмена: ").append(r.cancelOrder).append('\n')
        for (line in r.extra) append("- ").append(line).append('\n')
        append('\n')
    }

    // ---- Наружу ----

    /** Сводка файлом — тем же путём, что CSV Засечки и дневника еды. */
    suspend fun shareIntent(text: String, name: String): android.content.Intent =
        withContext(Dispatchers.IO) {
            val out = java.io.File(context.cacheDir, name)
            out.writeText(text)
            shareFileIntent(context, out, "text/plain")
        }

    // ---- Мелочи ----

    private fun human(date: String): String = runCatching {
        isoFormat.parse(date)?.let { humanDay.format(it) }
    }.getOrNull() ?: date

    private fun dayStart(date: String): Long = runCatching {
        isoFormat.parse(date)?.let { dayStartMs(it.time) }
    }.getOrNull() ?: dayStartMs(System.currentTimeMillis())

    private fun num(v: Int) = if (v > 0) v.toString() else "—"
    private fun dec(v: Double) = if (v > 0) String.format(Locale.US, "%.1f", v) else "—"
    private fun fmt0(v: Double) = String.format(Locale.US, "%.0f", v)
    private fun pace(secPerKm: Int) =
        "${secPerKm / 60}:" + String.format(Locale.US, "%02d", secPerKm % 60)
}
