package ru.zf.slushalka.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.flow.distinctUntilChanged
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.catalog.CatalogState
import ru.zf.slushalka.catalog.OpdsEntry
import ru.zf.slushalka.library.Book

/**
 * Каталог Флибусты: разделы, поиск, книга крупно и кнопка «Скачать».
 *
 * Стопка лент живёт в [CatalogState], а не в экране: ушёл в настройки сменить
 * адрес - вернулся туда же, где был. «Назад» снимает верхнюю ленту, с корня -
 * закрывает экран.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CatalogScreen(
    app: SlushalkaApp,
    onClose: () -> Unit,
    onOpenBook: (Book) -> Unit,
) {
    val catalog = app.catalog
    val stack by catalog.stack.collectAsState()
    val download by catalog.download.collectAsState()
    val prefs by app.state.prefs.collectAsState()
    // Библиотека нужна, чтобы отметить уже скачанное; после закачки она меняется.
    val books by app.state.books.collectAsState()
    val focus = LocalFocusManager.current

    LaunchedEffect(Unit) { catalog.openRoot() }

    val page = stack.lastOrNull()
    var query by remember { mutableStateOf("") }
    var selected by remember { mutableStateOf<OpdsEntry?>(null) }

    fun goBack() {
        if (!catalog.back()) onClose()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        page?.title ?: "Флибуста",
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.titleMedium,
                    )
                },
                navigationIcon = {
                    IconButton(onClick = ::goBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Назад")
                    }
                },
                actions = {
                    IconButton(onClick = { catalog.retry() }) {
                        Icon(Icons.Default.Refresh, contentDescription = "Перечитать")
                    }
                },
            )
        },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                placeholder = { Text("Автор или название") },
                leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
                trailingIcon = {
                    if (query.isNotBlank()) {
                        IconButton(onClick = { query = "" }) {
                            Icon(Icons.Default.Close, contentDescription = "Очистить")
                        }
                    }
                },
                singleLine = true,
                keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
                keyboardActions = KeyboardActions(onSearch = {
                    focus.clearFocus()
                    catalog.search(query)
                }),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp),
            )

            if (prefs.libraryUri.isBlank()) {
                Note(
                    "Папка с книгами ещё не выбрана - скачивать пока некуда. " +
                        "Смотреть каталог можно, выбрать папку - в настройках.",
                )
            }

            when {
                page == null || (page.loading && page.entries.isEmpty()) -> {
                    LinearProgressIndicator(Modifier.fillMaxWidth().padding(horizontal = 16.dp))
                    Spacer(Modifier.height(20.dp))
                    Text(
                        "Открываю каталог…",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 16.dp),
                    )
                }
                page.error != null && page.entries.isEmpty() -> Trouble(page.error, prefs.flibustaUrl) {
                    catalog.retry()
                }
                page.entries.isEmpty() -> Text(
                    "Ничего не нашлось",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp),
                )
                else -> FeedList(
                    app = app,
                    page = page,
                    books = books,
                    onNav = { e ->
                        e.feedLink?.let { link -> catalog.open(e.title, link.href) }
                    },
                    onBook = { selected = it },
                    onMore = { catalog.loadMore() },
                    onRetry = { if (page.next != null) catalog.loadMore() else catalog.retry() },
                )
            }
        }
    }

    selected?.let { entry ->
        BookSheet(
            app = app,
            entry = entry,
            download = download,
            inLibrary = remember(entry.key, books) { catalog.inLibrary(entry) },
            onFollow = { title, href ->
                selected = null
                catalog.open(title, href)
            },
            onOpenBook = { book ->
                selected = null
                catalog.dismissDownload()
                onOpenBook(book)
            },
            onDismiss = {
                selected = null
                catalog.dismissDownload()
            },
        )
    }
}

@Composable
private fun FeedList(
    app: SlushalkaApp,
    page: CatalogState.Page,
    books: List<Book>,
    onNav: (OpdsEntry) -> Unit,
    onBook: (OpdsEntry) -> Unit,
    onMore: () -> Unit,
    onRetry: () -> Unit,
) {
    val listState = rememberLazyListState()
    val entries = page.entries

    // Следующая страница подтягивается сама, когда докрутили до низа: листать
    // «новинки» по двадцать штук через кнопку было бы утомительно.
    LaunchedEffect(listState, entries.size, page.next) {
        if (page.next == null) return@LaunchedEffect
        snapshotFlow { listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: -1 }
            .distinctUntilChanged()
            .collect { last -> if (last >= entries.size - 3) onMore() }
    }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 4.dp),
    ) {
        itemsIndexed(entries, key = { i, e -> "$i:${e.key}" }) { i, entry ->
            if (page.authorCount > 0 && i == 0) SectionLabel("Авторы")
            if (page.authorCount > 0 && i == page.authorCount) SectionLabel("Книги")
            when {
                entry.isBook -> BookRow(app, entry, inLibrary = books.any { b -> sameBook(b, entry) }) { onBook(entry) }
                // «Об авторе» - запись без ссылки: читать здесь, идти некуда.
                entry.feedLink == null -> InfoRow(app, entry)
                else -> NavRow(app, entry) { onNav(entry) }
            }
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f))
        }
        item(key = "tail") {
            if (page.authorsUrl != null && entries.size == page.authorCount && page.error == null) {
                SectionLabel("Книги")
                Text(
                    "Книг с таким названием не нашлось",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(vertical = 6.dp),
                )
            }
            when {
                page.error != null -> Column(Modifier.fillMaxWidth().padding(vertical = 10.dp)) {
                    Text(page.error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
                    TextButton(onClick = onRetry) { Text("Повторить") }
                }
                page.loadingMore -> LinearProgressIndicator(Modifier.fillMaxWidth().padding(vertical = 14.dp))
                page.next != null -> TextButton(onClick = onMore, modifier = Modifier.fillMaxWidth()) {
                    Text("Показать ещё")
                }
            }
            Spacer(Modifier.height(24.dp))
        }
    }
}

/** Та же книга, что и запись каталога: по имени папки, в которую её положили. */
private fun sameBook(book: Book, entry: OpdsEntry): Boolean =
    book.textDocId != null && book.id.endsWith("/${CatalogState.FOLDER}/${CatalogState.folderName(entry)}")

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(top = 14.dp, bottom = 4.dp),
    )
}

