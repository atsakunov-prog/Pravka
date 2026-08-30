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
import ru.zf.slushalka.text.Alignment
import ru.zf.slushalka.text.Anchor
import ru.zf.slushalka.text.BookText

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
        val tree = treeUri() ?: return
        app.scope.launch {
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
    }

    /** Длительности нужны раньше звука: без них не посчитать место в книге. */
    private suspend fun ensureDurations(book: Book): Book {
        if (book.durationsReady) return book
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

    fun open(book: Book, autoPlay: Boolean) {
        val tree = treeUri() ?: return
        app.scope.launch {
            val ready = ensureDurations(book)
            _current.value = ready
            _text.value = null
            _alignment.value = null
            // Служба поднимается вместе с книгой: она и держит воспроизведение
            // живым, когда экран погаснет, и рисует плеер на локскрине.
            runCatching {
                app.startService(Intent(app, ru.zf.slushalka.player.PlaybackService::class.java))
            }
            if (!app.player.isOpen(ready.id)) {
                app.player.open(tree, ready, autoPlay = autoPlay)
            } else if (autoPlay) {
                app.player.playPause()
            }
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
            if (t != null) {
                _alignment.value = Alignment.build(book, t, app.positions.get(book.id).anchors)
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
    }

    fun dropAnchors() {
        val book = _current.value ?: return
        val text = _text.value ?: return
        app.positions.setAnchors(book.id, emptyList())
        _alignment.value = Alignment.build(book, text, emptyList())
        bump()
    }

    fun bump() {
        _positionsRev.value = _positionsRev.value + 1
    }

    fun stateOf(bookId: String): BookState = app.positions.get(bookId)

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

    fun declineResume() {
        val offer = _resumeOffer.value ?: return
        _resumeOffer.value = null
        // Отказ - тоже решение: пишем своё место поверх чужого, чтобы вопрос
        // не всплывал заново на каждом запуске.
        app.scope.launch { syncPush(offer.bookId) }
    }
}
