package ru.zf.pravka.core

// Post-processing of the model's reply before it is written back (spec 7.4).
object ResponseCleaner {

    private val preambleRegex = Regex(
        "^\\s*(вот исправленный текст|исправленный вариант|вот исправленный вариант|исправленный текст)\\s*:?\\s*",
        RegexOption.IGNORE_CASE,
    )

    // Модель «думает вслух» ПОСЛЕ ответа (16.09.2026, четыре случая на 1504
    // чистки): за готовым текстом шёл абзац «Хотя, если сомневаться в
    // расшифровке названия — оставлю ближе к исходному звучанию:» и второй
    // вариант целиком — в поле уезжало всё, включая рассуждение. Режем только
    // уверенный рисунок: абзац начинается с маркера сомнения И содержит
    // служебное слово, а следом идёт абзац, повторяющий начало по словам
    // (второй вариант того же текста). Остаётся ПОСЛЕДНИЙ вариант — он и есть
    // решение модели («оставлю ближе к исходнику»).
    private val metaStart = listOf(
        "хотя", "подожди", "стоп", "судя по", "оставлю", "оставляю", "уточню",
        "впрочем", "хм", "перепроверю", "проверю", "точнее",
    )
    private val metaCue = listOf(
        "исходник", "оставл", "провер", "вариант", "расшифровк", "по смыслу",
        "формулировк", "не подходит", "созвучн",
    )

    internal fun cutSelfTalk(text: String): String {
        val paras = text.split(Regex("\\n\\s*\\n"))
        if (paras.size < 3) return text
        for (i in paras.size - 2 downTo 1) {
            val p = paras[i].trim().lowercase()
            if (metaStart.none { p.startsWith(it) } || metaCue.none { it in p }) continue
            val head = paras.subList(0, i).joinToString("\n\n").trim()
            val tail = paras.subList(i + 1, paras.size).joinToString("\n\n").trim()
            if (head.isEmpty() || tail.isEmpty() || wordOverlap(head, tail) < 0.5) continue
            return tail
        }
        return text
    }

    private fun words(s: String): Set<String> =
        Regex("[\\p{L}\\p{N}]+").findAll(s.lowercase()).map { it.value }.toSet()

    /** Доля общих слов от меньшего набора: два варианта одного текста дают ≥ 0.5. */
    private fun wordOverlap(a: String, b: String): Double {
        val wa = words(a)
        val wb = words(b)
        if (wa.isEmpty() || wb.isEmpty()) return 0.0
        return wa.intersect(wb).size.toDouble() / minOf(wa.size, wb.size)
    }

    // Returns the cleaned text, or null when the reply must be considered
    // corrupted (last line of defense against the model eating a paragraph).
    fun clean(raw: String, original: String, lenient: Boolean = false): String? {
        var text = raw.trim()

        // Markdown fences around the whole reply.
        if (text.startsWith("```") && text.endsWith("```") && text.length > 6) {
            text = text.removePrefix("```").removeSuffix("```")
            // possible language tag on the first line
            text = text.substringAfter('\n', text).trim()
        }

        // Typical preambles.
        text = text.replace(preambleRegex, "").trim()
        text = cutSelfTalk(text)
        // Правило 15 промпта — только прямые кавычки; модель всё же ставит
        // ёлочки в паре процентов текстов (9 из 1200 в журнале). Замена
        // детерминированная, промпту она не мешает.
        text = text.replace('«', '"').replace('»', '"')

        // Wrapping quotes, if the whole reply is quoted but the original was not.
        val quotePairs = listOf('"' to '"', '«' to '»', '“' to '”')
        for ((open, close) in quotePairs) {
            if (text.length > 2 && text.first() == open && text.last() == close &&
                !(original.trim().startsWith(open) && original.trim().endsWith(close))
            ) {
                text = text.substring(1, text.length - 1).trim()
                break
            }
        }

        if (text.isEmpty()) return null
        // Directive rewrites ("короче", "длиннее") legally move length far
        // beyond the ratio gate - only the empty check applies to them.
        if (lenient) return text
        // Length sanity gate with absolute slack: a pure ratio rejected CORRECT
        // replies for short fragments where formatting legitimately changes
        // length a lot ("в 5" -> "в 17:00", sums, dates). The gate exists to
        // catch the model eating a paragraph, which only matters at paragraph
        // scale anyway.
        val origLen = original.trim().length
        if (text.length < origLen / 2 - 40 || text.length > origLen * 2 + 40) return null
        return text
    }
}
