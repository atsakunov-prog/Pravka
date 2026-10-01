package ru.zf.slushalka.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import ru.zf.slushalka.SlushalkaApp
import kotlinx.coroutines.launch
import ru.zf.slushalka.data.ServerLibrary
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    app: SlushalkaApp,
    onPickTree: () -> Unit,
    onOpen: (Book) -> Unit,
    onSettings: () -> Unit,
    onCatalog: () -> Unit,
    onStats: () -> Unit,
    /** Облако: книги там и синхронизация без сторонней программы. */
    onCloud: () -> Unit,
    /** Разговор о дочитанной книге - из меню книги. */
    onTalk: (Book) -> Unit,
) {
    val state = app.state
    val books by state.books.collectAsState()
    val serverBooks by state.serverBooks.collectAsState()
    val index by app.server.index.collectAsState()
    val serverStatus by app.server.status.collectAsState()
    val transfer by app.cloudBooks.transfer.collectAsState()
    val busy by state.busy.collectAsState()
    val notice by state.notice.collectAsState()
    val others by state.others.collectAsState()
    val offer by state.resumeOffer.collectAsState()
    val rev by state.positionsRev.collectAsState()
    val prefs by state.prefs.collectAsState()
    val update by app.updater.status.collectAsState()
    val scope = rememberCoroutineScope()
    var shelf by rememberSaveable { mutableStateOf(Shelf.ALL) }
    var menuFor by remember { mutableStateOf<Book?>(null) }
    var serverMenu by remember { mutableStateOf<ServerEntry?>(null) }
    var deleteAsk by remember { mutableStateOf<Book?>(null) }

    // Переключатель «На телефоне / В библиотеке» есть, когда настроено облако:
    // библиотека - это его оглавление. Без облака полка - только телефон.
    val serverOn = prefs.cloudReady
    val inServer = serverOn && prefs.libraryView == Settings.VIEW_SERVER
    LaunchedEffect(inServer) {
        // Открыл библиотеку - свежее оглавление; минуту назад уже брали - хватит.
        // В области приложения, а не экрана: ушёл с полки - запрос доходит.
        if (inServer) app.scope.launch { app.server.refreshIfStale() }
    }
    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(4_000)
            state.dismissNotice()
        }
    }

    // Напомнить с полки: книга открывается, и экран, куда она попала, сам
    // показывает пересказ до места, где остановились.
    val remind: (Book) -> Unit = { book ->
        state.requestRecap(book.id)
        onOpen(book)
    }

    // Телефон и сервер узнают одну книгу по имени папки, без учёта регистра.
    val localByFolder = remember(books) {
        books.groupBy { ServerLibrary.folderKey(it.folderName) }.mapValues { it.value.first() }
    }
    val entries = remember(serverBooks, localByFolder) {
        serverBooks.map { ServerEntry(it, localByFolder[ServerLibrary.folderKey(it.folderName)]) }
    }
    /** Что о книге на телефоне знает сервер: метка под обложкой. null - сервера нет. */
    fun phonePlace(book: Book): String? {
        val idx = index ?: return null
        return when {
            idx.byFolder(book.folderName) == null -> "только на телефоне"
            book.streams -> "звук на сервере"
            else -> "есть на сервере"
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Слушалка")
                        val sub = when {
                            inServer && index != null -> booksWord(serverBooks.size, "в библиотеке")
                            !inServer && books.isNotEmpty() -> booksWord(books.size, "на полке")
                            else -> null
                        }
                        if (sub != null) {
                            Text(
                                sub,
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    // Каталог Флибусты: найти книгу и положить её на эту же полку.
                    IconButton(onClick = onCatalog) {
                        Icon(Icons.Default.Search, contentDescription = "Флибуста")
                    }
                    // Статистика: сколько, когда и как быстро. Значок рисуется,
                    // как и кнопки плеера: в базовом наборе иконок графика нет.
                    IconButton(onClick = onStats) { StatsGlyph() }
                    IconButton(onClick = onCloud) {
                        Icon(Glyphs.Cloud, contentDescription = "Облако")
                    }
                    // В библиотеке - свежее оглавление сервера, на телефоне - папка.
                    IconButton(onClick = {
                        if (inServer) app.scope.launch { app.server.refresh() } else state.rescan()
                    }) {
                        Icon(
                            Icons.Default.Refresh,
                            contentDescription = if (inServer) "Обновить библиотеку" else "Перечитать папку",
                        )
                    }
                    IconButton(onClick = onSettings) {
                        Icon(Icons.Default.Settings, contentDescription = "Настройки")
                    }
                },
            )
        },
        bottomBar = {
            when {
                busy != null -> BusyBar(busy!!)
                notice != null -> NoticeBar(notice!!) { state.dismissNotice() }
            }
        },
    ) { padding ->
        if (!prefs.loaded) return@Scaffold
        if (prefs.libraryUri.isBlank()) {
            Welcome(onPickTree, Modifier.padding(padding))
            return@Scaffold
        }

        // Последняя книга - наверх и крупно: в девяти случаях из десяти
        // приложение открывают, чтобы продолжить именно её. Она могла быть и
        // с сервера - ищется в обоих списках.
        val lastId = app.positions.lastBook()
        val last = remember(books, serverBooks, lastId) { state.bookById(lastId) }
        val shownBooks: List<Book> = if (inServer) entries.map { it.shown } else books
        val progress = remember(shownBooks, last, rev) {
            (shownBooks + listOfNotNull(last)).associate { it.id to progressOf(app, it) }
        }
        val counts = remember(progress, shownBooks) {
            Shelf.entries.associateWith { sh -> shownBooks.count { sh.holds(progress.getValue(it.id)) } }
        }
        val shown = remember(shownBooks, progress, shelf, last) {
            shownBooks.filter { it.id != last?.id && shelf.holds(progress.getValue(it.id)) }
                .sortedWith(shelfOrder(progress))
        }
        val entryOf = remember(entries) { entries.associateBy { it.shown.id } }

        Refreshable(
            enabled = inServer && !prefs.readerEink,
            refreshing = serverStatus == ServerLibrary.Status.Loading,
            onRefresh = { app.scope.launch { app.server.refresh() } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyVerticalGrid(
                columns = GridCells.Adaptive(TILE_MIN),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(18.dp),
            ) {
                // Новая версия - первой строкой: чтобы обновиться, не надо ничего
                // никуда закидывать, довольно одной кнопки.
                (update as? ru.zf.slushalka.update.Updater.Status.Ready)?.let { ready ->
                    item(key = "update", span = { GridItemSpan(maxLineSpan) }) {
                        UpdateCard(ready.update.versionName) {
                            scope.launch { app.updater.downloadAndInstall(ready.update) }
                        }
                    }
                }
                (update as? ru.zf.slushalka.update.Updater.Status.Downloading)?.let { d ->
                    item(key = "downloading", span = { GridItemSpan(maxLineSpan) }) {
                        Column(Modifier.fillMaxWidth().padding(vertical = 8.dp)) {
                            Text("Качаю новую версию: ${d.percent}%", style = MaterialTheme.typography.bodyMedium)
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(
                                progress = { d.percent / 100f },
                                modifier = Modifier.fillMaxWidth(),
                            )
                        }
                    }
                }
                // Книгу с сервера качают прямо с полки - передачу видно здесь же.
                transfer?.let { tr ->
                    item(key = "transfer", span = { GridItemSpan(maxLineSpan) }) { TransferCard(app, tr) }
                }
                if (last != null) {
                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                        ContinueCard(
                            app, last, progress.getValue(last.id), prefs.recapAfterHours,
                            onOpen = onOpen, onRemind = remind,
                        )
                    }
                }
                if (serverOn) {
                    item(key = "view", span = { GridItemSpan(maxLineSpan) }) {
                        ViewSwitch(
                            inServer = inServer,
                            phoneCount = books.size,
                            serverCount = index?.let { serverBooks.size },
                            eink = prefs.readerEink,
                        ) { v -> scope.launch { app.settings.setLibraryView(v) } }
                    }
                }
                if (inServer) {
                    serverNote(index, serverStatus)?.let { note ->
                        item(key = "server-note", span = { GridItemSpan(maxLineSpan) }) {
                            Column {
                                Text(
                                    note,
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = if (serverStatus is ServerLibrary.Status.Failed) MaterialTheme.colorScheme.error
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                )
                                if (index == null && serverStatus == ServerLibrary.Status.Loading) {
                                    Spacer(Modifier.height(8.dp))
                                    LinearProgressIndicator(Modifier.fillMaxWidth())
                                }
                                if (serverStatus == ServerLibrary.Status.Missing) {
                                    TextButton(onClick = onCloud) { Text("Книги облака") }
                                }
                            }
                        }
                    }
                }
                if (!inServer && books.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            "В выбранной папке книг не нашлось. Книга - это папка с mp3 внутри; " +
                                "текст (fb2 или epub) и обложку клади туда же. Или найди книгу " +
                                "во Флибусте - лупа сверху." +
                                if (serverOn) " Или возьми из библиотеки на сервере." else "",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                } else if (shownBooks.isNotEmpty()) {
                    item(key = "shelves", span = { GridItemSpan(maxLineSpan) }) {
                        ShelfChips(shelf, counts) { shelf = it }
                    }
                }
                items(shown, key = { it.id }) { book ->
                    val entry = if (inServer) entryOf[book.id] else null
                    BookTile(
                        app = app,
                        book = book,
                        progress = progress.getValue(book.id),
                        others = others[book.id].orEmpty(),
                        place = if (inServer) entry?.where?.label else phonePlace(book),
                        onClick = {
                            // С сервера своё: книга не на телефоне спрашивает, как её взять.
                            if (entry != null && entry.local == null) serverMenu = entry else onOpen(book)
                        },
                        onLongClick = { if (entry != null) serverMenu = entry else menuFor = book },
                    )
                }
                if (shownBooks.isNotEmpty() && shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            shelf.empty,
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
        }
    }

    menuFor?.let { book ->
        val onServer = index?.byFolder(book.folderName) != null
        BookMenu(
            book = book,
            progress = progressOf(app, book),
            place = phonePlace(book),
            onOpen = { menuFor = null; onOpen(book) },
            onRemind = { menuFor = null; remind(book) },
            onTalk = { menuFor = null; onTalk(book) },
            // Уже лежит на сервере - выгружать незачем: в корне сервер принял бы
            // её за новую и отправил в «_Исходники».
            onUpload = if (prefs.cloudReady && !onServer && !book.streams) {
                { menuFor = null; app.cloudBooks.upload(book, toRoot = index != null) }
            } else null,
            onDelete = if (book.onPhone) { { menuFor = null; deleteAsk = book } } else null,
            onClose = { menuFor = null },
        )
    }

    serverMenu?.let { entry ->
        val idx = index
        val sb = idx?.byFolder(entry.server.folderName)
        ServerBookMenu(
            entry = entry,
            server = sb,
            progress = progressOf(app, entry.shown),
            busy = transfer?.finished == false,
            onOpen = entry.local?.let { local -> { serverMenu = null; onOpen(local) } },
            // Не скачивая: текст - во временный кэш, звук - потоком.
            onStream = if (entry.local == null) { { serverMenu = null; onOpen(entry.server) } } else null,
            onDownload = if (sb != null && idx != null && entry.where != Where.PHONE) {
                { serverMenu = null; app.cloudBooks.download(sb.folder, idx.booksDir, into = entry.local) }
            } else null,
            // Только текст - когда есть и текст, и звук, а на телефоне книги ещё нет.
            onTextOnly = if (sb != null && idx != null && entry.where == Where.SERVER &&
                sb.mainText != null && sb.audio.isNotEmpty()
            ) {
                { serverMenu = null; app.cloudBooks.downloadText(idx, sb) }
            } else null,
            onDelete = entry.local?.takeIf { it.onPhone }?.let { local -> { serverMenu = null; deleteAsk = local } },
            onClose = { serverMenu = null },
        )
    }

    deleteAsk?.let { book ->
        DeleteDialog(
            book = book,
            onServer = index?.byFolder(book.folderName) != null,
            onConfirm = { deleteAsk = null; state.deleteFromPhone(book) },
            onClose = { deleteAsk = null },
        )
    }

    offer?.let { o ->
        val book = state.bookById(o.bookId)
        AlertDialog(
            onDismissRequest = { state.declineResume() },
            title = { Text("Продолжить с другого устройства?") },
            text = {
                // У книги без записи место - страница, а не секунда.
                val where = if (book?.hasAudio == false && o.readChar >= 0) {
                    "стр. ${o.readChar / Settings.PAGE_CHARS + 1}"
                } else {
                    formatClock(o.absMs)
                }
                Text(
                    "«${book?.title ?: o.bookId}» — там остановились на $where " +
                        "(${formatAgo(o.at)}). Здесь место другое."
                )
            },
            confirmButton = {
                TextButton(onClick = { state.acceptResume() }) { Text("Перейти туда") }
            },
            dismissButton = {
                TextButton(onClick = { state.declineResume() }) { Text("Остаться здесь") }
            },
        )
    }
}

/** Где книга библиотеки: целиком на телефоне, только текст или только на сервере. */
private enum class Where(val label: String) {
    PHONE("на телефоне"),
    TEXT("текст на телефоне"),
    SERVER("на сервере"),
}

/** Книга сервера и её копия на телефоне, если есть. */
private class ServerEntry(val server: Book, val local: Book?) {
    /** Что показывать: своя копия главнее - у неё и обложка, и место, и ключ. */
    val shown: Book get() = local ?: server

    val where: Where
        get() = when {
            local == null -> Where.SERVER
            local.streams -> Where.TEXT
            !local.hasAudio && server.hasAudio -> Where.TEXT
            else -> Where.PHONE
        }
}

/** Что сказать над книгами библиотеки: грузится, нет оглавления, сервер не ответил. */
private fun serverNote(index: ServerLibrary.Index?, status: ServerLibrary.Status): String? = when {
    status == ServerLibrary.Status.Missing ->
        "На сервере нет оглавления index.json - это не домашняя библиотека. Книги этого облака - " +
            "на экране «Облако»; для библиотеки в настройках облака нужна папка «корень сервера»."
    status is ServerLibrary.Status.Failed ->
        if (index != null) "Сервер не ответил (${status.message}) - показываю оглавление, " +
            "взятое ${formatAgo(index.fetchedAt)}."
        else "Сервер не ответил: ${status.message}"
    index == null -> "Читаю оглавление библиотеки…"
    index.books.isEmpty() -> "В библиотеке пока пусто."
    else -> null
}

/**
 * Переключатель полки. Свой, а не сегмент Material: у того галочка въезжает
 * анимацией, а на электронной бумаге каждый кадр - мерцание.
 */
@Composable
private fun ViewSwitch(inServer: Boolean, phoneCount: Int, serverCount: Int?, eink: Boolean, onPick: (String) -> Unit) {
    val scheme = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(50)
    Row(
        Modifier
            .fillMaxWidth()
            .height(42.dp)
            .clip(shape)
            .border(1.dp, scheme.outline, shape),
    ) {
        listOf(
            Triple(Settings.VIEW_PHONE, "На телефоне · $phoneCount", !inServer),
            Triple(Settings.VIEW_SERVER, "В библиотеке" + (serverCount?.let { " · $it" } ?: ""), inServer),
        ).forEachIndexed { i, (id, label, on) ->
            if (i > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(scheme.outline))
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .background(if (on) scheme.secondaryContainer else Color.Transparent)
                    .then(
                        if (eink) Modifier.clickable(
                            interactionSource = remember { MutableInteractionSource() },
                            indication = null,
                        ) { onPick(id) } else Modifier.clickable { onPick(id) }
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.labelLarge,
                    color = if (on) scheme.onSecondaryContainer else scheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 8.dp),
                )
            }
        }
    }
}

