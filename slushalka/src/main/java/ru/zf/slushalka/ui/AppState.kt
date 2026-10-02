package ru.zf.slushalka.ui

import android.content.Intent
import android.net.Uri
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.BookState
import ru.zf.slushalka.data.PositionSync
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.library.Durations
import ru.zf.slushalka.library.LibraryScanner
import ru.zf.slushalka.library.documentUri
import ru.zf.slushalka.player.AudioChunk
import ru.zf.slushalka.text.Alignment
import ru.zf.slushalka.text.Anchor
import ru.zf.slushalka.text.BookText
import ru.zf.slushalka.text.Locator

/**
 * Состояние приложения между экранами: библиотека, открытая книга, её текст и
 * карта «аудио - текст». Экраны из этого только читают.
 */
class AppState(private val app: SlushalkaApp) {

    val settings = app.settings
    val prefs: StateFlow<Settings.Prefs> = settings.flow

    private val _books = MutableStateFlow<List<Book>>(emptyList())
    val books: StateFlow<List<Book>> = _books

    /** Непустое - значит внизу экрана висит полоска «чем занят». */
    private val _busy = MutableStateFlow<String?>(null)
    val busy: StateFlow<String?> = _busy

    private val _current = MutableStateFlow<Book?>(null)
    val current: StateFlow<Book?> = _current

    private val _text = MutableStateFlow<BookText?>(null)
    val text: StateFlow<BookText?> = _text

    private val _alignment = MutableStateFlow<Alignment?>(null)
    val alignment: StateFlow<Alignment?> = _alignment

    /**
     * Докуда дошёл другой человек: место в записи и место чтения, у каждого
     * своё время, - самые свежие с любого его устройства.
     */
    data class OtherPlace(
        val who: String,
        val absMs: Long,
        val readChar: Int,
        val listenAt: Long = 0,
        val readAt: Long = 0,
        /** Длина текста, как её видела его читалка: доля прочитанного. */
        val textChars: Int = 0,
    ) {
        val listenedLast: Boolean get() = listenAt > readAt
        val readShare: Float get() = if (textChars > 0 && readChar > 0) (readChar.toFloat() / textChars).coerceIn(0f, 1f) else 0f
    }

    /** Докуда дошли на других устройствах и у второго слушателя. */
    private val _others = MutableStateFlow<Map<String, List<OtherPlace>>>(emptyMap())
    val others: StateFlow<Map<String, List<OtherPlace>>> = _others

    /**
     * Место книги приехало с другого своего устройства и заметно отличается от
     * здешнего. Не спрашиваем, как раньше («продолжить там?» - и не тот ответ
     * сбивал место), а берём самое свежее и говорим об этом строкой с «Вернуть».
     */
    data class Moved(
        val bookId: String,
        val title: String,
        val device: String,
        /** Что поменялось: место в записи, место чтения. */
        val listen: Boolean,
        val read: Boolean,
        val now: BookState,
        /** Как было здесь - для «Вернуть». */
        val before: BookState,
    )

    private val _moved = MutableStateFlow<Moved?>(null)
    val moved: StateFlow<Moved?> = _moved

    /**
     * Место чтения открытой книги приехало с другого устройства, пока читалка
     * открыта: она переходит туда. Иначе следующая же страница записала бы
     * здешнюю со свежим временем - и приехавшее место пропало бы.
     */
    private val _readJump = MutableStateFlow<Int?>(null)
    val readJump: StateFlow<Int?> = _readJump

    fun takeReadJump() {
        _readJump.value = null
    }

    /** Читалка на экране: только ей и прыгать на приехавшее место. */
    @Volatile
    private var readerVisible = false

    fun readerOpened() {
        readerVisible = true
    }

    private val _recapOffer = MutableStateFlow(false)
    val recapOffer: StateFlow<Boolean> = _recapOffer

    private val _positionsRev = MutableStateFlow(0)
    /** Дёргается при каждой записи позиции: карточки библиотеки перерисовываются. */
    val positionsRev: StateFlow<Int> = _positionsRev

    /**
     * Книги библиотеки на сервере - в виде книг полки, с ключом, который у
     * них будет после скачивания. Пусто - облака нет или это не домашняя
     * библиотека (нет `index.json`).
     */
    private val _serverBooks = MutableStateFlow<List<Book>>(emptyList())
    val serverBooks: StateFlow<List<Book>> = _serverBooks

    /** Короткая строка внизу экрана о том, что сделалось: «удалено», «не вышло». */
    private val _notice = MutableStateFlow<String?>(null)
    val notice: StateFlow<String?> = _notice

    fun dismissNotice() {
        _notice.value = null
    }

    init {
        app.scope.launch {
            // Первое значение из DataStore приезжает асинхронно: спросить
            // раньше - получить заводскую пустоту и решить, что книг нет.
            val p = settings.flow.first { it.loaded }
            if (p.libraryUri.isNotBlank()) {
                _books.value = app.library.books(p.libraryUris)
                if (_books.value.isEmpty()) rescan() else {
                    syncPull()
                    // Полка из прежней версии - без серий: дочитать их в фоне.
                    fillSeries()
                }
            }
        }
        // Оглавление сервера пришло или сменилось - книги сервера пересобираются.
        app.scope.launch {
            app.server.index.collect { rebuildServerBooks() }
        }
    }

    /** Имя главной папки и для какого дерева оно узнано: папку могут сменить. */
    private var rootName: Pair<String, String>? = null

    private suspend fun rootName(): String? {
        val tree = treeUri() ?: return null
        rootName?.takeIf { it.first == tree.toString() }?.let { return it.second }
        val name = withContext(Dispatchers.IO) { LibraryScanner(app).rootName(tree) } ?: return null
        rootName = tree.toString() to name
        return name
    }

    private suspend fun rebuildServerBooks() {
        val index = app.server.index.value
        val root = if (index != null) rootName() else null
        val tree = treeUri()?.toString().orEmpty()
        _serverBooks.value = if (index == null || root == null) emptyList()
        else withContext(Dispatchers.Default) {
            index.books.map { ru.zf.slushalka.data.ServerLibrary.toBook(index, it, root, tree) }
        }
    }

    /** Книга по ключу: сперва с полки, потом с сервера - последняя могла быть оттуда. */
    fun bookById(id: String?): Book? {
        if (id == null) return null
        return _books.value.firstOrNull { it.id == id } ?: _serverBooks.value.firstOrNull { it.id == id }
    }

