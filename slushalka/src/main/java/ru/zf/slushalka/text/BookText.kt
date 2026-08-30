package ru.zf.slushalka.text

import org.json.JSONArray
import org.json.JSONObject

/** Глава: заголовок и границы в общем тексте книги. */
data class Chapter(val title: String, val start: Int, val end: Int) {
    val length get() = end - start
}

/**
 * Картинка книги на своём месте в тексте. [ref] - как она называлась внутри
 * файла (id для fb2, путь в архиве для epub), [file] - куда её положили.
 */
data class Picture(
    val charOffset: Int,
    val ref: String,
    val file: String = "",
    /** Название из самого файла (title/alt). Если его нет - берём строку под картинкой. */
    val caption: String = "",
)

/** Кусок читалки: либо абзац, либо картинка. */
data class Block(val start: Int, val text: String, val picture: Picture?) {
    val end get() = start + text.length + 1
}

/**
 * Книга в виде одной строки плюс разметка глав и картинок. Позиция в аудио
 * превращается в смещение в этой строке (см. Alignment), из него режется кусок
 * для вопроса и от него же отсчитывается страница в читалке.
 */
class BookText(
    val plain: String,
    val chapters: List<Chapter>,
    val title: String = "",
    val author: String = "",
    val pictures: List<Picture> = emptyList(),
) {
    val length get() = plain.length

    /** Страниц в книге по машинописной мерке: 1800 знаков. */
    val pages get() = (length / PAGE_CHARS + 1).coerceAtLeast(1)

    fun pageOf(offset: Int) = (offset / PAGE_CHARS + 1).coerceIn(1, pages)

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

    /**
     * Абзацы и картинки подряд - то, что листает читалка. Считается один раз
     * на книгу: у романа таких кусков тысячи, и делать это на каждом кадре
     * было бы расточительно.
     */
    val blocks: List<Block> by lazy(LazyThreadSafetyMode.NONE) {
        val out = ArrayList<Block>(plain.length / 120 + 16)
        val byOffset = pictures.groupBy { it.charOffset }
        var offset = 0
        for (line in plain.split('\n')) {
            val here = byOffset[offset].orEmpty()
            var lineIsCaption = false
            for ((k, pic) in here.withIndex()) {
                val own = pic.caption.trim()
                val under = line.trim()
                // Подпись: своя из файла, а если её нет - строка под картинкой.
                // Длинная строка - это уже проза, её не забираем.
                val takeLine = own.isBlank() && k == here.lastIndex &&
                    under.isNotEmpty() && under.length <= CAPTION_MAX
                if (takeLine) lineIsCaption = true
                out.add(
                    Block(offset, "", pic.copy(caption = if (own.isNotBlank()) own else if (takeLine) under else ""))
                )
            }
            if (!lineIsCaption && line.isNotBlank()) out.add(Block(offset, line, null))
            offset += line.length + 1
        }
        // Картинки, попавшие за последний абзац (в конце книги).
        pictures.filter { it.charOffset >= offset }.forEach { out.add(Block(offset, "", it)) }
        out
    }

    /** Картинки с уже разобранными подписями - для галереи и для плеера. */
    val picturesWithCaptions: List<Picture> by lazy(LazyThreadSafetyMode.NONE) {
        blocks.mapNotNull { it.picture }
    }

    /**
     * Какую картинку показывать в этом месте книги. Картинка становится
     * текущей **за страницу** до того, как до неё дойдёт текст: к тому времени,
     * как о ней зайдёт речь, она уже перед глазами.
     */
    fun pictureAt(offset: Int): Picture? =
        picturesWithCaptions.lastOrNull { it.charOffset <= offset + PAGE_CHARS }

    /** Номер блока, в котором лежит это место текста. */
    fun blockIndexAt(offset: Int): Int {
        val i = blocks.indexOfLast { it.start <= offset }
        return if (i >= 0) i else 0
    }

    fun metaJson(): JSONObject = JSONObject()
        .put("v", CACHE_VERSION)
        .put("title", title)
        .put("author", author)
        .put("chapters", JSONArray().apply {
            chapters.forEach {
                put(JSONObject().put("t", it.title).put("s", it.start).put("e", it.end))
            }
        })
        .put("pictures", JSONArray().apply {
            pictures.forEach {
                put(
                    JSONObject().put("c", it.charOffset).put("r", it.ref)
                        .put("f", it.file).put("cap", it.caption)
                )
            }
        })

    companion object {
        /** Знаков в «странице»: стандартная машинописная - 1800. */
        const val PAGE_CHARS = 1800

        /**
         * Версия разбора. Растёт, когда из книги начинают доставать что-то
         * новое: иначе уже разобранная книга так и осталась бы без этого -
         * готовый кэш никто бы не перечитал. Версия 2 - картинки, версия 3 -
         * они же, но найденные по-настоящему (см. Fb2Parser.hrefOf).
         */
        const val CACHE_VERSION = 5

        /** Длиннее этого строка под картинкой - уже проза, а не подпись. */
        const val CAPTION_MAX = 140

        fun fromMeta(plain: String, meta: JSONObject): BookText {
            val arr = meta.optJSONArray("chapters") ?: JSONArray()
            val pics = meta.optJSONArray("pictures") ?: JSONArray()
            return BookText(
                plain = plain,
                chapters = (0 until arr.length()).map {
                    val o = arr.getJSONObject(it)
                    Chapter(o.optString("t"), o.optInt("s"), o.optInt("e"))
                },
                title = meta.optString("title"),
                author = meta.optString("author"),
                pictures = (0 until pics.length()).map {
                    val o = pics.getJSONObject(it)
                    Picture(o.optInt("c"), o.optString("r"), o.optString("f"), o.optString("cap"))
                },
            )
        }

        /** Собирает текст и главы из накопленных кусков. */
        fun build(
            body: StringBuilder,
            marks: List<Pair<String, Int>>,
            title: String,
            author: String,
            pictures: List<Picture> = emptyList(),
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
                pictures = pictures.filter { it.charOffset <= plain.length },
            )
        }
    }
}

