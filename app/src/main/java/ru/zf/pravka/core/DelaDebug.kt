package ru.zf.pravka.core

import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import org.json.JSONArray
import org.json.JSONObject

/**
 * Режим отладки Дел (15.10.2026, docs/dela-phone-24.md, контракт — server/contract/dela-debug.json).
 *
 * Владелец: «Давай сделаем в делах накопление багов. Прямо такой debug mode. Наверху где-то прямо текстбокс в
 * телефоне и в компе. И туда я просто накидываю баги, причём они идут с тем, что у меня сейчас на экране». Записка —
 * операция `debug.add` в очередь Дел (без сети тоже), с ней снимок экрана ДАННЫМИ (что за экран вкладки, фильтры,
 * открытая карточка, видимые строки, «Новое», разговор с Claude) и последние действия: что ушло операциями, строки
 * журнала приложения. Разбирает сессия Claude на компе; статус и ответ видны в «Отладке» боковины Дел.
 *
 * Здесь — то, что не знает ни про экран, ни про сеть: состав снимка и действий, операции, разбор ответов (под тестом
 * `DelaDebugTest`). Не путать с «🐞 Баг или предложение» (FeedbackStore, docs/feedback.md): тот — про приложение
 * целиком и разбирается ночью; этот — про логику Дел, с экраном, и разбирается днём по живой базе сервера.
 */
object DelaDebug {

    /** Флаг сервера: без него строки нет и `debug.*` не шлётся (старый сервер отверг бы операцию). */
    const val FEATURE = "debug"
    const val ROWS = 200
    const val EVENTS = 30
    const val OPS = 30
    const val TEXT_MAX = 4000

    /**
     * Статус записки словом — слова владельца (16.10.2026: «новое / разбираю / починено / не баг / потом»), как веб
     * (`STATUS` в debug.js) и сервер (`debug.STATUS_WORD`).
     */
    val STATUS: Map<String, String> = linkedMapOf(
        "new" to "новое", "taken" to "разбираю", "fixed" to "починено", "not_bug" to "не баг", "later" to "потом",
    )

    /** Поле `debug` синка: включён ли режим, сколько записок ждут разбора, номер последней, когда разбор отвечал. */
    data class Info(val on: Boolean, val new: Int = 0, val last: Int = 0, val answered: String = "")

    fun info(resp: JSONObject): Info? {
        val o = resp.optJSONObject("debug") ?: return null
        return Info(o.optBoolean("on"), o.optInt("new"), o.optInt("last"), o.optString("answered").takeIf { it != "null" }.orEmpty())
    }

    fun infoJson(i: Info): JSONObject = JSONObject().put("on", i.on).put("new", i.new).put("last", i.last).put("answered", i.answered)

    /** Строка списка, как видна: id дела, было ли в окне, под каким заголовком группы. */
    data class Row(val id: String, val inView: Boolean, val group: String = "")

    /** Разговор с Claude на экране — как `turnMemo` веба: сказал, ответил, что сделано. */
    data class Said(val said: String, val reply: String, val done: String)

    /**
     * Экран вкладки Дел в миг записки — его собирает вкладка (она одна знает раздел, страницу, фильтры и что в окне).
     * [route]: `nav:<раздел>`, `page:<person|project|deal>:<id>`, `search:<что>`; [mode] — режим и фильтры как есть.
     */
    data class Screen(
        val route: String,
        val title: String,
        val sub: String = "",
        val mode: Map<String, Any?> = emptyMap(),
        val rows: List<Row> = emptyList(),
        val openTaskId: String = "",
        /** Страница поверх раздела: вид («person», «project», «deal») и id. */
        val pageKind: String = "",
        val pageId: String = "",
        /** «Новое» на экране — П1, П2… по порядку. */
        val newIds: List<String> = emptyList(),
        val talk: List<Said> = emptyList(),
        /** Строка Claude: набранное и думает ли он. */
        val claudeText: String = "",
        val claudeBusy: Boolean = false,
    )

    // ------------------------------------------------------------ последние операции

    private val recent = ArrayDeque<Pair<Long, JSONObject>>()

