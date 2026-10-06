package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject

/**
 * Свод — одна правда на сервере (06.10.2026, docs/svod-phone.md, контракт —
 * server/contract/svod.json). Владелец: «правда должна быть на сервере, а
 * телефон больше читает». Каждое знание телефона — промпт, словарь, правила,
 * справочник, выбор моделей — запись Свода: ключ, текст или JSON, версия.
 * Сервер держит, телефон кэширует (`data/SvodStore.kt`) и работает по кэшу и
 * без сети; заводской текст в APK — только запас на первый запуск.
 *
 * Здесь — то, что не знает ни про файлы, ни про сеть: разбор синка, форма
 * операции `svod.set`, первое знакомство и слияние «своё поверх серверного».
 * Проверяют JVM-тесты.
 */
object Svod {

    const val FEATURE = "svod"
    const val FEATURE_PEOPLE = "people"

    // ---- Ключи (контракт, `svod.keys`) ----
    const val DICT = "dict.main"
    const val RULES = "rules.pravka"
    const val MODELS = "models.routes"
    const val PRICES = "claude.prices"
    const val TEAM = "people.team"
    const val FAMILY = "people.family"
    const val CATEGORIES = "zasechka.categories"
    const val MONEY_PARTNER = "money.partner"
    const val MONEY_SUMMARY = "money.summary"
    const val MONEY_PAYEES = "money.payees"
    const val MONEY_ANCHORS = "money.anchors.factory"
    const val MONEY_MANUAL = "money.manual.factory"
    const val RAZNOSKA = "prompt.raznoska"

    /** Отказ `base_rev`: «на сервере уже версии N» — не ошибка, а повод слить и послать снова. */
    private const val STALE_MARK = "на сервере уже версии"

    /** Ключ: латиница, цифры, точка, дефис, подчёркивание; до 80 знаков — как проверяет сервер. */
    private val KEY = Regex("^[A-Za-z0-9._-]{1,80}$")

    fun validKey(key: String): Boolean = KEY.matches(key)

    /**
     * Запись Свода. Ровно одно из [body] (текст) и [value] (JSON, хранится
     * строкой — разбирает тот, кто читает). [rev] растёт на каждую настоящую
     * правку, [seq] — общий номер изменений, как у дел.
     */
    data class Entry(
        val key: String,
        val body: String? = null,
        val value: String? = null,
        val rev: Int = 0,
        val seq: Long = 0,
        val author: String = "",
        val reason: String = "",
        val updatedAt: String = "",
    ) {
        fun json(): Any? = value?.let { parseJson(it) }
    }

    /**
     * Своя правка, ещё не принятая сервером: видна сразу (как очередь у дел),
     * уходит операцией с [opId]. [baseRev] — какую версию видел пишущий
     * (null — «последний пишущий побеждает»).
     */
    data class Pending(
        val key: String,
        val body: String? = null,
        val value: String? = null,
        val author: String,
        val reason: String = "",
        val baseRev: Int? = null,
        val opId: String,
        val at: Long = 0,
    )

    // ------------------------------------------------------------ разбор

    fun entry(o: JSONObject): Entry? {
        val key = o.optString("key").trim()
        if (key.isEmpty() || !validKey(key)) return null
        val body = if (o.isNull("body")) null else o.optString("body")
        val value = if (o.isNull("value") || !o.has("value")) null else o.get("value").let { v ->
            when (v) {
                is JSONObject, is JSONArray -> v.toString()
                is String -> JSONObject.quote(v)
                else -> v.toString()
            }
        }
        return Entry(
            key = key,
            body = body,
            value = value,
            rev = o.optInt("rev"),
            seq = o.optLong("seq"),
            author = str(o, "author"),
            reason = str(o, "reason"),
            updatedAt = str(o, "updated_at"),
        )
    }

