package ru.zf.slushalka.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.border
import androidx.compose.animation.slideOutVertically
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
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
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredWidth
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
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
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.Hyphens
import androidx.compose.ui.text.style.LineBreak
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.zIndex
import java.io.File
import kotlin.math.roundToInt
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import ru.zf.slushalka.R
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.data.readerView
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.BookText

// ------------------------------------------------------------------- листание

@Composable
internal fun PagedBody(
    app: SlushalkaApp,
    bookId: String,
    blocks: List<Block>,
    palette: ReaderPalette,
    hits: TextHits,
    tones: PaperTones,
    look: PageLook,
    turn: String,
    shape: BookShape,
    /** Колонтитулы считаются от начала самой страницы: в развороте их две. */
    marksAt: (Int, PageNo?) -> PageMarks,
    margins: PageMargins,
    /** Тап по главе в нижнем колонтитуле открывает содержание. */
    onChapters: () -> Unit,
    gap: Dp,
    styleFor: (heading: Boolean, head: Boolean) -> TextStyle,
    isHeading: (Block) -> Boolean,
    /** Первый абзац главы набирается без абзацного отступа. */
    noIndent: (Int) -> Boolean,
    /** Первые слова главы - капителью. */
    smallCaps: Boolean,
    /** Неровности печати: перекос полосы и сдвиг базовых линий. */
    imperfect: Boolean,
    target: Int?,
    onTargetUsed: () -> Unit,
    /** Что на экране: от начала страницы (левой в развороте) до начала следующей. */
    onShown: (start: Int, end: Int) -> Unit,
    /** Тап по тексту; true - съеден (снял выделение, открыл пометку), листать не надо. */
    onTap: (root: Offset) -> Boolean,
    onToggleBars: () -> Unit,
    /** Серия тапов в корневых координатах: 2 - слово, 3 - фраза, 4 - абзац. */
    onTaps: (count: Int, root: Offset) -> Unit,
    /** Долгое нажатие; [fallback] - знак на случай, если палец не на строке. */
    onLongPress: (root: Offset, fallback: Int?) -> Unit,
    onPicture: (ShownPicture) -> Unit,
    ink: TextInk,
    keyTurn: KeyTurn?,
    /** Листать без анимации - на электронной бумаге каждый кадр мерцает. */
    instant: Boolean,
) {
    BoxWithConstraints(Modifier.fillMaxSize()) {
        val density = LocalDensity.current
        val measurer = rememberTextMeasurer()
        val topInset = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottomInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        // Меряем по карточке, а не по экрану: её поля, скругление и место под
        // колоду тексту не принадлежат. Мерки не зависят от места в книге
        // нарочно, иначе тающая колода гоняла бы разбивку при каждом
        // перелистывании.
        val card = cardMetrics(look, shape)
        // В развороте страниц две, каждая по своей половине экрана.
        val halves = if (shape.spread) 2 else 1
        val chrome = pageChrome(look, card, halves, shape.half)
        // Вниз, а не к ближайшему: место на странице меряется в целых
        // пикселях, и лишняя половина пикселя оборачивалась строкой, которая
        // на живой сборке срезалась нижним краем.
        val widthPx = with(density) {
            (maxWidth / halves - chrome.width - margins.width).toPx().toInt()
        }
        // Высота полосы - целое число строк. Иначе остаток (доли строки)
        // копится по-разному на соседних страницах, и низ у них расходится:
        // владелец на живой сборке - «нижняя строка на двух страницах должна
        // быть на одной высоте».
        val lineHeightPx = with(density) { styleFor(false, true).lineHeight.toPx() }
        val heightPx = with(density) {
            val avail = (maxHeight - topInset - bottomInset - chrome.height - margins.height).toPx()
            if (lineHeightPx > 1f) (kotlin.math.floor(avail / lineHeightPx) * lineHeightPx).toInt()
            else avail.toInt()
        }
        // Воздух вокруг заголовка - тоже целые строки, иначе всё, что под
        // ним, съезжает с сетки.
        val headingAir = with(density) { lineHeightPx.toDp() }
        val gapPx = with(density) { gap.roundToPx() }
        val headingTopPx = lineHeightPx.toInt()
        val headingGapPx = lineHeightPx.toInt()

        var pages by remember { mutableStateOf<List<Page>>(emptyList()) }
        // Средняя длина страницы в текущей разбивке: по ней и считаются
        // номера, иначе на развороте оба номера выходили одинаковыми.
        val pageStep = remember(pages) {
            if (pages.size < 3) 0
            else ((pages.last().startChar - pages.first().startChar) / (pages.size - 1))
                .coerceAtLeast(200)
        }
        val bookChars = remember(blocks) {
            blocks.lastOrNull()?.let { it.start + it.text.length } ?: 0
        }
        fun numberOf(start: Int): PageNo? =
            if (pageStep <= 0) null
            else PageNo(start / pageStep + 1, (bookChars / pageStep + 1).coerceAtLeast(1))
        var anchor by remember { mutableIntStateOf(-1) }
        var window by remember { mutableStateOf(0..0) }
        val scope = rememberCoroutineScope()
        val style = styleFor(false, true)
        val contStyle = styleFor(false, false)
        val headingStyle = styleFor(true, true)
        // Страница в списке и место в пейджере - разные вещи: в развороте на
        // одно место пейджера приходится две страницы.
        fun slotOf(index: Int) = if (shape.spread) index / 2 else index
        fun firstOf(slot: Int) = if (shape.spread) slot * 2 else slot
        val pagerState = rememberPagerState(
            pageCount = { (slotOf(pages.lastIndex) + 1).coerceAtLeast(1) },
        )

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
        //
        // Новый якорь - открытие книги или переход - считается от него. Всё
        // остальное (кегль, шрифт, поля, поворот экрана) - от читаемой
        // страницы: иначе после «А+» разбивка шла от давнего якоря, и читалка
        // откатывала к месту, с которого когда-то пришли.
        var shownStart by remember { mutableIntStateOf(-1) }
        var lastAnchor by remember { mutableIntStateOf(Int.MIN_VALUE) }
        LaunchedEffect(anchor, widthPx, heightPx, style, headingStyle, blocks, shape.spread) {
            if (widthPx <= 0 || heightPx <= 0) return@LaunchedEffect
            val at = if (anchor != lastAnchor || shownStart < 0) anchor else shownStart
            lastAnchor = anchor
            if (at < 0) return@LaunchedEffect
            val range = windowFor(at)
            window = range
            val fresh = Paginator.paginate(
                blocks = blocks,
                range = range,
                measurer = measurer,
                style = style,
                contStyle = contStyle,
                headingStyle = headingStyle,
                isHeading = isHeading,
                noIndent = noIndent,
                // Меряем ровно то, что нарисуем: с капителью строка шире.
                annotate = { txt, opens ->
                    if (opens && smallCaps) openingText(txt, 0, TextInk(), palette)
                    else androidx.compose.ui.text.AnnotatedString(txt)
                },
                widthPx = widthPx,
                heightPx = heightPx,
                gapPx = gapPx,
                headingTopPx = headingTopPx,
                headingGapPx = headingGapPx,
                lineHeightPx = lineHeightPx.toInt(),
            )
            pages = fresh
            if (fresh.isNotEmpty()) {
                val index = Paginator.indexOf(fresh, at)
                pagerState.scrollToPage(slotOf(index).coerceIn(0, slotOf(fresh.lastIndex)))
            }
        }

        LaunchedEffect(keyTurn) {
            val k = keyTurn ?: return@LaunchedEffect
            val to = pagerState.currentPage + k.dir
            if (to !in 0 until pagerState.pageCount) return@LaunchedEffect
            if (instant) pagerState.scrollToPage(to)
            else pagerState.animateScrollToPage(to, animationSpec = tween(if (look.volume) 240 else 460, easing = FastOutSlowInEasing))
        }

        LaunchedEffect(pagerState, pages) {
            snapshotFlow { pagerState.currentPage }
                .distinctUntilChanged()
                .collectLatest { slot ->
                    val first = firstOf(slot)
                    val page = pages.getOrNull(first) ?: return@collectLatest
                    // В развороте прочитанным считается всё до начала следующего.
                    val last = if (shape.spread) (first + 1).coerceAtMost(pages.lastIndex) else first
                    val end = pages.getOrNull(last + 1)?.startChar
                        ?: pages.getOrNull(last)?.pieces?.lastOrNull()?.end ?: page.startChar
                    shownStart = page.startChar
                    onShown(page.startChar, end)
                    // Подошли к краю окна - пересчитываем следующее, взяв за
                    // середину текущую страницу. Только если окно правда
                    // сдвинется, иначе пересчёт пошёл бы по кругу.
                    val nearEdge = first <= 1 || last >= pages.lastIndex - 1
                    if (nearEdge && windowFor(page.startChar) != window) anchor = page.startChar
                }
        }

        var origin by remember { mutableStateOf(Offset.Zero) }
        Box(
            Modifier
                .fillMaxSize()
                .onGloballyPositioned { origin = it.positionInRoot() }
                .pointerInput(pages, shape.spread) {
                    detectReaderTaps(
                        onTap = { pos ->
                            if (onTap(pos + origin)) return@detectReaderTaps
                            val slot = pagerState.currentPage
                            val to = when {
                                pos.x < size.width * 0.28f -> slot - 1
                                pos.x > size.width * 0.72f -> slot + 1
                                else -> {
                                    onToggleBars()
                                    return@detectReaderTaps
                                }
                            }
                            if (to in 0 until pagerState.pageCount) {
                                scope.launch {
                                    if (instant) return@launch pagerState.scrollToPage(to)
                                    // Полсекунды с замедлением в конце: рукой
                                    // страницу переворачивают примерно так.
                                    pagerState.animateScrollToPage(
                                        to,
                                        animationSpec = tween(
                                            if (look.volume) 240 else 460,
                                            easing = FastOutSlowInEasing,
                                        ),
                                    )
                                }
                            }
                        },
                        onTaps = { n, pos -> onTaps(n, pos + origin) },
                        onLongPress = { pos ->
                            // В развороте спрашивают про ту страницу, на которую нажали.
                            val first = firstOf(pagerState.currentPage)
                            val at = if (shape.spread && pos.x > size.width / 2) first + 1 else first
                            onLongPress(pos + origin, pages.getOrNull(at)?.startChar)
                        },
                    )
                },
        ) {
            // Подложка - книга или колода - стоит на месте: страницы ездят
            // поверх, а том лежит на столе. Книга целиком в экране: половина
            // разворота, уезжавшая за край, владельцу на живой сборке
            // читалась поломкой - «левый край книги вылезает».
            val screenWidth = this@BoxWithConstraints.maxWidth
            if (shape.half) Box(
                // Разворот шире экрана на две полоски подглядывания, и камера
                // ездит по нему: читаешь левую страницу - смахнул - книга
                // доехала до правой. Переворота листа тут нет.
                Modifier
                    // Слой шире экрана, а такой Compose ставит по центру: книга
                    // съезжала на полэкрана, у левой страницы край переплёта
                    // уходил за экран, у правой книга кончалась посередине.
                    // Камера считает от левого края - к нему и прижат.
                    .fillMaxWidth()
                    .wrapContentWidth(Alignment.Start, unbounded = true)
                    .requiredWidth(screenWidth * 2 - BOOK_PEEK * 2)
                    .fillMaxHeight()
                    .graphicsLayer {
                        val pos = pagerState.currentPage + pagerState.currentPageOffsetFraction
                        // Камера меряется по экрану, а слой шире него.
                        translationX = bookPan(
                            bookPhase(pos),
                            size.width / 2f + BOOK_PEEK.toPx(),
                            BOOK_PEEK.toPx(),
                        )
                    }
                    .padding(underPadding(card, topInset, bottomInset))
                    .pageUnder(tones, look, shape)
            ) else Box(
                Modifier
                    .fillMaxSize()
                    .padding(underPadding(card, topInset, bottomInset))
                    .pageUnder(tones, look, shape)
            )
            if (pages.isEmpty()) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Размечаю страницы…", color = palette.dim, fontSize = 13.sp)
                }
                return@Box
            }
            // Книжный вид перелистывается по-своему: слоты пейджера стоят на
            // месте книги, а лист либо заворачивается (одна страница), либо
            // поворачивается вокруг корешка (разворот).
            val book = look.volume
            // Доводка мягкая, без щелчка: страница должна ложиться, а не
            // защёлкиваться. Пружина без отскока и средней жёсткости - это
            // примерно вес бумаги.
            val fling = PagerDefaults.flingBehavior(
                state = pagerState,
                // В книге страница растворяется, и тянуть это незачем: жёсткая
                // пружина без отскока даёт короткий, чистый переход. У
                // карточек ход мягче - там страница едет вбок.
                // На e-ink страница встаёт сразу, без доводки: каждый кадр
                // доводки - мерцание электронной бумаги.
                snapAnimationSpec = if (instant) androidx.compose.animation.core.snap() else spring(
                    dampingRatio = Spring.DampingRatioNoBouncy,
                    stiffness = if (book) Spring.StiffnessMedium else Spring.StiffnessMediumLow,
                ),
            )
            HorizontalPager(
                state = pagerState,
                modifier = Modifier.fillMaxSize(),
                flingBehavior = fling,
                // На половине разворота за корешком видна соседняя страница -
                // её надо держать в композиции и в покое, иначе в полоске
                // подглядывания пусто.
                beyondViewportPageCount = if (shape.half) 1 else 0,
            ) { slot ->
                val off = { pagerState.turnOffset(slot) }
                val face: @Composable (Page?, PageSide, Modifier) -> Unit = { page, side, modifier ->
                    PageFace(
                        page = page, side = side, app = app, bookId = bookId, palette = palette,
                        hits = hits, tones = tones, look = look, shape = shape,
                        pad = pagePadding(look, card, side, topInset, bottomInset, shape.half),
                        margins = margins, style = style, contStyle = contStyle,
                        headingStyle = headingStyle, gap = gap, marksAt = marksAt,
                        ink = ink,
                        onPicture = onPicture, noIndent = noIndent, headingAir = headingAir,
                        smallCaps = smallCaps, imperfect = imperfect,
                        number = page?.let { numberOf(it.startChar) },
                        onChapters = onChapters, modifier = modifier,
                    )
                }
                // Ближняя к читаемому месту страница лежит поверх дальних.
                val away = slot - pagerState.currentPage
                val z = Modifier
                    .fillMaxSize()
                    .zIndex(-(kotlin.math.abs(away) + if (away < 0) 0.5f else 0f))
                // Перелистывание в книге - растворение, и только оно:
                // переворот листа владелец забраковал во всех видах, а
                // смахивание книжной странице не идёт («для книг растворение,
                // для стопки смахивание»). Настройка «Перелистывание»
                // остаётся за карточками.
                val bookTurn = Settings.TURN_FADE
                when {
                    // Половина разворота: чётная страница левая, нечётная
                    // правая; книга ездит камерой, страницы растворяются.
                    shape.half -> {
                        val side = if (slot % 2 == 0) PageSide.LEFT else PageSide.RIGHT
                        val position = { pagerState.currentPage + pagerState.currentPageOffsetFraction }
                        face(
                            pages.getOrNull(slot), side,
                            z.halfPan(side, BOOK_PEEK, off, position)
                                .graphicsLayer { alpha = 1f - off().coerceIn(0f, 1f) },
                        )
                    }

                    shape.spread -> Row(
                        z.pageTurn(if (book) bookTurn else turn, gentle = true, offset = off)
                    ) {
                        face(pages.getOrNull(slot * 2), PageSide.LEFT, Modifier.weight(1f).fillMaxHeight())
                        face(pages.getOrNull(slot * 2 + 1), PageSide.RIGHT, Modifier.weight(1f).fillMaxHeight())
                    }

                    else -> face(
                        pages.getOrNull(slot), PageSide.SINGLE,
                        z.pageTurn(if (book) bookTurn else turn, gentle = false, offset = off),
                    )
                }
            }
        }
    }
}

