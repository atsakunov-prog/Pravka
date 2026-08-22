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

// Маршруты Разноски: куда владелец САМ переложил дело, когда модель угадала
// не тот проект (или не поставила метки). Тот же цикл, что в «Обучении» у
// Правки, только учится не формулировкам, а раскладке.
//
// Каждая поправка едет в следующий разбор примером: «вот такое дело он кладёт
// вот сюда». Правил не пишем и модель не дообучаем - работает то же, что
// работает у Правки: его собственные примеры в промпте.
class RaznoskaRoutes(private val context: Context) {

    companion object {
        const val FILE_NAME = "raznoska-routes.json"
        // Сколько поправок хранить и сколько из них уезжает в промпт.
        private const val KEEP = 60
        private const val IN_PROMPT = 20
        private const val PROMPT_BUDGET = 1500
    }

    data class Route(
        val id: Long,
        val text: String,
        val project: String,
        val labels: List<String>,
        val priority: Int,
        val createdTs: Long,
    ) {
        /** «#Мармакс @жду P1» — правая часть маршрута. */
        fun destination(): String {
            val parts = mutableListOf<String>()
            if (project.isNotBlank()) parts.add("#$project")
            if (labels.isNotEmpty()) parts.add(labels.joinToString(" ") { "@$it" })
            if (priority in 1..3) parts.add("P" + (5 - priority))
            return parts.joinToString(" ")
        }
    }

    private val mutex = Mutex()
    private val file: File get() = File(context.filesDir, FILE_NAME)
    private var loaded = false

    private val _routesFlow = MutableStateFlow<List<Route>>(emptyList())
    val routesFlow: StateFlow<List<Route>> = _routesFlow

    suspend fun load(): List<Route> = mutex.withLock { ensureLoaded(); _routesFlow.value }

    /**
     * Запоминает поправку. Та же формулировка, поправленная ещё раз,
     * заменяет прежнюю запись: маршрут один, свежий главнее.
     */
    suspend fun remember(
        text: String,
        project: String,
        labels: List<String>,
        priority: Int,
    ) = mutex.withLock {
        ensureLoaded()
        val trimmed = text.trim().take(160)
        if (trimmed.isEmpty()) return@withLock
        if (project.isBlank() && labels.isEmpty() && priority == 1) return@withLock
        val rest = _routesFlow.value.filterNot { it.text.equals(trimmed, ignoreCase = true) }
        val route = Route(
            id = System.currentTimeMillis(),
            text = trimmed,
            project = project.trim(),
            labels = labels,
            priority = priority,
            createdTs = System.currentTimeMillis(),
        )
        write(listOf(route) + rest)
    }

    suspend fun forget(id: Long) = mutex.withLock {
        ensureLoaded()
        write(_routesFlow.value.filterNot { it.id == id })
    }

    suspend fun clear() = mutex.withLock {
        ensureLoaded()
        write(emptyList())
    }

    /**
     * Блок для промпта: его собственные раскладки, свежие первыми. Пустая
     * строка, пока поправок нет - разбор от этого не меняется.
     */
    fun promptBlock(): String {
        val routes = _routesFlow.value.take(IN_PROMPT)
        if (routes.isEmpty()) return ""
        val sb = StringBuilder(
            "ПОПРАВКИ ВЛАДЕЛЬЦА (он сам перекладывал похожие дела — держись этого,\n" +
                "он лучше знает свою систему):\n"
        )
        var used = 0
        for (route in routes) {
            val destination = route.destination()
            if (destination.isBlank()) continue
            val line = "— «" + route.text + "» → " + destination + "\n"
            if (used + line.length > PROMPT_BUDGET) break
            sb.append(line)
            used += line.length
        }
        return sb.toString().trim()
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        val parsed = withContext(Dispatchers.IO) {
            StoreFiles.readOrQuarantine(file) { text -> parse(JSONArray(text)) }
        }
        loaded = true
        if (parsed != null) _routesFlow.value = parsed
    }

    private fun write(list: List<Route>) {
        val kept = list.sortedByDescending { it.createdTs }.take(KEEP)
        _routesFlow.value = kept
        val json = serialize(kept).toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun parse(array: JSONArray): List<Route> {
        val out = mutableListOf<Route>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val text = o.optString("text").trim()
            if (text.isEmpty()) continue
            val labels = mutableListOf<String>()
            o.optJSONArray("labels")?.let { la ->
                for (j in 0 until la.length()) {
                    la.optString(j).takeIf { it.isNotBlank() }?.let { labels.add(it) }
                }
            }
            out.add(
                Route(
                    id = o.optLong("id", System.currentTimeMillis() + i),
                    text = text,
                    project = o.optString("project"),
                    labels = labels,
                    priority = o.optInt("priority", 1),
                    createdTs = o.optLong("created"),
                )
            )
        }
        return out.sortedByDescending { it.createdTs }
    }

    private fun serialize(routes: List<Route>): JSONArray = JSONArray().apply {
        for (r in routes) put(
            JSONObject().apply {
                put("id", r.id)
                put("text", r.text)
                put("project", r.project)
                put("labels", JSONArray().apply { r.labels.forEach { put(it) } })
                put("priority", r.priority)
                put("created", r.createdTs)
            }
        )
    }
}
