package ru.zf.pravka

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.material3.Checkbox
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
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.RoundRect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.platform.LocalContext
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
import ru.zf.pravka.core.PhoneDaySummary
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.PhoneSweeper
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.phoneDayKey
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.trigger.onZasechkaTap

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
    "не размечено" to 292f,
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

/** Points a span of [ms] in a category worth [worth] per hour contributes. */
private fun pointsOf(worth: Int, ms: Long): Int =
    kotlin.math.round(worth * ms.toDouble() / 3_600_000.0).toInt()

/**
 * Категория тегом-прямоугольником (макет владельца): цвет категории и её
 * же тон фоном, скруглённые углы. Вторая строка записи читается как
 * «[тег] · 33 м · +1» и влезает и в сложенный экран.
 */
@Composable
private fun CategoryTag(category: String) {
    val color = categoryColor(category)
    Text(
        category.ifBlank { "—" },
        style = MaterialTheme.typography.labelSmall,
        fontWeight = FontWeight.SemiBold,
        color = color,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(6.dp))
            .padding(horizontal = 7.dp, vertical = 2.dp),
    )
}

@Composable
private fun DotSep() {
    Text(
        "·",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.padding(horizontal = 6.dp),
    )
}

/** «+12» / «−4» / «·» - what this row did to the day's score. */
@Composable
private fun PointsChip(points: Int, bold: Boolean = false) {
    Text(
        when {
            points > 0 -> "+$points"
            points < 0 -> "$points"
            else -> "·"
        },
        style = MaterialTheme.typography.bodySmall,
        fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal,
        color = if (points == 0) MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
        else scoreColor(points.toFloat(), ROW_SCORE_SPAN),
        maxLines = 1,
        textAlign = TextAlign.Center,
        modifier = Modifier.width(38.dp).padding(start = 2.dp),
    )
}

// Шкала балла - та же радуга, что у категорий и у полоски дня: ноль зелёный,
// вниз через синий к фиолетовому, вверх через жёлтый к красному. Так строка
// сразу говорит, тянет она день вверх или вниз (владелец: «−1 зелёный,
// +20 очень красный»).
private const val ROW_SCORE_SPAN = 20f

private fun scoreColor(value: Float, span: Float): Color = when {
    value >= span * 0.75f -> Color(0xFFEF4444)
    value >= span * 0.45f -> Color(0xFFF97316)
    value >= span * 0.15f -> Color(0xFFEAB308)
    value > -span * 0.10f -> Color(0xFF22C55E)
    value > -span * 0.30f -> Color(0xFF06B6D4)
    value > -span * 0.55f -> Color(0xFF3B82F6)
    else -> Color(0xFF8B5CF6)
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
private val dayLabelFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

private fun fmtTime(ms: Long): String = timeFormat.format(Date(ms))

/** Milliseconds -> whole minutes, rounded to nearest (never truncated). */
private fun msToMin(ms: Long): Long = (ms + 30_000L) / 60_000L

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
    fun totalMin(now: Long): Long = msToMin(fragments.sumOf { it.durationMs(now) })
}

private fun entrySig(e: ZasechkaStore.Entry): String =
    "${e.title.trim().lowercase()}|${e.category.trim().lowercase()}|${e.client.trim().lowercase()}"

/**
 * Folds the day's entries (ascending) into units. Only closed auto entries may
 * sit between two fragments of the same activity - a manual entry in between
 * means the owner really switched, and that breaks the chain. Buffered autos
 * that are never followed by a resume stay ordinary standalone rows.
 */
