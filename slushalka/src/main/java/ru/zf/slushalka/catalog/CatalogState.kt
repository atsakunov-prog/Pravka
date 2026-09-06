package ru.zf.slushalka.catalog

import android.net.Uri
import android.provider.DocumentsContract
import java.io.File
import java.util.zip.ZipFile
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
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
 * Скачанная книга ложится папкой `<Автор> - <Название>/` с fb2 (или epub) и
 * обложкой внутри **прямо в корень библиотеки**, рядом с аудиокнигами - ровно
 * так, как библиотека понимает книгу, и так, чтобы всё лежало плоско и было
 * видно сразу (владелец: «папка Books в Downloads, там всё будет лежать»).
 * После этого папка перечитывается, и книга появляется на полке как любая
 * другая: читалка, вопросы по книге, пересказ - всё работает; нет только
 * звука. Если начитку купят позже, её достаточно положить в ту же папку.
 */
class CatalogState(private val app: SlushalkaApp) {

    val client = FlibustaClient(app.settings)

    /** Одна открытая лента. Страницы подгружаются в хвост того же списка. */
    data class Page(
        val title: String,
        val url: String,
        /** Для поиска: план запросов; записи авторов встают первыми. */
        val search: SmartSearch.Plan? = null,
        val entries: List<OpdsEntry> = emptyList(),
        /** Сколько первых записей - авторы из поиска (под ними идёт заголовок «Книги»). */
        val authorCount: Int = 0,
        val next: String? = null,
        val loading: Boolean = false,
        val loadingMore: Boolean = false,
        /** Авторы уже показаны, а медленный поиск по названиям ещё идёт. */
        val booksPending: Boolean = false,
        val error: String? = null,
    ) {
        /** Страница автора: сюда советник приходит с вопросом «с чего начать». */
        val isAuthor get() = url.contains("/opds/author/")
    }

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

    /**
     * Поиск: авторы - несколькими быстрыми запросами с вариантами написания,
     * книги по названию - одним медленным. См. [SmartSearch].
     */
    fun search(query: String) {
        val plan = SmartSearch.plan(query)
        if (plan.query.length < 2) return
        push(Page(title = "«${plan.query}»", url = client.searchUrl(plan.bookTerm, authors = false), search = plan))
    }

