package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.io.File
import java.time.LocalDate
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import ru.zf.pravka.core.Dictations
import ru.zf.pravka.core.Fmt
import ru.zf.pravka.data.DataRoot
import ru.zf.pravka.data.DictationHistory
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.PcDictations
import ru.zf.pravka.data.TranscriptionLog
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GhostKey
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.Hairline
import ru.zf.pravka.ui.Kv
import ru.zf.pravka.ui.KvGlyphs
import ru.zf.pravka.ui.KvShape
import ru.zf.pravka.ui.LocalPravkaType
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.SayBar
import ru.zf.pravka.ui.SayIcon
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.Segmented
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.ui.twoPane

// Экран «Правка» Правки 5.0 (этап 5, план §4; макет «Правка» холста «Ковчег на телефоне», 13.10.2026):
// история всех диктовок — с телефона (сам, гарнитура) и с компьютера («Диктовка» на ПК), свежие
// сверху. Строка — время, откуда, длительность, текст после чистки, цена; фильтр «Все · Телефон ·
// Компьютер»; строка внизу — поиск по сказанному и записавшемуся. Разложенный — список слева и
// выбранная диктовка целиком справа: «Сказал» и «Записалось», чем распознано, чем и за сколько
// почищено, и действия — только те, что в приложении уже есть: «Скопировать», «Разобрать заново»
// (телефонная, пока её звук лежит, — `replayTake`), «В словарь» (окно добавления словаря).
// «Слушать» не сделано: проигрывателя тейков в приложении нет, а выдумывать его здесь — не задача.
//
// Что было во вкладке 4.0 и нужно по-прежнему, здесь тоже: нерасшифрованные записи, черновик после
// сбоя и «причесать текст» — значком в строке: текст из буфера уходит в чистку, как с поля вкладки
// (свежий итог — плашкой сверху, результат — в буфере). Вид 4.0 открывает прежнюю вкладку (`PravkaTab`).

/** Сколько диктовок рисовать сразу: дальше — «Показать ещё». */
private const val PAGE = 150

