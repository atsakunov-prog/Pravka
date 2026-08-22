package ru.zf.pravka.data

import android.content.Context
import android.content.Intent
import java.io.File
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

// Засечка: the owner's timesheet. One file holds everything - the entries,
// the category list and the client list - because they change together and
// a single mutex keeps them consistent. Same persistence discipline as the
// dictionary (the other irreplaceable store): atomic writes, quarantine on
// corruption, every write through the DiskWriter thread.
//
// The timeline model: entries are back-to-back by default. A new entry
// CLOSES the previous open one at its own start time, so the day reads as a
// continuous ribbon; explicit "закрыть день"/breaks just close without
// opening. end == 0 means "still going".
class ZasechkaStore(private val context: Context) {

    companion object {
        const val FORMAT = "pravka-zasechka"
        private const val FILE_NAME = "zasechka.json"

        // The owner's taxonomy, with hints the categorizer sees. A hint is
        // the difference between "поговорил с Марианой" landing in Семья and
        // landing in Социальное - names live here, not in code.
        private const val CAT_SEED_VERSION = 5

        // The owner's rule: unrecorded time is not "unknown", it is «Потери».
        // Bounded holes at least this long become gap-filler entries...
        private const val GAP_FILL_MIN_MS = 5 * 60_000L
        // ...but only once the right edge has stood for a while: retro
        // dictation ("обедаю с 12:30") usually lands within the hour, and an
        // eagerly created filler would already be mirrored to Sheets.
        private const val GAP_FILL_QUARANTINE_MS = 45 * 60_000L
        private const val GAP_SOURCE = "gap"
        private const val GAP_CATEGORY = "Потери"
        // The normalize pass compares entries against each other (O(n^2) in the
        // worst case) and re-serializes the file, and it runs every five minutes
        // for the rest of the phone's life. History older than this is already
        // normalized and immutable in practice, so the pass stops looking at it
        // - otherwise the work grows with every logged day (and the owner's
        // symptom was exactly "the longer it runs, the worse the fold").
        private const val NORMALIZE_WINDOW_MS = 5 * 86_400_000L
        // Undo: a mutation snapshots the recent slice of the ribbon, so one
        // press puts back exactly what was there - including entries a voice
        // "удали обед" removed. Two days is far more than any single edit
        // touches; five steps deep in memory, the top one survives a restart.
        private const val UNDO_WINDOW_MS = 2 * 86_400_000L
        private const val UNDO_DEPTH = 5
        // Two mutations closer than this are one act (a chain edit updates
        // every fragment in a tight loop) - keep the state before the burst.
        private const val UNDO_BURST_MS = 1_500L
        // Sub-half-minute closed leftovers are splice artifacts, not facts:
        // they showed up in the owner's export as 0-minute rows.
        private const val CRUMB_MS = 30_000L
        val DEFAULT_CATEGORIES = listOf(
            Category("Сон", ""),
            Category("Спорт: силовая", "тренажёрка, железо, ОФП"),
            Category("Спорт: бег", ""),
            Category("Спорт: вело", "велотренировка"),
            Category("Спорт: прочее", "плавание, лыжи, остальной спорт"),
            Category("Передвижение: пешком", "ходьба, дойти куда-то"),
            Category("Передвижение: вело", "велосипед как транспорт"),
            Category("Передвижение: транспорт", "машина, такси, метро, поезд, самолёт"),
            Category("Еда", "завтрак, обед, ужин, перекус, готовка"),
            Category("Быт", "домашние дела, покупки, уборка, документы, врачи"),
            Category("Систематизация", "наведение порядка в жизни и работе: сборка и настройка Правки и Засечки, процессы, автоматизация, разгребание"),
            Category("Семья", "время и разговоры с Марианой, с Серёжей, с родными"),
            Category("Социальное: внешнее", "друзья, знакомые, встречи и переписка вне работы"),
            Category("Работа: привлечение", "маркетинг, контент, продажи, новые клиенты"),
            Category("Работа: текущая", "работа по действующим клиентам и проектам"),
            Category("Работа: планирование", "планирование, стратегия, разборы, финансы бизнеса"),
            Category("Работа: звонки", "рабочие созвоны и звонки по клиентам"),
            Category("Чтение", "книги, статьи"),
            Category("Секс: с Марианной", "супружеский секс"),
            Category("Секс: соло", "мастурбация"),
            Category("Отдых", "осознанный отдых: кино, сериалы, игры, гуляние, полежать"),
            Category(
                "Потери",
                "время, потраченное ни на что: залипание, бесцельный скроллинг, ютуб; дыры без записи падают сюда сами",
            ),
            Category("Звонки", "телефонный разговор, если непонятно с кем и о чём"),
        )

        // The v1 seed, kept only to recognize an UNTOUCHED list during the
        // seed migration - an edited list is never overwritten.
        private val SEED_V1_NAMES = setOf(
            "Встречи", "Контент", "Операционка", "Почта и мессенджеры",
            "Планирование", "Личное", "Семья", "Еда", "Спорт", "Дорога",
            "Отдых", "Перерыв",
        )
    }

