package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.graphics.Color
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
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperRow
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.SummaryLine
import ru.zf.pravka.trigger.onRaznoskaTap

// Разноска внутри вкладки «Дела»: разобранные наговоры, которые ещё не уехали
// в Todoist. Здесь их правят руками - тап по делу открывает его целиком, с
// проектом, метками, сроком и приоритетом, - и отправляют.
//
// Вкладка нарочно одна: в Todoist дела уезжают отсюда, из Todoist приезжают
// туда же. Одно место про дела, а не два.
//
// Второе издание (24.09.2026): первая плашка — «разноска» с кнопкой голоса,
// каждый наговор — своя плашка, окна дела, проекта и меток — листы снизу.
// Своя синяя подпись «РАЗНОСКА» ушла: вкладка и так в синей краске Дел.
@Composable
internal fun RaznoskaSection(app: PravkaApp) {
    val drafts by app.raznoskaStore.draftsFlow.collectAsState()
    val projectList by app.todoistStore.projectsFlow.collectAsState()
    val labelList by app.todoistStore.labelsFlow.collectAsState()
    var editing by remember { mutableStateOf<Pair<Long, ParsedTask>?>(null) }
    var busy by remember { mutableStateOf(false) }
    val scope = app.appScope

    LaunchedEffect(Unit) { app.raznoskaStore.load() }

    val pending = drafts.filter { it.pending }
    val done = drafts.filter { !it.pending }.take(3)
    val projectPaths = remember(projectList) { app.todoistStore.paths() }

    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        PaperCard(
            label = "разноска",
            info = "Кнопка «Д» — наговори дела, Опус разберёт их на задачи. " +
                "Плашка появится сразу; правь здесь, потом «Отправить».\n\n" +
                "Маршруты — куда ты сам перекладывал дела. Эти примеры уезжают в " +
                "следующий разбор — так Разноска перестаёт ошибаться дважды.",
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (done.isNotEmpty()) {
                    // Пока наговоры ждут — видно, что уже уехало; пусто — последний.
                    Text(
                        if (pending.isEmpty()) "последняя: " + doneLine(done.first())
                        else "Отправлено: " + done.joinToString(" · ") { doneLine(it) },
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(end = 8.dp),
                    )
                } else {
                    Spacer(Modifier.weight(1f))
                }
                PaperButton(
                    "Наговорить дела",
                    icon = Glyphs.Mic,
                    primary = true,
                    onClick = {
                        val service = PravkaAccessibilityService.instance
                        if (service == null) Feedback.toast(app, app.getString(R.string.toast_no_service))
                        else service.onRaznoskaTap()
                    },
                )
            }
            // Тумблер «Кнопка «Д» на экране» уехал в настройки (24.09.2026): во
            // вкладке ему было не место — рядом с ним жили дела, а не настройки.
            RoutesBlock(app)
        }

        for (draft in pending) {
            PaperCard(
                label = "наговор " + clock(draft.createdTs) + " · " + countWord(draft.pendingCount),
                trailing = {
                    GlyphButton(
                        Glyphs.Refresh,
                        "разобрать ещё раз",
                        enabled = !busy,
                        size = 30.dp,
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
                    )
                    GlyphButton(
                        Glyphs.Close,
                        "убрать наговор",
                        enabled = !busy,
                        size = 30.dp,
                        onClick = { scope.launch { app.raznoskaStore.delete(draft.id) } },
                    )
                },
            ) {
                for (task in draft.live) {
                    TaskLine(
                        task = task,
                        onClick = { if (!task.sent) editing = draft.id to task },
                    )
                }

                val dropped = draft.tasks.count { it.dropped }
                if (dropped > 0) PaperHint("убрано: $dropped")
                if (draft.notes.isNotBlank()) {
                    Spacer(Modifier.height(6.dp))
                    Text(
                        "Не дела (в CRM):",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(draft.notes, style = MaterialTheme.typography.bodySmall)
                    PaperTextButton("Копировать", icon = Glyphs.Copy, onClick = {
                        scope.launch {
                            ru.zf.pravka.target.ClipboardTarget(app).write(draft.notes)
                            Feedback.toast(app, "Скопировано")
                        }
                    })
                }
                if (draft.error.isNotBlank()) {
                    Text(
                        draft.error,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    if (draft.costUsd > 0) PaperHint(String.format(Locale.US, "%.3f", draft.costUsd) + " $")
                    Spacer(Modifier.weight(1f))
                    PaperButton(
                        if (busy) "…" else "Отправить в Todoist",
                        icon = Glyphs.Send,
                        primary = true,
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
                    )
                }
            }
        }
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
                    // Переложил дело руками - следующий разбор это учтёт.
                    runCatching { app.raznoskaEngine.learnRoute(edit.second, updated) }
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

// Маршруты: чему Разноска научилась на его поправках. Свёрнуто строкой-сводкой,
// потому что смотреть туда надо редко - только если она возит дело не туда.
// Что это такое — за «i» первой плашки (24.09.2026).
@Composable
private fun RoutesBlock(app: PravkaApp) {
    val routes by app.raznoskaRoutes.routesFlow.collectAsState()
    var open by remember { mutableStateOf(false) }
    val scope = app.appScope
    LaunchedEffect(Unit) { app.raznoskaRoutes.load() }
    if (routes.isEmpty()) return
    SummaryLine(
        title = "Маршруты",
        summary = routes.size.toString(),
        expanded = open,
        onToggle = { open = !open },
    ) {
        for (route in routes) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        "«" + route.text + "»",
                        style = MaterialTheme.typography.bodySmall,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        "→ " + route.destination(),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                GlyphButton(
                    Glyphs.Close,
                    "забыть маршрут",
                    size = 34.dp,
                    onClick = { scope.launch { app.raznoskaRoutes.forget(route.id) } },
                )
            }
        }
    }
}

