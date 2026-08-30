package ru.zf.slushalka.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.BookText

/** Цвета читалки живут отдельно от темы приложения: их переключают по свету, а не по системе. */
data class ReaderPalette(val bg: Color, val fg: Color, val dim: Color)

fun readerPalette(theme: String, dark: Boolean): ReaderPalette = when (theme) {
    Settings.THEME_PAPER -> ReaderPalette(Color(0xFFFBF8F1), Color(0xFF17150F), Color(0xFF6E6659))
    Settings.THEME_SEPIA -> ReaderPalette(Color(0xFFF3E6CE), Color(0xFF43331C), Color(0xFF8A7550))
    Settings.THEME_GREY -> ReaderPalette(Color(0xFF2A2D33), Color(0xFFCBC8C1), Color(0xFF8B8880))
    Settings.THEME_BLACK -> ReaderPalette(Color(0xFF000000), Color(0xFFB6B3AC), Color(0xFF6E6B65))
    else -> if (dark) ReaderPalette(Color(0xFF000000), Color(0xFFB6B3AC), Color(0xFF6E6B65))
    else ReaderPalette(Color(0xFFFBF8F1), Color(0xFF17150F), Color(0xFF6E6659))
}

fun fontOf(name: String): FontFamily = when (name) {
    Settings.FONT_SANS -> FontFamily.SansSerif
    Settings.FONT_MONO -> FontFamily.Monospace
    else -> FontFamily.Serif
}

/**
 * Читалка.
 *
 * Место в книге одно и то же со слушанием: перешёл читать - открылось там, где
 * кончился звук; нажал «слушать отсюда» - запись встала туда, где остановились
 * глаза. Два способа листать - прокруткой и постранично - переключаются в
 * настройках вида.
 */
