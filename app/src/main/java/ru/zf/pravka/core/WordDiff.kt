package ru.zf.pravka.core

// Word-level diff between the dictated text and the model's fix (spec 9.2:
// the "Что изменилось" sheet and the quick add-to-dictionary picker).
// Plain LCS over whitespace tokens - dictations are a few hundred words,
// so the quadratic table is cheap; anything huge is skipped.
//
// Tokens are MATCHED in normalized form (punctuation stripped, lowercased):
// otherwise a capitalized first word or an added comma chains neighboring
// tokens into one multi-word run and real word substitutions drown in it -
// the owner's first "В словарь" tap found nothing for exactly that reason.
object WordDiff {

    /** One replaced run: [before] words became [after] words (either may be empty). */
    data class Change(val before: String, val after: String)

    private const val MAX_TOKENS = 1500

    fun changes(before: String, after: String): List<Change> {
        val b = tokenize(before)
        val a = tokenize(after)
        if (b.size > MAX_TOKENS || a.size > MAX_TOKENS) return emptyList()

        val bn = b.map(::norm)
        val an = a.map(::norm)

        // LCS table over normalized tokens.
        val lcs = Array(b.size + 1) { IntArray(a.size + 1) }
        for (i in b.size - 1 downTo 0) {
            for (j in a.size - 1 downTo 0) {
                lcs[i][j] = if (bn[i] == an[j]) {
                    lcs[i + 1][j + 1] + 1
                } else {
                    maxOf(lcs[i + 1][j], lcs[i][j + 1])
                }
            }
        }

        // Walk the table, grouping consecutive non-matches into runs of the
        // ORIGINAL tokens (the display keeps punctuation and case).
        val result = mutableListOf<Change>()
        val delRun = mutableListOf<String>()
        val insRun = mutableListOf<String>()
        fun flush() {
            if (delRun.isNotEmpty() || insRun.isNotEmpty()) {
                result.add(Change(delRun.joinToString(" "), insRun.joinToString(" ")))
                delRun.clear()
                insRun.clear()
            }
        }
        var i = 0
        var j = 0
        while (i < b.size && j < a.size) {
            when {
                bn[i] == an[j] -> { flush(); i++; j++ }
                lcs[i + 1][j] >= lcs[i][j + 1] -> { delRun.add(b[i]); i++ }
                else -> { insRun.add(a[j]); j++ }
            }
        }
        while (i < b.size) { delRun.add(b[i]); i++ }
        while (j < a.size) { insRun.add(a[j]); j++ }
        flush()
        return result
    }

    /**
     * Word substitutions for the dictionary picker: runs with the same word
     * count are paired positionally; punctuation is stripped and pairs that
     * differ only in case are dropped.
     */
    fun wordPairs(before: String, after: String): List<Change> =
        changes(before, after)
            .flatMap { run ->
                val b = run.before.split(' ').filter { it.isNotBlank() }
                val a = run.after.split(' ').filter { it.isNotBlank() }
                if (b.isNotEmpty() && b.size == a.size) {
                    b.zip(a).map { (x, y) -> Change(strip(x), strip(y)) }
                } else {
                    emptyList()
                }
            }
            .filter { it.before.any(Char::isLetter) && it.after.any(Char::isLetter) }
            .filter { !it.before.equals(it.after, ignoreCase = true) }
            .distinctBy { it.before.lowercase() to it.after.lowercase() }

    private fun tokenize(text: String): List<String> =
        text.split(Regex("\\s+")).filter { it.isNotBlank() }

    /** Strips punctuation from both ends, keeps inner hyphens/apostrophes. */
    private fun strip(token: String): String =
        token.trim { !it.isLetterOrDigit() }

    /** Matching form: punctuation-stripped, lowercased; pure punctuation stays as is. */
    private fun norm(token: String): String {
        val stripped = strip(token).lowercase()
        return stripped.ifEmpty { token }
    }
}
