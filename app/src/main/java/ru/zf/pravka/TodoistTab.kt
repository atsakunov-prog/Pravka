package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.data.TodoistStore
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.VoiceInput
import ru.zf.pravka.trigger.onRaznoskaTap
import ru.zf.pravka.trigger.onRaznoskaText

// Вкладка «Дела»: список Todoist, тап по делу = оно становится текущим в
// ленте. Сверху - Разноска: наговорённые дела, которые ещё не уехали в
// Todoist, с правкой руками и кнопкой «Отправить» (см. RaznoskaSection).
//
// Группы: «Сегодня» (и всё просроченное - оно и есть сегодняшнее) раскрыта,
// «Без даты и без проекта» (входящие) и проекты свёрнуты. Поиск - простой
// фильтр по вхождению слова, он показывает плоский список поверх групп.
//
// Второе издание (24.09.2026): до него во вкладке не было ни одной плашки —
// дела и группы лежали прямо на фоне, и вкладка выглядела черновиком рядом с
// соседними. Теперь каждая группа — своя плашка со счётом в подписи и
// шевроном, сверху — строки состояния и поиск без плашки: это не раздел, а
// то, чем ищут по разделам.
@Composable
fun TodoistTab(app: PravkaApp) {
    val store = app.todoistStore
    val tasks by store.tasksFlow.collectAsState()
    val projects by store.projectsFlow.collectAsState()
    val status by store.statusFlow.collectAsState()
    val token by app.settings.todoistTokenFlow.collectAsState(initial = "")
    val ribbon by app.zasechkaStore.entriesFlow.collectAsState()

    var query by remember { mutableStateOf("") }
    // Поиск — значком в строке синка (26.09.2026, вечер: «сверху там не поиск по
    // делам, а наоборот, такая же плашка, где написано „говори дела“»).
    var searching by remember { mutableStateOf(false) }
    var draft by remember { mutableStateOf("") }
    val ownerName = app.profileStore.flow.collectAsState().value?.name
    val talking = ru.zf.pravka.ui.rememberRouteBusy(app.liveWork, "raznoska")
    var expanded by remember { mutableStateOf(setOf("today")) }
    var starting by remember { mutableStateOf("") }

    LaunchedEffect(token) {
        store.load()
        if (token.isNotBlank()) {
            app.todoistSync.refresh(force = false)
            // Закрытые дела могли остаться без коммента, если телефон спал -
            // открытие вкладки хороший момент дописать их.
            runCatching { app.todoistSync.flushLinks() }
        }
    }

    val running = ribbon.firstOrNull { it.open }
    val today = remember(tasks) { SimpleDateFormat("yyyy-MM-dd", Locale.US).format(Date()) }
    val projectName = remember(projects) { projects.associate { it.id to it.name } }
    val inboxIds = remember(projects) { projects.filter { it.inbox }.map { it.id }.toSet() }

    // Одно дело - в одну группу, порядок групп: сегодня, входящие, проекты.
    val todayTasks = tasks.filter { it.due.isNotBlank() && it.due <= today }
    val rest = tasks - todayTasks.toSet()
    val inboxTasks = rest.filter { it.projectId.isBlank() || it.projectId in inboxIds }
    val byProject = (rest - inboxTasks.toSet())
        .groupBy { it.projectId }
        .toList()
        .sortedBy { (id, _) -> projectName[id] ?: "я" }

    val onPick: (TodoistStore.Task) -> Unit = { task ->
        if (starting.isBlank()) {
            starting = task.id
            app.appScope.launch {
                val entry = runCatching { app.zasechkaEngine.startTask(task.content) }.getOrNull()
                if (entry != null) {
                    store.addLink(entry.id, task.id, entry.title, entry.start)
                    Feedback.toast(app, "⏱ ${entry.title}")
                } else {
                    Feedback.toast(app, "Не смог записать дело")
                }
                starting = ""
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = ScreenPad.Padding,
        verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
    ) {
        // ---- Пилюля наверху: сказать дела (версия 3, второй заход) ----
        // Та же пилюля, что выезжает у «Д»: кружок — голос (тот же тап, что по
        // кнопке), набранное — тот же разбор, что у голоса, и плашка с «ОК».
        item {
            VoiceInput(
                value = draft,
                onValueChange = { draft = it },
                placeholder = if (talking) "Разбираю…" else ru.zf.pravka.core.PillHint.say(ownerName, "говори дела"),
                onSend = {
                    val text = draft.trim()
                    val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                    else if (text.isNotEmpty()) {
                        draft = ""
                        service.onRaznoskaText(text)
                    }
                },
                onMic = {
                    val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                    else service.onRaznoskaTap()
                },
                sendEnabled = draft.isNotBlank(),
                maxLines = 4,
                busy = talking,
            )
        }
        item {
            // Название и пояснение живут в общей шапке (ui/Frame.kt); тут —
            // только то, что меняется: идущее дело, поиск, состояние синка.
            Column(Modifier.fillMaxWidth()) {
                if (running != null) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        modifier = Modifier.padding(start = 4.dp, bottom = 6.dp),
                    ) {
                        Icon(
                            Glyphs.Timer,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(16.dp),
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            "Сейчас идёт: ${running.title.ifBlank { "без названия" }}",
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.primary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    GlyphButton(
                        Glyphs.Refresh,
                        "обновить из Todoist",
                        onClick = { app.appScope.launch { app.todoistSync.refresh(force = true) } },
                    )
                    Text(
                        status,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    GlyphButton(
                        if (searching) Glyphs.Close else Glyphs.Search,
                        if (searching) "закрыть поиск" else "поиск по делам",
                        onClick = {
                            if (searching) query = ""
                            searching = !searching
                        },
                    )
                }
                if (searching || query.isNotEmpty()) {
                    PaperField(
                        value = query,
                        onValueChange = { query = it },
                        label = "Поиск по делам",
                    )
                }
                // Токен живёт в «Настройках → Дела» вместе с остальными ключами.
                // Здесь про него только напоминание, и то лишь пока его нет.
                if (token.isBlank()) {
                    Text(
                        "Токена Todoist нет — дела приехать не могут. Он вставляется " +
                            "в «Настройках», группа «Дела».",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                        modifier = Modifier.padding(start = 4.dp),
                    )
                }
            }
        }

        // Разноска: разобранные наговоры, которые ещё не уехали в Todoist.
        // Стоит выше списка - это то, что ждёт решения владельца.
        item { RaznoskaSection(app) }

        val needle = query.trim().lowercase()
        if (needle.isNotEmpty()) {
            val found = tasks.filter { it.content.lowercase().contains(needle) }
                .sortedWith(compareByDescending<TodoistStore.Task> { it.priority }.thenBy { it.order })
            item(key = "found") {
                PaperCard(label = "найдено · ${found.size}") {
                    if (found.isEmpty()) PaperHint("Ни одно дело этого слова не содержит.")
                    TaskRows(found, projectName, starting, onPick)
                }
            }
        } else {
            group("today", "Сегодня", todayTasks, expanded, projectName, starting, onPick) {
                expanded = expanded.toggle("today")
            }
            group("inbox", "Без даты и без проекта", inboxTasks, expanded, projectName, starting, onPick) {
                expanded = expanded.toggle("inbox")
            }
            for ((id, list) in byProject) {
                group(
                    key = "p:$id",
                    title = projectName[id] ?: "Проект",
                    list = list,
                    expanded = expanded,
                    projectName = projectName,
                    starting = starting,
                    onPick = onPick,
                ) { expanded = expanded.toggle("p:$id") }
            }
        }

        if (tasks.isEmpty()) {
            item {
                Box(Modifier.padding(start = 4.dp)) {
                    PaperHint(
                        if (token.isBlank()) "Вставь токен — и дела приедут."
                        else "Дел нет. Нажми «Обновить».",
                    )
                }
            }
        }
    }
}

private fun Set<String>.toggle(key: String): Set<String> =
    if (key in this) this - key else this + key

// Группа — одна плашка: название и счёт в подписи, шеврон справа. Свёрнутая
// показывает первые дела одной строкой, а не пустую плашку: список должен
// читаться сверху вниз, а не листаться насквозь (24.09.2026).
private fun androidx.compose.foundation.lazy.LazyListScope.group(
    key: String,
    title: String,
    list: List<TodoistStore.Task>,
    expanded: Set<String>,
    projectName: Map<String, String>,
    starting: String,
    onPick: (TodoistStore.Task) -> Unit,
    onToggle: () -> Unit,
) {
    if (list.isEmpty()) return
    val isOpen = key in expanded
    item(key = "g:$key") {
        val sorted = list.sortedWith(compareByDescending<TodoistStore.Task> { it.priority }.thenBy { it.order })
        // Подпись плашки не переносится под шеврон: длинное имя проекта
        // выдавило бы его из строки, и раскрытую группу стало бы не свернуть.
        val name = if (title.length > 30) title.take(29).trimEnd() + "…" else title
        PaperCard(
            label = "$name · ${list.size}",
            trailing = {
                GlyphButton(
                    Glyphs.ChevronDown,
                    if (isOpen) "свернуть" else "раскрыть",
                    onClick = onToggle,
                    size = 30.dp,
                    modifier = Modifier.rotate(if (isOpen) 180f else 0f),
                )
            },
        ) {
            if (isOpen) {
                TaskRows(sorted, projectName, starting, onPick)
            } else {
                Text(
                    sorted.take(4).joinToString(" · ") { it.content },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
                        .clickable(onClick = onToggle)
                        .padding(vertical = 2.dp),
                )
            }
        }
    }
}

/** Дела плашки строками, между ними — волосяная линия. */
@Composable
private fun TaskRows(
    list: List<TodoistStore.Task>,
    projectName: Map<String, String>,
    starting: String,
    onPick: (TodoistStore.Task) -> Unit,
) {
    list.forEachIndexed { i, task ->
        if (i > 0) RowRule()
        TaskRow(task, projectName[task.projectId].orEmpty(), starting == task.id, onPick)
    }
}

@Composable
private fun TaskRow(
    task: TodoistStore.Task,
    project: String,
    busy: Boolean,
    onPick: (TodoistStore.Task) -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(enabled = !busy) { onPick(task) }
            .padding(vertical = 8.dp),
    ) {
        // Приоритет цветом точки: p1 красная, p2 оранжевая, p3 синяя, p4 - никак.
        val dot = when (task.priority) {
            4 -> MaterialTheme.colorScheme.error
            3 -> Color(0xFFF97316)
            2 -> Color(0xFF3B82F6)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        }
        DelaPriorityDot(dot)
        Column(Modifier.weight(1f)) {
            Text(
                task.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
            val note = listOf(project, task.due).filter { it.isNotBlank() }.joinToString(" · ")
            if (note.isNotBlank()) {
                Text(
                    note,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        // «Запустить в ленте» — значком, а не символом «▶» (24.09.2026).
        if (busy) {
            Text("…", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.primary)
        } else {
            Icon(
                Glyphs.Play,
                contentDescription = "начать в ленте",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(20.dp),
            )
        }
    }
}

/**
 * Настройки «Дел»: один токен. Живёт здесь, рядом с режимом, а показывается в
 * общей вкладке настроек — как и настройки остальных режимов.
 */
@Composable
internal fun TodoistSettings(app: PravkaApp) {
    val token by app.settings.todoistTokenFlow.collectAsState(initial = "")
    val status by app.todoistStore.statusFlow.collectAsState()
    var draft by remember(token) { mutableStateOf(token) }
    ru.zf.pravka.ui.PaperCard(
        label = "токен",
        info = "Todoist → Настройки → Интеграции → Разработчик → API-токен. Ключ живёт только на телефоне.",
    ) {
        ru.zf.pravka.ui.PaperField(value = draft, onValueChange = { draft = it }, label = "Токен Todoist")
        if (status.isNotBlank()) {
            Text(
                status,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(Modifier.weight(1f))
            ru.zf.pravka.ui.PaperButton("Сохранить и проверить", primary = true, onClick = {
                app.appScope.launch {
                    app.settings.setTodoistToken(draft.trim())
                    app.todoistSync.refresh(force = true)
                }
            })
        }
    }
}

