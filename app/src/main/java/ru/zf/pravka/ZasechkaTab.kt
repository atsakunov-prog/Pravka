package ru.zf.pravka

import ru.zf.pravka.ui.fadingScroll

import ru.zf.pravka.core.Fmt

import androidx.compose.foundation.layout.imePadding

import androidx.compose.foundation.layout.navigationBarsPadding

import androidx.compose.foundation.layout.PaddingValues

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
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
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlin.math.roundToInt
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.core.WakingShare
import ru.zf.pravka.data.PhoneStore
import ru.zf.pravka.data.PhoneSweeper
import ru.zf.pravka.core.AutoPilotRules
import ru.zf.pravka.core.PlaceDeal
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.phoneDayKey
import ru.zf.pravka.ui.ChipRow
import ru.zf.pravka.ui.DayNav
import ru.zf.pravka.ui.Feedback
import ru.zf.pravka.ui.GlyphButton
import ru.zf.pravka.ui.Glyphs
import ru.zf.pravka.ui.IconAction
import ru.zf.pravka.ui.IconActionRow
import ru.zf.pravka.ui.InfoButton
import ru.zf.pravka.ui.PaperAlert
import ru.zf.pravka.ui.PaperButton
import ru.zf.pravka.ui.PaperCard
import ru.zf.pravka.ui.PaperChip
import ru.zf.pravka.ui.PaperField
import ru.zf.pravka.ui.PaperHint
import ru.zf.pravka.ui.PaperIconButton
import ru.zf.pravka.ui.PaperLabel
import ru.zf.pravka.ui.PaperSheet
import ru.zf.pravka.ui.PaperSlider
import ru.zf.pravka.ui.PaperTextButton
import ru.zf.pravka.ui.PaperToggle
import ru.zf.pravka.ui.RowRule
import ru.zf.pravka.ui.ScreenPad
import ru.zf.pravka.ui.SheetAction
import ru.zf.pravka.ui.VoiceInput
import ru.zf.pravka.ui.scrollFade
import ru.zf.pravka.ui.bottomFade
import ru.zf.pravka.trigger.onZasechkaTap

// Вкладка «Засечка»: the owner's day as a ribbon of entries, the numbers he
// loves, and the knobs. Everything the buttons capture lands here for review
// and fixing - the tab is deliberately editable down to minutes, because the
// voice pipeline is fast but not sacred.

// Радуга владельца живёт в core/CategoryRainbow.kt: по ней сортируются итоги
// здесь и раскладывается донат дня в «Отчёте». Тут — только заливка под тему.
internal fun categoryHue(name: String): Float = ru.zf.pravka.core.CategoryRainbow.hue(name)

/**
 * Цвет категории — её место на радуге (`ui.categoryFill`). Правка 4.0 красила
 * категории оттенками оранжевого по цене часа, и владелец перестал видеть,
 * чем занят (10.10.2026: «раньше вот этот вот радужный был намного лучше») —
 * радуга вернулась краской, а не только порядком итогов.
 */
@Composable
internal fun categoryColor(name: String): Color = ru.zf.pravka.ui.categoryFill(name)

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

internal fun scoreColor(value: Float, span: Float): Color = when {
    // Правка 4.0: радуги нет (DESIGN §4) — плюс шкалой краски Засечки, чем
    // больше, тем гуще; минус — тёплым предупреждением.
    value >= span * 0.45f -> ru.zf.pravka.ui.Modes.Zasechka.ramp[2]
    value >= span * 0.15f -> ru.zf.pravka.ui.Modes.Zasechka.ramp[1]
    value > -span * 0.10f -> ru.zf.pravka.ui.Modes.Zasechka.ramp[0]
    else -> ru.zf.pravka.ui.Ink.Warn
}

private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
private val dayLabelFormat = SimpleDateFormat("EEEE, d MMMM", Locale("ru"))

private fun fmtTime(ms: Long): String = timeFormat.format(Date(ms))

/** Milliseconds -> whole minutes, rounded to nearest (never truncated). */
private fun msToMin(ms: Long): Long = (ms + 30_000L) / 60_000L

private fun fmtDur(min: Long): String = ru.zf.pravka.core.Fmt.dur(min.toInt())

/**
 * Длительность полными словами — для подписи в шапке листа («1 ч 15 мин»):
 * там строка одна и места хватает, а в ленте остаётся короткая «1 ч 15 м».
 */
private fun fmtDurLong(min: Long): String = ru.zf.pravka.core.Fmt.dur(min.toInt())

/** «10:40–11:55 · 1 ч 15 мин»; у идущего — «10:40–… · 1 ч 15 мин, идёт», у цепочки перед временем Σ. */
private fun spanLine(start: Long, end: Long, open: Boolean, minutes: Long, net: Boolean = false): String =
    "${fmtTime(start)}–${if (open) "…" else fmtTime(end)} · " +
        (if (net) "Σ " else "") + fmtDurLong(minutes) + (if (open) ", идёт" else "")

// Навигатор дня: словом — день, под ним — дата (24.09.2026, общий DayNav
// набора). Раньше вчерашнего — день недели словом: так прошлый день и
// вспоминается, а число стоит под ним.
private val weekdayFormat = SimpleDateFormat("EEEE", Locale.forLanguageTag("ru"))
private val dateOnlyFormat = SimpleDateFormat("d MMMM", Locale.forLanguageTag("ru"))

private fun dayTitle(offset: Int, dayStart: Long): String = when (offset) {
    0 -> "Сегодня"
    1 -> "Вчера"
    else -> capFirst(weekdayFormat.format(Date(dayStart)))
}