    data class Category(val name: String, val hint: String)

    data class Entry(
        val id: Long,
        val start: Long,          // epoch ms
        val end: Long,            // epoch ms; 0 = the entry is still open
        val raw: String,          // what the owner actually said
        val title: String,        // short activity name (model or owner)
        val category: String,     // one of the category list ("" = unsorted)
        val client: String,       // "" when none
        val useful: Int,          // 1..5, 0 = not rated
        val source: String,       // "voice" | "text" | "edit" | "auto"
        val synced: Boolean,      // delivered to the Sheets webhook
        val createdAt: Long,
        val pomodoros: Int = 0,   // 🍅 completed while this entry ran
        val notionSynced: Boolean = false,  // delivered to the Notion mirror
    ) {
        val open: Boolean get() = end == 0L
        /** Exact span in ms - the only honest unit for adding a day up. */
        fun durationMs(now: Long = System.currentTimeMillis()): Long =
            ((if (open) now else end) - start).coerceAtLeast(0L)

        // Rounded, NOT truncated: flooring every row separately dropped up to
        // 59 seconds per entry, and an 18-entry day came out 7 minutes short of
        // the wall clock (owner's audit). Totals are summed in ms and rounded
        // once - see the tab.
        fun durationMin(now: Long = System.currentTimeMillis()): Long =
            (durationMs(now) + 30_000L) / 60_000L

        /**
         * The part of this entry that falls inside [from, to) - what a day or
         * week total may legitimately count. Clipping instead of trusting the
         * midnight split keeps a past day's total from swallowing an entry that
         * is still running today.
         */
        fun durationMsIn(from: Long, to: Long, now: Long = System.currentTimeMillis()): Long =
            (minOf(if (open) now else end, to) - maxOf(start, from)).coerceAtLeast(0L)
    }

    private val mutex = Mutex()
    private var loaded = false
    private var entries = mutableListOf<Entry>()
    private var categories = mutableListOf<Category>()
    private var clients = mutableListOf<String>()
    private var catSeedVersion = 1
    private var lastId = 0L

    private val _entriesFlow = MutableStateFlow<List<Entry>>(emptyList())
    val entriesFlow: StateFlow<List<Entry>> = _entriesFlow
    private val _categoriesFlow = MutableStateFlow<List<Category>>(emptyList())
    val categoriesFlow: StateFlow<List<Category>> = _categoriesFlow
    private val _clientsFlow = MutableStateFlow<List<String>>(emptyList())
    val clientsFlow: StateFlow<List<String>> = _clientsFlow

    /** What one press of «Отменить» would take back; null = nothing to undo. */
    private val _undoFlow = MutableStateFlow<String?>(null)
    val undoFlow: StateFlow<String?> = _undoFlow

    private class UndoStep(
        val label: String,
        val at: Long,
        val from: Long,
        val entries: List<Entry>,
    )

    private val undoSteps = ArrayDeque<UndoStep>()

    /** Remembers the recent ribbon BEFORE a mutation. Call inside the lock. */
    private fun snapshotLocked(label: String) {
        val now = System.currentTimeMillis()
        val last = undoSteps.lastOrNull()
        if (last != null && now - last.at < UNDO_BURST_MS) return
        val from = now - UNDO_WINDOW_MS
        undoSteps.addLast(
            UndoStep(
                label = label,
                at = now,
                from = from,
                entries = entries.filter { (if (it.open) Long.MAX_VALUE else it.end) > from },
            )
        )
        while (undoSteps.size > UNDO_DEPTH) undoSteps.removeFirst()
    }

    /** Puts the last remembered state back. Returns its label, or null. */
    suspend fun undoLast(): String? = mutex.withLock {
        ensureLoaded()
        val step = undoSteps.removeLastOrNull() ?: return@withLock null
        val kept = entries.filter { (if (it.open) Long.MAX_VALUE else it.end) <= step.from }
        // Restored rows go out to the mirrors again - the Sheets copy of a
        // row that came back must stop showing the deleted state.
        entries = (kept + step.entries.map { it.copy(synced = false, notionSynced = false) })
            .toMutableList()
        entries.sortBy { it.start }
        normalizeLocked()
        persist()
        step.label
    }

    private val file: File get() = File(context.filesDir, FILE_NAME)

    suspend fun all(): List<Entry> = mutex.withLock {
        ensureLoaded()
        entries.toList()
    }

    suspend fun categories(): List<Category> = mutex.withLock {
        ensureLoaded()
        categories.toList()
    }

    suspend fun clients(): List<String> = mutex.withLock {
        ensureLoaded()
        clients.toList()
    }

