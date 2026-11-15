package ru.zf.slushalka.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.data.readerView

/**
 * «Вид» - всё, как выглядит страница, одним листом и четырьмя вкладками
 * (16.11.2026). До этого лист «Как читать» был простынёй: шрифт, бумага,
 * устройство, восемь абзацев про объём страницы, картинки и разметка звука
 * подряд, а часть того же самого жила ещё и в общих настройках - владелец:
 * «чтобы поменять то, как выглядит страница, надо в трёх разных местах
 * что-то нажать». Теперь:
 *  - Текст: шрифт, кегль, насыщенность, интерлиньяж, поля, выключка;
 *  - Страница: вид (картинками), листание, разворот, колонтитулы, во весь
 *    экран, тапы, поворот;
 *  - Бумага: цвет и своя яркость;
 *  - Тонко: только то, что есть у выбранного вида, и книжный набор.
 * Лист невысокий и без затемнения: книга над ним видна и меняется на глазах.
 * Устройство - в общих настройках, картинки и разметка - в меню книги.
 */
@Composable
fun ReaderSettingsDialog(app: SlushalkaApp, onClose: () -> Unit) {
    val prefs by app.state.prefs.collectAsState()
    var tab by remember { mutableIntStateOf(lastTab) }
    PaperSheet(app = app, onClose = onClose, icon = Glyphs.TextFields, title = "Вид", seeThrough = true) {
        Spacer(Modifier.height(10.dp))
        Tabs(listOf("Текст", "Страница", "Бумага", "Тонко"), tab) { tab = it; lastTab = it }
        if (prefs.readerEink) {
            PaperNote(
                "${Settings.deviceLabel(prefs.readerDevice)}: всегда страницами и без анимаций - " +
                    "иначе электронная бумага мерцает. Устройство - в настройках.",
                Modifier.padding(top = 10.dp),
            )
        }
        when (tab) {
            0 -> TextTab(app)
            1 -> PageTab(app)
            2 -> PaperTab(app)
            else -> FineTab(app)
        }
        Spacer(Modifier.height(8.dp))
    }
}

/** На какой вкладке закрыли - на той и откроется: обычно крутят одно и то же. */
private var lastTab = 0

@Composable
private fun Tabs(titles: List<String>, selected: Int, onPick: (Int) -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Row(
        Modifier
            .fillMaxWidth()
            .clip(shape)
            .border(if (LocalEink.current) 1.dp else 0.7.dp, c.outlineVariant, shape)
            .padding(3.dp),
    ) {
        titles.forEachIndexed { i, title ->
            val on = i == selected
            Box(
                Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(11.dp))
                    .background(if (on) c.primaryContainer else Color.Transparent)
                    .clickable { onPick(i) }
                    .padding(vertical = 9.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    title,
                    style = MaterialTheme.typography.labelLarge.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal),
                    color = c.onSurface,
                    maxLines = 1,
                )
            }
        }
    }
}

