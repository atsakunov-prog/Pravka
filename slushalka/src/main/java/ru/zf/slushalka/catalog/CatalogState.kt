package ru.zf.slushalka.catalog

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Saf
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.documentUri

/**
 * Каталог Флибусты внутри приложения: стопка открытых лент, поиск и
 * скачивание книги **прямо в папку библиотеки**.
 *
 * Скачанная книга ложится папкой `Флибуста/<Автор> - <Название>/` с fb2 (или
 * epub) и обложкой внутри - ровно так, как библиотека понимает книгу. После
 * этого папка перечитывается, и книга появляется на полке как любая другая:
 * читалка, вопросы по книге, пересказ - всё работает; нет только звука. Если
 * начитку купят позже, её достаточно положить в ту же папку.
 */
class CatalogState(private val app: SlushalkaApp) {

    val client = FlibustaClient(app.settings)

    /** Одна открытая лента. Страницы подгружаются в хвост того же списка. */
    data class Page(
        val title: String,
        val url: String,
        /** Для поиска: вторая лента (авторы), её записи встают первыми. */
        val authorsUrl: String? = null,
        val entries: List<OpdsEntry> = emptyList(),
        /** Сколько первых записей - авторы из поиска (под ними идёт заголовок «Книги»). */
        val authorCount: Int = 0,
        val next: String? = null,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        val error: String? = null,
    )

    private val _stack = MutableStateFlow<List<Page>>(emptyList())
    /** Стопка лент: последняя - на экране, «назад» снимает её. */
    val stack: StateFlow<List<Page>> = _stack

    sealed interface Download {
        data object Idle : Download
        /** [entryKey] - [OpdsEntry.key]: издания одной книги делят `<id>`, но не номер файла. */
        data class Running(val entryKey: String, val percent: Int, val step: String) : Download
        data class Done(val entryKey: String, val book: Book?) : Download
        data class Failed(val entryKey: String, val message: String) : Download
    }

    private val _download = MutableStateFlow<Download>(Download.Idle)
    val download: StateFlow<Download> = _download

    private var loadJob: Job? = null
    private var downloadJob: Job? = null

    val top: Page? get() = _stack.value.lastOrNull()

    // -------------------------------------------------------------- ленты

    /** Открыть корень каталога, если ещё ничего не открыто. */
    fun openRoot() {
        if (_stack.value.isNotEmpty()) return
        push(Page(title = "Флибуста", url = client.rootUrl()))
    }

    fun open(title: String, href: String) {
        push(Page(title = title.ifBlank { "Флибуста" }, url = client.resolve(href)))
    }

    /** Поиск сразу по двум лентам: авторы (их мало, они первыми) и книги. */
    fun search(query: String) {
        val q = query.trim()
        if (q.length < 2) return
        push(
            Page(
                title = "«$q»",
                url = client.searchUrl(q, authors = false),
                authorsUrl = client.searchUrl(q, authors = true),
            )
        )
    }

    /** Снять верхнюю ленту. false - стопка уже пуста, экран пора закрывать. */
    fun back(): Boolean {
        val s = _stack.value
        if (s.size <= 1) return false
        loadJob?.cancel()
        _stack.value = s.dropLast(1)
        return true
    }

    /** Всё заново - с корня. Пригодится, когда сменили адрес сайта. */
    fun reset() {
        loadJob?.cancel()
        _stack.value = emptyList()
    }

    fun retry() {
        val page = top ?: return
        load(page.copy(entries = emptyList(), error = null))
    }

    private fun push(page: Page) {
        loadJob?.cancel()
        _stack.value = _stack.value + page
        load(page)
    }

    private fun replaceTop(transform: (Page) -> Page) {
        val s = _stack.value
        if (s.isEmpty()) return
        _stack.value = s.dropLast(1) + transform(s.last())
    }

    private fun load(page: Page) {
        replaceTop { page.copy(loading = true, error = null) }
        loadJob = app.scope.launch {
            val result = runCatching {
                coroutineScope {
                    val authors = page.authorsUrl?.let { u -> async { runCatching { client.feed(u) } } }
                    val books = client.feed(page.url)
                    val authorEntries = authors?.await()?.getOrNull()?.entries.orEmpty()
                    Triple(books, authorEntries, books.next)
                }
            }
            // Пока грузили, могли уйти на другую ленту - тогда ответ не наш.
            if (top?.url != page.url) return@launch
            result.onSuccess { (feed, authorEntries, next) ->
                replaceTop {
                    it.copy(
                        entries = authorEntries + feed.entries,
                        authorCount = authorEntries.size,
                        next = next,
                        loading = false,
                    )
                }
            }.onFailure { e ->
                replaceTop { it.copy(loading = false, error = FlibustaClient.readable(e)) }
            }
        }
    }