private fun daySubtitle(offset: Int, dayStart: Long): String =
    if (offset <= 1) dayLabelFormat.format(Date(dayStart)) else dateOnlyFormat.format(Date(dayStart))

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
internal fun ZasechkaTab(
    app: PravkaApp,
    /** Запись, которую открыть на правку сразу (лист записи «Сегодня» → «Поправить»). */
    editEntry: Long? = null,
    onEditHandled: () -> Unit = {},
) {
    val context = LocalContext.current
    val store = app.zasechkaStore
    val entries by store.entriesFlow.collectAsState()
    val categories by store.categoriesFlow.collectAsState()
    val categoryNames = remember(categories) { categories.map { it.name } }
    val clients by store.clientsFlow.collectAsState()
    val syncStatus by app.zasechkaSync.statusFlow.collectAsState()
    val ownerName = app.profileStore.flow.collectAsState().value?.name
    LaunchedEffect(Unit) { store.all() }  // first read triggers the load

    // Режим «Неделя» и кнопка отмены сняты (владелец, 15.09.2026: «я их не
    // использую»); отменить последнюю операцию можно из меню долгого нажатия «З».
    var dayOffset by remember { mutableStateOf(0) }
    var editing by remember { mutableStateOf<ZasechkaStore.Entry?>(null) }
    LaunchedEffect(editEntry, entries) {
        val id = editEntry ?: return@LaunchedEffect
        val e = entries.firstOrNull { it.id == id } ?: store.entryById(id) ?: return@LaunchedEffect
        editing = e
        onEditHandled()
    }
    // Chain edit: the whole sliced-up activity at once, all fragments.
    var editingChain by remember { mutableStateOf<List<ZasechkaStore.Entry>?>(null) }
    // Баббл 💬: комментарий к делу отдельным окном (у цепочки — к голове).
    var commenting by remember { mutableStateOf<ZasechkaStore.Entry?>(null) }
    // Тап по «не размечено» или по «··· N мин без записи»: сказать, что это
    // было, или присоединить к соседу (владелец: «очень часто что-то просто
    // не дозаписалось или обрубилось — тогда присоединять к прошлому»).
    var gapFor by remember { mutableStateOf<GapTarget?>(null) }
    // Лист записи по тапу на строку ленты (24.09.2026: «лента дышит» — в
    // строке больше нет четырёх значков). Держим id головы, а не саму запись:
    // пока лист открыт, лента живёт — идущее дело тикает, его останавливают
    // голосом, — и лист показывает запись нынешней, а не снимком с тапа.
    var sheetFor by remember { mutableStateOf<Long?>(null) }
    var draft by remember { mutableStateOf("") }
    var processing by remember { mutableStateOf(false) }
    // Карточка дела из плашки «дела» (05.10.2026).
    var openTask by remember { mutableStateOf<ru.zf.pravka.core.Dela.Task?>(null) }

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
    val rangeStart = dayStart
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
    val dayUnits = remember(rangeEntries) { buildDayUnits(rangeEntries).asReversed() }

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

    val doStop: () -> Unit = {
        app.appScope.launch { app.zasechkaEngine.closeOpen() }
    }

    // Правка 4.0 (07.10.2026, DESIGN §12.3, `screens/04`): сверху циферблат
    // суток и плашка времени, ниже — лента, дела, итоги по категориям и
    // телефон; строка «сказать» — внизу, со «стоп» и текущим занятием.
    val bedtimeMin by app.settings.todayBedtimeFlow.collectAsState(initial = ru.zf.pravka.data.Settings.TODAY_BEDTIME_DEFAULT)
    val day = remember(dayStart) { DayFacts.localDay(dayStart) }
    val facts = remember(entries, categories, day, now, bedtimeMin) {
        DayFacts.of(entries, categories, day, now, bedtimeMin)
    }
    val wide = ru.zf.pravka.ui.twoPane()
    val listState = rememberLazyListState()
    val openFromDial: (Long) -> Unit = { id ->
        val unit = dayUnits.firstOrNull { u -> u.fragments.any { it.id == id } || u.interruptions.any { it.id == id } }
        val head = unit?.fragments?.first()
        if (head != null) {
            if (head.source == "gap") gapFor = gapTargetOf(dayUnits, dayUnits.indexOf(unit), head) else sheetFor = head.id
        }
    }
    val dial: @Composable () -> Unit = {
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ru.zf.pravka.ui.Dial(
                sectors = facts.sectors,
                nowMin = facts.nowMin,
                score = Fmt.points(facts.score),
                size = if (wide) 256.dp else 308.dp,
                wide = wide,
                compare = facts.weekAgo?.let { w -> { ru.zf.pravka.ui.DialCompare(facts.score - w, Fmt.dayShort(facts.weekAgoDate)) } },
                place = if (wide) null else facts.rank?.let { "$it-й из 28 дней" },
                onSector = openFromDial,
            )
        }
    }
    val plate: @Composable () -> Unit = {
        ru.zf.pravka.ui.TimePlate(
            big = facts.big,
            sub = facts.sub,
            right = if (facts.aheadMin > 0) Fmt.durFuture(facts.aheadMin) else null,
            rightSub = if (facts.aheadMin > 0) "до сна в ${Fmt.hm(facts.bedtime)}" else null,
            parts = facts.barParts,
            legend = facts.legend,
            wide = wide,
            nav = {
                DayNav(
                    title = dayTitle(dayOffset, dayStart),
                    subtitle = Fmt.dayLong(day),
                    onPrev = { dayOffset += 1 },
                    onNext = if (dayOffset > 0) ({ dayOffset = (dayOffset - 1).coerceAtLeast(0) }) else null,
                )
            },
        )
    }
    // Лента — той же хроникой, что «Сегодня» (10.10.2026, владелец: «когда
    // открываю засечку, мне приходится немножко думать и переставлять свою
    // голову… давай синхронизируем»): записи по порядку дня сверху вниз,
    // дорога жизни с разметкой своих дней, «сейчас» с пульсирующей точкой.
    // Отметок других режимов и будущего здесь нет — у Засечки свои дела ниже;
    // дыры без записи между записями остались строкой «··· 25 м без записи».
    val ribbonItems = remember(entries, worthByCat, dayStart, now) {
        val dayEndMs = dayStart + 86_400_000L
        val ins = entries.filter { it.start < dayEndMs && (it.open || it.end > dayStart) }.map { e ->
            ru.zf.pravka.core.DayAssembler.EntryIn(
                id = e.id, start = e.start, end = e.end,
                title = e.title.ifBlank { e.raw.take(60) }.ifBlank { e.category },
                category = e.category, worth = worthOf(e.category),
                client = e.client, useful = e.useful, comment = e.comment,
                gap = e.source == "gap",
            )
        }
        ru.zf.pravka.core.DayAssembler.assemble(ru.zf.pravka.core.DayAssembler.Input(dayStart, now, ins)).items
            .filter { it is ru.zf.pravka.core.DayAssembler.DayItem.Entry || it is ru.zf.pravka.core.DayAssembler.DayItem.Now }
    }
    val ribbonLine = remember(ribbonItems, facts) { ru.zf.pravka.ui.balanceSpecs(ribbonItems, dayStart, facts.road) }
    val ribbon: @Composable () -> Unit = {
        Column(Modifier.fillMaxWidth()) {
            ribbonItems.forEachIndexed { i, item ->
                val next = ribbonItems.getOrNull(i + 1)
                ru.zf.pravka.ui.TimelineItem(
                    item, ru.zf.pravka.ui.lineColorAt(ribbonItems, i), next is ru.zf.pravka.core.DayAssembler.DayItem.Now, now,
                    onEntry = { openFromDial(it.id) },
                    balance = ribbonLine.getOrNull(i),
                )
                // Свежая дыра — тонкой строкой между записями: тап — «что это было».
                if (item is ru.zf.pravka.core.DayAssembler.DayItem.Entry && next is ru.zf.pravka.core.DayAssembler.DayItem.Entry) {
                    val gapMin = ((next.start - item.end) / 60_000L).toInt()
                    if (gapMin >= 5) {
                        val b = ribbonLine.getOrNull(i)
                        ru.zf.pravka.ui.HoleRow(
                            gapMin,
                            b?.let {
                                ru.zf.pravka.ui.BalanceSpec(
                                    it.bottom, it.bottom, it.color, it.color, ru.zf.pravka.core.BalanceLine.Kind.THROUGH,
                                    lanes = it.lanes?.let { l -> ru.zf.pravka.ui.Lanes(l.midBottom, l.midBottom, l.wallBottom, l.wallBottom) },
                                )
                            },
                        ) {
                            gapFor = GapTarget(
                                start = item.end,
                                end = next.start,
                                gap = null,
                                prev = entries.firstOrNull { it.id == item.id }?.takeIf { it.source != "gap" },
                                next = entries.firstOrNull { it.id == next.id }?.takeIf { it.source != "gap" },
                            )
                        }
                    }
                }
            }
            if (rangeEntries.isEmpty()) {
                ru.zf.pravka.ui.EmptyState("Записей за этот день нет", icon = Glyphs.Calendar)
            }
        }
    }
    val totals: @Composable () -> Unit = {
        val plus = facts.categories.filter { it.points > 0 }.sumOf { it.points }
        val minus = facts.categories.filter { it.points < 0 }.sumOf { it.points }
        PaperCard(
            label = "итоги",
            trailing = {
                Text(
                    "${Fmt.points(plus)} ${Fmt.MINUS} ${-minus}",
                    style = ru.zf.pravka.ui.LocalPravkaType.current.label,
                    color = ru.zf.pravka.ui.LocalMode.current.meta,
                    modifier = Modifier.padding(end = 6.dp),
                )
            },
            info = "Балл дня — сумма очков: час дела × ценность часа его категории " +
                "(правится в настройках Засечки). Плюс и минус в заголовке — отдельно: " +
                "ноль чаще всего честная ничья, час потерь съедает час работы. Строка — " +
                "категория: время, доля дня и очки; сон — только его часть внутри суток.",
        ) {
            val total = facts.categories.sumOf { it.minutes }.coerceAtLeast(1)
            val max = facts.categories.maxOfOrNull { it.minutes }?.coerceAtLeast(1) ?: 1
            facts.categories.forEach { c ->
                ru.zf.pravka.ui.CategoryRow(
                    name = c.name,
                    worth = c.worth,
                    minutes = c.minutes,
                    share = "${(c.minutes * 100f / total).toInt()} %",
                    points = c.points,
                    fraction = c.minutes / max.toFloat(),
                )
            }
            if (facts.categories.isEmpty()) ru.zf.pravka.ui.EmptyState("Пока пусто")
            if (facts.emptyCategories > 0) {
                Text(
                    "ещё ${facts.emptyCategories} без записей",
                    style = ru.zf.pravka.ui.LocalPravkaType.current.meta,
                    color = ru.zf.pravka.ui.LocalMode.current.meta,
                    modifier = Modifier.padding(top = 6.dp),
                )
            }
        }
    }
    val tasks: @Composable () -> Unit = {
        ZasechkaTasksCard(
            app = app,
            entries = entries,
            now = now,
            onComment = { commenting = it },
            onOpen = { openTask = it },
        )
    }

    Box(Modifier.fillMaxSize()) {
        if (wide) {
            // Разворот (§12.3): слева круг, плашка времени и итоги; справа лента.
            Row(Modifier.fillMaxSize()) {
                Column(
                    Modifier.width(386.dp).fillMaxHeight().fadingScroll().padding(start = 20.dp, bottom = 32.dp),
                    verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
                ) {
                    dial()
                    plate()
                    totals()
                    PhoneSection(app, dayStart, now)
                }
                Spacer(Modifier.width(24.dp))
                LazyColumn(
                    modifier = Modifier.weight(1f).fillMaxHeight().then(if (dayOffset == 0) Modifier.bottomFade() else Modifier).scrollFade(listState),
                    state = listState,
                    contentPadding = PaddingValues(end = 16.dp, bottom = 110.dp),
                    verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
                ) {
                    if (dayOffset == 0) item(key = "tasks") { tasks() }
                    item(key = "ribbon") { ribbon() }
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().then(if (dayOffset == 0) Modifier.bottomFade() else Modifier).scrollFade(listState),
                state = listState,
                contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 2.dp, bottom = 110.dp),
                verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap),
            ) {
                item(key = "dial") { dial() }
                item(key = "plate") { plate() }
                item(key = "ribbon") { ribbon() }
                if (dayOffset == 0) item(key = "tasks") { tasks() }
                item(key = "totals") { totals() }
                item(key = "phone") { PhoneSection(app, dayStart, now) }
            }
        }
        // Строка «сказать» Засечки (DESIGN §11.4): над подсказкой — текущее
        // занятие, слева «стоп», справа голос — тот же тап, что «З» на стекле.
        if (dayOffset == 0) {
            val open = entries.lastOrNull { it.open && it.source != "gap" }
            ru.zf.pravka.ui.SayBar(
                value = draft,
                onValueChange = { draft = it },
                placeholder = "Чем занят дальше?",
                onSend = submitText,
                onMic = {
                    val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                    if (service == null) Feedback.toast(context, context.getString(R.string.toast_no_service))
                    else service.onZasechkaTap()
                },
                sendEnabled = !processing && draft.isNotBlank(),
                maxLines = 1,
                busy = processing,
                busyLabel = "Разбираю",
                above = open?.let { e ->
                    {
                        ru.zf.pravka.ui.SayAbove(
                            "${capFirst(e.title.ifBlank { e.category })} · с ${fmtTime(e.start)}",
                            Fmt.durMs(now - e.start),
                        )
                    }
                },
                leading = open?.let { { ru.zf.pravka.ui.StopKey(doStop, "Остановить: ${it.title}") } },
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .then(if (wide) Modifier.padding(start = 410.dp) else Modifier)
                    .navigationBarsPadding()
                    .imePadding()
                    .padding(start = 12.dp, end = 12.dp, bottom = 18.dp),
            )
        }
    }

    // Клиент — из справочника Дел, свободный текст — запасом (docs/dela-server.md).
    val delaOn by app.delaServer.collectAsState()
    val delaSnap by app.delaStore.view.collectAsState()
    val delaProjects = if (delaOn) delaSnap.liveProjects() else emptyList()

    // Лист записи — то, что раньше висело значками на каждой строке. Первым
    // стоит «Поправить»: это и делал тап по строке до листа.
    sheetFor?.let { headId ->
        val unit = dayUnits.firstOrNull { it.fragments.first().id == headId }
        // Запись исчезла, пока лист открыт (удалили голосом, дело склеилось
        // с соседним) — лист закрывается сам, а не всплывает потом по отмене.
        LaunchedEffect(unit == null) { if (unit == null) sheetFor = null }
        if (unit != null) {
            val head = unit.fragments.first()
            val totalMs = unit.fragments.sumOf { it.durationMs(now) }
            EntrySheet(
                entry = head,
                subtitle = spanLine(
                    unit.start,
                    unit.fragments.last().end,
                    unit.open,
                    msToMin(totalMs),
                    net = unit.chain,
                ),
                points = pointsOf(worthOf(head.category), totalMs),
                pieces = unit.fragments.size,
                taskLabel = delaSnap.tasks[head.task]?.title.orEmpty(),
                onDismiss = { sheetFor = null },
                onEdit = {
                    sheetFor = null
                    if (unit.chain) editingChain = unit.fragments else editing = head
                },
                onComment = { sheetFor = null; commenting = head },
                onStop = if (unit.open) ({ sheetFor = null; doStop() }) else null,
                // У разрезанного дела удаляются все куски — как ✕ блока раньше.
                onDelete = {
                    sheetFor = null
                    app.appScope.launch { unit.fragments.forEach { store.delete(it.id) } }
                },
            )
        }
    }

    // Микрофон в редакторе: сказанное правит ИМЕННО эту запись — движок
    // получает её id и просит модель вернуть edit по ней, а не новое дело.
    val dictateEdit: (ZasechkaStore.Entry) -> Unit = { target ->
        val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
        if (service == null) Feedback.toast(context, context.getString(R.string.toast_no_service))
        else service.onZasechkaTap(editTargetId = target.id)
    }
    editing?.let { entry ->
        EditEntryDialog(
            entry = entry,
            categories = categoryNames,
            projects = delaProjects,
            taskTitle = delaSnap.tasks[entry.task]?.title.orEmpty(),
            onDismiss = { editing = null },
            onDictate = { editing = null; dictateEdit(entry) },
            onSave = { updated ->
                editing = null
                app.appScope.launch {
                    // Поменялся один комментарий — это не правка дела: без шага
                    // отмены, без «edit» в источнике и без обучения Засечки.
                    if (onlyCommentChanged(entry, updated)) store.setComment(entry.id, updated.comment)
                    else store.update(updated)
                    app.mirrorZasechkaComment(entry, entry.comment, updated.comment)
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
            projects = delaProjects,
            taskTitle = delaSnap.tasks[first.task]?.let { "${it.numLabel} ${it.title}" }.orEmpty(),
            onDismiss = { editingChain = null },
            onDictate = { editingChain = null; dictateEdit(first) },
            onSave = { updated ->
                editingChain = null
                app.appScope.launch {
                    app.mirrorZasechkaComment(first, first.comment, updated.comment)
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
                            project = updated.project,
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
                    // Запись из дела — дописанное уезжает и в само дело.
                    app.mirrorZasechkaComment(entry, entry.comment, text)
                    app.zasechkaSync.kickSoon(app.appScope)
                }
            },
        )
    }

    // Карточка дела из плашки «дела» — та же, что во вкладке «Дела».
    openTask?.let { picked ->
        val t = delaSnap.tasks[picked.id] ?: picked
        DelaTaskSheet(
            app = app,
            task = t,
            snap = delaSnap,
            isNew = false,
            onDismiss = { openTask = null },
            onSave = { after ->
                openTask = null
                val (set, was) = ru.zf.pravka.core.Dela.diff(t, after)
                if (set.length() > 0) app.appScope.launch { app.delaDo(listOf(ru.zf.pravka.core.Dela.setOp(t.id, set, was))) }
            },
            onStatus = { op ->
                openTask = null
                app.appScope.launch { app.delaDo(listOf(ru.zf.pravka.core.Dela.statusOp(op, t.id))) }
            },
            onStart = {
                openTask = null
                app.appScope.launch {
                    val entry = runCatching { app.zasechkaEngine.startTask(t) }.getOrNull()
                    Feedback.toast(app, if (entry != null) "⏱ ${entry.title}" else "Не смог записать дело")
                }
            },
        )
    }

    gapFor?.let { target ->
        GapDialog(
            target = target,
            now = now,
            onDismiss = { gapFor = null },
            onSay = {
                gapFor = null
                val service = ru.zf.pravka.trigger.PravkaAccessibilityService.instance
                if (service == null) Feedback.toast(context, context.getString(R.string.toast_no_service))
                // Якорь — границы дыры: сказанное ляжет ровно в неё (или с её
                // начала, если дыра живая), а не «сейчас».
                else service.onZasechkaTap(anchorStart = target.start, anchorEnd = target.end)
            },
            onType = { text ->
                gapFor = null
                app.appScope.launch {
                    val outcome = runCatching {
                        app.zasechkaEngine.record(text, "text", target.start, target.end)
                    }.getOrNull()
                    Feedback.toast(
                        app,
                        when {
                            outcome == null -> app.getString(R.string.z_record_failed)
                            outcome.action == "none" -> "🤷 ${outcome.say.ifBlank { "не про ленту" }}"
                            outcome.action == "insert" && outcome.error != null -> outcome.error
                            !outcome.categorized -> app.getString(R.string.z_saved_raw, outcome.error ?: "")
                            else -> "⏱ ${outcome.entry.title} ${fmtTime(outcome.entry.start)}" +
                                (if (outcome.entry.open) "" else "–${fmtTime(outcome.entry.end)}")
                        },
                    )
                }
            },
            onJoinPrev = {
                gapFor = null
                val prev = target.prev ?: return@GapDialog
                app.appScope.launch {
                    // Живая дыра — предыдущее дело просто ещё идёт: открываем
                    // его обратно, заполнитель уйдёт сам. Закрытая — предыдущее
                    // дело дотягивается до конца дыры.
                    store.update(prev.copy(end = if (target.end == 0L) 0L else target.end))
                    app.zasechkaSync.kickSoon(app.appScope)
                    Feedback.toast(
                        app,
                        if (target.end == 0L) "▶ «${prev.title}» снова идёт"
                        else "⏱ «${prev.title}» до ${fmtTime(target.end)}",
                    )
                }
            },
            onJoinNext = {
                gapFor = null
                val next = target.next ?: return@GapDialog
                app.appScope.launch {
                    store.update(next.copy(start = target.start))
                    app.zasechkaSync.kickSoon(app.appScope)
                    Feedback.toast(app, "⏱ «${next.title}» с ${fmtTime(target.start)}")
                }
            },
            // У заполнителя в строке тоже были 💬 и ✕ — они переехали сюда,
            // низом листа. У «··· N без записи» записи нет, и их нет.
            onComment = target.gap?.let { gap -> { gapFor = null; commenting = gap } },
            onDelete = target.gap?.let {
                {
                    gapFor = null
                    app.appScope.launch { target.pieces.forEach { store.delete(it.id) } }
                }
            },
        )
    }
}

/**
 * Дыра в ленте, по которой тапнул владелец: заполнитель «не размечено» или
 * «··· N мин без записи» между делами. [gap] — сама запись-заполнитель (у
 * свежей дыры её ещё нет), [prev] и [next] — соседи по времени, к которым
 * дыру можно присоединить; [end] = 0 — дыра живая, тикает до сейчас.
 * [pieces] — все куски заполнителя, если он собран в цепочку: удаляются вместе.
 */
private data class GapTarget(
    val start: Long,
    val end: Long,
    val gap: ZasechkaStore.Entry?,
    val prev: ZasechkaStore.Entry?,
    val next: ZasechkaStore.Entry?,
    val pieces: List<ZasechkaStore.Entry> = listOfNotNull(gap),
)

/** Соседи заполнителя по времени: список единиц дня идёт от новых к старым. */
private fun gapTargetOf(units: List<DayUnit>, index: Int, gap: ZasechkaStore.Entry): GapTarget {
    val prev = units.getOrNull(index + 1)?.fragments?.last()?.takeIf { it.source != "gap" }
    val next = if (gap.open) null else units.getOrNull(index - 1)?.fragments?.first()?.takeIf { it.source != "gap" }
    return GapTarget(
        start = gap.start,
        end = gap.end,
        gap = gap,
        prev = prev,
        next = next,
        pieces = units.getOrNull(index)?.fragments ?: listOf(gap),
    )
}

/**
 * Что делать с дырой. Владелец (08.09.2026): «нажать прямо на розовое „не
 * размечено“, и там выбор: либо сказать, что это было, либо присоединить к
 * предыдущему — очень часто что-то просто не дозаписалось или обрубилось».
 * Третий выход — к следующему: обрубается и начало.
 *
 * С 24.09.2026 — лист набора. «Сказать» — микрофон строки ввода (тот же
 * тап «З» с якорем-интервалом), выходы к соседям — кнопками со значками
 * вместо ▶ ◀ в подписи. Тап по «не размечено» открывает этот лист сразу, а не
 * общий лист записи: выбор — ровно то, за чем сюда тапают.
 */
@Composable
private fun GapDialog(
    target: GapTarget,
    now: Long,
    onDismiss: () -> Unit,
    onSay: () -> Unit,
    onType: (String) -> Unit,
    onJoinPrev: () -> Unit,
    onJoinNext: () -> Unit,
    onComment: (() -> Unit)?,
    onDelete: (() -> Unit)?,
) {
    var text by remember { mutableStateOf("") }
    val live = target.end == 0L
    val endMs = if (live) now else target.end
    PaperSheet(
        onDismiss = onDismiss,
        title = if (live) "Не размечено с ${fmtTime(target.start)}"
        else "Не размечено ${fmtTime(target.start)}–${fmtTime(target.end)}",
        icon = Glyphs.Timer,
        subtitle = fmtDurLong(msToMin(endMs - target.start)) + (if (live) ", идёт" else ""),
    ) {
        Text("Что это было?", style = MaterialTheme.typography.bodyMedium)
        VoiceInput(
            value = text,
            onValueChange = { text = it },
            placeholder = "набрать — или «П» надиктует сюда",
            onSend = { onType(text.trim()) },
            onMic = onSay,
            maxLines = 1,
        )
        PaperHint(
            if (live) "Голосом или текстом — ляжет с ${fmtTime(target.start)}"
            else "Голосом или текстом — ляжет ровно в ${fmtTime(target.start)}–${fmtTime(target.end)}"
        )
        if (target.prev != null || target.next != null) {
            Text(
                if (live) "Или это всё ещё то же дело:" else "Или это обрывок соседнего дела:",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            target.prev?.let { prev ->
                PaperButton(
                    if (live) "Продолжить «${capFirst(prev.title)}»"
                    else "К предыдущему: «${capFirst(prev.title)}»",
                    onClick = onJoinPrev,
                    modifier = Modifier.fillMaxWidth(),
                    icon = if (live) Glyphs.Play else Glyphs.Back,
                )
            }
            target.next?.let { next ->
                PaperButton(
                    "К следующему: «${capFirst(next.title)}»",
                    onClick = onJoinNext,
                    modifier = Modifier.fillMaxWidth(),
                    icon = Glyphs.Forward,
                )
            }
        }
        if (onComment != null || onDelete != null) {
            RowRule()
            IconActionRow {
                if (onComment != null) {
                    IconAction(
                        Glyphs.Note,
                        "Заметка",
                        onClick = onComment,
                        active = target.gap?.comment?.isNotBlank() == true,
                    )
                }
                if (onDelete != null) IconAction(Glyphs.Delete, "Удалить", onClick = onDelete)
            }
        }
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


/**
 * Маленький круглый «стоп» у идущего дела — единственное действие, что
 * осталось в строке ленты (24.09.2026): остановить идущее — самое частое, и
 * лезть за ним в лист было бы на шаг дольше. Красный, как квадратик раньше.
 */
@Composable
private fun StopButton(onStop: () -> Unit) {
    PaperIconButton(
        Glyphs.Stop,
        "остановить",
        onClick = onStop,
        size = 30.dp,
        tint = MaterialTheme.colorScheme.error,
    )
}

@Composable
private fun EntryRow(
    entry: ZasechkaStore.Entry,
    now: Long,
    worthOf: (String) -> Int,
    onStop: (() -> Unit)?,
    onClick: () -> Unit,
) {
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .then(
                if (entry.open) Modifier.runningNow() else Modifier
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
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
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 6.dp)) {
            val title = buildString {
                append(capFirst(entry.title.ifBlank { entry.raw.take(60) }))
                if (entry.client.isNotBlank()) append(" · ${entry.client}")
                if (entry.useful > 0) append(" ★${entry.useful}")
                if (entry.pomodoros > 0) append(" 🍅×${entry.pomodoros}")
            }
            Text(
                title,
                style = MaterialTheme.typography.bodyMedium,
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
                SourceMark(entry)
                val pts = pointsOf(worthOf(entry.category), entry.durationMs(now))
                if (pts != 0) {
                    DotSep()
                    PointsChip(pts)
                }
            }
            CommentLine(entry.comment)
        }
        if (onStop != null) {
            StopButton(onStop)
            Spacer(Modifier.width(4.dp))
        }
    }
}

// The whole interrupted activity as one block: the time column shows the full
// span, one tall line runs beside it, the header line carries the NET Σ
// (interruptions excluded). Тап по блоку открывает лист всего дела: правка
// и удаление там действуют на ВСЕ куски.
@Composable
private fun ChainBlock(
    unit: DayUnit,
    now: Long,
    worthOf: (String) -> Int,
    onStop: (() -> Unit)?,
    onClick: () -> Unit,
) {
    val head = unit.fragments.first()
    val last = unit.fragments.last()
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .height(IntrinsicSize.Min)
            .then(
                if (unit.open) Modifier.runningNow() else Modifier
            )
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
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
        // Те же две строки, что у одиночной записи; время — НЕТТО по
        // всем фрагментам дела (жирным: это сумма).
        Column(Modifier.weight(1f).padding(start = 8.dp, end = 6.dp)) {
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
                SourceMark(head)
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
            // Врезки внутри дела (сон, тренировка с часов, разрезавшие его) в
            // ленте отдельными строками не рисуются — владелец: «очень сильно
            // засоряет». Они остаются в данных и в выгрузках; тап по блоку
            // правит дело целиком. Параллельного трека больше нет: телефон
            // считается по дням и виден строкой у итогов дня.
        }
        if (onStop != null) {
            StopButton(onStop)
            Spacer(Modifier.width(4.dp))
        }
    }
}

/**
 * Лист записи — всё, что раньше висело значками на каждой строке ленты
 * (стоп, 💬, ✎, ✕ по 30dp). Владелец (24.09.2026): «лента дышит» — в строке
 * время, дело и категория, действия — по тапу. «Поправить» первым: это и
 * делал тап по строке до листа. У разрезанного дела [entry] — его голова,
 * правка и удаление действуют на все [pieces] кусков, заметка — у головы.
 */
@Composable
private fun EntrySheet(
    entry: ZasechkaStore.Entry,
    subtitle: String,
    points: Int,
    pieces: Int,
    /** «#57 Наташа: сверка» — запись начата из дела; пусто — нет. */
    taskLabel: String = "",
    onDismiss: () -> Unit,
    onEdit: () -> Unit,
    onComment: () -> Unit,
    onStop: (() -> Unit)?,
    onDelete: () -> Unit,
) {
    PaperSheet(
        onDismiss = onDismiss,
        title = capFirst(entry.title.ifBlank { entry.raw.take(60) }.ifBlank { "без названия" }),
        icon = Glyphs.Zasechka,
        subtitle = subtitle,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            CategoryTag(entry.category)
            if (entry.client.isNotBlank()) {
                DotSep()
                Text(
                    entry.client,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            if (points != 0) {
                DotSep()
                PointsChip(points, bold = true)
            }
        }
        if (pieces > 1) {
            PaperHint("Кусков: $pieces — правка и удаление действуют на всё дело целиком.")
        }
        // Откуда запись: из дела (комментарий уедет и в дело) или из календаря.
        if (taskLabel.isNotBlank()) PaperHint("Из дела: $taskLabel — заметка уедет и в дело")
        else if (entry.source == ru.zf.pravka.core.CalendarRules.SOURCE) PaperHint("Из календаря — по концу события закроется сама")
        // Комментарий целиком: в ленте видны только две первые строки.
        if (entry.comment.isNotBlank()) {
            Text(entry.comment, style = MaterialTheme.typography.bodyMedium)
        }
        if (entry.raw.isNotBlank() && entry.raw != entry.title) {
            PaperHint("Надиктовано: «${entry.raw.take(200)}»")
        }
        IconActionRow {
            IconAction(Glyphs.Edit, "Поправить", onClick = onEdit)
            // Заметка горит, когда слова уже есть, — как баббл 💬 в строке раньше.
            IconAction(Glyphs.Note, "Заметка", onClick = onComment, active = entry.comment.isNotBlank())
            if (onStop != null) IconAction(Glyphs.Stop, "Стоп", onClick = onStop)
            IconAction(Glyphs.Delete, "Удалить", onClick = onDelete)
        }
    }
}

// ---------------------------------------------------------------------------
// Настройки Засечки: напоминания, словари категорий и клиентов, правила
// разбора, автопилот, метки NFC. С 24.09.2026 кнопка «З» — в «Кнопках на
// экране» (все четыре тумблера рядом), таблица и intervals.icu — в
// «Подключениях» (`ZasechkaSheetsSettings`, `IntervalsSettings` ниже),
// резервные копии ленты — в «Обновлениях и службе».
// ---------------------------------------------------------------------------

@Composable
internal fun ZasechkaSettings(app: PravkaApp) {
    // Настройки открываются и БЕЗ захода в Засечку — стор мог быть не прочитан,
    // и редактор категорий показал бы пустоту. Отредактировать пустоту и
    // сохранить — значит затереть настоящие категории; загрузка обязана
    // случиться раньше.
    LaunchedEffect(Unit) { app.zasechkaStore.all() }
    val categories by app.zasechkaStore.categoriesFlow.collectAsState()
    val clients by app.zasechkaStore.clientsFlow.collectAsState()

    val gapMin by app.settings.zGapMinFlow.collectAsState(initial = 45)
    val dayStartH by app.settings.zDayStartFlow.collectAsState(initial = 9)
    val dayEndH by app.settings.zDayEndFlow.collectAsState(initial = 23)
    val checkins by app.settings.zCheckinsFlow.collectAsState(initial = true)
    val quiet by app.zQuietFlow.collectAsState(initial = false)
    // Ползунки пишут по отпусканию: запись DataStore на каждый шаг
    // перетаскивания — десятки записей файла за секунду.
    var gapSlider by remember(gapMin) { mutableStateOf(gapMin.toFloat()) }
    var startSlider by remember(dayStartH) { mutableStateOf(dayStartH.toFloat()) }
    var endSlider by remember(dayEndH) { mutableStateOf(dayEndH.toFloat()) }

    Column(verticalArrangement = Arrangement.spacedBy(ScreenPad.Gap)) {
        PaperCard(label = "напоминания") {
            // Первым — одним тумблером всё сразу (08.10.2026): Марианне Засечка
            // нужна как журнал сна и тренировок, а не как собеседник.
            PaperToggle(
                title = "Засечка молча",
                checked = quiet,
                onCheckedChange = { app.appScope.launch { app.settings.setZQuiet(it) } },
                hint = if (quiet) "сон, тренировки и экран пишутся сами — без вопросов" else null,
                info = "Лента пишется сама: сон по зарядке и экрану, сон и тренировки с часов через " +
                    "intervals, экран по дням. Ни напоминаний о дырах, ни «всё ещё …?», ни вопросов " +
                    "автопилота, ни дел за тебя — по подъёму («сборы детей»), звонку или календарю. " +
                    "С завода молчит у всех, кроме Саши.",
            )
            val gapShown = (gapSlider / 15f).roundToInt() * 15
            PaperSlider(
                title = "Дыра во времени",
                valueText = if (gapShown > 0) "через $gapShown мин" else "выключено",
                value = gapSlider,
                onValueChange = { gapSlider = it },
                onValueChangeFinished = {
                    app.appScope.launch { app.settings.setZGapMin((gapSlider / 15f).roundToInt() * 15) }
                },
                valueRange = 0f..120f,
                steps = 7,
            )
            PaperSlider(
                title = "День начинается",
                valueText = "${startSlider.roundToInt()}:00",
                value = startSlider,
                onValueChange = { startSlider = it },
                onValueChangeFinished = { app.appScope.launch { app.settings.setZDayStart(startSlider.roundToInt()) } },
                valueRange = 0f..12f,
                steps = 11,
                info = "Активные часы: утром кнопка спрашивает «день начался?», после конца — «закрыть день?». " +
                    "Вне этих часов о дырах не напоминает.",
            )
            PaperSlider(
                title = "День кончается",
                valueText = "${endSlider.roundToInt()}:00",
                value = endSlider,
                onValueChange = { endSlider = it },
                onValueChangeFinished = { app.appScope.launch { app.settings.setZDayEnd(endSlider.roundToInt()) } },
                valueRange = 12f..24f,
                steps = 11,
            )
            PaperToggle(
                title = "Спрашивать «всё ещё …?»",
                checked = checkins,
                onCheckedChange = { app.appScope.launch { app.settings.setZCheckins(it) } },
                info = "Когда дело идёт дольше базового времени своей категории, кнопка моргает и " +
                    "спрашивает. «Да» — считаем дальше, «Нет» — сразу новая запись.",
            )
        }

        // Пояснения разделов — за «i» в подписи плашки (24.09.2026); внутри
        // заголовки-дубли «Категории», «Клиенты и проекты» сняты.
        PaperCard(
            label = "категории",
            info = "Сонет выбирает строго из этого списка; пояснение — подсказка ему. Тап — править: " +
                "там же базовое время («всё ещё …?» после него) и ценность часа от −10 до +10, " +
                "из которой складывается баланс дня.",
        ) {
            CategoriesEditor(
                categories = categories,
                onChange = { app.appScope.launch { app.zasechkaStore.setCategories(it) } },
            )
        }
        PaperCard(label = "клиенты и проекты", info = "Помогают распознаванию и попадают в отчёты.") {
            EditableList(
                values = clients,
                onChange = { app.appScope.launch { app.zasechkaStore.setClients(it) } },
            )
        }

        // Плашку «правила разбора» раздел рисует сам: пустой набор не
        // показывается вовсе.
        ZasechkaRulesSection(app)

        // Своей выгрузки у ленты больше нет (09.09): вся жизнь одним xlsx,
        // лист «Засечка» как база в Notion, — за значком выгрузки в Статистике.

        PaperCard(label = "автопилот", info = AUTOPILOT_INFO) { AutoPilotSection(app) }
        PaperCard(label = "метки nfc", info = NFC_INFO) { NfcTagsSection(app) }
    }
}

/**
 * Google Sheets — зеркало ленты в таблицу. Живёт в «Подключениях» рядом с
 * остальными ключами: искать адрес скрипта в настройках Засечки было
 * «а где это было?».
 */
@Composable
internal fun ZasechkaSheetsSettings(app: PravkaApp) {
    LaunchedEffect(Unit) { app.zasechkaStore.all() }
    val syncStatus by app.zasechkaSync.statusFlow.collectAsState()
    val entries by app.zasechkaStore.entriesFlow.collectAsState()
    val webhook by app.settings.zWebhookFlow.collectAsState(initial = "")
    var url by remember(webhook) { mutableStateOf(webhook) }
    val pending = entries.count { !it.open && !it.synced }

    PaperCard(
        label = "таблица",
        info = "Скрипт и настройка за пять минут — docs/zasechka-sheets.md в репозитории. " +
            "Закрытые записи уходят в таблицу сами; «Синхронизировать» толкает очередь сейчас.",
    ) {
        PaperField(value = url, onValueChange = { url = it }, label = "URL веб-приложения Apps Script")
        if (syncStatus.isNotBlank()) PaperHint("Последняя отправка: $syncStatus")
        if (pending > 0) PaperHint("Ждут отправки: $pending")
        Spacer(Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
            PaperButton("Синхронизировать", icon = Glyphs.Refresh, onClick = {
                app.appScope.launch {
                    val result = app.zasechkaSync.syncNow()
                    result.onSuccess { n ->
                        Feedback.toast(app, if (n > 0) "Отправлено строк: $n" else "Всё уже в таблице")
                    }.onFailure { e ->
                        Feedback.toast(app, "Не удалось: ${e.message}")
                    }
                }
            })
            Spacer(Modifier.weight(1f))
            PaperButton("Сохранить", primary = true, enabled = url.trim() != webhook, onClick = {
                app.appScope.launch {
                    app.settings.setZWebhook(url)
                    Feedback.toast(app, "Сохранено")
                }
            })
        }
    }
}

/**
 * intervals.icu: тренировки двух последних суток встают в ленту, сон Garmin
 * дописывается к «сну», спорт и еда пишут туда своё. Ключ — один на всё
 * приложение, поэтому в «Подключениях», а не в Засечке.
 */
@Composable
internal fun IntervalsSettings(app: PravkaApp) {
    val icuAthlete by app.settings.icuAthleteFlow.collectAsState(initial = "")
    val icuKey by app.settings.icuKeyFlow.collectAsState(initial = "")
    var athleteField by remember(icuAthlete) { mutableStateOf(icuAthlete) }
    var keyField by remember(icuKey) { mutableStateOf(icuKey) }
    val icuProfile by app.sportStore.profileFlow.collectAsState()
    PaperCard(
        label = "ключ",
        info = "Тренировки за последние двое суток сами встают в ленту (бег, вело, силовая, ходьба), " +
            "а Garmin-длительность сна дописывается к записи «сон»; вес, пульс покоя и HRV — во " +
            "вкладке «Спорт». Часы и весы Garmin приходят в intervals.icu сами: там Settings → " +
            "Connections → Garmin Connect. Ключ: intervals.icu → Settings → Developer Settings → " +
            "API Key, Athlete ID — там же (i и цифры). Ключ — свой у каждого: чужой ключ показывает " +
            "чужие тренировки и пишет подходы и еду в чужой intervals.",
    ) {
        // Чей это аккаунт — словами (08.10.2026): на телефоне Марианны стоял
        // Сашин ключ, и вкладка показывала его тренировки как её.
        if (icuProfile.athleteName.isNotBlank()) {
            PaperHint(
                "Аккаунт: ${icuProfile.athleteName}" +
                    (if (icuProfile.weightKg > 0) " · вес в профиле ${"%.1f".format(icuProfile.weightKg)} кг" else "") +
                    " — его тренировки, сон и вес видит приложение",
            )
            Spacer(Modifier.height(6.dp))
        }
        PaperField(value = athleteField, onValueChange = { athleteField = it }, label = "Athlete ID (i…)")
        PaperField(value = keyField, onValueChange = { keyField = it }, label = "API Key")
        Spacer(Modifier.height(6.dp))
        Row {
            Spacer(Modifier.weight(1f))
            PaperButton("Сохранить и проверить", primary = true, onClick = {
                app.appScope.launch {
                    app.settings.setIcuAthlete(athleteField)
                    app.settings.setIcuKey(keyField)
                    Feedback.toast(app, "Сохранено — тренировки подтянутся в ближайший свип")
                    // Глубокая выгрузка сразу: имя аккаунта встаёт строкой выше, а
                    // сменился аккаунт — чужой кэш уходит (IcuSportSync.checkAccount).
                    runCatching { app.icuSportSync.refresh(force = true) }
                    app.icuSweeper.sweep(force = true)
                }
            })
        }
    }
}

/** Пояснение плашки «автопилот» — за её «i» в настройках Засечки (24.09.2026). */
private const val AUTOPILOT_INFO =
    "Wi-Fi-места, Bluetooth машины и датчик движения: приезд закрывает " +
        "передвижение сам, остальное — вопросом-пушем. Имя Wi-Fi система отдаёт " +
        "только с разрешением «Местоположение» (GPS при этом не включается), имя " +
        "Bluetooth-устройства на Android 12+ — с «Устройствами рядом»."

/** Как автопилот узнаёт место и что делает по приезду — за «i» у «Мест». */
private const val PLACES_INFO =
    "У каждого места два способа узнать приезд. «подключился» — " +
        "точнее, так ловится дом. «вижу сеть» — для мест вроде Летово, " +
        "к чьему Wi-Fi ты не подключаешься: приезд засчитывается, как " +
        "только сеть появилась в эфире. У места может быть дело по " +
        "приезду (карандаш): закрыв дорогу, автопилот сам начнёт его; не то — " +
        "«Сказать» в пуше заменит.\n\n" +
        "Новые сети: подключись к домашнему Wi-Fi — он появится здесь (и придёт " +
        "пуш «что это за место?»). Сети, которые просто слышно рядом, " +
        "подтягиваются сами раз в полчаса."

/**
 * Подзаголовок части внутри плашки («Места», «Машина») — со своим «i», если
 * есть что пояснить (24.09.2026): длинный абзац под заголовком раньше стоял
 * прямо в плашке и читался инструкцией, а не настройкой.
 */
@Composable
private fun SubHead(text: String, info: String? = null) {
    Row(
        Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 2.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text,
            style = MaterialTheme.typography.labelLarge,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.weight(1f),
        )
        if (info != null) InfoButton(text, info, size = 30.dp)
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

    // Пояснение раздела — за «i» у плашки (AUTOPILOT_INFO), здесь сразу дело.
    var permTick by remember { mutableStateOf(0) }
    val askPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { permTick++ }

    val places by settings.autoPlacesFlow.collectAsState(initial = emptyMap<String, String>())
    val visibleSsids by settings.autoVisibleFlow.collectAsState(initial = emptySet<String>())
    val carBt by settings.autoCarBtFlow.collectAsState(initial = "")
    val calOn by settings.autoCalOnFlow.collectAsState(initial = true)

    // Что автопилот видит ПРЯМО СЕЙЧАС. Первая версия молчала, и понять это
    // было нельзя ниоткуда — теперь состояние на виду.
    val pilot = ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.autoPilot
    Text(
        pilot?.statusLine() ?: "Служба выключена — автопилот спит",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
    )

    // Почему молчит — словами и с кнопкой. Раньше на это место приходила
    // одна кнопка «дать доступ», и она врала: доступ был выдан «только при
    // использовании», а служба всё равно не видела ни одной сети.
    val blockers = remember(permTick, carBt, calOn) {
        ru.zf.pravka.trigger.AutoPilot.blockers(context, carBt, calOn)
    }
    // Разрешение на уведомления просим один раз; если система его уже не
    // покажет (отказано дважды) — вторая кнопка ведёт в настройки Правки.
    var notifAsked by remember { mutableStateOf(false) }
    for (b in blockers) {
        Spacer(Modifier.height(8.dp))
        Text(
            "⚠ " + b.text,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error,
        )
        Spacer(Modifier.height(4.dp))
        PaperButton(
            when (b.fix) {
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION -> "Дать доступ к имени Wi-Fi"
                ru.zf.pravka.trigger.AutoPilot.FIX_BACKGROUND -> "Открыть разрешения Правки"
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION_SYS -> "Включить геолокацию"
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF -> "Разрешить уведомления"
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF_CHANNEL -> "Открыть канал уведомлений"
                ru.zf.pravka.trigger.AutoPilot.FIX_BT -> "Дать доступ к Bluetooth-устройствам"
                ru.zf.pravka.trigger.AutoPilot.FIX_CALENDAR -> "Дать доступ к календарю"
                else -> "Включить Wi-Fi"
            },
            icon = when (b.fix) {
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION,
                ru.zf.pravka.trigger.AutoPilot.FIX_LOCATION_SYS -> Glyphs.Place
                ru.zf.pravka.trigger.AutoPilot.FIX_BACKGROUND -> Glyphs.Key
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF,
                ru.zf.pravka.trigger.AutoPilot.FIX_NOTIF_CHANNEL -> Glyphs.Bell
                ru.zf.pravka.trigger.AutoPilot.FIX_BT -> Glyphs.Car
                ru.zf.pravka.trigger.AutoPilot.FIX_CALENDAR -> Glyphs.Calendar
                else -> Glyphs.Wifi
            },
            onClick = {
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
                    ru.zf.pravka.trigger.AutoPilot.FIX_CALENDAR ->
                        askPermission.launch(android.Manifest.permission.READ_CALENDAR)
                    else -> context.startActivity(
                        android.content.Intent(android.provider.Settings.ACTION_WIFI_SETTINGS)
                            .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                    )
                }
            },
        )
    }
    if (blockers.isNotEmpty()) {
        PaperTextButton("Проверить ещё раз", onClick = { permTick++ }, icon = Glyphs.Refresh)
    }

    // Вопросы — плашкой сверху (28.09.2026): пуш тогда тихий, в шторке.
    val platesOn by settings.autoPlatesFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Вопросы автопилота — плашкой сверху",
        checked = platesOn,
        onCheckedChange = { on -> scope.launch { settings.setAutoPlates(on) } },
        info = "Экран включён и открыт, пилюля свободна — вопрос встаёт строкой пилюли «З» " +
            "с теми же кнопками, а пуш ложится в шторку тихо, без баннера: копия на потом. " +
            "Ответил на плашке — пуш снимается, нажал в пуше — плашка уходит. Экран заперт " +
            "или пилюля занята — громкий пуш, как раньше. Плашку можно смахнуть вбок.",
    )

    // ---- Места по Wi-Fi ----
    // Сети, которые служба уже видела: владелец называет каждую местом
    // («это дача») — ровно то, что он просил, вместо угадывания SSID.
    val seen by settings.autoSeenFlow.collectAsState(initial = emptyMap<String, Long>())
    var namingSsid by remember { mutableStateOf<String?>(null) }
    var placeName by remember { mutableStateOf("") }
    val unnamed = seen.keys.filter { !places.containsKey(it) }
        .sortedByDescending { seen[it] ?: 0L }

    // Дело места по приезду: закрыв дорогу, автопилот сам начинает его
    // («Летово» → «Забираю Серёжу»). Ключ — имя места, см. Settings.
    val deals by settings.autoPlaceDealsFlow.collectAsState(initial = emptyMap<String, PlaceDeal>())
    val dealCategories by app.zasechkaStore.categoriesFlow.collectAsState()
    var dealPlace by remember { mutableStateOf<String?>(null) }
    SubHead("Места", info = PLACES_INFO)
    for ((ssid, name) in places) {
        val byAir = visibleSsids.contains(ssid)
        val deal = AutoPilotRules.dealFor(name, deals)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text("$name — $ssid", style = MaterialTheme.typography.bodyMedium)
                Text(
                    if (deal != null) {
                        "по приезду: «${deal.title}»" +
                            (if (deal.category.isNotBlank()) " [${deal.category}]" else "")
                    } else "по приезду — спросить, что делаешь",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            GlyphButton(
                Glyphs.Edit,
                if (deal != null) "изменить дело по приезду" else "задать дело по приезду",
                onClick = { dealPlace = name },
                size = 36.dp,
            )
            GlyphButton(
                Glyphs.Delete,
                "убрать место",
                onClick = { scope.launch { settings.removeAutoPlace(ssid) } },
                size = 36.dp,
            )
        }
        // Что считать приездом — выбором из двух, а не кнопкой-переключателем
        // со словом текущего режима: так видно и что выбрано, и что ещё есть.
        ChipRow(Modifier.padding(top = 4.dp, bottom = 8.dp)) {
            PaperChip("подключился", selected = !byAir, onClick = {
                if (byAir) scope.launch { settings.setAutoVisible(ssid, false) }
            })
            PaperChip("вижу сеть", selected = byAir, onClick = {
                if (!byAir) scope.launch { settings.setAutoVisible(ssid, true) }
            })
        }
    }
    dealPlace?.let { place ->
        PlaceDealDialog(
            place = place,
            current = AutoPilotRules.dealFor(place, deals),
            categories = dealCategories.map { it.name },
            onDismiss = { dealPlace = null },
            onSave = { title, category ->
                dealPlace = null
                scope.launch { settings.setAutoPlaceDeal(place, title, category) }
            },
        )
    }

    if (unnamed.isEmpty()) {
        PaperHint(if (places.isEmpty()) "Новых сетей пока не видел." else "Все увиденные сети названы.")
    } else {
        Text(
            "Что это за сети?",
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.padding(top = 6.dp),
        )
        for (ssid in unnamed) {
            if (namingSsid == ssid) {
                val done: () -> Unit = {
                    val name = placeName.trim()
                    if (name.isNotBlank()) {
                        scope.launch {
                            settings.addAutoPlace(ssid, name)
                            settings.removeAutoSeen(ssid)
                        }
                    }
                    namingSsid = null
                    placeName = ""
                }
                PaperField(
                    value = placeName,
                    onValueChange = { placeName = it },
                    label = "«$ssid» — это…",
                    trailing = {
                        GlyphButton(Glyphs.Check, "готово", onClick = done, tint = MaterialTheme.colorScheme.primary)
                    },
                )
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
                    PaperTextButton("Это место…", onClick = { namingSsid = ssid; placeName = "" })
                    PaperTextButton("Не место", onClick = { scope.launch { settings.removeAutoSeen(ssid) } })
                }
            }
        }
    }
    val autoArrive by settings.autoArriveFlow.collectAsState(initial = true)
    val leaveAsk by settings.autoLeaveAskFlow.collectAsState(initial = true)
    val carAsk by settings.autoCarAskFlow.collectAsState(initial = true)
    val carStart by settings.autoCarStartFlow.collectAsState(initial = true)
    val stillAsk by settings.autoStillAskFlow.collectAsState(initial = true)
    // Тумблеры — рядом с тем, чем они правят (24.09.2026): приезд и отъезд —
    // у мест, поездка — у машины, «точно ещё» — отдельно.
    Spacer(Modifier.height(4.dp))
    PaperToggle(
        title = "Приезд в место закрывает передвижение",
        checked = autoArrive,
        onCheckedChange = { on -> scope.launch { settings.setAutoArrive(on) } },
    )
    val walkStart by settings.autoWalkStartFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Спрашивать при отъезде из места",
        checked = leaveAsk,
        onCheckedChange = { on -> scope.launch { settings.setAutoLeaveAsk(on) } },
        info = "Вопрос приходит через десять минут после потери сети: вышел вынести мусор и " +
            "вернулся раньше — ни вопроса, ни разрыва в деле. Машина и вело с часов в эти " +
            "двадцать минут склеиваются с выходом: дорога начинается у двери. Сказал на " +
            "выходе, что делаешь («пошёл развозить детей», «иду к другу»), — не спрашивает " +
            "и поездку поверх не кладёт: за десять минут до потери сети, а со словами " +
            "выхода — за полчаса.",
    )
    PaperToggle(
        title = "Двадцать минут в движении после отъезда — пешком",
        checked = walkStart,
        onCheckedChange = { on -> scope.launch { settings.setAutoWalkStart(on) } },
        info = "Сеть места пропала, машина не подключилась, нового места не видно, а телефон " +
            "двадцать минут толкается — «Дорога пешком» начинается с момента отъезда сама. " +
            "Такси и метро толкают так же: в пуше кнопка «Транспорт».",
    )

    // ---- Машина по Bluetooth ----
    SubHead("Машина")
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
                PaperTextButton("Убрать", onClick = { scope.launch { settings.setAutoCarBt("") } })
            }
        }
        btPermTick -> {
            PaperButton(
                "Дать доступ к Bluetooth-устройствам",
                icon = Glyphs.Car,
                onClick = { askPermission.launch(android.Manifest.permission.BLUETOOTH_CONNECT) },
            )
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
                PaperHint("Спаренных Bluetooth-устройств не видно.")
            } else {
                Text("Какое устройство — машина?", style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(6.dp))
                ChipRow {
                    for ((name, addr) in bonded) {
                        PaperChip(
                            name,
                            selected = false,
                            onClick = { scope.launch { settings.setAutoCarBt(name, addr) } },
                        )
                    }
                }
            }
        }
    }
    Spacer(Modifier.height(4.dp))
    PaperToggle(
        title = "Машина подключилась — сразу начать «Поездку на машине»",
        checked = carStart,
        onCheckedChange = { on -> scope.launch { settings.setAutoCarStart(on) } },
        info = "Поездка стартует с момента подключения и закрывает текущее дело; в пуше " +
            "есть «Отменить». Отключилась машина — через две минуты вопрос «приехал?». " +
            "Приезд в место с открытой дорогой закрывает её и спрашивает, что теперь.",
    )
    if (!carStart) {
        PaperToggle(
            title = "…или хотя бы спросить «сел в машину?»",
            checked = carAsk,
            onCheckedChange = { on -> scope.launch { settings.setAutoCarAsk(on) } },
        )
    }

    // ---- Движение ----
    SubHead("Движение")
    PaperToggle(
        title = "«Всё ещё …?», когда телефон задвигался",
        checked = stillAsk,
        onCheckedChange = { on -> scope.launch { settings.setAutoStillAsk(on) } },
        info = "Взял телефон и пошёл — значит, началось другое дело. Датчик значимого " +
            "движения после двадцати минут любого дела, кроме дороги, спорта, быта и сна. " +
            "Первый толчок — не вопрос: десять минут считаются остальные, и спрашивает, " +
            "только если ты так и не сел; отъезд после толчка спросит вопрос «уехал?». Не " +
            "чаще раза в двадцать минут. Выключено — не спрашивает вовсе.",
    )

    // ---- Календарь ----
    SubHead("Календарь", info = CALENDAR_INFO)
    PaperToggle(
        title = "Встречи из календаря — в ленту сами",
        checked = calOn,
        onCheckedChange = { on -> scope.launch { settings.setAutoCalOn(on) } },
    )
    if (calOn) {
        val calPerm = remember(permTick) { ru.zf.pravka.trigger.CalendarPilot.hasPermission(context) }
        if (!calPerm) {
            PaperButton(
                "Дать доступ к календарю",
                icon = Glyphs.Calendar,
                onClick = { askPermission.launch(android.Manifest.permission.READ_CALENDAR) },
            )
        } else {
            val calCategory by settings.autoCalCategoryFlow
                .collectAsState(initial = ru.zf.pravka.core.CalendarRules.DEFAULT_CATEGORY)
            Text(
                "Категория встреч (БЖЖ — всегда спорт)",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(top = 6.dp),
            )
            ChipRow(Modifier.padding(top = 4.dp, bottom = 4.dp)) {
                val work = dealCategories.filter { it.name.startsWith("Работа", ignoreCase = true) }
                    .ifEmpty { dealCategories }
                for (c in work) {
                    PaperChip(
                        c.name,
                        selected = c.name == calCategory,
                        onClick = { scope.launch { settings.setAutoCalCategory(c.name) } },
                    )
                }
            }
            // null — не выбирали: только основной календарь аккаунта.
            val chosen by settings.autoCalendarsFlow.collectAsState(initial = null)
            val cals = remember(permTick) { ru.zf.pravka.trigger.CalendarPilot.calendars(context) }
            if (cals.isEmpty()) {
                PaperHint("Календарей на телефоне не видно.")
            } else {
                Text(
                    "Какие календари",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 6.dp),
                )
                val effective = chosen ?: cals.filter { it.primary }.map { it.name }.toSet()
                for (c in cals) {
                    PaperToggle(
                        title = c.name + if (c.primary) " · основной" else "",
                        checked = c.name in effective,
                        onCheckedChange = { on ->
                            scope.launch { settings.setAutoCalendar(c.name, on, effective) }
                        },
                    )
                }
            }
            // Что автопилот видит сегодня: молчащий календарь читается как поломка.
            var calToday by remember { mutableStateOf("") }
            LaunchedEffect(permTick, chosen) {
                calToday = kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) {
                    runCatching { ru.zf.pravka.trigger.CalendarPilot.todayLine(context, chosen) }
                        .getOrElse { "календарь не прочитался: ${it.message}" }
                }
            }
            if (calToday.isNotBlank()) PaperHint(calToday.replaceFirstChar { it.uppercase() })
            val calLine = ru.zf.pravka.trigger.PravkaAccessibilityService.instance?.calendarPilot?.lastFire.orEmpty()
            if (calLine.isNotBlank()) PaperHint("Последнее: $calLine")
        }
    }

    // ---- Звонки (06.10.2026) ----
    SubHead("Звонки", info = CALLS_INFO)
    val callsCut by settings.zCallsCutFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Звонок режет дело — с категорией по собеседнику",
        checked = callsCut,
        onCheckedChange = { on -> scope.launch { settings.setZCallsCut(on) } },
    )
    if (callsCut) {
        val callPerm = remember(permTick) { ru.zf.pravka.data.PhoneSweeper.hasCallLogAccess(context) }
        if (!callPerm) {
            PaperButton(
                "Дать доступ к журналу звонков",
                icon = Glyphs.Phone,
                onClick = { askPermission.launch(android.Manifest.permission.READ_CALL_LOG) },
            )
        }
        val family by settings.zCallFamilyFlow.collectAsState(initial = ru.zf.pravka.core.CallRules.FAMILY_DEFAULT)
        var familyText by remember(family) { mutableStateOf(family) }
        PaperField(
            value = familyText,
            onValueChange = { v ->
                familyText = v
                scope.launch { settings.setZCallFamily(v) }
            },
            label = "Семья — имена контактов через запятую",
            singleLine = false,
        )
        PaperHint(
            "Семья — [Семья]; люди Дел (по номеру или имени) — [Работа: звонки] с их клиентом; " +
                "остальные — [Звонки] и вопрос пушем. Ответил однажды — этот собеседник дальше так и приезжает.",
        )
    }

    // ---- Подъём и отбой ----
    SubHead("Подъём и отбой", info = WAKE_INFO)
    val wakeDeal by settings.autoWakeDealFlow.collectAsState(initial = null)
    var wakeEdit by remember { mutableStateOf(false) }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("По подъёму в будни, пн–пт", style = MaterialTheme.typography.bodyMedium)
            Text(
                wakeDeal?.let { d ->
                    "«${d.title}»" + (if (d.category.isNotBlank()) " [${d.category}]" else "") +
                        " — с момента подъёма, между пятью и десятью утра"
                } ?: "дела нет — только сон в ленту",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        GlyphButton(
            Glyphs.Edit,
            if (wakeDeal != null) "изменить дело по подъёму" else "задать дело по подъёму",
            onClick = { wakeEdit = true },
            size = 36.dp,
        )
    }
    if (wakeEdit) {
        PlaceDealDialog(
            place = "будни",
            current = wakeDeal,
            categories = dealCategories.map { it.name },
            onDismiss = { wakeEdit = false },
            onSave = { title, category ->
                wakeEdit = false
                scope.launch { settings.setAutoWakeDeal(title, category) }
            },
            heading = "Проснулся в будни — что начать?",
            hint = "Телефон нашёл ночь, подъём в будний день между пятью и десятью утра — " +
                "это дело начнётся с момента подъёма. Уже сказал, что делаешь, — не начнётся. " +
                "Не то — «Сказать» в пуше заменит, «Отменить» уберёт.",
            placeholder = "Сборы детей",
            removeLabel = "убрать дело по подъёму",
            icon = Glyphs.Moon,
        )
    }
    val bedtimeOn by settings.autoBedtimeFlow.collectAsState(initial = true)
    PaperToggle(
        title = "Сон сам: зарядка вечером и полчаса без движения",
        checked = bedtimeOn,
        onCheckedChange = { on -> scope.launch { settings.setAutoBedtime(on) } },
        info = "Поставил телефон на зарядку после 22:00, экран погас, полчаса без единого " +
            "толчка, и уже после 23:00 — в ленте начинается «Сон» с того мига, когда телефон " +
            "положили, а вечернее дело закрывается там же. Пуш тихий — лежит в шторке, экран " +
            "не зажигает; не спишь — «Отменить», вернётся прежнее дело. Сон, который ты " +
            "включил сам (метка NFC, «сплю» голосом), не трогается. Кончается сон, когда утром " +
            "телефон задвигался (с пяти утра; ночью взял, глянул, положил — не подъём) или " +
            "ты утром долго смотришь в экран; зарядку снимать не нужно. Ошибся — «Ещё сплю» " +
            "в утреннем пуше. Зарядка с семи вечера на столе сном не считается: важен час подключения.",
    )
}

