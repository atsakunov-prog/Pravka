package ru.zf.slushalka.text

import org.json.JSONArray
import org.json.JSONObject

/** Глава: заголовок и границы в общем тексте книги. */
data class Chapter(val title: String, val start: Int, val end: Int) {
    val length get() = end - start
}

/**
 * Книга в виде одной строки плюс разметка глав. Позиция в аудио превращается в
 * смещение в этой строке (см. Alignment), из него режется кусок для вопроса.
 */
class BookText(
    val plain: String,
    val chapters: List<Chapter>,
    val title: String = "",
    val author: String = "",
) {
    val length get() = plain.length

    fun chapterAt(offset: Int): Chapter? =
        chapters.lastOrNull { offset >= it.start } ?: chapters.firstOrNull()

    fun chapterIndexAt(offset: Int): Int =
        chapters.indexOfLast { offset >= it.start }.takeIf { it >= 0 } ?: 0

    /** Кусок текста, подрезанный по границам абзацев - модели читать удобнее. */
    fun slice(from: Int, to: Int, snapToParagraph: Boolean = true): String {
        val a = from.coerceIn(0, plain.length)
        val b = to.coerceIn(a, plain.length)
        if (!snapToParagraph) return plain.substring(a, b)
        var start = a
        if (start > 0) {
            val nl = plain.indexOf('\n', start)
            if (nl in start..(start + 400)) start = nl + 1
        }
        var end = b
        if (end < plain.length) {
            val nl = plain.lastIndexOf('\n', end)
            if (nl >= start && end - nl <= 400) end = nl
        }
        return plain.substring(start, end.coerceAtLeast(start))
    }

    fun metaJson(): JSONObject = JSONObject()
        .put("title", title)
        .put("author", author)
        .put("chapters", JSONArray().apply {
            chapters.forEach {
                put(JSONObject().put("t", it.title).put("s", it.start).put("e", it.end))
            }
        })

    companion object {
        fun fromMeta(plain: String, meta: JSONObject): BookText {
            val arr = meta.optJSONArray("chapters") ?: JSONArray()
            return BookText(
                plain = plain,
                chapters = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Chapter(o.optString("t"), o.optInt("s"), o.optInt("e"))
                },
                title = meta.optString("title"),
                author = meta.optString("author"),
            )
        }

        /** Собирает текст и главы из накопленных кусков. */
        fun build(
            body: StringBuilder,
            marks: List<Pair<String, Int>>,
            title: String,
            author: String,
        ): BookText {
            val plain = body.toString()
            val chapters = marks.mapIndexed { i, (t, start) ->
                val end = if (i == marks.lastIndex) plain.length else marks[i + 1].second
                Chapter(t, start.coerceAtMost(plain.length), end.coerceAtMost(plain.length))
            }.filter { it.end > it.start }
            return BookText(
                plain = plain,
                chapters = chapters.ifEmpty { listOf(Chapter("Книга", 0, plain.length)) },
                title = title,
                author = author,
            )
        }
    }
}

/** Что удалось достать из файла книги: текст и, если повезло, обложка. */
class ParsedBook(val text: BookText, val cover: ByteArray?)
