package ru.zf.slushalka.data

import android.net.Uri
import android.provider.DocumentsContract
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri

/**
 * Книги в облаке: хранить их там, а на полку качать ту, что читаешь сейчас.
 *
 * Книга в облаке - папка в `Книги/` той же раскладки, что на полке: аудио,
 * обложка, текст, подпапки дисков. Скачанная ложится папкой в корень главной
 * библиотеки, выгруженная - папкой в облако. Файл, который уже лежит там с
 * тем же размером, второй раз не везётся: оборванная передача продолжается
 * с того файла, на котором оборвалась.
 */
class CloudBooks(private val app: SlushalkaApp) {

    /** Идущая передача: что, сколько из скольких байт, какой файл сейчас. */
    data class Transfer(
        val name: String,
        val upload: Boolean,
        val doneBytes: Long = 0,
        val totalBytes: Long = 0,
        val file: String = "",
        val error: String? = null,
        val finished: Boolean = false,
    ) {
        val share: Float get() = if (totalBytes > 0) (doneBytes.toFloat() / totalBytes).coerceIn(0f, 1f) else 0f
    }

    private val _transfer = MutableStateFlow<Transfer?>(null)
    val transfer: StateFlow<Transfer?> = _transfer

    private var job: Job? = null
    val busy: Boolean get() = job?.isActive == true

    /** Книги в облаке - папки в `Книги/`. */
    suspend fun list(): Result<List<Cloud.Item>> =
        app.cloud.list(Cloud.BOOKS_DIR).map { items -> items.filter { it.dir }.sortedBy { it.name.lowercase() } }

    fun cancel() {
        job?.cancel()
        _transfer.value = _transfer.value?.copy(error = "Остановлено", finished = true)
    }

    fun clear() {
        if (!busy) _transfer.value = null
    }

    /** Скачать книгу из облака на полку: папкой в корень главной библиотеки. */
    fun download(name: String) {
        if (busy) return
        val tree = app.state.treeUri() ?: return
        job = app.scope.launch {
            var t = Transfer(name, upload = false)
            _transfer.value = t
            runCatching {
                val files = walkCloud("${Cloud.BOOKS_DIR}/$name", "")
                t = t.copy(totalBytes = files.sumOf { it.second })
                _transfer.value = t
                val root = DocumentsContract.getTreeDocumentId(tree)
                val bookDir = Saf.ensureChild(app, tree, root, name, DocumentsContract.Document.MIME_TYPE_DIR)
                    ?: throw Cloud.CloudException("Не завелась папка книги в библиотеке")
                for ((rel, size) in files) {
                    val dir = rel.substringBeforeLast('/', "").split('/').filter { it.isNotBlank() }
                        .fold(bookDir) { parent, part ->
                            Saf.ensureChild(app, tree, parent, part, DocumentsContract.Document.MIME_TYPE_DIR)
                                ?: throw Cloud.CloudException("Не завелась папка $part")
                        }
                    val fileName = rel.substringAfterLast('/')
                    // Уже лежит тем же размером - докачивать нечего.
                    if (localSize(tree, dir, fileName) == size) {
                        t = t.copy(doneBytes = t.doneBytes + size)
                        _transfer.value = t
                        continue
                    }
                    t = t.copy(file = rel)
                    _transfer.value = t
                    val docId = Saf.ensureChild(app, tree, dir, fileName, mimeOf(fileName))
                        ?: throw Cloud.CloudException("Не завёлся файл $rel")
                    val before = t.doneBytes
                    app.cloud.download("${Cloud.BOOKS_DIR}/$name/$rel") { input, _ ->
                        app.contentResolver.openOutputStream(documentUri(tree, docId), "wt")?.use { out ->
                            val buf = ByteArray(64 * 1024)
                            var got = 0L
                            var last = 0L
                            while (true) {
                                val n = input.read(buf)
                                if (n < 0) break
                                out.write(buf, 0, n)
                                got += n
                                // Прогресс - раз в полмегабайта: чаще незачем гонять экран.
                                if (got - last > 512 * 1024) {
                                    last = got
                                    _transfer.value = t.copy(doneBytes = before + got)
                                }
                            }
                        } ?: throw Cloud.CloudException("Не открылся файл $rel на запись")
                    }.getOrThrow()
                    t = t.copy(doneBytes = before + size)
                    _transfer.value = t
                }
            }.onFailure { e ->
                _transfer.value = t.copy(error = e.message ?: "Не вышло", finished = true)
                return@launch
            }
            _transfer.value = t.copy(finished = true, file = "")
            app.state.rescanNow()
        }
    }