/**
 * Что разбор увидел в файле. Нужен, когда картинок нет: по этим числам сразу
 * видно, чего именно не хватило - ссылок в тексте, самих картинок в файле или
 * совпадения между ними.
 */
data class ParseReport(
    /** Ссылок на картинки в тексте книги. */
    val refs: Int = 0,
    /** Вложений в файле всего и из них картинок. */
    val blocks: Int = 0,
    val imageBlocks: Int = 0,
    /** Сохранено на диск и сошлось со ссылками. */
    val written: Int = 0,
    val matched: Int = 0,
    val sample: String = "",
) {
    fun line(): String = buildString {
        append("ссылок в тексте: ").append(refs)
        append(", вложений: ").append(blocks)
        append(" (картинок ").append(imageBlocks).append(")")
        append(", сохранено: ").append(written)
        append(", сошлось: ").append(matched)
        if (sample.isNotBlank()) append(". ").append(sample)
    }

    fun toJson(): JSONObject = JSONObject()
        .put("refs", refs).put("blocks", blocks).put("imageBlocks", imageBlocks)
        .put("written", written).put("matched", matched).put("sample", sample)

    companion object {
        fun fromJson(o: JSONObject?): ParseReport {
            if (o == null) return ParseReport()
            return ParseReport(
                refs = o.optInt("refs"),
                blocks = o.optInt("blocks"),
                imageBlocks = o.optInt("imageBlocks"),
                written = o.optInt("written"),
                matched = o.optInt("matched"),
                sample = o.optString("sample"),
            )
        }
    }
}

/** Что удалось достать из файла книги: текст, обложка и картинки. */
class ParsedBook(
    val text: BookText,
    val cover: ByteArray?,
    val report: ParseReport = ParseReport(),
)
