package ru.zf.pravka.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

// «Плати по миру» — карта для зарубежных оплат (Anthropic, Zoom, Google
// Workspace, поездки). Выписки у сервиса нет: владелец копирует чат бота
// (23.09.2026: «эта выписка будет приходить вот так»). В чате пять видов
// сообщений, нужны три:
//
//   «Покупка: 24.25 €. Карта: *1234. GAMMA.APP. Остаток: 75.75 €.» — расход;
//   «Заявка на пополнение карты *1234 принята, ожидаем оплаты: 10000.00 ₽» и
//   «Пополнение карты *1234 на сумму 100.00 € прошло успешно» — деньги с
//     Тинькова и заодно курс;
//   «Списана комиссия €0.25.», «Осуществите оплату карты : 2990.00 ₽» — цена
//     самого сервиса.
//
// Остальное — шум, и он выбрасывается: отказы «недостаточно средств» (денег
// не списали), реклама, розыгрыши, рефералка и КОДЫ ПРИВЯЗКИ к Apple Pay /
// Google Pay — они в чате есть, их нельзя ни хранить, ни отправлять модели.
//
// Три вещи, найденные на настоящем чате:
//  - сумма заявки в рублях совпадает со списанием «Плати по миру» в Тинькове
//    ДО КОПЕЙКИ — это даёт и курс, и время, и отсев брошенных заявок (у тех
//    пары в Тинькове нет);
//  - до сентября у покупок нет даты, время стоит только у отказов; дата
//    выводится из соседей (своя строка времени или пополнение, склеенное с
//    Тиньковом);
//  - куски чата, вставленные внахлёст, повторяются — повтор узнаётся по
//    остатку: одна и та же покупка с тем же остатком дважды не бывает.
object PlatiChat {

    sealed class Event {
        abstract val card: String
        /** Порядок в чате: хронология, по которой выводятся даты. */
        abstract val seq: Int
        /** Своё время из строки «dd.MM.yyyy HH:mm» над сообщением, 0 — нет. */
        abstract val ts: Long

        data class Purchase(
            override val card: String, override val seq: Int, override val ts: Long,
            val minor: Long, val currency: String, val merchant: String, val balanceMinor: Long,
        ) : Event()

        data class TopUpRequest(
            override val card: String, override val seq: Int, override val ts: Long,
            val rubKop: Long,
        ) : Event()

        data class TopUp(
            override val card: String, override val seq: Int, override val ts: Long,
            val minor: Long, val currency: String,
        ) : Event()

        data class Fee(
            override val card: String, override val seq: Int, override val ts: Long,
            val minor: Long, val currency: String, val balanceMinor: Long,
        ) : Event()

        /** Выпуск карты: платится в рублях прямо с Тинькова. */
        data class IssueFee(
            override val card: String, override val seq: Int, override val ts: Long,
            val rubKop: Long,
        ) : Event()
    }

    // Число: «24.25», «10000.00», «-0.09». Не «[\d.,]+» — та схватила бы
    // точку конца фразы («комиссия €0.25.») вместе с числом.
    private const val N = """-?\d+(?:[.,]\d+)?"""
    private val TIME = Regex("""^(\d{2}\.\d{2}\.\d{4}) (\d{2}:\d{2})$""")
    private val PURCHASE = Regex(
        """Покупка:\s*($N)\s*([€$])\.\s*Карта:\s*\*(\d{4})\.\s*(.+?)\.\s*Остаток:\s*($N)\s*([€$])"""
    )
    private val REQUEST = Regex("""Заявка на пополнение карты \*(\d{4}) принята, ожидаем оплаты:\s*($N)\s*₽""")
    private val TOPUP = Regex("""Пополнение карты \*(\d{4}) на сумму ($N)\s*([€$]) прошло успешно""")
    private val FEE = Regex("""Списана комиссия\s*([€$])\s*($N)""")
    private val FEE_CARD = Regex("""Карта:\s*\*(\d{4})\.""")
    private val FEE_REST = Regex("""Остаток:\s*([€$])\s*($N)""")
    private val ISSUE = Regex("""Осуществите оплату карты\s*:\s*($N)\s*₽""")