@Composable
internal fun PravkaHistoryScreen(app: PravkaApp, serviceEnabled: Boolean, header: @Composable (subtitle: String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val wide = twoPane()
    var items by remember { mutableStateOf<List<Dictations.Item>?>(null) }
    var reload by remember { mutableIntStateOf(0) }
    val pcRev by app.pcDictations.rev.collectAsState()
    var filter by rememberSaveable { mutableStateOf(Dictations.Filter.ALL) }
    var query by rememberSaveable { mutableStateOf("") }
    var selected by rememberSaveable { mutableStateOf<String?>(null) }
    var shown by rememberSaveable { mutableIntStateOf(PAGE) }
    var sheet by remember { mutableStateOf<Dictations.Item?>(null) }
    var withAudio by remember { mutableStateOf<Set<String>>(emptySet()) }
    var draft by remember { mutableStateOf<String?>(null) }
    // Нерасшифрованные записи — своей плашкой, только если они есть: пустой элемент ленты дал бы лишний зазор.
    var hasRecordings by remember { mutableStateOf(false) }
    var replaying by remember { mutableStateOf<String?>(null) }
    var replayShare by remember { mutableFloatStateOf(0f) }
    var replayOut by remember { mutableStateOf<ReplayOut?>(null) }
    var dictAdd by remember { mutableStateOf(false) }
    val link by app.archiveSync.link.collectAsState()
    val pulls by app.archiveSync.pullState.collectAsState()
    val cleanResult by CleanBoxState.result

    // Журналы — диск: читаем на IO и перечитываем, когда что-то поменялось.
    LaunchedEffect(reload, pcRev) {
        val (list, audio, d) = withContext(Dispatchers.IO) {
            val l = DictationHistory.load(app, app.pcDictations)
            val a = l.mapNotNull { it.audio }.filterTo(HashSet()) { app.takeAudio.file(it) != null }
            hasRecordings = runCatching { app.recordings.list().isNotEmpty() }.getOrDefault(false)
            Triple(l, a, app.liveDraft.read())
        }
        items = list
        withAudio = audio
        draft = d
    }
    // Диктовки компа — с сервера при открытии (не чаще раза в минуту; тик службы — раз в пять).
    LaunchedEffect(Unit) { runCatching { app.archiveSync.pull(PcDictations.PULL) } }
    // Новая диктовка телефона — строка в журнале: заметить без подписки (крючок журнала занят архивом).
    LaunchedEffect(Unit) {
        val dir = DataRoot.dir(app)
        fun sizes() = File(dir, TranscriptionLog.FILE_NAME).length() to File(dir, HistoryLog.FILE_NAME).length()
        var last = withContext(Dispatchers.IO) { sizes() }
        while (true) {
            delay(10_000)
            val now = withContext(Dispatchers.IO) { sizes() }
            if (now != last) { last = now; reload++ }
        }
    }
    LaunchedEffect(cleanResult) { if (cleanResult != null) { delay(1_500); reload++ } }

    val all = items.orEmpty()
    val byFilter = remember(all, filter) { Dictations.view(all, filter) }
    val view = remember(byFilter, query) { Dictations.view(byFilter, Dictations.Filter.ALL, query) }
    val today = LocalDate.now()
    val st = remember(byFilter, today) { Dictations.stats(byFilter, today) }
    // Справа на развороте — выбранная, а пока не выбрали — свежая распознанная (не «не распознано»).
    val pick = view.firstOrNull { it.key == selected } ?: view.firstOrNull { it.ok } ?: view.firstOrNull()
    val pcState = pulls[PcDictations.PULL]

    fun copy(text: String) {
        putClipboard(context, text)
        Feedback.toast(context, context.getString(R.string.transcript_copied))
    }

    fun replay(item: Dictations.Item) {
        val name = item.audio ?: return
        if (replaying != null) return
        replaying = name
        replayShare = 0f
        replayTake(
            app, context, name, item.said,
            onShare = { replayShare = it },
            onGone = { replaying = null; withAudio = withAudio - name },
        ) { out ->
            replaying = null
            replayOut = out
            reload++
        }
    }

    val actions = DetailActions(
        copy = { i -> copy(i.wrote?.takeIf { it.isNotBlank() } ?: i.said) },
        replay = { i -> replay(i) },
        dict = { dictAdd = true },
        canReplay = { i -> i.origin != Dictations.Origin.PC && i.audio != null && i.audio in withAudio },
        replayShare = { i -> if (i.audio != null && i.audio == replaying) replayShare else null },
    )

    header(
        if (wide) statsLine(st, filter)
        else "телефон и компьютер"
    )
    Box(Modifier.fillMaxSize()) {
        val head: LazyListScope.(Boolean) -> Unit = { withKpi ->
            item(key = "seg") {
                Segmented(
                    Dictations.Filter.entries.map { it.label },
                    selected = filter.ordinal,
                    onSelect = { filter = Dictations.Filter.entries[it]; shown = PAGE },
                )
            }
            if (withKpi) item(key = "kpi") { Kpis(st) }
            if (CleanBoxState.busy.value || CleanBoxState.result.value != null || CleanBoxState.error.value != null) {
                item(key = "fresh") { LastCard(app, freshOnly = true) }
            }
            if (hasRecordings) item(key = "rec") { RecordingsSection(app.recordings, serviceEnabled) }
            draft?.let { d -> item(key = "draft") { DraftCard(d, onCopy = { copy(d) }, onDelete = { app.liveDraft.clear(); draft = null }) } }
            if (items != null && view.isEmpty()) {
                item(key = "empty") {
                    ru.zf.pravka.ui.EmptyState(emptyText(filter, query, link != null, pcState), icon = Glyphs.Wave)
                }
            }
        }
        val days: LazyListScope.(Boolean) -> Unit = { selectable ->
            for ((day, list) in Dictations.byDay(view.take(shown))) {
                item(key = "d:$day") {
                    PaperCard(label = dayTitle(day, today)) {
                        list.forEachIndexed { k, it ->
                            if (k > 0) Hairline()
                            DictationRow(
                                it,
                                selected = selectable && it.key == pick?.key,
                                twoLines = selectable,
                                onClick = { if (selectable) selected = it.key else sheet = it },
                            )
                        }
                    }
                }
            }
            if (view.size > shown) {
                item(key = "more") {
                    Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                        GhostKey("Показать ещё", onClick = { shown += PAGE }, icon = Glyphs.ChevronDown)
                    }
                }
            }
        }
        if (wide) {
            val left = rememberLazyListState()
            Row(Modifier.fillMaxSize().padding(horizontal = 8.dp)) {
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().bottomFade().scrollFade(left),
                    state = left,
                    contentPadding = PaddingValues(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
                ) {
                    head(false)
                    days(true)
                }
                Column(
                    Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState())
                        .padding(start = 8.dp, end = 8.dp, top = 4.dp, bottom = 110.dp),
                ) {
                    if (pick != null) PaperCard { DictationDetail(pick, actions) }
                }
            }
        } else {
            val list = rememberLazyListState()
            LazyColumn(
                modifier = Modifier.fillMaxSize().bottomFade().scrollFade(list),
                state = list,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 4.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
            ) {
                head(true)
                days(false)
            }
        }
        SearchBar(
            query = query,
            onQuery = { query = it; shown = PAGE },
            wide = wide,
            onCleanClipboard = {
                val clip = clipboardText(context)
                if (clip.isBlank()) Feedback.toast(context, "Буфер обмена пуст — нечего причёсывать")
                else { CleanBoxState.text.value = clip; startClean(app, context) }
            },
            busy = CleanBoxState.busy.value,
            modifier = Modifier.align(Alignment.BottomCenter),
        )
    }

    sheet?.let { s ->
        PaperSheet(
            onDismiss = { sheet = null },
            title = "Диктовка",
            icon = originIcon(s.origin),
            subtitle = whenLine(s, today),
        ) {
            DictationDetail(s, actions, inSheet = true)
        }
    }
    replayOut?.let { out ->
        ReplaySheet(out, Locale.forLanguageTag("ru"), onCopy = { copy(out.now) }, onDismiss = { replayOut = null })
    }
    if (dictAdd) {
        DictEntryDialog(
            entry = null,
            onDismiss = { dictAdd = false },
            onSave = { from, to, mode, note, _ ->
                scope.launch { app.dictionaryStore.add(from, to, mode, note) }
                dictAdd = false
                Feedback.toast(context, "В словаре: $from")
            },
            onDelete = null,
        )
    }
}

