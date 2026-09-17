package ru.zf.pravka.core

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.core.prompts.PromptsReview
import ru.zf.pravka.data.CorrectionsLog
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run
import ru.zf.pravka.data.PromptStore
import ru.zf.pravka.data.PromptVersions
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.provider.ClaudeBatches
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.Pricing

/**
 * Недельная правка промпта CLEAN (17.09.2026). Владелец: «раз в неделю Fable
 * проходился по идеям за неделю и чинил бы промпт… промпты бы хранились
 * внутри приложения… если у него нет идей — чтобы не придумывал… метрика:
 * насколько промпт работает лучше, а если хуже — возвращал всё обратно».
 *
 * Три стадии в ночь на субботу, после недельного разбора:
 * 1. Предложение (батч, ModelRoute.PROMPT_TUNE): идеи недели с советами,
 *    правки руками, повторы, действующий и заводской промпт, судьба прежних
 *    версий → либо «менять нечего», либо полный новый текст. Форма нового
 *    текста проверяется детерминированно (PromptTunePolicy.validate).
 * 2. Измерение (обычные запросы дневной модели, кусками): диктовки недели —
 *    сначала те, что владелец правил руками, — перечищаются новым промптом.
 * 3. Судья (батч Fable, тот же слепой промпт, что у тени): прежний текст
 *    против нового по каждой различающейся паре; плюс близость обоих к тому,
 *    как поправил владелец. Принимается только заметный перевес
 *    (PromptTunePolicy.decide); принятый текст ложится в PromptStore как
 *    override, версия с метриками — в PromptVersions.
 * Через неделю: доля чисток с правкой руками до и после смены; выросла в
 * 1,3 раза — откат к прежней версии сам. Кнопка «Вернуть прежний промпт» —
 * в любой момент.
 */
