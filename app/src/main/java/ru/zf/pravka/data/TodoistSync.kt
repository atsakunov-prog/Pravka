package ru.zf.pravka.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

// Прямой доступ к Todoist личным токеном: ни OAuth, ни посредников - один
// заголовок Bearer, как у intervals.icu.
//
// Todoist сейчас живёт на двух API одновременно: новый /api/v1 (страницы с
// курсором) и прежний /rest/v2 (простые массивы). Какой из них ответит на
// этом аккаунте - выясняем один раз на старте и запоминаем, поэтому смена
// сроков поддержки на стороне Todoist нас не сломает.
class TodoistSync(
    private val settings: Settings,
    private val store: TodoistStore,
    private val zasechkaStore: ZasechkaStore,
    private val client: OkHttpClient,
    private val eventLog: EventLog,
) {

    companion object {
        private const val V1 = "https://api.todoist.com/api/v1"
        private const val V2 = "https://api.todoist.com/rest/v2"
        private const val FRESH_MS = 10 * 60_000L
        private val JSON = "application/json; charset=utf-8".toMediaType()
    }

    private val running = AtomicBoolean(false)
    @Volatile private var base: String? = null

    /** Обновляет список дел, если кэш старше десяти минут (или force). */
    suspend fun refresh(force: Boolean): Boolean {
        val token = settings.todoistToken().trim()
        if (token.isBlank()) {
            store.setStatus("нет токена")
            return false
        }
        store.load()
        if (!force && System.currentTimeMillis() - store.syncedAtFlow.value < FRESH_MS) return false
        if (!running.compareAndSet(false, true)) return false
        try {
            return withContext(Dispatchers.IO) { pull(token) }
        } catch (e: kotlin.coroutines.cancellation.CancellationException) {
            throw e
        } catch (e: Throwable) {
            store.setStatus("сеть: ${e.javaClass.simpleName}")
            runCatching { eventLog.add("todoist: ${e.javaClass.simpleName}: ${e.message}") }
            return false
        } finally {
            running.set(false)
        }
    }

    private suspend fun pull(token: String): Boolean {
        val projectRows = fetchAll(token, "projects") ?: return false
        val taskRows = fetchAll(token, "tasks") ?: return false
        val projects = projectRows.mapNotNull { o ->
            val id = idOf(o) ?: return@mapNotNull null
            val name = o.optString("name")
            TodoistStore.Project(
                id = id,
                name = name,
                inbox = o.optBoolean("is_inbox_project", false) ||
                    o.optBoolean("inbox_project", false) ||
                    name.equals("Inbox", ignoreCase = true) ||
                    name.equals("Входящие", ignoreCase = true),
            )
        }
        val tasks = taskRows.mapNotNull { o ->
            val id = idOf(o) ?: return@mapNotNull null
            // Завершённые в списке активных не приходят, но v1 отдаёт флаг -
            // если он есть, верим ему.
            if (o.optBoolean("is_completed", false) || o.optBoolean("checked", false)) {
                return@mapNotNull null
            }
            val content = o.optString("content").trim()
            if (content.isEmpty()) return@mapNotNull null
            TodoistStore.Task(
                id = id,
                content = content,
                projectId = o.optString("project_id"),
                // Дата без времени: «сегодня» решается по дню, минуты дел нам
                // ни к чему - время считает лента.
                due = o.optJSONObject("due")?.optString("date")?.take(10).orEmpty(),
                priority = o.optInt("priority", 1),
                order = if (o.has("child_order")) o.optInt("child_order", 0) else o.optInt("order", 0),
                url = o.optString("url"),
            )
        }
        store.replaceList(tasks, projects, System.currentTimeMillis())
        store.setStatus(
            "обновлено " + SimpleDateFormat("HH:mm", Locale.US).format(Date()) +
                " · дел ${tasks.size}"
        )
        return true
    }

    /**
     * Дела или проекты целиком. Сначала пробуем /api/v1 (страницами по
     * курсору), при 404/410 - /rest/v2 (один массив). Ответ на 401 = токен
     * не тот, и об этом надо сказать словами, а не пустым списком.
     */
    private fun fetchAll(token: String, path: String): List<JSONObject>? {
        val out = mutableListOf<JSONObject>()
        var host = base
        if (host == null) {
            val probe = get("$V1/$path?limit=200", token)
            if (probe.code == 401) {
                store.setStatus("токен не принят (401)")
                return null
            }
            host = if (probe.body != null) V1 else V2
            base = host
        }
        var cursor: String? = null
        var pages = 0
        while (pages < 12) {
            pages++
            val url = buildString {
                append(host).append('/').append(path)
                if (host == V1) {
                    append("?limit=200")
                    if (cursor != null) append("&cursor=").append(cursor)
                }
            }
            val res = get(url, token)
            if (res.code == 401) {
                store.setStatus("токен не принят (401)")
                return null
            }
            val body = res.body ?: run {
                // Новый API мог ответить 404 - падаем на прежний и пробуем ещё раз.
                if (host == V1 && res.code == 404) {
                    base = V2
                    return fetchAll(token, path)
                }
                store.setStatus("Todoist ответил HTTP ${res.code}")
                return null
            }
            val trimmed = body.trimStart()
            if (trimmed.startsWith("[")) {
                val array = runCatching { JSONArray(body) }.getOrNull() ?: return null
                for (i in 0 until array.length()) array.optJSONObject(i)?.let { out.add(it) }
                return out
            }
            val root = runCatching { JSONObject(body) }.getOrNull() ?: return null
            val array = root.optJSONArray("results") ?: return out
            for (i in 0 until array.length()) array.optJSONObject(i)?.let { out.add(it) }
            cursor = root.optString("next_cursor").takeIf { it.isNotBlank() && it != "null" }
            if (cursor == null) return out
        }
        return out
    }

    /**
     * Время дела уезжает в коммент к задаче - Todoist остаётся местом, где
     * видно, сколько на что ушло. Пишется, когда запись ленты закрылась:
     * связку ставит вкладка, а закрытие проверяет тик службы.
     */
    suspend fun flushLinks() {
        val token = settings.todoistToken().trim()
        if (token.isBlank()) return
        val now = System.currentTimeMillis()
        val links = store.dueLinks(now)
        if (links.isEmpty()) return
        val entries = zasechkaStore.all()
        for (link in links) {
            val mine = entries.filter {
                it.title.trim().equals(link.title.trim(), ignoreCase = true) &&
                    it.start >= link.startedAt - 60_000
            }
            if (mine.isEmpty()) {
                store.dropLink(link.entryId)
                continue
            }
            // Пока дело идёт - ждём: коммент пишется один раз, по итогу.
            if (mine.any { it.open }) continue
            val from = mine.minOf { it.start }
            val to = mine.maxOf { it.end }
            val minutes = mine.sumOf { it.durationMs(now) } / 60_000
            if (minutes <= 0) {
                store.dropLink(link.entryId)
                continue
            }
            val dayFormat = SimpleDateFormat("d MMMM", Locale("ru"))
            val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
            val duration =
                if (minutes >= 60) "${minutes / 60} ч ${minutes % 60} м" else "$minutes м"
            val text = "⏱ ${dayFormat.format(Date(from))}, " +
                "${timeFormat.format(Date(from))}–${timeFormat.format(Date(to))} · $duration" +
                if (mine.size > 1) " (за ${mine.size} подхода)" else ""
            val posted = withContext(Dispatchers.IO) { comment(token, link.taskId, text) }
            if (posted) {
                eventLog.add("todoist: «${link.title}» → коммент $duration")
                store.dropLink(link.entryId)
            }
        }
    }

    private fun comment(token: String, taskId: String, text: String): Boolean {
        val payload = JSONObject().apply {
            put("task_id", taskId)
            put("content", text)
        }.toString()
        for (host in listOfNotNull(base, V1, V2).distinct()) {
            val request = Request.Builder()
                .url("$host/comments")
                .header("Authorization", "Bearer $token")
                .post(payload.toRequestBody(JSON))
                .build()
            val ok = runCatching {
                client.newCall(request).execute().use { it.isSuccessful }
            }.getOrDefault(false)
            if (ok) {
                base = host
                return true
            }
        }
        eventLog.add("todoist: коммент не записался (задача $taskId)")
        return false
    }

    /** id приходит строкой (v1) или числом (v2) - держим строкой. */
    private fun idOf(o: JSONObject): String? =
        o.opt("id")?.toString()?.trim()?.takeIf { it.isNotEmpty() && it != "null" }

    private class Res(val code: Int, val body: String?)

    private fun get(url: String, token: String): Res {
        val request = Request.Builder()
            .url(url)
            .header("Authorization", "Bearer $token")
            .build()
        return runCatching {
            client.newCall(request).execute().use { response ->
                Res(response.code, if (response.isSuccessful) response.body?.string() else null)
            }
        }.getOrElse { Res(0, null) }
    }
}
