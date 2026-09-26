package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.temporal.ChronoUnit
import kotlin.math.abs

// Статистика вкладки «Деньги» (владелец, 23.09.2026: «вкладка выглядит очень
// простой, ей требуется статистика… по месяцу, помимо недели… графики»). Всё
// считается кодом из журнала; модель здесь не участвует. Что обычно есть у
// таких экранов и что здесь сделано: период неделя/месяц с шагом назад,
// «ушло / пришло / сальдо» и сравнение с прошлым периодом, среднее в день и
// прогноз месяца по темпу, траты по дням, тренд за полгода, категории с
// разбивкой по магазинам, крупнейшие траты и регулярные платежи.
//
// Файл без Android: проверяют JVM-тесты.
object MoneyStats {

    enum class Kind { WEEK, MONTH }

    /** Период: [from] включительно, [to] — исключительно, время по Москве. */
    data class Period(val kind: Kind, val from: Long, val to: Long, val firstDay: LocalDate, val days: Int) {
        fun prev(): Period = of(kind, if (kind == Kind.WEEK) firstDay.minusDays(7) else firstDay.minusMonths(1))
        fun next(): Period = of(kind, if (kind == Kind.WEEK) firstDay.plusDays(7) else firstDay.plusMonths(1))
    }

    private val MSK = BankStatements.MSK

    fun startOf(day: LocalDate): Long = day.atStartOfDay(MSK).toInstant().toEpochMilli()

    fun dayOf(ts: Long): LocalDate = Instant.ofEpochMilli(ts).atZone(MSK).toLocalDate()

    /** Период, в который попадает [day]: неделя с понедельника или календарный месяц. */
    fun of(kind: Kind, day: LocalDate): Period = when (kind) {
        Kind.WEEK -> {
            val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
            Period(kind, startOf(monday), startOf(monday.plusDays(7)), monday, 7)
        }
        Kind.MONTH -> {
            val first = day.withDayOfMonth(1)
            val len = YearMonth.from(first).lengthOfMonth()
            Period(kind, startOf(first), startOf(first.plusMonths(1)), first, len)
        }
    }

    /** Считается ли запись тратой/доходом в итогах при кнопках «Личное · ЗФ» (по назначению). */
    private fun counted(e: MoneyEntry, scope: MoneyScope) =
        e.live() && e.category.isNotBlank() && scope.countsStat(e)

    private fun isIncome(e: MoneyEntry) = MoneyCategories.of(e.category)?.income == true

    /** Траты периода: учтённые категории и неразложенные расходы (они тоже ушли). */
    fun spending(entries: List<MoneyEntry>, p: Period, scope: MoneyScope): List<MoneyEntry> =
        entries.filter { e ->
            e.ts >= p.from && e.ts < p.to && e.live() && e.rubKop < 0 &&
                ((counted(e, scope) && !isIncome(e)) || (e.category.isBlank() && scope.countsStat(e)))
        }

    fun income(entries: List<MoneyEntry>, p: Period, scope: MoneyScope): List<MoneyEntry> =
        entries.filter { e -> e.ts >= p.from && e.ts < p.to && counted(e, scope) && isIncome(e) }

    /** Итоги периода и того же периода раньше: для «+12 % к прошлой неделе». */
    data class Totals(val spentKop: Long, val incomeKop: Long, val prevSpentKop: Long, val prevIncomeKop: Long, val count: Int) {
        /** Изменение трат к прошлому периоду, доля; null — сравнивать не с чем. */
        val spentDelta: Double? get() = if (prevSpentKop == 0L) null else (spentKop - prevSpentKop).toDouble() / prevSpentKop
        val balanceKop: Long get() = incomeKop - spentKop
    }

    fun totals(entries: List<MoneyEntry>, p: Period, scope: MoneyScope): Totals {
        val prev = p.prev()
        return Totals(
            spentKop = -spending(entries, p, scope).sumOf { it.rubKop },
            incomeKop = income(entries, p, scope).sumOf { it.rubKop },
            prevSpentKop = -spending(entries, prev, scope).sumOf { it.rubKop },
            prevIncomeKop = income(entries, prev, scope).sumOf { it.rubKop },
            count = spending(entries, p, scope).size,
        )
    }