/** Потянуть вниз - обновить. Где не нужно (телефонная полка, e-ink), просто коробка. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Refreshable(
    enabled: Boolean,
    refreshing: Boolean,
    onRefresh: () -> Unit,
    modifier: Modifier,
    content: @Composable () -> Unit,
) {
    if (enabled) {
        androidx.compose.material3.pulltorefresh.PullToRefreshBox(
            isRefreshing = refreshing,
            onRefresh = onRefresh,
            modifier = modifier,
        ) { content() }
    } else {
        Box(modifier) { content() }
    }
}

@Composable
private fun NoticeBar(text: String, onClose: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 4.dp, top = 4.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        TextButton(onClick = onClose) { Text("OK") }
    }
}

/** Самая узкая плитка: на телефоне выходит три в ряд, на раскрытом - пять-шесть. */
private val TILE_MIN = 104.dp

/**
 * Где книга по пути: сколько пройдено (звуком или глазами), когда трогали,
 * есть ли что напоминать.
 */
private data class Progress(
    val share: Float,
    val started: Boolean,
    val done: Boolean,
    val touchedAt: Long,
    /** Есть текст и уже пройдено достаточно, чтобы пересказ имел смысл. */
    val canRemind: Boolean,
)

private fun progressOf(app: SlushalkaApp, book: Book): Progress {
    val st = app.state.stateOf(book.id)
    val share = when {
        book.hasAudio && book.totalMs > 0 -> (st.absMs.toFloat() / book.totalMs).coerceIn(0f, 1f)
        else -> st.readShare
    }
    // Дочитанной книга без записи считается у последних двух процентов:
    // последняя страница - часто примечания и выходные данные, их не листают.
    val done = st.finished || share >= 0.98f
    val started = !done && (st.absMs > 60_000 || st.readChar > Settings.PAGE_CHARS || share > 0.01f)
    return Progress(
        share = share,
        started = started,
        done = done,
        touchedAt = st.updatedAt,
        canRemind = book.hasText && (st.absMs > 5 * 60_000 || st.readChar > 3 * Settings.PAGE_CHARS),
    )
}