    /** Массив `svod` ответа синка; нет ключа — пусто (старый сервер или «ничего нового»). */
    fun fromSync(resp: JSONObject): List<Entry> {
        val a = resp.optJSONArray("svod") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i -> a.optJSONObject(i)?.let(::entry) }
    }

    /**
     * Ответ синка поверх кэша. Записи на сервере не удаляются, поэтому и
     * полный синк только дописывает: пустой массив поверх непустого кэша
     * значит «ничего не поменялось» или «спросили не ту базу», а не «стёрли»
     * (железное правило 1). Строка не моложе своей (`rev`) переписывается —
     * повтор окна перекрытия безвреден.
     */
    fun merge(have: Map<String, Entry>, got: List<Entry>): Map<String, Entry> {
        if (got.isEmpty()) return have
        val out = LinkedHashMap(have)
        for (e in got) {
            val was = out[e.key]
            if (was == null || e.rev >= was.rev) out[e.key] = e
        }
        return out
    }

    fun json(e: Entry): JSONObject = JSONObject()
        .put("key", e.key)
        .put("body", e.body ?: JSONObject.NULL)
        .put("value", e.value?.let { parseJson(it) } ?: JSONObject.NULL)
        .put("rev", e.rev).put("seq", e.seq)
        .put("author", e.author).put("reason", e.reason).put("updated_at", e.updatedAt)

    fun json(p: Pending): JSONObject = JSONObject()
        .put("key", p.key)
        .put("body", p.body ?: JSONObject.NULL)
        .put("value", p.value?.let { parseJson(it) } ?: JSONObject.NULL)
        .put("author", p.author).put("reason", p.reason)
        .put("base_rev", p.baseRev ?: JSONObject.NULL)
        .put("op_id", p.opId).put("at", p.at)

    fun pending(o: JSONObject): Pending? {
        val e = entry(o) ?: return null
        val opId = str(o, "op_id").ifBlank { return null }
        return Pending(
            key = e.key, body = e.body, value = e.value, author = str(o, "author").ifBlank { "phone" },
            reason = str(o, "reason"), baseRev = if (o.isNull("base_rev") || !o.has("base_rev")) null else o.optInt("base_rev"),
            opId = opId, at = o.optLong("at"),
        )
    }

    // ------------------------------------------------------------ запись

    /** Операция `svod.set` для `/api/ops` — ровно одно из body и value. */
    fun op(p: Pending): JSONObject {
        require(validKey(p.key)) { "ключ Свода «${p.key}» — только латиница, цифры, точка, дефис, подчёркивание" }
        require((p.body == null) != (p.value == null)) { "в записи Свода ровно одно из body и value" }
        return JSONObject()
            .put("op", "svod.set")
            .put("op_id", p.opId)
            .put("key", p.key)
            .apply {
                if (p.body != null) put("body", p.body) else put("value", parseJson(p.value!!))
                put("author", p.author)
                if (p.reason.isNotBlank()) put("reason", p.reason)
                if (p.baseRev != null) put("base_rev", p.baseRev)
            }
    }

    /** Ответ сервера «на сервере уже версии N» — правка поверх старой версии, ничего не записано. */
    fun isStale(error: String): Boolean = error.contains(STALE_MARK)

    /**
     * Первое знакомство: что из своего положить в Свод. Только то, чего там
     * ещё нет: сервер уже может знать больше (запись «Денег», правка с другой
     * установки), и затирать её заводским текстом нельзя. `base_rev: 0` —
     * «записи не было»: если она успела появиться, сервер откажет, и это
     * нормально.
     */
    fun seedPlan(have: Set<String>, mine: Map<String, Pair<String?, String?>>): List<String> =
        mine.filter { (key, v) -> key !in have && validKey(key) && (v.first != null || v.second != null) }
            .keys.sorted()

    // ------------------------------------------------------------ слияние

    /**
     * Трёхстороннее слияние списка по ключу элемента: [base] — серверная
     * версия, поверх которой правил телефон, [server] — свежая серверная,
     * [local] — своя неотправленная. Своё добавленное и своё изменённое
     * остаётся; своё удалённое не воскресает; добавленное сервером
     * приходит; удалённое сервером уходит, если телефон его не трогал. Base
     * нет (первое знакомство) — объединение: потерять выученное за месяцы
     * хуже, чем получить лишнюю строку.
     *
     * Порядок — свой, новое с сервера — в конце.
     */
    fun <T> merge3(base: List<T>?, server: List<T>, local: List<T>, keyOf: (T) -> String, same: (T, T) -> Boolean): List<T> {
        val b = base?.associateBy(keyOf).orEmpty()
        val s = server.associateBy(keyOf)
        val out = LinkedHashMap<String, T>()
        for (l in local) {
            val k = keyOf(l)
            val was = b[k]
            val now = s[k]
            when {
                base == null -> out[k] = l
                was == null -> out[k] = l                               // своё новое
                now == null -> if (!same(l, was)) out[k] = l            // сервер убрал, а телефон правил — правка сильнее
                !same(l, was) -> out[k] = l                             // своё изменённое
                else -> out[k] = now                                    // не трогал — серверное
            }
        }
        val localKeys = local.map(keyOf).toHashSet()
        for (sv in server) {
            val k = keyOf(sv)
            if (k in out) continue
            if (base != null && k in b && k !in localKeys) continue      // телефон удалил
            out[k] = sv
        }
        return out.values.toList()
    }

    // ------------------------------------------------------------ словарь

    /**
     * `dict.main` — файл словаря, как у `DictionaryStore`, но без `hits`:
     * счётчик — местная статистика, иначе каждый тейк будил бы синк.
     */
    fun dictValue(entries: List<DictEntry>, seedVersion: Int): JSONObject = JSONObject()
        .put("format", "pravka-dictionary")
        .put("version", 1)
        .put("seedVersion", seedVersion)
        .put("entries", JSONArray().apply {
            for (e in entries) put(
                JSONObject().put("id", e.id).put("from", e.from).put("to", e.to).put("mode", e.mode.name)
                    .put("note", e.note).put("enabled", e.enabled).put("createdAt", e.createdAt)
            )
        })

    /**
     * Словарь из Свода. Форма — файл словаря или голый массив (так пишут
     * службы компа); запись без `id` получает свой номер после самых больших.
     */
    fun dictEntries(value: Any?): List<DictEntry> {
        val a = when (value) {
            is JSONObject -> value.optJSONArray("entries")
            is JSONArray -> value
            else -> null
        } ?: return emptyList()
        val out = ArrayList<DictEntry>()
        for (i in 0 until a.length()) {
            val o = a.optJSONObject(i) ?: continue
            val from = o.optString("from").ifBlank { o.optString("w") }.trim()
            if (from.isEmpty()) continue
            val mode = runCatching { DictMode.valueOf(o.optString("mode", "PROTECT").uppercase()) }.getOrNull() ?: continue
            out += DictEntry(
                id = o.optLong("id", 0), from = from, to = o.optString("to").trim(), mode = mode,
                note = o.optString("note").trim(), enabled = o.optBoolean("enabled", true),
                createdAt = o.optLong("createdAt", 0),
            )
        }
        var next = (out.maxOfOrNull { it.id } ?: 0L) + 1
        return out.map { if (it.id == 0L) it.copy(id = next++) else it }
    }

    /** Одна запись словаря — по `id`; сравнение без `hits` (они местные). */
    fun dictKey(e: DictEntry): String = e.id.toString()

    fun dictSame(a: DictEntry, b: DictEntry): Boolean =
        a.from == b.from && a.to == b.to && a.mode == b.mode && a.note == b.note && a.enabled == b.enabled

    /**
     * Свой словарь поверх серверного (отказ `base_rev` или новая версия из
     * синка при неотправленном своём). Трёхстороннее по `id`; одинаковые
     * `from` + вид из разных мест — одна запись (при равных — позже
     * заведённая). Счётчики — свои, по `id`.
     */
    fun mergeDict(base: List<DictEntry>?, server: List<DictEntry>, local: List<DictEntry>): List<DictEntry> {
        val merged = merge3(base, server, local, ::dictKey, ::dictSame)
        val hits = local.associate { it.id to it.hits }
        val byWord = LinkedHashMap<Pair<String, DictMode>, DictEntry>()
        for (e in merged) {
            val k = e.from.lowercase() to e.mode
            val was = byWord[k]
            if (was == null || e.createdAt > was.createdAt) byWord[k] = e
        }
        return byWord.values.map { it.copy(hits = hits[it.id] ?: it.hits) }
    }

    // ------------------------------------------------------------ чтение

    /**
     * Что сейчас знает телефон: кэш Свода со своей очередью поверх. Ставит
     * `SvodStore` при каждой перемене; читают промпты, цены и правила
     * партнёрства — места, где тащить стор по цепочке вызовов дороже, чем
     * спросить общее знание.
     */
    @Volatile var current: Map<String, Entry> = emptyMap()

    /** Текст записи или заводской запас. */
    fun text(key: String, factory: String): String =
        current[key]?.body?.takeIf { it.isNotBlank() } ?: factory

    /** JSON записи (объект или массив) или null. */
    fun value(key: String): Any? = current[key]?.json()

    fun obj(key: String): JSONObject? = value(key) as? JSONObject

    /**
     * «Кто есть кто в команде» для промптов (`{TEAM}`). Нет записи — заводская
     * строка того промпта, где стоит метка: так текст без Свода не меняется.
     */
    fun team(fallback: String): String = text(TEAM, fallback).trim()

    fun withTeam(text: String, fallback: String): String =
        if (!text.contains(PLACEHOLDER_TEAM)) text else text.replace(PLACEHOLDER_TEAM, team(fallback))

    const val PLACEHOLDER_TEAM = "{TEAM}"

    /** Текст заводской записи, если его нет — null; для `seedPlan`. */
    fun pair(body: String?): Pair<String?, String?> = body to null

    // ------------------------------------------------------------ помощники

    fun parseJson(s: String): Any? = runCatching {
        val t = s.trim()
        when {
            t.startsWith("{") -> JSONObject(t)
            t.startsWith("[") -> JSONArray(t)
            else -> org.json.JSONTokener(t).nextValue()
        }
    }.getOrNull()

    /** Одинаковый JSON с точностью до порядка ключей: правка тем же текстом — не правка. */
    fun sameJson(a: String?, b: String?): Boolean {
        if (a == b) return true
        if (a == null || b == null) return false
        return canon(parseJson(a)) == canon(parseJson(b))
    }

    private fun canon(v: Any?): String = when (v) {
        is JSONObject -> v.keys().asSequence().sorted().joinToString(",", "{", "}") { k -> JSONObject.quote(k) + ":" + canon(v.opt(k)) }
        is JSONArray -> (0 until v.length()).joinToString(",", "[", "]") { canon(v.opt(it)) }
        is String -> JSONObject.quote(v)
        null, JSONObject.NULL -> "null"
        else -> v.toString()
    }

    private fun str(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key).takeIf { it != "null" }.orEmpty()
}