    /** Главная папка библиотеки: сюда качает каталог и здесь лежит `_Слушалка`. */
    fun treeUri(): Uri? = prefs.value.libraryUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    /** Дерево, в котором лежит книга: папок несколько, и файлы книги ищутся в своей. */
    fun treeOf(book: Book): Uri? = book.treeUri ?: treeUri()

    // ------------------------------------------------------------ библиотека

    fun onTreePicked(uri: Uri) {
        keepPermission(uri)
        app.scope.launch {
            settings.setLibraryUri(uri.toString())
            rescan()
        }
    }

    /** Ещё одна папка с книгами - к главной, а не вместо неё. */
    fun onExtraTreePicked(uri: Uri) {
        keepPermission(uri)
        app.scope.launch {
            settings.addLibraryExtra(uri.toString())
            rescan()
        }
    }

    fun removeExtraTree(uri: String) {
        app.scope.launch {
            settings.removeLibraryExtra(uri)
            rescan()
        }
    }

    private fun keepPermission(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
    }

    fun rescan() {
        app.scope.launch { rescanNow() }
    }

    /** То же, но дождаться: каталог после скачивания хочет знать, появилась ли книга. */
    suspend fun rescanNow() {
        val tree = treeUri() ?: return
        val trees = prefs.value.libraryUris
        _busy.value = "Читаю папку…"
        val known = _books.value.associateBy { it.id }
        val found = withContext(Dispatchers.IO) {
            val scanner = LibraryScanner(app)
            // Ключ книги - путь от имени папки; две папки с одним именем и одной
            // книгой внутри дали бы двойника, а полка на двойных ключах падает.
            trees.flatMap { scanner.scan(Uri.parse(it)) }.distinctBy { it.id }
        }
        // Уже измеренные длительности переносим: мерить заново долго и незачем.
        val merged = found.map { b ->
            val old = known[b.id] ?: return@map b
            val byDoc = old.files.associateBy { it.key }
            b.copy(
                files = b.files.map { f ->
                    val prev = byDoc[f.key]
                    if (f.durationMs <= 0 && prev != null && prev.size == f.size) f.copy(durationMs = prev.durationMs) else f
                },
                title = b.title.ifBlank { old.title },
                author = b.author.ifBlank { old.author },
                // Серия читалась из того же файла текста - второй раз незачем.
                series = if (b.textDocId == old.textDocId) old.series else null,
                seriesNum = if (b.textDocId == old.textDocId) old.seriesNum else null,
                authorKey = if (b.textDocId == old.textDocId) old.authorKey else null,
            )
        }
        app.library.replace(tree.toString(), merged)
        _books.value = merged
        _busy.value = null
        // Открытая книга сменилась на полке - докачала звук, взята текстом.
        // Плеер на паузе переезжает на новые файлы сам: иначе книга, уже
        // лежащая на телефоне, так и играла бы с сервера до перезапуска.
        val cur = _current.value
        val fresh = cur?.let { c -> merged.firstOrNull { it.id == c.id } }
        if (cur != null && fresh != null && fresh != cur) {
            _current.value = fresh
            if (cur.streams && !fresh.streams && app.player.isOpen(fresh.id) && !app.player.state.value.playing) {
                treeOf(fresh)?.let { app.player.open(it, fresh) }
            }
        }
        // Имя главной папки узнаётся заново: её могли сменить, а книги сервера
        // получают ключ от него.
        rootName = null
        rebuildServerBooks()
        syncPull()
        fillSeries()
    }

    private var seriesJob: kotlinx.coroutines.Job? = null

    /**
     * Серии и фамилии авторов книг полки - из головы файла текста, по одному
     * разу на книгу: прочитанное переносится через перечитывание папки, а
     * «нет» помнится пустой строкой. Пачками, чтобы полка показывала их по
     * мере того, как они находятся, а не после всех ста книг.
     */
    private fun fillSeries() {
        if (seriesJob?.isActive == true) return
        seriesJob = app.scope.launch {
            while (true) {
                val todo = _books.value
                    .filter { it.textDocId != null && (it.series == null || it.authorKey == null) }
                    .take(SERIES_BATCH)
                if (todo.isEmpty()) break
                val found = withContext(Dispatchers.IO) {
                    todo.associate { b ->
                        val tree = treeOf(b)
                        val docId = b.textDocId
                        b.id to if (tree != null && docId != null) {
                            ru.zf.slushalka.text.BookMeta.read(app, documentUri(tree, docId), b.textName.orEmpty())
                        } else null
                    }
                }
                val updated = _books.value.map { b ->
                    if (b.id in found && (b.series == null || b.authorKey == null)) {
                        val m = found[b.id]
                        b.copy(
                            series = m?.series?.name ?: "",
                            seriesNum = m?.series?.number,
                            authorKey = m?.surname ?: "",
                        )
                    } else b
                }
                _books.value = updated
                treeUri()?.let { app.library.replace(it.toString(), updated) }
            }
        }
    }

    /**
     * Удалить папку книги с телефона. Книга, которая есть на сервере, с ним и
     * остаётся: место, вопросы и пометки живут в `_Слушалка/` и в приложении,
     * а не в папке книги. Книга только с телефона уходит насовсем - экран
     * предупреждает об этом до того, как спросить.
     */
    fun deleteFromPhone(book: Book) {
        val tree = treeOf(book) ?: return
        if (!book.onPhone) return
        app.scope.launch {
            // Открытая книга играла бы из удалённых файлов: сперва её закрыть.
            if (app.player.isOpen(book.id)) app.player.close()
            if (app.readAloud.state.value.bookId == book.id) app.readAloud.stop()
            if (_current.value?.id == book.id) {
                _current.value = null
                _text.value = null
                _alignment.value = null
            }
            val ok = withContext(Dispatchers.IO) {
                runCatching {
                    android.provider.DocumentsContract.deleteDocument(
                        app.contentResolver, documentUri(tree, book.folderDocId),
                    )
                }.getOrDefault(false)
            }
            _notice.value = if (ok) "«${book.title}» удалена с телефона" else "Папку книги удалить не вышло"
            rescanNow()
        }
    }

    /**
     * Звук книги, взятой только текстом, - по свежему оглавлению сервера, если
     * оно есть: метка в папке писалась в день скачивания, а сервер мог с тех
     * пор переложить файлы. Без оглавления - как в метке.
     */
    private fun withServerAudio(book: Book): Book {
        if (!book.streams || !book.onPhone) return book
        val index = app.server.index.value ?: return book
        val sb = index.byFolder(book.folderName) ?: return book
        val files = ru.zf.slushalka.data.ServerLibrary.toBook(index, sb, "", "").files
        if (files.isEmpty() || files == book.files) return book
        return book.copy(files = files, remoteDir = index.dirOf(sb))
    }

