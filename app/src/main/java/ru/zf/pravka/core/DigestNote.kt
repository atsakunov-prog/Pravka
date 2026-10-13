package ru.zf.pravka.core

/**
 * Последний дайджест Telegram для «Сегодня» (Правка 5.0, этап 2). Сводка
 * живёт у службы дайджеста на компе; дорогу к телефону через сервер делает
 * отдельная работа (`docs/redesign-5-plan.md`, §6) — до неё источника нет,
 * и карточка не показывается. [at] — когда собрана сводка, [title] — что за
 * окно («12 чатов · 286 сообщений»), [text] — сама сводка.
 *
 * Текст — строками: строка с «# » — заголовок раздела («Ждут ответа»),
 * остальные — абзацы; «Кто: что» — имя жирным, как `.dgp b` на холсте.
 */
data class DigestNote(
    val at: Long,
    val text: String,
    val title: String = "",
) {
    /** Кусок сводки: заголовок раздела или абзац с необязательным жирным началом. */
    data class Block(val heading: Boolean, val text: String, val lead: String = "")

    fun blocks(): List<Block> = text.lines().mapNotNull { raw ->
        val line = raw.trim().removePrefix("- ").removePrefix("• ").trim()
        when {
            line.isBlank() -> null
            line.startsWith("#") -> Block(true, line.trimStart('#').trim())
            else -> {
                // «Марианна: погасить Озон…» — имя до двоеточия жирным, если оно короткое.
                val cut = line.indexOf(": ")
                if (cut in 1..LEAD_MAX) Block(false, line.substring(cut + 2), line.substring(0, cut + 1))
                else Block(false, line)
            }
        }
    }

    companion object {
        /** Жирное начало — не длиннее этого: дальше двоеточие уже внутри фразы. */
        const val LEAD_MAX = 32
    }
}