@Composable
fun ReaderScreen(
    app: SlushalkaApp,
    onBack: () -> Unit,
    onListen: () -> Unit,
    onAsk: (charOffset: Int) -> Unit,
) {
    val state = app.state
    val book by state.current.collectAsState()
    val text by state.text.collectAsState()
    val prefs by state.prefs.collectAsState()
    val busy by state.busy.collectAsState()
    val play by app.player.state.collectAsState()

    var bars by remember { mutableStateOf(true) }
    var showSettings by remember { mutableStateOf(false) }
    var showRecap by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf<File?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    var pressed by remember { mutableStateOf<Int?>(null) }
    var offset by remember { mutableIntStateOf(0) }
    var target by remember { mutableStateOf<Int?>(null) }

    val palette = readerPalette(prefs.readerTheme, isSystemInDarkTheme())

    val view = LocalView.current
    DisposableEffect(prefs.readerKeepAwake) {
        // Читают долго и не трогая экран - гаснуть посреди страницы ему незачем.
        view.keepScreenOn = prefs.readerKeepAwake
        onDispose { view.keepScreenOn = false }
    }

    val t = text
    val bk = book
    if (t == null || bk == null) {
        Box(
            Modifier.fillMaxSize().background(palette.bg),
            contentAlignment = Alignment.Center,
        ) {
            Text(
                if (bk == null) "Книга не выбрана"
                else "Текста книги нет или он ещё разбирается",
                color = palette.dim,
            )
        }
        return
    }

    val blocks = t.blocks
    val chapterStarts = remember(t) { t.chapters.map { it.start }.toHashSet() }
    val isHeading: (Block) -> Boolean = { it.picture == null && it.start in chapterStarts && it.text.length < 120 }

    fun styleFor(heading: Boolean) = TextStyle(
        fontFamily = fontOf(prefs.readerFont),
        fontSize = (if (heading) prefs.readerSize + 3 else prefs.readerSize).sp,
        lineHeight = (prefs.readerSize * prefs.readerLineHeight).sp,
        fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
        textAlign = when {
            heading -> TextAlign.Center
            prefs.readerJustify -> TextAlign.Justify
            else -> TextAlign.Start
        },
        color = palette.fg,
    )

    // Открываемся там, где кончился звук, и, если разрешено, уточняем место
    // расшифровкой последних секунд - уже после того, как страница показана.
    LaunchedEffect(t, bk.id) {
        val start = state.readingStart()
        offset = start.offset
        target = start.offset
        if (start.fromAudio && prefs.refineOnSwitch && app.recognizer.supported) {
            val exact = state.readingOffsetNow()
            if (kotlin.math.abs(exact - start.offset) > 300) {
                target = exact
                notice = "Место уточнено по звуку"
            }
        }
    }

    LaunchedEffect(offset) {
        // Место чтения пишется на диск, когда листание успокоилось.
        kotlinx.coroutines.delay(700)
        state.saveReadChar(offset)
    }
    DisposableEffect(Unit) { onDispose { state.saveReadChar(offset) } }

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2600)
            notice = null
        }
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        val onLong: (Int) -> Unit = { pressed = it }
        val onTapPicture: (File) -> Unit = { picture = it }
        if (prefs.readerPaged) {
            PagedBody(
                app = app, bookId = bk.id, blocks = blocks, palette = palette,
                margin = prefs.readerMargin, styleFor = ::styleFor, isHeading = isHeading,
                target = target, onTargetUsed = { target = null },
                onOffset = { offset = it }, onToggleBars = { bars = !bars },
                onPicture = onTapPicture, onLongPress = onLong,
            )
        } else {
            ScrollBody(
                app = app, bookId = bk.id, blocks = blocks, palette = palette,
                margin = prefs.readerMargin, styleFor = ::styleFor, isHeading = isHeading,
                target = target, onTargetUsed = { target = null },
                onOffset = { offset = it }, onToggleBars = { bars = !bars },
                onPicture = onTapPicture, onLongPress = onLong,
            )
        }

        // Картинка поблизости: карта или план держится под рукой ещё пару
        // страниц после того, как встретилась в тексте.
        val near = remember(offset, t) {
            t.picturesWithCaptions
                .filter { it.file.isNotBlank() }
                .minByOrNull { kotlin.math.abs(it.charOffset - offset) }
                ?.takeIf { kotlin.math.abs(it.charOffset - offset) <= 2 * BookText.PAGE_CHARS }
        }
        near?.let { pic ->
            val file = app.texts.pictureFile(bk.id, pic.file)
            PictureChip(file, palette, Modifier.align(Alignment.BottomEnd).padding(14.dp)) {
                picture = file
            }
        }

        AnimatedVisibility(
            visible = bars,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .background(palette.bg.copy(alpha = 0.96f))
                    .statusBarsPadding()
                    .padding(horizontal = 6.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                TextButton(onClick = onBack) { Text("‹ Плеер", color = palette.fg) }
                Text(
                    t.chapterAt(offset)?.title.orEmpty(),
                    color = palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                if (app.state.picturesOnDisk() > 0) {
                    TextButton(onClick = { showGallery = true }) {
                        Text("Картинки", color = palette.fg)
                    }
                }
                TextButton(onClick = { showSettings = true }) {
                    Text("Аа  Вид", color = palette.fg)
                }
            }
        }

        AnimatedVisibility(
            visible = bars,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .background(palette.bg.copy(alpha = 0.96f))
                    .navigationBarsPadding()
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(
                    "стр. ${t.pageOf(offset)} из ${t.pages}",
                    color = palette.dim,
                    fontSize = 12.sp,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    TextButton(onClick = {
                        state.listenFrom(offset)
                        onListen()
                    }) { Text("Слушать отсюда", color = palette.fg) }
                    TextButton(onClick = { showRecap = true }) { Text("Что там было", color = palette.fg) }
                    TextButton(onClick = { onAsk(offset) }) { Text("Спросить", color = palette.fg) }
                }
            }
        }

        (notice ?: busy)?.let { line ->
            Text(
                line,
                color = palette.bg,
                fontSize = 12.sp,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(bottom = 96.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.fg.copy(alpha = 0.88f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }

    if (showSettings) {
        ReaderSettingsDialog(
            app,
            onGallery = { showGallery = true },
            onClose = { showSettings = false },
        )
    }
    if (showGallery) {
        PictureGallery(app) { showGallery = false }
    }
    if (showRecap) {
        RecapSheet(
            app,
            cutoffChar = offset,
            absMs = state.alignment.value?.audioAt(offset) ?: 0L,
            onClose = { showRecap = false },
        )
    }
    picture?.let { file -> ImageViewer(file) { picture = null } }
    pressed?.let { at ->
        AlertDialog(
            onDismissRequest = { pressed = null },
            title = { Text("Это место") },
            text = {
                Text(
                    if (play.absMs > 0)
                        "«Я тут» скажет карте, что на ${formatClock(play.absMs)} записи " +
                            "читают это место - и переходы со звука станут точными."
                    else "Запись ещё не играла, сверять не с чем.",
                    style = MaterialTheme.typography.bodyMedium,
                )
            },
            confirmButton = {
                if (play.absMs > 0) {
                    TextButton(onClick = {
                        state.addAnchor(play.absMs, at)
                        notice = "Отметил: карта стала точнее"
                        pressed = null
                    }) { Text("Я тут") }
                }
            },
            dismissButton = {
                Row {
                    TextButton(onClick = {
                        state.listenFrom(at)
                        pressed = null
                        onListen()
                    }) { Text("Слушать отсюда") }
                    TextButton(onClick = { pressed = null }) { Text("Отмена") }
                }
            },
        )
    }
}

// ------------------------------------------------------------------ прокрутка

@Composable
private fun ScrollBody(
    app: SlushalkaApp,
    bookId: String,
    blocks: List<Block>,
    palette: ReaderPalette,
    margin: Int,
    styleFor: (Boolean) -> TextStyle,
    isHeading: (Block) -> Boolean,
    target: Int?,
    onTargetUsed: () -> Unit,
    onOffset: (Int) -> Unit,
    onToggleBars: () -> Unit,
    onPicture: (File) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(target) {
        val to = target ?: return@LaunchedEffect
        listState.scrollToItem(blocks.indexOfLast { it.start <= to }.coerceAtLeast(0))
        onTargetUsed()
    }
    LaunchedEffect(listState) {
        snapshotFlow { listState.firstVisibleItemIndex }
            .distinctUntilChanged()
            .collectLatest { index -> blocks.getOrNull(index)?.let { onOffset(it.start) } }
    }

    Box(
        Modifier
            .fillMaxSize()
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        val step = listState.layoutInfo.viewportSize.height * 0.88f
                        when {
                            pos.x < size.width * 0.28f -> scope.launch { listState.animateScrollBy(-step) }
                            pos.x > size.width * 0.72f -> scope.launch { listState.animateScrollBy(step) }
                            else -> onToggleBars()
                        }
                    },
                    onLongPress = { pos ->
                        // Какой абзац под пальцем - спрашиваем у самого списка:
                        // вложенный обработчик жестов сломал бы листание тапом.
                        val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            pos.y >= it.offset && pos.y < it.offset + it.size
                        }
                        blocks.getOrNull(hit?.index ?: -1)?.let { onLongPress(it.start) }
                    },
                )
            },
    ) {
        LazyColumn(
            state = listState,
            modifier = Modifier.fillMaxSize(),
            // Читалка рисуется во весь экран, без Scaffold, поэтому системные
            // отступы считаем сами: иначе первая строка уезжает под часы.
            contentPadding = PaddingValues(
                start = margin.dp,
                end = margin.dp,
                top = 56.dp + WindowInsets.statusBars.asPaddingValues().calculateTopPadding(),
                bottom = 110.dp,
            ),
        ) {
            itemsIndexed(blocks, key = { i, _ -> i }) { _, block ->
                val pic = block.picture
                if (pic != null) {
                    val file = app.texts.pictureFile(bookId, pic.file)
                    PictureBlock(file, pic.caption, palette) { onPicture(file) }
                } else {
                    val heading = isHeading(block)
                    Text(
                        text = block.text,
                        style = styleFor(heading),
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(top = if (heading) 28.dp else 0.dp, bottom = 10.dp),
                    )
                }
            }
        }
    }
}

