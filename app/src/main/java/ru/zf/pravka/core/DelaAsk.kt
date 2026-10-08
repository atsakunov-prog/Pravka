package ru.zf.pravka.core

import java.time.LocalDate
import java.util.Locale
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela.str
import ru.zf.pravka.core.Dela.strings

/**
 * Правка дел словами — через сервер (`POST /api/ask`, контракт —
 * server/contract/dela-crm.json, раздел `ask`; docs/dela-phone-2.md, этап 1,
 * пункт 3). Здесь — то, что не знает про сеть: что видит Claude (scope),
 * что он ответил и как это вернуть одним движением.
 *
 * Почему правит сервер, а не телефон своим Claude: та же команда работает из
 * веба, и модель с глубиной — настройка человека на сервере («Настройки» веба).
 * Телефону выбирать нечего, и правка приходит уже применённой: в очередь она
 * не ложится, после ответа — синк. Спор с офлайн-правкой решает сервер по
 * `was`, как у любой другой правки.
 */
object DelaAsk {

    /** Потолок дел на экране у сервера (`ask.MAX_TASKS`): лишние он молча отрежет. */
    const val MAX_TASKS = 300

    /** Потолок сделок на экране (`deal_ids`, как у веба). */
    const val MAX_DEALS = 200

    /** Потолок предложений на экране (`suggestion_ids`, как у веба). */
    const val MAX_SUGGESTIONS = 150

    /** Потолок команды у сервера (`ask.MAX_INPUT`): длиннее — отказ словами, а не обрезка. */
    const val MAX_INPUT = 4000

    /**
     * Что видит Claude — то же, что видит человек: название экрана и дела в
     * порядке показа. id — только uuid (сервер иначе отвергнет весь запрос:
     * «scope: id — не uuid»), повторы — один раз. На странице проекта или
     * человека — их id: туда лягут новые дела, если команда про новое.
     */
    fun scope(
        title: String,
        taskIds: List<String>,
        focus: String = "",
        projectId: String = "",
        personId: String = "",
        dealId: String = "",
        card: String = "",
        suggestionIds: List<String> = emptyList(),
        visibleIds: List<String>? = null,
        selectedIds: List<String> = emptyList(),
        open: String = "",
    ): JSONObject {
        val ids = taskIds.filter { Dela.isUuid(it) }.distinct().take(MAX_TASKS)
        val o = JSONObject().put("title", title.take(200)).put("task_ids", JSONArray().apply { ids.forEach { put(it) } })
        if (focus.isNotBlank() && Dela.isUuid(focus)) o.put("focus", focus)
        if (projectId.isNotBlank() && Dela.isUuid(projectId)) o.put("project_id", projectId)
        if (personId.isNotBlank() && Dela.isUuid(personId)) o.put("person_id", personId)
        if (dealId.isNotBlank() && Dela.isUuid(dealId)) o.put("deal_id", dealId)
        if (card in CARDS) o.put("card", card)
        // «Новое»: предложения на экране по порядку — П1, П2… (`scope.suggestion_ids` сервера).
        val sugs = suggestionIds.filter { Dela.isUuid(it) }.distinct().take(MAX_SUGGESTIONS)
        if (sugs.isNotEmpty()) o.put("suggestion_ids", JSONArray().apply { sugs.forEach { put(it) } })
        return see(o, visibleIds, selectedIds, open)
    }

    /**
     * Что человек видит в миг отправки (08.10.2026, docs/dela-phone-7.md, `pageScope`
     * веба): [visibleIds] — строки, которые в окне прямо сейчас (null — неизвестно,
     * поля нет), [selectedIds] — выбранные, [open] — дело, открытое в карточке. Кто
     * такие «это» и «эти», решает сервер; старый сервер этих полей молча не заметит.
     */
    fun see(
        o: JSONObject,
        visibleIds: List<String>?,
        selectedIds: List<String> = emptyList(),
        open: String = "",
        dealIds: List<String> = emptyList(),
        visibleDealIds: List<String> = emptyList(),
    ): JSONObject {
        fun arr(ids: List<String>, max: Int = MAX_TASKS) = JSONArray().apply { ids.filter { Dela.isUuid(it) }.distinct().take(max).forEach { put(it) } }
        if (visibleIds != null) o.put("visible_ids", arr(visibleIds))
        // Сделки экрана (08.10.2026, docs/dela-phone-8.md): Воронка, клиенты, человек — «убери Альфу —
        // не работаем» Claude правит прямо отсюда. Нет сделок на экране — полей нет.
        val deals = dealIds.filter { Dela.isUuid(it) }
        if (deals.isNotEmpty()) {
            o.put("deal_ids", arr(deals, MAX_DEALS))
            o.put("visible_deal_ids", arr(visibleDealIds.filter { it in deals }, MAX_DEALS))
        }
        if (selectedIds.isNotEmpty()) o.put("selected_ids", arr(selectedIds))
        if (open.isNotBlank() && Dela.isUuid(open)) o.put("open", open)
        return o
    }

