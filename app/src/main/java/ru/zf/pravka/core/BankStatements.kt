package ru.zf.pravka.core

import java.security.MessageDigest
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

// Выписки банков → записи журнала денег. Разбор — КОДОМ, не моделью: цифры из
// банка должны доходить до журнала до копейки, а модель на тысяче строк рано
// или поздно «округлит». Модель потом только раскладывает и спрашивает.
//
// Форматы — те, что владелец реально выгружает (23.09.2026):
//  - Тиньков (Т-Банк): CSV из веб-версии, «;», запятая в дробях, дата со
//    временем до секунды, сумма в валюте счёта уже со знаком;
//  - Альфа (Марианна): CSV, «,», точка в дробях, только день, сумма всегда
//    положительная — направление в колонке «Тип», перед магазином город
//    («MOSCOW\MARUGA»);
//  - МКБ (Марианна): только .xlsx (читает `XlsxRead`), все ячейки — строки,
//    сумма со знаком, дата в двух видах («22.09.2026 21:23:36» и
//    «11.09.2026, 12:48»), описание в двух видах («CITY\\адрес*МАГАЗИН*…»
//    и «RUS\\CITY\\МАГАЗИН,адрес,…»), внизу строки итогов «Доход в RUB:»;
//  - «Плати по миру»: не файл, а текст чата бота — см. [PlatiChat].
//
// Колонки ищутся по названию, не по номеру: банк переставит колонку — разбор
// не поедет молча в соседнюю.
object BankStatements {

    val MSK: ZoneId = ZoneId.of("Europe/Moscow")

    data class Parsed(
        val entries: List<MoneyEntry>,
        /** Пропущено строк и почему — «ошибка» у Тинькова, «отменён» у Альфы. Молча не теряем. */
        val skipped: Int = 0,
        val skippedWhy: String = "",
    )

    /** Что за файл: по шапке, а не по имени — имя у выписки меняется каждый раз. */
    enum class Kind { TINKOFF, ALFA, MKB, UNKNOWN }

    fun detect(text: String): Kind {
        val head = text.trimStart('\uFEFF').lineSequence().firstOrNull().orEmpty()
        return when {
            head.contains("Сумма в валюте счёта") && head.contains("Дата операции") -> Kind.TINKOFF
            head.contains("operationDate") && head.contains("merchant") -> Kind.ALFA
            else -> Kind.UNKNOWN
        }
    }

    // ---- Тиньков ----

    fun tinkoff(text: String, owner: String = "sasha"): Parsed {
        val rows = Csv.parse(text.trimStart('\uFEFF'), ';')
        if (rows.isEmpty()) return Parsed(emptyList())
        val h = Csv.header(rows.first())
        fun col(r: List<String>, name: String): String = h[name]?.let { r.getOrNull(it) }.orEmpty().trim()
        require("Дата операции" in h && "Сумма в валюте счёта" in h) {
            "Это не выписка Тинькова: нет колонок «Дата операции» и «Сумма в валюте счёта»"
        }
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss")
        val out = mutableListOf<MoneyEntry>()
        val seen = HashMap<String, Int>()
        var skipped = 0
        for (r in rows.drop(1)) {
            if (r.all { it.isBlank() }) continue
            val status = col(r, "Статус")
            if (status.isNotEmpty() && status != "Ок") { skipped++; continue }
            val ts = runCatching {
                LocalDateTime.parse(col(r, "Дата операции"), fmt).atZone(MSK).toInstant().toEpochMilli()
            }.getOrNull() ?: run { skipped++; continue }
            val rub = MoneyFormat.parseKop(col(r, "Сумма в валюте счёта")) ?: run { skipped++; continue }
            val orig = MoneyFormat.parseKop(col(r, "Сумма операции")) ?: rub
            val currency = col(r, "Валюта операции").ifBlank { "RUB" }.let { if (it == "RUR") "RUB" else it }
            val what = col(r, "Описание")
            val card = col(r, "Номер карты")
            val account = listOf(col(r, "Имя счёта"), card).filter { it.isNotBlank() }.joinToString(" ")
            val base = listOf("t", owner, col(r, "Дата операции"), rub.toString(), what, card).joinToString("|")
            out.add(
                MoneyEntry(
                    id = stableId(base, seen),
                    owner = owner,
                    source = MoneyEntry.Source.TINKOFF,
                    ts = ts,
                    timeKnown = true,
                    rubKop = rub,
                    // Сумма операции у Тинькова без знака для части строк — знак берём у рублей.
                    origMinor = if (rub < 0) -kotlin.math.abs(orig) else kotlin.math.abs(orig),
                    currency = currency,
                    rubBasis = MoneyEntry.RubBasis.BANK,
                    what = what,
                    note = col(r, "Сообщение"),
                    mcc = col(r, "MCC"),
                    bankCategory = col(r, "Ваша категория").ifBlank { col(r, "Категория по-умолчанию") },
                    account = account,
                )
            )
        }
        return Parsed(out, skipped, if (skipped > 0) "не «Ок» или без даты/суммы" else "")
    }

