package ru.zf.pravka.core

import org.json.JSONObject

// Общие Деньги двух телефонов (25.09.2026). Владелец: «деньги у нас с
// Марианной общие… должно сливаться в единую базу вместе с каждым
// изменением»; «кто главнее в категориях? Никто, побеждает последняя правка».
//
// Устроено как журнал событий, а не как пересылка базы целиком: две базы,
// перекладываемые друг на друга, перетирали бы правки. Каждый телефон пишет
// ТОЛЬКО свой журнал («у сущности X поля стали такими в момент t на
// устройстве d») и читает чужие. Сложенные вместе журналы дают одно и то же
// на любом телефоне: по каждому полю побеждает последнее событие (время, при
// равенстве — имя устройства). Складывать можно в любом порядке и сколько
// угодно раз — итог тот же, поэтому оборванный обмен просто повторяется.
//
// Что едет, а что нет:
//  - записи журнала — факты (сумма, время, что, счёт) и решения (категория,
//    «для кого», вычеркнута, связи). Черновики до «ОК» не едут: их одобряет
//    тот, кто надиктовал. Вопрос сверки и сомнение модели не едут — каждый
//    телефон выводит их сам;
//  - справочник владельца (правила), вписанные остатки, счета ЗФ;
//  - сырьё (надиктовки, пуши, выписки) остаётся на своём телефоне: запись из
//    него уже едет.
//
// Два исключения из «последней правки»:
//  - категория, поставленная ЧЕЛОВЕКОМ, сильнее справочника и модели, когда
//    бы те ни сработали (то же правило, что внутри одного телефона);
//  - первое появление сущности в журнале — не решение, а «так было до
//    журнала»: оно пишется моментом [T_FIRST] и уступает любой настоящей
//    правке. Иначе первый обмен Марианны («ничего не вычеркнуто») перекрыл
//    бы вычеркнутое Сашей до подключения.
//
// Записи никогда не удаляются: событие может поменять поля или добавить
// запись, но не убрать её (железное правило 1).
//
// Файл без Android: проверяется JVM-тестом `MoneySyncTest`.
/** Всё, что едет между телефонами, — плоско: сущность → поле → значение; пустая строка — по умолчанию. */
typealias SyncFlat = Map<String, Map<String, String>>

object MoneySync {

    /** У сущности [id] поля [f] стали такими в момент [t] на устройстве [d]. */
    data class Event(val id: String, val t: Long, val d: String, val f: Map<String, String>)

    /** Значение поля и кто его поставил. */
    data class Cell(val v: String, val t: Long, val d: String)

    /** Момент первого появления: меньше любого настоящего времени. */
    const val T_FIRST = 1L

    /** Поле «категория · для кого · кто поставил» — одним значением: они меняются вместе. */
    const val CAT = "cat"

    private const val SEP = '\u001F'

    const val ENTRY = "e:"
    const val RULE = "r:"
    const val BALANCE = "b:"
    const val ZF = "z:"

    /** Поле правила «удалено». */
    const val DELETED = "x"

    // ---- Кто побеждает ----

    private fun rank(field: String, v: String): Int =
        if (field == CAT && v.substringAfterLast(SEP, "") == MoneyEntry.CategoryBy.OWNER.key) 1 else 0

    private fun order(a: Cell, b: Cell): Int = when {
        a.t != b.t -> a.t.compareTo(b.t)
        a.d != b.d -> a.d.compareTo(b.d)
        else -> a.v.compareTo(b.v)
    }

    /** Побеждает ли [new] прежнее [old] значение поля [field]. */
    fun wins(field: String, new: Cell, old: Cell?): Boolean {
        if (old == null) return true
        val rn = rank(field, new.v)
        val ro = rank(field, old.v)
        if (rn != ro) return rn > ro
        return order(new, old) > 0
    }

    /** Сложенные журналы всех телефонов. Складывать можно в любом порядке и повторно. */
    class Merged {
        val cells = HashMap<String, HashMap<String, Cell>>()

        /** Сложить события; вернуть сущности, у которых поменялось хоть одно поле. */
        fun fold(events: Iterable<Event>): Set<String> {
            val touched = HashSet<String>()
            for (e in events) {
                val row = cells.getOrPut(e.id) { HashMap() }
                for ((field, v) in e.f) {
                    val c = Cell(v, e.t, e.d)
                    val old = row[field]
                    if (wins(field, c, old)) {
                        if (old?.v != v) touched.add(e.id)
                        row[field] = c
                    }
                }
            }
            return touched
        }

