package ru.zf.pravka

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.Send
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
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
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
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
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.PhoneSweeper
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.phoneDayKey
import ru.zf.pravka.ui.Feedback

// Вкладка «Засечка»: the owner's day as a ribbon of entries, the numbers he
// loves, and the knobs. Everything the buttons capture lands here for review
// and fixing - the tab is deliberately editable down to minutes, because the
// voice pipeline is fast but not sacred.

// Owner's rainbow: every category sits on the spectrum by how well the hour
// is spent. Work and study burn red, sport is orange, people are the warm
// greens, recovery and logistics cool off through cyan and blue, and pure
// leisure lands on violet. The hue is fixed per name; a custom category the
// owner adds later gets a stable hash spot on the same rainbow. Light theme
// dims the value (inks on paper), dark theme runs full brightness (markers).
private val CATEGORY_HUES = mapOf(
    "работа: привлечение" to 0f,
    "работа: текущая" to 8f,
    "работа: планирование" to 16f,
    "работа: звонки" to 24f,
    "чтение" to 32f,
    "систематизация" to 40f,
    "спорт: силовая" to 48f,
    "спорт: бег" to 56f,
    "спорт: вело" to 64f,
    "спорт: прочее" to 72f,
    "семья" to 88f,
    "секс: с марианной" to 100f,
    "социальное: внешнее" to 118f,
    "звонки" to 135f,
    "сон" to 155f,
    "еда" to 170f,
    "передвижение: пешком" to 190f,
    "передвижение: вело" to 205f,
    "передвижение: транспорт" to 220f,
    "быт" to 235f,
    "отдых" to 262f,
    "секс: соло" to 278f,
    // Legacy v1/v2 name still alive on the device - keep it with "соло".
    "секс" to 278f,
    // The very bottom of the spectrum: time spent on nothing at all.
    // ("прокрастинация" lived for one build before the rename.)
    "потери" to 292f,
    "прокрастинация" to 292f,
)

/** Position on the effectiveness rainbow, 0 (red) .. 280 (violet). */
private fun categoryHue(name: String): Float {
    val key = name.trim().lowercase()
    return CATEGORY_HUES[key] ?: (abs(key.hashCode()) % 281).toFloat()
}

@Composable
private fun categoryColor(name: String): Color {
    val dark = isSystemInDarkTheme()
    if (name.isBlank()) return if (dark) Color(0xFF9A9184) else Color(0xFF8A8172)
    // Softened rainbow (owner: "чуть-чуть помягче") - same hues, less punch.
    return if (dark) Color.hsv(categoryHue(name), 0.55f, 0.94f)
    else Color.hsv(categoryHue(name), 0.68f, 0.64f)
}

private fun capFirst(s: String): String = s.replaceFirstChar { it.uppercase() }

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

// An activity the phone's automation sliced up (call spliced in, YouTube ate a
// piece, the owner re-said the same thing) reads back as ONE unit: fragments
// share a signature, interruptions are the auto entries that filled the gaps
// between them. A unit with a single fragment is just a plain ribbon row.
private data class DayUnit(
    val fragments: List<ZasechkaStore.Entry>,
    val interruptions: List<ZasechkaStore.Entry>,
) {
    val chain: Boolean get() = fragments.size > 1 || interruptions.isNotEmpty()
    val start: Long get() = fragments.first().start
    val open: Boolean get() = fragments.last().open
    fun endMs(now: Long): Long = fragments.last().let { if (it.open) now else it.end }
    /** Minutes of the activity's own fragments - interruptions not counted. */
    fun totalMin(now: Long): Long = fragments.sumOf { it.durationMin(now) }
}

private fun entrySig(e: ZasechkaStore.Entry): String =
    "${e.title.trim().lowercase()}|${e.category.trim().lowercase()}|${e.client.trim().lowercase()}"

/**
 * Folds the day's entries (ascending) into units. Only closed auto entries may
 * sit between two fragments of the same activity - a manual entry in between
 * means the owner really switched, and that breaks the chain. Buffered autos
 * that are never followed by a resume stay ordinary standalone rows.
 */
