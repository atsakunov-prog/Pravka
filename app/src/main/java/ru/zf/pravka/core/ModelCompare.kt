package ru.zf.pravka.core

import java.io.IOException
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlinx.coroutines.Dispatchers
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
 * Сравнение моделей (18.09.2026) — ручная тень. Владелец: «вернём тень, но без
 * этих 200 сообщений и без автостарта; я буду сам запускать и смотреть;
 * сравнивай всегда Сонет, Опус в обыкновенном режиме и Опус на low; период
 * будем выбирать». Стартует ТОЛЬКО кнопкой, с выбранным периодом и потолком
 * диктовок (10 / 20 / 40); тик службы лишь продолжает начатое.
 *
 * Стадии: три плеча чистят каждую диктовку тем же путём, что кнопка «П»
 * (ClaudeProvider.cleanOnce — поток, повтор, кэш), кусками по несколько
 * диктовок за тик с сохранением после каждой; когда все почищены — тройки с
 * различиями уходят судье (SHADOW_JUDGE, Fable) батчем под перемешанными
 * буквами. Итог — счёт по плечам, скорость, деньги, изъяны худших, примеры.
 * Ничего не меняет.
 */
class ModelCompare(
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
        private const val JUDGE_OUT_TOKENS = 6000
        const val ROUTE = "compare"
    }

    private val mutex = Mutex()
    private val timeFmt = SimpleDateFormat("HH:mm", Locale.US)

    /** Тик службы: только продолжить начатое. Сам ничего не стартует. */
    suspend fun tick(nowMs: Long = System.currentTimeMillis()) {
        if (settings.apiKey().isBlank()) return
        if (!mutex.tryLock()) return
        try {
            for (run in store.all().filter { it.isCompare && it.active }) {
                runCatching { advance(run, nowMs) }.onFailure { fail(run, it) }
            }
        } finally {
            mutex.unlock()
        }
    }

    private suspend fun advance(run: Run, nowMs: Long) {
        if (run.stage == ComparePolicy.STAGE_CLEAN) cleanChunk(run, nowMs)
        else if (nowMs - run.lastPollAt >= NightReview.POLL_MS) poll(run, nowMs)
    }

    /**
     * Запуск кнопкой: диктовки последних [days] дней без контекста, не больше [cap].
     * Сам ничего не чистит — только записывает прогон и возвращает его: первый
     * кусок экран запускает следом через [pollNow], иначе кнопка висела бы
     * минуты до первого тоста, а чистка шла бы в корутине экрана.
     */
    suspend fun start(days: Int, cap: Int, nowMs: Long = System.currentTimeMillis()): Result<Run> = runCatching {
        if (store.all().any { it.isCompare && it.active }) error("Сравнение уже идёт")
        val from = nowMs - days.coerceIn(1, 30) * 86_400_000L
        val entries = withContext(Dispatchers.IO) { history.readEntries(from, nowMs) }
            .filter { !it.withContext }
            .takeLast(cap.coerceIn(1, 60))
        fun doneRun(summary: String) = Run(
            id = nowMs, kind = ComparePolicy.KIND, startedAt = nowMs, fromMs = from, toMs = nowMs,
            stage = "done", manual = true, summary = summary, finishedAt = nowMs,
        )
        if (entries.isEmpty()) {
            val r = doneRun("За период нет чисток без контекста — сравнивать нечего.")
            store.save(r)
            return@runCatching r
        }
        val items = entries.mapIndexed { i, e -> ComparePolicy.Item("c-${i + 1}", e.tsMs, e.input, e.prose) }
        val run = Run(
            id = nowMs, kind = ComparePolicy.KIND, startedAt = nowMs, fromMs = from, toMs = nowMs,
            stage = ComparePolicy.STAGE_CLEAN, manual = true, lastPollAt = nowMs,
            progress = "почищено 0 из ${items.size} диктовок",
            evidence = mapOf("items" to ComparePolicy.toJson(items)),
        )
        store.save(run)
        log.add("сравнение: ${items.size} диктовок за $days дн., три плеча: " + ComparePolicy.ARMS.joinToString(", ") { it.label })
        run
    }

    private fun statsOf(run: Run): JSONObject = runCatching { JSONObject(run.evidence["stats"].orEmpty()) }.getOrDefault(JSONObject())

    /**
     * Кусок: до CHUNK_ITEMS диктовок, каждую — тремя плечами; сохранение после
     * каждой диктовки. Отмена побеждает всегда: перед диктовкой и перед каждой
     * записью прогон перечитывается из стора — запись поверх «отменено» оживляла
     * бы его, как у тени 17.09.
     */
    private suspend fun cleanChunk(run: Run, nowMs: Long) {
        val items = ComparePolicy.fromJson(run.evidence["items"]).toMutableList()
        var r = run.copy(lastPollAt = nowMs)
        var stopReason = ""
        var did = 0
        fun remaining() = items.count { it.pendingArms.isNotEmpty() && !it.failed }
        suspend fun persist(note: String = ""): Boolean {
            if (store.get(run.id)?.active != true) return false
            r = r.copy(
                evidence = r.evidence + ("items" to ComparePolicy.toJson(items)),
                progress = "почищено ${items.size - remaining()} из ${items.size} диктовок" + (if (note.isNotBlank()) " · $note" else "") + " · ${timeFmt.format(Date(System.currentTimeMillis()))}",
            )
            store.save(r)
            return true
        }
        outer@ for (idx in items.indices) {
            val item = items[idx]
            if (item.pendingArms.isEmpty() || item.failed) continue
            if (did >= ComparePolicy.CHUNK_ITEMS) break
            if (store.get(run.id)?.active != true) { log.add("сравнение: прогон снят во время чистки — останавливаюсь"); return }
            // Словарь и промпт — одни на все три плеча: сравниваем модели, не входы.
            val prepared = applier.prepare(item.input)
            val parts = provider.cleanPromptParts(prepared.dictBlock, prose = item.prose).copy(cacheStableAlways = true)
            var cur = item
            for (arm in item.pendingArms) {
                val res = provider.cleanOnce(arm.model, arm.effort, parts, prepared.text, routeKey = ROUTE)
                val err = res.exceptionOrNull()
                if (err != null) {
                    val msg = err.message ?: err.javaClass.simpleName
                    if (err is IOException || (err is ClaudeProvider.ApiException && err.retryable)) { stopReason = msg; items[idx] = cur; break@outer }
                    cur = cur.copy(results = cur.results + (arm.key to ComparePolicy.Result(failure = msg)))
                    continue
                }
                val out = res.getOrThrow()
                stats.recordAux(out.costUsd, out.inputTokens, out.outputTokens, route = ROUTE)
                r = r.copy(costUsd = r.costUsd + out.costUsd, inputTokens = r.inputTokens + out.inputTokens, cacheReadTokens = r.cacheReadTokens + out.cacheReadTokens)
                val cleaned = ResponseCleaner.clean(out.text, prepared.text)
                cur = cur.copy(
                    results = cur.results + (arm.key to (if (cleaned == null) ComparePolicy.Result(failure = "испорченный ответ", costUsd = out.costUsd, ms = out.latencyMs)
                    else ComparePolicy.Result(cleaned, out.costUsd, out.latencyMs))),
                )
            }
            items[idx] = cur
            did++
            if (!persist()) { log.add("сравнение: прогон снят во время чистки — останавливаюсь"); return }
        }
        if (stopReason.isNotBlank() && !persist("пауза: $stopReason")) return
        if (did > 0 || stopReason.isNotBlank()) log.add("сравнение: диктовок за тик $did, осталось ${remaining()}" + (if (stopReason.isNotBlank()) "; $stopReason" else ""))
        if (remaining() == 0 && store.get(run.id)?.active == true) store.save(dispatchJudge(r, items))
    }

    private suspend fun dispatchJudge(run: Run, items: List<ComparePolicy.Item>): Run {
        val judgeable = items.filter { it.judgeable }
        if (judgeable.isEmpty()) return finish(run, items, emptyList(), "")
        val judge = settings.modelChoice(ModelRoute.SHADOW_JUDGE)
        val rendered = judgeable.map { it.id to ComparePolicy.render(it) }
        val chunks = ShadowPolicy.chunks(rendered, ShadowPolicy.JUDGE_CHUNK_CHARS) { it.second.length }
        val requests = chunks.mapIndexed { i, chunk ->
            "j$i" to ClaudeBatches.params(judge.model, judge.effort, JUDGE_OUT_TOKENS, PromptsReview.COMPARE_JUDGE_SYSTEM, "ТРОЙКИ (${chunk.size}):\n\n" + chunk.joinToString("\n\n") { it.second })
        }
        val batchId = batches.create(requests)
        log.add("сравнение: судья ${judge.model} ${judge.effort}, батч $batchId, троек ${judgeable.size}, совпало все три ${items.count { it.allSame }}")
        val m = JSONObject().put("judge", judge.model).toString()
        return run.copy(stage = ComparePolicy.STAGE_JUDGE, checkBatchId = batchId, evidence = run.evidence + ("models" to m), progress = "судья: батч отправлен ${timeFmt.format(Date(System.currentTimeMillis()))}")
    }

    private suspend fun poll(run: Run, nowMs: Long) {
        var r = run.copy(lastPollAt = nowMs)
        store.save(r)
        if (nowMs - r.startedAt > NightReview.EXPIRE_MS) throw IllegalStateException("батч судьи не завершился за сутки")
        val st = batches.status(r.checkBatchId)
        if (!st.ended) {
            store.save(r.copy(progress = "судья: батч ${st.processing} · опрос ${timeFmt.format(Date(nowMs))}"))
            return
        }
        val url = st.resultsUrl ?: throw IllegalStateException("батч судьи завершён без результатов (ошибок ${st.errored}, истекло ${st.expired})")
        val items = batches.results(url)
        val model = runCatching { JSONObject(r.evidence["models"].orEmpty()).optString("judge") }.getOrDefault("").ifBlank { ModelRoute.SHADOW_JUDGE.defaultModel }
        val cost = items.sumOf { Pricing.costUsd(model, it.inputTokens, it.outputTokens, it.cacheWrite, it.cacheRead) } * ClaudeBatches.DISCOUNT
        val tokensIn = items.sumOf { it.inputTokens + it.cacheRead + it.cacheWrite }
        stats.recordAux(cost, tokensIn, items.sumOf { it.outputTokens }, route = ModelRoute.SHADOW_JUDGE.key)
        stats.recordCache(items.sumOf { it.cacheRead }, items.sumOf { it.cacheWrite })
        r = r.copy(costUsd = r.costUsd + cost, inputTokens = r.inputTokens + tokensIn, evidence = r.evidence + ("stats" to statsOf(r).put("judgeCost", cost).toString()))
        store.save(afterJudge(r, items))
    }

    private fun afterJudge(run: Run, results: List<ClaudeBatches.Item>): Run {
        val items = ComparePolicy.fromJson(run.evidence["items"])
        val verdicts = HashMap<String, ComparePolicy.Verdict>()
        val notes = ArrayList<String>()
        val errors = ArrayList<String>()
        for (item in results.sortedBy { it.customId }) {
            if (!item.ok) { errors += "${item.customId}: ${item.failure}"; continue }
            runCatching { ComparePolicy.parseJudge(item.text) }
                .onSuccess { verdicts.putAll(it.verdicts); if (it.summary.isNotBlank()) notes += it.summary }
                .onFailure { errors += "${item.customId}: ответ судьи не разобрался (${NightReviewPolicy.shortReason(it)})" }
        }
        val resolved = items.map { it ->
            if (!it.judgeable) it
            else {
                val v = verdicts[it.id] ?: return@map it
                it.copy(best = ComparePolicy.resolve(v.best, it.id), worst = ComparePolicy.resolve(v.worst, it.id), why = v.why, flaws = v.flaws)
            }
        }
        return finish(run.copy(error = errors.joinToString("; ")), resolved, notes, run.evidence["models"].orEmpty())
    }

    private fun finish(run: Run, items: List<ComparePolicy.Item>, notes: List<String>, modelsJson: String): Run {
        val judgeModel = runCatching { JSONObject(modelsJson).optString("judge") }.getOrDefault("").ifBlank { ModelRoute.SHADOW_JUDGE.defaultModel }
        val t = ComparePolicy.tally(items)
        val summary = ComparePolicy.summary(t, run.fromMs, run.toMs, Models.label(judgeModel), statsOf(run).optDouble("judgeCost", 0.0), notes)
        // Примеры — изменениями вида compare: from — надиктовано, to — JSON {плечо: текст}, verdict — лучший, note — худший.
        val examples = ComparePolicy.examples(items).map { it ->
            Change(
                id = it.id, kind = ComparePolicy.KIND, from = it.input.take(ComparePolicy.TEXT_CAP),
                to = JSONObject().apply { for (a in ComparePolicy.ARMS) put(a.label, it.results[a.key]?.text.orEmpty().take(ComparePolicy.TEXT_CAP)) }.toString(),
                verdict = ComparePolicy.label(it.best), note = if (it.worst.isBlank() || it.worst == "same") "" else ComparePolicy.label(it.worst),
                verdictWhy = it.why, why = it.flaws.joinToString(", ") { ShadowPolicy.flawLabel(it) }, status = "note",
            )
        }
        log.add("сравнение: ${t.total} диктовок — лучше всех " + ComparePolicy.ARMS.joinToString(", ") { "${it.label} ${t.best[it.key] ?: 0}" } + "; стоило $" + "%.3f".format(Locale.US, run.costUsd))
        return run.copy(stage = "done", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = "", summary = summary, changes = examples)
    }

    suspend fun pollNow(runId: Long): Result<String> = runCatching {
        mutex.withLock {
            val run = store.get(runId) ?: error("Прогон не найден")
            if (!run.active) return@runCatching "Уже завершено"
            // Кнопка не ждёт паузы опроса: чистка — следующий кусок, судья — статус батча сразу.
            val now = System.currentTimeMillis()
            runCatching { if (run.stage == ComparePolicy.STAGE_CLEAN) cleanChunk(run, now) else poll(run, now) }
                .onFailure { fail(run, it) }.getOrThrow()
        }
        val after = store.get(runId)
        if (after == null || !after.active) "Готово" else after.progress.ifBlank { "Ещё идёт" }
    }

    suspend fun cancel(runId: Long): Result<Unit> = runCatching {
        val run = store.get(runId) ?: error("Прогон не найден")
        if (!run.active) return@runCatching
        if (run.checkBatchId.isNotBlank()) runCatching { batches.cancel(run.checkBatchId) }.onFailure { log.add("сравнение: отмена батча не прошла: ${it.message}") }
        store.save(run.copy(stage = "failed", error = "отменено владельцем", finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
        log.add("сравнение: отменено владельцем на стадии ${run.stage}")
    }

    private suspend fun fail(run: Run, e: Throwable) {
        val msg = e.message ?: e.javaClass.simpleName
        log.add("сравнение НЕ УДАЛОСЬ: $msg")
        stats.recordError()
        store.save(run.copy(stage = "failed", error = msg, finishedAt = System.currentTimeMillis(), evidence = emptyMap(), progress = ""))
    }
}
