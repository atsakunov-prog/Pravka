package ru.zf.pravka.core

/**
 * Запас без модели захвата (шаг 0 петли, 15.10.2026; задание docs/dela-phone-25.md, п. 8). Сервер знает `capture`, а
 * фраза пошла прежней дорогой — Разноской телефона (правило сервера её не узнало, а модели захвата у сервера нет; или
 * текст пришёл кнопкой из чужого текста). Её дела всё равно ложатся в корзины захвата теми же двумя правилами, что у
 * сервера (`capture.from_model`, spec-0 §2.2):
 * - напоминание без своего срока (или срок — день напоминания без часа) — напоминание (`kind: remind`), не дело в куче:
 *   «напомни в 12» не утонет в «Запустить»;
 * - голое своё дело — без срока, напоминания, «Сейчас», не «отбить», не «жду», не повестка — «потом» с днём возврата
 *   (следующий рабочий день): само вернётся строкой «Вернулось», а не уйдёт на дно «Всех дел».
 * Остальное — как сказала Разноска. Только серверу с флагом `capture`: старый отверг бы незнакомые поля целиком.
 * [back] = false — дела с плашки чужого текста (встреча, дайджест: их бывает двадцать) «потом» не получают: иначе
 * завтра «Вернулось» утонуло бы в них; напоминание — напоминанием и там.
 */
object CaptureFallback {

    fun apply(t: Dela.Task, today: String, back: Boolean = true): Dela.Task {
        if (t.goal || t.remind) return t
        if (t.remindAt.isNotBlank()) {
            val day = DelaGroups.mskDay(t.remindAt)
            return if (t.dueDate.isBlank() || (t.dueDate == day && t.dueTime.isBlank())) {
                t.copy(kind = Dela.KIND_REMIND, dueDate = "", dueTime = "", focusOn = "", backOn = "", backSaid = "")
            } else t
        }
        if (t.remindPlace.isNotBlank()) {
            return if (t.dueDate.isBlank()) t.copy(kind = Dela.KIND_REMIND, focusOn = "", backOn = "", backSaid = "") else t
        }
        val bare = t.ball == Dela.MINE && !t.owed && t.dueDate.isBlank() && t.focusOn.isBlank() && t.backOn.isBlank()
        return if (bare && back) t.copy(backOn = Dela.nextWorkday(today)) else t
    }
}
