package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject

data class Bookmark(val absMs: Long, val at: Long, val note: String, val quote: String)

/** Закладки: место, время и кусок текста рядом - чтобы потом было понятно, что это было. */
class Bookmarks(context: Context) {

    private val file = File(context.filesDir, "bookmarks.json")
    private val byBook = HashMap<String, MutableList<Bookmark>>()

    init {
        Store.readOrQuarantine(file) { text ->
            val root = JSONObject(text)
            for (id in root.keys()) {
                val arr = root.getJSONArray(id)
                byBook[id] = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Bookmark(o.optLong("abs"), o.optLong("at"), o.optString("note"), o.optString("quote"))
                }.toMutableList()
            }
        }
    }

    @Synchronized
    fun of(bookId: String): List<Bookmark> = byBook[bookId]?.toList().orEmpty()

    @Synchronized
    fun add(bookId: String, mark: Bookmark) {
        byBook.getOrPut(bookId) { mutableListOf() }.add(mark)
        byBook[bookId]?.sortBy { it.absMs }
        persist()
    }

    @Synchronized
    fun remove(bookId: String, mark: Bookmark) {
        byBook[bookId]?.removeAll { it.absMs == mark.absMs && it.at == mark.at }
        persist()
    }

    /** Закладки книги - под ключ сервера, вместе с теми, что там уже есть. */
    @Synchronized
    fun rekey(old: String, new: String): Boolean {
        if (old == new) return false
        val from = byBook.remove(old) ?: return false
        byBook[new] = (byBook[new].orEmpty() + from).distinctBy { it.absMs to it.at }.sortedBy { it.absMs }.toMutableList()
        persist()
        return true
    }

    private fun persist() {
        val root = JSONObject()
        byBook.forEach { (id, list) ->
            root.put(id, JSONArray().apply {
                list.forEach {
                    put(
                        JSONObject().put("abs", it.absMs).put("at", it.at)
                            .put("note", it.note).put("quote", it.quote)
                    )
                }
            })
        }
        val text = root.toString()
        Store.post { Store.writeAtomic(file, text) }
    }
}

data class Ask(
    val at: Long,
    val absMs: Long,
    val question: String,
    val answer: String,
    val costUsd: Double = 0.0,
)

/**
 * История вопросов по книге - и как память, и как счёт расходов.
 *
 * Уезжает в папку `_Слушалка` вместе с позициями и сливается с тем, что
 * спросили с другого устройства ([merge]): [revision] растёт с каждой
 * записью, чтобы синхронизация не переписывала файл без изменений.
 */
class AskLog(context: Context) {

    private val file = File(context.filesDir, "asks.json")
    private val byBook = HashMap<String, MutableList<Ask>>()

    /** Номер изменения: сравнивается с тем, что уже уехало в папку библиотеки. */
    @Volatile
    var revision: Int = 0
        private set

    init {
        Store.readOrQuarantine(file) { text ->
            val root = JSONObject(text)
            for (id in root.keys()) {
                val arr = root.getJSONArray(id)
                byBook[id] = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Ask(
                        o.optLong("at"), o.optLong("abs"), o.optString("q"),
                        o.optString("a"), o.optDouble("usd"),
                    )
                }.toMutableList()
            }
        }
    }

    @Synchronized
    fun of(bookId: String): List<Ask> = byBook[bookId]?.toList().orEmpty()

    @Synchronized
    fun add(bookId: String, ask: Ask) {
        val list = byBook.getOrPut(bookId) { mutableListOf() }
        list.add(ask)
        // Полусотни последних вопросов хватает; дальше файл растёт без пользы.
        while (list.size > KEEP) list.removeAt(0)
        revision++
        persist()
    }

    @Synchronized
    fun all(): Map<String, List<Ask>> = byBook.mapValues { it.value.toList() }

    /**
     * Вопросы с другого устройства той же дорожки. Один и тот же вопрос
     * узнаётся по времени: два разных не задашь в одну миллисекунду. Возвращает
     * true, если что-то добавилось.
     */
    @Synchronized
    fun merge(bookId: String, remote: List<Ask>): Boolean {
        val list = byBook.getOrPut(bookId) { mutableListOf() }
        val merged = union(list, remote)
        if (merged.size == list.size) return false
        byBook[bookId] = merged.toMutableList()
        revision++
        persist()
        return true
    }

    /** Вопросы книги - под ключ сервера: две копии одной книги - один разговор. */
    @Synchronized
    fun rekey(old: String, new: String): Boolean {
        if (old == new) return false
        val from = byBook.remove(old) ?: return false
        byBook[new] = union(byBook[new].orEmpty(), from).toMutableList()
        revision++
        persist()
        return true
    }

    @Synchronized
    fun has(bookId: String): Boolean = byBook[bookId]?.isNotEmpty() == true

    @Synchronized
    fun totalUsd(): Double = byBook.values.sumOf { list -> list.sumOf { it.costUsd } }

    /** Сколько вопросов задано - из тех, что ещё хранятся (по полсотни на книгу). */
    @Synchronized
    fun count(): Int = byBook.values.sumOf { it.size }

    companion object {
        private const val KEEP = 50

        /**
         * Вопросы двух списков вместе. Один и тот же вопрос узнаётся по
         * времени: два разных не задашь в одну миллисекунду. По времени, не
         * больше полусотни последних.
         */
        fun union(a: List<Ask>, b: List<Ask>): List<Ask> {
            val known = a.mapTo(HashSet()) { it.at }
            val fresh = b.filter { it.at !in known && it.question.isNotBlank() }
            return (a + fresh).sortedBy { it.at }.takeLast(KEEP)
        }
    }

    private fun persist() {
        val root = JSONObject()
        byBook.forEach { (id, list) ->
            root.put(id, JSONArray().apply {
                list.forEach {
                    put(
                        JSONObject().put("at", it.at).put("abs", it.absMs)
                            .put("q", it.question).put("a", it.answer).put("usd", it.costUsd)
                    )
                }
            })
        }
        val text = root.toString()
        Store.post { Store.writeAtomic(file, text) }
    }
}
