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
    onAsk: (charOffset: Int, question: String?) -> Unit,
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
    var picture by remember { mutableStateOf<ShownPicture?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Плашка «делаю прямо сейчас» держится, пока идёт дело, а не гаснет по
    // таймеру, как короткие сообщения.
    var working by remember { mutableStateOf<String?>(null) }
    var pendingHighlight by remember { mutableStateOf<Int?>(null) }
    var pressed by remember { mutableStateOf<Int?>(null) }
    // Что на экране: [offset] - верх (начало страницы или первого абзаца),
    // [shownEnd] - конец видимого. [place] - точное место, с которого пришли
    // из записи; пока оно на экране, местом чтения считается оно, а не верх.
    var offset by remember { mutableIntStateOf(0) }
    var shownEnd by remember { mutableIntStateOf(0) }
    var place by remember { mutableStateOf<Int?>(null) }
    var target by remember { mutableStateOf<Int?>(null) }
    var highlightRange by remember { mutableStateOf<IntRange?>(null) }
    val highlight = remember { androidx.compose.animation.core.Animatable(0f) }

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
    // Считается один раз на книгу: панель читалки перерисовывается на каждой
    // прокрутке, и лазить в файловую систему на каждом кадре ей незачем.
    val hasPictures = remember(t, bk.id) { app.state.picturesOnDisk() > 0 }
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

    // Открываемся сразу - там, где место по карте, - и уже на открытой
    // странице слушаем оригинал и уточняем. Подсвечиваем только то, что
    // действительно нашли: подсветка на догадке карты вводит в заблуждение.
    LaunchedEffect(t, bk.id) {
        val start = state.readingStart()
        offset = start.offset
        target = start.offset
        place = start.offset
        if (!start.fromAudio) return@LaunchedEffect

        working = "Слушаю оригинал…"
        val result = state.refineReading()
        working = null
        when (result) {
            is AppState.Refine.Found -> {
                target = result.charOffset
                place = result.charOffset
                pendingHighlight = result.charOffset
            }
            AppState.Refine.Trusted -> pendingHighlight = start.offset
            AppState.Refine.NotFound ->
                notice = "Услышал, но в тексте не нашёл — место примерное"
            AppState.Refine.NoSpeech ->
                notice = "Не расслышал запись — место примерное"
            AppState.Refine.Off -> Unit
        }
    }

    // Найденное место коротко подсвечивается и гаснет: глазами сразу видно,
    // откуда читать, а через пару секунд ничто не мешает тексту.
    LaunchedEffect(pendingHighlight) {
        val at = pendingHighlight ?: return@LaunchedEffect
        // Подсвечиваем именно ту фразу, на которой остановилась запись, а не
        // весь абзац: глаз цепляется за неё сразу.
        highlightRange = t.sentenceAt(at)
        highlight.snapTo(1f)
        highlight.animateTo(
            targetValue = 0f,
            animationSpec = androidx.compose.animation.core.tween(
                durationMillis = 3400,
                delayMillis = 1800,
            ),
        )
        highlightRange = null
        pendingHighlight = null
    }

    // Место чтения. Пока страница, на которую пришли из записи, не перелистнута,
    // это само место записи, а не верх страницы: иначе «заглянул и закрыл»
    // откатывало бы звук к началу страницы. Перелистнули - верх экрана.
    fun readPlace(): Int = place?.takeIf { it >= offset && it < shownEnd } ?: offset

    LaunchedEffect(offset, shownEnd, place) {
        // Место чтения пишется на диск, когда листание успокоилось, - и запись
        // подтягивается к нему тем же движением.
        kotlinx.coroutines.delay(700)
        state.saveReadChar(readPlace())
    }
    DisposableEffect(Unit) { onDispose { state.saveReadChar(readPlace()) } }

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2600)
            notice = null
        }
    }

    Box(Modifier.fillMaxSize().background(palette.bg)) {
        val onLong: (Int) -> Unit = { pressed = it }
        val onTapPicture: (ShownPicture) -> Unit = { picture = it }
        if (prefs.readerPaged) {
            PagedBody(
                app = app, bookId = bk.id, blocks = blocks, palette = palette,
                margin = prefs.readerMargin, styleFor = ::styleFor, isHeading = isHeading,
                target = target, onTargetUsed = { target = null },
                onShown = { start, end -> offset = start; shownEnd = end },
                onToggleBars = { bars = !bars },
                onPicture = onTapPicture, onLongPress = onLong,
                highlight = highlightRange, highlightAlpha = highlight.value,
            )
        } else {
            ScrollBody(
                app = app, bookId = bk.id, blocks = blocks, palette = palette,
                margin = prefs.readerMargin, styleFor = ::styleFor, isHeading = isHeading,
                target = target, onTargetUsed = { target = null },
                onShown = { start, end -> offset = start; shownEnd = end },
                onToggleBars = { bars = !bars },
                onPicture = onTapPicture, onLongPress = onLong,
                highlight = highlightRange, highlightAlpha = highlight.value,
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
                picture = ShownPicture(file, pic.caption, pic.charOffset)
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
                // Книге без записи плеер не нужен - «назад» ведёт на полку.
                TextButton(onClick = onBack) { Text(if (bk.hasAudio) "‹ Плеер" else "‹ Полка", color = palette.fg) }
                Text(
                    t.chapterAt(offset)?.title.orEmpty(),
                    color = palette.dim,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    fontSize = 12.sp,
                    modifier = Modifier.weight(1f).padding(horizontal = 6.dp),
                )
                if (hasPictures) {
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
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        "стр. ${t.pageOf(offset)} из ${t.pages}",
                        color = palette.dim,
                        fontSize = 12.sp,
                    )
                    Spacer(Modifier.width(10.dp))
                    LoveLine(alpha = 0.3f, size = 10, color = palette.fg)
                }
                Row(horizontalArrangement = Arrangement.spacedBy(2.dp)) {
                    if (bk.hasAudio) {
                        TextButton(onClick = {
                            state.listenFrom(readPlace())
                            onListen()
                        }) { Text("Слушать отсюда", color = palette.fg) }
                    }
                    TextButton(onClick = { showRecap = true }) { Text("Содержание", color = palette.fg) }
                    TextButton(onClick = { onAsk(readPlace(), null) }) { Text("Спросить", color = palette.fg) }
                }
            }
        }

        (notice ?: working ?: busy)?.let { line ->
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
        PictureGallery(
            app,
            onAsk = { at, q -> showGallery = false; onAsk(at, q) },
            onClose = { showGallery = false },
        )
    }
    if (showRecap) {
        RecapSheet(
            app,
            cutoffChar = offset,
            absMs = state.alignment.value?.audioAt(offset) ?: 0L,
            onClose = { showRecap = false },
        )
    }
    picture?.let { shown ->
        ImageViewer(
            shown = shown,
            onAsk = {
                picture = null
                onAsk(shown.charOffset, ru.zf.slushalka.ask.Prompts.picture(shown.caption))
            },
            onClose = { picture = null },
        )
    }
    // Долгое нажатие - разговор о карте «звук ↔ текст»; книге без записи он ни к чему.
    pressed?.takeIf { bk.hasAudio }?.let { at ->
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
    /** Что на экране: от начала первого видимого абзаца до конца последнего. */
    onShown: (start: Int, end: Int) -> Unit,
    onToggleBars: () -> Unit,
    onPicture: (ShownPicture) -> Unit,
    onLongPress: (Int) -> Unit,
    highlight: IntRange?,
    highlightAlpha: Float,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()

    LaunchedEffect(target) {
        val to = target ?: return@LaunchedEffect
        listState.scrollToItem(blocks.indexOfLast { it.start <= to }.coerceAtLeast(0))
        onTargetUsed()
    }
    LaunchedEffect(listState) {
        snapshotFlow {
            val shown = listState.layoutInfo.visibleItemsInfo
            (shown.firstOrNull()?.index ?: -1) to (shown.lastOrNull()?.index ?: -1)
        }
            .distinctUntilChanged()
            .collectLatest { (first, last) ->
                val head = blocks.getOrNull(first) ?: return@collectLatest
                val tail = blocks.getOrNull(last) ?: head
                onShown(head.start, tail.end)
            }
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
                    PictureBlock(file, pic.caption, palette) {
                        onPicture(ShownPicture(file, pic.caption, pic.charOffset))
                    }
                } else {
                    val heading = isHeading(block)
                    Text(
                        text = litText(block.text, block.start, highlight, highlightAlpha, palette),
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
    /** Что на экране: от начала страницы до начала следующей. */
    onShown: (start: Int, end: Int) -> Unit,
    onToggleBars: () -> Unit,
    onPicture: (ShownPicture) -> Unit,
    onLongPress: (Int) -> Unit,
    highlight: IntRange?,
    highlightAlpha: Float,
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
                    val end = pages.getOrNull(index + 1)?.startChar
                        ?: page.pieces.lastOrNull()?.end ?: page.startChar
                    onShown(page.startChar, end)
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
                            PictureBlock(file, pic.caption, palette) {
                                onPicture(ShownPicture(file, pic.caption, pic.charOffset))
                            }
                        } else {
                            Text(
                                litText(piece.text, piece.start, highlight, highlightAlpha, palette),
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
    val bitmap = rememberPicture(file)
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
fun PictureGallery(
    app: SlushalkaApp,
    onAsk: ((charOffset: Int, question: String) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val book = app.state.current.collectAsState().value ?: return
    val text = app.state.text.collectAsState().value
    var open by remember { mutableStateOf<ShownPicture?>(null) }
    // Всё, что вынуто из файла, а не только размещённое в тексте.
    val files = remember(text) { app.texts.allPictures(book.id) }
    val byFile = remember(text) {
        text?.picturesWithCaptions?.filter { it.file.isNotBlank() }?.associateBy { it.file }.orEmpty()
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
                        val pic = byFile[file.name]
                        Thumb(file, pic?.caption.orEmpty()) {
                            open = ShownPicture(file, pic?.caption.orEmpty(), pic?.charOffset ?: 0)
                        }
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Закрыть") } },
    )
    open?.let { shown ->
        ImageViewer(
            shown = shown,
            onAsk = onAsk?.let {
                { open = null; onClose(); it(shown.charOffset, ru.zf.slushalka.ask.Prompts.picture(shown.caption)) }
            },
            onClose = { open = null },
        )
    }
}

@Composable
private fun Thumb(file: File, caption: String, onOpen: () -> Unit) {
    val bitmap = rememberPicture(file, target = 300)
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
    val bitmap = rememberPicture(file, target = 220)
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

/**
 * Тот же текст, но с подсвеченной фразой. Подсветка живёт внутри строки, а не
 * заливает абзац целиком: найденное предложение видно, соседние - нет.
 */
private fun litText(
    text: String,
    start: Int,
    highlight: IntRange?,
    alpha: Float,
    palette: ReaderPalette,
): androidx.compose.ui.text.AnnotatedString {
    if (highlight == null || alpha <= 0.01f) return androidx.compose.ui.text.AnnotatedString(text)
    val from = (highlight.first - start).coerceIn(0, text.length)
    val to = (highlight.last - start).coerceIn(from, text.length)
    if (to <= from) return androidx.compose.ui.text.AnnotatedString(text)
    return androidx.compose.ui.text.buildAnnotatedString {
        append(text)
        addStyle(
            androidx.compose.ui.text.SpanStyle(
                background = palette.fg.copy(alpha = 0.22f * alpha),
            ),
            from, to,
        )
    }
}