    suspend fun setCategories(value: List<Category>): Unit = mutex.withLock {
        ensureLoaded()
        categories = value
            .map { Category(it.name.trim(), it.hint.trim()) }
            .filter { it.name.isNotEmpty() }
            .distinctBy { it.name.lowercase() }
            .toMutableList()
        persist()
    }

    suspend fun setClients(value: List<String>): Unit = mutex.withLock {
        ensureLoaded()
        clients = value.map { it.trim() }.filter { it.isNotEmpty() }.distinct().toMutableList()
        persist()
    }

    /** The entry currently running, if any. */
    suspend fun openEntry(): Entry? = mutex.withLock {
        ensureLoaded()
        entries.lastOrNull { it.open }
    }

    /**
     * Starts a new entry at [start] and closes the open one (if any) at that
     * same moment - the ribbon stays continuous. A retroactive start earlier
     * than the open entry's own start clamps its end to its start (a 0-minute
     * entry the owner can delete) rather than going negative.
     *
     * The ribbon never overlaps - the day must sum to 24 hours (owner's audit
     * rule). A robot fact inside the retroactive window is kept AND deducted:
     * "обедаю с 16:43", said at 16:53 over a YouTube entry 16:43-16:50,
     * splits the meal into fragments AROUND the robot's minutes. The chain
     * view then shows one block with the net time and the interruption as a
     * parallel row. An auto entry only straddling the start is trimmed to it.
     * Manual entries are never touched - owner vs owner is the owner's fight.
     *
     * Returns the OPEN tail fragment (the running дело).
     */
    suspend fun startEntry(
        start: Long,
        raw: String,
        title: String,
        category: String,
        client: String,
        useful: Int,
        source: String,
    ): Entry = mutex.withLock {
        ensureLoaded()
        snapshotLocked("запись «${title.trim().ifBlank { "без названия" }}»")
        closeOpenLocked(start)
        val nowMs = System.currentTimeMillis()
        for (i in entries.indices) {
            val e = entries[i]
            if (e.source == "auto" && !e.open && e.start < start && e.end > start) {
                entries[i] = e.copy(end = start, synced = false, notionSynced = false)
            }
        }
        val opened = Entry(
            id = nextId(),
            start = start,
            end = 0L,
            raw = raw.trim(),
            title = title.trim(),
            category = category.trim(),
            client = client.trim(),
            useful = useful.coerceIn(0, 5),
            source = source,
            synced = false,
            createdAt = nowMs,
        )
        entries.add(opened)
        normalizeLocked()
        entries.sortBy { it.start }
        persist()
        // After the splice the running дело is the (only) open fragment.
        entries.lastOrNull { it.open } ?: opened
    }

    /**
     * The second audit invariant: no entry crosses local midnight - each DAY
     * must sum to 24 h on its own ("сон 23:30-07:00" used to sit entirely in
     * yesterday). An entry spanning midnight splits into per-day segments:
     * the head keeps the id (its mirror rows update in place) and the 🍅,
     * later segments get fresh ids; an OPEN entry gets a closed head and the
     * open tail keeps running in the new day. Runs in the same normalize
     * pass as the overlap splice - split first, so an overnight auto fact
     * (сон) becomes day-bounded before containment is checked.
     */
    private fun splitMidnightLocked(): Boolean {
        val nowMs = System.currentTimeMillis()
        val cutoff = nowMs - NORMALIZE_WINDOW_MS
        var changed = false
        val out = ArrayList<Entry>(entries.size)
        for (e in entries) {
            val effEnd = if (e.open) nowMs else e.end
            if (effEnd <= cutoff) {
                out.add(e)
                continue
            }
            var dayEnd = nextMidnightMs(e.start)
            if (effEnd <= dayEnd) {
                out.add(e)
                continue
            }
            changed = true
            var segStart = e.start
            var first = true
            while (dayEnd < effEnd) {
                out.add(
                    e.copy(
                        id = if (first) e.id else nextId(),
                        start = segStart,
                        end = dayEnd,
                        pomodoros = if (first) e.pomodoros else 0,
                        synced = false,
                        notionSynced = false,
                    )
                )
                first = false
                segStart = dayEnd
                dayEnd = nextMidnightMs(segStart)
            }
            out.add(
                e.copy(
                    id = nextId(),
                    start = segStart,
                    end = if (e.open) 0L else e.end,
                    pomodoros = 0,
                    synced = false,
                    notionSynced = false,
                )
            )
        }
        if (changed) {
            entries = out
            entries.sortBy { it.start }
        }
        return changed
    }

    // Next LOCAL midnight after [ms] - Calendar arithmetic survives DST.
    private fun nextMidnightMs(ms: Long): Long {
        val cal = java.util.Calendar.getInstance()
        cal.timeInMillis = ms
        cal.set(java.util.Calendar.HOUR_OF_DAY, 0)
        cal.set(java.util.Calendar.MINUTE, 0)
        cal.set(java.util.Calendar.SECOND, 0)
        cal.set(java.util.Calendar.MILLISECOND, 0)
        cal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        return cal.timeInMillis
    }

