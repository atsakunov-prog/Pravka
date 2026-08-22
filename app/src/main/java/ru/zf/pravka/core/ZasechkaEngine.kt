package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.ZasechkaSync
import ru.zf.pravka.data.dayStartMs
import ru.zf.pravka.provider.ClaudeProvider

// Засечка's counterpart to ProofreadEngine: one dictated (or typed) phrase in,
// one timesheet ACTION out. Usually that's a new entry (closes the previous
// open one -> continuous ribbon), but the owner can also command an EDIT
// ("поменяй мастурбацию с 16:00 на разработку") or a DELETE - Sonnet sees the
// day's numbered entries and points at the one to change. The hard rules:
// the take is NEVER lost (categorization failed -> saved raw), and when the
// intent is ambiguous the model is told to prefer "new" over touching data.
class ZasechkaEngine(
    private val claude: ClaudeProvider,
    private val store: ZasechkaStore,
    private val stats: Stats,
    private val eventLog: EventLog,
    private val sync: ZasechkaSync,
    private val scope: CoroutineScope,
) {

    data class Outcome(
        val entry: ZasechkaStore.Entry,
        val categorized: Boolean,
        val error: String?,
        val action: String = "new",       // "new" | "edit" | "delete"
        val previousTitle: String = "",   // edit: what the entry used to say
    )

    // "четверг, 21 августа, 14:32" - the model needs the weekday and clock to
    // resolve "с 13:00" and "последние полчаса" into an offset.
    private val nowFormat = SimpleDateFormat("EEEE, d MMMM, HH:mm", Locale("ru"))
    private val timeFormat = SimpleDateFormat("HH:mm", Locale.US)

    suspend fun record(raw: String, source: String): Outcome {
        val now = System.currentTimeMillis()
        val text = raw.trim()
        val categories = store.categories()
        val categoryNames = categories.map { it.name }
        val clients = store.clients()
        val previousTitle = store.all().lastOrNull()?.title.orEmpty()

        // Today's ribbon, numbered - the reference frame for edit/delete.
        val today = store.forRange(dayStartMs(now), now + 1).sortedBy { it.start }
        val todayLines = today.mapIndexed { i, e ->
            val end = if (e.open) "…" else timeFormat.format(Date(e.end))
            "${i + 1}. ${timeFormat.format(Date(e.start))}–$end · " +
                "${e.category.ifBlank { "без категории" }} · ${e.title.ifBlank { "(без названия)" }}"
        }

        val parsed = claude.zasechka(
            raw = text,
            categories = categories.map { it.name to it.hint },
            clients = clients,
            nowLocal = nowFormat.format(Date(now)),
            previousTitle = previousTitle,
            todayEntries = todayLines,
        )

        return parsed.fold(
            onSuccess = { p ->
                stats.recordAux(p.costUsd, p.tokensIn, p.tokensOut)
                val target = today.getOrNull(p.entryIndex - 1)
                when {
                    // Edit: touch only the fields the owner asked to change.
                    // The numbered line may be one FRAGMENT of a sliced-up дело
                    // - words apply to the whole chain, a new start moves the
                    // first fragment, a new end moves (or closes) the last.
                    p.action == "edit" && target != null -> {
                        val chain = expandChain(target, today)
                        val first = chain.first()
                        val last = chain.last()
                        // "еда была с 16:43 до 17:40" - clock times land on the
                        // chain's own day; a named end also closes an open дело.
                        val newStart = timeOnDay(first.start, p.startTime)
                        val newEnd = timeOnDay(first.start, p.endTime)
                        var shown: ZasechkaStore.Entry = target
                        for (f in chain) {
                            var nf = f.copy(
                                title = p.title.ifBlank { f.title },
                                category = categoryNames
                                    .firstOrNull { it.equals(p.category, ignoreCase = true) }
                                    ?: p.category.ifBlank { f.category },
                                client = p.client.ifBlank { f.client },
                                useful = if (p.useful > 0) p.useful else f.useful,
                                // A gap filler the owner NAMED is his claim now,
                                // not a filler - it must stop dying to overlaps.
                                source = if (f.source == "gap") "edit" else f.source,
                            )
                            if (newStart != null && f.id == first.id) {
                                nf = nf.copy(
                                    start = if (f.open) newStart else newStart.coerceAtMost(f.end),
                                )
                            }
                            if (newEnd != null && f.id == last.id) {
                                nf = nf.copy(end = newEnd.coerceAtLeast(nf.start))
                            }
                            store.update(nf)
                            if (f.id == target.id) shown = nf
                        }
                        eventLog.add(
                            "засечка-правка: «${target.title}» → «${shown.title}» [${shown.category}]" +
                                (if (chain.size > 1) " (${chain.size} куска)" else "")
                        )
                        sync.kickSoon(scope)
                        Outcome(shown, categorized = true, error = null, action = "edit", previousTitle = target.title)
                    }
                    p.action == "delete" && target != null -> {
                        val chain = expandChain(target, today)
                        chain.forEach { store.delete(it.id) }
                        eventLog.add(
                            "засечка-правка: удалена «${target.title}»" +
                                (if (chain.size > 1) " (${chain.size} куска)" else "")
                        )
                        sync.kickSoon(scope)
                        Outcome(target, categorized = true, error = null, action = "delete", previousTitle = target.title)
                    }
                    // Everything else (including an edit that failed to point
                    // at a real entry) lands as a NEW entry - data first.
                    else -> {
                        val start = now - p.startOffsetMin * 60_000L
                        val entry = store.startEntry(
                            start = start,
                            raw = text,
                            title = p.title.ifBlank { fallbackTitle(text) },
                            // Canonical casing when the model matched a known value.
                            category = categoryNames
                                .firstOrNull { it.equals(p.category, ignoreCase = true) }
                                ?: p.category,
                            client = clients.firstOrNull { it.equals(p.client, ignoreCase = true) }
                                ?: p.client,
                            useful = p.useful,
                            source = source,
                        )
                        eventLog.add(
                            "засечка: «${entry.title}» [${entry.category.ifBlank { "без категории" }}]" +
                                (if (p.startOffsetMin > 0) " с −${p.startOffsetMin} мин" else "")
                        )
                        sync.kickSoon(scope)
                        Outcome(entry, categorized = true, error = null)
                    }
                }
            },
            onFailure = { e ->
                stats.recordError()
                // Saved raw and unsorted - data first, categories later.
                val entry = store.startEntry(
                    start = now,
                    raw = text,
                    title = fallbackTitle(text),
                    category = "",
                    client = "",
                    useful = 0,
                    source = source,
                )
                eventLog.add("засечка: разбор не удался (${e.message}) — записано сырым")
                sync.kickSoon(scope)
                Outcome(entry, categorized = false, error = e.message)
            },
        )
    }

    /** Closes the running entry ("перерыв"/"конец дня"). Null if none was open. */
    suspend fun closeOpen(): ZasechkaStore.Entry? {
        val closed = store.closeOpen(System.currentTimeMillis())
        if (closed != null) {
            eventLog.add("засечка: закрыто «${closed.title}» (${closed.durationMin()} мин)")
            sync.kickSoon(scope)
        }
        return closed
    }

    private fun fallbackTitle(text: String): String {
        val cut = text.take(60)
        return if (cut.length < text.length) cut.trimEnd() + "…" else cut
    }

    private fun entrySigOf(e: ZasechkaStore.Entry): String =
        "${e.title.trim().lowercase()}|${e.category.trim().lowercase()}|${e.client.trim().lowercase()}"

    /**
     * Grows the voice edit's target to the whole sliced-up дело: neighboring
     * same-signature fragments whose gaps are fully covered (± 5 min) by
     * closed auto interruptions between them - the same rule the ribbon uses
     * to draw one block. A lone entry comes back as a list of one.
     */
    private fun expandChain(
        target: ZasechkaStore.Entry,
        todayAsc: List<ZasechkaStore.Entry>,
    ): List<ZasechkaStore.Entry> {
        val sig = entrySigOf(target)
        val chain = ArrayList<ZasechkaStore.Entry>()
        chain.add(target)
        val idx = todayAsc.indexOfFirst { it.id == target.id }
        if (idx < 0) return chain

        fun covered(from: ZasechkaStore.Entry, to: ZasechkaStore.Entry, between: List<ZasechkaStore.Entry>): Boolean {
            if (from.open || between.any { it.source != "auto" || it.open }) return false
            val cov = between.sumOf {
                (minOf(it.end, to.start) - maxOf(it.start, from.end)).coerceAtLeast(0L)
            }
            return to.start - from.end - cov <= 5 * 60_000L
        }

        var leftIdx = idx
        while (true) {
            val prevIdx = (leftIdx - 1 downTo 0)
                .firstOrNull { entrySigOf(todayAsc[it]) == sig } ?: break
            val between = todayAsc.subList(prevIdx + 1, leftIdx)
            if (!covered(todayAsc[prevIdx], chain.first(), between)) break
            chain.add(0, todayAsc[prevIdx])
            leftIdx = prevIdx
        }
        var rightIdx = idx
        while (true) {
            val nextIdx = (rightIdx + 1 until todayAsc.size)
                .firstOrNull { entrySigOf(todayAsc[it]) == sig } ?: break
            val between = todayAsc.subList(rightIdx + 1, nextIdx)
            if (!covered(chain.last(), todayAsc[nextIdx], between)) break
            chain.add(todayAsc[nextIdx])
            rightIdx = nextIdx
        }
        return chain
    }

    /** "16:43" -> epoch ms of that clock time on [anchor]'s day; null = keep. */
    private fun timeOnDay(anchor: Long, hhmm: String): Long? {
        if (hhmm.isBlank()) return null
        val m = Regex("^(\\d{1,2}):(\\d{2})$").find(hhmm) ?: return null
        val h = m.groupValues[1].toInt()
        val min = m.groupValues[2].toInt()
        if (h > 23 || min > 59) return null
        val cal = Calendar.getInstance()
        cal.timeInMillis = anchor
        cal.set(Calendar.HOUR_OF_DAY, h)
        cal.set(Calendar.MINUTE, min)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
