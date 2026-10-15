package ru.zf.pravka.core

/**
 * «Куда что» — короткая страница для владельца (захват, шаг 0 петли, 15.10.2026; договор, п. 6): что говорить, куда
 * ляжет, когда вернётся, что нажать, если не то. Текст один на веб и телефон — `server/contract/prompts/kuda-chto.md`
 * (в APK — ресурсом рядом с промптами разбора); веб берёт его видом `kuda`. Здесь — разбор маленького Markdown:
 * заголовки, абзацы, таблица, **жирный**. Таблица на узком экране — карточками: строка — фраза и её «куда / когда /
 * не то» по заголовкам столбцов.
 */
object KudaChto {

    const val FILE = "kuda-chto.md"

    sealed class Block {
        data class Head(val text: String) : Block()
        data class Para(val text: String) : Block()
        /** Строка таблицы: [cells] — по столбцам, [names] — заголовки столбцов (для подписи «куда ляжет: …»). */
        data class Row(val cells: List<String>, val names: List<String>) : Block()
    }

    fun blocks(md: String): List<Block> {
        val out = ArrayList<Block>()
        val lines = md.replace("\r", "").split('\n')
        var i = 0
        fun cells(r: String) = r.trim().removePrefix("|").removeSuffix("|").split('|').map { it.trim() }
        while (i < lines.size) {
            val l = lines[i]
            when {
                l.isBlank() -> i++
                l.startsWith("#") -> { out += Block.Head(l.trimStart('#').trim()); i++ }
                l.trimStart().startsWith("|") -> {
                    val rows = ArrayList<String>()
                    while (i < lines.size && lines[i].trimStart().startsWith("|")) { rows += lines[i]; i++ }
                    val body = rows.filter { !Regex("^\\|\\s*:?-").containsMatchIn(it.trim()) }.map(::cells)
                    val names = body.firstOrNull().orEmpty()
                    for (r in body.drop(1)) out += Block.Row(r, names)
                }
                else -> {
                    val sb = StringBuilder(l.trim())
                    i++
                    while (i < lines.size && lines[i].isNotBlank() && !lines[i].startsWith("#") && !lines[i].trimStart().startsWith("|")) {
                        sb.append(' ').append(lines[i].trim())
                        i++
                    }
                    out += Block.Para(sb.toString())
                }
            }
        }
        return out
    }

    /** Куски текста: (слова, жирные ли) — `**…**` жирным. */
    fun spans(text: String): List<Pair<String, Boolean>> {
        val out = ArrayList<Pair<String, Boolean>>()
        var last = 0
        for (m in Regex("\\*\\*([^*]+)\\*\\*").findAll(text)) {
            if (m.range.first > last) out += text.substring(last, m.range.first) to false
            out += m.groupValues[1] to true
            last = m.range.last + 1
        }
        if (last < text.length) out += text.substring(last) to false
        return out
    }
}