// ----------------------------------------------------------------- текст

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TextTab(app: SlushalkaApp) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = app.state.settings

    Label("Шрифт")
    // Шрифт выбирают глазами: ряд прокручивается, подпись набрана им самим.
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.FONTS.forEach { (id, title) ->
            FontChip(title, id, selected = prefs.readerFont == id) { scope.launch { s.setReaderFont(id) } }
        }
    }
    PaperNote(fontNote(prefs.readerFont), Modifier.padding(top = 6.dp))

    // Сколько знаков в строке - главная мерка книжного набора: в книге их
    // 45-55. Число показываем, решает владелец; «как в книге» - одним
    // делением, ширина знака пропорциональна кеглю.
    val perLine = charsPerLine(prefs.readerView())
    Label("Кегль")
    Row(verticalAlignment = Alignment.CenterVertically) {
        SizeButton("А", 14.sp, "Мельче") { scope.launch { s.setReaderSize(prefs.readerSize - 1) } }
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            Text("${prefs.readerSize}", style = MaterialTheme.typography.titleLarge)
            perLine?.let { PaperNote("≈$it знаков в строке") }
        }
        SizeButton("А", 22.sp, "Крупнее") { scope.launch { s.setReaderSize(prefs.readerSize + 1) } }
    }
    if (perLine != null && perLine !in 44..56) {
        val bookSize = (prefs.readerSize * perLine / 50f).toInt().coerceIn(12, 34)
        if (bookSize != prefs.readerSize) {
            Spacer(Modifier.height(8.dp))
            PaperButton("Как в книге: $bookSize, ≈50 знаков", icon = Glyphs.AutoStories, modifier = Modifier.fillMaxWidth()) {
                scope.launch { s.setReaderSize(bookSize) }
            }
        }
    }

    Label("Насыщенность")
    ChipRow(Settings.WEIGHTS, prefs.readerWeight, { Settings.weightLabel(it) }) { scope.launch { s.setReaderWeight(it) } }

    Label("Междустрочье")
    ChipRow(listOf(1.2f, 1.32f, 1.45f, 1.6f, 1.8f), prefs.readerLineHeight, { it.toString().replace('.', ',') }) {
        scope.launch { s.setReaderLineHeight(it) }
    }

    Label("Поля")
    ChipRow(listOf(0, 12, 20, 32, 48), prefs.readerMargin, { if (it == 0) "Нет" else "$it" }) {
        scope.launch { s.setReaderMargin(it) }
    }

    Spacer(Modifier.height(6.dp))
    PaperToggle("По ширине", prefs.readerJustify) { scope.launch { s.setReaderJustify(it) } }
    PaperToggle("Переносы", prefs.readerHyphens, hint = "С выключкой по ширине без них строку растаскивает дырами") {
        scope.launch { s.setReaderHyphens(it) }
    }
    PaperToggle("Абзацный отступ", prefs.readerIndent, hint = "Как в книге; выключен - абзацы разделены пустой строкой") {
        scope.launch { s.setReaderIndent(it) }
    }
}

// --------------------------------------------------------------- страница

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun PageTab(app: SlushalkaApp) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = app.state.settings
    val palette = readerPalette(prefs.readerTheme, androidx.compose.foundation.isSystemInDarkTheme())

    Label("Вид")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Settings.PAGE_STYLES.forEach { id ->
            StyleThumb(id, palette, selected = prefs.readerPageStyle == id) { scope.launch { s.setReaderPageStyle(id) } }
        }
    }
    PaperNote(Settings.pageStyleNote(prefs.readerPageStyle), Modifier.padding(top = 6.dp))

    Label("Листать")
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PaperChip("Страницами", selected = prefs.readerPaged || prefs.readerEink, icon = Glyphs.AutoStories) {
            scope.launch { s.setReaderPaged(true) }
        }
        PaperChip("Лентой", selected = !prefs.readerPaged && !prefs.readerEink, icon = Glyphs.Toc, enabled = !prefs.readerEink) {
            scope.launch { s.setReaderPaged(false) }
        }
    }
    val paged = prefs.readerPaged || prefs.readerEink

    if (paged && !prefs.readerEink) {
        Label("Перелистывание")
        TurnChips(app)
    }

    if (paged) {
        Label("Разворот")
        ChipRow(Settings.SPREADS, prefs.readerSpread, { Settings.spreadLabel(it) }) { scope.launch { s.setReaderSpread(it) } }
    }

    Label("Колонтитулы")
    ChipRow(Settings.FOOTERS, prefs.readerFooter, { Settings.footerLabel(it) }) { scope.launch { s.setReaderFooter(it) } }

    Label("Экран")
    PaperToggle(
        "Во весь экран",
        prefs.readerFullscreen,
        hint = "Часы и кнопки системы спрятаны, время - внизу страницы. Достать - смахнуть от края.",
    ) { scope.launch { s.setReaderFullscreen(it) } }
    PaperToggle("Не гасить экран", prefs.readerKeepAwake) { scope.launch { s.setReaderKeepAwake(it) } }
    PaperToggle(
        "Листать кнопками громкости",
        prefs.readerVolumeKeys,
        hint = "Вниз - вперёд, вверх - назад, пока ничего не звучит",
    ) { scope.launch { s.setReaderVolumeKeys(it) } }

    Label("Тап по странице")
    ChipRow(Settings.TAPS, prefs.readerTaps, { Settings.tapsLabel(it) }) { scope.launch { s.setReaderTaps(it) } }
    PaperNote(
        if (prefs.readerTaps == Settings.TAPS_FORWARD) "Одной рукой: назад - левый край, панели - тап у верха, остальное вперёд."
        else "Края листают, тап посередине - панели.",
        Modifier.padding(top = 6.dp),
    )

    Label("Поворот")
    ChipRow(Settings.ORIENTS, prefs.readerOrientation, { Settings.orientLabel(it) }) { scope.launch { s.setReaderOrientation(it) } }
}