/** Полки: всё, в процессе, не начатые, пройденные. */
private enum class Shelf(val label: String, val empty: String) {
    ALL("Все", ""),
    NOW("В процессе", "Ничего не начато - самое время."),
    NEW("Новые", "Непочатых книг нет. Лупа сверху - Флибуста."),
    DONE("Прочитано", "Пока ничего не дочитано до конца.");

    fun holds(p: Progress): Boolean = when (this) {
        ALL -> true
        NOW -> p.started
        NEW -> !p.started && !p.done
        DONE -> p.done
    }
}

/** Начатые - по свежести, за ними новые по названию, в конце пройденные. */
private fun shelfOrder(progress: Map<String, Progress>) = compareBy<Book>(
    { b -> progress.getValue(b.id).let { if (it.started) 0 else if (!it.done) 1 else 2 } },
    { b -> progress.getValue(b.id).let { if (it.started || it.done) -it.touchedAt else 0L } },
    { b -> b.title.lowercase() },
)

private fun booksWord(n: Int, where: String): String {
    val tail = n % 100
    val one = n % 10
    val word = when {
        tail in 11..14 -> "книг"
        one == 1 -> "книга"
        one in 2..4 -> "книги"
        else -> "книг"
    }
    return "$n $word $where"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ShelfChips(shelf: Shelf, counts: Map<Shelf, Int>, onPick: (Shelf) -> Unit) {
    Row(
        Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Shelf.entries.forEach { sh ->
            val n = counts[sh] ?: 0
            // Пустые полки, кроме «Все», не показываем: чип без книг - шум.
            if (sh != Shelf.ALL && n == 0 && sh != shelf) return@forEach
            FilterChip(
                selected = sh == shelf,
                onClick = { onPick(sh) },
                label = { Text("${sh.label} · $n") },
            )
        }
    }
}

@Composable
private fun Welcome(onPickTree: () -> Unit, modifier: Modifier = Modifier) {
    Box(modifier.fillMaxSize().padding(28.dp), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("Где книги?", style = MaterialTheme.typography.headlineMedium)
            Spacer(Modifier.height(12.dp))
            Text(
                "Выбери папку на телефоне, в которой будут лежать книги. Удобнее всего завести " +
                    "папку Books в Downloads: туда же будут ложиться книги из Флибусты, и всё " +
                    "видно в одном месте. Каждая книга - своя папка: mp3 внутри, рядом обложка " +
                    "и текст в fb2 или epub.\n\n" +
                    "Всё читается прямо оттуда, ничего никуда не копируется.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(20.dp))
            TextButton(onClick = onPickTree) { Text("Выбрать папку") }
        }
    }
}

@Composable
private fun BusyBar(text: String) {
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp)) {
        Text(text, style = MaterialTheme.typography.labelMedium)
        Spacer(Modifier.height(6.dp))
        LinearProgressIndicator(Modifier.fillMaxWidth())
    }
}