    /**
     * The owner's rule: time without an entry is not "unknown", it is
     * «Потери». Three moves, all in the normalize pass:
     * (1) WRAP: a real entry landing inside a filler SUBTRACTS from it - the
     * losses instantly hug the дело from both sides, no re-quarantine; pieces
     * shorter than 5 min die as crumbs. A real claim over the RUNNING losses
     * clips their start (or kills them while a real дело is open).
     * (2) FILL: a bounded hole >= 5 min between entries becomes a closed
     * filler once its right edge is 45 min old (the retro-dictation window).
     * (3) LIVE: when nothing is open and the last entry ended >= 5 min ago,
     * an OPEN filler starts at that end - "прямо сейчас идут потери" is
     * visible in the ribbon and keeps counting until a real дело lands.
     * Deleting a filler by hand is futile by design - unrecorded time grows
     * back; the way out is to NAME the time (edit it into a real дело).
     */
    private fun trimFillersLocked(): Boolean {
        if (entries.none { it.source == GAP_SOURCE }) return false
        val nowMs = System.currentTimeMillis()
        val cutoff = nowMs - NORMALIZE_WINDOW_MS
        val real = entries.filter {
            it.source != GAP_SOURCE && (if (it.open) nowMs else it.end) > cutoff
        }
        var changed = false
        val out = ArrayList<Entry>(entries.size)
        for (e in entries) {
            if (e.source != GAP_SOURCE || (!e.open && e.end <= cutoff)) {
                out.add(e)
                continue
            }
            if (e.open) {
                // Running losses: a real open дело kills them; a real closed
                // claim reaching past their start clips them to its end.
                if (real.any { it.open }) {
                    changed = true
                    continue
                }
                val claimEnd = real
                    .filter { r -> r.end > e.start && r.start < nowMs }
                    .maxOfOrNull { it.end }
                when {
                    claimEnd == null || claimEnd <= e.start -> out.add(e)
                    nowMs - claimEnd < GAP_FILL_MIN_MS -> changed = true  // crumb - drop
                    else -> {
                        out.add(e.copy(start = claimEnd, synced = false, notionSynced = false))
                        changed = true
                    }
                }
                continue
            }
            // Closed filler: subtract every real span; remainders >= 5 min
            // stay as losses (the head keeps the id), crumbs disappear.
            var pieces = mutableListOf(e.start to e.end)
            for (r in real) {
                val rEnd = if (r.open) nowMs else r.end
                if (r.start >= e.end || rEnd <= e.start) continue
                val next = mutableListOf<Pair<Long, Long>>()
                for ((s, en) in pieces) {
                    if (r.start >= en || rEnd <= s) {
                        next.add(s to en)
                        continue
                    }
                    if (r.start > s) next.add(s to r.start)
                    if (rEnd < en) next.add(rEnd to en)
                }
                pieces = next
            }
            val kept = pieces.filter { (s, en) -> en - s >= GAP_FILL_MIN_MS }
            if (kept.size == 1 && kept[0].first == e.start && kept[0].second == e.end) {
                out.add(e)
                continue
            }
            changed = true
            var first = true
            for ((s, en) in kept) {
                out.add(
                    e.copy(
                        id = if (first) e.id else nextId(),
                        start = s,
                        end = en,
                        synced = false,
                        notionSynced = false,
                    )
                )
                first = false
            }
        }
        if (changed) {
            entries = out
            entries.sortBy { it.start }
        }
        return changed
    }

    private fun gapEntry(start: Long, end: Long, nowMs: Long) = Entry(
        id = nextId(),
        start = start,
        end = end,
        raw = "",
        title = "потери",
        category = GAP_CATEGORY,
        client = "",
        useful = 0,
        source = GAP_SOURCE,
        synced = false,
        createdAt = nowMs,
    )

    private fun fillGapsLocked(): Boolean {
        val nowMs = System.currentTimeMillis()
        val cutoff = nowMs - GAP_FILL_QUARANTINE_MS
        val windowStart = nowMs - NORMALIZE_WINDOW_MS
        var changed = false
        val asc = entries.sortedBy { it.start }
        var prevEnd = -1L
        for (e in asc) {
            if (prevEnd > 0 && e.start - prevEnd >= GAP_FILL_MIN_MS &&
                e.start <= cutoff && e.start > windowStart
            ) {
                entries.add(gapEntry(prevEnd, e.start, nowMs))
                changed = true
            }
            val eEnd = if (e.open) nowMs else e.end
            if (eEnd > prevEnd) prevEnd = eEnd
        }
        // The live tail: nothing is running and the ribbon has been silent
        // for 5 minutes - losses start counting from the last entry's end,
        // openly, right in the ribbon. A retro claim later takes the span
        // back through closeOpenLocked + the trim above.
        if (entries.none { it.open } && prevEnd > 0 && nowMs - prevEnd >= GAP_FILL_MIN_MS) {
            entries.add(gapEntry(prevEnd, 0L, nowMs))
            changed = true
        }
        if (changed) entries.sortBy { it.start }
        return changed
    }