    /** Длительности нужны раньше звука: без них не посчитать место в книге. */
    private suspend fun ensureDurations(book: Book): Book {
        if (book.durationsReady || !book.hasAudio) return book
        val tree = treeOf(book) ?: return book
        val measured = withContext(Dispatchers.IO) {
            Durations.probe(app, tree, book, remote = { app.streaming.mediaSource(it) }) { done, total ->
                _busy.value = "Меряю длительности: $done из $total"
            }
        }
        _busy.value = null
        app.library.update(measured)
        _books.value = _books.value.map { if (it.id == measured.id) measured else it }
        return measured
    }

    // ----------------------------------------------------------------- книга

    /** Открывает книгу на месте, где остановились. Звук не трогает: пуск - рукой. */
    fun open(book: Book) {
        val tree = treeOf(book) ?: return
        app.scope.launch {
            // Озвучка другой книги вместе с этой - каша: выключаем.
            if (app.readAloud.state.value.active && app.readAloud.state.value.bookId != book.id) {
                app.readAloud.stop()
            }
            // Свежее место - с сервера, но книга его не ждёт: открывается по
            // здешнему сразу, а приехавшее место подхватят плеер на паузе
            // (adopt) и открытая читалка (readJump). Обычно оно уже здесь -
            // приём идёт при открытии приложения.
            syncPull()
            app.positions.touchLast(book.id)
            val ready = ensureDurations(withServerAudio(book))
            _current.value = ready
            _text.value = null
            _alignment.value = null
            if (ready.hasAudio) {
                // Служба поднимается вместе с книгой: она и держит воспроизведение
                // живым, когда экран погаснет, и рисует плеер на локскрине.
                runCatching {
                    app.startService(Intent(app, ru.zf.slushalka.player.PlaybackService::class.java))
                }
                if (!app.player.isOpen(ready.id)) app.player.open(tree, ready)
            } else {
                // Книга без записи: плеер не трогаем, а если в нём играет другая
                // книга - ставим на паузу, как при любом переходе к чтению.
                app.player.pauseForAsking()
            }
            offerRecapIfDue(ready)
            loadText(ready)
            // Открытая книга - теперь последняя: виджет показывает её.
            ru.zf.slushalka.widget.ContinueWidget.refresh(app)
        }
    }

    private fun loadText(book: Book) {
        val tree = treeOf(book) ?: return
        app.scope.launch {
            if (!book.hasText) return@launch
            _busy.value = "Разбираю текст книги…"
            val t = app.texts.textFor(tree, book) { pct -> _busy.value = "Качаю текст с сервера: $pct%" }
            _busy.value = null
            if (_current.value?.id != book.id) return@launch
            _text.value = t
            // Книге без записи карта «звук ↔ текст» не нужна: без неё читалка
            // открывается на сохранённой странице и ничего не сверяет по звуку.
            if (t != null && book.hasAudio) {
                _alignment.value = Alignment.build(book, t, app.positions.get(book.id).anchors)
                loadMarkup(book, t)
                // Читали позже, чем слушали (здесь или на другом устройстве), -
                // запись встаёт к странице: пуск звука продолжит с прочитанного.
                followLaterReading(book)
                // Текст разобран - теперь шторке есть что показать вместо обложки.
                app.player.refreshArtwork(force = true)
            }
        }
    }

    private fun offerRecapIfDue(book: Book) {
        val s = app.positions.get(book.id)
        val hours = prefs.value.recapAfterHours
        // Есть что напоминать: послушали хоть пять минут или прочли хоть три
        // страницы. Книгу без записи раньше не спрашивали вовсе - у неё
        // секунд записи нет, есть только страницы.
        val progressed = s.absMs > 5 * 60_000 || s.readChar > 3 * Settings.PAGE_CHARS
        _recapOffer.value = progressed &&
            s.updatedAt > 0 &&
            System.currentTimeMillis() - s.updatedAt > hours * 3600_000L &&
            book.hasText
    }

    /** Разобрать книгу заново - когда с картинками или главами что-то не так. */
    fun reparseText() {
        val book = _current.value ?: return
        app.texts.forget(book.id)
        _text.value = null
        _alignment.value = null
        artKey = null
        loadText(book)
    }

    fun parseReport(): ru.zf.slushalka.text.ParseReport? =
        _current.value?.let { app.texts.reportFor(it.id) }

    /**
     * Картинка этого места книги - для шторки и экрана блокировки. Считается
     * ровно так же, как на экране плеера: одна книга, одна картинка, где бы на
     * неё ни смотрели.
     */
    fun pictureUriAt(bookId: String, absMs: Long): Uri? {
        val book = _current.value?.takeIf { it.id == bookId } ?: return null
        val align = _alignment.value ?: return null
        val text = _text.value ?: return null
        val picture = text.pictureAt(align.charAt(absMs))?.takeIf { it.file.isNotBlank() } ?: return null
        // Спрашивают раз в три секунды из тика плеера, а картинка держится
        // несколько страниц: ходить на диск каждый раз незачем. Ключ с номером
        // книги обязателен - имена картинок внутри fb2 сплошь «0.jpg», и на
        // одном имени соседняя книга получила бы чужую иллюстрацию.
        val key = book.id + "/" + picture.file
        if (key == artKey) return artUri
        val file = app.texts.pictureFile(book.id, picture.file)
        artKey = key
        artUri = if (file.exists()) Uri.fromFile(file) else null
        return artUri
    }

    private var artKey: String? = null
    private var artUri: Uri? = null

    fun picturesOnDisk(): Int = _current.value?.let { app.texts.allPictures(it.id).size } ?: 0

    fun dismissRecap() {
        _recapOffer.value = false
    }

    // Пересказ, заказанный с полки: книга открывается, и экран, на который
    // она попала, - плеер или читалка - сам показывает «Напомнить».
    private val _recapRequest = MutableStateFlow<String?>(null)
    val recapRequest: StateFlow<String?> = _recapRequest

    fun requestRecap(bookId: String) {
        _recapRequest.value = bookId
    }

    /** Заказ этой книги взят - второй экран его уже не покажет. */
    fun takeRecapRequest(bookId: String): Boolean {
        if (_recapRequest.value != bookId) return false
        _recapRequest.value = null
        _recapOffer.value = false
        return true
    }

    fun closeBook() {
        app.player.saveNow()
        _current.value = null
        bump()
    }