    /** Следующая страница ленты - дописывается в хвост. */
    fun loadMore() {
        val page = top ?: return
        val next = page.next ?: return
        if (page.loading || page.loadingMore) return
        replaceTop { it.copy(loadingMore = true) }
        loadJob = app.scope.launch {
            val result = runCatching { client.feed(client.resolve(next)) }
            if (top?.url != page.url) return@launch
            result.onSuccess { feed ->
                replaceTop {
                    // Флибуста иногда повторяет записи на стыке страниц.
                    val known = it.entries.mapTo(HashSet()) { e -> e.key }
                    val fresh = feed.entries.filter { e -> e.key !in known }
                    it.copy(
                        entries = it.entries + fresh,
                        // Страница без единой новой записи, но со ссылкой «дальше», -
                        // повод остановиться, а не крутить подгрузку по кругу.
                        next = if (fresh.isEmpty()) null else feed.next,
                        loadingMore = false,
                    )
                }
            }.onFailure { e ->
                replaceTop { it.copy(loadingMore = false, error = FlibustaClient.readable(e)) }
            }
        }
    }

    // ----------------------------------------------------------- скачивание

    /** Уже лежит в библиотеке - та самая книга, если её скачивали отсюда. */
    fun inLibrary(entry: OpdsEntry): Book? {
        val suffix = "/$FOLDER/${folderName(entry)}"
        return app.state.books.value.firstOrNull { it.id.endsWith(suffix) && it.textDocId != null }
    }

    fun dismissDownload() {
        if (_download.value !is Download.Running) _download.value = Download.Idle
    }

    fun cancelDownload() {
        downloadJob?.cancel()
        downloadJob = null
        _download.value = Download.Idle
    }

    /**
     * Скачать книгу в библиотеку. [format] - «fb2» или «epub». fb2 приезжает
     * zip-ом с одним файлом внутри - распаковывается, чтобы в папке лежал
     * обычный `.fb2`, который разбирается без промежуточных копий.
     */
    fun download(entry: OpdsEntry, format: String) {
        if (_download.value is Download.Running) return
        val link = entry.acquisition(format) ?: run {
            _download.value = Download.Failed(entry.key, "У этой книги нет файла $format")
            return
        }
        val tree = app.state.treeUri() ?: run {
            _download.value = Download.Failed(entry.key, "Сначала выбери папку с книгами в настройках")
            return
        }
        _download.value = Download.Running(entry.key, 0, "Качаю…")
        downloadJob = app.scope.launch {
            val result = runCatching {
                val tmp = File(app.cacheDir, "flibusta.part")
                client.download(client.resolve(link.href), tmp) { p ->
                    _download.value = Download.Running(entry.key, p, "Качаю…")
                }
                _download.value = Download.Running(entry.key, 100, "Кладу в библиотеку…")
                withContext(Dispatchers.IO) { store(tree, entry, format, tmp) }
                // Обложка - отдельным файлом рядом: полка покажет её сразу,
                // не дожидаясь разбора книги. Не вышло - не беда, достанется из fb2.
                entry.cover?.let { href ->
                    runCatching {
                        val bytes = client.cover(href)?.let { bmp ->
                            java.io.ByteArrayOutputStream().also {
                                bmp.compress(android.graphics.Bitmap.CompressFormat.JPEG, 88, it)
                            }.toByteArray()
                        }
                        if (bytes != null) withContext(Dispatchers.IO) {
                            writeInto(tree, folderId(tree, entry), "cover.jpg", "image/jpeg", bytes)
                        }
                    }
                }
                tmp.delete()
                _download.value = Download.Running(entry.key, 100, "Перечитываю папку…")
                app.state.rescanNow()
                inLibrary(entry)
            }
            _download.value = result.fold(
                onSuccess = { Download.Done(entry.key, it) },
                onFailure = { e ->
                    if (e is kotlinx.coroutines.CancellationException) Download.Idle
                    else Download.Failed(entry.key, FlibustaClient.readable(e))
                },
            )
        }
    }

