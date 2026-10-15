package ru.zf.pravka.core

import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela.str

/**
 * Захват — «сказал → легло → вернётся» (шаг 0 петли этапа 4, 15.10.2026; задание — docs/dela-phone-22c.md, контракт —
 * server/contract/dela-capture.json, сервер — server/pravka_dela/capture.py).
 *
 * Владелец: «захват за три секунды, которому я доверяю… „напомни в 12 в Контуре отправить счёт“ — не знал, куда: в
 * календарь нельзя — Засечка примет за встречу; в Дела — утонет среди остального». Одна дверь: «сказать», «Д» и «З»
 * шлют фразу одной операцией `capture.say`; раскладывает СЕРВЕР — правилом за миллисекунды ($0) или моделью; корзину
 * выбирает код по полям; строку «куда легло и когда вернётся» и до трёх кнопок поправки (`capture.move`) отдаёт
 * сервер. Телефон кнопок не придумывает и строк не сочиняет — только рисует то, что сказал сервер (одна правда).
 *
 * Здесь — то, что не знает ни про сеть, ни про Android: операции и разбор ответа, под JVM-тестом против примеров
 * контракта.
 */
object Capture {

    /** Только правило: не узнало — `status: none`, ничего не записано (телефон параллельно спрашивает свою развилку). */
    const val LANE_RULE = "rule"

    /** Не узнало правило — наговорка ждёт модели на сервере (`status: pending`). */
    const val LANE_AUTO = "auto"

    /** Откуда фраза: строка «сказать», кнопка «Д», «З». */
    const val FROM_SAY = "say"
    const val FROM_D = "d"
    const val FROM_Z = "z"

    /** Сколько фразы сервер примет (`capture.MAX_INPUT`). */
    const val MAX_INPUT = 4000

    /** Модель раскладывает 5–10 с; спрашивать вид `capture?ref=` раз в 0,7 с, дольше полутора минут не ждать. */
    const val POLL_MS = 700L
    const val POLL_MAX_MS = 90_000L

    /** Отмена записи в пилюле и меню «Д» — десять минут, дальше — в самой записи. */
    const val UNDO_WINDOW_MS = 10 * 60_000L

    enum class Status(val key: String) {
        DONE("done"), PENDING("pending"), ERROR("error"), NONE("none");

        companion object {
            fun of(key: String): Status = entries.firstOrNull { it.key == key } ?: NONE
        }
    }

    /** Вариант кнопки «Время ▾»: «через час» → `at: hour`. */
    data class Option(val label: String, val at: String)

    /**
     * Кнопка поправки одним касанием (`chips[]` сервера): подпись и куда переложить. [options] — у «Время» (выбор
     * часа), [personId] — у тёзок заметки («Иван Тестов» · «Иван Петров»).
     */
    data class Chip(val label: String, val move: String, val options: List<Option> = emptyList(), val personId: String = "") {
        /** «Отменить» — полная отмена записи. */
        val cancel: Boolean get() = move == MOVE_CANCEL
    }

    /** Запись захвата: корзина, строка и кнопки; у дела — его id и время, у заметки и идеи — их id. */
    data class Item(
        val bucket: String,
        val line: String,
        val chips: List<Chip> = emptyList(),
        val taskId: String = "",
        val title: String = "",
        val remindAt: String = "",
        val remindPlace: String = "",
        val dueDate: String = "",
        val backOn: String = "",
        val noteId: String = "",
        val ideaId: String = "",
        val warn: String = "",
    )

    /**
     * Итог захвата одной фразы (`capture` в ответе операции и вида): [head] — одна строка на всю фразу, [items] — по
     * записи. У [Status.ERROR] — [error] словами и кнопки «Повторить» / «Дело потом» ([chips] верхнего уровня).
     */
    data class Result(
        val ref: String,
        val status: Status,
        val by: String = "",
        val head: String = "",
        val items: List<Item> = emptyList(),
        val text: String = "",
        val error: String = "",
        val form: String = "",
        val ms: Long = 0,
        val chips: List<Chip> = emptyList(),
    ) {
        /** Записано правилом или моделью — строку можно показывать как итог. */
        val done: Boolean get() = status == Status.DONE

        /**
         * Кнопки под строкой: у одной записи — её (до трёх), у сбоя — «Повторить» и «Дело потом»; у нескольких записей —
         * ничего: поправка — в списке по тапу (у каждой строки свои).
         */
        val lineChips: List<Pair<Int, Chip>>
            get() = when {
                status == Status.ERROR -> chips.map { 0 to it }
                items.size == 1 -> items[0].chips.map { 0 to it }
                else -> emptyList()
            }
    }

