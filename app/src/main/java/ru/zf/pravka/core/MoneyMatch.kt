package ru.zf.pravka.core

import kotlin.math.abs

// Сверка недельного пакета: Тиньков, Альфа, чат «Плати по миру» и то, что
// надиктовано. Всё здесь — КОДОМ и детерминированно: одна и та же пачка даёт
// одни и те же связи и вопросы, и тест это проверяет. Модель (`ClaudeMoney`)
// приходит потом — только предложить категорию тому, что код не узнал.
//
// Что делает, по порядку:
//  1. раскладывает строки выписок по справочнику (`MoneyRules`);
//  2. склеивает надиктованное с выпиской: та же сумма, дата рядом. У пары в
//     итоги идёт выписка (правда о сумме), голос отдаёт ей категорию и слова;
//  3. склеивает переводы Саша ↔ Марианна с двух сторон (из 44 переводов в
//     Альфе 42 нашли пару в Тинькове до рубля — 23.09.2026);
//  2а. пуш Т-Банка заменяется строкой выписки: та же сумма до копейки, та же
//     карта, время рядом. Выписка — правда, пуш отдаёт ей решённую категорию;
//  4. задаёт вопросы: неразложенные строки — группой по получателю
//     («Иван П., 17 раз, 102 000 ₽ — кто это?»), надиктованное без пары в
//     выписке — «не нашёл, это наличные?».
object MoneyMatch {

    private const val DAY = 86_400_000L

    /** Итог прохода: записи с проставленными категориями, связями и вопросами. */
    data class Result(
        val entries: List<MoneyEntry>,
        val voiceLinked: Int,
        val pushLinked: Int,
        val spouseLinked: Int,
        val classified: Int,
    )

    fun run(entries: List<MoneyEntry>, rules: List<MoneyRules.Rule>, now: Long): Result {
        var list = entries
        var classified = 0
        list = list.map { e ->
            if (!e.fromBank || e.categoryBy == MoneyEntry.CategoryBy.OWNER) return@map e
            val hit = MoneyRules.classify(e, rules)
            when {
                // Справочник владельца сильнее догадки модели; безличное правило — нет.
                hit != null && (e.categoryBy != MoneyEntry.CategoryBy.MODEL || hit.by == "справочник") -> {
                    if (e.category != hit.category) classified++
                    e.copy(
                        category = hit.category,
                        who = hit.who.ifEmpty { e.who },
                        categoryBy = MoneyEntry.CategoryBy.RULE,
                    )
                }
                else -> e
            }
        }
        val (withPush, pushLinked) = linkPush(list)
        val (withVoice, voiceLinked) = linkVoice(withPush)
        val (withSpouse, spouseLinked) = linkSpouse(withVoice)
        return Result(ask(withSpouse, now), voiceLinked, pushLinked, spouseLinked, classified)
    }

    // ---- 2а. Пуш ↔ выписка ----

    /** Вопрос о пуше, которому в пришедшей выписке пары нет. */
    const val ORPHAN_PUSH = "Пуш был, а в выписке Т-Банка его нет. Операцию отменили?"

    /**
     * Пуш и строка Тинькова — одна операция, если сумма та же ДО КОПЕЙКИ
     * (пуш пишет копейки, как банк), карта не противоречит и время в
     * пределах полутора суток (пуш — минута в минуту, но строка выписки
     * иногда встаёт датой проведения). Из кандидатов — ближайший по времени.
     * Пуш не удаляется: он остаётся следом, с номером заменившей его строки.
     */
    fun linkPush(entries: List<MoneyEntry>): Pair<List<MoneyEntry>, Int> {
        val pushes = entries.filter { it.source == MoneyEntry.Source.PUSH && it.replacedBy.isEmpty() && !it.dropped }
        if (pushes.isEmpty()) return entries to 0
        val byId = entries.associateBy { it.id }.toMutableMap()
        val taken = entries.filter { it.source == MoneyEntry.Source.PUSH }.map { it.replacedBy }.filter { it.isNotEmpty() }.toMutableSet()
        val bank = entries.filter { it.source == MoneyEntry.Source.TINKOFF && !it.dropped }
        var linked = 0
        for (p in pushes.sortedBy { it.ts }) {
            val pc = BankPush.cardOf(p.account)
            val hit = bank.asSequence()
                .filter { it.id !in taken && it.owner == p.owner && it.rubKop == p.rubKop }
                .filter { abs(it.ts - p.ts) <= DAY + DAY / 2 }
                .filter { b -> BankPush.cardOf(b.account).let { bc -> pc.isEmpty() || bc.isEmpty() || bc == pc } }
                .minByOrNull { abs(it.ts - p.ts) } ?: continue
            taken.add(hit.id)
            linked++
            val b = byId[hit.id] ?: hit
            val takeCategory = b.categoryBy != MoneyEntry.CategoryBy.OWNER && p.category.isNotBlank() &&
                (p.categoryBy == MoneyEntry.CategoryBy.OWNER || b.category.isBlank())
            // Голос, уже слитый с пушем, переезжает на строку выписки.
            val voice = p.matchId.takeIf { it.isNotEmpty() && b.matchId.isEmpty() }
            byId[b.id] = b.copy(
                category = if (takeCategory) p.category else b.category,
                who = b.who.ifBlank { p.who },
                categoryBy = if (takeCategory) p.categoryBy else b.categoryBy,
                note = b.note.ifBlank { p.note },
                matchId = voice ?: b.matchId,
            )
            if (voice != null) byId[voice]?.let { v -> byId[voice] = v.copy(matchId = b.id) }
            byId[p.id] = p.copy(replacedBy = b.id, question = "")
        }
        return entries.map { byId[it.id] ?: it } to linked
    }