/** Действия над диктовкой — одни на лист сложенного и панель разложенного. */
private class DetailActions(
    val copy: (Dictations.Item) -> Unit,
    val replay: (Dictations.Item) -> Unit,
    val dict: () -> Unit,
    val canReplay: (Dictations.Item) -> Boolean,
    val replayShare: (Dictations.Item) -> Float?,
)

/** «сегодня 30 диктовок · 25,6 мин голоса · 3 не распознаны» — подпись шапки разложенного. */
private fun statsLine(st: Dictations.DayStats, filter: Dictations.Filter): String {
    if (st.count == 0) return when (filter) {
        Dictations.Filter.PC -> "сегодня с компьютера не диктовал"
        else -> "сегодня диктовок нет"
    }
    val min = String.format(Locale.forLanguageTag("ru"), "%.1f", st.audioMs / 60_000.0)
    return buildString {
        append("сегодня ").append(plural(st.count, "диктовка", "диктовки", "диктовок"))
        append(" · ").append(min).append(" мин голоса")
        if (st.missed > 0) append(" · ").append(st.missed).append(" не распознан").append(if (st.missed == 1) "а" else "ы")
    }
}

private fun dayTitle(day: LocalDate, today: LocalDate): String = when (day) {
    today -> "Сегодня"
    today.minusDays(1) -> "Вчера"
    else -> Fmt.dayList(day).replaceFirstChar { it.uppercase() }
}

/** «Сегодня 12:44 · 3 мин 55 с» — когда и сколько. */
private fun whenLine(i: Dictations.Item, today: LocalDate): String {
    val day = Dictations.dayOf(i.atMs)
    return dayTitle(day, today) + " " + Fmt.hm(i.atMs) + " · " + Dictations.duration(i.audioMs)
}

