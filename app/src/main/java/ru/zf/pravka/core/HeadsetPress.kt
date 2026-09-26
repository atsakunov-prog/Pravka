package ru.zf.pravka.core

/**
 * Что делает кнопка гарнитуры — решение без Android под рукой.
 *
 * Гарнитура просит «голосового помощника» (у Shokz OpenComm2 2025 — Mute две
 * секунды вне звонка), Bluetooth-стек телефона открывает на это Правку
 * (`trigger/HeadsetButtonActivity.kt`). Дальше — правило владельца
 * (25.09.2026): «если с полем, то правка в поле; если без поля или на
 * заблоченном экране, то засечка».
 *
 * Замок проверяется РАНЬШЕ поля: на экране блокировки поле ввода с фокусом —
 * это поле ПИН-кода, и диктовать в него нельзя ни при каком раскладе.
 */
object HeadsetPress {

    enum class Action(val word: String) {
        /** Тейк уже идёт (любой кнопки) — нажатие его заканчивает, как второй тап. */
        STOP("стоп тейка"),

        /** Диктовка «П» в поле под курсором. */
        PRAVKA("Правка в поле"),

        /** Тейк «З» в ленту. */
        ZASECHKA("Засечка"),
    }

    /**
     * @param takeRunning микрофон уже занят тейком
     * @param locked экран заблокирован
     * @param fieldFocused в фокусе поле ввода, видимое владельцу
     * @param zasechkaOn «З» стоит на стекле (режим в профиле и тумблер кнопки):
     *   без неё нечем писать в ленту, и безполевая диктовка уходит в Правку —
     *   её собственный путь без поля (буфер и уведомление)
     */
    fun choose(
        takeRunning: Boolean,
        locked: Boolean,
        fieldFocused: Boolean,
        zasechkaOn: Boolean,
    ): Action = when {
        takeRunning -> Action.STOP
        !zasechkaOn -> Action.PRAVKA
        locked -> Action.ZASECHKA
        fieldFocused -> Action.PRAVKA
        else -> Action.ZASECHKA
    }

    /**
     * Слушать ли телефон в этом тейке. «Чем нажал — тем и слушаем»
     * (`docs/agreements.md`, «Микрофон выбирает владелец»): тейк с кнопки
     * гарнитуры слушает гарнитуру, какой бы кружок микрофона ни стоял в
     * веере; касание телефона — как выбрал владелец.
     */
    fun phoneMic(ownerChosePhone: Boolean, fromHeadset: Boolean): Boolean =
        ownerChosePhone && !fromHeadset
}
