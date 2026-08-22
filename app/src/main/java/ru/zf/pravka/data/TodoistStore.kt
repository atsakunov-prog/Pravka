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

// Кэш дел Todoist и связка «дело Todoist → запись в ленте».
//
// The list lives on disk so the tab opens instantly and works with no network
// (the phone is often in the metro when he picks the next task). The API is the
// source of truth; this is a mirror plus one piece of our own state - which
// Todoist task the running timesheet entry came from, so its time can be
// written back as a comment when the entry ends.
class TodoistStore(private val context: Context) {

    companion object {
        const val FILE_NAME = "todoist.json"
        // A link older than this never gets a comment: the owner clearly moved
        // on and we are not going to invent a duration for him.
        private const val LINK_MAX_MS = 36 * 3_600_000L
    }

    data class Task(
        val id: String,
        val content: String,
        val projectId: String,
        val due: String,        // "yyyy-MM-dd" or "" - date only, time is not our business
        val priority: Int,      // 1..4, 4 = p1 in the UI
        val order: Int,
        val url: String,
    )

    data class Project(val id: String, val name: String, val inbox: Boolean)

    /** Запись ленты, выросшая из дела Todoist - ждёт коммента о времени. */
    data class Link(val entryId: Long, val taskId: String, val title: String, val startedAt: Long)

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private var tasks = listOf<Task>()
    private var projects = listOf<Project>()
    private var links = mutableListOf<Link>()
    private var syncedAt = 0L

    private val _tasksFlow = MutableStateFlow<List<Task>>(emptyList())
    val tasksFlow: StateFlow<List<Task>> = _tasksFlow
    private val _projectsFlow = MutableStateFlow<List<Project>>(emptyList())
    val projectsFlow: StateFlow<List<Project>> = _projectsFlow
    private val _syncedAtFlow = MutableStateFlow(0L)
    val syncedAtFlow: StateFlow<Long> = _syncedAtFlow
    /** Что показывать под поиском: «обновлено 20:42» или текст ошибки. */
    private val _statusFlow = MutableStateFlow("")
    val statusFlow: StateFlow<String> = _statusFlow

    fun setStatus(text: String) {
        _statusFlow.value = text
    }

    /** Поднимает кэш с диска - вкладка зовёт это при открытии. */
    suspend fun load() = mutex.withLock { ensureLoaded() }

    suspend fun projects(): List<Project> = mutex.withLock { ensureLoaded(); projects }

    suspend fun replaceList(newTasks: List<Task>, newProjects: List<Project>, at: Long) =
        mutex.withLock {
            ensureLoaded()
            tasks = newTasks
            projects = newProjects
            syncedAt = at
            persist()
            publish()
        }

    /** Убирает дело из кэша сразу после закрытия, не дожидаясь обновления. */
    suspend fun forget(taskId: String) = mutex.withLock {
        ensureLoaded()
        if (tasks.none { it.id == taskId }) return@withLock
        tasks = tasks.filter { it.id != taskId }
        persist()
        publish()
    }

    suspend fun addLink(entryId: Long, taskId: String, title: String, at: Long) = mutex.withLock {
        ensureLoaded()
        links.removeAll { it.entryId == entryId }
        links.add(Link(entryId, taskId, title, at))
        persist()
    }

    /** Связки, по которым пора писать коммент; просроченные удаляются молча. */
    suspend fun dueLinks(now: Long): List<Link> = mutex.withLock {
        ensureLoaded()
        val stale = links.filter { now - it.startedAt > LINK_MAX_MS }
        if (stale.isNotEmpty()) {
            links.removeAll(stale)
            persist()
        }
        links.toList()
    }

    suspend fun dropLink(entryId: Long) = mutex.withLock {
        ensureLoaded()
        if (links.removeAll { it.entryId == entryId }) persist()
    }

    private fun publish() {
        _tasksFlow.value = tasks
        _projectsFlow.value = projects
        _syncedAtFlow.value = syncedAt
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val root = StoreFiles.readOrQuarantine(file) { JSONObject(it) }
            tasks = root?.optJSONArray("tasks")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Task(
                        id = id,
                        content = o.optString("content"),
                        projectId = o.optString("projectId"),
                        due = o.optString("due"),
                        priority = o.optInt("priority", 1),
                        order = o.optInt("order", 0),
                        url = o.optString("url"),
                    )
                }
            } ?: emptyList()
            projects = root?.optJSONArray("projects")?.let { array ->
                (0 until array.length()).mapNotNull { i ->
                    val o = array.optJSONObject(i) ?: return@mapNotNull null
                    val id = o.optString("id").takeIf { it.isNotBlank() } ?: return@mapNotNull null
                    Project(id, o.optString("name"), o.optBoolean("inbox", false))
                }
            } ?: emptyList()
            links = (
                root?.optJSONArray("links")?.let { array ->
                    (0 until array.length()).mapNotNull { i ->
                        val o = array.optJSONObject(i) ?: return@mapNotNull null
                        val taskId = o.optString("taskId").takeIf { it.isNotBlank() }
                            ?: return@mapNotNull null
                        Link(
                            entryId = o.optLong("entryId"),
                            taskId = taskId,
                            title = o.optString("title"),
                            startedAt = o.optLong("startedAt"),
                        )
                    }
                } ?: emptyList()
                ).toMutableList()
            syncedAt = root?.optLong("syncedAt") ?: 0L
        }
        loaded = true
        publish()
    }

    private fun persist() {
        val root = JSONObject().apply {
            put("syncedAt", syncedAt)
            put(
                "tasks",
                JSONArray().apply {
                    for (t in tasks) put(
                        JSONObject().apply {
                            put("id", t.id)
                            put("content", t.content)
                            put("projectId", t.projectId)
                            put("due", t.due)
                            put("priority", t.priority)
                            put("order", t.order)
                            put("url", t.url)
                        }
                    )
                }
            )
            put(
                "projects",
                JSONArray().apply {
                    for (p in projects) put(
                        JSONObject().apply {
                            put("id", p.id)
                            put("name", p.name)
                            put("inbox", p.inbox)
                        }
                    )
                }
            )
            put(
                "links",
                JSONArray().apply {
                    for (l in links) put(
                        JSONObject().apply {
                            put("entryId", l.entryId)
                            put("taskId", l.taskId)
                            put("title", l.title)
                            put("startedAt", l.startedAt)
                        }
                    )
                }
            )
        }
        val json = root.toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }
}