    /**
     * Splice leftovers shorter than half a minute are artifacts, not facts -
     * they surfaced as 0-minute rows in the owner's export. The open entry and
     * anything the owner could plausibly have meant (>= 30 s) stay.
     */
    private fun dropCrumbsLocked(): Boolean {
        val kept = entries.filter { it.open || it.end - it.start >= CRUMB_MS }
        if (kept.size == entries.size) return false
        entries = kept.toMutableList()
        return true
    }

    /** All ribbon invariants in one locked pass; true when anything changed. */
    private fun normalizeLocked(): Boolean {
        var changed = splitMidnightLocked()
        if (dropCrumbsLocked()) changed = true
        if (trimFillersLocked()) changed = true
        if (spliceOverlapsLocked()) changed = true
        if (fillGapsLocked()) changed = true
        // Fillers created just now may cross midnight themselves.
        if (splitMidnightLocked()) changed = true
        return changed
    }

    /**
     * Periodic nudge (the service's 5-min tick): just past midnight the
     * running дело must split into yesterday's closed head and today's open
     * tail even if nothing else mutates the store until morning.
     */
    suspend fun normalize(): Unit = mutex.withLock {
        ensureLoaded()
        if (normalizeLocked()) persist()
    }

    /**
     * The audit invariant: the ribbon never overlaps, the day sums to 24 h.
     * Any manual entry that covers closed auto facts - however the overlap
     * appeared (retroactive start, a time edit in the dialog or by voice,
     * an old build's data) - is spliced into fragments AROUND them: the дело
     * stays the main block (one chain, net time, interruptions as parallel
     * rows), the robot's minutes stay the robot's. The head fragment keeps
     * the id (its Sheets/Notion rows update in place) and is created even at
     * zero length - it anchors the block at the declared start; later
     * fragments get fresh ids, 🍅 stay on the tail.
     */
    private fun spliceOverlapsLocked(): Boolean {
        val nowMs = System.currentTimeMillis()
        val cutoff = nowMs - NORMALIZE_WINDOW_MS
        val autosAsc = entries
            .filter { it.source == "auto" && !it.open && it.end > cutoff }
            .sortedBy { it.start }
        if (autosAsc.isEmpty()) return false
        val rebuilt = ArrayList<Entry>(entries.size)
        var changed = false
        for (m in entries) {
            // Auto facts are the things spliced AROUND; gap fillers are never
            // hosts either - they die to overlaps instead (clear-and-refill).
            // Settled history is skipped with them (see NORMALIZE_WINDOW_MS).
            if (m.source == "auto" || m.source == GAP_SOURCE ||
                (!m.open && m.end <= cutoff)
            ) {
                rebuilt.add(m)
                continue
            }
            val mEnd = if (m.open) Long.MAX_VALUE else m.end
            val inside = autosAsc.filter { a ->
                a.start >= m.start && a.end <= mEnd && a.start < mEnd && a.end > m.start
            }
            if (inside.isEmpty()) {
                rebuilt.add(m)
                continue
            }
            changed = true
            var cursor = m.start
            var firstSeg = true
            for (a in inside) {
                if (a.start > cursor || firstSeg) {
                    rebuilt.add(
                        m.copy(
                            id = if (firstSeg) m.id else nextId(),
                            start = cursor,
                            end = a.start.coerceAtLeast(cursor),
                            pomodoros = 0,
                            synced = false,
                            notionSynced = false,
                        )
                    )
                    firstSeg = false
                }
                cursor = kotlin.math.max(cursor, a.end)
            }
            rebuilt.add(
                m.copy(
                    id = if (firstSeg) m.id else nextId(),
                    start = cursor,
                    end = if (m.open) 0L else kotlin.math.max(m.end, cursor),
                    synced = false,
                    notionSynced = false,
                )
            )
        }
        if (changed) {
            entries = rebuilt
            entries.sortBy { it.start }
        }
        return changed
    }

    /** Closes the running entry at [at]; null when nothing was open. */
    suspend fun closeOpen(at: Long): Entry? = mutex.withLock {
        ensureLoaded()
        val closed = closeOpenLocked(at)
        if (closed != null) {
            // An overnight дело closes across midnight - split it per day.
            normalizeLocked()
            persist()
        }
        closed
    }

    private fun closeOpenLocked(at: Long): Entry? {
        val index = entries.indexOfLast { it.open }
        if (index < 0) return null
        val open = entries[index]
        val closed = open.copy(end = at.coerceAtLeast(open.start), synced = false, notionSynced = false)
        entries[index] = closed
        return closed
    }