    /** Отметка «я тут»: с неё карта аудио-текст становится точной. */
    fun addAnchor(audioMs: Long, charOffset: Int) {
        val book = _current.value ?: return
        val text = _text.value ?: return
        val anchors = (app.positions.get(book.id).anchors + Anchor(audioMs, charOffset, manual = true))
            .distinctBy { it.audioMs / 1000 }
            .sortedBy { it.audioMs }
        app.positions.setAnchors(book.id, anchors)
        _alignment.value = Alignment.build(book, text, anchors)
        bump()
        // Отметка тут же уезжает в файл рядом с книгой - другим устройствам
        // не придётся выяснять то же самое заново.
        if (markupJob?.isActive != true) app.scope.launch { saveMarkup() }
    }

    fun dropAnchors() {
        val book = _current.value ?: return
        val text = _text.value ?: return
        cancelMarkup()
        app.positions.setAnchors(book.id, emptyList())
        _alignment.value = Alignment.build(book, text, emptyList())
        _markupProgress.value = null
        bump()
        // Вместе с отметками уходит и файл разметки: иначе та же карта
        // вернулась бы при следующем открытии книги.
        app.scope.launch { app.markup.delete(book) }
    }

    fun bump() {
        _positionsRev.value = _positionsRev.value + 1
    }

    fun stateOf(bookId: String): BookState = app.positions.get(bookId)

    // ------------------------------------------------- звук <-> текст

    /** Чем кончилась сверка места по звуку. */
    sealed interface Refine {
        /** Нашли: вот оно, место в тексте. */
        data class Found(val charOffset: Int) : Refine

        /** Рядом выверенная точка карты - слушать нечего, месту и так верим. */
        data object Trusted : Refine

        /** Сверка выключена или недоступна. */
        data object Off : Refine

        /** Распознаватель ничего не разобрал. */
        data object NoSpeech : Refine

        /** Расслышали, но в тексте не нашли. */
        data object NotFound : Refine
    }

    /**
     * Сверка места при переходе «слушаю → читаю».
     *
     * Читалка к этому времени уже открыта на месте по карте. Здесь слушаются
     * последние секунды записи и ищутся в ближайших абзацах - и только
     * найденное показывается человеку как точное. Не нашли - так и говорим,
     * а не делаем вид, будто нашли.
     */
    suspend fun refineReading(): Refine {
        val book = _current.value ?: return Refine.Off
        val text = _text.value ?: return Refine.Off
        val align = _alignment.value ?: return Refine.Off
        val absMs = app.player.state.value.absMs
        if (absMs <= 0) return Refine.Off
        if (align.distanceToAnchor(absMs) < TRUST_MAP_MS) return Refine.Trusted
        if (!prefs.value.refineOnSwitch || !app.recognizer.supported) return Refine.Off
        // Распознаватель один: пока идёт разметка, переход его не отнимает.
        if (markupJob?.isActive == true) return Refine.Off

        val r = probe(book, text, align, absMs)
        val hit = r.charOffset
        return when {
            hit != null -> {
                addAnchor(absMs, hit)
                Refine.Found(hit)
            }
            r.miss == Miss.NOT_FOUND -> Refine.NotFound
            else -> Refine.NoSpeech
        }
    }

    /** Чем кончилась проба - нужно, чтобы понимать, где рвётся, а не гадать. */
    private enum class Miss { OK, NO_AUDIO, NO_SPEECH, NOT_FOUND }

    private class Probe(
        val charOffset: Int?,
        val transcript: String?,
        val decodedMs: Long,
        val miss: Miss,
        /** Время записи, которому соответствует найденное место. */
        val anchorMs: Long = 0,
    )

    /**
     * Одна проба: кусок записи расшифровывается **на телефоне** и ищется в
     * тексте. Ни сети, ни моделей, ни денег - системный распознаватель
     * Андроида плюс обычный поиск по книге.
     *
     * [forward] - взять кусок ПОСЛЕ [atMs], а не перед ним. Так проверяется
     * переход из читалки в звук: перемотали по карте - и слушаем, туда ли
     * попали.
     */
    private suspend fun probe(
        book: Book,
        text: BookText,
        align: Alignment,
        atMs: Long,
        forward: Boolean = false,
        minVotes: Int = Locator.MIN_VOTES,
        staged: Boolean = true,
        radius: Int = Locator.DEFAULT_RADIUS,
    ): Probe {
        val tree = treeOf(book) ?: return Probe(null, null, 0, Miss.NO_AUDIO)
        val (index, inFile) = book.locate(atMs)
        val file = book.files.getOrNull(index) ?: return Probe(null, null, 0, Miss.NO_AUDIO)
        val from: Long
        val span: Long
        if (forward) {
            from = inFile
            span = minOf(CHUNK_MS, (file.durationMs - inFile).coerceAtLeast(0L))
        } else {
            from = (inFile - CHUNK_MS).coerceAtLeast(0L)
            span = (inFile - from).coerceAtMost(CHUNK_MS)
        }
        if (span < 4000) return Probe(null, null, 0, Miss.NO_AUDIO)
        // Место, которому отвечает услышанное, - конец куска.
        val anchorMs = if (forward) atMs + span else atMs

        val pcm = withContext(Dispatchers.IO) {
            // С сервера - через кэш плеера: только что игравшие секунды уже на
            // телефоне, недостающее докачивается куском по Range.
            if (file.isRemote) AudioChunk.decode(app.streaming.mediaSource(file), from, span)
            else AudioChunk.decode(app, documentUri(tree, file.docId), from, span)
        } ?: return Probe(null, null, 0, Miss.NO_AUDIO, anchorMs)

        val transcript = app.recognizer.recognize(pcm)
            ?: return Probe(null, null, pcm.durationMs, Miss.NO_SPEECH, anchorMs)

        val estimate = align.charAt(anchorMs)
        val bounds = boundsFor(align, text, anchorMs)
        val hit = if (staged) {
            Locator.findStaged(text, transcript, estimate, bounds, minVotes)
        } else {
            Locator.find(text, transcript, estimate, radius, minVotes, bounds)
        }
        return Probe(
            charOffset = hit?.charOffset,
            transcript = transcript,
            decodedMs = pcm.durationMs,
            miss = if (hit == null) Miss.NOT_FOUND else Miss.OK,
            anchorMs = anchorMs,
        )
    }

    /**
     * Между двумя выверенными точками карты место лежать не может - дальше них
     * искать незачем. Это и ускоряет поиск, и не даёт уехать в чужую главу.
     */
    private fun boundsFor(align: Alignment, text: BookText, atMs: Long): IntRange {
        val manual = align.anchors.filter { it.manual }
        val lo = manual.lastOrNull { it.audioMs < atMs }?.charOffset ?: 0
        val hi = manual.firstOrNull { it.audioMs > atMs }?.charOffset ?: text.length
        return lo..hi.coerceAtLeast(lo)
    }