    const val MOVE_CANCEL = "cancel"
    const val MOVE_TIME = "time"

    // ------------------------------------------------------------ операции

    /**
     * Фраза одной дверью: `capture.say`. [ref] — свой id захвата: по нему повтор безвреден (тот же итог), вид
     * `capture?ref=` отдаёт итог, `capture.move` поправляет. [saidAt] — когда сказано («в 12» считается от него: очередь
     * без связи может уйти позже). [hint] — как телефон слышит имена (хвостом для модели).
     */
    fun sayOp(text: String, ref: String, lane: String, from: String, saidAt: String, hint: String = "", opId: String = Dela.newId()): JSONObject =
        JSONObject().put("op", "capture.say").put("op_id", opId).put("text", text.trim().take(MAX_INPUT)).put("ref", ref)
            .put("lane", lane).put("from", from).put("said_at", saidAt)
            .apply { if (hint.isNotBlank()) put("hint", hint.take(MAX_INPUT)) }

    /** Нажатие кнопки поправки: `capture.move {ref, item, to, at?, person_id?}`. */
    fun moveOp(ref: String, item: Int, to: String, at: String = "", personId: String = "", opId: String = Dela.newId()): JSONObject =
        JSONObject().put("op", "capture.move").put("op_id", opId).put("ref", ref).put("item", item).put("to", to)
            .apply {
                if (at.isNotBlank()) put("at", at)
                if (personId.isNotBlank()) put("person_id", personId)
            }

    /** Нажатая кнопка (или выбранный вариант «Время») — операция. */
    fun moveFor(ref: String, item: Int, chip: Chip, option: Option? = null, opId: String = Dela.newId()): JSONObject =
        moveOp(ref, item, chip.move, at = option?.at.orEmpty(), personId = chip.personId, opId = opId)

    /** Свой id захвата: uuid без лишнего — сервер берёт `[A-Za-z0-9_-]{1,64}`. */
    fun newRef(): String = Dela.newId()

    // ------------------------------------------------------------ ответ

    /** Объект `capture` (ответ операции, вида, повтора) → итог; нет объекта — null. */
    fun parse(o: JSONObject?): Result? {
        if (o == null) return null
        val items = o.optJSONArray("items")?.let { a -> (0 until a.length()).mapNotNull { a.optJSONObject(it)?.let(::item) } }.orEmpty()
        return Result(
            ref = o.str("ref"),
            status = Status.of(o.str("status")),
            by = o.str("by"),
            head = o.str("head"),
            items = items,
            text = o.str("text"),
            error = o.str("error"),
            form = o.str("form"),
            ms = o.optLong("ms", 0L),
            chips = chips(o.optJSONArray("chips")),
        )
    }

    private fun item(o: JSONObject): Item {
        val t = o.optJSONObject("task")
        return Item(
            bucket = o.str("bucket"),
            line = o.str("line"),
            chips = chips(o.optJSONArray("chips")),
            taskId = t?.str("id").orEmpty(),
            title = t?.str("title").orEmpty().ifBlank { o.str("title") }.ifBlank { o.str("text") },
            remindAt = t?.str("remind_at").orEmpty(),
            remindPlace = t?.str("remind_place").orEmpty(),
            dueDate = t?.str("due_date").orEmpty(),
            backOn = t?.str("back_on").orEmpty(),
            noteId = o.str("note_id"),
            ideaId = o.str("idea_id"),
            warn = o.str("warn"),
        )
    }