    /** Траты по дням периода, рубли (положительные): для столбиков. */
    fun daily(entries: List<MoneyEntry>, p: Period, scope: MoneyScope): List<Long> {
        val out = LongArray(p.days)
        for (e in spending(entries, p, scope)) {
            val i = ChronoUnit.DAYS.between(p.firstDay, dayOf(e.ts)).toInt()
            if (i in out.indices) out[i] += -e.rubKop
        }
        return out.toList()
    }

    /** Средний день и прогноз месяца по темпу: сколько уйдёт до конца, если тратить так же. */
    data class Pace(val perDayKop: Long, val daysPassed: Int, val forecastKop: Long?)

    fun pace(entries: List<MoneyEntry>, p: Period, scope: MoneyScope, today: LocalDate): Pace {
        val spent = -spending(entries, p, scope).sumOf { it.rubKop }
        val passed = when {
            today < p.firstDay -> 0
            today >= p.firstDay.plusDays(p.days.toLong()) -> p.days
            else -> ChronoUnit.DAYS.between(p.firstDay, today).toInt() + 1
        }
        val perDay = if (passed > 0) spent / passed else 0L
        // Прогноз — только для идущего периода: у прошлого он и так известен.
        val forecast = if (passed in 1 until p.days) perDay * p.days else null
        return Pace(perDay, passed, forecast)
    }

    /** Один месяц тренда: сколько ушло и пришло. */
    data class Month(val month: YearMonth, val spentKop: Long, val incomeKop: Long)

    fun trend(entries: List<MoneyEntry>, today: LocalDate, months: Int, scope: MoneyScope): List<Month> =
        (months - 1 downTo 0).map { back ->
            val first = today.withDayOfMonth(1).minusMonths(back.toLong())
            val p = of(Kind.MONTH, first)
            Month(YearMonth.from(first), -spending(entries, p, scope).sumOf { it.rubKop }, income(entries, p, scope).sumOf { it.rubKop })
        }

    /** Категория периода: сумма, число трат, доля и разбивка по магазинам/получателям. */
    data class Category(
        val key: String,
        val kop: Long,
        val count: Int,
        val prevKop: Long,
        val merchants: List<Merchant>,
    )

    data class Merchant(val name: String, val kop: Long, val count: Int)

    fun categories(entries: List<MoneyEntry>, p: Period, scope: MoneyScope): List<Category> {
        val prev = spending(entries, p.prev(), scope).groupBy { it.category }
        return spending(entries, p, scope).groupBy { it.category }
            .map { (key, list) ->
                Category(
                    key = key,
                    kop = -list.sumOf { it.rubKop },
                    count = list.size,
                    prevKop = -(prev[key]?.sumOf { it.rubKop } ?: 0L),
                    merchants = list.groupBy { MoneyMerchants.canonical(it.what) }
                        .map { (name, l) -> Merchant(name, -l.sumOf { it.rubKop }, l.size) }
                        .sortedByDescending { it.kop },
                )
            }
            .sortedByDescending { it.kop }
    }

    /** Группы категорий (Дети, Дом, Здоровье, Жизнь, ЗФ) — для доната: пять цветов держатся за группой. */
    fun groups(cats: List<Category>): List<Pair<String, Long>> =
        cats.groupBy { MoneyCategories.of(it.key)?.group ?: "Без категории" }
            .map { (g, l) -> g to l.sumOf { it.kop } }
            .sortedByDescending { it.second }

    fun biggest(entries: List<MoneyEntry>, p: Period, scope: MoneyScope, n: Int = 5): List<MoneyEntry> =
        spending(entries, p, scope).sortedBy { it.rubKop }.take(n)

    /**
     * Регулярные платежи: один получатель в трёх и более разных месяцах из
     * последних шести, суммы похожи (разброс не больше трети от средней).
     * Подписки, кружки, няня, ЖКХ — то, что уходит само, и о чём забывают.
     */
    data class Recurring(val name: String, val avgKop: Long, val months: Int, val lastTs: Long, val category: String)

