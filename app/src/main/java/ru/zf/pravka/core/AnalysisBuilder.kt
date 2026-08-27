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
) {

    companion object {
        private const val BASELINE_DAYS = 28
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
            appendHoles(entries, now)
            appendSleep(dates, entries, now)
            appendHealth(dates)
            appendNutrition(dates)
            appendTraining(from, to, now, dates)
            appendCorrelations(entries, dates, now, from, to)
            appendTriggers(entries, now)
            appendPlan(dates, now)
            appendDataQuality(entries, dates, now)
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
        val cats = (base + periodEntries).map { it.category.trim().ifBlank { "без категории" } }
            .distinctBy { it.lowercase() }
            .sorted()
        append("<baseline>\n")
        append("Часы в сутки за предыдущие ").append(BASELINE_DAYS)
            .append(" дн. против периода.\nкатегория;ч_в_сутки_база;ч_в_сутки_период;отклонение_%\n")
        for (cat in cats) {
            val key = cat.lowercase()
            val baseH = base.filter { it.category.trim().lowercase() == key }
                .sumOf { it.durationMsIn(baseFrom, baseTo, now) } / 3_600_000.0 / BASELINE_DAYS
            val periodH = periodEntries.filter { it.category.trim().lowercase() == key }
                .sumOf { it.durationMs(now) } / 3_600_000.0 / periodDays
            if (baseH < 0.02 && periodH < 0.02) continue
            val dev = if (baseH < 0.02) 999.0 else (periodH - baseH) / baseH * 100
            append(cat).append(";").append(fmt2(baseH)).append(";").append(fmt2(periodH))
                .append(";").append(if (dev >= 999) "нет базы" else dev.roundToInt().toString())
                .append("\n")
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
