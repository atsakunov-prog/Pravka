package ru.zf.slushalka.ui

import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.Constraints
import ru.zf.slushalka.text.Block
import ru.zf.slushalka.text.Picture

/**
 * Кусок страницы: либо часть абзаца, либо картинка.
 *
 * [head] - кусок начинает абзац (а не продолжает его с прошлой страницы):
 * абзацный отступ ставится только ему. [heading] - это заголовок главы, и
 * рисовать его надо заголовочным стилем, тем же, каким мерили.
 */
data class PagePiece(
    val text: String,
    val picture: Picture?,
    val start: Int = 0,
    val head: Boolean = true,
    val heading: Boolean = false,
) {
    val end get() = start + text.length + 1
}

/**
 * Готовая страница: с какого знака книги начинается и что на ней стоит.
 *
 * [slack] - сколько места осталось незанятым. Обычно это доли строки, но
 * перед заголовком главы, который на страницу не влез, остаётся дыра в
 * несколько строк. В типографии её не оставляют: воздух у заголовка тянется,
 * и полоса всё равно кончается на своей линии. Этим и занимается страница
 * при рисовании.
 */
data class Page(val startChar: Int, val pieces: List<PagePiece>, val slack: Int = 0)

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
        /** Стиль продолжения абзаца на новой странице: без абзацного отступа. */
        contStyle: TextStyle,
        headingStyle: TextStyle,
        isHeading: (Block) -> Boolean,
        /** Первый абзац главы: набирается без абзацного отступа. */
        noIndent: (Int) -> Boolean = { false },
        /** Не оставлять одну строку абзаца внизу или вверху страницы. */
        /**
         * Как кусок будет выглядеть на странице. Нужна из-за капители: первые
         * слова главы набираются прописными, а они шире строчных, и мерить
         * простой строкой значило бы промахнуться на строку.
         */
        annotate: (text: String, opens: Boolean) -> AnnotatedString = { t, _ -> AnnotatedString(t) },
        widthPx: Int,
        heightPx: Int,
        gapPx: Int,
        /** Воздух над заголовком главы (кроме верха страницы) и под ним. */
        headingTopPx: Int = 0,
        headingGapPx: Int = gapPx,
        /**
         * Высота строки. Всё, что не строка текста - заголовок с воздухом
         * вокруг, - округляется до целого их числа: иначе текст под
         * заголовком съезжает с сетки, и низ полос на развороте расходится.
         */
        lineHeightPx: Int = 0,
    ): List<Page> {
        if (widthPx <= 0 || heightPx <= 0) return emptyList()
        val pages = ArrayList<Page>()
        var pieces = ArrayList<PagePiece>()
        var pageStart = -1
        var used = 0

        fun flush() {
            if (pieces.isNotEmpty()) {
                // Остаток места отдаётся странице: она растянет им воздух у
                // заголовка, чтобы низ полосы не уезжал вверх.
                val slack = (heightPx - used).coerceAtLeast(0)
                pages.add(Page(if (pageStart >= 0) pageStart else 0, pieces, slack))
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
            val heading = isHeading(block)
            if (heading && used > 0) used += headingTopPx

            while (text.isNotEmpty()) {
                val head = base == block.start
                // Продолжение абзаца меряется без отступа первой строки: он
                // есть только у настоящего начала, иначе разбивка и рисунок
                // разошлись бы на ширину отступа.
                val opens = head && noIndent(block.start)
                val st = if (heading) headingStyle else if (head && !opens) style else contStyle
                val remaining = heightPx - used
                val layout = measurer.measure(
                    annotate(text, opens),
                    st,
                    constraints = Constraints(maxWidth = widthPx),
                )
                if (layout.size.height <= remaining) {
                    if (pageStart < 0) pageStart = base
                    pieces.add(PagePiece(text, null, base, head, heading))
                    used += layout.size.height + if (heading) headingGapPx else gapPx
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
                // Отрезанный кусок перемеряется, и если он вырос - строка
                // снимается ещё раз.
                //
                // Причина не в округлении: с переносами абзац разбивается
                // «оптимально», то есть по всему абзацу сразу (LineBreak.
                // Paragraph). Кусок, отрезанный по границе строки, - это уже
                // другой абзац, и он раскладывается заново, иногда в лишнюю
                // строку. На живой сборке она и срезалась нижним краем.
                var cut = last
                var end = wordEnd(text, layout.getLineEnd(cut, visibleEnd = true))
                while (cut > 0) {
                    val fit = measurer.measure(
                        annotate(text.substring(0, end), opens),
                        st,
                        constraints = Constraints(maxWidth = widthPx),
                    )
                    if (fit.size.height <= remaining) break
                    cut--
                    end = wordEnd(text, layout.getLineEnd(cut, visibleEnd = true))
                }
                if (pageStart < 0) pageStart = base
                pieces.add(PagePiece(text.substring(0, end), null, base, head, heading))
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

    /**
     * Граница куска - по слову, а не по строке.
     *
     * Строка с переносом кончается посреди слова, и если резать страницу
     * ровно там, слово разваливается: на живой сборке владелец увидел внизу
     * «на служб близ», а «ости» уехало на следующую страницу - без дефиса и
     * без смысла. Дефис принадлежит строке, а не тексту, поэтому переносить
     * приходится слово целиком.
     */
    private fun wordEnd(text: String, lineEnd: Int): Int {
        val end = lineEnd.coerceIn(1, text.length)
        if (end >= text.length) return end
        if (!text[end - 1].isLetterOrDigit() || !text[end].isLetterOrDigit()) return end
        // Режем посреди слова - отходим назад до его начала.
        var i = end - 1
        while (i > 0 && text[i - 1].isLetterOrDigit()) i--
        return if (i <= 0) end else i
    }

    /** Номер страницы, на которой лежит это место книги. */
    fun indexOf(pages: List<Page>, charOffset: Int): Int {
        val i = pages.indexOfLast { it.startChar <= charOffset }
        return if (i >= 0) i else 0
    }
}