    /**
     * A phone-detected interruption (attention-eater session, a call) lands
     * in the ribbon retroactively. If an open entry covers [start], it is cut
     * at [start]; with [resumePrevious] (calls) a copy of it reopens at [end]
     * - the conversation pauses the work, it does not kill it. An open entry
     * that STARTED inside the interruption wins instead: the auto entry is
     * clamped to its start and nothing is spliced (the owner spoke - the
     * owner is right).
     *
     * Returns null when an equal auto entry is already there (re-sweep).
     */
    suspend fun insertInterruption(
        start: Long,
        end: Long,
        title: String,
        category: String,
        resumePrevious: Boolean,
        client: String = "",
    ): Entry? = mutex.withLock {
        ensureLoaded()
        if (end <= start) return@withLock null
        // Iron dedup, two rules. Same-title near-same-start catches a re-scan
        // of the same source row. The overlap rule catches messier realities
        // (the call log can hold SEVERAL rows for one call - VoIP apps write
        // their own copies): auto entries never legitimately overlap, the
        // ribbon is continuous by construction, so a newcomer covering an
        // existing auto entry by half its span is a duplicate, not a fact.
        val cleanTitle = title.trim()
        val newSpan = end - start
        if (entries.any { e ->
                e.source == "auto" && !e.open && (
                    (e.title == cleanTitle && kotlin.math.abs(e.start - start) < 60_000) ||
                        (kotlin.math.min(e.end, end) - kotlin.math.max(e.start, start))
                            .coerceAtLeast(0L) * 2 >= newSpan
                    )
            }
        ) return@withLock null
        var actualEnd = end
        var resumeTemplate: Entry? = null
        val openIndex = entries.indexOfLast { it.open }
        if (openIndex >= 0) {
            val open = entries[openIndex]
            if (open.start <= start) {
                entries[openIndex] = open.copy(end = start.coerceAtLeast(open.start), synced = false)
                if (resumePrevious) resumeTemplate = open
            } else if (open.start < end) {
                actualEnd = open.start
                if (actualEnd <= start) return@withLock null
            }
        }
        val entry = Entry(
            id = nextId(),
            start = start,
            end = actualEnd,
            raw = "",
            title = title.trim(),
            category = category.trim(),
            client = client.trim(),
            useful = 0,
            source = "auto",
            synced = false,
            createdAt = System.currentTimeMillis(),
        )
        entries.add(entry)
        resumeTemplate?.let { t ->
            // The resume keeps the ORIGINAL source: it is the owner's own
            // activity continuing, not a robot fact - so the dedup and
            // coveredByOwner rules treat it like the human's claim it is.
            // But NOT the dictation text: raw belongs to one act of speaking.
            // Copying it made rows whose raw described a past event - and if a
            // later take named something else, the row would read as a lie.
            entries.add(
                t.copy(
                    id = nextId(),
                    raw = "",
                    start = actualEnd,
                    end = 0L,
                    synced = false,
                    notionSynced = false,
                    createdAt = System.currentTimeMillis(),
                )
            )
        }
        // The night's сон spans midnight and the evening entry it cut may
        // too - normalize right here, not only on the next load.
        normalizeLocked()
        entries.sortBy { it.start }
        persist()
        entry
    }

    /**
     * Full replace by id. Any content change makes the row sync again.
     * A time edit that lands the entry OVER auto facts makes it the main
     * дело: the splice runs right here, not only on load.
     */
    suspend fun update(entry: Entry): Unit = mutex.withLock {
        ensureLoaded()
        val index = entries.indexOfFirst { it.id == entry.id }
        if (index >= 0) {
            snapshotLocked("правку «${entries[index].title.ifBlank { "без названия" }}»")
            entries[index] = entry.copy(synced = false, notionSynced = false)
            normalizeLocked()
            entries.sortBy { it.start }
            persist()
        }
    }

    suspend fun delete(id: Long): Unit = mutex.withLock {
        ensureLoaded()
        val doomed = entries.firstOrNull { it.id == id } ?: return@withLock
        snapshotLocked("удаление «${doomed.title.ifBlank { "без названия" }}»")
        if (entries.removeAll { it.id == id }) persist()
    }

    /** A completed 🍅 is credited to the entry that was running. */
    suspend fun incrementPomodoro(id: Long): Unit = mutex.withLock {
        ensureLoaded()
        val index = entries.indexOfFirst { it.id == id }
        if (index >= 0) {
            entries[index] = entries[index].copy(pomodoros = entries[index].pomodoros + 1)
            persist()
        }
    }

    /** Entries overlapping [from, to) - for the day view and the digests. */
    suspend fun forRange(from: Long, to: Long): List<Entry> = mutex.withLock {
        ensureLoaded()
        entries.filter { e ->
            val effectiveEnd = if (e.open) Long.MAX_VALUE else e.end
            e.start < to && effectiveEnd > from
        }
    }