private fun buildDayUnits(all: List<ZasechkaStore.Entry>): List<DayUnit> {
    val asc = all
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
internal fun ZasechkaTab(app: PravkaApp, onOpenSettings: () -> Unit = {}) {
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
    // Баббл 💬: комментарий к делу отдельным окном (у цепочки — к голове).
    var commenting by remember { mutableStateOf<ZasechkaStore.Entry?>(null) }
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
    // Worth per hour by category, and the day's balance - hoisted out of the
    // totals block so the bar can live right under the date (owner's layout)
    // and every ribbon row can show what it earns.
    val worthByCat = remember(categories) {
        categories.associate { it.name.trim().lowercase() to it.value }
    }
    val worthOf: (String) -> Int = { worthByCat[it.trim().lowercase()] ?: 0 }
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
                outcome.action == "none" ->
                    Feedback.toast(app, "🤷 ${outcome.say.ifBlank { "не про ленту" }}")
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
            // The score of the day sits right under its name (owner's layout).
            val rangeTo = minOf(now, dayEnd)
            val balance = rangeEntries.sumOf { e ->
                worthOf(e.category) * e.durationMsIn(rangeStart, rangeTo, now).toDouble() / 3_600_000.0
            }
            RainbowScoreBar(kotlin.math.round(balance).toInt(), weekMode)
            Spacer(Modifier.height(4.dp))
            Row(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                FilterChip(selected = !weekMode, onClick = { weekMode = false }, label = { Text("День") })
                FilterChip(selected = weekMode, onClick = { weekMode = true }, label = { Text("Неделя") })
                // Undo: a voice "удали обед" or a mistyped time is one press
                // away from coming back, with the act named on the button.
                val undoLabel by store.undoFlow.collectAsState()
                undoLabel?.let { label ->
                    TextButton(onClick = {
                        app.appScope.launch {
                            val undone = store.undoLast()
                            app.zasechkaSync.kickSoon(app.appScope)
                            Feedback.toast(app, if (undone != null) "↩︎ Отменено: $undone" else "Отменять нечего")
                        }
                    }) { Text("↩︎ $label") }
                }
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
        // ONE block: a tall line for the whole span, the net Σ beside it
        // (owner: "а то кусками") ----
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
                        worthOf = worthOf,
                        onStop = if (unit.open) doStop else null,
                        onEdit = { editingChain = unit.fragments },
                        onComment = { commenting = head },
                        onDelete = {
                            app.appScope.launch { unit.fragments.forEach { store.delete(it.id) } }
                        },
                        onEditInterruption = { editing = it },
                    )
                } else {
                    EntryRow(
                        entry = head,
                        now = now,
                        worthOf = worthOf,
                        onStop = if (head.open) doStop else null,
                        onEdit = { editing = head },
                        onComment = { commenting = head },
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
            // Summed in MILLISECONDS and rounded once: adding up per-entry
            // whole minutes is how the day used to come out short of the clock.
            val rangeTo = minOf(now, dayEnd)
            val mainEntries = rangeEntries
            val msByCat = HashMap<String, Long>()
            for (e in mainEntries) {
                val k = e.category.trim().lowercase()
                msByCat[k] = (msByCat[k] ?: 0L) + e.durationMsIn(rangeStart, rangeTo, now)
            }
            val minutesByCat = msByCat.mapValues { msToMin(it.value) }
            val names = (categoryNames + mainEntries.map { it.category.trim() }.filter { it.isNotBlank() })
                .distinctBy { it.lowercase() }
                .sortedBy { categoryHue(it) }
            val rows = names.map { it to (minutesByCat[it.lowercase()] ?: 0L) } +
                listOfNotNull(minutesByCat[""]?.takeIf { it > 0 }?.let { "" to it })
            val total = msToMin(msByCat.values.sum())
            // Day pomodoro counters live in the service's internal prefs.
            val pomoCount = remember(now, dayStart, weekMode) {
                val prefs = context.getSharedPreferences("pravka_internal", android.content.Context.MODE_PRIVATE)
                val fmt = SimpleDateFormat("yyyyMMdd", Locale.US)
                (0 until if (weekMode) 7 else 1).sumOf {
                    prefs.getInt("z_pomo_n_" + fmt.format(Date(dayStart - it * 86_400_000L)), 0)
                }
            }
            // The audit line: the ribbon must add up to the clock. Covered time
            // vs the day's elapsed span - a visible remainder means a real hole
            // (a fresh one, still inside the 45-minute «Потери» quarantine),
            // not rounding, which is now exact to the minute.
            val elapsedMs = (rangeTo - rangeStart).coerceAtLeast(0L)
            val uncoveredMin = msToMin((elapsedMs - msByCat.values.sum()).coerceAtLeast(0L))
            Text(
                (if (weekMode) "За неделю" else "За день") + ": ${fmtDur(total)} · записей: ${mainEntries.size}" +
                    (if (pomoCount > 0) " · 🍅 $pomoCount" else "") +
                    (if (uncoveredMin >= 2) " · не покрыто ${fmtDur(uncoveredMin)}" else ""),
                style = MaterialTheme.typography.titleSmall,
            )
            // Из чего сложился балл: плюс и минус по отдельности. Ноль на
            // полоске - это чаще всего не «не посчиталось», а честная ничья:
            // час потерь по −10 съедает час работы по +10, и это видно.
            var plus = 0.0
            var minus = 0.0
            for (e in mainEntries) {
                val v = worthOf(e.category) * e.durationMsIn(rangeStart, rangeTo, now) / 3_600_000.0
                if (v >= 0) plus += v else minus += v
            }
            val net = kotlin.math.round(plus + minus).toInt()
            Text(
                "Баланс ${if (net >= 0) "+$net" else "$net"} = " +
                    "+${kotlin.math.round(plus).toInt()} и ${kotlin.math.round(minus).toInt()}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            // Телефон за день — то, что пришло на смену параллельному треку.
            // Владелец: «просто давай считать каждый день, сколько на Клод,
            // телеграм, звонки, сколько на ютуб». Одной строкой, здесь же, у
            // итогов дня; подробности — в разделе «Телефон» ниже.
            val phoneDays by app.phoneStore.daysFlow.collectAsState()
            val phoneTracked by app.phoneStore.immersiveFlow.collectAsState()
            val phoneOff by app.phoneStore.offFlow.collectAsState()
            val phoneLabels by app.phoneStore.labelsFlow.collectAsState()
            val phoneLine = remember(phoneDays, phoneTracked, phoneOff, phoneLabels, dayStart, weekMode) {
                val keys =
                    if (weekMode) (0..6).map { phoneDayKey(dayStart - it * 86_400_000L) }
                    else listOf(phoneDayKey(dayStart))
                val agg = aggregatePhoneDays(phoneDays, keys)
                PhoneDaySummary.line(
                    PhoneDaySummary.of(agg, phoneTracked.filterKeys { it !in phoneOff }, phoneLabels)
                )
            }
            if (phoneLine.isNotBlank()) {
                Text(
                    "Телефон: $phoneLine",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Spacer(Modifier.height(6.dp))
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
                    // Сколько эта категория дала дню - тут и видно, кто съел
                    // балл: строка «Потери −19» объясняет ноль на полоске.
                    PointsChip(
                        pointsOf(worthOf(category), msByCat[category.trim().lowercase()] ?: 0L),
                        bold = true,
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
                    .mapValues { (_, list) ->
                        msToMin(list.sumOf { it.durationMsIn(rangeStart, minOf(now, dayEnd), now) })
                    }
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

        // Настройки режима живут в одной вкладке со всеми остальными -
        // здесь только дорога туда, одним тапом вместо трёх.
        item {
            Spacer(Modifier.height(16.dp))
            SettingsLink("Настройки Засечки", onOpenSettings)
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
                    // Поменялся один комментарий — это не правка дела: без шага
                    // отмены, без «edit» в источнике и без обучения Засечки.
                    if (onlyCommentChanged(entry, updated)) store.setComment(entry.id, updated.comment)
                    else store.update(updated)
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
        val shown = first.copy(end = if (last.open) 0L else last.end)
        EditEntryDialog(
            entry = shown,
            categories = categoryNames,
            onDismiss = { editingChain = null },
            onSave = { updated ->
                editingChain = null
                app.appScope.launch {
                    if (onlyCommentChanged(shown, updated)) {
                        store.setComment(first.id, updated.comment)
                        app.zasechkaSync.kickSoon(app.appScope)
                        return@launch
                    }
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
                            // Комментарий — как надиктовка: у головы, не у кусков.
                            nf = nf.copy(
                                start = if (f.open) updated.start else updated.start.coerceAtMost(f.end),
                                comment = updated.comment,
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

    commenting?.let { entry ->
        CommentDialog(
            entry = entry,
            onDismiss = { commenting = null },
            onSave = { text ->
                commenting = null
                app.appScope.launch {
                    store.setComment(entry.id, text)
                    app.zasechkaSync.kickSoon(app.appScope)
                }
            },
        )
    }
}

/**
 * The score of the day on a full rainbow (owner's design): the whole track is
 * the spectrum - violet on the far left, GREEN in the middle where zero sits,
 * red on the far right. The bright fill grows from the centre: right for a day
 * that paid off, left for one that sank. The colours belong to the TRACK, so
 * red only lights up when the day actually reaches it.
 */
@Composable
private fun RainbowScoreBar(balance: Int, weekMode: Boolean) {
    // Half the track is a strong day: eight hours of work at +8 plus an hour
    // of sport lands near a hundred; a week of those near five hundred.
    val span = if (weekMode) 500f else 100f
    val rainbow = listOf(
        Color(0xFF8B5CF6), Color(0xFF6366F1), Color(0xFF3B82F6), Color(0xFF06B6D4),
        Color(0xFF22C55E), Color(0xFFEAB308), Color(0xFFF97316), Color(0xFFEF4444),
    )
    val label = if (balance >= 0) "+$balance" else "$balance"
    val labelColor = scoreColor(balance.toFloat(), span)
    val trackColor = MaterialTheme.colorScheme.surfaceVariant
    // Полоска во всю ширину и ровно под галками навигации: у IconButton глиф
    // сидит в 12.dp от края, столько же отступа берёт себе дорожка - слева и
    // справа одинаково.
    Box(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp),
        contentAlignment = Alignment.Center,
    ) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val h = size.height
            val radius = androidx.compose.ui.geometry.CornerRadius(h / 2f)
            val middle = size.width / 2f
            val brush = androidx.compose.ui.graphics.Brush.horizontalGradient(
                colors = rainbow,
                startX = 0f,
                endX = size.width,
            )
            drawRoundRect(color = trackColor, size = size, cornerRadius = radius)
            // The whole spectrum, dimmed: both directions are visible as goals.
            drawRoundRect(brush = brush, size = size, cornerRadius = radius, alpha = 0.22f)
            val frac = (kotlin.math.abs(balance) / span).coerceIn(0f, 1f)
            val width = middle * frac
            if (width > 0f) {
                drawRoundRect(
                    brush = brush,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        if (balance >= 0) middle else middle - width, 0f,
                    ),
                    size = androidx.compose.ui.geometry.Size(width, h),
                    cornerRadius = radius,
                )
                // У нуля заливка ровная, скругление только на дальнем конце:
                // ноль - это срез, от которого тянешься, а не отдельная капля.
                val flat = kotlin.math.min(width, h / 2f)
                drawRect(
                    brush = brush,
                    topLeft = androidx.compose.ui.geometry.Offset(
                        if (balance >= 0) middle else middle - flat, 0f,
                    ),
                    size = androidx.compose.ui.geometry.Size(flat, h),
                )
            }
        }
        // Балл едет кружком по дорожке и стоит в своей точке - там, докуда
        // день дотянулся. Смещение задаётся тем же дробным сдвигом, что и
        // заливка: 0 = ноль в середине, +1 = правый край, −1 = левый.
        val bias = (balance / span).coerceIn(-1f, 1f)
        Text(
            label,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.Bold,
            color = Color(0xFF1B1B1B),
            maxLines = 1,
            modifier = Modifier
                .align(androidx.compose.ui.BiasAlignment(bias, 0f))
                .background(labelColor, RoundedCornerShape(50))
                .padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

// ---------------------------------------------------------------------------
// Ribbon rows: the plain one-line entry and the chain block (an activity the
// automation sliced up, shown whole again).
// ---------------------------------------------------------------------------

/**
 * Идущее прямо сейчас дело. Владелец: «удобно, что вижу текущее (нужно другим
 * цветом)». Раньше это была серая подложка surfaceVariant — тот же серый, что
 * у половины интерфейса, и в ленте из тридцати строк она не читалась.
 * Теперь цвет акцента и рамка: единственная строка, которая ещё идёт, должна
 * находиться взглядом без чтения.
 */
@Composable
private fun Modifier.runningNow(): Modifier = this
    .background(
        MaterialTheme.colorScheme.primary.copy(alpha = 0.13f),
        RoundedCornerShape(10.dp),
    )
    .border(
        1.dp,
        MaterialTheme.colorScheme.primary.copy(alpha = 0.45f),
        RoundedCornerShape(10.dp),
    )


@Composable
private fun EntryRow(
    entry: ZasechkaStore.Entry,
    now: Long,
    worthOf: (String) -> Int,
    onStop: (() -> Unit)?,
    onEdit: () -> Unit,
    onComment: () -> Unit,
    onDelete: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (entry.open) Modifier.runningNow() else Modifier
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
                .height(38.dp)
                .background(categoryColor(entry.category), RoundedCornerShape(2.dp)),
        )
        // Две строки (макет владельца): сверху ЛИЧНОЕ название дела, снизу
        // «[тег категории] · длительность · ±баллы» — так читается и на
        // сложенном экране, без ужатых колонок.
        Column(Modifier.weight(1f).padding(start = 8.dp)) {
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
            )
            Spacer(Modifier.height(2.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                CategoryTag(entry.category)
                DotSep()
                Text(
                    fmtDur(entry.durationMin(now)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
                val pts = pointsOf(worthOf(entry.category), entry.durationMs(now))
                if (pts != 0) {
                    DotSep()
                    PointsChip(pts)
                }
            }
            CommentLine(entry.comment)
        }
        if (onStop != null) {
            IconButton(onClick = onStop, modifier = Modifier.size(30.dp)) {
                Box(
                    Modifier
                        .size(11.dp)
                        .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)),
                )
            }
        }
        CommentBubble(hasComment = entry.comment.isNotBlank(), onClick = onComment)
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
// (interruptions excluded). Header edit/✕ act on ALL fragments.
@Composable
private fun ChainBlock(
    unit: DayUnit,
    now: Long,
    worthOf: (String) -> Int,
    onStop: (() -> Unit)?,
    onEdit: () -> Unit,
    onComment: () -> Unit,
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
                if (unit.open) Modifier.runningNow() else Modifier
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
                // Те же две строки, что у одиночной записи; время — НЕТТО по
                // всем фрагментам дела (жирным: это сумма).
                Column(Modifier.weight(1f).padding(start = 8.dp)) {
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
                    )
                    Spacer(Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        CategoryTag(head.category)
                        DotSep()
                        Text(
                            fmtDur(unit.totalMin(now)),
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold,
                            maxLines = 1,
                        )
                        val pts = pointsOf(
                            worthOf(head.category),
                            unit.fragments.sumOf { it.durationMs(now) },
                        )
                        if (pts != 0) {
                            DotSep()
                            PointsChip(pts, bold = true)
                        }
                    }
                    CommentLine(head.comment)
                }
                if (onStop != null) {
                    IconButton(onClick = onStop, modifier = Modifier.size(30.dp)) {
                        Box(
                            Modifier
                                .size(11.dp)
                                .background(MaterialTheme.colorScheme.error, RoundedCornerShape(2.dp)),
                        )
                    }
                }
                CommentBubble(hasComment = head.comment.isNotBlank(), onClick = onComment)
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
            // Врезки внутри дела (сон, тренировка с часов, разрезавшие его) в
            // ленте отдельными строками не рисуются — владелец: «очень сильно
            // засоряет». Они остаются в данных и в выгрузках; тап по блоку
            // правит дело целиком. Параллельного трека больше нет: телефон
            // считается по дням и виден строкой у итогов дня.
        }
    }
}

// ---------------------------------------------------------------------------
// Settings block: button, reminders, dictionaries of categories/clients,
// the Sheets webhook and the CSV export.
// ---------------------------------------------------------------------------

@Composable
internal fun ZasechkaSettings(app: PravkaApp) {
    val context = LocalContext.current
    // Настройки открываются и БЕЗ захода в Засечку — стор мог быть не прочитан,
    // и редактор категорий показал бы пустоту. Отредактировать пустоту и
    // сохранить — значит затереть настоящие категории; загрузка обязана
    // случиться раньше.
    LaunchedEffect(Unit) { app.zasechkaStore.all() }
    val categories by app.zasechkaStore.categoriesFlow.collectAsState()
    val clients by app.zasechkaStore.clientsFlow.collectAsState()
    val syncStatus by app.zasechkaSync.statusFlow.collectAsState()
    val entries by app.zasechkaStore.entriesFlow.collectAsState()

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
        val checkins by app.settings.zCheckinsFlow.collectAsState(initial = true)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(
                checked = checkins,
                onCheckedChange = { app.appScope.launch { app.settings.setZCheckins(it) } },
            )
            Text(
                "Спрашивать «всё ещё …?»",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
        Text(
            "Когда дело идёт дольше базового времени своей категории, кнопка моргает и " +
                "спрашивает. «Да» — считаем дальше, «Нет» — сразу новая запись.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(12.dp))

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

        // Notion mirror removed (owner's call): Sheets is the one mirror.

        Spacer(Modifier.height(12.dp))
        BackupsSection(app)

        ZasechkaLearning(app)

        Spacer(Modifier.height(12.dp))
        Text("Выгрузить CSV", style = MaterialTheme.typography.titleSmall)
        OutlinedButton(onClick = {
            app.appScope.launch {
                context.startActivity(app.zasechkaStore.shareCsvIntent())
            }
        }) { Text("Выгрузить ленту") }
        Text(
            "Строка на дело, сумма minutes за день — ровно 1440. Файл начинается " +
                "легендой для того, кому его отдают. Обычно не нужен: лента раз в " +
                "час сама уезжает в Notion, в «Правка: разборы».",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )

        Spacer(Modifier.height(18.dp))
        AutoPilotSection(app)

        Spacer(Modifier.height(18.dp))
        NfcTagsSection(app)
    }
}

/**
 * Автопилот: телефон сам замечает швы дня. Приезд в известный Wi-Fi закрывает
 * открытое «Передвижение» сам; отъезд из места и подключение машины спрашивают
 * пушем; длинное сидячее дело проверяется, когда телефон значимо задвигался.
 * Имя Wi-Fi система отдаёт только с разрешением «Местоположение» (GPS при этом
 * не включается), имя BT-устройства на Android 12+ — с «Устройствами рядом».
 */
@Composable
private fun AutoPilotSection(app: PravkaApp) {
    val context = LocalContext.current
    val scope = app.appScope
    val settings = app.settings

    Text("Автопилот", style = MaterialTheme.typography.titleSmall)
    Spacer(Modifier.height(4.dp))
    Text(
        "Wi-Fi-места, Bluetooth машины и датчик движения: приезд закрывает " +
            "передвижение сам, остальное — вопросом-пушем.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    var permTick by remember { mutableStateOf(0) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }

    val places by settings.autoPlacesFlow.collectAsState(initial = emptyMap<String, String>())
    val visibleSsids by settings.autoVisibleFlow.collectAsState(initial = emptySet<String>())
    val carBt by settings.autoCarBtFlow.collectAsState(initial = "")

    // ---- Места по Wi-Fi ----
    Spacer(Modifier.height(10.dp))
    // Что автопилот видит ПРЯМО СЕЙЧАС. Первая версия молчала, и понять это
    // было нельзя ниоткуда — теперь состояние на виду.
    val pilot = ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.autoPilot
    Text(
        pilot?.statusLine() ?: "Служба выключена — автопилот спит",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))

    // Почему молчит — словами и с кнопкой. Раньше на это место приходила
    // одна кнопка «дать доступ», и она врала: доступ был выдан «только при
    // использовании», а служба всё равно не видела ни одной сети.
    val blockers = remember(permTick, carBt) {
        ru.zf.pravka.trigger.AutoPilot.blockers(context, carBt)
    }
    // Разрешение на уведомления просим один раз; если система его уже не
    // покажет (отказано дважды) — вторая кнопка ведёт в настройки Правки.
    var notifAsked by remember { mutableStateOf(false) }
    for (b in blockers) {
        Text(
            "⚠ " + b.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        OutlinedButton(onClick = {
            when (b.fix) {
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION ->
                    askPermission.launch(android.Manifest.permission.ACCESS_FINE_LOCATION)
                ru.zf.pravka.trigger.AutoPilot.FIX_BACKGROUND -> {
                    // На Android 11+ системного диалога для фонового
                    // местоположения нет вовсе: только экран приложения,
                    // руками. Поэтому ведём прямо туда.
                    if (android.os.Build.VERSION.SDK_INT == 29) {
                        askPermission.launch("android.permission.ACCESS_BACKGROUND_LOCATION")
                    } else {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                android.net.Uri.fromParts("package", context.packageName, null),
                            ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                        Feedback.toast(app, "Разрешения → Местоположение → «Разрешать всегда»")
                    }
                }
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION_SYS ->
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_LOCATION_SOURCE_SETTINGS
                        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF -> {
                    if (android.os.Build.VERSION.SDK_INT >= 33 && !notifAsked) {
                        notifAsked = true
                        askPermission.launch("android.permission.POST_NOTIFICATIONS")
                    } else {
                        context.startActivity(
                            android.content.Intent(
                                android.provider.Settings.ACTION_APP_NOTIFICATION_SETTINGS
                            )
                                .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                                .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    }
                }
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF_CHANNEL ->
                    context.startActivity(
                        android.content.Intent(
                            android.provider.Settings.ACTION_CHANNEL_NOTIFICATION_SETTINGS
                        )
                            .putExtra(android.provider.Settings.EXTRA_APP_PACKAGE, context.packageName)
                            .putExtra(
                                android.provider.Settings.EXTRA_CHANNEL_ID,
                                ru.zf.pravka.trigger.AutoPilot.CHANNEL,
                            )
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                ru.zf.pravka.trigger.AutoPilot.FIX_BT ->
                    askPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
                else -> context.startActivity(
                    android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                        .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                )
            }
        }) {
            Text(
                when (b.fix) {
                    ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION -> "Дать доступ к имени Wi-Fi"
                    ru.zf.pravka.trigger.AutoPilot.FIX_BACKGROUND -> "Открыть разрешения Правки"
                    ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION_SYS -> "Включить геолокацию"
                    ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF -> "Разрешить уведомления"
                    ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF_CHANNEL -> "Открыть канал уведомлений"
                    ru.zf.pravka.trigger.AutoPilot.FIX_BT -> "Дать доступ к Bluetooth-устройствам"
                    else -> "Включить Wi-Fi"
                }
            )
        }
        Spacer(Modifier.height(6.dp))
    }
    if (blockers.isNotEmpty()) {
        TextButton(onClick = { permTick++ }) { Text("Проверить ещё раз") }
    }

    // Сети, которые служба уже видела: владелец называет каждую местом
    // («это дача») — ровно то, что он просил, вместо угадывания SSID.
    val seen by settings.autoSeenFlow.collectAsState(initial = emptyMap<String, Long>())
    var namingSsid by remember { mutableStateOf<String?>(null) }
    var placeName by remember { mutableStateOf("") }
    val unnamed = seen.keys.filter { !places.containsKey(it) }
        .sortedByDescending { seen[it] ?: 0L }

    if (places.isNotEmpty()) {
        Text("Мои места", style = MaterialTheme.typography.bodyMedium)
        Text(
            "Кнопка справа выбирает, что считать приездом. «подключился» — " +
                "точнее, так ловится дом. «вижу сеть» — для мест вроде Летово, " +
                "к чьему Wi-Fi ты не подключаешься: приезд засчитывается, как " +
                "только сеть появилась в эфире.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        for ((ssid, name) in places) {
            val byAir = visibleSsids.contains(ssid)
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "$name — $ssid",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.weight(1f),
                )
                TextButton(onClick = {
                    scope.launch { settings.setAutoVisible(ssid, !byAir) }
                }) { Text(if (byAir) "вижу сеть" else "подключился") }
                TextButton(onClick = { scope.launch { settings.removeAutoPlace(ssid) } }) {
                    Text("✕")
                }
            }
        }
        Spacer(Modifier.height(6.dp))
    }

    if (unnamed.isEmpty()) {
        Text(
            if (places.isEmpty()) {
                "Новых сетей пока не видел. Подключись к домашнему Wi-Fi — " +
                    "он появится здесь (и придёт пуш «что это за место?»). Сети, " +
                    "которые просто слышно рядом, подтягиваются сами раз в полчаса."
            } else "Все увиденные сети названы.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    } else {
        Text("Что это за сети?", style = MaterialTheme.typography.bodyMedium)
        for (ssid in unnamed) {
            if (namingSsid == ssid) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = placeName,
                        onValueChange = { placeName = it },
                        modifier = Modifier.weight(1f),
                        label = { Text("«$ssid» — это…") },
                        singleLine = true,
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(onClick = {
                        val name = placeName.trim()
                        if (name.isNotBlank()) {
                            scope.launch {
                                settings.addAutoPlace(ssid, name)
                                settings.removeAutoSeen(ssid)
                            }
                        }
                        namingSsid = null
                        placeName = ""
                    }) { Text("Готово") }
                }
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(ssid, style = MaterialTheme.typography.bodySmall)
                        Text(
                            "видел " + SimpleDateFormat("dd.MM HH:mm", Locale.US)
                                .format(Date(seen[ssid] ?: 0L)),
                            style = MaterialTheme.typography.labelSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    TextButton(onClick = { namingSsid = ssid; placeName = "" }) {
                        Text("Это место…")
                    }
                    TextButton(onClick = { scope.launch { settings.removeAutoSeen(ssid) } }) {
                        Text("Не место")
                    }
                }
            }
        }
    }

    // ---- Машина по Bluetooth ----
    Spacer(Modifier.height(12.dp))
    val needBtPerm = android.os.Build.VERSION.SDK_INT >= 31 &&
        context.checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) !=
        android.content.pm.PackageManager.PERMISSION_GRANTED
    // permTick пересчитывает и это условие тоже.
    val btPermTick = remember(permTick) { needBtPerm }
    val carBtAddr by settings.autoCarBtAddrFlow.collectAsState(initial = "")
    when {
        carBt.isNotBlank() -> {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("Машина: $carBt", style = MaterialTheme.typography.bodyMedium)
                    Text(
                        if (carBtAddr.isBlank()) {
                            // Машина заведена старой сборкой — только по имени. Имя
                            // система отдаёт не всегда; переустановить один раз
                            // стоит, чтобы узнавать и по адресу.
                            "узнаю только по имени — убери и выбери заново, будет и адрес"
                        } else "узнаю по имени и адресу $carBtAddr",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextButton(onClick = { scope.launch { settings.setAutoCarBt("") } }) {
                    Text("Убрать")
                }
            }
        }
        btPermTick -> {
            OutlinedButton(onClick = {
                askPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT)
            }) { Text("Дать доступ к Bluetooth-устройствам") }
        }
        else -> {
            // Имя → адрес: адрес едет в настройки вместе с именем, по нему
            // машина узнаётся и тогда, когда система имени не отдаёт.
            val bonded = remember(permTick) {
                runCatching {
                    (context.getSystemService(android.content.Context.BLUETOOTH_SERVICE)
                        as android.bluetooth.BluetoothManager)
                        .adapter?.bondedDevices
                        ?.mapNotNull { d -> d.name?.let { it to d.address.orEmpty() } }
                        ?.sortedBy { it.first }
                }.getOrNull().orEmpty()
            }
            if (bonded.isEmpty()) {
                Text(
                    "Спаренных Bluetooth-устройств не видно.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text("Какое устройство — машина?", style = MaterialTheme.typography.bodyMedium)
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    for ((name, addr) in bonded) {
                        FilterChip(
                            selected = false,
                            onClick = { scope.launch { settings.setAutoCarBt(name, addr) } },
                            label = { Text(name) },
                        )
                    }
                }
            }
        }
    }

    // ---- Тумблеры ----
    Spacer(Modifier.height(12.dp))
    val autoArrive by settings.autoArriveFlow.collectAsState(initial = true)
    val leaveAsk by settings.autoLeaveAskFlow.collectAsState(initial = true)
    val carAsk by settings.autoCarAskFlow.collectAsState(initial = true)
    val carStart by settings.autoCarStartFlow.collectAsState(initial = true)
    val stillAsk by settings.autoStillAskFlow.collectAsState(initial = true)
    @Composable
    fun toggle(checked: Boolean, label: String, onChange: (Boolean) -> Unit) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Switch(checked = checked, onCheckedChange = onChange)
            Spacer(Modifier.width(8.dp))
            Text(label, style = MaterialTheme.typography.bodyMedium)
        }
    }
    toggle(autoArrive, "Приезд в место закрывает передвижение") { on ->
        scope.launch { settings.setAutoArrive(on) }
    }
    toggle(leaveAsk, "Спрашивать при отъезде из места") { on ->
        scope.launch { settings.setAutoLeaveAsk(on) }
    }
    toggle(carStart, "Машина подключилась — сразу начать «Поездку на машине»") { on ->
        scope.launch { settings.setAutoCarStart(on) }
    }
    if (!carStart) {
        toggle(carAsk, "…или хотя бы спросить «сел в машину?»") { on ->
            scope.launch { settings.setAutoCarAsk(on) }
        }
    }
    Text(
        "Поездка стартует с момента подключения и закрывает текущее дело; в пуше " +
            "есть «Отменить». Отключилась машина — через две минуты вопрос «приехал?». " +
            "Приезд в место с открытой дорогой закрывает её и спрашивает, что теперь.",
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    toggle(stillAsk, "«Точно ещё …?», когда телефон задвигался") { on ->
        scope.launch { settings.setAutoStillAsk(on) }
    }
}

/**
 * Backups, out in the open: a copy of the ribbon per day, the state before any
 * sharp shrink, and the quarantined file if one ever appears. Restore puts a
 * copy back (undoable like any other operation), «Файлом» hands the raw JSON
 * out so nothing important is ever trapped in private storage.
 */
@Composable
private fun BackupsSection(app: PravkaApp) {
    val context = LocalContext.current
    var tick by remember { mutableStateOf(0) }
    var list by remember { mutableStateOf<List<ZasechkaStore.BackupInfo>>(emptyList()) }
    LaunchedEffect(tick) {
        list = runCatching { app.zasechkaStore.backups() }.getOrDefault(emptyList())
    }
    // Импорт возвращает ленту из любой выгрузки CSV - последняя линия обороны,
    // если и файл, и копии на диске подвели (копии живут в приватной памяти
    // приложения, а выгрузка уже уехала в мессенджер).
    val importer = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri != null) {
            app.appScope.launch {
                val n = runCatching { app.zasechkaStore.importCsv(uri) }.getOrDefault(0)
                if (n > 0) app.zasechkaSync.kickSoon(app.appScope)
                tick++
                Feedback.toast(
                    app,
                    when {
                        n > 0 -> "Вернулось записей: $n"
                        n < 0 -> "Файл не прочитался"
                        else -> "Новых записей в файле нет"
                    },
                )
            }
        }
    }
    Text("Резервные копии", style = MaterialTheme.typography.titleSmall)
    Text(
        "Копия всего нажитого (лента, словарь, правила, телефон) снимается раз в час, " +
            "плюс копия ленты на каждый день и перед любым резким сокращением записей. " +
            "«Восстановить» возвращает копию целиком (отменяется кнопкой ↩︎), " +
            "«Файлом» отдаёт сырой JSON. «Импорт CSV» поднимает ленту из любой выгрузки: " +
            "строки, которые уже есть, не удваиваются.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        TextButton(onClick = { tick++ }) { Text("Обновить") }
        TextButton(onClick = {
            app.appScope.launch {
                runCatching { context.startActivity(app.zasechkaStore.shareStoreIntent()) }
            }
        }) { Text("Текущий файл") }
        TextButton(onClick = {
            runCatching { importer.launch(arrayOf("*/*")) }
        }) { Text("Импорт CSV") }
    }
    if (list.isEmpty()) {
        Text(
            "Копий пока нет — первая появится в течение часа.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for (b in list) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    b.name.removePrefix("lenta-").removePrefix("zasechka-").removeSuffix(".json"),
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    "${b.entries} записей · ${b.bytes / 1024} КБ · " +
                        SimpleDateFormat("d MMM HH:mm", Locale("ru")).format(Date(b.at)),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            TextButton(onClick = {
                app.appScope.launch {
                    runCatching { context.startActivity(app.zasechkaStore.shareStoreIntent(b.name)) }
                }
            }) { Text("Файлом") }
            TextButton(
                onClick = {
                    app.appScope.launch {
                        val n = runCatching { app.zasechkaStore.restoreFrom(b.name) }.getOrDefault(0)
                        app.zasechkaSync.kickSoon(app.appScope)
                        Feedback.toast(
                            app,
                            if (n > 0) "Восстановлено записей: $n" else "В копии нечего восстанавливать",
                        )
                    }
                },
                enabled = b.entries > 0,
            ) { Text("Восстановить") }
        }
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
        "Сонет выбирает строго из этого списка; пояснение — подсказка ему. Тап — править: " +
            "там же базовое время («всё ещё …?» после него) и ценность часа от −10 до +10, " +
            "из которой складывается баланс дня.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    // Same order as the day's progress bars: along the rainbow, red first.
    for (category in categories.sortedBy { categoryHue(it.name) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editing = category },
        ) {
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = categoryColor(category.name),
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    // The two knobs at a glance: typical length and what an
                    // hour of it is worth (+ lifts the day, - sinks it).
                    Text(
                        (if (category.baseMin > 0) "⏱ ${category.baseMin} м  " else "") +
                            (if (category.value > 0) "+${category.value}" else "${category.value}"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 1,
                    )
                }
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
        var baseMin by remember(original) { mutableStateOf(original.baseMin.toString()) }
        var worth by remember(original) { mutableStateOf(original.value) }
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
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = baseMin,
                        onValueChange = { baseMin = it.filter { c -> c.isDigit() }.take(4) },
                        label = { Text("Базовое время, мин (0 — не спрашивать)") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        "Ценность часа: " + (if (worth > 0) "+$worth" else "$worth") +
                            when {
                                worth >= 7 -> " — тянет день вверх"
                                worth > 0 -> " — плюс"
                                worth == 0 -> " — ватерлиния, сервисное время"
                                worth > -7 -> " — минус"
                                else -> " — тянет день вниз"
                            },
                        style = MaterialTheme.typography.bodySmall,
                    )
                    Slider(
                        value = worth.toFloat(),
                        onValueChange = { worth = it.roundToInt() },
                        valueRange = -10f..10f,
                        steps = 19,
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
                                if (it.name == original.name) {
                                    ZasechkaStore.Category(
                                        name = trimmed,
                                        hint = hint.trim(),
                                        baseMin = baseMin.toIntOrNull() ?: 0,
                                        value = worth,
                                    )
                                } else it
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
    var callsMs = 0L
    var calls = 0
    val callers = HashMap<String, Long>()
    for (key in keys) {
        val d = days[key] ?: continue
        screenMs += d.screenMs
        pickups += d.pickups
        glances += d.glances
        callsMs += d.callsMs
        calls += d.calls
        for ((n, v) in d.callers) callers[n] = (callers[n] ?: 0L) + v
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
    return PhoneStore.Day(
        screenMs, pickups, glances, apps, appSessions, glanceApps, sites,
        callsMs = callsMs, calls = calls, callers = callers,
    )
}

@Composable
private fun PhoneSection(app: PravkaApp, dayStart: Long, weekMode: Boolean, now: Long) {
    val context = LocalContext.current
    val days by app.phoneStore.daysFlow.collectAsState()
    val immersive by app.phoneStore.immersiveFlow.collectAsState()
    val audioApps by app.phoneStore.audioFlow.collectAsState()
    val offApps by app.phoneStore.offFlow.collectAsState()
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
                "подъёмы телефона и счётчик отвлечений, а у итогов дня — строка «Телефон»: " +
                "сколько на YouTube, Telegram, Claude и звонки.",
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
            "Тап по приложению — считать его по дням (строка «Телефон» у итогов и база «Телефон» в Notion).",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Телефон в ленту не пишет ничего, кроме сна. Врезки резали дело,
    // параллельный трек засорял ленту — оба опыта владелец закрыл. Теперь
    // телефон считается по дням: строка «Телефон» у итогов и база «Телефон» в Notion.
    Spacer(Modifier.height(6.dp))
    Text(
        "В ленту телефон не пишет ничего, кроме сна по экрану. YouTube, Telegram, " +
            "Claude и звонки считаются по дням — строкой у итогов дня и в «Телефоне» Notion.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

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

    // ---- calls: counted per day from the call log ----
    val callsOn by app.settings.zCallsFlow.collectAsState(initial = true)
    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> callGranted = granted }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = 6.dp)) {
        Column(Modifier.weight(1f)) {
            Text("Считать звонки", style = MaterialTheme.typography.bodyMedium)
            Text(
                "Разговоры ≥1 мин по журналу: минуты, число и с кем — за день",
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

    // Список того, что считается по дням, — тумблерами, а не догадками по
    // серому списку экранного времени. Выключенное приложение остаётся в
    // списке со своей категорией: тумблер обратно — и оно снова считается.
    Spacer(Modifier.height(12.dp))
    Text("Приложения по дням", style = MaterialTheme.typography.titleSmall)
    Text(
        "Их минуты за день — в строке «Телефон» у итогов и в «Телефоне» Notion. Тап по " +
            "строке — категория-подсказка и «звук в фоне».",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (immersive.isEmpty()) {
        Text(
            "Пока пусто — отметь приложение тапом в списке выше.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    for ((pkg, category) in immersive.entries.sortedBy { appLabelOf(labels, it.key).lowercase() }) {
        val on = pkg !in offApps
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clickable { editingApp = pkg }
                .padding(vertical = 2.dp),
        ) {
            Switch(
                checked = on,
                onCheckedChange = { v -> app.appScope.launch { app.phoneStore.setTracked(pkg, v) } },
            )
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(
                    appLabelOf(labels, pkg),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (on) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    category.ifBlank { "без категории" } +
                        (if (pkg in audioApps) " · звук в фоне" else ""),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                )
            }
            IconButton(
                onClick = { app.appScope.launch { app.phoneStore.forgetApp(pkg) } },
                modifier = Modifier.size(30.dp),
            ) {
                Icon(
                    Icons.Filled.Clear,
                    contentDescription = "убрать из списка",
                    modifier = Modifier.size(15.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
    }

    // Досчёт прошлых дней: телефон помнит суточные суммы по приложениям и
    // журнал звонков дольше, чем живёт приложение. Дни до установки можно
    // досчитать — без сессий, но с честными минутами за день.
    var backfill by remember { mutableStateOf(false) }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = { backfill = true }) { Text("Досчитать прошлые дни") }
    Text(
        "Поднимает из памяти телефона суточные суммы по приложениям и звонки за " +
            "дни, которых Правка не видела сама. В ленту ничего не пишет — только в " +
            "счётчики телефона и в «Телефон» Notion.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    if (backfill) {
        BackfillDialog(app = app, onDismiss = { backfill = false })
    }

    editingApp?.let { pkg ->
        ImmersiveAppDialog(
            label = appLabelOf(labels, pkg),
            currentCategory = immersive[pkg],
            currentAudio = pkg in audioApps,
            categories = categories,
            onDismiss = { editingApp = null },
            onSave = { category, audio ->
                editingApp = null
                app.appScope.launch {
                    app.phoneStore.setImmersive(pkg, category)
                    app.phoneStore.setAudio(pkg, audio)
                }
            },
        )
    }
}

/**
 * Разметка задним числом. Показывает, что телефон помнит и чего в ленте нет,
 * даёт каждому источнику категорию и кладёт выбранное во второй трек.
 *
 * Категории не угадываются: «Клод» у владельца может быть и работой, и
 * систематизацией, и это знает только он. Поэтому список с выбором, а не
 * кнопка «сделай хорошо».
 */
/**
 * Самообучение Засечки: поправки владельца → предложенные правила → его «да»
 * → правила едут в каждый разбор. То же, что «Обучить» в Правке, но предмет
 * другой: не как он пишет, а что у него значат слова про время.
 *
 * Ничего не включается само. Правило, которое владелец не судил, в промпт не
 * идёт — иначе робот однажды начал бы учить сам себя на своих же промахах.
 */
@Composable
private fun ZasechkaLearning(app: PravkaApp) {
    val scope = app.appScope
    var rules by remember { mutableStateOf(emptyList<ru.zf.pravka.data.RulesStore.Rule>()) }
    var pendingCount by remember { mutableStateOf(0) }
    var reload by remember { mutableStateOf(0) }
    var busy by remember { mutableStateOf(false) }

    LaunchedEffect(reload) {
        rules = runCatching { app.zasechkaRules.all() }.getOrDefault(emptyList())
        // Материал — это все НАДИКТОВКИ, которых разбор ещё не видел, а не
        // только правки руками: самый частый промах владелец не правит, он к
        // нему привыкает.
        pendingCount = runCatching { app.zasechkaEngine.learnBacklog() }.getOrDefault(0)
    }

    val proposed = rules.filter { it.pending }
    val active = rules.filter { !it.pending }

    Spacer(Modifier.height(12.dp))
    Text("Самообучение", style = MaterialTheme.typography.titleSmall)
    Text(
        "Каждую ночь Опус сверяет, что ты наговорил, с тем, что из этого вышло " +
            "в ленте: где сказал «с 18:30 до 18:50», а записалось только " +
            "начало; куда раз за разом уезжает категория; какие слова у тебя " +
            "значат время. Найдёт закономерность — предложит " +
            "правило, и над кнопкой «З» загорится ⭐. Правило заработает " +
            "только после твоего «да».\n\nНочью это уходит батчем — вдвое " +
            "дешевле, ответ приходит в течение часа. Кнопки ниже считают " +
            "сразу и по полной цене: они на случай «хочу прямо сейчас».",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    Spacer(Modifier.height(8.dp))
    Row(verticalAlignment = Alignment.CenterVertically) {
        Button(
            enabled = !busy && pendingCount > 0,
            onClick = {
                busy = true
                scope.launch {
                    val n = runCatching { app.zasechkaEngine.learn() }.getOrDefault(-1)
                    Feedback.toast(
                        app,
                        when {
                            n > 0 -> "Предложено правил: $n"
                            n == 0 -> "Закономерностей не нашлось — это тоже ответ"
                            else -> "Разбор не дошёл, поправки целы"
                        },
                    )
                    busy = false
                    reload++
                }
            },
        ) { Text(if (busy) "Думаю…" else "Обучить") }
        Spacer(Modifier.width(10.dp))
        Text(
            if (pendingCount > 0) "ждёт разбора: $pendingCount"
            else "нового материала нет",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    // Первый раз: в ленте лежат месяцы надиктовок, которых разбор не видел.
    TextButton(
        enabled = !busy,
        onClick = {
            busy = true
            scope.launch {
                val n = runCatching { app.zasechkaEngine.learn(all = true) }.getOrDefault(-1)
                Feedback.toast(
                    app,
                    when {
                        n > 0 -> "Предложено правил: $n"
                        n == 0 -> "Закономерностей не нашлось — это тоже ответ"
                        else -> "Разбор не дошёл, материал цел"
                    },
                )
                busy = false
                reload++
            }
        },
    ) { Text("Прогнать всю историю") }

    if (proposed.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Предлагаю — суди", style = MaterialTheme.typography.bodyMedium)
        for (r in proposed) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Text(r.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                TextButton(onClick = {
                    scope.launch { app.zasechkaRules.approve(r.id); reload++ }
                }) { Text("Да") }
                TextButton(onClick = {
                    scope.launch { app.zasechkaRules.delete(r.id); reload++ }
                }) { Text("Нет", color = MaterialTheme.colorScheme.error) }
            }
        }
    }

    if (active.isNotEmpty()) {
        Spacer(Modifier.height(10.dp))
        Text("Действующие правила", style = MaterialTheme.typography.bodyMedium)
        for (r in active) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
            ) {
                Switch(
                    checked = r.enabled,
                    onCheckedChange = { v ->
                        scope.launch { app.zasechkaRules.setEnabled(r.id, v); reload++ }
                    },
                )
                Spacer(Modifier.width(10.dp))
                Text(
                    r.text,
                    style = MaterialTheme.typography.bodySmall,
                    color = if (r.enabled) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = { scope.launch { app.zasechkaRules.delete(r.id); reload++ } },
                    modifier = Modifier.size(30.dp),
                ) {
                    Icon(
                        Icons.Filled.Clear,
                        contentDescription = "удалить правило",
                        modifier = Modifier.size(15.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        }
    }
}

@Composable
private fun BackfillDialog(app: PravkaApp, onDismiss: () -> Unit) {
    var days by remember { mutableStateOf(30) }
    var scan by remember { mutableStateOf<ru.zf.pravka.data.PhoneSweeper.BackfillScan?>(null) }
    var scanning by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    val stamp = remember { SimpleDateFormat("d MMMM", Locale("ru")) }
    val labels by app.phoneStore.labelsFlow.collectAsState()
    val tracked by app.phoneStore.immersiveFlow.collectAsState()

    LaunchedEffect(days) {
        scanning = true
        scan = runCatching { app.phoneSweeper.scanBackfill(days) }.getOrNull()
        scanning = false
    }

    val found = scan
    AlertDialog(
        onDismissRequest = { if (!busy) onDismiss() },
        title = { Text("Досчитать прошлые дни") },
        text = {
            Column(
                Modifier
                    .heightIn(max = 420.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    for (d in listOf(7, 30, 90)) {
                        FilterChip(
                            selected = days == d,
                            onClick = { if (!busy) days = d },
                            label = { Text("$d дней") },
                        )
                    }
                }
                Spacer(Modifier.height(8.dp))
                when {
                    scanning -> Text("Смотрю память телефона…", style = MaterialTheme.typography.bodySmall)
                    found == null || found.days.isEmpty() -> Text(
                        "Досчитывать нечего: все дни за это окно Правка видела сама.",
                        style = MaterialTheme.typography.bodySmall,
                    )
                    else -> {
                        Text(
                            "Дней без данных: ${found.count}. Что в них нашлось:",
                            style = MaterialTheme.typography.bodySmall,
                        )
                        // Итог по окну — теми же словами, что строка «Телефон».
                        val total = aggregatePhoneDays(found.days, found.days.keys.sorted())
                        val line = PhoneDaySummary.line(PhoneDaySummary.of(total, tracked, labels))
                        Text(
                            line.ifBlank { "только экранное время без отмеченных приложений" },
                            style = MaterialTheme.typography.bodyMedium,
                            modifier = Modifier.padding(vertical = 4.dp),
                        )
                    }
                }
                if (found != null && !scanning) {
                    Spacer(Modifier.height(8.dp))
                    // Честно про глубину: суточные суммы система держит дольше
                    // поимённых событий, звонки — месяцами.
                    val lines = buildList {
                        when {
                            found.noUsageAccess ->
                                add("Нет доступа к статистике использования — приложений не будет.")
                            found.appsFrom > 0 ->
                                add("Приложения: суммы с ${stamp.format(Date(found.appsFrom))}.")
                            else -> add("Приложения: система не отдала сумм за это окно.")
                        }
                        when {
                            found.noCallAccess -> add("Нет доступа к журналу звонков.")
                            found.callsFrom > 0 -> add("Звонки: с ${stamp.format(Date(found.callsFrom))}.")
                        }
                        add("Дни, которые Правка считала сама, не трогаются: они точнее.")
                    }
                    Text(
                        lines.joinToString("\n"),
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
        },
        confirmButton = {
            Button(
                enabled = !busy && !scanning && found != null && found.days.isNotEmpty(),
                onClick = {
                    busy = true
                    app.appScope.launch {
                        val added = runCatching { app.phoneSweeper.applyBackfill(found!!) }.getOrDefault(0)
                        Feedback.toast(
                            app,
                            if (added > 0) "Досчитано дней: $added" else "Новых дней не нашлось",
                        )
                        busy = false
                        onDismiss()
                    }
                },
            ) { Text(if (busy) "Пишу…" else "Досчитать") }
        },
        dismissButton = { TextButton(enabled = !busy, onClick = onDismiss) { Text("Отмена") } },
    )
}

@Composable
private fun ImmersiveAppDialog(
    label: String,
    currentCategory: String?,
    currentAudio: Boolean,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (String?, Boolean) -> Unit,
) {
    var enabled by remember { mutableStateOf(currentCategory != null) }
    var category by remember { mutableStateOf(currentCategory ?: "Отдых") }
    var audio by remember { mutableStateOf(currentAudio) }
    var menu by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(label) },
        text = {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("Считать по дням", style = MaterialTheme.typography.bodyMedium)
                        Text(
                            "Минуты в этом приложении идут в строку «Телефон» у итогов дня " +
                                "и в «Телефон» Notion; в ленту ничего не пишется",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = enabled, onCheckedChange = { enabled = it })
                }
                if (enabled) {
                    Spacer(Modifier.height(8.dp))
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Звук в фоне", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Для аудиокниг, подкастов и музыки: время считается по " +
                                    "фоновой службе, а не по переднему плану — книга играет " +
                                    "с погасшим экраном, и иначе её не поймать вовсе",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        Switch(checked = audio, onCheckedChange = { audio = it })
                    }
                    Spacer(Modifier.height(8.dp))
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
            Button(onClick = { onSave(if (enabled) category else null, enabled && audio) }) {
                Text("Сохранить")
            }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Отмена") } },
    )
}

// ---------------------------------------------------------------------------
// Комментарий к делу: баббл в строке, строка под делом, своё окно.
// ---------------------------------------------------------------------------

/**
 * Баббл 💬 рядом с ✎ и ✕. В core-наборе иконок Material пузыря нет, а тащить
 * ради одного значка extended-набор незачем — он рисуется: контур пузыря с
 * хвостиком, залитый. Пустой — бледный, с комментарием — цветом акцента, так
 * в ленте с одного взгляда видно, у каких дел есть слова.
 */
@Composable
private fun CommentBubble(hasComment: Boolean, onClick: () -> Unit) {
    val tint = if (hasComment) MaterialTheme.colorScheme.primary
    else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.45f)
    IconButton(onClick = onClick, modifier = Modifier.size(30.dp)) {
        Canvas(Modifier.size(15.dp)) {
            val w = size.width
            val body = size.height * 0.78f
            val path = Path().apply {
                addRoundRect(RoundRect(Rect(0f, 0f, w, body), CornerRadius(body * 0.38f)))
                // Хвостик влево-вниз, как у реплики в мессенджере.
                moveTo(w * 0.18f, body - 1f)
                lineTo(w * 0.10f, size.height)
                lineTo(w * 0.42f, body - 1f)
                close()
            }
            drawPath(path, tint)
        }
    }
}

/** Третья строка записи — сам комментарий, той же бледностью, что дыры в ленте. */
@Composable
private fun CommentLine(comment: String) {
    if (comment.isBlank()) return
    Text(
        comment,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        maxLines = 2,
        overflow = TextOverflow.Ellipsis,
        modifier = Modifier.padding(top = 2.dp),
    )
}

/**
 * Поменялся ли в редакторе ТОЛЬКО комментарий. Время сравнивается минутами —
 * как оно и стоит в полях: у записи есть секунды, а поле их не показывает, и
 * нетронутое поле, распарсенное обратно, отличалось бы от записи на секунды.
 */
private fun onlyCommentChanged(before: ZasechkaStore.Entry, after: ZasechkaStore.Entry): Boolean =
    after.title == before.title && after.category == before.category && after.client == before.client &&
        fmtTime(after.start) == fmtTime(before.start) &&
        after.open == before.open && (after.open || fmtTime(after.end) == fmtTime(before.end))

/**
 * Комментарий к делу отдельным окном — по тапу на баббл. Одно поле и ничего
 * лишнего: слова к делу пишутся чаще, чем правится само дело, а полный
 * редактор с временем и категорией ради них долгий. Диктовка — кнопкой «П»
 * прямо в поле: это и есть движок Правки, со словарём, правилами и чисткой.
 */
@Composable
private fun CommentDialog(
    entry: ZasechkaStore.Entry,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var comment by remember { mutableStateOf(entry.comment) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(capFirst(entry.title.ifBlank { entry.category.ifBlank { "без названия" } })) },
        text = {
            Column {
                Text(
                    "${fmtTime(entry.start)}–${if (entry.open) "…" else fmtTime(entry.end)}" +
                        (if (entry.category.isBlank()) "" else " · ${entry.category}"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Комментарий") },
                    placeholder = { Text("что было внутри — «П» надиктует сюда") },
                    minLines = 3,
                    maxLines = 8,
                    modifier = Modifier.fillMaxWidth(),
                )
                if (entry.comment.isNotBlank()) {
                    TextButton(onClick = { onSave("") }) {
                        Text("Убрать комментарий", color = MaterialTheme.colorScheme.error)
                    }
                }
            }
        },
        confirmButton = { Button(onClick = { onSave(comment.trim()) }) { Text("Сохранить") } },
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
    var comment by remember { mutableStateOf(entry.comment) }
    var startText by remember { mutableStateOf(fmtTime(entry.start)) }
    var endText by remember { mutableStateOf(if (entry.open) "" else fmtTime(entry.end)) }
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
                // Комментарий — обычное поле: тап по «П» надиктует прямо сюда,
                // со словарём и чисткой, как в любое поле любого приложения.
                OutlinedTextField(
                    value = comment,
                    onValueChange = { comment = it },
                    label = { Text("Комментарий") },
                    placeholder = { Text("что было внутри — «П» надиктует сюда") },
                    minLines = 2,
                    maxLines = 5,
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
                        comment = comment.trim(),
                        start = newStart,
                        end = if (newEnd > 0) newEnd.coerceAtLeast(newStart) else newEnd,
                        // Полезность руками больше не ставится: её заменила
                        // ценность часа - она понятна и считается сама.
                        // У старых записей оценка остаётся как была.
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
