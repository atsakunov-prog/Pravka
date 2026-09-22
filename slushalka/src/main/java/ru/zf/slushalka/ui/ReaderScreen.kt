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
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.BookText

/** Цвета читалки живут отдельно от темы приложения: их переключают по свету, а не по системе. */
data class ReaderPalette(val bg: Color, val fg: Color, val dim: Color)

fun readerPalette(theme: String, dark: Boolean): ReaderPalette = when (theme) {
    // Краска, а не чернила: в книге буквы не угольно-чёрные, а тёмно-серые с
    // тёплым уходом - владелец попросил «шрифт как в книге, чуть более серый».
    // Абсолютный чёрный на светлой бумаге к тому же режет глаз на экране.
    // Бумага книжная, а не офисная: тёплый кремовый тон вместо почти белого.
    Settings.THEME_PAPER -> ReaderPalette(Color(0xFFF4EFE4), Color(0xFF2E2A25), Color(0xFF6E6659))
    Settings.THEME_SEPIA -> ReaderPalette(Color(0xFFF3E6CE), Color(0xFF4A3B26), Color(0xFF8A7550))
    Settings.THEME_GREY -> ReaderPalette(Color(0xFF2A2D33), Color(0xFFCBC8C1), Color(0xFF8B8880))
    Settings.THEME_BLACK -> ReaderPalette(Color(0xFF000000), Color(0xFFB6B3AC), Color(0xFF6E6B65))
    else -> if (dark) ReaderPalette(Color(0xFF000000), Color(0xFFB6B3AC), Color(0xFF6E6B65))
    else ReaderPalette(Color(0xFFF4EFE4), Color(0xFF2E2A25), Color(0xFF6E6659))
}

fun fontOf(name: String): FontFamily = when (name) {
    Settings.FONT_SANS -> FontFamily.SansSerif
    Settings.FONT_MONO -> FontFamily.Monospace
    Settings.FONT_SERIF -> FontFamily.Serif
    // Книжная антиква своей гарнитурой: системная «с засечками» на разных
    // прошивках разворачивается в разное, и у владельца книжный вид рисовался
    // гротеском. Literata нарисована для чтения с экрана, кириллица полная.
    else -> LITERATA
}

private val LITERATA = FontFamily(
    Font(R.font.literata_regular, FontWeight.Normal),
    Font(R.font.literata_italic, FontWeight.Normal, FontStyle.Italic),
    Font(R.font.literata_bold, FontWeight.SemiBold),
    Font(R.font.literata_bold, FontWeight.Bold),
)

/**
 * Типограф: то, что в наборе делают руками, а в файле книги обычно не сделано.
 *
 * Неразрывный пробел после коротких слов (предлоги, союзы, частицы) - главное
 * здесь: при выключке по формату «в» или «и» в конце строки сразу видно.
 * Дефис между пробелами - это тире, а не дефис; перед тире тоже неразрывный,
 * иначе оно уезжает в начало строки. Кавычки и сам текст не трогаем: в fb2
 * они обычно уже расставлены, а лезть в текст книги - последнее дело.
 */
fun typograph(text: String): String {
    val nbsp = '\u00A0'
    val dash = '\u2014'
    val sb = StringBuilder(text)
    // 1. Дефис, окружённый пробелами, - это тире, а не дефис. Длина та же.
    for (i in 1 until sb.length - 1) {
        if (sb[i] == '-' && sb[i - 1] == ' ' && sb[i + 1] == ' ') sb[i] = dash
    }
    // 2. Пробел перед тире - неразрывный: тире не должно начинать строку.
    for (i in 1 until sb.length) {
        if (sb[i] == dash && sb[i - 1] == ' ') sb[i - 1] = nbsp
    }
    // Неразрывного пробела после коротких слов здесь нет нарочно, хотя в
    // наборе он положен. Android не растягивает неразрывный пробел при
    // выключке по формату: предлог слипается со своим словом, а остальные
    // пробелы в строке растягиваются вдвое - получается рваный ритм, куда
    // заметнее висячего предлога. Поставить «клей» без растяжения (word
    // joiner) нельзя: он добавил бы знаки, а длина текста здесь
    // неприкосновенна - на ней держатся позиции чтения и разметка.
    return sb.toString()
}

