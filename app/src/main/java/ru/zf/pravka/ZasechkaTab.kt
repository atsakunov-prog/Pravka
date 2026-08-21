package ru.zf.pravka

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.ui.Feedback

// Вкладка «Засечка»: the owner's day as a ribbon of entries, the numbers he
// loves, and the knobs. Everything the buttons capture lands here for review
// and fixing - the tab is deliberately editable down to minutes, because the
// voice pipeline is fast but not sacred.

// Muted editorial palette for category chips/bars; a category keeps its color
// between sessions because it is picked by name hash, not by list position.
private val CATEGORY_COLORS = listOf(
    Color(0xFFB4551E), // терракота
    Color(0xFF7A6A2F), // олива
    Color(0xFF2F6B5E), // хвоя
    Color(0xFF54628F), // сумеречный синий
    Color(0xFF8A4E68), // брусника
    Color(0xFF9A6A28), // охра
    Color(0xFF5E7345), // мох
    Color(0xFF396B7E), // волна
    Color(0xFF7D5A9E), // вереск
    Color(0xFF9C4A3C), // кирпич
)

private fun categoryColor(name: String): Color =
    if (name.isBlank()) Color(0xFF8A8172)
    else CATEGORY_COLORS[abs(name.lowercase().hashCode()) % CATEGORY_COLORS.size]

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
private val dayLabelFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

private fun fmtTime(ms: Long): String = timeFormat.format(Date(ms))

private fun fmtDur(min: Long): String =
    if (min >= 60) "${min / 60} ч ${min % 60} м" else "$min м"

/** Local-midnight start of the day [offsetDays] before today. */
private fun dayStartBack(offsetDays: Int): Long {
    val cal = Calendar.getInstance()
    cal.add(Calendar.DAY_OF_YEAR, -offsetDays)
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}

/** "14:05" or "14.05" typed by the owner -> ms on the entry's day. */
private fun parseTimeOfDay(dayStart: Long, text: String): Long? {
    val m = Regex("^\\s*(\\d{1,2})[:.](\\d{2})\\s*$").find(text) ?: return null
    val h = m.groupValues[1].toInt()
    val min = m.groupValues[2].toInt()
    if (h > 23 || min > 59) return null
    return dayStart + h * 3_600_000L + min * 60_000L
}