    private fun chips(a: JSONArray?): List<Chip> {
        if (a == null) return emptyList()
        return (0 until a.length()).mapNotNull { i ->
            val c = a.optJSONObject(i) ?: return@mapNotNull null
            val label = c.str("label").ifBlank { return@mapNotNull null }
            val move = c.str("move").ifBlank { return@mapNotNull null }
            val opts = c.optJSONArray("options")?.let { oa ->
                (0 until oa.length()).mapNotNull { j -> oa.optJSONObject(j)?.let { x -> Option(x.str("label"), x.str("at")).takeIf { it.label.isNotBlank() && it.at.isNotBlank() } } }
            }.orEmpty()
            Chip(label, move, opts, c.str("person_id"))
        }.take(3)
    }

    /**
     * Ответ сервера на операцию (`results[]` очереди или мимо неё): принято — итог из `capture`; отказ — его словами
     * (правило 6: ошибку не затирать общей фразой). Итога нет вовсе — null.
     */
    sealed class Reply {
        data class Ok(val result: Result) : Reply()
        data class Refused(val error: String) : Reply()
    }

    fun reply(r: JSONObject?): Reply? {
        if (r == null) return null
        if (!r.optBoolean("ok", false)) return Reply.Refused(r.str("error").ifBlank { "сервер не принял без причины" })
        return parse(r.optJSONObject("capture"))?.let { Reply.Ok(it) }
    }

    // ------------------------------------------------------------ слова телефона

    /** Без связи с Делами: фраза ждёт в очереди на диске (spec-0 §2.5, строка «нет связи»). */
    const val OFFLINE_LINE = "Нет связи с Делами — записал, разложу, как появится связь"

    /** Пока модель раскладывает — то же, что скажет сервер, но до его ответа. */
    fun pendingLine(text: String): String {
        val t = text.trim().replace(Regex("\\s+"), " ")
        return "Записал «" + (if (t.length > 40) t.take(39) + "…" else t) + "» — раскладываю"
    }

    /** Строка итога для глаз: у одной записи — её строка, иначе голова сервера; пусто — что сказал сам телефон. */
    fun line(r: Result): String = when (r.status) {
        Status.ERROR -> r.head.ifBlank { "Не разложил: " + r.error.ifBlank { "без причины" } }
        Status.PENDING -> r.head.ifBlank { pendingLine(r.text) }
        Status.NONE -> r.head
        Status.DONE -> r.head.ifBlank { r.items.singleOrNull()?.line.orEmpty() }.ifBlank { "Записал" }
    }

    /** Строки записей для раскрытого списка (у одной записи список не нужен — она и есть строка). */
    fun rows(r: Result): List<String> = if (r.items.size > 1) r.items.map { it.line } else emptyList()

    /**
     * Что сказать в гарнитуру после захвата (голос «З»): коротко, по корзине. Строку целиком в ухо не читаем — она
     * на пилюле; голос только подтверждает, что легло.
     */
    fun spoken(r: Result): String = when {
        r.status == Status.ERROR -> "не разложил"
        r.status == Status.PENDING -> "записал, раскладываю"
        r.items.size > 1 -> "разложил"
        else -> when (r.items.firstOrNull()?.bucket) {
            "remind", "place" -> "напомню"
            "note" -> "записал заметку"
            "idea" -> "записал идею"
            "did" -> "отметил сделанным"
            "back" -> "отложил"
            else -> "записал"
        }
    }

    /** Кнопки, которые отменяют всё сказанное разом («Отменить последнее» в меню «Д»): по «Отменить» каждой записи. */
    fun cancelAll(r: Result, opId: (Int) -> String = { Dela.newId() }): List<JSONObject> =
        r.items.withIndex().filter { (_, x) -> x.bucket != "cancelled" && x.chips.any { it.cancel } }
            .map { (i, _) -> moveOp(r.ref, i, MOVE_CANCEL, opId = opId(i)) }

    /** Задача, которую лёг захват (для «открыть»): первое дело, иначе пусто. */
    fun firstTask(r: Result): String = r.items.firstOrNull { it.taskId.isNotBlank() }?.taskId.orEmpty()
}
