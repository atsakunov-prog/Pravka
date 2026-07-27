package ru.zf.pravka.core

// Post-processing of the model's reply before it is written back (spec 7.4).
object ResponseCleaner {

    private val preambleRegex = Regex(
        "^\\s*(вот исправленный текст|исправленный вариант|вот исправленный вариант|исправленный текст)\\s*:?\\s*",
        RegexOption.IGNORE_CASE,
    )

    // Returns the cleaned text, or null when the reply must be considered
    // corrupted (last line of defense against the model eating a paragraph).
    fun clean(raw: String, original: String): String? {
        var text = raw.trim()

        // Markdown fences around the whole reply.
        if (text.startsWith("```") && text.endsWith("```") && text.length > 6) {
            text = text.removePrefix("```").removeSuffix("```")
            // possible language tag on the first line
            text = text.substringAfter('\n', text).trim()
        }

        // Typical preambles.
        text = text.replace(preambleRegex, "").trim()

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
        val origLen = original.trim().length
        if (text.length < origLen / 2 || text.length > origLen * 2) return null
        return text
    }
}