    private fun cur(symbol: String) = when (symbol) { "€" -> "EUR"; "$" -> "USD"; else -> "RUB" }

    private val timeFmt = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")

    /** Текст чата → события по порядку, без повторов от вставки внахлёст. */
    fun parse(text: String): List<Event> {
        val out = mutableListOf<Event>()
        val seenKeys = HashSet<String>()
        // Последняя заявка по карте, КАКОЙ ОНА БЫЛА В ТЕКСТЕ — даже если сама
        // выброшена как повтор: пополнение внахлёст узнаётся по ней.
        val lastRequestRub = HashMap<String, Long>()
        var seq = 0
        // Сообщения разделены пустой строкой; строка времени — первая в своём сообщении.
        val blocks = text.replace("\r", "").split(Regex("\n\\s*\n"))
        for (raw in blocks) {
            val lines = raw.lines().map { it.trim() }.filter { it.isNotEmpty() }
            if (lines.isEmpty()) continue
            var ts = 0L
            val body = mutableListOf<String>()
            for (l in lines) {
                val m = TIME.matchEntire(l)
                if (m != null && body.isEmpty()) {
                    ts = runCatching {
                        LocalDateTime.parse("${m.groupValues[1]} ${m.groupValues[2]}", timeFmt)
                            .atZone(BankStatements.MSK).toInstant().toEpochMilli()
                    }.getOrDefault(0L)
                } else body.add(l)
            }
            val block = body.joinToString("\n")
            // Отказ: денег не списали, а вот комиссия за попытку — списали.
            if (block.contains("Операция отклонена")) continue

            PURCHASE.findAll(block).forEach { m ->
                val minor = MoneyFormat.parseKop(m.groupValues[1]) ?: return@forEach
                val currency = cur(m.groupValues[2])
                val card = m.groupValues[3]
                val merchant = m.groupValues[4].trim()
                val rest = MoneyFormat.parseKop(m.groupValues[5]) ?: 0L
                if (seenKeys.add("p|$card|$minor|$currency|$merchant|$rest")) {
                    out.add(Event.Purchase(card, seq++, ts, minor, currency, merchant, rest))
                }
            }
            REQUEST.findAll(block).forEach { m ->
                val rub = MoneyFormat.parseKop(m.groupValues[2]) ?: return@forEach
                // Одну и ту же заявку бот повторяет, и владелец создаёт её
                // дважды-трижды, а платит одну: до копейки одинаковая сумма —
                // одна заявка.
                lastRequestRub[m.groupValues[1]] = rub
                if (seenKeys.add("r|${m.groupValues[1]}|$rub")) {
                    out.add(Event.TopUpRequest(m.groupValues[1], seq++, ts, rub))
                }
            }
            TOPUP.findAll(block).forEach { m ->
                val minor = MoneyFormat.parseKop(m.groupValues[2]) ?: return@forEach
                val card = m.groupValues[1]
                val currency = cur(m.groupValues[3])
                // Повтор пополнения узнаём по заявке перед ним: одна заявка —
                // одно пополнение.
                if (seenKeys.add("u|$card|$minor|$currency|${lastRequestRub[card]}")) {
                    out.add(Event.TopUp(card, seq++, ts, minor, currency))
                }
            }
            FEE.find(block)?.let { m ->
                val minor = MoneyFormat.parseKop(m.groupValues[2]) ?: return@let
                val card = FEE_CARD.find(block)?.groupValues?.get(1).orEmpty()
                val rest = FEE_REST.find(block)?.groupValues?.get(2)?.let { MoneyFormat.parseKop(it) } ?: 0L
                if (seenKeys.add("f|$card|$minor|$rest")) {
                    out.add(Event.Fee(card, seq++, ts, minor, cur(m.groupValues[1]), rest))
                }
            }
            ISSUE.find(block)?.let { m ->
                val rub = MoneyFormat.parseKop(m.groupValues[1]) ?: return@let
                if (seenKeys.add("i|$rub")) out.add(Event.IssueFee("", seq++, ts, rub))
            }
        }
        return out
    }

    /** Списание «Плати по миру» в Тинькове: номер записи, время, копейки (положительные). */
    data class BankDebit(val id: String, val ts: Long, val rubKop: Long)

