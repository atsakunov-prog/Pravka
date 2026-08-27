package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sqrt
import ru.zf.pravka.data.FoodStore
import ru.zf.pravka.data.SportStore
import ru.zf.pravka.data.StrengthStore
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.dayKey
import ru.zf.pravka.data.dayStartMs

/**
 * Входные данные для разбора «Итогов» — блоками, как их ждёт промпт.
 *
 * Главный принцип, из-за которого этот файл вообще существует: МОДЕЛЬ НЕ
 * СЧИТАЕТ. Она прекрасно видит смысл в заметках и отвратительно складывает
 * четырёхзначные числа по тремстам строкам. Всё, что можно посчитать
 * детерминированно, считается здесь, а модель получает готовые числа и
 * занимается только интерпретацией. Ошибиться в сумме кодом нельзя —
 * ошибиться моделью можно и незаметно.
 */
class AnalysisBuilder(
    private val zasechka: ZasechkaStore,
    private val sport: SportStore,
    private val strength: StrengthStore,
    private val food: FoodStore,
    private val plan: ru.zf.pravka.data.PlanStore,
    private val icu: ru.zf.pravka.data.IcuSportSync,
    private val reports: ru.zf.pravka.data.AnalysisStore,
    // Домены, которых разбор раньше не видел вообще. Владелец: «пускай
    // учитывает и интерфейсы, и Notion, и вообще всё». Каждый из них
    // необязателен: нет токена, нет журнала, нет сети — блок просто не
    // появится, а разбор соберётся.
    private val todoist: ru.zf.pravka.data.TodoistStore? = null,
    private val stats: ru.zf.pravka.data.Stats? = null,
    private val transcripts: ru.zf.pravka.data.TranscriptionLog? = null,
    private val history: ru.zf.pravka.data.HistoryLog? = null,
    private val raznoska: ru.zf.pravka.data.RaznoskaStore? = null,
    private val diary: ru.zf.pravka.data.NotionDiarySync? = null,
) {

    companion object {
        private const val BASELINE_DAYS = 28
        // База считается только если суток с данными хватает. Иначе выходит
        // то, что владелец увидел своими глазами: «сон в базе 1.54 ч в сутки»
        // — это не его жизнь, это деление шести суток на двадцать восемь.
        private const val BASELINE_MIN_DAYS = 5
        // Окно «его же последние недели» — то, с чем сравнивается один день.
        // Без него разбор дня был сферическим конём: 40 минут работы — это
        // мало или это его обычный вторник? Ответ только отсюда.
        private const val RECENT_DAYS = 21
        // Сутки считаются размеченными, если лента покрывает хотя бы столько.
        private const val DAY_COVERED_MIN = 240L
        // Дыра короче этого — микро-заход (фрагментация внимания).
        private const val MICRO_MAX_MIN = 15
        // Дыра длиннее этого — обвал (оцепенение). Механика разная.
        private const val COLLAPSE_MIN_MIN = 90
        private const val ANOMALY_DURATION_MIN = 600
        private val HOLE_CATEGORIES = setOf("потери", "не размечено")
        // «Пожарный» в языке IFS: чем человек тушит состояние. Владелец
        // разбирает эти категории функционально, а не морально.
        private val FIREFIGHTER_CATEGORIES = setOf("потери", "не размечено", "секс: соло", "отдых")
        // Окно «что было после события»: три часа — столько живёт хвост
        // тяжёлой встречи, дальше начинается уже другой день.
        private const val AFTER_WINDOW_MIN = 180
        // Событие-триггер: либо разговор с человеком, либо длинный блок с
        // названным именем. Короткие созвоны на минуту сюда не идут.
        private const val TRIGGER_MIN_MIN = 10
        private val WEEKDAYS = listOf("вс", "пн", "вт", "ср", "чт", "пт", "сб")

        /** Группа категории — таксономия для промпта, по имени категории. */
        fun groupOf(category: String): String {
            val c = category.trim().lowercase()
            return when {
                c == "сон" -> "восстановление"
                c == "отдых" -> "восстановление"
                c in HOLE_CATEGORIES -> "дыра"
                c.startsWith("работа") -> "работа"
                c.startsWith("социальное") -> "работа"
                c == "звонки" -> "смешанное"
                c == "систематизация" -> "подготовка"
                c == "семья" -> "семья"
                c == "чтение" -> "развитие"
                c.startsWith("спорт") -> "тело"
                c.startsWith("секс") -> "регуляция"
                c.startsWith("передвижение") || c == "быт" || c == "еда" -> "обслуживание"
                c.isBlank() -> "без категории"
                else -> "прочее"
            }
        }
    }

    data class Built(val text: String, val hash: String, val chars: Int, val days: Int)

    private val hm = SimpleDateFormat("HH:mm", Locale.US)
    private val ymd = SimpleDateFormat("yyyy-MM-dd", Locale.US)
    private val ym = SimpleDateFormat("yyyy-MM", Locale.US)

    /**
     * [fromDate]..[toDate] включительно, «yyyy-MM-dd». Режим влияет только на
     * объём таймлайна: в deep полный лог не поместится, и туда уезжают
     * последние семь суток целиком плюс заметки за остальное — заметки важнее
     * строк, именно в них живёт то, чего не видно в цифрах.
     */
    suspend fun build(mode: String, fromDate: String, toDate: String, context: String = ""): Built {
        zasechka.all()
        runCatching { sport.load() }
        runCatching { strength.load() }
        runCatching { food.load() }
        runCatching { plan.load() }
        // Перед разбором тянем intervals.icu: там и активности с Garmin, и
        // план событиями. Разбирать по чёрствому кэшу — значит объявлять
        // «тренировок не было» там, где их просто не выгрузили. Сети нет —
        // работаем по кэшу, но скажем об этом в <meta>.
        runCatching { icu.refresh(force = true) }
        runCatching { icu.refreshPlan(plan, back = 40, ahead = 7) }

        val from = dayStartMs(parseDate(fromDate))
        val to = dayStartMs(parseDate(toDate)) + 86_400_000L
        val now = System.currentTimeMillis()
        val rangeTo = minOf(to, now)
        val days = ((to - from) / 86_400_000L).toInt().coerceAtLeast(1)
        val dates = (0 until days).map { dayKey(from + it * 86_400_000L) }

        val entries = zasechka.forRange(from, to).sortedBy { it.start }
        val categories = zasechka.categories().map { it.name }

        val text = buildString {
            append("<mode>").append(mode).append("</mode>\n\n")
            appendMeta(fromDate, toDate, days, entries, from, rangeTo, now, context)
            appendTaxonomy(entries, categories)
            appendAggregates(entries, dates, now)
            appendBaseline(entries, from, dates.size, now)
            appendRecent(from, to, now)
            appendHoles(entries, now)
            appendSleep(dates, entries, now)
            appendHealth(dates)
            appendNutrition(dates)
            appendTraining(from, to, now, dates)
            appendCorrelations(entries, dates, now, from, to)
            appendTriggers(entries, now)
            appendPlan(dates, now)
            appendTasks(dates, now)
            appendDiary(fromDate, toDate)
            appendAppUse(dates, now)
            appendDataQuality(entries, dates, now)
            appendMemory()
            appendModules()
            appendTimeline(mode, entries, now, to)
        }
        return Built(text, hashOf(text), text.length, days)
    }

    // ---- блоки ----

    private fun StringBuilder.appendMeta(
        fromDate: String,
        toDate: String,
        days: Int,
        entries: List<ZasechkaStore.Entry>,
        from: Long,
        rangeTo: Long,
        now: Long,
        context: String,
    ) {
        val coveredMs = entries.sumOf { it.durationMsIn(from, rangeTo, now) }
        val elapsed = (rangeTo - from).coerceAtLeast(1L)
        val unmarkedMs = entries
            .filter { it.category.trim().lowercase() == "не размечено" }
            .sumOf { it.durationMsIn(from, rangeTo, now) }
        append("<meta>\n")
        append("период: ").append(fromDate).append(" — ").append(toDate).append("\n")
        append("суток: ").append(days).append("\n")
        append("покрытие таймшита: ").append(pct(coveredMs.toDouble() / elapsed)).append("\n")
        append("доля \"не размечено\": ").append(pct(unmarkedMs.toDouble() / elapsed)).append("\n")
        // Недоделанные сутки — отдельная строка, а не сноска. Разбор дня, чей
        // лог кончается в обед, обязан считать день незаконченным: иначе
        // «работы 40 минут» звучит как приговор дню, который ещё идёт.
        val lastMark = entries.maxOfOrNull { if (it.open) now else it.end } ?: 0L
        if (rangeTo >= now - 60_000L) {
            append("период включает сегодня, день НЕ ЗАКОНЧЕН: сейчас ")
                .append(hm.format(Date(now)))
            if (lastMark > 0L) append(", лента доведена до ").append(hm.format(Date(lastMark)))
            append(". Прошло ").append(fmt2((now - dayStartMs(now)) / 3_600_000.0))
                .append(" ч из 24 — суммы по этому дню неполные по определению,")
                .append(" не называй их итогом дня\n")
        }
        if (context.isNotBlank()) append("известный контекст: ").append(context.trim()).append("\n")
        // Свежесть выгрузки из intervals: по ней видно, можно ли верить
        // блокам <training> и <health>, или они просто не доехали.
        val syncedAt = runCatching { sport.lastSyncAt() }.getOrDefault(0L)
        append("выгрузка intervals.icu: ")
        append(
            if (syncedAt <= 0L) "не было ни разу — тренировки и здоровье могут быть неполными"
            else "обновлена " + ((now - syncedAt) / 60_000L) + " мин назад"
        )
        append("\n")
        append("</meta>\n\n")
    }

    private fun StringBuilder.appendTaxonomy(
        entries: List<ZasechkaStore.Entry>,
        categories: List<String>,
    ) {
        val used = (categories + entries.map { it.category })
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .distinctBy { it.lowercase() }
            .sorted()
        if (used.isEmpty()) return
        append("<taxonomy>\nКатегория | Группа\n")
        for (c in used) append(c).append(" | ").append(groupOf(c)).append("\n")
        append("</taxonomy>\n\n")
    }

    private fun StringBuilder.appendAggregates(
        entries: List<ZasechkaStore.Entry>,
        dates: List<String>,
        now: Long,
    ) {
        val cats = entries.map { it.category.trim().ifBlank { "без категории" } }
            .distinctBy { it.lowercase() }
            .sortedBy { groupOf(it) + it }
        if (cats.isEmpty()) return
        append("<aggregates>\n")
        append("Минуты по категориям и дням; последние колонки — итог и часов в сутки.\n")
        append("категория;").append(dates.joinToString(";")).append(";ИТОГО;Ч_В_СУТКИ\n")
        for (cat in cats) {
            val perDay = dates.map { d -> minutesOf(entries, cat, d, now) }
            val total = perDay.sum()
            if (total == 0L) continue
            append(cat).append(";").append(perDay.joinToString(";"))
            append(";").append(total)
            append(";").append(fmt2(total.toDouble() / 60.0 / dates.size)).append("\n")
        }
        val dayTotals = dates.map { d -> entries.sumOf { e -> minutesIn(e, d, now) } }
        append("ВСЕГО;").append(dayTotals.joinToString(";")).append(";")
            .append(dayTotals.sum()).append(";")
            .append(fmt2(dayTotals.sum().toDouble() / 60.0 / dates.size)).append("\n")
        append("</aggregates>\n\n")
    }

    private suspend fun StringBuilder.appendBaseline(
        periodEntries: List<ZasechkaStore.Entry>,
        periodFrom: Long,
        periodDays: Int,
        now: Long,
    ) {
        val baseTo = periodFrom
        val baseFrom = baseTo - BASELINE_DAYS * 86_400_000L
        val base = zasechka.forRange(baseFrom, baseTo)
        if (base.isEmpty()) return
        // ДЕЛИМ НА СУТКИ С ДАННЫМИ, А НЕ НА КАЛЕНДАРНЫЕ. Владелец поймал этот
        // баг на своём же разборе: «сон в базе 1.54 ч в сутки против 7.40 в
        // периоде, отклонение +381 процент — это не изменение поведения, это
        // дефект расчёта базы». Лента жила шесть суток, а делилось на 28.
        val baseDates = (0 until BASELINE_DAYS).map { dayKey(baseFrom + it * 86_400_000L) }
        val realDays = baseDates.count { d ->
            base.sumOf { minutesIn(it, d, now) } >= DAY_COVERED_MIN
        }
        if (realDays < BASELINE_MIN_DAYS) {
            append("<baseline>\n")
            append("Базы нет: за предыдущие ").append(BASELINE_DAYS).append(" дн. размечено ")
                .append(realDays).append(" сут. (порог ").append(BASELINE_MIN_DAYS)
                .append("). Сравнивать период не с чем — не сравнивай и не выдумывай ")
                .append("отклонений. Для дня сравнение бери из <recent>.\n")
            append("</baseline>\n\n")
            return
        }
        val cats = (base + periodEntries).map { it.category.trim().ifBlank { "без категории" } }
            .distinctBy { it.lowercase() }
            .sorted()
        append("<baseline>\n")
        append("Часы в сутки до периода против периода. База: ").append(realDays)
            .append(" размеченных суток из ").append(BASELINE_DAYS)
            .append(" (делится на них, не на календарь).\n")
        append("категория;ч_в_сутки_база;ч_в_сутки_период;отклонение_%\n")
        for (cat in cats) {
            val key = cat.lowercase()
            val baseH = base.filter { it.category.trim().lowercase() == key }
                .sumOf { it.durationMsIn(baseFrom, baseTo, now) } / 3_600_000.0 / realDays
            val periodH = periodEntries.filter { it.category.trim().lowercase() == key }
                .sumOf { it.durationMs(now) } / 3_600_000.0 / periodDays
            if (baseH < 0.02 && periodH < 0.02) continue
            // Пустая клетка — это «нет базы» или «не было в периоде», а не
            // «минус сто процентов»: минус сто процентов от нуля — не число.
            val dev = when {
                baseH < 0.02 -> "нет базы"
                periodH < 0.02 -> "не было в периоде"
                else -> ((periodH - baseH) / baseH * 100).roundToInt().toString()
            }
            append(cat).append(";").append(fmt2(baseH)).append(";").append(fmt2(periodH))
                .append(";").append(dev).append("\n")
        }
        append("</baseline>\n\n")
    }

    /** Дыры: микро-заходы и обвалы считаются РАЗДЕЛЬНО — механика разная. */
    private fun StringBuilder.appendHoles(entries: List<ZasechkaStore.Entry>, now: Long) {
        val holes = entries.filter { it.category.trim().lowercase() in HOLE_CATEGORIES }
        if (holes.isEmpty()) return
        val micro = holes.filter { it.durationMin(now) < MICRO_MAX_MIN }
        val collapses = holes.filter { it.durationMin(now) >= COLLAPSE_MIN_MIN }
        append("<holes>\n")
        append("микро-заходы (короче ").append(MICRO_MAX_MIN).append(" мин): ")
            .append(micro.size).append(" эпизодов, ").append(micro.sumOf { it.durationMin(now) })
            .append(" мин\n")
        append("обвалы (от ").append(COLLAPSE_MIN_MIN).append(" мин): ")
            .append(collapses.size).append(" эпизодов, ")
            .append(collapses.sumOf { it.durationMin(now) }).append(" мин\n")
        if (collapses.isNotEmpty()) {
            append("обвалы поимённо: ")
            append(collapses.joinToString("; ") {
                dayKey(it.start) + " " + hm.format(Date(it.start)) + " " +
                    it.durationMin(now) + " мин " + it.title.ifBlank { it.category }
            })
            append("\n")
        }
        // Самый длинный непрерывный блок каждой категории — «что вообще
        // получалось держать без переключения».
        val longest = entries.groupBy { it.category.trim().ifBlank { "без категории" } }
            .mapValues { (_, list) -> list.maxByOrNull { it.durationMs(now) } }
            .entries.sortedByDescending { it.value?.durationMs(now) ?: 0L }
            .take(10)
        append("самый длинный блок по категориям: ")
        append(longest.joinToString("; ") { (cat, e) ->
            "$cat ${e?.durationMin(now) ?: 0} мин"
        })
        append("\n</holes>\n\n")
    }

    private fun StringBuilder.appendSleep(
        dates: List<String>,
        entries: List<ZasechkaStore.Entry>,
        now: Long,
    ) {
        val rows = mutableListOf<String>()
        for (d in dates) {
            // Отбой и подъём берём из ленты (категория «Сон»), счёт — с часов.
            val sleeps = entries.filter {
                it.category.trim().equals("Сон", true) && dayKey(it.start) == d
            }
            val health = sport.healthOn(d)
            if (sleeps.isEmpty() && health == null) continue
            val main = sleeps.maxByOrNull { it.durationMs(now) }
            val minutes = sleeps.sumOf { it.durationMin(now) }
            rows.add(
                d + ";" +
                    (main?.let { hm.format(Date(it.start)) } ?: "—") + ";" +
                    (main?.let { if (it.open) "…" else hm.format(Date(it.end)) } ?: "—") + ";" +
                    (if (minutes > 0) fmt2(minutes / 60.0) else health?.sleepHours?.let { fmt2(it) } ?: "—") + ";" +
                    (health?.sleepScore?.takeIf { it > 0 }?.toString() ?: "—")
            )
        }
        if (rows.isEmpty()) return
        append("<sleep>\nдата;отбой;подъём;длительность_ч;счёт_garmin\n")
        rows.forEach { append(it).append("\n") }
        append("</sleep>\n\n")
    }

    /**
     * Здоровье с часов через intervals.icu: HRV, пульс покоя, сон, шаги, вес и
     * форма (CTL/ATL/TSB). Это второй по важности источник после ленты: он
     * объясняет провалы, которые из таймшита выглядят как лень.
     */
    private fun StringBuilder.appendHealth(dates: List<String>) {
        val rows = dates.mapNotNull { d -> sport.healthOn(d)?.let { d to it } }
        if (rows.isEmpty()) return
        append("<health>\n")
        append("Из intervals.icu (данные Garmin).\n")
        append("дата;hrv;пульс_покоя;сон_ч;счёт_сна;шаги;вес;ctl;atl;tsb\n")
        for ((d, h) in rows) {
            append(d).append(";")
                .append(if (h.hrv > 0) h.hrv.toString() else "").append(";")
                .append(if (h.restingHr > 0) h.restingHr.toString() else "").append(";")
                .append(if (h.sleepHours > 0) fmt2(h.sleepHours) else "").append(";")
                .append(if (h.sleepScore > 0) h.sleepScore.toString() else "").append(";")
                .append(if (h.steps > 0) h.steps.toString() else "").append(";")
                .append(if (h.weightKg > 0) fmt2(h.weightKg) else "").append(";")
                .append(fmt2(h.ctl)).append(";").append(fmt2(h.atl)).append(";")
                .append(fmt2(h.tsb)).append("\n")
        }
        append("</health>\n\n")
    }

    private fun StringBuilder.appendNutrition(dates: List<String>) {
        val rows = mutableListOf<String>()
        for (d in dates) {
            val total = food.dayTotal(d)
            if (total.empty) continue
            val meals = food.mealsOn(d).sortedBy { it.ts }
            rows.add(
                d + ";" + total.kcal + ";" + total.protein + ";" + total.fat + ";" +
                    total.carbs + ";" +
                    (meals.firstOrNull()?.let { hm.format(Date(it.ts)) } ?: "—") + ";" +
                    (meals.lastOrNull()?.let { hm.format(Date(it.ts)) } ?: "—") + ";" +
                    meals.size
            )
        }
        if (rows.isEmpty()) return
        append("<nutrition>\nдата;ккал;белок_г;жир_г;углеводы_г;первый_приём;последний_приём;число_записей\n")
        rows.forEach { append(it).append("\n") }
        append("</nutrition>\n\n")
    }

    private fun StringBuilder.appendTraining(
        from: Long,
        to: Long,
        now: Long,
        dates: List<String>,
    ) {
        val all = sport.workoutsFlow.value
        val inRange = all.filter { it.start in from until to }.sortedBy { it.start }
        append("<training>\n")
        if (inRange.isEmpty()) {
            append("В периоде тренировок нет.\n")
        } else {
            append("дата;вид;мин;км;пульс;load;самочувствие\n")
            for (w in inRange) {
                append(dayKey(w.start)).append(";")
                    .append(SportCoach.sportName(w.type)).append(";")
                    .append(w.minutes).append(";")
                    .append(if (w.km >= 0.1) fmt2(w.km) else "").append(";")
                    .append(if (w.avgHr > 0) w.avgHr.toString() else "").append(";")
                    .append(w.load).append(";")
                    .append(if (w.feel in 1..5) w.feel.toString() else "").append("\n")
            }
        }
        // Дней с последней тренировки каждого вида — по ВСЕЙ истории, не только
        // по периоду: «Вело 17 дней назад» и есть ответ на «что вытеснено».
        val byType = all.groupBy { SportCoach.sportName(it.type) }
        if (byType.isNotEmpty()) {
            append("дней с последней: ")
            append(byType.entries.sortedBy { it.key }.joinToString(", ") { (name, list) ->
                val last = list.maxOf { it.start }
                name + " " + ((now - last) / 86_400_000L)
            })
            append("\n")
        }
        // Помесячно: объём и среднее самочувствие — самый недооценённый сигнал.
        val months = all.filter { it.start > now - 190L * 86_400_000L }
            .groupBy { ym.format(Date(it.start)) + ";" + SportCoach.sportName(it.type) }
        if (months.isNotEmpty()) {
            append("месяц;вид;мин_сумма;число;самочувствие_среднее\n")
            for ((key, list) in months.entries.sortedBy { it.key }) {
                val feels = list.mapNotNull { it.feel.takeIf { f -> f in 1..5 } }
                append(key).append(";").append(list.sumOf { it.minutes }).append(";")
                    .append(list.size).append(";")
                    .append(if (feels.isEmpty()) "—" else fmt2(feels.average())).append("\n")
            }
        }
        // Зарядка и турник: отметки владельца по дням периода.
        val gtg = strength.gtgFlow.value.filter { it.date in dates && it.any }
        if (gtg.isNotEmpty()) {
            append("зарядка по дням: ")
            append(gtg.sortedBy { it.date }.joinToString("; ") { it.date + " " + it.line() })
            append("\n")
        }
        append("</training>\n\n")
    }

    /**
     * Корреляции считает код, интерпретирует модель. n передаём обязательно —
     * иначе связь по четырём точкам выдаётся за закономерность.
     */
    private fun StringBuilder.appendCorrelations(
        entries: List<ZasechkaStore.Entry>,
        dates: List<String>,
        now: Long,
        from: Long,
        to: Long,
    ) {
        if (dates.size < 4) return
        val sleepH = dates.map { d ->
            entries.filter { it.category.trim().equals("Сон", true) && dayKey(it.start) == d }
                .sumOf { it.durationMin(now) } / 60.0
        }
        val holeMin = dates.map { d ->
            entries.filter { it.category.trim().lowercase() in HOLE_CATEGORIES }
                .sumOf { minutesIn(it, d, now) }.toDouble()
        }
        val trainMin = dates.map { d ->
            sport.workoutsFlow.value.filter { dayKey(it.start) == d }.sumOf { it.minutes }.toDouble()
        }
        val lines = mutableListOf<String>()
        // Сон и дыры СЛЕДУЮЩЕГО дня: сдвиг на день — это и есть вопрос
        // «во что обходится короткая ночь».
        if (dates.size >= 5) {
            val x = sleepH.dropLast(1)
            val y = holeMin.drop(1)
            pearson(x, y)?.let { lines.add("сон_часы vs дыры_мин_след_день: r=${fmt2(it)} (n=${x.size})") }
        }
        pearson(trainMin, holeMin)?.let {
            lines.add("тренировка_мин vs дыры_мин_тот_же_день: r=${fmt2(it)} (n=${trainMin.size})")
        }
        pearson(sleepH, trainMin)?.let {
            lines.add("сон_часы vs тренировка_мин: r=${fmt2(it)} (n=${sleepH.size})")
        }
        // Сон и зарядка СЛЕДУЮЩЕГО утра: «мало спал — забил зарядку» это
        // ровно эта пара, и её надо считать, а не угадывать по таймлайну.
        val charged = dates.map { d ->
            if (strength.gtgFlow.value.firstOrNull { it.date == d }?.charged == true) 1.0 else 0.0
        }
        if (dates.size >= 5) {
            val x = sleepH.dropLast(1)
            val y = charged.drop(1)
            pearson(x, y)?.let {
                lines.add("сон_часы vs зарядка_сделана_след_день (1/0): r=${fmt2(it)} (n=${x.size})")
            }
        }
        pearson(sleepH, charged)?.let {
            lines.add("сон_часы vs зарядка_сделана_тот_же_день (1/0): r=${fmt2(it)} (n=${sleepH.size})")
        }
        // HRV и форма — из intervals: объясняют провал, который из таймшита
        // выглядит просто ленью.
        val hrv = dates.map { d -> sport.healthOn(d)?.hrv?.toDouble() ?: 0.0 }
        val tsb = dates.map { d -> sport.healthOn(d)?.tsb ?: 0.0 }
        if (hrv.count { it > 0 } >= 4) {
            pearson(hrv, holeMin)?.let {
                lines.add("hrv vs дыры_мин_тот_же_день: r=${fmt2(it)} (n=${hrv.size})")
            }
        }
        if (tsb.count { it != 0.0 } >= 4) {
            pearson(tsb, trainMin)?.let {
                lines.add("форма_tsb vs тренировка_мин: r=${fmt2(it)} (n=${tsb.size})")
            }
        }

        // «Пожарный»: чем тушится состояние — потери, отдых, соло.
        val fireMin = dates.map { d ->
            entries.filter { it.category.trim().lowercase() in FIREFIGHTER_CATEGORIES }
                .sumOf { minutesIn(it, d, now) }.toDouble()
        }
        pearson(sleepH, fireMin)?.let {
            lines.add("сон_часы vs регуляция_мин_тот_же_день: r=${fmt2(it)} (n=${sleepH.size})")
        }
        if (dates.size >= 5) {
            val x = trainMin.dropLast(1)
            val y = fireMin.drop(1)
            pearson(x, y)?.let {
                lines.add("тренировка_мин vs регуляция_мин_след_день: r=${fmt2(it)} (n=${x.size})")
            }
        }
        if (lines.isEmpty()) return
        append("<correlations>\n")
        lines.forEach { append(it).append("\n") }
        append("</correlations>\n\n")
    }

    /**
     * Что происходит ПОСЛЕ событий с людьми — то, что владелец называет
     * «встретился с Тимофеем, и сразу включается Пожарный». Три часа после
     * каждого разговора, минуты по группам, и рядом — средние три часа дня
     * для сравнения: без базы «47 минут потерь» ничего не значат.
     *
     * Считаем ровно факты. Является ли это паттерном, решает модель по своему
     * правилу «три совпадения и больше», и она же обязана назвать это
     * гипотезой, а не диагнозом.
     */
    private fun StringBuilder.appendTriggers(entries: List<ZasechkaStore.Entry>, now: Long) {
        // Событие с человеком: имя в поле «клиент» или в названии разговора.
        val triggers = entries.filter { e ->
            e.durationMin(now) >= TRIGGER_MIN_MIN &&
                (e.client.isNotBlank() ||
                    e.category.trim().lowercase().let { it == "звонки" || it.startsWith("работа: звонки") } ||
                    Regex("(?i)(встреч|созвон|звонок|разговор|терапи)").containsMatchIn(e.title))
        }
        if (triggers.isEmpty()) return

        fun windowMinutes(afterMs: Long, filter: (ZasechkaStore.Entry) -> Boolean): Long {
            val to = afterMs + AFTER_WINDOW_MIN * 60_000L
            return entries.filter(filter).sumOf { it.durationMsIn(afterMs, to, now) } / 60_000L
        }

        // База: сколько «регуляции» в среднем приходится на любые три часа
        // периода. Ниже с этим и сравнивается хвост события.
        val totalMs = entries.sumOf { it.durationMs(now) }.coerceAtLeast(1L)
        val fireShare = entries
            .filter { it.category.trim().lowercase() in FIREFIGHTER_CATEGORIES }
            .sumOf { it.durationMs(now) }.toDouble() / totalMs
        append("<triggers>\n")
        append("Что было в ").append(AFTER_WINDOW_MIN)
            .append(" мин после разговоров и встреч. Для сравнения: в среднем по периоду ")
            .append("регуляция (потери, отдых, соло) занимает ").append(pct(fireShare))
            .append(" времени, то есть примерно ")
            .append((fireShare * AFTER_WINDOW_MIN).roundToInt())
            .append(" мин из каждых ").append(AFTER_WINDOW_MIN).append(".\n")
        append("событие;дата;время;длит_мин;кто;после_регуляция_мин;после_работа_мин;после_сон_мин\n")
        for (t in triggers.sortedBy { it.start }) {
            val end = if (t.open) now else t.end
            val fire = windowMinutes(end) {
                it.category.trim().lowercase() in FIREFIGHTER_CATEGORIES
            }
            val work = windowMinutes(end) {
                val c = it.category.trim().lowercase()
                c.startsWith("работа") || c == "систематизация"
            }
            val sleep = windowMinutes(end) { it.category.trim().equals("Сон", true) }
            append(t.title.ifBlank { t.category }.replace(';', ',')).append(";")
                .append(dayKey(t.start)).append(";")
                .append(hm.format(Date(t.start))).append(";")
                .append(t.durationMin(now)).append(";")
                .append(t.client.ifBlank { "—" }.replace(';', ',')).append(";")
                .append(fire).append(";").append(work).append(";").append(sleep).append("\n")
        }
        // Сводка по людям: сколько событий и средний хвост регуляции. Именно
        // здесь видно «после Ильи всегда провал», если оно есть.
        val byPerson = triggers.filter { it.client.isNotBlank() }.groupBy { it.client }
        if (byPerson.isNotEmpty()) {
            append("по людям: ")
            append(byPerson.entries.sortedByDescending { it.value.size }.joinToString("; ") { (who, list) ->
                val avg = list.map { t ->
                    val end = if (t.open) now else t.end
                    windowMinutes(end) { it.category.trim().lowercase() in FIREFIGHTER_CATEGORIES }
                }.average()
                "$who: событий ${list.size}, регуляция после в среднем ${avg.roundToInt()} мин"
            })
            append("\n")
        }
        append("</triggers>\n\n")
    }

    /**
     * План против факта и ЕГО ПРАВИЛА. План владелец пушит из чата в календарь
     * intervals, правила блока живут страницей в Notion — без этого разбор
     * рассуждает о тренировках в вакууме: «мало бегал» вместо «по плану было
     * три пробежки, сделана одна, и это его же правило про потолок пульса».
     */
    private suspend fun StringBuilder.appendPlan(dates: List<String>, now: Long) {
        runCatching { plan.load() }
        val planned = dates.flatMap { d -> runCatching { plan.dayOf(d) }.getOrNull().orEmpty() }
        val rules = runCatching { plan.rulesFlow.value }.getOrNull()
        if (planned.isEmpty() && (rules == null || !rules.known)) return
        append("<plan>\n")
        if (planned.isNotEmpty()) {
            append("Запланировано в календаре intervals (владелец пушит из чата).\n")
            append("дата;сессия;тип;мин;load;сделано_факт\n")
            for (p in planned.sortedBy { it.date }) {
                // Факт — из intervals: активность того же типа в тот же день.
                // Пишем не «да/нет», а минуты и load: «планировал 60, сделал
                // 22» и есть тот разговор, который стоит вести.
                val actual = sport.workoutsFlow.value.filter {
                    dayKey(it.start) == p.date && it.type.equals(p.type, ignoreCase = true)
                }
                val fact = when {
                    actual.isEmpty() -> "нет"
                    else -> "да, " + actual.sumOf { it.minutes } + " мин, load " +
                        actual.sumOf { it.load }
                }
                append(p.date).append(";")
                    .append(p.name.replace(';', ',')).append(";")
                    .append(p.type).append(";")
                    .append(p.minutes).append(";")
                    .append(p.load).append(";")
                    .append(fact).append("\n")
            }
            // Активности, которых в плане не было вовсе — «делал не то».
            val planTypes = planned.map { it.type.lowercase() to it.date }.toSet()
            val unplanned = sport.workoutsFlow.value.filter { w ->
                dayKey(w.start) in dates && (w.type.lowercase() to dayKey(w.start)) !in planTypes
            }
            if (unplanned.isNotEmpty()) {
                append("не по плану: ")
                append(unplanned.joinToString("; ") {
                    dayKey(it.start) + " " + SportCoach.sportName(it.type) + " " +
                        it.minutes + " мин load " + it.load
                })
                append("\n")
            }
        }
        if (rules != null && rules.known) {
            append("Его правила блока (страница Notion, правит руками)")
            if (rules.blockTitle.isNotBlank()) append(": ").append(rules.blockTitle)
            append("\n")
            if (rules.runHrCeiling > 0) append("потолок лёгкого бега: ").append(rules.runHrCeiling).append("\n")
            if (rules.greyZoneLow > 0 && rules.greyZoneHigh > 0) {
                append("серая зона (не работать): ").append(rules.greyZoneLow)
                    .append("–").append(rules.greyZoneHigh).append("\n")
            }
            if (rules.runsPerWeekMax > 0) append("пробежек в неделю не больше: ").append(rules.runsPerWeekMax).append("\n")
            if (rules.hoursBetweenRuns > 0) append("между пробежками часов: ").append(rules.hoursBetweenRuns).append("\n")
            if (rules.cancelOrder.isNotBlank()) append("что выпадает первым: ").append(rules.cancelOrder).append("\n")
            if (rules.kneeGreen.isNotBlank() || rules.kneeRed.isNotBlank()) {
                append("светофор колена — зелёный: ").append(rules.kneeGreen)
                    .append("; жёлтый: ").append(rules.kneeYellow)
                    .append("; красный: ").append(rules.kneeRed).append("\n")
            }
            if (rules.weekPlan.isNotEmpty()) {
                append("неделя по плану: ")
                append(rules.weekPlan.joinToString(", ") { (day, session) -> "$day — $session" })
                append("\n")
            }
            if (rules.sourceText.isNotBlank()) {
                append("страница блока прозой (тут объяснено почему):\n")
                append(rules.sourceText.take(3000))
                append("\n")
            }
        }
        append("</plan>\n\n")
    }

    /**
     * ЕГО ЖЕ ПОСЛЕДНИЕ ТРИ НЕДЕЛИ — одной таблицей. Это лекарство от того,
     * что владелец назвал «совершенно неинтересный разбор»: без окна разбор
     * одного дня рассуждал о сферическом коне. «Работа 40 минут» — это провал
     * или его обычная среда? Ответ только из сравнения с собственной нормой,
     * и медиана внизу таблицы даёт его прямо, без пересчёта в уме.
     */
    private suspend fun StringBuilder.appendRecent(periodFrom: Long, toExclusive: Long, now: Long) {
        val windowFrom = dayStartMs(toExclusive - 1) - (RECENT_DAYS - 1L) * 86_400_000L
        val windowTo = minOf(toExclusive, now)
        val entries = zasechka.forRange(windowFrom, windowTo)
        if (entries.isEmpty()) return
        val dates = (0 until RECENT_DAYS)
            .map { dayKey(windowFrom + it * 86_400_000L) }
            .filter { parseDate(it) < windowTo }
        val rows = mutableListOf<List<String>>()
        val numeric = mutableListOf<List<Double?>>()
        for (d in dates) {
            val covered = entries.sumOf { minutesIn(it, d, now) }
            if (covered < 30) continue
            val health = sport.healthOn(d)
            val meal = food.dayTotal(d)
            val trained = sport.workoutsFlow.value
                .filter { dayKey(it.start) == d }.sumOf { it.minutes }
            val sleep = minutesOf(entries, "Сон", d, now) / 60.0
            val work = groupMinutes(entries, "работа", d, now)
            val prep = groupMinutes(entries, "подготовка", d, now)
            val holes = groupMinutes(entries, "дыра", d, now)
            val body = groupMinutes(entries, "тело", d, now)
            val family = groupMinutes(entries, "семья", d, now)
            val reg = groupMinutes(entries, "регуляция", d, now)
            val charged = runCatching { strength.gtgOn(d)?.charged == true }.getOrDefault(false)
            rows.add(
                listOf(
                    d, weekday(d), fmt2(sleep), work.toString(), prep.toString(),
                    holes.toString(), body.toString(), family.toString(), reg.toString(),
                    (covered * 100 / 1440).toString(),
                    if (meal.empty) "" else meal.kcal.toString(),
                    if (meal.empty) "" else meal.protein.toString(),
                    health?.steps?.takeIf { it > 0 }?.toString() ?: "",
                    health?.hrv?.takeIf { it > 0 }?.toString() ?: "",
                    if (health != null) fmt2(health.tsb) else "",
                    trained.toString(),
                    if (charged) "да" else "нет",
                )
            )
            numeric.add(
                listOf(
                    sleep, work.toDouble(), prep.toDouble(), holes.toDouble(), body.toDouble(),
                    family.toDouble(), reg.toDouble(), null,
                    if (meal.empty) null else meal.kcal.toDouble(),
                    if (meal.empty) null else meal.protein.toDouble(),
                    health?.steps?.takeIf { it > 0 }?.toDouble(),
                    health?.hrv?.takeIf { it > 0 }?.toDouble(),
                    health?.tsb,
                    trained.toDouble(),
                    null,
                )
            )
        }
        if (rows.size < 3) return
        append("<recent>\n")
        append("Его собственная норма: последние ").append(rows.size)
            .append(" размеченных суток по ").append(RECENT_DAYS)
            .append("-дневному окну, включая период. Сравнивай день и период ")
            .append("ИМЕННО С ЭТИМ, а не с представлениями о правильной жизни.\n")
        append("дата;день;сон_ч;работа_м;систем_м;дыры_м;тело_м;семья_м;регуляц_м;")
        append("покрытие_%;ккал;белок_г;шаги;hrv;tsb;трен_м;зарядка\n")
        rows.forEach { append(it.joinToString(";")).append("\n") }
        // Медиана устойчивее среднего: одна ночь на три часа не должна
        // переписывать «обычную ночь».
        val medians = (0 until 15).map { i ->
            val col = numeric.mapNotNull { it.getOrNull(i) }
            if (col.isEmpty()) "" else fmt2(median(col))
        }
        append("МЕДИАНА;—;").append(medians[0]).append(";")
        append(medians.subList(1, 7).joinToString(";")).append(";—;")
        append(medians.subList(8, 14).joinToString(";")).append(";—\n")
        // Цифры окна без слов окна — половина дела: повтор ищется по смыслу
        // заметок, а не по минутам. Заметки самого периода тут не нужны, они
        // целиком лежат в <timeline>.
        val words = entries
            .filter { it.start < periodFrom }
            .filter { it.raw.trim().length > 25 }
            .sortedBy { it.start }
        if (words.isNotEmpty()) {
            append("Его заметки из этих суток (до периода; заметки самого ")
            append("периода — в <timeline>):\n")
            append("дата;время;категория;название;заметка\n")
            words.takeLast(80).forEach { e ->
                append(dayKey(e.start)).append(";")
                    .append(hm.format(Date(e.start))).append(";")
                    .append(e.category.ifBlank { "—" }).append(";")
                    .append(e.title.ifBlank { "—" }).append(";")
                    .append(e.raw.trim().replace('\n', ' ').take(180)).append("\n")
            }
        }
        append("</recent>\n\n")
    }

    /**
     * ДЕЛА ИЗ TODOIST — то, что он сам себе поставил. До этого разбор судил о
     * работе только по минутам в ленте и не знал, на что эти минуты должны
     * были уйти. Просроченный список — самая честная улика «чего изволите»:
     * своё лежит, а чужое сделано в тот же день.
     */
    private suspend fun StringBuilder.appendTasks(dates: List<String>, now: Long) {
        val store = todoist ?: return
        runCatching { store.load() }
        val tasks = store.tasksFlow.value
        if (tasks.isEmpty()) return
        val today = dayKey(now)
        val projects = store.projectsFlow.value.associateBy { it.id }
        fun pathOf(id: String): String =
            projects[id]?.let { runCatching { store.path(it) }.getOrDefault(it.name) } ?: "—"
        fun overdueDays(due: String): Long =
            if (due.isBlank() || due >= today) 0
            else (parseDate(today) - parseDate(due)) / 86_400_000L

        val overdue = tasks.filter { it.due.isNotBlank() && it.due < today }
        append("<tasks>\n")
        append("Открытые дела Todoist на сейчас. Списка «сделано» здесь нет — ")
        append("Todoist закрытые не отдаёт, поэтому судить о выполнении можно ")
        append("только по тому, что ушло из списка и что в нём залежалось.\n")
        append("всего открытых: ").append(tasks.size)
        append("; просрочено: ").append(overdue.size)
        append("; на сегодня: ").append(tasks.count { it.due == today })
        append("; без срока: ").append(tasks.count { it.due.isBlank() })
        append("; p1: ").append(tasks.count { it.priority >= 4 }).append("\n")
        val synced = store.syncedAtFlow.value
        append("список обновлялся: ")
            .append(if (synced <= 0) "не было ни разу" else ((now - synced) / 60_000L).toString() + " мин назад")
            .append("\n")
        append("проект;открытых;просрочено;самая старая просрочка_дн\n")
        tasks.groupBy { pathOf(it.projectId) }
            .entries.sortedByDescending { it.value.size }
            .take(14)
            .forEach { (path, list) ->
                val late = list.filter { it.due.isNotBlank() && it.due < today }
                append(path.replace(';', ',')).append(";").append(list.size).append(";")
                    .append(late.size).append(";")
                    .append(late.maxOfOrNull { overdueDays(it.due) } ?: 0).append("\n")
            }
        if (overdue.isNotEmpty()) {
            append("просроченные поимённо (до 20, дольше всех сверху):\n")
            overdue.sortedByDescending { overdueDays(it.due) }.take(20).forEach {
                append("- ").append(it.content.take(120))
                    .append(" | ").append(pathOf(it.projectId))
                    .append(" | срок ").append(it.due)
                    .append(", лежит ").append(overdueDays(it.due)).append(" дн.")
                    .append(if (it.priority >= 4) ", p1" else "")
                    .append("\n")
            }
        }
        val dueInPeriod = tasks.filter { it.due in dates }
        if (dueInPeriod.isNotEmpty()) {
            append("срок приходился на период и дело ВСЁ ЕЩЁ открыто:\n")
            dueInPeriod.take(15).forEach {
                append("- ").append(it.due).append(" ").append(it.content.take(120))
                    .append(" | ").append(pathOf(it.projectId)).append("\n")
            }
        }
        append("Сверь это с названиями в <timeline>: на какие проекты минуты в ")
        append("ленте были, а на какие открытых дел много и минут нет вовсе.\n")
        append("</tasks>\n\n")
    }

    /**
     * ПРИЛОЖЕНИЕ КАК УЛИКА. Владелец сам разрабатывает Правку, и в ленте это
     * лежит как «Систематизация» — часы. А здесь то, чего в ленте нет:
     * деньги на модель по суткам и объём того, что он в приложение наговорил.
     * Часы и рубли рядом и есть материал для разговора про «паттерн сначала»,
     * причём материал, который нельзя оспорить.
     */
    private suspend fun StringBuilder.appendAppUse(dates: List<String>, now: Long) {
        val costs = stats?.let { runCatching { it.dailyCosts(RECENT_DAYS) }.getOrNull() }
        val voice = transcripts?.let { runCatching { it.readLast(800) }.getOrNull() }
        val edits = history?.let { runCatching { it.readMeta(800) }.getOrNull() }
        val drafts = raznoska?.let { runCatching { it.load() }.getOrNull() }
        if (costs.isNullOrEmpty() && voice.isNullOrEmpty() && edits.isNullOrEmpty()) return
        append("<app>\n")
        append("Правка — его собственное приложение, он его и пишет. В ленте эта ")
        append("работа лежит как «Систематизация», здесь — то, чего в ленте нет.\n")
        val spentDays = costs.orEmpty().filter { it.second > 0.0001 }
        if (spentDays.isNotEmpty()) {
            append("деньги на модель по суткам (USD), свежий день первым: ")
            append(spentDays.joinToString(", ") {
                it.first + " " + String.format(Locale.US, "%.2f", it.second)
            })
            append("\n")
            val periodCost = spentDays.filter { it.first in dates }.sumOf { it.second }
            val windowCost = spentDays.sumOf { it.second }
            append("за период: ").append(String.format(Locale.US, "%.2f", periodCost))
                .append(" USD; за ").append(RECENT_DAYS).append(" дн.: ")
                .append(String.format(Locale.US, "%.2f", windowCost)).append(" USD\n")
        }
        if (!voice.isNullOrEmpty()) {
            val byDay = voice.groupBy { it.ts.take(10) }
            append("диктовки по суткам: дата;записей;знаков;минут_речи;ошибок\n")
            byDay.entries.sortedByDescending { it.key }.take(RECENT_DAYS).forEach { (d, list) ->
                append(d).append(";").append(list.size).append(";")
                    .append(list.sumOf { it.chars }).append(";")
                    .append(fmt2(list.sumOf { it.audioMs } / 60_000.0)).append(";")
                    .append(list.count { !it.ok }).append("\n")
            }
        }
        if (!edits.isNullOrEmpty()) {
            val inWindow = edits.filter { it.date >= (dates.firstOrNull() ?: "") }
            if (inWindow.isNotEmpty()) {
                append("обращения к модели по режимам за период и позже: ")
                append(inWindow.groupBy { it.mode.ifBlank { "—" } }
                    .entries.sortedByDescending { it.value.size }
                    .joinToString(", ") { it.key + " " + it.value.size })
                append("\n")
            }
        }
        if (!drafts.isNullOrEmpty()) {
            val pending = drafts.count { it.pending }
            append("разноска: черновиков ").append(drafts.size)
                .append(", неразобранных ").append(pending).append("\n")
        }
        append("</app>\n\n")
    }

    /**
     * ДНЕВНИК NOTION — ЕГО СЛОВАМИ. Самый дорогой текст в системе: в ленте
     * «Работа, 90 мин», а в дневнике то, что он про этот день сам написал.
     * Приложение пишет в ту же базу цифры, поэтому свой текст мы отсюда
     * вырезаем: процитировать модели нашу же арифметику как его слова — это
     * подделка данных, а не разбор.
     */
    private suspend fun StringBuilder.appendDiary(fromDate: String, toDate: String) {
        val sync = diary ?: return
        // Берём шире периода: его запись про вчерашний вечер объясняет
        // сегодняшнее утро, а разбор дня без соседних дней слеп.
        val wide = dayKey(dayStartMs(parseDate(fromDate)) - 6L * 86_400_000L)
        val rows = runCatching { sync.readRange(wide, toDate) }.getOrNull()
            ?.filter { it.any } ?: return
        if (rows.isEmpty()) return
        append("<diary>\n")
        append("База «Дневник» в Notion. Колонки «План» и «Заметки» пишет он ")
        append("сам — это его формулировки, цитируй дословно. Галочки, Feel, ")
        append("колено и вес приложение ставит автоматически: расхождение ")
        append("галочки с лентой — дефект данных, а не его забывчивость.\n")
        for (r in rows.sortedBy { it.date }) {
            val inPeriod = r.date >= fromDate && r.date <= toDate
            append(if (inPeriod) "- " else "- (до периода) ").append(r.date).append(": ")
            val bits = mutableListOf<String>()
            if (r.feel.isNotBlank()) bits.add("feel " + r.feel)
            if (r.knee.isNotBlank()) bits.add("колено " + r.knee)
            if (r.weightKg > 0) bits.add("вес " + fmt2(r.weightKg))
            bits.add("зарядка " + if (r.charged) "да" else "нет")
            bits.add("сессия " + if (r.done) "да" else "нет")
            append(bits.joinToString(", "))
            val his = r.hisWords
            if (his.isNotBlank()) append(" | ЕГО СЛОВАМИ: ").append(his.take(700))
            append("\n")
        }
        append("</diary>\n\n")
    }

    private fun StringBuilder.appendDataQuality(
        entries: List<ZasechkaStore.Entry>,
        dates: List<String>,
        now: Long,
    ) {
        val issues = mutableListOf<String>()
        for (e in entries) {
            if (e.durationMin(now) > ANOMALY_DURATION_MIN) {
                issues.add(
                    "длительность ${e.durationMin(now)} мин, ${dayKey(e.start)}, " +
                        "${e.title.ifBlank { e.category }} — превышен порог $ANOMALY_DURATION_MIN"
                )
            }
        }
        // Протянутая заметка: один и тот же raw в трёх и более подряд записях.
        var run = 1
        for (i in 1 until entries.size) {
            val same = entries[i].raw.isNotBlank() && entries[i].raw == entries[i - 1].raw
            if (same) run++ else run = 1
            val endOfRun = i == entries.size - 1 || entries.getOrNull(i + 1)?.raw != entries[i].raw
            if (run >= 3 && endOfRun) {
                issues.add(
                    "заметка повторяется в $run подряд записях ${dayKey(entries[i].start)} " +
                        hm.format(Date(entries[i - run + 1].start)) + "-" + hm.format(Date(entries[i].start))
                )
            }
        }
        // Поехавшая таксономия: одно и то же дело внутри периода лежит в
        // разных категориях (YouTube был «Отдых», стал «Потери»). Сравнимость
        // ломается молча, и заметить это можно только так.
        entries.filter { it.title.isNotBlank() && it.category.isNotBlank() }
            .groupBy { it.title.trim().lowercase() }
            .forEach { (title, list) ->
                val cats = list.map { it.category.trim() }.distinctBy { it.lowercase() }
                if (cats.size > 1) {
                    issues.add(
                        "«$title» внутри периода лежит в разных категориях: " +
                            cats.joinToString(", ") + " — сравнимость по этой активности ломается"
                    )
                }
            }
        for (d in dates) {
            val meals = food.mealsOn(d)
            val bySameMinute = meals.groupBy { it.ts / 60_000 }.filterValues { it.size > 3 }
            for ((_, list) in bySameMinute) {
                issues.add(
                    "${list.size} записей питания с одинаковой меткой времени $d " +
                        hm.format(Date(list.first().ts))
                )
            }
        }
        if (issues.isEmpty()) return
        append("<data_quality>\nАвтоматически найденные аномалии.\n")
        issues.take(20).forEach { append("- ").append(it).append("\n") }
        append("</data_quality>\n\n")
    }

    /**
     * Память между разборами: свои же прошлые паттерны и главные наблюдения.
     * Без этого каждый разбор начинался с чистого листа и повторял одно и то
     * же из недели в неделю — владелец просил ровно обратного: «он про меня
     * много знает».
     */
    private suspend fun StringBuilder.appendMemory() {
        runCatching { reports.load() }
        val patterns = reports.patternsFlow.value
        if (patterns.isNotEmpty()) {
            append("<known_patterns>\n")
            append("Твои же находки прошлых разборов. По каждому скажи: подтвердился, ")
            append("ослаб, исчез или данных не хватило.\n")
            // Колонка «слово Саши» — единственная в системе оценка модели
            // человеком, и она весит больше её собственной уверенности:
            // повтор можно увидеть в цифрах и всё равно ошибиться в том, что
            // он значит. Проверить это может только он.
            append("паттерн;впервые;последний_раз;разборов_подряд;была_уверенность;")
            append("слово_Саши\n")
            for (pt in patterns) {
                val verdict = when {
                    pt.accepted -> "ПОДТВЕРДИЛ (" + pt.verdictAt + ")"
                    pt.rejected -> "ОТКЛОНИЛ: не про него (" + pt.verdictAt + ")"
                    else -> "не смотрел"
                }
                append(pt.text.replace(';', ',')).append(";")
                    .append(pt.firstSeen).append(";")
                    .append(pt.lastSeen).append(";")
                    .append(pt.times).append(";")
                    .append(pt.confidence.ifBlank { "—" }).append(";")
                    .append(verdict).append("\n")
            }
            append("</known_patterns>\n\n")
        }
        // Главные наблюдения прошлых разборов: первые строки текста, без
        // машинного хвоста. Нужны, чтобы не пересказывать себя же.
        val history = reports.reportsFlow.value.filter { it.ready }.take(3)
        if (history.isEmpty()) return
        append("<history>\n")
        append("Прошлые разборы — начала, чтобы не повторяться дословно.\n")
        for (h in history) {
            val head = h.text.lineSequence()
                .map { it.trim() }
                .filter { it.isNotBlank() && !it.startsWith("#") && !it.startsWith("##") }
                .take(3)
                .joinToString(" ")
                .take(400)
            append("- ").append(h.mode).append(" ").append(h.from)
            if (h.to != h.from) append("—").append(h.to)
            append(": ").append(head).append("\n")
        }
        append("</history>\n\n")
    }

    /**
     * КАРТА ВКЛАДОК. Владелец просил разбор ещё и по своим программам —
     * отдельно Правка, Итоги, Засечка, Дело, Тело (С), Тело (Е). Названия и
     * границы направлений задаём здесь, а не оставляем модели: иначе каждый
     * разбор придумает свою нарезку и сравнить их между собой будет нельзя.
     */
    private fun StringBuilder.appendModules() {
        append("<modules>\n")
        append("Вкладки приложения — это его собственная нарезка жизни. ")
        append("Разбор по направлениям делай ИМЕННО В ЭТИХ границах и под ")
        append("этими именами.\n")
        append("вкладка | про что | откуда данные\n")
        append("Правка | диктовки и тексты: сколько наговорено, чем ")
            .append("пользовался, где спотыкалось | <app>\n")
        append("Итоги | сам разбор: подтверждаются ли прошлые находки, ")
            .append("отклонял ли он их, читает ли он это вообще | ")
            .append("<known_patterns>, <history>\n")
        append("Засечка | таймшит суток: структура, покрытие, дыры, ")
            .append("триггеры, сон | <aggregates>, <recent>, <holes>, ")
            .append("<sleep>, <triggers>, <timeline>\n")
        append("Дело | работа и дела: чистое рабочее время, чем ")
            .append("инициировано, что просрочено | <tasks>, <aggregates>, ")
            .append("<timeline>\n")
        append("Тело (С) | спорт: план против факта, объём, ")
            .append("самочувствие, форма, зарядка | <training>, <plan>, ")
            .append("<health>\n")
        append("Тело (Е) | еда: калораж, белок, распределение по суткам, ")
            .append("пропуски логирования | <nutrition>\n")
        append("</modules>\n\n")
    }

    private fun StringBuilder.appendTimeline(
        mode: String,
        entries: List<ZasechkaStore.Entry>,
        now: Long,
        to: Long,
    ) {
        append("<timeline>\n")
        append("Полный лог. Формат: дата;время;длительность;категория;название;заметка\n")
        // В deep полный лог не поместится: последние семь суток целиком, за
        // остальное — только строки с заметками. Заметки важнее строк.
        val fullFrom = if (mode == "deep") to - 7 * 86_400_000L else 0L
        for (e in entries) {
            val note = e.raw.trim().replace('\n', ' ')
            if (e.start < fullFrom && note.length <= 20) continue
            append(dayKey(e.start)).append(";")
                .append(hm.format(Date(e.start))).append(";")
                .append(e.durationMin(now)).append(";")
                .append(e.category.ifBlank { "—" }).append(";")
                .append(e.title.ifBlank { "—" }).append(";")
                .append(note.take(200)).append("\n")
        }
        append("</timeline>\n")
    }

    // ---- мелочи ----

    private fun minutesOf(
        entries: List<ZasechkaStore.Entry>,
        category: String,
        date: String,
        now: Long,
    ): Long {
        val key = category.trim().lowercase()
        return entries
            .filter { it.category.trim().ifBlank { "без категории" }.lowercase() == key }
            .sumOf { minutesIn(it, date, now) }
    }

    /** Минуты группы таксономии за сутки — так считаются колонки <recent>. */
    private fun groupMinutes(
        entries: List<ZasechkaStore.Entry>,
        group: String,
        date: String,
        now: Long,
    ): Long = entries
        .filter { groupOf(it.category) == group }
        .sumOf { minutesIn(it, date, now) }

    private fun median(values: List<Double>): Double {
        if (values.isEmpty()) return 0.0
        val sorted = values.sorted()
        val mid = sorted.size / 2
        return if (sorted.size % 2 == 1) sorted[mid] else (sorted[mid - 1] + sorted[mid]) / 2
    }

    private fun weekday(date: String): String {
        val cal = Calendar.getInstance().apply { timeInMillis = parseDate(date) }
        return WEEKDAYS[cal.get(Calendar.DAY_OF_WEEK) - 1]
    }

    private fun minutesIn(e: ZasechkaStore.Entry, date: String, now: Long): Long {
        val dayStart = dayStartMs(parseDate(date))
        return e.durationMsIn(dayStart, dayStart + 86_400_000L, now) / 60_000L
    }

    private fun parseDate(date: String): Long =
        runCatching { ymd.parse(date)?.time }.getOrNull() ?: System.currentTimeMillis()

    private fun pearson(x: List<Double>, y: List<Double>): Double? {
        if (x.size != y.size || x.size < 4) return null
        val mx = x.average()
        val my = y.average()
        var num = 0.0
        var dx = 0.0
        var dy = 0.0
        for (i in x.indices) {
            val a = x[i] - mx
            val b = y[i] - my
            num += a * b
            dx += a * a
            dy += b * b
        }
        if (dx < 1e-9 || dy < 1e-9) return null
        return num / sqrt(dx * dy)
    }

    private fun pct(share: Double): String = fmt2(share * 100) + "%"

    private fun fmt2(v: Double): String =
        if (abs(v) >= 100) v.roundToInt().toString()
        else String.format(Locale.US, "%.2f", v)

    private fun hashOf(text: String): String = runCatching {
        java.security.MessageDigest.getInstance("MD5")
            .digest(text.toByteArray())
            .joinToString("") { "%02x".format(it) }
            .take(12)
    }.getOrDefault(text.hashCode().toString(16))

    /** «Вчера» и «прошлая неделя» — в датах, как их ждёт build(). */
    fun yesterday(): String = dayKey(System.currentTimeMillis() - 86_400_000L)

    fun weekAgo(days: Int = 7): Pair<String, String> {
        val cal = Calendar.getInstance()
        cal.timeInMillis = System.currentTimeMillis() - 86_400_000L
        val to = dayKey(cal.timeInMillis)
        val from = dayKey(cal.timeInMillis - (days - 1) * 86_400_000L)
        return from to to
    }
}
