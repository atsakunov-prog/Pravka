package ru.zf.slushalka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.Lens
import ru.zf.slushalka.ask.LensItem
import ru.zf.slushalka.ask.Razbor
import ru.zf.slushalka.ask.RazborChapter
import ru.zf.slushalka.ask.RazborEngine
import ru.zf.slushalka.ask.RazborIdea
import ru.zf.slushalka.data.Settings
import ru.zf.slushalka.library.Book
import ru.zf.slushalka.text.BookText

/**
 * Разбор книги сервером: о книге, книга за 15 минут, идеи, главы с «что было
 * раньше», линзы по жанру.
 *
 * Без спойлер-барьера (08.10.2026, владелец: «он вообще не помогает, я
 * постоянно его выключаю»): разбор открыт целиком - все главы, идеи, «за 15
 * минут», все записи линз. Где читатель, видно по главе: вкладка «Главы»
 * открывается на ней, у неё сразу видно «что было раньше» и метка «ты здесь».
 *
 * «К месту» у записи со ссылкой: читалка листает туда, плеер встаёт туда по
 * разметке, как «Слушать отсюда».
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun RazborSheet(
    app: SlushalkaApp,
    /** Где читатель: по нему - текущая глава. */
    cutoffChar: Int,
    /** К месту: знак в тексте и нашёл ли его сервер по цитате (тогда это «страница», а не начало главы). */
    onGo: (charOffset: Int, exact: Boolean) -> Unit,
    onClose: () -> Unit,
) {
    val book by app.state.current.collectAsState()
    val text by app.state.text.collectAsState()
    val razbors by app.razbor.states.collectAsState()
    val b = book
    val t = text
    val r = b?.let { razbors[it.id] }

    // Лист закрыли - «за 15 минут» замолкает: без листа его не остановить.
    DisposableEffect(Unit) { onDispose { app.clip.stop() } }

    var tab by remember { mutableStateOf(TAB_CARD) }
    var ordering by remember { mutableStateOf(false) }
    val list = rememberLazyListState()

    // Глава, где читатель, - с единицы, как у сервера.
    val here = if (t != null) t.chapterIndexAt(cutoffChar) + 1 else 1
    val finished = b != null && t != null &&
        (app.positions.get(b.id).finished || cutoffChar >= t.length * 0.97)

    PaperSheet(
        app = app,
        onClose = onClose,
        icon = if (ordering) null else Glyphs.Lightbulb,
        title = if (ordering) "Разобрать заново" else "Разбор",
        subtitle = when {
            ordering || b == null || t == null -> null
            finished -> "${b.title} · дочитана"
            else -> "${b.title} · глава $here из ${t.chapters.size}"
        },
        tall = r != null && !ordering,
        scroll = r == null || ordering,
        actions = {
            if (ordering) PaperIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Назад") { ordering = false }
        },
    ) {
        if (b == null || t == null || r == null) {
            PaperBusy("Разбор ещё читается…")
            return@PaperSheet
        }
        if (ordering) {
            Spacer(Modifier.height(12.dp))
            RazborOrderPanel(app, b, t)
            return@PaperSheet
        }
        Spacer(Modifier.height(8.dp))

        // Вкладки: постоянные и линзы, какие сервер положил под жанр книги.
        val tabs = buildList {
            add(Triple(TAB_CARD, "О книге", Glyphs.LibraryBooks))
            if (r.summary15.isNotBlank()) add(Triple(TAB_SHORT, "За 15 минут", Glyphs.Headphones))
            if (r.ideas.isNotEmpty()) add(Triple(TAB_IDEAS, "Идеи", Glyphs.Lightbulb))
            if (r.chapters.isNotEmpty()) add(Triple(TAB_CHAPTERS, "Главы", Glyphs.Toc))
            r.lenses.forEach { add(Triple(it.key, it.title, lensIcon(it.key))) }
        }
        val current = tabs.firstOrNull { it.first == tab }?.first ?: TAB_CARD
        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            tabs.forEach { (key, label, icon) -> PaperChip(label, selected = current == key, icon = icon) { tab = key } }
        }
        Spacer(Modifier.height(10.dp))

        // Вкладка сменилась - с начала, а «Главы» - с той, где читатель: все
        // главы открыты, и своя иначе терялась бы посреди списка.
        LaunchedEffect(current) {
            val at = if (current == TAB_CHAPTERS) r.chapters.indexOfFirst { it.chapter >= here }.coerceAtLeast(0) else 0
            list.scrollToItem(at)
        }
        LazyColumn(Modifier.weight(1f), state = list, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            when (current) {
                TAB_CARD -> item { CardPage(r) }
                TAB_SHORT -> {
                    if (r.audio.isNotBlank()) item { ClipControls(app, r.audio) }
                    items(r.summary15.split('\n').map { it.trim() }.filter { it.isNotEmpty() }) { p ->
                        Text(p, style = bookBody())
                    }
                }
                TAB_IDEAS -> items(r.ideas) { idea -> IdeaCard(idea, t, onGo) }
                TAB_CHAPTERS -> items(r.chapters, key = { it.chapter }) { ch ->
                    ChapterCard(ch, here = !finished && ch.chapter == here, t, onGo)
                }
                else -> r.lenses.firstOrNull { it.key == current }?.let { lens ->
                    if (lens.items.isEmpty()) {
                        item { PaperNote("Здесь пока пусто.", Modifier.padding(vertical = 12.dp)) }
                    }
                    items(lens.items) { item -> LensCard(item, t, onGo) }
                }
            }
        }

        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                listOfNotNull(
                    r.model.takeIf { it.isNotBlank() }?.let { Settings.modelLabel(it) },
                    r.created.takeIf { it > 0 }?.let { stamp(it) },
                    r.usd.takeIf { it > 0 }?.let { "%.2f $".format(it) },
                ).joinToString(" · ", prefix = "сервер · "),
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f),
            )
            if (app.razbor.canOrder(b)) MiniAction(Icons.Default.Refresh, "Разобрать заново") { ordering = true }
        }
    }
}

