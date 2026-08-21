package ru.zf.pravka.core

import java.text.SimpleDateFormat
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
                    p.action == "edit" && target != null -> {
                        val updated = target.copy(
                            title = p.title.ifBlank { target.title },
                            category = categoryNames
                                .firstOrNull { it.equals(p.category, ignoreCase = true) }
                                ?: p.category.ifBlank { target.category },
                            client = p.client.ifBlank { target.client },
                            useful = if (p.useful > 0) p.useful else target.useful,
                        )
                        store.update(updated)
                        eventLog.add("засечка-правка: «${target.title}» → «${updated.title}» [${updated.category}]")
                        sync.kickSoon(scope)
                        Outcome(updated, categorized = true, error = null, action = "edit", previousTitle = target.title)
                    }
                    p.action == "delete" && target != null -> {
                        store.delete(target.id)
                        eventLog.add("засечка-правка: удалена «${target.title}»")
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
}