/**
 * Перелистывание - одним рядом, а не двумя настройками в разных местах: у
 * книги лист загибается или растворяется, у карточек едет или смахивается,
 * у бумаги - всё это.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TurnChips(app: SlushalkaApp) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = app.state.settings
    val style = prefs.readerPageStyle
    val curlable = (style == Settings.PAGE_VOLUME || style == Settings.PAGE_PAPER) && curlSupported
    val curl = curlable && prefs.readerBookTurn == Settings.BOOK_TURN_CURL
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (curlable) PaperChip("Загиб листа", selected = curl) { scope.launch { s.setReaderBookTurn(Settings.BOOK_TURN_CURL) } }
        if (style == Settings.PAGE_VOLUME) {
            PaperChip("Растворение", selected = !curl) { scope.launch { s.setReaderBookTurn(Settings.BOOK_TURN_FADE) } }
        } else {
            Settings.PAGE_TURNS.forEach { id ->
                PaperChip(Settings.pageTurnLabel(id), selected = !curl && prefs.readerPageTurn == id) {
                    scope.launch {
                        s.setReaderBookTurn(Settings.BOOK_TURN_FADE)
                        s.setReaderPageTurn(id)
                    }
                }
            }
        }
    }
    if (!curlSupported && (style == Settings.PAGE_VOLUME || style == Settings.PAGE_PAPER)) {
        PaperNote("Загиб листа считает шейдер, ему нужен Android 13.", Modifier.padding(top = 6.dp))
    }
}

/**
 * Вид страницы картинкой: что получишь, видно до тапа. Плоско и бумага - во
 * весь экран, у книги переплёт и стол, у колоды - кромки, у карточки - тень.
 */
