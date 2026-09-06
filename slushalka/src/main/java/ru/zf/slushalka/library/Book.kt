package ru.zf.slushalka.library

import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/** Один звуковой файл книги. Порядок в списке = порядок слушания. */
data class BookFile(
    val docId: String,
    val name: String,
    /** Путь внутри книги: у «дисковых» раскладок это «CD1/03.mp3». */
    val relPath: String,
    val size: Long,
    /** 0 - длительность ещё не измерена. */
    val durationMs: Long = 0L,
) {
    fun toJson(): JSONObject = JSONObject()
        .put("d", docId).put("n", name).put("p", relPath)
        .put("s", size).put("ms", durationMs)

    companion object {
        fun fromJson(o: JSONObject) = BookFile(
            docId = o.getString("d"),
            name = o.getString("n"),
            relPath = o.optString("p", o.getString("n")),
            size = o.optLong("s"),
            durationMs = o.optLong("ms"),
        )
    }
}

data class Book(
    /** Путь папки внутри библиотеки: он же ключ позиции и на других устройствах. */
    val id: String,
    val folderDocId: String,
    val title: String,
    val author: String,
    val files: List<BookFile>,
    val coverDocId: String? = null,
    /** fb2/epub рядом с аудио - без него вопросы работать не будут. */
    val textDocId: String? = null,
    val textName: String? = null,
) {
    val totalMs: Long get() = files.sumOf { it.durationMs }
    val durationsReady: Boolean get() = files.isNotEmpty() && files.all { it.durationMs > 0 }

    /**
     * Есть ли что слушать. Книга, скачанная из каталога, - это один fb2 без
     * записи: её только читают, плеер и карта «звук ↔ текст» ей не нужны.
     * Положи рядом mp3 - и при следующем чтении папки она станет обычной.
     */
    val hasAudio: Boolean get() = files.isNotEmpty()

    /** Смещение начала файла [index] от начала книги. */
    fun offsetOf(index: Int): Long {
        var sum = 0L
        for (i in 0 until index.coerceIn(0, files.size)) sum += files[i].durationMs
        return sum
    }

    /** Обратное к [offsetOf]: абсолютная позиция -> файл и позиция внутри него. */
    fun locate(absMs: Long): Pair<Int, Long> {
        var left = absMs.coerceAtLeast(0L)
        for ((i, f) in files.withIndex()) {
            if (left < f.durationMs || i == files.lastIndex) return i to left.coerceAtMost(f.durationMs)
            left -= f.durationMs
        }
        return 0 to 0L
    }

    fun toJson(): JSONObject = JSONObject()
        .put("id", id)
        .put("folder", folderDocId)
        .put("title", title)
        .put("author", author)
        .put("cover", coverDocId ?: JSONObject.NULL)
        .put("text", textDocId ?: JSONObject.NULL)
        .put("textName", textName ?: JSONObject.NULL)
        .put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })

    companion object {
        fun fromJson(o: JSONObject): Book {
            val arr = o.optJSONArray("files") ?: JSONArray()
            return Book(
                id = o.getString("id"),
                folderDocId = o.getString("folder"),
                title = o.optString("title"),
                author = o.optString("author"),
                files = (0 until arr.length()).map { BookFile.fromJson(arr.getJSONObject(it)) },
                coverDocId = o.optString("cover").takeIf { it.isNotBlank() && it != "null" },
                textDocId = o.optString("text").takeIf { it.isNotBlank() && it != "null" },
                textName = o.optString("textName").takeIf { it.isNotBlank() && it != "null" },
            )
        }
    }
}

/** Ссылка на документ SAF, собранная из дерева библиотеки. */
fun documentUri(treeUri: Uri, docId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