    /** Строка или плитка на экране: где она (уже обрезанная окном списка) и какой была бы целиком. */
    data class Seen(val top: Float, val bottom: Float, val left: Float, val right: Float, val fullHeight: Float, val fullWidth: Float)

    /**
     * «В окне» — как `seen` веба: видно хотя бы 60 % высоты и ширины, считая, что
     * снизу экран кончается у строки Claude ([bottom]) — строка, почти целиком
     * ушедшая под неё, не в счёт: её названия не видно.
     */
    fun inView(r: Seen, top: Float, bottom: Float): Boolean {
        if (r.fullHeight <= 0f || r.fullWidth <= 0f) return false
        val h = minOf(r.bottom, bottom) - maxOf(r.top, top)
        val w = r.right - r.left
        return h >= r.fullHeight * 0.6f && w >= r.fullWidth * 0.6f
    }

    /** Ключи в порядке экрана (сверху вниз, слева направо), которые [inView]. */
    fun visible(rows: Map<String, Seen>, top: Float, bottom: Float): List<String> =
        rows.entries.filter { inView(it.value, top, bottom) }.sortedWith(compareBy({ it.value.top }, { it.value.left })).map { it.key }
    /** Карточки, которые Claude видит целиком (`scope.card` сервера): клиент, сделка, человек. */
    val CARDS = setOf("client", "deal", "person")

    /**
     * Команда из карточки (06.10.2026, docs/dela-phone-4.md, `pageScope` веба):
     * сервер сам берёт Opus 5.5, даёт ему карточку целиком — сделки, людей,
     * хронологию — и правит не только дела, но и их (`crm[]` ответа). Новые дела
     * со страницы сделки ложатся в её проект и в неё саму.
     */
    fun clientScope(title: String, taskIds: List<String>, projectId: String): JSONObject =
        scope(title, taskIds, projectId = projectId, card = "client")

    fun dealScope(title: String, taskIds: List<String>, dealId: String, projectId: String): JSONObject =
        scope(title, taskIds, projectId = projectId, dealId = dealId, card = "deal")

    fun personScope(title: String, taskIds: List<String>, personId: String): JSONObject =
        scope(title, taskIds, personId = personId, card = "person")


    fun request(text: String, scope: JSONObject): JSONObject = JSONObject().put("text", text.trim()).put("scope", scope)

    /** Одна поправка Claude: как было (`before`) и стало (`after`), статус — парой [было, стало]. */
    data class Change(
        val id: String,
        val num: Int,
        val title: String,
        val before: JSONObject,
        val after: JSONObject,
        val statusFrom: String = "",
        val statusTo: String = "",
    )

    data class Note(val summary: String, val projectId: String)

    /**
     * Правка карточки (06.10.2026, `crm[]` ответа): хронология, люди, сделки —
     * [what] словами («в хронологию: …», «Иван: должность CFO»), [undo] —
     * операции «как было», готовые для очереди. Телефону карточки CRM правит
     * веб; здесь — показать, что поменялось, и вернуть вместе со всем.
     */
    data class Crm(val what: String, val undo: List<JSONObject>)

    /**
     * Решённое предложение «Нового» (`decided[]` ответа): П-номер [n], принято или
     * отклонено, вид, дело ([id], [title]; [created] — заведено им), что поменялось
     * ([before], [after], статус) и причина отказа. Отклонённое не возвращается.
     */
    data class Decided(
        val n: Int,
        val accept: Boolean,
        val kind: String,
        val id: String,
        val title: String,
        val created: Boolean,
        val before: JSONObject,
        val after: JSONObject,
        val statusFrom: String = "",
        val statusTo: String = "",
        val reason: String = "",
        val what: String = "",
    )

