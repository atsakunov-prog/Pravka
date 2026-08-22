package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.launch
import ru.zf.pravka.data.TodoistStore
import ru.zf.pravka.ui.Feedback

// Вкладка «Дела»: список Todoist, тап по делу = оно становится текущим в
// ленте. Сверху - Разноска: наговорённые дела, которые ещё не уехали в
// Todoist, с правкой руками и кнопкой «Отправить» (см. RaznoskaSection).
//
// Группы: «Сегодня» (и всё просроченное - оно и есть сегодняшнее) раскрыта,
// «Без даты и без проекта» (входящие) и проекты свёрнуты. Поиск - простой
// фильтр по вхождению слова, он показывает плоский список поверх групп.
@Composable
fun TodoistTab(app: PravkaApp) {
    val store = app.todoistStore
    val tasks by store.tasksFlow.collectAsState()
    val projects by store.projectsFlow.collectAsState()
    val status by store.statusFlow.collectAsState()
    val token by app.settings.todoistTokenFlow.collectAsState(initial = "")
    val ribbon by app.zasechkaStore.entriesFlow.collectAsState()

    var query by remember { mutableStateOf("") }
    var expanded by remember { mutableStateOf(setOf("today")) }
    var showSetup by remember { mutableStateOf(false) }
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
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
    ) {
        item {
            Text("Дела", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Тап по делу — оно становится текущим в ленте. Когда дело закончится, " +
                    "в задачу Todoist уедет коммент со временем. Кнопка «Р» — наговорить " +
                    "новые дела сюда.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )
            if (running != null) {
                Text(
                    "Сейчас идёт: ${running.title.ifBlank { "без названия" }}",
                    style = MaterialTheme.typography.bodyMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.primary,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                value = query,
                onValueChange = { query = it },
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Поиск по делам") },
                singleLine = true,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                TextButton(onClick = {
                    app.appScope.launch { app.todoistSync.refresh(force = true) }
                }) { Text("Обновить") }
                TextButton(onClick = { showSetup = !showSetup }) { Text("Токен") }
                Text(
                    status,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.padding(start = 4.dp),
                )
            }
            if (showSetup || token.isBlank()) {
                var draft by remember(token) { mutableStateOf(token) }
                Text(
                    "Todoist → Настройки → Интеграции → Разработчик → API-токен. " +
                        "Ключ живёт только на телефоне.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("Токен Todoist") },
                        singleLine = true,
                    )
                    TextButton(onClick = {
                        app.appScope.launch {
                            app.settings.setTodoistToken(draft)
                            app.todoistSync.refresh(force = true)
                        }
                    }) { Text("Сохранить") }
                }
            }
            Spacer(Modifier.height(6.dp))
        }

        // Разноска: разобранные наговоры, которые ещё не уехали в Todoist.
        // Стоит выше списка - это то, что ждёт решения владельца.
        item { RaznoskaSection(app) }

        val needle = query.trim().lowercase()
        if (needle.isNotEmpty()) {
            val found = tasks.filter { it.content.lowercase().contains(needle) }
                .sortedWith(compareByDescending<TodoistStore.Task> { it.priority }.thenBy { it.order })
            item {
                Text(
                    "Найдено: ${found.size}",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            items(found) { task ->
                TaskRow(task, projectName[task.projectId].orEmpty(), starting == task.id, onPick)
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
                Text(
                    if (token.isBlank()) "Вставь токен — и дела приедут."
                    else "Дел нет. Нажми «Обновить».",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}

private fun Set<String>.toggle(key: String): Set<String> =
    if (key in this) this - key else this + key

// Заголовок группы + её дела. Свёрнутая группа показывает только счёт -
// список должен читаться сверху вниз, а не листаться насквозь.
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
    item(key = "h:$key") {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable(onClick = onToggle)
                .padding(top = 10.dp, bottom = 2.dp),
        ) {
            Text(
                if (isOpen) "▾" else "▸",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.width(18.dp),
            )
            Text(
                title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f),
            )
            Text(
                list.size.toString(),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
    if (!isOpen) return
    items(list.sortedWith(compareByDescending<TodoistStore.Task> { it.priority }.thenBy { it.order })) { task ->
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
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = !busy) { onPick(task) }
            .padding(start = 18.dp, top = 5.dp, bottom = 5.dp),
    ) {
        // Приоритет цветом точки: p1 красная, p2 оранжевая, p3 синяя, p4 - никак.
        val dot = when (task.priority) {
            4 -> MaterialTheme.colorScheme.error
            3 -> androidx.compose.ui.graphics.Color(0xFFF97316)
            2 -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
            else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f)
        }
        Text("•", color = dot, style = MaterialTheme.typography.bodyMedium)
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
        Text(
            if (busy) "…" else "▶",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.primary,
        )
    }
}