    // ---- 2. Голос ↔ выписка ----

    /**
     * Пара — та же сумма (для валюты — в пределах 8 %: рубли голос берёт
     * по курсу ЦБ, банк — по своему) и день траты от «за сутки до» до «через
     * четыре дня после»: банк проводит позже, а владелец наговаривает иногда
     * вечером за весь день. Из кандидатов — ближайший по сумме, потом по дате.
     */
    fun linkVoice(entries: List<MoneyEntry>): Pair<List<MoneyEntry>, Int> {
        val byId = entries.associateBy { it.id }.toMutableMap()
        val taken = entries.filter { it.fromBank && it.matchId.isNotBlank() }.map { it.id }.toMutableSet()
        var linked = 0
        val voices = entries.filter {
            it.source == MoneyEntry.Source.VOICE && !it.draft && !it.dropped && it.matchId.isBlank() &&
                it.account != MoneyEntry.CASH
        }.sortedBy { it.ts }
        for (v in voices) {
            val tolerance = if (v.currency == "RUB") 100L else abs(v.rubKop) * 8 / 100
            val cand = entries.asSequence()
                .filter { it.fromBank && !it.dropped && it.replacedBy.isEmpty() && it.id !in taken }
                .filter { (it.rubKop < 0) == (v.rubKop < 0) }
                .filter { abs(abs(it.rubKop) - abs(v.rubKop)) <= tolerance }
                .filter { it.ts >= v.ts - DAY - DAY / 2 && it.ts <= v.ts + 4 * DAY }
                .sortedWith(compareBy({ abs(abs(it.rubKop) - abs(v.rubKop)) }, { abs(it.ts - v.ts) }))
                .firstOrNull() ?: continue
            taken.add(cand.id)
            linked++
            byId[v.id] = v.copy(matchId = cand.id, question = "")
            byId[cand.id] = cand.copy(
                matchId = v.id,
                // Голос одобрен владельцем на плашке — его категория весит как его решение.
                category = if (cand.categoryBy == MoneyEntry.CategoryBy.OWNER) cand.category else v.category.ifBlank { cand.category },
                who = cand.who.ifBlank { v.who },
                categoryBy = if (v.category.isNotBlank()) MoneyEntry.CategoryBy.OWNER else cand.categoryBy,
                question = "",
            )
        }
        return entries.map { byId[it.id] ?: it } to linked
    }

    // ---- 3. Саша ↔ Марианна ----

    fun linkSpouse(entries: List<MoneyEntry>): Pair<List<MoneyEntry>, Int> {
        val byId = entries.associateBy { it.id }.toMutableMap()
        val spouse = entries.filter { it.fromBank && it.category == "spouse" && !it.dropped && it.replacedBy.isEmpty() }
        val mine = spouse.filter { it.owner == "sasha" && it.matchId.isBlank() }
        val hers = spouse.filter { it.owner == "marianna" && it.matchId.isBlank() }.toMutableList()
        var linked = 0
        for (m in mine.sortedBy { it.ts }) {
            val pair = hers
                .filter { it.rubKop == -m.rubKop && abs(it.ts - m.ts) <= 2 * DAY + DAY / 2 }
                .minByOrNull { abs(it.ts - m.ts) } ?: continue
            hers.remove(pair)
            byId[m.id] = m.copy(matchId = pair.id)
            byId[pair.id] = pair.copy(matchId = m.id)
            linked++
        }
        return entries.map { byId[it.id] ?: it } to linked
    }

    // ---- 4. Вопросы ----

