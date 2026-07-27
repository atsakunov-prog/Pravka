package ru.zf.pravka.core

// Splits text into model-sized chunks along sentence boundaries (spec 6.2:
// Nano's output should stay under ~256 tokens, so ~120 input tokens ≈ 350
// chars per chunk). Each chunk keeps its original trailing whitespace, so
// concatenating the chunks reproduces the input exactly - paragraph borders
// survive the round trip.
object TextChunker {

    const val DEFAULT_MAX_CHARS = 350

    private val sentenceEnd = Regex("[.!?…]+[\"»)]*")

    fun chunk(text: String, maxChars: Int = DEFAULT_MAX_CHARS): List<String> {
        if (text.length <= maxChars) return listOf(text)

        // Candidate cut positions: after sentence enders (incl. trailing
        // whitespace) and after newlines.
        val cuts = sortedSetOf<Int>()
        for (m in sentenceEnd.findAll(text)) {
            var end = m.range.last + 1
            while (end < text.length && text[end] == ' ') end++
            cuts.add(end)
        }
        var i = text.indexOf('\n')
        while (i >= 0) {
            var end = i + 1
            while (end < text.length && text[end] == '\n') end++
            cuts.add(end)
            i = text.indexOf('\n', end)
        }
        cuts.add(text.length)

        val chunks = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            val limit = start + maxChars
            if (limit >= text.length) {
                chunks.add(text.substring(start))
                break
            }
            // Best sentence/paragraph cut within the window.
            var cut = cuts.floor(limit)?.takeIf { it > start }
            if (cut == null) {
                // No sentence border: cut at the last space in the window,
                // or hard-cut as the last resort.
                val space = text.lastIndexOf(' ', limit)
                cut = if (space > start) space + 1 else limit
            }
            chunks.add(text.substring(start, cut))
            start = cut
        }
        return chunks
    }
}
