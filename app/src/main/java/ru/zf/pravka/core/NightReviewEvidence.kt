package ru.zf.pravka.core

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import ru.zf.pravka.data.NightReviewStore.Change
import ru.zf.pravka.data.NightReviewStore.Run

/**
 * Свидетельства ночного разбора, которые считаются локально и бесплатно
 * (16.09.2026, второй заход). Владелец спросил: «как Fable будет смотреть —
 * последний день, неделю, правила плюс неделю?» Ответ в этом файле:
 *  - сырые тексты — только за окно прогона (сутки или неделя), иначе
 *    ежедневный разбор возил бы одну и ту же неделю семь раз;
 *  - ПОВТОРЫ — за скользящее окно длиннее (7 дней у дневного, 30 у недельного),
 *    но не текстами, а счётчиками: диффом «надиктовано → модель» по всем
 *    парам окна, что во что заменялось и сколько раз. Ослышка становится
 *    словарной, когда повторяется, а за одни сутки она редко повторится;
 *  - РЕШЕНИЯ РАНЬШЕ — что прошлые прогоны применили, что владелец вернул или
 *    отклонил, как сработало применённое. Это память автомата: без неё он
 *    предлагал бы каждую ночь то же самое.
 * Словарь и правила — всегда целиком: это состояние, которое правится, а не
 * журнал.
 */
object NightReviewEvidence {

    data class Sub(val from: String, val to: String, val count: Int)

    data class Aggregate(val pairs: Int, val words: Int, val subs: List<Sub>, val negationDeleted: Int)

    private val wordRe = Regex("[\\p{L}\\p{N}\\-']+")
    private const val MAX_TOKENS = 400
    private const val MAX_SIDE = 3
    private val negations = setOf("не", "нет")

    private fun tokens(s: String): List<String> =
        wordRe.findAll(s.lowercase().replace('ё', 'е')).map { it.value }.take(MAX_TOKENS).toList()

    /**
     * Повторяющиеся замены по всем парам окна: пословный дифф (LCS), куски до
     * трёх слов с каждой стороны, счёт по нормализованной паре. Плюс сколько
     * раз модель удалила «не»/«нет» не как заикание — это класс ошибок, за
     * которым разбор следит отдельно.
     */
    fun aggregate(pairs: List<Pair<String, String>>, top: Int = 60): Aggregate {
        val counts = HashMap<Pair<String, String>, Int>()
        var words = 0
        var negDeleted = 0
        for ((input, output) in pairs) {
            val a = tokens(input)
            val b = tokens(output)
            words += a.size
            for (h in hunks(a, b)) {
                if (h.del.isNotEmpty() && h.ins.isNotEmpty()) {
                    if (h.del.size <= MAX_SIDE && h.ins.size <= MAX_SIDE) {
                        val key = h.del.joinToString(" ") to h.ins.joinToString(" ")
                        counts[key] = (counts[key] ?: 0) + 1
                    }
                } else if (h.del.isNotEmpty()) {
                    // «не не могу» → «не могу» — заикание, не потеря отрицания.
                    for ((i, w) in h.del.withIndex()) {
                        if (w !in negations) continue
                        val prev = if (h.at > 0) a[h.at - 1] else ""
                        val next = a.getOrNull(h.at + h.del.size)
                        val neighbor = h.del.getOrNull(i - 1) ?: prev
                        if (neighbor in negations || next in negations) continue
                        negDeleted++
                    }
                }
            }
        }
        val subs = counts.entries
            .filter { it.value >= 2 }
            .sortedWith(compareBy({ -it.value }, { it.key.first }))
            .take(top)
            .map { Sub(it.key.first, it.key.second, it.value) }
        return Aggregate(pairs.size, words, subs, negDeleted)
    }

    private class Hunk(val at: Int, val del: List<String>, val ins: List<String>)

