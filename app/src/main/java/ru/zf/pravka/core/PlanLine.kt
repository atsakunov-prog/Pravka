package ru.zf.pravka.core

import ru.zf.pravka.data.ExerciseBook

// Одна строка плана из описания события intervals — так, как её пишет
// владелец (или его чат), когда пушит день в календарь:
//
//   «Отжимания 2×6»
//   «Вис ×2 до предела, отдых 1 мин — секунды в заметку»
//   «Осанка: chin tuck ×10 · скольжения по стене ×10 · грудь в проёме 30 сек»
//   «Гоблет-присед 2×6: гиря за рога у груди, локти вниз, 3 сек вниз»
//   «Вместо виса и резинки — тяга резинки к поясу 2×10–12»
//   «10 воздушных приседаний.»
//   «Отжимания 2×6 (стало легко — 2×8)»
//
// Строка раскладывается на НАЗВАНИЕ (по нему и только по нему ищется движение
// в справочнике — стемминг по пояснению превращал «руки на верх руля» в
// суперсет рук), ДОЗУ (то, что начинается с «2×6», «×10», «30 сек», «~3 мин»,
// «10–12») и ПОЯСНЕНИЕ (всё остальное: «отдых 1 мин», «секунды в заметку»).
//
// Раньше строка резалась только по « — » и матчилась целиком: «Гоблет-присед
// 2×6: гиря за рога…» уезжал в заголовок карточки целым абзацем, «Осанка: …»
// не находила упражнение, а «Отжимания 2×6 (стало легко — 2×8)» ломалась на
// тире внутри скобок. Разделители теперь считаются только ВНЕ скобок, а
// сегмент с дозой — и есть задача: сегменты до него — контекст («Вместо виса
// и резинки»), после — пояснение.
//
// Id строки стабилен между вкладкой и досылом в intervals: узнали движение —
// id упражнения, иначе позиция плюс нормализованный текст (плюс суффикс, чтобы
// строки зарядки не путались со строками силовой того же дня).
data class PlanLine(
    val raw: String,
    /** Название, как написано в плане (голова строки без дозы). */
    val name: String,
    val dose: String,
    val note: String,
    val exercise: ExerciseBook.Exercise?,
    val id: String,
) {
    /** Имя как в Notion, если движение узнано; иначе — как написано в плане. */
    val canonical: String get() = exercise?.name ?: name

    /** «Название — доза» одной строкой: заголовок карточки, шапка отчёта. */
    val title: String get() = if (dose.isBlank()) canonical else "$canonical — $dose"

    /** Доза и пояснение вместе — подстрочник под названием. */
    val detail: String
        get() = listOf(dose, note).filter { it.isNotBlank() }.joinToString(" · ")

    companion object {
        // С чего начинается доза. Берётся самое раннее совпадение в строке,
        // стоящее после пробела или открывающей скобки.
        private val DOSE_START = Regex(
            "(?:^|(?<=[\\s(]))(?:" +
                "\\d+\\s*[×xх*]\\s*\\d+(?:[–\\-]\\d+)?(?:\\s*(?:сек|мин|км))?" +   // 2×6, 3x8, 4х4–6, 2×30 сек
                "|[×x]\\s*\\d+" +                                   // ×10, ×2
                "|~\\s*\\d+" +                                      // ~3 мин
                "|\\d+(?:[–\\-]\\d+)?\\s*(?:сек|мин|км|раз|повтор|подход|круг|цикл|м(?=[\\s.,;)]|$))" +
                "|\\d+\\s*[–\\-]\\s*\\d+" +                         // 10–12
                ")"
        )
        // «10 воздушных приседаний» — число впереди это и есть доза.
        private val LEADING_COUNT = Regex("^(\\d+)\\s+(?=\\p{L})")
        // Похоже ли на дозу целиком (для хвоста после «: » и после запятой).
        private val LOOKS_LIKE_DOSE = Regex("[×x]\\s*\\d|\\d\\s*[×xх]\\s*\\d|\\d\\s*(?:сек|мин)(?![\\p{L}])|~\\s*\\d")
        private val STARTS_LIKE_DOSE = Regex("^(?:[×x]\\s*\\d|\\d+\\s*[×xх]\\s*\\d|~?\\d+\\s*(?:сек|мин|[–\\-]\\d))")

        fun parseAll(lines: List<String>, book: ExerciseBook, suffix: String = ""): List<PlanLine> =
            lines.mapIndexed { i, line -> parse(i, line, book, suffix) }

        fun parse(index: Int, line: String, book: ExerciseBook, suffix: String = ""): PlanLine {
            val trimmed = line.trim()
            val parts = splitOutside(trimmed, " — ").map { it.trim() }.filter { it.isNotBlank() }
                .ifEmpty { listOf(trimmed) }

            // Сегмент с дозой — задача. «Название — доза — пояснение» (доза
            // отдельным сегментом без названия) берёт название из сегмента
            // перед собой.
            var k = parts.indexOfFirst { splitDose(splitOutside(it, ": ", 2)[0]).second.isNotBlank() }
            if (k < 0) k = 0
            val colon = splitOutside(parts[k], ": ", 2)
            var (name, dose) = splitDose(colon[0])
            val tail = colon.getOrNull(1).orEmpty().trim()
            val notes = mutableListOf<String>()
            var nameFrom = k
            if (name.isBlank() && k > 0) {
                name = clean(parts[k - 1])
                nameFrom = k - 1
            }
            if (name.isBlank()) name = clean(parts[k])

            for ((i, p) in parts.withIndex()) {
                if (i == k || i == nameFrom) continue
                notes.add(clean(p))
            }
            if (tail.isNotBlank()) {
                if (dose.isBlank() && LOOKS_LIKE_DOSE.containsMatchIn(tail)) dose = clean(tail)
                else notes.add(0, clean(tail))
            }
            // «×2 до предела, отдых 1 мин»: после запятой — уже пояснение, если
            // это не вторая половина дозы («×10 назад, ×10 вперёд»).
            val comma = dose.indexOf(", ")
            if (comma > 0) {
                val rest = dose.substring(comma + 2).trim()
                if (rest.isNotBlank() && !STARTS_LIKE_DOSE.containsMatchIn(rest)) {
                    notes.add(0, clean(rest))
                    dose = clean(dose.substring(0, comma))
                }
            }

            val exercise = book.match(name)
            val id = exercise?.id ?: freeId(index, trimmed, suffix)
            return PlanLine(
                raw = trimmed,
                name = name,
                dose = dose,
                note = notes.filter { it.isNotBlank() }.distinct().joinToString("; "),
                exercise = exercise,
                id = id,
            )
        }

        /** Устойчивый id свободной строки плана: позиция + начало текста. */
        fun freeId(index: Int, line: String, suffix: String = ""): String =
            "task-$index-" + ExerciseBook.normalize(line).replace(' ', '-').take(30) + suffix

        /** Название и доза из головы строки. Дозы нет — вся голова название. */
        fun splitDose(text: String): Pair<String, String> {
            val t = text.trim()
            if (t.isEmpty()) return "" to ""
            val first = DOSE_START.find(t)
            if (first != null && first.range.first == 0) {
                // «2×30 сек растяжка» — доза впереди, название за ней.
                val rest = t.substring(first.range.last + 1)
                val restSplit = splitDose(rest)
                return if (restSplit.second.isBlank()) clean(rest) to clean(first.value)
                else restSplit.first to clean(first.value + " " + restSplit.second)
            }
            LEADING_COUNT.find(t)?.let { m ->
                val rest = t.substring(m.range.last + 1)
                val inner = splitDose(rest)
                return inner.first to (inner.second.ifBlank { "×" + m.groupValues[1] })
            }
            if (first == null) return clean(t) to ""
            return clean(t.substring(0, first.range.first)) to clean(t.substring(first.range.first))
        }

        /**
         * Разрез по разделителю только вне скобок и кавычек: «(стало легко —
         * 2×8)» и «(корпус: кошка-корова ×6)» остаются целыми.
         */
        fun splitOutside(text: String, sep: String, limit: Int = Int.MAX_VALUE): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var start = 0
            var i = 0
            while (i < text.length) {
                val c = text[i]
                when (c) {
                    '(', '[', '«' -> depth++
                    ')', ']', '»' -> if (depth > 0) depth--
                }
                if (depth == 0 && out.size < limit - 1 && text.startsWith(sep, i)) {
                    out.add(text.substring(start, i))
                    i += sep.length
                    start = i
                    continue
                }
                i++
            }
            out.add(text.substring(start))
            return out
        }

        private fun clean(s: String): String {
            val t = s.trim().trim(' ', ',', ':', ';', '·', '—', '-')
            // Точка на конце пункта — пунктуация списка, не часть имени; но
            // «Отдых минута. Задача дня…» — уже текст с предложениями.
            return (if (t.contains(". ")) t else t.removeSuffix(".")).trim()
        }
    }
}
