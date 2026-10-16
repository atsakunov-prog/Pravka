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
    /** События, что не операции и не журнал: сказал Claude, ответ, тост, что сервер не принял или сделал сверх. */
    const val NOTES = 30
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

    /**
     * Строка списка, как видна: id дела, было ли в окне, под каким заголовком группы; [depth] — отступ на экране
     * («Деревом», дела внутри над родителем в «Сейчас», шаги к цели), [folded] — родитель «Деревом» свёрнут.
     */
    data class Row(val id: String, val inView: Boolean, val group: String = "", val depth: Int = 0, val folded: Boolean = false)

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

    private val said = ArrayDeque<Pair<Long, JSONObject>>()

    /**
     * Событие страницы, которое не операция очереди и не строка журнала (поезд 16.10.2026, приёмка: «что сказал строке
     * Claude, что он ответил и поменял, тосты, ошибки сети»): [kind] — `ask`, `reply`, `toast`, `op_fail` (сервер не
     * принял), `op_result` (сервер сделал сверх: поднял родителя, вернул срок), `error`. В памяти процесса, как кольцо
     * операций; тосты самой записки («🐞 …») не копятся — каждая следующая несла бы предыдущие.
     */
    fun note(kind: String, text: String, data: JSONObject? = null, now: Long = System.currentTimeMillis()) {
        if (kind == "toast" && text.startsWith("🐞")) return
        val o = JSONObject().put("kind", kind).put("text", text.trim().take(600))
        if (data != null) o.put("data", shrink(data, 0))
        synchronized(said) {
            said.addLast(now to o)
            while (said.size > NOTES) said.removeFirst()
        }
    }

    fun recentNotes(): List<Pair<Long, JSONObject>> = synchronized(said) { said.toList() }

    internal fun clearOps() {
        synchronized(recent) { recent.clear() }
        synchronized(said) { said.clear() }
    }

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
        // Захват (правило 29 сервера, поезд 16.10.2026): напоминание — не дело, в свой час или по месту; «потом» — день
        // возврата и как сказано (как веб и `debug._row` сервера).
        if (t.remind) o.put("remind", "напоминание " + t.remindAt.ifBlank { if (t.remindPlace.isNotBlank()) "по месту «${t.remindPlace}»" else "" })
        else if (t.backOn.isNotBlank() && t.dueDate.isBlank()) o.put("back", "потом · вернётся ${t.backOn}" + if (t.backSaid.isNotBlank()) " («${t.backSaid}»)" else "")
        o.putIf("person", s.people[t.personId]?.label)
        o.putIf("project", s.projects[t.projectId]?.name)
        o.putIf("deal", s.deals[t.dealId]?.name)
        o.put("grp", DelaGroups.grpOf(t).title)
        if (!t.open) o.put("status", t.status)
        if (DelaGroups.isNow(t, today)) o.put("now", true)
        if (t.goal) o.put("goal", true)
        // Дела внутри дела (правило 27): родитель, место среди соседей, сколько внутри открыто — данными, как у веба;
        // отступ на экране и свёрнутый родитель «Деревом».
        DelaSteps.parentOf(t, s)?.let { o.put("in", numTitle(it)) }
        if (t.pos > 0) o.put("pos", t.pos)
        DelaSteps.insideOf(t.id, s).size.takeIf { it > 0 }?.let { o.put("inside", it) }
        if (r.depth > 0) o.put("depth", r.depth)
        if (r.folded) o.put("folded", true)
        return o.put("in_view", r.inView)
    }

    private fun numTitle(t: Dela.Task): String = (if (t.num > 0) "#${t.num} " else "") + t.title.take(80)

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
        // Дела внутри дела (правило 27): внутри чего это дело и что внутри него — по месту, как блок «Внутри».
        DelaSteps.parentOf(t, s)?.let { f.put("for", numTitle(it)) }
        val kids = DelaSteps.insideOf(t.id, s)
        if (kids.isNotEmpty()) f.put("inside", JSONArray(kids.mapIndexed { i, k ->
            "${i + 1}. ${numTitle(k)}" + (if (k.dueDate.isNotBlank()) " · срок ${k.dueDate}" else "") + (if (k.pos > 0) " · pos ${k.pos}" else "")
        }))
        DelaSteps.doneInside(t.id, s).takeIf { it > 0 }?.let { f.put("inside_done", it) }
        // Комментарии — как их рисует «Журнал» карточки (поезд 16.10.2026, проверка вагона 3): с пометкой «только тебе» у
        // приватных (правило 26 сервера). Без них записка с карточки о багах приватности («основание из Telegram ушло не в
        // приватный комментарий», «Это оно») не показала бы Claude ни комментариев, ни их приватности. Строки журнала
        // сервера карточка подгружает сама и сюда не попадают.
        val notes = comments(t.id, s)
        if (notes.isNotEmpty()) f.put("comments", JSONArray(notes))
        return JSONObject().put("kind", if (t.goal) "goal" else "task").put("id", t.id).putIf("num", t.num).put("title", t.title).put("fields", f)
    }

    /** Сколько последних комментариев дела в снимке и сколько знаков у каждого. */
    const val COMMENTS = 30
    const val COMMENT_MAX = 300

    /**
     * Комментарии дела строками «дата · автор · [только тебе · ]текст», старые первыми, последние [COMMENTS] — как
     * карточка (`DelaTaskPane`, «Журнал») и веб (`cardOf` в debug.js).
     */
    internal fun comments(taskId: String, s: Dela.Snapshot): List<String> = s.commentsOf(taskId).takeLast(COMMENTS).map { c ->
        val text = c.text.trim().let { if (it.length > COMMENT_MAX) it.take(COMMENT_MAX - 1) + "…" else it }
        listOf(c.createdAt.take(16).replace('T', ' '), c.authorId, if (c.private) "только тебе" else "", text)
            .filter { it.isNotBlank() }.joinToString(" · ")
    }

    /**
     * Главная вкладки («Сейчас» и «Сегодня») по порядку экрана — как её рисует `DelaHomeUi` (поезд 16.10.2026, проверка
     * вагона 3): в «Сейчас» дела внутри дел из «Сейчас» — прямо над родителем отступом ([inner] — дело и глубина), в
     * «Сегодня» — три подгруппы («Отбить», «Запустить», «Мониторить» — `DelaGroups.nested` по группе), в каждой дело
     * внутри — прямо перед родителем (`DelaSteps.stepsFirst`, как `TaskRows`). Раньше «Сегодня» шло в снимке одним куском
     * в порядке вида: дело внутри, получившее срок «сегодня» от родителя, на экране стояло перед ним, а в снимке — нет, и
     * подпись группы была без подгруппы. [seen] — id дел, бывших в окне.
     */
    fun homeRows(
        now: List<Dela.Task>,
        inner: List<Pair<Dela.Task, Int>>,
        todayList: List<Dela.Task>,
        s: Dela.Snapshot,
        today: String,
        seen: Set<String> = emptySet(),
    ): List<Row> {
        val out = ArrayList<Row>()
        val depthIn = inner.associate { it.first.id to it.second }
        DelaSteps.stepsFirst(now + inner.map { it.first }, s).forEach { out += Row(it.id, it.id in seen, "Сейчас", depthIn[it.id] ?: 0) }
        DelaGroups.nested(todayList, listOf(DelaGroups.Level.GRP), s, today).forEach { n ->
            DelaSteps.stepsFirst(n.items, s).forEach { out += Row(it.id, it.id in seen, "Сегодня › ${n.title}") }
        }
        return out
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
     * Действия записки — как кольцо веба, старые первыми: операции очереди ([ops] — миг и операция), события кольца
     * ([notes]: сказал Claude, ответ, тосты, что сервер не принял или сделал сверх) и строки журнала приложения
     * ([events]); все вместе по времени, не больше [EVENTS] + [OPS] + [NOTES]. [s] — копия: названия дел вместо id и
     * вложенность словами («внутрь «#12 …»», «вынуть из дела», «место 2»).
     */
    fun actions(
        ops: List<Pair<Long, JSONObject>>,
        events: List<String>,
        now: OffsetDateTime,
        notes: List<Pair<Long, JSONObject>> = emptyList(),
        s: Dela.Snapshot? = null,
    ): JSONArray {
        val all = ArrayList<Pair<String, JSONObject>>()
        fun iso(at: Long) = java.time.Instant.ofEpochMilli(at).atOffset(now.offset).toString()
        for ((at, op) in ops) {
            val t = iso(at)
            all += t to JSONObject().put("at", t).put("kind", "op").put("text", opWords(op, s)).put("data", op)
        }
        for ((at, n) in notes) {
            val t = iso(at)
            all += t to JSONObject(n.toString()).put("at", t)
        }
        for (line in events.takeLast(EVENTS)) {
            val t = logAt(line, now) ?: continue
            all += t to JSONObject().put("at", t).put("kind", "event").put("text", line.substring(minOf(line.length, 19)).trim().take(600))
        }
        return JSONArray().apply { all.sortedBy { it.first }.takeLast(EVENTS + OPS + NOTES).forEach { put(it.second) } }
    }

    private fun opWords(op: JSONObject, s: Dela.Snapshot? = null): String {
        val what = op.optJSONObject("task")?.optString("title").orEmpty().ifBlank {
            op.optJSONObject("data")?.let { it.optString("name").ifBlank { it.optString("summary") } }.orEmpty()
        }.ifBlank { s?.tasks?.get(op.optString("id"))?.title.orEmpty() }
        val nest = nestWords(op, s)
        return op.optString("op", "?") + (if (what.isNotBlank()) " «${what.take(80)}»" else "") + if (nest.isNotBlank()) " — $nest" else ""
    }

    private fun JSONObject.idOrNull(k: String): String? = opt(k)?.takeIf { it != JSONObject.NULL }?.toString()?.takeIf { it.isNotBlank() && it != "null" }

    /**
     * Вложенность словами (правило 27): без них в записке только uuid в `why_id`, и «вложил — а оно не там» Claude не
     * прочтёт. `why_id` на цель — это «Зачем», а не «внутрь».
     */
    internal fun nestWords(op: JSONObject, s: Dela.Snapshot?): String {
        val kind = op.optString("op")
        val set = when (kind) { "task.set" -> op.optJSONObject("set"); "task.create" -> op.optJSONObject("task"); else -> null } ?: return ""
        val out = ArrayList<String>()
        val why = set.idOrNull("why_id")
        if (set.has("why_id") && (kind == "task.set" || why != null)) {
            val p = why?.let { s?.tasks?.get(it) }
            out += when {
                why == null -> "вынуть из дела"
                p?.goal == true -> "Зачем — цель «${p.title.take(60)}»"
                else -> "внутрь «" + (p?.let { numTitle(it) } ?: why) + "»"
            }
        }
        if (set.has("pos")) out += set.idOrNull("pos")?.toIntOrNull()?.takeIf { it > 0 }?.let { "место $it" } ?: "место снято"
        return out.joinToString(", ")
    }

    // ------------------------------------------------------------ записка словами (лист «Отладка»)

    private fun oneLine(v: Any?, n: Int): String {
        val s = when (v) { null, JSONObject.NULL -> ""; is JSONArray -> (0 until v.length()).joinToString("; ") { v.opt(it).toString() }; else -> v.toString() }
        val x = s.split(Regex("\\s+")).filter { it.isNotEmpty() }.joinToString(" ")
        return if (x.length <= n) x else x.take(n - 1) + "…"
    }

    private val ROW_SKIP = setOf("id", "num", "title", "in_view", "depth", "in", "pos", "inside", "folded")
    private val SNAP_SKIP = setOf("v", "title", "route", "sub", "mode", "card", "selected", "rows", "rows_total", "kind")

    /**
     * Снимок записки словами — как `snapshot_lines` сервера (что видит Claude в `debug show`): экран, режим, открытое
     * полями, строки списка по порядку (★ — были в окне, «↳» — отступ, «внутри #N …», место, сколько внутри), прочее —
     * строкой на ключ. Лист «Отладка» раскрывает его под запиской.
     */
    fun snapshotLines(s: JSONObject?): List<String> {
        if (s == null || s.length() == 0) return listOf("снимка нет")
        val out = ArrayList<String>()
        out += "экран: " + listOf(s.optString("title"), s.optString("route"), s.optString("sub")).filter { it.isNotBlank() }.joinToString(" · ")
        s.optJSONObject("mode")?.let { m -> for (k in m.keys()) out += "  $k: ${oneLine(m.opt(k), 200)}" }
        s.optJSONObject("card")?.let { c ->
            out += "открыто: " + listOf(c.optString("kind"), c.optInt("num").takeIf { it > 0 }?.let { "#$it" }, c.optString("title"))
                .filter { !it.isNullOrBlank() }.joinToString(" ")
            c.optJSONObject("fields")?.let { f ->
                for (k in f.keys()) {
                    val v = f.opt(k)
                    if (v is JSONArray && v.length() > 0 && v.opt(0) is String) {
                        out += "  $k:"
                        for (i in 0 until v.length()) out += "    " + oneLine(v.opt(i), 200)
                    } else out += "  $k: ${oneLine(v, 300)}"
                }
            }
        }
        val rows = s.optJSONArray("rows")
        if (rows != null && rows.length() > 0) {
            val total = s.optInt("rows_total", rows.length())
            out += "строки списка: ${rows.length()}" + (if (total > rows.length()) " из $total" else "") + " · ★ — были в окне"
            for (i in 0 until rows.length()) {
                val r = rows.optJSONObject(i) ?: continue
                val nest = listOfNotNull(
                    r.optString("in").takeIf { it.isNotBlank() }?.let { "внутри $it" },
                    r.optInt("pos").takeIf { it > 0 }?.let { "место $it" },
                    r.optInt("inside").takeIf { it > 0 }?.let { "внутри открыто $it" + if (r.optBoolean("folded")) " (свёрнуто)" else "" },
                )
                val rest = r.keys().asSequence().filter { it !in ROW_SKIP }.mapNotNull { k ->
                    r.opt(k)?.takeIf { it != JSONObject.NULL && it.toString().isNotBlank() && it != false }?.let { if (it == true) k else oneLine(it, 80) }
                }.toList()
                out += "  " + (if (r.optBoolean("in_view")) "★ " else "  ") + "  ".repeat(minOf(r.optInt("depth"), 4)) +
                    (if (r.optInt("depth") > 0) "↳ " else "") + (r.optInt("num").takeIf { it > 0 }?.let { "#$it " } ?: "") +
                    r.optString("title").ifBlank { r.optString("id") } + (nest + rest).joinToString(" · ").let { if (it.isBlank()) "" else " — $it" }
            }
        }
        for (k in s.keys()) {
            if (k in SNAP_SKIP) continue
            val v = s.opt(k)
            if (v == null || v == JSONObject.NULL || v.toString().isBlank()) continue
            out += "$k: ${oneLine(v, 600)}"
        }
        return out
    }

    /** Последние действия словами, старые сверху: «10:14:52 Claude ответил: …». */
    fun actionLines(a: JSONArray?): List<String> {
        if (a == null || a.length() == 0) return listOf("действий нет")
        val word = mapOf("op" to "операция", "op_fail" to "сервер не принял", "op_result" to "сервер сделал", "ask" to "сказал Claude",
            "reply" to "Claude ответил", "toast" to "подсказка", "error" to "ошибка", "nav" to "переход", "event" to "журнал", "parse" to "разбор")
        return (0 until a.length()).mapNotNull { a.optJSONObject(it) }.map { x ->
            val at = x.optString("at")
            val hm = runCatching { OffsetDateTime.parse(at).atZoneSameInstant(ZoneId.of("Europe/Moscow")).toLocalTime().withNano(0).toString() }.getOrDefault(at.take(19))
            "$hm ${word[x.optString("kind")] ?: x.optString("kind")}: ${oneLine(x.optString("text"), 400)}"
        }
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