@Composable
private fun StyleThumb(style: String, palette: ReaderPalette, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val dark = palette.bg.red + palette.bg.green + palette.bg.blue < 1.5f
    val table = lerp(palette.bg, if (dark) Color.White else Color(0xFF5B4A36), if (dark) 0.10f else 0.28f)
    val ink = palette.fg.copy(alpha = 0.55f)
    val cast = Color.Black.copy(alpha = if (dark) 0.5f else 0.22f)
    Column(
        Modifier.width(70.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Canvas(
            Modifier
                .size(width = 54.dp, height = 78.dp)
                .clip(RoundedCornerShape(8.dp))
                .border(if (selected) 2.dp else 0.7.dp, if (selected) c.onSurface else c.outlineVariant, RoundedCornerShape(8.dp)),
        ) {
            val w = size.width
            val h = size.height
            val dp = 1.dp.toPx()
            fun lines(x0: Float, y0: Float, x1: Float, y1: Float) {
                var y = y0
                var i = 0
                while (y < y1) {
                    val end = if (i % 5 == 4) x0 + (x1 - x0) * 0.6f else x1
                    drawRect(ink, Offset(x0, y), Size(end - x0, 1.4f * dp))
                    y += 4.2f * dp
                    i++
                }
            }
            when (style) {
                Settings.PAGE_FLAT -> {
                    drawRect(palette.bg)
                    lines(6 * dp, 8 * dp, w - 6 * dp, h - 7 * dp)
                }
                Settings.PAGE_PAPER -> {
                    drawRect(palette.bg)
                    drawRect(
                        Brush.horizontalGradient(listOf(cast, Color.Transparent), startX = 0f, endX = 5 * dp),
                        size = Size(5 * dp, h),
                    )
                    for (k in 0 until 3) drawRect(cast.copy(alpha = cast.alpha * (0.8f - 0.2f * k)), Offset(w - (1.2f + 1.6f * k) * dp, 0f), Size(0.6f * dp, h))
                    lines(7 * dp, 8 * dp, w - 6 * dp, h - 7 * dp)
                }
                Settings.PAGE_VOLUME -> {
                    drawRect(table)
                    val cover = lerp(palette.bg, Color(0xFF7A3B2E), 0.75f)
                    drawRoundRect(cover, Offset(4 * dp, 4 * dp), Size(w - 8 * dp, h - 8 * dp), CornerRadius(2 * dp))
                    drawRect(lerp(cover, Color.Black, 0.25f), Offset(4 * dp, 4 * dp), Size(5 * dp, h - 8 * dp))
                    drawRect(palette.bg, Offset(10 * dp, 6.5f * dp), Size(w - 16 * dp, h - 13 * dp))
                    lines(13 * dp, 11 * dp, w - 9 * dp, h - 10 * dp)
                }
                Settings.PAGE_BOOK -> {
                    drawRect(table)
                    for (k in 3 downTo 1) {
                        drawRoundRect(lerp(palette.bg, table, 0.25f * k), Offset(6 * dp, 6 * dp + 2.2f * dp * k), Size(w - 12 * dp, h - 16 * dp), CornerRadius(4 * dp))
                    }
                    drawRoundRect(palette.bg, Offset(6 * dp, 6 * dp), Size(w - 12 * dp, h - 16 * dp), CornerRadius(4 * dp))
                    lines(10 * dp, 11 * dp, w - 10 * dp, h - 14 * dp)
                }
                else -> {
                    drawRect(table)
                    drawRoundRect(cast, Offset(6 * dp, 8 * dp), Size(w - 12 * dp, h - 13 * dp), CornerRadius(5 * dp))
                    drawRoundRect(palette.bg, Offset(6 * dp, 6 * dp), Size(w - 12 * dp, h - 13 * dp), CornerRadius(5 * dp))
                    lines(10 * dp, 11 * dp, w - 10 * dp, h - 12 * dp)
                }
            }
        }
        Spacer(Modifier.height(5.dp))
        Text(
            Settings.pageStyleLabel(style),
            style = MaterialTheme.typography.labelSmall.copy(fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal),
            color = if (selected) c.onSurface else c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
        )
    }
}

// ---------------------------------------------------------------- бумага

@Composable
private fun PaperTab(app: SlushalkaApp) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = app.state.settings

    Label("Цвет бумаги")
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        // На электронной бумаге первыми - чистые белый и чёрный: тёплый
        // оттенок там выходит серым.
        val eink = if (prefs.readerEink) listOf(
            Settings.THEME_EINK to "Чистая",
            Settings.THEME_EINK_NIGHT to "Чистая ночь",
        ) else emptyList()
        (eink + listOf(
            Settings.THEME_AUTO to "Как система",
            Settings.THEME_PAPER to "Бумага",
            Settings.THEME_SEPIA to "Сепия",
            Settings.THEME_GREY to "Серая",
            Settings.THEME_BLACK to "Ночь",
            Settings.THEME_WARM to "Тёплая ночь",
            Settings.THEME_TIME to "По часам",
        )).forEach { (id, title) ->
            PaperSwatch(id, title, selected = prefs.readerTheme == id) { scope.launch { s.setReaderTheme(id) } }
        }
    }
    PaperNote(
        when (prefs.readerTheme) {
            Settings.THEME_TIME -> "Днём бумага, с семи вечера сепия, с десяти - тёплая ночная."
            Settings.THEME_WARM -> "Коричневая бумага и песочные буквы - меньше синего в постели."
            Settings.THEME_AUTO -> "Светлая днём, ночь - когда телефон в тёмной теме."
            Settings.THEME_EINK, Settings.THEME_EINK_NIGHT -> "Без оттенка - для электронной бумаги."
            else -> "Окна читалки - вопрос, справочник, заметки - той же бумагой."
        },
        Modifier.padding(top = 6.dp),
    )

    Label("Яркость")
    val own = prefs.readerBrightness >= 0f
    PaperToggle("Своя яркость в книге", own, hint = "Только пока читаешь; полка и плеер - как в системе") {
        scope.launch { s.setReaderBrightness(if (it) 0.35f else -1f) }
    }
    // Окно книги: лист живёт в своём окне, а яркость читалки - у окна приложения.
    val view = androidx.compose.ui.platform.LocalView.current
    val window = remember(view) {
        generateSequence(view.context) { (it as? android.content.ContextWrapper)?.baseContext }
            .filterIsInstance<android.app.Activity>().firstOrNull()?.window
    }
    if (own) {
        // Ползунок двигает яркость сразу, а на диск пишет, когда палец отпустили.
        var live by remember(prefs.readerBrightness) { mutableStateOf(prefs.readerBrightness) }
        val c = MaterialTheme.colorScheme
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("☾", color = c.onSurfaceVariant, fontSize = 16.sp)
            Slider(
                value = live,
                onValueChange = { v ->
                    live = v
                    window?.let { w ->
                        w.attributes = w.attributes.apply { screenBrightness = v.coerceIn(0.01f, 1f) }
                    }
                },
                onValueChangeFinished = { scope.launch { s.setReaderBrightness(live) } },
                valueRange = 0.01f..1f,
                colors = SliderDefaults.colors(thumbColor = c.onSurface, activeTrackColor = c.onSurface),
                modifier = Modifier.weight(1f).padding(horizontal = 10.dp),
            )
            Text("☀", color = c.onSurfaceVariant, fontSize = 18.sp)
        }
    }
}

