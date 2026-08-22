package ru.zf.pravka.core

// Iron rule #3 of the owner's operating system: never create a task that
// already exists. The check runs LOCALLY against the cached list of his open
// tasks - no extra tokens, no extra latency, and it works offline. It only
// warns: the decision stays with the owner in the editor.
object TaskMatcher {

    // Words that carry no identity ("посмотреть", "по", "и"): counting them
    // would make every "Наташа: посмотреть ..." look like every other one.
    private val STOP = setOf(
        "и", "в", "во", "на", "по", "с", "со", "к", "о", "об", "от", "до", "за",
        "из", "у", "не", "для", "про", "что", "как", "же", "ли", "бы", "это",
        "надо", "нужно", "сделать", "ещё", "еще", "там", "тут", "его", "её", "ее",
    )

    private fun tokens(text: String): Set<String> =
        text.lowercase()
            .map { if (it.isLetterOrDigit()) it else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.length > 2 && it !in STOP }
            // Russian inflection: comparing stems ("сверк" vs "сверку") catches
            // the same task worded slightly differently, which is exactly how
            // duplicates appear across two digests.
            .map { it.take(5) }
            .toSet()

    /**
     * The closest existing task to [candidate], or null when nothing is close
     * enough. Same-project matches are preferred; a cross-project hit still
     * counts, because "one task lives in one project" is itself a rule the
     * owner wants enforced.
     */
    fun findDuplicate(
        candidate: ParsedTask,
        existing: List<Pair<String, String>>,  // content to projectId
    ): String? {
        val mine = tokens(candidate.content)
        if (mine.size < 2) return null
        var best: String? = null
        var bestScore = 0.0
        for ((content, projectId) in existing) {
            val theirs = tokens(content)
            if (theirs.isEmpty()) continue
            val shared = mine.count { it in theirs }
            if (shared < 2) continue
            val score = shared.toDouble() / minOf(mine.size, theirs.size) +
                if (projectId.isNotEmpty() && projectId == candidate.projectId) 0.15 else 0.0
            if (score > bestScore) {
                bestScore = score
                best = content
            }
        }
        return if (bestScore >= 0.6) best else null
    }
}