    /**
     * Операция легла в очередь Дел (`DelaStore.enqueue`) — коротко в кольцо: «сказал — сделал» на телефоне видно по
     * ним. Записки отладки в кольцо не идут — иначе каждая следующая несла бы предыдущие. В памяти процесса.
     */
    fun noteOps(ops: List<JSONObject>, now: Long = System.currentTimeMillis()) {
        synchronized(recent) {
            for (op in ops) {
                if (op.optString("op").startsWith("debug.")) continue
                recent.addLast(now to (shrink(op, 0) as JSONObject))
                while (recent.size > OPS) recent.removeFirst()
            }
        }
    }

    fun recentOps(): List<Pair<Long, JSONObject>> = synchronized(recent) { recent.toList() }

    internal fun clearOps() = synchronized(recent) { recent.clear() }

    /** Значение коротко: строки до 300, списки до 20, глубина 3 — одна операция не съест записку. */
    fun shrink(v: Any?, depth: Int): Any? = when (v) {
        null, JSONObject.NULL -> JSONObject.NULL
        is String -> if (v.length > 300) v.take(299) + "…" else v
        is JSONObject -> if (depth > 2) "{…}" else JSONObject().also { o -> for (k in v.keys()) o.put(k, shrink(v.opt(k), depth + 1)) }
        is JSONArray -> if (depth > 2) "[${v.length()}]" else JSONArray().also { a ->
            for (i in 0 until minOf(v.length(), 20)) a.put(shrink(v.opt(i), depth + 1))
            if (v.length() > 20) a.put("… ещё ${v.length() - 20}")
        }
        else -> v
    }

    // ------------------------------------------------------------ снимок

    private fun JSONObject.putIf(k: String, v: Any?): JSONObject {
        when (v) {
            null -> Unit
            is String -> if (v.isNotBlank()) put(k, v)
            is Collection<*> -> if (v.isNotEmpty()) put(k, JSONArray(v))
            is Map<*, *> -> if (v.isNotEmpty()) put(k, JSONObject(v.mapKeys { it.key.toString() }))
            is Boolean -> if (v) put(k, true)
            is Int -> if (v != 0) put(k, v)
            else -> put(k, v)
        }
        return this
    }

    private fun rowJson(r: Row, s: Dela.Snapshot, today: String): JSONObject {
        val t = s.tasks[r.id]
        val o = JSONObject().put("id", r.id)
        if (t == null) return o.put("title", "(дела нет в копии)").put("in_view", r.inView)
        o.putIf("num", t.num).put("title", t.title).putIf("group", r.group)
        if (t.dueDate.isNotBlank()) o.put("due", t.dueDate + if (t.dueTime.isNotBlank()) " " + t.dueTime else "")
        o.putIf("person", s.people[t.personId]?.label)
        o.putIf("project", s.projects[t.projectId]?.name)
        o.putIf("deal", s.deals[t.dealId]?.name)
        o.put("grp", DelaGroups.grpOf(t).title)
        if (!t.open) o.put("status", t.status)
        if (DelaGroups.isNow(t, today)) o.put("now", true)
        if (t.goal) o.put("goal", true)
        return o.put("in_view", r.inView)
    }

    /** Открытое дело целиком — все поля строки, как их знает копия, и имена вместо id. */
    private fun cardJson(t: Dela.Task, s: Dela.Snapshot): JSONObject {
        val f = Dela.json(t)
        for (k in f.keys().asSequence().toList()) {
            val v = f.opt(k)
            if (v == null || v == JSONObject.NULL || v == "" || (v is JSONArray && v.length() == 0)) f.remove(k)
        }
        s.people[t.personId]?.let { f.put("person_name", it.label) }
        s.projects[t.projectId]?.let { f.put("project_name", it.name) }
        s.deals[t.dealId]?.let { f.put("deal_name", it.name) }
        s.tasks[t.whyId]?.let { f.put("why_title", it.title) }
        return JSONObject().put("kind", if (t.goal) "goal" else "task").put("id", t.id).putIf("num", t.num).put("title", t.title).put("fields", f)
    }

