package ru.zf.pravka.core

import java.security.MessageDigest
import java.time.OffsetDateTime
import java.time.ZoneId
import org.json.JSONArray
import org.json.JSONObject

/**
 * Настройки телефона на сервере (14.10.2026; описание — docs/phone-settings-server.md, контракт —
 * server/contract/dela-phone.json): схема, снимок, правки с компьютера, проверка значения и спор «кто позже».
 *
 * Владелец: «настройки телефона править с ПК», «две копии одного и того же». Настройки живут в телефоне —
 * служба, кнопки и движок диктовки читают их без сети, — а сервер Дел держит копию по устройству и очередь
 * правок с компьютера. Схема, а не форма на каждую настройку: телефон сам описывает свои настройки (группа,
 * подпись, тип, допустимые значения, можно ли издалека и почему нельзя), веб рисует страницу по описанию —
 * новая настройка здесь появляется в вебе без правки сервера и веба.
 *
 * Здесь — только правила, без Android: что уходит в снимок (ключей и токенов — никогда), как проверить
 * пришедшее значение и когда правку с компьютера не ставить, потому что на телефоне меняли позже. Какие
 * настройки есть и как их читать и писать — `PhoneKnobs.kt`, дорога и файлы — `data/PhoneRemoteSync.kt`.
 */
object PhoneRemote {

    /** Флаг сервера в `features`: без него телефон снимков не шлёт — старый сервер ответил бы «нет такой операции». */
    const val FEATURE = "phone"

    /**
     * Секрет — по имени настройки, целым словом между точками и подчёркиваниями: «anthropic_api_key»,
     * «todoist_token», «home.password». То же правило у сервера (`phone.SECRET`): значение такой настройки
     * не уходит, править её издалека нельзя. «keep_on_fold» сюда не попадает: «keep» — не «key».
     */
    private val SECRET = Regex("(?:^|[._])(?:api_?key|keys?|tokens?|pass|password|passwd|pwd|secret|secrets|cookie|auth|login|pin)(?:$|[._])")
    private val KEY = Regex("^[a-z][a-z0-9_.]{0,63}$")
    private val GROUP = Regex("^[a-z][a-z0-9_]{0,31}$")

    fun isSecret(key: String): Boolean = SECRET.containsMatchIn(key)

    enum class Type(val key: String) { BOOL("bool"), CHOICE("choice"), SET("set"), INT("int"), TIME("time"), TEXT("text"), INFO("info") }

    data class Opt(val v: String, val t: String)

    /**
     * Одна настройка в схеме. [edit] — можно ли править с компьютера; [why] — почему нельзя, словами для веба;
     * [sec] — подзаголовок раздела внутри группы; [row] — настройки с одним row веб рисует одной строкой (модель
     * и усилие дороги). Значения: да/нет — Boolean, числа и время — Int (время — минуты от полуночи, после
     * полуночи — сверх суток), выбор и текст — String, несколько из вариантов — List<String>, показ — String.
     */
    data class Item(
        val key: String,
        val title: String,
        val type: Type,
        val hint: String = "",
        val options: List<Opt> = emptyList(),
        val min: Int? = null,
        val max: Int? = null,
        val step: Int = 1,
        val unit: String = "",
        val maxLen: Int = 200,
        val edit: Boolean = true,
        val why: String = "",
        val sec: String = "",
        val row: String = "",
    ) {
        /** Правится ли издалека на деле: показ и секрет — никогда, что бы ни написал автор схемы. */
        val remote: Boolean get() = edit && type != Type.INFO && !isSecret(key)
    }

    data class Group(
        val key: String,
        val title: String,
        val hint: String,
        /** Имя значка веба Дел (`ICONS` в static/app.js): clock, mic, claude… */
        val icon: String,
        val items: List<Item>,
        /** Почему группа целиком только в руках («разрешения выдаёт сам телефон»); пусто — обычная. */
        val note: String = "",
    )

    // ------------------------------------------------------------ схема

