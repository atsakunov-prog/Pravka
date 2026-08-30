package ru.zf.slushalka.data

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
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
 * не надо.
 */
class Markup(private val context: Context) {

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

    fun read(treeUri: Uri, book: Book): Map? {
        val docId = Saf.findChild(context, treeUri, book.folderDocId, FILE) ?: return null
        val text = Saf.readText(context, treeUri, docId) ?: return null
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

    fun write(
        treeUri: Uri,
        book: Book,
        text: BookText,
        by: String,
        anchors: List<Anchor>,
    ): Boolean {
        if (anchors.isEmpty()) return false
        val docId = Saf.ensureChild(
            context, treeUri, book.folderDocId, FILE, "application/json",
        ) ?: return false
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
        return Saf.writeText(context, treeUri, docId, body)
    }

    fun delete(treeUri: Uri, book: Book): Boolean {
        val docId = Saf.findChild(context, treeUri, book.folderDocId, FILE) ?: return true
        return runCatching {
            DocumentsContract.deleteDocument(
                context.contentResolver,
                ru.zf.slushalka.library.documentUri(treeUri, docId),
            )
        }.getOrDefault(false)
    }

    private companion object {
        const val FILE = "слушалка-разметка.json"
    }
}
