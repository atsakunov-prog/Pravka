package ru.zf.slushalka.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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

/** Шрифт, кегль, поля, интерлиньяж, цвет бумаги - обычный набор читалки. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ReaderSettingsDialog(app: SlushalkaApp, onGallery: () -> Unit, onClose: () -> Unit) {
    val state = app.state
    val prefs by state.prefs.collectAsState()
    val scope = rememberCoroutineScope()
    val s = state.settings

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text("Как читать") },
        text = {
            Column(Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState())) {

                // Первым - режим для электронной книги: он меняет разом всё
                // остальное, и на Boox его ищут прежде кегля.
                Toggle("Для электронной книги (e-ink)", prefs.readerEink) {
                    scope.launch { s.setReaderEink(it) }
                }
                Text(
                    if (prefs.readerEink)
                        "Чистый чёрный на белом, кегль на ${Settings.EINK_SIZE_BOOST} крупнее и текст на ступень " +
                            "жирнее, страницами, без теней, зерна и анимаций - они на электронной бумаге " +
                            "мерцают и серят. Выбранное ниже сохранено и вернётся, когда режим выключишь."
                    else "Onyx Boox, PocketBook, Kobo, Hisense: контраст, крупный и плотный шрифт, листание " +
                        "без анимации и физическими кнопками." +
                        if (ru.zf.slushalka.data.EinkDevice.likely) " Похоже, это как раз электронная книга." else "",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Toggle("Листать кнопками громкости", prefs.readerVolumeKeys) {
                    scope.launch { s.setReaderVolumeKeys(it) }
                }
                Text(
                    "Громкость вниз - вперёд, вверх - назад, пока ничего не звучит: при записи и озвучке " +
                        "кнопки снова про громкость. Кнопки листания электронных книг работают всегда.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Label("Как листать")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(false to "Прокруткой", true to "Страницами").forEach { (paged, title) ->
                        FilterChip(
                            selected = prefs.readerPaged == paged,
                            onClick = { scope.launch { s.setReaderPaged(paged) } },
                            label = { Text(title) },
                        )
                    }
                }
                Text(
                    if (prefs.readerPaged)
                        "Настоящие страницы: текст меряется в той ширине и тем шрифтом, каким " +
                            "будет нарисован, поэтому строка не режется краем пополам. " +
                            "Перелистывается смахиванием вбок и тапом по краям."
                    else "Обычная лента, а тап по краю листает ровно на страницу: следующая " +
                        "начинается со строки, которая не влезла целиком. Пальцем крутишь, " +
                        "тапом листаешь - одно другому не мешает. Тап посередине прячет панели.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                Label("Шрифт")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Settings.FONTS.forEach { (id, title) ->
                        FilterChip(
                            selected = prefs.readerFont == id,
                            onClick = { scope.launch { s.setReaderFont(id) } },
                            label = { Text(title, style = TextStyle(fontFamily = fontOf(id))) },
                        )
                    }
                }

                Text(
                    fontNote(prefs.readerFont),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )

                // Сколько знаков в строке - главная мерка книжного набора.
                // В книге их 45-55: короче строка - глаз скачет, длиннее -
                // теряет начало следующей. На телефоне столько выходит только
                // мелким кеглем, поэтому число показываем, а решает владелец.
                // Знаки считаются тем кеглем, каким книга правда набрана: в режиме
                // e-ink он крупнее сохранённого.
                val perLine = charsPerLine(prefs.readerView())
                Label("Кегль: ${prefs.readerSize}" + (perLine?.let { " · ≈$it знаков в строке" } ?: ""))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TextButton(onClick = { scope.launch { s.setReaderSize(prefs.readerSize - 1) } }) {
                        Text("А−", fontSize = 15.sp)
                    }
                    TextButton(onClick = { scope.launch { s.setReaderSize(prefs.readerSize + 1) } }) {
                        Text("А+", fontSize = 21.sp)
                    }
                    if (perLine != null && perLine !in 44..56) {
                        // Ширина знака прямо пропорциональна кеглю, значит
                        // знаков в строке - обратно ему: пересчёт одним
                        // делением, без подбора.
                        val bookSize = (prefs.readerSize * perLine / 50f).toInt().coerceIn(10, 40)
                        TextButton(onClick = { scope.launch { s.setReaderSize(bookSize) } }) {
                            Text("Как в книге")
                        }
                    }
                }

                Label("Междустрочье")
                NumberRow(
                    // 1,32 - книжный интерлиньяж: у антиквы в книге строки
                    // стоят теснее, чем принято в интерфейсах.
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

                val pics = state.text.collectAsState().value?.pictures?.size ?: 0
                val extracted = state.picturesOnDisk()
                Label("Картинки")
                Text(
                    when {
                        pics > 0 ->
                            "В книге их $pics. Стоят на своих местах в тексте; пока читаешь " +
                                "рядом, миниатюра висит в углу."
                        extracted > 0 ->
                            "Вынуто из файла: $extracted, но место в тексте для них не нашлось - " +
                                "смотреть их можно списком."
                        else -> "В этой книге картинок не нашлось."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (pics == 0 || extracted > pics) {
                    // Голое «не нашлось» ничего не объясняет: показываем, что
                    // именно разбор увидел в файле - по этим числам сразу ясно,
                    // чего не хватило.
                    state.parseReport()?.let { r ->
                        Text(
                            "Разбор увидел: ${r.line()}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    if (extracted > 0) {
                        TextButton(onClick = { onClose(); onGallery() }) { Text("Показать все") }
                    }
                    TextButton(onClick = { state.reparseText(); onClose() }) {
                        Text("Разобрать книгу заново")
                    }
                }

                Label("Бумага")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(
                        Settings.THEME_AUTO to "Как система",
                        Settings.THEME_PAPER to "Чёрным по белому",
                        Settings.THEME_SEPIA to "Сепия",
                        Settings.THEME_GREY to "Серая",
                        Settings.THEME_BLACK to "Белым по чёрному",
                    ).forEach { (id, title) ->
                        FilterChip(
                            selected = prefs.readerTheme == id,
                            onClick = { scope.launch { s.setReaderTheme(id) } },
                            label = { Text(title) },
                        )
                    }
                }

                PageLookSettings(app, labels = { Label(it) })

                Spacer(Modifier.height(10.dp))
                Toggle("Выключка по ширине", prefs.readerJustify) {
                    scope.launch { s.setReaderJustify(it) }
                }
                Toggle("Не гасить экран", prefs.readerKeepAwake) {
                    scope.launch { s.setReaderKeepAwake(it) }
                }

                Label("Переход со звука")
                val marked = state.isMarkedUp()
                Text(
                    when {
                        marked ->
                            "Книга размечена: место при переходе считается по карте мгновенно, " +
                                "ничего не распознаётся."
                        !app.recognizer.supported ->
                            "На этом устройстве распознавание файла недоступно - место берётся " +
                                "по карте, с точностью до страницы-другой."
                        else ->
                            "Книга не размечена. Разметка пройдёт по записи пробами, распознает " +
                                "их прямо на телефоне и найдёт в тексте - это ничего не стоит и " +
                                "не ходит в сеть. Карта ляжет файлом в папку книги, и другие " +
                                "устройства возьмут готовую."
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (app.recognizer.supported) {
                    Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        TextButton(onClick = { state.markupBook(); onClose() }) {
                            Text(if (marked) "Разметить ещё раз" else "Разметить книгу")
                        }
                        TextButton(onClick = { state.testProbe(); onClose() }) {
                            Text("Одна проба")
                        }
                    }
                    Toggle("Сверять место при переходе", prefs.refineOnSwitch) {
                        scope.launch { s.setRefineOnSwitch(it) }
                    }
                    Text(
                        "Про запас, для мест, до которых разметка не добралась: одна проба " +
                            "перед самым переходом.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Готово") } },
    )
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
        Text(
            "Сейчас включён режим e-ink: страница плоская, без теней и зерна. Выбранное здесь " +
                "вернётся, когда режим выключишь.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.PAGE_STYLES.forEach { id ->
            FilterChip(
                selected = prefs.readerPageStyle == id,
                onClick = { scope.launch { s.setReaderPageStyle(id) } },
                label = { Text(Settings.pageStyleLabel(id)) },
            )
        }
    }
    Text(
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
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    labels("Поле от края экрана: ${prefs.readerCardMargin}")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.CARD_MARGINS.forEach { v ->
            FilterChip(
                selected = prefs.readerCardMargin == v,
                onClick = { scope.launch { s.setReaderCardMargin(v) } },
                label = { Text(if (v == 0) "Без поля" else "$v") },
            )
        }
    }
    Text(
        "Сколько стола видно вокруг страницы. Ноль - карточка встык с краями, и её " +
            "верхние углы уходят под часы; несколько точек - и страница лежит в экране " +
            "целиком, всеми четырьмя углами на виду.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    labels("Тень под страницей")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.SHADOWS.forEach { id ->
            FilterChip(
                selected = prefs.readerShadow == id,
                onClick = { scope.launch { s.setReaderShadow(id) } },
                label = { Text(Settings.shadowLabel(id)) },
            )
        }
    }

    labels("Стол")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.TABLES.forEach { v ->
            FilterChip(
                selected = kotlin.math.abs(prefs.readerTable - v) < 0.01f,
                onClick = { scope.launch { s.setReaderTable(v) } },
                label = { Text(Settings.tableLabel(v)) },
            )
        }
    }
    Text(
        "Насколько поверхность вокруг страницы темнее её самой (на ночных темах - светлее). " +
            "К краям экрана она ещё немного темнеет: ровная заливка читается фоном, " +
            "затемнённая по углам - поверхностью, на которой что-то лежит.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(6.dp))
    Toggle("Фаска по кромке", prefs.readerBevel) { scope.launch { s.setReaderBevel(it) } }
    Toggle("Блик и затенение", prefs.readerSheen) { scope.launch { s.setReaderSheen(it) } }
    Toggle("Матовость: зерно и волокна", prefs.readerGrain) { scope.launch { s.setReaderGrain(it) } }
    Text(
        "Три слоя объёма, каждый сам по себе: светлая линия сверху и тёмная снизу, блик по " +
            "верхней трети с затенением по нижней пятой, и матовость - мелкое зерно в тон " +
            "поверхности, как иней на стекле диска Правки, плюс те же точки крупнее и размытые, " +
            "волокнами. Матовы бумага и картон переплёта; на столе только свет и тень.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    labels("Набор")
    Text(
        "Книжный набор разом: гарнитура Literata, интерлиньяж 1,32, поля по канону, " +
            "переносы, капитель и типограф. Каждую мелочь ниже можно " +
            "включить и выключить по отдельности - чтобы было с чем сравнивать.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(6.dp))
    FilledTonalButton(onClick = { scope.launch { s.setBookTypography() } }) {
        Text("Набрать как книгу")
    }
    Spacer(Modifier.height(6.dp))
    Toggle("Книжные поля 2:3:4:6", prefs.readerCanon) { scope.launch { s.setReaderCanon(it) } }
    Toggle("Капитель в начале главы", prefs.readerSmallCaps) { scope.launch { s.setReaderSmallCaps(it) } }
    Toggle("Типограф: тире и неразрывные", prefs.readerTypograph) { scope.launch { s.setReaderTypograph(it) } }
    Toggle("Неровности печати", prefs.readerImperfect) { scope.launch { s.setReaderImperfect(it) } }
    Text(
        "Поля по канону Ван де Граафа: внутреннее, верхнее, внешнее и нижнее как 2:3:4:6, " +
            "полоса смещена к корешку и вверх - от этого разворот и читается книгой. " +
            "Капитель: первые слова главы прописными пониженного кегля, первый абзац без " +
            "отступа. Типограф: дефис между пробелами становится тире, после коротких слов " +
            "неразрывный пробел (длина текста не меняется, места в книге не едут). " +
            "Неровности: полоса каждой страницы перекошена на доли градуса, базовые линии " +
            "соседних страниц не совпадают - как в печати.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    labels("Номер в углу страницы")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.FOOTERS.forEach { id ->
            FilterChip(
                selected = prefs.readerFooter == id,
                onClick = { scope.launch { s.setReaderFooter(id) } },
                label = { Text(Settings.footerLabel(id)) },
            )
        }
    }
    Text(
        if (prefs.readerFooter == Settings.FOOTER_BOOK)
            "Как в типографской книге: автор на левой странице, название на правой, под ними " +
                "тонкая линейка, а внизу по центру номер между тире. На одной странице автор " +
                "и название стоят рядом."
        else "Внизу справа, на самой странице: панель с теми же числами прячется по тапу, а " +
            "место в книге хочется видеть, не трогая экран.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    Spacer(Modifier.height(6.dp))
    Toggle("Абзацный отступ", prefs.readerIndent) { scope.launch { s.setReaderIndent(it) } }
    Text(
        "Как в свёрстанной книге: абзац начинается отступом первой строки, а не отбивкой " +
            "между абзацами. Продолжению абзаца на новой странице отступ не ставится.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Toggle("Переносы", prefs.readerHyphens) { scope.launch { s.setReaderHyphens(it) } }
    Text(
        "С выключкой по ширине без переносов строка растаскивается дырами между словами - " +
            "на широком экране это видно сразу. Вместе с переносами включается и абзацный " +
            "разбор: строки раскладываются по всему абзацу, а не каждая сама по себе.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    labels("Разворот")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.SPREADS.forEach { id ->
            FilterChip(
                selected = prefs.readerSpread == id,
                onClick = { scope.launch { s.setReaderSpread(id) } },
                label = { Text(Settings.spreadLabel(id)) },
            )
        }
    }
    Text(
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
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    labels("Перелистывание")
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Settings.PAGE_TURNS.forEach { id ->
            FilterChip(
                selected = prefs.readerPageTurn == id,
                onClick = { scope.launch { s.setReaderPageTurn(id) } },
                label = { Text(Settings.pageTurnLabel(id)) },
            )
        }
    }
    Text(
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
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
}

@Composable
private fun Label(text: String) {
    Spacer(Modifier.height(14.dp))
    Text(text, style = MaterialTheme.typography.labelMedium)
    Spacer(Modifier.height(4.dp))
}

@Composable
private fun <T> NumberRow(
    values: List<T>,
    selected: T,
    format: (T) -> String,
    onPick: (T) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        values.forEach { v ->
            FilterChip(
                selected = v == selected,
                onClick = { onPick(v) },
                label = { Text(format(v)) },
            )
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, onChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().height(44.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
        Switch(checked = checked, onCheckedChange = onChange)
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
