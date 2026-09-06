package ru.zf.slushalka.library

import android.content.Context
import android.media.MediaMetadataRetriever
import android.net.Uri
import android.provider.DocumentsContract

/**
 * Обход выбранной папки: что здесь книга, а что полка с книгами.
 *
 * Правило простое и предсказуемое: **папка с аудиофайлами внутри - это книга**.
 * Отдельно разбирается раскладка «диск 1 / диск 2»: если в папке самой аудио
 * нет, но лежит текст книги (fb2/epub) или подпапки называются частями, все
 * куски собираются в одну книгу, а не в две-три.
 *
 * И ещё одно: **папка с одним текстом и без подпапок - тоже книга**, только
 * без звука. Так выглядит книга, скачанная из каталога Флибусты: её читают
 * глазами, а звук, если появится рядом, подхватится при следующем чтении папки.
 */
class LibraryScanner(private val context: Context) {

    private data class Entry(
        val docId: String,
        val name: String,
        val mime: String,
        val size: Long,
    ) {
        val isDir get() = mime == DocumentsContract.Document.MIME_TYPE_DIR
    }

    fun scan(treeUri: Uri): List<Book> {
        val rootId = runCatching { DocumentsContract.getTreeDocumentId(treeUri) }.getOrNull()
            ?: return emptyList()
        val rootName = displayName(treeUri, rootId) ?: "Библиотека"
        return walk(treeUri, rootId, rootName, rootName).sortedWith(
            compareBy(NaturalOrder) { it.id }
        )
    }

    // ---------------------------------------------------------------- обход

