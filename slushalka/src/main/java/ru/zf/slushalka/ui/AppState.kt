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

    /** Докуда дошли на других устройствах и у второго слушателя. */
    private val _others = MutableStateFlow<Map<String, List<Pair<String, Long>>>>(emptyMap())
    val others: StateFlow<Map<String, List<Pair<String, Long>>>> = _others

    data class ResumeOffer(val bookId: String, val absMs: Long, val from: String, val at: Long)

    private val _resumeOffer = MutableStateFlow<ResumeOffer?>(null)
    val resumeOffer: StateFlow<ResumeOffer?> = _resumeOffer

    private val _recapOffer = MutableStateFlow(false)
    val recapOffer: StateFlow<Boolean> = _recapOffer

    private val _positionsRev = MutableStateFlow(0)
    /** Дёргается при каждой записи позиции: карточки библиотеки перерисовываются. */
    val positionsRev: StateFlow<Int> = _positionsRev

    init {
        app.scope.launch {
            // Первое значение из DataStore приезжает асинхронно: спросить
            // раньше - получить заводскую пустоту и решить, что книг нет.
            val tree = settings.flow.first { it.loaded }.libraryUri
            if (tree.isNotBlank()) {
                _books.value = app.library.books(tree)
                if (_books.value.isEmpty()) rescan() else syncPull()
            }
        }
    }

    fun treeUri(): Uri? = prefs.value.libraryUri.takeIf { it.isNotBlank() }?.let(Uri::parse)

    // ------------------------------------------------------------ библиотека

    fun onTreePicked(uri: Uri) {
        runCatching {
            app.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION,
            )
        }
        app.scope.launch {
            settings.setLibraryUri(uri.toString())
            rescan()
        }
    }

    fun rescan() {
        app.scope.launch { rescanNow() }
    }

    /** То же, но дождаться: каталог после скачивания хочет знать, появилась ли книга. */
    suspend fun rescanNow() {
        val tree = treeUri() ?: return
        _busy.value = "Читаю папку…"
        val known = _books.value.associateBy { it.id }
        val found = withContext(Dispatchers.IO) { LibraryScanner(app).scan(tree) }
        // Уже измеренные длительности переносим: мерить заново долго и незачем.
        val merged = found.map { b ->
            val old = known[b.id] ?: return@map b
            val byDoc = old.files.associateBy { it.docId }
            b.copy(
                files = b.files.map { f ->
                    val prev = byDoc[f.docId]
                    if (prev != null && prev.size == f.size) f.copy(durationMs = prev.durationMs) else f
                },
                title = b.title.ifBlank { old.title },
                author = b.author.ifBlank { old.author },
            )
        }
        app.library.replace(tree.toString(), merged)
        _books.value = merged
        _busy.value = null
        syncPull()
    }

    /** Длительности нужны раньше звука: без них не посчитать место в книге. */
    private suspend fun ensureDurations(book: Book): Book {
        if (book.durationsReady || !book.hasAudio) return book
        val tree = treeUri() ?: return book
        val measured = withContext(Dispatchers.IO) {
            Durations.probe(app, tree, book) { done, total ->
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
        val tree = treeUri() ?: return
        app.scope.launch {
            // Озвучка другой книги вместе с этой - каша: выключаем.
            if (app.readAloud.state.value.active && app.readAloud.state.value.bookId != book.id) {
                app.readAloud.stop()
            }
            val ready = ensureDurations(book)
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
            // Книгу открыли руками - вопрос «продолжить с другого устройства?»
            // про неё уже неактуален.
            if (_resumeOffer.value?.bookId == ready.id) _resumeOffer.value = null
            offerRecapIfDue(ready)
            loadText(ready)
        }
    }

    private fun loadText(book: Book) {
        val tree = treeUri() ?: return
        app.scope.launch {
            if (book.textDocId == null) return@launch
            _busy.value = "Разбираю текст книги…"
            val t = app.texts.textFor(tree, book)
            _busy.value = null
            if (_current.value?.id != book.id) return@launch
            _text.value = t
            // Книге без записи карта «звук ↔ текст» не нужна: без неё читалка
            // открывается на сохранённой странице и ничего не сверяет по звуку.
            if (t != null && book.hasAudio) {
                _alignment.value = Alignment.build(book, t, app.positions.get(book.id).anchors)
                loadMarkup(book, t)
                // Текст разобран - теперь шторке есть что показать вместо обложки.
                app.player.refreshArtwork(force = true)
            }
        }
    }

    private fun offerRecapIfDue(book: Book) {
        val s = app.positions.get(book.id)
        val hours = prefs.value.recapAfterHours
        _recapOffer.value = s.absMs > 5 * 60_000 &&
            s.updatedAt > 0 &&
            System.currentTimeMillis() - s.updatedAt > hours * 3600_000L &&
            book.textDocId != null
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
        val tree = treeUri() ?: return
        app.scope.launch { withContext(Dispatchers.IO) { app.markup.delete(tree, book) } }
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
        val tree = treeUri() ?: return Probe(null, null, 0, Miss.NO_AUDIO)
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
            AudioChunk.decode(app, documentUri(tree, file.docId), from, span)
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
        val tree = treeUri() ?: return false
        val book = _current.value ?: return false
        val text = _text.value ?: return false
        val anchors = app.positions.get(book.id).anchors.filter { it.manual }
        if (anchors.isEmpty()) return false
        return withContext(Dispatchers.IO) {
            app.markup.write(tree, book, text, prefs.value.profile.ifBlank { "без имени" }, anchors)
        }
    }

    /** Разметка, приехавшая вместе с книгой: считать заново ничего не надо. */
    private suspend fun loadMarkup(book: Book, text: BookText) {
        val tree = treeUri() ?: return
        val map = withContext(Dispatchers.IO) { app.markup.read(tree, book) } ?: return
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
        app.positions.setReadChar(book.id, offset)
        // В папку библиотеки - тем же шагом, что плеер на ходу: раз в две
        // минуты. Иначе после часа чтения второе устройство знало бы место
        // только с последней паузы звука.
        val now = System.currentTimeMillis()
        if (now - lastReadSyncAt > READ_SYNC_EVERY_MS) {
            lastReadSyncAt = now
            app.scope.launch { syncPush(book.id) }
        }
    }

    private var lastReadSyncAt = 0L

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

    fun readingStart(): ReadStart {
        val book = _current.value ?: return ReadStart(0, false)
        val saved = app.positions.get(book.id).readChar
        val align = _alignment.value ?: return ReadStart(saved.coerceAtLeast(0), false)
        val absMs = app.player.state.value.absMs
        // Запись стоит там, куда её довели глаза (откат при открытии книги -
        // самое большее полминуты): продолжаем со своей страницы, сверять по
        // звуку нечего. Ушла дальше - с тех пор слушали, и правда теперь у неё.
        return if (saved >= 0 && kotlin.math.abs(align.audioAt(saved) - absMs) <= READ_FRESH_MS) {
            ReadStart(saved, false)
        } else {
            ReadStart(align.charAt(absMs), true)
        }
    }


    // ---------------------------------------------------- синхронизация мест

    suspend fun syncPush(bookId: String) {
        val tree = treeUri() ?: return
        val p = prefs.value
        if (!p.syncPositions || p.profile.isBlank()) return
        app.player.saveNow()
        val all = app.positions.all()
        withContext(Dispatchers.IO) { app.sync.push(tree, p.profile, all) }
        bump()
    }

    fun syncPull() {
        val tree = treeUri() ?: return
        val p = prefs.value
        if (!p.syncPositions) return
        app.scope.launch {
            val remotes = withContext(Dispatchers.IO) { app.sync.pull(tree) }
            val mine = remotes.firstOrNull { it.profile.equals(p.profile, true) }
            val others = remotes.filter { !it.profile.equals(p.profile, true) }

            // Своя же дорожка с другого устройства: молча не подменяем - вдруг
            // там кто-то листал. Спрашиваем, если расхождение больше минуты.
            mine?.states?.forEach { (id, remote) ->
                val local = app.positions.get(id)
                if (remote.updatedAt > local.updatedAt + 60_000 &&
                    kotlin.math.abs(remote.absMs - local.absMs) > 30_000
                ) {
                    if (_resumeOffer.value == null) {
                        _resumeOffer.value = ResumeOffer(id, remote.absMs, p.profile, remote.updatedAt)
                    }
                } else if (remote.updatedAt > local.updatedAt) {
                    app.positions.merge(id, remote)
                }
            }

            _others.value = others.flatMap { r -> r.states.map { (id, s) -> id to (r.profile to s.absMs) } }
                .groupBy({ it.first }, { it.second })
            bump()
        }
    }

    fun acceptResume() {
        val offer = _resumeOffer.value ?: return
        _resumeOffer.value = null
        val book = _books.value.firstOrNull { it.id == offer.bookId } ?: return
        app.scope.launch {
            val ready = ensureDurations(book)
            _current.value = ready
            app.player.open(treeUri() ?: return@launch, ready, startAbsMs = offer.absMs)
            loadText(ready)
        }
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
        /** Место чтения уезжает в папку библиотеки не чаще, чем место слушания. */
        private const val READ_SYNC_EVERY_MS = 120_000L
    }

    fun declineResume() {
        val offer = _resumeOffer.value ?: return
        _resumeOffer.value = null
        // Отказ - тоже решение: пишем своё место поверх чужого, чтобы вопрос
        // не всплывал заново на каждом запуске.
        app.scope.launch { syncPush(offer.bookId) }
    }
}
