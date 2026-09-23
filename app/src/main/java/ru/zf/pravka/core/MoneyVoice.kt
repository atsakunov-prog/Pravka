package ru.zf.pravka.core

import java.time.LocalDate
import kotlin.math.abs

// Голос → записи журнала. Модель разбирает наговор (`provider/ClaudeMoney.kt`),
// а здесь — то, что не доверяется модели: вторая проверка СУММЫ и перевод в
// рубли. Владелец (23.09.2026): «нужно внимательно смотреть, чтобы он
// правильно понимал мои суммы». Модель сама пишет сомнение, когда смысл не
// решает; код добавляет своё, когда сказанное и записанное расходятся цифрами
// или сумма не по размеру траты.
object MoneyVoice {

    /** Одна трата из ответа модели — до перевода в рубли. */
    data class Item(
        val what: String,
        /** Сумма в валюте траты, минорные единицы (копейки, центы), положительная. */
        val minor: Long,
        val currency: String,
        /** Слова, в которых прозвучала сумма. */
        val heard: String,
        val income: Boolean,
        val category: String,
        val who: String,
        val cash: Boolean,
        /** ГГГГ-ММ-ДД; пусто — сегодня. */
        val date: String,
        val doubt: String,
    )

    /**
     * Сколько «много» для повседневной траты: кофе за 38 000 — почти
     * наверняка «380» с лишними нулями, а не праздник.
     */
    private val EVERYDAY = mapOf(
        "cafe" to 30_000_00L, "groceries" to 60_000_00L, "transport" to 30_000_00L,
        "subs_home" to 20_000_00L, "gifts" to 100_000_00L,
    )

    /**
     * Вторая проверка суммы, кодом. Возвращает сомнение для плашки или пустую
     * строку. Сомнение модели не заменяется, а дополняется.
     */
    fun sanity(item: Item, rubKop: Long): String {
        val notes = mutableListOf<String>()
        if (item.doubt.isNotBlank()) notes.add(item.doubt.trim())
        // Цифры в услышанном против цифр в записанном: «2 200» и 2200 — одно,
        // «220» и 2200 — нет. Слова с множителем («8к», «две тысячи», «полтора
        // ляма») сюда не идут: там цифры и не должны совпадать.
        val heardDigits = item.heard.filter { it.isDigit() }
        // Граница слова — вручную: «\b» в Java считает «к» не буквой.
        val multiplier = Regex("(?i)(\\d\\s*к(?![а-яё])|тыс|тыщ|косар|лям|млн|миллион|сотк|сотен|(?<![а-яё])сот(?![а-яё]))").containsMatchIn(item.heard)
        if (heardDigits.isNotEmpty() && !multiplier) {
            val whole = (item.minor / 100).toString()
            val withKop = whole + (item.minor % 100).toString().padStart(2, '0')
            if (heardDigits != whole && heardDigits != withKop) {
                notes.add("услышано «${item.heard.trim()}», записано ${MoneyFormat.orig(item.minor, item.currency)}")
            }
        }
        EVERYDAY[item.category]?.let { limit ->
            if (abs(rubKop) > limit) {
                notes.add("для «${MoneyCategories.title(item.category).lowercase()}» это много — проверь нули")
            }
        }
        return notes.distinct().joinToString("; ")
    }

    /** Курс валюты к рублю для надиктованного: предварительный (ЦБ или прикидка), пока сверка не найдёт банк. */
    fun interface Rates {
        fun rubPer(currency: String, day: LocalDate): Double?
    }

    /** Прикидка, если курса ЦБ под рукой нет: запись всё равно помечена «предварительно». */
    val ROUGH: Map<String, Double> = mapOf("USD" to 90.0, "EUR" to 100.0, "AMD" to 0.23, "BYN" to 28.0)

    /**
     * Разобранное → черновики журнала. Черновик до «ОК» не идёт ни в итоги,
     * ни в сверку; сырой наговор ([takeId]) хранится отдельно и не удаляется.
     */
    fun toDrafts(
        items: List<Item>,
        takeId: Long,
        owner: String,
        today: LocalDate,
        noonTs: (LocalDate) -> Long,
        takeTs: Long,
        rates: Rates,
    ): List<MoneyEntry> = items.mapIndexedNotNull { i, it ->
        if (it.minor <= 0) return@mapIndexedNotNull null
        val day = runCatching { LocalDate.parse(it.date) }.getOrNull() ?: today
        val currency = it.currency.uppercase().ifBlank { "RUB" }.let { c -> if (c == "RUR") "RUB" else c }
        val (rub, basis) = if (currency == "RUB") {
            it.minor to MoneyEntry.RubBasis.SAID
        } else {
            val r = rates.rubPer(currency, day) ?: ROUGH[currency] ?: 1.0
            Math.round(it.minor * r) to MoneyEntry.RubBasis.CBR_PRELIM
        }
        val signed = if (it.income) rub else -rub
        MoneyEntry(
            id = "v:$takeId:${i + 1}",
            owner = owner,
            source = MoneyEntry.Source.VOICE,
            // Сегодняшняя трата — время тейка; вчерашняя — полдень того дня.
            ts = if (day == today) takeTs else noonTs(day),
            timeKnown = day == today,
            rubKop = signed,
            origMinor = if (it.income) it.minor else -it.minor,
            currency = currency,
            rubBasis = basis,
            what = it.what,
            note = if (it.heard.isNotBlank()) "сказано: ${it.heard.trim()}" else "",
            account = if (it.cash) MoneyEntry.CASH else "",
            category = MoneyCategories.of(it.category)?.key ?: "other",
            who = MoneyCategories.WHO.firstOrNull { w -> w.first == it.who }?.first.orEmpty(),
            categoryBy = MoneyEntry.CategoryBy.MODEL,
            draft = true,
            takeId = takeId,
            doubt = sanity(it, signed),
        )
    }
}
