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

// ------------------------------------------------------------------ прокрутка

@Composable
internal fun ScrollBody(
    app: SlushalkaApp,
    bookId: String,
    blocks: List<Block>,
    palette: ReaderPalette,
    hits: TextHits,
    tones: PaperTones,
    look: PageLook,
    shape: BookShape,
    marks: PageMarks,
    margins: PageMargins,
    /** Тап по главе в нижнем колонтитуле открывает содержание. */
    onChapters: () -> Unit,
    /** Отбивка между абзацами; ноль, когда абзацы отступом. */
    gap: Dp,
    styleFor: (heading: Boolean, head: Boolean) -> TextStyle,
    isHeading: (Block) -> Boolean,
    /** Панели видны и сколько они закрывают сверху и снизу (px). */
    bars: Boolean,
    topBarPx: Int,
    bottomBarPx: Int,
    target: Int?,
    onTargetUsed: () -> Unit,
    /** Что на экране: от начала первого видимого абзаца до конца последнего. */
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
    /** Кнопка листания: вперёд или назад на страницу по строкам. */
    keyTurn: KeyTurn?,
) {
    val listState = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val density = LocalDensity.current
    val statusPx = with(density) { WindowInsets.statusBars.asPaddingValues().calculateTopPadding().roundToPx() }
    val navPx = with(density) { WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding().roundToPx() }
    // Где на экране лежит сам список - границы строк из [hits] даны в
    // координатах корня, и сравнивать их надо в одних единицах. Экран (корень)
    // и список теперь не одно и то же: список сидит внутри страницы, между
    // колонтитулами, а панели висят по краям экрана.
    var screenTop by remember { mutableStateOf(0f) }
    var screenBottom by remember { mutableStateOf(0f) }
    // Где сам слой жестов: тапы приходят в его координатах, а раскладка текста - в корневых.
    var origin by remember { mutableStateOf(Offset.Zero) }
    var listTop by remember { mutableStateOf(0f) }
    var listBottom by remember { mutableStateOf(0f) }

    LaunchedEffect(target) {
        val to = target ?: return@LaunchedEffect
        listState.scrollToItem(blocks.indexOfLast { it.start <= to }.coerceAtLeast(0))
        onTargetUsed()
    }

    /**
     * Читаемая полоса: между панелями, когда они видны, и между часами и
     * жестовой полоской, когда спрятаны. Тап по краю листает ровно на неё.
     */
    fun band(): ClosedFloatingPointRange<Float> {
        // Видимая полоса списка минус то, что закрывают панели (или часы и
        // жестовая полоска, когда панелей нет).
        val top = maxOf(listTop, screenTop + (if (bars) topBarPx else statusPx))
        val bottom = minOf(listBottom, screenBottom - (if (bars) bottomBarPx else navPx))
        return if (bottom > top) top..bottom else listTop..listBottom
    }

    /**
     * На страницу вперёд - по строкам, как в режиме страниц: следующая
     * начинается с первой строки, которая не влезла целиком. Прежний шаг
     * «0,88 экрана» не совпадал ни с видимым, ни со строками: с панелями
     * перескакивал строки, без них показывал уже прочитанное.
     */
    fun stepForward(): Float {
        val band = band()
        val height = band.endInclusive - band.start
        val next = hits.lines()
            .filter { (top, bottom) -> top >= band.start && bottom > band.endInclusive }
            .minByOrNull { it.first }
        val step = next?.let { it.first - band.start } ?: height
        // Строка выше полосы или щель между абзацами шире страницы - шаг на всю полосу.
        return if (step < height * 0.3f || step > height * 1.2f) height else step
    }

    /** Назад - на полосу, а затем строка, разрезанная верхним краем, показывается целиком. */
    suspend fun pageBack() {
        val height = band().endInclusive - band().start
        listState.animateScrollBy(-height)
        val top = band().start
        val cut = hits.lines().firstOrNull { (a, b) -> a < top && b > top } ?: return
        listState.animateScrollBy(cut.first - top)
    }
    LaunchedEffect(keyTurn) {
        val k = keyTurn ?: return@LaunchedEffect
        if (k.dir > 0) listState.animateScrollBy(stepForward()) else pageBack()
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

    val card = cardMetrics(look, shape)
    val safeTop = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
    val safeBottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
    Box(
        Modifier
            .fillMaxSize()
            .onGloballyPositioned {
                origin = it.positionInRoot()
                screenTop = origin.y
                screenBottom = screenTop + it.size.height
            }
            .pointerInput(Unit) {
                detectReaderTaps(
                    onTap = { pos ->
                        if (!onTap(pos + origin)) when {
                            pos.x < size.width * 0.28f -> scope.launch { pageBack() }
                            pos.x > size.width * 0.72f -> scope.launch { listState.animateScrollBy(stepForward()) }
                            else -> onToggleBars()
                        }
                    },
                    onTaps = { n, pos -> onTaps(n, pos + origin) },
                    onLongPress = { pos ->
                        // Какой абзац под пальцем - спрашиваем у самого списка:
                        // вложенный обработчик жестов сломал бы листание тапом.
                        val hit = listState.layoutInfo.visibleItemsInfo.firstOrNull {
                            pos.y >= it.offset && pos.y < it.offset + it.size
                        }
                        onLongPress(pos + origin, blocks.getOrNull(hit?.index ?: -1)?.start)
                    },
                )
            },
    ) {
        // Подложка - обложка книги или колода - стоит на месте, страница
        // лежит поверх. Системные отступы держит она, а не текст: страница
        // должна быть видна в экране целиком, всеми четырьмя углами.
        Box(
            Modifier
                .fillMaxSize()
                .padding(underPadding(card, safeTop, safeBottom))
                .pageUnder(tones, look, shape)
        )
        Box(
            Modifier
                .fillMaxSize()
                .padding(pagePadding(look, card, PageSide.SINGLE, safeTop, safeBottom))
                .pageSheet(tones, look, shape, PageSide.SINGLE)
        ) {
            LazyColumn(
                state = listState,
                // Список сидит между колонтитулами, а не под ними: иначе строка
                // уезжала под номер страницы и обрывалась краем карточки -
                // владелец это увидел первым делом. Внутри списка отступов по
                // вертикали нет, поэтому строка режется ровно по краю полосы, как
                // в любом окне, а не посреди колонтитула.
                modifier = Modifier
                    .fillMaxSize()
                    .padding(top = margins.top, bottom = margins.bottom)
                    .onGloballyPositioned {
                        listTop = it.positionInRoot().y
                        listBottom = listTop + it.size.height
                    },
                contentPadding = PaddingValues(
                    start = margins.start(PageSide.SINGLE),
                    end = margins.end(PageSide.SINGLE),
                    bottom = 40.dp,
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
                        // Абзац докладывает, где лежит и как разложен, - ради обводки.
                        DisposableEffect(block.start) { onDispose { hits.forget(block.start) } }
                        Text(
                            text = litText(block.text, block.start, ink, palette),
                            style = styleFor(heading, true),
                            onTextLayout = { hits.layout(block.start, it) },
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(top = if (heading) 28.dp else 0.dp, bottom = if (heading) 14.dp else gap)
                                .onGloballyPositioned { hits.place(block.start, it.boundsInRoot()) },
                        )
                    }
                }
            }
            PageMarksLayer(marks, PageSide.SINGLE, palette, margins, styleFor(false, false), onChapters)
        }
    }
}
