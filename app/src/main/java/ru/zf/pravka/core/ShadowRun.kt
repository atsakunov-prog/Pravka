package ru.zf.pravka.core

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.json.JSONObject
import ru.zf.pravka.core.prompts.PromptsReview
import ru.zf.pravka.data.EventLog
import ru.zf.pravka.data.HistoryLog
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.data.Models
import ru.zf.pravka.data.NightReviewStore
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run
import ru.zf.pravka.data.Settings
import ru.zf.pravka.data.Stats
import ru.zf.pravka.provider.ClaudeBatches
import ru.zf.pravka.provider.ClaudeProvider
import ru.zf.pravka.provider.Pricing

/**
 * Тень второй модели (16.09.2026). Владелец: «может, ночью первой пропустим
 * большой кусок диктовок через Opus? А дальше уже дневной. А разбирает Сонет
 * против Опуса пускай Fable 5.1 high».
 *
 * Две стадии. Первая — чистка: вторая модель (дорога SHADOW_CLEAN, заводское
 * Опус) чистит те же надиктовки, что днём чистила дневная (ModelRoute.PRAVKA),
 * тем же промптом, словарём и бюджетом (ClaudeProvider.cleanPromptParts +
 * ClaudeBatches.cleanParams). Чистка идёт ОБЫЧНЫМИ запросами, по CHUNK за тик
 * службы, с сохранением после каждого куска: батчи Опуса у Anthropic
 * простояли ночь и ещё три часа на «0 из N» (17.09.2026), а обычный запрос
 * к Опусу отвечает за секунды; полцены батча не стоят суток ожидания.
 * Вторая — судья (SHADOW_JUDGE, заводское Fable 5.1 high) батчем: батчи Fable
 * проходят за час, и там одна пачка пар, а не полсотни запросов. Судья слепой:
 * стороны A/B перемешаны, названий моделей он не видит. Итог — счёт, изъяны
 * проигравших, деньги, примеры — карточкой в «Разборах». Ничего само не
 * меняет: решение о переходе за владельцем.
 *
 * Прогон живёт в NightReviewStore с kind = shadow: процесс может умереть на
 * любой стадии, тик службы продолжит с того же места — недочищенные
 * диктовки видны по пустому полю shadow.
 */
