package ru.zf.slushalka.data

import android.content.Context
import java.io.File
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.slushalka.text.Alignment
import ru.zf.slushalka.text.Anchor

/** Куда возвращаться: снимок позиции с временем, когда он был сделан. */
data class Mark(val absMs: Long, val at: Long)

data class BookState(
    val bookId: String,
    val fileIndex: Int = 0,
    /** Позиция внутри файла. */
    val posMs: Long = 0,
    /** Она же от начала книги: по ней считаются проценты и место в тексте. */
    val absMs: Long = 0,
    val updatedAt: Long = 0,
    /** 0 - берётся общая скорость из настроек. */
    val speed: Float = 0f,
    val anchors: List<Anchor> = emptyList(),
    /** Последние места: если позиция всё-таки собьётся, есть куда вернуться. */
    val history: List<Mark> = emptyList(),
    val listenedMs: Long = 0,
    val finished: Boolean = false,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("file", fileIndex)
        .put("pos", posMs)
        .put("abs", absMs)
        .put("at", updatedAt)
        .put("speed", speed.toDouble())
        .put("anchors", Alignment.listToJson(anchors))
        .put("listened", listenedMs)
        .put("finished", finished)
        .put("history", JSONArray().apply {
            history.forEach { put(JSONObject().put("abs", it.absMs).put("at", it.at)) }
        })

    companion object {
        fun fromJson(id: String, o: JSONObject): BookState {
            val h = o.optJSONArray("history") ?: JSONArray()
            return BookState(
                bookId = id,
                fileIndex = o.optInt("file"),
                posMs = o.optLong("pos"),
                absMs = o.optLong("abs"),
                updatedAt = o.optLong("at"),
                speed = o.optDouble("speed", 0.0).toFloat(),
                anchors = Alignment.listFromJson(o.optJSONArray("anchors")),
                listenedMs = o.optLong("listened"),
                finished = o.optBoolean("finished"),
                history = (0 until h.length()).map {
                    val m = h.getJSONObject(it)
                    Mark(m.optLong("abs"), m.optLong("at"))
                },
            )
        }
    }
}

/**
 * Позиции в книгах - самое незаменимое, что здесь есть.
 *
 * Пишется на диск не «когда-нибудь потом», а тиком раз в двадцать секунд плюс
 * на каждой паузе, перемотке, смене файла и уходе приложения. Приложение,
 * выгруженное системой из памяти, ничего не теряет: последняя запись старше
 * максимум двадцати секунд.
 */
class PositionStore(context: Context) {

    private val file = File(context.filesDir, "positions.json")
    private val states = HashMap<String, BookState>()
    private var lastBookId: String? = null

    init {
        Store.readOrQuarantine(file) { text ->
            val root = JSONObject(text)
            lastBookId = root.optString("last").takeIf { it.isNotBlank() }
            val books = root.optJSONObject("books") ?: JSONObject()
            for (id in books.keys()) {
                states[id] = BookState.fromJson(id, books.getJSONObject(id))
            }
        }
    }

    @Synchronized
    fun get(bookId: String): BookState = states[bookId] ?: BookState(bookId)

    @Synchronized
    fun all(): Map<String, BookState> = HashMap(states)

    fun lastBook(): String? = lastBookId

    /** Отметка в истории ставится не чаще раза в две минуты. */
    @Synchronized
    fun save(state: BookState, markHistory: Boolean = false) {
        val now = System.currentTimeMillis()
        val prev = states[state.bookId]
        var history = state.history
        if (markHistory) {
            val last = history.lastOrNull()
            if (last == null || now - last.at > 2 * 60_000) {
                history = (history + Mark(state.absMs, now)).takeLast(40)
            }
        }
        // Пустая позиция поверх непустой - это не «начал заново», а баг:
        // так же, как лента Правки не умеет стираться целиком.
        if (prev != null && prev.absMs > 60_000 && state.absMs == 0L && !state.finished) return
        states[state.bookId] = state.copy(updatedAt = now, history = history)
        lastBookId = state.bookId
        persist()
    }

    @Synchronized
    fun merge(bookId: String, remote: BookState) {
        val local = states[bookId]
        if (local != null && local.updatedAt >= remote.updatedAt) return
        // Из чужого устройства приезжает только позиция; отметки «я тут» и
        // история - хозяйство этого телефона.
        states[bookId] = (local ?: BookState(bookId)).copy(
            fileIndex = remote.fileIndex,
            posMs = remote.posMs,
            absMs = remote.absMs,
            updatedAt = remote.updatedAt,
            finished = remote.finished,
        )
        persist()
    }

    @Synchronized
    fun setAnchors(bookId: String, anchors: List<Anchor>) {
        val s = states[bookId] ?: BookState(bookId)
        states[bookId] = s.copy(anchors = anchors.sortedBy { it.audioMs })
        persist()
    }

    private fun persist() {
        val snapshot = JSONObject().apply {
            put("last", lastBookId ?: "")
            put("books", JSONObject().apply {
                states.forEach { (id, s) -> put(id, s.toJson()) }
            })
        }.toString()
        Store.post { Store.writeAtomic(file, snapshot) }
    }

    fun flush() = Store.flush()
}
