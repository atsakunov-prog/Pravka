package ru.zf.pravka.core

import java.time.Instant
import java.time.LocalDate
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import ru.zf.pravka.data.CbrRates
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.MoneyStore
import ru.zf.pravka.data.Stats
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.askMoney
import ru.zf.pravka.provider.hintPayees
import ru.zf.pravka.provider.interpretMoneyAnswer
import ru.zf.pravka.provider.moneyPatterns
import ru.zf.pravka.provider.parseMoney

// Деньги: четвёртый движок рядом с Правкой, Засечкой и Разноской. Три дороги
// в один журнал (`MoneyStore`):
//
//  - ГОЛОС: кнопка «₽» → Опус разбирает наговор → черновики на диске → плашка
//    → «ОК». До «ОК» трат нет ни в итогах, ни в сверке — как у Разноски;
//  - ВЫПИСКИ: Тиньков, Альфа, чат «Плати по миру» → разбор кодом
//    (`BankStatements`, `PlatiChat`) → журнал без повторов;
//  - СВЕРКА: после каждой выписки и каждого «ОК» — `MoneyMatch`: справочник,
//    склейки, вопросы. Модель зовётся только по кнопке «Подсказать» — за
//    догадками по неузнанным получателям.
class MoneyEngine(
    private val claude: ClaudeProvider,
    private val dictionary: DictionaryApplier,
    private val dictionaryStore: DictionaryStore,
    private val store: MoneyStore,
    private val rates: CbrRates,
    private val stats: Stats,
    private val eventLog: EventLog,
    /** Чей это телефон — ключ профиля установки (data/Profile.kt): «sasha», «marianna»… */
    private val owner: () -> String = { "sasha" },
    /** Заводской справочник (`assets/money_payees.txt`): идёт ПОСЛЕ правил владельца. */
    private val factory: () -> List<MoneyRules.Rule> = { emptyList() },
    /** Заводские остатки счетов (`assets/money_balances.txt`): якоря баланса до первого пуша. */
    private val factoryBalances: () -> List<MoneyCashflow.Anchor> = { emptyList() },
    /** Записи со слов владельца (`assets/money_manual.txt`): наличные и прочее мимо выписок. */
    private val factoryManual: () -> String = { "" },
    /** Текст файла остатков — из него же строки «счёт ЗФ | …». */
    private val factoryAccountsText: () -> String = { "" },
) {

    /**
     * Все правила: сначала владельца (вписанные и запомненные ответами), потом
     * заводские. Из двух одинаково точных побеждает первое — то есть его.
     */
    fun allRules(): List<MoneyRules.Rule> = store.stateFlow.value.rules + factory()

    fun factoryCount(): Int = factory().size

    private fun noon(day: LocalDate): Long =
        day.atTime(12, 0).atZone(BankStatements.MSK).toInstant().toEpochMilli()

    private fun today(): LocalDate = LocalDate.now(BankStatements.MSK)

    // ---- Голос ----

    /** Наговор → черновики. Возвращает номер тейка: по нему плашка показывает разобранное. */
    suspend fun dictate(rawTranscript: String): Result<Long> {
        val transcript = rawTranscript.trim()
        if (transcript.isBlank()) return Result.failure(IllegalArgumentException("Пустой наговор"))
        val state = store.load()
        val prepared = dictionary.prepare(transcript)
        val parse = claude.parseMoney(
            transcript = prepared.text,
            dictBlock = prepared.dictBlock,
            payeesBlock = MoneyRules.toText(state.rules + factory()),
        ).getOrElse { e ->
            eventLog.add("деньги: разбор не вышел — ${e.message}")
            // Сырой наговор не теряется и без разбора: он в журнале тейков.
            store.addTake(MoneyStore.Take(System.currentTimeMillis(), transcript), emptyList())
            return Result.failure(e)
        }
        runCatching { dictionaryStore.incrementHits(prepared.firedIds) }
        runCatching { stats.recordAux(parse.costUsd, parse.tokensIn, parse.tokensOut, route = ModelRoute.MONEY.key) }
        val takeId = System.currentTimeMillis()
        // Курсы ЦБ — заранее, по одному запросу на день: `toDrafts` синхронный.
        val needed = parse.items.filter { it.currency.uppercase() !in setOf("RUB", "RUR", "") }
            .map { runCatching { LocalDate.parse(it.date) }.getOrNull() ?: today() }.toSet()
        val byDay = needed.associateWith { rates.on(it) }
        val drafts = MoneyVoice.toDrafts(
            items = parse.items,
            takeId = takeId,
            owner = owner(),
            today = today(),
            noonTs = ::noon,
            takeTs = takeId,
            rates = { cur, day -> byDay[day]?.get(cur.uppercase()) },
        )
        store.addTake(MoneyStore.Take(takeId, transcript, parse.costUsd, parse.model), drafts)
        val spent = String.format(java.util.Locale.US, "%.3f", parse.costUsd)
        eventLog.add("деньги: ${transcript.length} зн. → записей ${drafts.size}, сомнений ${drafts.count { it.doubt.isNotBlank() }}, $spent USD")
        return Result.success(takeId)
    }

    fun draftsOf(takeId: Long): List<MoneyEntry> =
        store.stateFlow.value.entries.filter { it.takeId == takeId && it.draft && !it.dropped }

    /** Все черновики, что ждут «ОК», свежие сверху. */
    fun pendingDrafts(): List<MoneyEntry> =
        store.stateFlow.value.entries.filter { it.draft && !it.dropped }.sortedByDescending { it.ts }

    /** «ОК» на плашке: отмеченные — в журнал, снятые — вычеркнуты (но не удалены). */
    suspend fun confirm(takeId: Long, chosen: Collection<String>) {
        val all = draftsOf(takeId).map { it.id }
        val keep = chosen.toSet()
        store.update(all) {
            if (it.id in keep) it.copy(draft = false, categoryBy = MoneyEntry.CategoryBy.OWNER)
            else it.copy(draft = false, dropped = true)
        }
        reconcile()
    }

    suspend fun drop(ids: Collection<String>) {
        store.update(ids) { it.copy(dropped = true) }
        reconcile()
    }

    /**
     * Правка руками: сумма, что это, категория, для кого. Сумма в рублях
     * от владельца — правда; если запись была в валюте, её рубли больше не
     * «предварительные».
     */
    suspend fun edit(id: String, rubKop: Long? = null, what: String? = null, category: String? = null, who: String? = null) {
        store.update(listOf(id)) { e ->
            e.copy(
                rubKop = rubKop?.let { if (e.rubKop < 0) -kotlin.math.abs(it) else kotlin.math.abs(it) } ?: e.rubKop,
                origMinor = if (rubKop != null && e.currency == "RUB") (if (e.rubKop < 0) -kotlin.math.abs(rubKop) else kotlin.math.abs(rubKop)) else e.origMinor,
                rubBasis = if (rubKop != null && e.currency != "RUB") MoneyEntry.RubBasis.SAID else e.rubBasis,
                what = what?.trim()?.ifEmpty { null } ?: e.what,
                category = category ?: e.category,
                who = who ?: e.who,
                categoryBy = if (category != null || who != null) MoneyEntry.CategoryBy.OWNER else e.categoryBy,
                doubt = if (rubKop != null) "" else e.doubt,
                question = if (category != null) "" else e.question,
            )
        }
        if (!store.stateFlow.value.entries.first { it.id == id }.draft) reconcile()
    }

    // ---- Выписки ----

    data class ImportOutcome(val kind: String, val rows: Int, val added: Int, val note: String)

    /**
     * Любой текст выписки: CSV Тинькова или Альфы, или вставленный чат
     * «Плати по миру». Что это — решает шапка, а не имя файла.
     */
    // Тысячи строк и сверка по всему журналу — не на главном потоке (Fold:
    // ничего тяжёлого там, где живут окна).
    suspend fun import(text: String): Result<ImportOutcome> = withContext(Dispatchers.Default) { runCatching {
        val clean = text.trimStart('\uFEFF')
        when {
            BankStatements.detect(clean) == BankStatements.Kind.TINKOFF -> importBank("Тиньков", BankStatements.tinkoff(clean, owner()))
            BankStatements.detect(clean) == BankStatements.Kind.ALFA -> importBank("Альфа", BankStatements.alfa(clean, "marianna"))
            BankStatements.detect(clean) == BankStatements.Kind.TBIZ -> importBank("ЗФ", BankStatements.tbiz(clean, owner()))
            PlatiChat.looksLike(clean) -> importPlati(clean)
            else -> throw IllegalArgumentException(
                "Не узнал формат: жду CSV Тинькова (с колонкой «Сумма в валюте счёта»), CSV Альфы (operationDate…) или текст чата «Плати по миру»"
            )
        }
    } }

    /**
     * Файлы недельного пакета — байтами: CSV Тинькова и Альфы, .xlsx МКБ,
     * текст чата. Тиньков — первым: по нему чат «Плати по миру» узнаёт курс
     * и даты. Итог — строка на файл, для тоста.
     */
    suspend fun importFiles(files: List<ByteArray>): List<String> {
        val ordered = files.sortedBy { b ->
            if (!XlsxRead.isZip(b) && BankStatements.detect(decode(b)) == BankStatements.Kind.TINKOFF) 0 else 1
        }
        return ordered.map { b ->
            importBytes(b).fold(
                { o -> "${o.kind}: ${o.rows} строк, новых ${o.added}" + if (o.note.isNotBlank()) " (${o.note})" else "" },
                { e -> e.message ?: e.javaClass.simpleName },
            )
        }
    }

    suspend fun importBytes(bytes: ByteArray): Result<ImportOutcome> =
        if (XlsxRead.isZip(bytes)) withContext(Dispatchers.Default) {
            runCatching {
                val rows = XlsxRead.firstSheet(bytes)
                require(BankStatements.isMkb(rows)) {
                    "Это .xlsx, но не выписка МКБ: жду колонки «Дата транзакции» и «Содержание операции»"
                }
                importBank("МКБ", BankStatements.mkb(rows, "marianna"))
            }
        } else import(decode(bytes))

    /** UTF-8 (Тиньков, Альфа); если там кракозябры — windows-1251, как у старых выгрузок. */
    fun decode(bytes: ByteArray): String {
        val utf = String(bytes, Charsets.UTF_8)
        return if (utf.count { it == '\uFFFD' } > 3) String(bytes, charset("windows-1251")) else utf
    }

    private suspend fun importBank(kind: String, parsed: BankStatements.Parsed): ImportOutcome {
        val entries = parsed.entries
        val info = MoneyStore.Import(
            ts = System.currentTimeMillis(), kind = kind, owner = entries.firstOrNull()?.owner.orEmpty(),
            rows = entries.size, added = 0,
            fromTs = entries.minOfOrNull { it.ts } ?: 0L, toTs = entries.maxOfOrNull { it.ts } ?: 0L,
            note = if (parsed.skipped > 0) "пропущено ${parsed.skipped}: ${parsed.skippedWhy}" else "",
        )
        val added = store.mergeImport(entries, info)
        // Тиньков мог принести оплаты «Плати по миру» за дни, чат которых уже
        // лежит: пересобираем курс и даты покупок.
        if (kind == "Тиньков" && store.stateFlow.value.platiLog.isNotBlank()) rebuildPlati()
        reconcile()
        eventLog.add("деньги: $kind — строк ${entries.size}, новых $added" + if (info.note.isNotEmpty()) ", ${info.note}" else "")
        return ImportOutcome(kind, entries.size, added, info.note)
    }

    private suspend fun importPlati(text: String): ImportOutcome {
        val fresh = PlatiChat.parse(text)
        val before = store.stateFlow.value.platiLog
        // Прежний чат + новый; повторы от внахлёста выбрасывает сам разбор.
        val merged = PlatiChat.canonical(PlatiChat.parse(listOf(before, PlatiChat.canonical(fresh)).filter { it.isNotBlank() }.joinToString("\n\n")))
        store.setPlatiLog(merged)
        val built = rebuildPlati()
        reconcile()
        val note = buildList {
            if (built.unpaidRequests > 0) add("заявок без оплаты в Тинькове: ${built.unpaidRequests}")
            if (built.roughRate > 0) add("покупок по прикидочному курсу: ${built.roughRate} — пришли Тиньков за эти дни")
        }.joinToString("; ")
        eventLog.add("деньги: Плати по миру — событий ${fresh.size}, покупок ${built.entries.size}" + if (note.isNotEmpty()) ", $note" else "")
        return ImportOutcome("Плати по миру", fresh.size, built.added, note)
    }

    private data class PlatiBuilt(val entries: List<MoneyEntry>, val added: Int, val unpaidRequests: Int, val roughRate: Int)

    /** Чат + списания Тинькова → покупки в рублях; строки Тинькова получают свою роль (пополнение или комиссия). */
    private suspend fun rebuildPlati(): PlatiBuilt {
        val s = store.load()
        val events = PlatiChat.parse(s.platiLog)
        // Пуш об оплате «Плати по миру» — тоже списание: заявка из чата
        // узнаётся оплаченной сразу, не дожидаясь выписки. Заменённый выпиской
        // пуш не в счёт — там уже есть его строка.
        val debits = s.entries.filter {
            (it.source == MoneyEntry.Source.TINKOFF || (it.source == MoneyEntry.Source.PUSH && it.replacedBy.isEmpty())) && it.rubKop < 0 &&
                MoneyRules.norm(it.what).let { w -> w.contains("плати по миру") || w.contains("platipomiru") }
        }.map { PlatiChat.BankDebit(it.id, it.ts, -it.rubKop) }
        val built = PlatiChat.build(events, debits, owner(), importedAt = System.currentTimeMillis())
        val added = store.mergeImport(
            built.entries,
            MoneyStore.Import(
                ts = System.currentTimeMillis(), kind = "Плати по миру", owner = owner(), rows = built.entries.size, added = 0,
                fromTs = built.entries.minOfOrNull { it.ts } ?: 0L, toTs = built.entries.maxOfOrNull { it.ts } ?: 0L,
            ),
        )
        if (built.bankLinks.isNotEmpty()) {
            store.update(built.bankLinks.keys) { e ->
                if (e.categoryBy == MoneyEntry.CategoryBy.OWNER) e
                else e.copy(category = built.bankLinks[e.id].orEmpty(), categoryBy = MoneyEntry.CategoryBy.RULE)
            }
        }
        return PlatiBuilt(built.entries, added, built.unpaidRequests, built.roughRate)
    }

    // ---- Пуши ----

    /**
     * Уведомление от Т-Банка или из чата «Плати по миру» (служба
     * `MoneyNotificationListener`). Сырьё ложится всегда (кроме чужих
     * приложений), запись — если разбор узнал операцию. Возвращает, что
     * вышло, словами — для журнала событий.
     */
    suspend fun onPush(pkg: String, title: String, text: String, postedAt: Long): String = withContext(Dispatchers.Default) {
        when (BankPush.from(pkg, title)) {
            BankPush.From.OTHER -> "не банк"
            BankPush.From.TBANK -> {
                val out = BankPush.parse(title, text)
                val entry = (out as? BankPush.Outcome.Money)?.let { BankPush.entry(it.p, postedAt, owner(), title, text) }
                val result = if (entry != null) MoneyStore.MONEY else (out as BankPush.Outcome.Skip).why
                val fresh = store.addPush(MoneyStore.Push(BankPush.key(title, text), postedAt, pkg, title, text, result), entry)
                if (!fresh) return@withContext "уже был"
                if (entry != null) {
                    if (MoneyRules.norm(entry.what).contains("плати по миру") && store.stateFlow.value.platiLog.isNotBlank()) rebuildPlati()
                    reconcile()
                    eventLog.add("деньги: пуш — ${entry.what} ${MoneyFormat.rub(entry.rubKop, sign = true)}")
                }
                result
            }
            BankPush.From.PLATI_CHAT -> {
                // Сообщение бота без своей строки времени получает время уведомления:
                // по нему `PlatiChat` ставит дату покупке и пополнению.
                val first = text.trim().lineSequence().firstOrNull().orEmpty()
                val stamped = if (Regex("""^\d{2}\.\d{2}\.\d{4} \d{2}:\d{2}$""").matches(first)) text.trim()
                else java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm")
                    .format(Instant.ofEpochMilli(postedAt).atZone(BankStatements.MSK)) + "\n" + text.trim()
                val events = PlatiChat.parse(stamped)
                val result = if (events.isEmpty()) "чат: не денежное" else "чат: событий ${events.size}"
                // Коды привязки к Apple Pay / Google Pay в чате есть — их сырьём не храним.
                val keep = if (events.isEmpty()) "" else PlatiChat.canonical(events)
                val fresh = store.addPush(MoneyStore.Push(BankPush.key(title, text), postedAt, pkg, title, keep, result), null)
                if (fresh && events.isNotEmpty()) importPlati(stamped)
                if (fresh) result else "уже был"
            }
        }
    }

    // ---- Баланс ----

    /**
     * Все якоря остатков: заводской снимок, вписанные владельцем и «Доступно»
     * из пушей Т-Банка. У каждого счёта берётся самый поздний.
     */
    fun anchors(): List<MoneyCashflow.Anchor> {
        val s = store.stateFlow.value
        val cards = MoneyCashflow.cardMap(s.entries)
        val fromPushes = s.pushes.filter { it.result == MoneyStore.MONEY }.mapNotNull { p ->
            val parsed = (BankPush.parse(p.title, p.text) as? BankPush.Outcome.Money)?.p ?: return@mapNotNull null
            val bal = parsed.balanceKop ?: return@mapNotNull null
            // Без карты («счет RUB») не знаем, какой это счёт, — не гадаем.
            val account = cards[parsed.card] ?: return@mapNotNull null
            MoneyCashflow.Anchor(account, p.ts, bal, "пуш «${p.title}»", covers = setOf("push-" + p.key))
        }
        return factoryBalances() + s.balances + fromPushes
    }

    /**
     * На старте: записи со слов владельца, которых в журнале ещё нет, — туда.
     * Один раз на номер: вычеркнутую владельцем файл не воскрешает.
     */
    suspend fun seedManual() {
        val list = MoneyCashflow.parseManual(factoryManual(), owner())
        val (add, stale) = MoneyCashflow.syncManual(store.load().entries, list)
        val added = if (add.isNotEmpty()) store.addMissing(add) else 0
        if (stale.isNotEmpty()) store.update(stale) { it.copy(dropped = true, matchId = "", question = "") }
        if (added > 0 || stale.isNotEmpty()) {
            eventLog.add("деньги: со слов владельца — добавлено $added, прежних версий вычеркнуто ${stale.size}")
            // Пары прежних версий — с другой стороны снимаем тоже: пусть сверка склеит заново.
            if (stale.isNotEmpty()) store.update(store.stateFlow.value.entries.filter { it.matchId in stale }.map { it.id }) { it.copy(matchId = "") }
            reconcile()
        }
    }

    /** Счета ЗФ: заводские (файл остатков) плюс отмеченные владельцем, минус снятые им. */
    fun zfAccounts(): Set<String> {
        val s = store.stateFlow.value
        return (MoneyCashflow.parseZfAccounts(factoryAccountsText()) + s.zfAccounts) - s.notZfAccounts
    }

    suspend fun setZfAccount(name: String, zf: Boolean) {
        store.setZfAccount(name, zf)
        eventLog.add("деньги: счёт $name — " + if (zf) "ЗФ" else "личный")
    }

    /** Кнопки «Личное · ЗФ» → фильтр для итогов, ДДС и счетов. */
    fun scope(personal: Boolean, zf: Boolean): MoneyScope =
        MoneyScope.of(personal, zf, store.stateFlow.value.entries, zfAccounts())

    /** Владелец вписал остаток счёта сейчас. */
    suspend fun setBalance(account: String, kop: Long) {
        store.addBalance(MoneyCashflow.Anchor(account, System.currentTimeMillis(), kop, "вписано"))
        eventLog.add("деньги: остаток $account — ${MoneyFormat.rub(kop)}")
    }

    // ---- Сверка ----

    suspend fun reconcile(): MoneyMatch.Result {
        var result: MoneyMatch.Result? = null
        withContext(Dispatchers.Default) {
            store.transform { st -> MoneyMatch.run(st.entries, st.rules + factory(), System.currentTimeMillis()).also { result = it }.entries }
        }
        return result ?: MoneyMatch.Result(store.stateFlow.value.entries, 0, 0, 0, 0)
    }

    /** Открытые вопросы группами: один получатель — один вопрос, свежие сверху. */
    data class Question(val key: String, val text: String, val entries: List<MoneyEntry>, val hintCategory: String = "", val hintWho: String = "")

    fun questions(): List<Question> {
        val open = store.stateFlow.value.entries.filter { it.question.isNotBlank() && !it.dropped && !it.draft }
        return open.groupBy {
            when {
                // «Пуш без пары в выписке» — вопрос про одну операцию, не про получателя.
                it.question == MoneyMatch.ORPHAN_PUSH -> "o:" + it.id
                it.fromBank -> "b:" + MoneyRules.norm(it.what)
                else -> "v:" + it.id
            }
        }
            .map { (k, v) ->
                val model = v.firstOrNull { it.categoryBy == MoneyEntry.CategoryBy.MODEL && it.category.isNotBlank() }
                Question(k, v.first().question, v.sortedByDescending { it.ts }, model?.category.orEmpty(), model?.who.orEmpty())
            }
            .sortedByDescending { q -> q.entries.maxOf { it.ts } }
    }

    /**
     * Ответ на вопрос. Для строк выписки [remember] = true (по умолчанию)
     * записывает правило в справочник: тот же получатель впредь раскладывается
     * сам и больше не спрашивается.
     */
    suspend fun answer(q: Question, category: String, who: String, remember: Boolean = true) {
        store.update(q.entries.map { it.id }) {
            it.copy(category = category, who = who, categoryBy = MoneyEntry.CategoryBy.OWNER, question = "")
        }
        val first = q.entries.first()
        if (remember && first.fromBank) {
            val sign = if (q.entries.all { it.rubKop < 0 }) -1 else if (q.entries.all { it.rubKop > 0 }) 1 else 0
            store.addRule(MoneyRules.Rule(pattern = first.what.trim(), category = category, who = who, sign = sign))
        }
        reconcile()
    }

    /** «Это наличные» — надиктованное без пары в выписке. */
    suspend fun markCash(ids: Collection<String>) {
        store.update(ids) { it.copy(account = MoneyEntry.CASH, question = "") }
        reconcile()
    }

    /**
     * Спросить модель о неузнанных: догадка категории ставится как MODEL
     * (её перебьёт любой ответ владельца и любое правило справочника), вопрос
     * — её словами.
     */
    suspend fun hint(): Result<Int> {
        val s = store.load()
        val groups = s.entries.filter { it.fromBank && it.live() && it.category.isBlank() }
            .groupBy { MoneyRules.norm(it.what) }
        if (groups.isEmpty()) return Result.success(0)
        // Не больше сотни групп за раз: самые крупные по деньгам — первыми.
        val top = groups.entries.sortedBy { g -> g.value.sumOf { it.rubKop } }.take(100)
        val block = top.joinToString("\n") { (key, v) ->
            val e = v.first()
            listOf(
                key, e.what, v.size.toString(), MoneyFormat.rub(v.sumOf { it.rubKop }),
                v.firstOrNull { it.note.isNotBlank() }?.note.orEmpty().take(60),
                e.mcc, e.bankCategory,
            ).joinToString(" | ")
        }
        val hints = claude.hintPayees(block, MoneyRules.toText(s.rules + factory())).getOrElse { return Result.failure(it) }
        runCatching { stats.recordAux(hints.costUsd, hints.tokensIn, hints.tokensOut, route = ModelRoute.MONEY_MATCH.key) }
        val byKey = hints.groups.associateBy { it.key }
        val ids = top.flatMap { it.value }.map { it.id }
        store.update(ids) { e ->
            val h = byKey[MoneyRules.norm(e.what)] ?: return@update e
            if (e.categoryBy == MoneyEntry.CategoryBy.OWNER) return@update e
            e.copy(
                category = if (h.sure) h.category else e.category,
                who = if (h.sure) h.who.ifEmpty { e.who } else e.who,
                categoryBy = if (h.sure && h.category.isNotBlank()) MoneyEntry.CategoryBy.MODEL else e.categoryBy,
                question = if (h.sure) "" else h.question.ifBlank { e.question },
            )
        }
        eventLog.add("деньги: подсказки — групп ${top.size}, уверенных ${hints.groups.count { it.sure }}")
        return Result.success(hints.groups.size)
    }

    // ---- Спросить Claude и паттерны ----

    private suspend fun context(): String {
        val s = store.load()
        return withContext(Dispatchers.Default) { MoneyContext.build(s.entries, today()) }
    }

    /** Вопрос владельца по его деньгам: ответ текстом, по выжимке журнала. */
    suspend fun ask(question: String): Result<String> {
        val data = context()
        val r = claude.askMoney(question, data).getOrElse { return Result.failure(it) }
        runCatching { stats.recordAux(r.costUsd, r.tokensIn, r.tokensOut, route = ModelRoute.MONEY_ASK.key) }
        eventLog.add("деньги: вопрос ${question.length} зн. → ответ ${r.text.length} зн.")
        return Result.success(r.text)
    }

    /** Паттерны за год — считаются по кнопке и лежат в журнале до следующего раза. */
    suspend fun patterns(): Result<String> {
        val data = context()
        val r = claude.moneyPatterns(data).getOrElse { return Result.failure(it) }
        runCatching { stats.recordAux(r.costUsd, r.tokensIn, r.tokensOut, route = ModelRoute.MONEY_PATTERNS.key) }
        store.setInsight(r.text, System.currentTimeMillis())
        return Result.success(r.text)
    }

    /**
     * «Сказать» на карточке вопроса: владелец объясняет голосом, Claude
     * превращает это в категорию и «для кого»; постоянный получатель
     * запоминается правилом с комментарием его словами.
     */
    /**
     * Микрофон на строке журнала (владелец, 24.09.2026: «IP carenko… понял,
     * что это полиграфия… нажать и сказать, что это такое, и чтобы сразу по
     * всем таким же операциям всё пересчиталось»). Все операции того же
     * получателя — по имени без номера кассы (`MoneyMerchants.canonical`), за
     * всё время — разом; ответ ЗАПОМИНАЕТСЯ правилом всегда: владелец сам
     * объясняет получателя, это и есть «записать в базу».
     */
    suspend fun explainPayee(entry: MoneyEntry, spoken: String): Result<Pair<ClaudeProvider.MoneyAnswer, Int>> {
        val name = MoneyMerchants.canonical(entry.what)
        val same = store.load().entries.filter {
            !it.draft && !it.dropped && it.replacedBy.isEmpty() && it.source != MoneyEntry.Source.VOICE &&
                MoneyMerchants.canonical(it.what).equals(name, ignoreCase = true)
        }.ifEmpty { listOf(entry) }
        val q = Question("p:" + MoneyRules.norm(name), "", same.sortedByDescending { it.ts })
        return answerByVoice(q, spoken, forceRemember = true, pattern = name).map { it to same.size }
    }

    suspend fun answerByVoice(
        q: Question,
        spoken: String,
        forceRemember: Boolean = false,
        pattern: String? = null,
    ): Result<ClaudeProvider.MoneyAnswer> {
        val a = claude.interpretMoneyAnswer(MoneyContext.card(q.entries), spoken, MoneyRules.toText(allRules()))
            .getOrElse { return Result.failure(it) }
        runCatching { stats.recordAux(a.costUsd, a.tokensIn, a.tokensOut, route = ModelRoute.MONEY.key) }
        if (a.unsure || a.category.isBlank()) return Result.success(a)
        store.update(q.entries.map { it.id }) {
            it.copy(category = a.category, who = a.who, categoryBy = MoneyEntry.CategoryBy.OWNER, question = "")
        }
        val first = q.entries.first()
        val remember = a.remember || forceRemember
        if (remember && (first.fromBank || first.source == MoneyEntry.Source.MANUAL)) {
            val sign = if (q.entries.all { it.rubKop < 0 }) -1 else if (q.entries.all { it.rubKop > 0 }) 1 else 0
            store.addRule(
                MoneyRules.Rule(
                    pattern = (pattern ?: first.what).trim(), category = a.category, who = a.who, sign = sign,
                    // Получатель на счетах обоих — правило на оба; на одном — только на его.
                    owner = q.entries.map { it.owner }.distinct().singleOrNull().orEmpty(), comment = a.comment,
                )
            )
        }
        eventLog.add("деньги: голосом ответ — ${first.what} → ${a.category}, операций ${q.entries.size}" + if (remember) ", запомнил" else "")
        reconcile()
        return Result.success(a)
    }

    // ---- Справочник ----

    suspend fun setRulesText(text: String): List<String> {
        val parsed = MoneyRules.parseText(text)
        if (parsed.errors.isEmpty() || parsed.rules.isNotEmpty()) {
            store.setRules(parsed.rules)
            reconcile()
        }
        return parsed.errors
    }

    fun rulesText(): String = MoneyRules.toText(store.stateFlow.value.rules)

    /** Начало недели (понедельник, 00:00 по Москве) для [ts]. */
    fun weekStart(ts: Long): Long {
        val day = Instant.ofEpochMilli(ts).atZone(BankStatements.MSK).toLocalDate()
        val monday = day.minusDays((day.dayOfWeek.value - 1).toLong())
        return monday.atStartOfDay(BankStatements.MSK).toInstant().toEpochMilli()
    }
}