private fun buildDayUnits(asc: List<ZasechkaStore.Entry>): List<DayUnit> {
    val units = ArrayList<DayUnit>()
    var fragments = ArrayList<ZasechkaStore.Entry>()
    var interruptions = ArrayList<ZasechkaStore.Entry>()
    var pending = ArrayList<ZasechkaStore.Entry>()

    fun flush() {
        if (fragments.isNotEmpty()) units.add(DayUnit(fragments, interruptions))
        for (p in pending) units.add(DayUnit(listOf(p), emptyList()))
        fragments = ArrayList(); interruptions = ArrayList(); pending = ArrayList()
    }

    for (e in asc) {
        val lastFrag = fragments.lastOrNull()
        if (lastFrag == null) {
            fragments.add(e)
            continue
        }
        when {
            entrySig(e) == entrySig(lastFrag) && !lastFrag.open -> {
                // Resume only counts if the buffered interruptions really cover
                // the pause (± 5 min of splice slack) - otherwise the owner was
                // simply away and the pieces stay separate.
                val covered = pending.sumOf {
                    (minOf(it.end, e.start) - maxOf(it.start, lastFrag.end)).coerceAtLeast(0L)
                }
                if (e.start - lastFrag.end - covered <= 5 * 60_000L) {
                    interruptions.addAll(pending)
                    pending = ArrayList()
                    fragments.add(e)
                } else {
                    flush()
                    fragments.add(e)
                }
            }
            e.source == "auto" && !e.open && !lastFrag.open && pending.size < 6 ->
                pending.add(e)
            else -> {
                flush()
                fragments.add(e)
            }
        }
    }
    flush()
    return units
}