class PromptTuner(
    private val settings: Settings,
    private val batches: ClaudeBatches,
    private val store: NightReviewStore,
    private val versions: PromptVersions,
    private val prompts: PromptStore,
    private val history: HistoryLog,
    private val corrections: CorrectionsLog,
    private val applier: DictionaryApplier,
    private val provider: ClaudeProvider,
    private val stats: Stats,
    private val log: EventLog,
) {
    companion object {
        /** Полный промпт ~5 тысяч токенов плюс шапка; мысли Fable — сверх этого (batchThinkingHeadroom). */
        private const val PROPOSE_OUT_TOKENS = 16_000
        private const val JUDGE_OUT_TOKENS = 6000
        const val CHUNK = 25
        private const val WEEKDAY = Calendar.SATURDAY
        const val DAY_LABEL = "прежний промпт"
        const val NEW_LABEL = "новый промпт"
    }

    private val mutex = Mutex()
    private val dayFmt = SimpleDateFormat("dd.MM", Locale.US)
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    suspend fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (!settings.promptTuneEnabledFlow.first()) return
        if (settings.apiKey().isBlank()) return
        if (!mutex.tryLock()) return
        try {
            val runs = store.all().filter { it.isTune }
            val active = runs.filter { it.active }
            for (run in active) runCatching { advance(run, nowMs) }.onFailure { fail(run, it) }
            if (active.isEmpty()) {
                val hour = settings.nightReviewHourFlow.first()
                val last = runs.maxOfOrNull { it.startedAt } ?: 0L
                if (NightReviewPolicy.dueOnWeekday(nowMs, hour, last, WEEKDAY)) {
                    if (overBudget()) log.add("правка промпта: не стартую — расход за сутки выше потолка $${settings.nightBudgetUsdFlow.first()}")
                    else start(manual = false, nowMs = nowMs).onFailure { log.add("правка промпта: не запустилась: ${it.message}") }
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun advance(run: Run, nowMs: Long) {
        if (run.stage == PromptTunePolicy.STAGE_MEASURE) measureChunk(run, nowMs)
        else if (nowMs - run.lastPollAt >= NightReview.POLL_MS) poll(run, nowMs)
    }

    // ---- запуск: откат по прошлой неделе, свидетельства, батч предложения ----

    suspend fun start(manual: Boolean, nowMs: Long = System.currentTimeMillis()): Result<Run> = runCatching {
        val from = nowMs - PromptTunePolicy.WINDOW_MS
        val rollbackNote = maybeRollback(nowMs)
        val current = prompts.effective(ProofreadMode.CLEAN)
        val factory = prompts.factory(PromptStore.PromptId.CLEAN_CLAUDE)
        val ideas = store.all()
            .filter { it.isReview && it.stage == "done" && it.startedAt >= from }
            .sortedBy { it.startedAt }
            .flatMap { r -> r.changes.filter { it.kind == "note" }.map { c -> Triple(r.startedAt, c.why, c.note) } }
        val corr = corrections.all().filter { it.ts in from until nowMs && it.edited.isNotBlank() }
        fun doneRun(summary: String) = Run(
            id = nowMs, kind = PromptTunePolicy.KIND, startedAt = nowMs, fromMs = from, toMs = nowMs,
            stage = "done", manual = manual, summary = listOf(rollbackNote, summary).filter { it.isNotBlank() }.joinToString("\n\n"), finishedAt = nowMs,
        )
        if (ideas.isEmpty() && corr.isEmpty()) {
            val r = doneRun("За неделю не было ни идей ночного разбора, ни правок руками — промпт не трогаю.")
            store.save(r)
            return@runCatching r
        }
        val pairs = withContext(Dispatchers.IO) { history.readEntries(from, nowMs) }
        val agg = NightReviewEvidence.aggregate(pairs.map { it.input to it.output })
        val past = versions.all().sortedByDescending { it.at }.take(10)
        val user = buildString {
            append("ПЕРИОД: ${dayFmt.format(Date(from))}–${dayFmt.format(Date(nowMs))}. Чисток ${pairs.size}, правок руками ${corr.size}, идей ${ideas.size}.\n\n")
            append("ИДЕИ НОЧНЫХ РАЗБОРОВ ЗА НЕДЕЛЮ (дата · наблюдение · совет):\n")
            for ((at, why, advice) in ideas) append("- ${dayFmt.format(Date(at))} · $why${if (advice.isNotBlank()) " · Совет: $advice" else ""}\n")
            append('\n')
            if (corr.isNotEmpty()) {
                append("ПРАВКИ РУКАМИ — <d> надиктовано, <m> модель, <o> как поправил владелец (эталон), последние ${minOf(corr.size, 30)}:\n")
                for (c in corr.takeLast(30)) append("<d>${c.dictated}</d>\n<m>${c.cleaned}</m>\n<o>${c.edited}</o>\n\n")
            }
            append(NightReviewEvidence.render(agg, "ПОВТОРЫ за неделю (что во что модель заменяла, сколько раз)")).append("\n\n")
            if (past.isNotEmpty()) {
                append("ПРЕЖНИЕ ВЕРСИИ ПРОМПТА И ИХ СУДЬБА:\n")
                for (v in past) {
                    append("- ${dayFmt.format(Date(v.at))} · ${v.source} · ${v.status}: ${v.note}")
                    if (v.judgeBetter + v.judgeWorse > 0) append(" · судья ${v.judgeBetter}:${v.judgeWorse}")
                    if (v.statusNote.isNotBlank()) append(" · ${v.statusNote}")
                    append('\n')
                }
                append('\n')
            }
            if (factory.trim() != current.trim()) {
                append("ЗАВОДСКОЙ ПРОМПТ ИЗ ТЕКУЩЕЙ СБОРКИ отличается от действующего — его новые формулировки сохрани (правило 3):\n<заводской>\n$factory\n</заводской>\n\n")
            }
            append("ДЕЙСТВУЮЩИЙ ПРОМПТ CLEAN:\n<промпт>\n$current\n</промпт>")
        }
        val choice = settings.modelChoice(ModelRoute.PROMPT_TUNE)
        val batchId = batches.create(
            listOf("tune" to ClaudeBatches.params(choice.model, choice.effort, PROPOSE_OUT_TOKENS, PromptsReview.TUNE_SYSTEM, user))
        )
        val run = Run(
            id = nowMs, kind = PromptTunePolicy.KIND, startedAt = nowMs, fromMs = from, toMs = nowMs,
            stage = PromptTunePolicy.STAGE_PROPOSE, manual = manual, analysisBatchId = batchId, lastPollAt = nowMs,
            evidence = mapOf("prompt_old" to current, "rollback" to rollbackNote),
            progress = "предложение: батч отправлен ${timeFmt.format(Date(nowMs))}",
        )
        store.save(run)
        log.add("правка промпта: батч $batchId, ${choice.model} ${choice.effort}, идей ${ideas.size}, правок ${corr.size}")
        run
    }

    /**
     * Откат по прошлой неделе: версия подбора живёт шесть дней и больше, доля
     * чисток с правкой руками после неё выросла в 1,3 раза при десяти и более
     * правках — возвращаем прежний текст. Возвращает строку для сводки или "".
     */
    private suspend fun maybeRollback(nowMs: Long): String {
        val active = versions.active() ?: return ""
        if (active.source != "tuner" || nowMs - active.at < PromptTunePolicy.ROLLBACK_AFTER_MS) return ""
        if (active.corrAfter >= 0) return ""
        val span = nowMs - active.at
        val after = withContext(Dispatchers.IO) { history.readEntries(active.at, nowMs) }.size
        val before = withContext(Dispatchers.IO) { history.readEntries(active.at - span, active.at) }.size
        val all = corrections.all()
        val corrAfter = all.count { it.ts in active.at until nowMs }
        val corrBefore = all.count { it.ts in (active.at - span) until active.at }
        val rateBefore = PromptTunePolicy.rate(corrBefore, before)
        val rateAfter = PromptTunePolicy.rate(corrAfter, after)
        val measured = active.copy(corrBefore = rateBefore, corrAfter = rateAfter)
        return if (PromptTunePolicy.shouldRollback(rateBefore, rateAfter, corrAfter)) {
            val why = "правок руками стало больше: ${PromptTunePolicy.pct(rateAfter)} чисток против ${PromptTunePolicy.pct(rateBefore)} неделей раньше ($corrAfter из $after против $corrBefore из $before)"
            revert(measured, "откат сам: $why")
            "Промпт от ${dayFmt.format(Date(active.at))} возвращён к прежнему: $why."
        } else {
            versions.save(measured)
            "Промпт от ${dayFmt.format(Date(active.at))} оставлен: правок руками ${PromptTunePolicy.pct(rateAfter)} чисток против ${PromptTunePolicy.pct(rateBefore)} неделей раньше."
        }
    }

    /** Вернуть текст, что был до этой версии: прежняя версия подбора или заводской. */
    private suspend fun revert(active: PromptVersions.Version, why: String) {
        val previous = versions.all().filter { it.at < active.at && it.status == "superseded" && it.source == "tuner" }.maxByOrNull { it.at }
        if (previous != null) {
            prompts.setOverride(PromptStore.PromptId.CLEAN_CLAUDE, previous.text)
            versions.save(previous.copy(status = "active", statusNote = "возвращена ${dayFmt.format(Date(System.currentTimeMillis()))}"))
        } else {
            prompts.resetToFactory(PromptStore.PromptId.CLEAN_CLAUDE)
        }
        versions.save(active.copy(status = "reverted", statusNote = why))
        log.add("правка промпта: версия от ${dayFmt.format(Date(active.at))} возвращена — $why")
    }

    /** Кнопка владельца «Вернуть прежний промпт». */
    suspend fun revertActive(): Result<String> = runCatching {
        val active = versions.active() ?: error("Действует заводской промпт — возвращать нечего")
        revert(active, "вернул владелец")
        "Возвращён промпт, что был до ${dayFmt.format(Date(active.at))}"
    }

    // ---- продвижение ----

    private suspend fun poll(run: Run, nowMs: Long) {
        var r = run.copy(lastPollAt = nowMs)
        store.save(r)
        if (nowMs - r.startedAt > NightReview.EXPIRE_MS) throw IllegalStateException("батч не завершился за сутки")
        val propose = r.stage == PromptTunePolicy.STAGE_PROPOSE
        val batchId = if (propose) r.analysisBatchId else r.checkBatchId
        val st = batches.status(batchId)
        if (!st.ended) {
            store.save(r.copy(progress = "${if (propose) "предложение" else "судья"}: батч ${st.processing} · опрос ${timeFmt.format(Date(nowMs))}"))
            return
        }
        val url = st.resultsUrl ?: throw IllegalStateException("батч завершён без результатов (ошибок ${st.errored}, истекло ${st.expired})")
        val items = batches.results(url)
        val route = if (propose) ModelRoute.PROMPT_TUNE else ModelRoute.SHADOW_JUDGE
        val model = settings.modelChoice(route).model
        val cost = items.sumOf { Pricing.costUsd(model, it.inputTokens, it.outputTokens, it.cacheWrite, it.cacheRead) } * ClaudeBatches.DISCOUNT
        val tokensIn = items.sumOf { it.inputTokens + it.cacheRead + it.cacheWrite }
        stats.recordAux(cost, tokensIn, items.sumOf { it.outputTokens }, route = route.key)
        stats.recordCache(items.sumOf { it.cacheRead }, items.sumOf { it.cacheWrite })
        r = r.copy(costUsd = r.costUsd + cost, inputTokens = r.inputTokens + tokensIn, cacheReadTokens = r.cacheReadTokens + items.sumOf { it.cacheRead })
        r = if (propose) afterPropose(r, items) else afterJudge(r, items)
        store.save(r)
    }

    private suspend fun afterPropose(run: Run, items: List<ClaudeBatches.Item>): Run {
        val item = items.firstOrNull { it.customId == "tune" } ?: items.firstOrNull()
        if (item == null || !item.ok) return finish(run, "Предложение не получено: ${item?.failure ?: "нет ответа"}", error = true)
        val proposal = try {
            PromptTunePolicy.parseProposal(item.text)
        } catch (e: Exception) {
            return finish(run, "Ответ подбора не разобрался: ${NightReviewPolicy.shortReason(e)}", error = true)
        }
        if (!proposal.change) return finish(run, "Промпт не меняю. ${proposal.why}")
        val old = run.evidence["prompt_old"].orEmpty()
        PromptTunePolicy.validate(old, proposal.prompt)?.let { reason ->
            versions.save(
                PromptVersions.Version(
                    id = run.id, at = run.id, source = "rejected", text = proposal.prompt, note = proposal.summary,
                    factoryHash = prompts.factory(PromptStore.PromptId.CLEAN_CLAUDE).hashCode(), status = "rejected", statusNote = "форма: $reason",
                )
            )
            return finish(run, "Предложение отклонено проверкой формы: $reason.\n\nЧто предлагалось: ${proposal.summary}")
        }
        // Измерение: диктовки недели, с правкой руками — первыми.
        val entries = withContext(Dispatchers.IO) { history.readEntries(run.fromMs, run.toMs) }.filter { !it.withContext }
        val corr = corrections.all().filter { it.ts in run.fromMs until run.toMs && it.edited.isNotBlank() }
        val ownerByDictated = corr.associateBy { it.dictated.trim() }
        val withOwner = entries.filter { ownerByDictated.containsKey(it.input.trim()) }
        val rest = entries.filter { !ownerByDictated.containsKey(it.input.trim()) }
        val chosen = (withOwner.takeLast(PromptTunePolicy.MEASURE_CAP) + rest.takeLast((PromptTunePolicy.MEASURE_CAP - withOwner.size).coerceAtLeast(0)))
            .sortedBy { it.tsMs }
        if (chosen.isEmpty()) return finish(run, "Новый промпт предложен (${proposal.summary}), но за неделю нет диктовок, на которых его измерить — не меняю.")
        val takes = chosen.mapIndexed { i, e ->
            val id = "p-${i + 1}"
            ShadowPolicy.Take(
                id, e.tsMs, e.input, e.input, e.output, e.costUsd, ShadowPolicy.flip(id), prose = e.prose,
                owner = ownerByDictated[e.input.trim()]?.edited.orEmpty(),
            )
        }
        log.add("правка промпта: предложение принято к измерению — ${proposal.summary}; диктовок ${takes.size}, с правкой руками ${withOwner.size}")
        return run.copy(
            stage = PromptTunePolicy.STAGE_MEASURE, analysisBatchId = "",
            evidence = run.evidence + ("prompt_new" to proposal.prompt) + ("summary_new" to proposal.summary) + ("why_new" to proposal.why) +
                ("takes" to ShadowPolicy.takesToJson(takes)),
            progress = "измерение: перечищено 0 из ${takes.size}",
        )
    }

    /** Кусок измерения: до CHUNK диктовок новым промптом на дневной модели тем же путём, что днём; сохранение после каждой. */
    private suspend fun measureChunk(run: Run, nowMs: Long) {
        val newPrompt = run.evidence["prompt_new"].orEmpty()
        val choice = settings.modelChoice(ModelRoute.PRAVKA)
        val takes = ShadowPolicy.takesFromJson(run.evidence["takes"]).toMutableList()
        val pending = takes.filter { it.shadow.isBlank() && it.failure.isBlank() }
        var r = run.copy(lastPollAt = nowMs)
        var stopReason = ""
        fun remaining() = takes.count { it.shadow.isBlank() && it.failure.isBlank() }
        suspend fun persist(note: String = "") {
            r = r.copy(
                evidence = r.evidence + ("takes" to ShadowPolicy.takesToJson(takes)),
                progress = "измерение: перечищено ${takes.size - remaining()} из ${takes.size}" + (if (note.isNotBlank()) " · $note" else "") + " · ${timeFmt.format(Date(System.currentTimeMillis()))}",
            )
            store.save(r)
        }
        for (t in pending.take(CHUNK)) {
            // Отмена побеждает, потолок дня — пауза до завтра.
            if (store.get(run.id)?.active != true) { log.add("правка промпта: прогон снят во время измерения — останавливаюсь"); return }
            if (overBudget()) { stopReason = "потолок дня $${settings.nightBudgetUsdFlow.first()} исчерпан — продолжу завтра"; break }
            val prepared = applier.prepare(t.input)
            val parts = provider.cleanPromptParts(prepared.dictBlock, prose = t.prose, template = newPrompt).copy(cacheStableAlways = true)
            val res = provider.cleanOnce(choice.model, choice.effort, parts, prepared.text)
            val idx = takes.indexOfFirst { it.id == t.id }
            val err = res.exceptionOrNull()
            if (err != null) {
                val msg = err.message ?: err.javaClass.simpleName
                if (err is IOException || (err is ClaudeProvider.ApiException && err.retryable)) { stopReason = msg; break }
                takes[idx] = t.copy(prepared = prepared.text, failure = msg)
                persist()
                continue
            }
            val out = res.getOrThrow()
            stats.recordAux(out.costUsd, out.inputTokens, out.outputTokens, route = ModelRoute.PROMPT_TUNE.key)
            r = r.copy(costUsd = r.costUsd + out.costUsd, inputTokens = r.inputTokens + out.inputTokens, cacheReadTokens = r.cacheReadTokens + out.cacheReadTokens)
            val cleaned = ResponseCleaner.clean(out.text, prepared.text)
            takes[idx] = if (cleaned == null) t.copy(prepared = prepared.text, failure = "испорченный ответ")
            else t.copy(prepared = prepared.text, shadow = cleaned, verdict = if (ShadowPolicy.same(cleaned, t.day)) "same" else "")
            persist()
        }
        if (stopReason.isNotBlank()) persist("пауза: $stopReason")
        if (remaining() == 0 && store.get(run.id)?.active == true) store.save(dispatchJudge(r, takes))
    }

    private suspend fun overBudget(): Boolean =
        stats.snapshotFlow.first().costTodayUsd > settings.nightBudgetUsdFlow.first()

    private suspend fun dispatchJudge(run: Run, takes: List<ShadowPolicy.Take>): Run {
        val differing = takes.filter { it.failure.isBlank() && it.verdict.isBlank() }
        if (differing.isEmpty()) {
            return finish(run, "Новый промпт (${run.evidence["summary_new"]}) дал на ${takes.size} диктовках те же тексты, что прежний — разницы нет, не меняю.")
        }
        val judge = settings.modelChoice(ModelRoute.SHADOW_JUDGE)
        val rendered = differing.map { it.id to ShadowPolicy.renderTake(it) }
        val chunks = ShadowPolicy.chunks(rendered, ShadowPolicy.JUDGE_CHUNK_CHARS) { it.second.length }
        val requests = chunks.mapIndexed { i, chunk ->
            "j$i" to ClaudeBatches.params(judge.model, judge.effort, JUDGE_OUT_TOKENS, PromptsReview.SHADOW_JUDGE_SYSTEM, "ПАРЫ (${chunk.size}):\n\n" + chunk.joinToString("\n\n") { it.second })
        }
        val batchId = batches.create(requests)
        log.add("правка промпта: судья, батч $batchId, пар ${differing.size}, совпало ${takes.count { it.verdict == "same" }}")
        return run.copy(stage = PromptTunePolicy.STAGE_JUDGE, checkBatchId = batchId, progress = "судья: батч отправлен ${timeFmt.format(Date(System.currentTimeMillis()))}")
    }

    private suspend fun afterJudge(run: Run, items: List<ClaudeBatches.Item>): Run {
        val takes = ShadowPolicy.takesFromJson(run.evidence["takes"])
        val verdicts = HashMap<String, ShadowPolicy.Verdict>()
        val notes = ArrayList<String>()
        val errors = ArrayList<String>()
        for (item in items.sortedBy { it.customId }) {
            if (!item.ok) { errors += "${item.customId}: ${item.failure}"; continue }
            runCatching { ShadowPolicy.parseJudge(item.text) }
                .onSuccess { verdicts.putAll(it.verdicts); if (it.summary.isNotBlank()) notes += it.summary }
                .onFailure { errors += "${item.customId}: ответ судьи не разобрался (${NightReviewPolicy.shortReason(it)})" }
        }
        val resolved = takes.map { t ->
            if (t.failure.isNotBlank() || t.verdict.isNotBlank()) t
            else {
                val v = verdicts[t.id] ?: return@map t.copy(verdict = "unjudged")
                t.copy(verdict = ShadowPolicy.judgedVerdict(ShadowPolicy.resolve(v.better, t.flip, DAY_LABEL, NEW_LABEL)), why = v.why, flaws = v.flaws)
            }
        }
        val tally = ShadowPolicy.tally(resolved, DAY_LABEL, NEW_LABEL)
        val withOwner = resolved.filter { it.owner.isNotBlank() && it.shadow.isNotBlank() }
        val simOld = if (withOwner.isEmpty()) 0.0 else withOwner.map { EvalRunner.similarity(it.owner, it.day) }.average()
        val simNew = if (withOwner.isEmpty()) 0.0 else withOwner.map { EvalRunner.similarity(it.owner, it.shadow) }.average()
        val decision = PromptTunePolicy.decide(tally.shadowBetter, tally.dayBetter, tally.tie, simOld, simNew, withOwner.size)
        val newPrompt = run.evidence["prompt_new"].orEmpty()
        val summaryNew = run.evidence["summary_new"].orEmpty()
        val factoryHash = prompts.factory(PromptStore.PromptId.CLEAN_CLAUDE).hashCode()
        val version = PromptVersions.Version(
            id = run.id, at = run.id, source = if (decision.adopt) "tuner" else "rejected", text = newPrompt, note = summaryNew,
            factoryHash = factoryHash, judgeBetter = tally.shadowBetter, judgeWorse = tally.dayBetter, judgeTie = tally.tie,
            simOld = simOld, simNew = simNew, ownerPairs = withOwner.size,
            status = if (decision.adopt) "active" else "rejected", statusNote = decision.why,
        )
        if (decision.adopt) {
            versions.active()?.let { versions.save(it.copy(status = "superseded", statusNote = "сменена ${dayFmt.format(Date(run.id))}")) }
            prompts.setOverride(PromptStore.PromptId.CLEAN_CLAUDE, newPrompt)
        }
        versions.save(version)
        val head = if (decision.adopt) "ПРОМПТ ОБНОВЛЁН: $summaryNew." else "Промпт не меняю: предложение «$summaryNew» не прошло измерение."
        val flaws = buildString {
            if (tally.dayFlaws.isNotEmpty()) append("\nПроигрывал прежний из-за: ").append(tally.dayFlaws.entries.take(5).joinToString(", ") { "${ShadowPolicy.flawLabel(it.key)} ${it.value}" }).append('.')
            if (tally.shadowFlaws.isNotEmpty()) append("\nПроигрывал новый из-за: ").append(tally.shadowFlaws.entries.take(5).joinToString(", ") { "${ShadowPolicy.flawLabel(it.key)} ${it.value}" }).append('.')
        }
        val summary = listOf(
            head,
            "Измерение на ${takes.size} диктовках недели: совпало слово в слово ${tally.same}; ${decision.why}." + flaws,
            run.evidence["why_new"].orEmpty().let { if (it.isNotBlank()) "Почему предлагалось: $it" else "" },
            notes.takeIf { it.isNotEmpty() }?.let { "Судья: " + it.joinToString(" ") }.orEmpty(),
        ).filter { it.isNotBlank() }.joinToString("\n\n")
        val examples = ShadowPolicy.examples(resolved, DAY_LABEL, NEW_LABEL, n = 12).map { t ->
            Change(
                id = t.id, kind = ShadowPolicy.KIND, mode = DAY_LABEL, toMode = NEW_LABEL,
                from = t.input.take(ShadowPolicy.TEXT_CAP), to = t.day.take(ShadowPolicy.TEXT_CAP), note = t.shadow.take(ShadowPolicy.TEXT_CAP),
                verdict = t.verdict, verdictWhy = t.why, why = t.flaws.joinToString(", ") { ShadowPolicy.flawLabel(it) }, status = "note",
            )
        }
        log.add("правка промпта: ${if (decision.adopt) "ПРИНЯТ" else "отклонён"} — ${decision.why}")
        return finish(run.copy(changes = examples, error = errors.joinToString("; ")), summary)
    }

    private fun finish(run: Run, summary: String, error: Boolean = false): Run {
        val rollback = run.evidence["rollback"].orEmpty()
        return run.copy(
            stage = "done", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = "",
            summary = listOf(rollback, summary).filter { it.isNotBlank() }.joinToString("\n\n"),
            error = if (error) summary else run.error,
        )
    }

    suspend fun pollNow(runId: Long): Result<String> = runCatching {
        mutex.withLock {
            // Читать под замком: снимок до ожидания устарел бы и повторил чужую работу.
            val run = store.get(runId) ?: error("Прогон не найден")
            if (!run.active) return@runCatching "Уже завершено"
            runCatching {
                if (run.stage == PromptTunePolicy.STAGE_MEASURE) measureChunk(run, System.currentTimeMillis()) else poll(run, System.currentTimeMillis())
            }.onFailure { fail(run, it) }.getOrThrow()
        }
        val after = store.get(runId)
        if (after == null || !after.active) "Готово" else after.progress.ifBlank { "Ещё идёт" }
    }

    suspend fun cancel(runId: Long): Result<Unit> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        if (!run.active) return@runCatching
        val batchId = if (run.stage == PromptTunePolicy.STAGE_PROPOSE) run.analysisBatchId else run.checkBatchId
        if (batchId.isNotBlank()) runCatching { batches.cancel(batchId) }.onFailure { log.add("правка промпта: отмена батча не прошла: ${it.message}") }
        store.save(run.copy(stage = "failed", error = "отменено владельцем", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
    }

    private suspend fun fail(run: Run, e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        log.add("правка промпта НЕ УДАЛАСЬ: $msg")
        stats.recordError()
        store.save(run.copy(stage = "failed", error = msg, finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
    }
}