    /** Поиск по совету советника: фамилия - к авторам, название - к книгам. */
    fun searchSuggested(author: String, title: String) {
        val plan = SmartSearch.planFor(author, title)
        if (plan.bookTerm.isBlank() && plan.authorTerms.isEmpty()) return
        push(
            Page(
                title = listOf(author, title).filter { it.isNotBlank() }.joinToString(" — "),
                url = client.searchUrl(plan.bookTerm.ifBlank { author }, authors = false) + "#совет",
                search = plan,
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
        cache.clear()
        _stack.value = emptyList()
    }

    fun retry() {
        val page = top ?: return
        // Повтор - это «спроси сайт ещё раз», а не «покажи то же из кэша».
        cache.clear()
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
            val plan = page.search
            if (plan != null) loadSearch(page, plan) else loadFeed(page)
        }
    }

    /** Пока грузили, могли уйти на другую ленту - тогда ответ уже не наш. */
    private fun stale(page: Page) = top?.url != page.url

    private suspend fun loadFeed(page: Page) {
        val result = runCatching { feed(page.url) }
        if (stale(page)) return
        result.onSuccess { f ->
            replaceTop { it.copy(entries = f.entries, next = f.next, loading = false) }
            prefetch(f.next)
        }.onFailure { e ->
            replaceTop { it.copy(loading = false, error = FlibustaClient.readable(e)) }
        }
    }

    /**
     * Поиск в два такта. Авторы приходят за секунду - их показываем сразу и
     * первыми. Книги по названию Флибуста ищет долго (десятки секунд бывает),
     * и ждать их, глядя на пустой экран, незачем: внизу висит «ищу книги…»,
     * а список авторов уже можно листать.
     */
    private suspend fun loadSearch(page: Page, plan: SmartSearch.Plan) = coroutineScope {
        val books = if (plan.bookTerm.isBlank()) null
        else async { runCatching { feed(client.searchUrl(plan.bookTerm, authors = false)) } }
        val authorFeeds = plan.authorTerms.map { term ->
            async { runCatching { feed(client.searchUrl(term, authors = true)) }.getOrNull() }
        }
        val authors = authorFeeds.awaitAll().filterNotNull().flatMap { it.entries }
            .distinctBy { SmartSearch.keyOf(it) }
            .sortedBy { SmartSearch.rank(it, plan) }
            .take(MAX_AUTHORS)
        if (stale(page)) return@coroutineScope
        replaceTop {
            it.copy(entries = authors, authorCount = authors.size, loading = false, booksPending = books != null)
        }
        if (books == null) return@coroutineScope

        var result = books.await()
        // Фраза целиком в названиях не нашлась - пробуем самое длинное слово:
        // «Азазель Акунин» → «Азазель». Один запасной запрос, не больше: он дорогой.
        val fallback = plan.bookFallback
        if (fallback != null && result.getOrNull()?.entries?.isEmpty() == true) {
            result = runCatching { feed(client.searchUrl(fallback, authors = false)) }
        }
        if (stale(page)) return@coroutineScope
        result.onSuccess { f ->
            replaceTop { it.copy(entries = it.entries + f.entries, next = f.next, booksPending = false) }
            prefetch(f.next)
        }.onFailure { e ->
            replaceTop { it.copy(booksPending = false, error = FlibustaClient.readable(e)) }
        }
    }

    /** Следующая страница ленты - дописывается в хвост. Обычно уже лежит в кэше: её подтянули заранее. */
    fun loadMore() {
        val page = top ?: return
        val next = page.next ?: return
        if (page.loading || page.loadingMore) return
        replaceTop { it.copy(loadingMore = true) }
        loadJob = app.scope.launch {
            val result = runCatching { feed(client.resolve(next)) }
            if (stale(page)) return@launch
            result.onSuccess { f ->
                replaceTop {
                    // Флибуста иногда повторяет записи на стыке страниц.
                    val known = it.entries.mapTo(HashSet()) { e -> e.key }
                    val fresh = f.entries.filter { e -> e.key !in known }
                    it.copy(
                        entries = it.entries + fresh,
                        // Страница без единой новой записи, но со ссылкой «дальше», -
                        // повод остановиться, а не крутить подгрузку по кругу.
                        next = if (fresh.isEmpty()) null else f.next,
                        loadingMore = false,
                    )
                }
                prefetch(f.next)
            }.onFailure { e ->
                replaceTop { it.copy(loadingMore = false, error = FlibustaClient.readable(e)) }
            }
        }
    }

    // ------------------------------------------------------------------ кэш

    private class Cached(val feed: OpdsFeed, val at: Long)

    /** Ленты по адресу, недавние. «Назад» и повторный поиск открываются мгновенно. */
    private val cache = object : LinkedHashMap<String, Cached>(64, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Cached>?) = size > CACHE_MAX
    }
    private val inflight = HashMap<String, Deferred<OpdsFeed>>()

    /**
     * Лента из кэша или с сайта. Один адрес не качается дважды одновременно:
     * подгрузка вперёд и докрутка до низа часто просят одно и то же.
     */
    private suspend fun feed(url: String): OpdsFeed {
        cache[url]?.takeIf { System.currentTimeMillis() - it.at < CACHE_TTL_MS }?.let { return it.feed }
        val job = inflight.getOrPut(url) {
            app.scope.async {
                try {
                    client.feed(url).also { cache[url] = Cached(it, System.currentTimeMillis()) }
                } finally {
                    inflight.remove(url)
                }
            }
        }
        return job.await()
    }

    /**
     * Следующая страница качается заранее, пока человек читает эту: у Флибусты
     * по двадцать записей на страницу, и каждая - отдельный медленный запрос.
     * К моменту, когда докрутили до низа, продолжение уже лежит в кэше.
     */
    private fun prefetch(next: String?) {
        val url = next?.let { client.resolve(it) } ?: return
        app.scope.launch { runCatching { feed(url) } }
    }

    // ----------------------------------------------------------- скачивание

    /**
     * Уже лежит в библиотеке - та самая книга, если её скачивали отсюда.
     * По имени папки, где бы она ни лежала: в корне или в прежней «Флибуста/».
     */
    fun inLibrary(entry: OpdsEntry): Book? {
        val suffix = "/${folderName(entry)}"
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

    /** Папка книги в корне библиотеки - заводится, если её ещё нет. */
    private fun folderId(tree: Uri, entry: OpdsEntry): String {
        val root = DocumentsContract.getTreeDocumentId(tree)
        return Saf.ensureChild(app, tree, root, folderName(entry), DocumentsContract.Document.MIME_TYPE_DIR)
            ?: throw FlibustaClient.CatalogException("Не удалось завести папку книги в библиотеке")
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
        private const val CACHE_MAX = 60
        private const val CACHE_TTL_MS = 20 * 60_000L
        /** Авторов в выдаче поиска - больше не нужно, дальше идут книги. */
        private const val MAX_AUTHORS = 25

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