// ------------------------------------------------------------------- листание

@Composable
private fun PagedBody(
    app: SlushalkaApp,
    bookId: String,
    blocks: List<Block>,
    palette: ReaderPalette,
    margin: Int,
    styleFor: (Boolean) -> TextStyle,
    isHeading: (Block) -> Boolean,
    target: Int?,
    onTargetUsed: () -> Unit,
    onOffset: (Int) -> Unit,
    onToggleBars: () -> Unit,
    onPicture: (File) -> Unit,
    onLongPress: (Int) -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val widthPx = with(density) { (maxWidth - margin.dp * 2).roundToPx() }
        val heightPx = with(density) {
            (maxHeight - topInset - bottomInset - PAGE_TOP - PAGE_BOTTOM).roundToPx()
        }
        val gapPx = with(density) { 10.dp.roundToPx() }

        var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
        var anchor by remember { mutableIntStateOf(-1) }
        var window by remember { mutableStateOf(0..0) }
        val scope = rememberCoroutineScope()
        val style = styleFor(false)
        val headingStyle = styleFor(true)
        val pagerState = rememberPagerState(pageCount = { pages.size.coerceAtLeast(1) })

        LaunchedEffect(target) {
            target?.let {
                anchor = it
                onTargetUsed()
            }
        }

        // Окно разбивки вокруг места: считаем один раз и держим, чтобы
        // пересчёт запускался только при настоящем сдвиге, а не по кругу.
        fun windowFor(at: Int): IntRange {
            val center = blocks.indexOfLast { it.start <= at }.coerceAtLeast(0)
            return (center - WINDOW_BLOCKS).coerceAtLeast(0)..
                (center + WINDOW_BLOCKS).coerceAtMost(blocks.lastIndex)
        }

        // Разбивка считается для окна вокруг текущего места: у романа страниц
        // под тысячу, и мерить их все ради одного разворота незачем.
        LaunchedEffect(anchor, widthPx, heightPx, style, headingStyle, blocks) {
            if (anchor < 0 || widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            val range = windowFor(anchor)
            window = range
            val fresh = Paginator.paginate(
                blocks = blocks,
                range = range,
                measurer = measurer,
                style = style,
                headingStyle = headingStyle,
                isHeading = isHeading,
                widthPx = widthPx,
                heightPx = heightPx,
                gapPx = gapPx,
            )
            pages = fresh
            val index = Paginator.indexOf(fresh, anchor)
            if (fresh.isNotEmpty()) pagerState.scrollToPage(index.coerceIn(0, fresh.lastIndex))
        }

        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collectLatest { index ->
                    val page = pages.getOrNull(index) ?: return@collectLatest
                    onOffset(page.startChar)
                    // Подошли к краю окна - пересчитываем следующее, взяв за
                    // середину текущую страницу. Только если окно правда
                    // сдвинется, иначе пересчёт пошёл бы по кругу.
                    val nearEdge = index <= 1 || index >= pages.lastIndex - 1
                    if (nearEdge && windowFor(page.startChar) != window) anchor = page.startChar
                }
        }

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pages) {
                    detectTapGestures(
                        onTap = { pos ->
                            val page = pagerState.currentPage
                            val to = when {
                                pos.x < size.width * 0.28f -> page - 1
                                pos.x > size.width * 0.72f -> page + 1
                                else -> {
                                    onToggleBars()
                                    return@detectTapGestures
                                }
                            }
                            if (to in 0 until pagerState.pageCount) {
                                scope.launch { pagerState.animateScrollToPage(to) }
                            }
                        },
                        onLongPress = {
                            pages.getOrNull(pagerState.currentPage)?.let { onLongPress(it.startChar) }
                        },
                    )
                },
        ) {
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Размечаю страницы…", color = palette.dim, fontSize = 13.sp)
                }
                return@Box
            }
            HorizontalPager(state = pagerState, modifier = Modifier.fillMaxSize()) { index ->
                val page = pages.getOrNull(index) ?: return@HorizontalPager
                Column(
                    Modifier
                        .fillMaxSize()
                        .padding(
                            start = margin.dp,
                            end = margin.dp,
                            top = topInset + PAGE_TOP,
                            bottom = bottomInset + PAGE_BOTTOM,
                        ),
                ) {
                    page.pieces.forEach { piece ->
                        val pic = piece.picture
                        if (pic != null) {
                            val file = app.texts.pictureFile(bookId, pic.file)
                            PictureBlock(file, pic.caption, palette) { onPicture(file) }
                        } else {
                            Text(
                                piece.text,
                                style = style,
                                modifier = Modifier.fillMaxWidth().padding(bottom = 10.dp),
                            )
                        }
                    }
                }
            }
        }
    }
}