    /**
     * Вопрос ставится на запись текстом, который увидит владелец. Строки
     * выписки без категории спрашиваются группой (ответ на одну — правило на
     * всех того же получателя); здесь каждой ставится один и тот же текст.
     */
    fun ask(entries: List<MoneyEntry>, now: Long): List<MoneyEntry> {
        // Какие дни покрыты выписками: надиктованное без пары спрашиваем,
        // только если выписка за тот день уже пришла, иначе вопрос преждевремен.
        // Пуши выпиской не считаются: они теряются, и «не нашлось среди пушей» — ещё не вопрос.
        val covered = entries.filter { it.fromBank && it.source != MoneyEntry.Source.PLATI && it.source != MoneyEntry.Source.PUSH }
            .groupBy { it.owner }
            .mapValues { (_, v) -> (v.minOf { it.ts }) to (v.maxOf { it.ts }) }
        // Покрытие выписками Т-Банка: пуш старше суток до её конца, так и не
        // нашедший пары, — скорее всего отменённая операция.
        val tbank = entries.filter { it.source == MoneyEntry.Source.TINKOFF }.groupBy { it.owner }
            .mapValues { (_, v) -> (v.minOf { it.ts }) to (v.maxOf { it.ts }) }
        fun orphan(e: MoneyEntry): Boolean {
            if (e.source != MoneyEntry.Source.PUSH || e.replacedBy.isNotEmpty()) return false
            val span = tbank[e.owner] ?: return false
            return e.ts >= span.first && e.ts < span.second - DAY
        }
        val groups = entries.filter { it.fromBank && !it.dropped && it.replacedBy.isEmpty() && it.category.isBlank() && it.matchId.isBlank() }
            .groupBy { MoneyRules.norm(it.what) }
        val groupText = groups.mapValues { (_, v) ->
            val sum = v.sumOf { it.rubKop }
            val times = if (v.size > 1) ", ${v.size} раз" else ""
            "${v.first().what}$times, ${MoneyFormat.rub(sum)} — что это?"
        }
        return entries.map { e ->
            when {
                e.dropped || e.draft || e.replacedBy.isNotEmpty() -> e.copy(question = "")
                orphan(e) -> e.copy(question = ORPHAN_PUSH)
                e.fromBank && e.category.isBlank() && e.matchId.isBlank() ->
                    e.copy(question = groupText[MoneyRules.norm(e.what)].orEmpty())
                e.source == MoneyEntry.Source.VOICE && e.matchId.isBlank() && e.account != MoneyEntry.CASH -> {
                    val span = covered[e.owner]
                    val due = span != null && e.ts in span.first..span.second && now - e.ts > 3 * DAY
                    e.copy(question = if (due) "Надиктовано, но в выписке не нашлось. Это наличные?" else "")
                }
                e.fromBank && e.question.isNotBlank() && e.category.isNotBlank() -> e.copy(question = "")
                else -> e
            }
        }
    }

    // ---- Сводка ----

    data class Line(val category: String, val kop: Long, val count: Int)

    data class Summary(
        val expenseKop: Long,
        val incomeKop: Long,
        val lines: List<Line>,
        /** Траты без категории — ждут ответа. */
        val unknownKop: Long,
        val unknownCount: Int,
        /** Снято наличными и не объяснено надиктовками. */
        val cashOutKop: Long,
        val cashSpentKop: Long,
        /** Ушло на «Плати по миру» и сколько из этого разобрано чатом. */
        val platiInKop: Long,
        val platiSpentKop: Long,
        val questions: Int,
    )

    fun summary(entries: List<MoneyEntry>, from: Long, to: Long, withZf: Boolean): Summary {
        val span = entries.filter { it.live() && it.ts in from until to }
        val counted = span.filter { it.category.isNotBlank() && MoneyCategories.counts(it.category, withZf) }
        val income = counted.filter { MoneyCategories.of(it.category)?.income == true }
        val spend = counted.filter { MoneyCategories.of(it.category)?.income != true }
        val unknown = span.filter { it.category.isBlank() && it.rubKop < 0 }
        val lines = spend.groupBy { it.category }
            .map { (k, v) -> Line(k, v.sumOf { it.rubKop }, v.size) }
            .sortedBy { it.kop }
        val cashOut = span.filter { it.category == "cash" && it.rubKop < 0 && it.fromBank }.sumOf { it.rubKop }
        val cashSpent = span.filter { it.source == MoneyEntry.Source.VOICE && it.matchId.isBlank() && it.rubKop < 0 }
            .sumOf { it.rubKop }
        val platiIn = span.filter { it.category == "plati" }.sumOf { it.rubKop }
        val platiSpent = span.filter { it.source == MoneyEntry.Source.PLATI }.sumOf { it.rubKop }
        return Summary(
            expenseKop = spend.sumOf { it.rubKop } + unknown.sumOf { it.rubKop },
            incomeKop = income.sumOf { it.rubKop },
            lines = lines,
            unknownKop = unknown.sumOf { it.rubKop },
            unknownCount = unknown.size,
            cashOutKop = cashOut,
            cashSpentKop = cashSpent,
            platiInKop = platiIn,
            platiSpentKop = platiSpent,
            questions = span.count { it.question.isNotBlank() },
        )
    }
}
