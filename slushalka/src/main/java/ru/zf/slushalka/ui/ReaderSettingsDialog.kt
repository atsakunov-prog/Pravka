package ru.zf.slushalka.ui

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
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.data.readerView

/**
 * «Как читать»: шрифт, кегль, бумага, листание, вид страницы. Лист сам
 * нарисован на выбранной бумаге - тапнул «Сепия», и окно стало сепией вместе
 * с книгой: так видно, что выбрал, не закрывая.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsDialog(app: SlushalkaApp, onGallery: () -> Unit, onClose: () -> Unit) {
    val state = app.state
    val prefs by state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = state.settings

    PaperSheet(app = app, onClose = onClose, icon = Glyphs.TextFields, title = "Как читать", tall = true) {

        // ------------------------------------------------ шрифт и кегль
        Label("Шрифт")
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Settings.FONTS.forEach { (id, title) ->
                FontChip(title, id, selected = prefs.readerFont == id) { scope.launch { s.setReaderFont(id) } }
            }
        }
        PaperNote(fontNote(prefs.readerFont), Modifier.padding(top = 8.dp))

        // Сколько знаков в строке - главная мерка книжного набора. В книге их
        // 45-55: короче строка - глаз скачет, длиннее - теряет начало
        // следующей. На телефоне столько выходит только мелким кеглем, поэтому
        // число показываем, а решает владелец. Знаки считаются тем кеглем, каким
        // книга правда набрана: в режиме e-ink он крупнее сохранённого.
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
            // Ширина знака прямо пропорциональна кеглю, значит знаков в строке -
            // обратно ему: пересчёт одним делением, без подбора.
            val bookSize = (prefs.readerSize * perLine / 50f).toInt().coerceIn(10, 40)
            Spacer(Modifier.height(8.dp))
            PaperButton("Как в книге: $bookSize, ≈50 знаков", icon = Glyphs.AutoStories, modifier = Modifier.fillMaxWidth()) {
                scope.launch { s.setReaderSize(bookSize) }
            }
        }

        Label("Междустрочье")
        NumberRow(
            // 1,32 - книжный интерлиньяж: у антиквы в книге строки стоят
            // теснее, чем принято в интерфейсах.
            values = listOf(1.2f, 1.32f, 1.45f, 1.6f, 1.8f),
            selected = prefs.readerLineHeight,
            format = { it.toString().replace('.', ',') },
        ) { scope.launch { s.setReaderLineHeight(it) } }

        Label("Поля")
        NumberRow(
            values = listOf(0, 12, 20, 32, 48),
            selected = prefs.readerMargin,
            format = { "$it" },
        ) { scope.launch { s.setReaderMargin(it) } }
        PaperToggle("Выключка по ширине", prefs.readerJustify) { scope.launch { s.setReaderJustify(it) } }

        // ------------------------------------------------ бумага
        Label("Бумага")
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            listOf(
                Settings.THEME_AUTO to "Как система",
                Settings.THEME_PAPER to "Бумага",
                Settings.THEME_SEPIA to "Сепия",
                Settings.THEME_GREY to "Серая",
                Settings.THEME_BLACK to "Ночь",
                Settings.THEME_WARM to "Тёплая ночь",
                Settings.THEME_TIME to "По часам",
            ).forEach { (id, title) ->
                PaperSwatch(id, title, selected = prefs.readerTheme == id) { scope.launch { s.setReaderTheme(id) } }
            }
        }
        PaperNote(
            when (prefs.readerTheme) {
                Settings.THEME_TIME -> "Днём бумага, с семи вечера сепия, с десяти - тёплая ночная: меньше синего к ночи."
                Settings.THEME_WARM -> "Коричневая бумага и песочная краска - глаза в темноте не режет."
                Settings.THEME_AUTO -> "Светлая днём, чёрная, когда телефон в тёмной теме."
                else -> "Окна читалки - вопрос, справочник, пометки - красятся той же бумагой."
            },
            Modifier.padding(top = 8.dp),
        )

        // ------------------------------------------------ листание
        Label("Как листать")
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperChip("Прокруткой", selected = !prefs.readerPaged, icon = Glyphs.Toc) { scope.launch { s.setReaderPaged(false) } }
            PaperChip("Страницами", selected = prefs.readerPaged, icon = Glyphs.AutoStories) { scope.launch { s.setReaderPaged(true) } }
        }
        PaperNote(
            if (prefs.readerPaged)
                "Настоящие страницы: текст меряется в той ширине и тем шрифтом, каким будет нарисован, " +
                    "поэтому строка не режется краем пополам. Смахни вбок или тапни по краю."
            else "Обычная лента, а тап по краю листает ровно на страницу: следующая начинается со строки, " +
                "которая не влезла целиком. Тап посередине прячет панели.",
            Modifier.padding(top = 8.dp),
        )
        PaperToggle(
            "Листать кнопками громкости",
            prefs.readerVolumeKeys,
            hint = "Вниз - вперёд, вверх - назад, пока ничего не звучит. Кнопки электронных книг работают всегда.",
        ) { scope.launch { s.setReaderVolumeKeys(it) } }
        PaperToggle("Не гасить экран", prefs.readerKeepAwake) { scope.launch { s.setReaderKeepAwake(it) } }

        // ------------------------------------------------ e-ink
        // Режим для электронной книги меняет разом всё выше, поэтому - своей
        // карточкой, а не ещё одним тумблером в ряду.
        Spacer(Modifier.height(12.dp))
        PaperCard(highlight = prefs.readerEink) {
            PaperToggle(
                "Для электронной книги",
                prefs.readerEink,
                hint = if (prefs.readerEink)
                    "Чистый чёрный на белом, кегль на ${Settings.EINK_SIZE_BOOST} крупнее и на ступень жирнее, " +
                        "страницами, без теней, зерна и анимаций. Выбранное здесь сохранено и вернётся, когда режим выключишь."
                else "Onyx Boox, PocketBook, Kobo, Hisense: контраст, крупный плотный шрифт, листание без анимации." +
                    if (ru.zf.slushalka.data.EinkDevice.likely) " Похоже, это как раз электронная книга." else "",
            ) { scope.launch { s.setReaderEink(it) } }
        }

        PageLookSettings(app, labels = { Label(it) })

        // ------------------------------------------------ служебное
        val pics = state.text.collectAsState().value?.pictures?.size ?: 0
        val extracted = state.picturesOnDisk()
        Label("Картинки")
        PaperNote(
            when {
                pics > 0 -> "В книге их $pics. Стоят на своих местах в тексте; пока читаешь рядом, миниатюра висит в углу."
                extracted > 0 -> "Вынуто из файла: $extracted, но место в тексте для них не нашлось - смотреть их можно списком."
                else -> "В этой книге картинок не нашлось."
            },
        )
        if (pics == 0 || extracted > pics) {
            // Голое «не нашлось» ничего не объясняет: показываем, что именно
            // разбор увидел в файле - по этим числам сразу ясно, чего не хватило.
            state.parseReport()?.let { r -> PaperNote("Разбор увидел: ${r.line()}") }
        }
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            if (extracted > 0) {
                PaperButton("Показать все", icon = Glyphs.PhotoLibrary, modifier = Modifier.weight(1f)) { onClose(); onGallery() }
            }
            PaperButton("Разобрать заново", icon = Icons.Default.Refresh, modifier = Modifier.weight(1f)) { state.reparseText(); onClose() }
        }

        Label("Переход со звука")
        val marked = state.isMarkedUp()
        PaperNote(
            when {
                marked -> "Книга размечена: место при переходе считается по карте мгновенно, ничего не распознаётся."
                !app.recognizer.supported ->
                    "На этом устройстве распознавание файла недоступно - место берётся по карте, с точностью до страницы-другой."
                else ->
                    "Книга не размечена. Разметка пройдёт по записи пробами, распознает их прямо на телефоне и найдёт " +
                        "в тексте - это ничего не стоит и не ходит в сеть. Карта ляжет файлом в папку книги, и другие " +
                        "устройства возьмут готовую."
            },
        )
        if (app.recognizer.supported) {
            Spacer(Modifier.height(8.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PaperButton(if (marked) "Разметить ещё раз" else "Разметить книгу", icon = Glyphs.Headphones, modifier = Modifier.weight(1f)) {
                    state.markupBook(); onClose()
                }
                PaperButton("Одна проба", icon = Glyphs.Mic, modifier = Modifier.weight(1f)) { state.testProbe(); onClose() }
            }
            PaperToggle(
                "Сверять место при переходе",
                prefs.refineOnSwitch,
                hint = "Про запас, для мест, до которых разметка не добралась: одна проба перед самым переходом.",
            ) { scope.launch { s.setRefineOnSwitch(it) } }
        }
        Spacer(Modifier.height(12.dp))
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
        Text(title, style = TextStyle(fontFamily = fontOf(font), fontSize = 16.sp, color = c.onSurface))
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
 * Объём страницы и перелистывание.
 *
 * Один кусок на два экрана: крутят его и из читалки («Аа Вид»), и из общих
 * настроек («Внешний вид»), а настройка одна - дублировать её парой чипов в
 * двух местах значило бы однажды их разойтись.
 *
 * [labels] - как экран подписывает разделы: в диалоге читалки свои подписи,
 * в общих настройках свои.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun PageLookSettings(app: SlushalkaApp, labels: @Composable (String) -> Unit) {
    val prefs by app.state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = app.state.settings

    labels("Вид страницы")
    if (prefs.readerEink) {
        PaperNote(
            "Сейчас включён режим e-ink: страница плоская, без теней и зерна. Выбранное здесь " +
                "вернётся, когда режим выключишь.",
            Modifier.padding(top = 8.dp),
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.PAGE_STYLES.forEach { id ->
            PaperChip(Settings.pageStyleLabel(id), selected = prefs.readerPageStyle == id) { scope.launch { s.setReaderPageStyle(id) } }
        }
    }
    PaperNote(
        when (prefs.readerPageStyle) {
            Settings.PAGE_VOLUME ->
                "Настоящий том: переплёт кантом по краю и цветом с обложки книги, плетёный " +
                    "корешок, обрез из многих страниц, каптал, светлый стол и мягкая тень по " +
                    "форме книги. Книга целиком в экране и остаётся на месте; страницы " +
                    "сменяются так, как выбрано в «Перелистывании»."
            Settings.PAGE_BOOK ->
                "Страница - верхняя карточка колоды: лежит на столе с мягкой тенью, по кромке " +
                    "тонкая фаска (светлая сверху, тёмная снизу), сверху блик, снизу затенение, " +
                    "поверх заливки зерно - те же слои, что у диска и плашек Правки. Под " +
                    "карточкой видны кромки следующих страниц, и чем больше остаётся, тем " +
                    "колода толще; у толстой книги она в четыре кромки, у повести в одну."
            Settings.PAGE_SOFT ->
                "Та же карточка со светом и тенью, но одна: колоды под ней нет."
            else -> "Ровная заливка во весь экран, без карточки и теней."
        },
        Modifier.padding(top = 8.dp),
    )

    labels("Поле от края экрана: ${prefs.readerCardMargin}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.CARD_MARGINS.forEach { v ->
            PaperChip(if (v == 0) "Без поля" else "$v", selected = prefs.readerCardMargin == v) { scope.launch { s.setReaderCardMargin(v) } }
        }
    }
    PaperNote(
        "Сколько стола видно вокруг страницы. Ноль - карточка встык с краями, и её " +
            "верхние углы уходят под часы; несколько точек - и страница лежит в экране " +
            "целиком, всеми четырьмя углами на виду.",
        Modifier.padding(top = 8.dp),
    )

    labels("Тень под страницей")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.SHADOWS.forEach { id ->
            PaperChip(Settings.shadowLabel(id), selected = prefs.readerShadow == id) { scope.launch { s.setReaderShadow(id) } }
        }
    }

    labels("Стол")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.TABLES.forEach { v ->
            PaperChip(Settings.tableLabel(v), selected = kotlin.math.abs(prefs.readerTable - v) < 0.01f) { scope.launch { s.setReaderTable(v) } }
        }
    }
    PaperNote(
        "Насколько поверхность вокруг страницы темнее её самой (на ночных темах - светлее). " +
            "К краям экрана она ещё немного темнеет: ровная заливка читается фоном, " +
            "затемнённая по углам - поверхностью, на которой что-то лежит.",
        Modifier.padding(top = 8.dp),
    )

    Spacer(Modifier.height(6.dp))
    PaperToggle("Фаска по кромке", prefs.readerBevel) { scope.launch { s.setReaderBevel(it) } }
    PaperToggle("Блик и затенение", prefs.readerSheen) { scope.launch { s.setReaderSheen(it) } }
    PaperToggle("Матовость: зерно и волокна", prefs.readerGrain) { scope.launch { s.setReaderGrain(it) } }
    PaperNote(
        "Три слоя объёма, каждый сам по себе: светлая линия сверху и тёмная снизу, блик по " +
            "верхней трети с затенением по нижней пятой, и матовость - мелкое зерно в тон " +
            "поверхности, как иней на стекле диска Правки, плюс те же точки крупнее и размытые, " +
            "волокнами. Матовы бумага и картон переплёта; на столе только свет и тень.",
        Modifier.padding(top = 8.dp),
    )

    labels("Набор")
    PaperNote(
        "Книжный набор разом: гарнитура Literata, интерлиньяж 1,32, поля по канону, " +
            "переносы, капитель и типограф. Каждую мелочь ниже можно " +
            "включить и выключить по отдельности - чтобы было с чем сравнивать.",
        Modifier.padding(top = 8.dp),
    )
    Spacer(Modifier.height(6.dp))
    PaperButton("Набрать как книгу", icon = Glyphs.AutoStories, primary = true, modifier = Modifier.fillMaxWidth()) {
        scope.launch { s.setBookTypography() }
    }
    Spacer(Modifier.height(6.dp))
    PaperToggle("Книжные поля 2:3:4:6", prefs.readerCanon) { scope.launch { s.setReaderCanon(it) } }
    PaperToggle("Капитель в начале главы", prefs.readerSmallCaps) { scope.launch { s.setReaderSmallCaps(it) } }
    PaperToggle("Типограф: тире и неразрывные", prefs.readerTypograph) { scope.launch { s.setReaderTypograph(it) } }
    PaperToggle("Неровности печати", prefs.readerImperfect) { scope.launch { s.setReaderImperfect(it) } }
    PaperNote(
        "Поля по канону Ван де Граафа: внутреннее, верхнее, внешнее и нижнее как 2:3:4:6, " +
            "полоса смещена к корешку и вверх - от этого разворот и читается книгой. " +
            "Капитель: первые слова главы прописными пониженного кегля, первый абзац без " +
            "отступа. Типограф: дефис между пробелами становится тире, после коротких слов " +
            "неразрывный пробел (длина текста не меняется, места в книге не едут). " +
            "Неровности: полоса каждой страницы перекошена на доли градуса, базовые линии " +
            "соседних страниц не совпадают - как в печати.",
        Modifier.padding(top = 8.dp),
    )

    labels("Номер в углу страницы")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.FOOTERS.forEach { id ->
            PaperChip(Settings.footerLabel(id), selected = prefs.readerFooter == id) { scope.launch { s.setReaderFooter(id) } }
        }
    }
    PaperNote(
        if (prefs.readerFooter == Settings.FOOTER_BOOK)
            "Как в типографской книге: автор на левой странице, название на правой, под ними " +
                "тонкая линейка, а внизу по центру номер между тире. На одной странице автор " +
                "и название стоят рядом."
        else "Внизу справа, на самой странице: панель с теми же числами прячется по тапу, а " +
            "место в книге хочется видеть, не трогая экран.",
        Modifier.padding(top = 8.dp),
    )

    Spacer(Modifier.height(6.dp))
    PaperToggle("Абзацный отступ", prefs.readerIndent) { scope.launch { s.setReaderIndent(it) } }
    PaperNote(
        "Как в свёрстанной книге: абзац начинается отступом первой строки, а не отбивкой " +
            "между абзацами. Продолжению абзаца на новой странице отступ не ставится.",
        Modifier.padding(top = 8.dp),
    )
    PaperToggle("Переносы", prefs.readerHyphens) { scope.launch { s.setReaderHyphens(it) } }
    PaperNote(
        "С выключкой по ширине без переносов строка растаскивается дырами между словами - " +
            "на широком экране это видно сразу. Вместе с переносами включается и абзацный " +
            "разбор: строки раскладываются по всему абзацу, а не каждая сама по себе.",
        Modifier.padding(top = 8.dp),
    )

    labels("Разворот")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.SPREADS.forEach { id ->
            PaperChip(Settings.spreadLabel(id), selected = prefs.readerSpread == id) { scope.launch { s.setReaderSpread(id) } }
        }
    }
    PaperNote(
        buildString {
            append(
                when (prefs.readerSpread) {
                    Settings.SPREAD_AUTO ->
                        "Две страницы рядом, когда экран это позволяет: раскрытая " +
                            "книжка-телефон, планшет, телефон набок. На узком экране в книжном " +
                            "виде - половина настоящего разворота: читаешь левую страницу, " +
                            "смахнул - книга доехала до правой, смахнул ещё - лист перевернулся " +
                            "вокруг корешка, и снова левая. За корешком видна полоска соседней " +
                            "страницы: без неё экран резал книгу ровно по сгибу."
                    Settings.SPREAD_ON ->
                        "Всегда две страницы, даже на узком экране. Полосы там выходят в " +
                            "половину ширины - кегль, скорее всего, придётся убавить."
                    else ->
                        "Всегда одна страница. В книжном виде это книга целиком в экране: " +
                            "корешок слева, обрез справа, лист заворачивается на месте."
                }
            )
            if (!prefs.readerPaged) {
                append(" Работает при листании страницами: сейчас выбрана прокрутка, ")
                append("а в ленте карточка одна.")
            }
        },
        Modifier.padding(top = 8.dp),
    )

    labels("Перелистывание")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.PAGE_TURNS.forEach { id ->
            PaperChip(Settings.pageTurnLabel(id), selected = prefs.readerPageTurn == id) { scope.launch { s.setReaderPageTurn(id) } }
        }
    }
    PaperNote(
        buildString {
            append(
                when (prefs.readerPageTurn) {
                    Settings.TURN_DECK ->
                        "Верхнюю карточку смахивают вбок - она чуть отклоняется, будто её " +
                            "отбросили, - а следующая всплывает из колоды на её место."
                    Settings.TURN_SLIDE -> "Обе страницы едут вбок - обычное листание."
                    else -> "Страница растворяется, не двигаясь с места."
                }
            )
            if (!prefs.readerPaged) {
                append(" Работает при листании страницами: сейчас выбрана прокрутка, ")
                append("и смахивать там нечего.")
            }
        },
        Modifier.padding(top = 8.dp),
    )
}

@Composable
private fun Label(text: String) {
    PaperLabel(text, Modifier.padding(top = 8.dp))
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun <T> NumberRow(
    values: List<T>,
    selected: T,
    format: (T) -> String,
    onPick: (T) -> Unit,
) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v -> PaperChip(format(v), selected = v == selected) { onPick(v) } }
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
    Settings.FONT_PT_SERIF -> "PT Serif: русская классика ПараТайпа, строгая и ясная - как в хорошем издании."
    Settings.FONT_LORA -> "Lora: тёплая, с каллиграфическим ходом - для романа вечером."
    Settings.FONT_MERRIWEATHER -> "Merriweather: крупное очко и плотный штрих - лучшая для e-ink и мелкого экрана."
    Settings.FONT_BITTER -> "Bitter: брусковые засечки, очень чёткая - на электронной бумаге не выцветает."
    Settings.FONT_PT_SANS -> "PT Sans: рубленая ПараТайпа, для тех, кто читает без засечек."
    Settings.FONT_SERIF -> "Системная с засечками: на разных телефонах своя."
    Settings.FONT_SANS -> "Системная рубленая."
    else -> "Машинописная: как рукопись."
}
