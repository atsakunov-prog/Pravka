package ru.zf.pravka.core

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
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
// Запрос — той же формы, что дневная чистка (ClaudeProvider.cleanPromptParts +
// ClaudeBatches.cleanParams), иначе измерялась бы не правка промпта, а
// разница форм. Идёт обычными запросами по одному, не батчем: батчи Опуса у
// Anthropic простояли ночь на «0 из N» (17.09.2026), а золотой набор — это
// десятки запросов на копейки, ждать их сутки незачем. Тот же ход — у тени
// (ShadowRun) и у измерения промпта (PromptTuner).
object EvalRunner {

    @Volatile var running = false
        private set
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set
    /** Что сейчас происходит — строкой для экрана. */
    @Volatile var stage = ""
        private set

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
                app.nightLog.add("эвал НЕ УДАЛСЯ: ${e.message ?: e.javaClass.simpleName}")
                app.stats.recordError()
            } finally {
                running = false
                stage = ""
            }
        }
    }

    private suspend fun run(app: PravkaApp) {
        val items = app.evalStore.all()
        total = items.size
        if (items.isEmpty()) return
        val applier = DictionaryApplier(app.dictionaryStore)
        val choice = app.settings.modelChoice(ModelRoute.PRAVKA)
        val rows = mutableListOf<EvalStore.ResultRow>()
        var exact = 0
        var sum = 0.0
        var failures = 0
        var spend = 0.0
        var tokensIn = 0
        var tokensOut = 0
        var cacheRead = 0
        var cacheWrite = 0
        stage = "чищу эталоны"
        app.nightLog.add("эвал: ${items.size} эталонов, ${choice.model} ${choice.effort}")
        for (item in items) {
            val prepared = applier.prepare(item.input)
            val parts = app.claudeProvider.cleanPromptParts(prepared.dictBlock, prose = false)
            val params = ClaudeBatches.cleanParams(choice.model, choice.effort, parts, prepared.text, cache = true)
            val res = runCatching { app.claudeBatches.single(params) }.getOrNull()
            done++
            if (res == null || !res.ok) {
                // A transient API failure must not score as 0% - that
                // records a catastrophic prompt regression that never
                // happened. Skip the item and say so.
                failures++
                app.learnLog.add("эвал ${done}/${total}: эталон ${item.id} без ответа (${res?.failure ?: "сбой запроса"}), пропущен")
                continue
            }
            spend += Pricing.costUsd(choice.model, res.inputTokens, res.outputTokens, res.cacheWrite, res.cacheRead)
            tokensIn += res.inputTokens + res.cacheRead + res.cacheWrite
            tokensOut += res.outputTokens
            cacheRead += res.cacheRead
            cacheWrite += res.cacheWrite
            // Тот же пост-процессинг, что у дневной чистки: мерим то, что
            // уехало бы в поле, а не сырой ответ.
            val actual = ResponseCleaner.clean(res.text, prepared.text) ?: res.text
            val score = similarity(item.expected, actual)
            if (normalized(item.expected) == normalized(actual)) exact++
            sum += score
            rows.add(EvalStore.ResultRow(item.id, score, actual))
            app.learnLog.add("эвал ${done}/${total}: ${"%.0f".format(score * 100)}%")
        }
        app.stats.recordAux(spend, tokensIn, tokensOut)
        app.stats.recordCache(cacheRead, cacheWrite)
        if (failures > 0) app.stats.recordError()
        if (rows.isEmpty()) {
            app.learnLog.add("эвал не удался: все ${failures} запросов провалились, результат не сохранён")
            app.nightLog.add("эвал НЕ УДАЛСЯ: все ${failures} запросов провалились")
        } else {
            val avg = sum / rows.size
            app.evalStore.saveRun("текущий", avg, exact, rows.size, rows.sortedBy { it.score })
            val failNote = if (failures > 0) ", сбоев: $failures (не в счёте)" else ""
            val line = "эвал завершён: средний ${"%.1f".format(avg * 100)}%, точных $exact из ${rows.size}$failNote, " +
                "стоил $" + "%.4f".format(java.util.Locale.US, spend) + " (из кэша $cacheRead токенов)"
            app.learnLog.add(line)
            app.nightLog.add(line)
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