    /** Куски различий по LCS: подряд идущие удаления и вставки между общими словами. */
    private fun hunks(a: List<String>, b: List<String>): List<Hunk> {
        val n = a.size
        val m = b.size
        if (n == 0 || m == 0) return if (n == 0 && m == 0) emptyList() else listOf(Hunk(0, a, b))
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) for (j in m - 1 downTo 0) {
            dp[i][j] = if (a[i] == b[j]) dp[i + 1][j + 1] + 1 else maxOf(dp[i + 1][j], dp[i][j + 1])
        }
        val out = ArrayList<Hunk>()
        var i = 0
        var j = 0
        var del = ArrayList<String>()
        var ins = ArrayList<String>()
        var at = 0
        fun flush() {
            if (del.isNotEmpty() || ins.isNotEmpty()) out.add(Hunk(at, del, ins))
            del = ArrayList(); ins = ArrayList()
        }
        while (i < n && j < m) {
            when {
                a[i] == b[j] -> { flush(); i++; j++; at = i }
                dp[i + 1][j] >= dp[i][j + 1] -> { if (del.isEmpty() && ins.isEmpty()) at = i; del.add(a[i]); i++ }
                else -> { if (del.isEmpty() && ins.isEmpty()) at = i; ins.add(b[j]); j++ }
            }
        }
        while (i < n) { if (del.isEmpty() && ins.isEmpty()) at = i; del.add(a[i]); i++ }
        while (j < m) { ins.add(b[j]); j++ }
        flush()
        return out
    }

    fun render(a: Aggregate, title: String): String {
        if (a.pairs == 0) return ""
        val sb = StringBuilder(title).append(": чисток ").append(a.pairs).append(", слов ").append(a.words)
            .append(", удалений «не/нет» не как заикание — ").append(a.negationDeleted).append('\n')
        if (a.subs.isEmpty()) sb.append("повторяющихся замен нет\n")
        for (s in a.subs) sb.append(s.count).append(" × «").append(s.from).append("» → «").append(s.to).append("»\n")
        return sb.toString().trimEnd()
    }

    private val day = SimpleDateFormat("dd.MM", Locale.US)

    /** Ключ изменения для памяти: тот же вид, вид записи и слово — то же решение. */
    fun key(c: Change): String = "${c.kind}|${c.mode}|${c.from.trim().lowercase()}"

    /** Что владелец вернул или отклонил сам за окно — предлагать снова нельзя. */
    fun blockedKeys(runs: List<Run>, sinceMs: Long): Set<String> = runs
        .filter { it.startedAt >= sinceMs }
        .flatMap { it.changes }
        .filter { it.status == "reverted" || (it.status == "rejected" && "владелец" in it.statusNote) }
        .map { key(it) }
        .toSet()

    /**
     * Решения прошлых прогонов текстом для модели: применено, вернул владелец,
     * отклонено проверкой или владельцем, и как применённое сработало с тех
     * пор ([hitsSince] — срабатывания записи словаря по её id из undo).
     */
    fun ledger(runs: List<Run>, sinceMs: Long, hitsSince: (Long) -> Int? = { null }, maxLines: Int = 80): String {
        val lines = ArrayList<String>()
        for (run in runs.filter { it.startedAt >= sinceMs && it.stage == "done" }.sortedByDescending { it.startedAt }) {
            for (c in run.changes) {
                // Отброшенное и пропущенное — не решение, памяти не нужно.
                if (c.isNote || c.status == "dropped" || c.status == "skipped") continue
                val hits = undoId(c)?.let(hitsSince)
                val tail = when (c.status) {
                    "applied" -> "применено" + (hits?.let { " (сработало $it раз)" } ?: "")
                    "reverted" -> "ВЕРНУЛ ВЛАДЕЛЕЦ"
                    "rejected" -> if ("владелец" in c.statusNote) "ОТКЛОНИЛ ВЛАДЕЛЕЦ" else "отклонено проверкой: ${c.verdictWhy}"
                    "proposed" -> "предложено, владелец не решил"
                    else -> c.status
                }
                lines.add("${day.format(Date(run.startedAt))} · ${c.title()} — $tail")
                if (lines.size >= maxLines) break
            }
            for (r in run.replies.takeLast(2)) lines.add("${day.format(Date(r.at))} · владелец написал: «${r.text.take(160)}»")
            if (lines.size >= maxLines) break
        }
        if (lines.isEmpty()) return ""
        return "РЕШЕНИЯ РАНЬШЕ (свежее сверху; возвращённое и отклонённое владельцем снова не предлагать):\n" +
            lines.take(maxLines).joinToString("\n")
    }

    private fun undoId(c: Change): Long? = runCatching {
        val u = org.json.JSONObject(c.undo)
        if (u.optString("op") in setOf("delete", "disable")) u.optLong("id") else null
    }.getOrNull()
}
