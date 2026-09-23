package ru.zf.pravka.core

import java.time.YearMonth

// ДДС по месяцам и баланс (владелец, 23.09.2026: «давай сделаем cashflow по
// месяцу классический и баланс»). Всё кодом из журнала, модель не участвует.
//
// ДДС — классический, в три раздела, со знаком (приток +, отток −):
//  - операционный: доходы и траты семьи по группам и категориям (ЗФ — по
//    тумблеру «+ ЗФ», как во всей вкладке), неразложенное — отдельной строкой;
//  - финансовый: займы получены и возвращены;
//  - перемещения — не трата, но деньги двигаются: между своими счетами, между
//    супругами, пополнение «Плати по миру», снятые наличные. Их нетто должно
//    быть около нуля; не ноль — значит, часть денег ушла на счёт, выписки
//    которого в журнале нет. Это показывается, а не прячется.
//
// Баланс. Остатков нет НИ В ОДНОЙ выписке (Тиньков, Альфа, МКБ дают только
// движения — проверено 23.09.2026), поэтому остаток счёта — от ЯКОРЯ:
// «Доступно …» из пуша Т-Банка, остаток бота «Плати по миру» или число,
// вписанное владельцем. От якоря остаток на любой день считается движениями
// журнала вперёд и назад. Счёт без якоря — «остаток неизвестен», а не ноль.
//
// Файл без Android: проверяют JVM-тесты.
object MoneyCashflow {

    enum class Kind { SECTION, GROUP, LINE, TOTAL, NOTE }

    /** Строка таблицы ДДС: значения по месяцам (копейки, со знаком). */
    data class Row(val title: String, val kind: Kind, val values: List<Long>, val key: String = "")

    private fun inMonth(e: MoneyEntry, ym: YearMonth): Boolean {
        val p = MoneyStats.of(MoneyStats.Kind.MONTH, ym.atDay(1))
        return e.ts >= p.from && e.ts < p.to
    }

    fun build(entries: List<MoneyEntry>, months: List<YearMonth>, withZf: Boolean): List<Row> {
        val live = entries.filter { it.live() }
        val byMonth = months.map { ym -> live.filter { inMonth(it, ym) } }
        fun sum(pred: (MoneyEntry) -> Boolean) = byMonth.map { l -> l.filter(pred).sumOf { it.rubKop } }
        val rows = mutableListOf<Row>()
        fun nonZero(v: List<Long>) = v.any { it != 0L }

        // ---- Операционный ----
        rows += Row("Операционная деятельность", Kind.SECTION, emptyList())
        val incomeKeys = MoneyCategories.ALL.filter { it.income }
        val income = incomeKeys.map { c -> c to sum { it.category == c.key } }.filter { nonZero(it.second) }
        val unknownIn = sum { it.category.isBlank() && it.rubKop > 0 }
        val inTotal = months.indices.map { i -> income.sumOf { it.second[i] } + unknownIn[i] }
        rows += Row("Поступления", Kind.GROUP, inTotal)
        income.forEach { (c, v) -> rows += Row(c.title, Kind.LINE, v, c.key) }
        if (nonZero(unknownIn)) rows += Row("без категории", Kind.LINE, unknownIn)

        val groups = listOf(MoneyCategories.G_HOME, MoneyCategories.G_KIDS, MoneyCategories.G_HEALTH, MoneyCategories.G_LIFE) +
            if (withZf) listOf(MoneyCategories.G_ZF) else emptyList()
        val outTotal = MutableList(months.size) { 0L }
        val outRows = mutableListOf<Row>()
        for (g in groups) {
            val cats = MoneyCategories.ALL.filter { it.group == g && !it.income }
                .map { c -> c to sum { it.category == c.key } }
                .filter { nonZero(it.second) }
            if (cats.isEmpty()) continue
            val gv = months.indices.map { i -> cats.sumOf { it.second[i] } }
            gv.forEachIndexed { i, v -> outTotal[i] += v }
            outRows += Row(g, Kind.GROUP, gv)
            // Крупные категории выше: так читают, где деньги.
            cats.sortedBy { it.second.sum() }.forEach { (c, v) -> outRows += Row(c.title, Kind.LINE, v, c.key) }
        }
        val unknownOut = sum { it.category.isBlank() && it.rubKop < 0 }
        if (nonZero(unknownOut)) {
            unknownOut.forEachIndexed { i, v -> outTotal[i] += v }
            outRows += Row("Без категории", Kind.GROUP, unknownOut)
        }
        rows += Row("Выплаты", Kind.GROUP, outTotal)
        rows += outRows
        val op = months.indices.map { i -> inTotal[i] + outTotal[i] }
        rows += Row("Операционный поток", Kind.TOTAL, op)

        // ---- Финансовый ----
        val got = sum { it.category == "loan" && it.rubKop > 0 }
        val paid = sum { it.category == "loan" && it.rubKop < 0 }
        val fin = months.indices.map { i -> got[i] + paid[i] }
        if (nonZero(got) || nonZero(paid)) {
            rows += Row("Финансовая деятельность", Kind.SECTION, emptyList())
            if (nonZero(got)) rows += Row("Займы получены", Kind.LINE, got)
            if (nonZero(paid)) rows += Row("Займы возвращены", Kind.LINE, paid)
            rows += Row("Финансовый поток", Kind.TOTAL, fin)
        }
        rows += Row("Чистый денежный поток", Kind.TOTAL, months.indices.map { i -> op[i] + fin[i] }, "net")

        // ---- Перемещения ----
        val moves = listOf("own", "spouse", "plati", "cash").map { k -> k to sum { it.category == k } }.filter { nonZero(it.second) }
        if (moves.isNotEmpty()) {
            rows += Row("Перемещения (не трата)", Kind.SECTION, emptyList())
            moves.forEach { (k, v) -> rows += Row(MoneyCategories.title(k), Kind.LINE, v, k) }
            rows += Row("Перемещения, нетто", Kind.TOTAL, months.indices.map { i -> moves.sumOf { it.second[i] } }, "moves")
        }
        return rows
    }