/**
 * Капитель для первых слов главы: Literata малых прописных не содержит, и
 * подмена шрифта тут была бы хуже подделки - берём прописные пониженного
 * кегля с разрядкой, как делают в наборе, когда капители нет.
 */
fun smallCapsHead(text: String, words: Int = 3): Pair<String, Int> {
    var seen = 0
    var i = 0
    while (i < text.length && seen < words) {
        val space = text.indexOf(' ', i)
        if (space < 0) { i = text.length; break }
        i = space + 1
        seen++
    }
    val end = i.coerceAtMost(text.length)
    return text.uppercase() to end
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
    onAsk: (charOffset: Int, question: String?, quote: String?) -> Unit,
) {
    val state = app.state
    val book by state.current.collectAsState()
    val text by state.text.collectAsState()
    val prefs by state.prefs.collectAsState()
    val busy by state.busy.collectAsState()
    val play by app.player.state.collectAsState()
    val speech by app.readAloud.state.collectAsState()

    var bars by remember { mutableStateOf(true) }
    var showRate by remember { mutableStateOf(false) }
    var showSettings by remember { mutableStateOf(false) }
    var showRecap by remember { mutableStateOf(false) }
    var showChapters by remember { mutableStateOf(false) }
    var showGuide by remember { mutableStateOf(false) }
    var guideQuery by remember { mutableStateOf("") }
    // «Обвести и спросить»: режим включён кнопкой, росчерк превращается в
    // диапазон знаков по раскладке текста (см. Lasso.kt).
    var lasso by remember { mutableStateOf(false) }
    var lassoPick by remember { mutableStateOf<IntRange?>(null) }
    val hits = remember { TextHits() }
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
        fontWeight = if (heading) FontWeight.Bold else FontWeight.Normal,
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

    Box(Modifier.fillMaxSize().readerBackdrop(tones, look)) {
        val onLong: (Int) -> Unit = { pressed = it }
        val onTapPicture: (ShownPicture) -> Unit = { picture = it }
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
                onToggleBars = { bars = !bars },
                onPicture = onTapPicture, onLongPress = onLong,
                highlight = speechRange ?: highlightRange,
                highlightAlpha = if (speechRange != null) SPEECH_ALPHA else highlight.value,
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
                onToggleBars = { bars = !bars },
                onPicture = onTapPicture, onLongPress = onLong,
                highlight = speechRange ?: highlightRange,
                highlightAlpha = if (speechRange != null) SPEECH_ALPHA else highlight.value,
            )
        }

        if (lasso) {
            LassoLayer(palette) { rect ->
                val picked = hits.select(rect)?.let { snapToWords(t.plain, it) }
                lasso = false
                if (picked == null || picked.last - picked.first < 2) {
                    notice = "Не попал по тексту - попробуй обвести ещё раз"
                } else {
                    lassoPick = picked
                }
            }
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
            // Пока нижняя плашка на экране, значок картинки стоит над ней, а не под.
            val lift = with(LocalDensity.current) { if (bars) bottomBarPx.toDp() else 0.dp }
            PictureChip(file, palette, Modifier.align(Alignment.BottomEnd).padding(bottom = lift).padding(14.dp)) {
                picture = ShownPicture(file, pic.caption, pic.charOffset)
            }
        }

        AnimatedVisibility(
            visible = bars,
            enter = barEnter(fromTop = true),
            exit = barExit(fromTop = true),
            modifier = Modifier.align(Alignment.TopCenter),
        ) {
            // Высота меряется вместе с отступами: по ней прокрутка решает,
            // сколько строк закрыто плашкой, а плашка теперь парит не у края.
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
            }
        }

        AnimatedVisibility(
            visible = bars,
            enter = barEnter(fromTop = false),
            exit = barExit(fromTop = false),
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
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "стр. ${t.pageOf(offset)} из ${t.pages}",
                            color = palette.dim,
                            fontSize = 12.sp,
                        )
                        Spacer(Modifier.width(10.dp))
                        LoveLine(alpha = 0.3f, size = 10, color = palette.fg)
                    }
                    if (speakingHere) {
                        // Озвучка идёт: вместо кнопок - управление ею. Абзац назад и
                        // вперёд, пауза, темп, выключить.
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
                        speech.error?.let { err ->
                            Text(err, color = palette.dim, fontSize = 12.sp)
                        }
                    } else androidx.compose.foundation.layout.FlowRow(
                        horizontalArrangement = Arrangement.spacedBy(2.dp),
                    ) {
                        if (bk.hasAudio) {
                            TextButton(onClick = {
                                state.listenFrom(readPlace())
                                onListen()
                            }) { Text("Слушать отсюда", color = palette.fg) }
                        } else {
                            // Записи нет - читает синтез речи, с этой страницы.
                            TextButton(onClick = { app.readAloud.start(bk, t, readPlace()) }) {
                                Text("Озвучить", color = palette.fg)
                            }
                        }
                        // «Содержание» - главы, тап - переход. Пересказ «что там было»
                        // раньше жил под этим словом, теперь он - «Напомнить».
                        TextButton(onClick = { showChapters = true }) { Text("Содержание", color = palette.fg) }
                        TextButton(onClick = { showRecap = true }) { Text("Напомнить", color = palette.fg) }
                        TextButton(onClick = { onAsk(readPlace(), null, null) }) { Text("Спросить", color = palette.fg) }
                        TextButton(onClick = { lasso = !lasso }) {
                            Text(if (lasso) "Не обводить" else "Обвести", color = if (lasso) palette.dim else palette.fg)
                        }
                        TextButton(onClick = { guideQuery = ""; showGuide = true }) { Text("Справочник", color = palette.fg) }
                    }
                    if (!speakingHere && !bk.hasAudio) {
                        speech.error?.let { err -> Text(err, color = palette.dim, fontSize = 12.sp) }
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
                    .padding(bottom = with(LocalDensity.current) { if (bars) bottomBarPx.toDp() + 12.dp else 96.dp })
                    .clip(RoundedCornerShape(20.dp))
                    .background(palette.fg.copy(alpha = 0.88f))
                    .padding(horizontal = 14.dp, vertical = 8.dp),
            )
        }
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
            onClose = { showGuide = false },
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
    // Обведённое: тот же лист, что у абзаца, но кусок уже выбран.
    lassoPick?.let { range ->
        val block = blocks.getOrNull(t.blockIndexAt(range.first))
        if (block == null) {
            LaunchedEffect(range) { lassoPick = null }
        } else {
            ParagraphSheet(
                app = app,
                text = t,
                block = block,
                hasAudio = bk.hasAudio,
                playAbsMs = play.absMs,
                lasso = t.plain.substring(range.first, range.last.coerceAtMost(t.length)),
                lassoEnd = range.last,
                onAsk = { atChar, q, quote ->
                    lassoPick = null
                    onAsk(atChar, q, quote)
                },
                onAnchor = {
                    state.addAnchor(play.absMs, block.start)
                    notice = "Отметил: карта стала точнее"
                    lassoPick = null
                },
                onListen = {
                    state.listenFrom(block.start)
                    lassoPick = null
                    onListen()
                },
                onGuide = { q ->
                    lassoPick = null
                    guideQuery = q
                    showGuide = true
                },
                onClose = { lassoPick = null },
            )
        }
    }

    // Долгое нажатие на абзац: спросить про него (или про выделенные фразы),
    // заглянуть в справочник, а у аудиокниги - ещё и поправить карту.
    pressed?.let { at ->
        val block = blocks.getOrNull(t.blockIndexAt(at))?.takeIf { it.picture == null && it.text.isNotBlank() }
        if (block == null) {
            // Под пальцем картинка или пустота - говорить не о чём.
            LaunchedEffect(at) { pressed = null }
        } else {
            ParagraphSheet(
                app = app,
                text = t,
                block = block,
                hasAudio = bk.hasAudio,
                playAbsMs = play.absMs,
                onAsk = { atChar, q, quote ->
                    pressed = null
                    onAsk(atChar, q, quote)
                },
                onAnchor = {
                    state.addAnchor(play.absMs, at)
                    notice = "Отметил: карта стала точнее"
                    pressed = null
                },
                onListen = {
                    state.listenFrom(at)
                    pressed = null
                    onListen()
                },
                onGuide = { q ->
                    pressed = null
                    guideQuery = q
                    showGuide = true
                },
                onClose = { pressed = null },
            )
        }
    }
}

