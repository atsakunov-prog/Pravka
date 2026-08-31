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

    companion object {
        // Сколько прошлых дней показывать разборщику как «вот его словарь дел».
        private const val RECENT_DAYS = 4L
    }

    data class Outcome(
        val entry: ZasechkaStore.Entry,
        val categorized: Boolean,
        val error: String?,
        val action: String = "new",       // new | insert | parallel | edit | delete | stop | none
        val previousTitle: String = "",   // edit: what the entry used to say
        val say: String = "",             // none: почему ничего не записано
        // Вторая половина фразы, если она была: «…и параллельно смотрел ютуб».
        val parallel: ZasechkaStore.Entry? = null,
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
            "${i + 1}. ${if (e.parallel) "∥ " else ""}${timeFormat.format(Date(e.start))}–$end · " +
                "${e.category.ifBlank { "без категории" }} · ${e.title.ifBlank { "(без названия)" }}"
        }

        // Прошлые дни для контекста: как владелец САМ называл свои дела и в
        // какие категории их клал (в том числе после правок руками). Без этого
        // одно и то же дело приезжает каждый день под новым именем, и неделя
        // не складывается.
        val dayStart = dayStartMs(now)
        val recentLines = store.forRange(dayStart - RECENT_DAYS * 86_400_000L, dayStart)
            .filter { it.source != "gap" && it.source != "auto" && it.title.isNotBlank() }
            .groupBy { "${it.title.trim().lowercase()}|${it.category.trim().lowercase()}" }
            .values
            .mapNotNull { group -> group.maxByOrNull { it.start }?.let { it to group.size } }
            .sortedByDescending { (last, _) -> last.start }
            .take(30)
            .map { (last, times) ->
                "- «${last.title}» [${last.category.ifBlank { "без категории" }}]" +
                    (if (times > 1) " ×$times" else "")
            }

        val parsed = claude.zasechka(
            raw = text,
            categories = categories.map { it.name to it.hint },
            clients = clients,
            nowLocal = nowFormat.format(Date(now)),
            previousTitle = previousTitle,
            todayEntries = todayLines,
            recentEntries = recentLines,
        )

        return parsed.fold(
            onSuccess = { p ->
                stats.recordAux(p.costUsd, p.tokensIn, p.tokensOut)
                val target = today.getOrNull(p.entryIndex - 1)
                // ВРЕМЯ РЕШАЕТ, А НЕ СЛОВО МОДЕЛИ. Раньше вставка задним
                // числом случалась только когда модель САМА назвала намерение
                // «insert», а начало из «с 12:00» в ветке new просто терялось —
                // запись начиналась «сейчас». Отсюда и шло то, что владелец
                // назвал «очень нестабильно работает текст в таймшит, если по
                // предыдущему»: одна и та же фраза то ложилась верно, то
                // съедала полдня. Теперь границы считаются ВСЕГДА, а new это
                // или insert — выводится из них: назван конец в прошлом,
                // значит кусок закрыт, что бы модель ни думала.
                val said = spokenSpan(p, now)
                val insertSpan: Pair<Long, Long>? =
                    if (p.action == "new" || p.action == "insert") said.closedPast(now) else null
                when {
                    // «Всё, закончил» — закрыть открытое, нового не начинать.
                    p.action == "stop" -> {
                        val at = (timeOnDay(now, p.endTime)
                            ?: (now - p.startOffsetMin * 60_000L)).coerceAtMost(now)
                        val closed = store.closeOpen(at)
                        if (closed == null) {
                            Outcome(
                                fakeEntry(now, text),
                                categorized = true,
                                error = null,
                                action = "none",
                                say = "Открытого дела нет — закрывать нечего",
                            )
                        } else {
                            eventLog.add("засечка: закрыто голосом «${closed.title}» (${closed.durationMin()} мин)")
                            sync.kickSoon(scope)
                            Outcome(closed, categorized = true, error = null, action = "stop")
                        }
                    }
                    // Не про ленту (будущее, мусор распознавания) — не пишем.
                    p.action == "none" -> {
                        eventLog.add("засечка: не записано — ${p.say.ifBlank { "не про ленту" }}")
                        Outcome(
                            fakeEntry(now, text),
                            categorized = true,
                            error = null,
                            action = "none",
                            say = p.say.ifBlank { "это не про ленту" },
                        )
                    }
                    // «Параллельно слушаю Акунина» — только второй трек:
                    // основное дело как шло, так и идёт, и минут не теряет.
                    p.action == "parallel" -> {
                        val title = p.title.ifBlank { fallbackTitle(text) }
                        val category = canonicalCategory(p.category, categoryNames)
                        val client = canonicalClient(p.client, clients)
                        // «Ой, пока был в туалете, смотрел ютуб» — параллель к
                        // УЖЕ ЗАПИСАННОМУ делу: границы берутся у него, а не у
                        // часов. Целиком по цепочке: дело могло быть разрезано.
                        val over = target?.let { expandChain(it, today) }
                        val span = said.closedPast(now)
                        val entry = when {
                            // Дело ещё идёт — параллель тоже открытая, с его начала.
                            over != null && over.last().open -> store.startParallel(
                                start = over.first().start, raw = text,
                                title = title, category = category, client = client, source = source,
                            )
                            over != null -> store.insertParallel(
                                start = over.first().start, end = over.last().end, raw = text,
                                title = title, category = category, client = client, source = source,
                            )
                            span != null -> store.insertParallel(
                                start = span.first, end = span.second, raw = text,
                                title = title, category = category, client = client, source = source,
                            )
                            else -> store.startParallel(
                                start = said.start ?: now, raw = text,
                                title = title, category = category, client = client, source = source,
                            )
                        }
                        if (entry == null) {
                            Outcome(
                                fakeEntry(now, text), categorized = false,
                                error = "Параллель не записалась", action = "none",
                            )
                        } else {
                            eventLog.add(
                                "засечка ∥ «${entry.title}» [${entry.category.ifBlank { "без категории" }}]" +
                                    (over?.let { " поверх «${it.first().title}»" } ?: "")
                            )
                            sync.kickSoon(scope)
                            Outcome(entry, categorized = true, error = null, action = "parallel")
                        }
                    }
                    insertSpan != null -> {
                        val (insStart, insEnd) = insertSpan
                        val entry = store.insertClosed(
                            start = insStart,
                            end = insEnd,
                            raw = text,
                            title = p.title.ifBlank { fallbackTitle(text) },
                            category = categoryNames
                                .firstOrNull { it.equals(p.category, ignoreCase = true) }
                                ?: p.category,
                            client = clients.firstOrNull { it.equals(p.client, ignoreCase = true) }
                                ?: p.client,
                            useful = p.useful,
                        )
                        if (entry == null) {
                            eventLog.add("засечка-вставка: «${p.title}» не поместилась — внутри записи владельца")
                            return@fold Outcome(
                                fakeEntry(now, text),
                                categorized = false,
                                error = "Там уже есть твои записи — поправь руками",
                                action = "insert",
                            )
                        }
                        // «С 18:00 до 19:00 готовил еду и слушал книгу»:
                        // параллель берёт границы у первого дела.
                        val alongside = if (!p.hasParallel) null else store.insertParallel(
                            start = insStart,
                            end = insEnd,
                            raw = text,
                            title = p.parallelTitle,
                            category = canonicalCategory(p.parallelCategory, categoryNames),
                            client = canonicalClient(p.parallelClient, clients),
                            source = source,
                        )
                        eventLog.add(
                            "засечка-вставка: «${entry.title}» " +
                                "[${entry.category.ifBlank { "без категории" }}] " +
                                "${(insEnd - insStart) / 60_000} мин задним числом, обрамление продолжено" +
                                (alongside?.let { " ∥ «${it.title}»" } ?: "")
                        )
                        sync.kickSoon(scope)
                        Outcome(entry, categorized = true, error = null, action = "insert", parallel = alongside)
                    }
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
                        // Начало берём из названного времени, если оно было:
                        // «с 12:00 время с семьёй» обязано начаться в 12:00,
                        // а не сейчас. Не назвали — отступ назад, не назвали и
                        // его — сейчас.
                        val start = said.start ?: now
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
                        // «Готовлю еду и параллельно смотрю ютуб»: первое дело
                        // в ленту, второе поверх него — и еда остаётся едой.
                        val alongside = if (!p.hasParallel) null else store.startParallel(
                            start = start,
                            raw = text,
                            title = p.parallelTitle,
                            category = canonicalCategory(p.parallelCategory, categoryNames),
                            client = canonicalClient(p.parallelClient, clients),
                            source = source,
                        )
                        eventLog.add(
                            "засечка: «${entry.title}» [${entry.category.ifBlank { "без категории" }}]" +
                                (if (start < now - 60_000L)
                                    " задним числом с " + timeFormat.format(Date(start))
                                else "") +
                                (alongside?.let { " ∥ «${it.title}» [${it.category}]" } ?: "")
                        )
                        sync.kickSoon(scope)
                        Outcome(entry, categorized = true, error = null, parallel = alongside)
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

    /**
     * Что владелец сказал про время — одним местом и по одним правилам.
     *
     * Модель возвращает четыре поля, и раньше каждая ветка разбирала их
     * по-своему: вставка смотрела на все четыре, новая запись — только на
     * отступ назад, а названное «с 12:00» в ней терялось молча. Здесь они
     * сводятся один раз, и дальше обе ветки работают с готовыми границами.
     *
     * Порядок приоритетов ровно такой, каким владелец и говорит: названное
     * время точнее, чем «полчаса назад», а «полчаса назад» точнее, чем
     * ничего.
     */
    private data class Span(val start: Long?, val end: Long?) {
        /**
         * Закрытый кусок в прошлом — то, что кладётся вставкой. Обе границы
         * известны, конец позже начала и не в будущем. Минута запаса: пока
         * фраза договаривается и уезжает в модель, «до 16:52» успевает стать
         * будущим на десяток секунд.
         */
        fun closedPast(now: Long): Pair<Long, Long>? {
            val s = start ?: return null
            val e = end ?: return null
            if (e <= s) return null
            if (e > now + 60_000L) return null
            return s to minOf(e, now)
        }
    }

    private fun spokenSpan(p: ClaudeProvider.ZasechkaParse, now: Long): Span {
        // Начало: названное время, иначе отступ назад, иначе неизвестно.
        var start = timeOnDay(now, p.startTime)
            ?: if (p.startOffsetMin > 0) now - p.startOffsetMin * 60_000L else null
        // Время «в будущем» на сегодняшней дате — это вчерашний вечер:
        // в час ночи «с 23:30» означает вчера, а не через сутки.
        if (start != null && start > now + 60_000L) start -= 86_400_000L

        var end = timeOnDay(now, p.endTime)
            ?: if (p.durationMin > 0 && start != null) start + p.durationMin * 60_000L else null
        // «с 23:40 до 00:20» — конец уже за полночь.
        if (end != null && start != null && end <= start) end += 86_400_000L
        return Span(start, end)
    }

    /**
     * Дело из Todoist становится текущей записью. Название берём ЕГО - буква
     * в букву, как в Todoist: тогда лента, коммент в задаче и таблица говорят
     * об одном и том же деле. Категорию ищем сначала в собственной истории
     * (это же дело он уже трекал - и, возможно, правил категорию руками), и
     * только если такого дела ещё не было, спрашиваем Сонета. Никакой правки
     * и удаления здесь быть не может: тап по делу - всегда новая запись.
     */
    suspend fun startTask(title: String): ZasechkaStore.Entry {
        val now = System.currentTimeMillis()
        val clean = title.trim().take(120)
        val known = store.all()
            .lastOrNull { it.title.trim().equals(clean, ignoreCase = true) && it.category.isNotBlank() }
        var category = known?.category.orEmpty()
        var client = known?.client.orEmpty()
        if (category.isBlank()) {
            val categories = store.categories()
            val parsed = claude.zasechka(
                raw = clean,
                categories = categories.map { it.name to it.hint },
                clients = store.clients(),
                nowLocal = nowFormat.format(Date(now)),
                previousTitle = "",
                todayEntries = emptyList(),
                recentEntries = emptyList(),
            ).getOrNull()
            if (parsed != null) {
                category = categories.map { it.name }
                    .firstOrNull { it.equals(parsed.category, ignoreCase = true) } ?: parsed.category
                client = parsed.client
            }
        }
        val entry = store.startEntry(
            start = now,
            raw = clean,
            title = clean,
            category = category,
            client = client,
            useful = 0,
            source = "todoist",
        )
        eventLog.add("засечка ← todoist: «${entry.title}» [${entry.category.ifBlank { "без категории" }}]")
        sync.kickSoon(scope)
        return entry
    }

    /** Closes the running entry ("перерыв"/"конец дня"). Null if none was open. */
    suspend fun closeOpen(): ZasechkaStore.Entry? {
        val now = System.currentTimeMillis()
        val closed = store.closeOpen(now)
        // Конец дня — конец обоих треков: забытое «слушаю книгу» иначе тикало
        // бы до полуночи и накручивало баллы. Закрыть ТОЛЬКО параллель можно
        // её собственным ✕ в ленте.
        val closedParallel = store.closeParallel(now)
        if (closed != null) {
            eventLog.add("засечка: закрыто «${closed.title}» (${closed.durationMin()} мин)")
        }
        if (closedParallel != null) {
            eventLog.add("засечка ∥ закрыто «${closedParallel.title}» (${closedParallel.durationMin()} мин)")
        }
        if (closed != null || closedParallel != null) sync.kickSoon(scope)
        return closed
    }

    /** Закрывает идущую параллель, не трогая основное дело. */
    suspend fun closeParallel(): ZasechkaStore.Entry? {
        val closed = store.closeParallel(System.currentTimeMillis())
        if (closed != null) {
            eventLog.add("засечка ∥ закрыто «${closed.title}» (${closed.durationMin()} мин)")
            sync.kickSoon(scope)
        }
        return closed
    }

    /** Название категории буква в букву, как в списке владельца. */
    private fun canonicalCategory(named: String, known: List<String>): String =
        known.firstOrNull { it.equals(named, ignoreCase = true) } ?: named

    private fun canonicalClient(named: String, known: List<String>): String =
        known.firstOrNull { it.equals(named, ignoreCase = true) } ?: named

    /** Запись-пустышка для Outcome, когда в ленту ничего не легло. */
    private fun fakeEntry(now: Long, raw: String) = ZasechkaStore.Entry(
        id = 0,
        start = now,
        end = now,
        raw = raw,
        title = "",
        category = "",
        client = "",
        useful = 0,
        source = "voice",
        synced = true,
        createdAt = now,
    )

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
        today: List<ZasechkaStore.Entry>,
    ): List<ZasechkaStore.Entry> {
        // Параллельная запись ни во что не сращивается: её никто не резал, и
        // соседние куски основного дела к ней отношения не имеют. Второй трек
        // из рассмотрения тоже убираем — иначе «ютуб поверх еды» сошёл бы за
        // прерывание, которое разорвало еду на два куска.
        if (target.parallel) return listOf(target)
        val todayAsc = today.filter { !it.parallel }
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