    /**
     * True when the owner's own CLOSED entries already claim most of
     * [from, to) - the shared "human beats robot" rule every auto-inserter
     * (attention eaters, calls, sleep, workouts) checks before writing.
     */
    suspend fun coveredByOwner(from: Long, to: Long): Boolean {
        if (to <= from) return true
        val manualMs = forRange(from, to)
            // Gap fillers are NOT the owner's claim - a night filled with
            // «Потери» must not block the сон insert that explains it.
            .filter { !it.open && it.source != "auto" && it.source != GAP_SOURCE }
            .sumOf {
                (kotlin.math.min(it.end, to) - kotlin.math.max(it.start, from)).coerceAtLeast(0L)
            }
        return manualMs * 2 >= to - from
    }

    /** Closed entries the Sheets mirror has not seen yet, oldest first. */
    suspend fun unsynced(): List<Entry> = mutex.withLock {
        ensureLoaded()
        entries.filter { !it.open && !it.synced }
    }

    suspend fun markSynced(ids: Collection<Long>): Unit = mutex.withLock {
        ensureLoaded()
        if (ids.isEmpty()) return@withLock
        val idSet = ids.toSet()
        entries = entries.map { if (it.id in idSet) it.copy(synced = true) else it }.toMutableList()
        persist()
    }


    // ---- CSV export (same share pattern as the transcription metrics) ----

    suspend fun shareCsvIntent(): Intent {
        val list = all()
        val dateFormat = SimpleDateFormat("yyyy-MM-dd", Locale.US)
        val timeFormat = SimpleDateFormat("HH:mm", Locale.US)
        val now = System.currentTimeMillis()
        var previousRaw = ""
        val csv = buildString {
            // end/minutes are never blank now (a running entry is closed at
            // "now" and flagged is_open), source tells a dictated row from one
            // the app filled in, and raw is printed once per act of speaking -
            // continuation fragments leave it empty instead of repeating it.
            append("date,start,end,minutes,title,category,client,useful,source,is_open,raw\n")
            for (e in list) {
                val end = if (e.open) now else e.end
                append(dateFormat.format(Date(e.start))).append(',')
                append(timeFormat.format(Date(e.start))).append(',')
                append(timeFormat.format(Date(end))).append(',')
                append(e.durationMin(now).toString()).append(',')
                append(csvEscape(e.title)).append(',')
                append(csvEscape(e.category)).append(',')
                append(csvEscape(e.client)).append(',')
                append(if (e.useful > 0) e.useful.toString() else "").append(',')
                append(e.source).append(',')
                append(if (e.open) "true" else "false").append(',')
                val raw = if (e.raw.isNotBlank() && e.raw == previousRaw) "" else e.raw
                if (e.raw.isNotBlank()) previousRaw = e.raw
                append(csvEscape(raw)).append('\n')
            }
        }
        val out = File(context.cacheDir, "pravka-zasechka.csv")
        withContext(Dispatchers.IO) { out.writeText(csv) }
        return shareFileIntent(context, out, "text/csv")
    }

    private fun csvEscape(s: String): String {
        if (s.none { it == ',' || it == '"' || it == '\n' || it == '\r' }) return s
        return "\"" + s.replace("\"", "\"\"").replace('\r', ' ').replace('\n', ' ') + "\""
    }

    // ---- persistence ----

    // Ids must stay unique across process restarts; wall-clock ms is unique
    // for a single human, and the max() guard survives a clock step back.
    private fun nextId(): Long {
        val id = System.currentTimeMillis().coerceAtLeast(lastId + 1)
        lastId = id
        return id
    }