@Composable
internal fun ZasechkaTab(app: PravkaApp) {
    val context = LocalContext.current
    val store = app.zasechkaStore
    val entries by store.entriesFlow.collectAsState()
    val categories by store.categoriesFlow.collectAsState()
    val clients by store.clientsFlow.collectAsState()
    val syncStatus by app.zasechkaSync.statusFlow.collectAsState()
    LaunchedEffect(Unit) { store.all() }  // first read triggers the load

    var dayOffset by remember { mutableStateOf(0) }
    var weekMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ZasechkaStore.Entry?>(null) }
    var draft by remember { mutableStateOf("") }
    var processing by remember { mutableStateOf(false) }

    // The "идёт N мин" counters tick without any data changing.
    var now by remember { mutableStateOf(System.currentTimeMillis()) }
    LaunchedEffect(Unit) {
        while (true) {
            delay(30_000)
            now = System.currentTimeMillis()
        }
    }

    val dayStart = remember(dayOffset) { dayStartBack(dayOffset) }
    val dayEnd = dayStart + 86_400_000L
    val rangeStart = if (weekMode) dayStart - 6 * 86_400_000L else dayStart
    val rangeEntries = remember(entries, rangeStart, dayEnd) {
        entries.filter { it.start in rangeStart until dayEnd }.sortedBy { it.start }
    }
    val open = entries.lastOrNull { it.open }

    val submitText: () -> Unit = submit@{
        val text = draft.trim()
        if (text.isBlank() || processing) return@submit
        processing = true
        draft = ""
        // App-scope, not the composable's: leaving the tab must not lose a take.
        app.appScope.launch {
            val outcome = runCatching { app.zasechkaEngine.record(text, "text") }.getOrNull()
            processing = false
            when {
                outcome == null -> Feedback.toast(app, app.getString(R.string.z_record_failed))
                !outcome.categorized ->
                    Feedback.toast(app, app.getString(R.string.z_saved_raw, outcome.error ?: ""))
                else -> Feedback.toast(app, "⏱ ${outcome.entry.title}")
            }
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 32.dp),
    ) {
        item {
            Text("Засечка", style = MaterialTheme.typography.headlineSmall)
            Text(
                "Кнопка «З» — нажал, сказал, чем занят, нажал ещё раз. Сонет разберёт по категориям сам.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp, bottom = 12.dp),
            )
        }

        // ---- date navigation + day/week toggle ----
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = { dayOffset += if (weekMode) 7 else 1 }) {
                    Icon(Icons.Filled.KeyboardArrowLeft, contentDescription = "раньше")
                }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    val label = when {
                        weekMode -> "неделя до " + dayLabelFormat.format(Date(dayStart))
                        dayOffset == 0 -> "сегодня"
                        dayOffset == 1 -> "вчера"
                        else -> dayLabelFormat.format(Date(dayStart))
                    }
                    Text(label, style = MaterialTheme.typography.titleMedium)
                }
                IconButton(
                    onClick = { dayOffset = (dayOffset - (if (weekMode) 7 else 1)).coerceAtLeast(0) },
                    enabled = dayOffset > 0,
                ) {
                    Icon(Icons.Filled.KeyboardArrowRight, contentDescription = "позже")
                }
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(selected = !weekMode, onClick = { weekMode = false }, label = { Text("День") })
                FilterChip(selected = weekMode, onClick = { weekMode = true }, label = { Text("Неделя") })
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- the running entry (today only) ----
        if (!weekMode && dayOffset == 0) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(14.dp)) {
                        if (open != null) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryChip(open.category)
                                Spacer(Modifier.width(8.dp))
                                Text(
                                    open.title.ifBlank { "(без названия)" },
                                    style = MaterialTheme.typography.titleMedium,
                                    modifier = Modifier.weight(1f),
                                )
                            }
                            val minutes = open.durationMin(now)
                            Text(
                                "идёт ${fmtDur(minutes)} · с ${fmtTime(open.start)}" +
                                    (if (open.client.isNotBlank()) " · ${open.client}" else ""),
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                            Row(Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                OutlinedButton(onClick = {
                                    app.appScope.launch { app.zasechkaEngine.closeOpen() }
                                }) { Text("Завершить") }
                                TextButton(onClick = { editing = open }) { Text("Править") }
                            }
                        } else {
                            Text(
                                "Сейчас ничего не записывается",
                                style = MaterialTheme.typography.titleSmall,
                            )
                            Text(
                                "Нажми «З» на экране или надиктуй/впиши дело ниже.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(12.dp))
            }

            // ---- quick add: voice or typed ----
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(if (processing) "Разбираю…" else "Чем занят? (текстом)") },
                        singleLine = true,
                        enabled = !processing,
                    )
                    IconButton(onClick = { submitText() }, enabled = !processing && draft.isNotBlank()) {
                        Icon(Icons.Filled.Send, contentDescription = "записать")
                    }
                }
                TextButton(onClick = {
                    val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(context, context.getString(R.string.toast_no_service))
                    else service.onZasechkaTap()
                }) { Text("🎙 Надиктовать") }
                Spacer(Modifier.height(8.dp))
            }
        }

        // ---- totals by category ----
        item {
            val byCategory = rangeEntries
                .groupBy { it.category }
                .mapValues { (_, list) -> list.sumOf { it.durationMin(now) } }
                .entries.sortedByDescending { it.value }
            val total = byCategory.sumOf { it.value }
            Text(
                (if (weekMode) "За неделю" else "За день") + ": ${fmtDur(total)} · записей: ${rangeEntries.size}",
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val max = byCategory.maxOfOrNull { it.value } ?: 0L
            for ((category, minutes) in byCategory) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Text(
                        category.ifBlank { "без категории" },
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.width(130.dp),
                        maxLines = 1,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
                    ) {
                        val fraction = if (max > 0) minutes.toFloat() / max else 0f
                        Box(
                            Modifier
                                .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                                .height(10.dp)
                                .background(categoryColor(category), RoundedCornerShape(5.dp)),
                        )
                    }
                    Text(
                        fmtDur(minutes),
                        style = MaterialTheme.typography.bodySmall,
                        modifier = Modifier.padding(start = 8.dp).width(64.dp),
                    )
                }
            }
            if (byCategory.isEmpty()) {
                Text(
                    "Пока пусто.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(12.dp))
        }

        // ---- clients (who eats the time) ----
        if (weekMode) {
            item {
                val byClient = rangeEntries
                    .filter { it.client.isNotBlank() }
                    .groupBy { it.client }
                    .mapValues { (_, list) -> list.sumOf { it.durationMin(now) } }
                    .entries.sortedByDescending { it.value }
                if (byClient.isNotEmpty()) {
                    Text("По клиентам", style = MaterialTheme.typography.titleSmall)
                    for ((client, minutes) in byClient) {
                        Text(
                            "$client — ${fmtDur(minutes)}",
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(vertical = 2.dp),
                        )
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }

        // ---- the ribbon itself ----
        if (!weekMode) {
            val dayList = rangeEntries
            items(dayList, key = { it.id }) { entry ->
                // A visible hole in the ribbon is the whole point of the app -
                // show it between entries instead of papering over it.
                val index = dayList.indexOf(entry)
                if (index > 0) {
                    val prev = dayList[index - 1]
                    if (!prev.open) {
                        val gapMin = (entry.start - prev.end) / 60_000L
                        if (gapMin >= 5) {
                            Text(
                                "···  ${fmtDur(gapMin)} без записи",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.padding(start = 60.dp, top = 2.dp, bottom = 2.dp),
                            )
                        }
                    }
                }
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { editing = entry }
                        .padding(vertical = 6.dp),
                ) {
                    Column(Modifier.width(52.dp)) {
                        Text(fmtTime(entry.start), style = MaterialTheme.typography.bodySmall)
                        Text(
                            if (entry.open) "…" else fmtTime(entry.end),
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Box(
                        Modifier
                            .width(4.dp)
                            .height(36.dp)
                            .background(categoryColor(entry.category), RoundedCornerShape(2.dp)),
                    )
                    Column(Modifier.weight(1f).padding(start = 10.dp)) {
                        Text(
                            entry.title.ifBlank { entry.raw.take(60) },
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Medium,
                            maxLines = 2,
                        )
                        val details = buildList {
                            add(fmtDur(entry.durationMin(now)))
                            if (entry.category.isNotBlank()) add(entry.category) else add("без категории")
                            if (entry.client.isNotBlank()) add(entry.client)
                            if (entry.useful > 0) add("★${entry.useful}")
                        }.joinToString(" · ")
                        Text(
                            details,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                }
            }
            if (rangeEntries.isEmpty()) {
                item {
                    Text(
                        "Записей за этот день нет.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 8.dp),
                    )
                }
            }
        }

        // ---- settings for the whole Засечка mode ----
        item {
            Spacer(Modifier.height(16.dp))
            ZasechkaConfig(app, categories, clients, syncStatus, entries)
        }
    }

    editing?.let { entry ->
        EditEntryDialog(
            entry = entry,
            categories = categories,
            onDismiss = { editing = null },
            onSave = { updated ->
                editing = null
                app.appScope.launch {
                    store.update(updated)
                    app.zasechkaSync.kickSoon(app.appScope)
                }
            },
            onDelete = {
                editing = null
                app.appScope.launch { store.delete(entry.id) }
            },
        )
    }
}

@Composable
private fun CategoryChip(category: String) {
    Box(
        Modifier.background(categoryColor(category).copy(alpha = 0.25f), RoundedCornerShape(8.dp)),
    ) {
        Text(
            category.ifBlank { "—" },
            fontSize = 12.sp,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Settings block: button, reminders, dictionaries of categories/clients,
// the Sheets webhook and the CSV export.
// ---------------------------------------------------------------------------

@Composable
private fun ZasechkaConfig(
    app: PravkaApp,
    categories: List<String>,
    clients: List<String>,
    syncStatus: String,
    entries: List<ZasechkaStore.Entry>,
) {
    val context = LocalContext.current
    var expanded by remember { mutableStateOf(false) }

    TextButton(onClick = { expanded = !expanded }) {
        Text(if (expanded) "▾ Настройки Засечки" else "▸ Настройки Засечки")
    }
    if (!expanded) return

    val zEnabled by app.settings.zEnabledFlow.collectAsState(initial = true)
    val gapMin by app.settings.zGapMinFlow.collectAsState(initial = 45)
    val dayStartH by app.settings.zDayStartFlow.collectAsState(initial = 9)
    val dayEndH by app.settings.zDayEndFlow.collectAsState(initial = 23)
    val webhook by app.settings.zWebhookFlow.collectAsState(initial = "")

    Column(Modifier.padding(top = 4.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("Кнопка «З» на экране", style = MaterialTheme.typography.bodyMedium)
                Text(
                    "Видна всегда, в любом приложении",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(checked = zEnabled, onCheckedChange = { v ->
                app.appScope.launch { app.settings.setZEnabled(v) }
            })
        }

        Spacer(Modifier.height(8.dp))
        Text(
            if (gapMin > 0) "Напоминать о дыре во времени: через $gapMin мин"
            else "Напоминания о дырах: выключены",
            style = MaterialTheme.typography.bodyMedium,
        )
        Slider(
            value = gapMin.toFloat(),
            onValueChange = { v ->
                val snapped = (v / 15f).roundToInt() * 15
                app.appScope.launch { app.settings.setZGapMin(snapped) }
            },
            valueRange = 0f..120f,
            steps = 7,
        )
        Text(
            "Активные часы: с $dayStartH:00 до $dayEndH:00 (утром — «день начался?», после — «закрыть день?»)",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Slider(
            value = dayStartH.toFloat(),
            onValueChange = { v -> app.appScope.launch { app.settings.setZDayStart(v.roundToInt()) } },
            valueRange = 0f..12f,
            steps = 11,
        )
        Slider(
            value = dayEndH.toFloat(),
            onValueChange = { v -> app.appScope.launch { app.settings.setZDayEnd(v.roundToInt()) } },
            valueRange = 12f..24f,
            steps = 11,
        )

        Spacer(Modifier.height(8.dp))
        EditableList(
            title = "Категории",
            hint = "Сонет выбирает строго из этого списка",
            values = categories,
            onChange = { app.appScope.launch { app.zasechkaStore.setCategories(it) } },
        )
        Spacer(Modifier.height(8.dp))
        EditableList(
            title = "Клиенты и проекты",
            hint = "Помогают распознаванию и попадают в отчёты",
            values = clients,
            onChange = { app.appScope.launch { app.zasechkaStore.setClients(it) } },
        )

        Spacer(Modifier.height(12.dp))
        Text("Google Sheets", style = MaterialTheme.typography.titleSmall)
        var url by remember(webhook) { mutableStateOf(webhook) }
        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("URL веб-приложения Apps Script") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = {
                app.appScope.launch {
                    app.settings.setZWebhook(url)
                    Feedback.toast(app, "Сохранено")
                }
            }) { Text("Сохранить") }
            OutlinedButton(onClick = {
                app.appScope.launch {
                    val result = app.zasechkaSync.syncNow()
                    result.onSuccess { n ->
                        Feedback.toast(app, if (n > 0) "Отправлено строк: $n" else "Всё уже в таблице")
                    }.onFailure { e ->
                        Feedback.toast(app, "Не удалось: ${e.message}")
                    }
                }
            }) { Text("Синхронизировать") }
        }
        val pending = entries.count { !it.open && !it.synced }
        Text(
            buildString {
                append("Скрипт и настройка за 5 минут: docs/zasechka-sheets.md в репозитории.")
                if (syncStatus.isNotBlank()) append("\nПоследняя отправка: $syncStatus.")
                if (pending > 0) append("\nЖдут отправки: $pending.")
            },
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            app.appScope.launch {
                context.startActivity(app.zasechkaStore.shareCsvIntent())
            }
        }) { Text("Выгрузить CSV") }
    }
}

@Composable
private fun EditableList(
    title: String,
    hint: String,
    values: List<String>,
    onChange: (List<String>) -> Unit,
) {
    Text(title, style = MaterialTheme.typography.titleSmall)
    Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    for (value in values) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { onChange(values - value) }) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "удалить",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    var newValue by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newValue,
            onValueChange = { newValue = it },
            label = { Text("Добавить") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val v = newValue.trim()
                if (v.isNotEmpty()) {
                    onChange(values + v)
                    newValue = ""
                }
            },
            enabled = newValue.isNotBlank(),
        ) { Text("OK") }
    }
}

// ---------------------------------------------------------------------------
// Entry editor: every field down to the minutes.
// ---------------------------------------------------------------------------

@Composable
private fun EditEntryDialog(
    entry: ZasechkaStore.Entry,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (ZasechkaStore.Entry) -> Unit,
    onDelete: () -> Unit,
) {
    var title by remember { mutableStateOf(entry.title) }
    var category by remember { mutableStateOf(entry.category) }
    var client by remember { mutableStateOf(entry.client) }
    var startText by remember { mutableStateOf(fmtTime(entry.start)) }
    var endText by remember { mutableStateOf(if (entry.open) "" else fmtTime(entry.end)) }
    var useful by remember { mutableStateOf(entry.useful) }
    var categoryMenu by remember { mutableStateOf(false) }

    val entryDayStart = remember(entry.id) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = entry.start
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Запись") },
        text = {
            Column {
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("Дело") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Box {
                    OutlinedButton(onClick = { categoryMenu = true }) {
                        Text("Категория: " + category.ifBlank { "нет" })
                    }
                    DropdownMenu(expanded = categoryMenu, onDismissRequest = { categoryMenu = false }) {
                        DropdownMenuItem(
                            text = { Text("без категории") },
                            onClick = { category = ""; categoryMenu = false },
                        )
                        for (c in categories) {
                            DropdownMenuItem(text = { Text(c) }, onClick = { category = c; categoryMenu = false })
                        }
                    }
                }
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = client,
                    onValueChange = { client = it },
                    label = { Text("Клиент/проект") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        value = startText,
                        onValueChange = { startText = it },
                        label = { Text("Начало") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                    OutlinedTextField(
                        value = endText,
                        onValueChange = { endText = it },
                        label = { Text(if (entry.open) "Конец (пусто = идёт)" else "Конец") },
                        singleLine = true,
                        modifier = Modifier.weight(1f),
                    )
                }
                Spacer(Modifier.height(8.dp))
                Text(if (useful > 0) "Полезность: $useful из 5" else "Полезность: не оценена")
                Slider(
                    value = useful.toFloat(),
                    onValueChange = { useful = it.roundToInt() },
                    valueRange = 0f..5f,
                    steps = 4,
                )
                if (entry.raw.isNotBlank() && entry.raw != entry.title) {
                    Text(
                        "Надиктовано: «${entry.raw.take(200)}»",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = onDelete) {
                    Text("Удалить запись", color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                val newStart = parseTimeOfDay(entryDayStart, startText) ?: entry.start
                val newEnd = when {
                    endText.isBlank() -> if (entry.open) 0L else entry.end
                    else -> parseTimeOfDay(entryDayStart, endText)
                        ?: (if (entry.open) 0L else entry.end)
                }
                onSave(
                    entry.copy(
                        title = title.trim(),
                        category = category.trim(),
                        client = client.trim(),
                        start = newStart,
                        end = if (newEnd > 0) newEnd.coerceAtLeast(newStart) else newEnd,
                        useful = useful.coerceIn(0, 5),
                        source = "edit",
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