// ----------------------------------------------------------------- тонко

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FineTab(app: SlushalkaApp) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = app.state.settings
    val style = prefs.readerPageStyle
    val volume = style == Settings.PAGE_VOLUME
    val paper = style == Settings.PAGE_PAPER
    val card = style == Settings.PAGE_BOOK || style == Settings.PAGE_SOFT

    PaperNote(
        "Здесь - только то, что есть у вида «${Settings.pageStyleLabel(style)}».",
        Modifier.padding(top = 12.dp),
    )

    if (volume || paper) {
        Label("Просвет с оборота")
        PaperToggle("Печать с оборота проступает", prefs.readerBleed, hint = "Зеркально и размыто, как сквозь тонкую бумагу") {
            scope.launch { s.setReaderBleed(it) }
        }
        if (prefs.readerBleed) {
            ChipRow(Settings.BLEED_LEVELS, prefs.readerBleedLevel, { Settings.bleedLabel(it) }, same = { a, b -> kotlin.math.abs(a - b) < 0.01f }) {
                scope.launch { s.setReaderBleedLevel(it) }
            }
        }
    }
    if (volume) {
        Label("Книга")
        PaperToggle("Обрез снизу", prefs.readerBookTilt, hint = "Книга чуть от себя: под страницами виден торец блока") {
            scope.launch { s.setReaderBookTilt(it) }
        }
        PaperToggle("Ляссе", prefs.readerBookRibbon, hint = "Ленточка-закладка из корешка") { scope.launch { s.setReaderBookRibbon(it) } }
    }
    if (volume || card) {
        Label("Поле от края экрана")
        ChipRow(Settings.CARD_MARGINS, prefs.readerCardMargin, { if (it == 0) "Нет" else "$it" }) {
            scope.launch { s.setReaderCardMargin(it) }
        }
        Label("Тень и стол")
        ChipRow(Settings.SHADOWS, prefs.readerShadow, { Settings.shadowLabel(it) }) { scope.launch { s.setReaderShadow(it) } }
        Spacer(Modifier.height(8.dp))
        ChipRow(Settings.TABLES, prefs.readerTable, { Settings.tableLabel(it) }, same = { a, b -> kotlin.math.abs(a - b) < 0.01f }) {
            scope.launch { s.setReaderTable(it) }
        }
    }
    if (volume || paper || card) {
        Label("Бумага на ощупь")
        PaperToggle("Матовость: зерно и волокна", prefs.readerGrain) { scope.launch { s.setReaderGrain(it) } }
        if (card) {
            PaperToggle("Фаска по кромке", prefs.readerBevel) { scope.launch { s.setReaderBevel(it) } }
            PaperToggle("Блик и затенение", prefs.readerSheen) { scope.launch { s.setReaderSheen(it) } }
        }
    }

    Label("Набор")
    PaperButton("Набрать как книгу", icon = Glyphs.AutoStories, modifier = Modifier.fillMaxWidth()) {
        scope.launch { s.setBookTypography() }
    }
    PaperNote(
        "Литерата, интерлиньяж 1,32, переносы, капитель, типограф и книжный вид разом. Ниже - по одному, чтобы сравнивать.",
        Modifier.padding(top = 6.dp, bottom = 4.dp),
    )
    if (volume) PaperToggle("Книжные поля 2:3:4:6", prefs.readerCanon, hint = "Полоса смещена к корешку и вверх") {
        scope.launch { s.setReaderCanon(it) }
    }
    PaperToggle("Капитель в начале главы", prefs.readerSmallCaps) { scope.launch { s.setReaderSmallCaps(it) } }
    PaperToggle("Типограф", prefs.readerTypograph, hint = "Тире вместо дефиса, неразрывный пробел после коротких слов") {
        scope.launch { s.setReaderTypograph(it) }
    }
    PaperToggle("Неровности печати", prefs.readerImperfect, hint = "Полоса чуть перекошена, краска берётся волокнами") {
        scope.launch { s.setReaderImperfect(it) }
    }
}

