package ru.zf.slushalka.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.Picture

/** Кусок страницы: либо часть абзаца, либо картинка. */
data class PagePiece(val text: String, val picture: Picture?, val start: Int = 0) {
    val end get() = start + text.length + 1
}

/** Готовая страница: с какого знака книги начинается и что на ней стоит. */
data class Page(val startChar: Int, val pieces: List<PagePiece>)

/**
 * Разбивка текста на страницы для режима листания.
 *
 * Настоящая разбивка, а не «прокрутка на высоту экрана»: абзац меряется в той
 * же ширине и тем же шрифтом, каким будет нарисован, и режется **по строкам**,
 * поэтому строка никогда не оказывается разрезанной краем страницы пополам.
 *
 * Считается не вся книга, а окно вокруг текущего места: у романа страниц под
 * тысячу, и мерить их все ради одного разворота незачем.
 */
object Paginator {

    fun paginate(
        blocks: List<Block>,
        range: IntRange,
        measurer: TextMeasurer,
        style: TextStyle,
        headingStyle: TextStyle,
        isHeading: (Block) -> Boolean,
        widthPx: Int,
        heightPx: Int,
        gapPx: Int,
    ): List<Page> {
        if (widthPx <= 0 || heightPx <= 0) return emptyList()
        val pages = ArrayList<Page>()
        var pieces = ArrayList<PagePiece>()
        var pageStart = -1
        var used = 0

        fun flush() {
            if (pieces.isNotEmpty()) {
                pages.add(Page(if (pageStart >= 0) pageStart else 0, pieces))
                pieces = ArrayList()
            }
            used = 0
            pageStart = -1
        }

        for (i in range) {
            val block = blocks.getOrNull(i) ?: continue
            if (block.picture != null) {
                // Картинке - своя страница: так она видна целиком, и не надо
                // гадать, влезет ли она в остаток текущей.
                flush()
                pages.add(Page(block.start, listOf(PagePiece("", block.picture, block.start))))
                continue
            }

            var text = block.text
            var base = block.start
            val st = if (isHeading(block)) headingStyle else style

            while (text.isNotEmpty()) {
                val remaining = heightPx - used
                val layout = measurer.measure(
                    AnnotatedString(text),
                    st,
                    constraints = Constraints(maxWidth = widthPx),
                )
                if (layout.size.height <= remaining) {
                    if (pageStart < 0) pageStart = base
                    pieces.add(PagePiece(text, null, base))
                    used += layout.size.height + gapPx
                    break
                }
                var last = -1
                for (line in 0 until layout.lineCount) {
                    if (layout.getLineBottom(line) <= remaining) last = line else break
                }
                if (last < 0) {
                    // Ни одной строки не влезло. На пустой странице это значит,
                    // что строка выше страницы - тогда ставим её силой, иначе
                    // разбивка зациклится.
                    if (pieces.isEmpty() && used == 0) last = 0 else {
                        flush()
                        continue
                    }
                }
                val end = layout.getLineEnd(last, visibleEnd = true).coerceIn(1, text.length)
                if (pageStart < 0) pageStart = base
                pieces.add(PagePiece(text.substring(0, end), null, base))
                flush()
                val rest = text.substring(end)
                val trimmed = rest.trimStart()
                base += end + (rest.length - trimmed.length)
                text = trimmed
            }
        }
        flush()
        return pages
    }

    /** Номер страницы, на которой лежит это место книги. */
    fun indexOf(pages: List<Page>, charOffset: Int): Int {
        val i = pages.indexOfLast { it.startChar <= charOffset }
        return if (i >= 0) i else 0
    }
}