@Composable
internal fun ZasechkaTab(app: PravkaApp) {
    val context = LocalContext.current
    val store = app.zasechkaStore
    val entries by store.entriesFlow.collectAsState()
    val categories by store.categoriesFlow.collectAsState()
    val categoryNames = remember(categories) { categories.map { it.name } }
    val clients by store.clientsFlow.collectAsState()
    val syncStatus by app.zasechkaSync.statusFlow.collectAsState()
    LaunchedEffect(Unit) { store.all() }  // first read triggers the load

    var dayOffset by remember { mutableStateOf(0) }
    var weekMode by remember { mutableStateOf(false) }
    var editing by remember { mutableStateOf<ZasechkaStore.Entry?>(null) }
    // Chain edit: the whole sliced-up activity at once, all fragments.
    var editingChain by remember { mutableStateOf<List<ZasechkaStore.Entry>?>(null) }
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
        // Tie-break: on an equal start the owner's entry goes before the auto
        // fact - a zero-length head fragment must precede the interruption it
        // was split around, or the chain does not assemble.
        entries.filter { it.start in rangeStart until dayEnd }
            .sortedWith(compareBy({ it.start }, { it.source == "auto" }))
    }
    // Day view groups the ribbon into units (chains + singles), newest first.
    val dayUnits = remember(rangeEntries, weekMode) {
        if (weekMode) emptyList() else buildDayUnits(rangeEntries).asReversed()
    }

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
                outcome.action == "edit" ->
                    Feedback.toast(app, "✏️ «${outcome.previousTitle}» → «${outcome.entry.title}»")
                outcome.action == "delete" ->
                    Feedback.toast(app, "🗑 «${outcome.entry.title}» удалена")
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

        // ---- quick add: one dense row, voice or typed ----
        if (!weekMode && dayOffset == 0) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = draft,
                        onValueChange = { draft = it },
                        modifier = Modifier.weight(1f),
                        label = { Text(if (processing) "Разбираю…" else "Чем занят?") },
                        singleLine = true,
                        enabled = !processing,
                    )
                    IconButton(onClick = { submitText() }, enabled = !processing && draft.isNotBlank()) {
                        Icon(Icons.Filled.Send, contentDescription = "записать")
                    }
                    IconButton(onClick = {
                        val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                        if (service == null) Feedback.toast(context, context.getString(R.string.toast_no_service))
                        else service.onZasechkaTap()
                    }) { Text("🎙", fontSize = 18.sp) }
                }
                Spacer(Modifier.height(6.dp))
            }
        }

        // ---- the day's history first (owner's layout), newest on top. An
        // uninterrupted entry is one dense table line; a sliced-up activity is
        // ONE block: a tall line for the whole span, the net Σ beside it, the
        // interruptions as parallel indented rows (owner: "а то кусками") ----
        if (!weekMode) {
            itemsIndexed(dayUnits, key = { _, u -> u.fragments.first().id }) { index, unit ->
                val head = unit.fragments.first()
                val doStop: () -> Unit = {
                    app.appScope.launch { app.zasechkaEngine.closeOpen() }
                }
                if (unit.chain) {
                    ChainBlock(
                        unit = unit,
                        now = now,
                        onStop = if (unit.open) doStop else null,
                        onEdit = { editingChain = unit.fragments },
                        onDelete = {
                            app.appScope.launch { unit.fragments.forEach { store.delete(it.id) } }
                        },
                        onEditInterruption = { editing = it },
                    )
                } else {
                    EntryRow(
                        entry = head,
                        now = now,
                        onStop = if (head.open) doStop else null,
                        onEdit = { editing = head },
                        onDelete = { app.appScope.launch { store.delete(head.id) } },
                    )
                }
                // A visible hole in the ribbon is the whole point of the app -
                // drawn between this unit and the chronologically older one.
                if (index < dayUnits.size - 1) {
                    val older = dayUnits[index + 1]
                    if (!older.open) {
                        val gapMin = (unit.start - older.endMs(now)) / 60_000L
                        if (gapMin >= 5) {
                            Text(
                                "···  ${fmtDur(gapMin)} без записи",
                                style = MaterialTheme.typography.bodySmall,
                                // Deliberately faint: the holes must not
                                // shout louder than the entries.
                                color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f),
                                modifier = Modifier.padding(start = 56.dp, top = 1.dp, bottom = 1.dp),
                            )
                        }
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
            item { Spacer(Modifier.height(10.dp)) }
        }

        // ---- totals: EVERY category, laid out in rainbow order (red work at
        // the top, violet leisure at the bottom) - the owner reads the shape
        // of the day at a glance and aims for the inverted triangle: long red
        // bars up top, short violet ones below. Zero rows stay visible but dim.
        item {
            val minutesByCat = HashMap<String, Long>()
            for (e in rangeEntries) {
                val k = e.category.trim().lowercase()
                minutesByCat[k] = (minutesByCat[k] ?: 0L) + e.durationMin(now)
            }
            val names = (categoryNames + rangeEntries.map { it.category.trim() }.filter { it.isNotBlank() })
                .distinctBy { it.lowercase() }
                .sortedBy { categoryHue(it) }
            val rows = names.map { it to (minutesByCat[it.lowercase()] ?: 0L) } +
                listOfNotNull(minutesByCat[""]?.takeIf { it > 0 }?.let { "" to it })
            val total = minutesByCat.values.sum()
            // Day pomodoro counters live in the service's internal prefs.
            val pomoCount = remember(now, dayStart, weekMode) {
                val prefs = context.getSharedPreferences("pravka_internal", android.content.Context.MODE_PRIVATE)
                val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
                (0 until if (weekMode) 7 else 1).sumOf {
                    prefs.getInt("z_pomo_n_" + fmt.format(Date(dayStart - it * 86_400_000L)), 0)
                }
            }
            Text(
                (if (weekMode) "За неделю" else "За день") + ": ${fmtDur(total)} · записей: ${rangeEntries.size}" +
                    (if (pomoCount > 0) " · 🍅 $pomoCount" else ""),
                style = MaterialTheme.typography.titleSmall,
                modifier = Modifier.padding(bottom = 6.dp),
            )
            val max = rows.maxOfOrNull { it.second } ?: 0L
            for ((category, minutes) in rows) {
                val rowAlpha = if (minutes > 0) 1f else 0.4f
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.padding(vertical = 2.dp),
                ) {
                    Text(
                        category.ifBlank { "без категории" },
                        style = MaterialTheme.typography.bodySmall,
                        color = categoryColor(category).copy(alpha = rowAlpha),
                        fontWeight = if (minutes > 0) FontWeight.SemiBold else FontWeight.Normal,
                        modifier = Modifier.width(130.dp),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Box(
                        Modifier
                            .weight(1f)
                            .height(10.dp)
                            .background(
                                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = rowAlpha * 0.9f),
                                RoundedCornerShape(5.dp),
                            ),
                    ) {
                        if (minutes > 0 && max > 0) {
                            val fraction = minutes.toFloat() / max
                            Box(
                                Modifier
                                    .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                                    .height(10.dp)
                                    .background(categoryColor(category), RoundedCornerShape(5.dp)),
                            )
                        }
                    }
                    Text(
                        if (minutes > 0) fmtDur(minutes) else "—",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurface.copy(alpha = rowAlpha),
                        modifier = Modifier.padding(start = 8.dp).width(64.dp),
                    )
                }
            }
            if (rows.isEmpty()) {
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

        // ---- the phone layer: separate from the ribbon by design ----
        item {
            PhoneSection(app, dayStart, weekMode, now)
            Spacer(Modifier.height(12.dp))
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
            categories = categoryNames,
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

    // Chain edit: the dialog shows the activity as one whole (full span, first
    // fragment's raw); saving fans the change out to EVERY fragment. Start
    // moves the first fragment, end moves the last, the middles keep their
    // splice times - only the words change there.
    editingChain?.let { chain ->
        val first = chain.first()
        val last = chain.last()
        EditEntryDialog(
            entry = first.copy(end = if (last.open) 0L else last.end),
            categories = categoryNames,
            onDismiss = { editingChain = null },
            onSave = { updated ->
                editingChain = null
                app.appScope.launch {
                    for (f in chain) {
                        var nf = f.copy(
                            title = updated.title,
                            category = updated.category,
                            client = updated.client,
                            useful = updated.useful,
                            source = "edit",
                        )
                        if (f.id == first.id) {
                            // An open fragment has end=0 - never clamp against it.
                            nf = nf.copy(
                                start = if (f.open) updated.start else updated.start.coerceAtMost(f.end),
                            )
                        }
                        if (f.id == last.id) {
                            nf = nf.copy(
                                end = if (updated.end > 0) updated.end.coerceAtLeast(nf.start) else updated.end,
                            )
                        }
                        store.update(nf)
                    }
                    app.zasechkaSync.kickSoon(app.appScope)
                }
            },
            onDelete = {
                editingChain = null
                app.appScope.launch { chain.forEach { store.delete(it.id) } }
            },
        )
    }
}

// ---------------------------------------------------------------------------
// Ribbon rows: the plain one-line entry and the chain block (an activity the
// automation sliced up, shown whole again).
// ---------------------------------------------------------------------------

@Composable
private fun EntryRow(
    entry: ZasechkaStore.Entry,
    now: Long,
    onStop: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (entry.open) Modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    RoundedCornerShape(10.dp),
                ) else Modifier
            )
            .clickable(onClick = onEdit)
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.width(46.dp)) {
            Text(
                fmtTime(entry.start),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (entry.open) "…" else fmtTime(entry.end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .width(3.dp)
                .height(30.dp)
                .background(categoryColor(entry.category), RoundedCornerShape(2.dp)),
        )
        // A table, not a ragged line (owner's spec): category and
        // duration sit in fixed columns, the title takes the rest.
        Text(
            entry.category.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.SemiBold,
            color = categoryColor(entry.category),
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.width(104.dp).padding(start = 6.dp),
        )
        Text(
            fmtDur(entry.durationMin(now)),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            modifier = Modifier.width(58.dp).padding(start = 4.dp),
        )
        val title = buildString {
            append(capFirst(entry.title.ifBlank { entry.raw.take(60) }))
            if (entry.client.isNotBlank()) append(" · ${entry.client}")
            if (entry.useful > 0) append(" ★${entry.useful}")
            if (entry.pomodoros > 0) append(" 🍅×${entry.pomodoros}")
        }
        Text(
            title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.weight(1f).padding(start = 6.dp),
        )
        if (onStop != null) {
            IconButton(onClick = onStop, modifier = Modifier.size(30.dp)) {
                Box(
                    Modifier
                        .size(11.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)),
                )
            }
        }
        IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.Edit,
                contentDescription = "править",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
            Icon(
                Icons.Filled.Clear,
                contentDescription = "удалить",
                modifier = Modifier.size(15.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

// The whole interrupted activity as one block: the time column shows the full
// span, one tall line runs beside it, the header line carries the NET Σ
// (interruptions excluded), and each interruption is its own small parallel
// row inside - tappable for its own edit. Header edit/✕ act on ALL fragments.
@Composable
private fun ChainBlock(
    unit: DayUnit,
    now: Long,
    onStop: (() -> Unit)?,
    onEdit: () -> Unit,
    onDelete: () -> Unit,
    onEditInterruption: (ZasechkaStore.Entry) -> Unit,
) {
    val head = unit.fragments.first()
    val last = unit.fragments.last()
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (unit.open) Modifier.background(
                    MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.55f),
                    RoundedCornerShape(10.dp),
                ) else Modifier
            )
            .padding(vertical = 2.dp),
    ) {
        Column(Modifier.width(46.dp)) {
            Text(
                fmtTime(head.start),
                style = MaterialTheme.typography.bodySmall,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                if (last.open) "…" else fmtTime(last.end),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Box(
            Modifier
                .width(3.dp)
                .fillMaxHeight()
                .background(categoryColor(head.category), RoundedCornerShape(2.dp)),
        )
        Column(Modifier.weight(1f)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().clickable(onClick = onEdit),
            ) {
                Text(
                    head.category.ifBlank { "—" },
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    color = categoryColor(head.category),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.width(104.dp).padding(start = 6.dp),
                )
                // The number he otherwise sums by hand: net time of the
                // activity across all its fragments. Bold = it's a total.
                Text(
                    fmtDur(unit.totalMin(now)),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = FontWeight.SemiBold,
                    maxLines = 1,
                    modifier = Modifier.width(58.dp).padding(start = 4.dp),
                )
                val pomos = unit.fragments.sumOf { it.pomodoros }
                val title = buildString {
                    append(capFirst(head.title.ifBlank { head.raw.take(60) }))
                    if (head.client.isNotBlank()) append(" · ${head.client}")
                    if (head.useful > 0) append(" ★${head.useful}")
                    if (pomos > 0) append(" 🍅×$pomos")
                }
                Text(
                    title,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f).padding(start = 6.dp),
                )
                if (onStop != null) {
                    IconButton(onClick = onStop, modifier = Modifier.size(30.dp)) {
                        Box(
                            Modifier
                                .size(11.dp)
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                IconButton(onClick = onEdit, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Edit,
                        contentDescription = "править всё дело",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                IconButton(onClick = onDelete, modifier = Modifier.size(30.dp)) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "удалить всё дело",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            for (br in unit.interruptions) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onEditInterruption(br) }
                        .padding(vertical = 1.dp),
                ) {
                    Text(
                        "${fmtTime(br.start)}–${fmtTime(br.end)}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.width(82.dp).padding(start = 6.dp),
                    )
                    Box(
                        Modifier
                            .width(2.dp)
                            .height(14.dp)
                            .background(categoryColor(br.category), RoundedCornerShape(1.dp)),
                    )
                    Text(
                        br.category.ifBlank { "—" },
                        style = MaterialTheme.typography.bodySmall,
                        color = categoryColor(br.category),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.width(90.dp).padding(start = 4.dp),
                    )
                    Text(
                        fmtDur(br.durationMin(now)),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        modifier = Modifier.width(50.dp).padding(start = 4.dp),
                    )
                    Text(
                        capFirst(br.title.ifBlank { br.raw.take(60) }),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f).padding(start = 6.dp),
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Settings block: button, reminders, dictionaries of categories/clients,
// the Sheets webhook and the CSV export.
// ---------------------------------------------------------------------------

@Composable
private fun ZasechkaConfig(
    app: PravkaApp,
    categories: List<ZasechkaStore.Category>,
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
        CategoriesEditor(
            categories = categories,
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
        Text("intervals.icu", style = MaterialTheme.typography.titleSmall)
        Text(
            "Тренировки за последние двое суток сами встают в ленту (бег, вело, силовая, ходьба), " +
                "а Garmin-длительность сна дописывается к записи «сон». Ключ: intervals.icu → " +
                "Settings → Developer Settings → API Key.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val icuAthlete by app.settings.icuAthleteFlow.collectAsState(initial = "")
        val icuKey by app.settings.icuKeyFlow.collectAsState(initial = "")
        var athleteField by remember(icuAthlete) { mutableStateOf(icuAthlete) }
        var keyField by remember(icuKey) { mutableStateOf(icuKey) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(
                value = athleteField,
                onValueChange = { athleteField = it },
                label = { Text("Athlete ID (i…)") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
            OutlinedTextField(
                value = keyField,
                onValueChange = { keyField = it },
                label = { Text("API Key") },
                singleLine = true,
                modifier = Modifier.weight(1f),
            )
        }
        TextButton(onClick = {
            app.appScope.launch {
                app.settings.setIcuAthlete(athleteField)
                app.settings.setIcuKey(keyField)
                Feedback.toast(app, "Сохранено — тренировки подтянутся в ближайший свип")
                app.icuSweeper.sweep(force = true)
            }
        }) { Text("Сохранить и проверить") }

        Spacer(Modifier.height(12.dp))
        Text("Notion", style = MaterialTheme.typography.titleSmall)
        Text(
            "Зеркало записей в базу Notion (по одной странице на запись, правки обновляют " +
                "страницу). Токен: notion.so/my-integrations → создать интеграцию → Internal " +
                "Integration Secret; затем в базе ••• → Connections → добавить интеграцию.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        val notionToken by app.settings.notionTokenFlow.collectAsState(initial = "")
        val notionDb by app.settings.notionDbFlow.collectAsState(initial = "")
        var tokenField by remember(notionToken) { mutableStateOf(notionToken) }
        var dbField by remember(notionDb) { mutableStateOf(notionDb) }
        OutlinedTextField(
            value = tokenField,
            onValueChange = { tokenField = it },
            label = { Text("Integration Secret (ntn_… / secret_…)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        OutlinedTextField(
            value = dbField,
            onValueChange = { dbField = it },
            label = { Text("ID базы (32 знака из ссылки)") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
        val notionStatus by app.notionSync.statusFlow.collectAsState()
        TextButton(onClick = {
            app.appScope.launch {
                app.settings.setNotionToken(tokenField)
                app.settings.setNotionDb(dbField.replace("-", "").trim())
                val result = app.notionSync.syncNow()
                result.onSuccess { n ->
                    Feedback.toast(app, if (n > 0) "В Notion отправлено: $n" else "Всё уже в Notion")
                }.onFailure { e ->
                    Feedback.toast(app, "Notion: ${e.message}")
                }
            }
        }) { Text("Сохранить и проверить") }
        if (notionStatus.isNotBlank()) {
            Text(
                "Последняя отправка в Notion: $notionStatus",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }

        Spacer(Modifier.height(12.dp))
        OutlinedButton(onClick = {
            app.appScope.launch {
                context.startActivity(app.zasechkaStore.shareCsvIntent())
            }
        }) { Text("Выгрузить CSV") }
    }
}

@Composable
private fun CategoriesEditor(
    categories: List<ZasechkaStore.Category>,
    onChange: (List<ZasechkaStore.Category>) -> Unit,
) {
    var editing by remember { mutableStateOf<ZasechkaStore.Category?>(null) }
    Text("Категории", style = MaterialTheme.typography.titleSmall)
    Text(
        "Сонет выбирает строго из этого списка; пояснение — подсказка ему, что сюда относится. Тап — править.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    for (category in categories) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = category },
        ) {
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Text(category.name, style = MaterialTheme.typography.bodyMedium)
                if (category.hint.isNotBlank()) {
                    Text(
                        category.hint,
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
            }
            IconButton(onClick = { onChange(categories.filter { it.name != category.name }) }) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "удалить",
                    modifier = Modifier.size(16.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }
    var newName by remember { mutableStateOf("") }
    Row(verticalAlignment = Alignment.CenterVertically) {
        OutlinedTextField(
            value = newName,
            onValueChange = { newName = it },
            label = { Text("Добавить категорию") },
            singleLine = true,
            modifier = Modifier.weight(1f),
        )
        TextButton(
            onClick = {
                val v = newName.trim()
                if (v.isNotEmpty()) {
                    onChange(categories + ZasechkaStore.Category(v, ""))
                    newName = ""
                }
            },
            enabled = newName.isNotBlank(),
        ) { Text("OK") }
    }
    editing?.let { original ->
        var name by remember(original) { mutableStateOf(original.name) }
        var hint by remember(original) { mutableStateOf(original.hint) }
        AlertDialog(
            onDismissRequest = { editing = null },
            title = { Text("Категория") },
            text = {
                Column {
                    OutlinedTextField(
                        value = name,
                        onValueChange = { name = it },
                        label = { Text("Название") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = hint,
                        onValueChange = { hint = it },
                        label = { Text("Что сюда относится (подсказка Сонету)") },
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            },
            confirmButton = {
                Button(onClick = {
                    editing = null
                    val trimmed = name.trim()
                    if (trimmed.isNotEmpty()) {
                        onChange(
                            categories.map {
                                if (it.name == original.name) ZasechkaStore.Category(trimmed, hint.trim())
                                else it
                            }
                        )
                    }
                }) { Text("Сохранить") }
            },
            dismissButton = { TextButton(onClick = { editing = null }) { Text("Отмена") } },
        )
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
// The phone layer: screen time, pickups, отвлечения and per-app minutes.
// A separate ledger from the ribbon (owner's design): most app time is
// tooling inside a bigger activity. Tapping an app row promotes it to an
// "attention eater" - its sessions then auto-claim ribbon time.
// ---------------------------------------------------------------------------

// WebView time is browsing rendered through a helper package - fold it into
// Chrome so the list shows one honest "браузер" row.
private val PKG_ALIAS = mapOf(
    "com.google.android.webview" to "com.android.chrome",
    "com.android.webview" to "com.android.chrome",
)
// Instant names for the frequent flyers even before the label resolver runs
// (QUERY_ALL_PACKAGES makes the resolver work for the rest).
private val FRIENDLY_LABELS = mapOf(
    "com.android.chrome" to "Chrome",
    "us.zoom.videomeetings" to "Zoom",
    "org.telegram.messenger" to "Telegram",
    "org.telegram.messenger.web" to "Telegram",
    "com.google.android.youtube" to "YouTube",
    "com.google.android.apps.docs.editors.docs" to "Google Docs",
    "com.google.android.apps.docs" to "Google Drive",
    "com.adobe.reader" to "Adobe Reader",
)

// Some packages (work profile, hidden components) refuse a label - the last
// TWO segments at least say whose package it is ("zoom.videomeetings").
private fun appLabelOf(labels: Map<String, String>, pkg: String): String =
    labels[pkg] ?: FRIENDLY_LABELS[pkg] ?: pkg.split('.').takeLast(2).joinToString(".")

// Furniture: never phone use - launchers, system UI, the docked-hub
// screensaver, the dialer (call time is a ribbon entry). Old stored data may
// still carry them; fresh sweeps exclude most at the source.
private fun isFurniturePkg(pkg: String): Boolean =
    pkg.contains("launcher", ignoreCase = true) ||
        pkg.contains("systemui", ignoreCase = true) ||
        pkg.contains("hubui", ignoreCase = true) ||
        pkg.contains("dream", ignoreCase = true) ||
        pkg.contains("dialer", ignoreCase = true) ||
        pkg.contains("incallui", ignoreCase = true) ||
        pkg.contains("telecom", ignoreCase = true)

// Non-distractions in "отвлекали": furniture plus the music player (owner's
// call: skipping a track is not a distraction) - but music stays in app time.
private fun isNoisePkg(pkg: String): Boolean =
    isFurniturePkg(pkg) || pkg.contains("music", ignoreCase = true)

private fun aggregatePhoneDays(
    days: Map<String, PhoneStore.Day>,
    keys: List<String>,
): PhoneStore.Day {
    var screenMs = 0L
    var pickups = 0
    var glances = 0
    val apps = HashMap<String, Long>()
    val appSessions = HashMap<String, Int>()
    val glanceApps = HashMap<String, Int>()
    val sites = HashMap<String, Long>()
    for (key in keys) {
        val d = days[key] ?: continue
        screenMs += d.screenMs
        pickups += d.pickups
        glances += d.glances
        for ((raw, v) in d.apps) {
            val p = PKG_ALIAS[raw] ?: raw
            apps[p] = (apps[p] ?: 0L) + v
        }
        for ((raw, v) in d.appSessions) {
            val p = PKG_ALIAS[raw] ?: raw
            appSessions[p] = (appSessions[p] ?: 0) + v
        }
        for ((raw, v) in d.glanceApps) {
            val p = PKG_ALIAS[raw] ?: raw
            glanceApps[p] = (glanceApps[p] ?: 0) + v
        }
        for ((s, v) in d.sites) sites[s] = (sites[s] ?: 0L) + v
    }
    return PhoneStore.Day(screenMs, pickups, glances, apps, appSessions, glanceApps, sites)
}

@Composable
private fun PhoneSection(app: PravkaApp, dayStart: Long, weekMode: Boolean, now: Long) {
    val context = LocalContext.current
    val days by app.phoneStore.daysFlow.collectAsState()
    val immersive by app.phoneStore.immersiveFlow.collectAsState()
    val labels by app.phoneStore.labelsFlow.collectAsState()
    val categoryEntries by app.zasechkaStore.categoriesFlow.collectAsState()
    val categories = remember(categoryEntries) { categoryEntries.map { it.name } }
    var usageGranted by remember { mutableStateOf(PhoneSweeper.hasUsageAccess(context)) }
    var callGranted by remember { mutableStateOf(PhoneSweeper.hasCallLogAccess(context)) }
    var editingApp by remember { mutableStateOf<String?>(null) }

    var expanded by remember { mutableStateOf(false) }

    // Re-check permissions and freshen the aggregates while the tab is open
    // (the `now` clock ticks every 30 seconds).
    LaunchedEffect(now, dayStart, weekMode) {
        usageGranted = PhoneSweeper.hasUsageAccess(context)
        callGranted = PhoneSweeper.hasCallLogAccess(context)
        if (usageGranted) app.phoneSweeper.sweep()
    }

    val keys =
        if (weekMode) (0..6).map { phoneDayKey(dayStart - it * 86_400_000L) }
        else listOf(phoneDayKey(dayStart))
    val agg = aggregatePhoneDays(days, keys)

    // Collapsed by default (owner's ask) - the headline carries the numbers.
    TextButton(onClick = { expanded = !expanded }, contentPadding = PaddingValues(0.dp)) {
        Text(
            (if (expanded) "▾ Телефон" else "▸ Телефон") +
                when {
                    !usageGranted -> " · нет доступа"
                    agg.screenMs > 0 ->
                        " · ${fmtDur(agg.screenMs / 60_000)} · ↑${agg.pickups} · отвл. ${agg.glances}"
                    else -> ""
                },
        )
    }
    if (!expanded) return
    if (!usageGranted) {
        Text(
            "Дай Правке доступ к статистике использования — появятся время в приложениях, " +
                "подъёмы телефона и счётчик отвлечений. Пожиратели внимания (YouTube) и звонки " +
                "будут сами вставать в ленту.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        OutlinedButton(onClick = {
            runCatching {
                context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                )
            }
        }) { Text("Дать доступ к статистике") }
        return
    }

    Text(
        "Отвлечение = взял телефон и убрал быстрее чем за 2 минуты",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    val topApps = agg.apps.entries
        .filter { !isFurniturePkg(it.key) }
        .sortedByDescending { it.value }
        .take(8)
    val maxMs = topApps.firstOrNull()?.value ?: 0L
    for ((pkg, ms) in topApps) {
        val label = appLabelOf(labels, pkg)
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editingApp = pkg }
                .padding(vertical = 2.dp),
        ) {
            Text(
                (if (immersive.containsKey(pkg)) "⚡ " else "") + label,
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.width(130.dp),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Box(
                Modifier
                    .weight(1f)
                    .height(10.dp)
                    .background(MaterialTheme.colorScheme.surfaceVariant, RoundedCornerShape(5.dp)),
            ) {
                val fraction = if (maxMs > 0) ms.toFloat() / maxMs else 0f
                Box(
                    Modifier
                        .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                        .height(10.dp)
                        .background(
                            // Warm family: gold for tools, terracotta for the
                            // attention eaters.
                            if (immersive.containsKey(pkg)) Color(0xFFC2410C) else Color(0xFFD97706),
                            RoundedCornerShape(5.dp),
                        ),
                )
            }
            Text(
                fmtDur(ms / 60_000),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(start = 8.dp).width(64.dp),
            )
        }
    }
    if (topApps.isEmpty()) {
        Text(
            "Данных пока нет — они появятся в течение нескольких минут.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text(
            "Тап по приложению — сделать «пожирателем внимания» (авто-запись в ленту).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }

    // Chrome per-site rows removed with the omnibox poller (owner's call:
    // the fold black-screens correlated with it). Old site data stays in
    // phone.json but is no longer shown or collected.

    val topGlance = agg.glanceApps.entries
        .filter { !isNoisePkg(it.key) }
        .sortedByDescending { it.value }
        .take(3)
    if (topGlance.isNotEmpty()) {
        Text(
            "Чаще всего отвлекали: " + topGlance.joinToString(", ") {
                "${appLabelOf(labels, it.key)} ×${it.value}"
            },
            style = MaterialTheme.typography.bodySmall,
            modifier = Modifier.padding(top = 4.dp),
        )
    }

    // ---- calls: interruption entries that resume the paused activity ----
    val callsOn by app.settings.zCallsFlow.collectAsState(initial = true)
    val callCategory by app.settings.zCallCategoryFlow.collectAsState(initial = "Звонки")
    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> callGranted = granted }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Звонки в ленту", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Разговор ≥1 мин прерывает дело и продолжает его после",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        if (!callGranted) {
            TextButton(onClick = { callPermission.launch(Manifest.permission.READ_CALL_LOG) }) {
                Text("Разрешить")
            }
        } else {
            Switch(checked = callsOn, onCheckedChange = { v ->
                app.appScope.launch { app.settings.setZCalls(v) }
            })
        }
    }
    if (callGranted && callsOn) {
        var callCatMenu by remember { mutableStateOf(false) }
        Box {
            TextButton(onClick = { callCatMenu = true }) { Text("Категория звонков: $callCategory") }
            DropdownMenu(expanded = callCatMenu, onDismissRequest = { callCatMenu = false }) {
                for (c in (listOf("Звонки") + categories).distinct()) {
                    DropdownMenuItem(text = { Text(c) }, onClick = {
                        callCatMenu = false
                        app.appScope.launch { app.settings.setZCallCategory(c) }
                    })
                }
            }
        }
    }

    editingApp?.let { pkg ->
        ImmersiveAppDialog(
            label = appLabelOf(labels, pkg),
            currentCategory = immersive[pkg],
            categories = categories,
            onDismiss = { editingApp = null },
            onSave = { category ->
                editingApp = null
                app.appScope.launch { app.phoneStore.setImmersive(pkg, category) }
            },
        )
    }
}

@Composable
private fun ImmersiveAppDialog(
    label: String,
    currentCategory: String?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (String?) -> Unit,
) {
    var enabled by remember { mutableStateOf(currentCategory != null) }
    var category by remember { mutableStateOf(currentCategory ?: "Отдых") }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Пожиратель внимания", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Сессия в этом приложении сама прерывает текущее дело и встаёт в ленту",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    Box {
                        OutlinedButton(onClick = { menu = true }) { Text("Категория: $category") }
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            for (c in (listOf("Отдых") + categories).distinct()) {
                                DropdownMenuItem(text = { Text(c) }, onClick = { category = c; menu = false })
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onSave(if (enabled) category else null) }) { Text("Сохранить") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
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
                        // An edited robot fact stays a robot fact: it keeps
                        // living inside its block and keeps blocking its own
                        // re-sweep duplicate.
                        source = if (entry.source == "auto") "auto" else "edit",
                    )
                )
            }) { Text("Сохранить") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Отмена") }
        },
    )
}