    private fun pageJson(kind: String, id: String, s: Dela.Snapshot): JSONObject? = when (kind) {
        "person" -> s.people[id]?.let { JSONObject().put("kind", "person").put("id", id).put("title", it.name).put("fields", Dela.json(it)) }
        "project" -> s.projects[id]?.let {
            JSONObject().put("kind", if (it.kind == "client") "client" else "project").put("id", id).put("title", it.name).put("fields", Dela.json(it))
        } ?: if (id.isBlank()) JSONObject().put("kind", "project").put("title", "Входящие") else null
        "deal" -> s.deals[id]?.let { JSONObject().put("kind", "deal").put("id", id).put("title", it.name).put("fields", Dela.json(it)) }
        else -> null
    }

    /**
     * Снимок экрана данными — тот же состав, что у веба (`snapshot` в debug.js): маршрут, заголовок, режим, открытая
     * карточка, видимые строки (до [ROWS]), «Новое», разговор с Claude; плюс телефонное — строки журнала
     * приложения ([events]) и очередь ([queue] — ещё не ушло на сервер).
     */
    fun snapshot(
        sc: Screen,
        s: Dela.Snapshot,
        today: String,
        build: String,
        events: List<String> = emptyList(),
        queue: List<JSONObject> = emptyList(),
    ): JSONObject {
        val o = JSONObject().put("v", 1).put("route", sc.route).put("kind", "phone").put("title", sc.title).putIf("sub", sc.sub)
        if (sc.mode.isNotEmpty()) o.put("mode", JSONObject().also { m -> for ((k, v) in sc.mode) if (v != null && v != "" && v != false) m.put(k, v) })
        val card = s.tasks[sc.openTaskId]?.let { cardJson(it, s) } ?: pageJson(sc.pageKind, sc.pageId, s)
        if (card != null) o.put("card", card)
        val seen = LinkedHashMap<String, Row>()
        for (r in sc.rows) if (r.id !in seen) seen[r.id] = r
        if (seen.isNotEmpty()) {
            o.put("rows", JSONArray().apply { seen.values.take(ROWS).forEach { put(rowJson(it, s, today)) } })
            o.put("rows_total", seen.size)
        }
        if (sc.newIds.isNotEmpty()) o.put("new", JSONArray().apply {
            sc.newIds.take(60).forEachIndexed { i, id ->
                val g = s.suggestions[id]
                val p = runCatching { JSONObject(g?.payload ?: "{}") }.getOrDefault(JSONObject())
                put(JSONObject().put("n", "П${i + 1}").put("id", id).putIf("kind", g?.kind).putIf("source", g?.source)
                    .putIf("title", p.optString("title").ifBlank { s.tasks[g?.taskId]?.title.orEmpty() }).putIf("status", g?.status))
            }
        })
        if (sc.talk.isNotEmpty()) o.put("talk", JSONArray().apply {
            sc.talk.takeLast(8).forEach { put(JSONObject().put("said", it.said.take(600)).put("reply", it.reply.take(900)).put("done", it.done.take(700))) }
        })
        o.putIf("quick_text", sc.claudeText).putIf("claude_busy", sc.claudeBusy)
        o.put("today", today).put("seq", s.seq).put("synced_at", s.syncedAt).putIf("build", build)
        if (events.isNotEmpty()) o.put("events", JSONArray(events.takeLast(EVENTS)))
        if (queue.isNotEmpty()) o.put("queue", JSONArray().apply {
            queue.filterNot { it.optString("op").startsWith("debug.") }.take(30).forEach { put(shrink(it, 0)) }
        })
        return o
    }

    // ------------------------------------------------------------ действия

    private val LOG_STAMP = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS")

    /** Миг строки журнала («MM-dd HH:mm:ss.SSS  текст» — `EventLog`) — ISO; год — нынешний, будущее — прошлогоднее. */
    fun logAt(line: String, now: OffsetDateTime, zone: ZoneId = ZoneId.systemDefault()): String? {
        if (line.length < 18) return null
        return runCatching {
            var t = LocalDateTime.parse("${now.year}-${line.substring(0, 18)}", LOG_STAMP).atZone(zone).toOffsetDateTime()
            if (t.isAfter(now.plusDays(1))) t = t.minusYears(1)
            t.toString()
        }.getOrNull()
    }

