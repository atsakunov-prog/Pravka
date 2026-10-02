package ru.zf.slushalka.library

import android.net.Uri
import android.provider.DocumentsContract
import org.json.JSONArray
import org.json.JSONObject

/**
 * Один звуковой файл книги. Порядок в списке = порядок слушания.
 *
 * Файл лежит либо на телефоне - документ SAF ([docId]), либо на сервере
 * библиотеки - путь от корня облака ([remote]): у книги, взятой «только
 * текстом» или открытой с сервера, звук идёт потоком.
 */
data class BookFile(
    val docId: String,
    val name: String,
    /** Путь внутри книги: у «дисковых» раскладок это «CD1/03.mp3». */
    val relPath: String,
    val size: Long,
    /** 0 - длительность ещё не измерена. */
    val durationMs: Long = 0L,
    /** Путь на сервере (`Книги/<папка>/<файл>`); пусто - файл на телефоне. */
    val remote: String = "",
) {
    val isRemote: Boolean get() = remote.isNotBlank()

    /** Чем файл отличается от соседей: документом на телефоне или путём на сервере. */
    val key: String get() = docId.ifBlank { remote }

    fun toJson(): JSONObject = JSONObject()
        .put("d", docId).put("n", name).put("p", relPath)
        .put("s", size).put("ms", durationMs)
        .apply { if (isRemote) put("r", remote) }

    companion object {
        fun fromJson(o: JSONObject) = BookFile(
            docId = o.getString("d"),
            name = o.getString("n"),
            relPath = o.optString("p", o.getString("n")),
            size = o.optLong("s"),
            durationMs = o.optLong("ms"),
            remote = o.optString("r"),
        )
    }
}

data class Book(
    /** Путь папки внутри библиотеки: он же ключ позиции и на других устройствах. */
    val id: String,
    val folderDocId: String,
    /**
     * Дерево SAF, в котором книга найдена. Папок у библиотеки может быть
     * несколько, и все ссылки на файлы книги собираются от её дерева, а не от
     * главной папки. Пусто - книга из библиотеки, записанной до 08.09: она из
     * главной папки.
     */
    val tree: String = "",
    val title: String,
    val author: String,
    val files: List<BookFile>,
    val coverDocId: String? = null,
    /** fb2/epub рядом с аудио - без него вопросы работать не будут. */
    val textDocId: String? = null,
    val textName: String? = null,
    /**
     * Папка книги на сервере библиотеки (`Книги/<папка>`). Пусто - сервер о
     * книге не знает: она только на телефоне или облака нет вовсе.
     */
    val remoteDir: String = "",
    /** Текст на сервере, когда своего файла нет: книга открыта, не скачиваясь. */
    val textRemote: String? = null,
    /** Обложка на сервере - у книги, которой нет на телефоне. */
    val coverRemote: String? = null,
    /**
     * Серия: из головы файла текста ([ru.zf.slushalka.text.BookMeta]) или из
     * оглавления сервера. null - ещё не смотрели, пустая строка - смотрели,
     * серии нет: второй раз файл не читается.
     */
    val series: String? = null,
    /** Номер в серии, как в книге: «3», «1.5»; null - без номера. */
    val seriesNum: String? = null,
) {
    val totalMs: Long get() = files.sumOf { it.durationMs }
    val durationsReady: Boolean get() = files.isNotEmpty() && files.all { it.durationMs > 0 }

    /**
     * Есть ли что слушать. Книга, скачанная из каталога, - это один fb2 без
     * записи: её только читают, плеер и карта «звук ↔ текст» ей не нужны.
     * Положи рядом mp3 - и при следующем чтении папки она станет обычной.
     */
    val hasAudio: Boolean get() = files.isNotEmpty()

    val treeUri: Uri? get() = tree.takeIf { it.isNotBlank() }?.let(Uri::parse)

    /** Есть текст - свой или на сервере: читалка, вопросы, справочник. */
    val hasText: Boolean get() = textDocId != null || textRemote != null

    /** У книги есть папка на телефоне. Нет - книга открыта прямо с сервера. */
    val onPhone: Boolean get() = folderDocId.isNotBlank()

    /** Звук идёт с сервера потоком, хотя бы частью. */
    val streams: Boolean get() = files.any { it.isRemote }

    /** Имя папки книги - по нему телефон и сервер узнают одну книгу. */
    val folderName: String get() = id.substringAfterLast('/')

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
        .put("tree", tree)
        .put("title", title)
        .put("author", author)
        .put("cover", coverDocId ?: JSONObject.NULL)
        .put("text", textDocId ?: JSONObject.NULL)
        .put("textName", textName ?: JSONObject.NULL)
        .put("files", JSONArray().apply { files.forEach { put(it.toJson()) } })
        .apply {
            if (remoteDir.isNotBlank()) put("remoteDir", remoteDir)
            textRemote?.let { put("textRemote", it) }
            coverRemote?.let { put("coverRemote", it) }
            series?.let { put("series", it) }
            seriesNum?.let { put("seriesNum", it) }
        }

    companion object {
        fun fromJson(o: JSONObject): Book {
            val arr = o.optJSONArray("files") ?: JSONArray()
            return Book(
                id = o.getString("id"),
                folderDocId = o.getString("folder"),
                tree = o.optString("tree"),
                title = o.optString("title"),
                author = o.optString("author"),
                files = (0 until arr.length()).map { BookFile.fromJson(arr.getJSONObject(it)) },
                coverDocId = o.optString("cover").takeIf { it.isNotBlank() && it != "null" },
                textDocId = o.optString("text").takeIf { it.isNotBlank() && it != "null" },
                textName = o.optString("textName").takeIf { it.isNotBlank() && it != "null" },
                remoteDir = o.optString("remoteDir"),
                textRemote = o.optString("textRemote").takeIf { it.isNotBlank() },
                coverRemote = o.optString("coverRemote").takeIf { it.isNotBlank() },
                // Пустая серия - «смотрели, нет»: её надо отличить от «не смотрели».
                series = if (o.has("series") && !o.isNull("series")) o.optString("series") else null,
                seriesNum = o.optString("seriesNum").takeIf { o.has("seriesNum") && !o.isNull("seriesNum") && it.isNotBlank() },
            )
        }
    }
}

/** Ссылка на документ SAF, собранная из дерева библиотеки. */
fun documentUri(treeUri: Uri, docId: String): Uri =
    DocumentsContract.buildDocumentUriUsingTree(treeUri, docId)