/** Подъём и отбой — за «i» у заголовка. */
private const val WAKE_INFO =
    "Вечер: зарядка после 22:00, погашенный экран и полчаса тишины (решается не раньше " +
        "23:00) — «Сон» начинается сам, вечернее дело закрыто, тихий пуш с «Отменить». " +
        "Утро: телефон задвигался после пяти — сон кончился; в будни с этого момента сам " +
        "начинается дело по подъёму — сборы детей. Без автосна утро узнаётся, как раньше, " +
        "по ночи на экране."

/** Встречи из календаря — за «i» у заголовка «Календарь». */
private const val CALLS_INFO =
    "Владелец, 06.10.2026: «звонки должны перебивать текущее дело… когда я говорю по " +
        "телефону, я не работаю; а если сижу и пришёл звонок по работе — я прямо работаю». " +
        "Разговор от двух минут встаёт в ленту врезкой: дело режется на начале звонка и " +
        "продолжается после. Категорию решает собеседник, а не дело, которое шло. Не режет " +
        "свой записанный звонок, встречу из календаря, дорогу и тренировку. Пуш — «Работа», " +
        "«Семья», «Убрать» (дело сшивается обратно). Выключено — звонки только в счётчиках дня."

private const val CALENDAR_INFO =
    "Встреча из календаря телефона начинается в ленте сама с начала события и " +
        "закрывает текущее дело; по концу события закрывается и возвращает то, что " +
        "шло до неё, — как перерыв по метке NFC. Не встреча — «Отменить» в пуше; " +
        "затянулась — «Ещё идёт». Смотрятся только выбранные календари (с завода — " +
        "основной), без событий на весь день, отклонённых и «свободен». Встреча узнаёт " +
        "себя в твоих словах — по названию и клиенту: сказал «встречи в Птиц» — второй " +
        "записи нет, а если забыл переключиться, по концу события она закроется сама. " +
        "Сказал то же самое уже после начала — твоя запись заберёт её начало. Сказал " +
        "другое за десять минут до начала или позже — встреча не пишется. БЖЖ — не " +
        "созвон: «BJJ: борьба» спортом, и тренировка с часов дописывается к ней, а не " +
        "режет. Клиент — проект Дел по имени или алиасу в названии встречи; своя " +
        "сторона («ЗФ», «Знакомый Финансист») из названия уходит."

