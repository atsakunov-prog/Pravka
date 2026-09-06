package ru.zf.slushalka.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
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
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun GuideSheet(
    app: SlushalkaApp,
    /** Где читатель: по этому месту считается «текущая глава». */
    cutoffChar: Int,
    /** С чего начать поиск - имя героя из абзаца, например. */
    initialQuery: String = "",
    onAsk: (question: String) -> Unit,
    onClose: () -> Unit,
) {
    val book by app.state.current.collectAsState()
    val text by app.state.text.collectAsState()
    val prefs by app.state.prefs.collectAsState()
    val states by app.guide.states.collectAsState()
    val coroutine = rememberCoroutineScope()
    val sheet = rememberModalBottomSheetState(skipPartiallyExpanded = true)

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

    ModalBottomSheet(onDismissRequest = onClose, sheetState = sheet) {
        // Во весь рост - только когда есть что листать; предложение заказать
        // справочник во весь экран смотрелось бы пустым.
        val tall = st?.status == GuideState.Status.READY
        Column(
            Modifier
                .fillMaxWidth()
                .then(if (tall) Modifier.fillMaxHeight(0.94f) else Modifier)
                .padding(horizontal = 20.dp)
                .padding(bottom = 16.dp),
        ) {
            Text("Справочник", style = MaterialTheme.typography.titleLarge)
            if (b == null || t == null) {
                Text("Текст книги ещё разбирается…", color = MaterialTheme.colorScheme.onSurfaceVariant)
                return@Column
            }
            Text(
                "«${b.title}» · дочитано глав: $readChapters из ${t.chapters.size}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            Spacer(Modifier.height(10.dp))

            when (st?.status) {
                null -> {
                    val est = remember(t, prefs.guideModel) { app.guide.estimate(t, prefs.guideModel) }
                    Text(
                        "Справочника по этой книге ещё нет. ${Settings.modelLabel(prefs.guideModel)} прочтёт её " +
                            "целиком - ${est.pages} стр." + (if (est.parts > 1) " в ${est.parts} частях" else "") +
                            " - и составит краткое содержание каждой главы и статьи о героях, местах и словах, " +
                            "каждую с привязкой к главе. Ты увидишь только то, что уже дочитал.",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Считается пакетным запросом: вдвое дешевле обычного, готово обычно в течение часа, " +
                            "самое позднее к завтрашнему дню. Приложение можно закрыть - справочник заберётся " +
                            "при следующем открытии книги и лягет файлом в её папку: кто читает ту же книгу с " +
                            "той же папки, получит его даром. Модель меняется в настройках, раздел «Модели».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(14.dp))
                    Button(
                        enabled = !busy,
                        onClick = {
                            busy = true
                            error = null
                            coroutine.launch {
                                app.guide.start(b, t).onFailure { error = it.message ?: "Не вышло заказать" }
                                busy = false
                            }
                        },
                        modifier = Modifier.fillMaxWidth(),
                    ) { Text(if (busy) "Отправляю книгу…" else "Составить справочник · ≈ %.2f $".format(est.usd)) }
                }

                GuideState.Status.PENDING -> {
                    Text("Готовится…", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Заказан ${stamp(st.createdAt)}, проверено ${stamp(st.checkedAt)}. " +
                            "Обычно час; лист проверяет сам, пока открыт.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(8.dp))
                    LinearProgressIndicator(Modifier.fillMaxWidth())
                    Spacer(Modifier.height(8.dp))
                    Row {
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                coroutine.launch {
                                    app.guide.refresh(b, t).onFailure { error = it.message }
                                    busy = false
                                }
                            },
                        ) { Text("Проверить сейчас") }
                        Spacer(Modifier.weight(1f))
                        TextButton(onClick = { app.guide.forget(b) }) { Text("Забыть заказ") }
                    }
                }

                GuideState.Status.FAILED -> {
                    Text("Не вышло", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
                    Text(
                        "Пакет посчитался, но справочник из ответа не собрался. Что именно случилось:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Spacer(Modifier.height(6.dp))
                    Column(Modifier.heightIn(max = 220.dp).verticalScroll(rememberScrollState())) {
                        Text(st.error.ifBlank { "Причина неизвестна" }, style = MaterialTheme.typography.bodySmall)
                    }
                    if (st.costUsd > 0) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Списано %.2f $ · ${Settings.modelLabel(st.model)} · ${stamp(st.createdAt)}".format(st.costUsd),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Spacer(Modifier.height(10.dp))
                    Button(onClick = { app.guide.forget(b) }) { Text("Заказать заново") }
                }

                GuideState.Status.READY -> {
                    val guide = st.guide ?: return@Column
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            if (showAll) "Вся книга - со спойлерами"
                            else if (readChapters == 0) "Первая глава ещё не дочитана - пока пусто"
                            else "Главы 1–$readChapters, без спойлеров",
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.weight(1f),
                        )
                        Switch(checked = showAll, onCheckedChange = { showAll = it })
                    }
                    OutlinedTextField(
                        value = query,
                        onValueChange = { query = it },
                        placeholder = { Text("Имя, место, слово") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    val chapters = guide.chapters.filter { it.chapter <= upTo && it.matches(query) }
                    val lists = listOf(guide.characters, guide.places, guide.terms)
                    val visible = lists.map { list -> list.mapNotNull { it.visibleAt(upTo) }.filter { it.matches(query) } }
                    val labels = listOf("Главы · ${chapters.size}", "Герои · ${visible[0].size}", "Места · ${visible[1].size}", "Словарь · ${visible[2].size}")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        labels.forEachIndexed { i, label ->
                            FilterChip(selected = tab == i, onClick = { tab = i }, label = { Text(label) })
                        }
                    }
                    Spacer(Modifier.height(4.dp))
                    val empty = if (tab == 0) chapters.isEmpty() else visible[tab - 1].isEmpty()
                    if (empty) {
                        Text(
                            when {
                                query.isNotBlank() -> "Ничего похожего в дочитанных главах."
                                readChapters == 0 -> "Откроется, когда дочитаешь первую главу."
                                else -> "В дочитанных главах здесь пока пусто."
                            },
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = 12.dp),
                        )
                    }
                    LazyColumn(Modifier.weight(1f)) {
                        if (tab == 0) {
                            items(chapters) { ch ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { openChapter = ch }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Text(
                                        "Глава ${ch.chapter}" + (if (ch.title.isNotBlank()) ". ${ch.title}" else ""),
                                        style = MaterialTheme.typography.bodyLarge,
                                        fontWeight = FontWeight.SemiBold,
                                    )
                                    Spacer(Modifier.height(4.dp))
                                    Text(ch.summary, style = MaterialTheme.typography.bodyMedium)
                                }
                                HorizontalDivider()
                            }
                        } else {
                            items(visible[tab - 1]) { e ->
                                Column(
                                    Modifier
                                        .fillMaxWidth()
                                        .clickable { open = e }
                                        .padding(vertical = 8.dp),
                                ) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(e.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                                        Spacer(Modifier.weight(1f))
                                        Text(
                                            "гл. ${e.chapter}",
                                            style = MaterialTheme.typography.labelSmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                        )
                                    }
                                    if (e.aliases.isNotEmpty()) {
                                        Text(
                                            e.aliases.joinToString(", "),
                                            style = MaterialTheme.typography.bodySmall,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            maxLines = 1,
                                            overflow = TextOverflow.Ellipsis,
                                        )
                                    }
                                    Text(
                                        e.role,
                                        style = MaterialTheme.typography.bodyMedium,
                                        maxLines = 2,
                                        overflow = TextOverflow.Ellipsis,
                                    )
                                }
                                HorizontalDivider()
                            }
                        }
                    }
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "${Settings.modelLabel(st.model)} · ${stamp(st.createdAt)}" +
                                (if (st.by.isNotBlank()) " · ${st.by}" else "") +
                                (if (st.costUsd > 0) " · %.2f $".format(st.costUsd) else "") +
                                (if (st.error.isNotBlank()) " · с оговорками" else ""),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(onClick = { app.guide.forget(b) }) { Text("Пересобрать") }
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

            error?.let {
                Spacer(Modifier.height(8.dp))
                Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
    }

    open?.let { e ->
        val visibleNotes = e.notes.filter { it.chapter <= upTo }
        AlertDialog(
            onDismissRequest = { open = null },
            title = { Text(e.name) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    if (e.aliases.isNotEmpty()) {
                        Text(
                            e.aliases.joinToString(", "),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Spacer(Modifier.height(6.dp))
                    }
                    Text(e.role, style = MaterialTheme.typography.bodyMedium)
                    visibleNotes.forEach { n ->
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Глава ${n.chapter}",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.primary,
                        )
                        Text(n.text, style = MaterialTheme.typography.bodyMedium)
                    }
                    if (!showAll) {
                        // Одна и та же строка у всех статей: сказать «дальше о нём ещё
                        // три записи» - значит выдать, что герой ещё сыграет.
                        Spacer(Modifier.height(10.dp))
                        Text(
                            "Записи о следующих главах откроются по мере чтения.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { open = null }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(onClick = { askFor = e to (st?.guide?.kindOf(e) ?: 0); open = null }) { Text("Спросить…") }
            },
        )
    }

    // Глава: содержание целиком и «Спросить…» - как у героя. Глава для вопроса
    // притворяется статьёй: имя - номер и название, роль - содержание.
    openChapter?.let { ch ->
        AlertDialog(
            onDismissRequest = { openChapter = null },
            title = { Text("Глава ${ch.chapter}" + (if (ch.title.isNotBlank()) ". ${ch.title}" else "")) },
            text = {
                Column(Modifier.heightIn(max = 440.dp).verticalScroll(rememberScrollState())) {
                    Text(ch.summary, style = MaterialTheme.typography.bodyMedium)
                }
            },
            confirmButton = { TextButton(onClick = { openChapter = null }) { Text("Закрыть") } },
            dismissButton = {
                TextButton(onClick = {
                    askFor = GuideEntry(
                        name = "Глава ${ch.chapter}" + (if (ch.title.isNotBlank()) " («${ch.title}»)" else ""),
                        aliases = emptyList(),
                        chapter = ch.chapter,
                        role = ch.summary,
                        notes = emptyList(),
                    ) to 3
                    openChapter = null
                }) { Text("Спросить…") }
            },
        )
    }

    // Что именно спросить про статью: свой вопрос сверху, ниже - три вопроса,
    // которые модель придумала по этой статье (урезанной до дочитанных глав);
    // пока думает или не вышло - готовые по виду статьи.
    askFor?.let { (e, kind) ->
        var own by remember(e) { mutableStateOf("") }
        val key = "${e.name}|$upTo"
        var suggested by remember(e, upTo) { mutableStateOf(suggestCache[key]) }
        var thinking by remember(e, upTo) { mutableStateOf(false) }
        LaunchedEffect(e, upTo) {
            if (suggested != null || b == null) return@LaunchedEffect
            thinking = true
            app.ask.suggest(b, e.visibleAt(upTo) ?: e, kind, readChapters)
                .onSuccess { list -> if (list.isNotEmpty()) { suggestCache[key] = list; suggested = list } }
            thinking = false
        }
        fun go(q: String) {
            askFor = null
            onAsk(Prompts.aboutEntry(e.name, e.aliases, q))
        }
        AlertDialog(
            onDismissRequest = { askFor = null },
            title = { Text("Спросить про «${e.name}»") },
            text = {
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    OutlinedTextField(
                        value = own,
                        onValueChange = { own = it },
                        placeholder = { Text("Свой вопрос") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 1,
                        maxLines = 4,
                    )
                    Spacer(Modifier.height(10.dp))
                    val list = suggested
                    when {
                        list != null -> {
                            Text(
                                "Или один из этих:",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            list.forEach { q ->
                                TextButton(onClick = { go(q) }, modifier = Modifier.fillMaxWidth()) {
                                    Text(q, modifier = Modifier.fillMaxWidth())
                                }
                            }
                        }
                        thinking -> {
                            Text(
                                "Придумываю вопросы по статье…",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                            Spacer(Modifier.height(6.dp))
                            LinearProgressIndicator(Modifier.fillMaxWidth())
                        }
                        else -> FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                            Prompts.guidePresets(kind).forEach { q ->
                                AssistChip(onClick = { go(q) }, label = { Text(q) })
                            }
                        }
                    }
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Вопрос уйдёт с текстом книги до этого места, без спойлеров.",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            },
            confirmButton = {
                TextButton(enabled = own.isNotBlank(), onClick = { go(own) }) { Text("Спросить") }
            },
            dismissButton = { TextButton(onClick = { askFor = null }) { Text("Отмена") } },
        )
    }
}

private fun stamp(ms: Long): String =
    if (ms <= 0) "—" else SimpleDateFormat("d.MM HH:mm", Locale("ru")).format(Date(ms))
