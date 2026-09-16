package ru.zf.pravka.core

// Правка владельца руками → словарь без модели.
//
// Владелец (15.09.2026): «должны добавляться правки, если я беру и правлю тот
// текст, который он уже прислал в текстбокс». Раньше это делал Опус батчем по
// расписанию — и слежка за полями, и расписание сняты. Но самый частый случай
// правки — одно слово вместо другого («Стаффджет» вместо «стаф джет»), и для
// него модель не нужна: достаточно сравнить, что мы прислали, с тем, что стало,
// и увидеть ровно одну замену. Всё сложнее (переставил фразы, дописал абзац)
// остаётся кнопке «Разобрать сейчас».
object EditDiff {

    /**
     * Единственная замена: [from] — слова, которые были у нас, [to] — на что
     * владелец их поправил. [similar] — похожи ли строки по буквам: похожие —
     * ошибка распознавания (замена всегда, HARD), непохожие — предпочтение
     * слова (подсказка модели, HINT): «Стафджет → Стаффджет» и «сделать →
     * выполнить» — разные случаи, и жёсткая замена во втором испортила бы
     * следующий текст. [inflection] — то же слово в другой форме («Папа →
     * Пап», «следующее → следующая»): по буквам похоже, но это правка под
     * контекст, а не ослышка. Первые сутки захвата (15–16.09.2026) такие
     * правки уезжали в HARD и переписывали каждое «папа» в «пап» во всех
     * текстах; теперь они не идут в словарь без модели — только в очередь
     * «Разобрать сейчас», где Опус видит контекст.
     */
    data class Substitution(val from: String, val to: String, val similar: Boolean, val inflection: Boolean = false)

    private const val MAX_SIDE = 3

    /**
     * Токен: слово без обрамляющей пунктуации; ключ сравнения — в нижнем
     * регистре; [endsSentence] — за словом стояла точка, «!», «?» или
     * многоточие: по ней отделяется чужой хвост сообщения от замены.
     */
    private data class Tok(val text: String, val key: String, val endsSentence: Boolean)

    private fun tokens(s: String): List<Tok> =
        s.split(Regex("\\s+"))
            .filter { it.isNotBlank() }
            .mapNotNull { raw ->
                val text = raw.trim { !it.isLetterOrDigit() }
                if (text.isEmpty()) null
                else Tok(text, text.lowercase(), raw.trimEnd().let { it.endsWith('.') || it.endsWith('!') || it.endsWith('?') || it.endsWith('…') })
            }

    private class Hunk(val del: MutableList<Tok>, val ins: MutableList<Tok>, val afterEqual: Boolean)