/**
 * Дело места по приезду: название и категория; пустое название — дела нет.
 * Лист набора вместо AlertDialog (24.09.2026): «Убрать» — корзиной слева,
 * «Отмену» заменили крестик и свайп.
 */
@Composable
private fun PlaceDealDialog(
    place: String,
    current: PlaceDeal?,
    categories: List<String>,
    onDismiss: () -> Unit,
    onSave: (title: String, category: String) -> Unit,
    // Тот же лист служит делу по подъёму — слова свои, механика одна.
    heading: String = "Приехал в «$place» — что начать?",
    hint: String = "Дорога закроется приездом, и это дело начнётся с того же момента. " +
        "Ошибся автопилот — «Сказать» в пуше заменит его.",
    placeholder: String = "Забираю Серёжу",
    removeLabel: String = "убрать дело по приезду",
    icon: androidx.compose.ui.graphics.vector.ImageVector = Glyphs.Place,
) {
    var title by remember { mutableStateOf(current?.title.orEmpty()) }
    var category by remember { mutableStateOf(current?.category.orEmpty()) }
    PaperAlert(
        onDismiss = onDismiss,
        title = heading,
        icon = icon,
        confirm = SheetAction("Готово") { onSave(title.trim(), category) },
        destructive = if (current != null) SheetAction(removeLabel) { onSave("", "") } else null,
    ) {
        PaperHint(hint)
        PaperField(
            value = title,
            onValueChange = { title = it },
            label = "Дело",
            placeholder = placeholder,
        )
        CategoryPicker(selected = category, options = categories, onSelect = { category = it })
    }
}