    private fun walk(treeUri: Uri, docId: String, folderName: String, relPath: String): List<Book> {
        val entries = children(treeUri, docId)
        val directAudio = entries.filter { !it.isDir && isAudio(it) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
        val dirs = entries.filter { it.isDir }.sortedWith(compareBy(NaturalOrder) { it.name })
        val cover = pickCover(entries)
        val text = pickText(entries)

        if (directAudio.isNotEmpty()) {
            // Книга здесь. Аудио из вложенных папок (бонусы, вторая часть)
            // дописывается в хвост - слушать его всё равно после основного.
            val files = directAudio.map { it.toFile("") } +
                dirs.flatMap { collectAudio(treeUri, it.docId, it.name + "/") }
            return listOf(makeBook(relPath, docId, folderName, files, cover, text))
        }

        if (dirs.isEmpty()) {
            // Текст без записи и без подпапок - книга для чтения. Условие про
            // подпапки нарочно: случайный fb2 в корне библиотеки не должен
            // превращать всю полку в одну «книгу».
            return if (text != null) listOf(makeBook(relPath, docId, folderName, emptyList(), cover, text))
            else emptyList()
        }

        val merge = text != null || dirs.all { looksLikePart(it.name) }
        if (merge) {
            val files = dirs.flatMap { collectAudio(treeUri, it.docId, it.name + "/") }
            if (files.isNotEmpty()) {
                return listOf(makeBook(relPath, docId, folderName, files, cover, text))
            }
        }
        return dirs.flatMap { walk(treeUri, it.docId, it.name, "$relPath/${it.name}") }
    }

    private fun collectAudio(treeUri: Uri, docId: String, prefix: String): List<BookFile> {
        val entries = children(treeUri, docId)
        val here = entries.filter { !it.isDir && isAudio(it) }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .map { it.toFile(prefix) }
        val deeper = entries.filter { it.isDir }
            .sortedWith(compareBy(NaturalOrder) { it.name })
            .flatMap { collectAudio(treeUri, it.docId, prefix + it.name + "/") }
        return here + deeper
    }

    private fun Entry.toFile(prefix: String) =
        BookFile(docId = docId, name = name, relPath = prefix + name, size = size)

    private fun makeBook(
        relPath: String,
        docId: String,
        folderName: String,
        files: List<BookFile>,
        cover: Entry?,
        text: Entry?,
    ): Book {
        val (author, title) = splitAuthorTitle(folderName)
        return Book(
            id = relPath,
            folderDocId = docId,
            title = title,
            author = author,
            files = files,
            coverDocId = cover?.docId,
            textDocId = text?.docId,
            textName = text?.name,
        )
    }

    // ------------------------------------------------------------- разбор имён

    /** «Акунин - Азазель» и «Азазель (Акунин)» - обе раскладки встречаются. */
    private fun splitAuthorTitle(folder: String): Pair<String, String> {
        val clean = folder.trim().removeSuffix("/")
        Regex("^(.{2,40}?)\\s+[-–—]\\s+(.+)$").find(clean)?.let { m ->
            val left = m.groupValues[1].trim()
            val right = m.groupValues[2].trim()
            // Номер тома слева («01 - Азазель») автором не является.
            if (!left.all { it.isDigit() || it == '.' }) return left to right
            return "" to right
        }
        return "" to clean
    }

    private fun looksLikePart(name: String): Boolean =
        Regex("^(cd|disk|disc|диск|часть|part|том|book)\\s*[-_ ]?\\d+$", RegexOption.IGNORE_CASE)
            .matches(name.trim()) || name.trim().all { it.isDigit() }

    private fun pickCover(entries: List<Entry>): Entry? {
        val images = entries.filter { !it.isDir && isImage(it) }
        if (images.isEmpty()) return null
        val named = images.firstOrNull { e ->
            listOf("cover", "folder", "front", "обложка", "cover_")
                .any { e.name.lowercase().contains(it) }
        }
        return named ?: images.maxByOrNull { it.size }
    }

    private fun pickText(entries: List<Entry>): Entry? {
        val texts = entries.filter { !it.isDir && isText(it) }
        // fb2 разбирается точнее epub (главы размечены тегами, без вёрстки).
        return texts.firstOrNull { it.name.endsWith(".fb2", true) }
            ?: texts.firstOrNull { it.name.endsWith(".fb2.zip", true) }
            ?: texts.firstOrNull { it.name.endsWith(".epub", true) }
            ?: texts.firstOrNull()
    }

    // ------------------------------------------------------------------ SAF

    private fun children(treeUri: Uri, docId: String): List<Entry> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(treeUri, docId)
        val out = ArrayList<Entry>()
        runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE,
                    DocumentsContract.Document.COLUMN_SIZE,
                ),
                null, null, null,
            )?.use { c ->
                while (c.moveToNext()) {
                    out += Entry(
                        docId = c.getString(0),
                        name = c.getString(1) ?: continue,
                        mime = c.getString(2) ?: "",
                        size = if (c.isNull(3)) 0L else c.getLong(3),
                    )
                }
            }
        }
        return out
    }

    private fun displayName(treeUri: Uri, docId: String): String? {
        val uri = documentUri(treeUri, docId)
        return runCatching {
            context.contentResolver.query(
                uri,
                arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME),
                null, null, null,
            )?.use { if (it.moveToFirst()) it.getString(0) else null }
        }.getOrNull()
    }

    companion object {
        private val AUDIO_EXT = setOf("mp3", "m4a", "m4b", "aac", "ogg", "opus", "oga", "flac", "wav", "mp4")
        private val IMAGE_EXT = setOf("jpg", "jpeg", "png", "webp")
        private val TEXT_EXT = setOf("fb2", "epub", "zip")

        private fun ext(name: String) = name.substringAfterLast('.', "").lowercase()

        private fun isAudio(e: Entry) = e.mime.startsWith("audio/") || ext(e.name) in AUDIO_EXT

        private fun isImage(e: Entry) = e.mime.startsWith("image/") || ext(e.name) in IMAGE_EXT

        private fun isText(e: Entry) =
            ext(e.name) in TEXT_EXT || e.name.endsWith(".fb2.zip", true)
    }
}

/**
 * Длительности файлов. Нужны не только для «сколько осталось»: без них не
 * посчитать, в каком месте книги мы сейчас, а значит не задать вопрос.
 */
object Durations {

    /**
     * Измеряет всё, что ещё не измерено, и заодно подхватывает из тегов имя
     * книги и автора, если из названия папки их вытащить не удалось.
     */
    fun probe(
        context: Context,
        treeUri: Uri,
        book: Book,
        onProgress: (done: Int, total: Int) -> Unit = { _, _ -> },
    ): Book {
        val todo = book.files.count { it.durationMs <= 0 }
        if (todo == 0) return book
        var done = 0
        var tagAlbum: String? = null
        var tagArtist: String? = null
        val files = book.files.map { f ->
            if (f.durationMs > 0) return@map f
            val r = MediaMetadataRetriever()
            val ms = runCatching {
                r.setDataSource(context, documentUri(treeUri, f.docId))
                if (tagAlbum == null) {
                    tagAlbum = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ALBUM)
                    tagArtist = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_ARTIST)
                }
                r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
            }.getOrDefault(0L)
            runCatching { r.release() }
            done++
            onProgress(done, todo)
            f.copy(durationMs = ms)
        }
        return book.copy(
            files = files,
            title = book.title.ifBlank { tagAlbum.orEmpty() }.ifBlank { book.title },
            author = book.author.ifBlank { tagArtist.orEmpty() },
        )
    }
}
