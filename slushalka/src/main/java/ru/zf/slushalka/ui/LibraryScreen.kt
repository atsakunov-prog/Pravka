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
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Close
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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import ru.zf.slushalka.data.ServerLibrary
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookMeta

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
    val rev by state.positionsRev.collectAsState()
    val prefs by state.prefs.collectAsState()
    val update by app.updater.status.collectAsState()
    val scope = rememberCoroutineScope()
    var shelf by rememberSaveable { mutableStateOf(Shelf.ALL) }
    var menuFor by remember { mutableStateOf<Book?>(null) }
    var serverMenu by remember { mutableStateOf<ServerEntry?>(null) }
    var deleteAsk by remember { mutableStateOf<Book?>(null) }
    // Серия, по которой отобрана полка: плашка с крестиком наверху. Помнится
    // по имени - ключ сравнения из него и выводится.
    var seriesPick by rememberSaveable { mutableStateOf<String?>(null) }
    val listView = prefs.shelfLayout == Settings.LAYOUT_LIST

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
    // Разбор - тем же порядком: книга открывается, и лист «Разбор» встаёт сам,
    // когда разбор прочитан - ему нужен текст книги, чтобы ссылки вели к месту.
    val razborOf: (Book) -> (() -> Unit)? = { book ->
        if (book.hasText && app.razbor.known(book, index?.byFolder(book.folderName))) {
            {
                state.requestRazbor(book.id)
                onOpen(book)
            }
        } else null
    }

    // Телефон и сервер узнают одну книгу по имени папки, без учёта регистра.
    val localByFolder = remember(books) {
        books.groupBy { ServerLibrary.folderKey(it.folderName) }.mapValues { it.value.first() }
    }
    val entries = remember(serverBooks, localByFolder) {
        serverBooks.map { ServerEntry(it, localByFolder[ServerLibrary.folderKey(it.folderName)]) }
    }
    val entryOf = remember(entries) { entries.associateBy { it.shown.id } }
    val serverByFolder = remember(serverBooks) {
        serverBooks.associateBy { ServerLibrary.folderKey(it.folderName) }
    }

    /** Книга и её пара на сервере: метки, «скачать», серия. null - на сервере такой нет. */
    fun entryFor(book: Book): ServerEntry? =
        entryOf[book.id]?.takeIf { inServer }
            ?: serverByFolder[ServerLibrary.folderKey(book.folderName)]?.let { ServerEntry(it, book.takeIf { b -> b.onPhone }) }

    /**
     * Серия книги: сперва из библиотеки - сервер разбирает серии сам и
     * называет их одинаково у всех книг; нет там - своя, из файла текста.
     */
    fun seriesOf(book: Book): BookMeta.Series? {
        serverByFolder[ServerLibrary.folderKey(book.folderName)]?.let { s ->
            s.series?.takeIf { it.isNotBlank() }?.let { return BookMeta.Series(it, s.seriesNum) }
        }
        return book.series?.takeIf { it.isNotBlank() }?.let { BookMeta.Series(it, book.seriesNum) }
    }

    /**
     * Фамилия автора для порядка «по автору». Из разметки fb2/epub, если она
     * там есть; иначе по имени: сервер раскладывает папки «Имя Фамилия», а
     * Флибуста и прежние папки на телефоне - «Фамилия Имя».
     */
    fun surnameOf(book: Book): String {
        book.authorKey?.takeIf { it.isNotBlank() }?.let { return it }
        val first = book.author.split(',', ';').first().trim()
        val words = first.split(Regex("\\s+")).filter { it.isNotBlank() }
        if (words.size <= 1) return first
        val serverStyle = !book.onPhone || serverByFolder.containsKey(ServerLibrary.folderKey(book.folderName))
        return if (serverStyle) words.last() else words.first()
    }

    /** Что можно скачать у книги и как: целиком, только текст, докачать звук. null - нечего. */
    fun downloadFor(book: Book): DownloadOffer? {
        val e = entryFor(book) ?: return null
        if (e.where == Where.PHONE) return null
        val idx = index ?: return null
        val sb = idx.byFolder(e.server.folderName) ?: return null
        val tr = transfer
        val mine = tr != null && !tr.finished && !tr.upload && ServerLibrary.folderKey(tr.name) == ServerLibrary.folderKey(sb.folder)
        val options = buildList {
            if (e.where == Where.TEXT) {
                add("Докачать звук · ${formatBytes(sb.audioBytes)}" to {
                    app.cloudBooks.download(sb.folder, idx.booksDir, into = e.local)
                })
            } else {
                add("Скачать целиком · ${formatBytes(sb.audioBytes + sb.textBytes)}" to {
                    app.cloudBooks.download(sb.folder, idx.booksDir)
                })
                if (sb.mainText != null && sb.audio.isNotEmpty()) {
                    add("Только текст · ${formatBytes(sb.textBytes)}, звук потоком" to {
                        app.cloudBooks.downloadText(idx, sb)
                    })
                }
            }
        }
        return DownloadOffer(options, progress = if (mine) tr!!.share else null, busy = tr != null && !tr.finished && !mine)
    }

    /** Что в книге есть: текст, звук или оба - своё или на сервере. */
    fun formatOf(book: Book): String {
        val server = entryFor(book)?.server
        val text = book.hasText || server?.hasText == true
        val audio = book.hasAudio || server?.hasAudio == true
        val span = (book.totalMs.takeIf { it > 0 } ?: server?.totalMs ?: 0L).takeIf { audio && it > 0 }
        return when {
            text && audio -> "текст + аудио"
            audio -> "только аудио"
            else -> "только текст"
        } + (span?.let { " · " + formatSpan(it) } ?: "")
    }

    /** Нажали на серию: полка - только она, по номерам; полки сбрасываются на «Все». */
    val pickSeries: (String) -> Unit = { name ->
        seriesPick = name
        shelf = Shelf.ALL
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
        // Отобрана серия - на полке только она, целиком: «Продолжить» не
        // выдёргивает из неё книгу наверх, порядок - по номерам.
        val seriesKey = seriesPick?.let(BookMeta::key)
        val base = remember(shownBooks, seriesKey, serverByFolder) {
            if (seriesKey == null) shownBooks
            else shownBooks.filter { b -> seriesOf(b)?.name?.let(BookMeta::key) == seriesKey }
        }
        val top = last.takeIf { seriesKey == null }
        // Свёрнутые группы - по ключу: серия или фамилия.
        var collapsed by rememberSaveable { mutableStateOf(listOf<String>()) }
        val counts = remember(progress, base) {
            Shelf.entries.associateWith { sh -> base.count { sh.holds(progress.getValue(it.id)) } }
        }
        // По сериям и по автору полка - группами с заголовками; в группе книга
        // стоит на своём месте, даже если она же наверху в «Продолжить».
        val grouped = seriesKey == null &&
            (prefs.shelfSort == Settings.SORT_SERIES || prefs.shelfSort == Settings.SORT_AUTHOR)
        val shown = remember(base, progress, shelf, top, seriesKey, prefs.shelfSort) {
            base.filter { (grouped || it.id != top?.id) && shelf.holds(progress.getValue(it.id)) }
                .sortedWith(
                    if (seriesKey != null) compareBy<Book>({ BookMeta.order(seriesOf(it)?.number) }).then(TitleOrder)
                    else shelfOrder(prefs.shelfSort, progress, ::seriesOf, ::surnameOf)
                )
        }

        val groups: List<ShelfGroup>? = remember(shown, grouped, prefs.shelfSort, progress) {
            if (!grouped) null
            else if (prefs.shelfSort == Settings.SORT_SERIES) groupBySeries(shown, progress, ::seriesOf)
            else groupByAuthor(shown, progress, ::surnameOf)
        }

        // Книга на полке - плиткой или строкой; одна и та же в простом порядке и в группах.
        val bookCell: @Composable (Book) -> Unit = { book ->
            val entry = if (inServer) entryOf[book.id] else null
            val place = if (inServer) entry?.where?.label else phonePlace(book)
            val onClick = {
                // С сервера своё: книга не на телефоне спрашивает, как её взять.
                if (entry != null && entry.local == null) serverMenu = entry else onOpen(book)
            }
            val onLongClick = { if (entry != null) serverMenu = entry else menuFor = book }
            if (listView) {
                BookRow(
                    app = app,
                    book = book,
                    progress = progress.getValue(book.id),
                    place = place,
                    format = formatOf(book),
                    series = seriesOf(book),
                    onSeries = pickSeries,
                    download = downloadFor(book),
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            } else {
                BookTile(
                    app = app,
                    book = book,
                    progress = progress.getValue(book.id),
                    place = place,
                    series = seriesOf(book),
                    onSeries = pickSeries,
                    download = downloadFor(book),
                    onClick = onClick,
                    onLongClick = onLongClick,
                )
            }
        }

        Refreshable(
            enabled = inServer && !prefs.readerEink,
            refreshing = serverStatus == ServerLibrary.Status.Loading,
            onRefresh = { app.scope.launch { app.server.refresh() } },
            modifier = Modifier.fillMaxSize().padding(padding),
        ) {
            LazyVerticalGrid(
                // Список - строками во всю ширину; на раскрытом экране - в две колонки.
                columns = GridCells.Adaptive(if (listView) ROW_MIN else TILE_MIN),
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(16.dp),
                horizontalArrangement = Arrangement.spacedBy(14.dp),
                verticalArrangement = Arrangement.spacedBy(if (listView) 10.dp else 18.dp),
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
                seriesPick?.let { name ->
                    item(key = "series", span = { GridItemSpan(maxLineSpan) }) {
                        SeriesBar(name, base.size) { seriesPick = null }
                    }
                }
                if (top != null) {
                    item(key = "continue", span = { GridItemSpan(maxLineSpan) }) {
                        ContinueCard(
                            app, top, progress.getValue(top.id), prefs.recapAfterHours,
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
                                val trouble = serverStatus == ServerLibrary.Status.Missing ||
                                    serverStatus is ServerLibrary.Status.Failed
                                if (trouble) {
                                    // Куда ходили - видно сразу: чаще всего дело в адресе.
                                    Text(
                                        "Облако: ${prefs.cloudUrl.substringAfter("://").trimEnd('/')}, папка " +
                                            (if (prefs.cloudAtRoot) "корень сервера" else "«${prefs.cloudDir}»"),
                                        style = MaterialTheme.typography.labelMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                                val home = prefs.cloudUrl.trimEnd('/').equals(Settings.HOME_LIBRARY_URL, ignoreCase = true) &&
                                    prefs.cloudAtRoot
                                Column {
                                    // Облако смотрит не туда - одним тапом на домашнюю
                                    // библиотеку: адрес и корень, логин и пароль прежние.
                                    if (trouble && !home) {
                                        TextButton(onClick = {
                                            app.scope.launch {
                                                app.settings.setCloudUrl(Settings.HOME_LIBRARY_URL)
                                                app.settings.setCloudDir(Settings.ROOT_DIR)
                                                app.server.forget()
                                                // Снимок настроек догоняет запись не мгновенно:
                                                // без ожидания запрос ушёл бы на прежний адрес.
                                                app.settings.flow.first {
                                                    it.cloudUrl == Settings.HOME_LIBRARY_URL && it.cloudAtRoot
                                                }
                                                app.server.refresh()
                                            }
                                        }) { Text("Подключить домашнюю библиотеку") }
                                    }
                                    if (trouble) TextButton(onClick = onSettings) { Text("Настройки") }
                                    if (serverStatus == ServerLibrary.Status.Missing) {
                                        TextButton(onClick = onCloud) { Text("Книги облака") }
                                    }
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
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.weight(1f)) { ShelfChips(shelf, counts) { shelf = it } }
                            // Группы - свернуть разом или развернуть: обзор серий одним экраном.
                            if (groups != null && groups.size > 1) {
                                val allFolded = groups.all { it.key in collapsed }
                                TextButton(onClick = {
                                    collapsed = if (allFolded) emptyList() else groups.map { it.key }
                                }) { Text(if (allFolded) "Развернуть" else "Свернуть") }
                            }
                            // Внутри серии порядок один - по номерам: выбор порядка там не нужен.
                            if (seriesKey == null) {
                                SortButton(prefs.shelfSort) { v -> scope.launch { app.settings.setShelfSort(v) } }
                            }
                            // Плитки или список - значок того, во что переключит.
                            IconButton(onClick = {
                                scope.launch {
                                    app.settings.setShelfLayout(if (listView) Settings.LAYOUT_GRID else Settings.LAYOUT_LIST)
                                }
                            }) {
                                Icon(
                                    if (listView) Glyphs.GridView else Glyphs.ViewList,
                                    contentDescription = if (listView) "Плитками" else "Списком",
                                )
                            }
                        }
                    }
                }
                val g = groups
                if (g == null) {
                    items(shown, key = { it.id }) { book -> bookCell(book) }
                } else {
                    g.forEach { group ->
                        val folded = group.key in collapsed
                        item(key = "g:" + group.key, span = { GridItemSpan(maxLineSpan) }) {
                            GroupHeader(group, folded) {
                                collapsed = if (folded) collapsed - group.key else collapsed + group.key
                            }
                        }
                        if (!folded) items(group.books, key = { it.id }) { book -> bookCell(book) }
                    }
                }
                if (shownBooks.isNotEmpty() && shown.isEmpty()) {
                    item(span = { GridItemSpan(maxLineSpan) }) {
                        Text(
                            if (seriesKey != null) {
                                if (inServer) "В библиотеке книг этой серии нет."
                                else "На телефоне книг этой серии нет - загляни в библиотеку."
                            } else shelf.empty,
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
            series = seriesOf(book),
            onSeries = { name -> menuFor = null; pickSeries(name) },
            onOpen = { menuFor = null; onOpen(book) },
            onRemind = { menuFor = null; remind(book) },
            onRazbor = razborOf(book)?.let { go -> { menuFor = null; go() } },
            onTalk = { menuFor = null; onTalk(book) },
            // Уже лежит на сервере - выгружать незачем: в корне сервер принял бы
            // её за новую и отправил в «_Исходники».
            onUpload = if (prefs.cloudReady && !onServer && !book.streams) {
                { menuFor = null; app.cloudBooks.upload(book, toRoot = index != null) }
            } else null,
            onDelete = if (book.onPhone) { { menuFor = null; deleteAsk = book } } else null,
            places = placesOf(app, book),
            onClose = { menuFor = null },
        )
    }

    serverMenu?.let { entry ->
        val idx = index
        val sb = idx?.byFolder(entry.server.folderName)
        ServerBookMenu(
            entry = entry,
            server = sb,
            series = seriesOf(entry.shown),
            onSeries = { name -> serverMenu = null; pickSeries(name) },
            progress = progressOf(app, entry.shown),
            busy = transfer?.finished == false,
            onOpen = entry.local?.let { local -> { serverMenu = null; onOpen(local) } },
            // Своя копия - её и открыть; нет - книга сервера, как «Слушать с сервера».
            onRazbor = razborOf(entry.local ?: entry.server)?.let { go -> { serverMenu = null; go() } },
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
            places = placesOf(app, entry.shown),
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
        if (index != null) "Библиотека не обновилась: ${status.message}. Показываю оглавление, " +
            "взятое ${formatAgo(index.fetchedAt)}."
        else "Библиотека не открылась: ${status.message}."
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

/** Строка списка не уже этого: на телефоне одна колонка, на раскрытом - две. */
private val ROW_MIN = 330.dp

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

/** Название для порядка: кавычки и скобки впереди не в счёт, «2» раньше «10». */
private val TitleOrder = Comparator<Book> { a, b ->
    ru.zf.slushalka.library.NaturalOrder.compare(
        a.title.trimStart { !it.isLetterOrDigit() },
        b.title.trimStart { !it.isLetterOrDigit() },
    )
}

/**
 * Порядок полки.
 * - Последние: что открывали, по свежести; нетронутые - за ними по названию.
 * - По автору: по фамилии, у одного автора - серии по номерам, потом
 *   остальное по названию; книги без автора - в конце.
 * - По названию.
 * - По сериям: серии по алфавиту, внутри по номерам; книги вне серий - в конце.
 */
private fun shelfOrder(
    sort: String,
    progress: Map<String, Progress>,
    series: (Book) -> BookMeta.Series?,
    surname: (Book) -> String,
): Comparator<Book> = when (sort) {
    Settings.SORT_AUTHOR -> compareBy<Book>(
        { surname(it).isBlank() },
        { surname(it).lowercase() },
        { it.author.lowercase() },
        { series(it) == null },
        { series(it)?.name?.let(BookMeta::key).orEmpty() },
        { BookMeta.order(series(it)?.number) },
    ).then(TitleOrder)
    Settings.SORT_TITLE -> TitleOrder
    Settings.SORT_SERIES -> compareBy<Book>(
        { series(it) == null },
        { series(it)?.name?.let(BookMeta::key).orEmpty() },
        { BookMeta.order(series(it)?.number) },
    ).then(TitleOrder)
    else -> compareBy<Book>(
        { progress.getValue(it.id).touchedAt <= 0L },
        { -progress.getValue(it.id).touchedAt },
    ).then(TitleOrder)
}

/** Группа полки: серия или автор, сколько в ней и что пройдено, книги по порядку. */
private class ShelfGroup(val key: String, val title: String, val note: String, val books: List<Book>)

/** «10 книг · прочитано 3 · в процессе 1». */
private fun groupNote(books: List<Book>, progress: Map<String, Progress>): String {
    val done = books.count { progress[it.id]?.done == true }
    val now = books.count { progress[it.id]?.started == true }
    return listOfNotNull(
        booksWord(books.size, "").trim(),
        if (done > 0) "прочитано $done" else null,
        if (now > 0) "в процессе $now" else null,
    ).joinToString(" · ")
}

/**
 * По сериям: серия - заголовок, под ним книги по номерам; серии по алфавиту,
 * книги вне серий - последней группой. Порядок внутри уже задан сортировкой.
 */
private fun groupBySeries(
    shown: List<Book>,
    progress: Map<String, Progress>,
    series: (Book) -> BookMeta.Series?,
): List<ShelfGroup> {
    val by = shown.groupBy { series(it)?.name?.let(BookMeta::key).orEmpty() }
    val named = by.filterKeys { it.isNotEmpty() }
        .map { (k, books) -> ShelfGroup("s:$k", series(books.first())!!.name, groupNote(books, progress), books) }
        .sortedWith { a, b -> ru.zf.slushalka.library.NaturalOrder.compare(a.title, b.title) }
    val loose = by[""]?.let { ShelfGroup("s:", "Без серии", groupNote(it, progress), it) }
    return named + listOfNotNull(loose)
}

/**
 * По автору: автор - заголовок (по фамилии, так что «Борис Акунин» и
 * «Акунин Борис» - один автор), под ним его серии по номерам и остальное;
 * книги без автора - последней группой.
 */
private fun groupByAuthor(
    shown: List<Book>,
    progress: Map<String, Progress>,
    surname: (Book) -> String,
): List<ShelfGroup> {
    val by = shown.groupBy { surname(it).trim().lowercase() }
    val named = by.filterKeys { it.isNotEmpty() }.map { (k, books) ->
        // Имя в заголовке - как оно чаще написано у его книг.
        val name = books.groupingBy { it.author.trim() }.eachCount().maxBy { it.value }.key
        ShelfGroup("a:$k", name, groupNote(books, progress), books)
    }.sortedWith { a, b -> ru.zf.slushalka.library.NaturalOrder.compare(a.key, b.key) }
    val loose = by[""]?.let { ShelfGroup("a:", "Автор не указан", groupNote(it, progress), it) }
    return named + listOfNotNull(loose)
}

/** Заголовок группы: стрелка, имя, сколько книг; нажатие сворачивает и разворачивает. */
@Composable
private fun GroupHeader(group: ShelfGroup, folded: Boolean, onToggle: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onToggle)
            .padding(top = 10.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(
            if (folded) Icons.AutoMirrored.Filled.KeyboardArrowRight else Icons.Default.KeyboardArrowDown,
            contentDescription = if (folded) "Развернуть" else "Свернуть",
        )
        Spacer(Modifier.width(6.dp))
        Column(Modifier.weight(1f)) {
            Text(group.title, style = MaterialTheme.typography.titleMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(group.note, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** Выбор порядка: значок и меню с галочкой у выбранного. */
@Composable
private fun SortButton(sort: String, onPick: (String) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        IconButton(onClick = { open = true }) {
            Icon(Glyphs.Sort, contentDescription = "Порядок: " + Settings.sortLabel(sort))
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            Settings.SORTS.forEach { v ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(Settings.sortLabel(v)) },
                    leadingIcon = {
                        if (v == sort) Icon(androidx.compose.material.icons.Icons.Default.Check, contentDescription = null)
                        else Spacer(Modifier.size(24.dp))
                    },
                    onClick = {
                        open = false
                        onPick(v)
                    },
                )
            }
        }
    }
}

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
 * Книга на полке: обложка с полоской пройденного, название, серия, автор и
 * где остановились (свои и чужие устройства). В углу обложки - «скачать»,
 * если книги на телефоне нет или она там только текстом. Долгое нажатие -
 * меню книги.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookTile(
    app: SlushalkaApp,
    book: Book,
    progress: Progress,
    /** Где книга: на телефоне, на сервере, текстом. null - сервера нет, и говорить не о чем. */
    place: String?,
    series: BookMeta.Series?,
    onSeries: (String) -> Unit,
    download: DownloadOffer?,
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
            if (progress.started) ProgressStrip(progress.share, Modifier.align(Alignment.BottomStart), 4.dp)
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
            // «Скачать» - в нижнем углу, над полоской пройденного: верхние
            // заняты «✓» и «текст».
            if (download != null) {
                DownloadButton(download, Modifier.align(Alignment.BottomEnd).padding(end = 5.dp, bottom = 9.dp), onCover = true)
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            book.title,
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
        if (series != null) SeriesLink(series, onSeries)
        if (book.author.isNotBlank()) {
            Text(
                book.author,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
        val line = statusLine(book, progress)
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

/**
 * Книга строкой списка: маленькая обложка, название, серия, автор, что в
 * книге есть (текст, звук или оба) и сколько пройдено, где она. «Скачать» -
 * справа, кнопкой во весь рост строки: в списке до угла обложки не дотянуться.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
private fun BookRow(
    app: SlushalkaApp,
    book: Book,
    progress: Progress,
    place: String?,
    /** «текст + аудио · 12 ч», «только текст» - что в книге есть. */
    format: String,
    series: BookMeta.Series?,
    onSeries: (String) -> Unit,
    download: DownloadOffer?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(onClick = onClick, onLongClick = onLongClick)
            .padding(vertical = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(
            Modifier
                .width(56.dp)
                .aspectRatio(COVER_RATIO)
                .shadow(3.dp, RoundedCornerShape(6.dp))
                .clip(RoundedCornerShape(6.dp)),
        ) {
            CoverTile(app, book, Modifier.fillMaxSize())
            if (progress.started) ProgressStrip(progress.share, Modifier.align(Alignment.BottomStart), 3.dp)
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                book.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (series != null) SeriesLink(series, onSeries)
            if (book.author.isNotBlank()) {
                Text(
                    book.author,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                format,
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            val line = listOf(
                if (progress.done) (if (book.hasAudio) "дослушано ✓" else "прочитано ✓")
                else statusLine(book, progress, withSpan = false),
                place.orEmpty(),
            ).filter { it.isNotBlank() }.joinToString(" · ")
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
        }
        if (download != null) DownloadButton(download, Modifier.padding(start = 4.dp), onCover = false)
    }
}

/** Полоска пройденного по нижнему краю обложки: видно с первого взгляда, не читая цифр. */
@Composable
private fun ProgressStrip(share: Float, modifier: Modifier, height: androidx.compose.ui.unit.Dp) {
    Box(
        modifier
            .fillMaxWidth()
            .height(height)
            .background(Color.Black.copy(alpha = 0.35f)),
    ) {
        Box(
            Modifier
                .fillMaxHeight()
                .fillMaxWidth(share.coerceIn(0.02f, 1f))
                .background(MaterialTheme.colorScheme.primary),
        )
    }
}

/** Свой процент, а нет его - длительность или «без текста». */
private fun statusLine(book: Book, progress: Progress, withSpan: Boolean = true): String = when {
    progress.done -> if (book.hasAudio) "дослушано" else "прочитано"
    progress.started -> "${(progress.share * 100).toInt().coerceAtLeast(1)}%"
    withSpan && book.hasAudio && book.totalMs > 0 -> formatSpan(book.totalMs)
    withSpan && !book.hasText -> "без текста"
    else -> ""
}

/** Серия под названием - ссылкой: нажал, и на полке вся серия по порядку. */
@Composable
private fun SeriesLink(series: BookMeta.Series, onSeries: (String) -> Unit) {
    Text(
        series.name + (series.number?.let { " · $it" } ?: ""),
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.primary,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .clip(RoundedCornerShape(4.dp))
            .clickable { onSeries(series.name) }
            .padding(vertical = 2.dp),
    )
}

/** Плашка отобранной серии: имя, сколько книг и крестик - снова все книги. */
@Composable
private fun SeriesBar(name: String, count: Int, onClear: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(MaterialTheme.colorScheme.secondaryContainer)
            .padding(start = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f).padding(vertical = 8.dp)) {
            Text(
                "СЕРИЯ",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
            Text(
                name,
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            Text(
                booksWord(count, "").trim(),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.7f),
            )
        }
        IconButton(onClick = onClear) {
            Icon(Icons.Default.Close, contentDescription = "Все книги", tint = MaterialTheme.colorScheme.onSecondaryContainer)
        }
    }
}

/**
 * Что можно скачать у книги: пункты меню «Скачать целиком», «Только текст»,
 * «Докачать звук»; [progress] - эта книга качается сейчас; [busy] - качается
 * другая, вторая передача разом не начнётся.
 */
private class DownloadOffer(
    val options: List<Pair<String, () -> Unit>>,
    val progress: Float?,
    val busy: Boolean,
)

/**
 * Кнопка «скачать»: кружок в углу обложки или значок в строке списка.
 * Нажатие - меню способов с объёмом каждого: гигабайт звука случайным тапом
 * не поедет. Идёт скачивание этой книги - вместо значка его доля.
 */
@Composable
private fun DownloadButton(offer: DownloadOffer, modifier: Modifier, onCover: Boolean) {
    var open by remember { mutableStateOf(false) }
    Box(modifier) {
        val size = if (onCover) 30.dp else 40.dp
        Box(
            Modifier
                .size(size)
                .clip(CircleShape)
                .background(if (onCover) Color.Black.copy(alpha = 0.55f) else Color.Transparent)
                .clickable(enabled = offer.progress == null) { open = true },
            contentAlignment = Alignment.Center,
        ) {
            val tint = if (onCover) Color.White else MaterialTheme.colorScheme.primary
            val p = offer.progress
            if (p != null) {
                androidx.compose.material3.CircularProgressIndicator(
                    progress = { p },
                    modifier = Modifier.size(size - 8.dp),
                    color = tint,
                    strokeWidth = 2.5.dp,
                )
            } else {
                Icon(
                    Glyphs.CloudDownload,
                    contentDescription = "Скачать",
                    tint = tint.copy(alpha = if (offer.busy) 0.5f else 1f),
                    modifier = Modifier.size(if (onCover) 18.dp else 24.dp),
                )
            }
        }
        androidx.compose.material3.DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (offer.busy) {
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text("Идёт другая передача - эта подождёт") },
                    onClick = { open = false },
                    enabled = false,
                )
            }
            offer.options.forEach { (label, action) ->
                androidx.compose.material3.DropdownMenuItem(
                    text = { Text(label) },
                    enabled = !offer.busy,
                    leadingIcon = { Icon(Glyphs.CloudDownload, contentDescription = null, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        action()
                    },
                )
            }
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
    series: BookMeta.Series?,
    onSeries: (String) -> Unit,
    onOpen: () -> Unit,
    onRemind: () -> Unit,
    /** Открыть разбор сервера; null - разбора у книги нет. */
    onRazbor: (() -> Unit)?,
    onTalk: () -> Unit,
    /** Выгрузить в облако; null - облако не настроено или книга там уже есть. */
    onUpload: (() -> Unit)?,
    /** Удалить папку книги с телефона; null - папки на телефоне нет. */
    onDelete: (() -> Unit)?,
    /** Свои места и места других - видно, кто где. */
    places: List<PlaceLine>,
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
                PlacesBlock(places)
                if (series != null) SeriesMenuLink(series, onSeries)
                if (progress.canRemind) {
                    Spacer(Modifier.height(10.dp))
                    FilledTonalButton(onClick = onRemind, modifier = Modifier.fillMaxWidth()) {
                        Icon(Glyphs.History, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(Modifier.width(6.dp))
                        Text("Напомнить, о чём там")
                    }
                }
                if (onRazbor != null) {
                    Spacer(Modifier.height(6.dp))
                    RazborButton(onRazbor)
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
    series: BookMeta.Series?,
    onSeries: (String) -> Unit,
    progress: Progress,
    /** Идёт другая передача: вторая разом не начинается. */
    busy: Boolean,
    onOpen: (() -> Unit)?,
    /** Открыть разбор сервера; null - разбора у книги нет. */
    onRazbor: (() -> Unit)?,
    /** Слушать и читать прямо с сервера; null - книга и так на телефоне. */
    onStream: (() -> Unit)?,
    onDownload: (() -> Unit)?,
    /** Скачать текст, а звук слушать с сервера; null - не к этой книге. */
    onTextOnly: (() -> Unit)?,
    onDelete: (() -> Unit)?,
    places: List<PlaceLine>,
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
                PlacesBlock(places)
                if (series != null) SeriesMenuLink(series, onSeries)
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
                if (onRazbor != null) {
                    Spacer(Modifier.height(6.dp))
                    RazborButton(onRazbor)
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

/** «Разбор книги» в карточке: сервер разобрал её - о книге, идеи, линзы, книга за 15 минут. */
@Composable
private fun RazborButton(onClick: () -> Unit) {
    FilledTonalButton(onClick = onClick, modifier = Modifier.fillMaxWidth()) {
        Icon(Glyphs.Lightbulb, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Разбор книги")
    }
}

/** Строка «где я» в меню книги: что и когда. */
private data class PlaceLine(val text: String)

/**
 * Свои места в книге: где слушал и где читал, каждое со своим временем, - чтобы
 * было видно, что место не сбилось. Только свои: места у каждого личные.
 */
private fun placesOf(app: SlushalkaApp, book: Book): List<PlaceLine> {
    val st = app.state.stateOf(book.id)
    return listOfNotNull(
        if (st.listenAt > 0 && book.hasAudio) {
            "Слушал до ${formatClock(st.absMs)}" +
                (if (book.totalMs > 0) " (${(st.absMs * 100 / book.totalMs).toInt()}%)" else "") +
                ", ${formatAgo(st.listenAt)}"
        } else null,
        if (st.readChar >= 0 && st.readAt > 0) {
            "Читал стр. ${st.readChar / Settings.PAGE_CHARS + 1}" +
                (if (st.textChars > 0) " (${(st.readChar * 100L / st.textChars).toInt()}%)" else "") +
                ", ${formatAgo(st.readAt)}"
        } else null,
    ).map(::PlaceLine)
}

@Composable
private fun PlacesBlock(places: List<PlaceLine>) {
    if (places.isEmpty()) return
    Spacer(Modifier.height(8.dp))
    places.forEach { p ->
        Text(
            p.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Серия в меню книги: «Серия «Дюна», книга 2 - вся серия». */
@Composable
private fun SeriesMenuLink(series: BookMeta.Series, onSeries: (String) -> Unit) {
    TextButton(onClick = { onSeries(series.name) }, modifier = Modifier.fillMaxWidth()) {
        Text(
            "Серия «${series.name}»" + (series.number?.let { ", книга $it" } ?: "") + " - вся серия",
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
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