/**
 * Продолжить: крупная обложка, где остановился, сколько осталось и давно ли
 * открывал. Рядом с книгой - «Напомнить»: после перерыва пересказ до места,
 * где остановился, - тем же листом, что в читалке. Открывает книгу на своём
 * месте, но не заводит: пуск - рукой, как у любой другой.
 */
@Composable
private fun ContinueCard(
    app: SlushalkaApp,
    book: Book,
    progress: Progress,
    recapAfterHours: Int,
    onOpen: (Book) -> Unit,
    onRemind: (Book) -> Unit,
) {
    val st = app.state.stateOf(book.id)
    val left = (book.totalMs - st.absMs).coerceAtLeast(0)
    // Давно не открывал - напоминание выходит вперёд, а не прячется в меню.
    val away = st.updatedAt > 0 && System.currentTimeMillis() - st.updatedAt > recapAfterHours * 3600_000L
    Card(
        onClick = { onOpen(book) },
        shape = RoundedCornerShape(22.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
            CoverTile(
                app, book,
                Modifier
                    .width(104.dp)
                    .aspectRatio(COVER_RATIO)
                    .shadow(10.dp, RoundedCornerShape(10.dp))
                    .clip(RoundedCornerShape(10.dp)),
            )
            Spacer(Modifier.width(16.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    if (book.hasAudio) "ПРОДОЛЖИТЬ СЛУШАТЬ" else "ПРОДОЛЖИТЬ ЧИТАТЬ",
                    style = MaterialTheme.typography.labelSmall,
                )
                Spacer(Modifier.height(4.dp))
                Text(
                    book.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
                if (book.author.isNotBlank()) {
                    Text(
                        book.author,
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = { progress.share },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(6.dp))
                Text(
                    when {
                        !book.hasAudio -> if (st.readChar > 0) {
                            "стр. ${st.readChar / Settings.PAGE_CHARS + 1}" +
                                if (progress.share > 0f) " · ${(progress.share * 100).toInt()}%" else ""
                        } else "текст, без звука"
                        book.totalMs > 0 ->
                            "${(progress.share * 100).toInt()}% · осталось ${formatLeft(left, st.speed.takeIf { it > 0 } ?: 1f)}"
                        else -> "длительности ещё не измерены"
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (st.updatedAt > 0) {
                    Text(
                        if (away) "не открывал ${formatAgo(st.updatedAt).removeSuffix(" назад")}"
                        else "открывал ${formatAgo(st.updatedAt)}",
                        style = MaterialTheme.typography.labelSmall,
                        color = if (away) MaterialTheme.colorScheme.tertiary
                        else MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Button(onClick = { onOpen(book) }) {
                        Text(if (book.hasAudio) "Слушать" else "Читать")
                    }
                    if (progress.canRemind) {
                        FilledTonalButton(onClick = { onRemind(book) }) {
                            Icon(Glyphs.History, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Напомнить")
                        }
                    }
                }
            }
        }
    }
}

/** Пропорция плитки - книжная, 2:3: на полке стоят книги, а не альбомы. */
private const val COVER_RATIO = 2f / 3f

/**
 * Книга на полке: обложка с полоской пройденного, название, автор и где
 * остановились (свои и чужие устройства). Долгое нажатие - меню книги.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    app: SlushalkaApp,
    book: Book,
    progress: Progress,
    others: List<AppState.OtherPlace>,
    /** Где книга: на телефоне, на сервере, текстом. null - сервера нет, и говорить не о чем. */
    place: String?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick),
    ) {
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(COVER_RATIO)
                .shadow(6.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp)),
        ) {
            CoverTile(app, book, Modifier.fillMaxSize())
            if (progress.started) {
                // Полоска по нижнему краю обложки: сколько пройдено, видно с
                // первого взгляда, не читая цифр.
                Box(
                    Modifier
                        .align(Alignment.BottomStart)
                        .fillMaxWidth()
                        .height(4.dp)
                        .background(Color.Black.copy(alpha = 0.35f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxHeight()
                            .fillMaxWidth(progress.share.coerceIn(0.02f, 1f))
                            .background(MaterialTheme.colorScheme.primary),
                    )
                }
            }
            if (progress.done) {
                Text(
                    "✓",
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(6.dp)
                        .clip(CircleShape)
                        .background(MaterialTheme.colorScheme.primary)
                        .padding(horizontal = 7.dp, vertical = 2.dp),
                )
            }
            if (!book.hasAudio) {
                // Книга без записи - только читается: пусть это видно на обложке.
                Text(
                    "текст",
                    color = Color.White,
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(6.dp)
                        .clip(RoundedCornerShape(6.dp))
                        .background(Color.Black.copy(alpha = 0.45f))
                        .padding(horizontal = 5.dp, vertical = 1.dp),
                )
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val mine = when {
            progress.done -> if (book.hasAudio) "дослушано" else "прочитано"
            progress.started -> "${(progress.share * 100).toInt().coerceAtLeast(1)}%"
            book.hasAudio && book.totalMs > 0 -> formatSpan(book.totalMs)
            !book.hasText -> "без текста"
            else -> ""
        }
        val theirs = others.joinToString(" · ") { o ->
            when {
                book.totalMs > 0 -> "${o.who} ${(o.absMs * 100 / book.totalMs).toInt()}%"
                o.readChar >= 0 -> "${o.who} стр. ${o.readChar / Settings.PAGE_CHARS + 1}"
                else -> o.who
            }
        }
        val line = listOf(mine, theirs).filter { it.isNotBlank() }.joinToString(" · ")
        if (line.isNotBlank()) {
            Text(
                line,
                style = MaterialTheme.typography.labelSmall,
                color = if (progress.started) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (place != null) {
            Text(
                place,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.8f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** Долгое нажатие по книге: открыть или напомнить, о чём там. */
@Composable
private fun BookMenu(
    book: Book,
    progress: Progress,
    /** Где книга относительно сервера; null - сервера нет. */
    place: String?,
    onOpen: () -> Unit,
    onRemind: () -> Unit,
    onTalk: () -> Unit,
    /** Выгрузить в облако; null - облако не настроено или книга там уже есть. */
    onUpload: (() -> Unit)?,
    /** Удалить папку книги с телефона; null - папки на телефоне нет. */
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(book.title, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium)
                Text(
                    when {
                        progress.done -> "Пройдена до конца."
                        progress.started -> "Пройдено ${(progress.share * 100).toInt()}%."
                        else -> "Ещё не начата."
                    } + (place?.let { " ${it.replaceFirstChar(Char::uppercase)}." } ?: ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (progress.canRemind) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onRemind, modifier = Modifier.fillMaxWidth()) {
                        Icon(Glyphs.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Напомнить, о чём там")
                    }
                }
                if (book.hasText && (progress.done || progress.started)) {
                    Spacer(Modifier.height(6.dp))
                    FilledTonalButton(onClick = onTalk, modifier = Modifier.fillMaxWidth()) {
                        Icon(Glyphs.Forum, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Поговорить о книге")
                    }
                }
                if (onUpload != null) {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = onUpload, modifier = Modifier.fillMaxWidth()) {
                        Icon(Glyphs.CloudUpload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Выгрузить в облако")
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Удалить с телефона", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onOpen) { Text("Открыть") } },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

/**
 * Книга библиотеки: где она, сколько весит и как её взять. Не на телефоне -
 * скачать; на телефоне текстом - докачать звук; на телефоне - открыть.
 */
@Composable
private fun ServerBookMenu(
    entry: ServerEntry,
    server: ServerLibrary.ServerBook?,
    progress: Progress,
    /** Идёт другая передача: вторая разом не начинается. */
    busy: Boolean,
    onOpen: (() -> Unit)?,
    /** Слушать и читать прямо с сервера; null - книга и так на телефоне. */
    onStream: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    /** Скачать текст, а звук слушать с сервера; null - не к этой книге. */
    onTextOnly: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val book = entry.shown
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(book.title, maxLines = 3, overflow = TextOverflow.Ellipsis) },
        text = {
            Column {
                if (book.author.isNotBlank()) Text(book.author, style = MaterialTheme.typography.bodyMedium)
                val facts = buildList {
                    if (server != null && server.audio.isNotEmpty()) {
                        add("звук ${formatSpan(server.audioMs.takeIf { it > 0 } ?: entry.server.totalMs)} · " +
                            formatBytes(server.audioBytes))
                    }
                    server?.mainText?.let { add("текст ${it.name.substringAfterLast('.', "").lowercase()} · ${formatBytes(it.size)}") }
                    if (server != null && server.audio.isEmpty()) add("без записи")
                }
                if (facts.isNotEmpty()) {
                    Text(
                        facts.joinToString("; "),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    when (entry.where) {
                        Where.PHONE -> "На телефоне целиком."
                        Where.TEXT -> "На телефоне только текст, звук играет с сервера."
                        Where.SERVER -> "Только на сервере."
                    } + when {
                        progress.done -> " Пройдена до конца."
                        progress.started -> " Пройдено ${(progress.share * 100).toInt()}%."
                        else -> ""
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (onStream != null) {
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = onStream, modifier = Modifier.fillMaxWidth()) {
                        Icon(
                            if (book.hasAudio) Glyphs.Headphones else Glyphs.MenuBook,
                            contentDescription = null,
                            modifier = Modifier.size(18.dp),
                        )
                        Spacer(Modifier.width(6.dp))
                        Text(if (book.hasAudio) "Слушать с сервера" else "Читать с сервера")
                    }
                }
                if (onTextOnly != null && server != null) {
                    Spacer(Modifier.height(if (onStream != null) 6.dp else 10.dp))
                    FilledTonalButton(onClick = onTextOnly, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Glyphs.MenuBook, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Только текст · ${formatBytes(server.textBytes)}, звук потоком")
                    }
                }
                if (onDownload != null && server != null) {
                    Spacer(Modifier.height(if (onTextOnly != null || onStream != null) 6.dp else 10.dp))
                    FilledTonalButton(onClick = onDownload, enabled = !busy, modifier = Modifier.fillMaxWidth()) {
                        Icon(Glyphs.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text(
                            if (entry.where == Where.TEXT) "Докачать звук · ${formatBytes(server.audioBytes)}"
                            else "Скачать целиком · ${formatBytes(server.audioBytes + server.textBytes)}"
                        )
                    }
                    if (busy) {
                        Text(
                            "Идёт другая передача - эта начнётся, когда та кончится.",
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                if (onDelete != null) {
                    TextButton(onClick = onDelete, modifier = Modifier.fillMaxWidth()) {
                        Text("Удалить с телефона", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = {
            if (onOpen != null) TextButton(onClick = onOpen) { Text("Открыть") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
}

/**
 * Удалить с телефона. Книга есть на сервере - уходит только папка здесь;
 * нет - книга пропадает насовсем, и это сказано до кнопки.
 */
@Composable
private fun DeleteDialog(book: Book, onServer: Boolean, onConfirm: () -> Unit, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Удалить с телефона?") },
        text = {
            Text(
                if (onServer) {
                    "Папка «${book.folderName}» удалится с телефона. На сервере книга остаётся - её можно " +
                        "слушать оттуда или скачать снова. Место, вопросы и пометки не пропадут."
                } else {
                    "Этой книги на сервере нет: папка «${book.folderName}» удалится насовсем, вернуть её будет " +
                        "неоткуда. Место, вопросы и пометки останутся в приложении."
                }
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(if (onServer) "Удалить" else "Удалить насовсем", color = MaterialTheme.colorScheme.error)
            }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("Оставить") } },
    )
}

/** «Есть новая версия» - одна кнопка, дальше система сама поставит поверх. */
@Composable
private fun UpdateCard(versionName: String, onUpdate: () -> Unit) {
    Card(
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.tertiaryContainer,
            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
        ),
        modifier = Modifier.fillMaxWidth(),
    ) {
        Row(
            Modifier.padding(start = 14.dp, top = 6.dp, end = 6.dp, bottom = 6.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                "Есть новая версия $versionName",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.weight(1f),
            )
            TextButton(onClick = onUpdate) { Text("Обновить") }
        }
    }
}