/** Откуда — значком: телефон, гарнитура, компьютер (набор Ковчега). */
private fun originIcon(o: Dictations.Origin): ImageVector = when (o) {
    Dictations.Origin.PHONE -> Glyphs.Phone
    Dictations.Origin.HEADSET -> KvGlyphs.byKey("headphones") ?: Glyphs.Phone
    Dictations.Origin.PC -> KvGlyphs.byKey("monitor") ?: Glyphs.Phone
}

/** «телефон · Shokz» / «телефон» / «компьютер». */
private fun originLabel(i: Dictations.Item): String = when (i.origin) {
    Dictations.Origin.PHONE -> "телефон"
    Dictations.Origin.HEADSET -> "телефон · ${i.device}"
    Dictations.Origin.PC -> "компьютер"
}

/** Подпись строки: «Shokz · 15 с · Сонет $0,02», у компа ещё «в буфере» и «отменено». */
private fun metaLine(i: Dictations.Item): String = buildList {
    add(if (i.origin == Dictations.Origin.HEADSET) i.device else if (i.origin == Dictations.Origin.PC) "компьютер" else "телефон")
    if (i.audioMs > 0) add(Dictations.duration(i.audioMs))
    if (!i.ok) {
        add(i.error ?: "не распознано")
    } else {
        i.cleanModel?.let { m ->
            add(listOfNotNull(ru.zf.pravka.data.Models.label(m).substringBefore(' '), Dictations.usd(i.cleanUsd)).joinToString(" "))
        }
        when (i.status) {
            "clipboard" -> add("в буфере")
            "cancelled" -> add("отменено")
        }
    }
}.joinToString(" · ")

private fun emptyText(filter: Dictations.Filter, query: String, linked: Boolean, pc: ru.zf.pravka.data.ArchiveSync.PullState?): String = when {
    query.isNotBlank() -> "Ничего не нашлось по «${query.trim()}»"
    filter == Dictations.Filter.PC && !linked ->
        "Диктовки компьютера едут через архив на компе, а он не подключён: Настройки → Подключения → Архив"
    filter == Dictations.Filter.PC && pc?.waiting == true ->
        "Архив на компе ещё не отдаёт диктовки компьютера — ждёт обновления сервера"
    filter == Dictations.Filter.PC && !pc?.error.isNullOrBlank() -> "Диктовки компьютера не забрались: ${pc?.error}"
    filter == Dictations.Filter.PC -> "С компьютера диктовок пока нет"
    else -> "Диктовок пока нет — скажи что-нибудь кнопкой «П»"
}

private fun clipboardText(context: android.content.Context): String {
    val cm = context.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
    return cm.primaryClip?.getItemAt(0)?.coerceToText(context)?.toString().orEmpty()
}

