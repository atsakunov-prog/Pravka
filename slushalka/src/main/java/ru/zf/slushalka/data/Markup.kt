package ru.zf.slushalka.data

import org.json.JSONObject
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.Alignment
import ru.zf.slushalka.text.Anchor
import ru.zf.slushalka.text.BookText

/**
 * Разметка книги — карта «секунда записи ↔ знак текста», лежащая **файлом
 * рядом с самой книгой**, а не в памяти приложения.
 *
 * Считается один раз: приложение само проходит по записи пробами, распознаёт
 * их на телефоне и находит в тексте. Дальше переключение между звуком и
 * текстом — обычный расчёт по карте, без распознавания и без единого запроса
 * куда бы то ни было.
 *
 * Файл живёт в папке книги, поэтому переезжает вместе с ней: скопировал книгу
 * на планшет или второму слушателю — разметка уже там, второй раз её считать
 * не надо. У книги, чей звук на сервере библиотеки, — ещё и в её папке там
 * ([BookDir]).
 */
class Markup(private val dir: BookDir) {

    data class Map(
        val textName: String,
        val textLength: Int,
        val totalMs: Long,
        val fileCount: Int,
        val madeAt: Long,
        val by: String,
        val anchors: List<Anchor>,
    ) {
        /**
         * Разметка годится только для той же пары «эта запись + этот текст».
         * Другое издание книги или другая начитка сдвинут все места разом, и
         * старая карта уводила бы мимо с полной уверенностью.
         */
        fun matches(book: Book, text: BookText): Boolean =
            textLength == text.length &&
                fileCount == book.files.size &&
                kotlin.math.abs(totalMs - book.totalMs) < 2000
    }

    suspend fun read(book: Book): Map? {
        val text = dir.read(book, FILE) ?: return null
        return runCatching {
            val o = JSONObject(text)
            Map(
                textName = o.optString("text"),
                textLength = o.optInt("chars"),
                totalMs = o.optLong("audioMs"),
                fileCount = o.optInt("files"),
                madeAt = o.optLong("at"),
                by = o.optString("by"),
                anchors = Alignment.listFromJson(o.optJSONArray("points")),
            )
        }.getOrNull()
    }

    suspend fun write(
        book: Book,
        text: BookText,
        by: String,
        anchors: List<Anchor>,
    ): Boolean {
        if (anchors.isEmpty()) return false
        val body = JSONObject().apply {
            put("книга", book.title)
            put("text", book.textName.orEmpty())
            put("chars", text.length)
            put("audioMs", book.totalMs)
            put("files", book.files.size)
            put("at", System.currentTimeMillis())
            put("by", by)
            put("points", Alignment.listToJson(anchors.sortedBy { it.audioMs }))
        }.toString()
        return dir.write(book, FILE, body)
    }

    suspend fun delete(book: Book): Boolean = dir.delete(book, FILE)

    companion object {
        /** Карта рядом с книгой - на телефоне и в её папке на сервере. */
        const val FILE = "слушалка-разметка.json"
    }
}