    /**
     * [before] — текст, который прислали мы; [after] — поле сейчас. Поле может
     * держать и другой текст вокруг нашего (голову и хвост сообщения): вставки
     * в самом начале и в самом конце — контекст, не правка; хвост, прилипший к
     * замене последнего слова, отрезается по концу предложения. Возвращает
     * замену только когда внутри ровно одна и обе стороны — от одного до трёх
     * слов, причём хотя бы одна сторона — одно слово (склейка или разбиение:
     * «стаф джет» → «Стаффджет»); две соседние замены разных слов — не
     * словарный случай.
     */
    fun singleSubstitution(before: String, after: String): Substitution? {
        val a = tokens(before)
        val b = tokens(after)
        if (a.isEmpty() || b.isEmpty()) return null
        if (a.size > 600 || b.size > 600) return null

        // LCS по ключам — общая подпоследовательность слов.
        val n = a.size
        val m = b.size
        val dp = Array(n + 1) { IntArray(m + 1) }
        for (i in n - 1 downTo 0) {
            for (j in m - 1 downTo 0) {
                dp[i][j] = if (a[i].key == b[j].key) dp[i + 1][j + 1] + 1
                else maxOf(dp[i + 1][j], dp[i][j + 1])
            }
        }
        // Проход по выравниванию: куски несовпадений между общими словами.
        val hunks = ArrayList<Hunk>()
        var equal = 0
        var i = 0
        var j = 0
        var cur: Hunk? = null
        var seenEqual = false
        while (i < n || j < m) {
            if (i < n && j < m && a[i].key == b[j].key) {
                cur = null
                equal++
                seenEqual = true
                i++; j++
            } else {
                if (cur == null) { cur = Hunk(ArrayList(), ArrayList(), seenEqual); hunks.add(cur) }
                if (j < m && (i >= n || dp[i][j + 1] >= dp[i + 1][j])) { cur.ins.add(b[j]); j++ }
                else { cur.del.add(a[i]); i++ }
            }
        }
        if (equal < 2 || hunks.isEmpty()) return null

        // Голова: чистая вставка до первого общего слова — чужой текст; если в
        // голове есть и удаление, чужое — всё до последнего конца предложения
        // среди вставленных слов (кроме самого последнего).
        val first = hunks.first()
        if (!first.afterEqual && first.ins.isNotEmpty()) {
            val cut = (0 until first.ins.size - 1).lastOrNull { first.ins[it].endsSentence }
            if (first.del.isEmpty()) first.ins.clear()
            else if (cut != null) repeat(cut + 1) { first.ins.removeAt(0) }
        }
        // Хвост: чистая вставка после последнего общего слова — чужой текст; у
        // замены последнего слова хвост отрезается по первому концу предложения.
        val last = hunks.last()
        if (last.ins.isNotEmpty()) {
            if (last.del.isEmpty()) last.ins.clear()
            else {
                val cut = last.ins.indexOfFirst { it.endsSentence }
                if (cut in 0 until last.ins.size - 1) {
                    while (last.ins.size > cut + 1) last.ins.removeAt(last.ins.size - 1)
                }
            }
        }
        val inner = hunks.filter { it.del.isNotEmpty() || it.ins.isNotEmpty() }
        if (inner.size != 1) return null
        val h = inner.single()
        if (h.del.isEmpty() || h.ins.isEmpty()) return null
        if (h.del.size > MAX_SIDE || h.ins.size > MAX_SIDE) return null
        // Обе стороны длиннее слова — это две правки рядом, а не одна замена,
        // если только не совпали все слова, кроме одного.
        var del = h.del.toList()
        var ins = h.ins.toList()
        if (del.size >= 2 && ins.size >= 2) {
            if (del.size != ins.size) return null
            val diff = del.indices.filter { del[it].key != ins[it].key }
            if (diff.size != 1) return null
            del = listOf(del[diff[0]])
            ins = listOf(ins[diff[0]])
        }
        val from = del.joinToString(" ") { it.text }
        val to = ins.joinToString(" ") { it.text }
        if (from.equals(to, ignoreCase = true)) return null
        return Substitution(
            from, to,
            similar = similarity(from.lowercase(), to.lowercase()) >= 0.5,
            inflection = sameStem(from, to),
        )
    }

    /**
     * Одно слово в другой форме: общая основа от трёх букв, разошлись только
     * хвосты не длиннее трёх букв с каждой стороны. «Папа/Пап», «Рубрика/Рубрик»,
     * «следующее/следующая», «проговорился/проговорил» — да; «Стафджет/Стаффджет»
     * (разошлись в середине) и «lifans/onlyfans» (разные начала) — нет.
     * Словосочетания не рассматриваются: «стаф джет → Стаффджет» — склейка.
     */
    fun sameStem(a: String, b: String): Boolean {
        val x = a.lowercase()
        val y = b.lowercase()
        if (' ' in x || ' ' in y) return false
        var p = 0
        while (p < x.length && p < y.length && x[p] == y[p]) p++
        if (p < 3) return false
        return x.length - p <= 3 && y.length - p <= 3
    }

    /** 1 − расстояние Левенштейна / длина длинной строки. */
    fun similarity(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val n = a.length
        val m = b.length
        var prev = IntArray(m + 1) { it }
        var cur = IntArray(m + 1)
        for (i in 1..n) {
            cur[0] = i
            for (j in 1..m) {
                val cost = if (a[i - 1] == b[j - 1]) 0 else 1
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + cost)
            }
            val t = prev; prev = cur; cur = t
        }
        return 1.0 - prev[m].toDouble() / maxOf(n, m)
    }
}
