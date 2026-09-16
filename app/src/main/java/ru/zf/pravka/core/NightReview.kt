package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import org.json.JSONArray
import org.json.JSONObject
import ru.zf.pravka.core.prompts.PromptsReview
import ru.zf.pravka.data.CorrectionsLog
import ru.zf.pravka.data.DictionaryStore
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.RulesStore
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.data.TranscriptionLog
import ru.zf.pravka.provider.ClaudeBatches
import ru.zf.pravka.provider.Pricing

/**
 * Ночной разбор (16.09.2026). Владелец: «чтобы он каждую ночь делал всё, что
 * ты сейчас сделал: сам правил и применял то, что высоковероятно, а
 * низковероятное предлагал; и чтобы ещё один Fable его проверял».
 *
 * Конвейер на батчах: три запроса первого прохода (словарь и ослышки ·
 * поведение модели · правила), по одному на измерение; когда батч готов —
 * второй батч проверки по тем же свидетельствам; когда готов он — высокое и
 * подтверждённое применяется, остальное ложится предложениями, утром
 * владелец читает отчёт и отвечает текстом. Всё состояние — в
 * NightReviewStore: процесс может умереть на любой стадии, тик службы
 * продолжит с того же места.
 */
class NightReview(
    private val settings: Settings,
    private val batches: ClaudeBatches,
    private val store: NightReviewStore,
    private val dictionary: DictionaryStore,
    private val rules: RulesStore,
    private val history: HistoryLog,
    private val transcripts: TranscriptionLog,
    private val corrections: CorrectionsLog,
    private val prompts: PromptStore,
    private val stats: Stats,
    private val log: EventLog,
) {
    companion object {
        /** Статус батча спрашиваем не чаще чем раз в десять минут: он идёт до часа. */
        const val POLL_MS = 10 * 60_000L
        /** Окно счётчиков повторов: неделя у дневного прогона, месяц у недельного. */
        const val DAILY_AGG_MS = 7 * 86_400_000L
        const val WEEKLY_AGG_MS = 30 * 86_400_000L
        /** Память решений, которую видят все три прохода. */
        const val LEDGER_MS = 30 * 86_400_000L
        /** Батч живёт сутки; сутки с запасом без результата — прогон провален. */
        const val EXPIRE_MS = 26 * 3600_000L
        private val DIMS = listOf("dict", "model", "rules")
        private const val OUT_TOKENS = 8000
    }

    private val mutex = Mutex()
    private val dayFmt = SimpleDateFormat("dd.MM", Locale.US)
    private fun date(ms: Long) = dayFmt.format(Date(ms))

    /** Тик службы, раз в несколько минут: продвинуть активный прогон или запустить назревший. */
    suspend fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (!settings.nightReviewEnabledFlow.first()) return
        if (settings.apiKey().isBlank()) return
        if (!mutex.tryLock()) return
        try {
            val runs = store.all()
            val active = runs.filter { it.active }
            for (run in active) {
                if (nowMs - run.lastPollAt < POLL_MS) continue
                runCatching { poll(run, nowMs) }.onFailure { fail(run, it) }
            }
            if (active.isEmpty()) {
                val hour = settings.nightReviewHourFlow.first()
                val lastDaily = runs.filter { it.kind == NightReviewPolicy.DAILY }.maxOfOrNull { it.startedAt } ?: 0L
                val lastWeekly = runs.filter { it.kind == NightReviewPolicy.WEEKLY }.maxOfOrNull { it.startedAt } ?: 0L
                // Один прогон за тик: дневной и недельный в пятницу идут друг за другом.
                NightReviewPolicy.dueKinds(nowMs, hour, lastDaily, lastWeekly).firstOrNull()?.let { kind ->
                    start(kind, manual = false, nowMs = nowMs).onFailure {
                        log.add("ночной разбор: не запустился ($kind): ${it.message}")
                    }
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Запуск прогона: собрать свидетельства, отправить батч первого прохода. */
    suspend fun start(kind: String, manual: Boolean, nowMs: Long = System.currentTimeMillis()): Result<Run> = runCatching {
        val (from, to) = NightReviewPolicy.window(kind, nowMs)
        val evidence = gather(kind, from, to)
        if (evidence.isEmpty()) {
            // День без диктовок тоже фиксируется — иначе тик пробовал бы запуск до вечера.
            val empty = Run(
                id = nowMs, kind = kind, startedAt = nowMs, fromMs = from, toMs = to, stage = "done", manual = manual,
                summary = "За период не было ни чисток, ни правок — разбирать нечего.", finishedAt = nowMs,
            )
            store.save(empty)
            return@runCatching empty
        }
        val choice = settings.modelChoice(ModelRoute.NIGHT_REVIEW)
        val requests = DIMS.mapNotNull { dim ->
            evidence[dim]?.takeIf { it.isNotBlank() }?.let { user ->
                dim to ClaudeBatches.params(choice.model, choice.effort, OUT_TOKENS, PromptsReview.ANALYZE_SYSTEM, user)
            }
        }
        val batchId = batches.create(requests)
        val run = Run(
            id = nowMs, kind = kind, startedAt = nowMs, fromMs = from, toMs = to, stage = "analysis", manual = manual,
            analysisBatchId = batchId, evidence = evidence, lastPollAt = nowMs,
        )
        store.save(run)
        log.add("ночной разбор ($kind): батч $batchId, запросов ${requests.size}, ${choice.model} ${choice.effort}")
        run
    }

    // ---- свидетельства ----

    private suspend fun gather(kind: String, from: Long, to: Long): Map<String, String> {
        val pairs = history.readEntries(from, to)
        val corr = corrections.all().filter { it.ts in from until to }
        if (pairs.isEmpty() && corr.isEmpty()) return emptyMap()
        val takes = transcripts.readRange(from, to)
        val dict = dictionary.all()
        val ruleList = rules.all()
        val weekly = kind == NightReviewPolicy.WEEKLY
        // Повторы — за окно длиннее сырых текстов, счётчиками (см. NightReviewEvidence).
        val aggFrom = to - if (weekly) WEEKLY_AGG_MS else DAILY_AGG_MS
        val agg = NightReviewEvidence.aggregate(history.readEntries(aggFrom, to).map { it.input to it.output })
        val aggBlock = NightReviewEvidence.render(agg, "ПОВТОРЫ за ${if (weekly) 30 else 7} дней (что во что модель заменяла, сколько раз)")
        val ledger = ledgerBlock(dict)
        val period = "Период: ${date(from)}–${date(to)} (${if (weekly) "неделя" else "сутки"}). " +
            "Диктовок ${takes.size} (пустых ${takes.count { !it.ok }}), чисток моделью ${pairs.size}, правок руками ${corr.size}."
        val pairsBlock = block("ЧИСТКИ — <d> надиктовано, <m> что сделала модель", pairs, if (weekly) 90_000 else 40_000) {
            "<d>${it.input}</d>\n<m>${it.output}</m>"
        }
        val corrBlock = block("ПРАВКИ РУКАМИ — <d> надиктовано, <m> модель, <o> как поправил владелец (эталон)", corr, 30_000) {
            "<d>${it.dictated}</d>\n<m>${it.cleaned}</m>\n<o>${it.edited}</o>"
        }
        val dictBlock = "СЛОВАРЬ (id|вид|from|to|срабатываний|вкл|заметка):\n" +
            dict.joinToString("\n") { "${it.id}|${it.mode}|${it.from}|${it.to}|${it.hits}|${if (it.enabled) 1 else 0}|${it.note.take(60)}" }
        val rulesOn = settings.rulesInPromptFlow.first()
        val rulesBlock = if (ruleList.isEmpty()) "" else
            "ПРАВИЛА ОБУЧЕНИЯ (id|вкл|текст). Блок правил в промпт сейчас ${if (rulesOn) "ВКЛЮЧЁН" else "ВЫКЛЮЧЕН тумблером владельца"}:\n" +
                ruleList.joinToString("\n") { "${it.id}|${if (it.enabled) 1 else 0}|${it.text}" }
        val glitches = mixedScript(takes)
        val out = LinkedHashMap<String, String>()
        out["dict"] = listOf(
            "ЗАДАЧА: словарь и ослышки. Найди ослышки распознавателя, которые модель или владелец чинят раз за разом; " +
                "записи словаря, которые ломают живые слова или никогда не стреляют; имена и термины без защиты.",
            period, aggBlock, ledger, pairsBlock, corrBlock, dictBlock,
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        out["model"] = listOf(
            "ЗАДАЧА: поведение модели и распознавателя. По парам <d>→<m>: удаления слов (особенно «не», «нет»), " +
                "смена рода и лица, местоимения, превращённые в имена, цепочки запятых вместо точек, выдуманные слова, " +
                "разнобой в именах. По правкам руками — где модель промахнулась. Здесь ответ в основном заметками " +
                "(kind note) с числами; словарные изменения — только для имён и терминов.",
            period, aggBlock,
            if (glitches.isBlank()) "" else "Слова со смешанным алфавитом у распознавателя: $glitches",
            pairsBlock, corrBlock,
            "ПРОМПТ CLEAN, действующий сейчас (не правь, только заметки):\n" + prompts.effective(ProofreadMode.CLEAN),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        // Правила — состояние, которое меняется редко: каждую ночь их гонять
        // незачем, если блок в промпте выключен. Недельный смотрит всегда.
        if (rulesBlock.isNotBlank() && (weekly || rulesOn)) out["rules"] = listOf(
            "ЗАДАЧА: правила обучения. По журналу реши, какие включённые правила выключить (вредят, противоречат " +
                "промпту или друг другу, дублируют его) и какие выключенные — включить. Новых не сочиняй.",
            period, ledger, rulesBlock, pairsBlock.take(30_000), corrBlock,
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        return out
    }

    /** Память решений для промптов: прошлые прогоны + сколько раз сработало применённое. */
    private suspend fun ledgerBlock(dict: List<DictEntry>): String {
        val runs = store.all()
        val since = System.currentTimeMillis() - LEDGER_MS
        val byId = dict.associateBy { it.id }
        return NightReviewEvidence.ledger(runs, since, hitsSince = { id -> byId[id]?.hits })
    }

    private fun <T> block(title: String, items: List<T>, maxChars: Int, render: (T) -> String): String {
        if (items.isEmpty()) return ""
        val sb = StringBuilder(title).append(" — ").append(items.size).append(":\n")
        // Свежее важнее: режем с начала периода, если не влезает.
        val rendered = items.map(render)
        var used = 0
        val kept = ArrayList<String>()
        for (s in rendered.asReversed()) {
            if (used + s.length > maxChars) break
            kept.add(s); used += s.length
        }
        if (kept.size < rendered.size) sb.append("(показаны последние ${kept.size})\n")
        kept.asReversed().forEach { sb.append(it).append("\n\n") }
        return sb.toString().trimEnd()
    }

    private fun mixedScript(takes: List<TranscriptionLog.Entry>): String {
        val re = Regex("\\S+")
        val found = LinkedHashSet<String>()
        for (t in takes) for (w in re.findAll(t.text)) {
            val s = w.value
            if (s.any { it in 'А'..'я' } && s.any { it in 'A'..'z' }) found.add(s)
            if (found.size >= 30) break
        }
        return found.joinToString(", ")
    }

    // ---- продвижение прогона ----

    private suspend fun poll(run: Run, nowMs: Long) {
        var r = run.copy(lastPollAt = nowMs)
        store.save(r)
        if (nowMs - r.startedAt > EXPIRE_MS) throw IllegalStateException("батч не завершился за сутки")
        val batchId = when (r.stage) {
            "analysis" -> r.analysisBatchId
            "check" -> r.checkBatchId
            else -> r.auditBatchId
        }
        val st = batches.status(batchId)
        if (!st.ended) {
            log.add("ночной разбор: батч $batchId ещё идёт (в работе ${st.inFlight})")
            return
        }
        val url = st.resultsUrl
            ?: throw IllegalStateException("батч завершён без результатов (ошибок ${st.errored}, истекло ${st.expired})")
        val items = batches.results(url)
        val route = when (r.stage) {
            "analysis" -> ModelRoute.NIGHT_REVIEW
            "check" -> ModelRoute.NIGHT_CHECK
            else -> ModelRoute.NIGHT_AUDIT
        }
        val choice = settings.modelChoice(route)
        val cost = items.sumOf { Pricing.costUsd(choice.model, it.inputTokens, it.outputTokens, it.cacheWrite, it.cacheRead) } * ClaudeBatches.DISCOUNT
        stats.recordAux(cost, items.sumOf { it.inputTokens + it.cacheRead + it.cacheWrite }, items.sumOf { it.outputTokens })
        r = r.copy(costUsd = r.costUsd + cost)
        r = when (r.stage) {
            "analysis" -> afterAnalysis(r, items)
            "check" -> afterCheck(r, items)
            else -> afterAudit(r, items)
        }
        store.save(r)
    }

    private suspend fun afterAnalysis(run: Run, items: List<ClaudeBatches.Item>): Run {
        val summaries = ArrayList<String>()
        val errors = ArrayList<String>()
        val changes = ArrayList<Change>()
        for (dim in DIMS) {
            val item = items.firstOrNull { it.customId == dim } ?: continue
            if (!item.ok) { errors += "$dim: ${item.failure}"; continue }
            runCatching { NightReviewPolicy.parseAnalysis(item.text, dim) }
                .onSuccess { a -> if (a.summary.isNotBlank()) summaries += a.summary; changes += a.changes }
                .onFailure { errors += "$dim: ответ не разобрался (${it.message})" }
        }
        // Память: что владелец вернул или отклонил за месяц, снова не предлагается —
        // даже если модель не послушала промпт.
        val blocked = NightReviewEvidence.blockedKeys(store.all(), System.currentTimeMillis() - LEDGER_MS)
        val remembered = changes.map { c ->
            if (!c.isNote && NightReviewEvidence.key(c) in blocked)
                c.copy(status = "skipped", statusNote = "владелец уже вернул или отклонил такое — не предлагаю снова")
            else c
        }
        val toCheck = remembered.filter { !it.isNote && it.status == "proposed" }
        val partial = run.copy(summary = summaries.joinToString("\n\n"), error = errors.joinToString("; "), changes = remembered)
        if (toCheck.isEmpty()) return finish(partial)
        val choice = settings.modelChoice(ModelRoute.NIGHT_CHECK)
        val requests = DIMS.mapNotNull { dim ->
            val mine = toCheck.filter { it.id.startsWith("$dim-") }
            if (mine.isEmpty()) return@mapNotNull null
            val evidence = run.evidence[dim].orEmpty()
            val list = JSONArray().apply { for (c in mine) put(changeJson(c)) }
            "check-$dim" to ClaudeBatches.params(
                choice.model, choice.effort, OUT_TOKENS, PromptsReview.CHECK_SYSTEM,
                evidence + "\n\nПРЕДЛОЖЕНИЯ ПЕРВОГО АУДИТОРА:\n" + list.toString(),
            )
        }
        val batchId = batches.create(requests)
        log.add("ночной разбор: проверка, батч $batchId, изменений ${toCheck.size}")
        return partial.copy(stage = "check", checkBatchId = batchId)
    }

    private fun changeJson(c: Change): JSONObject = JSONObject().apply {
        put("id", c.id); put("kind", c.kind); put("mode", c.mode)
        if (c.toMode.isNotBlank()) put("to_mode", c.toMode)
        put("from", c.from); put("to", c.to); put("note", c.note)
        if (c.ruleId != 0L) put("rule_id", c.ruleId)
        put("why", c.why); put("confidence", c.confidence)
    }

    private suspend fun afterCheck(run: Run, items: List<ClaudeBatches.Item>): Run {
        val verdicts = HashMap<String, Pair<String, String>>()
        val errors = ArrayList<String>()
        for (item in items) {
            if (!item.ok) { errors += "${item.customId}: ${item.failure}"; continue }
            runCatching { NightReviewPolicy.parseVerdicts(item.text) }
                .onSuccess { verdicts.putAll(it) }
                .onFailure { errors += "${item.customId}: вердикты не разобрались (${it.message})" }
        }
        val updated = run.changes.map { c ->
            if (c.isNote || c.status != "proposed") c
            else {
                val (verdict, why) = verdicts[c.id] ?: ("unsure" to "проверка не ответила")
                val x = c.copy(verdict = verdict, verdictWhy = why)
                if (verdict == "reject") x.copy(status = "rejected") else x
            }
        }
        val allErrors = listOf(run.error, errors.joinToString("; ")).filter { it.isNotBlank() }.joinToString("; ")
        val partial = run.copy(changes = updated, error = allErrors)
        val remaining = updated.filter { !it.isNote && it.status == "proposed" }
        if (remaining.isEmpty()) return finish(partial)
        // Третий проход — согласование: итог ночи против памяти решений и
        // текущего состояния, одним запросом на всё.
        val choice = settings.modelChoice(ModelRoute.NIGHT_AUDIT)
        val dict = dictionary.all()
        val user = listOf(
            "ИТОГ НОЧИ — изменения с вердиктами проверки (JSON):",
            JSONArray().apply {
                for (c in remaining) put(changeJson(c).put("verdict", c.verdict).put("verdict_why", c.verdictWhy))
            }.toString(),
            "СВОДКИ ПЕРВЫХ ДВУХ ПРОХОДОВ:\n" + partial.summary,
            ledgerBlock(dict),
            "СЛОВАРЬ СЕЙЧАС (id|вид|from|to|срабатываний|вкл):\n" +
                dict.joinToString("\n") { "${it.id}|${it.mode}|${it.from}|${it.to}|${it.hits}|${if (it.enabled) 1 else 0}" },
            rules.all().takeIf { it.isNotEmpty() }?.let { list ->
                "ПРАВИЛА СЕЙЧАС (id|вкл|текст):\n" + list.joinToString("\n") { "${it.id}|${if (it.enabled) 1 else 0}|${it.text}" }
            }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        val batchId = batches.create(
            listOf("audit" to ClaudeBatches.params(choice.model, choice.effort, OUT_TOKENS, PromptsReview.AUDIT_SYSTEM, user))
        )
        log.add("ночной разбор: согласование, батч $batchId, изменений ${remaining.size}")
        return partial.copy(stage = "audit", checkBatchId = run.checkBatchId, auditBatchId = batchId)
    }

    /** После согласования: придержанное остаётся предложением, остальное high+approve применяется. */
    private suspend fun afterAudit(run: Run, items: List<ClaudeBatches.Item>): Run {
        val item = items.firstOrNull { it.customId == "audit" }
        var assessment = ""
        var holds: Map<String, String> = emptyMap()
        var error = ""
        if (item == null || !item.ok) error = "согласование: ${item?.failure ?: "нет ответа"}"
        else runCatching { NightReviewPolicy.parseAudit(item.text) }
            .onSuccess { assessment = it.assessment; holds = it.holds }
            .onFailure { error = "согласование: ответ не разобрался (${it.message})" }
        var applied = 0
        val updated = run.changes.map { c ->
            when {
                c.isNote || c.status != "proposed" -> c
                c.id in holds -> c.copy(verdict = "hold", verdictWhy = "согласование: ${holds[c.id]}" +
                    (if (c.verdictWhy.isNotBlank()) " (проверка: ${c.verdictWhy})" else ""))
                NightReviewPolicy.autoApply(c) && applied < NightReviewPolicy.AUTO_CAP -> {
                    val done = applyChange(c, run)
                    if (done.status == "applied") applied++
                    done
                }
                else -> c
            }
        }
        val allErrors = listOf(run.error, error).filter { it.isNotBlank() }.joinToString("; ")
        val summary = listOf(
            if (assessment.isNotBlank()) "Согласование: $assessment" else "",
            run.summary,
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        return finish(run.copy(changes = updated, error = allErrors, summary = summary))
    }

    private fun finish(run: Run): Run {
        val head = NightReviewPolicy.headline(run.applied(), run.proposed(), run.rejected(), run.changes.count { it.isNote })
        log.add("ночной разбор (${run.kind}): $head стоил $" + "%.3f".format(Locale.US, run.costUsd))
        return run.copy(
            stage = "done",
            finishedAt = System.currentTimeMillis(),
            evidence = emptyMap(),
            summary = listOf(head, run.summary).filter { it.isNotBlank() }.joinToString("\n\n"),
        )
    }

    private suspend fun fail(run: Run, e: Throwable) {
        // Правило 6: причина целиком, а не «что-то пошло не так».
        val msg = e.message ?: e.javaClass.simpleName
        log.add("ночной разбор (${run.kind}) НЕ УДАЛСЯ: $msg")
        stats.recordError()
        store.save(run.copy(stage = "failed", error = msg, finishedAt = System.currentTimeMillis(), evidence = emptyMap()))
    }

    // ---- применение и откат ----

    private suspend fun findEntry(c: Change) = dictionary.all().firstOrNull {
        it.from.equals(c.from, ignoreCase = true) && (c.mode.isBlank() || it.mode.name == c.mode)
    }

    private fun undo(op: String, id: Long, extra: JSONObject.() -> Unit = {}): String =
        JSONObject().put("op", op).put("id", id).apply(extra).toString()

    private suspend fun applyChange(c: Change, run: Run): Change = try {
        when (c.kind) {
            "dict_add" -> {
                val mode = runCatching { DictMode.valueOf(c.mode) }.getOrNull()
                    ?: return c.copy(status = "failed", statusNote = "непонятный вид «${c.mode}»")
                val existing = findEntry(c)
                when {
                    existing != null && existing.enabled -> c.copy(status = "skipped", statusNote = "уже в словаре")
                    existing != null -> {
                        dictionary.update(existing.copy(enabled = true))
                        c.copy(status = "applied", undo = undo("disable", existing.id))
                    }
                    else -> {
                        val e = dictionary.add(c.from, c.to, mode, "ночной разбор ${date(run.startedAt)}: ${c.why.take(120)}".trim())
                        c.copy(status = "applied", undo = undo("delete", e.id))
                    }
                }
            }
            "dict_disable" -> {
                val e = findEntry(c)
                if (e == null) c.copy(status = "failed", statusNote = "нет такой записи")
                else if (!e.enabled) c.copy(status = "skipped", statusNote = "уже выключена")
                else { dictionary.update(e.copy(enabled = false)); c.copy(status = "applied", undo = undo("enable", e.id)) }
            }
            "dict_mode" -> {
                val e = findEntry(c)
                val newMode = runCatching { DictMode.valueOf(c.toMode) }.getOrNull()
                if (e == null) c.copy(status = "failed", statusNote = "нет такой записи")
                else if (newMode == null) c.copy(status = "failed", statusNote = "непонятный вид ${c.toMode}")
                else {
                    dictionary.update(e.copy(mode = newMode, note = c.note.ifBlank { e.note }))
                    c.copy(status = "applied", undo = undo("restore", e.id) { put("mode", e.mode.name); put("note", e.note) })
                }
            }
            "rule_disable", "rule_enable" -> {
                val r = rules.all().firstOrNull { it.id == c.ruleId }
                if (r == null) c.copy(status = "failed", statusNote = "нет правила ${c.ruleId}")
                else {
                    rules.setEnabled(c.ruleId, c.kind == "rule_enable")
                    c.copy(status = "applied", undo = undo("rule", c.ruleId) { put("enabled", r.enabled) })
                }
            }
            else -> c
        }
    } catch (e: Exception) {
        c.copy(status = "failed", statusNote = e.message ?: e.javaClass.simpleName)
    }

    private suspend fun revertChange(c: Change): Change = try {
        val u = JSONObject(c.undo)
        val id = u.optLong("id")
        when (u.optString("op")) {
            "delete" -> dictionary.delete(id)
            "disable" -> dictionary.all().firstOrNull { it.id == id }?.let { dictionary.update(it.copy(enabled = false)) }
            "enable" -> dictionary.all().firstOrNull { it.id == id }?.let { dictionary.update(it.copy(enabled = true)) }
            "restore" -> dictionary.all().firstOrNull { it.id == id }?.let {
                dictionary.update(it.copy(mode = DictMode.valueOf(u.optString("mode")), note = u.optString("note")))
            }
            "rule" -> rules.setEnabled(id, u.optBoolean("enabled"))
            else -> throw IllegalStateException("нечего откатывать")
        }
        c.copy(status = "reverted", statusNote = "возвращено")
    } catch (e: Exception) {
        c.copy(statusNote = "откат не удался: ${e.message}")
    }

    /** Кнопки под изменением: apply · reject · revert. */
    suspend fun act(runId: Long, changeId: String, action: String): Result<Unit> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        val c = run.changes.firstOrNull { it.id == changeId } ?: error("Изменение не найдено")
        val updated = when (action) {
            "apply" -> if (c.status == "proposed" || c.status == "rejected") applyChange(c, run) else c
            "reject" -> if (c.status == "proposed") c.copy(status = "rejected", statusNote = "отклонил владелец") else c
            "revert" -> if (c.status == "applied") revertChange(c) else c
            else -> c
        }
        store.save(run.copy(changes = run.changes.map { if (it.id == changeId) updated else it }))
        log.add("ночной разбор: владелец — $action ${c.title()} → ${updated.status}")
    }

    /** Ответ владельца обычным языком: модель переводит в действия, мы их выполняем. */
    suspend fun reply(runId: Long, text: String): Result<String> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        val choice = settings.modelChoice(ModelRoute.NIGHT_CHECK)
        val list = run.changes.filter { !it.isNote }.joinToString("\n") { "${it.id} | ${it.title()} | ${it.status}" }
        val item = batches.single(
            ClaudeBatches.params(choice.model, choice.effort, 2000, PromptsReview.REPLY_SYSTEM, "ИЗМЕНЕНИЯ:\n$list\n\nОТВЕТ САШИ:\n$text")
        )
        if (!item.ok) error(item.failure)
        stats.recordAux(
            Pricing.costUsd(choice.model, item.inputTokens, item.outputTokens, item.cacheWrite, item.cacheRead),
            item.inputTokens + item.cacheRead + item.cacheWrite, item.outputTokens,
        )
        val plan = NightReviewPolicy.parseReply(item.text)
        var r = store.get(runId) ?: run
        var did = 0
        for ((id, action) in plan.actions) {
            val c = r.changes.firstOrNull { it.id == id } ?: continue
            val updated = when (action) {
                "revert" -> if (c.status == "applied") revertChange(c) else null
                "apply" -> if (c.status == "proposed" || c.status == "rejected") applyChange(c, r) else null
                "reject" -> if (c.status == "proposed") c.copy(status = "rejected", statusNote = "отклонил владелец") else null
                else -> null
            } ?: continue
            r = r.copy(changes = r.changes.map { if (it.id == id) updated else it })
            did++
        }
        val answer = plan.answer.ifBlank { "Сделано." }
        val result = "$answer (действий: $did)"
        store.save(r.copy(replies = r.replies + NightReviewStore.Reply(System.currentTimeMillis(), text, result)))
        log.add("ночной разбор: ответ владельца → действий $did")
        result
    }
}