    fun recurring(entries: List<MoneyEntry>, today: LocalDate, scope: MoneyScope): List<Recurring> {
        val from = startOf(today.withDayOfMonth(1).minusMonths(5))
        val pool = entries.filter { e ->
            e.ts >= from && e.live() && e.rubKop < 0 && e.category.isNotBlank() &&
                scope.countsStat(e) && !isIncome(e)
        }
        return pool.groupBy { MoneyMerchants.canonical(it.what) }
            .mapNotNull { (name, list) ->
                val byMonth = list.groupBy { YearMonth.from(dayOf(it.ts)) }.mapValues { (_, l) -> -l.sumOf { it.rubKop } }
                if (byMonth.size < 3) return@mapNotNull null
                val avg = byMonth.values.average()
                val spread = byMonth.values.maxOf { abs(it - avg) }
                if (avg <= 0 || spread > avg / 3) return@mapNotNull null
                Recurring(name, avg.toLong(), byMonth.size, list.maxOf { it.ts }, list.groupingBy { it.category }.eachCount().maxBy { it.value }.key)
            }
            .sortedByDescending { it.avgKop }
    }
}

/**
 * Одно имя магазина вместо десяти строк банка: «VV_KKM 1183.4», «VV 1183_3»,
 * «vkusvill», «ВкусВилл» — это ВкусВилл. Для разбивки категории по магазинам
 * и для регулярных платежей: иначе «Продукты» развалились бы на двадцать
 * касс одного магазина.
 */
object MoneyMerchants {
    private val BRANDS: List<Pair<Regex, String>> = listOf(
        "вкусвилл|vkusvill|\\bvv[ _]" to "ВкусВилл",
        "азбука|azbuka|ab daily|av azbuka" to "Азбука Вкуса",
        "лавка|lavka" to "Яндекс Лавка",
        "перекр[её]ст|perekrest|vprok" to "Перекрёсток",
        "яндекс такси|yandex go|yandex\\.taxi|яндекс go" to "Яндекс Такси",
        "яндекс еда|yandex eda" to "Яндекс Еда",
        "самокат" to "Самокаты",
        "ozon|озон" to "Ozon",
        "wildberries|вайлдберриз" to "Wildberries",
        "anthropic|claude" to "Anthropic",
        "google\\s*\\*?\\s*workspace|workspace_znako" to "Google Workspace",
        "google\\s*\\*?\\s*google play|google play" to "Google Play",
        "zoom\\.com" to "Zoom",
        "кофемания|kofemaniya" to "Кофемания",
        "surf coffee" to "Surf Coffee",
        "додо|dodo" to "Додо Пицца",
        "аптека 36|36,6" to "Аптека 36,6",
        "золотое яблоко|gold apple" to "Золотое яблоко",
        "maruga|маруга" to "Маруга",
        "ovoshhi|овощи|frukty|фрукты" to "Овощи и фрукты",
        "братья караваевы|bratya karavaevy" to "Братья Караваевы",
        "mcba|atm|банкомат|выдача наличных|снятие" to "Банкомат",
    ).map { (re, name) -> Regex(re, RegexOption.IGNORE_CASE) to name }

    // Двадцать регулярок на запись — дорого, а названий в журнале в разы меньше, чем записей.
    private val memo = java.util.concurrent.ConcurrentHashMap<String, String>()
    private val TAIL_NUMBER = Regex("[\\s_#№-]*\\d[\\d_.\\s-]*$")

    fun canonical(what: String): String = memo.getOrPut(what) { compute(what) }

    private fun compute(what: String): String {
        val w = what.trim()
        BRANDS.firstOrNull { it.first.containsMatchIn(w) }?.let { return it.second }
        // Номер кассы и магазина в хвосте — не часть имени: «MAGAZIN 382» и «MAGAZIN 17» — одна сеть.
        return w.replace(TAIL_NUMBER, "").trim().ifEmpty { w }
    }
}