    fun schemaJson(groups: List<Group>): JSONObject = JSONObject().put("v", 1).put("groups", JSONArray().apply {
        for (g in groups) put(JSONObject().put("key", g.key).put("title", g.title).put("hint", g.hint).put("icon", g.icon)
            .put("note", g.note).put("items", JSONArray().apply { g.items.forEach { put(itemJson(it)) } }))
    })

    private fun itemJson(i: Item): JSONObject = JSONObject().apply {
        put("key", i.key).put("title", i.title).put("type", i.type.key).put("edit", i.remote)
        if (i.hint.isNotBlank()) put("hint", i.hint)
        if (i.why.isNotBlank()) put("why", i.why)
        if (i.sec.isNotBlank()) put("sec", i.sec)
        if (i.row.isNotBlank()) put("row", i.row)
        if (i.unit.isNotBlank()) put("unit", i.unit)
        if (i.options.isNotEmpty()) put("options", JSONArray().apply { i.options.forEach { put(JSONObject().put("v", it.v).put("t", it.t)) } })
        if (i.type == Type.INT || i.type == Type.TIME) {
            i.min?.let { put("min", it) }
            i.max?.let { put("max", it) }
            if (i.step > 1) put("step", i.step)
        }
        if (i.type == Type.TEXT) put("max", i.maxLen)
    }

    /**
     * Отпечаток схемы: другая сборка с другими настройками — другой отпечаток, и снимок несёт схему заново.
     * Считается по своей строке, а не по `JSONObject.toString()`: порядок ключей org.json на JVM и в Android
     * разный, а отпечаток должен быть один.
     */
    fun schemaHash(groups: List<Group>): String {
        val s = StringBuilder()
        for (g in groups) {
            s.append("G|").append(g.key).append('|').append(g.title).append('|').append(g.hint).append('|').append(g.icon).append('|').append(g.note).append('\n')
            for (i in g.items) {
                s.append("I|").append(i.key).append('|').append(i.title).append('|').append(i.type.key).append('|').append(i.hint).append('|')
                    .append(i.options.joinToString(",") { it.v + "=" + it.t }).append('|').append(i.min).append('|').append(i.max).append('|')
                    .append(i.step).append('|').append(i.unit).append('|').append(i.maxLen).append('|').append(i.remote).append('|')
                    .append(i.why).append('|').append(i.sec).append('|').append(i.row).append('\n')
            }
        }
        val d = MessageDigest.getInstance("SHA-256").digest(s.toString().toByteArray(Charsets.UTF_8))
        return d.joinToString("") { "%02x".format(it) }.take(32)
    }

    /** Схема годится серверу: ключи латиницей и по одному разу, у выбора есть варианты. Иначе — что не так. */
    fun problems(groups: List<Group>): List<String> {
        val out = mutableListOf<String>()
        val seen = HashSet<String>()
        for (g in groups) {
            if (!GROUP.matches(g.key)) out += "группа ${g.key}: ключ не латиницей"
            for (i in g.items) {
                if (!KEY.matches(i.key)) out += "${i.key}: ключ не латиницей"
                if (!seen.add(i.key)) out += "${i.key}: дважды"
                if ((i.type == Type.CHOICE || i.type == Type.SET) && i.options.isEmpty()) out += "${i.key}: нет вариантов"
                if (i.min != null && i.max != null && i.min > i.max) out += "${i.key}: min больше max"
            }
        }
        return out
    }

    // ------------------------------------------------------------ значения

    /** Значение из JSON (правка с сервера) — в своё: Boolean, Int, String, List<String>; null — пусто. */
    fun fromJson(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is Boolean -> v
        is Int -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
        is Double -> if (v % 1.0 == 0.0 && v in Int.MIN_VALUE.toDouble()..Int.MAX_VALUE.toDouble()) v.toInt() else v
        is Number -> v.toDouble().let { d -> if (d % 1.0 == 0.0) d.toInt() else d }
        is String -> v
        is JSONArray -> (0 until v.length()).map { v.opt(it)?.toString().orEmpty() }
        is Collection<*> -> v.map { it?.toString().orEmpty() }
        else -> v.toString()
    }