    /**
     * Ответ задания. `route = edit` — поправки дел на экране (`changed`) и, может
     * быть, новые дела; `route = new` — команда целиком про новые дела, сервер
     * отдал её разбору (как `/api/parse`): `tasks` и `notes` — что заведено.
     */
    data class Result(
        val route: String,
        val reply: String,
        val changed: List<Change>,
        val tasks: List<Dela.Task>,
        val notes: List<Note>,
        val errors: List<String>,
        val model: String,
        val crm: List<Crm> = emptyList(),
        val decided: List<Decided> = emptyList(),
    ) {
        /** Дела, которых коснулся Claude: поправленные и новые (решённые предложения — в [decided]). */
        val count: Int get() = changed.size + tasks.size
        val isNew: Boolean get() = route == "new"
        /** Есть что вернуть «Вернуть всё»: дела или правки карточки с «как было». */
        val undoable: Boolean get() = changed.isNotEmpty() || tasks.isNotEmpty() || decided.any { it.accept && it.id.isNotBlank() } || crm.any { it.undo.isNotEmpty() }

        /** Сделал ли что-нибудь: поправки, новые дела, заметки, решения, карточка. */
        val did: Boolean get() = changed.isNotEmpty() || tasks.isNotEmpty() || notes.isNotEmpty() || decided.isNotEmpty() || crm.isNotEmpty()
    }