/**
 * Выбор категории в окне: кнопка с текущей и выпадающий список. Своей детали
 * в наборе нет (24.09.2026): чипами два десятка категорий в окно не лезут, а
 * выбранная уезжала бы за край ряда.
 */
@Composable
private fun CategoryPicker(
    selected: String,
    options: List<String>,
    onSelect: (String) -> Unit,
    noneLabel: String? = "без категории",
) {
    var open by remember { mutableStateOf(false) }
    Box {
        PaperButton(
            "Категория: " + selected.ifBlank { "нет" },
            onClick = { open = true },
            icon = Glyphs.Tag,
        )
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            if (noneLabel != null) {
                DropdownMenuItem(text = { Text(noneLabel) }, onClick = { onSelect(""); open = false })
            }
            for (c in options) {
                DropdownMenuItem(text = { Text(c) }, onClick = { onSelect(c); open = false })
            }
        }
    }
}

/**
 * Backups, out in the open: a copy of the ribbon per day, the state before any
 * sharp shrink, and the quarantined file if one ever appears. Restore puts a
 * copy back (undoable like any other operation), «Файлом» hands the raw JSON
 * out so nothing important is ever trapped in private storage.
 *
 * Лежит в плашке «резервные копии ленты» (настройки «Приложения»), поэтому
 * своего заголовка нет, а пояснение — за «i» в строке состояния (24.09.2026).
 */