    /**
     * Одиночная проба напоказ: сколько звука вынули, что услышали, нашлось ли
     * это в книге. Когда разметка не находит ничего, только это и отвечает на
     * вопрос «а что, собственно, сломалось».
     */
    fun testProbe() {
        val book = _current.value ?: return
        val text = _text.value ?: return
        if (markupJob?.isActive == true) return
        app.scope.launch {
            _markupProgress.value = MarkupProgress(0, 1, 0, true, "Пробую…")
            val align = _alignment.value ?: return@launch
            val at = app.player.state.value.absMs.takeIf { it > 20_000 } ?: (book.totalMs / 3)
            val r = probe(book, text, align, at)
            val note = when (r.miss) {
                Miss.NO_AUDIO -> "Звук не декодировался. Формат файла плеер играет, а декодер " +
                    "не осилил - напиши, какой это формат."
                Miss.NO_SPEECH -> "Звук вынут (${r.decodedMs / 1000} с, 16 кГц), но распознаватель " +
                    "не вернул ни слова. Похоже, офлайн-распознавание русского на телефоне не " +
                    "поставлено: Настройки → Система → Языки → Голосовой ввод → Распознавание речи."
                Miss.NOT_FOUND -> "Услышано: «${r.transcript?.take(120)}». В тексте книги это место " +
                    "не нашлось - либо текст не от этой записи, либо кусок пришёлся на музыку."
                Miss.OK -> "Услышано: «${r.transcript?.take(120)}». Нашлось на странице " +
                    "${text.pageOf(r.charOffset ?: 0)} - всё работает."
            }
            _markupProgress.value = MarkupProgress(1, 1, if (r.miss == Miss.OK) 1 else 0, false, note)
        }
    }

    // --------------------------------------------------------------- разметка

    data class MarkupProgress(
        val done: Int,
        val total: Int,
        val hits: Int,
        val running: Boolean,
        val note: String = "",
    )

    private val _markupProgress = MutableStateFlow<MarkupProgress?>(null)
    val markupProgress: StateFlow<MarkupProgress?> = _markupProgress

    private var markupJob: kotlinx.coroutines.Job? = null

    /** Есть ли у книги готовая разметка (своя или приехавшая из файла). */
    fun isMarkedUp(): Boolean = (_alignment.value?.manualCount ?: 0) >= MARKUP_ENOUGH

    fun cancelMarkup() {
        markupJob?.cancel()
        markupJob = null
    }

    /**
     * Разметка книги: [perHour] проб на каждый час записи, вразнобой внутри
     * часа. Идёт по порядку - каждая следующая проба опирается на уже
     * найденные точки, поэтому окно поиска сужается, а попаданий становится
     * больше.
     *
     * Прерванную разметку можно запустить снова: места, рядом с которыми точка
     * уже есть, пропускаются.
     */
    fun markupBook(perHour: Int = 3) {
        val book = _current.value ?: return
        val text = _text.value ?: return
        if (markupJob?.isActive == true) return
        if (!app.recognizer.supported) {
            _markupProgress.value = MarkupProgress(
                0, 0, 0, false,
                "Распознавание на этом устройстве недоступно - разметку не сделать",
            )
            return
        }
        val hours = (book.totalMs / 3600_000.0).coerceAtLeast(0.5)
        val total = (hours * perHour).toInt().coerceIn(4, 200)
        val rnd = java.util.Random(book.id.hashCode().toLong())

        markupJob = app.scope.launch {
            var done = 0
            var hits = 0
            var noAudio = 0
            var noSpeech = 0
            var notFound = 0
            _markupProgress.value = MarkupProgress(0, total, 0, true)
            try {
                for (i in 0 until total) {
                    val step = book.totalMs.toDouble() / total
                    // Вразнобой внутри своего отрезка: строгая сетка норовит
                    // попадать на стыки файлов, заставки и музыку.
                    val at = (step * (i + 0.2 + rnd.nextDouble() * 0.6))
                        .toLong()
                        .coerceIn(20_000L, (book.totalMs - 20_000L).coerceAtLeast(20_000L))

                    val align = _alignment.value ?: break
                    done++
                    if (align.distanceToAnchor(at) < ALREADY_NEAR_MS) {
                        _markupProgress.value = MarkupProgress(done, total, hits, true)
                        continue
                    }
                    _markupProgress.value = MarkupProgress(done, total, hits, true)

                    val radius = radiusFor(align, at)
                    val r = probe(
                        book, text, align, at,
                        minVotes = MARKUP_MIN_VOTES, staged = false, radius = radius,
                    )
                    when (r.miss) {
                        Miss.NO_AUDIO -> noAudio++
                        Miss.NO_SPEECH -> noSpeech++
                        Miss.NOT_FOUND -> notFound++
                        Miss.OK -> {}
                    }
                    val hit = r.charOffset
                    if (hit != null && monotonic(align, at, hit)) {
                        addAnchor(at, hit)
                        hits++
                        _markupProgress.value = MarkupProgress(done, total, hits, true)
                    }
                    // Если распознаватель молчит с самого начала, дальше молоть
                    // тридцать проб незачем - лучше сказать об этом сразу.
                    if (done >= EARLY_GIVE_UP && hits == 0 && noSpeech + noAudio >= done) break
                }
                val saved = saveMarkup()
                _markupProgress.value = MarkupProgress(
                    done, total, hits, false,
                    when {
                        hits == 0 && noSpeech > noAudio + notFound ->
                            "Распознаватель не вернул ни слова ни на одной пробе. Проверь, что " +
                                "стоит офлайн-распознавание русского: Настройки → Система → " +
                                "Языки → Голосовой ввод → Распознавание речи."
                        hits == 0 && noAudio > 0 ->
                            "Звук не декодировался ($noAudio из $done проб). Напиши, в каком " +
                                "формате файлы книги."
                        hits == 0 ->
                            "Услышанное не нашлось в тексте ни разу. Похоже, текст не от этой " +
                                "записи - другое издание или другая начитка."
                        // Папка книги может оказаться доступной только на чтение -
                        // об этом лучше сказать, чем молча оставить карту здесь.
                        !saved -> "Готово: выверено $hits из $done, но записать карту в папку " +
                            "книги не вышло - она осталась только на этом телефоне"
                        else -> "Готово: выверено $hits из $done, карта лежит рядом с книгой"
                    },
                )
            } catch (e: kotlinx.coroutines.CancellationException) {
                // Найденное до отмены - уже польза, его и сохраняем.
                saveMarkup()
                _markupProgress.value = MarkupProgress(done, total, hits, false, "Остановлено")
                throw e
            }
        }
    }

