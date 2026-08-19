package ru.zf.pravka.core

import kotlinx.coroutines.launch
import ru.zf.pravka.PravkaApp
import ru.zf.pravka.data.EvalStore

// Runs the golden set through the CURRENT effective prompt/dictionary/rules
// and scores each output against the reference. This is how prompt changes
// get measured instead of eyeballed. Runs on the app scope (survives leaving
// the screen); progress is polled by the UI.
object EvalRunner {

    @Volatile var running = false
        private set
    @Volatile var done = 0
        private set
    @Volatile var total = 0
        private set

    fun start(app: PravkaApp) {
        if (running) return
        running = true
        done = 0
        app.appScope.launch {
            try {
                val items = app.evalStore.all()
                total = items.size
                if (items.isEmpty()) return@launch
                val applier = DictionaryApplier(app.dictionaryStore)
                val rows = mutableListOf<EvalStore.ResultRow>()
                var exact = 0
                var sum = 0.0
                var spend = 0.0
                for (item in items) {
                    val prepared = applier.prepare(item.input)
                    val res = app.claudeProvider.proofread(
                        prepared.text, ProofreadMode.CLEAN, prepared.dictBlock,
                        onDelta = null, directive = "", contextBefore = "", modelOverride = null,
                    ).getOrNull()
                    if (res != null) {
                        spend += res.costUsd
                        app.stats.recordAux(res.costUsd, res.inputTokens, res.outputTokens)
                    }
                    val actual = res?.text ?: ""
                    val score = similarity(item.expected, actual)
                    if (normalized(item.expected) == normalized(actual)) exact++
                    sum += score
                    rows.add(EvalStore.ResultRow(item.id, score, actual))
                    done++
                    app.learnLog.add("эвал ${done}/${total}: ${"%.0f".format(score * 100)}%")
                }
                val avg = if (rows.isEmpty()) 0.0 else sum / rows.size
                app.evalStore.saveRun("текущий", avg, exact, rows.size, rows.sortedBy { it.score })
                app.learnLog.add(
                    "эвал завершён: средний ${"%.1f".format(avg * 100)}%, точных $exact из ${rows.size}, " +
                        "стоил $" + "%.4f".format(java.util.Locale.US, spend)
                )
            } finally {
                running = false
            }
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
