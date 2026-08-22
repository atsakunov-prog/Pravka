package ru.zf.pravka

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
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
import ru.zf.pravka.core.ParsedTask
import ru.zf.pravka.data.RaznoskaStore
import ru.zf.pravka.data.TodoistStore
import ru.zf.pravka.trigger.PravkaAccessibilityService
import ru.zf.pravka.ui.Feedback

// Разноска внутри вкладки «Дела»: разобранные наговоры, которые ещё не уехали
// в Todoist. Здесь их правят руками - тап по делу открывает его целиком, с
// проектом, метками, сроком и приоритетом, - и отправляют.
//
// Вкладка нарочно одна: в Todoist дела уезжают отсюда, из Todoist приезжают
// туда же. Одно место про дела, а не два.
@Composable
internal fun RaznoskaSection(app: PravkaApp) {
    val drafts by app.raznoskaStore.draftsFlow.collectAsState()
    val projectList by app.todoistStore.projectsFlow.collectAsState()
    val labelList by app.todoistStore.labelsFlow.collectAsState()
    val buttonOn by app.settings.rEnabledFlow.collectAsState(initial = true)
    var editing by remember { mutableStateOf<Pair<Long, ParsedTask>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = app.appScope

    LaunchedEffect(Unit) { app.raznoskaStore.load() }

    val pending = drafts.filter { it.pending }
    val done = drafts.filter { !it.pending }.take(3)
    val projectPaths = remember(projectList) { app.todoistStore.paths() }

    Column(Modifier.fillMaxWidth()) {
        Text(
            "РАЗНОСКА",
            style = MaterialTheme.typography.labelMedium,
            color = RaznoskaBlue,
            modifier = Modifier.padding(top = 4.dp),
        )
        Text(
            "Кнопка «Р» — наговори дела, Опус разберёт их на задачи. " +
                "Плашка появится сразу; правь здесь, потом «Отправить».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                val service = PravkaAccessibilityService.instance
                if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                else service.onRaznoskaTap()
            }) { Text("🎙 Наговорить дела") }
            if (pending.isEmpty() && done.isNotEmpty()) {
                Text(
                    "последняя: " + doneLine(done.first()),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = buttonOn,
                onCheckedChange = { on -> scope.launch { app.settings.setREnabled(on) } },
            )
            Text(
                "Кнопка «Р» на экране",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Spacer(Modifier.height(6.dp))

        for (draft in pending) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(bottom = 10.dp),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceContainerLow,
                ),
            ) {
                Column(Modifier.padding(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(
                            "наговор " + clock(draft.createdTs) + " · " + countWord(draft.pendingCount),
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            modifier = Modifier.weight(1f),
                        )
                        TextButton(
                            enabled = !busy,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val result = app.raznoskaEngine.resplit(draft.id)
                                    busy = false
                                    result.onFailure {
                                        Feedback.toast(app, it.message ?: "Не вышло")
                                    }
                                }
                            },
                        ) { Text("Ещё раз") }
                        TextButton(
                            enabled = !busy,
                            onClick = { scope.launch { app.raznoskaStore.delete(draft.id) } },
                        ) { Text("✕") }
                    }

                    for (task in draft.live) {
                        TaskLine(
                            task = task,
                            onClick = { if (!task.sent) editing = draft.id to task },
                        )
                    }

                    val dropped = draft.tasks.count { it.dropped }
                    if (dropped > 0) {
                        Text(
                            "убрано: $dropped",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    if (draft.notes.isNotBlank()) {
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "Не дела (в CRM):",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                        Text(draft.notes, style = MaterialTheme.typography.bodySmall)
                        TextButton(onClick = {
                            scope.launch {
                                ru.zf.pravka.target.ClipboardTarget(app).write(draft.notes)
                                Feedback.toast(app, "Скопировано")
                            }
                        }) { Text("Копировать") }
                    }
                    if (draft.error.isNotBlank()) {
                        Text(
                            draft.error,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error,
                        )
                    }
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Button(
                            enabled = !busy && draft.pendingCount > 0,
                            onClick = {
                                busy = true
                                scope.launch {
                                    val outcome = app.raznoskaEngine.send(draft.id)
                                    busy = false
                                    Feedback.toast(
                                        app,
                                        when {
                                            outcome.ok -> "✓ " + countWord(outcome.created) + " в Todoist"
                                            outcome.created > 0 ->
                                                "Отправлено ${outcome.created}, осталось ${outcome.failed}"
                                            else -> "Не отправилось: " + outcome.error
                                        },
                                        long = !outcome.ok,
                                    )
                                }
                            },
                        ) { Text(if (busy) "…" else "Отправить в Todoist") }
                        if (draft.costUsd > 0) {
                            Text(
                                String.format(Locale.US, "%.3f", draft.costUsd) + " $",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                }
            }
        }

        if (pending.isNotEmpty() && done.isNotEmpty()) {
            Text(
                "Отправлено: " + done.joinToString(" · ") { doneLine(it) },
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.height(10.dp))
    }

    val edit = editing
    if (edit != null) {
        TaskDialog(
            task = edit.second,
            projects = projectPaths,
            labels = labelList,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                scope.launch {
                    val draft = app.raznoskaStore.byId(edit.first) ?: return@launch
                    app.raznoskaStore.replaceTasks(
                        edit.first,
                        draft.tasks.map { if (it.id == updated.id) updated else it },
                    )
                }
            },
            onDrop = {
                editing = null
                scope.launch {
                    val draft = app.raznoskaStore.byId(edit.first) ?: return@launch
                    app.raznoskaStore.replaceTasks(
                        edit.first,
                        draft.tasks.map {
                            if (it.id == edit.second.id) it.copy(dropped = true) else it
                        },
                    )
                }
            },
        )
    }
}

/** Одна строка дела в карточке: заголовок, под ним проект/метки/срок. */
@Composable
private fun TaskLine(task: ParsedTask, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                if (task.sent) "✓" else "•",
                color = if (task.sent) RaznoskaBlue else priorityColor(task.priority),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(end = 6.dp),
            )
            Text(
                task.content,
                style = MaterialTheme.typography.bodyMedium,
                maxLines = 3,
                modifier = Modifier.weight(1f),
            )
        }
        val meta = taskMeta(task)
        if (meta.isNotBlank()) {
            Text(
                meta,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(start = 18.dp),
            )
        }
        if (task.duplicateOf.isNotBlank() && !task.sent) {
            Text(
                "⚠ похоже: " + task.duplicateOf,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                maxLines = 2,
                modifier = Modifier.padding(start = 18.dp),
            )
        }
    }
}