class ShadowRun(
    private val settings: Settings,
    private val batches: ClaudeBatches,
    private val store: NightReviewStore,
    private val history: HistoryLog,
    private val applier: DictionaryApplier,
    private val provider: ClaudeProvider,
    private val stats: Stats,
    private val log: EventLog,
) {
    companion object {
        /** Бюджет ответа судьи на один запрос (до ~40 вердиктов с обоснованием). */
        private const val JUDGE_OUT_TOKENS = 6000
        /** Сколько диктовок чистится за один тик службы: тик каждые пять минут, запрос к Опусу — секунды. */
        const val CHUNK = 25
        /** Батч чистки старой сборки без единого готового запроса за полтора часа отменяется — дочищаем обычными запросами. */
        const val BATCH_STALL_MS = 90 * 60_000L
    }

    private val mutex = Mutex()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    /** Тик службы: продвинуть активный прогон тени или запустить назревший (раз в сутки после часа разбора). */
    suspend fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (!settings.shadowRunEnabledFlow.first()) return
        if (settings.apiKey().isBlank()) return
        if (!mutex.tryLock()) return
        try {
            val runs = store.all().filter { it.isShadow }
            val active = runs.filter { it.active }
            for (run in active) {
                runCatching { advance(run, nowMs) }.onFailure { fail(run, it) }
            }
            if (active.isEmpty()) {
                val hour = settings.nightReviewHourFlow.first()
                val last = runs.maxOfOrNull { it.startedAt } ?: 0L
                if (NightReviewPolicy.dueDaily(nowMs, hour, last)) {
                    start(manual = false, nowMs = nowMs).onFailure { log.add("тень: не запустилась: ${it.message}") }
                }
            }
        } finally {
            mutex.unlock()
        }
    }

    /** Чистка обычными запросами идёт каждый тик; батчи (судья и чистка старой сборки) опрашиваются раз в POLL_MS. */
    private suspend fun advance(run: Run, nowMs: Long) {
        if (run.stage == ShadowPolicy.STAGE_CLEAN && run.analysisBatchId.isBlank()) cleanChunk(run, nowMs)
        else if (nowMs - run.lastPollAt >= NightReview.POLL_MS) poll(run, nowMs)
    }

    /**
     * Запуск: собрать диктовки окна, сохранить прогон — чистка начнётся этим же
     * вызовом первым куском. Большой кусок ([big]) — один раз: сам, когда
     * прогонов тени ещё не было вовсе, или по кнопке «за месяц»; иначе сутки.
     */
    suspend fun start(manual: Boolean, big: Boolean = false, nowMs: Long = System.currentTimeMillis()): Result<Run> = runCatching {
        val shadows = store.all().filter { it.isShadow }
        val lastDoneTo = shadows.filter { it.stage == "done" }.maxOfOrNull { it.toMs }
        val w = ShadowPolicy.window(nowMs, lastDoneTo, big = big || shadows.isEmpty())
        val day = settings.modelChoice(ModelRoute.PRAVKA)
        val shadow = settings.modelChoice(ModelRoute.SHADOW_CLEAN)
        fun doneRun(summary: String) = Run(
            id = nowMs, kind = ShadowPolicy.KIND, startedAt = nowMs, fromMs = w.fromMs, toMs = w.toMs,
            stage = "done", manual = manual, summary = summary, finishedAt = nowMs,
        )
        if (shadow.model == day.model && shadow.effort == day.effort) {
            val r = doneRun("Вторая модель совпадает с дневной (${Models.label(day.model)}) — сравнивать нечего. Выбери другую в настройках, группа «Модели», дорога «Тень: вторая модель».")
            store.save(r)
            return@runCatching r
        }
        // Правило 2: чтение журнала — не на главном потоке службы.
        val entries = withContext(Dispatchers.IO) { history.readEntries(w.fromMs, w.toMs) }
            // Чистки с директивой чипа, контекстом поля или разговором по журналу
            // не повторить — их нет в записи; сравнивать их нечестно.
            .filter { !it.withContext }
            .takeLast(w.cap)
        if (entries.isEmpty()) {
            val r = doneRun("За период не было чисток без контекста — сравнивать нечего.")
            store.save(r)
            return@runCatching r
        }
        val takes = entries.mapIndexed { i, e ->
            val id = "s-${i + 1}"
            ShadowPolicy.Take(id, e.tsMs, e.input, e.input, e.output, e.costUsd, ShadowPolicy.flip(id), prose = e.prose)
        }
        val run = Run(
            id = nowMs, kind = ShadowPolicy.KIND, startedAt = nowMs, fromMs = w.fromMs, toMs = w.toMs,
            stage = ShadowPolicy.STAGE_CLEAN, manual = manual, lastPollAt = nowMs,
            progress = "почищено 0 из ${takes.size}",
            evidence = mapOf(
                "takes" to ShadowPolicy.takesToJson(takes),
                "models" to JSONObject().put("day", day.model).put("shadow", shadow.model).put("effort", shadow.effort)
                    .put("first", w.first).toString(),
            ),
        )
        store.save(run)
        log.add("тень: ${takes.size} диктовок, ${shadow.model} ${shadow.effort} обычными запросами${if (w.first) ", большой кусок" else ""}")
        // Первый кусок — сразу, не дожидаясь тика: владелец нажал кнопку и смотрит.
        if (mutex.tryLock()) {
            try { runCatching { cleanChunk(run, nowMs) }.onFailure { fail(run, it) } } finally { mutex.unlock() }
        }
        store.get(run.id) ?: run
    }

    private fun models(run: Run): JSONObject = runCatching { JSONObject(run.evidence["models"].orEmpty()) }.getOrDefault(JSONObject())
    private fun statsOf(run: Run): JSONObject = runCatching { JSONObject(run.evidence["stats"].orEmpty()) }.getOrDefault(JSONObject())

    /**
     * Кусок чистки: до CHUNK недочищенных диктовок обычными запросами второй
     * модели, тем же запросом, что днём. Сеть упала или лимит — кусок
     * заканчивается раньше, остальное дочистит следующий тик. Когда
     * недочищенных не осталось — пары уходят судье.
     */
    private suspend fun cleanChunk(run: Run, nowMs: Long) {
        val m = models(run)
        val model = m.optString("shadow").ifBlank { ModelRoute.SHADOW_CLEAN.defaultModel }
        val effort = m.optString("effort")
        val takes = ShadowPolicy.takesFromJson(run.evidence["takes"]).toMutableList()
        val pending = takes.filter { it.shadow.isBlank() && it.failure.isBlank() }
        var cost = 0.0
        var tokensIn = 0
        var tokensOut = 0
        var cacheRead = 0
        var cacheWrite = 0
        var done = 0
        var stopReason = ""
        for (t in pending.take(CHUNK)) {
            // Словарь — сегодняшний, не тот, что был в ночь диктовки: дрейф
            // невелик, а вторая модель должна видеть то же, что видит дневная сейчас.
            val prepared = applier.prepare(t.input)
            val parts = provider.cleanPromptParts(prepared.dictBlock, prose = t.prose)
            // Точка кэша на стабильной голове: запросы идут подряд, голову
            // пишет первый, остальные читают из кэша второй модели.
            val params = ClaudeBatches.cleanParams(model, effort, parts, prepared.text, cache = true)
            val item = try {
                batches.single(params)
            } catch (e: IOException) {
                stopReason = "сеть: ${e.message ?: e.javaClass.simpleName}"
                break
            } catch (e: ClaudeBatches.BatchException) {
                // 429/5xx — подождать до следующего тика; 4xx по запросу — сбой этой диктовки.
                val msg = e.message.orEmpty()
                if (Regex("ответил (429|5\\d\\d)").containsMatchIn(msg)) { stopReason = msg; break }
                takes[takes.indexOfFirst { it.id == t.id }] = t.copy(prepared = prepared.text, failure = msg)
                done++
                continue
            }
            cost += Pricing.costUsd(model, item.inputTokens, item.outputTokens, item.cacheWrite, item.cacheRead)
            tokensIn += item.inputTokens + item.cacheRead + item.cacheWrite
            tokensOut += item.outputTokens
            cacheRead += item.cacheRead
            cacheWrite += item.cacheWrite
            val idx = takes.indexOfFirst { it.id == t.id }
            takes[idx] = when {
                !item.ok -> t.copy(prepared = prepared.text, failure = item.failure)
                else -> {
                    // Тот же пост-процессинг, что у дневной чистки: преамбулы, мысли
                    // вслух, ёлочки — иначе судья мерил бы то, что режет ResponseCleaner.
                    val cleaned = ResponseCleaner.clean(item.text, prepared.text)
                    if (cleaned == null) t.copy(prepared = prepared.text, failure = "испорченный ответ")
                    else t.copy(prepared = prepared.text, shadow = cleaned, verdict = if (ShadowPolicy.same(cleaned, t.day)) "same" else "")
                }
            }
            done++
        }
        if (tokensIn > 0) {
            stats.recordAux(cost, tokensIn, tokensOut)
            stats.recordCache(cacheRead, cacheWrite)
        }
        val st = statsOf(run).put("shadowCost", statsOf(run).optDouble("shadowCost", 0.0) + cost)
        val remaining = takes.count { it.shadow.isBlank() && it.failure.isBlank() }
        var r = run.copy(
            evidence = run.evidence + ("takes" to ShadowPolicy.takesToJson(takes)) + ("stats" to st.toString()),
            costUsd = run.costUsd + cost, inputTokens = run.inputTokens + tokensIn, cacheReadTokens = run.cacheReadTokens + cacheRead,
            lastPollAt = nowMs,
            progress = "почищено ${takes.size - remaining} из ${takes.size}" +
                (if (stopReason.isNotBlank()) " · пауза: $stopReason" else "") + " · ${timeFmt.format(Date(nowMs))}",
        )
        if (done > 0 || stopReason.isNotBlank()) {
            log.add("тень: почищено $done за тик, осталось $remaining, стоило $" + "%.3f".format(Locale.US, cost) + (if (stopReason.isNotBlank()) "; $stopReason" else ""))
        }
        if (remaining == 0) r = dispatchJudge(r, takes)
        store.save(r)
    }

    /** Все диктовки почищены: совпавшее посчитано, различающиеся пары — судье батчем; нечего судить — итог сразу. */
    private suspend fun dispatchJudge(run: Run, takes: List<ShadowPolicy.Take>): Run {
        val differing = takes.filter { it.failure.isBlank() && it.verdict.isBlank() }
        if (differing.isEmpty()) return finish(run, takes, emptyList(), "")
        val judge = settings.modelChoice(ModelRoute.SHADOW_JUDGE)
        val rendered = differing.map { it.id to ShadowPolicy.renderTake(it) }
        val chunks = ShadowPolicy.chunks(rendered, ShadowPolicy.JUDGE_CHUNK_CHARS) { it.second.length }
        val requests = chunks.mapIndexed { i, chunk ->
            "j$i" to ClaudeBatches.params(
                judge.model, judge.effort, JUDGE_OUT_TOKENS, PromptsReview.SHADOW_JUDGE_SYSTEM,
                "ПАРЫ (${chunk.size}):\n\n" + chunk.joinToString("\n\n") { it.second },
            )
        }
        val batchId = batches.create(requests)
        log.add("тень: судья ${judge.model} ${judge.effort}, батч $batchId, пар ${differing.size} в ${chunks.size} запросах, совпало ${takes.count { it.verdict == "same" }}")
        // Судья запомнен в прогоне: цену и подпись считаем по тому, кто судил, а не по настройке утром.
        val withJudge = models(run).put("judge", judge.model).toString()
        return run.copy(
            stage = ShadowPolicy.STAGE_JUDGE, checkBatchId = batchId, analysisBatchId = "",
            evidence = run.evidence + ("models" to withJudge),
            progress = "судья: батч отправлен ${timeFmt.format(Date(System.currentTimeMillis()))}",
        )
    }

    /** Опрос батча: судьи — всегда; чистки — только у прогона старой сборки, который ещё шёл батчем. */
    private suspend fun poll(run: Run, nowMs: Long) {
        var r = run.copy(lastPollAt = nowMs)
        store.save(r)
        if (nowMs - r.startedAt > NightReview.EXPIRE_MS) throw IllegalStateException("батч не завершился за сутки")
        val clean = r.stage == ShadowPolicy.STAGE_CLEAN
        val batchId = if (clean) r.analysisBatchId else r.checkBatchId
        val st = batches.status(batchId)
        if (!st.ended) {
            val total = st.succeeded + st.errored + st.inFlight + st.expired + st.canceled
            if (clean && st.succeeded == 0 && nowMs - r.startedAt > BATCH_STALL_MS) {
                // Батч Опуса стоит на нуле — не ждём суток: отменяем и дочищаем сами.
                runCatching { batches.cancel(batchId) }.onFailure { log.add("тень: отмена батча $batchId не прошла: ${it.message}") }
                log.add("тень: батч $batchId не сдвинулся за ${(nowMs - r.startedAt) / 60_000} минут — отменён, дочищаю обычными запросами")
                store.save(r.copy(analysisBatchId = "", progress = "батч не сдвинулся — отменён, чищу обычными запросами"))
                return
            }
            log.add("тень: батч $batchId ещё идёт (${st.processing}, в работе ${st.inFlight}, готово ${st.succeeded})")
            store.save(
                r.copy(progress = "батч ${st.processing}: готово ${st.succeeded} из $total" + (if (st.errored > 0) ", ошибок ${st.errored}" else "") + " · опрос ${timeFmt.format(Date(nowMs))}")
            )
            return
        }
        val url = st.resultsUrl
            ?: throw IllegalStateException("батч завершён без результатов (ошибок ${st.errored}, истекло ${st.expired})")
        val items = batches.results(url)
        val m = models(r)
        val model = if (clean) m.optString("shadow") else m.optString("judge").ifBlank { ModelRoute.SHADOW_JUDGE.defaultModel }
        val cost = items.sumOf { Pricing.costUsd(model, it.inputTokens, it.outputTokens, it.cacheWrite, it.cacheRead) } * ClaudeBatches.DISCOUNT
        val tokensIn = items.sumOf { it.inputTokens + it.cacheRead + it.cacheWrite }
        val cacheRead = items.sumOf { it.cacheRead }
        stats.recordAux(cost, tokensIn, items.sumOf { it.outputTokens })
        stats.recordCache(cacheRead, items.sumOf { it.cacheWrite })
        log.add("тень (${r.stage}): вход $tokensIn токенов, из кэша $cacheRead, стоило $" + "%.3f".format(Locale.US, cost))
        r = r.copy(costUsd = r.costUsd + cost, inputTokens = r.inputTokens + tokensIn, cacheReadTokens = r.cacheReadTokens + cacheRead)
        r = if (clean) afterCleanBatch(r, items, cost) else afterJudge(r, items)
        store.save(r)
    }

    /** Результаты батча чистки старой сборки: что пришло — записываем, чего нет — дочистят обычные запросы. */
    private suspend fun afterCleanBatch(run: Run, items: List<ClaudeBatches.Item>, cost: Double): Run {
        val byId = items.associateBy { it.customId }
        val takes = ShadowPolicy.takesFromJson(run.evidence["takes"]).map { t ->
            val item = byId[t.id] ?: return@map t
            if (!item.ok) return@map t.copy(failure = item.failure)
            val cleaned = ResponseCleaner.clean(item.text, t.prepared) ?: return@map t.copy(failure = "испорченный ответ")
            t.copy(shadow = cleaned, verdict = if (ShadowPolicy.same(cleaned, t.day)) "same" else "")
        }
        val st = statsOf(run).put("shadowCost", statsOf(run).optDouble("shadowCost", 0.0) + cost)
        val remaining = takes.count { it.shadow.isBlank() && it.failure.isBlank() }
        val r = run.copy(
            analysisBatchId = "",
            evidence = run.evidence + ("takes" to ShadowPolicy.takesToJson(takes)) + ("stats" to st.toString()),
            progress = "почищено ${takes.size - remaining} из ${takes.size}",
        )
        return if (remaining == 0) dispatchJudge(r, takes) else r
    }

    private fun afterJudge(run: Run, items: List<ClaudeBatches.Item>): Run {
        val takes = ShadowPolicy.takesFromJson(run.evidence["takes"])
        val m = models(run)
        val dayLabel = Models.label(m.optString("day"))
        val shadowLabel = Models.label(m.optString("shadow"))
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
                t.copy(
                    verdict = ShadowPolicy.judgedVerdict(ShadowPolicy.resolve(v.better, t.flip, dayLabel, shadowLabel)),
                    why = v.why, flaws = v.flaws,
                )
            }
        }
        return finish(run, resolved, notes, errors.joinToString("; "))
    }

    private fun finish(run: Run, takes: List<ShadowPolicy.Take>, judgeNotes: List<String>, error: String): Run {
        val m = models(run)
        val st = statsOf(run)
        val dayLabel = Models.label(m.optString("day"))
        val shadowLabel = Models.label(m.optString("shadow"))
        val tally = ShadowPolicy.tally(takes, dayLabel, shadowLabel)
        val summary = ShadowPolicy.summary(
            tally, run.fromMs, run.toMs, dayLabel, shadowLabel, m.optString("effort"),
            Models.label(m.optString("judge").ifBlank { ModelRoute.SHADOW_JUDGE.defaultModel }),
            takes.sumOf { it.dayCostUsd }, st.optDouble("shadowCost", 0.0), judgeNotes, m.optBoolean("first"),
        )
        // Примеры — в изменениях прогона видом shadow: from — надиктовано, to —
        // дневная, note — вторая, mode/toMode — их названия для карточки.
        val examples = ShadowPolicy.examples(takes, dayLabel, shadowLabel).map { t ->
            Change(
                id = t.id, kind = ShadowPolicy.KIND, mode = dayLabel, toMode = shadowLabel,
                from = t.input.take(ShadowPolicy.TEXT_CAP), to = t.day.take(ShadowPolicy.TEXT_CAP),
                note = t.shadow.take(ShadowPolicy.TEXT_CAP), verdict = t.verdict, verdictWhy = t.why,
                why = t.flaws.joinToString(", ") { ShadowPolicy.flawLabel(it) }, status = "note",
            )
        }
        val cache = if (run.inputTokens > 0) " Вход ${run.inputTokens} токенов, из кэша ${run.cacheReadTokens} (${100 * run.cacheReadTokens / run.inputTokens}%)." else ""
        log.add("тень: ${tally.total} диктовок, лучше $shadowLabel ${tally.shadowBetter}, лучше $dayLabel ${tally.dayBetter}, совпало ${tally.same}; стоила $" + "%.3f".format(Locale.US, run.costUsd))
        return run.copy(
            stage = "done", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = "",
            summary = summary + cache, error = error, changes = examples,
        )
    }

    /** «Проверить сейчас» под идущей тенью: кусок чистки или опрос батча без десятиминутной паузы. */
    suspend fun pollNow(runId: Long): Result<String> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        if (!run.active) return@runCatching "Тень уже завершена"
        mutex.withLock {
            runCatching {
                if (run.stage == ShadowPolicy.STAGE_CLEAN && run.analysisBatchId.isBlank()) cleanChunk(run, System.currentTimeMillis())
                else poll(run, System.currentTimeMillis())
            }.onFailure { fail(run, it) }.getOrThrow()
        }
        val after = store.get(runId)
        if (after == null || !after.active) "Готово" else after.progress.ifBlank { "Ещё идёт" }
    }

    /** «Отменить»: батч (если он есть) отменяется у Anthropic, тень закрывается с причиной. */
    suspend fun cancel(runId: Long): Result<Unit> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        if (!run.active) return@runCatching
        val batchId = if (run.stage == ShadowPolicy.STAGE_CLEAN) run.analysisBatchId else run.checkBatchId
        if (batchId.isNotBlank()) {
            runCatching { batches.cancel(batchId) }.onFailure { log.add("тень: отмена батча $batchId не прошла: ${it.message}") }
        }
        store.save(run.copy(stage = "failed", error = "отменено владельцем", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
        log.add("тень: отменена владельцем на стадии ${run.stage}")
    }

    private suspend fun fail(run: Run, e: Throwable) {
        // Правило 6: причина целиком, а не «что-то пошло не так».
        val msg = e.message ?: e.javaClass.simpleName
        log.add("тень НЕ УДАЛАСЬ: $msg")
        stats.recordError()
        store.save(run.copy(stage = "failed", error = msg, finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
    }
}
