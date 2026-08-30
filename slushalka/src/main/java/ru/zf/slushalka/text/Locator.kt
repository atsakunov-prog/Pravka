package ru.zf.slushalka.text

/**
 * Где в книге прозвучала эта фраза.
 *
 * Расшифровка с распознавателя всегда кривовата: пропущенные слова, чужие
 * окончания, «Эраст» вместо «Эрасте». Поэтому ищем не строкой, а голосованием
 * по парам соседних слов: каждая пара, найденная в тексте, голосует за место,
 * с которого фраза могла начаться. Правильное место набирает голосов заметно
 * больше всех остальных - а если не набирает, честнее вернуть «не нашёл», чем
 * увести читателя не туда.
 *
 * Ищем **в окне** вокруг предполагаемого места, а не по всей книге: пять-семь
 * обычных слов встречаются в романе десятки раз, и поиск по всей книге увёл бы
 * в чужую главу с полной уверенностью в себе.
 */
object Locator {

    data class Hit(val charOffset: Int, val votes: Int, val confidence: Double)

    /** Окно поиска: примерно ±17 страниц, это больше любой разумной ошибки карты. */
    const val DEFAULT_RADIUS = 30_000

    fun find(
        text: BookText,
        transcript: String,
        aroundChar: Int,
        radius: Int = DEFAULT_RADIUS,
    ): Hit? {
        val needle = stems(transcript)
        if (needle.size < MIN_WORDS) return null

        val from = (aroundChar - radius).coerceIn(0, text.length)
        val to = (aroundChar + radius).coerceIn(from, text.length)
        if (to - from < 200) return null
        val window = text.plain.substring(from, to)
        val words = tokenize(window)
        if (words.size < needle.size + 2) return null

        // Индекс пар соседних слов окна: пара -> где встречается.
        val index = HashMap<Long, MutableList<Int>>(words.size * 2)
        for (i in 0 until words.size - 1) {
            index.getOrPut(pair(words[i].stem, words[i + 1].stem)) { ArrayList(2) }.add(i)
        }

        val votes = HashMap<Int, Int>()
        var pairs = 0
        for (j in 0 until needle.size - 1) {
            val occurrences = index[pair(needle[j], needle[j + 1])] ?: continue
            // Пара, встречающаяся в окне слишком часто («и он», «в этом»),
            // ничего не различает - только шумит.
            if (occurrences.size > MAX_OCCURRENCES) continue
            pairs++
            for (i in occurrences) {
                val start = i - j
                if (start >= 0) votes[start] = (votes[start] ?: 0) + 1
            }
        }
        if (pairs < MIN_WORDS - 1) return null

        var bestStart = -1
        var best = 0
        for ((start, v) in votes) if (v > best) {
            best = v
            bestStart = start
        }
        if (bestStart < 0) return null
        // Второй по силе - не сосед победителя (соседи это тот же самый ответ,
        // сдвинутый на слово из-за пропущенного в расшифровке).
        var second = 0
        for ((start, v) in votes) {
            if (kotlin.math.abs(start - bestStart) <= NEIGHBOUR) continue
            if (v > second) second = v
        }

        val needed = maxOf(MIN_VOTES, (pairs * MIN_SHARE).toInt())
        if (best < needed) return null
        if (second > 0 && best < second * MIN_MARGIN) return null

        // Место - там, где фраза кончилась: слушатель уже дошёл до этого слова.
        val endWord = (bestStart + needle.size).coerceIn(0, words.size - 1)
        val offset = from + words[endWord].offset
        return Hit(
            charOffset = offset.coerceIn(0, text.length),
            votes = best,
            confidence = best.toDouble() / pairs,
        )
    }

    // ------------------------------------------------------------- разбор слов

    private class Word(val stem: String, val offset: Int)

    private fun tokenize(s: String): List<Word> {
        val out = ArrayList<Word>(s.length / 6 + 8)
        var i = 0
        while (i < s.length) {
            if (!s[i].isLetterOrDigit()) {
                i++
                continue
            }
            val start = i
            while (i < s.length && s[i].isLetterOrDigit()) i++
            out.add(Word(stem(s.substring(start, i)), start))
        }
        return out
    }

    private fun stems(s: String): List<String> = tokenize(s).map { it.stem }

    /**
     * Огрубление вместо настоящей морфологии: у русского слова окончание
     * меняется, а первые пять букв - почти никогда.
     */
    private fun stem(word: String): String {
        val lower = word.lowercase().replace('ё', 'е')
        return if (lower.length <= 4) lower else lower.substring(0, 5)
    }

    private fun pair(a: String, b: String): Long =
        (a.hashCode().toLong() shl 32) xor (b.hashCode().toLong() and 0xFFFFFFFFL)

    private const val MIN_WORDS = 5
    private const val MIN_VOTES = 3
    private const val MIN_SHARE = 0.30
    private const val MIN_MARGIN = 1.6
    private const val MAX_OCCURRENCES = 12
    private const val NEIGHBOUR = 5
}