    private suspend fun ensureLoaded() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            val root = StoreFiles.readOrQuarantine(file) { JSONObject(it) }
            categories = root?.optJSONArray("categories")?.toCategoryList()?.toMutableList()
                ?: DEFAULT_CATEGORIES.toMutableList()
            catSeedVersion = root?.optInt("catSeed", 1) ?: CAT_SEED_VERSION
            if (root != null && catSeedVersion < CAT_SEED_VERSION) {
                // v1 -> v2: the owner's real taxonomy replaced the draft, but
                // only if the list was never touched - hand edits win.
                if (catSeedVersion < 2 && categories.map { it.name }.toSet() == SEED_V1_NAMES) {
                    categories = DEFAULT_CATEGORIES.toMutableList()
                }
                // v2 -> v3 is ADDITIVE (owner asked for new categories): the
                // old "Секс" splits into the two new ones, everything else
                // missing from the seed is appended; hand edits survive.
                if (catSeedVersion < 3) {
                    categories = categories
                        .filter { !it.name.equals("Секс", ignoreCase = true) }
                        .toMutableList()
                }
                // v4 briefly added «Прокрастинация»; v5 renames it to «Потери»
                // (owner's call: the automatic filler for unrecorded time) and
                // appends whatever else the seed has and the list lacks.
                if (catSeedVersion < 5) {
                    val loss = DEFAULT_CATEGORIES.first { it.name == GAP_CATEGORY }
                    val old = categories.indexOfFirst {
                        it.name.equals("Прокрастинация", ignoreCase = true)
                    }
                    if (categories.none { it.name.equals(GAP_CATEGORY, ignoreCase = true) }) {
                        if (old >= 0) categories[old] = loss
                    } else if (old >= 0) {
                        categories.removeAt(old)
                    }
                    for (c in DEFAULT_CATEGORIES) {
                        if (categories.none { it.name.equals(c.name, ignoreCase = true) }) {
                            categories.add(c)
                        }
                    }
                }
                catSeedVersion = CAT_SEED_VERSION
                persistQueued()
            }
            clients = root?.optJSONArray("clients")?.toStringList()?.toMutableList() ?: mutableListOf()
            entries = mutableListOf()
            root?.optJSONArray("entries")?.let { array ->
                for (i in 0 until array.length()) {
                    val o = array.optJSONObject(i) ?: continue
                    val start = o.optLong("start", 0)
                    if (start <= 0) continue
                    entries.add(
                        Entry(
                            id = o.optLong("id", 0),
                            start = start,
                            end = o.optLong("end", 0),
                            raw = o.optString("raw", ""),
                            title = o.optString("title", ""),
                            category = o.optString("category", ""),
                            client = o.optString("client", ""),
                            useful = o.optInt("useful", 0),
                            source = o.optString("source", "voice"),
                            synced = o.optBoolean("synced", false),
                            createdAt = o.optLong("createdAt", start),
                            pomodoros = o.optInt("pomodoros", 0),
                            notionSynced = o.optBoolean("notionSynced", false),
                        )
                    )
                }
            }
            entries.sortBy { it.start }
            // One-time hygiene: collapse duplicate auto rows that piled up
            // before the overlap guard existed (one call, many log rows).
            val seenAuto = HashSet<String>()
            val cleaned = entries.filter { e ->
                e.source != "auto" || e.open ||
                    seenAuto.add("${e.title}|${e.start / 60_000}|${e.end / 60_000}")
            }
            if (cleaned.size != entries.size) {
                entries = cleaned.toMutableList()
                persistQueued()
            }
            lastId = entries.maxOfOrNull { it.id } ?: 0L
            // Repair old data with the same shared pass: midnight-crossing
            // entries split per day, overlaps splice around auto facts.
            if (normalizeLocked()) persistQueued()
            if (root == null) persistQueued()
        }
        loaded = true
        publish()
    }

    private fun publish() {
        _undoFlow.value = undoSteps.lastOrNull()?.label
        _entriesFlow.value = entries.toList()
        _categoriesFlow.value = categories.toList()
        _clientsFlow.value = clients.toList()
    }

    private fun persist() {
        persistQueued()
        publish()
    }

    private fun persistQueued() {
        val json = toJson().toString()
        DiskWriter.post { StoreFiles.writeAtomic(file, json) }
    }

    private fun toJson(): JSONObject = JSONObject().apply {
        put("format", FORMAT)
        put("version", 1)
        put("catSeed", catSeedVersion)
        put(
            "categories",
            JSONArray().apply {
                for (c in categories) {
                    put(JSONObject().apply { put("name", c.name); put("hint", c.hint) })
                }
            }
        )
        put("clients", JSONArray(clients))
        put(
            "entries",
            JSONArray().apply {
                for (e in entries) {
                    put(
                        JSONObject().apply {
                            put("id", e.id)
                            put("start", e.start)
                            put("end", e.end)
                            put("raw", e.raw)
                            put("title", e.title)
                            put("category", e.category)
                            put("client", e.client)
                            put("useful", e.useful)
                            put("source", e.source)
                            put("synced", e.synced)
                            put("createdAt", e.createdAt)
                            if (e.pomodoros > 0) put("pomodoros", e.pomodoros)
                            put("notionSynced", e.notionSynced)
                        }
                    )
                }
            }
        )
    }
}

private fun JSONArray.toStringList(): List<String> =
    (0 until length()).mapNotNull { optString(it).takeIf { s -> s.isNotBlank() } }

// Categories were plain strings in v1 files; both shapes stay readable.
private fun JSONArray.toCategoryList(): List<ZasechkaStore.Category> =
    (0 until length()).mapNotNull { i ->
        optJSONObject(i)?.let { o ->
            o.optString("name").trim().takeIf { it.isNotEmpty() }
                ?.let { ZasechkaStore.Category(it, o.optString("hint").trim()) }
        } ?: optString(i).trim().takeIf { it.isNotEmpty() }?.let { ZasechkaStore.Category(it, "") }
    }

/** Start of the local calendar day containing [at]. */
fun dayStartMs(at: Long): Long {
    val cal = Calendar.getInstance()
    cal.timeInMillis = at
    cal.set(Calendar.HOUR_OF_DAY, 0)
    cal.set(Calendar.MINUTE, 0)
    cal.set(Calendar.SECOND, 0)
    cal.set(Calendar.MILLISECOND, 0)
    return cal.timeInMillis
}