/** Подсветка читаемой фразы держится ровно, пока говорит движок, - не гаснет, как найденное место. */
private const val SPEECH_ALPHA = 0.42f

/** Темп озвучки. Значения - как у скорости плеера, только у синтеза шаг заметнее. */
@Composable
private fun ReadAloudRateDialog(app: SlushalkaApp, rate: Float, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Темп озвучки") },
        text = {
            androidx.compose.foundation.layout.FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
            ) {
                listOf(0.7f, 0.85f, 1.0f, 1.15f, 1.3f, 1.5f, 1.75f, 2.0f).forEach { r ->
                    androidx.compose.material3.FilterChip(
                        selected = kotlin.math.abs(rate - r) < 0.01f,
                        onClick = {
                            app.readAloud.setRate(r)
                            scope.launch { app.settings.setTtsRate(r) }
                        },
                        label = { Text(formatSpeed(r)) },
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
    )
}

// ------------------------------------------------------------------ прокрутка

@Composable
private fun ScrollBody(
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
    onToggleBars: () -> Unit,
    onPicture: (ShownPicture) -> Unit,
    onLongPress: (Int) -> Unit,
    highlight: IntRange?,
    highlightAlpha: Float,
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
                screenTop = it.positionInRoot().y
                screenBottom = screenTop + it.size.height
            }
            .pointerInput(Unit) {
                detectTapGestures(
                    onTap = { pos ->
                        when {
                            pos.x < size.width * 0.28f -> scope.launch { pageBack() }
                            pos.x > size.width * 0.72f -> scope.launch { listState.animateScrollBy(stepForward()) }
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
                            text = litText(block.text, block.start, highlight, highlightAlpha, palette),
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

// ------------------------------------------------------------------- листание

@Composable
private fun PagedBody(
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
                    if (opens && smallCaps) openingText(txt, 0, null, 0f, palette)
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

        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(pages, shape.spread) {
                    detectTapGestures(
                        onTap = { pos ->
                            val slot = pagerState.currentPage
                            val to = when {
                                pos.x < size.width * 0.28f -> slot - 1
                                pos.x > size.width * 0.72f -> slot + 1
                                else -> {
                                    onToggleBars()
                                    return@detectTapGestures
                                }
                            }
                            if (to in 0 until pagerState.pageCount) {
                                scope.launch {
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
                        onLongPress = { pos ->
                            // В развороте спрашивают про ту страницу, на которую нажали.
                            val first = firstOf(pagerState.currentPage)
                            val at = if (shape.spread && pos.x > size.width / 2) first + 1 else first
                            pages.getOrNull(at)?.let { onLongPress(it.startChar) }
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
                snapAnimationSpec = spring(
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
                        highlight = highlight, highlightAlpha = highlightAlpha,
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
private fun PageFace(
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
    highlight: IntRange?,
    highlightAlpha: Float,
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
                            piece.text, piece.start, highlight, highlightAlpha, palette,
                        ) else litText(piece.text, piece.start, highlight, highlightAlpha, palette),
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
private fun BoxScope.PageMarksLayer(
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
private val PAGE_TOP = 52.dp
/** Воздух над заголовком главы и под ним - те же числа и в разбивке, и в рисунке. */
private val HEADING_TOP = 24.dp
private val HEADING_GAP = 14.dp
private val PAGE_BOTTOM = 44.dp
/**
 * Сколько абзацев вокруг текущего места разбивать на страницы за раз.
 *
 * Было 260. С переносами разбор строк абзацный, а не простой, и считается он
 * заметно дольше - окно подрезано, чтобы «Размечаю страницы…» не растягивалось
 * на глазах. Края окна пересчитываются на подходе, так что читателю видна та
 * же бесконечная книга.
 */
private const val WINDOW_BLOCKS = 160

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
/**
 * Первый абзац главы: первые слова капителью.
 *
 * Literata малых прописных не содержит, поэтому капитель - прописные
 * пониженного кегля с разрядкой: так её и подделывают в наборе, когда своей
 * нет. Длина строки при этом не меняется ни на знак - меняются только стили
 * поверх тех же букв, - значит разбивка на страницы остаётся верной.
 */
private fun openingText(
    text: String,
    start: Int,
    highlight: IntRange?,
    alpha: Float,
    palette: ReaderPalette,
): androidx.compose.ui.text.AnnotatedString {
    val base = litText(text, start, highlight, alpha, palette)
    // Три слова или первое предложение - что короче: длинную капитель читать
    // тяжело, она сбивает с ритма.
    var end = 0
    var words = 0
    while (end < text.length && words < 3) {
        val space = text.indexOf(' ', end)
        if (space < 0) { end = text.length; break }
        end = space + 1
        words++
    }
    end = end.coerceAtMost(text.length).coerceAtMost(28)
    if (end <= 0) return base
    return androidx.compose.ui.text.buildAnnotatedString {
        append(base)
        addStyle(
            androidx.compose.ui.text.SpanStyle(
                fontSize = 0.86.em,
                letterSpacing = 0.06.em,
                fontFeatureSettings = "smcp",
            ),
            0, end,
        )
        // Прописными - тем же текстом, но в верхнем регистре: SpanStyle
        // регистра не меняет, поэтому подменяем сам кусок.
    }.let { styled ->
        androidx.compose.ui.text.buildAnnotatedString {
            append(text.substring(0, end).uppercase())
            addStyle(
                androidx.compose.ui.text.SpanStyle(
                    fontSize = 0.86.em,
                    letterSpacing = 0.06.em,
                ),
                0, end,
            )
            append(styled.subSequence(end, styled.length))
        }
    }
}

/**
 * Детерминированный «шум» по месту в книге: одна и та же страница всегда
 * перекошена одинаково. Без этого неровности печати дрожали бы на каждом
 * кадре, и вместо живой бумаги вышла бы рябь.
 */
private fun pageNoise(key: Int, salt: Int): Float {
    val v = kotlin.math.sin(key * 0.0173 + salt * 12.9898) * 43758.5453
    return (v - kotlin.math.floor(v)).toFloat()
}

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

/** Отступ плашки от края экрана, когда у страницы своего поля нет. */
private val BAR_INSET = 10.dp

private val BAR_SHAPE = RoundedCornerShape(18.dp)

// Плашки выезжают из-за края, к которому прижаты, а не проявляются на месте:
// так видно, откуда они и куда уйдут. Уход короче появления - ждать его незачем.
private fun barEnter(fromTop: Boolean) =
    slideInVertically(tween(260, easing = FastOutSlowInEasing)) { if (fromTop) -it else it } +
        fadeIn(tween(180))

private fun barExit(fromTop: Boolean) =
    slideOutVertically(tween(200, easing = FastOutSlowInEasing)) { if (fromTop) -it else it } +
        fadeOut(tween(160))

/**
 * Плашка читалки: карточка над страницей с тенью, а не полоса поперёк неё.
 * В тёмной теме тень не видна, поэтому карточку там поднимает подсветка фона
 * и кромка; в светлой кромка еле заметна, работает тень.
 */
@Composable
private fun BarCard(
    palette: ReaderPalette,
    inner: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit,
) {
    val dark = palette.bg.luminance() < 0.5f
    val face = if (dark) lerp(palette.bg, palette.fg, 0.07f) else palette.bg
    Column(
        Modifier
            .fillMaxWidth()
            .shadow(
                elevation = if (dark) 6.dp else 12.dp,
                shape = BAR_SHAPE,
                ambientColor = Color.Black.copy(alpha = 0.25f),
                spotColor = Color.Black.copy(alpha = 0.35f),
            )
            .background(face, BAR_SHAPE)
            .border(0.5.dp, palette.fg.copy(alpha = if (dark) 0.16f else 0.08f), BAR_SHAPE)
            .then(inner),
        content = content,
    )
}
