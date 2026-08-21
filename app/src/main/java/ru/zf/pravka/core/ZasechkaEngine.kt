package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.CoroutineScope
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.ZasechkaStore
import ru.zf.pravka.data.ZasechkaSync
import ru.zf.pravka.provider.ClaudeProvider

// Засечка's counterpart to ProofreadEngine: one dictated (or typed) phrase in,
// one structured timesheet entry out. The pipeline is
//   phrase -> Sonnet (title/category/client/usefulness/retro-start)
//          -> store (closes the previous open entry -> continuous ribbon)
//          -> Sheets mirror kick.
// The hard rule: the take is NEVER lost. If categorization fails (no network,
// no key), the phrase is still saved raw with an empty category - the owner
// sorts it later in the tab.
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
    )

    // "четверг, 21 августа, 14:32" - the model needs the weekday and clock to
    // resolve "с 13:00" and "последние полчаса" into an offset.
    private val nowFormat = SimpleDateFormat("EEEE, d MMMM, HH:mm", Locale("ru"))

    suspend fun record(raw: String, source: String): Outcome {
        val now = System.currentTimeMillis()
        val text = raw.trim()
        val categories = store.categories()
        val clients = store.clients()
        val previousTitle = store.all().lastOrNull()?.title.orEmpty()

        val parsed = claude.zasechka(
            raw = text,
            categories = categories,
            clients = clients,
            nowLocal = nowFormat.format(Date(now)),
            previousTitle = previousTitle,
        )

        return parsed.fold(
            onSuccess = { p ->
                stats.recordAux(p.costUsd, p.tokensIn, p.tokensOut)
                val start = now - p.startOffsetMin * 60_000L
                val entry = store.startEntry(
                    start = start,
                    raw = text,
                    title = p.title.ifBlank { fallbackTitle(text) },
                    // Canonical casing when the model matched a known value.
                    category = categories.firstOrNull { it.equals(p.category, ignoreCase = true) }
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