    /** Своё значение — в JSON снимка: Set — списком в порядке вариантов (у set) или по алфавиту. */
    fun toJson(v: Any?, item: Item? = null): Any = when (v) {
        null -> JSONObject.NULL
        is Boolean, is Int, is String -> v
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
        is Float -> if (v % 1f == 0f) v.toInt() else v.toDouble()
        is Double -> if (v % 1.0 == 0.0) v.toInt() else v
        is Collection<*> -> JSONArray().apply { ordered(v.map { it.toString() }, item).forEach { put(it) } }
        else -> v.toString()
    }

    private fun ordered(list: List<String>, item: Item?): List<String> {
        val order = item?.options?.map { it.v }
        return if (order.isNullOrEmpty()) list.distinct().sorted() else order.filter { it in list } + list.filter { it !in order }.distinct().sorted()
    }

    /** Одно и то же значение: 60 и 60L, список и набор — не разница. */
    fun same(a: Any?, b: Any?): Boolean = norm(a) == norm(b)

    private fun norm(v: Any?): Any? = when (v) {
        null, JSONObject.NULL -> null
        is Long -> if (v in Int.MIN_VALUE..Int.MAX_VALUE) v.toInt() else v
        is Double -> if (v % 1.0 == 0.0) v.toInt() else v
        is Float -> if (v % 1f == 0f) v.toInt() else v.toDouble()
        is JSONArray -> fromJson(v).let { norm(it) }
        is Collection<*> -> v.map { it.toString() }.toSortedSet().toList()
        else -> v
    }

    /**
     * Значение правки по своей схеме — второй раз после сервера: сервер проверял по схеме, которую телефон
     * прислал раньше, а сборка с тех пор могла смениться. Результат — значение в своём виде или причина.
     */
    fun check(item: Item, v: Any?): Result<Any> {
        val name = "«${item.title}»"
        fun bad(why: String): Result<Any> = Result.failure(IllegalArgumentException("$name: $why"))
        if (!item.remote) return bad("с компьютера не правится" + if (item.why.isNotBlank()) " — ${item.why}" else "")
        val x = fromJson(v)
        return when (item.type) {
            Type.INFO -> bad("только показывается")
            Type.BOOL -> if (x is Boolean) Result.success(x) else bad("нужно да или нет")
            Type.INT, Type.TIME -> {
                val n = (x as? Int) ?: return bad("нужно целое число")
                when {
                    (item.min != null && n < item.min) || (item.max != null && n > item.max) -> bad("от ${item.min} до ${item.max}")
                    item.step > 1 && (n - (item.min ?: 0)) % item.step != 0 -> bad("шаг ${item.step}")
                    else -> Result.success(n)
                }
            }
            Type.CHOICE -> if (x is String && item.options.any { it.v == x }) Result.success(x) else bad("такого варианта нет")
            Type.SET -> {
                val list = (x as? List<*>)?.map { it.toString() } ?: return bad("нужен список")
                if (list.any { s -> item.options.none { it.v == s } }) bad("такого варианта нет")
                else Result.success(item.options.map { it.v }.filter { it in list })
            }
            Type.TEXT -> {
                val s = (x as? String)?.split(Regex("\\s+"))?.filter { it.isNotEmpty() }?.joinToString(" ") ?: return bad("нужен текст")
                if (s.length > item.maxLen) bad("длиннее ${item.maxLen} знаков") else Result.success(s)
            }
        }
    }

    /** Значение словами — журнал и «поменяно с компьютера»: вариант — подписью, да/нет — словом, время — «23:30». */
    fun label(item: Item?, v: Any?): String {
        val x = fromJson(if (v is Collection<*>) JSONArray(v.map { it.toString() }) else v)
        if (item != null && item.options.isNotEmpty()) {
            val names = item.options.associate { it.v to it.t }
            if (x is List<*>) return x.joinToString(", ") { names[it.toString()] ?: it.toString() }.ifBlank { "ничего" }
            names[x?.toString()]?.let { return it }
        }
        return when {
            x == null -> "—"
            x is Boolean -> if (x) "да" else "нет"
            item?.type == Type.TIME && x is Int -> hm(x)
            item != null && item.unit.isNotBlank() -> "$x ${item.unit}"
            x is List<*> -> x.joinToString(", ")
            else -> x.toString()
        }
    }