/** Что пишется на полях страницы. Пустая строка - не рисуется. */
/**
 * Номер страницы по настоящей разбивке, а не по условным 1800 знакам.
 *
 * Условная мерка давала на развороте два одинаковых номера: экранная
 * страница короче, и соседние места книги попадали в одно «условное».
 * Здесь шаг - средняя длина страницы в текущей разбивке.
 */
data class PageNo(val page: Int, val total: Int)

data class PageMarks(
    /** Верхний колонтитул книжного вида. */
    val author: String = "",
    val title: String = "",
    /** Нижний колонтитул: глава - по ней открывается содержание. */
    val chapter: String = "",
    /** Номер в нижнем углу: «142 / 380 · 37%». */
    val corner: String = "",
    /** Номер внизу по центру, как в типографской книге: «— 46 —». */
    val center: String = "",
) {
    val head: Boolean get() = author.isNotEmpty() || title.isNotEmpty()
    val foot: Boolean get() = chapter.isNotEmpty() || center.isNotEmpty()
    val any: Boolean get() = head || foot || corner.isNotEmpty()
}

/** Одна страница: лист (или карточка) и текст на ней. */
@Composable
internal fun PageFace(
    page: Page?,
    side: PageSide,
    app: SlushalkaApp,
    bookId: String,
    palette: ReaderPalette,
    hits: TextHits,
    tones: PaperTones,
    look: PageLook,
    shape: BookShape,
    pad: PaddingValues,
    margins: PageMargins,
    style: TextStyle,
    contStyle: TextStyle,
    headingStyle: TextStyle,
    gap: Dp,
    marksAt: (Int, PageNo?) -> PageMarks,
    number: PageNo?,
    onChapters: () -> Unit,
    ink: TextInk,
    onPicture: (ShownPicture) -> Unit,
    noIndent: (Int) -> Boolean,
    /** Воздух над заголовком и под ним: ровно строка, чтобы не сбить сетку. */
    headingAir: Dp,
    smallCaps: Boolean,
    imperfect: Boolean,
    modifier: Modifier,
) {
    Box(
        modifier
            .padding(pad)
            .pageSheet(tones, look, shape, side)
    ) {
        if (page == null) return@Box
        PageMarksLayer(marksAt(page.startChar, number), side, palette, margins, contStyle, onChapters)
        Column(
            Modifier
                .fillMaxSize()
                .padding(
                    start = margins.start(side),
                    end = margins.end(side),
                    top = margins.top,
                    bottom = margins.bottom,
                )
                // Неровности печати: полоса набора чуть перекошена, базовые
                // линии соседних страниц не совпадают. Угол и сдвиг - от места
                // страницы в книге, чтобы не дрожали между кадрами.
                .then(
                    // Только едва заметный перекос полосы. Сдвиг базовых
                    // линий отсюда убран совсем: он разводил низ соседних
                    // страниц, а это первое, что видно на развороте.
                    if (!imperfect) Modifier else Modifier.graphicsLayer {
                        rotationZ = (pageNoise(page.startChar, 1) - 0.5f) * 0.16f
                    }
                ),
        ) {
            // Остаток места на странице отдаётся воздуху у заголовка: без
            // этого перед главой, которая не влезла, зияла дыра в несколько
            // строк, а низ полосы уезжал вверх. В книгах воздух у заголовка
            // для того и тянется.
            val headings = page.pieces.count { it.heading }
            val density = LocalDensity.current
            // Воздух у заголовка тянется первым. Если заголовка нет, остаток
            // раскладывается между абзацами, но не больше чем по строке на
            // промежуток: разгонка хороша в меру, иначе текст расползается.
            val stretch = with(density) {
                if (headings > 0) (page.slack / headings).toDp() else 0.dp
            }
            val spread = with(density) {
                val gaps = page.pieces.size - 1
                if (headings > 0 || gaps < 1) 0.dp
                else (page.slack / gaps).toDp().coerceAtMost(headingAir)
            }
            page.pieces.forEachIndexed { index, piece ->
                val pic = piece.picture
                if (pic != null) {
                    val file = app.texts.pictureFile(bookId, pic.file)
                    PictureBlock(file, pic.caption, palette) {
                        onPicture(ShownPicture(file, pic.caption, pic.charOffset))
                    }
                } else {
                    DisposableEffect(piece.start) { onDispose { hits.forget(piece.start) } }
                    val opens = piece.head && noIndent(piece.start)
                    Text(
                        if (opens && smallCaps) openingText(
                            piece.text, piece.start, ink, palette,
                        ) else litText(piece.text, piece.start, ink, palette),
                        // Тем же стилем, каким мерили: заголовок - заголовочным,
                        // первый абзац главы и продолжение абзаца - без отступа.
                        style = when {
                            piece.heading -> headingStyle
                            piece.head && !opens -> style
                            else -> contStyle
                        },
                        onTextLayout = { hits.layout(piece.start, it) },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(
                                // Треть растяжки сверху, две трети снизу:
                                // заголовок должен висеть ближе к своему
                                // тексту, чем к чужому.
                                top = if (piece.heading && index > 0) headingAir + stretch / 3 else 0.dp,
                                bottom = when {
                                    piece.heading -> headingAir + stretch * 2 / 3
                                    index < page.pieces.lastIndex -> gap + spread
                                    else -> gap
                                },
                            )
                            .onGloballyPositioned { hits.place(piece.start, it.boundsInRoot()) },
                    )
                }
            }
        }
    }
}

