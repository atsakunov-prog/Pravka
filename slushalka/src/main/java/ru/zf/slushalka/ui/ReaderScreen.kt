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
    onAsk: (charOffset: Int, question: String?, quote: String?) -> Unit,
    hasMic: () -> Boolean,
    onNeedMic: () -> Unit,
    /** Разговор о книге: место чтения и дочитана ли. */
    onTalk: (cutoff: Int, finished: Boolean) -> Unit,
) {
    val state = app.state
    val book by state.current.collectAsState()
    val text by state.text.collectAsState()
    val stored by state.prefs.collectAsState()
    // Дальше читалка видит настройки через режим e-ink: он подменяет то, что
    // электронная бумага показывает плохо, а сохранённое не трогает.
    val prefs = stored.readerView()
    val eink = stored.readerEink
    val busy by state.busy.collectAsState()
    val play by app.player.state.collectAsState()
    val speech by app.readAloud.state.collectAsState()
    val recapOffer by state.recapOffer.collectAsState()
    val recapRequest by state.recapRequest.collectAsState()

    var bars by remember { mutableStateOf(true) }
    var showRate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRecap by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var guideQuery by remember { mutableStateOf("") }
    // «Обвести и спросить»: режим включён из листа Claude, росчерк
    // превращается в диапазон знаков по раскладке текста (см. Lasso.kt).
    var lasso by remember { mutableStateOf(false) }
    val hits = remember { TextHits() }
    // Выделенный кусок: два тапа - слово, три - фраза, четыре - абзац, долгое
    // нажатие и обводка - тоже сюда. Пока он есть, нижняя плашка - про него.
    var selection by remember { mutableStateOf<IntRange?>(null) }
    var showMore by remember { mutableStateOf(false) }
    var showClaude by remember { mutableStateOf(false) }
    var showNotes by remember { mutableStateOf(false) }
    var showSearch by remember { mutableStateOf(false) }
    // Кнопка листания (громкость, PageDown электронной книги) - просьба телу читалки.
    var keyTurn by remember { mutableStateOf<KeyTurn?>(null) }
    DisposableEffect(Unit) {
        PageKeys.listener = { dir -> keyTurn = KeyTurn(dir, (keyTurn?.seq ?: 0) + 1) }
        onDispose { PageKeys.listener = null }
    }
    // «Дочитал - поговорим?» спрашивается раз за открытие книги, не на каждой странице.
    var talkOffered by remember(book?.id) { mutableStateOf(false) }
    // Открытая пометка: новая (из выделения) или прежняя (тап по маркеру).
    var editing by remember { mutableStateOf<ru.zf.slushalka.data.Note?>(null) }
    var editingByVoice by remember { mutableStateOf(false) }
    var showGallery by remember { mutableStateOf(false) }
    var picture by remember { mutableStateOf<ShownPicture?>(null) }
    var notice by remember { mutableStateOf<String?>(null) }
    // Плашка «делаю прямо сейчас» держится, пока идёт дело, а не гаснет по
    // таймеру, как короткие сообщения.
    var working by remember { mutableStateOf<String?>(null) }
    var pendingHighlight by remember { mutableStateOf<Int?>(null) }
    // Что на экране: [offset] - верх (начало страницы или первого абзаца),
    // [shownEnd] - конец видимого. [place] - точное место, с которого пришли
    // из записи; пока оно на экране, местом чтения считается оно, а не верх.
    var offset by remember { mutableIntStateOf(0) }
    var shownEnd by remember { mutableIntStateOf(0) }
    var place by remember { mutableStateOf<Int?>(null) }
    var target by remember { mutableStateOf<Int?>(null) }
    var highlightRange by remember { mutableStateOf<IntRange?>(null) }
    val highlight = remember { androidx.compose.animation.core.Animatable(0f) }

    // Бумага по времени суток сверяется с часами раз в пять минут: вечером
    // книга сама переходит на сепию, ночью - на тёплую.
    var hour by remember { mutableIntStateOf(java.time.LocalTime.now().hour) }
    LaunchedEffect(prefs.readerTheme) {
        while (prefs.readerTheme == Settings.THEME_TIME) {
            hour = java.time.LocalTime.now().hour
            kotlinx.coroutines.delay(5 * 60_000L)
        }
    }
    val palette = readerPalette(prefs.readerTheme, isSystemInDarkTheme(), hour)
    // Объём страницы считается от цвета бумаги: на белой он держится на тенях,
    // на чёрной - на засветах (см. BookPage.kt).
    // Переплёт красится по обложке книги: владелец просил, чтобы цвет книги
    // изнутри совпадал с её обложкой из fb2. Обложка уже лежит в кэше Covers
    // (её показывает полка); если книга без обложки - остаётся крафт.
    val context = LocalContext.current
    var coverSeed by remember(book?.id) { mutableStateOf<Color?>(null) }
    LaunchedEffect(book?.id) {
        val bk = book ?: return@LaunchedEffect
        val tree = app.state.treeOf(bk) ?: return@LaunchedEffect
        val bmp = runCatching { Covers.load(context, tree, bk, app.texts) }.getOrNull()
        coverSeed = bmp?.let { coverTone(it) }?.let { Color(it) }
    }
    val tones = remember(palette.bg, prefs.readerTable, coverSeed) {
        PaperTones(palette.bg, prefs.readerTable, coverSeed)
    }
    // Что включено в виде страницы - одним набором, а не восемью параметрами.
    val look = PageLook(
        style = prefs.readerPageStyle,
        margin = prefs.readerCardMargin.dp,
        shadow = prefs.readerShadow,
        bevel = prefs.readerBevel,
        sheen = prefs.readerSheen,
        grain = prefs.readerGrain,
    )

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

    // Типограф работает по блокам, а не при рисовании: разбивка на страницы
    // меряет тот же текст, что рисуется. Все замены сохраняют длину строки
    // (пробел на неразрывный, «пробел-дефис-пробел» на «пробел-тире-пробел»),
    // поэтому места в книге - разметка, позиции, синк - не едут ни на знак.
    val blocks = remember(t, prefs.readerTypograph) {
        if (!prefs.readerTypograph) t.blocks
        else t.blocks.map { if (it.picture != null) it else it.copy(text = typograph(it.text)) }
    }
    // Считается один раз на книгу: панель читалки перерисовывается на каждой
    // прокрутке, и лазить в файловую систему на каждом кадре ей незачем.
    val hasPictures = remember(t, bk.id) { app.state.picturesOnDisk() > 0 }
    val chapterStarts = remember(t) { t.chapters.map { it.start }.toHashSet() }
    val isHeading: (Block) -> Boolean = { it.picture == null && it.start in chapterStarts && it.text.length < 120 }
    // Первый абзац главы: он идёт сразу за заголовком и набирается без
    // абзацного отступа - отступ отделяет абзац от предыдущего, а до него
    // ничего нет. Так набирают книги, и капитель ставится тоже сюда.
    val chapterFirst = remember(t) {
        val set = HashSet<Int>()
        t.blocks.forEachIndexed { i, b ->
            if (b.picture == null && b.start in chapterStarts && b.text.length < 120) {
                t.blocks.getOrNull(i + 1)?.let { if (it.picture == null) set.add(it.start) }
            }
        }
        set
    }
    val noIndent: (Int) -> Boolean = { it in chapterFirst }
    // Канон - только в книжном виде и только при листании страницами: полоса
    // смещается к корешку, а у карточки из колоды корешка нет, и смещённый
    // текст читался бы там перекосом, а не набором.
    val margins = pageMargins(
        prefs.readerMargin,
        prefs.readerCanon && prefs.readerPaged && prefs.readerPageStyle == Settings.PAGE_VOLUME,
    )

    // Отбивка между абзацами: в книге её нет, абзац начинается отступом первой
    // строки. Тумблер «Абзацный отступ» переключает одно на другое разом - и
    // здесь, и в разбивке на страницы, иначе они разошлись бы.
    val paragraphGap = if (prefs.readerIndent) 0.dp else 10.dp

    /** [head] - кусок начинает абзац; продолжению на новой странице отступ не положен. */
    fun styleFor(heading: Boolean, head: Boolean = true) = TextStyle(
        // Отступ в четверть с небольшим кегля: полтора было по-машинописному
        // много, в книгах отступ примерно в кегль с четвертью.
        textIndent = if (prefs.readerIndent && !heading && head) TextIndent(firstLine = 1.25.em)
        else TextIndent.None,
        // Переносы: без них выключка по ширине растаскивает строку дырами -
        // на широком экране это видно сразу.
        //
        // Разбиение при них абзацное, а не простое, и это не украшение:
        // с простым Android переносов не делает вовсе - владелец проверил на
        // живой сборке («переносы не появились»). Плата - разбивка на страницы
        // считается дольше; окно вокруг места мы за это подрезали.
        hyphens = if (prefs.readerHyphens) Hyphens.Auto else Hyphens.None,
        lineBreak = if (prefs.readerHyphens) LineBreak.Paragraph else LineBreak.Simple,
        fontFamily = fontOf(prefs.readerFont),
        fontSize = (if (heading) prefs.readerSize + 3 else prefs.readerSize).sp,
        lineHeight = (prefs.readerSize * prefs.readerLineHeight).sp,
        fontWeight = when {
            heading -> FontWeight.Bold
            // На электронной бумаге основной текст - на ступень жирнее.
            eink -> FontWeight.Medium
            else -> FontWeight.Normal
        },
        // Краска чуть растекается по бумаге: еле заметный ореол того же
        // цвета. Без него буквы выглядят вырезанными, а не напечатанными.
        shadow = if (prefs.readerImperfect) {
            androidx.compose.ui.graphics.Shadow(
                color = palette.fg.copy(alpha = 0.30f),
                offset = androidx.compose.ui.geometry.Offset.Zero,
                blurRadius = 0.7f,
            )
        } else null,
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

    // Справочник: заказанный пакетом - проверить, готовый - положить файлом в
    // папку книги, чужой из папки - подхватить. Всё при открытии книги, чтобы
    // в лист идти уже за готовым.
    LaunchedEffect(bk.id) { app.guide.sync(bk, t) }

    // Найденное место коротко подсвечивается и гаснет: глазами сразу видно,
    // откуда читать, а через пару секунд ничто не мешает тексту.
    LaunchedEffect(pendingHighlight) {
        val at = pendingHighlight ?: return@LaunchedEffect
        // Подсвечиваем именно ту фразу, на которой остановилась запись, а не
        // весь абзац: глаз цепляется за неё сразу.
        highlightRange = t.sentenceAt(at)
        highlight.snapTo(1f)
        if (eink) {
            // На e-ink плавное угасание - два десятка перерисовок экрана с
            // мерцанием. Подсветка просто держится и исчезает разом.
            kotlinx.coroutines.delay(4000)
            highlight.snapTo(0f)
            highlightRange = null
            pendingHighlight = null
            return@LaunchedEffect
        }
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

    // Смена способа листать посреди чтения: новое тело читалки открывается на
    // той же странице. Само оно места не знает - переход ему задаётся целью,
    // а цель к этому моменту уже израсходована прежним телом. Без этого
    // страницы ждали бы якоря вечно («Размечаю страницы…»), а прокрутка
    // начиналась бы с первой строки книги и туда же записывала место чтения.
    var pagedNow by remember { mutableStateOf(prefs.readerPaged) }
    LaunchedEffect(prefs.readerPaged) {
        if (pagedNow == prefs.readerPaged) return@LaunchedEffect
        pagedNow = prefs.readerPaged
        target = readPlace()
        place = null
    }

    // Высота панелей - для листания тапом в прокрутке: под панелями текст не
    // читают, и «страница» - это полоса между ними. Помнится и когда панели
    // спрятаны, чтобы не мерить заново при каждом появлении.
    var topBarPx by remember { mutableIntStateOf(0) }
    var bottomBarPx by remember { mutableIntStateOf(0) }

    // Озвучка этой книги: подсвечивается читаемая фраза, а страница идёт за
    // чтецом - когда фраза уходит за край экрана, читалка перелистывает к ней.
    val speakingHere = speech.active && speech.bookId == bk.id
    val speechRange = if (speakingHere && speech.speaking) speech.range else null
    LaunchedEffect(speech.charOffset, speakingHere, speech.speaking) {
        if (!speakingHere || !speech.speaking) return@LaunchedEffect
        if (speech.charOffset < offset || speech.charOffset >= shownEnd) target = speech.charOffset
    }

    LaunchedEffect(offset, shownEnd, place) {
        // Место чтения пишется на диск, когда листание успокоилось, - и запись
        // подтягивается к нему тем же движением.
        kotlinx.coroutines.delay(700)
        state.saveReadChar(readPlace())
    }
    DisposableEffect(Unit) {
        onDispose {
            state.saveReadChar(readPlace())
            // Ушли с экрана - чтение глазами кончилось, журнал подходов об этом узнаёт.
            state.readerClosed()
        }
    }

    // «Напомнить» с полки: пересказ открывается сам, когда место уже встало,
    // - иначе он пересказал бы книгу до первой страницы.
    LaunchedEffect(recapRequest, shownEnd) {
        if (shownEnd > 0 && state.takeRecapRequest(bk.id)) showRecap = true
    }

    LaunchedEffect(notice) {
        if (notice != null) {
            kotlinx.coroutines.delay(2600)
            notice = null
        }
    }

    // Сколько книги позади: столько бумаги перешло в левую стопку. Место берём
    // верхом экрана, а не readPlace(): стопка меняется от страницы к странице,
    // а не от того, откуда пришли со звука.
    val progress = (offset.toFloat() / t.length.coerceAtLeast(1)).coerceIn(0f, 1f)
    // Толщина книги - от её объёма, и на всю книгу одна: рассказ и эпопея
    // должны выглядеть по-разному, но внутри книги ничего не ездит.
    val thickness = remember(t) { bookThickness(t.length) }
    // Ширина окна - для разворота. Берём у окна, а не у Configuration: масштаб
    // интерфейса меняет плотность, и системные dp разошлись бы с нашими.
    val screenWidth = with(LocalDensity.current) {
        LocalWindowInfo.current.containerSize.width.toDp()
    }
    val spread = spreadOn(prefs.readerSpread, prefs.readerPaged, screenWidth)
    // Узкий экран показывает половину настоящего разворота - с корешком и
    // полоской соседней страницы за ним. «Всегда одна» оставляет книгу
    // целиком в экране.
    val shape = BookShape(
        thickness = thickness,
        progress = progress,
        spread = spread,
        half = prefs.readerPaged && !spread &&
            prefs.readerPageStyle == Settings.PAGE_VOLUME &&
            prefs.readerSpread != Settings.SPREAD_OFF,
    )
    val card = cardMetrics(look, shape)
    // Колонтитул в нижнем углу страницы: панель прячется, а «где я в книге»
    // хочется видеть всегда.
    // Номер считается от начала самой страницы, а не от верха экрана: в
    // развороте страниц две, и на живой сборке обе показывали один и тот же
    // номер.
    val marksAt: (Int, PageNo?) -> PageMarks = { at, no ->
        val share = (at.toFloat() / t.length.coerceAtLeast(1)).coerceIn(0f, 1f)
        val page = no?.page ?: t.pageOf(at)
        val total = no?.total ?: t.pages
        when (prefs.readerFooter) {
            Settings.FOOTER_NONE -> PageMarks()
            Settings.FOOTER_PAGE -> PageMarks(corner = "$page / $total")
            Settings.FOOTER_PERCENT -> PageMarks(corner = "${(share * 100).roundToInt()}%")
            Settings.FOOTER_BOTH -> PageMarks(
                corner = "$page / $total · ${(share * 100).roundToInt()}%"
            )
            // Как в типографской книге: автор и название на верхнем поле, номер
            // страницы внизу по центру. Без тире вокруг: в книгах номер стоит
            // голым, тире - это из машинописи.
            else -> PageMarks(
                author = t.author.ifBlank { bk.title },
                title = t.title.ifBlank { bk.title },
                // Внизу - глава и номер со всем объёмом: по главе открывается
                // содержание, и из книги видно, где ты и сколько осталось.
                chapter = t.chapterAt(at)?.title.orEmpty(),
                center = "$page / $total",
            )
        }
    }
    val marks = marksAt(offset, null)

    // Пометки книги: маркером в тексте и списком за кнопкой «Пометки».
    val notesRev by app.notes.revision.collectAsState()
    val notes = remember(notesRev, bk.id) { app.notes.of(bk.id) }
    val (selectionColor, noteColor) = inkColors(palette, eink)
    val ink = TextInk(
        highlight = speechRange ?: highlightRange,
        highlightAlpha = if (speechRange != null) SPEECH_ALPHA else highlight.value,
        selection = selection,
        notes = remember(notes) { notes.map { it.start..it.end } },
        selectionColor = selectionColor,
        noteColor = noteColor,
        // Тёплый маркер на e-ink - бледно-серая плашка, её не видно:
        // пометка там ещё и подчёркнута.
        underlineNotes = eink,
    )
    val clipboard = androidx.compose.ui.platform.LocalClipboardManager.current

    // Что сейчас на экране - чтобы тап попадал в видимую страницу, а не в
    // прозрачную соседнюю, которая при растворении лежит на том же месте.
    fun onScreen(start: Int): Boolean = start >= offset && start < shownEnd.coerceAtLeast(offset + 1)

    /** Тап по тексту: снять выделение или открыть пометку под пальцем. true - тап съеден. */
    fun tapText(root: androidx.compose.ui.geometry.Offset): Boolean {
        if (selection != null) {
            selection = null
            return true
        }
        val at = hits.charAt(root, ::onScreen) ?: return false
        val note = notes.firstOrNull { at >= it.start && at < it.end } ?: return false
        editing = note
        editingByVoice = false
        return true
    }

    fun selectTaps(count: Int, root: androidx.compose.ui.geometry.Offset, fallback: Int? = null) {
        val at = hits.charAt(root, ::onScreen) ?: fallback ?: return
        selectionFor(count, t, blocks, at)?.let { selection = it }
    }

    fun quoteOf(r: IntRange): String =
        t.plain.substring(r.first.coerceIn(0, t.length), r.last.coerceIn(r.first, t.length)).trim()

    fun newNote(r: IntRange, voice: Boolean) {
        val now = System.currentTimeMillis()
        editing = ru.zf.slushalka.data.Note(
            id = now, start = r.first, end = r.last, quote = quoteOf(r).take(NOTE_QUOTE_MAX),
            text = "", updatedAt = now,
        )
        editingByVoice = voice
    }

    // Плашки узнают о режиме e-ink отсюда: без теней, анимаций и полутонов.
    androidx.compose.runtime.CompositionLocalProvider(LocalEink provides eink) {
    Box(Modifier.fillMaxSize().readerBackdrop(tones, look)) {
        if (prefs.readerPaged) {
            PagedBody(
                app = app, bookId = bk.id, blocks = blocks, palette = palette, hits = hits,
                tones = tones, look = look, turn = prefs.readerPageTurn,
                shape = shape, marksAt = marksAt,
                margins = margins, onChapters = { showChapters = true }, gap = paragraphGap,
                styleFor = ::styleFor, isHeading = isHeading,
                noIndent = noIndent, smallCaps = prefs.readerSmallCaps,
                imperfect = prefs.readerImperfect,
                target = target, onTargetUsed = { target = null },
                onShown = { start, end -> offset = start; shownEnd = end },
                onTap = ::tapText,
                onToggleBars = { bars = !bars },
                onTaps = { n, root -> selectTaps(n, root) },
                onLongPress = { root, fallback -> selectTaps(4, root, fallback) },
                onPicture = { picture = it },
                ink = ink,
                keyTurn = keyTurn,
                instant = eink,
            )
        } else {
            ScrollBody(
                app = app, bookId = bk.id, blocks = blocks, palette = palette, hits = hits,
                tones = tones, look = look, shape = shape, marks = marks,
                margins = margins, onChapters = { showChapters = true }, gap = paragraphGap,
                styleFor = ::styleFor, isHeading = isHeading,
                bars = bars, topBarPx = topBarPx, bottomBarPx = bottomBarPx,
                target = target, onTargetUsed = { target = null },
                onShown = { start, end -> offset = start; shownEnd = end },
                onTap = ::tapText,
                onToggleBars = { bars = !bars },
                onTaps = { n, root -> selectTaps(n, root) },
                onLongPress = { root, fallback -> selectTaps(4, root, fallback) },
                onPicture = { picture = it },
                ink = ink,
                keyTurn = keyTurn,
            )
        }

        if (lasso) {
            LassoLayer(palette) { rect ->
                val picked = hits.select(rect)?.let { snapToWords(t.plain, it) }
                lasso = false
                if (picked == null || picked.last - picked.first < 2) {
                    notice = "Не попал по тексту - попробуй обвести ещё раз"
                } else {
                    selection = picked
                }
            }
        }

        // Нижняя плашка видна, пока есть выделение: действия с ним живут в ней.
        val bottomShown = bars || selection != null

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
            // Пока нижняя плашка на экране, значок картинки стоит над ней, а не под.
            val lift = with(LocalDensity.current) { if (bottomShown) bottomBarPx.toDp() else 0.dp }
            PictureChip(file, palette, Modifier.align(Alignment.BottomEnd).padding(bottom = lift).padding(14.dp)) {
                picture = ShownPicture(file, pic.caption, pic.charOffset)
            }
        }

        AnimatedVisibility(
            visible = bars && selection == null,
            enter = barEnter(fromTop = true, eink = eink),
            exit = barExit(fromTop = true, eink = eink),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // Высота меряется вместе с отступами: по ней прокрутка решает,
            // сколько строк закрыто плашкой, а плашка парит не у края.
            Box(
                Modifier
                    .onSizeChanged { topBarPx = it.height }
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = maxOf(card.side, BAR_INSET), vertical = 6.dp),
            ) {
                BarCard(palette) {
                    Row(
                        Modifier.padding(horizontal = 4.dp, vertical = 2.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        // Книге без записи плеер не нужен - «назад» ведёт на полку.
                        TextButton(onClick = onBack) {
                            Text(if (bk.hasAudio) "‹ Плеер" else "‹ Полка", color = palette.fg)
                        }
                        // Глава - она же вход в содержание: где я и куда уйти,
                        // в одном месте, а не двумя кнопками.
                        Column(
                            Modifier
                                .weight(1f)
                                .clip(RoundedCornerShape(10.dp))
                                .clickable { showChapters = true }
                                .padding(horizontal = 6.dp, vertical = 4.dp),
                        ) {
                            Text(
                                t.chapterAt(offset)?.title?.takeIf { it.isNotBlank() } ?: "Содержание",
                                color = palette.fg,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                                fontSize = 13.sp,
                            )
                            Text(
                                "глава ${t.chapterIndexAt(offset) + 1} из ${t.chapters.size.coerceAtLeast(1)} · содержание",
                                color = palette.dim,
                                maxLines = 1,
                                fontSize = 10.sp,
                            )
                        }
                        BarIcon(Glyphs.ManageSearch, "Найти по смыслу", palette) { showSearch = true }
                        if (hasPictures) BarIcon(Glyphs.Image, "Картинки", palette) { showGallery = true }
                        BarIcon(Glyphs.TextFields, "Вид", palette) { showSettings = true }
                    }
                }
            }
        }

        AnimatedVisibility(
            visible = bottomShown,
            enter = barEnter(fromTop = false, eink = eink),
            exit = barExit(fromTop = false, eink = eink),
            modifier = Modifier.align(Alignment.BottomCenter),
        ) {
            Box(
                Modifier
                    .onSizeChanged { bottomBarPx = it.height }
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(
                        start = maxOf(card.side, BAR_INSET),
                        end = maxOf(card.side, BAR_INSET),
                        top = 6.dp,
                        bottom = maxOf(card.bottom, BAR_INSET),
                    ),
            ) {
                BarCard(palette, Modifier.padding(horizontal = 8.dp, vertical = 6.dp)) {
                    val sel = selection
                    when {
                        sel != null -> SelectionBar(
                            quote = quoteOf(sel),
                            palette = palette,
                            onAsk = {
                                selection = null
                                onAsk(sel.last, null, quoteOf(sel))
                            },
                            onContext = {
                                selection = null
                                onAsk(sel.last, ru.zf.slushalka.ask.Prompts.CONTEXT, quoteOf(sel))
                            },
                            onNote = { newNote(sel, voice = false) },
                            onVoiceNote = { newNote(sel, voice = true) },
                            onCopy = {
                                clipboard.setText(androidx.compose.ui.text.AnnotatedString(quoteOf(sel)))
                                notice = "Скопировал"
                                selection = null
                            },
                            onMore = { showMore = true },
                            onClear = { selection = null },
                        )

                        speakingHere -> {
                            // Озвучка идёт: вместо кнопок - управление ею. Абзац назад и
                            // вперёд, пауза, темп, выключить.
                            BookProgress(t, offset, palette, Modifier.padding(horizontal = 6.dp, vertical = 4.dp))
                            Row(
                                Modifier.fillMaxWidth(),
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(2.dp),
                            ) {
                                TextButton(onClick = { app.readAloud.skip(-1) }) { Text("‹ абзац", color = palette.fg) }
                                PlayPauseButton(speech.speaking, size = 46.dp) { app.readAloud.playPause() }
                                TextButton(onClick = { app.readAloud.skip(+1) }) { Text("абзац ›", color = palette.fg) }
                                Spacer(Modifier.weight(1f))
                                SpeedButton(speech.rate, size = 40.dp) { showRate = true }
                                TextButton(onClick = { app.readAloud.stop() }) { Text("Стоп", color = palette.fg) }
                            }
                            speech.error?.let { err -> Text(err, color = palette.dim, fontSize = 12.sp) }
                        }

                        else -> {
                            Row(
                                Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                val share = (offset * 100L / t.length.coerceAtLeast(1)).toInt().coerceIn(0, 100)
                                Text("$share%", color = palette.dim, fontSize = 11.sp)
                                Spacer(Modifier.width(8.dp))
                                BookProgress(t, offset, palette, Modifier.weight(1f))
                                Spacer(Modifier.width(8.dp))
                                LoveLine(alpha = 0.3f, size = 10, color = palette.fg)
                            }
                            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                                if (bk.hasAudio) {
                                    BarAction(Glyphs.Headphones, "Слушать", palette) {
                                        state.listenFrom(readPlace())
                                        onListen()
                                    }
                                } else {
                                    // Записи нет - читает синтез речи, с этой страницы.
                                    BarAction(Glyphs.RecordVoiceOver, "Озвучить", palette) {
                                        app.readAloud.start(bk, t, readPlace())
                                    }
                                }
                                BarAction(Glyphs.AutoAwesome, "Claude", palette) { showClaude = true }
                                BarAction(Glyphs.MenuBook, "Справочник", palette) {
                                    guideQuery = ""
                                    showGuide = true
                                }
                                BarAction(Glyphs.EditNote, "Пометки", palette, badge = notes.size) { showNotes = true }
                            }
                            if (!bk.hasAudio) {
                                speech.error?.let { err -> Text(err, color = palette.dim, fontSize = 12.sp) }
                            }
                        }
                    }
                }
            }
        }

        // Дочитал - предложить поговорить о книге. Раньше дочитанная книга
        // молча уходила на полку.
        val atEnd = shownEnd >= t.length * 0.97 && t.length > 0
        if (atEnd && !talkOffered && !recapOffer) {
            val drop = with(LocalDensity.current) { if (bars && selection == null) topBarPx.toDp() else 0.dp }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = drop + 4.dp)
                    .padding(horizontal = maxOf(card.side, BAR_INSET) + 12.dp),
            ) {
                BarCard(palette, Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                    Text("Дочитал. Поговорим о книге?", color = palette.fg, fontSize = 13.sp)
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { talkOffered = true }) { Text("Потом", color = palette.dim) }
                        TextButton(onClick = {
                            talkOffered = true
                            onTalk(t.length, true)
                        }) { Text("Поговорим", color = palette.fg) }
                    }
                }
            }
        }

        // Вернулся после перерыва - предложить вспомнить, на чём остановился.
        // Раньше это спрашивал только плеер, а книгу без записи - никто.
        if (recapOffer && !showRecap) {
            val drop = with(LocalDensity.current) { if (bars && selection == null) topBarPx.toDp() else 0.dp }
            Box(
                Modifier
                    .align(Alignment.TopCenter)
                    .statusBarsPadding()
                    .padding(top = drop + 4.dp)
                    .padding(horizontal = maxOf(card.side, BAR_INSET) + 12.dp),
            ) {
                BarCard(palette, Modifier.padding(start = 14.dp, end = 4.dp, top = 8.dp, bottom = 4.dp)) {
                    Text(
                        "Давно не открывал. Напомнить, на чём остановился?",
                        color = palette.fg,
                        fontSize = 13.sp,
                    )
                    Row {
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { state.dismissRecap() }) { Text("Не надо", color = palette.dim) }
                        TextButton(onClick = {
                            state.dismissRecap()
                            showRecap = true
                        }) { Text("Напомни", color = palette.fg) }
                    }
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
                    .padding(bottom = with(LocalDensity.current) { if (bottomShown) bottomBarPx.toDp() + 12.dp else 96.dp })
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.fg.copy(alpha = 0.88f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
    }
    }

    // Выделение снимается кнопкой «назад», а не уводит из книги.
    androidx.activity.compose.BackHandler(enabled = selection != null) { selection = null }

    if (showClaude) {
        ClaudeSheet(
            app = app,
            actions = listOf(
                ClaudeAction(Glyphs.QuestionAnswer, "Спросить о книге", "Любой вопрос о прочитанном - без спойлеров") {
                    onAsk(readPlace(), null, null)
                },
                ClaudeAction(Glyphs.History, "Напомнить, что было", "Пересказ последних глав до этой страницы") {
                    showRecap = true
                },
                ClaudeAction(Glyphs.Gesture, "Обвести и спросить", "Обведи пальцем кусок - и спроси про него") {
                    lasso = true
                },
                ClaudeAction(Glyphs.ManageSearch, "Найти по смыслу", "«Где был разговор про балет» - сначала по справочнику") {
                    showSearch = true
                },
                ClaudeAction(Glyphs.Forum, "Поговорить о книге", "Темы от Claude и разговор - как после книжного клуба") {
                    val at = readPlace()
                    onTalk(at, at >= t.length * 0.97)
                },
                ClaudeAction(Glyphs.MenuBook, "Справочник", "Герои, места и словарь - по прочитанным главам") {
                    guideQuery = ""
                    showGuide = true
                },
            ),
            onClose = { showClaude = false },
        )
    }
    if (showNotes) {
        NotesSheet(
            app = app,
            text = t,
            notes = notes,
            title = t.title.ifBlank { bk.title },
            onGo = { n ->
                showNotes = false
                place = null
                target = n.start
                pendingHighlight = null
            },
            onEdit = { n -> showNotes = false; editing = n; editingByVoice = false },
            onClose = { showNotes = false },
        )
    }
    editing?.let { n ->
        val known = notes.any { it.id == n.id }
        NoteEditor(
            app = app,
            note = n,
            hasMic = hasMic,
            onNeedMic = onNeedMic,
            listenAtOnce = editingByVoice,
            onSave = { body ->
                // Пустая новая пометка - это передумал, а не маркер без слов:
                // выделить без мысли можно и так. Прежнюю пустой не стираем.
                if (body.isNotBlank() || known) {
                    app.notes.put(bk.id, n.copy(text = body))
                    app.scope.launch { state.syncPush(bk.id) }
                    notice = "Пометка на полях"
                }
                editing = null
                selection = null
            },
            onDelete = if (known) {
                {
                    app.notes.remove(bk.id, n.id)
                    app.scope.launch { state.syncPush(bk.id) }
                    editing = null
                }
            } else null,
            onAsk = { body ->
                editing = null
                selection = null
                onAsk(n.end, body.ifBlank { null }, n.quote)
            },
            onClose = { editing = null },
        )
    }

    if (showRate) {
        ReadAloudRateDialog(app, speech.rate) { showRate = false }
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
            onAsk = { at, q -> showGallery = false; onAsk(at, q, null) },
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
    if (showChapters) {
        ContentsSheet(
            app = app,
            text = t,
            currentOffset = offset,
            onPick = { start ->
                showChapters = false
                place = null
                target = start
            },
            onClose = { showChapters = false },
        )
    }
    if (showGuide) {
        GuideSheet(
            app = app,
            cutoffChar = readPlace(),
            initialQuery = guideQuery,
            onAsk = { q -> showGuide = false; onAsk(readPlace(), q, null) },
            onGoChapter = { n ->
                t.chapters.getOrNull(n - 1)?.let { c ->
                    showGuide = false
                    place = null
                    target = c.start
                }
            },
            onClose = { showGuide = false },
        )
    }
    if (showSearch) {
        SearchSheet(
            app = app,
            book = bk,
            text = t,
            cutoff = readPlace().coerceAtLeast(shownEnd),
            hasMic = hasMic,
            onNeedMic = onNeedMic,
            onGo = { at ->
                showSearch = false
                place = null
                target = at
                pendingHighlight = at
            },
            onClose = { showSearch = false },
        )
    }
    picture?.let { shown ->
        ImageViewer(
            shown = shown,
            onAsk = {
                picture = null
                onAsk(shown.charOffset, ru.zf.slushalka.ask.Prompts.picture(shown.caption), null)
            },
            onClose = { picture = null },
        )
    }
    // «Ещё» у выделения: готовые вопросы, справочник по упомянутым героям, а у
    // аудиокниги - «Я тут» и «Слушать отсюда». Лист прежний, кусок уже выбран.
    val sel = selection
    if (showMore && sel != null) {
        val block = blocks.getOrNull(t.blockIndexAt(sel.first))
        if (block == null) {
            LaunchedEffect(sel) { showMore = false }
        } else {
            ParagraphSheet(
                app = app,
                text = t,
                block = block,
                hasAudio = bk.hasAudio,
                playAbsMs = play.absMs,
                lasso = quoteOf(sel),
                lassoEnd = sel.last,
                onAsk = { atChar, q, quote ->
                    showMore = false
                    selection = null
                    onAsk(atChar, q, quote)
                },
                onAnchor = {
                    state.addAnchor(play.absMs, sel.first)
                    notice = "Отметил: карта стала точнее"
                    showMore = false
                    selection = null
                },
                onListen = {
                    state.listenFrom(sel.first)
                    showMore = false
                    selection = null
                    onListen()
                },
                onGuide = { q ->
                    showMore = false
                    selection = null
                    guideQuery = q
                    showGuide = true
                },
                onQuoteCard = { quote ->
                    showMore = false
                    selection = null
                    QuoteCard.share(context, quote, t.title.ifBlank { bk.title }, t.author.ifBlank { bk.author }, palette, prefs.readerFont)
                },
                onClose = { showMore = false },
            )
        }
    } else if (showMore) {
        LaunchedEffect(Unit) { showMore = false }
    }
}

/** Длиннее цитата в пометку не уезжает: абзац целиком - уже не цитата, а глава. */
private const val NOTE_QUOTE_MAX = 2000

/**
 * Нижняя плашка при выделении: кусок строкой сверху, под ним - что с ним
 * сделать. Спросить и контекст уходят к Claude, пометка остаётся на полях.
 */
@Composable
private fun ColumnScope.SelectionBar(
    quote: String,
    palette: ReaderPalette,
    onAsk: () -> Unit,
    onContext: () -> Unit,
    onNote: () -> Unit,
    onVoiceNote: () -> Unit,
    onCopy: () -> Unit,
    onMore: () -> Unit,
    onClear: () -> Unit,
) {
    Row(Modifier.padding(start = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(
            "«${quote.replace('\n', ' ')}»",
            color = palette.dim,
            fontSize = 12.sp,
            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f),
        )
        TextButton(onClick = onClear) { Text("Снять", color = palette.fg, fontSize = 12.sp) }
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        BarAction(Glyphs.QuestionAnswer, "Спросить", palette, onClick = onAsk)
        BarAction(Glyphs.TravelExplore, "Контекст", palette, onClick = onContext)
        BarAction(Glyphs.EditNote, "Пометка", palette, onClick = onNote)
        BarAction(Glyphs.Mic, "Голосом", palette, onClick = onVoiceNote)
        BarAction(Glyphs.ContentCopy, "Копия", palette, onClick = onCopy)
        BarAction(Glyphs.FormatQuote, "Ещё", palette, onClick = onMore)
    }
}

/** Подсветка читаемой фразы держится ровно, пока говорит движок, - не гаснет, как найденное место. */
private const val SPEECH_ALPHA = 0.42f

/** Темп озвучки. Значения - как у скорости плеера, только у синтеза шаг заметнее. */
@Composable
private fun ReadAloudRateDialog(app: SlushalkaApp, rate: Float, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    PaperSheet(app = app, onClose = onClose, icon = Glyphs.Speed, title = "Темп озвучки", subtitle = formatSpeed(rate)) {
        Spacer(Modifier.height(14.dp))
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f).forEach { r ->
                PaperChip(formatSpeed(r), selected = kotlin.math.abs(rate - r) < 0.01f) {
                    app.readAloud.setRate(r)
                    scope.launch { app.settings.setTtsRate(r) }
                }
            }
        }
        PaperNote("Голос и тембр - в настройках, раздел «Озвучка».", Modifier.padding(top = 12.dp))
    }
}