// ------------------------------------------------------------ меню книги

/**
 * Меню книги из читалки: всё служебное, что раньше лежало в листе вида -
 * карточка, картинки, разметка звука, разбор текста заново.
 */
@Composable
fun BookToolsSheet(
    app: SlushalkaApp,
    onCard: (() -> Unit)?,
    onGallery: () -> Unit,
    onRazbor: (() -> Unit)?,
    onClose: () -> Unit,
) {
    val state = app.state
    val prefs by state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val book by state.current.collectAsState()
    val pics = state.text.collectAsState().value?.pictures?.size ?: 0
    val extracted = remember { state.picturesOnDisk() }
    PaperSheet(app = app, onClose = onClose, icon = Glyphs.MenuBook, title = "Книга", subtitle = book?.title) {
        Spacer(Modifier.height(6.dp))
        if (onCard != null) PaperRow(Icons.Default.Info, "О книге", "Карточка: о чём она, что в ней есть, заметки, удалить") { onCard() }
        if (onRazbor != null) PaperRow(Glyphs.Lightbulb, "Разбор", "От сервера: о книге, идеи, линзы, книга за 15 минут") { onRazbor() }
        if (extracted > 0) {
            PaperRow(
                Glyphs.PhotoLibrary,
                "Картинки",
                if (pics > 0) "В книге $pics - стоят на своих местах" else "Вынуто из файла: $extracted, списком",
            ) { onGallery() }
        }
        PaperRow(Icons.Default.Refresh, "Разобрать текст заново", "Если картинок или глав не видно") {
            state.reparseText()
            onClose()
        }
        if (pics == 0 || extracted > pics) {
            state.parseReport()?.let { r -> PaperNote("Разбор увидел: ${r.line()}", Modifier.padding(start = 4.dp, top = 4.dp)) }
        }
        if (book?.hasAudio == true) {
            Label("Звук и текст")
            val marked = state.isMarkedUp()
            PaperNote(
                when {
                    marked -> "Книга размечена: переход со звука на текст точен сразу."
                    !app.recognizer.supported -> "Распознавание на этом устройстве недоступно - место берётся по карте, с точностью до страницы-другой."
                    else -> "Книга не размечена: разметка пройдёт по записи пробами прямо на телефоне, даром и без сети."
                },
            )
            if (app.recognizer.supported) {
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperButton(if (marked) "Разметить ещё раз" else "Разметить", icon = Glyphs.Headphones, modifier = Modifier.weight(1f)) {
                        state.markupBook(); onClose()
                    }
                    PaperButton("Одна проба", icon = Glyphs.Mic, modifier = Modifier.weight(1f)) { state.testProbe(); onClose() }
                }
                PaperToggle(
                    "Сверять место при переходе",
                    prefs.refineOnSwitch,
                    hint = "Одна проба перед самым переходом - для мест, куда разметка не добралась",
                ) { scope.launch { state.settings.setRefineOnSwitch(it) } }
            }
        }
    }
}

