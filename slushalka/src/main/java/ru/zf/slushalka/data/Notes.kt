package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import org.json.JSONArray
import org.json.JSONObject

/**
 * Пометка на полях: выделенный кусок текста и то, что о нём подумалось.
 *
 * [id] - время создания в миллисекундах: по нему пометка узнаётся на другом
 * устройстве, два разных куска в одну миллисекунду не выделишь. Удалённая
 * остаётся надгробием ([deleted]) - иначе при слиянии её вернула бы копия,
 * которая ещё лежит в папке синхронизации.
 */
data class Note(
    val id: Long,
    val start: Int,
    val end: Int,
    val quote: String,
    val text: String,
    val updatedAt: Long,
    val deleted: Boolean = false,
)

/**
 * Пометки по книгам. Хранятся у себя и уезжают в `_Слушалка/пометки-<имя>.json`
 * рядом с позициями и вопросами; сливаются по [Note.id], свежая правка побеждает.
 */
class Notes(context: Context) {

    private val file = File(context.filesDir, "notes.json")
    private val byBook = HashMap<String, MutableList<Note>>()

    /** Номер изменения: и для синхронизации, и чтобы читалка перерисовала подчёркивания. */
    private val _revision = MutableStateFlow(0)
    val revision: StateFlow<Int> = _revision

    init {
        Store.readOrQuarantine(file) { text ->
            val root = JSONObject(text)
            for (id in root.keys()) byBook[id] = fromJson(root.getJSONArray(id)).toMutableList()
        }
    }

    /** Живые пометки книги по порядку текста. */
    @Synchronized
    fun of(bookId: String): List<Note> =
        byBook[bookId].orEmpty().filter { !it.deleted }.sortedBy { it.start }

    @Synchronized
    fun count(bookId: String): Int = byBook[bookId].orEmpty().count { !it.deleted }

    /** Новая или поправленная: узнаётся по [Note.id]. */
    @Synchronized
    fun put(bookId: String, note: Note) {
        val list = byBook.getOrPut(bookId) { mutableListOf() }
        list.removeAll { it.id == note.id }
        list.add(note.copy(updatedAt = System.currentTimeMillis()))
        changed()
    }

    @Synchronized
    fun remove(bookId: String, id: Long) {
        val list = byBook[bookId] ?: return
        val i = list.indexOfFirst { it.id == id }
        if (i < 0) return
        list[i] = list[i].copy(deleted = true, text = "", quote = "", updatedAt = System.currentTimeMillis())
        changed()
    }

    /** Всё, с надгробиями, - для папки синхронизации. */
    @Synchronized
    fun all(): Map<String, List<Note>> = byBook.mapValues { it.value.toList() }

    /** Пометки с другого устройства той же дорожки; true - что-то поменялось. */
    @Synchronized
    fun merge(bookId: String, remote: List<Note>): Boolean {
        val list = byBook.getOrPut(bookId) { mutableListOf() }
        var any = false
        for (r in remote) {
            val i = list.indexOfFirst { it.id == r.id }
            when {
                i < 0 -> { list.add(r); any = true }
                r.updatedAt > list[i].updatedAt -> { list[i] = r; any = true }
            }
        }
        if (any) changed()
        return any
    }

    private fun changed() {
        _revision.value = _revision.value + 1
        val root = JSONObject()
        byBook.forEach { (id, list) -> root.put(id, toJson(list)) }
        val text = root.toString()
        Store.post { Store.writeAtomic(file, text) }
    }

    companion object {
        fun toJson(list: List<Note>): JSONArray = JSONArray().apply {
            list.forEach {
                put(
                    JSONObject().put("id", it.id).put("s", it.start).put("e", it.end)
                        .put("q", it.quote).put("t", it.text).put("u", it.updatedAt)
                        .put("del", it.deleted)
                )
            }
        }

        fun fromJson(arr: JSONArray): List<Note> = (0 until arr.length()).map {
            val o = arr.getJSONObject(it)
            Note(
                id = o.optLong("id"),
                start = o.optInt("s"),
                end = o.optInt("e"),
                quote = o.optString("q"),
                text = o.optString("t"),
                updatedAt = o.optLong("u"),
                deleted = o.optBoolean("del"),
            )
        }
    }
}