    fun hm(minutes: Int): String = "%02d:%02d".format((minutes % 1440) / 60, minutes % 60)

    // ------------------------------------------------------------ правки с компьютера

    /** Правка с компьютера из синка: [atMs] — когда её сделали (часы сервера). */
    data class Edit(val id: String, val device: String, val key: String, val value: Any?, val atMs: Long)

    /** Ждущие правки этого телефона из ответа синка (поле `phone.edits`); чужие телефоны — мимо. */
    fun edits(sync: JSONObject, device: String): List<Edit> {
        val a = sync.optJSONObject("phone")?.optJSONArray("edits") ?: return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val o = a.optJSONObject(i) ?: return@mapNotNull null
            if (o.optString("device") != device) return@mapNotNull null
            val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            val key = o.optString("key").takeIf { it.isNotBlank() } ?: return@mapNotNull null
            Edit(id, device, key, fromJson(o.opt("value")), parseMs(o.optString("at")))
        }
    }

    /** Ждущие правки из вида `phone` (дорога без синка Дел — режим «Дела» выключен в профиле). */
    fun pendingFromView(view: JSONObject, device: String): List<Edit> {
        val a = view.optJSONArray("pending") ?: return emptyList()
        return edits(JSONObject().put("phone", JSONObject().put("edits", a)), device)
    }

    fun parseMs(iso: String): Long = runCatching { OffsetDateTime.parse(iso).toInstant().toEpochMilli() }.getOrDefault(0L)

    enum class Verdict { APPLY, SAME, CONFLICT }

    /**
     * Ставить ли правку с компьютера. Уже стоит то же — ставить нечего, но ответить «взял». Настройку на самом
     * телефоне меняли ПОСЛЕ правки — побеждает последний: телефон (спор, «осталось с телефона»). Иначе — ставить.
     */
    fun decide(edit: Edit, current: Any?, changedAtMs: Long?): Verdict = when {
        same(current, edit.value) -> Verdict.SAME
        changedAtMs != null && edit.atMs > 0 && changedAtMs > edit.atMs -> Verdict.CONFLICT
        else -> Verdict.APPLY
    }

    /** Что делать с правкой с компьютера: поставить это значение или сразу ответить. */
    sealed class Outcome {
        data class Write(val value: Any) : Outcome()
        data class Answer(val ack: Ack) : Outcome()
    }

    /**
     * Решение по одной правке целиком, до записи: есть ли такая настройка ([item] — null, нет), правится ли она
     * издалека ([writable] — есть ли у неё сеттер), годится ли значение, не стоит ли оно уже, не меняли ли её
     * на телефоне позже. Запись — дело вызывающего: тот же сеттер, что у тумблера.
     */
    fun resolve(edit: Edit, item: Item?, writable: Boolean, current: Any?, changedAtMs: Long?, nowMs: Long): Outcome {
        fun no(note: String, conflict: Boolean = false) = Outcome.Answer(Ack(edit.id, false, conflict, note))
        if (item == null) return no("на телефоне нет такой настройки — сборка другая")
        if (!writable || !item.remote) return no("«${item.title}» с компьютера не правится" + if (item.why.isNotBlank()) " — ${item.why}" else "")
        val value = check(item, edit.value).getOrElse { return no(it.message.orEmpty()) }
        return when (decide(edit.copy(value = value), current, changedAtMs)) {
            Verdict.SAME -> Outcome.Answer(Ack(edit.id, true))
            Verdict.CONFLICT -> no(
                "на телефоне поменяли позже (${whenWords(changedAtMs ?: nowMs, nowMs)}) — осталось с телефона: ${label(item, current)}",
                conflict = true,
            )
            Verdict.APPLY -> Outcome.Write(value)
        }
    }

    /** Ответ на правку в снимке: поставил; не встало (почему); на телефоне поменяли позже. */
    data class Ack(val id: String, val ok: Boolean, val conflict: Boolean = false, val note: String = "") {
        fun json(): JSONObject = JSONObject().put("id", id).put("ok", ok).apply {
            if (conflict) put("conflict", true)
            if (note.isNotBlank()) put("note", note)
        }

        companion object {
            fun of(o: JSONObject): Ack? = o.optString("id").takeIf { it.isNotBlank() }
                ?.let { Ack(it, o.optBoolean("ok"), o.optBoolean("conflict"), o.optString("note")) }
        }
    }

    // ------------------------------------------------------------ снимок

    /**
     * Снимок для `phone.snapshot`. Значения — только настроек схемы и только не секретных: [values] может нести
     * что угодно (так проще собирать), а наружу уходит лишь описанное. [schema] — null, когда сервер её уже знает.
     */
    fun snapshot(
        device: String,
        name: String,
        app: String,
        groups: List<Group>,
        values: Map<String, Any?>,
        changed: Map<String, Long>,
        status: Map<String, String>,
        acks: List<Ack>,
        withSchema: Boolean,
    ): JSONObject {
        val items = groups.flatMap { it.items }.associateBy { it.key }
        val vals = JSONObject()
        for ((k, v) in values) {
            val item = items[k] ?: continue
            if (isSecret(k) || v == null) continue
            vals.put(k, toJson(v, item))
        }
        return JSONObject().put("op", "phone.snapshot").put("device", device).put("name", name).put("app", app)
            .put("schema_hash", schemaHash(groups))
            .apply { if (withSchema) put("schema", schemaJson(groups)) }
            .put("values", vals)
            .put("changed", JSONObject().apply { for ((k, t) in changed) if (k in items && !isSecret(k) && t > 0) put(k, t) })
            .put("status", JSONObject().apply { for ((g, s) in status) if (s.isNotBlank() && groups.any { it.key == g }) put(g, s.take(200)) })
            .put("acks", JSONArray().apply { acks.forEach { put(it.json()) } })
    }

    // ------------------------------------------------------------ «поменяно с компьютера»

    /** Что телефон поставил с компьютера: экран «Телефон» показывает это строкой «поменяно с компьютера в …». */
    data class Applied(val key: String, val group: String, val title: String, val value: String, val atMs: Long)

    /** Свежее за трое суток, новые сверху — дальше это уже история, а не новость. */
    const val RECENT_MS = 3 * 24 * 3_600_000L

    fun recent(list: List<Applied>, nowMs: Long): List<Applied> =
        list.filter { nowMs - it.atMs in 0..RECENT_MS }.sortedByDescending { it.atMs }

    /** «в 14:12», «вчера в 09:05», «12.10 в 18:40» — по Москве, как всё время в Ковчеге. */
    fun whenWords(atMs: Long, nowMs: Long, zone: ZoneId = MSK): String {
        val at = java.time.Instant.ofEpochMilli(atMs).atZone(zone)
        val now = java.time.Instant.ofEpochMilli(nowMs).atZone(zone)
        val days = java.time.temporal.ChronoUnit.DAYS.between(at.toLocalDate(), now.toLocalDate())
        val hm = "%02d:%02d".format(at.hour, at.minute)
        return when (days) {
            0L -> "в $hm"
            1L -> "вчера в $hm"
            else -> "%02d.%02d в %s".format(at.dayOfMonth, at.monthValue, hm)
        }
    }

    /** Подпись строки группы на экране «Телефон»: «поменяно с компьютера в 14:12». */
    fun groupNote(list: List<Applied>, group: String, nowMs: Long): String? =
        recent(list, nowMs).firstOrNull { it.group == group }?.let { "поменяно с компьютера ${whenWords(it.atMs, nowMs)}" }

    val MSK: ZoneId = ZoneId.of("Europe/Moscow")
}