@Composable
private fun Note(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp),
    )
}

/** Каталог не открылся: сказать почему и что с этим делать, а не показать пустой экран. */
@Composable
private fun Trouble(error: String, url: String, onRetry: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(16.dp)) {
        Text(error, style = MaterialTheme.typography.bodyLarge)
        Spacer(Modifier.height(8.dp))
        Text(
            "Адрес каталога: $url. Если сайт не открывается в этой сети, включи VPN или впиши " +
                "адрес зеркала в настройках, раздел «Флибуста».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(10.dp))
        Button(onClick = onRetry) { Text("Повторить") }
    }
}

@Composable
private fun NavRow(app: SlushalkaApp, entry: OpdsEntry, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // У авторов в поиске бывает портрет - показываем кружком, как в
        // адресной книге. У разделов картинки нет, и место под неё не резервируется.
        entry.cover?.let { href ->
            CatalogCover(app, href, entry.title, Modifier.size(44.dp).clip(MaterialTheme.shapes.extraLarge))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            val hint = entry.summary.lineSequence().firstOrNull { it.isNotBlank() }.orEmpty()
            if (hint.isNotBlank()) {
                Text(
                    hint,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Text("›", color = MaterialTheme.colorScheme.onSurfaceVariant, fontSize = 22.sp)
    }
}

/** Запись, по которой некуда идти, - например, биография автора: показывается целиком. */
@Composable
private fun InfoRow(app: SlushalkaApp, entry: OpdsEntry) {
    Row(Modifier.fillMaxWidth().padding(vertical = 12.dp)) {
        entry.cover?.let { href ->
            CatalogCover(app, href, entry.title, Modifier.size(64.dp, 80.dp).clip(MaterialTheme.shapes.small))
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(entry.title, style = MaterialTheme.typography.titleMedium)
            if (entry.summary.isNotBlank()) {
                Text(
                    entry.summary,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 10,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun BookRow(app: SlushalkaApp, entry: OpdsEntry, inLibrary: Boolean, onClick: () -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        CatalogCover(app, entry.cover, entry.title, Modifier.size(56.dp, 80.dp).clip(MaterialTheme.shapes.small))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(
                entry.title,
                style = MaterialTheme.typography.titleMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            if (entry.authorLine.isNotBlank()) {
                Text(
                    entry.authorLine,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Text(
                metaLine(entry),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            if (inLibrary) {
                Text(
                    "в библиотеке",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.primary,
                )
            }
        }
    }
}

/** «fb2 · epub · 408 Kb · 2000 · Приключения Эраста Фандорина #1». */
private fun metaLine(entry: OpdsEntry): String = buildList {
    val formats = entry.acquisitions.map { it.format }.filter { it in READABLE }.distinct()
    if (formats.isNotEmpty()) add(formats.joinToString(" · "))
    if (entry.size.isNotBlank()) add(entry.size)
    if (entry.issued.isNotBlank()) add(entry.issued)
    if (entry.series.isNotBlank()) add(entry.series)
}.joinToString(" · ")

private val READABLE = setOf("fb2", "epub")

/**
 * Книга крупно: обложка, аннотация, откуда ещё можно пойти (автор, серия) и
 * кнопка скачивания с ходом закачки. Пока книга качается, лист закрывать
 * можно - закачка живёт в [CatalogState] и доедет сама.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun BookSheet(
    app: SlushalkaApp,
    entry: OpdsEntry,
    download: CatalogState.Download,
    inLibrary: Book?,
    onFollow: (title: String, href: String) -> Unit,
    onOpenBook: (Book) -> Unit,
    onDismiss: () -> Unit,
) {
    val catalog = app.catalog
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(onDismissRequest = onDismiss, sheetState = sheet) {
        Column(
            Modifier
                .fillMaxWidth()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(bottom = 32.dp),
        ) {
            Row {
                CatalogCover(app, entry.cover, entry.title, Modifier.size(104.dp, 150.dp).clip(MaterialTheme.shapes.medium))
                Spacer(Modifier.width(16.dp))
                Column(Modifier.weight(1f)) {
                    Text(entry.title, style = MaterialTheme.typography.titleLarge)
                    if (entry.authorLine.isNotBlank()) {
                        Spacer(Modifier.height(4.dp))
                        Text(entry.authorLine, style = MaterialTheme.typography.bodyLarge)
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        metaLine(entry),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    if (entry.categories.isNotEmpty()) {
                        Text(
                            entry.categories.take(3).joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }

            Spacer(Modifier.height(14.dp))
            Actions(entry, download, inLibrary, catalog, onOpenBook)

            if (entry.summary.isNotBlank()) {
                Spacer(Modifier.height(14.dp))
                Text(entry.summary, style = MaterialTheme.typography.bodyMedium)
            }

            val related = entry.related.filter { it.title.isNotBlank() }
            if (related.isNotEmpty()) {
                Spacer(Modifier.height(10.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    related.forEach { link ->
                        TextButton(onClick = { onFollow(link.title, link.href) }) { Text(link.title) }
                    }
                }
            }
            Spacer(Modifier.height(8.dp))
            Text(
                "Книга ляжет в папку «${CatalogState.FOLDER}» внутри библиотеки - для чтения " +
                    "и вопросов, звука у неё нет. Появится начитка - положи её в ту же папку.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun Actions(
    entry: OpdsEntry,
    download: CatalogState.Download,
    inLibrary: Book?,
    catalog: CatalogState,
    onOpenBook: (Book) -> Unit,
) {
    val mine = when (download) {
        is CatalogState.Download.Running -> download.entryKey == entry.key
        is CatalogState.Download.Done -> download.entryKey == entry.key
        is CatalogState.Download.Failed -> download.entryKey == entry.key
        CatalogState.Download.Idle -> false
    }
    when {
        mine && download is CatalogState.Download.Running -> {
            Text(
                "${download.step} ${if (download.percent in 1..99) "${download.percent}%" else ""}",
                style = MaterialTheme.typography.bodyMedium,
            )
            Spacer(Modifier.height(6.dp))
            // Конвертер epub отдаёт файл без длины - тогда полоска бежит, а не стоит на нуле.
            if (download.percent in 1..99) {
                LinearProgressIndicator(
                    progress = { download.percent / 100f },
                    modifier = Modifier.fillMaxWidth(),
                )
            } else {
                LinearProgressIndicator(Modifier.fillMaxWidth())
            }
            TextButton(onClick = { catalog.cancelDownload() }) { Text("Отменить") }
        }
        mine && download is CatalogState.Download.Done -> {
            val book = download.book
            Text(
                if (book != null) "Готово: книга в библиотеке" else "Файл лежит в папке, но на полке пока не виден - перечитай папку",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.primary,
            )
            if (book != null) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = { onOpenBook(book) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                    Text("Читать", style = MaterialTheme.typography.titleMedium)
                }
            }
        }
        mine && download is CatalogState.Download.Failed -> {
            Text(download.message, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.error)
            Spacer(Modifier.height(8.dp))
            DownloadButtons(entry, enabled = true) { catalog.download(entry, it) }
        }
        inLibrary != null -> {
            Text("Уже в библиотеке", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(8.dp))
            Button(onClick = { onOpenBook(inLibrary) }, modifier = Modifier.fillMaxWidth().height(50.dp)) {
                Text("Читать", style = MaterialTheme.typography.titleMedium)
            }
        }
        else -> {
            val busyElsewhere = download is CatalogState.Download.Running
            if (busyElsewhere) {
                Text(
                    "Сейчас качается другая книга - эта подождёт",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(6.dp))
            }
            DownloadButtons(entry, enabled = !busyElsewhere) { catalog.download(entry, it) }
        }
    }
}

/** fb2 - главной кнопкой: он разбирается точнее epub (главы размечены тегами). */
@Composable
private fun DownloadButtons(entry: OpdsEntry, enabled: Boolean, onDownload: (String) -> Unit) {
    val hasFb2 = entry.acquisition("fb2") != null
    val hasEpub = entry.acquisition("epub") != null
    if (!hasFb2 && !hasEpub) {
        Text(
            "У этой книги нет ни fb2, ни epub - читалке нечего открыть",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        return
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        if (hasFb2) {
            Button(
                onClick = { onDownload("fb2") },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(50.dp),
            ) { Text("Скачать fb2", style = MaterialTheme.typography.titleMedium) }
        }
        if (hasEpub) {
            OutlinedButton(
                onClick = { onDownload("epub") },
                enabled = enabled,
                modifier = Modifier.weight(1f).height(50.dp),
            ) { Text(if (hasFb2) "epub" else "Скачать epub", style = MaterialTheme.typography.titleMedium) }
        }
    }
}

/** Обложка из каталога: грузится по сети один раз, дальше из памяти. Нет её - плашка с названием. */
@Composable
fun CatalogCover(app: SlushalkaApp, href: String?, title: String, modifier: Modifier = Modifier) {
    val client = app.catalog.client
    val bitmap by produceState<Bitmap?>(client.cachedCover(href), href) {
        value = client.cachedCover(href) ?: href?.let { runCatching { client.cover(it) }.getOrNull() }
    }
    Box(modifier.background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        val bmp = bitmap
        if (bmp != null) {
            Image(
                bitmap = bmp.asImageBitmap(),
                contentDescription = title,
                modifier = Modifier.fillMaxSize(),
                contentScale = ContentScale.Crop,
            )
        } else {
            Text(
                title.take(20),
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 9.sp, letterSpacing = 0.sp),
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(4.dp),
            )
        }
    }
}
