package ru.zf.slushalka.text

// Разбор HTML внутри epub делается регулярками, а не XML-парсером: в живых
// книгах попадаются необъявленные сущности и незакрытые теги, на которых
// строгий парсер падает целиком, а нам нужен текст, пусть и не идеальный.
object TextExtract {

    private val DROP_BLOCKS = Regex("(?is)<(script|style|head)[^>]*>.*?</\\1>")
    private val BREAKS = Regex("(?i)</(p|div|h[1-6]|li|tr|blockquote)>|<br\\s*/?>")
    private val TAGS = Regex("<[^>]*>")
    private val MANY_NL = Regex("\n{3,}")
    private val SPACES = Regex("[ \t ]{2,}")

    fun stripHtml(html: String): String {
        var s = html
        s = DROP_BLOCKS.replace(s, " ")
        s = BREAKS.replace(s, "\n")
        s = TAGS.replace(s, "")
        s = decodeEntities(s)
        s = s.replace("\r\n", "\n").replace('\r', '\n')
        s = s.lines().joinToString("\n") { it.trim() }
        s = MANY_NL.replace(s, "\n\n")
        return SPACES.replace(s, " ").trim()
    }

    fun decodeEntities(s: String): String {
        if ('&' !in s) return s
        val sb = StringBuilder(s.length)
        var i = 0
        while (i < s.length) {
            val c = s[i]
            if (c != '&') {
                sb.append(c); i++; continue
            }
            val semi = s.indexOf(';', i + 1)
            if (semi < 0 || semi - i > 10) {
                sb.append(c); i++; continue
            }
            val name = s.substring(i + 1, semi)
            val replacement = when {
                name.startsWith("#x") || name.startsWith("#X") ->
                    name.drop(2).toIntOrNull(16)?.let { String(Character.toChars(it)) }
                name.startsWith("#") ->
                    name.drop(1).toIntOrNull()?.let { String(Character.toChars(it)) }
                else -> NAMED[name]
            }
            if (replacement == null) {
                sb.append(c); i++
            } else {
                sb.append(replacement); i = semi + 1
            }
        }
        return sb.toString()
    }

    private val NAMED = mapOf(
        "amp" to "&", "lt" to "<", "gt" to ">", "quot" to "\"", "apos" to "'",
        "nbsp" to " ", "mdash" to "—", "ndash" to "–", "laquo" to "«", "raquo" to "»",
        "hellip" to "…", "rsquo" to "’", "lsquo" to "‘", "ldquo" to "“", "rdquo" to "”",
        "shy" to "", "copy" to "©", "deg" to "°", "middot" to "·", "bull" to "•",
    )

    /** Заголовок главы из первого <h1>..<h3>, иначе из <title>. */
    fun firstHeading(html: String): String? {
        Regex("(?is)<h[1-3][^>]*>(.*?)</h[1-3]>").find(html)?.let {
            val t = stripHtml(it.groupValues[1]).lines().firstOrNull()?.trim()
            if (!t.isNullOrBlank()) return t
        }
        Regex("(?is)<title[^>]*>(.*?)</title>").find(html)?.let {
            val t = decodeEntities(it.groupValues[1]).trim()
            if (t.isNotBlank()) return t
        }
        return null
    }

    /**
     * Картинки внутри epub-главы.
     *
     * Теги вычищаются регулярками, поэтому `<img>` сначала подменяется
     * маркером из символа, которого в книгах не бывает, а уже после вычистки
     * маркеры снимаются - и их места в готовом тексте становятся местами
     * картинок.
     */
    private const val MARK = '\u0001'
    private const val SEP = '\u0002'

    private val IMG = Regex("(?is)<(img|image)\\s[^>]*>")

    fun markImages(html: String): String = IMG.replace(html) { m ->
        val attrs = m.value
        val src = Regex("(?i)(?:xlink:href|href|src)\\s*=\\s*[\"']([^\"']+)[\"']")
            .find(attrs)?.groupValues?.get(1)
        val caption = Regex("(?i)(?:alt|title)\\s*=\\s*[\"']([^\"']*)[\"']")
            .find(attrs)?.groupValues?.get(1).orEmpty()
        if (src.isNullOrBlank()) "" else "$MARK$src$SEP$caption$MARK"
    }

    /** Снимает маркеры и отдаёт чистый текст вместе с местами картинок. */
    fun takeMarks(text: String): Pair<String, List<Pair<Int, String>>> {
        if (MARK !in text) return text to emptyList()
        val sb = StringBuilder(text.length)
        val marks = ArrayList<Pair<Int, String>>()
        var i = 0
        while (i < text.length) {
            val c = text[i]
            if (c != MARK) {
                sb.append(c)
                i++
                continue
            }
            val end = text.indexOf(MARK, i + 1)
            if (end < 0) {
                i++
                continue
            }
            marks.add(sb.length to text.substring(i + 1, end))
            i = end + 1
        }
        return sb.toString() to marks
    }
}