@Composable
internal fun BackupsSection(app: PravkaApp) {
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
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            if (list.isEmpty()) "Копий пока нет — первая появится в течение часа."
            else "Копий: ${list.size}",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f),
        )
        // Кнопки ↩︎ во вкладке больше нет (15.09) — отмена живёт в меню
        // долгого нажатия «З», так и сказано.
        InfoButton(
            "Резервные копии",
            "Копия всего нажитого (лента, словарь, правила, телефон) снимается раз в час, " +
                "плюс копия ленты на каждый день и перед любым резким сокращением записей. " +
                "«Восстановить» возвращает копию целиком (отменяется из меню долгого нажатия «З»), " +
                "значок «поделиться» у копии и «Текущий файл» отдают сырой JSON. " +
                "«Импорт CSV» поднимает ленту из любой выгрузки: " +
                "строки, которые уже есть, не удваиваются.",
            size = 30.dp,
        )
    }
    IconActionRow {
        IconAction(Glyphs.Refresh, "Обновить", onClick = { tick++ })
        IconAction(Glyphs.Share, "Текущий файл", onClick = {
            app.appScope.launch {
                runCatching { context.startActivity(app.zasechkaStore.shareStoreIntent()) }
            }
        })
        IconAction(Glyphs.Download, "Импорт CSV", onClick = {
            runCatching { importer.launch(arrayOf("*/*")) }
        })
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
            GlyphButton(
                Glyphs.Share,
                "отдать файлом",
                onClick = {
                    app.appScope.launch {
                        runCatching { context.startActivity(app.zasechkaStore.shareStoreIntent(b.name)) }
                    }
                },
                size = 36.dp,
            )
            PaperTextButton(
                "Восстановить",
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
            )
        }
    }
}

