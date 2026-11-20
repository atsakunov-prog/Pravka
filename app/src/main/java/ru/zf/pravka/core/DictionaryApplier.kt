package ru.zf.pravka.core

import ru.zf.pravka.data.DictionaryStore

// Applies the dictionary to a proofread request (spec 7.2/7.3):
// HARD entries are replaced deterministically before the model sees the
// text; HINT/PROTECT entries that actually occur in the text are collected
// into the {DICT} block. Pre-filtering is mandatory - the full dictionary
// would bloat the prompt and waste money on Claude.
class DictionaryApplier(private val store: DictionaryStore) {

    data class Prepared(
        val text: String,          // input after HARD replacements
        val dictBlock: String,     // assembled {DICT} block, may be empty
        val firedIds: List<Long>,  // entries that fired (HARD applied or HINT/PROTECT matched)
    )

    suspend fun prepare(input: String): Prepared {
        val entries = store.all().filter { it.enabled }
        var text = input
        val fired = mutableListOf<Long>()

        // HARD: exact word-boundary match, case-insensitive, first-letter
        // case preserved (spec 7.2). Longest first so multi-word variants
        // win over their own substrings.
        for (entry in entries.filter { it.mode == DictMode.HARD }.sortedByDescending { it.from.length }) {
            val regex = boundaryRegex(entry.from, withRussianEndings = false) ?: continue
            if (regex.containsMatchIn(text)) {
                text = regex.replace(text) { m -> preserveFirstCase(m.value, entry.to) }
                fired += entry.id
            }
        }

        // HINT/PROTECT: detection tolerates typical Russian case endings
        // (spec section 13: "сейфа" must also catch "сейфам").
        val hints = entries.filter {
            it.mode == DictMode.HINT &&
                boundaryRegex(it.from, withRussianEndings = true)?.containsMatchIn(text) == true
        }
        val protects = entries.filter {
            it.mode == DictMode.PROTECT &&
                boundaryRegex(it.from, withRussianEndings = true)?.containsMatchIn(text) == true
        }
        fired += hints.map { it.id }
        fired += protects.map { it.id }
        val names = peopleNames()

        // Ослышка словаря в другой форме (20.11.2026, владелец: «у меня ебеда
        // просчиталась как ебеда»). Жёсткая замена ловит только записанную
        // форму: в словаре «ебеде» → EBITDA, а GigaAM на компе услышал «ебедой»
        // и «ебеда» — замена промахнулась, подсказки модели не было, в текст
        // ушла «Ебедой». Заменять по основе нельзя (та же беда, что «осу» →
        // «осуди»), подсказать — можно: модель решает по смыслу. Строки в
        // запросе — только на найденные формы, не на весь словарь.
        val loose = looseHints(entries, text, names)

        // Люди из Дел (06.10.2026, docs/svod-phone.md, 3.7): имена, короткие и
        // другие имена — PROTECT на лету, только встреченные в тексте. В словарь
        // (`dict.main`) их не кладём: карточки живут в Делах и меняются там.
        val known = protects.map { it.from.lowercase() }.toHashSet()
        val people = names.asSequence()
            .map { it.trim() }
            .filter { it.length >= 3 && known.add(it.lowercase()) }
            .filter { boundaryRegex(it, withRussianEndings = true)?.containsMatchIn(text) == true }
            .take(MAX_PEOPLE)
            .map { DictEntry(from = it, mode = DictMode.PROTECT, note = "человек из Дел", createdAt = 0L) }
            .toList()

        return Prepared(text, buildDictBlock(hints + loose, protects + people), fired)
    }

    private fun buildDictBlock(hints: List<DictEntry>, protects: List<DictEntry>): String {
        if (hints.isEmpty() && protects.isEmpty()) return ""
        return buildString {
            if (hints.isNotEmpty()) {
                append("Учти при правке:\n")
                for (h in hints) {
                    append("- \"").append(h.from).append("\"")
                    if (h.note.isNotBlank()) append(" (").append(h.note).append(")")
                    if (h.to.isNotBlank()) append(" — это ").append(h.to)
                    append("\n")
                }
            }
            if (protects.isNotEmpty()) {
                // Словарь задаёт написание, не падеж: PROTECT «Марианн» ловил
                // «Марианны» (допуск окончаний) и модель писала «самой Марианн»
                // (лог ночного разбора 17.09.2026, 55 срабатываний).
                append("Эти имена и термины написаны верно — не исправляй их на созвучные; ")
                append("падеж, в котором слово стоит в тексте, сохраняй (словарь задаёт написание, а не форму):\n")
                for (pr in protects) {
                    append("- ").append(pr.from)
                    if (pr.note.isNotBlank()) append(" (").append(pr.note).append(")")
                    append("\n")
                }
            }
        }.trim()
    }

