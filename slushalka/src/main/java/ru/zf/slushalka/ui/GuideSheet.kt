package ru.zf.slushalka.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.Send
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.slushalka.SlushalkaApp
import ru.zf.slushalka.ask.GuideChapter
import ru.zf.slushalka.ask.GuideEntry
import ru.zf.slushalka.ask.GuideState
import ru.zf.slushalka.ask.Prompts
import ru.zf.slushalka.data.Settings

/**
 * Справочник по книге: главы, герои, места, словарь.
 *
 * Показывает только дочитанные главы: статьи о героях, которые ещё не
 * появились, спрятаны, заметки о будущих главах - тоже, и текущая глава
 * считается ещё не прочитанной - её запись рассказала бы то, что на этой
 * странице впереди. Тумблер «всё, со спойлерами» снимает это руками. Пока
 * справочника нет - кнопка заказать (пакетом, вдвое дешевле, обычно в течение
 * часа); пока считается - лист сам проверяет раз в минуту.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GuideSheet(
    app: SlushalkaApp,
    /** Где читатель: по этому месту считается «текущая глава». */
    cutoffChar: Int,
    /** С чего начать поиск - имя героя из абзаца, например. */
    initialQuery: String = "",
    onAsk: (question: String) -> Unit,
    /** Перейти к началу главы (номер с единицы); null - перейти некуда (плеер). */
    onGoChapter: ((chapter: Int) -> Unit)? = null,
    onClose: () -> Unit,
) {
    val book by app.state.current.collectAsState()
    val text by app.state.text.collectAsState()
    val prefs by app.state.prefs.collectAsState()
    val states by app.guide.states.collectAsState()
    val coroutine = rememberCoroutineScope()

    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var query by remember { mutableStateOf(initialQuery) }
    var tab by remember { mutableIntStateOf(if (initialQuery.isBlank()) 0 else 1) }
    var showAll by remember { mutableStateOf(false) }
    var open by remember { mutableStateOf<GuideEntry?>(null) }
    var openChapter by remember { mutableStateOf<GuideChapter?>(null) }
    /** О чём спрашивают и какого оно вида: 0 герой, 1 место, 2 слово, 3 глава. */
    var askFor by remember { mutableStateOf<Pair<GuideEntry, Int>?>(null) }
    // Придуманные моделью вопросы про статью - на время листа, чтобы второе
    // открытие той же статьи не стоило второго запроса.
    val suggestCache = remember { HashMap<String, List<String>>() }

    val b = book
    val t = text

    // При открытии: своё состояние с диска, чужой файл из папки книги, проверка
    // пакета - всё одним вызовом.
    LaunchedEffect(b?.id, t) { b?.let { app.guide.sync(it, t).onFailure { e -> error = e.message } } }
    val st = b?.let { states[it.id] }

    // Пакет считается - проверяем раз в минуту, пока лист открыт.
    LaunchedEffect(st?.status, b?.id) {
        val bk = b ?: return@LaunchedEffect
        if (st?.status != GuideState.Status.PENDING) return@LaunchedEffect
        while (true) {
            delay(60_000)
            app.guide.refresh(bk, t).onFailure { error = it.message }
        }
    }

    // Дочитанные главы: текущая не в счёт.
    val readChapters = t?.chapterIndexAt(cutoffChar) ?: 0
    val upTo = if (showAll) Int.MAX_VALUE else readChapters

    val ready = st?.status == GuideState.Status.READY
    // Статья, глава и вопрос открываются в том же листе, а не диалогом
    // поверх него: стрелка назад возвращает к списку на том же месте.
    val detailTitle = when {
        askFor != null -> "Спросить про «${askFor!!.first.name}»"
        open != null -> open!!.name
        openChapter != null -> "Глава ${openChapter!!.chapter}" +
            (if (openChapter!!.title.isNotBlank()) ". ${openChapter!!.title}" else "")
        else -> null
    }
    fun back() {
        when {
            askFor != null -> askFor = null
            open != null -> open = null
            else -> openChapter = null
        }
    }

    PaperSheet(
        app = app,
        onClose = onClose,
        icon = if (detailTitle != null) null else Glyphs.MenuBook,
        title = detailTitle ?: "Справочник",
        subtitle = if (detailTitle != null) null
        else if (b != null && t != null) "${b.title} · дочитано глав: $readChapters из ${t.chapters.size}" else null,
        // Во весь рост - только когда есть что листать; предложение заказать
        // справочник во весь экран смотрелось бы пустым.
        tall = ready,
        scroll = !ready,
        actions = {
            if (detailTitle != null) {
                PaperIconButton(Icons.AutoMirrored.Filled.KeyboardArrowLeft, "Назад") { back() }
            }
        },
    ) {
        if (b == null || t == null) {
            PaperBusy("Текст книги ещё разбирается…")
            return@PaperSheet
        }
        Spacer(Modifier.height(12.dp))

        when (st?.status) {
            null -> {
                val est = remember(t, prefs.guideModel) { app.guide.estimate(t, prefs.guideModel) }
                Text(
                    "Справочника по этой книге ещё нет. ${Settings.modelLabel(prefs.guideModel)} прочтёт её " +
                        "целиком - ${est.pages} стр." + (if (est.parts > 1) " в ${est.parts} частях" else "") +
                        " - и составит краткое содержание каждой главы и статьи о героях, местах и словах, " +
                        "каждую с привязкой к главе. Ты увидишь только то, что уже дочитал.",
                    style = bookBody(),
                )
                Spacer(Modifier.height(10.dp))
                PaperNote(
                    "Считается пакетным запросом: вдвое дешевле обычного, готово обычно в течение часа, " +
                        "самое позднее к завтрашнему дню. Приложение можно закрыть - справочник заберётся " +
                        "при следующем открытии книги и ляжет файлом в её папку: кто читает ту же книгу с " +
                        "той же папки, получит его даром. Модель меняется в настройках, раздел «Модели».",
                )
                Spacer(Modifier.height(16.dp))
                PaperButton(
                    if (busy) "Отправляю книгу…" else "Составить справочник · ≈ %.2f $".format(est.usd),
                    icon = Glyphs.AutoAwesome,
                    primary = true,
                    enabled = !busy,
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    busy = true
                    error = null
                    coroutine.launch {
                        app.guide.start(b, t).onFailure { error = it.message ?: "Не вышло заказать" }
                        busy = false
                    }
                }
            }

            GuideState.Status.PENDING -> {
                Text("Готовится…", style = MaterialTheme.typography.titleMedium)
                PaperNote("Заказан ${stamp(st.createdAt)}, проверено ${stamp(st.checkedAt)}. Обычно час; лист проверяет сам, пока открыт.")
                PaperBusy(if (busy) "Проверяю…" else "Жду пакет")
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    PaperButton("Проверить сейчас", icon = Icons.Default.Refresh, enabled = !busy, modifier = Modifier.weight(1f)) {
                        busy = true
                        coroutine.launch {
                            app.guide.refresh(b, t).onFailure { error = it.message }
                            busy = false
                        }
                    }
                    PaperButton("Забыть заказ", icon = Icons.Default.Delete, modifier = Modifier.weight(1f)) { app.guide.forget(b) }
                }
            }

            GuideState.Status.FAILED -> {
                Text("Не вышло", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                PaperNote("Пакет посчитался, но справочник из ответа не собрался. Что именно случилось:")
                Spacer(Modifier.height(6.dp))
                PaperCard {
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        Text(st.error.ifBlank { "Причина неизвестна" }, style = MaterialTheme.typography.bodySmall)
                    }
                }
                if (st.costUsd > 0) {
                    PaperNote(
                        "Списано %.2f $ · ${Settings.modelLabel(st.model)} · ${stamp(st.createdAt)}".format(st.costUsd),
                        Modifier.padding(top = 6.dp),
                    )
                }
                Spacer(Modifier.height(12.dp))
                PaperButton("Заказать заново", icon = Icons.Default.Refresh, primary = true) { app.guide.forget(b) }
            }

            GuideState.Status.READY -> {
                val guide = st.guide ?: return@PaperSheet
                val a = askFor
                val e = open
                val ch = openChapter
                when {
                    a != null -> AskPage(app, b, a.first, a.second, upTo, readChapters, suggestCache) { q ->
                        askFor = null
                        onAsk(Prompts.aboutEntry(a.first.name, a.first.aliases, q))
                    }
                    e != null -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        EntryPage(e, guide, upTo, showAll) { other -> open = other }
                        Spacer(Modifier.height(16.dp))
                        PaperButton("Спросить Claude", icon = Glyphs.QuestionAnswer, primary = true, modifier = Modifier.fillMaxWidth()) {
                            askFor = e to guide.kindOf(e)
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    ch != null -> Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
                        Text(ch.summary, style = bookBody())
                        Spacer(Modifier.height(16.dp))
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            if (onGoChapter != null) {
                                PaperButton("К главе", icon = Glyphs.AutoStories, modifier = Modifier.weight(1f)) {
                                    openChapter = null
                                    onGoChapter(ch.chapter)
                                }
                            }
                            // Глава для вопроса притворяется статьёй: имя - номер и
                            // название, роль - содержание.
                            PaperButton("Спросить", icon = Glyphs.QuestionAnswer, primary = true, modifier = Modifier.weight(1f)) {
                                askFor = GuideEntry(
                                    name = "Глава ${ch.chapter}" + (if (ch.title.isNotBlank()) " («${ch.title}»)" else ""),
                                    aliases = emptyList(),
                                    chapter = ch.chapter,
                                    role = ch.summary,
                                    notes = emptyList(),
                                ) to 3
                            }
                        }
                        Spacer(Modifier.height(8.dp))
                    }
                    else -> {
                        PaperField(
                            value = query,
                            onValue = { query = it },
                            placeholder = "Имя, место, слово",
                            maxLines = 1,
                            leading = Icons.Default.Search,
                        ) {
                            PaperIconButton(
                                if (showAll) Glyphs.LockOpen else Icons.Default.Lock,
                                if (showAll) "Спрятать спойлеры" else "Показать всё",
                                active = showAll,
                            ) { showAll = !showAll }
                        }
                        PaperNote(
                            when {
                                showAll -> "Вся книга - со спойлерами. Замок - спрятать обратно."
                                readChapters == 0 -> "Первая глава ещё не дочитана - пока пусто."
                                else -> "Главы 1–$readChapters, без спойлеров."
                            },
                            Modifier.padding(start = 6.dp, top = 6.dp, bottom = 10.dp),
                            color = if (showAll) MaterialTheme.colorScheme.tertiary else Color.Unspecified,
                        )
                        val chapters = guide.chapters.filter { it.chapter <= upTo && it.matches(query) }
                        val lists = listOf(guide.characters, guide.places, guide.terms)
                        val visible = lists.map { list -> list.mapNotNull { it.visibleAt(upTo) }.filter { it.matches(query) } }
                        val events = guide.events.filter { it.chapter <= upTo && it.matches(query) }
                        val tabs = listOf(
                            Triple(Glyphs.Toc, "Главы", chapters.size),
                            Triple(Icons.Default.Person, "Герои", visible[0].size),
                            Triple(Icons.Default.Place, "Места", visible[1].size),
                            Triple(Glyphs.Translate, "Словарь", visible[2].size),
                            Triple(Glyphs.Timeline, "Хронология", events.size),
                        )
                        Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                            tabs.forEachIndexed { i, (icon, label, n) ->
                                PaperChip("$label · $n", selected = tab == i, icon = icon) { tab = i }
                            }
                        }
                        Spacer(Modifier.height(10.dp))
                        val empty = when (tab) {
                            0 -> chapters.isEmpty()
                            4 -> events.isEmpty()
                            else -> visible[tab - 1].isEmpty()
                        }
                        if (empty) {
                            PaperNote(
                                when {
                                    // Старый справочник: хронологии в нём нет вовсе, а не «пока пусто».
                                    tab == 4 && guide.events.isEmpty() ->
                                        "Этот справочник составлен до того, как в нём появилась хронология и " +
                                            "связи героев. «Пересобрать» внизу закажет его заново - с ними."
                                    query.isNotBlank() -> "Ничего похожего в дочитанных главах."
                                    readChapters == 0 -> "Откроется, когда дочитаешь первую главу."
                                    else -> "В дочитанных главах здесь пока пусто."
                                },
                                Modifier.padding(vertical = 12.dp),
                            )
                        }
                        LazyColumn(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            when (tab) {
                                // Хронология: по главам, где о событии узнаёт читатель;
                                // «когда» - время книги, если она его называет.
                                4 -> items(events) { ev ->
                                    PaperCard(onClick = onGoChapter?.let { go -> { go(ev.chapter) } }) {
                                        Row {
                                            Column(Modifier.width(70.dp)) {
                                                Text("гл. ${ev.chapter}", style = MaterialTheme.typography.labelLarge)
                                                if (ev.whenText.isNotBlank()) {
                                                    Text(ev.whenText, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                                }
                                            }
                                            Text(ev.text, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                                        }
                                    }
                                }
                                0 -> items(chapters) { c ->
                                    PaperCard(onClick = { openChapter = c }) {
                                        Text(
                                            "Глава ${c.chapter}" + (if (c.title.isNotBlank()) ". ${c.title}" else ""),
                                            style = MaterialTheme.typography.titleMedium,
                                        )
                                        Spacer(Modifier.height(4.dp))
                                        Text(c.summary, style = MaterialTheme.typography.bodyMedium, maxLines = 4, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                                else -> items(visible[tab - 1]) { en ->
                                    PaperCard(onClick = { open = en }) {
                                        Row(verticalAlignment = Alignment.CenterVertically) {
                                            Text(en.name, style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                                            Text("гл. ${en.chapter}", style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                                        }
                                        if (en.aliases.isNotEmpty()) {
                                            PaperNote(en.aliases.joinToString(", "))
                                        }
                                        Text(en.role, style = MaterialTheme.typography.bodyMedium, maxLines = 2, overflow = TextOverflow.Ellipsis)
                                    }
                                }
                            }
                        }
                        Row(Modifier.padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                "${Settings.modelLabel(st.model)} · ${stamp(st.createdAt)}" +
                                    (if (st.by.isNotBlank()) " · ${st.by}" else "") +
                                    (if (st.costUsd > 0) " · %.2f $".format(st.costUsd) else "") +
                                    (if (st.error.isNotBlank()) " · с оговорками" else ""),
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            MiniAction(Icons.Default.Refresh, "Пересобрать") { app.guide.forget(b) }
                        }
                        if (st.error.isNotBlank()) {
                            Text(
                                st.error,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
            }
        }

        error?.let { PaperError(it) }
    }
}

/** Статья справочника: имена, роль книжным шрифтом, связи, записи по главам. */
@Composable
private fun EntryPage(e: GuideEntry, guide: ru.zf.slushalka.ask.Guide, upTo: Int, showAll: Boolean, onOpen: (GuideEntry) -> Unit) {
    val visibleNotes = e.notes.filter { it.chapter <= upTo }
    val visibleLinks = e.links.filter { it.chapter <= upTo }
    if (e.aliases.isNotEmpty()) {
        PaperNote(e.aliases.joinToString(", "))
        Spacer(Modifier.height(8.dp))
    }
    Text(e.role, style = bookBody())
    if (visibleLinks.isNotEmpty()) {
        // Связи - как в семейном древе на форзаце: кто кому кем приходится.
        // Тап по имени - статья того героя.
        PaperLabel("Связи")
        visibleLinks.forEach { l ->
            val other = guide.characters.firstOrNull { c ->
                c.names.any { it.equals(l.to, ignoreCase = true) }
            }?.visibleAt(upTo)
            Row(
                Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(10.dp))
                    .clickable(enabled = other != null) { other?.let(onOpen) }
                    .padding(vertical = 6.dp, horizontal = 2.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(l.kind, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.width(120.dp))
                Text(
                    l.to,
                    style = MaterialTheme.typography.bodyLarge,
                    fontWeight = if (other != null) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.weight(1f),
                )
                if (other != null) Icon(Glyphs.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
    visibleNotes.forEach { n ->
        PaperLabel("Глава ${n.chapter}")
        Text(n.text, style = MaterialTheme.typography.bodyLarge)
    }
    if (!showAll) {
        // Одна и та же строка у всех статей: сказать «дальше о нём ещё три
        // записи» - значит выдать, что герой ещё сыграет.
        PaperNote("Записи о следующих главах откроются по мере чтения.", Modifier.padding(top = 14.dp))
    }
}

/**
 * Что именно спросить про статью: свой вопрос сверху, ниже - три вопроса,
 * которые модель придумала по этой статье (урезанной до дочитанных глав);
 * пока думает или не вышло - готовые по виду статьи.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.AskPage(
    app: SlushalkaApp,
    b: ru.zf.slushalka.library.Book,
    e: GuideEntry,
    kind: Int,
    upTo: Int,
    readChapters: Int,
    suggestCache: HashMap<String, List<String>>,
    go: (String) -> Unit,
) {
    var own by remember(e) { mutableStateOf("") }
    val key = "${e.name}|$upTo"
    var suggested by remember(e, upTo) { mutableStateOf(suggestCache[key]) }
    var thinking by remember(e, upTo) { mutableStateOf(false) }
    LaunchedEffect(e, upTo) {
        if (suggested != null) return@LaunchedEffect
        thinking = true
        app.ask.suggest(b, e.visibleAt(upTo) ?: e, kind, readChapters)
            .onSuccess { list -> if (list.isNotEmpty()) { suggestCache[key] = list; suggested = list } }
        thinking = false
    }
    Column(Modifier.weight(1f).verticalScroll(rememberScrollState())) {
        PaperField(value = own, onValue = { own = it }, placeholder = "Свой вопрос", maxLines = 4) {
            val can = own.isNotBlank()
            Box(
                Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(if (can) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.surfaceContainerHighest)
                    .clickable(enabled = can) { go(own) },
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    Icons.AutoMirrored.Filled.Send,
                    contentDescription = "Спросить",
                    tint = if (can) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(20.dp),
                )
            }
        }
        val list = suggested
        when {
            list != null -> {
                PaperLabel("Или один из этих")
                list.forEach { q ->
                    PaperCard(onClick = { go(q) }) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(q, style = MaterialTheme.typography.bodyLarge, modifier = Modifier.weight(1f))
                            Icon(Glyphs.ChevronRight, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                }
            }
            thinking -> PaperBusy("Придумываю вопросы по статье…")
            else -> {
                PaperLabel("Готовые")
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Prompts.guidePresets(kind).forEach { q -> PaperChip(q, selected = false) { go(q) } }
                }
            }
        }
        PaperNote("Вопрос уйдёт с текстом книги до этого места, без спойлеров.", Modifier.padding(top = 10.dp))
    }
}

private fun stamp(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("d.MM HH:mm", Locale("ru")).format(Date(ms))