/**
 * Заказ разбора у сервера: цена прикидкой, кнопка, а пока сервер считает -
 * кто заказал и когда. Сервер пишет статус в файл заказа, движок читает его
 * раз в минуту и, когда готово, сам перечитывает разбор и справочник.
 */
@Composable
fun RazborOrderPanel(app: SlushalkaApp, book: Book, text: BookText) {
    val orders by app.razbor.orders.collectAsState()
    val razbors by app.razbor.states.collectAsState()
    val order = orders[book.id]
    val again = razbors[book.id] != null
    val coroutine = rememberCoroutineScope()
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // Открыли - заказ с сервера: его могли положить с другого телефона или из
    // панели «Книги». Считается - движок следит сам, раз в минуту.
    LaunchedEffect(book.id) { app.razbor.checkOrder(book, text) }

    if (order?.waiting == true) {
        PaperCard {
            Text(
                (if (order.status == RazborEngine.QUEUED) "Заказан" else "Готовится") +
                    " - заказал ${order.who}, ${stamp(order.at)}. Сервер считает 5–10 минут; " +
                    "готовое появится в Слушалке само.",
                style = MaterialTheme.typography.bodyMedium,
            )
            PaperBusy(if (order.status == RazborEngine.QUEUED) "В очереди у сервера" else "Сервер читает книгу")
        }
        return
    }
    Text(
        "Сервер библиотеки прочтёт книгу целиком (${Settings.modelLabel(ru.zf.slushalka.ask.Models.OPUS_ID)}) " +
            "и положит в её папку разбор: о книге, книга за 15 минут - текстом и голосом, идеи, " +
            "линзы по жанру, «что было раньше» по главам - и полный справочник: главы, герои, " +
            "связи, хронология.",
        style = bookBody(),
    )
    if (order?.failed == true) {
        Spacer(Modifier.height(8.dp))
        PaperError("Прошлый заказ не вышел (${stamp(order.at)}): ${order.error.ifBlank { "сервер не сказал почему" }}")
    }
    Spacer(Modifier.height(12.dp))
    val usd = remember(text) { app.razbor.estimate(text) }
    PaperButton(
        when {
            busy -> "Кладу заказ…"
            again -> "Разобрать заново · ≈ %.2f $".format(usd)
            else -> "Разобрать книгу · ≈ %.2f $".format(usd)
        },
        icon = Glyphs.Lightbulb,
        primary = !again,
        enabled = !busy,
        modifier = Modifier.fillMaxWidth(),
    ) {
        busy = true
        error = null
        coroutine.launch {
            app.razbor.order(book, text).onFailure { error = it.message ?: "Не вышло заказать" }
            busy = false
        }
    }
    PaperNote("Займёт 5–10 минут, само появится в Слушалке.", Modifier.padding(top = 6.dp))
    error?.let { PaperError(it) }
}