        fun has(id: String): Boolean = cells.containsKey(id)

        fun values(id: String): Map<String, String> = cells[id]?.mapValues { it.value.v }.orEmpty()

        fun flat(): SyncFlat = cells.mapValues { (_, row) -> row.mapValues { it.value.v } }
    }

    // ---- Что сейчас на телефоне ----

    /** То, что едет, из базы телефона. Черновики — нет. */
    fun flatten(
        entries: List<MoneyEntry>,
        rules: List<MoneyRules.Rule>,
        balances: List<MoneyCashflow.Anchor>,
        zf: Set<String>,
        notZf: Set<String>,
    ): SyncFlat {
        val out = LinkedHashMap<String, Map<String, String>>()
        for (e in entries) if (!e.draft) out[ENTRY + e.id] = entryFields(e)
        for (r in rules) {
            val id = ruleId(r)
            // Два правила одного ключа — первое: оно и побеждает в справочнике.
            if (id !in out) out[id] = ruleFields(r)
        }
        for (b in balances) out[balanceId(b)] = balanceFields(b)
        for (n in notZf) out[ZF + n] = mapOf("zf" to "0")
        for (n in zf) out[ZF + n] = mapOf("zf" to "1")
        return out
    }

    fun entryFields(e: MoneyEntry): Map<String, String> = linkedMapOf(
        "own" to e.owner,
        "src" to e.source.key,
        "ts" to e.ts.toString(),
        "tk" to if (e.timeKnown) "" else "0",
        "rub" to e.rubKop.toString(),
        "orig" to if (e.origMinor == e.rubKop) "" else e.origMinor.toString(),
        "cur" to if (e.currency == "RUB") "" else e.currency,
        "basis" to if (e.rubBasis == MoneyEntry.RubBasis.BANK) "" else e.rubBasis.key,
        "what" to e.what,
        "note" to e.note,
        "mcc" to e.mcc,
        "bcat" to e.bankCategory,
        "acc" to e.account,
        CAT to cat(e.category, e.who, e.categoryBy),
        "drop" to if (e.dropped) "1" else "",
        "match" to e.matchId,
        "take" to if (e.takeId == 0L) "" else e.takeId.toString(),
        "repl" to e.replacedBy,
    )

    fun cat(category: String, who: String, by: MoneyEntry.CategoryBy): String =
        if (category.isEmpty() && who.isEmpty() && by == MoneyEntry.CategoryBy.NONE) ""
        else "$category$SEP$who$SEP${by.key}"

    /** Запись из полей журнала поверх [base] (для новой — пустой). */
    fun withFields(base: MoneyEntry, f: Map<String, String>): MoneyEntry {
        var e = base
        fun long(v: String) = v.toLongOrNull() ?: 0L
        for ((k, v) in f) e = when (k) {
            "own" -> e.copy(owner = v)
            "src" -> e.copy(source = MoneyEntry.Source.of(v))
            "ts" -> e.copy(ts = long(v))
            "tk" -> e.copy(timeKnown = v != "0")
            "rub" -> e.copy(rubKop = long(v))
            "cur" -> e.copy(currency = v.ifEmpty { "RUB" })
            "basis" -> e.copy(rubBasis = if (v.isEmpty()) MoneyEntry.RubBasis.BANK else MoneyEntry.RubBasis.of(v))
            "what" -> e.copy(what = v)
            "note" -> e.copy(note = v)
            "mcc" -> e.copy(mcc = v)
            "bcat" -> e.copy(bankCategory = v)
            "acc" -> e.copy(account = v)
            CAT -> {
                val p = v.split(SEP)
                e.copy(
                    category = p.getOrElse(0) { "" },
                    who = p.getOrElse(1) { "" },
                    categoryBy = MoneyEntry.CategoryBy.of(p.getOrElse(2) { "" }),
                )
            }
            "drop" -> e.copy(dropped = v == "1")
            "match" -> e.copy(matchId = v)
            "take" -> e.copy(takeId = long(v))
            "repl" -> e.copy(replacedBy = v)
            else -> e
        }
        // «orig» пуст — «как в рублях»: ставится ПОСЛЕ суммы, в каком бы
        // порядке ни пришли поля, и тянется за новой суммой, если не задан.
        val orig = f["orig"] ?: entryFields(base).getValue("orig")
        return e.copy(origMinor = if (orig.isEmpty()) e.rubKop else long(orig))
    }

    fun ruleId(r: MoneyRules.Rule): String = RULE + MoneyRules.norm(r.pattern) + "|" + r.sign + "|" + r.owner