    // ---- Альфа ----

    fun alfa(text: String, owner: String = "marianna"): Parsed {
        val rows = Csv.parse(text.trimStart('\uFEFF'), ',')
        if (rows.isEmpty()) return Parsed(emptyList())
        val h = Csv.header(rows.first())
        fun col(r: List<String>, name: String): String = h[name]?.let { r.getOrNull(it) }.orEmpty().trim()
        require("operationDate" in h && "amount" in h && "type" in h) {
            "Это не выписка Альфы: нет колонок operationDate, amount, type"
        }
        val fmt = DateTimeFormatter.ofPattern("dd.MM.yyyy")
        val out = mutableListOf<MoneyEntry>()
        val seen = HashMap<String, Int>()
        var skipped = 0
        for (r in rows.drop(1)) {
            if (r.all { it.isBlank() }) continue
            // Пустой статус у поступлений — норма Альфы, не ошибка.
            val status = col(r, "status")
            if (status.isNotEmpty() && status != "Выполнен") { skipped++; continue }
            val day = runCatching { LocalDate.parse(col(r, "operationDate"), fmt) }.getOrNull()
                ?: run { skipped++; continue }
            // Времени у Альфы нет: полдень, чтобы сдвиг зоны не унёс день.
            val ts = day.atTime(12, 0).atZone(MSK).toInstant().toEpochMilli()
            val amount = MoneyFormat.parseKop(col(r, "amount"))?.let { kotlin.math.abs(it) }
                ?: run { skipped++; continue }
            val rub = when (col(r, "type")) {
                "Списание" -> -amount
                "Пополнение" -> amount
                else -> { skipped++; continue }
            }
            val currency = col(r, "currency").ifBlank { "RUB" }.let { if (it == "RUR") "RUB" else it }
            val what = stripCity(col(r, "merchant"))
            val card = col(r, "cardNumber").takeLast(4)
            val account = if (card.isNotBlank()) "${col(r, "cardName")} *$card".trim()
            else col(r, "accountName")
            val base = listOf("a", owner, col(r, "operationDate"), rub.toString(), what, col(r, "comment")).joinToString("|")
            out.add(
                MoneyEntry(
                    id = stableId(base, seen),
                    owner = owner,
                    source = MoneyEntry.Source.ALFA,
                    ts = ts,
                    timeKnown = false,
                    rubKop = rub,
                    origMinor = rub,
                    currency = currency,
                    what = what,
                    note = col(r, "comment"),
                    mcc = col(r, "mcc"),
                    bankCategory = col(r, "category"),
                    account = account,
                )
            )
        }
        return Parsed(out, skipped, if (skipped > 0) "не «Выполнен» или без даты/суммы" else "")
    }

    // ---- МКБ ----

    /** Строки листа МКБ по шапке: «Дата транзакции», «Содержание операции». */
    fun isMkb(rows: List<List<String>>): Boolean =
        rows.firstOrNull()?.let { h -> h.any { it.trim() == "Дата транзакции" } && h.any { it.trim() == "Содержание операции" } } == true

    fun mkb(rows: List<List<String>>, owner: String = "marianna"): Parsed {
        if (rows.isEmpty()) return Parsed(emptyList())
        val h = Csv.header(rows.first())
        fun col(r: List<String>, name: String): String = h[name]?.let { r.getOrNull(it) }.orEmpty().trim()
        require("Дата транзакции" in h && "Сумма в валюте счета" in h) {
            "Это не выписка МКБ: нет колонок «Дата транзакции» и «Сумма в валюте счета»"
        }
        // Час бывает без ведущего нуля («27.06.2026 0:28:33») — «H», а не «HH»:
        // на настоящей выписке так потерялись восемь переводов.
        val full = DateTimeFormatter.ofPattern("dd.MM.yyyy H:mm:ss")
        val short = DateTimeFormatter.ofPattern("dd.MM.yyyy, H:mm")
        val out = mutableListOf<MoneyEntry>()
        val seen = HashMap<String, Int>()
        var skipped = 0
        for (r in rows.drop(1)) {
            if (r.all { it.isBlank() }) continue
            val rawDate = col(r, "Дата транзакции")
            // Итоги банка внизу листа («Доход в RUB:») — не операции.
            if (rawDate.endsWith(":")) continue
            val ts = (runCatching { LocalDateTime.parse(rawDate, full) }.getOrNull()
                ?: runCatching { LocalDateTime.parse(rawDate, short) }.getOrNull())
                ?.atZone(MSK)?.toInstant()?.toEpochMilli()
                ?: run { skipped++; continue }
            val rub = MoneyFormat.parseKop(col(r, "Сумма в валюте счета")) ?: run { skipped++; continue }
            val orig = MoneyFormat.parseKop(col(r, "Сумма в валюте операции")) ?: rub
            val currency = col(r, "Валюта операции").ifBlank { "RUB" }.let { if (it == "RUR") "RUB" else it }
            val (what, kind) = mkbMerchant(col(r, "Содержание операции"))
            val base = listOf("m", owner, rawDate, rub.toString(), col(r, "Содержание операции"), col(r, "Код авторизации")).joinToString("|")
            out.add(
                MoneyEntry(
                    id = stableId(base, seen),
                    owner = owner,
                    source = MoneyEntry.Source.MKB,
                    ts = ts,
                    timeKnown = true,
                    rubKop = rub,
                    origMinor = orig,
                    currency = currency,
                    what = what,
                    // «Оплата товаров и услуг» у каждой второй строки — шум; остальное говорит.
                    note = kind.takeIf { !it.startsWith("Оплата товаров") }.orEmpty(),
                    mcc = col(r, "MCC"),
                    bankCategory = col(r, "Категория"),
                    account = "МКБ",
                )
            )
        }
        return Parsed(out, skipped, if (skipped > 0) "без даты/суммы" else "")
    }