/**
 * Список категорий: имя цветом радуги, справа базовое время и ценность часа,
 * под ним подсказка разбору. Заголовок и пояснение — у плашки снаружи.
 */
@Composable
private fun CategoriesEditor(
    categories: List<ZasechkaStore.Category>,
    onChange: (List<ZasechkaStore.Category>) -> Unit,
) {
    var editing by remember { mutableStateOf<ZasechkaStore.Category?>(null) }
    // Same order as the day's progress bars: along the rainbow, red first.
    for (category in categories.sortedBy { categoryHue(it.name) }) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(10.dp))
                .clickable { editing = category },
        ) {
            Column(Modifier.weight(1f).padding(vertical = 3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        category.name,
                        style = MaterialTheme.typography.bodyMedium,
                        color = categoryColor(category.name),
                        modifier = Modifier.weight(1f),
                    )
                    // The two knobs at a glance: typical length and what an
                    // hour of it is worth (+ lifts the day, - sinks it).
                    // Часики — значком набора, а не эмодзи ⏱.
                    if (category.baseMin > 0) {
                        Icon(
                            Glyphs.Timer,
                            contentDescription = "базовое время",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.size(13.dp),
                        )
                        Spacer(Modifier.width(3.dp))
                        Text(
                            "${category.baseMin} м",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            maxLines = 1,
                        )
                        Spacer(Modifier.width(8.dp))
                    }
                    Text(
                        if (category.value > 0) "+${category.value}" else "${category.value}",
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
                    )
                }
            }
            GlyphButton(
                Glyphs.Delete,
                "удалить категорию",
                onClick = { onChange(categories.filter { it.name != category.name }) },
                size = 36.dp,
            )
        }
    }
    var newName by remember { mutableStateOf("") }
    Spacer(Modifier.height(4.dp))
    PaperField(
        value = newName,
        onValueChange = { newName = it },
        label = "Добавить категорию",
        trailing = {
            GlyphButton(
                Glyphs.Plus,
                "добавить",
                onClick = {
                    val v = newName.trim()
                    if (v.isNotEmpty()) {
                        onChange(categories + ZasechkaStore.Category(v, ""))
                        newName = ""
                    }
                },
                enabled = newName.isNotBlank(),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
    editing?.let { original ->
        var name by remember(original) { mutableStateOf(original.name) }
        var hint by remember(original) { mutableStateOf(original.hint) }
        var baseMin by remember(original) { mutableStateOf(original.baseMin.toString()) }
        var worth by remember(original) { mutableStateOf(original.value) }
        PaperAlert(
            onDismiss = { editing = null },
            title = "Категория",
            icon = Glyphs.Tag,
            subtitle = original.name,
            confirm = SheetAction("Сохранить") {
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
            },
        ) {
            PaperField(value = name, onValueChange = { name = it }, label = "Название")
            PaperField(
                value = hint,
                onValueChange = { hint = it },
                label = "Что сюда относится (подсказка Сонету)",
                singleLine = false,
                maxLines = 4,
            )
            PaperField(
                value = baseMin,
                onValueChange = { baseMin = it.filter { c -> c.isDigit() }.take(4) },
                label = "Базовое время, мин (0 — не спрашивать)",
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            )
            // Ползунок меняет только поле окна — пишет «Сохранить», поэтому
            // по отпусканию делать нечего.
            PaperSlider(
                title = "Ценность часа",
                valueText = if (worth > 0) "+$worth" else "$worth",
                value = worth.toFloat(),
                onValueChange = { worth = it.roundToInt() },
                onValueChangeFinished = {},
                valueRange = -10f..10f,
                steps = 19,
            )
            PaperHint(
                when {
                    worth >= 7 -> "Тянет день вверх"
                    worth > 0 -> "Плюс"
                    worth == 0 -> "Ватерлиния, сервисное время"
                    worth > -7 -> "Минус"
                    else -> "Тянет день вниз"
                }
            )
        }
    }
}

/** Список строк с «удалить» и полем «добавить»; подпись и пояснение — у плашки. */
@Composable
private fun EditableList(
    values: List<String>,
    onChange: (List<String>) -> Unit,
) {
    for (value in values) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(value, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
            GlyphButton(Glyphs.Delete, "удалить", onClick = { onChange(values - value) }, size = 36.dp)
        }
    }
    var newValue by remember { mutableStateOf("") }
    Spacer(Modifier.height(4.dp))
    PaperField(
        value = newValue,
        onValueChange = { newValue = it },
        label = "Добавить",
        trailing = {
            GlyphButton(
                Glyphs.Plus,
                "добавить",
                onClick = {
                    val v = newValue.trim()
                    if (v.isNotEmpty()) {
                        onChange(values + v)
                        newValue = ""
                    }
                },
                enabled = newValue.isNotBlank(),
                tint = MaterialTheme.colorScheme.primary,
            )
        },
    )
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
internal fun appLabelOf(labels: Map<String, String>, pkg: String): String =
    labels[pkg] ?: FRIENDLY_LABELS[pkg] ?: pkg.split('.').takeLast(2).joinToString(".")

// Furniture: never phone use - launchers, system UI, the docked-hub
// screensaver, the dialer (call time is a ribbon entry). Old stored data may
// still carry them; fresh sweeps exclude most at the source.
internal fun isFurniturePkg(pkg: String): Boolean =
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
private fun PhoneSection(app: PravkaApp, dayStart: Long, now: Long) {
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

    // Re-check permissions and freshen the aggregates while the tab is open
    // (the `now` clock ticks every 30 seconds).
    LaunchedEffect(now, dayStart) {
        usageGranted = PhoneSweeper.hasUsageAccess(context)
        callGranted = PhoneSweeper.hasCallLogAccess(context)
        if (usageGranted) app.phoneSweeper.sweep()
    }

    val agg = aggregatePhoneDays(days, listOf(phoneDayKey(dayStart)))
    val tracked = remember(immersive, offApps) { immersive.filterKeys { it !in offApps } }
    val callPermission = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> callGranted = granted }

    // Раздел — плашка с заголовком, всегда раскрыт и без пояснений (владелец,
    // 15.09.2026: «просто телефон, общее время и дальше по каждому приложению
    // сколько»). Тап по приложению — считать его по дням (строка в «Телефоне»
    // Notion и в Общей статистике) или перестать; считаемое подсвечено.
    // Долгое нажатие — категория и «звук в фоне». Тумблер звонков и досчёт
    // прошлых дней сняты: звонки считаются всегда, если журнал разрешён.
    PaperCard(
        label = "телефон",
        // На плашке пояснений нет (владелец, 15.09), но тап и долгое нажатие
        // по приложению иначе не узнать ниоткуда — они за «i» (24.09.2026).
        info = "Тап по приложению — считать его по дням или перестать: минуты уходят в " +
            "строку «Телефон» Notion и в Общую статистику, в ленту не пишется ничего; " +
            "считаемое — жирным, с галочкой и полоской акцента. Долгое нажатие — " +
            "категория и «звук в фоне». Звонки считаются по журналу, если он разрешён.",
        trailing = {
            if (usageGranted && agg.screenMs > 0) {
                Text(
                    fmtDur(agg.screenMs / 60_000),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold,
                )
            }
        },
    ) {
        if (!usageGranted) {
            PaperButton(
                "Дать доступ к статистике использования",
                icon = Glyphs.Key,
                primary = true,
                onClick = {
                    runCatching {
                        context.startActivity(
                            android.content.Intent(android.provider.Settings.ACTION_USAGE_ACCESS_SETTINGS)
                        )
                    }
                },
            )
            return@PaperCard
        }

        val trackedMs = agg.apps.entries.filter { it.key in tracked }.sumOf { it.value }
        Text(
            buildString {
                append("экран ").append(fmtDur(agg.screenMs / 60_000))
                if (trackedMs > 0) append(" · считается ").append(fmtDur(trackedMs / 60_000))
                if (agg.calls > 0) {
                    append(" · звонки ").append(fmtDur(agg.callsMs / 60_000)).append(" · ").append(agg.calls)
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(6.dp))

        val topApps = agg.apps.entries
            .filter { !isFurniturePkg(it.key) }
            .sortedByDescending { it.value }
            .take(10)
        val maxMs = topApps.firstOrNull()?.value ?: 0L
        for ((pkg, ms) in topApps) {
            val label = appLabelOf(labels, pkg)
            val on = pkg in tracked
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .combinedClickable(
                        onClick = {
                            app.appScope.launch {
                                when {
                                    // Категория здесь — заглушка: считать по дням
                                    // можно без неё, а настоящую даст долгое нажатие.
                                    pkg !in immersive -> app.phoneStore.setImmersive(pkg, "телефон")
                                    pkg in offApps -> app.phoneStore.setTracked(pkg, true)
                                    else -> app.phoneStore.setTracked(pkg, false)
                                }
                            }
                        },
                        onLongClick = { editingApp = pkg },
                    )
                    .padding(vertical = 3.dp),
            ) {
                Text(
                    label,
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (on) MaterialTheme.colorScheme.onSurface
                    else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.width(120.dp),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Box(
                    Modifier
                        .weight(1f)
                        .height(10.dp)
                        .background(ru.zf.pravka.ui.LocalMode.current.tint.copy(alpha = 0.12f), RoundedCornerShape(5.dp)),
                ) {
                    val fraction = if (maxMs > 0) ms.toFloat() / maxMs else 0f
                    Box(
                        Modifier
                            .fillMaxWidth(fraction.coerceIn(0.02f, 1f))
                            .height(10.dp)
                            .background(
                                // Правка 4.0: полосы — в шкале Засечки. Считаемое —
                                // светлой ступенью, остальное — тёмной.
                                if (on) ru.zf.pravka.ui.Modes.Zasechka.ramp[1]
                                else ru.zf.pravka.ui.Modes.Zasechka.ramp[3].copy(alpha = 0.75f),
                                RoundedCornerShape(5.dp),
                            ),
                    )
                }
                Text(
                    fmtDur(ms / 60_000),
                    style = MaterialTheme.typography.bodySmall,
                    fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal,
                    modifier = Modifier.padding(start = 8.dp).width(56.dp),
                    textAlign = TextAlign.End,
                )
                // Галочка считаемого — значком набора; место под неё держится
                // и у несчитаемых, чтобы столбец минут не прыгал.
                Box(Modifier.width(18.dp), contentAlignment = Alignment.CenterEnd) {
                    if (on) {
                        Icon(
                            Glyphs.Check,
                            contentDescription = "считается",
                            tint = MaterialTheme.colorScheme.primary,
                            modifier = Modifier.size(13.dp),
                        )
                    }
                }
            }
        }
        if (topApps.isEmpty()) {
            Text(
                "Данных пока нет — появятся в течение нескольких минут.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        // Звонки считаются по журналу; без разрешения — только кнопка дать его.
        if (!callGranted) {
            Spacer(Modifier.height(4.dp))
            PaperTextButton(
                "Разрешить журнал звонков",
                onClick = { callPermission.launch(Manifest.permission.READ_CALL_LOG) },
                icon = Glyphs.Phone,
            )
        }
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
 * Правила разбора Засечки: набор, одобренный владельцем, едет в каждый разбор
 * фразы. Самообучение, которое их предлагало каждую ночь (батч Опусом, ⭐ над
 * «З», «Обучить», «Прогнать всю историю»), снято 08.09.2026 — владелец: «все
 * паттерны и так уже найдены, звёздочка очень сильно раздражает, я на них
 * вообще не смотрю». Осталось то, что работает: список с тумблерами.
 * Предложение, застрявшее с прежних ночей, показывается ещё раз — судить или
 * снять руками, само оно никуда не уедет и в промпт не попадёт.
 *
 * Плашку раздел рисует сам (24.09.2026): раньше он стоял голым текстом между
 * плашками настроек, а пустой не показывается вовсе — снаружи этого не знают.
 */
@Composable
private fun ZasechkaRulesSection(app: PravkaApp) {
    val scope = app.appScope
    var rules by remember { mutableStateOf(emptyList<ru.zf.pravka.data.RulesStore.Rule>()) }
    var reload by remember { mutableStateOf(0) }

    LaunchedEffect(reload) {
        rules = runCatching { app.zasechkaRules.all() }.getOrDefault(emptyList())
    }

    val proposed = rules.filter { it.pending }
    val active = rules.filter { !it.pending }
    // Пустой раздел не показываем: добавить правило руками отсюда нельзя,
    // а робот новых не предлагает — говорить было бы нечего.
    if (proposed.isEmpty() && active.isEmpty()) return

    PaperCard(
        label = "правила разбора",
        info = "Что у тебя значат слова про время и дела — уходит в каждый разбор " +
            "фразы. Новых правил робот не предлагает, набор правится здесь.",
    ) {
        if (proposed.isNotEmpty()) {
            Text("Предложено раньше — суди или сними", style = MaterialTheme.typography.bodyMedium)
            for (r in proposed) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp),
                ) {
                    Text(r.text, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                    PaperTextButton("Да", onClick = {
                        scope.launch { app.zasechkaRules.approve(r.id); reload++ }
                    })
                    PaperTextButton("Нет", color = MaterialTheme.colorScheme.error, onClick = {
                        scope.launch { app.zasechkaRules.delete(r.id); reload++ }
                    })
                }
            }
        }

        if (active.isNotEmpty()) {
            // Подзаголовок нужен, только когда рядом есть предложенные:
            // иначе он повторял бы подпись плашки.
            if (proposed.isNotEmpty()) {
                Text(
                    "Действующие правила",
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 10.dp),
                )
            }
            for (r in active) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.weight(1f)) {
                        PaperToggle(
                            title = r.text,
                            checked = r.enabled,
                            onCheckedChange = { v ->
                                scope.launch { app.zasechkaRules.setEnabled(r.id, v); reload++ }
                            },
                        )
                    }
                    GlyphButton(
                        Glyphs.Delete,
                        "удалить правило",
                        onClick = { scope.launch { app.zasechkaRules.delete(r.id); reload++ } },
                        size = 36.dp,
                    )
                }
            }
        }
    }
}

/**
 * Приложение телефона по долгому нажатию: считать ли его по дням, «звук в
 * фоне» и категория-подсказка. Лист набора (24.09.2026); пояснения под
 * тумблерами — за их «i».
 */
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
    PaperAlert(
        onDismiss = onDismiss,
        title = label,
        icon = Glyphs.Phone,
        confirm = SheetAction("Сохранить") { onSave(if (enabled) category else null, enabled && audio) },
    ) {
        PaperToggle(
            title = "Считать по дням",
            checked = enabled,
            onCheckedChange = { enabled = it },
            info = "Минуты в этом приложении идут в строку «Телефон» у итогов дня " +
                "и в «Телефон» Notion; в ленту ничего не пишется.",
        )
        if (enabled) {
            PaperToggle(
                title = "Звук в фоне",
                checked = audio,
                onCheckedChange = { audio = it },
                info = "Для аудиокниг, подкастов и музыки: время считается по " +
                    "фоновой службе, а не по переднему плану — книга играет " +
                    "с погасшим экраном, и иначе её не поймать вовсе.",
            )
            CategoryPicker(
                selected = category,
                options = (listOf("Отдых") + categories).distinct(),
                onSelect = { category = it },
                noneLabel = null,
            )
        }
    }
}