    data class Built(
        /** Покупки и комиссии карты — записи журнала, в рублях по курсу своего пополнения. */
        val entries: List<MoneyEntry>,
        /** Строки Тинькова, склеенные с заявкой (пополнение) или с выпуском карты (комиссия): id → категория. */
        val bankLinks: Map<String, String>,
        /** Заявки, за которые в Тинькове нет денег: брошенные или Тиньков ещё не прислан. */
        val unpaidRequests: Int,
        /** Покупки, у которых курс взят не свой, а прикидкой: пополнение без пары в Тинькове. */
        val roughRate: Int,
    )

    /**
     * Прикидка курса, когда своей пары нет (Тиньков за эти дни ещё не
     * прислан): число из чата того же лета. Такие записи помечены как
     * предварительные и пересчитываются, как только придёт выписка.
     */
    private val ROUGH = mapOf("EUR" to 100.0, "USD" to 90.0)

    /**
     * События → записи. [debits] — списания «Плати по миру» из Тинькова: по
     * ним заявка узнаётся оплаченной (сумма до копейки, ближайшая по порядку),
     * получает время, и её пополнение даёт курс покупкам после него.
     */
    fun build(events: List<Event>, debits: List<BankDebit>, owner: String = "sasha", importedAt: Long): Built {
        val pool = debits.sortedBy { it.ts }.toMutableList()
        val paidTs = HashMap<Int, Long>() // seq заявки → время оплаты из Тинькова
        val links = LinkedHashMap<String, String>()
        var unpaid = 0
        for (e in events) {
            when (e) {
                is Event.TopUpRequest -> {
                    // Ищем ПЕРВОЕ по времени неиспользованное списание той же
                    // суммы, не раньше предыдущей оплаченной заявки: чат идёт
                    // по времени, Тиньков — тоже.
                    val floor = paidTs.values.maxOrNull() ?: Long.MIN_VALUE
                    val hit = pool.firstOrNull { it.rubKop == e.rubKop && it.ts >= floor - 60_000 }
                        ?: pool.firstOrNull { it.rubKop == e.rubKop }
                    if (hit != null) {
                        pool.remove(hit)
                        paidTs[e.seq] = hit.ts
                        links[hit.id] = "plati"
                    } else unpaid++
                }
                is Event.IssueFee -> {
                    pool.firstOrNull { it.rubKop == e.rubKop }?.let {
                        pool.remove(it)
                        paidTs[e.seq] = it.ts
                        links[it.id] = "fees"
                    }
                }
                else -> Unit
            }
        }

        // Якоря времени: своя строка времени или оплата заявки в Тинькове.
        val anchors = events.mapNotNull { e ->
            val t = if (e.ts > 0) e.ts else paidTs[e.seq] ?: 0L
            if (t > 0) e.seq to t else null
        }
        fun timeOf(seq: Int): Long =
            anchors.lastOrNull { it.first <= seq }?.second
                ?: anchors.firstOrNull { it.first > seq }?.second
                ?: importedAt

        // Курс по карте: рубли оплаченной заявки / валюта её пополнения.
        val rate = HashMap<String, Double>()
        val rateIsOwn = HashMap<String, Boolean>()
        val lastReq = HashMap<String, Event.TopUpRequest>()
        val out = mutableListOf<MoneyEntry>()
        val seen = HashMap<String, Int>()
        var rough = 0
        fun rub(card: String, minor: Long, currency: String): Pair<Long, Boolean> {
            val r = rate[card]
            return if (r != null) Math.round(minor * r) to (rateIsOwn[card] == true)
            else Math.round(minor * (ROUGH[currency] ?: 1.0)) to false
        }
        for (e in events) {
            when (e) {
                is Event.TopUpRequest -> lastReq[e.card] = e
                is Event.TopUp -> {
                    val req = lastReq[e.card]
                    if (req != null && paidTs.containsKey(req.seq) && e.minor > 0) {
                        rate[e.card] = req.rubKop.toDouble() / e.minor
                        rateIsOwn[e.card] = true
                    } else if (req != null && e.minor > 0 && rate[e.card] == null) {
                        // Пары в Тинькове нет, но сумма заявки в чате есть — курс
                        // из неё всё равно точнее прикидки.
                        rate[e.card] = req.rubKop.toDouble() / e.minor
                        rateIsOwn[e.card] = false
                    }
                }
                is Event.Purchase -> {
                    val (kop, own) = rub(e.card, e.minor, e.currency)
                    if (!own) rough++
                    val own3 = own
                    out.add(
                        MoneyEntry(
                            id = BankStatements.stableId(
                                "p|$owner|${e.card}|${e.minor}|${e.currency}|${e.merchant}|${e.balanceMinor}", seen,
                            ),
                            owner = owner,
                            source = MoneyEntry.Source.PLATI,
                            ts = if (e.ts > 0) e.ts else timeOf(e.seq),
                            timeKnown = e.ts > 0,
                            rubKop = -kop,
                            origMinor = -e.minor,
                            currency = e.currency,
                            rubBasis = if (own3) MoneyEntry.RubBasis.TOPUP else MoneyEntry.RubBasis.CBR_PRELIM,
                            what = e.merchant,
                            account = "Плати по миру *${e.card}",
                        )
                    )
                }
                is Event.Fee -> {
                    val (kop, own) = rub(e.card, e.minor, e.currency)
                    out.add(
                        MoneyEntry(
                            id = BankStatements.stableId("p|$owner|fee|${e.card}|${e.minor}|${e.balanceMinor}", seen),
                            owner = owner,
                            source = MoneyEntry.Source.PLATI,
                            ts = if (e.ts > 0) e.ts else timeOf(e.seq),
                            timeKnown = e.ts > 0,
                            rubKop = -kop,
                            origMinor = -e.minor,
                            currency = e.currency,
                            rubBasis = if (own) MoneyEntry.RubBasis.TOPUP else MoneyEntry.RubBasis.CBR_PRELIM,
                            what = "Комиссия за неудачную попытку",
                            account = "Плати по миру *${e.card}",
                            category = "fees",
                            categoryBy = MoneyEntry.CategoryBy.RULE,
                        )
                    )
                }
                is Event.IssueFee -> Unit // платится с Тинькова: там и лежит, под категорией «комиссии»
            }
        }
        return Built(out, links, unpaid, rough)
    }

