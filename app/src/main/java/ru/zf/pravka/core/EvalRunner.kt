package ru.zf.pravka.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.data.EvalStore
import ru.zf.pravka.data.ModelRoute
import ru.zf.pravka.provider.ClaudeBatches
import ru.zf.pravka.provider.Pricing

// Runs the golden set through the CURRENT effective prompt/dictionary/rules
// and scores each output against the reference. This is how prompt changes
// get measured instead of eyeballed. Runs on the app scope (survives leaving
// the screen); progress is polled by the UI.
//
// Батчем (16.09.2026): все эталоны уходят одним Message Batch — вдвое дешевле,
// а спешить эвалу некуда. Запрос — той же формы, что дневная чистка
// (ClaudeProvider.cleanPromptParts + ClaudeBatches.cleanParams), иначе
// измерялась бы не правка промпта, а разница форм. Id батча хранится в
// EvalStore: умер процесс — следующий запуск продолжит опрос, а не заплатит
// за набор второй раз.
object EvalRunner {

    @Volatile var running = false
        private set
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set
    /** Что сейчас происходит — строкой для экрана: батч идёт минуты, а не секунды. */
    @Volatile var stage = ""
        private set

    /** Статус батча спрашиваем каждые 20 секунд: набор маленький, обычно готов за минуты. */
    private const val POLL_MS = 20_000L
    /** Батч живёт сутки; старше — забываем и шлём заново. */
    private const val EXPIRE_MS = 26 * 3600_000L

    fun start(app: PravkaApp) {
        if (running) return
        running = true
        done = 0
        total = 0
        stage = "собираю запросы"
        // Default dispatcher: dictionary regexes and the word-LCS scorer are
        // CPU work that has no business on the main thread.
        app.appScope.launch(Dispatchers.Default) {
            try {
                run(app)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Правило 6: причина целиком.
                app.learnLog.add("эвал не удался: ${e.message ?: e.javaClass.simpleName}")
                app.stats.recordError()
            } finally {
                running = false
                stage = ""
            }
        }
    }

    private suspend fun run(app: PravkaApp) {
        val now = System.currentTimeMillis()
        var pending = app.evalStore.pendingBatch()
        if (pending != null && now - pending.at > EXPIRE_MS) {
            app.evalStore.clearPending()
            pending = null
        }
        val model: String
        val items: List<EvalStore.Item>
        val batchId: String
        val startedAt: Long
        if (pending != null) {
            model = pending.model
            items = pending.items
            batchId = pending.batchId
            startedAt = pending.at
            app.learnLog.add("эвал: продолжаю батч $batchId (${items.size} эталонов)")
        } else {
            items = app.evalStore.all()
            total = items.size
            if (items.isEmpty()) return
            val applier = DictionaryApplier(app.dictionaryStore)
            val choice = app.settings.modelChoice(ModelRoute.PRAVKA)
            model = choice.model
            val requests = items.map { item ->
                val prepared = applier.prepare(item.input)
                val parts = app.claudeProvider.cleanPromptParts(prepared.dictBlock, prose = false)
                "e-${item.id}" to ClaudeBatches.cleanParams(choice.model, choice.effort, parts, prepared.text, cache = true)
            }
            stage = "отправляю батч"
            batchId = app.claudeBatches.create(requests)
            startedAt = now
            app.evalStore.savePending(EvalStore.PendingBatch(batchId, model, startedAt, items))
            app.learnLog.add("эвал: батч $batchId, ${items.size} эталонов, $model ${choice.effort}")
        }
        total = items.size
        var status = app.claudeBatches.status(batchId)
        while (!status.ended) {
            done = status.succeeded + status.errored + status.expired + status.canceled
            stage = "в очереди у Anthropic"
            if (System.currentTimeMillis() - startedAt > EXPIRE_MS) {
                app.evalStore.clearPending()
                throw IllegalStateException("батч $batchId не завершился за сутки")
            }
            delay(POLL_MS)
            status = app.claudeBatches.status(batchId)
        }
        done = total
        stage = "считаю"
        val url = status.resultsUrl
        if (url == null) {
            app.evalStore.clearPending()
            throw IllegalStateException("батч завершён без результатов (ошибок ${status.errored}, истекло ${status.expired})")
        }
        val results = app.claudeBatches.results(url).associateBy { it.customId }
        val rows = mutableListOf<EvalStore.ResultRow>()
        var exact = 0
        var sum = 0.0
        var failures = 0
        var spend = 0.0
        var tokensIn = 0
        var tokensOut = 0
        var cacheRead = 0
        var cacheWrite = 0
        for (item in items) {
            val res = results["e-${item.id}"]
            if (res == null || !res.ok) {
                // A transient API failure must not score as 0% - that
                // records a catastrophic prompt regression that never
                // happened. Skip the item and say so.
                failures++
                app.learnLog.add("эвал: эталон ${item.id} без ответа (${res?.failure ?: "нет строки в результатах"}), пропущен")
                continue
            }
            spend += Pricing.costUsd(model, res.inputTokens, res.outputTokens, res.cacheWrite, res.cacheRead) * ClaudeBatches.DISCOUNT
            tokensIn += res.inputTokens + res.cacheRead + res.cacheWrite
            tokensOut += res.outputTokens
            cacheRead += res.cacheRead
            cacheWrite += res.cacheWrite
            // Тот же пост-процессинг, что у дневной чистки: мерим то, что
            // уехало бы в поле, а не сырой ответ.
            val actual = ResponseCleaner.clean(res.text, item.input) ?: res.text
            val score = similarity(item.expected, actual)
            if (normalized(item.expected) == normalized(actual)) exact++
            sum += score
            rows.add(EvalStore.ResultRow(item.id, score, actual))
        }
        app.stats.recordAux(spend, tokensIn, tokensOut)
        app.stats.recordCache(cacheRead, cacheWrite)
        if (failures > 0) app.stats.recordError()
        app.evalStore.clearPending()
        if (rows.isEmpty()) {
            app.learnLog.add("эвал не удался: все ${failures} запросов провалились, результат не сохранён")
        } else {
            val avg = sum / rows.size
            app.evalStore.saveRun("текущий", avg, exact, rows.size, rows.sortedBy { it.score })
            val failNote = if (failures > 0) ", сбоев: $failures (не в счёте)" else ""
            app.learnLog.add(
                "эвал завершён батчем: средний ${"%.1f".format(avg * 100)}%, точных $exact из ${rows.size}$failNote, " +
                    "стоил $" + "%.4f".format(java.util.Locale.US, spend) + " (из кэша $cacheRead токенов)"
            )
        }
    }

    private fun normalized(s: String): String =
        s.lowercase().replace(Regex("\\s+"), " ").trim()

    /** Word-level similarity: 2*LCS / (lenA + lenB), in [0..1]. */
    fun similarity(a: String, b: String): Double {
        val wa = normalized(a).split(' ').filter { it.isNotEmpty() }
        val wb = normalized(b).split(' ').filter { it.isNotEmpty() }
        if (wa.isEmpty() && wb.isEmpty()) return 1.0
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        // Classic LCS over words; the golden set is short texts, O(n*m) is fine.
        val dp = Array(wa.size + 1) { IntArray(wb.size + 1) }
        for (i in 1..wa.size) {
            for (j in 1..wb.size) {
                dp[i][j] = if (wa[i - 1] == wb[j - 1]) dp[i - 1][j - 1] + 1
                else maxOf(dp[i - 1][j], dp[i][j - 1])
            }
        }
        return 2.0 * dp[wa.size][wb.size] / (wa.size + wb.size)
    }
}
