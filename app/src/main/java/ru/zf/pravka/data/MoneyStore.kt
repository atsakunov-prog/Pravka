package ru.zf.pravka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.MoneyEntry
import ru.zf.pravka.core.MoneyRules

// Журнал денег: надиктованные траты, строки выписок, справочник получателей и
// сырые надиктовки. Один файл `money.json`.
//
// Данные НЕЗАМЕНИМЫЕ — те же правила, что у ленты Засечки и дневника еды
// (`docs/agreements.md`, железное правило 1):
//  - пишем только после чтения: стор, не дочитавший файл, файл не трогает;
//  - записи не удаляются — вычёркиваются (`dropped`), поэтому журнал может
//    только расти, и запись, в которой записей МЕНЬШЕ, чем было прочитано, —
//    это поломка, а не правка: её не пишем и говорим об этом в журнал;
//  - сырая надиктовка (`takes`) не удаляется никогда: модель могла ошибиться
//    в сумме, и правда — в том, что сказано.
//
// Здесь же — правила справочника с телефона (вписанные и запомненные
// ответами); они перебивают заводские из `assets/money_payees.txt`.
class MoneyStore(private val context: Context, private val log: (String) -> Unit = {}) {

    companion object {
        const val FILE_NAME = "money.json"
        /** [Push.result] пуша, ставшего записью. */
        const val MONEY = "запись"
    }

    /** Одна надиктовка: что сказано, во что обошёлся разбор. */
    data class Take(
        val id: Long,
        val text: String,
        val costUsd: Double = 0.0,
        val model: String = "",
    )

    /** Одна загрузка выписки: что пришло и сколько нового. */
    data class Import(
        val ts: Long,
        val kind: String,
        val owner: String,
        val rows: Int,
        val added: Int,
        val fromTs: Long,
        val toTs: Long,
        val note: String = "",
    )

    /**
     * Пойманное уведомление банка как есть — сырьё, как надиктовка: разбор
     * мог ошибиться, правда — в тексте. [result] — что из него вышло
     * («запись», «отказ — денег не списали», «не денежный…»).
     */
    data class Push(
        val key: String,
        val ts: Long,
        val pkg: String,
        val title: String,
        val text: String,
        val result: String,
    )

    data class State(
        val entries: List<MoneyEntry> = emptyList(),
        val takes: List<Take> = emptyList(),
        val rules: List<MoneyRules.Rule> = emptyList(),
        val imports: List<Import> = emptyList(),
        /**
         * Чат «Плати по миру» без шума: только денежные сообщения
         * (`PlatiChat.canonical`), накопленные за все вставки. Нужен, чтобы
         * пересобрать курс и даты, когда Тиньков за те же дни придёт позже
         * чата. Кодов привязки и рекламы здесь нет по построению.
         */
        val platiLog: String = "",
        /** Последние паттерны от Claude и когда они посчитаны: вкладка не платит за них при каждом открытии. */
        val insight: String = "",
        val insightTs: Long = 0L,
        val pushes: List<Push> = emptyList(),
        /** Остатки, вписанные владельцем: якоря баланса (`MoneyCashflow.Anchor`). */
        val balances: List<ru.zf.pravka.core.MoneyCashflow.Anchor> = emptyList(),
        /** Счета, отмеченные владельцем как счета ЗФ (имя баланса) — поверх заводских. */
        val zfAccounts: Set<String> = emptySet(),
        /** Заводские счета ЗФ, которые владелец снял: «это личный». */
        val notZfAccounts: Set<String> = emptySet(),
    )

    private val mutex = Mutex()
    private val file: File get() = File(DataRoot.dir(context), FILE_NAME)
    private var loaded = false
    private var loadedCount = 0

    private val _state = MutableStateFlow(State())
    val stateFlow: StateFlow<State> = _state

    suspend fun load(): State = mutex.withLock { ensureLoaded(); _state.value }

    fun byId(id: String): MoneyEntry? = _state.value.entries.firstOrNull { it.id == id }

    /** Свежая надиктовка: сырой текст и разобранные строки — сразу на диск, до «ОК». */
    suspend fun addTake(take: Take, drafts: List<MoneyEntry>) = mutex.withLock {
        ensureLoaded()
        val s = _state.value
        write(s.copy(takes = s.takes + take, entries = s.entries + drafts))
    }

    /** Поменять записи по номерам (правка, «ОК», вычеркнуть, связать). */
    suspend fun update(ids: Collection<String>, change: (MoneyEntry) -> MoneyEntry) = mutex.withLock {
        ensureLoaded()
        val set = ids.toSet()
        val s = _state.value
        write(s.copy(entries = s.entries.map { if (it.id in set) change(it) else it }))
    }