/** Одна строка дела в карточке: заголовок, под ним проект/метки/срок. */
@Composable
private fun TaskLine(task: ParsedTask, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 5.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            // Отправленное — галкой краски режима, остальное — точкой приоритета.
            Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterStart) {
                if (task.sent) {
                    Icon(
                        Glyphs.Check,
                        contentDescription = "отправлено",
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(14.dp),
                    )
                } else {
                    DelaPriorityDot(priorityColor(task.priority))
                }
            }
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

/**
 * Точка приоритета дела — кружок, а не символ «•»: его размер пляшет от
 * шрифта. Общая для Разноски и списка Todoist (24.09.2026).
 */
@Composable
internal fun DelaPriorityDot(color: Color) {
    Box(
        Modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}

// Дело целиком: текст, проект, метки, срок, приоритет. Одно окно - одна
// запись в стор, поэтому набор текста не дёргает список на каждой букве.
// С 24.09.2026 — лист снизу: «Готово» справа под пальцем, «убрать дело» —
// корзиной у левого края, приоритет — чипами, проект и метки — строками,
// которые открывают свои листы поверх.
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

    PaperAlert(
        onDismiss = onDismiss,
        title = "Дело",
        icon = Glyphs.Delo,
        confirm = SheetAction("Готово", icon = Glyphs.Check) {
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
        },
        dismiss = SheetAction("Отмена", onClick = onDismiss),
        destructive = SheetAction("Убрать это дело", icon = Glyphs.Delete, onClick = onDrop),
    ) {
        PaperField(
            value = content,
            onValueChange = { content = it },
            label = "Кто: что сделать",
            singleLine = false,
        )
        PaperField(
            value = description,
            onValueChange = { description = it },
            label = "Описание (если без него непонятно)",
            singleLine = false,
        )
        PaperRow(
            title = "#" + projectName.ifBlank { "проект не выбран" },
            icon = Glyphs.ListLines,
            onClick = { picking = "project" },
        )
        PaperRow(
            title = if (chosen.isEmpty()) "метки" else chosen.joinToString(" ") { "@" + it },
            icon = Glyphs.Tag,
            onClick = { picking = "labels" },
        )
        ChipRow {
            for (p in listOf(ParsedTask.P1, ParsedTask.P2, ParsedTask.P3, ParsedTask.P4)) {
                PaperChip("P" + (5 - p), selected = p == priority, onClick = { priority = p })
            }
        }
        PaperField(
            value = due,
            onValueChange = { due = it },
            label = "Срок ГГГГ-ММ-ДД",
        )
        PaperField(
            value = repeat,
            onValueChange = { repeat = it },
            label = "Повтор словами («каждый вторник»)",
        )
        if (task.duplicateOf.isNotBlank()) {
            Text(
                "⚠ В Todoist уже есть похожее: " + task.duplicateOf,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
            )
        }
    }

    if (picking == "project") {
        PaperSheet(onDismiss = { picking = "" }, title = "Проект", icon = Glyphs.ListLines) {
            PickRow("без проекта (Inbox)", selected = projectId.isBlank()) {
                projectId = ""
                projectName = ""
                picking = ""
            }
            for ((path, project) in projects) {
                PickRow(path, selected = project.id == projectId) {
                    projectId = project.id
                    projectName = path
                    picking = ""
                }
            }
        }
    }

    if (picking == "labels") {
        PaperSheet(
            onDismiss = { picking = "" },
            title = "Метки",
            icon = Glyphs.Tag,
            footer = {
                Spacer(Modifier.weight(1f))
                PaperButton("Готово", icon = Glyphs.Check, primary = true, onClick = { picking = "" })
            },
        ) {
            if (labels.isEmpty()) {
                Text("Метки ещё не приехали — нажми «Обновить» во вкладке.")
            }
            for (label in labels) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(10.dp))
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
    }
}

/**
 * Строка выбора в листе: тап выбирает и закрывает, выбранное — галкой.
 * `PaperRow` не подошёл: его шеврон «дальше» в выборе врёт (24.09.2026).
 */
@Composable
private fun PickRow(text: String, selected: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(vertical = 10.dp, horizontal = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.bodyMedium,
            color = if (selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface,
            modifier = Modifier.weight(1f),
        )
        if (selected) {
            Icon(
                Glyphs.Check,
                contentDescription = "выбрано",
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(18.dp),
            )
        }
    }
}

@Composable
private fun priorityColor(priority: Int) = when (priority) {
    ParsedTask.P1 -> MaterialTheme.colorScheme.error
    ParsedTask.P2 -> Color(0xFFF97316)
    ParsedTask.P3 -> Color(0xFF3B82F6)
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
