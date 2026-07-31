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

        return Prepared(text, buildDictBlock(hints, protects), fired)
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
                append("Не исправляй эти слова, они написаны верно:\n")
                append(protects.joinToString(", ") { it.from })
                append("\n")
            }
        }.trim()
    }

    companion object {
        // Word boundaries via lookarounds - \b is unreliable for Cyrillic.
        // Endings tolerance is used for DETECTION only, never for HARD
        // replacement (suffix-swallowing "осу" -> "осуди" would be a disaster).
        fun boundaryRegex(from: String, withRussianEndings: Boolean): Regex? {
            val trimmed = from.trim()
            if (trimmed.isEmpty()) return null
            val suffix = if (withRussianEndings && trimmed.last().isCyrillic()) "[а-яёА-ЯЁ]{0,3}" else ""
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