    /**
     * События обратно в текст бота — только денежные сообщения, по одному в
     * блоке, с временем, где оно было. Так чат хранится на телефоне БЕЗ шума
     * и кодов привязки, а пересобрать его можно, когда придёт Тиньков за те
     * же дни: [parse] от этого текста даёт те же события.
     */
    fun canonical(events: List<Event>): String {
        val tf = DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
        fun num(minor: Long): String {
            val a = kotlin.math.abs(minor)
            return (if (minor < 0) "-" else "") + (a / 100) + "." + (a % 100).toString().padStart(2, '0')
        }
        fun sym(c: String) = if (c == "USD") "$" else "€"
        return events.joinToString("\n\n") { e ->
            val time = if (e.ts > 0) java.time.Instant.ofEpochMilli(e.ts).atZone(BankStatements.MSK).format(tf) + "\n" else ""
            time + when (e) {
                is Event.Purchase -> "Покупка: ${num(e.minor)} ${sym(e.currency)}. Карта: *${e.card}. ${e.merchant}. Остаток: ${num(e.balanceMinor)} ${sym(e.currency)}."
                is Event.TopUpRequest -> "Заявка на пополнение карты *${e.card} принята, ожидаем оплаты: ${num(e.rubKop)} ₽"
                is Event.TopUp -> "Пополнение карты *${e.card} на сумму ${num(e.minor)} ${sym(e.currency)} прошло успешно"
                is Event.Fee -> "Карта: *${e.card}.\nСписана комиссия ${sym(e.currency)}${num(e.minor)}.\nОстаток: ${sym(e.currency)}${num(e.balanceMinor)}."
                is Event.IssueFee -> "Заявка на выпуск карты принята. Осуществите оплату карты : ${num(e.rubKop)} ₽"
            }
        }
    }

    /** Похоже ли вставленное на чат бота: хоть одна покупка или пополнение. */
    fun looksLike(text: String): Boolean =
        PURCHASE.containsMatchIn(text) || TOPUP.containsMatchIn(text) || REQUEST.containsMatchIn(text)
}