    private fun plural(n: Int): String = when {
        n % 10 == 1 && n % 100 != 11 -> "месту"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "местам"
        else -> "местам"
    }

    /**
     * Окно поиска. Пока точек нет, карта - одна пропорция на всю книгу, и
     * ошибаться она может страниц на тридцать. Когда точка есть с обеих
     * сторон, промахнуться уже почти негде.
     */
    private fun radiusFor(align: Alignment, at: Long): Int {
        val near = align.distanceToAnchor(at)
        return when {
            near < 45 * 60_000L -> 12_000
            align.manualCount > 0 -> 30_000
            else -> 60_000
        }
    }

    /**
     * Время идёт вперёд, текст тоже. Проба, которая просит поставить точку
     * назад относительно соседей, - это промах распознавания, а не открытие.
     */
    private fun monotonic(align: Alignment, at: Long, charOffset: Int): Boolean {
        val manual = align.anchors.filter { it.manual }
        val prev = manual.lastOrNull { it.audioMs < at }
        val next = manual.firstOrNull { it.audioMs > at }
        if (prev != null && charOffset <= prev.charOffset) return false
        if (next != null && charOffset >= next.charOffset) return false
        return true
    }

    fun dismissMarkupNote() {
        if (_markupProgress.value?.running != true) _markupProgress.value = null
    }

    /** Карта уезжает файлом в папку книги - оттуда её возьмут другие устройства. */
    private suspend fun saveMarkup(): Boolean {
        val book = _current.value ?: return false
        val text = _text.value ?: return false
        val anchors = app.positions.get(book.id).anchors.filter { it.manual }
        if (anchors.isEmpty()) return false
        return app.markup.write(book, text, prefs.value.profile.ifBlank { "без имени" }, anchors)
    }

    /** Разметка, приехавшая вместе с книгой: считать заново ничего не надо. */
    private suspend fun loadMarkup(book: Book, text: BookText) {
        val map = app.markup.read(book) ?: return
        if (!map.matches(book, text)) return
        val mine = app.positions.get(book.id).anchors
        // Свои отметки главнее: их ставили руками и здесь.
        val merged = (mine + map.anchors.filter { remote ->
            mine.none { kotlin.math.abs(it.audioMs - remote.audioMs) < 30_000 }
        }).sortedBy { it.audioMs }
        if (merged.size == mine.size) return
        app.positions.setAnchors(book.id, merged)
        _alignment.value = Alignment.build(book, text, merged)
        bump()
    }

    /**
     * Обратный переход: читал глазами - продолжаю слушать отсюда.
     *
     * Перемотка по карте - мгновенная, но карта может ошибаться. Поэтому,
     * если рядом нет выверенной точки, приложение слушает несколько секунд с
     * того места, куда попало, находит их в тексте и **поправляет перемотку** -
     * заодно оставляя на карте новую отметку.
     */
    fun listenFrom(charOffset: Int) {
        val align = _alignment.value ?: return
        val book = _current.value ?: return
        val text = _text.value ?: return
        val first = align.audioAt(charOffset)
        app.player.seekTo(first)

        val trusted = align.distanceToAnchor(first) < TRUST_MAP_MS
        if (trusted || !prefs.value.refineOnSwitch || !app.recognizer.supported ||
            markupJob?.isActive == true
        ) {
            if (!app.player.state.value.playing) app.player.playPause()
            return
        }
        app.scope.launch {
            _busy.value = "Ищу это место в записи…"
            val r = probe(book, text, align, first, forward = true)
            _busy.value = null
            r.charOffset?.let { found ->
                addAnchor(r.anchorMs, found)
                val corrected = (_alignment.value ?: align).audioAt(charOffset)
                // Поправляем, только если промах слышимый.
                if (kotlin.math.abs(corrected - first) > 3_000) app.player.seekTo(corrected)
            }
            if (!app.player.state.value.playing) app.player.playPause()
        }
    }

    /**
     * Место чтения. Пишется не само по себе: место в книге одно, и пока
     * читаешь глазами, запись подтягивается к странице - на диск они ложатся
     * вместе, с каждой перелистнутой страницей. Вернулся к плееру или открыл
     * приложение через день - звук стоит там, где остановились глаза.
     */
    fun saveReadChar(offset: Int) {
        val book = _current.value ?: return
        followReading(book, offset)
        app.positions.setReadChar(book.id, offset, _text.value?.length ?: 0)
        noteReading(book, offset)
        // На сервер - когда листание успокоилось: каждая страница уезжает через
        // несколько секунд, а не раз в две минуты, как раньше, - второе
        // устройство знает место чтения почти сразу.
        schedulePush(book.id)
    }

    private var pushJob: kotlinx.coroutines.Job? = null

    /** Отправить места чуть погодя: подряд идущие страницы уезжают одной отправкой. */
    fun schedulePush(bookId: String, delayMs: Long = PUSH_DEBOUNCE_MS) {
        pushJob?.cancel()
        pushJob = app.scope.launch {
            kotlinx.coroutines.delay(delayMs)
            syncPush(bookId)
        }
    }

    /**
     * Запись - к прочитанному, если читали позже, чем слушали: место чтения
     * приехало с другого устройства или книгу закрыли из читалки, а плеер
     * открылся на месте звука.
     */
    private fun followLaterReading(book: Book) {
        val st = app.positions.get(book.id)
        val align = _alignment.value ?: return
        // Строго позже: равное время - место из версии до 03.10, кто последним, неизвестно.
        if (st.readChar < 0 || st.readAt <= st.listenAt) return
        if (app.player.isOpen(book.id)) app.player.followReading(align.audioAt(st.readChar))
    }

    /**
     * Страница в журнал подходов - но только когда её читают глазами. Пока
     * говорит озвучка, страницы листаются за ней, и абзацы в журнал кладёт она
     * сама; пока идёт запись, читалка идёт за чтецом - это слушание, и его
     * считает плеер. Иначе один и тот же час записался бы дважды.
     */
    private fun noteReading(book: Book, offset: Int) {
        val speech = app.readAloud.state.value
        if (speech.active && speech.bookId == book.id) return
        if (app.player.isOpen(book.id) && app.player.state.value.playing) return
        app.journal.reading(book.id, offset)
    }

    /** Читалку закрыли: чтение глазами остановилось, время до возвращения не в зачёт. */
    fun readerClosed() {
        readerVisible = false
        _readJump.value = null
        val book = _current.value ?: return
        val speech = app.readAloud.state.value
        // Озвучка продолжает и с закрытой читалкой - её подход не трогаем.
        if (speech.active && speech.bookId == book.id) return
        if (app.player.isOpen(book.id) && app.player.state.value.playing) return
        app.journal.stopped()
    }

