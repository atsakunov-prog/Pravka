package ru.zf.pravka.data

import android.content.Context
import java.io.File
import java.time.LocalDate
import java.time.OffsetDateTime
import java.time.temporal.ChronoUnit
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.Dela

/**
 * Дела на телефоне: копия сервера (`dela.json`) и очередь операций
 * (`dela-outbox.json`). docs/dela-server.md, «Задание телефону», пункт 2.
 *
 * Копия — расходная: сервер источник правды, и полный синк соберёт её
 * заново. Очередь — нет: в ней правки владельца, которые сервер ещё не
 * видел (закрыл дело в метро, наговорил три дела без сети). Поэтому очередь
 * пишется на диск сразу и сама, до того как правка показалась на экране, а
 * копия — фоном через общий писатель.
 *
 * Что видит вкладка — копия с очередью поверх ([view]): своя правка видна
 * сразу, до ответа сервера. Операция уходит из очереди, только когда сервер
 * на неё ответил: принял — следующий синк привезёт ту же строку с новым
 * `rev`; отказал (`ok: false` — формат или права, повтор не поможет) —
 * снимается и остаётся словами в [notices], а не исчезает молча.
 */
class DelaStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "dela.json"
        const val OUTBOX_FILE = "dela-outbox.json"
        /** Последние ответы CRM-видов (`/api/view/…`) — расходный кэш, как и копия. */
        const val VIEWS_FILE = "dela-views.json"
        private const val KEEP_NOTICES = 30
        /** Видов клиента, сделки и человека бывает много — храним свежие. */
        private const val KEEP_VIEWS = 60
        /**
         * Выбор этого телефона, как память браузера у веба: избранные проекты
         * (звезда) и группировка списков («по датам», «по проектам»…). На сервер
         * не едет, с базой — едет.
         */
        const val FAVS_FILE = "dela-favs.json"
    }

    /** Ответ CRM-вида и когда он пришёл: экран открывается с ним без сети и обновляется. */
    data class CachedView(val at: Long, val data: JSONObject)

    /** Операция в очереди: сама операция с op_id, когда встала, сколько раз пробовали и чем кончилось. */
    data class Queued(val op: JSONObject, val at: Long, val tries: Int = 0, val error: String = "") {
        val opId: String get() = op.optString("op_id")
    }

    /** То, что владелец должен узнать словами: отказ сервера, спор правок. */
    data class Notice(val at: Long, val text: String, val error: Boolean)

    private val mutex = Mutex()
    private val file: File get() = File(DataRoot.dir(context), FILE_NAME)
    private val outboxFile: File get() = File(DataRoot.dir(context), OUTBOX_FILE)
    private val viewsFile: File get() = File(DataRoot.dir(context), VIEWS_FILE)
    private val favsFile: File get() = File(DataRoot.dir(context), FAVS_FILE)
    private var loaded = false

    private var server = Dela.Snapshot()
    private var outbox = mutableListOf<Queued>()
    private var notices = mutableListOf<Notice>()

    /** Кто я на сервере (id пользователя из `/api/me`): виды «моё» считаются от него. */
    @Volatile var me: String = ""
        private set

    private val _view = MutableStateFlow(Dela.Snapshot())
    /** Копия сервера с неотправленной очередью поверх — то, что показывает вкладка. */
    val view: StateFlow<Dela.Snapshot> = _view

    private val _queued = MutableStateFlow<List<Queued>>(emptyList())
    val queued: StateFlow<List<Queued>> = _queued

    private val _notices = MutableStateFlow<List<Notice>>(emptyList())
    val noticesFlow: StateFlow<List<Notice>> = _notices

    private val _favs = MutableStateFlow<Set<String>>(emptySet())
    /** Избранные проекты — сверху «Проектов». */
    val favsFlow: StateFlow<Set<String>> = _favs

    private val _groups = MutableStateFlow<Map<String, String>>(emptyMap())
    /** Группировка списков по ключу списка («inbox», «all», «project») — как `S.groups` веба. */
    val groupsFlow: StateFlow<Map<String, String>> = _groups

    private val _filters = MutableStateFlow<JSONArray?>(null)
    /**
     * Фильтры Дел (10.10.2026, `settings.filters` из `/api/me`): живут на сервере,
     * веб видит те же. null — сервер их ещё не отдал (заводские); копия — для
     * открытия без сети, своя правка — сразу сюда и в очередь (`user.settings`).
     */
    val filtersFlow: StateFlow<JSONArray?> = _filters

    /** Что сказал сервер: своё неотправленное (в очереди `user.settings`) не перетирается. */
    suspend fun setServerFilters(a: JSONArray?) = mutex.withLock {
        ensureLoaded()
        if (outbox.any { it.op.optString("op") == "user.settings" }) return@withLock
        if (a?.toString() == _filters.value?.toString()) return@withLock
        _filters.value = a
        writePrefs()
    }

    /** Свой список фильтров целиком: виден сразу, на сервер — операцией очереди. */
    suspend fun saveFilters(a: JSONArray, op: JSONObject) {
        mutex.withLock {
            ensureLoaded()
            _filters.value = a
            writePrefs()
        }
        enqueue(listOf(op))
    }

    private val _clientOpen = MutableStateFlow<Set<String>>(emptySet())
    /**
     * Клиенты, у которых проекты раскрыты — в ☰ и в «Клиентах» (`S.clientOpen`
     * веба, 06.10.2026): раскрытое помнится, как избранное.
     */
    val clientOpenFlow: StateFlow<Set<String>> = _clientOpen

    /** Раскрыть или свернуть проекты клиентов: один клиент или «раскрыть все / свернуть все». */
    suspend fun setClientOpen(ids: Collection<String>, open: Boolean) = mutex.withLock {
        ensureLoaded()
        _clientOpen.value = if (open) _clientOpen.value + ids else _clientOpen.value - ids.toSet()
        writePrefs()
    }

    /** Звезда у проекта: поставить или снять. Файл крошечный — пишется сразу. */
    suspend fun toggleFav(projectId: String) = mutex.withLock {
        ensureLoaded()
        _favs.value = _favs.value.let { if (projectId in it) it - projectId else it + projectId }
        writePrefs()
    }

    suspend fun setGroup(list: String, by: String) = mutex.withLock {
        ensureLoaded()
        _groups.value = _groups.value + (list to by)
        writePrefs()
    }

    private suspend fun writePrefs() {
        val text = JSONObject().put("v", 1)
            .put("projects", JSONArray().apply { _favs.value.sorted().forEach { put(it) } })
            .put("groups", JSONObject().apply { for ((k, v) in _groups.value) put(k, v) })
            .put("clientOpen", JSONArray().apply { _clientOpen.value.sorted().forEach { put(it) } })
            .apply { _filters.value?.let { put("filters", it) } }
            .toString()
        withContext(Dispatchers.IO) { StoreFiles.writeAtomic(favsFile, text) }
    }

    private var views = LinkedHashMap<String, CachedView>()
    private val _views = MutableStateFlow<Map<String, CachedView>>(emptyMap())
    /**
     * CRM-виды с сервера по пути («pipeline», «deal?deal_id=…»). Считает их
     * сервер — логика общая с вебом; телефон показывает последний ответ и
     * накладывает поверх свою очередь (`DelaCrm.overlay*`).
     */
    val viewsFlow: StateFlow<Map<String, CachedView>> = _views

    /** Ответ вида — в кэш и на диск фоном. Пустой ответ — тоже ответ («клиентов нет»): он не стирает файл, а заменяет одну запись. */
    suspend fun putView(path: String, data: JSONObject, at: Long) = mutex.withLock {
        ensureLoaded()
        views.remove(path)
        views[path] = CachedView(at, data)
        while (views.size > KEEP_VIEWS) views.remove(views.keys.first())
        _views.value = LinkedHashMap(views)
        val o = JSONObject().put("v", 1).put("views", JSONObject().apply {
            for ((k, v) in views) put(k, JSONObject().put("at", v.at).put("data", v.data))
        })
        val text = o.toString()
        DiskWriter.post { StoreFiles.writeAtomic(viewsFile, text) }
    }

    suspend fun load() = mutex.withLock { ensureLoaded() }

    suspend fun setMe(user: String) = mutex.withLock {
        ensureLoaded()
        if (me == user) return@withLock
        me = user
        publish()
    }

    /** Номер последнего изменения, которое видел телефон: `since` следующего синка. */
    suspend fun seq(): Long = mutex.withLock { ensureLoaded(); server.seq }

    /**
     * Ответ синка поверх копии. Полный ответ, в котором нет ничего, поверх
     * непустой копии не ложится: строки на сервере не удаляются, и пустота
     * значит не «всё убрали», а «спросили не ту базу» (железное правило 1:
     * пустой список поверх файла не пишется). Возвращает причину отказа или "".
     */
    suspend fun applySync(resp: JSONObject, now: Long): String = mutex.withLock {
        ensureLoaded()
        val next = Dela.merge(server, resp, now)
        if (resp.optBoolean("full", false) && next.empty && !server.empty) {
            return@withLock "сервер прислал пустой список поверх ${server.tasks.size} дел — копию не трогаю"
        }
        server = next
        persist()
        publish()
        ""
    }

    /** Подключились заново (может быть, к другому серверу): копию — с нуля, очередь остаётся. */
    suspend fun resetCopy() = mutex.withLock {
        ensureLoaded()
        server = Dela.Snapshot()
        persist()
        // Виды CRM — того сервера, которого больше нет: и они с нуля.
        views.clear()
        _views.value = emptyMap()
        DiskWriter.post { StoreFiles.writeAtomic(viewsFile, JSONObject().put("v", 1).put("views", JSONObject()).toString()) }
        publish()
    }

    /** Операции — в очередь, на диск сразу. Тот же op_id второй раз не встаёт. */
    suspend fun enqueue(ops: List<JSONObject>) = mutex.withLock {
        ensureLoaded()
        val have = outbox.map { it.opId }.toHashSet()
        val now = System.currentTimeMillis()
        var added = 0
        for (op in ops) {
            var id = op.optString("op_id")
            if (id.isBlank()) continue
            // op_id на сервере — uuid; иная строка уронила бы всю пачку, и
            // очередь встала бы навсегда. Выводим из неё постоянный uuid.
            if (!Dela.isUuid(id)) {
                id = Dela.stableId(id)
                op.put("op_id", id)
            }
            if (id in have) continue
            outbox += Queued(op, now)
            have += id
            added++
        }
        if (added > 0) {
            writeOutbox()
            publish()
        }
    }

    /** Что отправить: вся очередь по порядку. */
    suspend fun pending(): List<Queued> = mutex.withLock { ensureLoaded(); outbox.toList() }

    /** Есть ли ещё в очереди эта операция (не дошла до сервера). */
    fun isQueued(opId: String): Boolean = _queued.value.any { it.opId == opId }

    /**
     * «Отменить» Разноски: операция, ещё не ушедшая на сервер, просто
     * снимается. true — сняли; false — она уже на сервере, отменять надо
     * следующей операцией.
     */
    suspend fun dropQueued(opId: String): Boolean = mutex.withLock {
        ensureLoaded()
        val removed = outbox.removeAll { it.opId == opId }
        if (removed) {
            writeOutbox()
            publish()
        }
        removed
    }

    /**
     * Ответ `/api/ops`: принятое уходит из очереди, отвергнутое — тоже, но
     * словами в заметки; спор правок (`conflicts`) — заметкой: владелец
     * должен знать, что поле меняли и с другой стороны.
     */
    suspend fun ack(results: JSONArray): Int = mutex.withLock {
        ensureLoaded()
        val byId = outbox.associateBy { it.opId }
        val done = HashSet<String>()
        val now = System.currentTimeMillis()
        for (i in 0 until results.length()) {
            val r = results.optJSONObject(i) ?: continue
            val id = r.optString("op_id").takeIf { it.isNotBlank() && it != "null" } ?: continue
            val q = byId[id] ?: continue
            done += id
            val what = Dela.describe(q.op, _view.value)
            if (!r.optBoolean("ok", false)) {
                // «На сервере уже версии N» у записи Свода — не отказ, а повод
                // слить своё со свежим: это делает SvodStore, владельцу шуметь незачем.
                if (q.op.optString("op") == "svod.set" && ru.zf.pravka.core.Svod.isStale(r.optString("error"))) continue
                notices.add(0, Notice(now, "Сервер не принял $what: ${r.optString("error").ifBlank { "без причины" }}", true))
                continue
            }
            val conflicts = r.optJSONArray("conflicts")
            if (conflicts != null && conflicts.length() > 0) {
                val fields = (0 until conflicts.length()).joinToString(", ") { fieldName(conflicts.optString(it)) }
                notices.add(0, Notice(now, "$what: $fields меняли и с другой стороны — осталось твоё, прежнее в журнале дела", false))
            }
        }
        if (done.isNotEmpty()) {
            outbox.removeAll { it.opId in done }
            while (notices.size > KEEP_NOTICES) notices.removeAt(notices.lastIndex)
            writeOutbox()
            publish()
        }
        done.size
    }

    /** Отправка не удалась целиком (сеть, сервер): очередь та же, причина — при операциях. */
    suspend fun markTry(error: String) = mutex.withLock {
        ensureLoaded()
        if (outbox.isEmpty()) return@withLock
        outbox = outbox.map { it.copy(tries = it.tries + 1, error = error) }.toMutableList()
        writeOutbox()
        publish()
    }

    suspend fun dismissNotices() = mutex.withLock {
        ensureLoaded()
        if (notices.isEmpty()) return@withLock
        notices.clear()
        writeOutbox()
        publish()
    }

    private fun fieldName(key: String): String = when (key) {
        "title" -> "название"
        "notes" -> "заметки"
        "project_id" -> "проект"
        "deal_id" -> "сделку"
        "ball" -> "мяч"
        "person_id" -> "человека"
        "nudge_on" -> "напоминание"
        "due_date" -> "срок"
        "due_time" -> "время"
        "estimate_min" -> "оценку"
        "money" -> "деньги"
        "stage" -> "стадию"
        "outcome" -> "итог"
        "paid_on" -> "оплату"
        "invoiced_on" -> "счёт"
        "cadence" -> "теплоту"
        "focus_on" -> "«Сейчас»"
        "labels" -> "метки"
        "status" -> "статус"
        else -> key
    }

    private fun publish() {
        val today = LocalDate.now().toString()
        val nowIso = OffsetDateTime.now().truncatedTo(ChronoUnit.SECONDS).toString()
        _view.value = Dela.overlay(server, outbox.map { it.op }, me, today, nowIso)
        _queued.value = outbox.toList()
        _notices.value = notices.toList()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { Dela.fromJson(JSONObject(it)) }?.let { server = Dela.derive(it) }
            StoreFiles.readOrQuarantine(outboxFile) { JSONObject(it) }?.let { o ->
                me = o.optString("me")
                o.optJSONArray("ops")?.let { a ->
                    for (i in 0 until a.length()) {
                        val q = a.optJSONObject(i) ?: continue
                        val op = q.optJSONObject("op") ?: continue
                        outbox += Queued(op, q.optLong("at"), q.optInt("tries"), q.optString("error"))
                    }
                }
                o.optJSONArray("notices")?.let { a ->
                    for (i in 0 until a.length()) {
                        val n = a.optJSONObject(i) ?: continue
                        notices += Notice(n.optLong("at"), n.optString("text"), n.optBoolean("error"))
                    }
                }
            }
            StoreFiles.readOrQuarantine(viewsFile) { JSONObject(it) }?.optJSONObject("views")?.let { v ->
                for (k in v.keys()) {
                    val c = v.optJSONObject(k) ?: continue
                    val data = c.optJSONObject("data") ?: continue
                    views[k] = CachedView(c.optLong("at"), data)
                }
            }
        }
        _views.value = LinkedHashMap(views)
        withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(favsFile) { JSONObject(it) }?.let { o ->
                o.optJSONArray("projects")?.let { a ->
                    _favs.value = (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }.toSet()
                }
                o.optJSONObject("groups")?.let { g -> _groups.value = g.keys().asSequence().associateWith { g.optString(it) } }
                _filters.value = o.optJSONArray("filters")
                o.optJSONArray("clientOpen")?.let { a ->
                    _clientOpen.value = (0 until a.length()).mapNotNull { i -> a.optString(i).takeIf { it.isNotBlank() } }.toSet()
                }
            }
        }
        loaded = true
        publish()
    }

    private fun persist() {
        val json = Dela.toJson(server).toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    /** Очередь — синхронно: это правки владельца, которых больше нигде нет. */
    private suspend fun writeOutbox() {
        val o = JSONObject().put("v", 1).put("me", me)
            .put("ops", JSONArray().apply {
                for (q in outbox) put(JSONObject().put("op", q.op).put("at", q.at).put("tries", q.tries).put("error", q.error))
            })
            .put("notices", JSONArray().apply {
                for (n in notices) put(JSONObject().put("at", n.at).put("text", n.text).put("error", n.error))
            })
        val text = o.toString()
        withContext(Dispatchers.IO) { StoreFiles.writeAtomic(outboxFile, text) }
    }
}