// Дело целиком: текст, проект, метки, срок, приоритет. Один диалог - одна
// запись в стор, поэтому набор текста не дёргает список на каждой букве.
@Composable
private fun TaskDialog(
    task: ParsedTask,
    projects: List<Pair<String, TodoistStore.Project>>,
    labels: List<String>,
    onDismiss: () -> Unit,
    onSave: (ParsedTask) -> Unit,
    onDrop: () -> Unit,
) {
    var content by remember { mutableStateOf(task.content) }
    var description by remember { mutableStateOf(task.description) }
    var projectId by remember { mutableStateOf(task.projectId) }
    var projectName by remember { mutableStateOf(task.projectName) }
    var due by remember { mutableStateOf(task.due) }
    var repeat by remember { mutableStateOf(task.repeat) }
    var priority by remember { mutableStateOf(task.priority) }
    var chosen by remember { mutableStateOf(task.labels.toSet()) }
    var picking by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Дело") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = content,
                    onValueChange = { content = it },
                    label = { Text("Кто: что сделать") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("Описание (если без него непонятно)") },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedButton(
                    onClick = { picking = "project" },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        "#" + projectName.ifBlank { "проект не выбран" },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                OutlinedButton(
                    onClick = { picking = "labels" },
                    modifier = Modifier.fillMaxWidth(),
                ) {
                    Text(
                        if (chosen.isEmpty()) "метки" else chosen.joinToString(" ") { "@" + it },
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                    for (p in listOf(ParsedTask.P1, ParsedTask.P2, ParsedTask.P3, ParsedTask.P4)) {
                        val label = "P" + (5 - p)
                        if (p == priority) {
                            Button(onClick = { priority = p }) { Text(label) }
                        } else {
                            OutlinedButton(onClick = { priority = p }) { Text(label) }
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = due,
                    onValueChange = { due = it },
                    label = { Text("Срок ГГГГ-ММ-ДД") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = repeat,
                    onValueChange = { repeat = it },
                    label = { Text("Повтор словами («каждый вторник»)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (task.duplicateOf.isNotBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "⚠ В Todoist уже есть похожее: " + task.duplicateOf,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(4.dp))
                TextButton(onClick = onDrop) { Text("Убрать это дело") }
            }
        },
        confirmButton = {
            TextButton(onClick = {
                onSave(
                    task.copy(
                        content = content.trim(),
                        description = description.trim(),
                        projectId = projectId,
                        projectName = projectName,
                        labels = chosen.toList(),
                        priority = priority,
                        due = due.trim(),
                        repeat = repeat.trim(),
                    )
                )
            }) { Text("Готово") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )

    if (picking == "project") {
        AlertDialog(
            onDismissRequest = { picking = "" },
            title = { Text("Проект") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Text(
                        "без проекта (Inbox)",
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable {
                                projectId = ""
                                projectName = ""
                                picking = ""
                            }
                            .padding(vertical = 10.dp),
                    )
                    for ((path, project) in projects) {
                        Text(
                            path,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    projectId = project.id
                                    projectName = path
                                    picking = ""
                                }
                                .padding(vertical = 10.dp),
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = "" }) { Text("Закрыть") } },
        )
    }

    if (picking == "labels") {
        AlertDialog(
            onDismissRequest = { picking = "" },
            title = { Text("Метки") },
            text = {
                Column(
                    Modifier
                        .heightIn(max = 420.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    if (labels.isEmpty()) {
                        Text("Метки ещё не приехали — нажми «Обновить» во вкладке.")
                    }
                    for (label in labels) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    chosen = if (label in chosen) chosen - label else chosen + label
                                },
                        ) {
                            Checkbox(
                                checked = label in chosen,
                                onCheckedChange = {
                                    chosen = if (label in chosen) chosen - label else chosen + label
                                },
                            )
                            Text("@" + label)
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { picking = "" }) { Text("Готово") } },
        )
    }
}

private val RaznoskaBlue = androidx.compose.ui.graphics.Color(0xFF2A5D82)

@Composable
private fun priorityColor(priority: Int) = when (priority) {
    ParsedTask.P1 -> MaterialTheme.colorScheme.error
    ParsedTask.P2 -> androidx.compose.ui.graphics.Color(0xFFF97316)
    ParsedTask.P3 -> androidx.compose.ui.graphics.Color(0xFF3B82F6)
    else -> MaterialTheme.colorScheme.onSurfaceVariant
}

private fun taskMeta(task: ParsedTask): String {
    val parts = mutableListOf<String>()
    if (task.projectName.isNotBlank()) parts.add("#" + task.projectName)
    if (task.labels.isNotEmpty()) parts.add(task.labels.joinToString(" ") { "@" + it })
    if (task.repeat.isNotBlank()) parts.add(task.repeat)
    else if (task.due.isNotBlank()) parts.add(task.due)
    if (task.priority != ParsedTask.P4) parts.add(task.priorityLabel)
    if (task.projectName.isBlank()) parts.add("проект не выбран")
    return parts.joinToString(" · ")
}

private fun clock(ts: Long): String = SimpleDateFormat("HH:mm", Locale.US).format(Date(ts))

private fun countWord(n: Int): String {
    val word = when {
        n % 10 == 1 && n % 100 != 11 -> "дело"
        n % 10 in 2..4 && n % 100 !in 12..14 -> "дела"
        else -> "дел"
    }
    return "$n $word"
}

private fun doneLine(draft: RaznoskaStore.Draft): String =
    clock(draft.createdTs) + " · " + countWord(draft.live.count { it.sent })