private val PAGE_TOP = 52.dp
private val PAGE_BOTTOM = 60.dp
/** Сколько абзацев вокруг текущего места разбивать на страницы за раз. */
private const val WINDOW_BLOCKS = 260

// ------------------------------------------------------------------- картинки

/**
 * Картинка в тексте - карточкой в рамке, как обложка: вписана целиком, а не
 * растянута во всю полосу. Под ней подпись - из самого файла или строка,
 * стоявшая под картинкой в книге.
 */
@Composable
private fun PictureBlock(
    file: File,
    caption: String,
    palette: ReaderPalette,
    onOpen: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(Pictures.cached(file), file.path) {
        if (value == null) value = Pictures.load(file)
    }
    val bmp = bitmap ?: return
    Column(
        Modifier
            .fillMaxWidth()
            .padding(vertical = 18.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(palette.fg.copy(alpha = 0.06f))
            .clickable(onClick = onOpen)
            .padding(10.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = caption,
            contentScale = ContentScale.Fit,
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(max = 420.dp)
                .clip(RoundedCornerShape(8.dp)),
        )
        if (caption.isNotBlank()) {
            Spacer(Modifier.height(8.dp))
            Text(
                caption,
                color = palette.fg,
                fontSize = 13.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(horizontal = 6.dp),
            )
        }
        Spacer(Modifier.height(6.dp))
        Text("нажми, чтобы рассмотреть", color = palette.dim, fontSize = 11.sp)
    }
}

/** Все картинки книги разом - чтобы карту можно было открыть, когда вздумается. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PictureGallery(app: SlushalkaApp, onClose: () -> Unit) {
    val book = app.state.current.collectAsState().value ?: return
    val text = app.state.text.collectAsState().value
    var open by remember { mutableStateOf<File?>(null) }
    // Всё, что вынуто из файла, а не только размещённое в тексте.
    val files = remember(text) { app.texts.allPictures(book.id) }
    val captions = remember(text) {
        text?.picturesWithCaptions
            ?.filter { it.file.isNotBlank() && it.caption.isNotBlank() }
            ?.associate { it.file to it.caption }
            .orEmpty()
    }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Картинки книги") },
        text = {
            if (files.isEmpty()) {
                Text("В файле книги картинок не нашлось.")
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(96.dp),
                    modifier = Modifier.heightIn(max = 420.dp),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    items(files) { file ->
                        Thumb(file, captions[file.name].orEmpty()) { open = file }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
    open?.let { file -> ImageViewer(file) { open = null } }
}

@Composable
private fun Thumb(file: File, caption: String, onOpen: () -> Unit) {
    val bitmap by produceState<android.graphics.Bitmap?>(Pictures.cached(file), file.path) {
        if (value == null) value = Pictures.load(file, target = 300)
    }
    Column(Modifier.clickable(onClick = onOpen)) {
        Box(
            Modifier
                .height(96.dp)
                .fillMaxWidth()
                .clip(RoundedCornerShape(8.dp)),
        ) {
            bitmap?.let {
                Image(
                    bitmap = it.asImageBitmap(),
                    contentDescription = caption,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize(),
                )
            }
        }
        if (caption.isNotBlank()) {
            Text(
                caption,
                fontSize = 10.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
    }
}

@Composable
private fun PictureChip(
    file: File,
    palette: ReaderPalette,
    modifier: Modifier = Modifier,
    onOpen: () -> Unit,
) {
    val bitmap by produceState<android.graphics.Bitmap?>(Pictures.cached(file), file.path) {
        if (value == null) value = Pictures.load(file, target = 220)
    }
    val bmp = bitmap ?: return
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(palette.fg.copy(alpha = 0.10f))
            .clickable(onClick = onOpen)
            .padding(6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Image(
            bitmap = bmp.asImageBitmap(),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier.size(34.dp).clip(RoundedCornerShape(6.dp)),
        )
        Spacer(Modifier.width(8.dp))
        Text("картинка", color = palette.fg, fontSize = 12.sp)
        Spacer(Modifier.width(4.dp))
    }
}