    // ---- Баланс ----

    /** Якорь остатка: на [ts] на счёте [account] было [kop]. [covers] — записи, уже вошедшие в это число. */
    data class Anchor(
        val account: String,
        val ts: Long,
        val kop: Long,
        val source: String,
        val covers: Set<String> = emptySet(),
    )

    /**
     * Счёт записи для баланса: «Т-Банк · Black Premium», «Альфа · …», «МКБ».
     * У Т-Банка карта — не счёт: *1519 и *0292 (карта Марианны) — один Black
     * Premium, поэтому пуш с картой находит свой счёт по строкам выписки.
     */
    fun accountOf(e: MoneyEntry, cardToAccount: Map<String, String>): String? = when (e.source) {
        MoneyEntry.Source.TINKOFF -> "Т-Банк · " + stripCard(e.account).ifBlank { "счёт" }
        MoneyEntry.Source.PUSH -> cardToAccount[BankPush.cardOf(e.account)] ?: "Т-Банк · счёт"
        MoneyEntry.Source.ALFA -> "Альфа · " + stripCard(e.account).ifBlank { "счёт" }
        MoneyEntry.Source.MKB -> "МКБ"
        else -> null // голос и наличные — не счёт банка; «Плати по миру» — в валюте, отдельно
    }

    /** Кошелёк наличных — отдельный счёт баланса. */
    const val WALLET = "Наличные"

    /**
     * Движения кошелька: траты и доходы «наличными» (голос, записи со слов
     * владельца) плюс строки банка «наличные» с обратным знаком — снял в
     * банкомате 50 000: банк −50 000, кошелёк +50 000; внёс 480 000: наоборот.
     */
    fun walletMoves(entries: List<MoneyEntry>): List<MoneyEntry> =
        entries.filter { !it.draft && !it.dropped && it.replacedBy.isEmpty() }.mapNotNull { e ->
            when {
                e.account == MoneyEntry.CASH && !(e.source == MoneyEntry.Source.VOICE && e.matchId.isNotBlank()) -> e
                e.fromBank && e.category == "cash" -> e.copy(id = e.id + "~кошелёк", rubKop = -e.rubKop)
                else -> null
            }
        }

    /**
     * Записи со слов владельца (`assets/money_manual.txt`): «дата | сумма |
     * категория | что | счёт | для кого». Номер — из самой строки: файл,
     * прочитанный дважды, записи не удваивает.
     */
    fun parseManual(text: String, owner: String): List<MoneyEntry> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split('|').map { it.trim() }
            if (p.size < 4) return@mapNotNull null
            val day = runCatching {
                java.time.LocalDate.parse(p[0], java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy"))
            }.getOrNull() ?: return@mapNotNull null
            val kop = MoneyFormat.parseKop(p[1]) ?: return@mapNotNull null
            val cat = MoneyCategories.find(p[2])?.key ?: return@mapNotNull null
            val account = p.getOrNull(4).orEmpty().let { if (it.equals("наличные", true)) MoneyEntry.CASH else it }
            MoneyEntry(
                id = "manual-" + BankPush.key(p[0] + "|" + p[1] + "|" + p[3], owner),
                owner = owner,
                source = MoneyEntry.Source.MANUAL,
                ts = day.atTime(12, 0).atZone(BankStatements.MSK).toInstant().toEpochMilli(),
                timeKnown = false,
                rubKop = kop,
                what = p[3],
                account = account,
                category = cat,
                who = MoneyCategories.findWho(p.getOrNull(5).orEmpty()),
                categoryBy = MoneyEntry.CategoryBy.OWNER,
            )
        }