/** Итог дня тремя числами (`.kpis` макета): диктовок, минут голоса, не распознано. */
@Composable
private fun Kpis(st: Dictations.DayStats) {
    val t = LocalPravkaType.current
    PaperCard {
        Row(Modifier.fillMaxWidth()) {
            val min = String.format(Locale.forLanguageTag("ru"), "%.1f", st.audioMs / 60_000.0)
            for ((n, unit, label) in listOf(
                Triple(st.count.toString(), "", plural(st.count, "диктовка", "диктовки", "диктовок").substringAfter(' ') + " сегодня"),
                Triple(min, "мин", "голоса"),
                Triple(st.missed.toString(), "", if (st.missed % 10 == 1 && st.missed % 100 != 11) "не распознана" else "не распознаны"),
            )) {
                Column(Modifier.weight(1f)) {
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(n, style = t.valueL.copy(fontSize = 22.sp, lineHeight = 26.sp), color = Kv.text, maxLines = 1)
                        if (unit.isNotEmpty()) {
                            Text(unit, style = t.label.copy(fontSize = 13.sp), color = Kv.faint, modifier = Modifier.padding(start = 3.dp, bottom = 3.dp))
                        }
                    }
                    Text(label, style = t.meta, color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }
}

/** Строка списка (`.li` макета): время, текст, значок «откуда» и подпись; выбранная — мягким акцентом. */
@Composable
private fun DictationRow(item: Dictations.Item, selected: Boolean, twoLines: Boolean, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(7.dp))
            .background(if (selected) Kv.accentSoft else Color.Transparent)
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 9.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Text(
            Fmt.hm(item.atMs),
            style = t.meta.copy(fontSize = 12.sp),
            color = Kv.faint,
            modifier = Modifier.width(42.dp).padding(top = 2.dp),
        )
        Column(Modifier.weight(1f)) {
            Text(
                if (item.ok) item.text else "не распознано",
                style = t.body.copy(fontSize = if (twoLines) 13.5.sp else 14.sp, lineHeight = if (twoLines) 18.sp else 19.sp),
                color = if (item.ok) Kv.text else Kv.warn,
                maxLines = if (twoLines) 2 else 3,
                overflow = TextOverflow.Ellipsis,
            )
            Row(Modifier.padding(top = 2.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(originIcon(item.origin), null, tint = Kv.faint, modifier = Modifier.size(14.dp))
                Spacer(Modifier.width(5.dp))
                Text(metaLine(item), style = t.meta.copy(fontSize = 12.5.sp), color = Kv.faint, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/**
 * Диктовка целиком: когда и откуда, «Сказал» и «Записалось», чем распознано и чем почищено,
 * действия. В разложенном — панель справа, в сложенном — лист по тапу на строке.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ColumnScope.DictationDetail(item: Dictations.Item, actions: DetailActions, inSheet: Boolean = false) {
    val t = LocalPravkaType.current
    val today = LocalDate.now()
    if (!inSheet) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(whenLine(item, today), style = t.overline, color = Kv.faint, modifier = Modifier.weight(1f), maxLines = 1)
            Text(originLabel(item), style = t.meta.copy(fontSize = 12.5.sp), color = Kv.faint, maxLines = 1)
        }
    } else {
        Text(originLabel(item), style = t.meta.copy(fontSize = 12.5.sp), color = Kv.faint)
    }
    Eyebrow("Сказал")
    SelectionContainer {
        Text(
            item.said.ifBlank { "распознаватель не разобрал ни слова" },
            style = t.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = if (item.said.isBlank()) Kv.warn else Kv.muted,
        )
    }
    Eyebrow("Записалось")
    val wrote = item.wrote
    SelectionContainer {
        Text(
            when {
                !wrote.isNullOrBlank() -> wrote
                !item.ok -> "ничего — диктовка не распознана"
                item.cleanError != null -> "как сказано — чистка не вышла"
                item.origin == Dictations.Origin.PC -> "как сказано — без Сонета"
                else -> "без чистки — диктовка ушла не в поле (Засечка, еда, дела) или чистка не нашлась"
            },
            style = t.body.copy(fontSize = 14.sp, lineHeight = 21.sp),
            color = if (!wrote.isNullOrBlank()) Kv.text else Kv.faint,
        )
    }
    Spacer(Modifier.height(10.dp))
    SetRow("Распознал", Dictations.engineLabel(item.engine))
    SetRow("Почистил", cleanLine(item))
    Dictations.statusLabel(item.status)?.let { SetRow("Итог", it) }
    if (!item.ok && !item.error.isNullOrBlank() && item.error != Dictations.statusLabel(item.status)) SetRow("Почему", item.error)
    Spacer(Modifier.height(10.dp))
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        if (item.said.isNotBlank() || !item.wrote.isNullOrBlank()) {
            GhostKey("Скопировать", onClick = { actions.copy(item) }, icon = Glyphs.Copy)
        }
        val share = actions.replayShare(item)
        if (share != null) {
            GhostKey("Разбираю… ${(share * 100).toInt()} %", onClick = {}, icon = Glyphs.Wave, enabled = false)
        } else if (actions.canReplay(item)) {
            GhostKey("Разобрать заново", onClick = { actions.replay(item) }, icon = Glyphs.Wave)
        }
        SoftKey("В словарь", Glyphs.Dictionary, onClick = actions.dict)
    }
}

/** «Сонет 5.5 · $0,011 · 2,1 с» или почему не чистилось. */
private fun cleanLine(i: Dictations.Item): String {
    val model = i.cleanModel ?: return when {
        i.cleanError != null -> "не вышло: ${i.cleanError}"
        i.origin == Dictations.Origin.PC && i.ok -> "Сонет выключен — только словарь"
        else -> "не чистилось"
    }
    return listOfNotNull(
        ru.zf.pravka.data.Models.label(model),
        Dictations.usd(i.cleanUsd),
        i.cleanMs.takeIf { it > 0 }?.let { String.format(Locale.forLanguageTag("ru"), "%.1f с", it / 1000.0) },
        i.cleanError?.let { "ошибка: $it" },
    ).joinToString(" · ")
}

@Composable
private fun Eyebrow(text: String) {
    Text(
        text,
        style = LocalPravkaType.current.overline,
        color = Kv.faint,
        modifier = Modifier.padding(top = 12.dp, bottom = 4.dp),
    )
}

/** Строка «название — значение» (`.set` макета). */
@Composable
private fun SetRow(title: String, value: String) {
    val t = LocalPravkaType.current
    Column {
        Hairline()
        Row(Modifier.fillMaxWidth().padding(vertical = 9.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, style = t.body.copy(fontSize = 14.sp), color = Kv.text)
            Spacer(Modifier.width(12.dp))
            Text(
                value,
                style = t.meta.copy(fontSize = 13.5.sp),
                color = Kv.muted,
                modifier = Modifier.weight(1f),
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
            )
        }
    }
}

/** Мягкая кнопка (`.btn.soft` макета): фон мягкого акцента, слово акцентом. */
@Composable
private fun SoftKey(text: String, icon: ImageVector, onClick: () -> Unit) {
    val t = LocalPravkaType.current
    val shape = RoundedCornerShape(KvShape.Control)
    Row(
        Modifier
            // Как у `GhostKey`: цель касания 48, видно 38 — в одном ряду кнопки встают вровень.
            .minimumInteractiveComponentSize()
            .defaultMinSize(minHeight = 38.dp)
            .clip(shape)
            .background(Kv.accentSoft)
            .clickable(onClick = onClick)
            .padding(horizontal = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = Kv.accent, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text(text, style = t.label.copy(fontSize = 14.sp), color = Kv.accent, maxLines = 1)
    }
}

/** Черновик после сбоя: диктовка оборвалась до вставки, а текст успел сохраниться. */
@Composable
private fun DraftCard(text: String, onCopy: () -> Unit, onDelete: () -> Unit) {
    val t = LocalPravkaType.current
    PaperCard(label = "Черновик после сбоя") {
        Text(text, style = t.body, color = Kv.text)
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            GhostKey("Скопировать", onClick = onCopy, icon = Glyphs.Copy)
            GhostKey("Удалить", onClick = onDelete, icon = Glyphs.Delete)
        }
    }
}

/**
 * Строка внизу (`.say` макета): поиск по диктовкам; справа — «причесать текст из буфера»
 * (вставил что-то чужое — тем же движком и режимом, что «П», итог в буфере), а набран запрос —
 * «очистить». Клавиша справа прячет клавиатуру: поиск идёт по мере набора.
 */
@Composable
private fun SearchBar(
    query: String,
    onQuery: (String) -> Unit,
    wide: Boolean,
    onCleanClipboard: () -> Unit,
    busy: Boolean,
    modifier: Modifier,
) {
    val focus = LocalFocusManager.current
    SayBar(
        value = query,
        onValueChange = onQuery,
        placeholder = if (wide) "Найти в диктовках: что сказал или что записалось…" else "Найти в диктовках…",
        onSend = { focus.clearFocus() },
        sendEnabled = query.isNotBlank(),
        maxLines = 1,
        busy = busy,
        busyLabel = "Причёсываю текст из буфера",
        sendIcon = Glyphs.Search,
        trailing = {
            if (query.isEmpty()) SayIcon(Glyphs.Paste, "причесать текст из буфера", onClick = onCleanClipboard)
            else SayIcon(Glyphs.Close, "очистить поиск", onClick = { onQuery("") })
        },
        modifier = modifier
            .navigationBarsPadding()
            .imePadding()
            .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
    )
}