    /**
     * Пересчитать записи под замком: сверка читает и пишет ОДНО состояние, и
     * «ОК», нажатый посреди сверки, не затирается её устаревшей копией.
     */
    suspend fun transform(block: (State) -> List<MoneyEntry>) = mutex.withLock {
        ensureLoaded()
        write(_state.value.copy(entries = block(_state.value)))
    }

    /** Заменить записи целиком (сверка раздала связи и вопросы). Записей не может стать меньше. */
    suspend fun replaceAll(entries: List<MoneyEntry>) = mutex.withLock {
        ensureLoaded()
        write(_state.value.copy(entries = entries))
    }

    /**
     * Выписка: новые строки добавляются, знакомые (тот же номер) — не
     * удваиваются. У знакомой обновляется только то, что знает банк; то,
     * что решил владелец (категория, «для кого», связи, ответы), не трогается.
     */
    suspend fun mergeImport(incoming: List<MoneyEntry>, info: Import): Int = mutex.withLock {
        ensureLoaded()
        val s = _state.value
        val byId = s.entries.associateBy { it.id }
        var added = 0
        val updated = s.entries.toMutableList()
        val index = s.entries.withIndex().associate { it.value.id to it.index }
        for (e in incoming) {
            val old = byId[e.id]
            if (old == null) {
                updated.add(e)
                added++
            } else {
                val i = index[e.id] ?: continue
                updated[i] = old.copy(
                    rubKop = e.rubKop,
                    origMinor = e.origMinor,
                    currency = e.currency,
                    rubBasis = e.rubBasis,
                    ts = e.ts,
                    timeKnown = e.timeKnown,
                    note = old.note.ifBlank { e.note },
                )
            }
        }
        write(s.copy(entries = updated, imports = s.imports + info.copy(added = added)))
        added
    }

    /**
     * Пуш банка: сырьё — в [State.pushes], запись (если вышла) — в журнал.
     * Тот же пуш второй раз (обновление уведомления, обход шторки при
     * подключении службы) ничего не добавляет: false.
     */
    suspend fun addPush(push: Push, entry: MoneyEntry?): Boolean = mutex.withLock {
        ensureLoaded()
        val s = _state.value
        if (s.pushes.any { it.key == push.key }) return@withLock false
        // Незнакомые и не денежные храним последние 300 — чтобы было на чём
        // научить разбор новому виду; денежные — все, это сырьё записей.
        val skipped = s.pushes.filter { it.result != MONEY }
        val trimmed = if (push.result != MONEY && skipped.size >= 300) s.pushes - skipped.first() else s.pushes
        val entries = if (entry != null && s.entries.none { it.id == entry.id }) s.entries + entry else s.entries
        write(s.copy(pushes = trimmed + push, entries = entries))
        true
    }

    /**
     * Переразбор пушей (шаг переразбора истории, `core/HistoryFixes.kt`):
     * записи — из нового разбора сырья, сырым пушам, ставшим записью, — «запись».
     * Под замком, как сверка; записей не может стать меньше (`write`).
     */
    suspend fun applyReparse(block: (State) -> ru.zf.pravka.core.MoneyReparse.Out): ru.zf.pravka.core.MoneyReparse.Out =
        mutex.withLock {
            ensureLoaded()
            val s = _state.value
            val out = block(s)
            val pushes = if (out.nowMoney.isEmpty()) s.pushes
            else s.pushes.map { if (it.key in out.nowMoney) it.copy(result = MONEY) else it }
            if (out.changed + out.added > 0) write(s.copy(entries = out.entries, pushes = pushes))
            out
        }

    /** Владелец вписал остаток счёта: новый якорь, прежние остаются историей. */
    suspend fun addBalance(a: ru.zf.pravka.core.MoneyCashflow.Anchor) = mutex.withLock {
        ensureLoaded()
        write(_state.value.copy(balances = _state.value.balances + a))
    }

    /** Добавить записи, которых ещё нет (по номеру); знакомые не трогаются вовсе. */
    suspend fun addMissing(incoming: List<MoneyEntry>): Int = mutex.withLock {
        ensureLoaded()
        val s = _state.value
        val known = s.entries.map { it.id }.toHashSet()
        val fresh = incoming.filter { it.id !in known }
        if (fresh.isNotEmpty()) write(s.copy(entries = s.entries + fresh))
        fresh.size
    }