/**
 * Колонтитулы поверх страницы.
 *
 * Рисуются не в колонке текста, а поверх неё, в запасе под панели: иначе
 * верхний колонтитул съедал бы строку, и разбивку на страницы пришлось бы
 * считать с поправкой на него. Автор на левой странице, название на правой -
 * как в книге; на одной странице оба сразу.
 */
@Composable
internal fun BoxScope.PageMarksLayer(
    marks: PageMarks,
    side: PageSide,
    palette: ReaderPalette,
    margins: PageMargins,
    style: TextStyle,
    onChapters: () -> Unit,
) {
    if (!marks.any) return
    // Кегль колонтитула - 60% основного, разрядка 0.08 em: так он читается
    // служебной строкой, а не началом текста.
    val small = style.copy(
        fontSize = style.fontSize * 0.6f,
        lineHeight = style.fontSize * 0.78f,
        letterSpacing = style.fontSize * 0.08f,
        color = palette.dim,
        textAlign = TextAlign.Start,
        fontWeight = FontWeight.Normal,
    )
    if (marks.head) {
        // Линейка идёт ПОД колонтитулом во всю ширину полосы - так её
        // ставят в книгах, и так она отделяет служебную строку от текста.
        // Короткая линейка между автором и названием висела в пустоте.
        Column(
            Modifier
                .align(Alignment.TopStart)
                .fillMaxWidth()
                .padding(
                    start = margins.start(side),
                    end = margins.end(side),
                    // Колонтитул сидит в верхнем поле: под текстом он читался
                    // бы первой строкой полосы.
                    top = (margins.top - 24.dp).coerceAtLeast(4.dp),
                ),
        ) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (side != PageSide.RIGHT) {
                    Text(marks.author, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Spacer(Modifier.weight(1f))
                if (side != PageSide.LEFT) {
                    Text(marks.title, style = small, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
            HorizontalDivider(
                Modifier.fillMaxWidth().padding(top = 3.dp),
                thickness = 0.8.dp,
                color = palette.dim.copy(alpha = 0.45f),
            )
        }
    }
    if (marks.foot) {
        // Внизу такая же линейка, как вверху, и под ней служебная строка:
        // слева глава, справа номер со всем объёмом. По главе открывается
        // содержание - из книги видно, где ты, и можно уйти в другую часть.
        Column(
            Modifier
                .align(Alignment.BottomStart)
                .fillMaxWidth()
                .padding(
                    start = margins.start(side),
                    end = margins.end(side),
                    bottom = 8.dp,
                ),
        ) {
            HorizontalDivider(
                Modifier.fillMaxWidth().padding(bottom = 3.dp),
                thickness = 0.8.dp,
                color = palette.dim.copy(alpha = 0.45f),
            )
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                if (marks.chapter.isNotEmpty()) {
                    Text(
                        marks.chapter,
                        style = small,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .weight(1f, fill = false)
                            .clickable(onClick = onChapters),
                    )
                }
                Spacer(Modifier.weight(1f))
                if (marks.center.isNotEmpty()) Text(marks.center, style = small, maxLines = 1)
            }
        }
    }
    if (marks.corner.isNotEmpty()) {
        Text(
            marks.corner,
            style = small,
            modifier = Modifier
                .align(Alignment.BottomEnd)
                .padding(end = margins.end(side), bottom = 12.dp),
        )
    }
}

/**
 * Поля полосы набора.
 *
 * Канон Ван де Граафа: внутреннее, верхнее, внешнее и нижнее в пропорции
 * 2:3:4:6. Полоса смещена к корешку и вверх - именно это и делает разворот
 * похожим на книгу, а не на текст в рамке. Размер задаёт настройка «поле»,
 * канон - только пропорцию: бумажные поля (внешнее в две девятых ширины) на
 * телефоне съели бы полполосы, и в строке осталось бы знаков тридцать.
 *
 * Без канона поля равные со всех сторон, как было: тумблер оставлен, чтобы
 * владелец мог сравнить.
 */
data class PageMargins(val inner: Dp, val top: Dp, val outer: Dp, val bottom: Dp) {
    /** Сколько ширины и высоты отнимают поля - для разбивки на страницы. */
    val width: Dp get() = inner + outer
    val height: Dp get() = top + bottom

    fun start(side: PageSide): Dp = if (side == PageSide.LEFT) outer else inner
    fun end(side: PageSide): Dp = if (side == PageSide.LEFT) inner else outer
}

fun pageMargins(margin: Int, canon: Boolean): PageMargins {
    if (!canon) return PageMargins(margin.dp, PAGE_TOP, margin.dp, PAGE_BOTTOM)
    // База меньше самого поля: канон задаёт пропорцию, а не размер. На бумаге
    // внешнее поле - две девятых ширины; на телефоне такое оставило бы в
    // строке знаков тридцать, и книга читалась бы колонкой газеты.
    val base = margin / 2.5f
    // Верхнее и нижнее поле держат колонтитул и номер: ниже этого они
    // налезут на текст.
    return PageMargins(
        // Внутреннее поле чуть шире канонических двух долей: у корешка бумага
        // уходит в сгиб, и текст, посаженный вплотную, читается зажатым -
        // владелец на живой сборке: «слишком близко к корню книги печатается».
        inner = (base * 2.8f).dp,
        // Верхнее поле держит колонтитул с линейкой и воздух под ней: текст,
        // начинающийся сразу под линейкой, липнет к верхнему краю - владелец
        // на живой сборке это и увидел.
        top = (base * 4.2f).coerceAtLeast(34f).dp,
        outer = (base * 4).dp,
        // Нижнее поле, наоборот, ужато: канонические шесть долей - про
        // бумажный разворот, где места вдоволь, а на экране это провал под
        // текстом и потерянная строка.
        bottom = (base * 3.2f).coerceAtLeast(22f).dp,
    )
}

/**
 * Поля карточки и ленты: сверху колонтитул, снизу номер. Нижнее было больше
 * верхнего на восемь точек - на живой сборке это читалось провалом под
 * текстом («снизу очень много, сверху нормально»).
 */
internal val PAGE_TOP = 52.dp
/** Воздух над заголовком главы и под ним - те же числа и в разбивке, и в рисунке. */
internal val HEADING_TOP = 24.dp
internal val HEADING_GAP = 14.dp
internal val PAGE_BOTTOM = 44.dp
/**
 * Сколько абзацев вокруг текущего места разбивать на страницы за раз.
 *
 * Было 260. С переносами разбор строк абзацный, а не простой, и считается он
 * заметно дольше - окно подрезано, чтобы «Размечаю страницы…» не растягивалось
 * на глазах. Края окна пересчитываются на подходе, так что читателю видна та
 * же бесконечная книга.
 */
internal const val WINDOW_BLOCKS = 160