    fun ruleFields(r: MoneyRules.Rule): Map<String, String> = linkedMapOf(
        "p" to r.pattern,
        "c" to r.category,
        "w" to r.who,
        "s" to if (r.sign == 0) "" else r.sign.toString(),
        "o" to r.owner,
        "n" to r.comment,
        DELETED to "",
    )

    fun ruleOf(f: Map<String, String>): MoneyRules.Rule = MoneyRules.Rule(
        pattern = f["p"].orEmpty(),
        category = f["c"].orEmpty(),
        who = f["w"].orEmpty(),
        sign = f["s"]?.toIntOrNull() ?: 0,
        owner = f["o"].orEmpty(),
        comment = f["n"].orEmpty(),
    )

    fun balanceId(b: MoneyCashflow.Anchor): String = BALANCE + b.account + "|" + b.ts

    fun balanceFields(b: MoneyCashflow.Anchor): Map<String, String> = linkedMapOf(
        "acc" to b.account, "ts" to b.ts.toString(), "kop" to b.kop.toString(), "src" to b.source,
    )

    // ---- Что сказать другим ----

    /**
     * Что на телефоне отличается от сложенного журнала — новые события этого
     * устройства. Сущность, которой в журнале ещё нет, пишется моментом
     * [T_FIRST] и только непустыми полями: это «как было», не правка.
     * Правило, пропавшее из справочника, — событие «удалено». Запись, которой
     * на телефоне нет, событий не порождает: записи не удаляются.
     */
    fun diff(cur: SyncFlat, known: Merged, now: Long, device: String): List<Event> {
        val out = ArrayList<Event>()
        for ((id, fields) in cur) {
            val row = known.cells[id]
            if (row == null) {
                val set = fields.filterValues { it.isNotEmpty() }
                if (set.isNotEmpty()) out.add(Event(id, T_FIRST, device, set))
                continue
            }
            val changed = fields.filter { (k, v) -> (row[k]?.v ?: "") != v }
            if (changed.isNotEmpty()) out.add(Event(id, now, device, changed))
        }
        for ((id, row) in known.cells) {
            if (!id.startsWith(RULE) || id in cur) continue
            if (row[DELETED]?.v != "1") out.add(Event(id, now, device, mapOf(DELETED to "1")))
        }
        return out
    }

    // ---- Что взять у других ----

    data class Local(
        val entries: List<MoneyEntry>,
        val rules: List<MoneyRules.Rule>,
        val balances: List<MoneyCashflow.Anchor>,
        val zf: Set<String>,
        val notZf: Set<String>,
    ) {
        fun flat(): SyncFlat = flatten(entries, rules, balances, zf, notZf)
    }

    /** Итог: записей добавлено, записей поправлено, прочего (правила, остатки, счета ЗФ) поменялось. */
    data class Applied(val local: Local, val added: Int, val changed: Int, val other: Int) {
        val any: Boolean get() = added + changed + other > 0
    }