    /** Запись подтягивается к странице. Стоит ли она, решает плеер: идущий звук главнее. */
    private fun followReading(book: Book, offset: Int) {
        val align = _alignment.value ?: return
        val audioMs = align.audioAt(offset)
        if (app.player.isOpen(book.id)) {
            app.player.followReading(audioMs)
        } else {
            // Плеер этой книги не поднят - пишем прямо в позиции, тем же порядком.
            val (index, inFile) = book.locate(audioMs)
            app.positions.save(
                app.positions.get(book.id).copy(fileIndex = index, posMs = inFile, absMs = audioMs),
            )
        }
    }

    /** Откуда открыть читалку. [fromAudio] - место взято из записи, его стоит сверить. */
    data class ReadStart(val offset: Int, val fromAudio: Boolean)

    /**
     * С какого места открыть читалку: где остановился последним - глазами или
     * ушами. У каждого места своё время, и решает оно, а не догадка «запись
     * ушла от страницы дальше минуты»: читал, потом дослушал главу - читалка
     * открывается после прослушанного; слушал, потом дочитал - на своей
     * странице.
     */
    fun readingStart(): ReadStart {
        val book = _current.value ?: return ReadStart(0, false)
        val st = app.positions.get(book.id)
        val saved = st.readChar
        val align = _alignment.value ?: return ReadStart(saved.coerceAtLeast(0), false)
        val absMs = if (app.player.isOpen(book.id)) app.player.state.value.absMs else st.absMs
        return when {
            // Читалку не открывали - с места звука.
            saved < 0 -> ReadStart(align.charAt(absMs), true)
            // Читали после того, как слушали, - своя страница, сверять нечего.
            st.readAt > st.listenAt -> ReadStart(saved, false)
            // Слушали позже (или время одно - место из версии до 03.10), но звук
            // стоит у той же страницы: своя страница точнее карты.
            kotlin.math.abs(align.audioAt(saved) - absMs) <= READ_FRESH_MS -> ReadStart(saved, false)
            else -> ReadStart(align.charAt(absMs), true)
        }
    }


    // ---------------------------------------------------- синхронизация мест

    /**
     * Отправить свои места: в папку библиотеки (её возит сторонняя
     * синхронизация) и в облако. Файл мест - свой у этого устройства, другие
     * его не пишут; прежний файл на человека - для копий, которые ещё не
     * обновились. Не вышло (нет сети) - отправится со следующей переменой или
     * при следующем открытии приложения.
     */
    suspend fun syncPush(bookId: String) {
        val tree = treeUri()
        val p = prefs.value
        if (!p.syncPositions || p.profile.isBlank()) return
        app.player.saveNow()
        val all = app.positions.all()
        // Вопросы - только когда что-то спросили или слилось: файл с ответами
        // потолще позиций, и переписывать его каждые две минуты незачем.
        val asksRev = app.askLog.revision
        val asks = if (asksRev != pushedAsksRev) app.askLog.all() else null
        val notesRev = app.notes.revision.value
        val notes = if (notesRev != pushedNotesRev) app.notes.all() else null
        if (tree != null) {
            withContext(Dispatchers.IO) {
                app.sync.pushPlaces(tree, p.profile, p.device, all)
                app.sync.push(tree, p.profile, all)
                if (asks != null) app.sync.pushAsks(tree, p.profile, asks)
                if (notes != null) app.sync.pushNotes(tree, p.profile, notes)
            }
        }
        // Те же файлы - в облако, если оно настроено: тогда синхронизация
        // идёт без сторонней программы, которая возит папку библиотеки. Файл
        // мест - через .partial: читающий не увидит половину.
        var ok = true
        if (p.cloudReady && p.cloudSync) {
            val dir = ru.zf.slushalka.data.Cloud.SYNC_DIR
            fun path(prefix: String) = dir + "/" + PositionSync.fileName(prefix, p.profile)
            ok = app.cloud.putTextAtomic(
                dir + "/" + PositionSync.placesFileName(p.profile, p.device),
                PositionSync.placesJson(p.profile, p.device, all),
            ).isSuccess
            app.cloud.putText(path(PositionSync.PREFIX), PositionSync.positionsJson(p.profile, all))
            if (asks != null) app.cloud.putText(path(PositionSync.ASKS_PREFIX), PositionSync.asksJson(p.profile, asks))
            if (notes != null) app.cloud.putText(path(PositionSync.NOTES_PREFIX), PositionSync.notesJson(p.profile, notes))
        }
        pushPending = !ok
        if (asks != null) pushedAsksRev = asksRev
        if (notes != null) pushedNotesRev = notesRev
        bump()
    }

    private var pushedAsksRev = -1
    private var pushedNotesRev = -1

    /** Последняя отправка не дошла: дошлём при следующем приёме. */
    private var pushPending = false

    private val pullLock = kotlinx.coroutines.sync.Mutex()
    private var pullJob: kotlinx.coroutines.Job? = null

    fun syncPull() {
        syncPullJob()
    }

    /** Приём мест: идущий не дублируется - второй вызов ждёт тот же. */
    private fun syncPullJob(): kotlinx.coroutines.Job {
        pullJob?.takeIf { it.isActive }?.let { return it }
        return app.scope.launch { pullLock.withLockSafe { syncPullNow() } }.also { pullJob = it }
    }

    private suspend fun <T> kotlinx.coroutines.sync.Mutex.withLockSafe(block: suspend () -> T): T {
        lock()
        try {
            return block()
        } finally {
            unlock()
        }
    }