    /** Выгрузить книгу с полки в облако - всю папку, как она лежит. */
    fun upload(book: Book) {
        if (busy) return
        val tree = app.state.treeOf(book) ?: return
        val name = book.id.substringAfterLast('/')
        job = app.scope.launch {
            var t = Transfer(name, upload = true)
            _transfer.value = t
            runCatching {
                val files = withContext(Dispatchers.IO) { walkLocal(tree, book.folderDocId, "") }
                t = t.copy(totalBytes = files.sumOf { it.size })
                _transfer.value = t
                val there = walkCloud("${Cloud.BOOKS_DIR}/$name", "").toMap()
                for (f in files) {
                    if (there[f.rel] == f.size) {
                        t = t.copy(doneBytes = t.doneBytes + f.size)
                        _transfer.value = t
                        continue
                    }
                    t = t.copy(file = f.rel)
                    _transfer.value = t
                    app.cloud.upload("${Cloud.BOOKS_DIR}/$name/${f.rel}", f.size) {
                        app.contentResolver.openInputStream(documentUri(tree, f.docId))
                    }.getOrThrow()
                    t = t.copy(doneBytes = t.doneBytes + f.size)
                    _transfer.value = t
                }
            }.onFailure { e ->
                _transfer.value = t.copy(error = e.message ?: "Не вышло", finished = true)
                return@launch
            }
            _transfer.value = t.copy(finished = true, file = "")
        }
    }

    /** Файлы книги в облаке: путь внутри книги и размер. Две ступени вложенности хватает дискам. */
    private suspend fun walkCloud(path: String, prefix: String, depth: Int = 0): List<Pair<String, Long>> {
        val items = app.cloud.list(path).getOrThrow()
        return items.flatMap { item ->
            if (item.dir) {
                if (depth >= 2) emptyList() else walkCloud("$path/${item.name}", "$prefix${item.name}/", depth + 1)
            } else listOf("$prefix${item.name}" to item.size)
        }
    }

    private data class LocalFile(val rel: String, val docId: String, val size: Long)

    private fun walkLocal(tree: Uri, dirId: String, prefix: String, depth: Int = 0): List<LocalFile> {
        val out = ArrayList<LocalFile>()
        query(tree, dirId).forEach { (docId, name, mime, size) ->
            if (mime == DocumentsContract.Document.MIME_TYPE_DIR) {
                if (depth < 2) out += walkLocal(tree, docId, "$prefix$name/", depth + 1)
            } else out += LocalFile("$prefix$name", docId, size)
        }
        return out
    }

    private data class Row(val docId: String, val name: String, val mime: String, val size: Long)

    private fun query(tree: Uri, parentId: String): List<Row> {
        val uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, parentId)
        val out = ArrayList<Row>()
        runCatching {
            app.contentResolver.query(
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
                    out += Row(c.getString(0), c.getString(1) ?: "", c.getString(2) ?: "", c.getLong(3))
                }
            }
        }
        return out
    }

    private fun localSize(tree: Uri, dirId: String, name: String): Long? =
        query(tree, dirId).firstOrNull { it.name == name }?.size

    /** MIME по расширению: без него провайдер документов допишет «.bin». */
    private fun mimeOf(name: String): String = when (name.substringAfterLast('.', "").lowercase()) {
        "mp3" -> "audio/mpeg"
        "m4a", "m4b" -> "audio/mp4"
        "ogg", "opus" -> "audio/ogg"
        "flac" -> "audio/flac"
        "jpg", "jpeg" -> "image/jpeg"
        "png" -> "image/png"
        "epub" -> "application/epub+zip"
        "json" -> "application/json"
        else -> "application/octet-stream"
    }
}