    companion object {
        /**
         * Имена людей Дел для PROTECT — ставит приложение (живые карточки
         * синка). Пусто — Дела не на сервере.
         */
        @Volatile var peopleNames: () -> List<String> = { emptyList() }

        /** Потолок имён в одном запросе: длинная встреча не должна раздувать промпт. */
        private const val MAX_PEOPLE = 20

        /** Потолок строк «ослышка в другой форме» в одном запросе. */
        private const val MAX_LOOSE = 10

        private val VOWEL_TAIL = Regex("[аеёиоуыэюяйьАЕЁИОУЫЭЮЯЙЬ]+$")
        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")

        /** Основа слова: без конечных гласных, «й» и «ь» — «ебеде» → «ебед». */
        internal fun stem(word: String): String = word.trim().replace(VOWEL_TAIL, "")

        /**
         * Ослышки жёстких замен в другой форме — подсказками модели, не заменой.
         * Берутся однословные кириллические ослышки с основой от четырёх букв
         * («осу» не пройдёт), ищутся как основа плюс до трёх букв окончания
         * в тексте ПОСЛЕ жёстких замен (точная форма уже заменена). Не
         * подсказываем, если найденное слово — само верное слово в другом
         * падеже («Марианной» при «мариану» → «Марианну»), слово, которое
         * словарь считает верным, или имя человека из Дел: без этих трёх
         * отсечек прогон по 207 тейкам 6–20.11 давал лишние строки на каждое
         * «Марианной»; с ними — 3 тейка: обе «ебеды» и «Мариан/марианом» с
         * одной «н». Одно найденное
         * слово — одна строка: из записей берётся та, чья ослышка ближе к нему.
         */
        internal fun looseHints(entries: List<DictEntry>, text: String, people: List<String>): List<DictEntry> {
            val good = HashSet<String>()
            for (e in entries) {
                if (!e.enabled) continue
                val w = if (e.mode == DictMode.PROTECT) e.from else e.to
                w.lowercase().split(NOT_WORD).filterTo(good) { it.length >= 3 }
            }
            people.forEach { n -> n.lowercase().split(NOT_WORD).filterTo(good) { it.length >= 3 } }
            val goodStems = good.map { stem(it) }.filter { it.length >= 5 }.toHashSet()

            class Pick(val entry: DictEntry, val form: String, val near: Int)
            val picks = LinkedHashMap<String, Pick>()
            for (e in entries) {
                if (!e.enabled || e.mode != DictMode.HARD || e.to.isBlank()) continue
                val from = e.from.trim()
                if (from.any { it.isWhitespace() } || from.none { it.isCyrillic() }) continue
                val base = stem(from)
                if (base.length < 4) continue
                val toBase = stem(e.to.lowercase())
                val rx = runCatching {
                    Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(base) + "[а-яё]{0,3}(?![\\p{L}\\p{N}])")
                }.getOrNull() ?: continue
                for (m in rx.findAll(text)) {
                    val w = m.value.lowercase()
                    if (toBase.length >= 4 && w.startsWith(toBase)) continue
                    if (w in good || goodStems.any { w.startsWith(it) }) continue
                    val near = w.zip(from.lowercase()).takeWhile { (a, b) -> a == b }.size
                    val old = picks[w]
                    if (old == null || near > old.near) picks[w] = Pick(e, m.value, near)
                }
            }
            return picks.values.take(MAX_LOOSE).map {
                DictEntry(
                    from = it.form,
                    to = it.entry.to,
                    mode = DictMode.HINT,
                    note = "ослышка словаря «${it.entry.from.trim()}» в другой форме",
                    createdAt = 0L,
                )
            }
        }

        // Word boundaries via lookarounds - \b is unreliable for Cyrillic.
        // Endings tolerance is used for DETECTION only, never for HARD
        // replacement (suffix-swallowing "осу" -> "осуди" would be a disaster).
        fun boundaryRegex(from: String, withRussianEndings: Boolean): Regex? {
            val trimmed = from.trim()
            if (trimmed.isEmpty()) return null
            // Хвост окончаний — только для слов от трёх букв: у «та» он ловил
            // «так» и «там», и подсказка «та — это ТА» уезжала в промпт 753 раза
            // (16.09.2026), у «Ви» — «вид» и «вижу».
            val suffix =
                if (withRussianEndings && trimmed.length >= 3 && trimmed.last().isCyrillic()) "[а-яёА-ЯЁ]{0,3}"
                else ""
            return runCatching {
                Regex("(?iu)(?<![\\p{L}\\p{N}])" + Regex.escape(trimmed) + suffix + "(?![\\p{L}\\p{N}])")
            }.getOrNull()
        }

        fun preserveFirstCase(matched: String, replacement: String): String {
            if (replacement.isEmpty()) return replacement
            return if (matched.first().isUpperCase() && replacement.first().isLowerCase()) {
                replacement.replaceFirstChar { it.titlecase() }
            } else {
                replacement
            }
        }

        private fun Char.isCyrillic(): Boolean = Character.UnicodeBlock.of(this) == Character.UnicodeBlock.CYRILLIC
    }
}
