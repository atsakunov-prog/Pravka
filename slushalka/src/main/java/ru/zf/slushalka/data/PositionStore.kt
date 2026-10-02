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
    /** Где остановились глазами. -1 - читалку в этой книге ещё не открывали. */
    val readChar: Int = -1,
    /**
     * Длина текста книги в знаках, как её видела читалка. Нужна полке: без
     * неё у книги без записи не посчитать, сколько прочитано, - текст на
     * полке не разбирают, это долго.
     */
    val textChars: Int = 0,
    /**
     * Когда место в записи поставлено слушанием (или рукой в плеере). Не
     * трогается, когда запись лишь подтянули к странице: так видно, что было
     * позже - слушали или читали, - и книга открывается там, где остановился
     * последним, а не там, куда её довела арифметика пересчёта.
     */
    val listenAt: Long = 0,
    /** Когда место чтения поставлено чтением: листанием или озвучкой. */
    val readAt: Long = 0,
) {
    /** Слушали позже, чем читали: место в записи главнее места в тексте. */
    val listenedLast: Boolean get() = listenAt > readAt
    /** Доля прочитанного глазами; 0 - не открывали или длина неизвестна. */
    val readShare: Float
        get() = if (textChars > 0 && readChar > 0) (readChar.toFloat() / textChars).coerceIn(0f, 1f) else 0f

    fun toJson(): JSONObject = JSONObject()
        .put("file", fileIndex)
        .put("pos", posMs)
        .put("abs", absMs)
        .put("at", updatedAt)
        .put("speed", speed.toDouble())
        .put("anchors", Alignment.listToJson(anchors))
        .put("listened", listenedMs)
        .put("finished", finished)
        .put("read", readChar)
        .put("chars", textChars)
        .put("listenAt", listenAt)
        .put("readAt", readAt)
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
                readChar = o.optInt("read", -1),
                textChars = o.optInt("chars"),
                // Позиции до 03.10 знали одно время на оба места: оно и
                // становится временем каждого.
                listenAt = o.optLong("listenAt", o.optLong("at")),
                readAt = o.optLong("readAt", if (o.optInt("read", -1) >= 0) o.optLong("at") else 0L),
                history = (0 until h.length()).map {
                    val m = h.getJSONObject(it)
                    Mark(m.optLong("abs"), m.optLong("at"))
                },
            )
        }
    }
}

/** Что изменило слияние: место в записи, место чтения или ничего. */
data class Merged(val listen: Boolean, val read: Boolean, val before: BookState) {
    val any: Boolean get() = listen || read
}

/**
 * Слить своё место с другого устройства - каждое место по своему времени:
 * слушал позже на планшете - место в записи оттуда, читал позже здесь -
 * место чтения здешнее. Раньше было одно время на оба места, и чтение на
 * одном устройстве затирало слушание на другом. Отметки «я тут» и история -
 * хозяйство этого телефона, они не едут. Старый файл без места чтения (-1)
 * своё место не стирает. «Дочитано» едет вместе с более свежим временем.
 */
fun mergeStates(local: BookState, remote: BookState): Pair<BookState, Merged> {
    val listen = remote.listenAt > local.listenAt
    val read = remote.readChar >= 0 && remote.readAt > local.readAt
    val finishedNews = remote.updatedAt > local.updatedAt && remote.finished != local.finished
    if (!listen && !read && !finishedNews) return local to Merged(false, false, local)
    var s = local
    if (listen) {
        s = s.copy(fileIndex = remote.fileIndex, posMs = remote.posMs, absMs = remote.absMs, listenAt = remote.listenAt)
    }
    if (read) {
        s = s.copy(
            readChar = remote.readChar,
            readAt = remote.readAt,
            textChars = if (remote.textChars > 0) remote.textChars else s.textChars,
        )
    }
    if (remote.updatedAt > local.updatedAt) s = s.copy(finished = remote.finished)
    s = s.copy(updatedAt = maxOf(local.updatedAt, remote.updatedAt, s.listenAt, s.readAt))
    return s to Merged(listen, read, local)
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

    /**
     * Записать место в записи. [listened] - его поставило слушание или рука в
     * плеере: у места новое время, книга - последняя тронутая. Иначе (запись
     * подтянули к странице, сменили скорость) время места прежнее: «тронуто» -
     * это когда слушали или читали, а не когда приложение что-то пересчитало.
     * Отметка в истории ставится не чаще раза в две минуты.
     */
    @Synchronized
    fun save(state: BookState, markHistory: Boolean = false, listened: Boolean = false) {
        val now = System.currentTimeMillis()
        val prev = states[state.bookId]
        var history = state.history
        if (markHistory && listened) {
            val last = history.lastOrNull()
            if (last == null || now - last.at > 2 * 60_000) {
                history = (history + Mark(state.absMs, now)).takeLast(40)
            }
        }
        // Пустая позиция поверх непустой - это не «начал заново», а баг:
        // так же, как лента Правки не умеет стираться целиком.
        if (prev != null && prev.absMs > 60_000 && state.absMs == 0L && !state.finished) return
        val listenAt = if (listened) now else prev?.listenAt ?: state.listenAt
        states[state.bookId] = state.copy(
            listenAt = listenAt,
            readAt = prev?.readAt ?: state.readAt,
            updatedAt = maxOf(listenAt, prev?.readAt ?: state.readAt, if (listened) now else prev?.updatedAt ?: 0L),
            history = history,
        )
        if (listened) lastBookId = state.bookId
        persist()
    }

    @Synchronized
    fun merge(bookId: String, remote: BookState): Merged {
        val local = states[bookId] ?: BookState(bookId)
        val (merged, m) = mergeStates(local, remote)
        if (!m.any && merged == local) return m
        states[bookId] = merged
        persist()
        return m
    }

    /** Вернуть место, как было до слияния, - и сделать его самым свежим, чтобы оно и уехало. */
    @Synchronized
    fun restore(before: BookState) {
        val now = System.currentTimeMillis()
        val cur = states[before.bookId] ?: BookState(before.bookId)
        states[before.bookId] = cur.copy(
            fileIndex = before.fileIndex,
            posMs = before.posMs,
            absMs = before.absMs,
            readChar = before.readChar,
            finished = before.finished,
            listenAt = now,
            readAt = if (before.readChar >= 0) now else cur.readAt,
            updatedAt = now,
        )
        persist()
    }

    /** Место в читалке: поставлено чтением - листанием или озвучкой. */
    @Synchronized
    fun setReadChar(bookId: String, offset: Int, textChars: Int = 0) {
        val s = states[bookId] ?: BookState(bookId)
        if (s.readChar == offset && (textChars <= 0 || s.textChars == textChars)) return
        val now = System.currentTimeMillis()
        states[bookId] = s.copy(
            readChar = offset,
            textChars = if (textChars > 0) textChars else s.textChars,
            readAt = if (s.readChar != offset) now else s.readAt,
            updatedAt = if (s.readChar != offset) now else s.updatedAt,
        )
        lastBookId = bookId
        persist()
    }

    /** Книгу открыли: она - последняя, даже если ещё не слушали и не листали. */
    @Synchronized
    fun touchLast(bookId: String) {
        if (lastBookId == bookId) return
        lastBookId = bookId
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