    /**
     * Принять места: свои с других устройств и чужие.
     *
     * Свои сливаются по книге и по каждому месту отдельно (см.
     * [ru.zf.slushalka.data.PositionStore.merge]): самое свежее слушание и
     * самое свежее чтение, с какого бы устройства они ни были. Заметно
     * сдвинулось место книги - строка «место с Boox» с «Вернуть»; открытый
     * плеер на паузе встаёт туда же. Чужие - самые свежие места каждого
     * человека со всех его устройств: их видно на полке.
     */
    private suspend fun syncPullNow() {
        val p = prefs.value
        if (!p.syncPositions) return
        val tree = treeUri()
        val remotes = (if (tree != null) withContext(Dispatchers.IO) { app.sync.pull(tree) } else emptyList()) +
            cloudRemotes(p)
        val mine = remotes.filter { it.profile.equals(p.profile, true) && p.profile.isNotBlank() }
        val others = remotes.filter { !it.profile.equals(p.profile, true) }

        // Свои: от старых к свежим - последнее слово за самым свежим.
        var moved: Moved? = null
        for (r in mine.sortedBy { it.at }) {
            for ((id, remote) in r.states) {
                val m = app.positions.merge(id, remote)
                if (!m.any) continue
                val now = app.positions.get(id)
                if (m.listen) app.player.adopt(id, now.absMs)
                if (m.read && readerVisible && _current.value?.id == id && now.readChar >= 0) _readJump.value = now.readChar
                // Своё же устройство (файл, записанный отсюда) - не новость.
                if (r.device.isNotBlank() && r.device == p.device) continue
                if (far(id, m.before, now)) {
                    val title = bookById(id)?.title ?: id.substringAfterLast('/')
                    moved = Moved(id, title, r.device.ifBlank { "другого устройства" }, m.listen, m.read, now, m.before)
                }
            }
        }
        if (moved != null) _moved.value = moved

        // Чужие: по человеку и книге - свежайшее слушание и свежайшее чтение.
        _others.value = others
            .groupBy { it.profile.trim() }
            .flatMap { (who, list) ->
                list.flatMap { it.states.entries }
                    .groupBy({ it.key }, { it.value })
                    .map { (id, states) ->
                        val l = states.maxBy { it.listenAt }
                        val rd = states.filter { it.readChar >= 0 }.maxByOrNull { it.readAt }
                        id to OtherPlace(
                            who = who,
                            absMs = l.absMs,
                            readChar = rd?.readChar ?: -1,
                            listenAt = l.listenAt,
                            readAt = rd?.readAt ?: 0L,
                            textChars = rd?.textChars ?: 0,
                        )
                    }
            }
            .groupBy({ it.first }, { it.second })

        bump()
        // Вопросы и пометки - отдельно и после: места нужны сразу, а эти файлы
        // потолще и ждать их незачем.
        app.scope.launch { pullAsksAndNotes(p, tree) }
        // Прошлая отправка не дошла - сеть, похоже, есть: дошлём.
        if (pushPending) app.positions.lastBook()?.let { schedulePush(it, 0) }
    }

    private suspend fun pullAsksAndNotes(p: Settings.Prefs, tree: Uri?) {
        // Вопросы, заданные с другого устройства, - в свою историю.
        if (tree != null) {
            val asks = withContext(Dispatchers.IO) { app.sync.pullAsks(tree, p.profile) }
            asks?.forEach { (id, list) -> app.askLog.merge(id, list) }
            // И пометки на полях: одна книга - одни поля на всех устройствах.
            val notes = withContext(Dispatchers.IO) { app.sync.pullNotes(tree, p.profile) }
            notes?.forEach { (id, list) -> app.notes.merge(id, list) }
        }
        if (p.cloudReady && p.cloudSync && p.profile.isNotBlank()) {
            val dir = ru.zf.slushalka.data.Cloud.SYNC_DIR
            app.cloud.getText(dir + "/" + PositionSync.fileName(PositionSync.ASKS_PREFIX, p.profile)).getOrNull()
                ?.let(PositionSync::parseAsks)?.forEach { (id, list) -> app.askLog.merge(id, list) }
            app.cloud.getText(dir + "/" + PositionSync.fileName(PositionSync.NOTES_PREFIX, p.profile)).getOrNull()
                ?.let(PositionSync::parseNotes)?.forEach { (id, list) -> app.notes.merge(id, list) }
        }
        bump()
    }

    /** Сдвиг, о котором стоит сказать: больше полминуты записи или страницы текста. */
    private fun far(id: String, before: BookState, now: BookState): Boolean {
        val listen = kotlin.math.abs(now.absMs - before.absMs) > 30_000 && before.listenAt > 0
        val read = before.readChar >= 0 && kotlin.math.abs(now.readChar - before.readChar) > Settings.PAGE_CHARS
        return listen || read
    }

    /** Места из облака - и файлы мест, и прежние файлы позиций; пусто - облака нет или оно молчит. */
    private suspend fun cloudRemotes(p: Settings.Prefs): List<PositionSync.Remote> {
        if (!p.cloudReady || !p.cloudSync) return emptyList()
        val dir = ru.zf.slushalka.data.Cloud.SYNC_DIR
        val names = app.cloud.list(dir).getOrNull().orEmpty()
            .filter { !it.dir && (PositionSync.isPositions(it.name) || PositionSync.isPlaces(it.name)) }
        return names.mapNotNull { item ->
            app.cloud.getText("$dir/${item.name}").getOrNull()?.let {
                if (PositionSync.isPlaces(item.name)) PositionSync.parsePlaces(it) else PositionSync.parseRemote(it)
            }
        }
    }

    fun dismissMoved() {
        _moved.value = null
    }

    /**
     * «Вернуть»: здешнее место было правильным. Оно становится самым свежим и
     * уезжает - иначе следующий приём снова принёс бы приехавшее.
     */
    fun undoMoved() {
        val m = _moved.value ?: return
        _moved.value = null
        app.positions.restore(m.before)
        app.player.adopt(m.bookId, m.before.absMs)
        bump()
        app.scope.launch { syncPush(m.bookId) }
    }

    companion object {
        /** Сколько записи расшифровывать для сверки: шести секунд хватает на
         * полтора десятка слов, а распознаётся такой кусок за секунду-другую. */
        private const val CHUNK_MS = 6_000L
        /** Ближе этого к выверенной точке карте можно верить как есть. Полминуты
         * записи - это абзац-другой, дальше карта уже промахивается заметно. */
        private const val TRUST_MAP_MS = 45_000L
        /** Разметка не переделывает то, что уже размечено. */
        private const val ALREADY_NEAR_MS = 4 * 60_000L
        /** Проба идёт без человека - планка попадания выше, чем при ручном переходе. */
        private const val MARKUP_MIN_VOTES = 5
        /** Столько выверенных точек - и книга считается размеченной. */
        private const val MARKUP_ENOUGH = 6
        /** Столько пустых проб подряд - и дальше молоть незачем. */
        private const val EARLY_GIVE_UP = 5
        /** Запись не дальше минуты от места чтения - значит, с тех пор не слушали.
         * Полминуты из этой минуты съедает откат при открытии книги. */
        private const val READ_FRESH_MS = 60_000L
        /** Серии читаются пачками по столько книг: полка обновляется между пачками. */
        private const val SERIES_BATCH = 12
        /** Перелистнул - места уезжают через столько, если листание успокоилось. */
        private const val PUSH_DEBOUNCE_MS = 5_000L
    }
}