// ---------------------------------------------------------------- детали

@Composable
private fun Label(text: String) {
    PaperLabel(text, Modifier.padding(top = 4.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> ChipRow(
    values: List<T>,
    selected: T,
    format: (T) -> String,
    same: (T, T) -> Boolean = { a, b -> a == b },
    onPick: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v -> PaperChip(format(v), selected = same(v, selected)) { onPick(v) } }
    }
}

/** Образец бумаги: страничка её цвета с «Аа» её краской и подпись под ней. */
@Composable
private fun PaperSwatch(theme: String, title: String, selected: Boolean, onClick: () -> Unit) {
    val p = readerPalette(theme, androidx.compose.foundation.isSystemInDarkTheme())
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(12.dp)
    Column(
        Modifier.width(68.dp).clip(RoundedCornerShape(14.dp)).clickable(onClick = onClick).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Box(
            Modifier
                .size(width = 52.dp, height = 64.dp)
                .clip(shape)
                .background(p.bg)
                .border(if (selected) 2.dp else 0.7.dp, if (selected) c.onSurface else c.outlineVariant, shape),
            contentAlignment = Alignment.Center,
        ) {
            val face = TextStyle(fontFamily = fontOf(Settings.FONT_BOOK), fontSize = 20.sp)
            if (theme == Settings.THEME_TIME || theme == Settings.THEME_AUTO) {
                // Меняющаяся бумага - половинками: какой она бывает днём и ночью.
                val day = readerPalette(Settings.THEME_PAPER, false)
                val night = readerPalette(if (theme == Settings.THEME_TIME) Settings.THEME_WARM else Settings.THEME_BLACK, true)
                Row(Modifier.fillMaxSize()) {
                    Box(Modifier.weight(1f).fillMaxHeight().background(day.bg), contentAlignment = Alignment.CenterEnd) {
                        Text("А", style = face.copy(color = day.fg))
                    }
                    Box(Modifier.weight(1f).fillMaxHeight().background(night.bg), contentAlignment = Alignment.CenterStart) {
                        Text("а", style = face.copy(color = night.fg))
                    }
                }
            } else {
                Text("Аа", style = face.copy(color = p.fg))
            }
        }
        Spacer(Modifier.height(6.dp))
        Text(
            title,
            style = MaterialTheme.typography.labelSmall,
            color = if (selected) c.onSurface else c.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 2,
        )
    }
}

/** Шрифт выбирают глазами: подпись чипа набрана им самим. */
@Composable
private fun FontChip(title: String, font: String, selected: Boolean, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    val shape = RoundedCornerShape(14.dp)
    Box(
        Modifier
            .clip(shape)
            .background(if (selected) c.primaryContainer else Color.Transparent)
            .border(if (selected) 1.5.dp else 0.8.dp, if (selected) c.onSurface else c.outlineVariant, shape)
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 9.dp),
    ) {
        Text(title, style = TextStyle(fontFamily = fontOf(font), fontSize = 16.sp, color = c.onSurface), maxLines = 1)
    }
}