    /**
     * Привести телефон к сложенному журналу [target]. [before] — снимок полей
     * на начало обмена: поле, которое владелец поменял уже ПОСЛЕ снимка, не
     * трогается — его правка свежее и уйдёт следующим обменом. Записи только
     * меняются и добавляются.
     */
    fun apply(now: Local, before: SyncFlat, target: Merged): Applied {
        val cur = now.flat()
        var changed = 0
        var added = 0
        var other = 0

        /** Поля, которые надо взять из журнала: там другое, а здесь с начала обмена не трогали. */
        fun take(id: String): Map<String, String> {
            val want = target.values(id)
            if (want.isEmpty()) return emptyMap()
            val have = cur[id].orEmpty()
            val was = before[id].orEmpty()
            return want.filter { (k, v) ->
                val h = have[k] ?: ""
                h != v && h == (was[k] ?: "")
            }
        }

        val entries = now.entries.map { e ->
            if (e.draft) return@map e
            val t = take(ENTRY + e.id)
            if (t.isEmpty()) e else withFields(e, t).also { if (it != e) changed++ }
        }.toMutableList()
        val have = now.entries.map { ENTRY + it.id }.toHashSet()
        for ((id, row) in target.cells) {
            if (!id.startsWith(ENTRY) || id in have) continue
            val f = row.mapValues { it.value.v }
            if (f["ts"].isNullOrEmpty() && f["rub"].isNullOrEmpty()) continue
            val base = MoneyEntry(id = id.removePrefix(ENTRY), owner = "", source = MoneyEntry.Source.MANUAL, ts = 0L, rubKop = 0L, what = "")
            entries.add(withFields(base, f))
            added++
        }

        // Справочник: порядок владельца сохраняется, новое — в конец.
        val rules = ArrayList<MoneyRules.Rule>()
        val seen = HashSet<String>()
        for (r in now.rules) {
            val id = ruleId(r)
            val first = seen.add(id)
            val t = if (first) take(id) else emptyMap()
            if (t[DELETED] == "1") { other++; continue }
            if (t.isEmpty()) { rules.add(r); continue }
            val next = ruleOf(ruleFields(r) + t)
            if (next != r) other++
            rules.add(next)
        }
        for ((id, row) in target.cells) {
            if (!id.startsWith(RULE) || id in seen || id in cur) continue
            val f = row.mapValues { it.value.v }
            if (f[DELETED] == "1" || f["p"].isNullOrEmpty()) continue
            // Удалено здесь во время обмена — не воскрешать.
            if (id in before) continue
            rules.add(ruleOf(f))
            other++
        }

        val balances = now.balances.toMutableList()
        val haveBalance = now.balances.map { balanceId(it) }.toHashSet()
        for ((id, row) in target.cells) {
            if (!id.startsWith(BALANCE) || id in haveBalance) continue
            val f = row.mapValues { it.value.v }
            val ts = f["ts"]?.toLongOrNull() ?: continue
            balances.add(MoneyCashflow.Anchor(f["acc"].orEmpty(), ts, f["kop"]?.toLongOrNull() ?: 0L, f["src"].orEmpty()))
            other++
        }

        val zf = now.zf.toMutableSet()
        val notZf = now.notZf.toMutableSet()
        for ((id, row) in target.cells) {
            if (!id.startsWith(ZF)) continue
            val name = id.removePrefix(ZF)
            val v = row["zf"]?.v ?: continue
            val h = cur[id]?.get("zf") ?: ""
            if (h == v || h != (before[id]?.get("zf") ?: "")) continue
            if (v == "1") { zf.add(name); notZf.remove(name) } else { notZf.add(name); zf.remove(name) }
            other++
        }

        return Applied(Local(entries, rules, balances, zf, notZf), added, changed, other)
    }

    // ---- Файлы ----

    /** Строка журнала: одно событие одной строкой JSON. */
    fun encode(e: Event): String = JSONObject().apply {
        put("i", e.id); put("t", e.t); put("d", e.d)
        put("f", JSONObject().apply { for ((k, v) in e.f) put(k, v) })
    }.toString()

    /** Строка журнала → событие; битая строка (оборванная запись) — null, остальное читается. */
    fun decode(line: String): Event? = runCatching {
        if (line.isBlank()) return null
        val o = JSONObject(line)
        val f = o.getJSONObject("f")
        val map = LinkedHashMap<String, String>()
        for (k in f.keys()) map[k] = f.optString(k)
        Event(o.getString("i"), o.getLong("t"), o.getString("d"), map)
    }.getOrNull()

    fun decodeAll(text: String): List<Event> = text.lineSequence().mapNotNull { decode(it) }.toList()

    /** Кусок журнала устройства: `sasha-3f9a2c.000001.jsonl`. */
    fun chunkName(device: String, n: Int): String = device + "." + n.toString().padStart(6, '0') + ".jsonl"

    /** Паспорт устройства рядом с кусками: имя владельца телефона — для строки «с телефона Марианны». */
    fun deviceFileName(device: String): String = "$device.device.json"

    data class Chunk(val device: String, val n: Int)

    private val CHUNK = Regex("""^([a-z0-9-]+)\.(\d{6})\.jsonl$""")

    fun parseChunk(name: String): Chunk? =
        CHUNK.matchEntire(name)?.let { Chunk(it.groupValues[1], it.groupValues[2].toInt()) }

    fun parseDeviceFile(name: String): String? =
        Regex("""^([a-z0-9-]+)\.device\.json$""").matchEntire(name)?.groupValues?.get(1)

    /** Кусок закрывается, перевалив за этот размер: закрытые скачиваются один раз. */
    const val CHUNK_BYTES = 256 * 1024

    /** Имя устройства для файлов: профиль + случайный хвост; только [a-z0-9-]. */
    fun deviceId(profileId: String, tail: String): String {
        val p = profileId.lowercase().replace(Regex("[^a-z0-9-]"), "-").trim('-').ifEmpty { "user" }
        return "$p-$tail"
    }
}
