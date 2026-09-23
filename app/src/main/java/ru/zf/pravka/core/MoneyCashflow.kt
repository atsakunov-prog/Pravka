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

    /** Прежний вызов: «+ ЗФ» выключен — личное, включён — всё. */
    fun build(entries: List<MoneyEntry>, months: List<YearMonth>, withZf: Boolean): List<Row> =
        build(entries, months, MoneyScope.of(withZf))

    /** Вклад записи в ячейку ДДС: категория или строка ВГО («vgo:…»). */
    private data class Part(val e: MoneyEntry, val key: String, val kop: Long)

    private val VGO_TITLES = linkedMapOf(
        "vgo:paid_for_zf" to "За ЗФ со своих карт",
        "vgo:zf_paid_for_me" to "Личное, оплаченное с бизнес-карты",
        "vgo:owner_paid" to "Оплачено владельцем со своих карт",
        "vgo:owner_personal" to "Личное владельца с бизнес-карты",
        "vgo:payout" to "Выплаты владельцу",
    )

    /**
     * Во что превращается запись при кнопках [scope]. Сторона — чьи деньги,
     * назначение — на что; где они расходятся, появляется ВГО. При обеих
     * кнопках ВГО нет: трата — по назначению, выплата владельцу при книгах
     * ЗФ исключается с обеих сторон.
     */
    private fun partsOf(e: MoneyEntry, scope: MoneyScope): List<Part> {
        val ez = scope.entityZf(e)
        val pz = scope.purposeZf(e)
        val k = e.category
        val v = e.rubKop
        return when {
            scope.both -> when {
                // Пара «ЗФ заплатила — владелец получил» — внутри, исключается целиком.
                (k == "inc_zf" || k == "zf_owner") && e.matchId.isNotBlank() -> emptyList()
                // Выплата без пары: деньги ушли из ЗФ туда, чего в журнале нет.
                k == "zf_owner" -> listOf(Part(e, "vgo:payout", v))
                else -> listOf(Part(e, k, v))
            }
            scope.personal -> when {
                !ez && pz == true -> listOf(Part(e, "vgo:paid_for_zf", v))
                !ez -> listOf(Part(e, k, v))
                pz == false -> listOf(Part(e, k, v), Part(e, "vgo:zf_paid_for_me", -v))
                else -> emptyList()
            }
            else -> when {
                ez && pz == false -> listOf(Part(e, "vgo:owner_personal", v))
                ez && k == "zf_owner" -> listOf(Part(e, "vgo:payout", v))
                ez -> listOf(Part(e, k, v))
                pz == true -> listOf(Part(e, k, v), Part(e, "vgo:owner_paid", -v))
                // Без книг ЗФ её выплату владельцу видно только с его стороны.
                k == "inc_zf" && !scope.zfBooks -> listOf(Part(e, "vgo:payout", -v))
                else -> emptyList()
            }
        }
    }

    fun build(entries: List<MoneyEntry>, months: List<YearMonth>, scope: MoneyScope): List<Row> {
        val parts = entries.filter { it.live() }.flatMap { partsOf(it, scope) }
        val byMonth = months.map { ym -> parts.filter { inMonth(it.e, ym) } }
        fun sum(pred: (Part) -> Boolean) = byMonth.map { l -> l.filter(pred).sumOf { it.kop } }
        val rows = mutableListOf<Row>()
        fun nonZero(v: List<Long>) = v.any { it != 0L }

        // ---- Операционный ----
        rows += Row("Операционная деятельность", Kind.SECTION, emptyList())
        val incomeKeys = MoneyCategories.ALL.filter { it.income }
        val income = incomeKeys.map { c -> c to sum { it.key == c.key } }.filter { nonZero(it.second) }
        val unknownIn = sum { it.key.isBlank() && it.kop > 0 }
        val inTotal = months.indices.map { i -> income.sumOf { it.second[i] } + unknownIn[i] }
        rows += Row("Поступления", Kind.GROUP, inTotal)
        income.forEach { (c, v) -> rows += Row(c.title, Kind.LINE, v, c.key) }
        if (nonZero(unknownIn)) rows += Row("без категории", Kind.LINE, unknownIn)

        val groups = listOf(MoneyCategories.G_HOME, MoneyCategories.G_KIDS, MoneyCategories.G_HEALTH, MoneyCategories.G_LIFE, MoneyCategories.G_ZF)
        val outTotal = MutableList(months.size) { 0L }
        val outRows = mutableListOf<Row>()
        for (g in groups) {
            val cats = MoneyCategories.ALL.filter { it.group == g && !it.income }
                .map { c -> c to sum { it.key == c.key } }
                .filter { nonZero(it.second) }
            if (cats.isEmpty()) continue
            val gv = months.indices.map { i -> cats.sumOf { it.second[i] } }
            gv.forEachIndexed { i, x -> outTotal[i] += x }
            outRows += Row(g, Kind.GROUP, gv)
            // Крупные категории выше: так читают, где деньги.
            cats.sortedBy { it.second.sum() }.forEach { (c, x) -> outRows += Row(c.title, Kind.LINE, x, c.key) }
        }
        val unknownOut = sum { it.key.isBlank() && it.kop < 0 }
        if (nonZero(unknownOut)) {
            unknownOut.forEachIndexed { i, x -> outTotal[i] += x }
            outRows += Row("Без категории", Kind.GROUP, unknownOut)
        }
        rows += Row("Выплаты", Kind.GROUP, outTotal)
        rows += outRows
        val op = months.indices.map { i -> inTotal[i] + outTotal[i] }
        rows += Row("Операционный поток", Kind.TOTAL, op)

        // ---- Финансовый ----
        val got = sum { it.key == "loan" && it.kop > 0 }
        val paid = sum { it.key == "loan" && it.kop < 0 }
        val fin = months.indices.map { i -> got[i] + paid[i] }
        if (nonZero(got) || nonZero(paid)) {
            rows += Row("Финансовая деятельность", Kind.SECTION, emptyList())
            if (nonZero(got)) rows += Row("Займы получены", Kind.LINE, got)
            if (nonZero(paid)) rows += Row("Займы возвращены", Kind.LINE, paid)
            rows += Row("Финансовый поток", Kind.TOTAL, fin)
        }

        // ---- ВГО: между ЗФ и владельцем (только когда включена одна кнопка) ----
        val vgo = VGO_TITLES.map { (k, t) -> Triple(k, t, sum { it.key == k }) }.filter { nonZero(it.third) }
        val vgoNet = months.indices.map { i -> vgo.sumOf { it.third[i] } }
        if (vgo.isNotEmpty()) {
            rows += Row("ВГО: между ЗФ и владельцем", Kind.SECTION, emptyList())
            vgo.forEach { (k, t, x) -> rows += Row(t, Kind.LINE, x, k) }
            rows += Row("ВГО, нетто", Kind.TOTAL, vgoNet, "vgo")
        }
        rows += Row("Чистый денежный поток", Kind.TOTAL, months.indices.map { i -> op[i] + fin[i] + vgoNet[i] }, "net")

        // ---- Перемещения ----
        // Банкомат и «Плати по миру» — внутри семьи: вторая сторона —
        // кошелёк и карта Плати, оба в журнале. Нетто «мимо журнала» — только
        // между своими счетами и супругами: там вторая сторона бывает в банке
        // без выписки.
        val moves = listOf("own", "spouse", "plati", "cash").map { k -> k to sum { it.key == k } }.filter { nonZero(it.second) }
        if (moves.isNotEmpty()) {
            rows += Row("Перемещения (не трата)", Kind.SECTION, emptyList())
            moves.forEach { (k, x) ->
                val title = when (k) { "cash" -> "Банкомат (банк ↔ кошелёк)"; "plati" -> "Пополнение Плати по миру"; else -> MoneyCategories.title(k) }
                rows += Row(title, Kind.LINE, x, k)
            }
            val outside = moves.filter { it.first == "own" || it.first == "spouse" }
            if (outside.isNotEmpty()) {
                rows += Row("Мимо журнала, нетто", Kind.TOTAL, months.indices.map { i -> outside.sumOf { it.second[i] } }, "moves")
            }
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
        MoneyEntry.Source.TBIZ -> TBIZ_NAME
        else -> null // голос и наличные — не счёт банка; «Плати по миру» — в валюте, отдельно
    }

    /** Счета ЗФ из файла остатков: строки «счёт ЗФ | Т-Банк · Счет для бизнеса». */
    fun parseZfAccounts(text: String): Set<String> = text.lines()
        .map { it.split('|').map { p -> p.trim() } }
        .filter { it.size >= 2 && it[0].equals("счёт ЗФ", ignoreCase = true) }
        .map { it[1] }
        .toSet()

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
        entries.filter { it.source == MoneyEntry.Source.TINKOFF || it.source == MoneyEntry.Source.TBIZ }
            .mapNotNull { e ->
                BankPush.cardOf(e.account).takeIf { it.isNotEmpty() }?.let {
                    it to if (e.source == MoneyEntry.Source.TBIZ) TBIZ_NAME else "Т-Банк · " + stripCard(e.account)
                }
            }
            .toMap()

    /** Расчётный счёт ЗФ в Т-Бизнесе — в балансе, «Счетах» и списке счетов ЗФ. */
    const val TBIZ_NAME = "Т-Бизнес · ЗФ"

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
    /** Долг Наташе — её доля из выплат ЗФ: переводы «Доля Наташи» его гасят. */
    const val NATASHA_DEBT = "Долг Наташе (доля ЗФ)"

    /**
     * Все движения по счетам: банковские строки — своим счетам, наличные и
     * банкомат — кошельку, выплаты доли Наташи — ещё и долгу ей (с обратным
     * знаком: заплатил 400 000 — долг меньше на 400 000).
     */
    fun movesByAccount(entries: List<MoneyEntry>): Map<String, List<MoneyEntry>> {
        val cards = cardMap(entries)
        val usable = entries.filter { !it.draft && !it.dropped && it.replacedBy.isEmpty() }
        val bank = usable.mapNotNull { e -> accountOf(e, cards)?.let { it to e } }.groupBy({ it.first }, { it.second })
        val wallet = walletMoves(entries)
        // Долг Наташе: начисления доли (записи «Долг: начислено» на этот счёт)
        // минус переводы ей — с нуля, а не от якоря: так он верен в любой месяц.
        val share = usable.filter { it.category == "zf_share" && !(it.source == MoneyEntry.Source.VOICE && it.matchId.isNotBlank()) }
            .map { it.copy(id = it.id + "~долг", rubKop = -it.rubKop) } +
            usable.filter { it.account == NATASHA_DEBT }
        return bank +
            (if (wallet.isNotEmpty()) mapOf(WALLET to wallet) else emptyMap()) +
            (if (share.isNotEmpty()) mapOf(NATASHA_DEBT to share) else emptyMap())
    }

    /** Остаток счёта на [at] от якоря [anchor] по его движениям [list]; null — якоря нет. */
    private fun balanceAt(list: List<MoneyEntry>, anchor: Anchor?, at: Long, entries: List<MoneyEntry>): Long? = anchor?.let { a ->
        // Пуш-якорь и строка выписки, его заменившая, — одна операция, уже в числе.
        val covered = a.covers + entries.filter { it.id in a.covers }.map { it.replacedBy }.filter { it.isNotEmpty() }
        val after = list.filter { it.id !in covered && it.ts > a.ts && it.ts <= at }.sumOf { it.rubKop }
        val before = list.filter { it.id !in covered && it.ts > at && it.ts <= a.ts }.sumOf { it.rubKop }
        // Операции, вошедшие в якорь, но случившиеся позже [at], вычитаются тоже.
        val coveredLater = list.filter { it.id in covered && it.ts > at }.sumOf { it.rubKop }
        a.kop + after - before - coveredLater
    }

    private fun latestAnchors(anchors: List<Anchor>) = anchors.groupBy { it.account }.mapValues { (_, v) -> v.maxBy { it.ts } }

    fun balances(entries: List<MoneyEntry>, anchors: List<Anchor>, at: Long, recentFrom: Long): List<Account> {
        val moves = movesByAccount(entries)
        val latest = latestAnchors(anchors)
        val names = (moves.filter { (_, l) -> l.any { it.ts in recentFrom..at } }.keys + latest.keys).toSortedSet()
        return names.map { name ->
            val list = moves[name].orEmpty()
            Account(name, balanceAt(list, latest[name], at, entries), latest[name], list.filter { it.ts in recentFrom..at }.sumOf { it.rubKop })
        }
    }

    /** Движение по одному счёту за период: откуда пришло и куда ушло, по категориям. */
    data class AccountFlow(
        val name: String,
        val startKop: Long?,
        val endKop: Long?,
        val inKop: Long,
        val outKop: Long,
        /** Категория (название) → сумма со знаком, крупные первыми. */
        val byCategory: List<Pair<String, Long>>,
    )

    /**
     * Счета за период [from]…[to): начало, пришло, ушло, конец. Кошелёк и
     * долг Наташе — такие же счета. Порядок: наличные, Т-Банк, Альфа, МКБ,
     * долги — как владелец их держит в голове.
     */
    fun accountFlows(entries: List<MoneyEntry>, anchors: List<Anchor>, from: Long, to: Long, now: Long): List<AccountFlow> {
        val moves = movesByAccount(entries)
        val latest = latestAnchors(anchors)
        val end = minOf(to - 1, now)
        val names = (moves.filter { (_, l) -> l.any { it.ts in from until to } }.keys + latest.keys).distinct()
        fun order(n: String) = when {
            n == WALLET -> 0
            n.startsWith("Т-Банк") -> 1
            n.startsWith("Альфа") -> 2
            n == "МКБ" -> 3
            else -> 4
        }
        return names.sortedWith(compareBy({ order(it) }, { it })).map { name ->
            val list = moves[name].orEmpty()
            val span = list.filter { it.ts in from until to }
            AccountFlow(
                name = name,
                startKop = balanceAt(list, latest[name], from - 1, entries),
                endKop = balanceAt(list, latest[name], end, entries),
                inKop = span.filter { it.rubKop > 0 }.sumOf { it.rubKop },
                outKop = span.filter { it.rubKop < 0 }.sumOf { it.rubKop },
                byCategory = span.groupBy { MoneyCategories.title(it.category) }
                    .map { (k, v) -> k to v.sumOf { it.rubKop } }
                    .sortedByDescending { kotlin.math.abs(it.second) },
            )
        }
    }

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

    /**
     * Долг по займам по журналу: получено минус возвращено. «По журналу» —
     * значит, с первой строки выписки: заём, взятый до неё, здесь не виден,
     * и экран это говорит.
     */
    fun loanDebt(entries: List<MoneyEntry>, at: Long): Long =
        entries.filter { it.live() && it.category == "loan" && it.ts <= at }.sumOf { it.rubKop }.coerceAtLeast(0L)
}