/** «А» мельче и крупнее - кругом, как кнопки плашки. */
@Composable
private fun SizeButton(label: String, size: androidx.compose.ui.unit.TextUnit, description: String, onClick: () -> Unit) {
    val c = MaterialTheme.colorScheme
    Box(
        Modifier
            .size(52.dp)
            .clip(CircleShape)
            .border(0.8.dp, c.outline, CircleShape)
            .clickable(onClickLabel = description, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Text(label, style = TextStyle(fontFamily = fontOf(Settings.FONT_BOOK), fontSize = size, color = c.onSurface))
    }
}

/**
 * Сколько знаков помещается в строке при нынешних кегле, шрифте и полях.
 *
 * Меряется той же строчной прозой, какой набрана книга: у кириллицы средняя
 * ширина знака сильно зависит от гарнитуры, и считать по ширине «м» или по
 * кеглю было бы гаданием. null - когда мерить негде (нет окна).
 */
@Composable
private fun charsPerLine(prefs: Settings.Prefs): Int? {
    val density = LocalDensity.current
    val measurer = rememberTextMeasurer()
    val screen = with(density) { LocalWindowInfo.current.containerSize.width.toDp() }
    if (screen <= 0.dp) return null
    return remember(prefs, screen) {
        val look = PageLook(
            style = prefs.readerPageStyle,
            margin = prefs.readerCardMargin.dp,
            shadow = prefs.readerShadow,
            bevel = prefs.readerBevel,
            sheen = prefs.readerSheen,
            grain = prefs.readerGrain,
        )
        val spread = spreadOn(prefs.readerSpread, prefs.readerPaged, screen)
        val shape = BookShape(10.dp, 0.5f, spread)
        val card = cardMetrics(look, shape)
        val halves = if (spread) 2 else 1
        val chrome = pageChrome(look, card, halves)
        val margins = pageMargins(
            prefs.readerMargin,
            prefs.readerCanon && prefs.readerPaged && prefs.readerPageStyle == Settings.PAGE_VOLUME,
        )
        val line = screen / halves - chrome.width - margins.width
        if (line <= 0.dp) return@remember null
        val sample = "строчная проза средней длины, по ней и меряем ширину знака"
        val style = TextStyle(
            fontFamily = fontOf(prefs.readerFont),
            fontSize = prefs.readerSize.sp,
        )
        val width = measurer.measure(sample, style).size.width.toFloat()
        val per = width / sample.length
        if (per <= 0f) null else (with(density) { line.toPx() } / per).toInt()
    }
}

/** Чем гарнитура хороша - одной строкой под выбором, чтобы выбирать не вслепую. */
private fun fontNote(font: String): String = when (font) {
    Settings.FONT_BOOK -> "Literata: нарисована для чтения с экрана, спокойная книжная антиква."
    Settings.FONT_PT_SERIF -> "PT Serif: русская классика ПараТайпа, строгая и ясная."
    Settings.FONT_LORA -> "Lora: тёплая, с каллиграфическим ходом - для романа вечером."
    Settings.FONT_MERRIWEATHER -> "Merriweather: крупное очко и плотный штрих - для e-ink и мелкого экрана."
    Settings.FONT_BITTER -> "Bitter: брусковые засечки, очень чёткая - на электронной бумаге не выцветает."
    Settings.FONT_PT_SANS -> "PT Sans: рубленая ПараТайпа, для тех, кто читает без засечек."
    Settings.FONT_SERIF -> "Системная с засечками: на разных телефонах своя."
    Settings.FONT_SANS -> "Системная рубленая."
    else -> "Машинописная: как рукопись."
}
