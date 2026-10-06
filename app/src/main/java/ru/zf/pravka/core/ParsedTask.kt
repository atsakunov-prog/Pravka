package ru.zf.pravka.core

// One task extracted from a "наговор" (Разноска). Lives from the moment Opus
// splits the dictation until Todoist (or the home Dela server) accepts it - and
// stays on disk in between, so a parsed set is never lost to a process death
// or a dead network.
data class ParsedTask(
    val id: Long,
    val content: String,
    val description: String = "",
    // Resolved against the live catalogue (Todoist or Dela). projectId empty =
    // the model named a project we could not match; the owner picks one in the editor.
    val projectId: String = "",
    val projectName: String = "",
    val labels: List<String> = emptyList(),
    // Todoist's own scale: 4 = P1 (top), 1 = P4 (default). Kept in the API's
    // numbering so nothing has to be flipped at send time.
    val priority: Int = P4,
    // ISO date (YYYY-MM-DD) for a one-off deadline.
    val due: String = "",
    // Recurrence in words ("каждый вторник") - goes as due_string with due_lang=ru.
    val repeat: String = "",
    // Local near-duplicate found among the owner's open tasks (title only).
    val duplicateOf: String = "",
    // Filled once Todoist created it (or the Dela queue took it): a retry must
    // never create it twice.
    val sentId: String = "",
    // The owner threw this one out in the editor - kept for the record, not sent.
    val dropped: Boolean = false,
    // ---- Дела на домашнем сервере (03.10.2026, docs/dela-server.md) ----
    // Мяч и человек вместо меток «жду», «обещал», «повестка» и имён команды:
    // «обещал Ивану» — это ball = mine и Иван, а не метка, которую выбрасывали.
    val ball: String = Dela.MINE,
    val personId: String = "",
    val personName: String = "",
    val estimateMin: Int = 0,
    // "paid" — оплата согласована, "potential" — развитие, "" — как у проекта.
    val money: String = "",
    val want: Boolean = false,
    // id дела и op_id операции — телефона, с первой секунды разбора: повтор
    // отправки (сеть, смерть процесса) уходит с теми же ключами, и сервер
    // отдаёт прежний ответ вместо дубля. Вместо sentId и X-Request-Id Todoist.
    val delaId: String = "",
    val opId: String = "",
    // ---- Время и напоминание (06.10.2026, общий промпт server/contract/prompts/raznoska.txt) ----
    // «ЧЧ:ММ» — время самого дела («созвон в 15:00»), только при сроке.
    val dueTime: String = "",
    // Напоминание в Telegram: момент ISO со смещением или место автопилота («дом»).
    val remindAt: String = "",
    val remindPlace: String = "",
) {
    val sent: Boolean get() = sentId.isNotBlank()

    /** P1..P4 as the owner sees them in Todoist. */
    val priorityLabel: String get() = "P" + (5 - priority.coerceIn(1, 4))

    companion object {
        const val P1 = 4
        const val P2 = 3
        const val P3 = 2
        const val P4 = 1

        /** "P1".."P4" (any case, "1".."4" too) -> Todoist's priority int. */
        fun priorityOf(text: String): Int {
            val digit = text.trim().removePrefix("P").removePrefix("p").removePrefix("Р")
                .trim().toIntOrNull() ?: return P4
            return when (digit) {
                1 -> P1
                2 -> P2
                3 -> P3
                else -> P4
            }
        }
    }
}

/**
 * «Не дело» из наговора для Дел на сервере: факт, статус, мысль вслух — с
 * проектом и людьми, если модель их узнала. Уходит в хронологию CRM заметкой
 * (`interaction.add`, kind = note); id и op_id — с разбора, как у дел.
 */
data class ParsedNote(
    val text: String,
    val projectId: String = "",
    val projectName: String = "",
    val personIds: List<String> = emptyList(),
    val id: String = "",
    val opId: String = "",
)
