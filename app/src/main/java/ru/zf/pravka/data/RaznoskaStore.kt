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
import ru.zf.pravka.core.ParsedTask

// Разноска: каждый наговор, разобранный на дела, лежит на диске с той секунды,
// как Опус его разобрал, и до того, как Todoist их принял. Отправленные
// остаются журналом.
//
// Правило, ради которого этот стор и существует: разобранный наговор не
// теряется. Нет сети, нет токена, приложение убито посреди отправки, владелец
// ушёл от плашки - дела всё равно здесь, и у каждого своя отметка «уже
// создано», поэтому повтор не может ничего удвоить.
class RaznoskaStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "raznoska.json"
        // Отправленные наговоры держим журналом; старые уходят с хвоста.
        private const val KEEP_DONE = 40
    }

    data class Draft(
        val id: Long,
        val createdTs: Long,
        val transcript: String,
        val notes: String,
        val tasks: List<ParsedTask>,
        val error: String = "",
        val costUsd: Double = 0.0,
        val model: String = "",
    ) {
        val live: List<ParsedTask> get() = tasks.filter { !it.dropped }
        // Пустой набор — тоже «сделано»: наговор без дел (или тот, где все
        // дела убрали руками) уходит в журнал, а не висит вечно ждущим.
        val allSent: Boolean get() = live.all { it.sent }
        val anySent: Boolean get() = tasks.any { it.sent }
        val pending: Boolean get() = !allSent
        val pendingCount: Int get() = live.count { !it.sent }
    }

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private val _draftsFlow = MutableStateFlow<List<Draft>>(emptyList())
    val draftsFlow: StateFlow<List<Draft>> = _draftsFlow

    suspend fun load(): List<Draft> = mutex.withLock { ensureLoaded(); _draftsFlow.value }

    /** Свежий разбор: он сразу на диске, ещё до того, как владелец увидел плашку. */
    suspend fun add(
        transcript: String,
        notes: String,
        tasks: List<ParsedTask>,
        costUsd: Double,
        model: String,
    ): Draft = mutex.withLock {
        ensureLoaded()
        val now = System.currentTimeMillis()
        val draft = Draft(
            id = now,
            createdTs = now,
            transcript = transcript,
            notes = notes,
            tasks = tasks,
            costUsd = costUsd,
            model = model,
        )
        write(listOf(draft) + _draftsFlow.value)
        draft
    }

    suspend fun replaceTasks(draftId: Long, tasks: List<ParsedTask>) = mutex.withLock {
        ensureLoaded()
        write(_draftsFlow.value.map { if (it.id == draftId) it.copy(tasks = tasks) else it })
    }

    suspend fun setError(draftId: Long, error: String) = mutex.withLock {
        ensureLoaded()
        write(_draftsFlow.value.map { if (it.id == draftId) it.copy(error = error) else it })
    }

    /** Todoist создал дело - отметка, ради которой повтор безопасен. */
    suspend fun markSent(draftId: Long, taskId: Long, todoistId: String) = mutex.withLock {
        ensureLoaded()
        write(
            _draftsFlow.value.map { d ->
                if (d.id != draftId) d
                else d.copy(
                    tasks = d.tasks.map {
                        if (it.id == taskId) it.copy(sentId = todoistId.ifBlank { "ok" }) else it
                    }
                )
            }
        )
    }

    /** Отмена отправки: дело снова ждёт. */
    suspend fun clearSent(draftId: Long, taskId: Long) = mutex.withLock {
        ensureLoaded()
        write(
            _draftsFlow.value.map { d ->
                if (d.id != draftId) d
                else d.copy(tasks = d.tasks.map { if (it.id == taskId) it.copy(sentId = "") else it })
            }
        )
    }

    suspend fun delete(draftId: Long) = mutex.withLock {
        ensureLoaded()
        write(_draftsFlow.value.filterNot { it.id == draftId })
    }

    fun byId(draftId: Long): Draft? = _draftsFlow.value.firstOrNull { it.id == draftId }

    /** Неотправленные наговоры, свежие сверху - их и показывает вкладка. */
    fun pending(): List<Draft> = _draftsFlow.value.filter { it.pending }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val parsed = withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { text -> parse(JSONArray(text)) }
        }
        loaded = true
        if (parsed != null) _draftsFlow.value = parsed
    }

    // Неотправленные не выкидываются никогда; обрезается только журнал.
    private fun write(list: List<Draft>) {
        val pending = list.filter { it.pending }
        val done = list.filter { !it.pending }.sortedByDescending { it.createdTs }.take(KEEP_DONE)
        val kept = (pending + done).sortedByDescending { it.createdTs }
        _draftsFlow.value = kept
        val json = serialize(kept).toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun parse(array: JSONArray): List<Draft> {
        val out = mutableListOf<Draft>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val tasks = mutableListOf<ParsedTask>()
            o.optJSONArray("tasks")?.let { ta ->
                for (j in 0 until ta.length()) {
                    val t = ta.optJSONObject(j) ?: continue
                    val labels = mutableListOf<String>()
                    t.optJSONArray("labels")?.let { la ->
                        for (k in 0 until la.length()) {
                            la.optString(k).takeIf { it.isNotBlank() }?.let { labels.add(it) }
                        }
                    }
                    tasks.add(
                        ParsedTask(
                            id = t.optLong("id", (j + 1).toLong()),
                            content = t.optString("content"),
                            description = t.optString("description"),
                            projectId = t.optString("projectId"),
                            projectName = t.optString("projectName"),
                            labels = labels,
                            priority = t.optInt("priority", ParsedTask.P4),
                            due = t.optString("due"),
                            repeat = t.optString("repeat"),
                            duplicateOf = t.optString("dup"),
                            sentId = t.optString("sentId"),
                            dropped = t.optBoolean("dropped", false),
                        )
                    )
                }
            }
            out.add(
                Draft(
                    id = o.optLong("id"),
                    createdTs = o.optLong("created"),
                    transcript = o.optString("transcript"),
                    notes = o.optString("notes"),
                    tasks = tasks,
                    error = o.optString("error"),
                    costUsd = o.optDouble("cost", 0.0),
                    model = o.optString("model"),
                )
            )
        }
        return out.sortedByDescending { it.createdTs }
    }

    private fun serialize(drafts: List<Draft>): JSONArray = JSONArray().apply {
        for (d in drafts) put(
            JSONObject().apply {
                put("id", d.id)
                put("created", d.createdTs)
                put("transcript", d.transcript)
                put("notes", d.notes)
                put("error", d.error)
                put("cost", d.costUsd)
                put("model", d.model)
                put(
                    "tasks",
                    JSONArray().apply {
                        for (t in d.tasks) put(
                            JSONObject().apply {
                                put("id", t.id)
                                put("content", t.content)
                                put("description", t.description)
                                put("projectId", t.projectId)
                                put("projectName", t.projectName)
                                put("labels", JSONArray().apply { t.labels.forEach { put(it) } })
                                put("priority", t.priority)
                                put("due", t.due)
                                put("repeat", t.repeat)
                                put("dup", t.duplicateOf)
                                put("sentId", t.sentId)
                                put("dropped", t.dropped)
                            }
                        )
                    }
                )
            }
        )
    }
}
