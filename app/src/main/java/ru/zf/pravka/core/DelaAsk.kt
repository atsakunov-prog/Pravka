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
    ): JSONObject {
        val ids = taskIds.filter { Dela.isUuid(it) }.distinct().take(MAX_TASKS)
        val o = JSONObject().put("title", title.take(200)).put("task_ids", JSONArray().apply { ids.forEach { put(it) } })
        if (focus.isNotBlank() && Dela.isUuid(focus)) o.put("focus", focus)
        if (projectId.isNotBlank() && Dela.isUuid(projectId)) o.put("project_id", projectId)
        if (personId.isNotBlank() && Dela.isUuid(personId)) o.put("person_id", personId)
        return o
    }

    /** Микрофон у дела: команда про одно это дело. */
    fun taskScope(taskId: String): JSONObject = scope("Одно дело", listOf(taskId), focus = taskId)

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
    ) {
        /** Дела, которых коснулся Claude: поправленные и новые. */
        val count: Int get() = changed.size + tasks.size
        val isNew: Boolean get() = route == "new"
        /** Есть что вернуть «Вернуть всё»: дела или правки карточки с «как было». */
        val undoable: Boolean get() = count > 0 || crm.any { it.undo.isNotEmpty() }
    }

    fun parse(o: JSONObject): Result {
        fun objs(key: String): List<JSONObject> {
            val a = o.optJSONArray(key) ?: return emptyList()
            return (0 until a.length()).mapNotNull { a.optJSONObject(it) }
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
