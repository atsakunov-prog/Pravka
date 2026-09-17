package ru.zf.pravka.core

import java.util.Locale

/**
 * Недельная правка промпта CLEAN (17.09.2026) — чистая политика без Android.
 * Владелец: «раз в неделю Fable проходился по идеям за неделю и чинил бы
 * промпт… если у него нет идей — чтобы не придумывал… метрика: сравнивал
 * расшифровки прошлой недели с этой, насколько промпт работает лучше, а если
 * хуже — возвращал всё обратно». Здесь: разбор предложения, проверка формы
 * нового промпта, правило «принять или отклонить» по слепому судье и
 * близости к правкам руками, правило отката через неделю по доле правок.
 */
object PromptTunePolicy {
    const val KIND = "tune"
    const val STAGE_PROPOSE = "tune_propose"
    const val STAGE_MEASURE = "tune_measure"
    const val STAGE_JUDGE = "tune_judge"

    /** Окно идей и измерения — неделя; прогон идёт в ночь на субботу, после недельного разбора. */
    const val WINDOW_MS = 7 * 86_400_000L
    /** Сколько диктовок недели перечищается новым промптом; с правкой руками — первыми. */
    const val MEASURE_CAP = 60
    /** Меньше стольких решающих пар у судьи — не на чём решать, промпт не меняется. */
    const val MIN_JUDGED = 5
    /** Новый должен выигрывать заметно: не 11:10, а хотя бы 5:4. */
    const val ADOPT_RATIO = 1.25
    /** Близость к правкам владельца не должна упасть больше, чем на это. */
    const val SIM_TOLERANCE = 0.01
    /** Откат через неделю: доля правок руками выросла в 1,3 раза при хотя бы десяти правках. */
    const val ROLLBACK_RATIO = 1.3
    const val ROLLBACK_MIN_CORRECTIONS = 10
    /** Откат смотрится не раньше, чем через шесть дней жизни версии: неделя на неделю. */
    const val ROLLBACK_AFTER_MS = 6 * 86_400_000L

    private const val PROMPT_OPEN = "<<<PROMPT>>>"
    private const val PROMPT_CLOSE = "<<<END>>>"

    data class Proposal(val change: Boolean, val why: String, val summary: String, val prompt: String)

    /**
     * Ответ подбора: JSON-шапка {"change", "why", "summary"} и, если change —
     * полный текст промпта между маркерами. Маркеры вместо JSON-строки: двенадцать
     * тысяч знаков с кавычками и переносами в JSON модель экранирует с ошибками.
     */
    fun parseProposal(raw: String): Proposal {
        val open = raw.indexOf(PROMPT_OPEN)
        val head = if (open >= 0) raw.substring(0, open) else raw
        val o = NightReviewPolicy.jsonObject(head)
        val change = o.optBoolean("change", false)
        var prompt = ""
        if (change) {
            require(open >= 0) { "change=true, а текста промпта между маркерами нет" }
            val close = raw.indexOf(PROMPT_CLOSE, open)
            require(close > open) { "нет закрывающего маркера промпта" }
            prompt = raw.substring(open + PROMPT_OPEN.length, close).trim('\n', '\r', ' ')
        }
        return Proposal(change, o.optString("why").trim(), o.optString("summary").trim(), prompt)
    }

    /** Проверка формы нового промпта; null — годится, иначе причина отказа. */
    fun validate(old: String, new: String): String? {
        if (new.isBlank()) return "пустой текст"
        if (new.trim() == old.trim()) return "текст не изменился"
        for (ph in listOf(Prompts.PLACEHOLDER_DICT, Prompts.PLACEHOLDER_INPUT)) {
            val was = old.split(ph).size - 1
            val now = new.split(ph).size - 1
            if (now != was) return "метка $ph встречается $now раз, а должна $was"
        }
        val tags = listOf("<словарь>", "</словарь>", "<текст>", "</текст>")
        for (t in tags) if (old.contains(t) && !new.contains(t)) return "пропал тег $t"
        if (new.contains("```")) return "в тексте markdown-ограда"
        val ratio = new.length.toDouble() / old.length.coerceAtLeast(1)
        if (ratio < 0.8) return "текст короче прежнего на ${((1 - ratio) * 100).toInt()}% — что-то выброшено"
        if (ratio > 1.35) return "текст длиннее прежнего на ${((ratio - 1) * 100).toInt()}% — это не точечная правка"
        return null
    }

    data class Decision(val adopt: Boolean, val why: String)

    /** Принять новый промпт: судья заметно за него и правки руками не стали дальше. */
    fun decide(newBetter: Int, oldBetter: Int, tie: Int, simOld: Double, simNew: Double, ownerPairs: Int): Decision {
        val decisive = newBetter + oldBetter
        if (decisive < MIN_JUDGED) return Decision(false, "решающих пар у судьи $decisive, меньше $MIN_JUDGED — не на чём решать (равноценных $tie)")
        if (newBetter <= oldBetter || newBetter < oldBetter * ADOPT_RATIO) {
            return Decision(false, "судья: новый лучше в $newBetter, прежний в $oldBetter (равноценных $tie) — перевеса нет")
        }
        if (ownerPairs >= 5 && simNew < simOld - SIM_TOLERANCE) {
            return Decision(false, "судья за новый ($newBetter:$oldBetter), но к правкам владельца он дальше: ${pct(simNew)} против ${pct(simOld)} на $ownerPairs парах")
        }
        val sim = if (ownerPairs > 0) "; к правкам владельца ${pct(simNew)} против ${pct(simOld)} на $ownerPairs парах" else ""
        return Decision(true, "судья: новый лучше в $newBetter, прежний в $oldBetter, равноценных $tie$sim")
    }

    fun rate(corrections: Int, cleanups: Int): Double = if (cleanups <= 0) 0.0 else corrections.toDouble() / cleanups

    /** Доля правок руками после смены выросла заметно — откатывать. */
    fun shouldRollback(corrBefore: Double, corrAfter: Double, correctionsAfter: Int): Boolean =
        correctionsAfter >= ROLLBACK_MIN_CORRECTIONS && corrAfter > corrBefore * ROLLBACK_RATIO && corrAfter > 0.0

    fun pct(v: Double): String = "%.0f%%".format(Locale.US, v * 100)
}