    /** Отметить счёт как счёт ЗФ или как личный. */
    suspend fun setZfAccount(name: String, zf: Boolean) = mutex.withLock {
        ensureLoaded()
        val s = _state.value
        write(
            s.copy(
                zfAccounts = if (zf) s.zfAccounts + name else s.zfAccounts - name,
                notZfAccounts = if (zf) s.notZfAccounts - name else s.notZfAccounts + name,
            )
        )
    }

    suspend fun setInsight(text: String, ts: Long) = mutex.withLock {
        ensureLoaded()
        write(_state.value.copy(insight = text, insightTs = ts))
    }

    suspend fun setPlatiLog(text: String) = mutex.withLock {
        ensureLoaded()
        write(_state.value.copy(platiLog = text))
    }

    suspend fun setRules(rules: List<MoneyRules.Rule>) = mutex.withLock {
        ensureLoaded()
        write(_state.value.copy(rules = rules))
    }

    /** Одно правило из ответа на вопрос сверки: то же название впредь раскладывается само. */
    suspend fun addRule(rule: MoneyRules.Rule) = mutex.withLock {
        ensureLoaded()
        val s = _state.value
        val rest = s.rules.filterNot {
            MoneyRules.norm(it.pattern) == MoneyRules.norm(rule.pattern) && it.sign == rule.sign && it.owner == rule.owner
        }
        write(s.copy(rules = rest + rule))
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val existed = withContext(Dispatchers.IO) { file.exists() }
        val parsed = withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { text -> parse(JSONObject(text)) }
        }
        // Файл был, а прочитать не вышло (он ушёл в карантин `.corrupt`, и
        // `.prev` не спас) — stateFlow пуст, и первая же запись положила бы
        // пустоту на место журнала. «Прочитано» ставим, только если файла
        // не было вовсе или он разобрался.
        if (parsed == null && existed) {
            log("деньги: money.json не читается — пишу только в память, файл не трогаю")
            throw IllegalStateException("Журнал денег не прочитался — запись остановлена, файл цел")
        }
        loaded = true
        if (parsed != null) _state.value = parsed
        loadedCount = _state.value.entries.size
    }

    private fun write(next: State) {
        // Незаменимые данные: журнал только растёт. Меньше записей, чем
        // прочитано, — не правка, а ошибка кода; пишем в память, не на диск.
        if (next.entries.size < loadedCount || next.takes.size < _state.value.takes.size) {
            log("деньги: отказ записи — было ${loadedCount} записей / ${_state.value.takes.size} надиктовок, " +
                "стало бы ${next.entries.size} / ${next.takes.size}")
            return
        }
        _state.value = next
        loadedCount = next.entries.size
        val json = serialize(next).toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    // ---- JSON ----

    private fun parse(o: JSONObject): State {
        val entries = mutableListOf<MoneyEntry>()
        o.optJSONArray("entries")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let { entries.add(entryOf(it)) }
        }
        val takes = mutableListOf<Take>()
        o.optJSONArray("takes")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                takes.add(Take(it.optLong("id"), it.optString("text"), it.optDouble("cost", 0.0), it.optString("model")))
            }
        }
        val rules = mutableListOf<MoneyRules.Rule>()
        o.optJSONArray("rules")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                rules.add(
                    MoneyRules.Rule(
                        pattern = it.optString("p"), category = it.optString("c"), who = it.optString("w"),
                        sign = it.optInt("s", 0), owner = it.optString("o"), comment = it.optString("n"),
                    )
                )
            }
        }
        val imports = mutableListOf<Import>()
        o.optJSONArray("imports")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                imports.add(
                    Import(
                        it.optLong("ts"), it.optString("kind"), it.optString("owner"), it.optInt("rows"),
                        it.optInt("added"), it.optLong("from"), it.optLong("to"), it.optString("note"),
                    )
                )
            }
        }
        val pushes = mutableListOf<Push>()
        o.optJSONArray("pushes")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                pushes.add(Push(it.optString("k"), it.optLong("ts"), it.optString("pkg"), it.optString("t"), it.optString("x"), it.optString("r")))
            }
        }
        val balances = mutableListOf<ru.zf.pravka.core.MoneyCashflow.Anchor>()
        o.optJSONArray("balances")?.let { a ->
            for (i in 0 until a.length()) a.optJSONObject(i)?.let {
                balances.add(ru.zf.pravka.core.MoneyCashflow.Anchor(it.optString("acc"), it.optLong("ts"), it.optLong("kop"), it.optString("src")))
            }
        }
        fun strings(key: String): Set<String> = o.optJSONArray(key)?.let { a -> (0 until a.length()).map { a.optString(it) }.toSet() } ?: emptySet()
        return State(
            entries, takes, rules, imports, o.optString("plati"), o.optString("insight"), o.optLong("insightTs"), pushes, balances,
            strings("zfAccounts"), strings("notZfAccounts"),
        )
    }

    private fun entryOf(o: JSONObject) = MoneyEntry(
        id = o.optString("id"),
        owner = o.optString("owner"),
        source = MoneyEntry.Source.of(o.optString("src")),
        ts = o.optLong("ts"),
        timeKnown = o.optBoolean("tk", true),
        rubKop = o.optLong("rub"),
        origMinor = o.optLong("orig", o.optLong("rub")),
        currency = o.optString("cur", "RUB"),
        rubBasis = MoneyEntry.RubBasis.of(o.optString("basis")),
        what = o.optString("what"),
        note = o.optString("note"),
        mcc = o.optString("mcc"),
        bankCategory = o.optString("bcat"),
        account = o.optString("acc"),
        category = o.optString("cat"),
        who = o.optString("who"),
        categoryBy = MoneyEntry.CategoryBy.of(o.optString("by")),
        draft = o.optBoolean("draft", false),
        dropped = o.optBoolean("dropped", false),
        matchId = o.optString("match"),
        question = o.optString("q"),
        takeId = o.optLong("take"),
        doubt = o.optString("doubt"),
        replacedBy = o.optString("repl"),
    )

    private fun serialize(s: State): JSONObject = JSONObject().apply {
        put("v", 1)
        put("entries", JSONArray().apply {
            for (e in s.entries) put(JSONObject().apply {
                put("id", e.id); put("owner", e.owner); put("src", e.source.key)
                put("ts", e.ts); put("tk", e.timeKnown); put("rub", e.rubKop)
                put("orig", e.origMinor); put("cur", e.currency); put("basis", e.rubBasis.key)
                put("what", e.what)
                if (e.note.isNotEmpty()) put("note", e.note)
                if (e.mcc.isNotEmpty()) put("mcc", e.mcc)
                if (e.bankCategory.isNotEmpty()) put("bcat", e.bankCategory)
                if (e.account.isNotEmpty()) put("acc", e.account)
                if (e.category.isNotEmpty()) put("cat", e.category)
                if (e.who.isNotEmpty()) put("who", e.who)
                if (e.categoryBy != MoneyEntry.CategoryBy.NONE) put("by", e.categoryBy.key)
                if (e.draft) put("draft", true)
                if (e.dropped) put("dropped", true)
                if (e.matchId.isNotEmpty()) put("match", e.matchId)
                if (e.question.isNotEmpty()) put("q", e.question)
                if (e.takeId != 0L) put("take", e.takeId)
                if (e.doubt.isNotEmpty()) put("doubt", e.doubt)
                if (e.replacedBy.isNotEmpty()) put("repl", e.replacedBy)
            })
        })
        put("takes", JSONArray().apply {
            for (t in s.takes) put(JSONObject().apply {
                put("id", t.id); put("text", t.text); put("cost", t.costUsd); put("model", t.model)
            })
        })
        put("rules", JSONArray().apply {
            for (r in s.rules) put(JSONObject().apply {
                put("p", r.pattern); put("c", r.category)
                if (r.who.isNotEmpty()) put("w", r.who)
                if (r.sign != 0) put("s", r.sign)
                if (r.owner.isNotEmpty()) put("o", r.owner)
                if (r.comment.isNotEmpty()) put("n", r.comment)
            })
        })
        if (s.platiLog.isNotEmpty()) put("plati", s.platiLog)
        if (s.insight.isNotEmpty()) { put("insight", s.insight); put("insightTs", s.insightTs) }
        put("zfAccounts", JSONArray().apply { s.zfAccounts.forEach { put(it) } })
        put("notZfAccounts", JSONArray().apply { s.notZfAccounts.forEach { put(it) } })
        put("balances", JSONArray().apply {
            for (b in s.balances) put(JSONObject().apply {
                put("acc", b.account); put("ts", b.ts); put("kop", b.kop); put("src", b.source)
            })
        })
        put("pushes", JSONArray().apply {
            for (p in s.pushes) put(JSONObject().apply {
                put("k", p.key); put("ts", p.ts); put("pkg", p.pkg); put("t", p.title); put("x", p.text); put("r", p.result)
            })
        })
        put("imports", JSONArray().apply {
            for (i in s.imports) put(JSONObject().apply {
                put("ts", i.ts); put("kind", i.kind); put("owner", i.owner); put("rows", i.rows)
                put("added", i.added); put("from", i.fromTs); put("to", i.toTs)
                if (i.note.isNotEmpty()) put("note", i.note)
            })
        })
    }
}