    private fun stripCard(account: String) = account.replace(Regex("""\s*\*\d{4}\b"""), "").trim()

    /** Карта Т-Банка → счёт, по строкам выписки: «1519» → «Т-Банк · Black Premium». */
    fun cardMap(entries: List<MoneyEntry>): Map<String, String> =
        entries.filter { it.source == MoneyEntry.Source.TINKOFF }
            .mapNotNull { e -> BankPush.cardOf(e.account).takeIf { it.isNotEmpty() }?.let { it to "Т-Банк · " + stripCard(e.account) } }
            .toMap()

    data class Account(
        val name: String,
        /** Остаток на [at], копейки; null — якоря нет. */
        val kop: Long?,
        val anchor: Anchor?,
        /** Движение за период ДДС — видно и без якоря. */
        val flowKop: Long,
    )

    /**
     * Остатки всех счетов на момент [at]. Счета — все, где были движения за
     * [recentFrom]…[at] или есть якорь; движение по счёту — любые записи
     * (и «не трата» тоже: перевод жене уменьшает остаток так же, как кофе).
     */
    fun balances(entries: List<MoneyEntry>, anchors: List<Anchor>, at: Long, recentFrom: Long): List<Account> {
        val cards = cardMap(entries)
        // Для остатка — все движения, кроме черновиков, вычеркнутых и пушей, заменённых выпиской.
        val moves = entries.filter { !it.draft && !it.dropped && it.replacedBy.isEmpty() }
            .mapNotNull { e -> accountOf(e, cards)?.let { it to e } }
            .groupBy({ it.first }, { it.second })
            .let { m -> walletMoves(entries).takeIf { it.isNotEmpty() }?.let { w -> m + (WALLET to w) } ?: m }
        val latest = anchors.groupBy { it.account }.mapValues { (_, v) -> v.maxBy { it.ts } }
        val names = (moves.filter { (_, l) -> l.any { it.ts in recentFrom..at } }.keys + latest.keys).toSortedSet()
        return names.map { name ->
            val list = moves[name].orEmpty()
            val a = latest[name]
            val flow = list.filter { it.ts in recentFrom..at }.sumOf { it.rubKop }
            val kop = a?.let { anchor ->
                // Пуш-якорь и строка выписки, его заменившая, — одна операция, уже в числе.
                val covered = anchor.covers + entries.filter { it.id in anchor.covers }.map { it.replacedBy }.filter { it.isNotEmpty() }
                val after = list.filter { it.id !in covered && it.ts > anchor.ts && it.ts <= at }.sumOf { it.rubKop }
                val before = list.filter { it.id !in covered && it.ts > at && it.ts <= anchor.ts }.sumOf { it.rubKop }
                // Операции, вошедшие в якорь, но случившиеся позже [at], вычитаются тоже.
                val coveredLater = list.filter { it.id in covered && it.ts > at }.sumOf { it.rubKop }
                anchor.kop + after - before - coveredLater
            }
            Account(name, kop, a, flow)
        }
    }

    /**
     * Долг по займам по журналу: получено минус возвращено. «По журналу» —
     * значит, с первой строки выписки: заём, взятый до неё, здесь не виден,
     * и экран это говорит.
     */
    /**
     * Файл якорей («дата время | счёт | остаток | пояснение») — заводские
     * остатки из `assets/money_balances.txt`. Строки с ошибкой пропускаются.
     */
    fun parseAnchors(text: String): List<Anchor> = text.lines()
        .map { it.trim() }
        .filter { it.isNotEmpty() && !it.startsWith("#") }
        .mapNotNull { line ->
            val p = line.split('|').map { it.trim() }
            if (p.size < 3) return@mapNotNull null
            // С секундами — когда якорь ставится ровно на строку выписки.
            val ts = listOf("dd.MM.yyyy H:mm:ss", "dd.MM.yyyy H:mm").firstNotNullOfOrNull { f ->
                runCatching {
                    java.time.LocalDateTime.parse(p[0], java.time.format.DateTimeFormatter.ofPattern(f))
                        .atZone(BankStatements.MSK).toInstant().toEpochMilli()
                }.getOrNull()
            } ?: return@mapNotNull null
            val kop = MoneyFormat.parseKop(p[2]) ?: return@mapNotNull null
            Anchor(p[1], ts, kop, p.getOrNull(3)?.ifBlank { null } ?: "файл остатков")
        }

    fun loanDebt(entries: List<MoneyEntry>, at: Long): Long =
        entries.filter { it.live() && it.category == "loan" && it.ts <= at }.sumOf { it.rubKop }.coerceAtLeast(0L)
}