    private fun store(tree: Uri, entry: OpdsEntry, format: String, tmp: File) {
        if (tmp.length() < 2_000) throw FlibustaClient.CatalogException("Файл пришёл пустым")
        // Не readNBytes: он появился только в Android 13, а minSdk у нас 26.
        val head = tmp.inputStream().use { input ->
            val buf = ByteArray(64)
            val n = input.read(buf)
            if (n > 0) buf.copyOf(n) else ByteArray(0)
        }
        val isZip = head.size >= 2 && head[0] == 'P'.code.toByte() && head[1] == 'K'.code.toByte()
        val headText = head.toString(Charsets.UTF_8).trimStart()
        if (headText.startsWith("<!DOCTYPE", true) || headText.startsWith("<html", true)) {
            throw FlibustaClient.CatalogException("Вместо книги пришла страница сайта - попробуй позже")
        }
        val dir = folderId(tree, entry)
        val name = fileName(entry)
        when (format) {
            "fb2" -> {
                if (isZip) {
                    // Внутри архива один fb2 - его и кладём, уже распакованным.
                    val ok = ZipFile(tmp).use { zip ->
                        val e = zip.entries().asSequence().firstOrNull { it.name.endsWith(".fb2", true) }
                            ?: throw FlibustaClient.CatalogException("В архиве не нашлось fb2")
                        zip.getInputStream(e).use { input ->
                            writeInto(tree, dir, "$name.fb2", MIME_FB2) { out -> input.copyTo(out) }
                        }
                    }
                    if (!ok) throw FlibustaClient.CatalogException("Не удалось записать файл в папку книг")
                } else if (headText.startsWith("<?xml") || headText.contains("<FictionBook")) {
                    tmp.inputStream().use { input ->
                        if (!writeInto(tree, dir, "$name.fb2", MIME_FB2) { out -> input.copyTo(out) }) {
                            throw FlibustaClient.CatalogException("Не удалось записать файл в папку книг")
                        }
                    }
                } else throw FlibustaClient.CatalogException("Файл не похож ни на fb2, ни на архив")
            }
            "epub" -> {
                if (!isZip) throw FlibustaClient.CatalogException("Файл не похож на epub")
                tmp.inputStream().use { input ->
                    if (!writeInto(tree, dir, "$name.epub", "application/epub+zip") { out -> input.copyTo(out) }) {
                        throw FlibustaClient.CatalogException("Не удалось записать файл в папку книг")
                    }
                }
            }
            else -> throw FlibustaClient.CatalogException("Формат $format читалка не открывает")
        }
    }

    /** Папка книги внутри `Флибуста/` - заводится, если её ещё нет. */
    private fun folderId(tree: Uri, entry: OpdsEntry): String {
        val root = DocumentsContract.getTreeDocumentId(tree)
        val shelf = Saf.ensureChild(app, tree, root, FOLDER, DocumentsContract.Document.MIME_TYPE_DIR)
            ?: throw FlibustaClient.CatalogException("Не удалось завести папку «$FOLDER» в библиотеке")
        return Saf.ensureChild(app, tree, shelf, folderName(entry), DocumentsContract.Document.MIME_TYPE_DIR)
            ?: throw FlibustaClient.CatalogException("Не удалось завести папку книги")
    }

    private fun writeInto(tree: Uri, dir: String, name: String, mime: String, bytes: ByteArray): Boolean =
        writeInto(tree, dir, name, mime) { it.write(bytes) }

    /** «wt» - усечение: прежний недокачанный файл не должен остаться хвостом. */
    private fun writeInto(
        tree: Uri, dir: String, name: String, mime: String,
        body: (java.io.OutputStream) -> Unit,
    ): Boolean {
        val docId = Saf.ensureChild(app, tree, dir, name, mime) ?: return false
        return runCatching {
            app.contentResolver.openOutputStream(documentUri(tree, docId), "wt")?.use(body) != null
        }.getOrDefault(false)
    }

    companion object {
        /** Полка внутри библиотеки, куда ложится всё скачанное. */
        const val FOLDER = "Флибуста"

        /** Своего MIME у fb2 в Android нет; октет-стрим не заставит систему дописать расширение. */
        private const val MIME_FB2 = "application/octet-stream"

        /** «Акунин Борис - Азазель»: имя папки, из которого библиотека прочитает автора и название. */
        fun folderName(entry: OpdsEntry): String {
            val author = safe(entry.authors.firstOrNull().orEmpty()).take(40)
            val title = safe(entry.title).take(80).ifBlank { "Без названия" }
            return if (author.length >= 2) "$author - $title" else title
        }

        private fun fileName(entry: OpdsEntry): String = safe(entry.title).take(80).ifBlank { "book" }

        private fun safe(s: String): String =
            s.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
                .trimEnd('.')
    }
}