// ---------------------------------------------------------------------------
// Комментарий к делу: строка под делом, «Заметка» в листе записи, своё окно.
// Баббл 💬 в строке снят вместе с остальными значками (24.09.2026): что у
// дела есть слова, видно по третьей строке, а «Заметка» в листе горит.
// ---------------------------------------------------------------------------

/**
 * Откуда запись, значком после длительности: из дела — галочка дел, из
 * календаря — календарь (05.10.2026: «понимать, что этим делом я занимаюсь»).
 * Остальные — без значка: лента дышит.
 */
@Composable
private fun SourceMark(e: ZasechkaStore.Entry) {
    val glyph = when {
        e.task.isNotBlank() -> Glyphs.Delo
        e.source == ru.zf.pravka.core.CalendarRules.SOURCE -> Glyphs.Calendar
        else -> return
    }
    Spacer(Modifier.width(5.dp))
    Icon(
        glyph,
        contentDescription = if (e.task.isNotBlank()) "из дела" else "из календаря",
        tint = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.size(12.dp),
    )
}

/** Третья строка записи — сам комментарий, той же бледностью, что дыры в ленте. */
@Composable
private fun CommentLine(comment: String) {
    if (comment.isBlank()) return
    Text(
        comment,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
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
 * Комментарий к делу отдельным окном — по «Заметке» в листе записи. Одно поле
 * и ничего лишнего: слова к делу пишутся чаще, чем правится само дело, а
 * полный редактор с временем и категорией ради них долгий. Диктовка — кнопкой
 * «П» прямо в поле: это и есть движок Правки, со словарём, правилами и
 * чисткой. «Убрать комментарий» — корзиной слева, как удаление в любом окне.
 */
@Composable
private fun CommentDialog(
    entry: ZasechkaStore.Entry,
    onDismiss: () -> Unit,
    onSave: (String) -> Unit,
) {
    var comment by remember { mutableStateOf(entry.comment) }
    PaperAlert(
        onDismiss = onDismiss,
        title = capFirst(entry.title.ifBlank { entry.category.ifBlank { "без названия" } }),
        icon = Glyphs.Note,
        subtitle = "${fmtTime(entry.start)}–${if (entry.open) "…" else fmtTime(entry.end)}" +
            (if (entry.category.isBlank()) "" else " · ${entry.category}"),
        confirm = SheetAction("Сохранить") { onSave(comment.trim()) },
        destructive = if (entry.comment.isNotBlank()) SheetAction("убрать комментарий") { onSave("") } else null,
    ) {
        PaperField(
            value = comment,
            onValueChange = { comment = it },
            label = "Комментарий",
            placeholder = "что было внутри — «П» надиктует сюда",
            singleLine = false,
            minLines = 3,
            maxLines = 8,
        )
    }
}

// ---------------------------------------------------------------------------
// Entry editor: every field down to the minutes.
// ---------------------------------------------------------------------------

/**
 * Правка записи — лист набора (24.09.2026). Микрофон — в шапке справа, где
 * владелец его и просил («в кнопке edit сверху кнопка микрофончика»);
 * «Удалить запись» — корзиной у левого края низа, «Сохранить» — справа.
 * «Отмену» заменили крестик и свайп.
 */
@Composable
private fun EditEntryDialog(
    entry: ZasechkaStore.Entry,
    categories: List<String>,
    /** Справочник Дел: клиент выбирается чипом, свободный текст — запасом. Пусто — Дела не на сервере. */
    projects: List<ru.zf.pravka.core.Dela.Project> = emptyList(),
    /** Дело, из которого начата запись («#57 Иван: прислать модель»), — для подписи. */
    taskTitle: String = "",
    onDismiss: () -> Unit,
    onSave: (ZasechkaStore.Entry) -> Unit,
    onDelete: () -> Unit,
    /** Микрофон сверху: надиктовать поправку — «он поменяет» (владелец, 15.09). */
    onDictate: (() -> Unit)? = null,
) {
    var title by remember { mutableStateOf(entry.title) }
    var category by remember { mutableStateOf(entry.category) }
    var client by remember { mutableStateOf(entry.client) }
    var project by remember { mutableStateOf(entry.project) }
    var comment by remember { mutableStateOf(entry.comment) }
    var startText by remember { mutableStateOf(fmtTime(entry.start)) }
    var endText by remember { mutableStateOf(if (entry.open) "" else fmtTime(entry.end)) }

    val entryDayStart = remember(entry.id) {
        val cal = Calendar.getInstance()
        cal.timeInMillis = entry.start
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        cal.timeInMillis
    }

    val save: () -> Unit = {
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
                project = project,
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
    }

    PaperSheet(
        onDismiss = onDismiss,
        title = "Правка записи",
        icon = Glyphs.Edit,
        subtitle = "${fmtTime(entry.start)}–${if (entry.open) "…" else fmtTime(entry.end)}",
        actions = {
            if (onDictate != null) {
                GlyphButton(
                    Glyphs.Mic,
                    "надиктовать поправку",
                    onClick = onDictate,
                    tint = MaterialTheme.colorScheme.primary,
                )
            }
        },
        footer = {
            PaperIconButton(
                Glyphs.Delete,
                "удалить запись",
                onClick = onDelete,
                tint = MaterialTheme.colorScheme.error,
            )
            Spacer(Modifier.weight(1f))
            PaperButton("Сохранить", onClick = save, primary = true)
        },
    ) {
        if (onDictate != null) {
            PaperHint("Микрофон: скажи, что поменять, — «это был обед», «до 17:40», «категория семья».")
        }
        PaperField(value = title, onValueChange = { title = it }, label = "Дело")
        CategoryPicker(selected = category, options = categories, onSelect = { category = it })
        PaperField(
            value = client,
            onValueChange = { text ->
                client = text
                // Свободный текст: связь с проектом — только при точном имени или алиасе.
                val n = ru.zf.pravka.core.Dela.norm(text)
                project = projects.firstOrNull { p ->
                    n != null && (ru.zf.pravka.core.Dela.norm(p.name) == n || p.aliases.any { ru.zf.pravka.core.Dela.norm(it) == n })
                }?.id.orEmpty()
            },
            label = if (project.isNotBlank()) "Клиент/проект · из справочника" else "Клиент/проект",
        )
        if (projects.isNotEmpty()) {
            val n = ru.zf.pravka.core.Dela.norm(client)
            val shown = projects.filter { p -> p.sphere == "work" && p.id != project }
                .filter { p -> n == null || ru.zf.pravka.core.Dela.norm(p.name + " " + p.aliases.joinToString(" "))?.contains(n) == true }
                .take(8)
            if (shown.isNotEmpty()) {
                ru.zf.pravka.ui.ChipRow {
                    for (p in shown) {
                        ru.zf.pravka.ui.PaperChip(p.name, selected = false, onClick = { client = p.name; project = p.id })
                    }
                }
            }
        }
        if (taskTitle.isNotBlank()) PaperHint("Из дела: $taskTitle")
        // Комментарий — обычное поле: тап по «П» надиктует прямо сюда,
        // со словарём и чисткой, как в любое поле любого приложения.
        PaperField(
            value = comment,
            onValueChange = { comment = it },
            label = "Комментарий",
            placeholder = "что было внутри — «П» надиктует сюда",
            singleLine = false,
            minLines = 2,
            maxLines = 5,
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            PaperField(
                value = startText,
                onValueChange = { startText = it },
                modifier = Modifier.weight(1f),
                label = "Начало",
            )
            PaperField(
                value = endText,
                onValueChange = { endText = it },
                modifier = Modifier.weight(1f),
                label = if (entry.open) "Конец (пусто = идёт)" else "Конец",
            )
        }
        if (entry.raw.isNotBlank() && entry.raw != entry.title) {
            PaperHint("Надиктовано: «${entry.raw.take(200)}»")
        }
    }
}