    /**
     * Действия записки — как кольцо веба, старые первыми: операции очереди ([ops] — миг и операция) и строки журнала
     * приложения ([events]); последние [EVENTS] + [OPS] вместе, по времени.
     */
    fun actions(ops: List<Pair<Long, JSONObject>>, events: List<String>, now: OffsetDateTime): JSONArray {
        val all = ArrayList<Pair<String, JSONObject>>()
        for ((at, op) in ops) {
            val iso = java.time.Instant.ofEpochMilli(at).atOffset(now.offset).toString()
            all += iso to JSONObject().put("at", iso).put("kind", "op").put("text", opWords(op)).put("data", op)
        }
        for (line in events.takeLast(EVENTS)) {
            val iso = logAt(line, now) ?: continue
            all += iso to JSONObject().put("at", iso).put("kind", "event").put("text", line.substring(minOf(line.length, 19)).trim().take(600))
        }
        return JSONArray().apply { all.sortedBy { it.first }.takeLast(EVENTS + OPS).forEach { put(it.second) } }
    }

    private fun opWords(op: JSONObject): String {
        val what = op.optJSONObject("task")?.optString("title").orEmpty().ifBlank {
            op.optJSONObject("data")?.let { it.optString("name").ifBlank { it.optString("summary") } }.orEmpty()
        }
        return op.optString("op", "?") + if (what.isNotBlank()) " «${what.take(80)}»" else ""
    }

    // ------------------------------------------------------------ операции и ответы

    /** Записка: id — свой (повтор после обрыва связи даст прежнюю записку), [shot] — картинка data:image/… или пусто. */
    fun addOp(
        opId: String, id: String, text: String, version: String, screen: String, at: String,
        snapshot: JSONObject, actions: JSONArray, shot: String? = null,
    ): JSONObject {
        val note = JSONObject().put("id", id).put("text", text.trim().take(TEXT_MAX)).put("source", "phone")
            .put("version", version.take(80)).put("screen", screen.take(80)).put("at", at)
            .put("snapshot", snapshot).put("actions", actions)
        if (!shot.isNullOrBlank()) note.put("shot", shot)
        return JSONObject().put("op_id", opId).put("op", "debug.add").put("note", note)
    }

    fun setOp(opId: String, id: String, status: String? = null, answer: String? = null): JSONObject =
        JSONObject().put("op_id", opId).put("op", "debug.set").put("id", id).apply {
            if (status != null) put("status", status)
            if (answer != null) put("answer", answer)
        }

    /** Записка в списке (`GET /api/view/debug`). */
    data class Note(
        val id: String, val num: Int, val at: String, val text: String, val source: String, val status: String,
        val answer: String = "", val statusBy: String = "", val statusAt: String = "", val title: String = "",
        val route: String = "", val version: String = "", val screen: String = "", val hasShot: Boolean = false,
    ) {
        val statusWord: String get() = STATUS[status] ?: status
    }

    private fun JSONObject.s(k: String): String = optString(k).takeIf { it != "null" }.orEmpty()

    fun note(o: JSONObject): Note = Note(
        id = o.s("id"), num = o.optInt("num"), at = o.s("said_at"), text = o.s("text"), source = o.s("source"),
        status = o.s("status").ifBlank { "new" }, answer = o.s("answer"), statusBy = o.s("status_by"), statusAt = o.s("status_at"),
        title = o.s("title"), route = o.s("route"), version = o.s("version"), screen = o.s("screen"), hasShot = o.optBoolean("has_shot"),
    )

    fun notes(resp: JSONObject): List<Note> {
        val a = resp.optJSONArray("items") ?: return emptyList()
        return (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::note) }
    }

    /** «16.10 10:15» (или «сегодня 10:15») — время записки у владельца. */
    fun whenWords(iso: String, today: String): String = runCatching {
        val t = OffsetDateTime.parse(iso).atZoneSameInstant(ZoneId.of("Europe/Moscow"))
        val d = t.toLocalDate().toString()
        (if (d == today) "сегодня" else String.format(java.util.Locale.ROOT, "%02d.%02d", t.dayOfMonth, t.monthValue)) +
            String.format(java.util.Locale.ROOT, " %02d:%02d", t.hour, t.minute)
    }.getOrDefault(iso.take(16))
}