@Composable
private fun CardPage(r: Razbor) = Column {
    val c = r.card
    if (c.about.isNotBlank()) Text(c.about, style = bookBody())
    listOf("Жанр" to c.genre, "Для кого" to c.age, "Эпоха" to c.era, "Серия" to c.series)
        .filter { it.second.isNotBlank() }
        .forEach { (label, value) ->
            PaperLabel(label)
            Text(value, style = MaterialTheme.typography.bodyLarge)
        }
    if (r.lenses.isNotEmpty()) {
        PaperLabel("Линзы")
        PaperNote(r.lenses.joinToString(" · ") { it.title })
    }
}

/** «За 15 минут» голосом: тот же рассказчик, что у машинных аудиокниг, звук - с сервера. */
@Composable
private fun ClipControls(app: SlushalkaApp, path: String) {
    val clip by app.clip.state.collectAsState()
    val mine = clip.path == path
    PaperCard {
        Row(verticalAlignment = Alignment.CenterVertically) {
            PlayPauseButton(mine && (clip.playing || clip.loading), size = 48.dp) {
                // Книга и изложение разом - каша: книга встаёт на паузу.
                app.clip.toggle(path) { app.player.pauseForAsking() }
            }
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    when {
                        !mine -> "Слушать"
                        clip.loading -> "Загружаю с сервера…"
                        else -> formatClock(clip.posMs) + if (clip.durMs > 0) " / " + formatClock(clip.durMs) else ""
                    },
                    style = MaterialTheme.typography.titleSmall,
                )
                if (mine && clip.durMs > 0) {
                    Slider(
                        value = (clip.posMs.toFloat() / clip.durMs).coerceIn(0f, 1f),
                        onValueChange = { app.clip.seekTo(it) },
                    )
                } else {
                    PaperNote("Тем же голосом, что машинные аудиокниги")
                }
            }
            if (mine) {
                MiniAction(Glyphs.Replay, "15 с") { app.clip.seekBy(-15_000) }
            }
        }
        clip.error?.takeIf { mine }?.let { PaperError(it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun IdeaCard(idea: RazborIdea, text: BookText, onGo: (Int, Boolean) -> Unit) {
    PaperCard {
        if (idea.title.isNotBlank()) Text(idea.title, style = MaterialTheme.typography.titleMedium)
        if (idea.idea.isNotBlank()) {
            Spacer(Modifier.height(4.dp))
            Text(idea.idea, style = MaterialTheme.typography.bodyLarge)
        }
        if (idea.why.isNotBlank()) {
            PaperLabel("Почему неочевидно")
            Text(idea.why, style = MaterialTheme.typography.bodyMedium)
        }
        if (idea.question.isNotBlank()) {
            PaperLabel("Вопрос")
            Text(idea.question, style = MaterialTheme.typography.bodyMedium.copy(fontStyle = FontStyle.Italic))
        }
        if (idea.links.isNotEmpty()) {
            PaperLabel("К месту")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                idea.links.forEach { ref ->
                    PaperChip("гл. ${ref.chapter}", selected = false, icon = Glyphs.AutoStories) {
                        onGo(ref.at(text), ref.quote.isNotBlank())
                    }
                }
            }
        }
    }
}

/**
 * Глава: что в ней, а по тапу - и что было до неё. Глава, где читатель, -
 * закрашена, с меткой «ты здесь» и «что было раньше» сразу: за ним сюда и
 * приходят, вернувшись к книге.
 */
@Composable
private fun ChapterCard(ch: RazborChapter, here: Boolean, text: BookText, onGo: (Int, Boolean) -> Unit) {
    var expanded by remember(ch.chapter) { mutableStateOf(false) }
    PaperCard(onClick = { expanded = !expanded }, highlight = here) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Глава ${ch.chapter}" + if (ch.title.isNotBlank()) ". ${ch.title}" else "",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.weight(1f),
            )
            if (here) {
                Text(
                    "ты здесь",
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.SemiBold),
                    color = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.padding(start = 8.dp),
                )
            }
        }
        Spacer(Modifier.height(4.dp))
        if (ch.summary.isNotBlank()) {
            Text(
                ch.summary,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (ch.before.isNotBlank() && (expanded || here)) {
            PaperLabel("Что было раньше")
            Text(
                ch.before,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = if (expanded) Int.MAX_VALUE else 4,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (expanded) {
            Spacer(Modifier.height(10.dp))
            val at = text.chapters.getOrNull(ch.chapter - 1)?.start ?: ch.pos.coerceAtLeast(0)
            PaperButton("К главе", icon = Glyphs.AutoStories) { onGo(at, false) }
        }
    }
}

/** Запись линзы: заголовок с приговором, текст, строки, цитата и «К месту». */
@Composable
private fun LensCard(item: LensItem, text: BookText, onGo: (Int, Boolean) -> Unit) {
    val ref = item.ref
    PaperCard {
        if (item.title.isNotBlank() || item.verdict.isNotBlank()) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(item.title, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                if (item.verdict.isNotBlank()) {
                    PaperChip(item.verdict, selected = true, warn = item.verdict.lowercase() == "выдумка") {}
                }
            }
        }
        if (item.text.isNotBlank()) {
            if (item.title.isNotBlank()) Spacer(Modifier.height(4.dp))
            Text(item.text, style = MaterialTheme.typography.bodyLarge)
        }
        item.lines.forEach { (label, value) ->
            PaperLabel(label)
            Text(value, style = MaterialTheme.typography.bodyMedium)
        }
        if (ref != null) {
            if (ref.quote.isNotBlank()) {
                Spacer(Modifier.height(6.dp))
                PaperQuote(ref.quote, maxLines = 3)
            }
            Row {
                Spacer(Modifier.weight(1f))
                MiniAction(Glyphs.AutoStories, "К месту · гл. ${ref.chapter}") { onGo(ref.at(text), ref.quote.isNotBlank()) }
            }
        }
    }
}

private fun lensIcon(key: String) = when (key) {
    "finance" -> Glyphs.Timeline
    "myths" -> Glyphs.Psychology
    "world" -> Glyphs.TravelExplore
    "rulers" -> Glyphs.History
    "science" -> Glyphs.AutoAwesome
    "print" -> Glyphs.Brush
    "kids.questions" -> Glyphs.QuestionAnswer
    "kids.words" -> Glyphs.Translate
    Lens.SCARY -> Glyphs.ChildCare
    else -> Glyphs.Visibility
}

private const val TAB_CARD = "card"
private const val TAB_SHORT = "short"
private const val TAB_IDEAS = "ideas"
private const val TAB_CHAPTERS = "chapters"

private fun stamp(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("d.MM HH:mm", Locale("ru")).format(Date(ms))