    private val OP_KINDS = listOf("Оплата товаров и услуг", "Выдача наличных", "Возврат")
    private val SERVICE_PREFIX = Regex("^(//[^/]*)+//")

    /**
     * Описание МКБ → (получатель, вид операции). Два вида строк:
     * «MOSCOW\\BYSTR …*BRATYA KARAVAEVY*MOSCOW*RUSSIAN FEDERATION\\Оплата товаров
     * и услуг» (магазин — второе поле между «*») и
     * «RUS\\MOSCOW\\AB DAILY,STR PRECHISTENKA 24/1,MOSCOW,RU» (магазин — до
     * первой запятой). Служебный хвост «//ВЗС//0-00//» у зарплат срезается.
     */
    fun mkbMerchant(raw: String): Pair<String, String> {
        val pieces = raw.split('\\').map { it.trim() }.filter { it.isNotEmpty() }.toMutableList()
        var kind = ""
        if (pieces.size > 1 && OP_KINDS.any { pieces.last().startsWith(it) }) kind = pieces.removeAt(pieces.lastIndex)
        var body = pieces.lastOrNull() ?: raw.trim()
        body = body.replace(SERVICE_PREFIX, "").trim()
        val star = body.split('*').map { it.trim() }.filter { it.isNotEmpty() }
        val name = when {
            star.size >= 3 -> star[1]
            star.size == 2 -> star[0]
            body.count { it == ',' } >= 2 -> body.substringBefore(',').trim()
            else -> body
        }
        return name to kind
    }

    /** «MOSCOW\MARUGA» → «MARUGA»: иначе Маруга с Альфы и с Тинькова — два разных получателя. */
    fun stripCity(merchant: String): String {
        val i = merchant.indexOf('\\')
        return (if (i >= 0) merchant.substring(i + 1) else merchant).trim().replace(Regex("\\s{2,}"), " ")
    }

    /**
     * Постоянный номер строки выписки: хеш её сути. Две строки, одинаковые
     * до буквы (два кофе в одну секунду), различает счётчик повторов внутри
     * файла — та же выписка второй раз даст те же номера.
     */
    fun stableId(base: String, seen: MutableMap<String, Int>): String {
        val n = seen.merge(base, 1, Int::plus) ?: 1
        val key = if (n == 1) base else "$base#$n"
        val digest = MessageDigest.getInstance("SHA-1").digest(key.toByteArray(Charsets.UTF_8))
        return base.take(1) + ":" + digest.take(10).joinToString("") { "%02x".format(it) }
    }
}

/** Разбор CSV с кавычками: «""» внутри поля — кавычка, перенос строки внутри кавычек — часть поля. */
object Csv {
    fun parse(text: String, delimiter: Char): List<List<String>> {
        val rows = mutableListOf<List<String>>()
        var row = mutableListOf<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (quoted) {
                if (c == '"') {
                    if (i + 1 < text.length && text[i + 1] == '"') { field.append('"'); i++ } else quoted = false
                } else field.append(c)
            } else when (c) {
                '"' -> quoted = true
                delimiter -> { row.add(field.toString()); field.clear() }
                '\r' -> Unit
                '\n' -> { row.add(field.toString()); field.clear(); rows.add(row); row = mutableListOf() }
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) { row.add(field.toString()); rows.add(row) }
        return rows
    }

    fun header(row: List<String>): Map<String, Int> =
        row.mapIndexed { i, name -> name.trim().trimStart('\uFEFF') to i }.toMap()
}