    fun parse(o: JSONObject): Result {
        fun objs(key: String): List<JSONObject> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
        }
        fun pair(c: JSONObject): Pair<String, String> {
            val st = c.optJSONArray("status")?.takeIf { it.length() == 2 } ?: return "" to ""
            return (if (st.isNull(0)) "" else st.optString(0)) to (if (st.isNull(1)) "" else st.optString(1))
        }
        val decided = objs("decided").map { x ->
            val (from, to) = pair(x)
            Decided(
                n = x.optInt("n", 0), accept = x.str("decision") == "accept", kind = x.str("kind"), id = x.str("id"),
                title = x.str("title"), created = !x.isNull("created") && x.optBoolean("created", false),
                before = x.optJSONObject("before") ?: JSONObject(), after = x.optJSONObject("after") ?: JSONObject(),
                statusFrom = from, statusTo = to, reason = x.str("reason"), what = x.str("what"),
            )
        }
        val changed = objs("changed").mapNotNull { c ->
            val id = c.str("id").ifBlank { return@mapNotNull null }
            val st = c.optJSONArray("status")
            Change(
                id = id,
                num = if (c.isNull("num")) 0 else c.optInt("num", 0),
                title = c.str("title"),
                before = c.optJSONObject("before") ?: JSONObject(),
                after = c.optJSONObject("after") ?: JSONObject(),
                statusFrom = st?.takeIf { it.length() == 2 }?.let { if (it.isNull(0)) "" else it.optString(0) }.orEmpty(),
                statusTo = st?.takeIf { it.length() == 2 }?.let { if (it.isNull(1)) "" else it.optString(1) }.orEmpty(),
            )
        }
        val errors = o.optJSONArray("errors")?.let { a -> (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() && it != "null" } } }.orEmpty()
        return Result(
            route = o.str("route").ifBlank { "edit" },
            reply = o.str("reply").trim(),
            changed = changed,
            tasks = objs("tasks").mapNotNull { Dela.task(it) },
            notes = objs("notes").map { Note(it.str("summary"), it.str("project_id")) }.filter { it.summary.isNotBlank() },
            errors = errors,
            model = o.str("model"),
            crm = objs("crm").map { c ->
                val u = c.optJSONArray("undo")
                Crm(c.str("what").trim(), if (u == null) emptyList() else (0 until u.length()).mapNotNull { u.optJSONObject(it) })
            }.filter { it.what.isNotBlank() || it.undo.isNotEmpty() },
            decided = decided,
        )
    }

    /**
     * «Вернуть всё» — по правилу контракта (`ask.undo`): каждому changed —
     * `task.set` с `before` (если не пусто) и по статусу «было»: open —
     * `task.reopen`, done — `task.done`, cancelled — `task.cancel`; каждому
     * новому делу — `task.cancel` (строки на сервере не удаляются). В `was` —
     * то, что поставил Claude: если с тех пор поле меняли руками, сервер
     * скажет о споре, а не затрёт молча. Правки карточки (`crm[].undo`) —
     * как прислал сервер, каждой свой op_id (как у веба: «карточка — как было»).
     */
    fun undoOps(r: Result): List<JSONObject> {
        val out = mutableListOf<JSONObject>()
        for (c in r.changed) {
            if (c.before.length() > 0) {
                val was = JSONObject()
                for (k in c.before.keys()) if (c.after.has(k)) was.put(k, c.after.opt(k))
                out += Dela.setOp(c.id, JSONObject(c.before.toString()), was)
            }
            when (c.statusFrom) {
                Dela.OPEN -> out += Dela.statusOp("task.reopen", c.id)
                Dela.DONE -> out += Dela.statusOp("task.done", c.id)
                Dela.CANCELLED -> out += Dela.statusOp("task.cancel", c.id)
            }
        }
        for (t in r.tasks) out += Dela.statusOp("task.cancel", t.id)
        // Принятое из «Нового» (`turnUndo` веба): заведённое — отменой, уточнённое — как было.
        for (x in r.decided) {
            if (!x.accept || !Dela.isUuid(x.id)) continue
            if (x.created) { out += Dela.statusOp("task.cancel", x.id); continue }
            if (x.before.length() > 0) {
                val was = JSONObject()
                for (k in x.before.keys()) if (x.after.has(k)) was.put(k, x.after.opt(k))
                out += Dela.setOp(x.id, JSONObject(x.before.toString()), was)
            }
            when (x.statusFrom) {
                Dela.OPEN -> out += Dela.statusOp("task.reopen", x.id)
                Dela.DONE -> out += Dela.statusOp("task.done", x.id)
                Dela.CANCELLED -> out += Dela.statusOp("task.cancel", x.id)
            }
        }
        for (c in r.crm) for (u in c.undo) {
            if (u.str("op").isBlank()) continue
            out += JSONObject(u.toString()).put("op_id", Dela.newId())
        }
        return out
    }

    /** Что поменялось — словами, как в вебе: «срок 07.10, в «Сейчас», жду Наташа». */
    fun describe(c: Change, s: Dela.Snapshot, today: String = s.today): String {
        val a = c.after
        val out = mutableListOf<String>()
        if (a.has("title")) out += "название «${a.str("title")}»"
        if (a.has("due_date")) {
            val d = a.str("due_date")
            out += if (d.isBlank()) "без срока" else "срок " + ddmm(d, today) + a.str("due_time").takeIf { it.isNotBlank() }?.let { " ${it.take(5)}" }.orEmpty()
        } else if (a.has("due_time")) {
            out += a.str("due_time").let { if (it.isBlank()) "без времени" else "время ${it.take(5)}" }
        }
        if (a.has("focus_on")) out += if (a.str("focus_on").isBlank()) "из «Сейчас»" else "в «Сейчас»"
        if (a.has("project_id")) out += a.str("project_id").let { if (it.isBlank()) "без проекта" else "проект " + (s.projects[it]?.name ?: "?") }
        if (a.str("deal_id").isNotBlank()) out += "сделка " + (s.deals[a.str("deal_id")]?.name ?: "?")
        if (a.has("ball") || a.has("person_id")) {
            val who = if (a.has("person_id")) s.people[a.str("person_id")]?.label.orEmpty() else ""
            val ball = if (a.has("ball")) BALL[a.str("ball")] ?: a.str("ball") else "человек"
            out += ball + if (who.isNotBlank()) " $who" else ""
        }
        if (a.has("notes")) out += "дописал заметку"
        if (a.has("estimate_min")) out += if (a.isNull("estimate_min") || a.optInt("estimate_min", 0) == 0) "без оценки" else "${a.optInt("estimate_min")} мин"
        if (a.has("labels")) out += a.strings("labels").let { if (it.isEmpty()) "без меток" else "метки " + it.joinToString(", ") }
        when (c.statusTo) {
            Dela.DONE -> out += "сделано"
            Dela.CANCELLED -> out += "отменено"
            Dela.OPEN -> out += "в работу"
        }
        return out.joinToString(", ")
    }

    private val BALL = mapOf(Dela.MINE to "моё", Dela.WAITING to "жду", Dela.AGENDA to "повестка")

    // ------------------------------------------------------------ разговор (09.10.2026)

    /**
     * Реплика разговора с Claude (docs/dela-phone-9.md, п. 12; `claudeSay` веба):
     * что сказано, что он ответил и сделал; [undone] — «Вернуть» уже нажато.
     */
    data class Turn(val said: String, val result: Result, val undone: Boolean = false)

    /** Сколько прошлых реплик видит Claude (`TALK_KEEP` веба). */
    const val TALK_KEEP = 8

    private val DECIDED = mapOf("create" to "заведено", "close" to "закрыто", "update" to "уточнено", "assign" to "взято себе")

    /** Решение словами: «П1 заведено: …», «П2 отклонено: …». */
    fun decidedWord(x: Decided): String =
        "П${x.n} " + if (x.accept) (DECIDED[x.kind] ?: "принято") + (if (x.title.isNotBlank()) ": ${x.title}" else "")
        else "отклонено" + (x.title.ifBlank { x.what }.takeIf { it.isNotBlank() }?.let { ": $it" } ?: "")

    /**
     * Что сделано репликой — коротко, для памяти разговора на сервере (`turnMemo`
     * веба): `{said, reply, done}`; вернул — «Саша вернул всё это как было».
     */
    fun memo(t: Turn, s: Dela.Snapshot): JSONObject {
        val r = t.result
        val out = mutableListOf<String>()
        if (r.isNew) {
            if (r.tasks.isNotEmpty()) out += "завёл: " + r.tasks.joinToString(", ") { it.title }
            if (r.notes.isNotEmpty()) out += "в хронологию: " + r.notes.joinToString("; ") { it.summary }
        } else {
            for (c in r.changed) out += (c.after.str("title").ifBlank { c.title }) + ": " + describe(c, s)
            if (r.tasks.isNotEmpty()) out += "завёл: " + r.tasks.joinToString(", ") { it.title }
            for (x in r.decided) out += "П${x.n} " + if (x.accept) DECIDED[x.kind] ?: "принято" else "отклонено"
            for (c in r.crm) out += c.what
        }
        if (r.errors.isNotEmpty()) out += "не вышло: " + r.errors.joinToString("; ")
        val done = out.joinToString("; ").ifBlank { "ничего не менял" }.take(700) + if (t.undone) " — Саша вернул всё это как было" else ""
        return JSONObject().put("said", t.said.take(600)).put("reply", r.reply.take(900)).put("done", done)
    }

    /**
     * Следующая реплика уходит вместе с разговором: `scope.history` — до
     * [TALK_KEEP] прошлых реплик. Старый сервер поля молча не заметит.
     */
    fun withHistory(scope: JSONObject, turns: List<Turn>, s: Dela.Snapshot): JSONObject {
        if (turns.isEmpty()) return scope
        return JSONObject(scope.toString()).put("history", JSONArray().apply { turns.takeLast(TALK_KEEP).forEach { put(memo(it, s)) } })
    }

    /** «07.10», а не своего года — «07.10.27»: как `D.ddmm` веба. */
    fun ddmm(iso: String, today: String): String {
        val d = runCatching { LocalDate.parse(iso.take(10)) }.getOrNull() ?: return iso
        val y = runCatching { LocalDate.parse(today.take(10)).year }.getOrNull()
        return String.format(Locale.ROOT, "%02d.%02d", d.dayOfMonth, d.monthValue) +
            if (y != null && y != d.year) String.format(Locale.ROOT, ".%02d", d.year % 100) else ""
    }

    /**
     * Опрос задания: первые 15 раз — раз в секунду (обычная правка — 3–6 с),
     * потом реже; пять минут без ответа — честное «думает дольше», не вечный круг.
     */
    fun pollDelayMs(attempt: Int): Long = if (attempt < 15) 1_000L else 2_500L

    const val MAX_POLLS = 130
}
